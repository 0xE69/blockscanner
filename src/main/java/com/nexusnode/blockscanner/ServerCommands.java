/*
 * Crafting Dead
 * Copyright (C) 2022  NexusNode LTD
 *
 * This Non-Commercial Software License Agreement (the "Agreement") is made between
 * you (the "Licensee") and NEXUSNODE (BRAD HUNTER). (the "Licensor").
 * By installing or otherwise using Crafting Dead (the "Software"), you agree to be
 * bound by the terms and conditions of this Agreement as may be revised from time
 * to time at Licensor's sole discretion.
 *
 * If you do not agree to the terms and conditions of this Agreement do not download,
 * copy, reproduce or otherwise use any of the source code available online at any time.
 *
 * https://github.com/nexusnode/crafting-dead/blob/1.18.x/LICENSE.txt
 *
 * https://craftingdead.net/terms.php
 */
package com.nexusnode.blockscanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber
public class ServerCommands {
    
    // Flag to control ongoing replacement operations
    private static final AtomicBoolean replacementActive = new AtomicBoolean(false);
    private static final Set<ChunkPos> pendingChunks = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    
    // Track progress for reporting
    private static int totalChunksToProcess = 0;
    private static int totalBlocksReplaced = 0;
    private static final int PROGRESS_UPDATE_INTERVAL = 3000; // 3 seconds in milliseconds
    
    // Add these fields for tracking scanmap progress
    private static boolean scanMapActive = false;
    private static final List<BlockPos> scanPositions = new ArrayList<>();
    private static int currentScanPosition = 0;
    private static int scanRenderDistance = 256;

    // Add a new flag to properly track when chunk processing is finished
    private static boolean processingCompleted = false;
    
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LOGGER.info("Registering BlockScanner commands...");
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
    
        // Register /blockscanner command
        dispatcher.register(
            Commands.literal("blockscanner")
                // Change permission level to 0 so anyone can use basic commands 
                .requires(source -> source.hasPermission(0)) 
                .then(Commands.literal("scan")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                        .executes(context -> scanArea(context, IntegerArgumentType.getInteger(context, "radius"))))
                    .executes(context -> scanArea(context, 64))) // Default radius
                .then(Commands.literal("replace")
                    .then(Commands.argument("from", StringArgumentType.string())
                        .then(Commands.argument("to", StringArgumentType.string())
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .executes(context -> replaceBlocks(
                                    context, 
                                    StringArgumentType.getString(context, "from"),
                                    StringArgumentType.getString(context, "to"),
                                    IntegerArgumentType.getInteger(context, "radius"))))
                            .executes(context -> replaceBlocks(
                                context, 
                                StringArgumentType.getString(context, "from"),
                                StringArgumentType.getString(context, "to"),
                                64))))) // Default radius
                .then(Commands.literal("reload")
                    .executes(ServerCommands::reloadConfig))
                .then(Commands.literal("replacechunk")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> replaceChunk(
                            context, 
                            BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                .then(Commands.literal("activate")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                        .executes(context -> activateReplacements(
                            context, 
                            IntegerArgumentType.getInteger(context, "radius"))))
                    .executes(context -> activateReplacements(context, 128))) // Default radius
                .then(Commands.literal("deactivate")
                    .executes(ServerCommands::deactivateReplacements))
                .then(Commands.literal("status")
                    .executes(ServerCommands::checkStatus))
                .then(Commands.literal("processall")
                    .executes(ServerCommands::processAllLoadedChunks))
                // Add new command for toggling auto-scan around players
                .then(Commands.literal("autoscan")
                    .then(Commands.literal("on")
                        .executes(context -> {
                            BlockScanner.playerAutoScanEnabled = true;
                            context.getSource().sendSuccess(
                                new TextComponent("Auto-scanning around players enabled (32 block radius, checks every 2 seconds)"), 
                                true
                            );
                            System.out.println("[BlockScanner] Auto-scanning around players enabled");
                            return 1;
                        })
                    )
                    .then(Commands.literal("off")
                        .executes(context -> {
                            BlockScanner.playerAutoScanEnabled = false;
                            context.getSource().sendSuccess(
                                new TextComponent("Auto-scanning around players disabled"), 
                                true
                            );
                            System.out.println("[BlockScanner] Auto-scanning around players disabled");
                            return 1;
                        })
                    )
                    .then(Commands.literal("status")
                        .executes(context -> {
                            String status = BlockScanner.playerAutoScanEnabled ? "enabled" : "disabled";
                            context.getSource().sendSuccess(
                                new TextComponent("Auto-scanning around players is currently " + status), 
                                true
                            );
                            return 1;
                        })
                    )
                )
                .then(Commands.literal("addblock")
                    .then(Commands.argument("from", StringArgumentType.string())
                        .then(Commands.argument("to", StringArgumentType.string())
                            .executes(context -> addBlockReplacement(
                                context,
                                StringArgumentType.getString(context, "from"),
                                StringArgumentType.getString(context, "to"))))))
                // Add commands for working with scanned blocks
                .then(Commands.literal("listscanned")
                    .executes(ServerCommands::listScannedBlocks))
                .then(Commands.literal("generateconfig")
                    .executes(ServerCommands::generateReplacementConfig))
                // Add new scanmap command
                .then(Commands.literal("scanmap")
                    .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                            .then(Commands.argument("x2", IntegerArgumentType.integer())
                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                    .then(Commands.argument("renderDistance", IntegerArgumentType.integer(32, 512))
                                        .executes(context -> scanMap(
                                            context,
                                            IntegerArgumentType.getInteger(context, "x1"),
                                            IntegerArgumentType.getInteger(context, "z1"),
                                            IntegerArgumentType.getInteger(context, "x2"),
                                            IntegerArgumentType.getInteger(context, "z2"),
                                            IntegerArgumentType.getInteger(context, "renderDistance")
                                        )))
                                    .executes(context -> scanMap(
                                        context,
                                        IntegerArgumentType.getInteger(context, "x1"),
                                        IntegerArgumentType.getInteger(context, "z1"),
                                        IntegerArgumentType.getInteger(context, "x2"),
                                        IntegerArgumentType.getInteger(context, "z2"),
                                        256) // Default render distance
                                    ))))))
                .then(Commands.literal("stopmap")
                    .executes(ServerCommands::stopMapScan))
                // Add new replacemap command
                .then(Commands.literal("replacemap")
                    .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                            .then(Commands.argument("x2", IntegerArgumentType.integer())
                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                    .then(Commands.argument("renderDistance", IntegerArgumentType.integer(32, 512))
                                        .executes(context -> replaceMap(
                                            context,
                                            IntegerArgumentType.getInteger(context, "x1"),
                                            IntegerArgumentType.getInteger(context, "z1"),
                                            IntegerArgumentType.getInteger(context, "x2"),
                                            IntegerArgumentType.getInteger(context, "z2"),
                                            IntegerArgumentType.getInteger(context, "renderDistance")
                                        )))
                                    .executes(context -> replaceMap(
                                        context,
                                        IntegerArgumentType.getInteger(context, "x1"),
                                        IntegerArgumentType.getInteger(context, "z1"),
                                        IntegerArgumentType.getInteger(context, "x2"),
                                        IntegerArgumentType.getInteger(context, "z2"),
                                        256) // Default render distance
                                    ))))))
                // Add new registryscan command
                .then(Commands.literal("registryscan")
                    .requires(source -> source.hasPermission(2)) // Require higher permission for this command
                    .executes(ServerCommands::scanRegistries))
                .then(Commands.literal("listmods")
                    .executes(ServerCommands::listMods))
                .then(Commands.literal("listitems")
                    .then(Commands.argument("modid", StringArgumentType.string())
                        .executes(context -> listModItems(
                            context, 
                            StringArgumentType.getString(context, "modid"))))
                    .executes(ServerCommands::listAllItems))
                .then(Commands.literal("listblocks")
                    .then(Commands.argument("modid", StringArgumentType.string())
                        .executes(context -> listModBlocks(
                            context, 
                            StringArgumentType.getString(context, "modid"))))
                    .executes(ServerCommands::listAllBlocks))
        );
        
        // Also register with a shorter alias for convenience
        dispatcher.register(
            Commands.literal("bscan")
                .requires(source -> source.hasPermission(0))
                .redirect(dispatcher.getRoot().getChild("blockscanner"))
        );
        
        LOGGER.info("BlockScanner commands registered successfully!");
        System.out.println("[BlockScanner] Commands registered. Use /blockscanner or /bscan");
    }
    
    private static int scanArea(CommandContext<CommandSourceStack> context, int radius) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos playerPos = new BlockPos(source.getPosition());
        
        source.sendSuccess(new TextComponent("Scanning blocks in a " + radius + " block radius..."), true);
        
        // Count modded blocks
        int moddedBlocksCount = 0;
        List<String> moddedBlockTypes = new ArrayList<>();
        
        // Iterate through blocks in radius
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    
                    // Skip unloaded chunks
                    if (!level.isLoaded(pos)) continue;
                    
                    BlockState state = level.getBlockState(pos);
                    String registryName = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
                    
                    if (!registryName.startsWith("minecraft:")) {
                        moddedBlocksCount++;
                        if (!moddedBlockTypes.contains(registryName)) {
                            moddedBlockTypes.add(registryName);
                            
                            // Add to global scanned blocks tracker
                            ScannedBlocksTracker.addScannedBlock(registryName);
                        }
                    }
                }
            }
        }
        
        source.sendSuccess(new TextComponent("Found " + moddedBlocksCount + " modded blocks of " + 
                                            moddedBlockTypes.size() + " different types."), true);
        
        // List the found block types
        for (String blockType : moddedBlockTypes) {
            source.sendSuccess(new TextComponent("- " + blockType), false);
        }
        
        // After scanning, update message to show how to generate config
        if (!moddedBlockTypes.isEmpty()) {
            source.sendSuccess(new TextComponent("Run '/blockscanner generateconfig' to create a suggested replacement configuration"), true);
        }
        
        return moddedBlocksCount;
    }
    
    private static int replaceBlocks(
        CommandContext<CommandSourceStack> context, 
        String fromBlockId, 
        String toBlockId, 
        int radius
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos playerPos = new BlockPos(source.getPosition());
        
        // Validate block IDs
        ResourceLocation fromLocation = ResourceLocation.tryParse(fromBlockId);
        ResourceLocation toLocation = ResourceLocation.tryParse(toBlockId);
        
        if (fromLocation == null || toLocation == null) {
            source.sendFailure(new TextComponent("Invalid block ID format"));
            return 0;
        }
        
        Block fromBlock = ForgeRegistries.BLOCKS.getValue(fromLocation);
        Block toBlock = ForgeRegistries.BLOCKS.getValue(toLocation);
        
        if (fromBlock == null || toBlock == null) {
            source.sendFailure(new TextComponent("One or both blocks don't exist"));
            return 0;
        }
        
        source.sendSuccess(new TextComponent("Replacing " + fromBlockId + " with " + toBlockId + 
            " in a " + radius + " block radius..."), true);
        
        // Replace blocks
        int replacedCount = 0;
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    
                    // Skip unloaded chunks
                    if (!level.isLoaded(pos)) continue;
                    
                    BlockState currentState = level.getBlockState(pos);
                    String registryName = ForgeRegistries.BLOCKS.getKey(currentState.getBlock()).toString();
                    
                    if (registryName.equals(fromBlockId)) {
                        BlockState newState = toBlock.defaultBlockState();
                        level.setBlock(pos, newState, 3);
                        replacedCount++;
                    }
                }
            }
        }
        
        source.sendSuccess(new TextComponent("Replaced " + replacedCount + " blocks"), true);
        return replacedCount;
    }
    
    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        ConfigLoader.loadBlockReplacements();
        Map<String, String> replacements = ConfigLoader.blockReplacements;
        
        source.sendSuccess(new TextComponent("Reloaded block replacements configuration. " + 
            replacements.size() + " replacements loaded."), true);
        
        return replacements.size();
    }
    
    private static int activateReplacements(CommandContext<CommandSourceStack> context, int radius) 
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        if (replacementActive.get()) {
            source.sendFailure(new TextComponent("Block replacement is already active"));
            return 0;
        }
        
        replacementActive.set(true);
        ServerLevel level = source.getLevel();
        BlockPos centerPos = new BlockPos(source.getPosition());
        ChunkPos centerChunkPos = new ChunkPos(centerPos);
        
        source.sendSuccess(new TextComponent("Activating block replacements in a radius of " + radius + 
            " blocks around position " + centerPos), true);
        
        // Calculate chunk radius based on block radius (16 blocks = 1 chunk)
        int chunkRadius = (radius / 16) + 1;
        int chunksFound = 0;
        
        // Reset tracking counters
        totalBlocksReplaced = 0;
        pendingChunks.clear();
        
        // Queue all chunks in the area for processing
        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                ChunkPos pos = new ChunkPos(centerChunkPos.x + x, centerChunkPos.z + z);
                if (level.hasChunk(pos.x, pos.z)) {
                    pendingChunks.add(pos);
                    chunksFound++;
                }
            }
        }
        
        // Set total chunks for progress tracking
        totalChunksToProcess = chunksFound;
        
        source.sendSuccess(new TextComponent("Queued " + chunksFound + 
            " chunks for processing. Use '/blockscanner status' to check progress."), true);
        
        // Start a background task to process chunks
        startProcessingThread(level);
        
        return chunksFound;
    }
    
    private static int deactivateReplacements(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!replacementActive.get()) {
            source.sendFailure(new TextComponent("Block replacement is not currently active"));
            return 0;
        }
        
        // Set the flag to stop the process
        replacementActive.set(false);
        int remainingChunks = pendingChunks.size();
        int processedChunks = totalChunksToProcess - remainingChunks;
        pendingChunks.clear();
        
        source.sendSuccess(new TextComponent("Block replacement has been deactivated:"), true);
        source.sendSuccess(new TextComponent(String.format(
            "- Processed: %d/%d chunks (%.1f%%)", 
            processedChunks, totalChunksToProcess, 
            (float) processedChunks / totalChunksToProcess * 100)), true);
        source.sendSuccess(new TextComponent(String.format(
            "- Replaced: %d blocks", totalBlocksReplaced)), true);
        source.sendSuccess(new TextComponent(String.format(
            "- Remaining: %d chunks were not processed", remainingChunks)), true);
        
        return 1;
    }
    
    private static int checkStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (replacementActive.get()) {
            int remaining = pendingChunks.size();
            int processed = totalChunksToProcess - remaining;
            float percentComplete = totalChunksToProcess > 0 ? (float) processed / totalChunksToProcess * 100 : 0;
            
            // Create a text-based progress bar
            int barLength = 20;
            int filledLength = (int) (barLength * processed / totalChunksToProcess);
            StringBuilder progressBar = new StringBuilder("[");
            for (int i = 0; i < barLength; i++) {
                if (i < filledLength) {
                    progressBar.append("=");
                } else if (i == filledLength) {
                    progressBar.append(">");
                } else {
                    progressBar.append(" ");
                }
            }
            progressBar.append("]");
            
            source.sendSuccess(new TextComponent("Block replacement is active:"), true);
            source.sendSuccess(new TextComponent(String.format(
                "Progress: %s %.1f%%", progressBar, percentComplete)), true);
            source.sendSuccess(new TextComponent(String.format(
                "Chunks: %d/%d processed, %d remaining", 
                processed, totalChunksToProcess, remaining)), true);
            source.sendSuccess(new TextComponent(String.format(
                "Blocks replaced: %d", totalBlocksReplaced)), true);
        } else {
            source.sendSuccess(new TextComponent("Block replacement is not currently active."), true);
        }
        
        source.sendSuccess(new TextComponent(
            "Loaded " + ConfigLoader.blockReplacements.size() + " block replacements from configuration."), true);
        
        return 1;
    }
    
    private static int processAllLoadedChunks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (replacementActive.get()) {
            source.sendFailure(new TextComponent("Block replacement is already active. " + 
                "Please deactivate it first with '/blockscanner deactivate'"));
            return 0;
        }
        
        ServerLevel level = source.getLevel();
        Set<ChunkPos> loadedChunks = new HashSet<>();
        
        // Get all loaded chunks in 1.18.2 compatible way
        for (int chunkX = level.getMinSection(); chunkX < level.getMaxSection(); chunkX++) {
            for (int chunkZ = level.getMinSection(); chunkZ < level.getMaxSection(); chunkZ++) {
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                if (level.hasChunk(pos.x, pos.z)) {
                    loadedChunks.add(pos);
                }
            }
        }
        
        if (loadedChunks.isEmpty()) {
            source.sendFailure(new TextComponent("No loaded chunks found"));
            return 0;
        }
        
        // Reset tracking counters
        totalBlocksReplaced = 0;
        pendingChunks.clear();
        
        // Add all loaded chunks to the pending list
        pendingChunks.addAll(loadedChunks);
        
        // Set total chunks for progress tracking
        totalChunksToProcess = loadedChunks.size();
        
        replacementActive.set(true);
        
        source.sendSuccess(new TextComponent("Processing all " + loadedChunks.size() + 
            " loaded chunks. This may take a while."), true);
        
        // Start a background task to process chunks
        startProcessingThread(level);
        
        return loadedChunks.size();
    }
    
    // Start a background processing thread - KEEP ONLY THIS VERSION
    private static void startProcessingThread(ServerLevel level) {
        // Reset the processing completed flag before starting
        processingCompleted = false;
        
        new Thread(() -> {
            try {
                // Use processedChunks to track progress
                int processedChunks = 0;
                long lastProgressUpdate = System.currentTimeMillis();
                int lastTotalBlocksReplaced = 0;
                
                // Use a thread pool that scales based on available processors
                final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
                ExecutorService executor = Executors.newFixedThreadPool(THREADS, 
                        r -> {
                            Thread t = new Thread(r, "BlockScanner-ChunkWorker");
                            t.setDaemon(true);
                            return t;
                        });
                
                // Track the last progress time to detect stalls
                long lastProgressTime = System.currentTimeMillis();
                int lastProcessedChunks = 0;
                
                // Quick exit if nothing to process
                if (pendingChunks.isEmpty()) {
                    LOGGER.info("[BlockScanner] No chunks to process, exiting immediately");
                    processingCompleted = true;
                    replacementActive.set(false);
                    return;
                }
                
                // Create a list of replacement tasks to process in parallel
                while (replacementActive.get() && !pendingChunks.isEmpty()) {
                    // Take chunks in batches to improve performance - process more chunks at once
                    Set<ChunkPos> batchChunks = new HashSet<>();
                    pendingChunks.stream().limit(10).forEach(chunk -> { // Increased from 5 to 10
                        batchChunks.add(chunk);
                        pendingChunks.remove(chunk);
                    });
                    
                    if (batchChunks.isEmpty()) break;
                    
                    // Process chunks in parallel
                    List<Future<Integer>> futures = new ArrayList<>();
                    for (ChunkPos chunkPos : batchChunks) {
                        if (!replacementActive.get()) break;
                        
                        // Submit async chunk processing task to thread pool
                        futures.add(executor.submit(() -> processChunkWithReplacementsAsync(level, chunkPos)));
                    }
                    
                    // Collect results
                    for (Future<Integer> future : futures) {
                        try {
                            int replacements = future.get();
                            totalBlocksReplaced += replacements;
                            processedChunks++; // Increment the counter we've declared
                        } catch (InterruptedException | ExecutionException e) {
                            LOGGER.error("Error processing chunk: " + e.getMessage());
                        }
                    }
                    
                    // Update progress periodically
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL) {
                        // Calculate blocks replaced since last update
                        int newBlocksReplaced = totalBlocksReplaced - lastTotalBlocksReplaced;
                        lastTotalBlocksReplaced = totalBlocksReplaced;
                        
                        // Create progress bar with processing speed info
                        int barLength = 30;
                        int filledLength = (int) (barLength * (totalChunksToProcess - pendingChunks.size()) / totalChunksToProcess);
                        StringBuilder progressBar = new StringBuilder("[");
                        for (int i = 0; i < barLength; i++) {
                            if (i < filledLength) {
                                progressBar.append("=");
                            } else if (i == filledLength) {
                                progressBar.append(">");
                            } else {
                                progressBar.append(" ");
                            }
                        }
                        progressBar.append("]");
                        
                        long timeElapsed = currentTime - lastProgressUpdate;
                        double chunksPerSecond = (double) batchChunks.size() / (timeElapsed / 1000.0);
                        
                        String progressMessage = String.format(
                            "[BlockScanner] Progress: %s %.1f%% | %d/%d chunks (%.1f chunks/s) | %d blocks replaced total | %d new blocks replaced",
                            progressBar, (float) (totalChunksToProcess - pendingChunks.size()) / totalChunksToProcess * 100, 
                            totalChunksToProcess - pendingChunks.size(), totalChunksToProcess, 
                            chunksPerSecond, totalBlocksReplaced, newBlocksReplaced
                        );
                        
                        System.out.println(progressMessage);
                        LOGGER.info(progressMessage);
                        
                        // Update the timestamp for next progress update
                        lastProgressUpdate = currentTime;
                        
                        // Check if we're making progress
                        if (processedChunks > lastProcessedChunks) {
                            lastProgressTime = currentTime;
                            lastProcessedChunks = processedChunks;
                        } else if (currentTime - lastProgressTime > 60000) { // No progress for 60 seconds
                            LOGGER.warn("[BlockScanner] No progress in chunk processing for 60 seconds, aborting");
                            break; // Exit the processing loop
                        }
                    }
                    
                    // Use a shorter sleep to improve throughput but still prevent excessive CPU usage
                    try {
                        Thread.sleep(200); // 200ms pause between batches (reduced from 500ms)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // Shutdown executor
                executor.shutdown();
                try {
                    // Wait for remaining tasks to complete
                    executor.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    LOGGER.error("Executor shutdown interrupted", e);
                }
                
                // Final progress update
                int completedChunks = totalChunksToProcess - pendingChunks.size();
                String completionMessage = String.format(
                    "[BlockScanner] Processing complete: %d/%d chunks processed (%d blocks replaced)",
                    completedChunks, totalChunksToProcess, totalBlocksReplaced
                );
                
                System.out.println(completionMessage);
                LOGGER.info(completionMessage);
                
                // Set the flag to indicate processing is complete
                processingCompleted = true;
                
            } catch (Exception e) {
                System.err.println("[BlockScanner] Error in background processing: " + e.getMessage());
                e.printStackTrace();
            } finally {
                replacementActive.set(false);
                pendingChunks.clear();
                processingCompleted = true;  // Make sure this is set in the finally block as well
            }
        }, "BlockScanner-ChunkProcessor").start();
    }
    
    private static int replaceChunk(CommandContext<CommandSourceStack> context, BlockPos blockPos) 
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        
        ChunkPos chunkPos = new ChunkPos(blockPos);
        source.sendSuccess(new TextComponent("Processing chunk at " + chunkPos), true);
        
        Set<String> replacedBlocks = new HashSet<>();
        int replacementCount = processChunkWithReplacements(level, chunkPos, replacedBlocks);
        
        source.sendSuccess(new TextComponent("Replaced " + replacementCount + " blocks in chunk " + chunkPos), true);
        
        for (String replacement : replacedBlocks) {
            source.sendSuccess(new TextComponent("- " + replacement), false);
        }
        
        return replacementCount;
    }
    
    // Shared method for chunk processing with tracking
    private static int processChunkWithReplacements(ServerLevel level, ChunkPos chunkPos, Set<String> replacedBlocks) {
        int replacementCount = 0;
        Map<String, String> blockReplacements = ConfigLoader.blockReplacements;
        
        // Improve chunk processing with better block iteration
        try {
            LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
            
            // Process only loaded sections to skip empty areas
            for (LevelChunkSection section : chunk.getSections()) {
                if (section == null || section.hasOnlyAir()) continue;
                
                int yStart = section.bottomBlockY();
                int yEnd = yStart + 16;
                
                // Process each block in the section
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = yStart; y < yEnd; y++) {
                            BlockPos pos = new BlockPos(chunkPos.x * 16 + x, y, chunkPos.z * 16 + z);
                            
                            try {
                                BlockState state = level.getBlockState(pos);
                                String registryName = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
                                
                                // Skip vanilla blocks
                                if (registryName.startsWith("minecraft:")) continue;
                                
                                // Replace block if it exists in the replacements map
                                if (blockReplacements.containsKey(registryName)) {
                                    String replacementBlock = blockReplacements.get(registryName);
                                    ResourceLocation replacementBlockLocation = new ResourceLocation(replacementBlock);
                                    Block replacement = ForgeRegistries.BLOCKS.getValue(replacementBlockLocation);
                                    
                                    if (replacement != null) {
                                        try {
                                            // Use safer block replacement approach
                                            safelyReplaceBlock(level, pos, replacement.defaultBlockState());
                                            if (replacedBlocks != null) {
                                                replacedBlocks.add(registryName + " -> " + replacementBlock);
                                            }
                                            replacementCount++;
                                        } catch (Exception e) {
                                            LOGGER.warn("Error replacing block " + registryName + " at " + pos + ": " + e.getMessage(), e);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Only log at debug level to avoid spamming the console
                                LOGGER.debug("Error processing block at " + pos + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error processing chunk at " + chunkPos + ": " + e.getMessage(), e);
        }
        
        return replacementCount;
    }
    
    private static int addBlockReplacement(CommandContext<CommandSourceStack> context, String fromBlockId, String toBlockId) {
        CommandSourceStack source = context.getSource();
        
        // Validate block IDs
        ResourceLocation fromLocation = ResourceLocation.tryParse(fromBlockId);
        ResourceLocation toLocation = ResourceLocation.tryParse(toBlockId);
        
        if (fromLocation == null || toLocation == null) {
            source.sendFailure(new TextComponent("Invalid block ID format. Use modid:blockname"));
            return 0;
        }
        
        // Verify the blocks exist
        Block fromBlock = ForgeRegistries.BLOCKS.getValue(fromLocation);
        Block toBlock = ForgeRegistries.BLOCKS.getValue(toLocation);
        
        if (fromBlock == null || toBlock == null) {
            source.sendFailure(new TextComponent("One or both blocks don't exist in the game"));
            return 0;
        }
        
        // Add to config
        boolean success = ConfigLoader.addBlockReplacement(fromBlockId, toBlockId);
        
        if (success) {
            source.sendSuccess(new TextComponent("Added replacement: " + fromBlockId + " → " + toBlockId), true);
            source.sendSuccess(new TextComponent("Configuration saved successfully"), true);
            return 1;
        } else {
            source.sendFailure(new TextComponent("Failed to save configuration"));
            return 0;
        }
    }
    
    /**
     * List all scanned blocks in the console
     */
    private static int listScannedBlocks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        Set<String> scannedBlocks = ScannedBlocksTracker.getScannedBlocks();
        
        if (scannedBlocks.isEmpty()) {
            source.sendSuccess(new TextComponent("No modded blocks have been scanned yet"), true);
            return 0;
        }
        
        // Group blocks by mod ID for better organization
        Map<String, List<String>> blocksByMod = new java.util.HashMap<>();
        
        for (String blockId : scannedBlocks) {
            String[] parts = blockId.split(":", 2);
            if (parts.length == 2) {
                String modId = parts[0];
                blocksByMod.computeIfAbsent(modId, k -> new ArrayList<>()).add(blockId);
            }
        }
        
        source.sendSuccess(new TextComponent("Found " + scannedBlocks.size() + " scanned block types:"), true);
        
        // List blocks by mod
        for (Map.Entry<String, List<String>> entry : blocksByMod.entrySet()) {
            source.sendSuccess(new TextComponent("Mod: " + entry.getKey() + " (" + entry.getValue().size() + " blocks)"), true);
            
            // Limit displayed blocks to 10 per mod to avoid spam
            int count = 0;
            for (String blockId : entry.getValue()) {
                if (count++ < 10) {
                    source.sendSuccess(new TextComponent("  - " + blockId), false);
                } else if (count == 11) {
                    source.sendSuccess(new TextComponent("  - ... and " + (entry.getValue().size() - 10) + " more"), false);
                    break;
                }
            }
        }
        
        return scannedBlocks.size();
    }
    
    /**
     * Generate a suggested replacement configuration file
     */
    private static int generateReplacementConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (ScannedBlocksTracker.generateReplacementConfig()) {
            source.sendSuccess(new TextComponent("Generated suggested replacement configuration based on scanned blocks"), true);
            source.sendSuccess(new TextComponent("File saved to: config/blockscanner/suggested_replacements.json"), true);
            source.sendSuccess(new TextComponent("Edit this file and rename it to block_replacements.json to use it"), true);
            return 1;
        } else {
            source.sendFailure(new TextComponent("Failed to generate replacement configuration"));
            return 0;
        }
    }
    
    // New method for safer block replacement that preserves properties
    private static void safelyReplaceBlock(ServerLevel level, BlockPos pos, BlockState newState) {
        try {
            BlockState oldState = level.getBlockState(pos);
            BlockState stateToUse = preserveBlockProperties(oldState, newState);
            
            // Try to copy NBT data if applicable
            copyBlockEntityData(level, pos, oldState.getBlock(), stateToUse.getBlock());
            
            // First try with standard replacement (flag 3 = update + notify)
            level.setBlock(pos, stateToUse, 3);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("updating neighbours")) {
                LOGGER.info("Trying alternative block replacement method for " + pos + " due to neighbor update issue");
                try {
                    // Try with flag 2 (only notify, no updates to neighbors)
                    BlockState oldState = level.getBlockState(pos);
                    BlockState stateToUse = preserveBlockProperties(oldState, newState);
                    level.setBlock(pos, stateToUse, 2);
                } catch (Exception e2) {
                    // If that also fails, try with flag 0 (no updates at all)
                    try {
                        BlockState oldState = level.getBlockState(pos);
                        BlockState stateToUse = preserveBlockProperties(oldState, newState);
                        level.setBlock(pos, stateToUse, 0);
                    } catch (Exception e3) {
                        throw new RuntimeException("All block replacement methods failed for " + pos, e3);
                    }
                }
            } else {
                // It's a different exception, rethrow it
                throw e;
            }
        }
    }
    
    /**
     * Attempts to copy properties from the old block state to the new one
     * Preserves rotation, direction, and other common properties
     */
    private static BlockState preserveBlockProperties(BlockState oldState, BlockState newState) {
        BlockState result = newState;
        
        // Fix the Collection type mismatch
        java.util.Collection<net.minecraft.world.level.block.state.properties.Property<?>> oldProps = oldState.getProperties();
        java.util.Collection<net.minecraft.world.level.block.state.properties.Property<?>> newProps = newState.getProperties();
        
        // Find properties that exist in both blocks
        for (net.minecraft.world.level.block.state.properties.Property<?> oldProp : oldProps) {
            String propName = oldProp.getName();
            
            // Look for matching property in new block
            for (net.minecraft.world.level.block.state.properties.Property<?> newProp : newProps) {
                if (newProp.getName().equals(propName) && oldProp.getValueClass() == newProp.getValueClass()) {
                    // Found matching property, try to copy the value
                    result = copyProperty(oldState, result, oldProp, newProp);
                }
            }
        }
        
        return result;
    }
    
    /**
     * Copy a property value from old state to new state using generic types
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Comparable<T>> BlockState copyProperty(
            BlockState oldState, BlockState newState,
            net.minecraft.world.level.block.state.properties.Property oldProp,
            net.minecraft.world.level.block.state.properties.Property newProp) {
        try {
            T value = (T)oldState.getValue(oldProp);
            // Check if the new property accepts this value
            if (newProp.getPossibleValues().contains(value)) {
                return newState.setValue((net.minecraft.world.level.block.state.properties.Property<T>)newProp, value);
            }
        } catch (Exception e) {
            LOGGER.debug("Unable to copy property {} from {} to {}: {}", 
                oldProp.getName(), oldState.getBlock().getRegistryName(), 
                newState.getBlock().getRegistryName(), e.getMessage());
        }
        return newState;
    }
    
    /**
     * Copy BlockEntity NBT data when replacing blocks
     */
    private static void copyBlockEntityData(ServerLevel level, BlockPos pos, Block oldBlock, Block newBlock) {
        // Fix the isEntityBlock method issue
        boolean oldHasBlockEntity = false;
        boolean newHasBlockEntity = false;
        
        try {
            // Check if blocks might have block entities
            oldHasBlockEntity = level.getBlockEntity(pos) != null;
            newHasBlockEntity = newBlock instanceof net.minecraft.world.level.block.EntityBlock;
        } catch (Exception e) {
            LOGGER.debug("Error checking block entity capability: {}", e.getMessage());
            return;
        }
        
        // Only proceed if both blocks can have block entities
        if (!oldHasBlockEntity || !newHasBlockEntity) {
            return;
        }
        
        try {
            // Get the old BlockEntity
            net.minecraft.world.level.block.entity.BlockEntity oldBE = level.getBlockEntity(pos);
            if (oldBE == null) {
                return;
            }
            
            // Save the old BlockEntity's data
            net.minecraft.nbt.CompoundTag oldData = oldBE.saveWithoutMetadata();
            if (oldData.isEmpty()) {
                return;
            }
            
            // Store the data to apply after block replacement
            level.getServer().tell(new net.minecraft.server.TickTask(0, () -> {
                try {
                    // Get the new BlockEntity after replacement
                    net.minecraft.world.level.block.entity.BlockEntity newBE = level.getBlockEntity(pos);
                    if (newBE != null) {
                        // Create a copy to avoid modifying the original
                        net.minecraft.nbt.CompoundTag dataCopy = oldData.copy();
                        
                        // Keep original block ID to avoid confusion
                        dataCopy.remove("id");
                        
                        // Load data into new BlockEntity
                        try {
                            newBE.load(dataCopy);
                            // Mark the BlockEntity as dirty to ensure it's saved
                            newBE.setChanged();
                            
                            // Send update to clients
                            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
                        } catch (Exception e) {
                            LOGGER.debug("Failed to load NBT data into new block entity: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Error copying block entity data: {}", e.getMessage());
                }
            }));
        } catch (Exception e) {
            LOGGER.debug("Exception while attempting to copy BlockEntity data: {}", e.getMessage());
        }
    }
    
    /**
     * Systematically scan a rectangular area of the map by teleporting the player to various points
     * and activating the block scanner at each point.
     */
    private static int scanMap(CommandContext<CommandSourceStack> context, int x1, int z1, int x2, int z2, int renderDistance) 
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException(); // Will throw if not a player
        ServerLevel level = source.getLevel();
        
        if (scanMapActive) {
            source.sendFailure(new TextComponent("A map scan is already in progress. Use '/blockscanner stopmap' to cancel."));
            return 0;
        }
        
        // Calculate grid points for teleportation
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        
        // Calculate how many teleport points we need based on the render distance
        // Use 75% of render distance to ensure overlap between scanned areas
        int gridStep = (int) (renderDistance * 0.75);
        scanRenderDistance = renderDistance;
        
        // Clear previous scan positions and prepare new list
        scanPositions.clear();
        currentScanPosition = 0;
        
        // Generate grid of scan positions
        for (int x = minX; x <= maxX; x += gridStep) {
            for (int z = minZ; z <= maxZ; z += gridStep) {
                scanPositions.add(new BlockPos(x, 200, z));
            }
        }
        
        // Add the corners to ensure complete coverage
        scanPositions.add(new BlockPos(maxX, 200, minZ));
        scanPositions.add(new BlockPos(minX, 200, maxZ));
        scanPositions.add(new BlockPos(maxX, 200, maxZ));
        
        // Remove duplicates
        Set<BlockPos> uniquePositions = new HashSet<>(scanPositions);
        scanPositions.clear();
        scanPositions.addAll(uniquePositions);
        
        source.sendSuccess(new TextComponent(String.format(
            "Starting map scan from (%d, %d) to (%d, %d) with render distance %d",
            minX, minZ, maxX, maxZ, renderDistance)), true);
        
        source.sendSuccess(new TextComponent(String.format(
            "Will teleport to %d positions and activate scanner at each one",
            scanPositions.size())), true);
        
        // Start the scan process
        scanMapActive = true;
        teleportAndScan(player, level);
        
        return scanPositions.size();
    }
    
    /**
     * Teleport to the next position and activate the scanner.
     * This is called recursively after each position is processed.
     */
    private static void teleportAndScan(ServerPlayer player, ServerLevel level) {
        if (!scanMapActive || currentScanPosition >= scanPositions.size()) {
            if (scanMapActive) {
                player.sendMessage(new TextComponent("Map scan complete!")
                    .withStyle(ChatFormatting.GREEN), UUID.randomUUID());
                LOGGER.info("Map scan complete, processed {} positions", scanPositions.size());
                scanMapActive = false;
            }
            return;
        }
        
        BlockPos pos = scanPositions.get(currentScanPosition);
        
        // Teleport player to the position
        player.teleportTo(level, pos.getX(), pos.getY(), pos.getZ(), player.getYRot(), player.getXRot());
        
        player.sendMessage(new TextComponent(String.format(
            "Teleported to position %d/%d: (%d, %d, %d)", 
            currentScanPosition + 1, scanPositions.size(), pos.getX(), pos.getY(), pos.getZ()))
            .withStyle(ChatFormatting.GOLD), UUID.randomUUID());
        
        // Check server availability and schedule scan with proper null checks
        final net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null) {
            LOGGER.error("Server is null, cannot continue map scan");
            scanMapActive = false;
            player.sendMessage(new TextComponent("Error: Server instance is null, scan aborted")
                .withStyle(ChatFormatting.RED), UUID.randomUUID());
            return;
        }
        
        // Wait 5 seconds before activating the scanner to allow chunks to load
        server.tell(new net.minecraft.server.TickTask(100, () -> { // 5 seconds (100 ticks)
            if (!scanMapActive) return;
            
            // Activate the scanner
            try {
                // Only activate if player is still online and server is available
                if (!player.isAlive()) {
                    LOGGER.error("Player disconnected during map scan");
                    scanMapActive = false;
                    return;
                }
                
                // Execute the activate command
                String command = "blockscanner activate " + scanRenderDistance;
                net.minecraft.server.MinecraftServer playerServer = player.getServer();
                if (playerServer == null) {
                    LOGGER.error("Player server is null, cannot execute command");
                    scanMapActive = false;
                    return;
                }
                
                playerServer.getCommands().performCommand(
                    player.createCommandSourceStack(),
                    command
                );
                
                player.sendMessage(new TextComponent(String.format(
                    "Activated scanner at (%d, %d, %d) with render distance %d", 
                    pos.getX(), pos.getY(), pos.getZ(), scanRenderDistance))
                    .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
                
                // Schedule next teleport after 45 seconds (900 ticks)
                // Always check server availability first
                final net.minecraft.server.MinecraftServer currentServer = player.getServer();
                if (currentServer != null) {
                    currentServer.tell(new net.minecraft.server.TickTask(900, () -> {
                        // Move to next position
                        currentScanPosition++;
                        teleportAndScan(player, level);
                    }));
                } else {
                    LOGGER.error("Server is null when trying to schedule next position");
                    scanMapActive = false;
                }
                
            } catch (Exception e) {
                LOGGER.error("Error during map scan: {}", e.getMessage(), e);
                player.sendMessage(new TextComponent("Error during map scan: " + e.getMessage())
                    .withStyle(ChatFormatting.RED), UUID.randomUUID());
                
                // Try to continue anyway, but check if we can safely do so
                if (scanMapActive && player.isAlive()) {
                    currentScanPosition++;
                    teleportAndScan(player, level);
                } else {
                    scanMapActive = false;
                }
            }
        }));
    }
    
    /**
     * Stop an in-progress map scan
     */
    private static int stopMapScan(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!scanMapActive) {
            source.sendFailure(new TextComponent("No map scan is currently active"));
            return 0;
        }
        
        scanMapActive = false;
        source.sendSuccess(new TextComponent(String.format(
            "Map scan stopped after processing %d/%d positions",
            currentScanPosition, scanPositions.size())), true);
        
        return 1;
    }
    
    /**
     * Systematically replace blocks in a rectangular area of the map by teleporting the player to various points
     * and activating block replacement at each point.
     */
    private static int replaceMap(CommandContext<CommandSourceStack> context, int x1, int z1, int x2, int z2, int renderDistance) 
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException(); // Will throw if not a player
        ServerLevel level = source.getLevel();
        
        if (scanMapActive) {
            source.sendFailure(new TextComponent("A map scan is already in progress. Use '/blockscanner stopmap' to cancel."));
            return 0;
        }
        
        // Calculate grid points for teleportation
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        
        // Calculate how many teleport points we need based on the render distance
        // Use 75% of render distance to ensure overlap between scanned areas
        int gridStep = (int) (renderDistance * 0.75);
        scanRenderDistance = renderDistance;
        
        // Clear previous scan positions and prepare new list
        scanPositions.clear();
        currentScanPosition = 0;
        
        // Generate grid of scan positions
        for (int x = minX; x <= maxX; x += gridStep) {
            for (int z = minZ; z <= maxZ; z += gridStep) {
                scanPositions.add(new BlockPos(x, 200, z));
            }
        }
        
        // Add the corners to ensure complete coverage
        scanPositions.add(new BlockPos(maxX, 200, minZ));
        scanPositions.add(new BlockPos(minX, 200, maxZ));
        scanPositions.add(new BlockPos(maxX, 200, maxZ));
        
        // Remove duplicates
        Set<BlockPos> uniquePositions = new HashSet<>(scanPositions);
        scanPositions.clear();
        scanPositions.addAll(uniquePositions);
        
        source.sendSuccess(new TextComponent(String.format(
            "Starting map replacement from (%d, %d) to (%d, %d) with render distance %d",
            minX, minZ, maxX, maxZ, renderDistance)), true);
        
        source.sendSuccess(new TextComponent(String.format(
            "Will teleport to %d positions and activate replacements at each one",
            scanPositions.size())), true);
        
        // Ensure configs are loaded
        ConfigLoader.loadBlockReplacements();
        int replacementCount = ConfigLoader.blockReplacements.size();
        
        source.sendSuccess(new TextComponent(String.format(
            "Loaded %d block replacement rules", replacementCount)), true);
        
        if (replacementCount == 0) {
            source.sendFailure(new TextComponent("No block replacement rules found. Please add rules first using '/blockscanner addblock'"));
            return 0;
        }
        
        // Start the scan process
        scanMapActive = true;
        teleportAndReplace(player, level);
        
        return scanPositions.size();
    }
    
    /**
     * Teleport to the next position and activate the replacer.
     * This is called recursively after each position is processed.
     */
    private static void teleportAndReplace(ServerPlayer player, ServerLevel level) {
        if (!scanMapActive || currentScanPosition >= scanPositions.size()) {
            if (scanMapActive) {
                player.sendMessage(new TextComponent("Map replacement complete!")
                    .withStyle(ChatFormatting.GREEN), UUID.randomUUID());
                LOGGER.info("Map replacement complete, processed {} positions", scanPositions.size());
                scanMapActive = false;
            }
            return;
        }
        
        BlockPos pos = scanPositions.get(currentScanPosition);
        
        // Teleport player to the position
        player.teleportTo(level, pos.getX(), pos.getY(), pos.getZ(), player.getYRot(), player.getXRot());
        
        player.sendMessage(new TextComponent(String.format(
            "Teleported to position %d/%d: (%d, %d, %d)", 
            currentScanPosition + 1, scanPositions.size(), pos.getX(), pos.getY(), pos.getZ()))
            .withStyle(ChatFormatting.GOLD), UUID.randomUUID());
        
        // Check server availability and schedule scan with proper null checks
        final net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null) {
            LOGGER.error("Server is null, cannot continue map replacement");
            scanMapActive = false;
            player.sendMessage(new TextComponent("Error: Server instance is null, replacement aborted")
                .withStyle(ChatFormatting.RED), UUID.randomUUID());
            return;
        }
        
        // SIGNIFICANTLY increase initial delay to wait for chunks to render
        // Increase from 30 seconds to 60 seconds (1200 ticks) to ensure client has rendered chunks
        player.sendMessage(new TextComponent(String.format(
            "Waiting 60 seconds for chunks to fully load before starting chunk loading check..."))
            .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
            
        // Force a few chunks to load immediately around the player position
        ChunkPos centerChunk = new ChunkPos(pos);
        LOGGER.info("Forcing chunks to load around position {}", pos);
        
        // First, force immediate chunk loading in a small radius
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                ChunkPos loadPos = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                LOGGER.debug("Requesting immediate chunk load at {}", loadPos);
                level.getChunk(loadPos.x, loadPos.z);
            }
        }
        
        // Send periodic position updates to the client to help with chunk loading
        // Set up 5 intermediate chunk loading checks at 10 second intervals
        for (int i = 1; i <= 5; i++) {
            final int checkNumber = i;
            server.tell(new net.minecraft.server.TickTask(i * 200, () -> { // Schedule at 10, 20, 30, 40, 50 seconds
                if (!scanMapActive) return;
                
                int loadedCount = countLoadedChunks(level, centerChunk, 3);
                player.sendMessage(new TextComponent(String.format(
                    "Chunk loading progress check %d/5: %d chunks loaded in a 7x7 area", 
                    checkNumber, loadedCount))
                    .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
                    
                // Have the player "jump" slightly to encourage chunk loading
                if (checkNumber % 2 == 0) {
                    player.teleportTo(level, pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5, player.getYRot(), player.getXRot());
                }
            }));
        }
        
        // Then continue with the regular delayed checking after a full minute
        server.tell(new net.minecraft.server.TickTask(1200, () -> { // 60 seconds
            if (!scanMapActive) return;
            
            // After initial delay, start checking if chunks are actually loading
            startChunkLoadingCheck(player, level, pos, 0);
        }));
    }

    /**
     * Count the number of loaded chunks in a square area around the center chunk
     */
    private static int countLoadedChunks(ServerLevel level, ChunkPos centerChunk, int radius) {
        int loadedCount = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkPos checkPos = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                if (level.hasChunk(checkPos.x, checkPos.z)) {
                    loadedCount++;
                }
            }
        }
        return loadedCount;
    }

    // Add these fields to control logging
    private static long lastLogTime = 0;
    private static int logCounter = 0;
    private static String lastLogMessage = "";
    private static final long LOG_THROTTLE_MS = 1000; // Minimum 1 second between logs
    private static long lastWarningTime = 0;
    private static final long WARNING_THROTTLE_MS = 10000; // Minimum 10 seconds between warnings
    
    /**
     * Verifies chunk loading status before activating replacements
     * Will progressively check if chunks are loaded and only proceed when ready
     */
    private static void startChunkLoadingCheck(ServerPlayer player, ServerLevel level, BlockPos pos, int attemptCount) {
        if (!scanMapActive || !player.isAlive()) {
            scanMapActive = false;
            return;
        }
        
        final net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null) return;
        
        // Check if chunks around position are loaded
        // Check a larger area - 7x7 chunks instead of 5x5
        int loadedChunkCount = 0;
        int totalChunksToCheck = 49; // 7x7 chunk area centered on player
        
        ChunkPos centerChunk = new ChunkPos(pos);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                ChunkPos checkPos = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                
                // Actively try to load the chunk if it's not already loaded
                if (!level.hasChunk(checkPos.x, checkPos.z)) {
                    LOGGER.debug("Requesting chunk load at {}", checkPos);
                    // This just requests the chunk without blocking
                    level.getChunk(checkPos.x, checkPos.z);
                } else {
                    loadedChunkCount++;
                }
            }
        }
        
        // Log load progress
        float percentLoaded = (float)loadedChunkCount / totalChunksToCheck * 100;
        
        if (attemptCount % 2 == 0) { // Only send message every 2 attempts to avoid spam
            player.sendMessage(new TextComponent(String.format(
                "Chunk loading status: %.1f%% loaded (%d/%d chunks) - Attempt %d", 
                percentLoaded, loadedChunkCount, totalChunksToCheck, attemptCount + 1))
                .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
        }
        
        // Lower the threshold for considering it ready - we'll check more thoroughly later
        // Or if we've waited for 30 attempts (5 minutes at 10 seconds per attempt), proceed anyway
        boolean readyToScan = (loadedChunkCount >= totalChunksToCheck * 0.5) || (attemptCount >= 30);
        
        if (readyToScan) {
            player.sendMessage(new TextComponent(String.format(
                "Chunks loaded: %.1f%% (%d/%d). Proceeding with final preparation.", 
                percentLoaded, loadedChunkCount, totalChunksToCheck))
                .withStyle(percentLoaded > 50 ? ChatFormatting.GREEN : ChatFormatting.GOLD), 
                UUID.randomUUID());
            
            // Wait an additional 10 seconds for any remaining chunks to load
            server.tell(new net.minecraft.server.TickTask(200, () -> {
                // Reset processingCompleted flag before starting a new processing run
                processingCompleted = false;
                
                // Force a final chunk check to be sure we have chunks loaded
                int finalLoadedCount = countLoadedChunks(level, centerChunk, 3);
                float finalPercentLoaded = (float)finalLoadedCount / totalChunksToCheck * 100;
                
                // Execute the activate command with an adjusted radius based on loaded chunks
                // Dynamically reduce radius if few chunks loaded to prevent server overload
                int adjustedRadius = Math.min(scanRenderDistance / 2, 128);
                if (finalPercentLoaded < 70) {
                    adjustedRadius = Math.min(adjustedRadius, 64); // Further reduce if chunk loading is poor
                }
                
                try {
                    player.sendMessage(new TextComponent(String.format(
                        "Final chunk status: %.1f%% (%d/%d chunks). Starting replacement with radius %d.", 
                        finalPercentLoaded, finalLoadedCount, totalChunksToCheck, adjustedRadius))
                        .withStyle(ChatFormatting.GREEN), UUID.randomUUID());
                    
                    String command = "blockscanner activate " + adjustedRadius;
                    net.minecraft.server.MinecraftServer playerServer = player.getServer();
                    if (playerServer == null) {
                        LOGGER.error("Player server is null, cannot execute command");
                        scanMapActive = false;
                        return;
                    }
                    
                    playerServer.getCommands().performCommand(
                        player.createCommandSourceStack(),
                        command
                    );
                    
                    // Wait 10 seconds before first check to let processing start
                    server.tell(new net.minecraft.server.TickTask(200, () -> {
                        // Begin monitoring the processing
                        monitorProcessingProgress(player, level, 0, System.currentTimeMillis());
                    }));
                } catch (Exception e) {
                    LOGGER.error("Error activating replacements: {}", e.getMessage(), e);
                    // Move to next position if there's an error
                    server.tell(new net.minecraft.server.TickTask(60, () -> {
                        currentScanPosition++;
                        teleportAndReplace(player, level);
                    }));
                }
            }));
        } else {
            // Continue waiting for chunks to load
            // Longer delay between checks - 10 seconds (200 ticks) instead of 5
            server.tell(new net.minecraft.server.TickTask(200, () -> {
                startChunkLoadingCheck(player, level, pos, attemptCount + 1);
            }));
        }
    }
    
    /**
     * Throttled logging to prevent console spam
     * Only logs if the message changes or if the minimum time between logs has passed
     */
    private static void throttledLog(Logger logger, String message, ServerPlayer player, UUID messageId) {
        long now = System.currentTimeMillis();
        
        // Skip logging if the message is the same and not enough time has passed
        if (message.equals(lastLogMessage) && (now - lastLogTime < LOG_THROTTLE_MS)) {
            logCounter++;
            return;
        }
        
        // If we skipped messages, report the count
        if (logCounter > 0) {
            String skipMessage = "Skipped " + logCounter + " duplicate log messages";
            logger.debug(skipMessage);
            logCounter = 0;
        }
        
        // Log the new message
        logger.info(message);
        
        // Only send player messages for significant updates or every 15 seconds
        if (!message.equals(lastLogMessage) || (now - lastLogTime >= 15000)) {
            if (player != null && player.isAlive() && messageId != null) {
                player.sendMessage(new TextComponent(message)
                    .withStyle(ChatFormatting.YELLOW), messageId);
            }
        }
        
        // Update tracking variables
        lastLogMessage = message;
        lastLogTime = now;
    }
    
    /**
     * Throttled warning logging to prevent console spam for warnings
     */
    private static void throttledWarning(Logger logger, String message, ServerPlayer player, UUID messageId) {
        long now = System.currentTimeMillis();
        
        // Only log warnings at most once every WARNING_THROTTLE_MS milliseconds
        if (now - lastWarningTime < WARNING_THROTTLE_MS) {
            return;
        }
        
        // Log the warning message
        logger.warn(message);
        
        // Always send warning messages to the player
        if (player != null && player.isAlive() && messageId != null) {
            player.sendMessage(new TextComponent("[Warning] " + message)
                .withStyle(ChatFormatting.RED), messageId);
        }
        
        // Update last warning time
        lastWarningTime = now;
    }
    
    /**
     * This method continuously monitors the progress of chunk processing with improved stall detection
     */
    private static void monitorProcessingProgress(ServerPlayer player, ServerLevel level, 
                                                 final int checkCount, final long startTime) {
        if (!scanMapActive || player == null || !player.isAlive()) {
            scanMapActive = false;
            return;
        }
        
        final net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) {
            LOGGER.error("Server is null when monitoring processing progress");
            scanMapActive = false;
            return;
        }
        
        // Calculate elapsed time in seconds
        long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
        
        // If processing is not active anymore and the completed flag is set, we're done
        if (!replacementActive.get() && processingCompleted) {
            String completionMessage = String.format("Processing completed in %d seconds, moving to next position...", elapsedSeconds);
            throttledLog(LOGGER, completionMessage, player, UUID.randomUUID());
            
            // Add a significantly longer delay before teleporting to next position
            // This ensures the client has time to finish any pending operations
            player.sendMessage(new TextComponent(String.format(
                "Position complete. Waiting 20 seconds before moving to next position..."))
                .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
                
            server.tell(new net.minecraft.server.TickTask(400, () -> {
                currentScanPosition++;
                // Reset completion flags before moving to next position
                processingCompleted = false;
                // Log the transition between positions
                LOGGER.info("Moving from position {} to position {} of {}", 
                    currentScanPosition, currentScanPosition + 1, scanPositions.size());
                teleportAndReplace(player, level);
            }));
            return;
        }
        
        // Get current progress information
        int remaining = pendingChunks.size();
        int processed = totalChunksToProcess - remaining;
        float percentComplete = totalChunksToProcess > 0 ? (float) processed / totalChunksToProcess * 100 : 0;
        
        // IMPROVED STALL DETECTION:
        // Instead of using a fixed timeout, check for progress between calls
        if (checkCount > 2) {
            // Check if we've stalled with simple consecutive progress checks
            int stalledCount = 0;
            int lastProcessed = 0;
            
            if (checkCount > 3 && processed == lastProcessed) {
                stalledCount++;
                
                // Only force completion if we've been stalled for multiple checks
                // AND either we're past 30 seconds total OR we've made very little progress
                boolean forceDueToStall = stalledCount >= 3 && 
                                        (elapsedSeconds > 30 || processed < totalChunksToProcess * 0.05);
                
                if (forceDueToStall) {
                    String logMessage = String.format(
                        "Forcing completion of replacements at position %d/%d due to stalled processing: %d/%d chunks processed after %d seconds (no progress for %d checks)", 
                        currentScanPosition + 1, scanPositions.size(), processed, totalChunksToProcess, 
                        elapsedSeconds, stalledCount);
                    
                    throttledWarning(LOGGER, logMessage, player, UUID.randomUUID());
                    
                    // Force completion
                    if (replacementActive.get()) {
                        replacementActive.set(false);
                        pendingChunks.clear();
                        processingCompleted = true;
                    }
                    
                    // Move to next position with a longer delay to let server recover
                    player.sendMessage(new TextComponent(String.format(
                        "Processing stalled. Waiting 15 seconds before moving to next position..."))
                        .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
                        
                    server.tell(new net.minecraft.server.TickTask(300, () -> {
                        currentScanPosition++;
                        teleportAndReplace(player, level);
                    }));
                    return;
                }
            } else {
                lastProcessed = processed;
                stalledCount = 0;
            }
        }
        
        // If we've been checking for more than 3 minutes, force completion
        if (elapsedSeconds > 180) {  // 3 minutes timeout
            String logMessage = String.format(
                "Forcing completion of replacements at position %d/%d due to 3-minute timeout after %d checks", 
                currentScanPosition + 1, scanPositions.size(), checkCount);
            
            throttledWarning(LOGGER, logMessage, player, UUID.randomUUID());
            
            // Force completion
            if (replacementActive.get()) {
                replacementActive.set(false);
                pendingChunks.clear();
                processingCompleted = true;
            }
            
            // Move to next position with increased delay
            player.sendMessage(new TextComponent(String.format(
                "Processing timed out. Waiting 15 seconds before moving to next position..."))
                .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
                
            server.tell(new net.minecraft.server.TickTask(300, () -> {
                currentScanPosition++;
                teleportAndReplace(player, level);
            }));
            return;
        }
        
        // Only send status update every 15 seconds
        if (checkCount % 3 == 0) {
            String logMessage = String.format("Map replacement progress at position %d/%d: %s%% complete (%d/%d chunks), %d seconds elapsed", 
                currentScanPosition + 1, scanPositions.size(), 
                String.format("%.1f", percentComplete), processed, totalChunksToProcess, elapsedSeconds);
            
            throttledLog(LOGGER, logMessage, player, UUID.randomUUID());
            
            // Check for server lag by comparing current time with expected elapsed time
            if (elapsedSeconds > 60 && processed < totalChunksToProcess / 10) { // Less than 10% after 1 minute
                String warningMessage = String.format(
                    "Possible server lag detected at position %d/%d, progress too slow: %d/%d after %d seconds", 
                    currentScanPosition + 1, scanPositions.size(), processed, totalChunksToProcess, elapsedSeconds);
                
                throttledWarning(LOGGER, warningMessage, player, UUID.randomUUID());
                
                // Force completion
                if (replacementActive.get()) {
                    replacementActive.set(false);
                    pendingChunks.clear();
                    processingCompleted = true;
                }
                
                // Wait longer before moving to next position to let server recover
                player.sendMessage(new TextComponent(String.format(
                    "Server appears to be lagging. Waiting 20 seconds before moving to next position..."))
                    .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
                    
                server.tell(new net.minecraft.server.TickTask(400, () -> {
                    currentScanPosition++;
                    teleportAndReplace(player, level);
                }));
                return;
            }
        }
        
        // Keep the check interval at 15 seconds (300 ticks)
        if (server != null) {
            // This check is necessary since server could be null
            server.tell(new net.minecraft.server.TickTask(300, () -> {
                monitorProcessingProgress(player, level, checkCount + 1, startTime);
            }));
        }
    }
    
    /**
     * Process chunks with replacements asynchronously with improved performance
     */
    private static int processChunkWithReplacementsAsync(ServerLevel level, ChunkPos chunkPos) {
        int replacementCount = 0;
        Map<String, String> blockReplacements = ConfigLoader.blockReplacements;
        
        // Track blocks to be replaced
        Map<BlockPos, BlockState> blocksToReplace = new java.util.HashMap<>();
        
        try {
            LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
            
            // Process only loaded sections to skip empty areas
            for (LevelChunkSection section : chunk.getSections()) {
                if (section == null || section.hasOnlyAir()) continue;
                
                int yStart = section.bottomBlockY();
                int yEnd = yStart + 16;
                
                // Process each block in the section
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = yStart; y < yEnd; y++) {
                            BlockPos pos = new BlockPos(chunkPos.x * 16 + x, y, chunkPos.z * 16 + z);
                            
                            try {
                                BlockState state = level.getBlockState(pos);
                                String registryName = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
                                
                                // Skip vanilla blocks
                                if (registryName.startsWith("minecraft:")) continue;
                                
                                // Add to replace list if it exists in the replacements map
                                if (blockReplacements.containsKey(registryName)) {
                                    String replacementBlock = blockReplacements.get(registryName);
                                    ResourceLocation replacementBlockLocation = new ResourceLocation(replacementBlock);
                                    Block replacement = ForgeRegistries.BLOCKS.getValue(replacementBlockLocation);
                                    
                                    if (replacement != null) {
                                        // Store for batch processing
                                        blocksToReplace.put(pos, replacement.defaultBlockState());
                                    }
                                }
                            } catch (Exception e) {
                                // Only log at debug level to avoid spamming the console
                                LOGGER.debug("Error processing block at " + pos + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
            
            // Apply replacements on the main thread in batches
            if (!blocksToReplace.isEmpty()) {
                replacementCount = applyBlockReplacementsBatch(level, blocksToReplace);
            }
            
        } catch (Exception e) {
            LOGGER.error("Error processing chunk at " + chunkPos + ": " + e.getMessage(), e);
        }
        
        return replacementCount;
    }
    
    /**
     * Apply block replacements in a batch to improve performance
     */
    private static int applyBlockReplacementsBatch(ServerLevel level, Map<BlockPos, BlockState> blocksToReplace) {
        int replacementCount = 0;
        
        // Process in batches of 100 to avoid overwhelming the server
        List<BlockPos> positions = new ArrayList<>(blocksToReplace.keySet());
        int batchSize = 100;
        
        for (int i = 0; i < positions.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, positions.size());
            List<BlockPos> batch = positions.subList(i, endIndex);
            
            // Submit batch to server main thread
            net.minecraft.server.MinecraftServer server = level.getServer();
            if (server != null) {
                server.execute(() -> {
                    try {
                        for (int j = 0; j < batch.size(); j++) {
                            BlockPos pos = batch.get(j);
                            BlockState newState = blocksToReplace.get(pos);
                            
                            try {
                                safelyReplaceBlock(level, pos, newState);
                            } catch (Exception e) {
                                LOGGER.warn("Error replacing block at " + pos + ": " + e.getMessage(), e);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error in batch replacement: " + e.getMessage(), e);
                    }
                });
            }
            
            replacementCount += batch.size();
            
            // Small sleep to allow server to process
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        return replacementCount;
    }
    
    /**
     * Command to scan all modded registry entries and save to YAML
     */
    private static int scanRegistries(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        source.sendSuccess(new TextComponent("Starting scan of all modded registry entries...").withStyle(ChatFormatting.GOLD), true);
        
        // Perform scan in a separate thread to avoid blocking the server
        new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();
                boolean success = ModdedItemScanner.scanAndSaveRegistries();
                long duration = System.currentTimeMillis() - startTime;
                
                if (success) {
                    // Send success message after scan is complete
                    source.sendSuccess(
                        new TextComponent(String.format("Registry scan complete in %.2f seconds!", duration / 1000.0))
                            .withStyle(ChatFormatting.GREEN), 
                        true);
                    
                    // Send info on results
                    List<String> modIds = ModdedItemScanner.getAllModIds();
                    source.sendSuccess(
                        new TextComponent(String.format("Found %d modded items and blocks across %d mods", 
                                ModdedItemScanner.getAllModdedItems().size() + ModdedItemScanner.getAllModdedBlocks().size(),
                                modIds.size()))
                            .withStyle(ChatFormatting.GREEN), 
                        true);
                    
                    // Tell player about the output files
                    source.sendSuccess(
                        new TextComponent("Registry entries saved to config/blockscanner/ (modded_items.yml, modded_blocks.yml, and all_modded_registries.yml)")
                            .withStyle(ChatFormatting.AQUA), 
                        true);
                } else {
                    source.sendFailure(new TextComponent("Failed to complete registry scan. Check logs for details."));
                }
            } catch (Exception e) {
                LOGGER.error("Error during registry scan: " + e.getMessage(), e);
                source.sendFailure(new TextComponent("Error during registry scan: " + e.getMessage()));
            }
        }, "RegistryScan-Thread").start();
        
        return 1;
    }
    
    /**
     * Command to list all mods with registry entries
     */
    private static int listMods(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        List<String> modIds = ModdedItemScanner.getAllModIds();
        if (modIds.isEmpty()) {
            source.sendSuccess(
                new TextComponent("No modded registry entries found. Run '/blockscanner registryscan' first.")
                    .withStyle(ChatFormatting.YELLOW), 
                true);
            return 0;
        }
        
        source.sendSuccess(
            new TextComponent(String.format("Found %d mods with registry entries:", modIds.size()))
                .withStyle(ChatFormatting.GREEN), 
            true);
        
        // Show mods in pages to avoid message overflow
        int page = 1;
        int modsPerPage = 10;
        int totalPages = (modIds.size() + modsPerPage - 1) / modsPerPage;
        
        source.sendSuccess(new TextComponent(String.format("Page %d/%d:", page, totalPages)), true);
        
        for (int i = 0; i < Math.min(modsPerPage, modIds.size()); i++) {
            String modId = modIds.get(i);
            Map<String, List<String>> entries = ModdedItemScanner.getEntriesForMod(modId);
            
            source.sendSuccess(
                new TextComponent(String.format("- %s (%d items, %d blocks)", 
                        modId, entries.get("items").size(), entries.get("blocks").size()))
                    .withStyle(ChatFormatting.AQUA), 
                false);
        }
        
        if (totalPages > 1) {
            source.sendSuccess(
                new TextComponent("Use '/blockscanner listitems <modid>' or '/blockscanner listblocks <modid>' to see specific entries")
                    .withStyle(ChatFormatting.YELLOW), 
                true);
        }
        
        return modIds.size();
    }
    
    /**
     * Command to list items for a specific mod
     */
    private static int listModItems(CommandContext<CommandSourceStack> context, String modId) {
        CommandSourceStack source = context.getSource();
        
        Map<String, List<String>> entries = ModdedItemScanner.getEntriesForMod(modId);
        List<String> items = entries.get("items");
        
        if (items.isEmpty()) {
            source.sendSuccess(
                new TextComponent(String.format("No items found for mod '%s'", modId))
                    .withStyle(ChatFormatting.YELLOW), 
                true);
            return 0;
        }
        
        source.sendSuccess(
            new TextComponent(String.format("Found %d items for mod '%s':", items.size(), modId))
                .withStyle(ChatFormatting.GREEN), 
            true);
        
        // Show first 15 items to avoid message overflow
        int maxDisplay = Math.min(15, items.size());
        for (int i = 0; i < maxDisplay; i++) {
            source.sendSuccess(new TextComponent("- " + items.get(i)), false);
        }
        
        if (items.size() > maxDisplay) {
            source.sendSuccess(
                new TextComponent(String.format("...and %d more (check modded_items.yml for full list)", 
                        items.size() - maxDisplay))
                    .withStyle(ChatFormatting.YELLOW), 
                true);
        }
        
        return items.size();
    }
    
    /**
     * Command to list blocks for a specific mod
     */
    private static int listModBlocks(CommandContext<CommandSourceStack> context, String modId) {
        CommandSourceStack source = context.getSource();
        
        Map<String, List<String>> entries = ModdedItemScanner.getEntriesForMod(modId);
        List<String> blocks = entries.get("blocks");
        
        if (blocks.isEmpty()) {
            source.sendSuccess(
                new TextComponent(String.format("No blocks found for mod '%s'", modId))
                    .withStyle(ChatFormatting.YELLOW), 
                true);
            return 0;
        }
        
        source.sendSuccess(
            new TextComponent(String.format("Found %d blocks for mod '%s':", blocks.size(), modId))
                .withStyle(ChatFormatting.GREEN), 
            true);
        
        // Show first 15 blocks to avoid message overflow
        int maxDisplay = Math.min(15, blocks.size());
        for (int i = 0; i < maxDisplay; i++) {
            source.sendSuccess(new TextComponent("- " + blocks.get(i)), false);
        }
        
        if (blocks.size() > maxDisplay) {
            source.sendSuccess(
                new TextComponent(String.format("...and %d more (check modded_blocks.yml for full list)", 
                        blocks.size() - maxDisplay))
                    .withStyle(ChatFormatting.YELLOW), 
                true);
        }
        
        return blocks.size();
    }
    
    /**
     * Command to list all modded items
     */
    private static int listAllItems(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        List<String> items = ModdedItemScanner.getAllModdedItems();
        if (items.isEmpty()) {
            source.sendSuccess(
                new TextComponent("No modded items found. Run '/blockscanner registryscan' first.")
                    .withStyle(ChatFormatting.YELLOW), 
                true);
            return 0;
        }
        
        source.sendSuccess(
            new TextComponent(String.format("Found %d modded items in total", items.size()))
                .withStyle(ChatFormatting.GREEN), 
            true);
        
        source.sendSuccess(
            new TextComponent("Check modded_items.yml for the full list, or use '/blockscanner listitems <modid>' for specific mods")
                .withStyle(ChatFormatting.AQUA), 
            true);
        
        // Show first 10 items as an example
        int maxDisplay = Math.min(10, items.size());
        source.sendSuccess(new TextComponent("First " + maxDisplay + " items as examples:"), false);
        
        for (int i = 0; i < maxDisplay; i++) {
            source.sendSuccess(new TextComponent("- " + items.get(i)), false);
        }
        
        return items.size();
    }
    
    /**
     * Command to list all modded blocks
     */
    private static int listAllBlocks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        List<String> blocks = ModdedItemScanner.getAllModdedBlocks();
        if (blocks.isEmpty()) {
            source.sendSuccess(
                new TextComponent("No modded blocks found. Run '/blockscanner registryscan' first.")
                    .withStyle(ChatFormatting.YELLOW), 
                true);
            return 0;
        }
        
        source.sendSuccess(
            new TextComponent(String.format("Found %d modded blocks in total", blocks.size()))
                .withStyle(ChatFormatting.GREEN), 
            true);
        
        source.sendSuccess(
            new TextComponent("Check modded_blocks.yml for the full list, or use '/blockscanner listblocks <modid>' for specific mods")
                .withStyle(ChatFormatting.AQUA), 
            true);
        
        // Show first 10 blocks as an example
        int maxDisplay = Math.min(10, blocks.size());
        source.sendSuccess(new TextComponent("First " + maxDisplay + " blocks as examples:"), false);
        
        for (int i = 0; i < maxDisplay; i++) {
            source.sendSuccess(new TextComponent("- " + blocks.get(i)), false);
        }
        
        return blocks.size();
    }
}
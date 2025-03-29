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

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

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
        if (moddedBlockTypes.size() > 0) {
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
    
    // Start a background processing thread
    private static void startProcessingThread(ServerLevel level) {
        new Thread(() -> {
            try {
                // Use processedChunks to track progress
                int processedChunks = 0;
                long lastProgressUpdate = System.currentTimeMillis();
                int lastTotalBlocksReplaced = 0;
                
                // Use a larger thread pool for processing chunks in parallel
                final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
                ExecutorService executor = Executors.newFixedThreadPool(THREADS, 
                        r -> {
                            Thread t = new Thread(r, "BlockScanner-ChunkWorker");
                            t.setDaemon(true);
                            return t;
                        });
                
                while (replacementActive.get() && !pendingChunks.isEmpty()) {
                    // Take up to 20 chunks at a time for better batching
                    Set<ChunkPos> batchChunks = new HashSet<>();
                    pendingChunks.stream().limit(20).forEach(chunk -> {
                        batchChunks.add(chunk);
                        pendingChunks.remove(chunk);
                    });
                    
                    if (batchChunks.isEmpty()) break;
                    
                    // Process chunks in parallel
                    List<Future<Integer>> futures = new ArrayList<>();
                    for (ChunkPos chunkPos : batchChunks) {
                        if (!replacementActive.get()) break;
                        
                        // Submit chunk processing task to thread pool
                        futures.add(executor.submit(() -> processChunkWithReplacements(level, chunkPos)));
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
                    
                    // Update progress every PROGRESS_UPDATE_INTERVAL milliseconds
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL) {
                        // Use processedChunks in our log message
                        LOGGER.debug("Processed {} chunks so far with {} blocks replaced", 
                            processedChunks, totalBlocksReplaced);
                        
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
                
            } catch (Exception e) {
                System.err.println("[BlockScanner] Error in background processing: " + e.getMessage());
                e.printStackTrace();
            } finally {
                replacementActive.set(false);
                pendingChunks.clear();
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
                if (section == null || section.isEmpty()) continue;
                
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
    
    // Overloaded version without tracking
    private static int processChunkWithReplacements(ServerLevel level, ChunkPos chunkPos) {
        return processChunkWithReplacements(level, chunkPos, null);
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
    
    // New method for safer block replacement
    private static void safelyReplaceBlock(ServerLevel level, BlockPos pos, BlockState newState) {
        try {
            // First try with standard replacement (flag 3 = update + notify)
            level.setBlock(pos, newState, 3);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("updating neighbours")) {
                LOGGER.info("Trying alternative block replacement method for " + pos + " due to neighbor update issue");
                try {
                    // Try with flag 2 (only notify, no updates to neighbors)
                    level.setBlock(pos, newState, 2);
                } catch (Exception e2) {
                    // If that also fails, try with flag 0 (no updates at all)
                    try {
                        level.setBlock(pos, newState, 0);
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
}
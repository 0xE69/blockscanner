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
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
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

@Mod.EventBusSubscriber
public class ServerCommands {
    
    // Flag to control ongoing replacement operations
    private static final AtomicBoolean replacementActive = new AtomicBoolean(false);
    private static final Set<ChunkPos> pendingChunks = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // Register /blockscanner command
        dispatcher.register(
            Commands.literal("blockscanner")
                .requires(source -> source.hasPermission(2)) // Require op permission level 2
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
        );
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
        
        return moddedBlocksCount;
    }
    
    // ... other methods with TextComponent fixes ...
    
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
                for (int z = -radius; x <= radius; z++) {
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
        pendingChunks.clear();
        
        source.sendSuccess(new TextComponent("Block replacement has been deactivated. " + 
            remainingChunks + " chunks were not processed."), true);
        
        return 1;
    }
    
    private static int checkStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (replacementActive.get()) {
            int remaining = pendingChunks.size();
            source.sendSuccess(new TextComponent("Block replacement is currently active with " + 
                remaining + " chunks remaining to be processed."), true);
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
        Set<ChunkPos> loadedChunks = new java.util.HashSet<>();
        
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
        
        // Add all loaded chunks to the pending list
        pendingChunks.addAll(loadedChunks);
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
                int processedChunks = 0;
                int totalReplacements = 0;
                
                while (replacementActive.get() && !pendingChunks.isEmpty()) {
                    // Take up to 5 chunks at a time
                    Set<ChunkPos> batchChunks = new java.util.HashSet<>();
                    pendingChunks.stream().limit(5).forEach(chunk -> {
                        batchChunks.add(chunk);
                        pendingChunks.remove(chunk);
                    });
                    
                    if (batchChunks.isEmpty()) break;
                    
                    for (ChunkPos chunkPos : batchChunks) {
                        if (!replacementActive.get()) break;
                        
                        // Process this chunk
                        int replacements = processChunkWithReplacements(level, chunkPos);
                        totalReplacements += replacements;
                        processedChunks++;
                        
                        // Don't overload the server - sleep a bit between chunks
                        Thread.sleep(50);
                    }
                }
                
                // Send a completion message to server console
                System.out.println("[BlockScanner] Finished processing " + processedChunks + 
                    " chunks with " + totalReplacements + " block replacements");
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
        
        Set<String> replacedBlocks = new java.util.HashSet<>();
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
        
        // Process each block in the chunk
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
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
                        LOGGER.error("Error processing block at " + pos + ": " + e.getMessage());
                    }
                }
            }
        }
        
        return replacementCount;
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
    
    // Overloaded version without tracking
    private static int processChunkWithReplacements(ServerLevel level, ChunkPos chunkPos) {
        return processChunkWithReplacements(level, chunkPos, null);
    }
}

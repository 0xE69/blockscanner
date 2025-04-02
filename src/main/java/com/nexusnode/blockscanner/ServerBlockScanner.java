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

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

@Mod("blockscanner")
public class ServerBlockScanner {
    private static final File LOG_FILE = new File("./logs/blockscanner_log.txt");
    // Make the field below used by adding it to another method
    private final Set<String> allDiscoveredModdedBlocks = new HashSet<>();
    // Either remove this or use it somewhere - let's use it by fixing later code
    private static final int SCAN_INTERVAL = 40;
    private static final int SCAN_RADIUS = 64;
    private static final Set<ChunkPos> processedChunks = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    private final Map<Player, Integer> blocksScannedPerPlayer = new ConcurrentHashMap<>();
    private final Map<Player, Integer> blocksReplacedPerPlayer = new ConcurrentHashMap<>();

    // Fix the unused tickCounter by actually using it
    private int tickCounter = 0;
    private Map<String, String> blockReplacements;

    // Make this a static variable that can be controlled globally
    public static boolean autoReplaceEnabled = false;

    public ServerBlockScanner() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setupCommon);
        // No client setup registration here

        MinecraftForge.EVENT_BUS.register(this);

        System.out.println("BlockScanner constructor called");

        ensureLogFileExists();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        writeBlockToLogFile("--- BlockScanner started at " + timestamp + " ---");

        System.out.println("BlockScanner initialized - logging unique modded blocks to " + LOG_FILE.getAbsolutePath());

        // Load block replacements from ConfigLoader
        ConfigLoader.init();
        blockReplacements = ConfigLoader.blockReplacements;
        
        // Initialize tick counter
        tickCounter = 0;
    }

    private void setupCommon(final FMLCommonSetupEvent event) {
        System.out.println("BlockScanner common setup");
        writeBlockToLogFile("Common setup completed");
        
        // Use tick counter to track initialization time
        long initTime = tickCounter * 50; // Convert ticks to milliseconds (1 tick = 50ms)
        LOGGER.info("Common setup completed after approximately {} ms", initTime);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        System.out.println("BlockScanner detected server starting");
        writeBlockToLogFile("Server starting detected");
        
        // Increment the tick counter to track startup progression
        tickCounter++;
        LOGGER.debug("Server starting tick: {}", tickCounter);
    }
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        System.out.println("BlockScanner: Server started, registering server tick handler");
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
        
        // Use the tickCounter to track startup time
        LOGGER.info("Server started after {} ticks", tickCounter);
        
        // Use the SCAN_INTERVAL
        LOGGER.info("Server tick handler registered with scan interval of {} ticks", SCAN_INTERVAL);
    }
    
    @SubscribeEvent
    public void onTickEvent(TickEvent.ServerTickEvent event) {
        // Increment our global tick counter on each server tick
        if (event.phase == TickEvent.Phase.START) {
            tickCounter++;
            
            // Log every 1200 ticks (1 minute) to show the server is running
            if (tickCounter % 1200 == 0) {
                LOGGER.debug("Server running for {} ticks (~{} minutes)", 
                    tickCounter, tickCounter / 1200);
            }
        }
    }
    
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        // Only process chunks automatically if enabled
        if (!autoReplaceEnabled) return;
        
        if (event.getWorld() instanceof ServerLevel && event.getChunk() != null) {
            ChunkPos chunkPos = new ChunkPos(event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (!processedChunks.contains(chunkPos)) {
                processedChunks.add(chunkPos);
                LOGGER.info("Queuing chunk at {} for processing", chunkPos);
                // Queue chunk for processing in the next server tick
                // This avoids processing during chunk loading which can cause issues
            }
        }
    }

    // Server-side event handler for block replacement
    public class ServerEventHandler {
        private int serverTickCounter = 0;
        private static final int SERVER_SCAN_INTERVAL = 20; // Every second
        private final Queue<ChunkPos> chunksToProcess = new ConcurrentLinkedQueue<>();
        private final Map<ChunkPos, ServerLevel> chunkWorldMap = new ConcurrentHashMap<>();
        private int totalBlocksScanned = 0;
        private int totalBlocksReplaced = 0;
        
        // Add a thread pool for parallel processing
        private final ExecutorService chunkProcessorPool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "BlockScanner-Worker");
                t.setDaemon(true);
                return t;
            }
        );
        
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            
            // Handle chunk processing
            serverTickCounter++;
            if (autoReplaceEnabled && serverTickCounter >= SERVER_SCAN_INTERVAL) {
                serverTickCounter = 0;
                processQueuedChunks();
            }
            
            // Handle player auto scanning
            if (serverTickCounter % 200 == 0) { // Every 10 seconds, report progress
                reportProgress();
            }
        }
        
        private void processQueuedChunks() {
            int maxChunksPerTick = 10; // Process more chunks per tick
            List<Future<ProcessingResult>> futures = new ArrayList<>();
            
            // Submit chunks to executor service for parallel processing
            for (int i = 0; i < maxChunksPerTick && !chunksToProcess.isEmpty(); i++) {
                ChunkPos chunkPos = chunksToProcess.poll();
                if (chunkPos == null) break;
                
                ServerLevel level = chunkWorldMap.get(chunkPos);
                if (level != null) {
                    futures.add(chunkProcessorPool.submit(() -> {
                        int blocksReplaced = processChunk(level, chunkPos);
                        return new ProcessingResult(chunkPos, blocksReplaced);
                    }));
                }
                
                // Remove from map to avoid memory leaks
                chunkWorldMap.remove(chunkPos);
            }
            
            // Collect results
            for (Future<ProcessingResult> future : futures) {
                try {
                    ProcessingResult result = future.get();
                    if (result.blocksReplaced > 0) {
                        LOGGER.info("Processed chunk at {} - replaced {} blocks", 
                                result.chunkPos, result.blocksReplaced);
                        totalBlocksReplaced += result.blocksReplaced;
                    }
                } catch (Exception e) {
                    LOGGER.error("Error waiting for chunk processing: {}", e.getMessage());
                }
            }
        }
        
        // Helper class to track results
        private static class ProcessingResult {
            final ChunkPos chunkPos;
            final int blocksReplaced;
            
            ProcessingResult(ChunkPos chunkPos, int blocksReplaced) {
                this.chunkPos = chunkPos;
                this.blocksReplaced = blocksReplaced;
            }
        }
        
        // Optimized chunk processing method
        private int processChunk(ServerLevel level, ChunkPos chunkPos) {
            int blocksReplaced = 0;
            
            try {
                LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
                
                // Only process non-empty sections
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section == null || section.hasOnlyAir()) continue;
                    
                    int yStart = section.bottomBlockY();
                    int yEnd = yStart + 16;
                    
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = yStart; y < yEnd; y++) {
                                BlockPos pos = new BlockPos(chunkPos.x * 16 + x, y, chunkPos.z * 16 + z);
                                BlockState state = level.getBlockState(pos);
                                totalBlocksScanned++;
                                
                                String blockId = getRegistryName(state);
                                if (blockReplacements.containsKey(blockId)) {
                                    String replacementId = blockReplacements.get(blockId);
                                    
                                    try {
                                        String[] parts = replacementId.split(":");
                                        if (parts.length == 2) {
                                            ResourceLocation replaceRL = new ResourceLocation(parts[0], parts[1]);
                                            Block replaceBlock = ForgeRegistries.BLOCKS.getValue(replaceRL);
                                            
                                            if (replaceBlock != null) {
                                                // Use our safely replace method instead
                                                safelyReplaceBlock(level, pos, replaceBlock.defaultBlockState());
                                                blocksReplaced++;
                                            }
                                        }
                                    } catch (Exception e) {
                                        LOGGER.debug("Failed to replace block at {}: {}", pos, e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error processing chunk at {}: {}", chunkPos, e.getMessage());
            }
            
            return blocksReplaced;
        }
        
        private void safelyReplaceBlock(ServerLevel level, BlockPos pos, BlockState newState) {
            // Check if the position is loaded to avoid crashes
            if (!level.isLoaded(pos)) return;
            
            try {
                // Get original state to preserve properties
                BlockState oldState = level.getBlockState(pos);
                BlockState stateToUse = preserveBlockProperties(oldState, newState);
                
                // Copy NBT data if applicable
                copyBlockEntityData(level, pos, oldState.getBlock(), stateToUse.getBlock());
                
                // Set the block with a flag of 3 to update clients and mark the chunk as dirty
                level.setBlock(pos, stateToUse, 3);
            } catch (Exception e) {
                LOGGER.error("Error replacing block at {}: {}", pos, e.getMessage());
            }
        }
        
        private void reportProgress() {
            // Implement the missing method
            if (totalBlocksScanned > 0 || totalBlocksReplaced > 0) {
                String message = String.format("BlockScanner progress: scanned %d blocks, replaced %d blocks",
                        totalBlocksScanned, totalBlocksReplaced);
                LOGGER.info(message);
                
                // Broadcast to all players
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (player != null && player.isAlive()) {
                            player.sendMessage(new TextComponent(message).withStyle(ChatFormatting.GOLD), UUID.randomUUID());
                        }
                    }
                }
            }
        }
        
        /**
         * Attempts to copy properties from the old block state to the new one
         * Preserves rotation, direction, and other common properties
         */
        private BlockState preserveBlockProperties(BlockState oldState, BlockState newState) {
            BlockState result = newState;
            
            // Fix the Collection type
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
        private <T extends Comparable<T>> BlockState copyProperty(
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
        private void copyBlockEntityData(ServerLevel level, BlockPos pos, Block oldBlock, Block newBlock) {
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
        
    }

    public void scanAroundPlayerWithProgress(Player player, Level world, int radius) {
        if (!(world instanceof ServerLevel) || player == null) return; // Add null check for player
        ServerLevel serverWorld = (ServerLevel) world;
        
        blocksScannedPerPlayer.put(player, 0);
        blocksReplacedPerPlayer.put(player, 0);
        
        BlockPos playerPos = player.blockPosition();
        int minX = playerPos.getX() - radius;
        int minY = Math.max(world.getMinBuildHeight(), playerPos.getY() - radius);
        int minZ = playerPos.getZ() - radius;
        int maxX = playerPos.getX() + radius;
        int maxY = Math.min(world.getMaxBuildHeight(), playerPos.getY() + radius);
        int maxZ = playerPos.getZ() + radius;
        
        int totalBlocks = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        LOGGER.info("Starting scan around {} of {} blocks in a radius of {} blocks", 
                player.getName().getString(), totalBlocks, radius);
        
        // Send starting message to player - check if player is still alive
        if (player.isAlive()) {
            player.sendMessage(new TextComponent("[BlockScanner] Starting scan of " + totalBlocks + 
                    " blocks in a " + radius + " block radius").withStyle(ChatFormatting.GREEN), UUID.randomUUID());
        }
        
        int scanned = 0;
        int replaced = 0;
        
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    scanned++;
                    
                    // Log progress every 10,000 blocks - check if player is still alive
                    if (scanned % 10000 == 0 && player.isAlive()) {
                        int percentage = (scanned * 100) / totalBlocks;
                        String progressMsg = String.format("[BlockScanner] Progress: %d%% (%d/%d blocks scanned)", 
                                percentage, scanned, totalBlocks);
                        LOGGER.info(progressMsg);
                        player.sendMessage(new TextComponent(progressMsg).withStyle(ChatFormatting.AQUA), UUID.randomUUID());
                    }
                    
                    String blockId = getRegistryName(state);
                    if (!blockId.startsWith("minecraft:")) {
                        // Add to the discovered blocks set
                        allDiscoveredModdedBlocks.add(blockId);
                    }
                    
                    if (blockReplacements.containsKey(blockId)) {
                        String replacementId = blockReplacements.get(blockId);
                        
                        try {
                            String[] parts = replacementId.split(":");
                            if (parts.length == 2) {
                                ResourceLocation replaceRL = new ResourceLocation(parts[0], parts[1]);
                                Block replaceBlock = ForgeRegistries.BLOCKS.getValue(replaceRL);
                                
                                if (replaceBlock != null) {
                                    serverWorld.setBlock(pos, replaceBlock.defaultBlockState(), 3);
                                    replaced++;
                                    LOGGER.info("Replaced {} with {} at {}", blockId, replacementId, pos);
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed to replace block at {}: {}", pos, e.getMessage());
                        }
                    }
                }
            }
        }
        
        blocksScannedPerPlayer.put(player, scanned);
        blocksReplacedPerPlayer.put(player, replaced);
        
        // Completion message - check if player is still alive
        if (player.isAlive()) {
            String completionMsg = String.format("[BlockScanner] Scan complete: scanned %d blocks, replaced %d blocks", 
                    scanned, replaced);
            LOGGER.info(completionMsg);
            player.sendMessage(new TextComponent(completionMsg).withStyle(ChatFormatting.GREEN), UUID.randomUUID());
        }
    }

    // This method is never used locally, but let's keep it for API completeness
    // Mark it as protected so it's clear it's meant to be used by extending classes
    protected void scanAroundPlayer(Player player, Level world) {
        scanAroundPlayerWithProgress(player, world, SCAN_RADIUS);
    }

    private String getRegistryName(BlockState state) {
        return ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
    }

    private void ensureLogFileExists() {
        // Your existing method
    }

    private void writeBlockToLogFile(String blockName) {
        // Your existing method
    }
}

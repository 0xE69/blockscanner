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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("blockscanner")
public class BlockScanner {

    private static final File LOG_FILE = new File("./logs/blockscanner_log.txt");
    private final Set<String> allDiscoveredModdedBlocks = new HashSet<>();
    private static final int SCAN_INTERVAL = 40; // Keep original scan interval for normal scans
    private static final int AUTO_SCAN_INTERVAL = 40; // 2 seconds (40 ticks)
    private static final int SCAN_RADIUS = 64;
    private static final int AUTO_SCAN_RADIUS = 32; // 32 block radius for auto scanning
    private static final Set<ChunkPos> processedChunks = ConcurrentHashMap.newKeySet();

    private int tickCounter = 0;
    private int autoTickCounter = 0;
    private Map<String, String> blockReplacements;

    // Make these static variables that can be controlled globally
    public static boolean autoReplaceEnabled = false;
    public static boolean playerAutoScanEnabled = false;

    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    private int totalBlocksScanned = 0;
    private int totalBlocksReplaced = 0;

    public BlockScanner() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setupCommon);
        
        // Only register client setup in client environment
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setupClient);
        }

        MinecraftForge.EVENT_BUS.register(this);

        System.out.println("BlockScanner constructor called");

        ensureLogFileExists();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        writeBlockToLogFile("--- BlockScanner started at " + timestamp + " ---");

        System.out.println("BlockScanner initialized - logging unique modded blocks to " + LOG_FILE.getAbsolutePath());

        // Initialize configuration systems
        ConfigLoader.init();
        ScannedBlocksTracker.init();
        blockReplacements = ConfigLoader.blockReplacements;

        System.out.println("==============================================");
        System.out.println("BlockScanner mod is initializing!");
        System.out.println("Version: 1.0.0");
        System.out.println("Use /blockscanner or /bscan in-game");
        System.out.println("==============================================");
    }

    private void setupCommon(final FMLCommonSetupEvent event) {
        System.out.println("BlockScanner common setup");
        writeBlockToLogFile("Common setup completed");
    }

    @OnlyIn(Dist.CLIENT)
    private void setupClient(final FMLClientSetupEvent event) {
        System.out.println("BlockScanner client setup");
        writeBlockToLogFile("Client setup completed");

        MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        System.out.println("BlockScanner detected server starting");
        writeBlockToLogFile("Server starting detected");
    }
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("BlockScanner: Server started, registering server tick handler");
        System.out.println("[BlockScanner] Server started, commands should now be available");
        System.out.println("[BlockScanner] Try using /blockscanner or /bscan");
        writeBlockToLogFile("Server started, registering server tick handler");
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
    }
    
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        // Only process chunks automatically if enabled
        if (!autoReplaceEnabled) return;
        
        if (event.getWorld() instanceof ServerLevel && event.getChunk() != null) {
            ChunkPos chunkPos = new ChunkPos(event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (!processedChunks.contains(chunkPos)) {
                processedChunks.add(chunkPos);
                // Queue chunk for processing in the next server tick
                // This avoids processing during chunk loading which can cause issues
            }
        }
    }

    // Server-side event handler for block replacement
    public class ServerEventHandler {
        private int serverTickCounter = 0;
        private static final int SERVER_SCAN_INTERVAL = 20; // Every second
        
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
            autoTickCounter++;
            if (playerAutoScanEnabled && autoTickCounter >= AUTO_SCAN_INTERVAL) {
                autoTickCounter = 0;
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    LOGGER.info("Auto-scan tick triggered, scanning for players");
                    for (ServerLevel level : server.getAllLevels()) {
                        for (Player player : level.players()) {
                            LOGGER.info("Auto-scanning around player {} in a {} block radius", 
                                    player.getName().getString(), AUTO_SCAN_RADIUS);
                            player.sendMessage(new TextComponent(
                                    "[BlockScanner] Starting automatic scan in a " + AUTO_SCAN_RADIUS + " block radius")
                                    .withStyle(ChatFormatting.YELLOW), UUID.randomUUID());
                            
                            // Forcefully reload block replacements before each scan to ensure we have the latest
                            ConfigLoader.loadBlockReplacements();
                            blockReplacements = ConfigLoader.blockReplacements;
                            LOGGER.info("Loaded {} block replacements for auto-scan", blockReplacements.size());
                            
                            scanAroundPlayerWithProgress(player, level, AUTO_SCAN_RADIUS);
                        }
                    }
                }
            }
            
            // Report progress every 5 minutes (6000 ticks)
            if (serverTickCounter % 6000 == 0) {
                reportScanProgress();
            }
        }
        
        private void reportScanProgress() {
            if (totalBlocksScanned > 0 || totalBlocksReplaced > 0) {
                String message = String.format("BlockScanner statistics - Total scanned: %d blocks, Total replaced: %d blocks", 
                        totalBlocksScanned, totalBlocksReplaced);
                LOGGER.info(message);
                
                // Broadcast to all players
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        player.sendMessage(new TextComponent(message).withStyle(ChatFormatting.GOLD), UUID.randomUUID());
                    }
                }
            }
        }
        
        private void processQueuedChunks() {
            // Only process chunks automatically if enabled
            if (!processedChunks.isEmpty()) {
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                
                System.out.println("[BlockScanner] Processing queued chunks: " + processedChunks.size() + " remaining");
                
                // Get a batch of chunks to process
                ChunkPos[] chunksToProcess = processedChunks.stream().limit(10).toArray(ChunkPos[]::new);
                int totalChunks = chunksToProcess.length;
                int processedCount = 0;
                
                for (ChunkPos chunkPos : chunksToProcess) {
                    processedChunks.remove(chunkPos);
                    processedCount++;
                    
                    // Progress indicator
                    System.out.println("[BlockScanner] Processing chunk " + processedCount + " of " + totalChunks + 
                                      " [" + (processedCount * 100 / totalChunks) + "%]");
                    
                    // Process each world
                    for (ServerLevel level : server.getAllLevels()) {
                        if (level.hasChunk(chunkPos.x, chunkPos.z)) {
                            processChunk(level, chunkPos);
                        }
                    }
                }
                
                // Report completion
                System.out.println("[BlockScanner] Chunk processing batch complete. " + processedChunks.size() + " chunks remaining in queue.");
            }
        }
        
        private void processChunk(ServerLevel level, ChunkPos chunkPos) {
            Set<String> replacedBlocks = new HashSet<>();
            int replacementCount = 0;
            
            // Process each block in the chunk
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        BlockPos pos = new BlockPos(chunkPos.x * 16 + x, y, chunkPos.z * 16 + z);
                        
                        try {
                            BlockState state = level.getBlockState(pos);
                            String registryName = getRegistryName(state);
                            
                            // Skip vanilla blocks
                            if (registryName.startsWith("minecraft:")) continue;
                            
                            // Replace block if it exists in the replacements map
                            if (blockReplacements.containsKey(registryName)) {
                                String replacementBlock = blockReplacements.get(registryName);
                                ResourceLocation replacementBlockLocation = new ResourceLocation(replacementBlock);
                                Block replacement = ForgeRegistries.BLOCKS.getValue(replacementBlockLocation);
                                
                                if (replacement != null) {
                                    try {
                                        // Use safer block replacement
                                        safelyReplaceBlock(level, pos, replacement.defaultBlockState());
                                        replacedBlocks.add(registryName + " -> " + replacementBlock);
                                        replacementCount++;
                                    } catch (Exception e) {
                                        LOGGER.warn("Error replacing block " + registryName + " at " + pos + ": " + e.getMessage(), e);
                                    }
                                } else {
                                    System.err.println("Invalid block ID in block replacements: " + replacementBlock);
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Error processing block at " + pos + ": " + e.getMessage());
                        }
                    }
                }
            }
            
            if (replacementCount > 0) {
                System.out.println("Processed chunk at " + chunkPos + ": replaced " + replacementCount + " blocks");
                for (String replacement : replacedBlocks) {
                    writeBlockToLogFile("Replaced: " + replacement);
                }
            }
        }
        
        // New method for safer block replacement
        private void safelyReplaceBlock(ServerLevel level, BlockPos pos, BlockState newState) {
            try {
                // First try with standard replacement (flag 3 = update + notify)
                level.setBlock(pos, newState, 3);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("updating neighbors")) {
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

    // Keep the client-side handler for singleplayer worlds
    @OnlyIn(Dist.CLIENT)
    public class ClientEventHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }

            tickCounter++;
            if (tickCounter < SCAN_INTERVAL) return;
            tickCounter = 0;

            if (allDiscoveredModdedBlocks.isEmpty()) {
                String playerName = "unknown";
                if (mc.player.getName() != null) {
                    playerName = mc.player.getName().getString();
                }
                
                System.out.println("BlockScanner performing first scan around player: " + playerName);
                writeBlockToLogFile("First scan around player: " + playerName);
            }

            scanAroundPlayer(mc.player, mc.level);
        }
        
        // Add methods to show scan progress in client chat
        public void showScanStartMessage(int radius, int totalBlocks) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    new TextComponent("[BlockScanner] Starting scan of " + totalBlocks + 
                        " blocks in a " + radius + " block radius").withStyle(ChatFormatting.GREEN), 
                    false
                );
            }
        }
        
        public void showScanProgressMessage(int percentage, int scanned, int total) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    new TextComponent(String.format("[BlockScanner] Progress: %d%% (%d/%d blocks scanned)", 
                        percentage, scanned, total)).withStyle(ChatFormatting.AQUA), 
                    false
                );
            }
        }
        
        public void showScanCompleteMessage(int scanned, int replaced) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    new TextComponent(String.format("[BlockScanner] Scan complete: scanned %d blocks, replaced %d blocks", 
                        scanned, replaced)).withStyle(ChatFormatting.GREEN), 
                    true
                );
            }
        }
    }

    private void scanAroundPlayer(Player player, Level world) {
        scanAroundPlayerWithProgress(player, world, SCAN_RADIUS);
    }

    private void scanAroundPlayerWithProgress(Player player, Level world, int radius) {
        if (player == null || world == null) {
            System.out.println("[BlockScanner] Player or world is null, cannot scan");
            return;
        }
        
        BlockPos playerPos;
        try {
            playerPos = player.blockPosition();
            if (playerPos == null) {
                System.out.println("[BlockScanner] Player position is null, cannot scan");
                return;
            }
        } catch (Exception e) {
            System.err.println("[BlockScanner] Unable to get player position: " + e.getMessage());
            return;
        }
        
        Set<String> newModdedBlocks = new HashSet<>();
        int scanCount = 0;
        int loadedChunks = 0;
        int blocksReplaced = 0;
        int totalBlocksToCheck = (2 * radius + 1) * (2 * radius + 1) * (2 * radius + 1);

        System.out.println("[BlockScanner] Starting scan around player at " + playerPos + " with radius " + radius);
        System.out.println("[BlockScanner] Approximately " + totalBlocksToCheck + " blocks to check");
        System.out.println("[BlockScanner] Using " + blockReplacements.size() + " block replacements");

        // Print the first 10 replacement entries for debugging
        int count = 0;
        for (Map.Entry<String, String> entry : blockReplacements.entrySet()) {
            if (count++ < 10) {
                System.out.println("[BlockScanner] Replacement entry: " + entry.getKey() + " -> " + entry.getValue());
            } else {
                break;
            }
        }

        int progressInterval = Math.max(1, totalBlocksToCheck / 10); // Report progress 10 times
        int lastProgressPercent = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    scanCount++;
                    
                    // Use progressInterval for progress reporting
                    if (scanCount % progressInterval == 0) {
                        int progressPercent = (scanCount * 100) / totalBlocksToCheck;
                        // Report progress every 10%
                        if (progressPercent >= lastProgressPercent + 10) {
                            lastProgressPercent = progressPercent;
                            System.out.println("[BlockScanner] Scan progress: " + progressPercent + "% complete");
                        }
                    }

                    if (pos.getY() < world.getMinBuildHeight() || pos.getY() > world.getMaxBuildHeight()) continue;
                    if (!world.isLoaded(pos)) continue;

                    loadedChunks++;

                    try {
                        BlockState state = world.getBlockState(pos);
                        String registryName = getRegistryName(state);
                        if (registryName == null || registryName.isEmpty()) {
                            continue; // Skip blocks with invalid registry names
                        }

                        if (!registryName.startsWith("minecraft:")) {
                            newModdedBlocks.add(registryName);
                            allDiscoveredModdedBlocks.add(registryName); // Update class field too
                            // Fix the unused isNewBlock variable - actually check its value 
                            boolean isNewBlock = ScannedBlocksTracker.addScannedBlock(registryName);
                            if (isNewBlock) {
                                LOGGER.info("Discovered new block type: {}", registryName);
                            }
                            
                            // Replace block if it exists in the replacements map
                            if (blockReplacements.containsKey(registryName)) {
                                String replacementBlock = blockReplacements.get(registryName);
                                ResourceLocation replacementBlockLocation = new ResourceLocation(replacementBlock);
                                Block replacement = ForgeRegistries.BLOCKS.getValue(replacementBlockLocation);

                                if (replacement != null) {
                                    BlockState replacementState = replacement.defaultBlockState();
                                    world.setBlock(pos, replacementState, 3);
                                    blocksReplaced++;
                                } else {
                                    System.err.println("[BlockScanner] Invalid block ID in block replacements: " + replacementBlock);
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[BlockScanner] Error scanning block at " + pos + ": " + e.getMessage());
                    }
                }
            }
        }

        System.out.println("[BlockScanner] Scan complete: 100%");
        System.out.println("[BlockScanner] Checked " + scanCount + " blocks in " + loadedChunks + " loaded chunk positions.");
        System.out.println("[BlockScanner] Replaced " + blocksReplaced + " blocks during scan.");

        if (!newModdedBlocks.isEmpty()) {
            System.out.println("[BlockScanner] Discovered " + newModdedBlocks.size() + " new modded blocks:");
            for (String block : newModdedBlocks) {
                System.out.println("[BlockScanner] - " + block);
                writeBlockToLogFile(block);
            }
            
            // Generate suggested replacements after discovering new blocks
            if (ScannedBlocksTracker.generateReplacementConfig()) {
                player.sendMessage(new TextComponent("[BlockScanner] Generated suggested replacement config with newly discovered blocks")
                        .withStyle(ChatFormatting.GOLD), UUID.randomUUID());
            }
        }
        
        // Send starting message to player
        player.sendMessage(new TextComponent("[BlockScanner] Starting scan of " + totalBlocksToCheck + 
                " blocks in a " + radius + " block radius").withStyle(ChatFormatting.GREEN), UUID.randomUUID());
        
        // Log progress periodically
        if (scanCount % 10000 == 0) {
            int percentage = (scanCount * 100) / totalBlocksToCheck;
            String progressMsg = String.format("[BlockScanner] Progress: %d%% (%d/%d blocks scanned)", 
                    percentage, scanCount, totalBlocksToCheck);
            LOGGER.info(progressMsg);
            player.sendMessage(new TextComponent(progressMsg).withStyle(ChatFormatting.AQUA), UUID.randomUUID());
        }
        
        // Send completion message
        String completionMsg = String.format("[BlockScanner] Scan complete: scanned %d blocks, replaced %d blocks", 
                scanCount, blocksReplaced);
        LOGGER.info(completionMsg);
        player.sendMessage(new TextComponent(completionMsg).withStyle(ChatFormatting.GREEN), UUID.randomUUID());
    }

    private String getRegistryName(BlockState state) {
        if (state == null || state.getBlock() == null) {
            return "";
        }
        
        // Safe way to get registry name
        try {
            return ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
        } catch (Exception e) {
            System.err.println("Error getting registry name: " + e.getMessage());
            return "";
        }
    }

    private void ensureLogFileExists() {
        if (!LOG_FILE.exists()) {
            try {
                File parentDir = LOG_FILE.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                LOG_FILE.createNewFile();
                System.out.println("Created log file at: " + LOG_FILE.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Failed to create log file: " + e.getMessage());
                e.printStackTrace(); // Print stack trace for debugging
            }
        }
    }

    private void writeBlockToLogFile(String blockName) {
        try (FileWriter writer = new FileWriter(LOG_FILE, true)) {
            writer.write(blockName + "\n");
            writer.flush();
            System.out.println("Wrote to log file: " + blockName);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        }
    }
}

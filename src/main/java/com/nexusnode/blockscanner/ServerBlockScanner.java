package com.nexusnode.blockscanner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod("blockscanner")
public class ServerBlockScanner {
    private static final File LOG_FILE = new File("./logs/blockscanner_log.txt");
    private final Set<String> allDiscoveredModdedBlocks = new HashSet<>();
    private static final int SCAN_INTERVAL = 40;
    private static final int SCAN_RADIUS = 64;
    private static final Set<ChunkPos> processedChunks = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    private final Map<Player, Integer> blocksScannedPerPlayer = new ConcurrentHashMap<>();
    private final Map<Player, Integer> blocksReplacedPerPlayer = new ConcurrentHashMap<>();

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
    }

    private void setupCommon(final FMLCommonSetupEvent event) {
        System.out.println("BlockScanner common setup");
        writeBlockToLogFile("Common setup completed");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        System.out.println("BlockScanner detected server starting");
        writeBlockToLogFile("Server starting detected");
    }
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        System.out.println("BlockScanner: Server started, registering server tick handler");
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
            int processedThisTick = 0;
            final int MAX_CHUNKS_PER_TICK = 5; // Limit chunks per tick to avoid lag
            
            while (processedThisTick < MAX_CHUNKS_PER_TICK && !chunksToProcess.isEmpty()) {
                ChunkPos chunkPos = chunksToProcess.poll();
                ServerLevel level = chunkWorldMap.get(chunkPos);
                
                if (level != null) {
                    LOGGER.info("Processing chunk at {}", chunkPos);
                    processChunk(level, chunkPos);
                    processedThisTick++;
                }
                
                // Remove from map after processing
                chunkWorldMap.remove(chunkPos);
            }
            
            if (processedThisTick > 0) {
                LOGGER.info("Processed {} chunks this tick", processedThisTick);
            }
        }
        
        public void queueChunkForProcessing(ServerLevel level, ChunkPos pos) {
            if (!chunksToProcess.contains(pos)) {
                chunksToProcess.add(pos);
                chunkWorldMap.put(pos, level);
                LOGGER.debug("Added chunk at {} to processing queue", pos);
            }
        }
        
        private void processChunk(ServerLevel level, ChunkPos chunkPos) {
            int minX = chunkPos.getMinBlockX();
            int minZ = chunkPos.getMinBlockZ();
            int maxX = chunkPos.getMaxBlockX();
            int maxZ = chunkPos.getMaxBlockZ();
            int blocksScanned = 0;
            int blocksReplaced = 0;
            
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        blocksScanned++;
                        
                        String blockId = getRegistryName(state);
                        if (blockReplacements.containsKey(blockId)) {
                            String replacementId = blockReplacements.get(blockId);
                            LOGGER.debug("Found block to replace: {} with {}", blockId, replacementId);
                            
                            try {
                                String[] parts = replacementId.split(":");
                                if (parts.length == 2) {
                                    ResourceLocation replaceRL = new ResourceLocation(parts[0], parts[1]);
                                    Block replaceBlock = ForgeRegistries.BLOCKS.getValue(replaceRL);
                                    
                                    if (replaceBlock != null) {
                                        safelyReplaceBlock(level, pos, replaceBlock.defaultBlockState());
                                        blocksReplaced++;
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
            
            totalBlocksScanned += blocksScanned;
            totalBlocksReplaced += blocksReplaced;
            
            if (blocksReplaced > 0) {
                LOGGER.info("Chunk processed: scanned {} blocks, replaced {} blocks", 
                        blocksScanned, blocksReplaced);
            }
        }
        
        private void safelyReplaceBlock(ServerLevel level, BlockPos pos, BlockState newState) {
            // Check if the position is loaded to avoid crashes
            if (!level.isLoaded(pos)) return;
            
            try {
                // Set the block with a flag of 3 to update clients and mark the chunk as dirty
                level.setBlock(pos, newState, 3);
            } catch (Exception e) {
                LOGGER.error("Error replacing block at {}: {}", pos, e.getMessage());
            }
        }
        
        private void reportProgress() {
            if (totalBlocksScanned > 0 || totalBlocksReplaced > 0) {
                String message = String.format("[BlockScanner] Progress: scanned %d blocks, replaced %d blocks", 
                        totalBlocksScanned, totalBlocksReplaced);
                LOGGER.info(message);
                
                // Broadcast to all players
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD));
                    }
                }
            }
        }
    }

    public void scanAroundPlayerWithProgress(Player player, Level world, int radius) {
        if (!(world instanceof ServerLevel)) return;
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
        
        // Send starting message to player
        player.sendSystemMessage(Component.literal("[BlockScanner] Starting scan of " + totalBlocks + 
                " blocks in a " + radius + " block radius").withStyle(ChatFormatting.GREEN));
        
        int scanned = 0;
        int replaced = 0;
        
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    scanned++;
                    
                    // Log progress every 10,000 blocks
                    if (scanned % 10000 == 0) {
                        int percentage = (scanned * 100) / totalBlocks;
                        String progressMsg = String.format("[BlockScanner] Progress: %d%% (%d/%d blocks scanned)", 
                                percentage, scanned, totalBlocks);
                        LOGGER.info(progressMsg);
                        player.sendSystemMessage(Component.literal(progressMsg).withStyle(ChatFormatting.AQUA));
                    }
                    
                    String blockId = getRegistryName(state);
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
        
        String completionMsg = String.format("[BlockScanner] Scan complete: scanned %d blocks, replaced %d blocks", 
                scanned, replaced);
        LOGGER.info(completionMsg);
        player.sendSystemMessage(Component.literal(completionMsg).withStyle(ChatFormatting.GREEN));
    }

    private void scanAroundPlayer(Player player, Level world) {
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

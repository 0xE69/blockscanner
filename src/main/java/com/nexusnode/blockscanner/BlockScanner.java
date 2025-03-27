package com.nexusnode.blockscanner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;

@Mod("blockscanner")
public class BlockScanner {

    private static final File LOG_FILE = new File("./logs/blockscanner_log.txt");
    // Keep track of all unique modded blocks we've seen
    private final Set<String> allDiscoveredModdedBlocks = new HashSet<>();
    // How often to scan (every X ticks)
    private static final int SCAN_INTERVAL = 40; // About 2 seconds
    // Scanning range around player
    private static final int SCAN_RADIUS = 64; // 64 blocks in each direction (as requested)
    
    private int tickCounter = 0;
    
    public BlockScanner() {
        // Register for lifecycle events
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setupCommon);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setupClient);
        
        // Register for server events
        MinecraftForge.EVENT_BUS.register(this);
        
        // Log startup message
        System.out.println("BlockScanner constructor called");
        
        // Ensure the log file exists
        ensureLogFileExists();
        
        // Add a timestamp to show when the mod was loaded
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        writeBlockToLogFile("--- BlockScanner started at " + timestamp + " ---");
        
        System.out.println("BlockScanner initialized - logging unique modded blocks to " + LOG_FILE.getAbsolutePath());
    }
    
    private void setupCommon(final FMLCommonSetupEvent event) {
        System.out.println("BlockScanner common setup");
        writeBlockToLogFile("Common setup completed");
    }
    
    @OnlyIn(Dist.CLIENT)
    private void setupClient(final FMLClientSetupEvent event) {
        System.out.println("BlockScanner client setup");
        writeBlockToLogFile("Client setup completed");
        
        // Register for client-specific events
        MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
    }
    
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        System.out.println("BlockScanner detected server starting");
        writeBlockToLogFile("Server starting detected");
    }
    
    // Client-side event handler inner class
    @OnlyIn(Dist.CLIENT)
    public class ClientEventHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            // Only run once per tick cycle (at the END phase)
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            
            // Skip if no world or player is loaded
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            
            // Only scan every SCAN_INTERVAL ticks to avoid performance issues
            tickCounter++;
            if (tickCounter < SCAN_INTERVAL) {
                return;
            }
            tickCounter = 0;
            
            // Log once when we start scanning
            if (allDiscoveredModdedBlocks.isEmpty()) {
                System.out.println("BlockScanner performing first scan around player: " + mc.player.getName().getString());
                writeBlockToLogFile("First scan around player: " + mc.player.getName().getString());
            }
            
            scanAroundPlayer(mc.player, mc.level);
        }
    }
    
    private void scanAroundPlayer(Player player, Level world) {
        Set<String> newModdedBlocks = new HashSet<>();
        BlockPos playerPos = player.blockPosition();
        int scanCount = 0;
        int loadedChunks = 0;
        
        // Log scanning start
        System.out.println("Starting scan around player at " + playerPos);
        
        // Scan in a cube around the player
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    
                    // Skip positions outside world height limits
                    if (pos.getY() < world.getMinBuildHeight() || pos.getY() > world.getMaxBuildHeight()) {
                        continue;
                    }
                    
                    // Skip unloaded chunks
                    if (!world.isLoaded(pos)) {
                        continue;
                    }
                    
                    loadedChunks++;
                    
                    try {
                        BlockState state = world.getBlockState(pos);
                        String registryName = getRegistryName(state);
                        scanCount++;
                        
                        if (!registryName.startsWith("minecraft") && !allDiscoveredModdedBlocks.contains(registryName)) {
                            newModdedBlocks.add(registryName);
                            allDiscoveredModdedBlocks.add(registryName);
                        }
                    } catch (Exception e) {
                        System.err.println("Error scanning block at " + pos + ": " + e.getMessage());
                    }
                }
            }
        }
        
        System.out.println("Scan complete. Checked " + scanCount + " blocks in " + loadedChunks + " loaded chunk positions.");
        
        if (!newModdedBlocks.isEmpty()) {
            System.out.println("Discovered " + newModdedBlocks.size() + " new modded blocks:");
            for (String block : newModdedBlocks) {
                System.out.println("- " + block);
                writeBlockToLogFile(block);
            }
        }
    }
    
    private String getRegistryName(BlockState state) {
        return ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
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
            writer.flush(); // Ensure content is written immediately
            
            // Debug output to verify writing
            System.out.println("Wrote to log file: " + blockName);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        }
    }
}

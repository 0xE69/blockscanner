package com.nexusnode.blockscanner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

@Mod("blockscanner")
public class BlockScanner {

    private static final File LOG_FILE = new File("./logs/blockscanner_log.txt");
    // Keep track of all unique modded blocks we've seen
    private final Set<String> allDiscoveredModdedBlocks = new HashSet<>();
    // How often to scan (every X ticks)
    private static final int SCAN_INTERVAL = 40; // About 2 seconds
    // Scanning range around player
    private static final int SCAN_RADIUS = 32; // 32 blocks in each direction
    
    private int tickCounter = 0;
    
    public BlockScanner() {
        // Ensure the log file exists
        ensureLogFileExists();
        
        // Register this instance for Forge events
        MinecraftForge.EVENT_BUS.register(this);
        
        System.out.println("BlockScanner initialized - logging unique modded blocks to " + LOG_FILE.getAbsolutePath());
    }
    
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // Only run on server side and once per tick cycle
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END) {
            return;
        }
        
        // Only scan every SCAN_INTERVAL ticks to avoid performance issues
        tickCounter++;
        if (tickCounter < SCAN_INTERVAL) {
            return;
        }
        tickCounter = 0;
        
        ServerPlayer player = (ServerPlayer)event.player;
        scanAroundPlayer(player);
    }
    
    private void scanAroundPlayer(ServerPlayer player) {
        ServerLevel world = player.getLevel();
        Set<String> newModdedBlocks = new HashSet<>();
        BlockPos playerPos = player.blockPosition();
        
        // Scan in a cube around the player
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    
                    // Skip positions outside world height limits
                    if (pos.getY() < world.getMinBuildHeight() || pos.getY() > world.getMaxBuildHeight()) {
                        continue;
                    }
                    
                    BlockState state = world.getBlockState(pos);
                    String registryName = getRegistryName(state);
                    
                    if (!registryName.startsWith("minecraft") && !allDiscoveredModdedBlocks.contains(registryName)) {
                        newModdedBlocks.add(registryName);
                        allDiscoveredModdedBlocks.add(registryName);
                    }
                }
            }
        }
        
        if (!newModdedBlocks.isEmpty()) {
            System.out.println("Player " + player.getName().getString() + " discovered new modded blocks:");
            for (String block : newModdedBlocks) {
                System.out.println("- " + block);
                writeBlockToLogFile(block);
            }
        }
    }

    private String getRegistryName(BlockState state) {
        return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
    }

    private void ensureLogFileExists() {
        if (!LOG_FILE.exists()) {
            try {
                File parentDir = LOG_FILE.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                LOG_FILE.createNewFile();
            } catch (IOException e) {
                System.err.println("Failed to create log file: " + e.getMessage());
            }
        }
    }

    private void writeBlockToLogFile(String blockName) {
        try (FileWriter writer = new FileWriter(LOG_FILE, true)) {
            writer.write(blockName + "\n");
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }
}

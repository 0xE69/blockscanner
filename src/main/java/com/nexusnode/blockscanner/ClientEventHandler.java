package com.nexusnode.blockscanner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class ClientEventHandler {

    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    private static final int SCAN_INTERVAL = 40; // About 2 seconds
    private static final int SCAN_RADIUS = 64; // 64 blocks in each direction
    private int tickCounter = 0;
    private Map<String, String> blockReplacements = new HashMap<>();

    public ClientEventHandler() {
        // Use ConfigLoader instead of loading directly
        blockReplacements = ConfigLoader.blockReplacements;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        tickCounter++;
        if (tickCounter < SCAN_INTERVAL) {
            return;
        }
        tickCounter = 0;

        scanAroundPlayer(mc.player, mc.level);
    }

    private void scanAroundPlayer(Player player, Level world) {
        if (player == null || world == null) {
            return;
        }
        
        BlockPos playerPos = player.blockPosition();
        
        System.out.println("Starting scan around player at " + playerPos);
        showScanStartMessage(SCAN_RADIUS, (SCAN_RADIUS * 2 + 1) * (SCAN_RADIUS * 2 + 1) * (SCAN_RADIUS * 2 + 1));

        int totalBlocks = 0;
        int processedBlocks = 0;
        int replacedBlocks = 0;

        // Scan in a cube around the player
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    totalBlocks++;
                    
                    // Skip positions outside world height limits
                    if (pos.getY() < world.getMinBuildHeight() || pos.getY() > world.getMaxBuildHeight()) {
                        continue;
                    }

                    // Skip unloaded chunks
                    if (!world.isLoaded(pos)) {
                        continue;
                    }

                    processedBlocks++;
                    
                    // Progress reporting (every 10%)
                    if (processedBlocks % 10000 == 0) {
                        int percent = (processedBlocks * 100) / totalBlocks;
                        showScanProgressMessage(percent, processedBlocks, totalBlocks);
                    }

                    try {
                        BlockState state = world.getBlockState(pos);
                        String registryName = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();

                        if (blockReplacements.containsKey(registryName)) {
                            String replacementBlockId = blockReplacements.get(registryName);
                            ResourceLocation replacementLocation = ResourceLocation.tryParse(replacementBlockId);
                            if (replacementLocation != null) {
                                BlockState replacementState = ForgeRegistries.BLOCKS.getValue(replacementLocation).defaultBlockState();
                                try {
                                    safelyReplaceBlock(world, pos, replacementState);
                                    replacedBlocks++;
                                } catch (Exception e) {
                                    LOGGER.warn("Error replacing block " + registryName + " at " + pos + ": " + e.getMessage(), e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error scanning or replacing block at " + pos + ": " + e.getMessage(), e);
                    }
                }
            }
        }

        System.out.println("Scan complete. Scanned " + processedBlocks + " blocks, replaced " + replacedBlocks + " blocks.");
        showScanCompleteMessage(processedBlocks, replacedBlocks);
    }
    
    private void safelyReplaceBlock(Level world, BlockPos pos, BlockState newState) {
        try {
            // First try with standard replacement (flag 3 = update + notify)
            world.setBlockAndUpdate(pos, newState);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("updating neighbors")) {
                LOGGER.info("Trying alternative block replacement method for " + pos + " due to neighbor update issue");
                try {
                    // Use the setBlock method with flag 2 (only notify, no updates to neighbors)
                    world.setBlock(pos, newState, 2);
                } catch (Exception e2) {
                    // If that also fails, try with flag 0 (no updates at all)
                    try {
                        world.setBlock(pos, newState, 0);
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
    
    public void showScanStartMessage(int radius, int totalBlocks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("[BlockScanner] Starting scan of " + totalBlocks + 
                    " blocks in a " + radius + " block radius").withStyle(ChatFormatting.GREEN), 
                false
            );
        }
    }
    
    public void showScanProgressMessage(int percentage, int scanned, int total) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal(String.format("[BlockScanner] Progress: %d%% (%d/%d blocks scanned)", 
                    percentage, scanned, total)).withStyle(ChatFormatting.AQUA), 
                false
            );
        }
    }
    
    public void showScanCompleteMessage(int scanned, int replaced) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal(String.format("[BlockScanner] Scan complete: scanned %d blocks, replaced %d blocks", 
                    scanned, replaced)).withStyle(ChatFormatting.GREEN), 
                true
            );
        }
    }
}

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

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

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
            // Get original state to preserve properties
            BlockState oldState = world.getBlockState(pos);
            BlockState stateToUse = preserveBlockProperties(oldState, newState);
            
            // First try with standard replacement (flag 3 = update + notify)
            world.setBlockAndUpdate(pos, stateToUse);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("updating neighbors")) {
                LOGGER.info("Trying alternative block replacement method for " + pos + " due to neighbor update issue");
                try {
                    // Get original state again in case it changed
                    BlockState oldState = world.getBlockState(pos);
                    BlockState stateToUse = preserveBlockProperties(oldState, newState);
                    
                    // Use the setBlock method with flag 2 (only notify, no updates to neighbors)
                    world.setBlock(pos, stateToUse, 2);
                } catch (Exception e2) {
                    // If that also fails, try with flag 0 (no updates at all)
                    try {
                        BlockState oldState = world.getBlockState(pos);
                        BlockState stateToUse = preserveBlockProperties(oldState, newState);
                        world.setBlock(pos, stateToUse, 0);
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
    private BlockState preserveBlockProperties(BlockState oldState, BlockState newState) {
        BlockState result = newState;
        
        // Fix the collection type mismatch by using Collection instead of Set
        java.util.Collection<net.minecraft.world.level.block.state.properties.Property<?>> oldProps = oldState.getProperties();
        java.util.Collection<net.minecraft.world.level.block.state.properties.Property<?>> newProps = newState.getProperties();
        
        // Find properties that exist in both blocks
        for (net.minecraft.world.level.block.state.properties.Property<?> oldProp : oldProps) {
            String propName = oldProp.getName();
            
            // Common properties to preserve: facing, rotation, axis, etc.
            boolean isImportantProperty = propName.equals("facing") || 
                                         propName.equals("rotation") || 
                                         propName.equals("axis") ||
                                         propName.equals("half") ||
                                         propName.equals("type") ||
                                         propName.equals("waterlogged");
            
            // Log important properties for debugging
            if (isImportantProperty) {
                LOGGER.debug("Found important property to preserve: {} with value {}", 
                    propName, oldState.getValue(oldProp));
            }
            
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
    
    public void showScanStartMessage(int radius, int totalBlocks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && mc.player.isAlive()) {
            // Fixed null safety check
            mc.player.displayClientMessage(
                new TextComponent("[BlockScanner] Starting scan of " + totalBlocks + 
                    " blocks in a " + radius + " block radius").withStyle(ChatFormatting.GREEN), 
                false
            );
        }
    }
    
    public void showScanProgressMessage(int percentage, int scanned, int total) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && mc.player.isAlive()) {
            // Fixed null safety check
            mc.player.displayClientMessage(
                new TextComponent(String.format("[BlockScanner] Progress: %d%% (%d/%d blocks scanned)", 
                    percentage, scanned, total)).withStyle(ChatFormatting.AQUA), 
                false
            );
        }
    }
    
    public void showScanCompleteMessage(int scanned, int replaced) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            // Ensure player is not null before accessing it
            Player player = mc.player;
            if (player != null && player.isAlive()) {
                player.displayClientMessage(
                    new TextComponent(String.format("[BlockScanner] Scan complete: scanned %d blocks, replaced %d blocks", 
                        scanned, replaced)).withStyle(ChatFormatting.GREEN), 
                    true
                );
            }
        }
    }
}

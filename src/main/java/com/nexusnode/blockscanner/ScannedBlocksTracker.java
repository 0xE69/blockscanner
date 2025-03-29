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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles tracking and saving scanned blocks for the BlockScanner mod.
 * This is separate from the block replacements configuration.
 */
public class ScannedBlocksTracker {
    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File scannedBlocksFile = null;
    private static Set<String> scannedBlocks = new HashSet<>();
    
    /**
     * Initialize the scanned blocks tracker
     */
    public static void init() {
        loadScannedBlocks();
    }
    
    /**
     * Gets the mod's configuration directory
     */
    private static File getConfigDir() {
        File configDir = FMLPaths.CONFIGDIR.get().toFile();
        File blockScannerConfigDir = new File(configDir, "blockscanner");
        if (!blockScannerConfigDir.exists()) {
            blockScannerConfigDir.mkdirs();
            LOGGER.info("Created mod config directory at {}", blockScannerConfigDir.getAbsolutePath());
        }
        return blockScannerConfigDir;
    }
    
    /**
     * Loads previously scanned blocks from the config file
     */
    public static void loadScannedBlocks() {
        File configDir = getConfigDir();
        scannedBlocksFile = new File(configDir, "scanned_blocks.json");
        
        LOGGER.info("Attempting to load scanned blocks from {}", scannedBlocksFile.getAbsolutePath());
        
        if (!scannedBlocksFile.exists()) {
            LOGGER.info("No previous scanned blocks file found, creating new one");
            scannedBlocks = new HashSet<>();
            saveScannedBlocks();
            return;
        }
        
        try {
            Type listType = new TypeToken<List<String>>(){}.getType();
            List<String> blocksList = GSON.fromJson(new FileReader(scannedBlocksFile), listType);
            
            if (blocksList != null) {
                scannedBlocks = new HashSet<>(blocksList);
                LOGGER.info("Loaded {} previously scanned block types", scannedBlocks.size());
            } else {
                scannedBlocks = new HashSet<>();
                LOGGER.warn("Empty or invalid scanned blocks file");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load scanned blocks: {}", e.getMessage(), e);
            scannedBlocks = new HashSet<>();
        }
    }
    
    /**
     * Saves the currently scanned blocks to the config file
     */
    public static boolean saveScannedBlocks() {
        if (scannedBlocksFile == null) {
            File configDir = getConfigDir();
            scannedBlocksFile = new File(configDir, "scanned_blocks.json");
        }
        
        try {
            List<String> blocksList = new ArrayList<>(scannedBlocks);
            
            try (FileWriter writer = new FileWriter(scannedBlocksFile)) {
                GSON.toJson(blocksList, writer);
                LOGGER.info("Saved {} scanned block types to {}", blocksList.size(), scannedBlocksFile.getAbsolutePath());
                return true;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save scanned blocks: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Adds a newly discovered block to the tracker
     */
    public static boolean addScannedBlock(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }
        
        boolean isNew = scannedBlocks.add(blockId);
        if (isNew) {
            // Only save if we found a new block
            saveScannedBlocks();
        }
        return isNew;
    }
    
    /**
     * Add multiple blocks at once
     */
    public static int addScannedBlocks(Set<String> blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return 0;
        }
        
        int initialSize = scannedBlocks.size();
        scannedBlocks.addAll(blockIds);
        int newBlocks = scannedBlocks.size() - initialSize;
        
        if (newBlocks > 0) {
            saveScannedBlocks();
        }
        
        return newBlocks;
    }
    
    /**
     * Get all currently scanned blocks
     */
    public static Set<String> getScannedBlocks() {
        return new HashSet<>(scannedBlocks);
    }
    
    /**
     * Get all scanned blocks from a specific mod ID
     */
    public static Set<String> getScannedBlocksForMod(String modId) {
        return scannedBlocks.stream()
                .filter(block -> block.startsWith(modId + ":"))
                .collect(Collectors.toSet());
    }
    
    /**
     * Generate a suggested replacement config from scanned blocks
     */
    public static boolean generateReplacementConfig() {
        File configDir = getConfigDir();
        File suggestedReplacementsFile = new File(configDir, "suggested_replacements.json");
        
        try {
            List<Map<String, String>> replacementsList = scannedBlocks.stream()
                    .map(block -> {
                        java.util.Map<String, String> entry = new java.util.HashMap<>();
                        entry.put("original", block);
                        entry.put("replacement", "minecraft:stone"); // Default replacement
                        return entry;
                    })
                    .collect(Collectors.toList());
            
            try (FileWriter writer = new FileWriter(suggestedReplacementsFile)) {
                GSON.toJson(replacementsList, writer);
                LOGGER.info("Generated suggested replacements for {} blocks at {}", 
                        replacementsList.size(), suggestedReplacementsFile.getAbsolutePath());
                return true;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to generate suggested replacements: {}", e.getMessage(), e);
            return false;
        }
    }
}

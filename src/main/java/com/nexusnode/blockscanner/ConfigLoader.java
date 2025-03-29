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
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "blockscanner", bus = Mod.EventBusSubscriber.Bus.MOD)
public class ConfigLoader {

    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    public static Map<String, String> blockReplacements = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile = null;

    // Add a cache timestamp to track config freshness
    private static long lastConfigLoadTime = 0;
    private static final long CONFIG_CACHE_DURATION = 5000; // 5 seconds cache

    public static void init() {
        loadBlockReplacements();
    }
    
    /**
     * Gets the configuration directory based on whether we're running on client or server
     */
    private static File getConfigDir() {
        // Use Forge's path provider to get the proper config directory
        File configDir = FMLPaths.CONFIGDIR.get().toFile();
        LOGGER.info("Using config directory: {}", configDir.getAbsolutePath());
        
        // Create mod-specific subdirectory
        File blockScannerConfigDir = new File(configDir, "blockscanner");
        if (!blockScannerConfigDir.exists()) {
            blockScannerConfigDir.mkdirs();
            LOGGER.info("Created mod config directory at {}", blockScannerConfigDir.getAbsolutePath());
        }
        
        return blockScannerConfigDir;
    }

    /**
     * Load block replacements with caching to improve performance during batch operations
     */
    public static void loadBlockReplacements() {
        long currentTime = System.currentTimeMillis();
        
        // If config was recently loaded and the file hasn't changed, use the cached version
        if (currentTime - lastConfigLoadTime < CONFIG_CACHE_DURATION && configFile != null && 
            configFile.exists() && configFile.lastModified() < lastConfigLoadTime) {
            LOGGER.debug("Using cached block replacements, age: {} ms", (currentTime - lastConfigLoadTime));
            return;
        }
        
        // Get the correct config directory 
        File configDir = getConfigDir();
        
        // Define the config file in the proper location
        configFile = new File(configDir, "block_replacements.json");
        String configFilePath = configFile.getAbsolutePath();
        
        LOGGER.info("Loading block replacements from {}", configFilePath);
        
        // If config doesn't exist, create default
        if (!configFile.exists()) {
            LOGGER.info("Config file not found at {}, attempting to create default", configFilePath);
            
            if (createDefaultConfig(configFile)) {
                LOGGER.info("Created default config file at {}", configFilePath);
            } else {
                LOGGER.error("Failed to create default config file. Using empty configuration.");
                blockReplacements = new HashMap<>();
                return;
            }
        }
        
        // Now load the config
        try {
            LOGGER.info("Loading replacements from {}", configFilePath);
            Type listType = new TypeToken<List<Map<String, String>>>(){}.getType();
            
            // Synchronize access to avoid issues with concurrent modification
            synchronized (GSON) {
                List<Map<String, String>> replacementsList;
                try (FileReader reader = new FileReader(configFile)) {
                    replacementsList = GSON.fromJson(reader, listType);
                }
                
                Map<String, String> newReplacements = new HashMap<>();
                
                if (replacementsList != null) {
                    for (Map<String, String> entry : replacementsList) {
                        String original = entry.get("original");
                        String replacement = entry.get("replacement");
                        if (original != null && replacement != null) {
                            newReplacements.put(original, replacement);
                        } else {
                            LOGGER.error("Invalid entry in block replacements: " + entry);
                        }
                    }
    
                    LOGGER.info("Loaded {} block replacements from {}", newReplacements.size(), configFilePath);
                    
                    // Update the shared map in a thread-safe manner
                    blockReplacements = Collections.unmodifiableMap(newReplacements);
                    
                    // Update cache timestamp
                    lastConfigLoadTime = System.currentTimeMillis();
                } else {
                    LOGGER.warn("Empty or invalid replacements list in config file");
                    blockReplacements = new HashMap<>();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load block replacements from JSON: {}", e.getMessage(), e);
            blockReplacements = new HashMap<>(); // Ensure we have an empty map at minimum
        }
    }
    
    /**
     * Creates a default configuration file if one doesn't exist
     */
    private static boolean createDefaultConfig(File configFile) {
        try {
            // First try to find the default config in the resources
            InputStream defaultConfigStream = ConfigLoader.class.getClassLoader()
                    .getResourceAsStream("blockscanner/default_replacements.json");
            
            if (defaultConfigStream != null) {
                // Read the default config
                InputStreamReader reader = new InputStreamReader(defaultConfigStream);
                Type listType = new TypeToken<List<Map<String, String>>>(){}.getType();
                List<Map<String, String>> defaultConfig = GSON.fromJson(reader, listType);
                
                // Write it to the config file
                try (FileWriter writer = new FileWriter(configFile)) {
                    GSON.toJson(defaultConfig, writer);
                }
                
                LOGGER.info("Created default replacement config from resources");
                return true;
            } else {
                // If we can't find a built-in default, create a minimal example
                List<Map<String, String>> exampleList = createExampleReplacements();
                
                try (FileWriter writer = new FileWriter(configFile)) {
                    GSON.toJson(exampleList, writer);
                }
                
                LOGGER.info("Created example config with basic entries");
                return true;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create default config: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Creates a basic example replacement configuration
     */
    private static List<Map<String, String>> createExampleReplacements() {
        List<Map<String, String>> exampleList = new ArrayList<>();
        
        // Example 1: A generic mod block
        Map<String, String> example1 = new HashMap<>();
        example1.put("original", "modid:example_block");
        example1.put("replacement", "minecraft:stone");
        exampleList.add(example1);
        
        // Example 2: Thermal Series copper ore
        Map<String, String> example2 = new HashMap<>();
        example2.put("original", "thermal:copper_ore");
        example2.put("replacement", "minecraft:copper_ore");
        exampleList.add(example2);
        
        // Example 3: Applied Energistics 2 quartz glass
        Map<String, String> example3 = new HashMap<>();
        example3.put("original", "ae2:quartz_glass");
        example3.put("replacement", "minecraft:glass");
        exampleList.add(example3);
        
        return exampleList;
    }
    
    /**
     * Adds a new block replacement to the configuration and saves it
     */
    public static boolean addBlockReplacement(String originalId, String replacementId) {
        if (originalId == null || replacementId == null) {
            return false;
        }
        
        // Add to the in-memory map
        blockReplacements.put(originalId, replacementId);
        
        // Now save the updated config
        return saveReplacements();
    }
    
    /**
     * Saves the current block replacements to the config file
     */
    public static boolean saveReplacements() {
        if (configFile == null) {
            LOGGER.error("Cannot save replacements - config file not initialized");
            return false;
        }
        
        try {
            // Convert the map to the list format used in the JSON
            List<Map<String, String>> replacementsList = new ArrayList<>();
            
            for (Map.Entry<String, String> entry : blockReplacements.entrySet()) {
                Map<String, String> jsonEntry = new HashMap<>();
                jsonEntry.put("original", entry.getKey());
                jsonEntry.put("replacement", entry.getValue());
                replacementsList.add(jsonEntry);
            }
            
            // Write to the config file
            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(replacementsList, writer);
                LOGGER.info("Saved {} block replacements to {}", replacementsList.size(), configFile.getAbsolutePath());
                return true;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save block replacements: {}", e.getMessage(), e);
            return false;
        }
    }
}

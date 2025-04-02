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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * Handles scanning and logging of all modded items and blocks in the game
 * Creates YAML files containing registry names organized by mod ID
 */
public class ModdedItemScanner {
    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    private static final String ITEMS_FILENAME = "modded_items.yml";
    private static final String BLOCKS_FILENAME = "modded_blocks.yml";
    private static final String ALL_REGISTRIES_FILENAME = "all_modded_registries.yml";
    
    // Cache for registry entries
    private static final Set<String> moddedItems = new HashSet<>();
    private static final Set<String> moddedBlocks = new HashSet<>();
    private static final Map<String, Set<String>> moddedItemsByMod = new HashMap<>();
    private static final Map<String, Set<String>> moddedBlocksByMod = new HashMap<>();
    
    /**
     * Scans all modded items and blocks and saves them to YAML files
     * @return true if scan was successful, false otherwise
     */
    public static boolean scanAndSaveRegistries() {
        LOGGER.info("Starting scan of all modded registries...");
        System.out.println("[BlockScanner] Starting scan of all modded registries...");
        
        long startTime = System.currentTimeMillis();
        
        // Clear previous cache
        moddedItems.clear();
        moddedBlocks.clear();
        moddedItemsByMod.clear();
        moddedBlocksByMod.clear();
        
        // Scan items
        scanModdedItems();
        
        // Scan blocks
        scanModdedBlocks();
        
        // Save to files
        boolean itemsSaved = saveItemsToYaml();
        boolean blocksSaved = saveBlocksToYaml();
        boolean allRegistriesSaved = saveAllRegistriesToYaml();
        
        long duration = System.currentTimeMillis() - startTime;
        
        LOGGER.info("Registry scan complete in {} ms. Found {} modded items and {} modded blocks.",
                duration, moddedItems.size(), moddedBlocks.size());
        System.out.println(String.format("[BlockScanner] Registry scan complete in %d ms", duration));
        System.out.println(String.format("[BlockScanner] Found %d modded items and %d modded blocks", 
                moddedItems.size(), moddedBlocks.size()));
        
        return itemsSaved && blocksSaved && allRegistriesSaved;
    }
    
    /**
     * Scans the Item registry for all modded items
     */
    private static void scanModdedItems() {
        LOGGER.info("Scanning modded items...");
        System.out.println("[BlockScanner] Scanning modded items...");
        
        IForgeRegistry<Item> itemRegistry = ForgeRegistries.ITEMS;
        
        // Loop through all items in the registry
        for (Item item : itemRegistry.getValues()) {
            ResourceLocation registryName = itemRegistry.getKey(item);
            
            if (registryName != null && !registryName.getNamespace().equals("minecraft")) {
                String itemId = registryName.toString();
                String modId = registryName.getNamespace();
                
                // Add to global set
                moddedItems.add(itemId);
                
                // Add to mod-specific set
                if (!moddedItemsByMod.containsKey(modId)) {
                    moddedItemsByMod.put(modId, new HashSet<>());
                }
                moddedItemsByMod.get(modId).add(itemId);
            }
        }
        
        LOGGER.info("Found {} modded items from {} different mods", 
                moddedItems.size(), moddedItemsByMod.size());
    }
    
    /**
     * Scans the Block registry for all modded blocks
     */
    private static void scanModdedBlocks() {
        LOGGER.info("Scanning modded blocks...");
        System.out.println("[BlockScanner] Scanning modded blocks...");
        
        IForgeRegistry<Block> blockRegistry = ForgeRegistries.BLOCKS;
        
        // Loop through all blocks in the registry
        for (Block block : blockRegistry.getValues()) {
            ResourceLocation registryName = blockRegistry.getKey(block);
            
            if (registryName != null && !registryName.getNamespace().equals("minecraft")) {
                String blockId = registryName.toString();
                String modId = registryName.getNamespace();
                
                // Add to global set
                moddedBlocks.add(blockId);
                
                // Add to mod-specific set
                if (!moddedBlocksByMod.containsKey(modId)) {
                    moddedBlocksByMod.put(modId, new HashSet<>());
                }
                moddedBlocksByMod.get(modId).add(blockId);
            }
        }
        
        LOGGER.info("Found {} modded blocks from {} different mods", 
                moddedBlocks.size(), moddedBlocksByMod.size());
    }
    
    /**
     * Saves modded items to a YAML file
     */
    private static boolean saveItemsToYaml() {
        if (moddedItems.isEmpty()) {
            LOGGER.warn("No modded items to save");
            return false;
        }
        
        LOGGER.info("Saving modded items to YAML...");
        File outputFile = getOutputFile(ITEMS_FILENAME);
        
        try {
            // Create YAML structure
            Map<String, Object> yamlData = new TreeMap<>(); // TreeMap for alphabetical ordering
            
            // Root elements
            yamlData.put("totalItems", moddedItems.size());
            yamlData.put("totalMods", moddedItemsByMod.size());
            
            // Items by mod
            Map<String, Object> itemsByMod = new TreeMap<>();
            for (Map.Entry<String, Set<String>> entry : moddedItemsByMod.entrySet()) {
                String modId = entry.getKey();
                Set<String> items = entry.getValue();
                
                Map<String, Object> modData = new HashMap<>();
                modData.put("count", items.size());
                modData.put("items", new ArrayList<>(items));
                
                itemsByMod.put(modId, modData);
            }
            yamlData.put("mods", itemsByMod);
            
            // Write YAML to file
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write("# BlockScanner - Modded Items Registry\n");
                writer.write("# Contains all modded items registered in the game\n\n");
                yaml.dump(yamlData, writer);
            }
            
            LOGGER.info("Modded items saved to {}", outputFile.getAbsolutePath());
            System.out.println("[BlockScanner] Modded items saved to " + outputFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save modded items to YAML: {}", e.getMessage(), e);
            System.err.println("[BlockScanner] Failed to save modded items: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Saves modded blocks to a YAML file
     */
    private static boolean saveBlocksToYaml() {
        if (moddedBlocks.isEmpty()) {
            LOGGER.warn("No modded blocks to save");
            return false;
        }
        
        LOGGER.info("Saving modded blocks to YAML...");
        File outputFile = getOutputFile(BLOCKS_FILENAME);
        
        try {
            // Create YAML structure
            Map<String, Object> yamlData = new TreeMap<>(); // TreeMap for alphabetical ordering
            
            // Root elements
            yamlData.put("totalBlocks", moddedBlocks.size());
            yamlData.put("totalMods", moddedBlocksByMod.size());
            
            // Blocks by mod
            Map<String, Object> blocksByMod = new TreeMap<>();
            for (Map.Entry<String, Set<String>> entry : moddedBlocksByMod.entrySet()) {
                String modId = entry.getKey();
                Set<String> blocks = entry.getValue();
                
                Map<String, Object> modData = new HashMap<>();
                modData.put("count", blocks.size());
                modData.put("blocks", new ArrayList<>(blocks));
                
                blocksByMod.put(modId, modData);
            }
            yamlData.put("mods", blocksByMod);
            
            // Write YAML to file
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write("# BlockScanner - Modded Blocks Registry\n");
                writer.write("# Contains all modded blocks registered in the game\n\n");
                yaml.dump(yamlData, writer);
            }
            
            LOGGER.info("Modded blocks saved to {}", outputFile.getAbsolutePath());
            System.out.println("[BlockScanner] Modded blocks saved to " + outputFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save modded blocks to YAML: {}", e.getMessage(), e);
            System.err.println("[BlockScanner] Failed to save modded blocks: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Saves all modded registries to a combined YAML file
     */
    private static boolean saveAllRegistriesToYaml() {
        LOGGER.info("Saving all modded registries to YAML...");
        File outputFile = getOutputFile(ALL_REGISTRIES_FILENAME);
        
        try {
            // Create YAML structure
            Map<String, Object> yamlData = new TreeMap<>(); // TreeMap for alphabetical ordering
            
            // Root elements
            yamlData.put("totalItems", moddedItems.size());
            yamlData.put("totalBlocks", moddedBlocks.size());
            
            // Combined mod list (union of item and block mods)
            Set<String> allMods = new HashSet<>(moddedItemsByMod.keySet());
            allMods.addAll(moddedBlocksByMod.keySet());
            yamlData.put("totalMods", allMods.size());
            
            // Generate combined registry by mod
            Map<String, Object> registriesByMod = new TreeMap<>();
            for (String modId : allMods) {
                Map<String, Object> modData = new HashMap<>();
                
                // Add items for this mod
                Set<String> items = moddedItemsByMod.getOrDefault(modId, new HashSet<>());
                modData.put("itemCount", items.size());
                modData.put("items", new ArrayList<>(items));
                
                // Add blocks for this mod
                Set<String> blocks = moddedBlocksByMod.getOrDefault(modId, new HashSet<>());
                modData.put("blockCount", blocks.size());
                modData.put("blocks", new ArrayList<>(blocks));
                
                // Total entries for this mod
                modData.put("totalEntries", items.size() + blocks.size());
                
                registriesByMod.put(modId, modData);
            }
            yamlData.put("mods", registriesByMod);
            
            // Write YAML to file
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write("# BlockScanner - All Modded Registries\n");
                writer.write("# Contains all modded items and blocks registered in the game\n\n");
                yaml.dump(yamlData, writer);
            }
            
            LOGGER.info("All modded registries saved to {}", outputFile.getAbsolutePath());
            System.out.println("[BlockScanner] All modded registries saved to " + outputFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save all modded registries to YAML: {}", e.getMessage(), e);
            System.err.println("[BlockScanner] Failed to save all modded registries: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets the registry entries for a specific mod
     */
    public static Map<String, List<String>> getEntriesForMod(String modId) {
        Map<String, List<String>> result = new HashMap<>();
        
        // Get items for this mod
        Set<String> items = moddedItemsByMod.getOrDefault(modId, new HashSet<>());
        result.put("items", new ArrayList<>(items));
        
        // Get blocks for this mod
        Set<String> blocks = moddedBlocksByMod.getOrDefault(modId, new HashSet<>());
        result.put("blocks", new ArrayList<>(blocks));
        
        return result;
    }
    
    /**
     * Gets list of all mod IDs that have registered items or blocks
     */
    public static List<String> getAllModIds() {
        Set<String> allMods = new HashSet<>(moddedItemsByMod.keySet());
        allMods.addAll(moddedBlocksByMod.keySet());
        return new ArrayList<>(allMods);
    }
    
    /**
     * Gets the output file in the correct directory
     */
    private static File getOutputFile(String filename) {
        // Use the blockscanner config directory for YAML files
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("blockscanner");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create config directory: {}", e.getMessage(), e);
        }
        
        return configDir.resolve(filename).toFile();
    }
    
    /**
     * Gets all modded blocks (sorted)
     */
    public static List<String> getAllModdedBlocks() {
        return moddedBlocks.stream().sorted().collect(Collectors.toList());
    }
    
    /**
     * Gets all modded items (sorted)
     */
    public static List<String> getAllModdedItems() {
        return moddedItems.stream().sorted().collect(Collectors.toList());
    }
}

package com.nexusnode.blockscanner;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "blockscanner", bus = Mod.EventBusSubscriber.Bus.MOD)
public class ConfigLoader {

    private static final Logger LOGGER = LogManager.getLogger("BlockScanner");
    public static Map<String, String> blockReplacements = new HashMap<>();

    public static void init() {
        loadBlockReplacements();
    }

    public static void loadBlockReplacements() {
        // First check the configs directory
        String configFilePath = "./configs/block_replacements.json";
        File configDir = new File("./configs");
        if (!configDir.exists()) {
            configDir.mkdirs();
            LOGGER.info("Created configs directory");
        }
        
        // If config doesn't exist in configs, check resources
        if (!Files.exists(Paths.get(configFilePath))) {
            File resourceFile = new File("./src/main/resources/config/block_replacements.json");
            if (resourceFile.exists()) {
                // Try to copy the default config to the configs directory
                try {
                    Files.copy(resourceFile.toPath(), new File(configFilePath).toPath());
                    LOGGER.info("Copied default config to configs directory");
                } catch (IOException e) {
                    LOGGER.error("Failed to copy default config: " + e.getMessage());
                }
            } else {
                LOGGER.info("Default config not found at " + resourceFile.getAbsolutePath());
                // Try to find it in the classpath (for when running from JAR)
                try {
                    var classLoader = ConfigLoader.class.getClassLoader();
                    var resource = classLoader.getResourceAsStream("config/block_replacements.json");
                    if (resource != null) {
                        Files.copy(resource, new File(configFilePath).toPath());
                        LOGGER.info("Extracted default config from JAR to configs directory");
                    } else {
                        LOGGER.error("Could not find default config in JAR");
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to extract default config from JAR: " + e.getMessage());
                }
            }
        }
        
        // Now try to load the config, falling back to the resource path if needed
        Gson gson = new Gson();
        Type listType = new TypeToken<List<Map<String, String>>>(){}.getType();

        try {
            File file = new File(configFilePath);
            if (file.exists()) {
                List<Map<String, String>> replacementsList = gson.fromJson(new FileReader(file), listType);
                blockReplacements = new HashMap<>();
                
                for (Map<String, String> entry : replacementsList) {
                    String original = entry.get("original");
                    String replacement = entry.get("replacement");
                    if (original != null && replacement != null) {
                        blockReplacements.put(original, replacement);
                    } else {
                        LOGGER.error("Invalid entry in block replacements: " + entry);
                    }
                }

                LOGGER.info("Loaded " + blockReplacements.size() + " block replacements from " + configFilePath);
            } else {
                blockReplacements = new HashMap<>();
                LOGGER.error("Config file not found: " + configFilePath);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load block replacements from JSON: " + e.getMessage(), e);
        }
    }
}

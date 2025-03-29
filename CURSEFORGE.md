[h1]BlockScanner[/h1]

BlockScanner is a powerful Minecraft Forge utility mod designed to solve a critical problem faced by modded Minecraft players: migrating worlds between mod packs or dealing with removed mods.

[h2]What Does BlockScanner Do?[/h2]

When removing mods from a world or upgrading to a newer mod pack, you often encounter issues with missing blocks that can corrupt chunks or cause errors. BlockScanner provides a solution by:

1. Scanning chunks for modded blocks
2. Replacing them with configurable alternatives 
3. Doing this automatically or on-demand through commands

[h2]Key Features[/h2]

[list]
[*][b]Block Scanning[/b]: Detects all modded blocks in a specified radius
[*][b]Configurable Replacements[/b]: Define custom block replacements via JSON configuration
[*][b]Server Commands[/b]: Full suite of admin commands for managing the scanning process
[*][b]Auto-Scanning[/b]: Can automatically scan around players and newly loaded chunks
[*][b]Safe Block Replacement[/b]: Multiple fallback mechanisms to prevent crashes during replacement
[*][b]Detailed Logging[/b]: Complete logs of all detected and replaced blocks
[*][b]Visual Progress Tracking[/b]: Real-time progress bars and percentage indicators for long operations
[*][b]Multi-Threaded Processing[/b]: Background processing to prevent server lag during replacements
[/list]

[h2]Commands[/h2]

[list]
[*][b]/blockscanner scan [radius][/b] - Scan for modded blocks in specified radius
[*][b]/blockscanner replace <from> <to> [radius][/b] - Replace specific blocks in radius
[*][b]/blockscanner reload[/b] - Reload the configuration file
[*][b]/blockscanner addblock <from> <to>[/b] - Add a new block replacement to the config file
[*][b]/blockscanner replacechunk <pos>[/b] - Replace blocks in a specific chunk
[*][b]/blockscanner activate [radius][/b] - Start automatic replacement in radius
[*][b]/blockscanner deactivate[/b] - Stop automatic replacement
[*][b]/blockscanner status[/b] - Check current status of replacements
[*][b]/blockscanner processall[/b] - Process all loaded chunks
[*][b]/blockscanner autoscan on|off|status[/b] - Control automatic scanning around players
[*][b]/blockscanner listscanned[/b] - List all scanned modded blocks
[*][b]/blockscanner generateconfig[/b] - Generate a suggested replacement configuration based on scanned blocks
[/list]

[h2]Configuration[/h2]

BlockScanner uses two separate configuration files:

1. [b]Block Replacements[/b]: Located at [code]config/blockscanner/block_replacements.json[/code]
2. [b]Scanned Blocks Tracker[/b]: Located at [code]config/blockscanner/scanned_blocks.json[/code]

[h3]Block Replacements[/h3]

This configuration defines which blocks should be replaced with what. The format is:

[code]
[
    {
        "original": "modid:block_to_replace",
        "replacement": "modid:replacement_block"
    },
    ...
]
[/code]

A default configuration is provided in the mod, but you can customize it to fit your needs.

[h3]Scanned Blocks Tracker[/h3]

This file maintains a list of all modded blocks that have been discovered during scanning. It's automatically updated when new blocks are found and can be used to generate suggested replacement configurations.

[h3]Adding New Block Replacements[/h3]

You can add new block replacements in four ways:

1. [b]Manually edit[/b] the [code]config/blockscanner/block_replacements.json[/code] file
2. [b]Use the in-game command[/b]: [code]/blockscanner addblock modid:blockname minecraft:replacement_block[/code]
3. [b]Scan and identify blocks[/b] with [code]/blockscanner scan 32[/code], then run [code]/blockscanner generateconfig[/code] to create a suggested replacement configuration
4. [b]Use the auto-generated config[/b]: After scanning, edit the [code]config/blockscanner/suggested_replacements.json[/code] file and rename it to [code]block_replacements.json[/code]

After making changes, run [code]/blockscanner reload[/code] to apply them without restarting the game.

[h2]Usage Examples[/h2]

[h3]Removing a Mod[/h3]

If you're removing a mod (e.g., "tconstruct"), add entries to replace its blocks with suitable alternatives:

[code]
{
    "original": "tconstruct:clear_glass",
    "replacement": "minecraft:glass"
}
[/code]

[h3]Upgrading a World[/h3]

When upgrading to a newer Minecraft version or mod pack, scan your world and generate a list of blocks that need replacement:

1. Install BlockScanner in your old world
2. Run [code]/blockscanner scan 128[/code] to identify modded blocks
3. Configure replacements in the JSON file
4. Run [code]/blockscanner activate 128[/code] to replace blocks in a 128-block radius

[h3]Server Administration[/h3]

For server admins managing large worlds:

1. Configure the block replacement JSON
2. Use [code]/blockscanner processall[/code] to process all loaded chunks
3. Enable auto-scanning with [code]/blockscanner autoscan on[/code] to handle newly loaded chunks

[h2]New Features[/h2]

[h3]Visual Progress Tracking[/h3]

BlockScanner now provides real-time progress information:

[list]
[*]Text-based progress bars in the console and server log
[*]Percentage completion indicators for long-running operations
[*]Regular status updates for block replacement operations
[*]Summary reports upon task completion
[/list]

Example progress bar:
[code][BlockScanner] Progress: [===========>         ] 56.7% | 123/217 chunks | 4526 blocks replaced total | 342 new blocks replaced[/code]

[h3]Auto-Scanning Around Players[/h3]

The mod can automatically scan around players at regular intervals:

[list]
[*]Enable with [code]/blockscanner autoscan on[/code]
[*]Disable with [code]/blockscanner autoscan off[/code]
[*]Check status with [code]/blockscanner autoscan status[/code]
[/list]

This is useful for servers where players are exploring and you want to ensure blocks are replaced as they discover new areas.

[h3]Improved Block Replacement[/h3]

The block replacement system now has multiple fallback mechanisms to prevent crashes:

1. First attempts normal block replacement (with updates)
2. If that fails, tries replacement without neighbor updates
3. As a last resort, attempts replacement with no updates at all

[h2]Installation[/h2]

1. Install Minecraft Forge for version 1.18.2
2. Download the BlockScanner mod JAR file
3. Place the JAR file in your Minecraft [code]mods[/code] folder
4. Start Minecraft and verify the mod is loaded
5. Customize the [code]configs/block_replacements.json[/code] file if needed

[h2]Compatibility[/h2]

[list]
[*]Minecraft: 1.18.2
[*]Forge: 40.x.x+
[/list]

[h2]Troubleshooting[/h2]

[h3]Auto-Scan Not Replacing Blocks[/h3]

If you've enabled auto-scanning with [code]/blockscanner autoscan on[/code] but aren't seeing blocks being replaced:

1. [b]Check Configuration File[/b]: Ensure your [code]config/blockscanner/block_replacements.json[/code] file exists and contains valid entries matching the blocks in your world.
2. [b]Manual Config Reload[/b]: Run [code]/blockscanner reload[/code] to force reload the configuration file.
3. [b]Verify Block IDs[/b]: Use [code]/blockscanner scan 32[/code] to see what modded blocks are actually in your area.
4. [b]Force Immediate Processing[/b]: Use [code]/blockscanner processall[/code] to immediately process all loaded chunks.

[h3]Command Not Working[/h3]

If the [code]/blockscanner[/code] command doesn't work:

1. [b]Check Installation[/b]: Ensure the mod JAR file is correctly placed in your [code]mods[/code] folder
2. [b]Verify Permissions[/b]: Make sure you are op on the server (or try with a single player world)
3. [b]Try Alias[/b]: Use [code]/bscan[/code] as an alternative command
4. [b]Restart Minecraft[/b]: Sometimes a full restart is needed after installing mods

[h2]Version History[/h2]

For a detailed list of changes in each version, please see the CHANGELOG.txt file.

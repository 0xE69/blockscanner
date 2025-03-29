# BlockScanner

BlockScanner is a powerful Minecraft Forge utility mod designed to solve a critical problem faced by modded Minecraft players: migrating worlds between mod packs or dealing with removed mods.

## What Does BlockScanner Do?

When removing mods from a world or upgrading to a newer mod pack, you often encounter issues with missing blocks that can corrupt chunks or cause errors. BlockScanner provides a solution by:

1. Scanning chunks for modded blocks
2. Replacing them with configurable alternatives 
3. Doing this automatically or on-demand through commands

## Key Features

- **Block Scanning**: Detects all modded blocks in a specified radius
- **Configurable Replacements**: Define custom block replacements via JSON configuration
- **Server Commands**: Full suite of admin commands for managing the scanning process
- **Auto-Scanning**: Can automatically scan around players and newly loaded chunks
- **Safe Block Replacement**: Multiple fallback mechanisms to prevent crashes during replacement
- **Detailed Logging**: Complete logs of all detected and replaced blocks

## Commands

- `/blockscanner scan [radius]` - Scan for modded blocks in specified radius
- `/blockscanner replace <from> <to> [radius]` - Replace specific blocks in radius
- `/blockscanner reload` - Reload the configuration file
- `/blockscanner addblock <from> <to>` - Add a new block replacement to the config file
- `/blockscanner replacechunk <pos>` - Replace blocks in a specific chunk
- `/blockscanner activate [radius]` - Start automatic replacement in radius
- `/blockscanner deactivate` - Stop automatic replacement
- `/blockscanner status` - Check current status of replacements
- `/blockscanner processall` - Process all loaded chunks
- `/blockscanner autoscan on|off|status` - Control automatic scanning around players
- `/blockscanner listscanned` - List all scanned modded blocks
- `/blockscanner generateconfig` - Generate a suggested replacement configuration based on scanned blocks

## Configuration

BlockScanner uses two separate configuration files:

1. **Block Replacements**: Located at `config/blockscanner/block_replacements.json`
2. **Scanned Blocks Tracker**: Located at `config/blockscanner/scanned_blocks.json`

### Block Replacements

This configuration defines which blocks should be replaced with what. The format is:

```json
[
    {
        "original": "modid:block_to_replace",
        "replacement": "modid:replacement_block"
    },
    ...
]
```

A default configuration is provided in the mod, but you can customize it to fit your needs.

### Scanned Blocks Tracker

This file maintains a list of all modded blocks that have been discovered during scanning. It's automatically updated when new blocks are found and can be used to generate suggested replacement configurations.

### Adding New Block Replacements

You can add new block replacements in four ways:

1. **Manually edit** the `config/blockscanner/block_replacements.json` file
2. **Use the in-game command**: `/blockscanner addblock modid:blockname minecraft:replacement_block`
3. **Scan and identify blocks** with `/blockscanner scan 32`, then run `/blockscanner generateconfig` to create a suggested replacement configuration
4. **Use the auto-generated config**: After scanning, edit the `config/blockscanner/suggested_replacements.json` file and rename it to `block_replacements.json`

After making changes, run `/blockscanner reload` to apply them without restarting the game.

## Usage Examples

### Removing a Mod

If you're removing a mod (e.g., "tconstruct"), add entries to replace its blocks with suitable alternatives:

```json
{
    "original": "tconstruct:clear_glass",
    "replacement": "minecraft:glass"
}
```

### Upgrading a World

When upgrading to a newer Minecraft version or mod pack, scan your world and generate a list of blocks that need replacement:

1. Install BlockScanner in your old world
2. Run `/blockscanner scan 128` to identify modded blocks
3. Configure replacements in the JSON file
4. Run `/blockscanner activate 128` to replace blocks in a 128-block radius

### Server Administration

For server admins managing large worlds:

1. Configure the block replacement JSON
2. Use `/blockscanner processall` to process all loaded chunks
3. Enable auto-scanning with `/blockscanner autoscan on` to handle newly loaded chunks

## New Features

### Auto-Scanning Around Players

The mod can now automatically scan around players at regular intervals:

- Enable with `/blockscanner autoscan on`
- Disable with `/blockscanner autoscan off`
- Check status with `/blockscanner autoscan status`

This is useful for servers where players are exploring and you want to ensure blocks are replaced as they discover new areas.

### Improved Block Replacement

The block replacement system now has multiple fallback mechanisms to prevent crashes:

1. First attempts normal block replacement (with updates)
2. If that fails, tries replacement without neighbor updates
3. As a last resort, attempts replacement with no updates at all

### Progress Reporting

BlockScanner now shows progress in the game chat, keeping players informed about:

- Scan start with total blocks to check
- Progress percentage during scan
- Completion summary with total blocks scanned and replaced

## Workflow Example

Here's a typical workflow for using BlockScanner to prepare a world for mod removal:

1. **Scan for modded blocks**:
   ```
   /blockscanner scan 128
   ```

2. **Review the scanned blocks**:
   ```
   /blockscanner listscanned
   ```

3. **Generate a suggested replacement configuration**:
   ```
   /blockscanner generateconfig
   ```

4. **Edit the generated configuration** to customize replacements

5. **Reload the configuration**:
   ```
   /blockscanner reload
   ```

6. **Enable automatic replacement around players**:
   ```
   /blockscanner autoscan on
   ```

7. **Process all loaded chunks**:
   ```
   /blockscanner processall
   ```

## Installation

1. Install Minecraft Forge for version 1.18.2
2. Download the BlockScanner mod JAR file
3. Place the JAR file in your Minecraft `mods` folder
4. Start Minecraft and verify the mod is loaded
5. Customize the `configs/block_replacements.json` file if needed

## Compatibility

- Minecraft: 1.18.2
- Forge: 40.x.x+

## Potential Use Cases

- Server administrators migrating between mod packs
- Players removing performance-heavy mods
- Fixing worlds where certain mods have been discontinued
- Preparing worlds for sharing with friends who may not have the same mods

## Logging

BlockScanner creates logs in the `logs/blockscanner_log.txt` file, which contains information about all discovered modded blocks and replacements performed.

## Troubleshooting

### Auto-Scan Not Replacing Blocks

If you've enabled auto-scanning with `/blockscanner autoscan on` but aren't seeing blocks being replaced:

1. **Check Configuration File**: Ensure your `config/blockscanner/block_replacements.json` file exists and contains valid entries matching the blocks in your world.

2. **Manual Config Reload**: Run `/blockscanner reload` to force reload the configuration file.

3. **Verify Block IDs**: Use `/blockscanner scan 32` to see what modded blocks are actually in your area. This will list their exact IDs which you can add to your config.

4. **Verify Config Format**: Make sure your config file follows the exact format:
   ```json
   [
       {
           "original": "modid:block_to_replace",
           "replacement": "minecraft:replacement_block"
       }
   ]
   ```

5. **Increase Log Level**: In your `server.properties` file, set `log-level=INFO` to get more detailed logs.

6. **Force Immediate Processing**: Use `/blockscanner processall` to immediately process all loaded chunks.

7. **Verify Block Locations**: Some blocks may be in unloaded chunks. Try moving around the area to load more chunks.

### Command Order for Best Results

When setting up for the first time:
1. Run `/blockscanner scan 32` to identify modded blocks
2. Edit your config file with the discovered blocks
3. Run `/blockscanner reload` to load the updated config
4. Run `/blockscanner autoscan on` to start automatic scanning
5. Run `/blockscanner processall` to immediately process all loaded chunks

### Command Not Working

If the `/blockscanner` command doesn't work:

1. **Check Installation**: Ensure the mod JAR file is correctly placed in your `mods` folder
2. **Verify Permissions**: Make sure you are op on the server (or try with a single player world)
3. **Check Logs**: Look in the `logs/latest.log` file for any error messages related to BlockScanner
4. **Try Alias**: Use `/bscan` as an alternative command
5. **Restart Minecraft**: Sometimes a full restart is needed after installing mods
6. **Command Conflicts**: Check if you have any other mods that might conflict with command registration

### Forge Version

Make sure you're using Minecraft Forge 40.x.x+ for Minecraft 1.18.2. Earlier or later versions may not be compatible.

## License

This mod is distributed under the terms of the license in the LICENSE.txt file.

## Support

For issues, suggestions, or questions, please use the GitHub issue tracker.

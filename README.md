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
- **Visual Progress Tracking**: Real-time progress bars and percentage indicators for long operations
- **Multi-Threaded Processing**: Background processing to prevent server lag during replacements

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

### Visual Progress Tracking

BlockScanner now provides real-time progress information:

- Text-based progress bars in the console and server log
- Percentage completion indicators for long-running operations
- Regular status updates for block replacement operations
- Summary reports upon task completion

Example progress bar:

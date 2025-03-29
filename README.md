# BlockScanner

BlockScanner is a powerful Minecraft Forge utility mod designed to solve a critical problem faced by modded Minecraft players: migrating worlds between mod packs or dealing with removed mods.

## What Does BlockScanner Do?

BlockScanner scans your Minecraft world for blocks from specific mods and can automatically replace them with vanilla or other modded blocks based on a configurable mapping. This helps prevent world corruption and missing blocks when:

- Upgrading a world to a new mod pack
- Removing mods from an existing world
- Fixing worlds where certain mods are no longer available or maintained

## Key Features

- **Automatic Block Replacement**: Automatically replaces blocks according to your configuration
- **Block Scanning**: Identifies and logs all modded blocks in your world
- **Chunk Processing**: Process specific chunks or regions of your world
- **Configurable Replacements**: Easily define block replacements in a JSON configuration file
- **Server Commands**: Extensive command system for server administrators
- **Client-Side Support**: Works in both singleplayer and multiplayer environments

## Commands

BlockScanner provides a comprehensive set of commands (requires operator permission level 2):

- `/blockscanner scan [radius]` - Scans for modded blocks in the specified radius
- `/blockscanner replace <from> <to> [radius]` - Manually replace blocks of one type with another
- `/blockscanner reload` - Reload the block replacements configuration file
- `/blockscanner replacechunk <pos>` - Process a specific chunk at the given position
- `/blockscanner activate [radius]` - Start automatic block replacement in the specified radius
- `/blockscanner deactivate` - Stop any active block replacement process
- `/blockscanner status` - Check the status of the block replacement process
- `/blockscanner processall` - Process all currently loaded chunks

## Configuration

BlockScanner uses a JSON configuration file located at `./configs/block_replacements.json`. The format is:

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

## License

All Rights Reserved

## Support

For issues, suggestions, or questions, please use the GitHub issue tracker.

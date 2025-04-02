# BlockScanner

A Minecraft Forge mod for scanning and replacing modded blocks to help with world maintenance and mod transitions.

## Overview

BlockScanner provides tools to scan Minecraft worlds for blocks from specific mods, and replace them with blocks from other mods or vanilla Minecraft. This is especially useful when:

- Uninstalling mods from an existing world
- Upgrading worlds between Minecraft versions
- Fixing broken/missing blocks after mod updates

## Features

- **Block Scanning**: Scans for modded blocks around players or in specified areas
- **Automatic Replacement**: Configurable mappings to replace blocks from one mod with another
- **Property Preservation**: Maintains block rotation, direction, and other properties during replacement
- **NBT Data Preservation**: Keeps inventory contents and other block entity data when replacing containers and machines
- **Bulk Operations**: Process entire areas or maps with a single command
- **Registry Scanning**: Identifies all modded blocks and items in your current installation

## Commands

BlockScanner provides several commands, all accessible via `/blockscanner` or the shorthand `/bscan`.

### Basic Commands

- `/bscan scan [radius]` - Scan for modded blocks around you
- `/bscan replace <fromBlock> <toBlock> [radius]` - Replace specific blocks
- `/bscan reload` - Reload configuration files
- `/bscan addblock <fromBlock> <toBlock>` - Add a new block replacement rule
- `/bscan listscanned` - List all scanned modded blocks
- `/bscan generateconfig` - Generate a suggested replacement configuration

### Area Processing

- `/bscan activate [radius]` - Activate block replacements in an area
- `/bscan processall` - Process all loaded chunks
- `/bscan replacechunk <pos>` - Replace blocks in a specific chunk
- `/bscan status` - Check replacement status

### Map Operations

- `/bscan scanmap <x1> <z1> <x2> <z2> [renderDistance]` - Scan a rectangular area of the map
- `/bscan replacemap <x1> <z1> <x2> <z2> [renderDistance]` - Replace blocks in a map area
- `/bscan stopmap` - Stop an in-progress map scan

### Auto Scanning

- `/bscan autoscan on|off` - Toggle automatic scanning around players
- `/bscan autoscan status` - Check auto-scanning status

### Registry Commands

- `/bscan registryscan` - Scan and save all modded registry entries
- `/bscan listmods` - List all mods with registry entries
- `/bscan listitems [modid]` - List modded items (optionally filtered by mod)
- `/bscan listblocks [modid]` - List modded blocks (optionally filtered by mod)

## Configuration

BlockScanner stores its configuration in the `config/blockscanner/` directory:

- `block_replacements.json` - Main configuration file for block replacement rules
- `suggested_replacements.json` - Generated suggestions based on scanned blocks
- `modded_blocks.yml` - List of discovered modded blocks
- `modded_items.yml` - List of discovered modded items

### Block Replacement Format

The block replacement configuration uses a simple JSON format:

```json
{
  "modid:block_name": "minecraft:replacement_block",
  "anothermod:some_block": "minecraft:stone",
  "problematic:machine_block": "minecraft:furnace"
}
```

## Automatic Replacement

When block replacement is active, the mod will:

1. Preserve block orientation (e.g., a north-facing furnace remains north-facing)
2. Maintain block states (e.g., slabs placed on the top half stay on the top half)
3. Copy NBT data when possible (e.g., inventory contents in containers)

## Advanced Usage

### Fixing Missing Blocks

When a mod is uninstalled and leaves missing blocks, run these commands:

1. `/bscan registryscan` - Scan all available blocks in current installation
2. `/bscan scan 128` - Scan around the player for modded blocks
3. `/bscan generateconfig` - Create suggested replacements
4. Edit the suggested_replacements.json file to adjust replacements 
5. Rename to block_replacements.json or copy the content to the existing file
6. `/bscan reload` - Reload the configuration
7. `/bscan activate 128` - Activate replacements

### Map Processing

For large servers or maps, use the map commands:

```
/bscan replacemap -1000 -1000 1000 1000 256
```

This will systematically teleport to positions covering the specified area, 
activating replacements at each location.

## Performance Considerations

- Processing large areas can be resource-intensive
- The mod uses multi-threading where possible to improve performance
- For very large operations, consider increasing Java heap allocation

## Compatibility

This mod is compatible with:
- Minecraft Forge 1.18.2
- Single player and multiplayer servers
- Most other Forge mods

## License

Crafting Dead Copyright (C) 2022 NexusNode LTD
This mod is available under the terms of the Non-Commercial Software License Agreement.

## Credits

Developed by NexusNode team

# BlockScanner

A Minecraft Forge mod for scanning and logging modded blocks in your world.

## Overview

BlockScanner is a lightweight utility mod that scans the blocks around your player and logs any non-vanilla (modded) blocks it finds. This is particularly useful for:

- Identifying blocks from unknown mods in modpacks
- Creating a reference list of all modded blocks in your world
- Debugging modpack configurations
- Finding rare or hidden modded blocks

## Features

- **Automated Scanning**: Periodically scans blocks around your player while you explore
- **Modded Block Identification**: Detects and logs only non-vanilla blocks
- **Deduplication**: Each unique block is logged only once
- **Configurable**: Can adjust scan interval and radius as needed
- **Client-Side Only**: Works on any server without requiring server-side installation
- **Performance Friendly**: Only scans loaded chunks with configurable frequency

## Installation

1. Install Minecraft Forge for version 1.18.2
2. Download the latest BlockScanner release JAR file
3. Place the JAR file in your Minecraft mods folder
4. Launch Minecraft with the Forge profile

## Usage

The mod works automatically in the background:

1. Start Minecraft with the mod installed
2. Join any world or server
3. Explore the world - BlockScanner will automatically detect modded blocks
4. View the log file at `./logs/blockscanner_log.txt` to see all discovered blocks

## Configuration

Default settings:
- **Scan Radius**: 64 blocks in each direction around the player
- **Scan Interval**: Every 40 ticks (approximately 2 seconds)

To modify these settings, edit the values in the source code and build a custom version.

## Building From Source

Prerequisites:
- JDK 8 or higher
- Gradle

Steps:
1. Clone this repository
2. Run `./gradlew build`
3. Find the compiled JAR in `build/libs/`

## Compatibility

- Minecraft: 1.18.2
- Forge: 40.3.0+
- Works with any modpack for the matching version

## License

All Rights Reserved

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests for:
- Bug fixes
- Performance improvements
- New features
- Documentation improvements

## Credits

Developed for the Minecraft modding community to make modpack exploration easier.

---

*Note: This mod is not affiliated with Mojang Studios or Microsoft.*

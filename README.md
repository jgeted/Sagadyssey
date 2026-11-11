# Sagadyssey (Epic Odyssey)

A medieval-themed Minecraft 1.21.11 expansion mod built with NeoForge.

> **Current Phase:** Playable social-simulation alpha
> **GitHub:** https://github.com/jgeted/Sagadyssey

---

## Download & Installation (Players)

### Prerequisites

1. Install **Minecraft 1.21.11** (Java Edition)
2. Install **NeoForge 21.11.48** (download from [NeoForge website](https://neoforged.net/))

### Installation

1. Download the `.jar` from the [Minecraft 1.21.11 release](https://github.com/jgeted/Sagadyssey/releases/tag/dev-v1.9.0-mc1.21.11)
2. Place the `.jar` file into Minecraft's `mods` folder:
   - **Windows:** `%APPDATA%\.minecraft\mods`
   - **macOS:** `~/Library/Application Support/minecraft/mods`
   - **Linux:** `~/.minecraft/mods`
3. Launch the NeoForge 1.21.11 client and create a new world.

The [Minecraft 1.21.1 development release](https://github.com/jgeted/Sagadyssey/releases/tag/dev-v1.9.0-20261105) remains available for existing installations.

---

## Build & Development (Developers)

### Requirements

| Tool | Version |
|------|---------|
| JDK | **21** (Eclipse Adoptium / Temurin recommended) |
| Minecraft | 1.21.11 |
| NeoForge | 21.11.48 |
| Gradle | Bundled with project (`gradlew` / `gradlew.bat`) |

### Clone & Build

```bash
# Clone the repository
git clone https://github.com/jgeted/Sagadyssey.git
cd Sagadyssey

# Build
./gradlew build

# Output at build/libs/sagadyssey-<version>.jar
```

### Run the Client

On Windows, double-click **Start-Sagadyssey.cmd** in the project folder. It starts
Minecraft with the current Sagadyssey code and automatically selects an installed
Temurin Java 21 when available. Keep the launcher window open while playing.
Worlds are stored in `run/saves`; create a Creative, cheats-enabled superflat world
from the single-player menu for phase 3 testing.

```bash
./gradlew runClient
```

Minecraft will launch automatically after building, with the mod loaded.

### In-game regression tests

```bash
./gradlew runGameTestServer -Pphase9GameTestRun=mc12111-core
```

Use a fresh run label for each GameTest launch. For a restart scenario, pass
`-PgameTestRestartFrom=<stopped-run-label>` and a separate
`-Pphase9GameTestRun=<new-run-label>` to load a copy of that test world.

---

## Publishing a Release (Maintainers)

### Via GitHub Release

```bash
# 1. Build
./gradlew build

# 2. Tag and push (replace with the version from gradle.properties)
git tag v1.9.0
git push origin v1.9.0

# 3. Create the release on GitHub:
#    - Go to https://github.com/jgeted/Sagadyssey/releases
#    - Click "Create a new release"
#    - Select the tag you just pushed
#    - Upload the .jar from build/libs/
#    - Write release notes and publish
```

You can also download build artifacts from the Actions page for each commit (auto-uploaded).

---

## Project Structure

| Module | Description | Status |
|--------|-------------|--------|
| **Core** | Networking, config, research/skill tree | In progress |
| **NPC** | Recruitment, professions, group command, combat, logistics | Playable alpha |
| **Society** | Characters, settlements, politics, economy, history, world events | Playable alpha |
| **Scenarios** | Command-deployed fixed small worlds for superflat maps | Playable alpha |
| **Structure** | World-generated medieval buildings and settlement registration | In progress |
| **Vehicle** | NPC mounts and convoy movement | In progress |

Fixed superflat scenario commands, revision migration and acceptance checks are documented in
[docs/fixed-world-scenario.md](docs/fixed-world-scenario.md).

---

## License

All Rights Reserved

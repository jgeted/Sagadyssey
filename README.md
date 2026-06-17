# Sagadyssey (Epic Odyssey)

A medieval-themed Minecraft 1.21.1 expansion mod built with NeoForge.

> **Current Phase:** Micro-mod validation (one block + one GUI)
> **GitHub:** https://github.com/jgeted/Sagadyssey

---

## Download & Installation (Players)

### Prerequisites

1. Install **Minecraft 1.21.1** (Java Edition)
2. Install **NeoForge 21.1.233** (download from [NeoForge website](https://neoforged.net/))

### Installation

1. Download the latest `.jar` from the [Releases page](https://github.com/jgeted/Sagadyssey/releases)
2. Place the `.jar` file into Minecraft's `mods` folder:
   - **Windows:** `%APPDATA%\.minecraft\mods`
   - **macOS:** `~/Library/Application Support/minecraft/mods`
   - **Linux:** `~/.minecraft/mods`
3. Launch the NeoForge 1.21.1 client

### No Release Available?

If no release has been published yet, you can get a build from GitHub Actions:

1. Open the [Actions page](https://github.com/jgeted/Sagadyssey/actions)
2. Click the latest successful build (green checkmark)
3. Download the `.jar` from the **Artifacts** section at the bottom of the page

---

## Build & Development (Developers)

### Requirements

| Tool | Version |
|------|---------|
| JDK | **21** (Eclipse Adoptium / Temurin recommended) |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233 |
| Gradle | Bundled with project (`gradlew` / `gradlew.bat`) |

### Clone & Build

```bash
# Clone the repository
git clone https://github.com/jgeted/Sagadyssey.git
cd Sagadyssey

# Build
./gradlew build

# Output at build/libs/sagadyssey-1.0.0.jar
```

### Run the Client

```bash
./gradlew runClient
```

Minecraft will launch automatically after building, with the mod loaded.

### Network Setup (Mainland China)

If `maven.neoforged.net` is inaccessible (SSL handshake failure / connection reset), refer to `CLAUDE.md` for proxy configuration instructions.

---

## Publishing a Release (Maintainers)

### Via GitHub Release

```bash
# 1. Build
./gradlew build

# 2. Tag and push
git tag v1.0.0
git push origin v1.0.0

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
| **NPC** | Medieval NPCs, recruitment, jobs, factions | Planned |
| **Structure** | World-generated camps/settlements, blueprint building | Planned |
| **Vehicle** | Enhanced horse/donkey/dog/boat AI | Planned |

---

## License

All Rights Reserved

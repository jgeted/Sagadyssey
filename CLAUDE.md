# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Sagadyssey**（史诗远行）— a medieval-themed expansion mod for Minecraft 1.21.1, built with NeoForge.

- **MOD_ID:** `sagadyssey`
- **Base package:** `com.jgeted.sagadyssey`
- **GitHub:** https://github.com/jgeted/Sagadyssey
- **Developers:** jgeted + T
- **Language:** Code in English, comments and git messages in Chinese

## Module Architecture

The mod has 4 modules built in order:

```
Core (v0.1.0) → NPC → Structure → Vehicle (Automation: TBD)
```

| Module | Purpose | Status |
|--------|---------|--------|
| **Core** | Network, config, research/skill tree system | Planned |
| **NPC** | Medieval NPCs (workers, soldiers, archers), recruitment, jobs, faction system | Planned |
| **Structure** | World-generated camps/settlements, buildable blueprints | Planned |
| **Vehicle** | Enhanced horse/donkey/dog/boat AI, possible equipment slots | Planned |

Current phase: **Pre-development** — environment setup and micro-mod (proving the build->run pipeline with one block + one GUI).

## Development Workflow

```bash
# Build and compile
./gradlew build

# Run Minecraft client (test in-game)
./gradlew runClient

# Run a single test
./gradlew test --tests "com.jgeted.sagadyssey.*"

# Full test suite
./gradlew test
```

Before committing: always run `./gradlew build` to verify compilation. For entity/NPC changes, also run `./gradlew runClient` and verify in-game.

## Code Conventions

### Naming
- **Classes:** PascalCase English (`NpcWorker`, `ResearchTracker`)
- **Methods:** camelCase English (`getResearchPoints()`, `isHostileTo()`)
- **Variables:** camelCase English, no cryptic abbreviations (`player` not `pl`)
- **Constants:** UPPER_SNAKE_CASE (`MAX_NPC_COUNT`)
- **Booleans:** prefix with `is`/`has`/`can`
- **Packages:** `com.jgeted.sagadyssey.{module}.{feature}`

### Conventions
- **Comments in Chinese** (Simplified), code identifiers in English
- Git commit messages in Chinese
- Indent with **4 spaces**, no tabs
- Braces on same line (K&R style)
- No `System.out.println()` — use the mod's `Logger`
- Class file order: package → imports (alphabetical, no wildcard) → class Javadoc → constants → fields → constructor → public methods → private methods

### File Organization

```
src/main/java/com/jgeted/sagadyssey/
├── core/
│   ├── SagadysseyMod.java       # @Mod main class
│   ├── config/
│   ├── network/
│   ├── research/
│   └── util/
├── npc/
│   ├── entity/
│   ├── ai/
│   ├── container/
│   ├── gui/
│   ├── network/
│   └── registry/
├── structure/
│   └── ...
└── vehicle/
    └── ...

src/main/resources/
├── META-INF/neoforge.mods.toml
└── assets/sagadyssey/
    ├── textures/
    ├── models/
    └── lang/
        ├── en_us.json
        └── zh_cn.json
```

Each module is a separate package — do not dump everything in root.

## Registration Naming

- Blocks: `sagadyssey:campfire_block`
- Items: `sagadyssey:blueprint_item`
- Entities: `sagadyssey:npc_worker`

All lowercase with underscores.

## Key Design Decisions (from project planning)

1. **No heavy AW2-style framework** — no 37-packet network layer, no .aws parser, no complex faction reputation
2. **NPCs** — brand-new entity classes (6-8 types), not based on vanilla mobs. Spawn in villages + wilderness. Recruited by paying emeralds. Job switching via GUI or item assignment
3. **Population control** — dual scheme: small camps spawn full and decay; large settlements bind population to bed count with cooldown-based replacement
4. **Structure building** — blueprint item → ghost preview → confirm placement. Uses vanilla 1.21+ structure placement. Scanner (C-scheme) deferred
5. **Research/skill tree** — points earned from vanilla advancements (tiered), spent to unlock technologies in a GUI
6. **Vehicles** — enhance vanilla mounts (horse, donkey, dog, boat), focus on AI pathfinding improvements via Mixin

## Git Workflow

- One commit per feature point, no multi-day accumulations
- Commit messages in Chinese: `添加 NPC 职业切换 GUI`
- Branches: `main` (stable), `dev` (daily), `feature/npc-spawn-system` (large features)
- Before committing: `./gradlew build` must pass

## Critical Technical Risks

- **Mixin**: core mechanism for vehicle AI enhancements; two mods Mixin-injecting the same method will conflict
- **GUI complexity**: skill tree and job switching GUIs involve Screen system, coordinate math, button events, scroll regions
- **World generation**: even simple presets require choosing between vanilla datapack structures (easier but limited) vs custom placement (flexible but more code)
- **Multiplayer testing**: NPC systems and faction relations need early multiplayer testing, singleplayer won't catch all issues

## Network Setup (国内网络)

If `maven.neoforged.net` is inaccessible (SSL handshake failure / Connection reset):

```bash
# 1. Set Windows system proxy in gradle.properties:
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=10808
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=10808

# 2. Download NeoForge dependencies manually using curl through proxy:
PROXY="http://127.0.0.1:10808"
CACHE_DIR="$HOME/.gradle/caches/modules-2/files-2.1"

download_place() {
    local group="$1" artifact="$2" version="$3" jar="$4" url="$5"
    local dest_dir="$CACHE_DIR/${group//.//}/$artifact/$version"
    mkdir -p "$dest_dir"
    curl -x "$PROXY" -sL --max-time 30 -o "$dest_dir/$jar" "$url"
    local hash=$(sha1sum "$dest_dir/$jar" | cut -d' ' -f1)
    mkdir -p "$dest_dir/$hash"
    mv "$dest_dir/$jar" "$dest_dir/$hash/$jar"
}

# 3. Also download POM files the same way (replace .jar with .pom in URL)

# 4. Clear metadata cache:
rm -rf ~/.gradle/caches/modules-2/metadata-2.107
rm -rf ~/.gradle/daemon

# 5. Kill Java processes and run build via PowerShell (Git Bash may not route through VPN):
powershell.exe -Command '& {
  $env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
  $env:Path="$env:JAVA_HOME\bin;$env:PATH"
  $output = & .\gradlew.bat build --no-daemon 2>&1
  $output | Out-File -FilePath $env:USERPROFILE\build_log.txt -Encoding UTF8
}'
```

## GitHub Upload

- **No Claude Code copyright / AI attribution** in any commit or file
- Push command: `git push -u origin main`
- Commit messages in Chinese

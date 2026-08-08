# 00 - Overview

Iris is a world generation engine for Minecraft servers and mod loaders. It builds terrain, biomes, caves, structures, objects, and entities from editable JSON packs, exposes an in-game studio authoring workflow, and runs as a Bukkit-family plugin or as a Fabric, Forge, or NeoForge server mod. Cross-platform generation is designed and tested for deterministic parity when artifacts, pack bytes, seeds, and test areas are identical; verify release candidates with GoldenHash. This branch targets Minecraft 26.2; Java 25 is required everywhere.

## Platforms

| Platform | Artifact | Minecraft | Notes |
|---|---|---|---|
| Paper / Purpur / Leaf / Canvas | plugin jar | 26.1.2 – 26.2 | Full plugin feature set |
| Folia | plugin jar | 26.1.2 – 26.2 | Region-safe scheduling; runtime world create is staged for restart (see `01 - Installation & Platforms.md`, `06 - Worlds & Lifecycle.md`) |
| Spigot / CraftBukkit | plugin jar | 26.1.2 – 26.2 | Full plugin feature set |
| Fabric | mod jar | 26.2 | Server worldgen + client HUD; Fabric Loader 0.19.3+ |
| Forge | mod jar | 26.2 | Server worldgen + client HUD; Forge 65.0.4+ |
| NeoForge | mod jar | 26.2 | Server worldgen + client HUD; NeoForge 26.2.0.12-beta+ |

Plugin identity: name `Iris` (from root project name), command `iris` with aliases `ir` / `irs`, `folia-supported: true`, `load: STARTUP`, `api-version` 26.1 (loads on 26.1.2 and 26.2). Soft-depends include PlaceholderAPI, WorldEdit, item plugins, MythicMobs; Multiverse-Core is ordered after Iris (`loadbefore` / paper `load: AFTER`).

Mod id on all three loaders: `irisworldgen`.

## Feature map

| Area | What it covers | Doc |
|---|---|---|
| Install and platforms | Plugin vs mod jars, data dirs, first boot, native worldgen matrix | `01 - Installation & Platforms.md` |
| First steps | Create, load, teleport, pregen, studio | `02 - Getting Started.md` |
| Configuration | `settings.json` keys, defaults, hotload | `03 - Configuration.md` |
| Commands and permissions | Full `/iris` tree, Bukkit vs modded argument style | `04 - Commands & Permissions.md` |
| Pack layout | Roots, keys, snippets, world snapshot vs studio | `05 - Concepts & Pack Layout.md` |
| Worlds | create / load / unload / remove, main world, Folia, pack copy | `06 - Worlds & Lifecycle.md` |
| Pregeneration | Jobs, cache, mantle, HUD | `07 - Pregeneration.md` |
| Localization | Locales, overrides, client lang | `08 - Localization.md` |
| PlaceholderAPI | `%iris_…%` keys and migration | `09 - PlaceholderAPI.md` |
| Studio and schemas | Studio worlds, VSCode workspace, hotload | `10 - Studio & VSCode Schemas.md` |
| Dimensions | Dimension JSON, modes, height, imports | `11 - Dimensions.md` |
| Regions | Region-level content | `12 - Regions.md` |
| Biomes | Biome JSON, layers, custom biomes, spawns | `13 - Biomes.md` |
| Generators and noise | Generators, styles, expressions, images | `14 - Generators & Noise.md` |
| Caves and carving | Cave profiles, field modules | `15 - Caves & Carving.md` |
| Surfaces | Decorators, deposits, palettes | `16 - Surfaces, Decorators & Deposits.md` |
| Procedural decoration | Trees, fungi, coral, crystals, formations, ruins | `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md` |
| Structures overview | Objects vs jigsaw vs native | `18 - Structures Overview.md` |
| Objects | Creating and importing `.iob` | `19 - Objects.md` |
| Object placement | Placing objects in biomes and regions | `20 - Object Placement.md` |
| Jigsaw | Iris multi-piece structures | `21 - Jigsaw Structures.md` |
| Native structures | Vanilla / datapack structures on Iris | `22 - Native Structures & Datapacks.md` |
| Loot and entities | Pack entities, loot, spawners, markers | `23 - Loot, Entities, Spawners, Markers.md` |
| Pack extensions | Reusable snippets and the inactive pack-mod schema | `24 - Pack Mods & Snippets.md` |
| Pack management | Download, validate, cleanup, package, update-world | `25 - Pack Management.md` |
| Minimal pack example | Walkthrough | `26 - Example - Minimal Dimension.md` |
| Overworld example | Editing the shipping overworld | `27 - Example - Configuring Overworld.md` |
| Integrations | WorldEdit, Multiverse, Mythic, item plugins, tree feller | `28 - Integrations.md` |
| Client HUD | Client mod HUD and protocol channel | `29 - Client HUD & Protocol.md` |
| Platform matrix | Bukkit vs Fabric / Forge / NeoForge differences | `30 - Platform Differences.md` |
| Operator checks | Manual verification | `31 - Operator Runbooks & Smoke Tests.md` |
| Determinism | Goldenhash cross-platform gate | `32 - Determinism & Goldenhash.md` |
| Performance | Threads, mantle, SIMD, pregen caps | `33 - Performance Tuning.md` |
| Maintainer — MC version bump | Version bump procedure | `85 - Maintainer - MC Version Bump.md` |
| Maintainer — release | Release steps | `86 - Maintainer - Release Checklist.md` |
| Maintainer — readiness | Living readiness tracker | `87 - Maintainer - Release Readiness.md` |
| API — setup | Bukkit public API dependency | `90 - API - Getting Started.md` |
| API — terrain | Terrain query service | `91 - API - Terrain.md` |
| API — events | Engine and pregen events | `92 - API - World Events.md` |
| API — tree feller | Tree feller service | `93 - API - Tree Feller.md` |
| API — modded | Modded public API (`art.arcane.iris.modded.api`) | `94 - API - Modded.md` |

Docs `00`–`33` are for operators and pack authors in reading order. `85`–`87` are maintainer checklists. `90`–`94` are for plugin and mod developers.

## Content model (brief)

| Term | Meaning |
|---|---|
| Pack | Directory of JSON and `.iob` under `packs/<key>/` with at least `dimensions/*.json` |
| Dimension | Root config for a world type (height, modes, regions, imports) |
| Region / biome / generator | Spatial and terrain authoring units |
| Object / structure | Placed content (`.iob`, Iris jigsaw, native or datapack structures) |
| Studio | Transient authoring world with live pack hotload and VSCode schemas; deleted on close and purged at startup |
| World pack snapshot | Production worlds copy the pack into `<world>/iris/pack` and read that copy (see `05 - Concepts & Pack Layout.md`) |

## Project layout

| Path | Role |
|---|---|
| `core/` | Pure-JVM engine, pack loader, pregen, studio services, localization catalogs |
| `core/agent/` | Agent helper module used by the core build |
| `spi/` | Platform SPI and pure-JVM contracts (`IrisPlatform`, protocol types) |
| `adapters/bukkit/plugin/` | Bukkit plugin main, commands, public Bukkit API, Paper plugin descriptor |
| `adapters/bukkit/nms/v26_2_R1/` | NMS bindings for the current Minecraft line |
| `adapters/minecraft-common/` | Shared adapter code used by Bukkit and mod loaders |
| `adapters/modded-common/` | Shared Fabric / Forge / NeoForge worldgen, commands, services |
| `adapters/client-common/` | Client HUD and world-type screens |
| `adapters/fabric/`, `adapters/forge/`, `adapters/neoforge/` | Standalone loader builds (own `settings.gradle`) |
| `probe/` | Offline tooling and stub platform |
| `buildSrc/` | Shared Gradle helpers (artifact verification, API generation) |
| `dist/` | Built consumer jars after `buildAllToOut` |
| `docs/` | Authoritative product and API documentation |

## Building

Requirements: JDK 25 (`JAVA_HOME` set). From the Iris repo root:

```
./gradlew build
./gradlew test
./gradlew buildAllToOut
```

`buildAllToOut` writes every platform jar into `dist/`:

```
Iris v<version> [CraftBukkit] <mc>.jar
Iris v<version> [Fabric] <mc>+<loader>.jar
Iris v<version> [Forge] <mc>+<loader>.jar
Iris v<version> [NeoForge] <mc>+<loader>.jar
```

Per-platform: `./gradlew buildBukkit`, `buildFabric`, `buildForge`, `buildNeoforge`. SPI jar: `./gradlew :spi:jar` → `spi/build/libs/`.

Modded adapters are driven with their own project root when developing:

```
./gradlew -p adapters/fabric   runServer
./gradlew -p adapters/forge    runServer
./gradlew -p adapters/neoforge runServer
```

`-PincludeModdedAdapters=true` can surface those builds in the root composite for IDE import only; it is off by default because each adapter includes the root build back for `core`/`spi` substitution.

Current version property: `irisVersion=4.0.0-26.2` in `gradle.properties`.

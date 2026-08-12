# 00 - Overview

Iris is a world generation engine for Minecraft: it replaces the vanilla chunk generator with terrain, biomes, caves, structures, objects, and entities built from editable JSON packs. The same engine ships as a Bukkit-family plugin and as a Fabric, Forge, or NeoForge server mod, and generates identical chunks on all four when artifacts, pack bytes, seed, and area match. This page is the map of the documentation set: read it to find the page you actually need, then leave. This branch targets Minecraft 26.2 and requires Java 25 everywhere.

## Who this documentation is for

There are three audiences and the numbering reflects them. Pages `00`–`33` are for **server operators** installing Iris and **pack authors** writing dimensions, in roughly the order a newcomer needs them. Pages `85`–`87` are **maintainer** checklists for cutting a release. Pages `90`–`94` are for **Java developers** consuming the Iris API from their own plugin or mod.

Reading the set front to back is a waste of time. Pick the outcome you want from the table below and follow only that row.

## Choose a learning path

| You want to | Read, in order |
|---|---|
| Get Iris running and make one world | `01 - Installation & Platforms.md` → `02 - Getting Started.md` → `31 - Operator Runbooks.md` |
| Write a pack from scratch | `05 - Concepts & Pack Layout.md` → `10 - Studio & VSCode Schemas.md` → `26 - Example - Minimal Dimension.md` |
| Shape terrain and lay out biomes | `11 - Dimensions.md` → `12 - Regions.md` → `13 - Biomes.md` → `14 - Generators & Noise.md` |
| Add caves, surface detail, and vegetation | `15 - Caves & Carving.md` → `16 - Surfaces, Decorators & Deposits.md` → `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md` |
| Place a building or structure | `18 - Structures Overview.md` first to pick an approach, then `19 - Objects.md` + `20 - Object Placement.md` for single `.iob` objects, `21 - Jigsaw Structures.md` for multi-piece Iris structures, or `22 - Native Structures & Datapacks.md` for vanilla/datapack ones |
| Ship a pack to a production server | `25 - Pack Management.md` → `06 - Worlds & Lifecycle.md` → `07 - Pregeneration.md` → `31 - Operator Runbooks.md` |
| Make another plugin or mod work with Iris | `28 - Integrations.md` → `30 - Platform Differences.md`; if you're writing Java against Iris, start at `90 - API - Getting Started.md` |

Each tutorial page ends with something you can observe — a world that loads, a chunk that generates, a hotload that lands. Confirm that before moving to the next page. Iris failures cascade misleadingly: a biome key you typo'd in step two shows up three systems later as a cave, decorator, or structure that "doesn't work," and you'll debug the wrong thing.

## Platforms

One plugin jar covers the whole Bukkit family; each mod loader gets its own jar. Pick by what your server runs, not by feature set — the generator is the same code on all of them.

| Platform | Artifact | Minecraft | What's different |
|---|---|---|---|
| Paper / Purpur / Leaf / Canvas | plugin jar | 26.1.2 – 26.2 | Nothing; this is the reference plugin target |
| Spigot / CraftBukkit | plugin jar | 26.1.2 – 26.2 | Nothing for generation. Paper-only APIs degrade gracefully |
| Folia | plugin jar | 26.1.2 – 26.2 | Region-safe scheduling, and `/iris create` cannot build a live world at runtime — it stages the world and requires a restart. See `01 - Installation & Platforms.md` and `06 - Worlds & Lifecycle.md` |
| Fabric | mod jar | 26.2 | Server worldgen plus an optional client HUD; needs Fabric Loader 0.19.3+ and Java 25 |
| Forge | mod jar | 26.2 | Same; needs Forge 65.x (built against 26.2-65.0.4) |
| NeoForge | mod jar | 26.2 | Same; needs NeoForge 26.2.x (built against 26.2.0.12-beta) |

The plugin registers as `Iris` with command `/iris` (aliases `/ir`, `/irs`), `folia-supported: true`, `load: STARTUP`, and `api-version: 26.1` — the low api-version is deliberate so one jar loads on both 26.1.2 and 26.2. Its descriptor declares two permissions, `iris.all` (the whole command tree) and `iris.treefeller` (survival tree felling only), both defaulting to op. Optional soft-dependencies load before Iris; Multiverse-Core is ordered *after* Iris so Multiverse sees Iris generators once they exist. Full list in `01 - Installation & Platforms.md`.

All three mod loaders use mod id `irisworldgen`, and register `/ir` and `/irs` as command redirects the same way the plugin does.

## Feature map

Every feature of Iris is documented on exactly one page. Find the subject, go there.

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
| Operator checks | Manual verification | `31 - Operator Runbooks.md` |
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

## Content model

Six terms carry most of the documentation. Learn them here and the rest of the set reads much faster.

| Term | What it is |
|---|---|
| Pack | A folder of JSON and `.iob` files under `packs/<key>/`. It needs at least one `dimensions/*.json` to count as a pack at all — a folder without one is treated as absent and will be re-downloaded |
| Dimension | The root config for one world type: height range, generation modes, which regions it uses, what native content it imports. One dimension file is one world's ruleset |
| Region / biome / generator | The authoring units under a dimension. Regions divide the map, biomes fill regions, generators produce the actual heightmap noise |
| Object / structure | Placed content. An object is a single saved build (`.iob`); a structure is either an Iris jigsaw of several objects, or a vanilla/datapack/mod structure Iris allows through |
| Studio | A throwaway authoring world that reads the live pack folder and hotloads your edits into new chunks. Deleted when you close it, and any leftovers are purged at startup |
| World pack snapshot | A production world copies the pack into `<world>/iris/pack` at creation and reads only that copy forever after. This is the single most common source of "my edits did nothing" — see `05 - Concepts & Pack Layout.md` |

## Project layout

Relevant if you're building Iris or filing a bug against a specific subsystem.

| Path | Role |
|---|---|
| `core/` | The engine: pack loader, generation pipeline, pregen, studio services, localization catalogs. Pure JVM, no platform types |
| `core/agent/` | Java instrumentation agent (premain/agent-class jar) consumed by the core build |
| `spi/` | The platform contract (`IrisPlatform`, protocol types) that lets `core/` stay platform-free |
| `adapters/bukkit/plugin/` | Bukkit plugin main class, the Director command tree, the public Bukkit API, and both plugin descriptors |
| `adapters/bukkit/nms/v26_2_R1/` | NMS bindings for the current Minecraft line |
| `adapters/minecraft-common/` | Source shared by the Bukkit and mod-loader adapters |
| `adapters/modded-common/` | Source shared by Fabric, Forge, and NeoForge: worldgen hooks, Brigadier commands, services |
| `adapters/client-common/` | Client-dist source: HUD, keybinds, world-type screens |
| `adapters/fabric/`, `adapters/forge/`, `adapters/neoforge/` | The three loader builds. Each is a standalone Gradle build with its own `settings.gradle` |
| `probe/` | Offline tooling and a stub platform for running the engine without a server |
| `buildSrc/` | Gradle helpers: artifact verification, NMS bindings, API generation |
| `dist/` | Where `buildAllToOut` drops the finished consumer jars |
| `docs/` | This documentation set, which is the authority over any hosted copy |

`minecraft-common`, `modded-common`, and `client-common` are source trees only — they have no `build.gradle` and are not Gradle projects. The loader builds pull them in as extra source directories.

## Developer build check

Set `JAVA_HOME` to a JDK 25, then from the Iris root:

```text
java -version
./gradlew build
./gradlew buildAllToOut
```

The check passes when `build` finishes with no failed tasks and `buildAllToOut` leaves one current jar per platform in `dist/`. `build` already runs the tests, so use `./gradlew test` only when you want to rerun tests without reassembling every artifact.

At version `4.0.0-26.2` the four jars are named like this — the CraftBukkit one carries the supported Minecraft *range*, the loader jars carry `<mc>+<loader>`:

```text
Iris v4.0.0-26.2 [CraftBukkit] 26.1.2-26.2.jar
Iris v4.0.0-26.2 [Fabric] 26.2+0.19.3.jar
Iris v4.0.0-26.2 [Forge] 26.2+65.0.4.jar
Iris v4.0.0-26.2 [NeoForge] 26.2+26.2.0.12-beta.jar
```

Build one platform at a time with `./gradlew buildBukkit`, `buildFabric`, `buildForge`, or `buildNeoforge`. The SPI jar comes from `./gradlew :spi:jar` and lands in `spi/build/libs/`.

To iterate on a loader adapter, drive it from its own project root — these are separate Gradle builds, so a root-level invocation won't reach them:

```text
./gradlew -p adapters/fabric   runServer
./gradlew -p adapters/forge    runServer
./gradlew -p adapters/neoforge runServer
```

`-PincludeModdedAdapters=true` surfaces those builds in the root composite for IDE import. It's off by default because each adapter includes the root build back for `core`/`spi` substitution, which closes a composite cycle.

A passing Bukkit jar proves nothing about the loaders. Loom, ForgeGradle, and ModDevGradle each fail in their own ways, so if a loader build breaks while the root build is green, rerun that adapter from its own root and fix its first error rather than re-running the root build.

The current version lives in `gradle.properties` as `irisVersion=4.0.0-26.2`.

Next: install Iris with `01 - Installation & Platforms.md`.

# Iris Agent Guide

Iris is a world generation engine for Minecraft servers and mod loaders. It generates terrain, biomes, caves, structures, objects, and entities from editable JSON packs, with an in-game studio authoring workflow. The same engine runs as a Bukkit-family plugin and as a Fabric, Forge, or NeoForge server mod. Read this file before making any change; the workspace-level `../AGENTS.md` also applies when working inside the VolmitSoftware workspace.

## Documentation Policy (mandatory)

- `docs/` is the authoritative reference for every feature of this plugin/mod. Files are flat (no subfolders) and numbered `NN - Title.md`, ordered for someone new to Iris; API docs always keep the highest numbers.
- ANY change that alters a feature, command, permission, setting, pack JSON contract, studio/editor workflow, pregen behavior, structure/object system, integration, localization, client HUD/protocol, or public API surface MUST update the matching numbered doc in the same workstream. A behavior change with stale docs is an incomplete change — do not finish work without the doc update.
- Docs state actual runtime behavior, not intended behavior. If a change fixes a documented quirk, update or remove that quirk entry. If a change introduces surprising behavior, document it plainly.
- Docs are purely factual reference material: no marketing language, no emojis, no filler. Each file opens with a 1–4 sentence summary.
- Cross-references use exact filenames (for example `see "04 - Commands & Permissions.md"`). When adding or renumbering files, fix every cross-reference.
- Hosted external docs are not authority; this `docs/` tree is.
- Maintainer-only checklists use high numbers before the API series and are titled `Maintainer — …`.

## Doc Index

| File | Covers |
|------|--------|
| `00 - Overview.md` | What Iris is, feature map, doc index, project layout |
| `01 - Installation & Platforms.md` | Plugin/mod install, data dirs, first boot, platforms, native worldgen matrix |
| `02 - Getting Started.md` | First world, teleport, basic pregen, first studio |
| `03 - Configuration.md` | `settings.json` keys, defaults, hotload |
| `04 - Commands & Permissions.md` | Full `/iris` tree, Bukkit vs modded, permissions |
| `05 - Concepts & Pack Layout.md` | Pack roots, keys, folders, snippets, world snapshot vs studio |
| `06 - Worlds & Lifecycle.md` | create/load/unload/remove, main world, Folia, pack copy |
| `07 - Pregeneration.md` | pregen ops, cache, mantle, HUD |
| `08 - Localization.md` | locales, overrides, client lang |
| `09 - PlaceholderAPI.md` | `%iris_…%` keys and migration |
| `10 - Studio & VSCode Schemas.md` | Studio workflow, schemas, hotload |
| `11 - Dimensions.md` | Dimension JSON, modes, height, imports |
| `12 - Regions.md` | Regions and region-level content |
| `13 - Biomes.md` | Biome JSON, layers, custom biomes, spawns |
| `14 - Generators & Noise.md` | Generators, styles, expressions, images |
| `15 - Caves & Carving.md` | Cave profiles, field modules, carving |
| `16 - Surfaces, Decorators & Deposits.md` | Decorators, deposits, palettes |
| `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md` | Procedural decoration systems |
| `18 - Structures Overview.md` | Objects vs jigsaw vs native structures |
| `19 - Objects.md` | Creating and importing `.iob` objects |
| `20 - Object Placement.md` | Placing objects in biomes and regions |
| `21 - Jigsaw Structures.md` | Iris multi-piece structures |
| `22 - Native Structures & Datapacks.md` | Vanilla/datapack structures on Iris |
| `23 - Loot, Entities, Spawners, Markers.md` | Pack entities and loot |
| `24 - Pack Mods & Snippets.md` | Injectors/replacers and snippets |
| `25 - Pack Management.md` | Download, validate, cleanup, package, update-world |
| `26 - Example - Minimal Dimension.md` | Minimal pack walkthrough |
| `27 - Example - Configuring Overworld.md` | Editing the shipping overworld |
| `28 - Integrations.md` | WorldEdit, Multiverse, Mythic, item plugins, tree feller |
| `29 - Client HUD & Protocol.md` | Client mod HUD and protocol |
| `30 - Platform Differences.md` | Bukkit vs Fabric/Forge/NeoForge matrix |
| `31 - Operator Runbooks & Smoke Tests.md` | Manual verification |
| `32 - Determinism & Goldenhash.md` | Cross-platform parity gate |
| `33 - Performance Tuning.md` | Threads, mantle, SIMD, pregen caps |
| `85 - Maintainer - MC Version Bump.md` | Version bump procedure |
| `86 - Maintainer - Release Checklist.md` | Release steps |
| `87 - Maintainer - Release Readiness.md` | Living readiness tracker |
| `90 - API - Getting Started.md` | Bukkit public API setup |
| `91 - API - Terrain.md` | Terrain query service |
| `92 - API - World Events.md` | Engine and pregen events |
| `93 - API - Tree Feller.md` | Tree feller service |
| `94 - API - Modded.md` | Modded public API |

Docs `00`–`33` serve operators and pack authors in reading order; `85`–`87` are maintainer; `90`–`94` serve plugin and mod developers.

## Build and Platforms

- Java 25 required. Independent Gradle build from `Iris/`: `./gradlew build`, `./gradlew test`.
- Artifacts: Bukkit-family plugin jar; Fabric, Forge, and NeoForge mod jars under `dist/` when built.
- Modules: `core` (engine), `spi` (platform SPI), `adapters/bukkit/plugin` (plugin + Bukkit API), `adapters/modded-common` + loader adapters, `probe` (offline tooling).
- Default pack downloads at first boot from the IrisDimensions overworld release; packs live under the platform data directory `packs/<key>/`.

## Content Model (brief)

- **Pack** — directory of JSON and `.iob` resources under `packs/<key>/` with at least `dimensions/*.json`.
- **Dimension** — root config for a world type (height, modes, regions, imports).
- **Region / Biome / Generator** — spatial and terrain authoring units.
- **Object / Structure** — placed content (`.iob`, Iris jigsaw, native/datapack structures).
- **Studio** — transient authoring world with live pack hotload and VSCode schemas.

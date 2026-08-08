# 30 - Platform Differences

Iris runs the same generation core on Bukkit-family servers and on Fabric, Forge, and NeoForge. Adapters differ in world lifecycle, command surface, permissions, datapacks, and optional tools. Shared config is `settings.json`; mod loaders add `modded.json`. See `01 - Installation & Platforms.md`, `03 - Configuration.md`, and `04 - Commands & Permissions.md`.

## Artifacts and entry points

| Surface | Artifact | Bootstrap |
|---------|----------|-----------|
| Bukkit / Paper / Folia | CraftBukkit-shaded plugin jar | `plugin.yml` / `paper-plugin.yml`, `folia-supported: true`, load `STARTUP` |
| Fabric | Fabric mod jar | `IrisFabricBootstrap` registers commands and services |
| Forge | Forge mod jar | `IrisForgeBootstrap` |
| NeoForge | NeoForge mod jar | `IrisNeoForgeBootstrap` |

Core engine: `core/`. Shared modded logic: `adapters/modded-common/`. SPI: `spi/`.

## Data directories

| Item | Bukkit | Fabric / Forge / NeoForge |
|------|--------|---------------------------|
| Settings | `plugins/Iris/settings.json` | `<configDir>/iris/settings.json` |
| Packs | `plugins/Iris/packs/` | `<configDir>/irisworldgen/packs/` |
| Mod config | — | `<configDir>/irisworldgen/modded.json` |
| World datapacks | world `datapacks/` + Iris ingest | world `datapacks/`; dimension-type pack name `iris` under `data/irisworldgen/dimension_type/` |
| Dump / developer files | under plugin data folder | under mod data folder |
| Persistent dynamic-world registry | `worlds.json` in plugin data | `<world-root>/iris/iris-dimensions.json` |

Hotload: Bukkit file-watch engine; modded 3s poll. Same invalidate/reload/locale path.

## World model

| Concern | Bukkit | Modded |
|---------|--------|--------|
| Create | `/iris create` → managed world name, generator Iris, optional main-world | `/iris create` or `/iris world enable` → dimension id + pack injection |
| Load / unload | `/iris load` (`import`), `/iris unload` | `/iris world disable` unloads; no separate load command |
| Remove / delete | `/iris remove` optional folder delete | `/iris world delete` wipes chunk/mantle data |
| Primary / main world | create `main=true` and Bukkit yml registration paths | `modded.json` primary + `routePlayersToPrimaryWorld`; `/iris world mainworld`, `replace-overworld` |
| Evacuate | `/iris evacuate <world>` | `/iris evacuate [dimension]` → primary/overworld fallback |
| Studio world | Transient studio world via StudioSVC | Studio dimension under `irisworldgen:studio_*` |
| Folia | Regionized schedulers; pregen `runtimeSchedulerMode` forces `FOLIA` when regionized | N/A (not Bukkit Folia) |

Default pack bootstrap still downloads the IrisDimensions overworld release into `packs/overworld` when missing (shared provisioner).

Modded startup quarantines a corrupt persistent-dimension registry as `iris-dimensions.json.broken-<timestamp>` and continues without those dynamic worlds. Recovery details are in `06 - Worlds & Lifecycle.md`.

## Commands and permissions

| Concern | Bukkit | Modded |
|---------|--------|--------|
| Parser | VolmLib Director; `key=value` optionals | Brigadier; ordered args and flag literals |
| Root aliases | `iris`, `ir`, `irs` | same + redirects |
| Staff gate | `iris.all` (declared in `plugin.yml` and `paper-plugin.yml`, default `op`) | `LEVEL_GAMEMASTERS` for mutations |
| Public inspect | same gate as staff (`iris.all` required for all `/iris`) | `LEVEL_ALL` for version/info/height/metrics/what/help |
| Tree feller | `iris.treefeller` (plugin.yml, default op) | `irisworldgen:treefeller` (Fabric); Forge/NeoForge PermissionAPI node |
| Help | Director mini-menu | `ModdedCommandHelp` sections + clickable pages |

Full command tables and stubs: `04 - Commands & Permissions.md`.

## Feature matrix

| Feature | Bukkit | Fabric | Forge | NeoForge |
|---------|--------|--------|-------|----------|
| Core terrain / biomes / objects / jigsaw | yes | yes | yes | yes |
| Pack validate / cleanup / download | yes | yes | yes | yes |
| Pregen | yes (Paper-like / Folia modes) | yes (`moddedPregenInFlight`) | yes | yes |
| Studio open/close/vscode/package | yes | yes | yes | yes |
| Object wand / paste / save / undo | yes | yes | yes | yes |
| Object expand selection | no | yes | yes | yes |
| Object WorldEdit import | yes (WorldEdit soft depend) | no (stub) | no | no |
| Object studio world | yes | no (stub) | no | no |
| Schematic convert | yes | no (stub) | no | no |
| Structure import / capture | yes (NMS) | message only | message only | message only |
| Structure list / info / place / verify | yes | yes | yes | yes |
| Datapack Modrinth ingest/remove | yes | message only | message only | message only |
| Dimension-type datapack install/status | N/A / different path | yes | yes | yes |
| Studio loot GUI / entity spawn / profile / objects report | yes | no (stub) | no | no |
| Studio importvanilla | yes | message (run on Bukkit) | message | message |
| PlaceholderAPI | soft depend | no | no | no |
| Multiverse-Core | soft depend / loadbefore | no | no | no |
| Item plugins (ItemsAdder, Mythic, etc.) | paper soft deps | loader-specific / limited | limited | limited |
| Public API package `art.arcane.iris.api` | plugin jar | see `94 - API - Modded.md` | same | same |
| Client HUD / protocol | optional client mod | optional client mod | optional | optional |
| Tree feller | settings + `iris.treefeller` | settings + platform permission | same | same |
| Auto Spigot/Paper timeout config | yes | no | no | no |
| Custom biome restart prompts | yes (`iris.all` / op) | different datapack flow | same | same |

## Settings that are platform-sensitive

| Setting | Notes |
|---------|-------|
| `pregen.runtimeSchedulerMode` | Resolves using Bukkit/Folia detection; regionized always Folia |
| `pregen.paperLikeBackendMode` | Bukkit Paper-like pregen ticket vs service |
| `pregen.moddedPregenInFlight` | Modded pregen concurrency budget |
| `autoConfiguration.*` | Spigot/Paper server.properties/watchdog (Bukkit) |
| `world.worldEditWandCUI` | WorldEdit present on Bukkit |
| `general.autoIngestDatapacks` / `autoImportDatapackStructures` | Bukkit datapack ingest pipeline primary consumer |
| `gui.useServerLaunchedGuis` | Both; host implementation differs (`BukkitGuiHost` vs `ModdedGuiHost`) |

`modded.json` keys exist only on mod loaders.

## Integrations

| Integration | Bukkit | Modded |
|-------------|--------|--------|
| WorldEdit | soft depend; object `we` | not wired |
| Multiverse-Core | load order / link | not used |
| PlaceholderAPI | `%iris_…%` | no |
| MythicMobs / item plugins | paper-plugin optional deps | not the Bukkit pipeline |
| Tree feller | plugin permission | loader permission node |

See `28 - Integrations.md`, `09 - PlaceholderAPI.md`.

## NMS / version binding

- Bukkit plugin binds to a specific Paper/CraftBukkit revision (v26 NMS module in-tree).
- Structure import/capture and vanilla import studio paths require that NMS binding.
- Mod adapters use Minecraft mappings for the same game version line without the Bukkit plugin APIs.

## Determinism and parity

Goldenhash and genhash exist on both surfaces (command placement differs: Bukkit under `developer`, modded often at root). Use disposable worlds; mantle reset options exist on Bukkit goldenhash. See `32 - Determinism & Goldenhash.md`.

## Operator checklist when moving packs between platforms

1. Copy `packs/<key>/` between data folders.
2. Structure/vanilla imports that need Bukkit: run import on Bukkit, then copy the pack to the mod server.
3. Align `settings.json` keys that matter for generation (`generator`, `performance`, `treeFeller`); ignore Bukkit-only autoConfiguration if unused.
4. On modded, set `modded.json` primary/main-world if you need overworld replacement.
5. Re-run `/iris pack validate` and `/iris datapack status` (modded) or ingest (Bukkit) after moves.

## Related

- `01 - Installation & Platforms.md`
- `03 - Configuration.md`
- `04 - Commands & Permissions.md`
- `06 - Worlds & Lifecycle.md`
- `07 - Pregeneration.md`
- `22 - Native Structures & Datapacks.md`
- `28 - Integrations.md`
- `29 - Client HUD & Protocol.md`
- `94 - API - Modded.md`

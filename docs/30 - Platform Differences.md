# 30 - Platform Differences

Iris runs the same generation core on Bukkit-family servers and on Fabric, Forge, and NeoForge. Terrain output is identical; everything around it differs, and this page is the reference matrix for those differences. Shared configuration is `settings.json`; mod loaders add `modded.json`. Related detail lives in `01 - Installation & Platforms.md`, `03 - Configuration.md`, and `04 - Commands & Permissions.md`.

## What actually differs

Five categories cover almost everything an operator runs into:

- **Command syntax.** Bukkit uses VolmLib Director, where optional arguments are `key=value` in any order. Mod loaders use Brigadier, where arguments are positional and options are bare literals. The same feature reads very differently on each.
- **World lifecycle.** Bukkit creates named worlds and can stage an exact replacement of a configured vanilla slot on restart. Mod loaders register dimension ids and enable or disable them.
- **Authoring tools that need Bukkit.** Anything built on NMS, WorldEdit, or inventory GUIs is Bukkit-only: Jigsaw Studio, structure import and capture, vanilla import, schematic conversion, and the Studio loot and entity GUIs. Packs authored there run fine everywhere.
- **Permissions.** Bukkit gates the entire `/iris` tree behind one permission. Mod loaders gate mutating commands at gamemaster level but leave inspection open to any player.
- **File locations.** Both platforms have an Iris data directory, but the modded side splits it: settings live under one root and packs under another.

Terrain, biomes, objects, jigsaw runtime, caves, and structures behave the same on all four. If generated terrain differs between platforms, that is a determinism defect, not a platform difference — see `32 - Determinism & Goldenhash.md`.

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
| GoldenHash baselines | `plugins/Iris/golden/` | `<configDir>/irisworldgen/golden/` |
| Studio pack exports | `plugins/Iris/packs/exports/` | `<configDir>/irisworldgen/exports/` |
| Generated datapack | world `datapacks/` + Iris ingest | `<configDir>/irisworldgen/generated/datapack/`; dimension-type pack name `iris` under `data/irisworldgen/dimension_type/` |
| Parity / developer dumps | under plugin data folder | `<configDir>/iris/parity/` |
| Persistent dynamic-world registry | `plugins/Iris/worlds.json` | `<world-root>/iris/iris-dimensions.json` |

On mod loaders only `settings.json` and the parity dumps use the `iris/` root; every pack, config, and generated artifact uses `irisworldgen/`. Both roots sit under the loader config directory.

Hotload is polled on both platforms, at different rates:

| Watcher | Bukkit | Modded |
|---------|--------|--------|
| Pack / studio content | 1 s scan over the shared reactive folder | 250 ms scan, 1 s check latch, 2 s hold-off after recent generation |
| `settings.json` | Reloaded through the same reactive path | Dedicated 3 s poll |

Both use the same invalidate, reload, and locale path once a change is detected.

## World model

| Concern | Bukkit | Modded |
|---------|--------|--------|
| Create | `/iris create` → managed world name, generator Iris, optional main-world; on Paper-family servers `overwrite=true` stages replacement of an existing exact Iris or vanilla slot for the next restart | `/iris create` or `/iris world enable` → dimension id plus pack injection |
| Load / unload | `/iris load` (alias `import`), `/iris unload` | `/iris world disable` unloads; there is no separate load command |
| Remove / delete | `/iris remove`, optionally deleting the folder | `/iris world delete` wipes chunk and mantle data |
| Primary / main world | `main=true` for a new level root, or name the configured main world with `overwrite=true` for journaled in-place replacement | `modded.json` `primaryWorld` plus `routePlayersToPrimaryWorld`; `/iris world mainworld` (and `mainworld off`), `/iris world replace-overworld` |
| Evacuate | `/iris evacuate <world>` — world argument required, player-only origin | `/iris evacuate [dimension]` — defaults to the sender's current level; destination is always the vanilla overworld, and evacuating the overworld itself is refused |
| Studio world | Transient studio world via StudioSVC; `/iris jigsaw` can select the Jigsaw Studio generator for one activation | Studio dimension under `irisworldgen:studio_*`; no Jigsaw Studio authoring tree |
| Folia | Regionized schedulers; pregen `runtimeSchedulerMode` always resolves to `FOLIA` on a regionized runtime | Not applicable |

Startup installs the IrisDimensions Overworld and Underworld beta releases into `packs/overworld` and `packs/underworld` when missing. Paper bootstrap publishes both in one rollback scope before compiling the aggregate datapack; legacy Bukkit and modded startup use the same managed release sources.

Modded startup quarantines a corrupt persistent-dimension registry as `iris-dimensions.json.broken-<timestamp>` and continues without those dynamic worlds. Recovery: `06 - Worlds & Lifecycle.md`.

Exact vanilla-slot replacement requires a full Paper-family plugin bootstrap. Without it the staging call fails outright, which is why the feature is Paper/Purpur/Leaf/Folia only.

## Commands and permissions

| Concern | Bukkit | Modded |
|---------|--------|--------|
| Parser | VolmLib Director; `key=value` optionals in any order | Brigadier; positional arguments and bare flag literals |
| Root aliases | `iris`, `ir`, `irs` | `iris`, with `ir` and `irs` registered as redirects |
| Staff gate | `iris.all` (declared in `plugin.yml` and `paper-plugin.yml`, default `op`) — required for every `/iris` subcommand | `LEVEL_GAMEMASTERS` for anything that mutates, downloads, opens Studio, or starts a pregen |
| Open to any player | Nothing | `LEVEL_ALL`: `help`, `version`, `info`, `worlds`, `height`, `metrics` (alias `measure`), and the whole `what` subtree |
| Deliberately gated reads | — | `seed` and `accesslist` stay at gamemaster level even though `worlds` shows similar output without the seed field |
| Tree feller | `iris.treefeller` (`plugin.yml` and `paper-plugin.yml`, default `op`) | Fabric `irisworldgen:treefeller`; Forge and NeoForge PermissionAPI node `irisworldgen.treefeller`, defaulting to gamemaster level |
| Help | Director mini-menu | `ModdedCommandHelp` sections with clickable pages |

Full command tables and stubs: `04 - Commands & Permissions.md`.

## Feature matrix

| Feature | Bukkit | Fabric | Forge | NeoForge |
|---------|--------|--------|-------|----------|
| Core terrain / biomes / objects / jigsaw | yes | yes | yes | yes |
| Saved planar/spatial Iris jigsaw runtime | yes | yes | yes | yes |
| Jigsaw Studio (`/iris jigsaw` authoring tree) | yes | not registered | not registered | not registered |
| Pack validate / cleanup / restore / status | yes | yes | yes | yes |
| Pack download (`/iris download`, root-level on both) | yes | yes | yes | yes |
| Exact restart replacement of configured Overworld/Nether/End slots | Paper/Purpur/Leaf/Folia | no | no | no |
| Pregen | yes (Paper-like / Folia modes) | yes (`moddedPregenInFlight`) | yes | yes |
| Studio open / close / vscode / package | yes | yes | yes | yes |
| Studio importvanilla | yes | message: run on Bukkit | same | same |
| Studio loot GUI / entity spawn / profile / objects report | yes | message only | message only | message only |
| Object wand / paste / save / undo | yes | yes | yes | yes |
| Object contract / shift selection | yes | yes | yes | yes |
| Object expand selection | no | yes | yes | yes |
| Object WorldEdit import (`we`) | yes (WorldEdit soft depend) | message only | message only | message only |
| Object studio world | yes | message only | message only | message only |
| Schematic convert (`.schem` → `.iob`) | yes | message only | message only | message only |
| Structure import / capture | yes (v26 NMS binding) | message only | message only | message only |
| Structure list / info / place / verify | yes | yes | yes | yes |
| Datapack Modrinth ingest / list / remove | yes | message only | message only | message only |
| Dimension-type datapack install / status | not applicable | yes | yes | yes |
| PlaceholderAPI | soft depend | no | no | no |
| Multiverse-Core | soft depend / loadbefore | no | no | no |
| Item plugins (ItemsAdder, Mythic, and similar) | paper soft deps | loader-specific / limited | limited | limited |
| Public API package `art.arcane.iris.api` | plugin jar | see `94 - API - Modded.md` | same | same |
| Client HUD / protocol | optional client mod | optional client mod | optional | optional |
| Tree feller | settings + `iris.treefeller` | settings + loader permission | same | same |
| Auto Spigot/Paper timeout and watchdog config | yes | no | no | no |
| Custom biome restart prompts | yes (`iris.all` / op) | different datapack flow | same | same |

"Message only" means the command exists and prints an explanation of where to run it instead — it is not a silent failure.

Jigsaw pack resources are shared runtime data; only the in-game authoring surface is Bukkit-only. Bukkit exposes one global Studio project and world and one owning Jigsaw session: non-owner block, inventory, interaction, and mutating-command changes are cancelled across that Studio world, while autosave and graph-operation barriers serialize the owner's changes. On Folia a save schedules every intersecting chunk snapshot on its owning region and writes only after the complete capture validates. These protections have automated coverage but still need the live multi-region runbook in `31 - Operator Runbooks.md`. A strict `VANILLA_PORTABLE` export targets unmodded Minecraft 26.2 and is a separate compatibility gate.

## Platform-sensitive settings

| Setting | Where it matters |
|---------|------------------|
| `pregen.runtimeSchedulerMode` | Bukkit only; resolved from Bukkit/Folia detection, and a regionized runtime always resolves to Folia |
| `pregen.paperLikeBackendMode` | Bukkit only; ticket versus service chunk acquisition |
| `pregen.moddedPregenInFlight` | Mod loaders only; concurrent pregen chunk budget |
| `autoConfiguration.*` | Bukkit only; Spigot keep-alive, Paper watchdog, custom-biome restart |
| `world.worldEditWandCUI` | Bukkit only; requires WorldEdit |
| `general.autoIngestDatapacks` / `general.autoImportDatapackStructures` | Bukkit datapack ingest pipeline is the primary consumer |
| `gui.useServerLaunchedGuis` | Both, but the host implementation differs (`BukkitGuiHost` versus `ModdedGuiHost`) |

`modded.json` keys exist only on mod loaders.

## Integrations

| Integration | Bukkit | Modded |
|-------------|--------|--------|
| WorldEdit | soft depend; object `we` import | not wired |
| Multiverse-Core | load order / link | not used |
| PlaceholderAPI | `%iris_…%` | no |
| MythicMobs and item plugins | paper-plugin optional deps | not the Bukkit pipeline |
| Tree feller | plugin permission | loader permission node |

See `28 - Integrations.md` and `09 - PlaceholderAPI.md`.

## NMS and version binding

- The Bukkit plugin binds to a specific Paper/CraftBukkit revision (the in-tree v26 NMS module).
- Structure import and capture, and the vanilla import studio path, require that binding. This is why they cannot be ported to mod loaders as-is.
- Mod adapters use Minecraft mappings for the same game version line, without the Bukkit plugin APIs.

## Determinism and parity

GoldenHash exists on both surfaces; only the command placement differs (Bukkit under `developer`, modded at the root). Use disposable worlds. Bukkit exposes mantle-reset and deep-dump options that modded does not, and modded always resets mantle. Cross-platform comparisons always emit a Minecraft-version warning because the two platforms report the version string differently. Full procedure: `32 - Determinism & Goldenhash.md`.

## Moving a pack between platform families

1. Freeze the pack bytes and seed. Validate and package on the source platform.
2. Finish anything Bukkit-only first: structure and vanilla imports, schematic conversion, WorldEdit imports, and Jigsaw Studio work. Complete the atomic saves before copying.
3. Copy only `packs/<key>/` into the destination packs root. Never copy a Bukkit world folder into a modded world or the reverse.
4. Restart so destination registries and forced datapacks are built before any world is created.
5. Align the `settings.json` keys that affect generation (`generator`, `performance`, `treeFeller`). Bukkit-only `autoConfiguration` keys can be ignored.
6. On mod loaders, set `modded.json` `primaryWorld` if you need overworld replacement.
7. Re-run `/iris pack validate`, then `/iris datapack status` on modded or the ingest flow on Bukkit.
8. Create a disposable world with the same seed and run the same small GoldenHash inputs plus the platform's fresh-install runbook.

The move passes when validation, world creation, restart, and the deterministic comparison all pass. Matching screenshots are useful context, but the hash comparison is what counts.

## Related

- `01 - Installation & Platforms.md`
- `03 - Configuration.md`
- `04 - Commands & Permissions.md`
- `06 - Worlds & Lifecycle.md`
- `07 - Pregeneration.md`
- `21 - Jigsaw Structures.md`
- `22 - Native Structures & Datapacks.md`
- `28 - Integrations.md`
- `29 - Client HUD & Protocol.md`
- `31 - Operator Runbooks.md`
- `32 - Determinism & Goldenhash.md`
- `94 - API - Modded.md`

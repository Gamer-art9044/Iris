# 01 - Installation & Platforms

Iris installs as either a Bukkit-family plugin jar or a self-contained Fabric, Forge, or NeoForge mod jar. Java 25 is required on every platform. On first boot the default `overworld` pack is downloaded when missing; packs live under each platform’s data directory.

## Installation outcome

Complete one platform path below. A successful install has all three results:

1. Iris reaches its enabled/ready state without an exception.
2. The platform data directory contains `settings.json` and a loadable `packs/overworld/` directory.
3. `/iris` prints help from the server console. On a modded client, the Iris keybind category is an additional client-side check, not a substitute for the server check.

Keep the previous jar/mod and the entire Iris data directory until the new build passes these checks. Replacing the binary does not update pack snapshots already stored inside worlds.

## Requirements

| Requirement | Value |
|---|---|
| Java | 25 (`--release 25` / `java >= 25` on mod loaders) |
| Minecraft (plugin) | 26.1.2 – 26.2 (`api-version` 26.1) |
| Minecraft (mod) | 26.2 |
| Fabric Loader | 0.19.3+ |
| Forge | 65.0.4+ |
| NeoForge | 26.2.0.12-beta+ |
| Network | Outbound HTTPS on first boot for default pack download (GitHub IrisDimensions overworld release / pack install) |

Before replacing an existing installation:

1. Run `java -version` and confirm the server is using Java 25, not merely that Java 25 is installed elsewhere.
2. Match the jar label to the target platform and Minecraft version.
3. Stop the server cleanly.
4. Back up the existing Iris jar/mod, Iris data directory, and every Iris world you intend to retain.

Do not copy multiple Iris platform jars into the same `plugins/` or `mods/` directory.

## Plugin install (Paper / Purpur / Leaf / Canvas / Folia / Spigot)

1. Place the CraftBukkit-labelled plugin jar into `plugins/`.
2. Start the server. Iris loads at `STARTUP` (`plugin.yml` / `paper-plugin.yml`).
3. On first boot Iris provisions the default `overworld` pack into `plugins/Iris/packs/overworld` when missing (source: IrisDimensions overworld `beta` release zip).
4. Settings are written at `plugins/Iris/settings.json` if absent (`IrisSettings.read()`).

Validate the plugin install from the server console:

```text
/iris version
/iris pack validate pack=overworld
```

The first command must report the running Iris, platform, and Minecraft versions. The second must resolve the downloaded pack and finish without blocking validation errors. Then complete the disposable-world workflow in `02 - Getting Started.md`; a command response alone does not prove that the generator can create chunks.

Iris denies player login until managed external datapacks and installed dimension packs complete startup validation. Unchanged validated datapacks and packs reuse their persisted content/context results; a failed external datapack state keeps login and all Iris world creation locked, while an install or repair that changes registry inputs requires the clean restart Iris reports. A dimension pack with blocking errors remains unavailable to world and Studio creation without preventing healthy validated packs from being used.

Command root: `/iris` (aliases `/ir`, `/irs`). Explicit permission in the descriptor: `iris.treefeller` (default op). Command access uses the Director permission model rooted at `iris.all` (see `04 - Commands & Permissions.md`).

Soft dependencies (optional, not bundled): PlaceholderAPI, CraftEngine, Nexo, ItemsAdder, SCore, ExecutableItems, MythicLib, MMOItems, eco, EcoItems, MythicMobs, MythicCrucible, KGenerators, WorldEdit. Multiverse-Core is ordered after Iris so Multiverse sees Iris generators after Iris is up.

Before creating a real world, run the Bukkit fresh-install smoke in `31 - Operator Runbooks & Smoke Tests.md`. If the first-boot pack download fails, fix network access and restart; do not create an empty directory named `overworld` as a workaround because an incomplete pack is not a usable dimension.

### Folia note

`folia-supported: true`. Engine work uses region-safe scheduling. Runtime `/iris create` does **not** hot-create a live world on Folia: Iris stages world files, pack snapshot, and `bukkit.yml` registration, then requires a server restart before the world generates and loads. After restart, use `/iris load` or rely on the registered world entry as appropriate. See `06 - Worlds & Lifecycle.md`.

## Mod install (Fabric / Forge / NeoForge)

1. Place the matching mod jar into `mods/`.
2. Start the dedicated server (or a client for singleplayer; see below).
3. The jar is self-contained: core, SPI, and required Fabric API modules are bundled where applicable. Mod id: `irisworldgen`.
4. On first boot, if `config/irisworldgen/modded.json` has `autoDownloadDefaultPack` true (default) and `defaultPack` (default `overworld`) is missing, Iris downloads `IrisDimensions/<pack>` (branch `master` for the auto-prefetch path) into the packs folder before the forced worldgen datapack is written.

Validate the server-side mod install:

```text
/iris version
/iris pack validate overworld
```

The install passes when Iris reports the expected loader/version, `config/irisworldgen/packs/overworld/dimensions/` contains a dimension JSON file, and validation has no blocking errors. Restart once before creating a world if the pack or its generated dimension-type datapack was installed during this boot.

Packs installed later register custom dimension types (height ranges) and custom biomes through the forced datapack at server start. **Restart once after adding a pack** so worlds get full heights and biomes. Worlds created before that restart run with fallback heights.

### Singleplayer (modded clients)

Installed Iris packs appear as selectable World Types on the Create New World screen (`IRIS:<Pack>` style presets from the forced datapack). The integrated server runs the same engine as dedicated servers.

### Client HUD

Installing the mod jar on a client adds a pregeneration HUD (progress bar, chunks done/total, percent, chunks/s, ETA; yellow while paused). Key `H` (rebindable, category “Iris”) toggles it. The HUD talks to modded Iris servers and Bukkit/Paper Iris over channel `irisworldgen:main` (custom payloads on modded, plugin messaging on Bukkit). Vanilla clients are unaffected and get the server-side boss bar instead; on non-Iris servers the client mod is inert.

## Data directories

### Plugin (`plugins/Iris/`)

| Path | Role |
|---|---|
| `plugins/Iris/settings.json` | Engine settings (`IrisSettings`); created with defaults on first read |
| `plugins/Iris/packs/<key>/` | Installed packs (workspace name `packs`) |
| `plugins/Iris/bootstrap/` | Default-pack provision marker and related bootstrap state |
| `plugins/Iris/datapacks/` | Datapack download cache / staging (Bukkit datapack tooling) |
| `plugins/Iris/languages/overrides/<locale>.json` | Optional server message overrides |
| `<world>/iris/pack/` | Per-world pack **snapshot** used by production engines |

World dimension roots for managed Iris worlds are under the server’s world container (Iris managed dimension storage); see `06 - Worlds & Lifecycle.md`.

### Mod (`config/` relative to the game instance)

| Path | Role |
|---|---|
| `config/irisworldgen/packs/<pack>/` | Installed packs; valid when `dimensions/<dimension>.json` exists |
| `config/irisworldgen/generated/datapack/iris/` | Generated forced datapack (owned by Iris; do not edit) |
| `config/irisworldgen/modded.json` | Mod-side config: `defaultPack`, `autoDownloadDefaultPack`, primary world routing, main-world override |
| `config/iris/` | Engine data directory: `settings.json` and per-world engine state via `dataFile` |

Pack resolution for engines, commands, and the forced datapack uses `config/irisworldgen/packs`. The engine data folder is `config/iris` — different roots.

### Default `modded.json` keys

| Key | Default | Effect |
|---|---|---|
| `defaultPack` | `overworld` | Pack auto-download and default create pack name |
| `autoDownloadDefaultPack` | `true` | Async prefetch at boot when pack missing |
| `primaryWorld` | `""` | Primary-world router target dimension id |
| `routePlayersToPrimaryWorld` | `true` | Route players from vanilla overworld when primary is set |
| `mainWorldPack` | `""` | Main-world generator override pack ref |
| `mainWorldSeed` | `0` | Seed for main-world override |
| `mainWorldAutoRestart` | `false` | Auto-restart related to main-world override |

## Platform defaults (settings)

`IrisSettings` is shared across platforms. Generator default relevant to install and first world:

| Key path | Default | Effect |
|---|---|---|
| `generator.defaultWorldType` | `overworld` | Bukkit `/iris create` resolves `type=default` to this pack/dimension key |
| `general.language` | `en_US` | Server locale selection |
| `studio.openVSCode` | `true` | Whether studio may launch VSCode |
| `studio.autoStartDefaultStudio` | `false` | Do not auto-open studio on boot |

Full key list: `03 - Configuration.md`.

## First boot pack download

| Platform | Behavior |
|---|---|
| Plugin | `DefaultPackBootstrapProvisioner` downloads `https://github.com/IrisDimensions/overworld/releases/download/beta/overworld.zip` into `packs/overworld` when not already provisioned |
| Mod | If `autoDownloadDefaultPack` and pack missing, async install of configured `defaultPack` into `config/irisworldgen/packs` |

Manual install: `/iris download <pack>` (alias `dl`). Default overworld uses the beta-release path; other packs use `IrisDimensions/<pack>/<branch>` (plugin default branch `stable` for non-default; mod download defaults branch `stable` unless auto-prefetch uses `master` — see `25 - Pack Management.md`).

## Installation recovery

| Symptom | Check | Recovery |
|---|---|---|
| Iris does not appear in `/iris version` | Wrong directory, wrong platform jar, duplicate jar, Java mismatch, or an enable exception | Stop the server, keep only the matching artifact, confirm Java 25, and fix the first Iris exception in the startup log |
| `settings.json` exists but `packs/overworld` does not | Default-pack download failed or is still incomplete | Restore outbound HTTPS or install a complete pack, then restart; do not create an empty `overworld` folder |
| Pack validates but modded height/biomes use fallbacks | Forced datapack was generated after registries loaded | Restart once with the pack already installed, then create a new disposable world |
| Bukkit command is denied for a non-op | `iris.all` is missing | Grant `iris.all`; `iris.treefeller` controls only survival tree felling |
| Client HUD is absent but server commands work | Client mod missing, disabled keybind, or server capability not negotiated | Install the matching client mod, reconnect, and verify the Iris keybind category; server generation does not require the client HUD |
| Existing world ignores a newly installed pack | Production world is using its stored snapshot | Follow the explicit snapshot update or new-world workflow in `06 - Worlds & Lifecycle.md` and `25 - Pack Management.md` |

## Native worldgen over Iris terrain

Iris replaces the chunk generator. Vanilla and mod worldgen only runs where Iris runs it. Identical on every platform:

| Vanilla / mod worldgen | Over Iris terrain | Control |
|---|---|---|
| Structures (vanilla, datapack, mod) | Yes, on by default | `importedStructures.disabled` denies individual keys |
| Placed features: ores, trees, plants, springs, geodes | Yes, **off by default** | `importedFeatures.enabled` per dimension, with per-step and per-key filters |
| Carvers (caves, canyons, mod carvers) | Never | No `NoiseGeneratorSettings` for a carver to sample; use pack `caves` / `carvings` |
| Surface builders and surface rules | Never | Iris builds surfaces from pack palettes |
| Mod biomes | Only as `derivative`, `vanillaDerivative`, `biomeScatter`, or `biomeSkyScatter` target | Iris chooses biomes from the pack |
| Mob spawning, including mod mobs | Yes | Biome spawn tables merged with the vanilla derivative’s |

With `importedFeatures` off (default), chunk output is the pure Iris result. Full control reference: `94 - API - Modded.md` (also applies conceptually on Bukkit for imported native stages).

Independently of that flag, Iris custom biomes inherit biome tags of their vanilla derivative on every platform, so tag-driven content (`#minecraft:is_overworld`, mod spawn rules, etc.) applies to Iris custom biomes.

## Build artifacts

From repo root with JDK 25:

```
./gradlew buildAllToOut
```

Output under `dist/`:

| Pattern | Platform |
|---|---|
| `Iris v… [CraftBukkit] ….jar` | Plugin |
| `Iris v… [Fabric] ….jar` | Fabric |
| `Iris v… [Forge] ….jar` | Forge |
| `Iris v… [NeoForge] ….jar` | NeoForge |

Next: create a world and open studio in `02 - Getting Started.md`. Settings detail in `03 - Configuration.md`.

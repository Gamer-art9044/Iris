# 01 - Installation & Platforms

Iris ships as one Bukkit-family plugin jar and three self-contained mod jars (Fabric, Forge, NeoForge). This page gets the right artifact onto your server, tells you how to prove the install actually worked, and documents where Iris puts its files on each platform. Java 25 is required everywhere. On first boot Iris downloads the managed `overworld` and `underworld` beta packs if they aren't already present.

Read this before `02 - Getting Started.md`. If Iris is already installed and you just want a world, skip ahead.

## What a good install looks like

Whichever path you take, you're done when all three of these are true:

1. Iris reached its enabled/ready state with no exception in the startup log.
2. The data directory has a `settings.json` and loadable `packs/overworld/` and `packs/underworld/` directories.
3. `/iris` prints help from the server console.

On a modded client the Iris keybind category showing up is a nice extra signal, but it proves the client mod loaded — not that the server can generate chunks. Always check server-side.

Keep the old jar and the entire Iris data directory until the new build passes these checks. Swapping the binary does **not** update pack snapshots already copied into existing worlds; that's a separate operation covered in `06 - Worlds & Lifecycle.md`.

## Requirements

| Requirement | Value |
|---|---|
| Java | 25. The mod jars declare `java >= 25` and refuse to load on anything older |
| Minecraft (plugin) | 26.1.2 – 26.2. One jar covers both; `api-version` is pinned to 26.1 so it loads on the older line too |
| Minecraft (mod) | 26.2 only |
| Fabric Loader | 0.19.3+ |
| Forge | 65.x (built and tested against 26.2-65.0.4) |
| NeoForge | 26.2.x (built and tested against 26.2.0.12-beta) |
| Network | Outbound HTTPS to `github.com` on first boot, to fetch the IrisDimensions Overworld and Underworld beta release assets |

Before replacing an existing installation:

1. Run `java -version` **on the server** and confirm it reports 25. Having Java 25 installed somewhere on the box is not the same as the server process using it.
2. Match the jar label to the platform and Minecraft version you're running.
3. Stop the server cleanly.
4. Back up the Iris jar/mod, the Iris data directory, and every Iris world you intend to keep.

Never put two Iris platform jars in the same `plugins/` or `mods/` folder. That fails in confusing ways rather than picking a winner.

## Plugin install (Paper / Purpur / Leaf / Canvas / Folia / Spigot)

1. Drop the CraftBukkit-labelled plugin jar into `plugins/`.
2. Start the server. Iris loads at `STARTUP`, before worlds are created, because it has to register generators first.
3. First boot writes `plugins/Iris/settings.json` with defaults if it's absent, and provisions `overworld` and `underworld` into `plugins/Iris/packs/` from their IrisDimensions `beta` release ZIPs when missing.

Then verify from the server console:

```text
/iris version
/iris pack validate pack=overworld
/iris pack validate pack=underworld
```

`/iris version` prints exactly one line — `Iris v<version> by Volmit Software`. That's the whole output; it does not report platform or Minecraft version, so use it only as a "the command tree is alive" check. Each `pack validate` must resolve the downloaded pack and finish with no blocking errors.

Pass an explicit pack name. `/iris pack validate` with no argument fails with a missing-argument error rather than validating everything, because the parameter is required. To validate every installed pack, pass the key with an empty value: `/iris pack validate pack=`.

A command responding is not proof the generator can produce chunks. Finish with the disposable-world walkthrough in `02 - Getting Started.md`.

### Startup validation gates login

Iris blocks player login until external datapack validation and dimension-pack validation both complete. The kick message names the reason and tells you to check the console.

The two gates behave differently. A failed or restart-pending **external datapack** state keeps login locked and blocks all Iris world creation until you fix it and restart — Iris tells you when a restart is what's required. Unchanged, already-validated datapacks and packs reuse their persisted results, so this costs nothing on a normal boot. A **dimension pack** with blocking errors does *not* lock the server: that one pack is refused for world and Studio creation, an error listing the reasons is printed at startup, and every healthy pack stays usable.

### Permissions

The descriptor declares two permissions, both defaulting to op:

| Permission | Grants |
|---|---|
| `iris.all` | The entire `/iris` command tree — worlds, studio, pregen, packs, developer tools |
| `iris.treefeller` | Survival tree felling with an axe. Nothing else |

There is exactly one permission check, at the command root, against `iris.all`. Subcommands do not derive their own permission nodes — there is no `iris.all.pregen`. If a non-op needs any Iris command, they need `iris.all`, and that is all of it. See `04 - Commands & Permissions.md`.

Command root is `/iris`, aliases `/ir` and `/irs`.

### Soft dependencies

None of these are bundled or required. When present they load before Iris so Iris can see them: PlaceholderAPI, CraftEngine, Nexo, ItemsAdder, SCore, ExecutableItems, MythicLib, MMOItems, eco, EcoItems, MythicMobs, MythicCrucible, KGenerators, WorldEdit. Multiverse-Core is deliberately ordered *after* Iris so that Multiverse sees Iris generators once they're registered. Integration details in `28 - Integrations.md`.

### Folia

`folia-supported: true`, and engine work uses region-safe scheduling. The one behavioral difference that matters at install time: `/iris create` cannot build a live world at runtime on Folia. Instead it stages the world files, installs the pack snapshot, registers the world in `bukkit.yml`, and prints a message telling you to restart. After the restart the world generates and loads on its own from that `bukkit.yml` entry — you do not need to run `/iris load`. See `06 - Worlds & Lifecycle.md`.

### If the first-boot download fails

Fix network access and restart. Do not create an empty folder named `overworld` to silence the error: a folder without a `dimensions/*.json` inside it is treated as absent (and re-downloaded), and a partial pack is not a usable dimension. To install by hand later, use `/iris download overworld` and `/iris download underworld`.

Before you create a world you care about, run the Bukkit fresh-install runbook in `31 - Operator Runbooks.md`.

## Mod install (Fabric / Forge / NeoForge)

1. Drop the matching mod jar into `mods/`.
2. Start the dedicated server, or a client if you want singleplayer.
3. The jar is self-contained — engine, SPI, and the required Fabric API modules are bundled. Mod id is `irisworldgen` on all three loaders.
4. On first boot, if `autoDownloadDefaultPack` is true in `config/irisworldgen/modded.json` (it is by default), a daemon thread installs the managed `overworld` and `underworld` beta packs when missing, then a configured non-managed `defaultPack` if you've set one. Only after that does it write the forced worldgen datapack.

Verify server-side:

```text
/iris version
/iris pack validate overworld
/iris pack validate underworld
```

Modded `/iris version` prints more than the Bukkit one — mod version, platform, Minecraft version, and the count of loaded Iris dimensions. The install passes when that line looks right, both managed pack directories contain their primary dimension JSON, and validation reports no blocking errors.

### Restart once after installing a pack

This is the modded-specific gotcha. Packs register their custom dimension types (which set the world height range) and their custom biomes through the forced datapack, and that datapack is read when the server builds its registries at start. A pack installed during *this* boot may land after registries are already built.

So: **restart once after any pack is installed, before creating a world with it.** Worlds created before that restart run with fallback heights and will not have the pack's real height range or custom biomes. If a pack or its generated dimension-type datapack was installed during the current boot, restart before continuing.

### Singleplayer on a modded client

Installed Iris packs show up as selectable World Types on the Create New World screen, named `IRIS:<Pack>` (or `IRIS:<Pack> / <Dimension>` when a pack exposes more than one dimension). The integrated server runs the same engine as a dedicated one.

### Client HUD

Installing the mod jar on a client adds a pregeneration HUD showing a progress bar, chunks done and total, percent, chunks per second, and ETA, turning yellow while paused. `H` toggles it. The keybind category is "Iris" and also holds `M` (Iris Vision Map) and `J` (Iris What overlay); all three are rebindable. Details in `29 - Client HUD & Protocol.md`.

The HUD talks to both modded Iris servers and Bukkit/Paper Iris over channel `irisworldgen:main` — custom payloads on modded, plugin messaging on Bukkit. Vanilla clients are unaffected and get the server-side boss bar instead. On a non-Iris server the client mod is inert.

## Data directories

### Plugin

| Path | What lives there |
|---|---|
| `plugins/Iris/settings.json` | Engine settings. Written with defaults if absent, and rewritten on every read, so keys added by a new Iris version appear automatically with their defaults and your edits survive |
| `plugins/Iris/packs/<key>/` | Installed packs. This is the live tree the Studio reads and edits |
| `plugins/Iris/bootstrap/` | First-boot provisioning marker (`provisioned.properties`) recording what was installed and against which compiler identity |
| `plugins/Iris/datapacks/` | External datapack imports pulled from Modrinth by `/iris datapack`, plus a `staging/` subfolder used mid-download |
| `plugins/Iris/languages/overrides/<locale>.json` | Optional server message overrides. See `08 - Localization.md` |
| `<level-root>/datapacks/iris/` | The aggregate worldgen datapack Iris compiles from your installed packs. Iris owns this; do not hand-edit it |
| `<level-root>/dimensions/<namespace>/<name>/` | Storage for a managed Iris world. Namespace is `iris` for worlds Iris creates |
| `<world>/iris/pack/` | The per-world pack **snapshot**. A production engine reads only this copy, never `plugins/Iris/packs/` |

`<level-root>` is the server's level directory — the folder named by `level-name` in `server.properties`.

That last row is worth internalizing early: editing `plugins/Iris/packs/overworld/` has no effect on a world that already exists, because that world froze a copy of the pack at creation time. See `05 - Concepts & Pack Layout.md`.

### Mod

Paths are relative to the game instance's `config/` directory.

| Path | What lives there |
|---|---|
| `config/irisworldgen/packs/<pack>/` | Installed packs. A pack counts as installed when `dimensions/<dimension>.json` exists |
| `config/irisworldgen/generated/datapack/iris/` | The generated forced datapack (datapack id `iris_worldgen`), plus a hash sidecar used to detect staleness. Iris owns this; do not edit it |
| `config/irisworldgen/modded.json` | Mod-side config: default pack, auto-download, primary-world routing, main-world override |
| `config/iris/` | Engine data directory — `settings.json` and per-world engine state |

Two different roots, and mixing them up is a common mistake. Packs, the generated datapack, and mod config live under `config/irisworldgen/`. The shared engine's own data lives under `config/iris/`.

### `modded.json`

Written with these defaults on first read. If the file is unparseable Iris logs an error and falls back to defaults without rewriting it, so a syntax error is silent apart from the log line — check the log if a setting seems ignored.

| Key | Default | What it does |
|---|---|---|
| `defaultPack` | `overworld` | The pack `/iris create` uses when you don't name one, and the extra pack auto-download will fetch beyond the two managed betas |
| `autoDownloadDefaultPack` | `true` | Whether boot installs missing packs at all. Set false for air-gapped servers where you place pack folders by hand |
| `primaryWorld` | `""` | Dimension id players get routed into. Empty means no routing. Set by `/iris world replace-overworld` rather than by hand |
| `routePlayersToPrimaryWorld` | `true` | Whether the routing above actually happens. Set false to keep a primary world configured but stop moving players into it |
| `mainWorldPack` | `""` | Pack whose generator replaces the vanilla main world. Empty means the vanilla overworld is untouched |
| `mainWorldSeed` | `0` | Seed for that main-world override |
| `mainWorldAutoRestart` | `false` | When true, `/iris world mainworld` halts the server immediately so the override takes effect. Leave false unless you have a supervisor that restarts the process |

Only `/iris world replace-overworld`, `/iris world disable|delete` on the primary, and `/iris world mainworld` rewrite this file at runtime.

## Settings that affect install and first world

`IrisSettings` is shared across every platform; only the file location differs. The three keys most likely to matter before your first world:

| Key path | Default | When you'd change it |
|---|---|---|
| `generator.defaultWorldType` | `overworld` | Bukkit `/iris create` resolves `type=default` through this. Point it at your own pack so plain `/iris create <name>` produces your world instead of the stock overworld |
| `general.language` | `en_US` | Server-side message locale. See `08 - Localization.md` |
| `studio.openVSCode` | `true` | Set false on a headless box so `/iris studio vscode` writes the workspace file without trying to launch an editor |

Full key list: `03 - Configuration.md`.

## First-boot pack download

| Platform | What happens |
|---|---|
| Plugin | `DefaultPackBootstrapProvisioner` installs the Overworld and Underworld beta release assets into `packs/overworld` and `packs/underworld` independently, then compiles the aggregate datapack once |
| Mod | If `autoDownloadDefaultPack` is on, a daemon thread installs both managed beta packs plus any distinct configured `defaultPack` into `config/irisworldgen/packs`, then regenerates the forced datapack |

Manual install is `/iris download <pack>` (alias `dl`). The two managed packs are special-cased: `overworld` and `underworld` always come from their pinned beta release assets and **ignore the `branch` argument entirely**. Any other pack resolves as `IrisDimensions/<pack>/<branch>`, with `branch` defaulting to `stable`.

The managed-pack match is case-sensitive, so `/iris download Overworld` misses the special case and tries `IrisDimensions/Overworld/stable` instead. Use lowercase.

One more branch inconsistency to be aware of on modded: when `/iris create` auto-downloads a pack that isn't installed, and when boot fetches a non-managed `defaultPack`, both use the `master` branch — not `stable`. Only the explicit `/iris download` command defaults to `stable`. Pin the branch explicitly if it matters. See `25 - Pack Management.md`.

## When the install goes wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| `/iris version` does nothing | Wrong directory, wrong platform jar, a duplicate Iris jar, Java below 25, or an exception during enable | Stop the server, leave exactly one matching artifact in place, confirm Java 25, then fix the **first** Iris exception in the startup log — later ones are usually fallout |
| `settings.json` exists but a managed pack is missing | The beta download failed or is still running | Restore outbound HTTPS or drop in the complete release pack, then restart. Do not create an empty pack folder to paper over it |
| Players are kicked at login with an Iris message | Startup validation hasn't passed | Read the reason in the kick text and the console. External datapack failures lock login; fix the datapack state and restart |
| Pack validates, but modded heights and biomes are wrong | The forced datapack was generated after registries had already loaded | Restart once with the pack already on disk, then create a fresh disposable world to confirm |
| A non-op can't run any Iris command | `iris.all` isn't granted | Grant `iris.all`. `iris.treefeller` only covers survival tree felling and grants no commands |
| Client HUD missing but server commands work | Client mod absent, keybind unbound, or capability not negotiated | Install the matching client mod, reconnect, check the Iris keybind category. Server-side generation never depends on the client HUD |
| An existing world ignores a newly installed pack | The world is reading its frozen snapshot | Use the explicit snapshot update or create a new world — `06 - Worlds & Lifecycle.md` and `25 - Pack Management.md` |

## Native worldgen over Iris terrain

Iris replaces the chunk generator outright, so vanilla and mod worldgen only runs where Iris explicitly runs it. This behaves the same on every platform.

| Vanilla / mod worldgen | Runs over Iris terrain? | Control |
|---|---|---|
| Structures (vanilla, datapack, mod) | Yes, on by default | Deny families with `importedStructures.disabled`, or one complete key with `importedStructures.disabledExact` |
| Placed features: ores, trees, plants, springs, geodes | Yes, but **off by default** | `importedFeatures.enabled` per dimension, with per-step (`steps` / `disabledSteps`) and per-key (`disabled`) filters |
| Carvers (caves, canyons, mod carvers) | Never | There's no `NoiseGeneratorSettings` for a carver to sample against. Use the pack's `caves` and `carvings` instead |
| Surface builders and surface rules | Never | Iris builds surfaces from pack palettes |
| Mod biomes | Only as a `derivative`, `vanillaDerivative`, `biomeScatter`, or `biomeSkyScatter` target | Iris always picks the biome from the pack |
| Mob spawning, including mod mobs | Yes | Biome spawn tables are merged with the vanilla derivative's |

With `importedFeatures` off — the default — chunk output is pure Iris. The full control reference is `94 - API - Modded.md`, which also applies conceptually on Bukkit for the imported native stages.

Separately from that flag, Iris custom biomes inherit the biome tags of their vanilla derivative on every platform. Tag-driven content such as `#minecraft:is_overworld` and mod spawn rules therefore applies to Iris custom biomes without any extra configuration.

## Build artifacts

If you're building rather than downloading, from the repo root with JDK 25:

```text
./gradlew buildAllToOut
```

Four jars land in `dist/`. The CraftBukkit jar's version token is the supported Minecraft *range*; the loader jars use `<mc>+<loader>`:

| Pattern | Platform |
|---|---|
| `Iris v<version> [CraftBukkit] 26.1.2-26.2.jar` | Plugin (whole Bukkit family, including Folia) |
| `Iris v<version> [Fabric] 26.2+<loader>.jar` | Fabric |
| `Iris v<version> [Forge] 26.2+<loader>.jar` | Forge |
| `Iris v<version> [NeoForge] 26.2+<loader>.jar` | NeoForge |

Per-platform build tasks and the developer build gate are in `00 - Overview.md`.

Next: create a world and open the Studio in `02 - Getting Started.md`. Every settings key is in `03 - Configuration.md`.

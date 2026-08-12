# 03 - Configuration

Iris keeps its shared runtime settings in `settings.json` under the platform data folder. On first boot Iris writes a full defaults file if one is missing, and every successful load rewrites the file so new keys appear with defaults. Bukkit adds `compat.json`; mod loaders add `modded.json`. See `01 - Installation & Platforms.md` for data paths and `33 - Performance Tuning.md` for how to measure a tuning change.

## What you actually need to change

The shipped defaults are correct for almost every server. Most operators only ever touch a handful of keys:

| You want to | Change |
|---|---|
| Run the server in another language | `general.language` |
| See why generation is behaving oddly | `general.debug`, or `/iris debug` |
| Stop Iris opening desktop windows on the host | `gui.useServerLaunchedGuis`, `studio.openVSCode` |
| Survive pregen on a memory-constrained box | `pregen.maxResidentTectonicPlates`, the `performance.*CacheSize` keys |
| Turn on the survival tree feller | `treeFeller.enabled` |
| Catch broken pack keys instead of silently ignoring them | `general.strictContentKeys` |

Everything else is either already right, only meaningful while diagnosing a specific problem, or inert on your platform. Each table below marks which is which.

## File locations

| Platform | Shared settings | Packs root | Platform-only config |
|----------|-----------------|------------|----------------------|
| Bukkit / Paper / Folia | `plugins/Iris/settings.json` | `plugins/Iris/packs/` | `plugins/Iris/compat.json` |
| Fabric / Forge / NeoForge | `<configDir>/iris/settings.json` | `<configDir>/irisworldgen/packs/` | `<configDir>/irisworldgen/modded.json` |

`<configDir>` is the loader config directory (game `config/` on Fabric, Forge, and NeoForge). Both surfaces use the same `IrisSettings` schema for `settings.json`.

The modded split is real and easy to get wrong: the engine data folder is `<configDir>/iris`, but installed packs, the generated datapack, and `modded.json` live under `<configDir>/irisworldgen`. Iris also creates an empty `<configDir>/iris/packs` directory; that is not the pack root and putting a pack there will not load it.

## Changing a setting safely

1. Start Iris once so it writes the current schema and defaults.
2. Copy `settings.json` outside the server directory as a rollback file.
3. Change one key. Keep its JSON type: quoted values such as `"false"` are strings, not booleans.
4. Save the file, then run `/iris reload` or wait for the hotload poll (about 3 seconds on both platforms).
5. Confirm the console logs `Hotloaded settings.json` or the reload success message, with no parse error.
6. Exercise the affected feature. If nothing changed, check the "Takes effect" column below — several keys are captured when a service, pool, or cache is constructed and need a restart.

If parsing fails, restore the saved file and restart. Do not delete `settings.json` unless resetting every setting to defaults is what you want.

To change only the server locale, edit the existing `general` object in place:

```json
{
  "general": {
    "language": "de_DE"
  }
}
```

That fragment shows the field location; do not replace a populated settings file with it. After `/iris reload`, run `/iris help` and confirm the selected locale is active. Iris rewrites the complete settings file after a successful load, including defaults for fields that were absent.

### Validation and rollback

| Result | Meaning | Action |
|---|---|---|
| Reload succeeds and the feature changes | The file parsed and the setting is read live | Keep the backup until the next clean restart |
| Reload succeeds but behavior is unchanged | The value was captured when a service, pool, or cache was built | Restart, then retest the same workload |
| Parse error in console, file unchanged | Gson threw before the rewrite, so your broken file is still on disk and Iris is running built-in defaults | Fix the JSON, reload; restore the backup if you cannot |
| File is rewritten with defaults | Missing or unknown fields were normalized by `IrisSettings` | Reapply only intentional overrides; do not restore an obsolete full file over new defaults |
| Modded and Bukkit paths differ | The wrong data root was edited | Use the path table above and confirm the file timestamp changed before reloading |

## Load, save, hotload

| Action | Behavior |
|--------|----------|
| First boot | Create `settings.json` with current defaults if the file is absent |
| Load | Parse with Gson into `IrisSettings`. On failure, log `Configuration Error in settings.json!` and run on built-in defaults for that boot — the bad file is left untouched, because the rewrite never runs |
| After a successful load | Rewrite `settings.json` as pretty JSON so new keys and migrated values persist. Comments and hand formatting are lost |
| `/iris reload` | Invalidate the cached settings, re-read the file, reload the locale. On modded it also schedules a forced datapack regeneration. It does not restart services, reload packs, or rebuild engines |
| Hotload (Bukkit) | `SettingsHotloadWatch` polls every 60 ticks (about 3 s) through the VolmLib `ConfigHotloadEngine`. A `lastModified` or size change triggers a read; the reload only runs if the normalized file content actually differs. Logs `Hotloaded settings.json` |
| Hotload (modded) | `ModdedSettingsHotloadService` polls `lastModified` every 3 s. Because a load rewrites the file, a touch with no edit still produces one reload (it does not loop). Logs `Hotloaded settings.json` |
| Locale refresh | Bukkit calls `IrisLanguage.update()` on every poll; modded calls it only when the file is unchanged, and calls a full `IrisLanguage.reload()` when it did change |
| `forceSave()` | Only `/iris debug` writes settings back from memory |

Legacy migration: if the raw JSON still contains `world.anbientEntitySpawningSystem`, the value is copied to `world.ambientEntitySpawningSystem` and logged once.

## Root object

Top-level Gson fields on `IrisSettings`. Every nested object is created with defaults when missing.

| Field | Nested class | Covers |
|-------|--------------|--------|
| `general` | `IrisSettingsGeneral` | Locale, debug output, console colors, datapack ingest, strict keys, splash |
| `world` | `IrisSettingsWorld` | Entity systems, async world tick, WorldEdit CUI, pregen cache |
| `gui` | `IrisSettingsGUI` | Server-launched desktop GUIs |
| `autoConfiguration` | `IrisSettingsAutoconfiguration` | Spigot/Paper server-file fixups, custom-biome restart |
| `generator` | `IrisSettingsGenerator` | Default pack for world creation, leaf decay |
| `concurrency` | `IrisSettingsConcurrency` | Nothing configurable — see below |
| `studio` | `IrisSettingsStudio` | Studio world behavior |
| `performance` | `IrisSettingsPerformance` | Mantle residency, loader caches, SIMD, engine service pool |
| `pregen` | `IrisSettingsPregen` | Pregen scheduling, mantle backpressure, timeouts |
| `sentry` | `IrisSettingsSentry` | Error reporter |
| `treeFeller` | `IrisSettingsTreeFeller` | Survival tree feller |

Static helper `IrisSettings.getThreadCount(int c)`: for `c` in `{-1, -2, -4}` it returns `max(availableProcessors / -c, 1)`; otherwise `max(c, 2)`, floored at 1.

## `general` — locale, diagnostics, and console output

This group decides what Iris says and how loudly. `language`, `debug`, and `strictContentKeys` are the ones worth touching; the colour and spin keys are cosmetic; the datapack keys change startup work on Bukkit only.

| Key | Default | Takes effect | What it does |
|-----|---------|--------------|--------------|
| `language` | `"en_US"` | Live | Selects the locale catalog for all Iris messages. Reloaded by `/iris reload` and by both hotload watchers |
| `commandSounds` | `true` | Live | **Bukkit only.** Plays the amethyst chime on `/iris` tab completion and success/failure sounds after a command. Turn off if the noise annoys staff |
| `debug` | `false` | Live | Enables verbose engine tracing on the console and writes per-chunk crash dumps under `debug/chunk-errors/`. Toggle with `/iris debug` rather than editing by hand; leave off in production because it is loud |
| `dumpMantleOnError` | `false` | Live | When a tectonic plate read reports an error, dump the decoded region to `dump/<name>.bin` instead of logging a timing line. Turn on only when investigating mantle corruption |
| `disableNMS` | `false` | **Restart** | **Bukkit only.** Forces the no-op NMS binding. Iris logs a warning and world creation stops working entirely, so this is a diagnostic escape hatch, not a compatibility switch. Read in a class initializer, so a reload will not change it |
| `pluginMetrics` | `true` | **Restart** | **Bukkit only.** Registers the bStats reporter at enable |
| `splashLogoStartup` | `true` | **Restart** | Prints the ASCII logo and version block at startup. Set false for quieter console logs |
| `useConsoleCustomColors` | `true` | Live | Gradient/hex colouring for console output. Set false if your log viewer mangles it — you still get legacy colour codes. Iris also forces both colour keys off in memory if Adventure fails to bind |
| `useCustomColorsIngame` | `true` | Live | Same, for messages sent to players |
| `adjustVanillaHeight` | `false` | **Restart** | **Bukkit only.** Overwrites the vanilla `overworld`/`the_nether`/`the_end` dimension-type JSON with Iris height when compiling the datapack. It is part of the datapack fingerprint, so flipping it forces a datapack rebuild |
| `autoIngestDatapacks` | `true` | **Restart** | **Bukkit only.** Downloads and installs configured `datapackImports` during the startup admission gate. Unchanged committed content reuses its persisted result instead of revalidating; managed structures stay scoped to the declaring Iris dimensions |
| `autoImportDatapackStructures` | `false` | Live (next ingest) | **Bukkit only.** Converts every registered datapack structure into editable Iris pools, pieces, and objects — thousands of files in your pack folder. Native generation never needs those copies, so leave it off and run `/iris structure import <dimension>` when you actually want them |
| `strictContentKeys` | `false` | Live | Promotes unresolved pack content keys and bad block-state properties from warnings to blocking pack errors. Worth turning on while developing a pack. `-Diris.strictContent` overrides it in both directions, and the bare property with no value counts as true |
| `spinh` | `-20` | Live | Hue factor of the animated "aura" gradient on Iris text |
| `spins` | `7` | Live | Saturation factor of the same gradient |
| `spinb` | `8` | Live | Brightness factor of the same gradient |

## `world` — entity systems and the async world tick

Iris runs its own spawning and effects pass on a background loop, separate from vanilla mob spawning. These keys decide whether that loop does anything and how often. Turning the spawn systems off makes Iris worlds feel emptier but removes an entire class of tick cost; the defaults are the intended experience.

| Key | Default | Takes effect | What it does |
|-----|---------|--------------|--------------|
| `postLoadBlockUpdates` | `true` | Live | Runs a block-update pass over freshly generated chunks near players so placed objects settle (physics and waterlogging fixups). Turning it off is faster but leaves floating or unwatered blocks from some objects |
| `forcePersistEntities` | `true` | Live | Marks every Iris-spawned entity persistent so vanilla mob-cap and distance rules do not despawn it. Turn off if pack-spawned mobs are accumulating |
| `ambientEntitySpawningSystem` | `true` | Live | Enables the biome/region ambient spawn lists on the async tick (legacy key `anbientEntitySpawningSystem` is migrated automatically) |
| `asyncTickIntervalMS` | `700` | Live (next tick) | Milliseconds between world-manager passes that handle spawning, effects, and cleanup. Raise it to cut background cost on a busy server; lower it only if pack spawns feel too sparse |
| `targetSpawnEntitiesPerChunk` | `0.95` | Live | Entity saturation ceiling. Once entities per loaded chunk exceed this, Iris stops spawning (the Bukkit path also backs off for 5 seconds). Lower it on servers already near their entity budget |
| `markerEntitySpawningSystem` | `true` | Live | Enables spawning driven by mantle marker blocks, which is how packs place specific mobs at specific generated features |
| `effectSystem` | `true` | Live | Applies per-biome and per-region `IrisEffect`s (potion effects, particles, sounds) to players |
| `worldEditWandCUI` | `true` | Live | **Bukkit only.** Lets a WorldEdit selection act as an Iris wand and draws the particle outline for it |
| `globalPregenCache` | `false` | Live, one event late | **Bukkit only.** Maintains a persistent per-world bitmap of already-generated chunks so pregen can skip finished work across restarts. The enable/disable flip is observed on the following world-init or chunk-load event, not the current one |

With both `markerEntitySpawningSystem` and `ambientEntitySpawningSystem` false, the world manager skips all related entity work.

## `gui` — desktop windows launched by the server

Iris can open AWT windows on the machine running the server: the noise explorer, the vision map, and the pregen viewer. That is useful on a local dev box and wrong on a headless host, which is the only reason to touch this group.

| Key | Default | Takes effect | What it does |
|-----|---------|--------------|--------------|
| `useServerLaunchedGuis` | `true` | Live | Allows server-side GUI hosts to open windows. Set false on any remote or headless server; the commands then report that GUIs are unavailable instead of trying |
| `maximumPregenGuiFPS` | `false` | Live | Repaints the pregen map window as fast as possible instead of roughly four times a second. Only affects the local window, never generation throughput |
| `colorMode` | `true` | Per window open | Colour rendering in the noise explorer instead of grayscale. It is captured when the window opens, so close and reopen the explorer to apply a change |

## `autoConfiguration` — Bukkit server-file fixups

Iris edits a couple of server config files at boot so long chunk generation does not look like a hang to the server's own watchdogs. Leave these on unless you manage those files yourself. They are all Bukkit-only and all read once during enable.

| Key | Default | Takes effect | What it does |
|-----|---------|--------------|--------------|
| `configureSpigotTimeoutTime` | `true` | **Restart** | Raises `timeout-time` in `spigot.yml` so a long generation stall does not kill the server |
| `configurePaperWatchdogDelay` | `true` | **Restart** | Raises Paper's watchdog early-warning and timeout for the same reason |
| `autoRestartOnCustomBiomeInstall` | `true` | **Restart** | When a datapack install registers new custom biomes and reports that a restart is required, Iris restarts the server itself instead of waiting for an admin |

These keys are no-ops on mod loaders.

## `generator` — defaults for world creation

| Key | Default | Takes effect | What it does |
|-----|---------|--------------|--------------|
| `defaultWorldType` | `"overworld"` | Live | **Bukkit only.** The pack key used whenever a world, studio, or command omits one — including a bare `Iris` generator string in `bukkit.yml` and `/iris create name type=default`. Mod loaders use `defaultPack` in `modded.json` instead |
| `preventLeafDecay` | `true` | Effectively **restart** | Marks generated leaves persistent so they do not decay. The flag is baked into resolved block data that is then cached, so already-resolved leaf blocks keep the old behavior after a reload. Unrelated to the per-dimension `preventLeafDecay` field in pack JSON |

## `concurrency` — nothing to configure

This object has no serialized fields. Gson writes `{}`, and anything you type inside it is silently discarded the next time Iris saves the file. The values are derived from CPU count at runtime:

| Method | Result | Used by |
|--------|--------|---------|
| `getParallelism()` | `max(2, availableProcessors)` | Default `MultiBurst` pools, hybrid pregen thread count, locator searches |
| `getIoParallelism()` | `max(2, availableProcessors / 2)` | The shared IO burst pool |
| `getWorldGenThreads()` | `max(2, availableProcessors)` | Async pregen concurrency cap and the Moonrise worker-pool adjustment |

## `performance` — caches, mantle residency, and the engine service pool

This is the memory-versus-rework group. Larger loader caches trade heap for fewer pack reloads; mantle keys decide how long generated region data stays resident before being written out. Most keys here are captured when a pool or cache is built, so plan on a restart. Use `33 - Performance Tuning.md` for the measurement procedure — changing these blind usually makes things worse.

| Key | Default | Takes effect | What it does |
|-----|---------|--------------|--------------|
| `trimMantleInStudio` | `false` | Live | Lets the maintenance pass trim mantle in studio worlds. With the default `false`, studio engines skip the whole maintenance pass, which keeps edits responsive at the cost of growing memory during long authoring sessions |
| `mantleKeepAlive` | `30` | Live | Seconds a mantle plate stays resident before it is eligible for trimming. Scaled down automatically as reclaim pressure rises. Lower it when heap is tight, raise it if the same regions are reloaded repeatedly |
| `noiseCacheSize` | `1024` | Mixed | Capacity of the noise sample caches. The terrain query API picks it up live; the engine's own caches need an engine hotload or restart. Pregen temporarily raises it to at least 4096 in memory and does not lower it again or persist the change |
| `resourceLoaderCacheSize` | `1024` | **Restart / pack reload** | How many loaded pack resources stay cached per loader. Captured when a pack's `IrisData` is opened |
| `objectLoaderCacheSize` | `4096` | **Restart / pack reload** | Same, for `.iob` objects, matter objects, and images. Raise it for object-heavy packs when heap allows; lower it first when profiling shows retained pack data |
| `mantleCleanupDelay` | `200` | Live | Delay in **ticks** before a loaded chunk's mantle cleanup runs — the default is 10 seconds. Read from the raw field with no clamping, so a negative value is floored at 0 ms and a huge value really does postpone cleanup |
| `simdKernels` | `true` | **Restart** | Uses Vector API noise kernels when `jdk.incubator.vector` is on the module path, otherwise scalar fallbacks. Chosen once during class initialization, so toggling it and reloading does nothing, and it is silently inert without the JVM module flag |

### `performance.engineSVC`

The engine maintenance service is a small scheduled pool that trims and unloads mantle plates. Its three sizing keys are read once at enable, so a restart is required for any change to matter.

| Key | Default | Takes effect | What it does |
|-----|---------|--------------|--------------|
| `useVirtualThreads` | `true` | **Restart** | Builds the maintenance thread factory from virtual threads instead of platform threads |
| `forceMulticoreWrite` | `false` | Live | Makes every maintenance pass unload all eligible tectonic plates instead of only unloading under heap pressure. Trades steadier memory for more write work; useful during long pregens on a small heap |
| `priority` | `5` (`Thread.NORM_PRIORITY`) | **Restart** | Thread priority, clamped to `[MIN_PRIORITY, MAX_PRIORITY]`. It is applied only when `useVirtualThreads` is false, so with the shipped defaults this key does nothing |
| `parallelism` | `-1` | **Restart** | Maintenance pool size. `>0` is capped at `processors * 2`; `<=0` uses `ceil(sqrt(processors))`, at least 1 |

## `pregen` — scheduling, timeouts, and mantle backpressure

These keys bound how aggressively pregeneration pushes the server. They are read when a pregen job is constructed, so a change applies to the *next* job, not a running one. The two that matter in practice are `maxResidentTectonicPlates` (the memory ceiling) and, on mod loaders, `moddedPregenInFlight` (the concurrency ceiling). The rest exist for diagnosing a specific failure mode.

| Key | Default | Applies to | What it does and how it resolves |
|-----|---------|------------|----------------------------------|
| `runtimeSchedulerMode` | `AUTO` | Bukkit | `AUTO`, `PAPER_LIKE`, `FOLIA`. A regionized (Folia) runtime resolves to `FOLIA` before the setting is consulted, and off Folia a configured `FOLIA` is downgraded to `PAPER_LIKE`. Since `AUTO` also lands on `PAPER_LIKE` for every recognized and unrecognized fork, this key changes nothing in practice — the one exception is a non-regionized server that still identifies itself as Folia by name or version, where `AUTO` picks `FOLIA` and an explicit `PAPER_LIKE` does not |
| `paperLikeBackendMode` | `AUTO` | Bukkit, non-Folia | `AUTO`, `TICKET`, `SERVICE`. `SERVICE` uses the service executor (`paper-service`); `TICKET` and `AUTO` both use the ticket executor (`paper-ticket`). Ignored entirely on Folia. Try `SERVICE` only if ticket-based chunk loading is producing timeouts |
| `chunkLoadTimeoutSeconds` | `15` | Both | Clamped to `[5, 120]`. How long pregen waits for one chunk load before it counts as timed out. **On mod loaders the effective value is floored at 120**, so any value below that is ignored there |
| `timeoutWarnIntervalMs` | `500` | Bukkit | Minimum 250. Rate-limits the "timed out async pregen chunk load" and failed-release warnings so a bad run does not flood the log. Not read on mod loaders |
| `saveIntervalMs` | `30000` | Both | Clamped to `[5000, 900000]`. How often a running pregen flushes progress. Lower it if you expect to lose the process and want a closer resume point; the cost is more IO |
| `maxResidentTectonicPlates` | `96` | Both | Minimum 16. The mantle memory ceiling, and the first knob to lower on an out-of-memory pregen. The effective cap is also scaled by world height and by roughly 60% of the heap budget against a ~48 MB reference plate at height 384, with a floor of 16 — so on a small heap you may already be running below the configured number |
| `mantleBackpressureWaitMs` | `25` | Both | Clamped to `[5, 1000]`. Sleep granularity while pregen waits for resident plates to drop below the cap |
| `mantleBackpressureTimeoutMs` | `60000` | Both | Clamped to `[5000, 600000]`. How long that wait may last before Iris logs a backpressure warning and lowers its adaptive in-flight limit. Seeing this warning repeatedly means `maxResidentTectonicPlates` is too high for your heap, not too low |
| `moddedPregenInFlight` | `0` | Modded | Concurrent chunk budget for modded pregen. `>0` is capped at 512; `<=0` derives `max(16, min(48, cpu * 2))`. Lower it when modded pregen causes chunk-load timeouts or memory growth. Inert on Bukkit |

## `sentry` — error reporting

Read once during boot on both platforms, so every change here needs a restart.

| Key | Default | What it does |
|-----|---------|--------------|
| `includeServerId` | `true` | **Bukkit only.** Attaches the server id to reports so recurring reports from one server can be grouped. Not read on mod loaders |
| `disableAutoReporting` | `false` | Skips Sentry initialization entirely. Set true if you do not want automatic error reports leaving the machine |
| `debug` | `false` | Turns on Sentry's own debug logging. Useful only when reports are not arriving |

## `treeFeller` — survival tree felling

Off by default because it changes survival gameplay. Both keys are read live, so `/iris reload` is enough.

| Key | Default | What it does |
|-----|---------|--------------|
| `enabled` | `false` | Master switch. With it on, a permitted player breaking one log fells the whole Iris-managed tree. Disabling it mid-run cancels an in-flight fell on mod loaders only; the Bukkit runner does not re-read the setting once a fell has started |
| `durabilityPreservationChance` | `0` | Percent chance per block that the axe takes no durability, clamped to `[0, 100]`. An integration may override this per call |

Requires permission `iris.treefeller` on Bukkit, or the platform tree-feller node on mod loaders. See `04 - Commands & Permissions.md` and `28 - Integrations.md`.

## `studio` — authoring world behavior

Only two of the four keys in this section do anything today.

| Key | Default | What it does |
|-----|---------|--------------|
| `openVSCode` | `true` | Whether `/iris studio vscode` launches an editor after writing the workspace file. Set false on a headless box |
| `entitySpawning` | `true` | Whether mobs spawn inside studio worlds. Has no effect on normal worlds |
| `disableTimeAndWeather` | `true` | Nothing. Present in the settings model but not read by any code path today |
| `autoStartDefaultStudio` | `false` | Nothing. Present in the settings model but not read by any code path today |

Studio workflow details: see `10 - Studio & VSCode Schemas.md`.

## Bukkit-only: `compat.json`

On Bukkit, Iris loads `plugins/Iris/compat.json` at startup and writes the complete built-in table to `compat.default.json` next to it for reference. This is how you keep a pack working on a server that lacks some block or item it references: Iris substitutes the replacement instead of failing.

Built-in mappings always stay active; entries read from `compat.json` are appended to them. Both files are read once at boot — `/iris reload` does not re-read them, and mod loaders do not use them at all.

```json
{
  "blockFilters": [
    { "when": "example:missing_block", "supplement": "minecraft:stone", "exact": false }
  ],
  "itemFilters": [
    { "when": "example:missing_item", "supplement": "minecraft:stick" }
  ]
}
```

| Field | Applies to | Behavior |
|-------|------------|----------|
| `when` | block and item filters | The unsupported source key to match |
| `supplement` | block and item filters | The replacement key. If the replacement is also unsupported, Iris re-runs the lookup on it, up to 16 hops, and falls back to `STONE` with an error |
| `exact` | block filters only | When true, match the full key including namespace and block-state properties (`minecraft:some_log[axis=x]`). When false, match the bare material name. Item filters have no `exact` field |

A block substitution logs `Compat: Using '<supplement>' in place of '<when>' since this server doesnt support '<when>'` as a warning; item substitutions log the same at debug level. Invalid JSON logs the failure and leaves the built-in mappings active.

One quirk to know: when `compat.json` is absent, Iris seeds it with a copy of the entire built-in table. On the next boot those entries are appended to the built-ins again, so the runtime list holds every default twice. It is harmless because the first match wins, but if you are editing the file, delete the entries you did not add.

## Modded-only: `modded.json`

Path: `<configDir>/irisworldgen/modded.json`, written with defaults on first load if missing. Not used by the Bukkit plugin. Unlike `settings.json` this file is parsed by hand rather than Gson, is cached once, and has no hotload — a restart is required except for the keys that Iris rewrites itself. Malformed JSON logs `Iris modded config at … is invalid; using defaults` and runs on defaults **without** rewriting your file.

| Key | Default | What it does |
|-----|---------|--------------|
| `defaultPack` | `"overworld"` | Pack used by `/iris create` when none is given. A distinct non-managed value is also prefetched when auto-download is on |
| `autoDownloadDefaultPack` | `true` | Downloads the managed Overworld and Underworld beta packs when missing, plus any distinct configured default. Set false on an air-gapped server and install packs by hand |
| `primaryWorld` | `""` | Iris dimension id used for player routing |
| `routePlayersToPrimaryWorld` | `true` | Sends players to the primary world when one is set |
| `mainWorldPack` | `""` | Pack (or `pack:dimensionKey`) for the main-world preset |
| `mainWorldSeed` | `0` | Seed for the main-world preset |
| `mainWorldAutoRestart` | `false` | Restarts the server automatically after a main-world inject instead of telling you to |

`/iris world mainworld`, `/iris world replace-overworld`, and the primary-world clear paths write this file directly. See `06 - Worlds & Lifecycle.md` and `30 - Platform Differences.md`.

## What is not in these files

- Pack JSON (dimensions, biomes, objects) lives under `packs/<key>/` — see `05 - Concepts & Pack Layout.md`.
- Per-world studio and workspace files are generated under pack roots — see `10 - Studio & VSCode Schemas.md`.
- Locale files and overrides — see `08 - Localization.md`.

## Related

- `01 - Installation & Platforms.md`
- `04 - Commands & Permissions.md`
- `07 - Pregeneration.md`
- `25 - Pack Management.md`
- `30 - Platform Differences.md`
- `33 - Performance Tuning.md`

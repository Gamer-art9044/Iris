# 03 - Configuration

Iris stores shared runtime settings in `settings.json` under the platform data folder. On first boot Iris writes a full defaults file if missing; every successful load rewrites the file so new keys appear with defaults. See `01 - Installation & Platforms.md` for data paths and `33 - Performance Tuning.md` for tuning guidance.

## Tutorial: change one setting safely

1. Start Iris once so it writes the current schema and defaults.
2. Copy `settings.json` outside the server directory as a rollback file.
3. Change one key only. Keep its JSON type unchanged; quoted numbers and strings such as `"false"` are not booleans.
4. Save the file and use `/iris reload`, or wait for the platform hotload interval described below.
5. Confirm the console reports the settings reload without a parse exception.
6. Exercise the affected feature. For performance or thread-pool settings, restart before judging the result because some values are read when services are constructed.

If parsing fails, restore the saved file and restart. Do not delete `settings.json` unless resetting every setting to defaults is intentional.

For example, to change only the server locale, edit the existing `general` object in place:

```json
{
  "general": {
    "language": "de_DE"
  }
}
```

This fragment shows the field location; do not replace a populated settings file with the fragment. After `/iris reload`, run `/iris help` and confirm the selected locale is active. Iris rewrites the complete settings file after a successful load, including defaults for fields that were absent.

### Validation and rollback

| Result | Meaning | Action |
|---|---|---|
| Reload succeeds and the affected feature changes | File parsed and the setting reached a live reload path | Keep the backup until the next clean restart |
| Reload succeeds but behavior is unchanged | Setting is read only when a service or engine is constructed | Restart, then retest the same workload |
| Parse exception or requested locale rejected | JSON shape, type, or locale is invalid | Restore the backup, reload, and make one smaller edit |
| File is rewritten with defaults | Missing fields were normalized by `IrisSettings` | Reapply only intentional overrides; do not restore an obsolete full file over new defaults |
| Modded and Bukkit paths differ | The wrong data root was edited | Use the path table below and confirm the changed file timestamp before reloading |

## File locations

| Platform | Shared settings | Packs root | Modded-only config |
|----------|-----------------|------------|--------------------|
| Bukkit / Paper / Folia | `plugins/Iris/settings.json` | `plugins/Iris/packs/` | — |
| Fabric / Forge / NeoForge | `<configDir>/iris/settings.json` | `<configDir>/irisworldgen/packs/` | `<configDir>/irisworldgen/modded.json` |

`<configDir>` is the loader config directory (game `config/` for Fabric/Forge/NeoForge). Both surfaces use the same `IrisSettings` schema for `settings.json`.

## Load, save, hotload

| Action | Behavior |
|--------|----------|
| First boot | Create `settings.json` with current defaults if the file is absent |
| Load | Parse with Gson into `IrisSettings`; on parse failure log and keep empty defaults for that boot |
| After load | Rewrite `settings.json` (pretty JSON) so new keys and migrated values persist |
| `/iris reload` | Invalidate cached settings, re-read `settings.json`, reload locale |
| Hotload (Bukkit) | `SettingsHotloadWatch` via VolmLib `ConfigHotloadEngine`; on content change invalidates, reloads, reloads language, logs `Hotloaded settings.json` |
| Hotload (modded) | `ModdedSettingsHotloadService` polls every 3s; on `lastModified` change invalidates, reloads, reloads language, logs `Hotloaded settings.json` |
| Locale-only tick | Hotload paths also call `IrisLanguage.update()` when the file is unchanged |
| `forceSave()` | Used by `/iris debug` and similar toggles that mutate settings in memory |

Legacy migration: if raw JSON still has `world.anbientEntitySpawningSystem`, it is copied to `world.ambientEntitySpawningSystem` and logged once.

## Root object

Top-level Gson fields on `IrisSettings` (all nested objects are created with defaults when missing):

| Field | Nested class | Purpose |
|-------|--------------|---------|
| `general` | `IrisSettingsGeneral` | Language, debug, colors, datapack ingest, strict keys, splash |
| `world` | `IrisSettingsWorld` | Entity systems, async tick, WorldEdit CUI, pregen cache |
| `gui` | `IrisSettingsGUI` | Server-launched GUIs and pregen GUI options |
| `autoConfiguration` | `IrisSettingsAutoconfiguration` | Spigot/Paper timeout autoconfig, custom-biome restart |
| `generator` | `IrisSettingsGenerator` | Default world type, leaf decay |
| `concurrency` | `IrisSettingsConcurrency` | Runtime thread helpers only (no persisted fields) |
| `studio` | `IrisSettingsStudio` | Studio open/VSCode/weather/spawn defaults |
| `performance` | `IrisSettingsPerformance` | Mantle, caches, SIMD, nested engine SVC |
| `pregen` | `IrisSettingsPregen` | Pregen scheduler, mantle residency, timeouts |
| `sentry` | `IrisSettingsSentry` | Error reporter options |
| `treeFeller` | `IrisSettingsTreeFeller` | Survival tree feller enable and axe durability |

Static helper `IrisSettings.getThreadCount(int c)`: for `c` in `{-1,-2,-4}` returns `max(availableProcessors / -c, 1)`; otherwise `max(c, 2)` floored to at least 1.

## `general`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `language` | string | `"en_US"` | Active locale key; reloaded by `/iris reload` and hotload |
| `commandSounds` | boolean | `true` | Tab-complete amethyst chime on Bukkit when true |
| `debug` | boolean | `false` | Toggled by `/iris debug`; saved immediately |
| `dumpMantleOnError` | boolean | `false` | Dump mantle plates when tectonic errors occur |
| `disableNMS` | boolean | `false` | Disable NMS bindings when true |
| `pluginMetrics` | boolean | `true` | Plugin metrics reporting |
| `splashLogoStartup` | boolean | `true` | Console splash on enable |
| `useConsoleCustomColors` | boolean | `true` | Custom colors for console senders |
| `useCustomColorsIngame` | boolean | `true` | Custom colors for player senders |
| `adjustVanillaHeight` | boolean | `false` | Adjust vanilla height handling |
| `autoIngestDatapacks` | boolean | `true` | Validate and ingest configured external datapacks during the startup admission gate; unchanged committed content reuses its persisted result without another remote/full validation, and managed structures remain scoped to declaring Iris dimensions |
| `autoImportDatapackStructures` | boolean | `false` | Opt-in bulk write of every registered datapack structure as editable Iris resources; prefer `/iris structure import <dimension>` |
| `strictContentKeys` | boolean | `false` | Unresolved pack content keys and bad block-state properties become blocking pack errors; system property `-Diris.strictContent` overrides when set |
| `spinh` | int | `-20` | Splash / spin color H |
| `spins` | int | `7` | Splash / spin color S |
| `spinb` | int | `8` | Splash / spin color B |

## `world`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `postLoadBlockUpdates` | boolean | `true` | Post-load block updates |
| `forcePersistEntities` | boolean | `true` | Force entity persistence |
| `ambientEntitySpawningSystem` | boolean | `true` | Ambient entity spawning (legacy key `anbientEntitySpawningSystem` migrated) |
| `asyncTickIntervalMS` | long | `700` | World manager async tick interval ms |
| `targetSpawnEntitiesPerChunk` | double | `0.95` | Target entity density per chunk |
| `markerEntitySpawningSystem` | boolean | `true` | Marker-driven entity spawning |
| `effectSystem` | boolean | `true` | Engine effects |
| `worldEditWandCUI` | boolean | `true` | WorldEdit wand CUI integration (Bukkit) |
| `globalPregenCache` | boolean | `false` | Global pregen cache |

If both `markerEntitySpawningSystem` and `ambientEntitySpawningSystem` are false, the world manager skips related entity work.

## `gui`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `useServerLaunchedGuis` | boolean | `true` | Allow server-side GUI hosts (noise map, vision, pregen UI) |
| `maximumPregenGuiFPS` | boolean | `false` | Cap pregen GUI at max FPS when true |
| `colorMode` | boolean | `true` | Colored GUI mode |

## `autoConfiguration`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `configureSpigotTimeoutTime` | boolean | `true` | Raise Spigot timeout on Bukkit family when supported |
| `configurePaperWatchdogDelay` | boolean | `true` | Adjust Paper watchdog delay when supported |
| `autoRestartOnCustomBiomeInstall` | boolean | `true` | Auto-restart path after custom biome datapack install when required |

Bukkit-oriented; no-op or unused on mod loaders.

## `generator`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `defaultWorldType` | string | `"overworld"` | Default pack/dimension type key for world create when not overridden by command defaults |
| `preventLeafDecay` | boolean | `true` | Prevent leaf decay on Iris-managed leaves when true |

## `concurrency`

This section has **no public fields** serialized to JSON. Gson writes an empty object `{}`. Methods used at runtime:

| Method | Result |
|--------|--------|
| `getParallelism()` | `max(2, availableProcessors)` |
| `getIoParallelism()` | `max(2, availableProcessors / 2)` |
| `getWorldGenThreads()` | `max(2, availableProcessors)` |

## `studio`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `openVSCode` | boolean | `true` | Open VS Code / workspace on studio open paths |
| `disableTimeAndWeather` | boolean | `true` | Freeze time/weather in studio worlds |
| `entitySpawning` | boolean | `true` | Allow entity spawning in studio |
| `autoStartDefaultStudio` | boolean | `false` | Auto-open default studio on enable |

## `performance`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `engineSVC` | object | see below | Nested engine service thread pool |
| `trimMantleInStudio` | boolean | `false` | Trim mantle while in studio |
| `mantleKeepAlive` | int | `30` | Mantle keep-alive window |
| `noiseCacheSize` | int | `1024` | Noise cache capacity |
| `resourceLoaderCacheSize` | int | `1024` | Resource loader cache |
| `objectLoaderCacheSize` | int | `4096` | Object loader cache |
| `mantleCleanupDelay` | int | `200` | Cleanup delay ticks; world manager uses `max(mantleCleanupDelay * 50, 0)` ms |
| `simdKernels` | boolean | `true` | SIMD-accelerated noise kernels when available |

### `performance.engineSVC`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `useVirtualThreads` | boolean | `true` | Prefer virtual threads when available |
| `forceMulticoreWrite` | boolean | `false` | Force multicore write path |
| `priority` | int | `Thread.NORM_PRIORITY` (5) | Clamped to `[MIN_PRIORITY, MAX_PRIORITY]` |
| `parallelism` | int | `-1` | `>0`: min of configured and `processors * 2`; `≤0`: `ceil(sqrt(processors))` at least 1 |

## `pregen`

| Key | Type | Default | Effective clamp / resolve |
|-----|------|---------|---------------------------|
| `runtimeSchedulerMode` | enum | `AUTO` | `AUTO`, `PAPER_LIKE`, `FOLIA`. Regionized (Folia) always resolves to `FOLIA`. On non-regionized, configured `FOLIA` is forced to `PAPER_LIKE`. `AUTO` probes server name/version/class for Folia vs Paper-like family |
| `paperLikeBackendMode` | enum | `AUTO` | `AUTO`, `TICKET`, `SERVICE`. Non-`AUTO` uses the configured value; `AUTO` resolves to `TICKET` |
| `chunkLoadTimeoutSeconds` | int | `15` | Clamped `[5, 120]` |
| `timeoutWarnIntervalMs` | int | `500` | Minimum 250 |
| `saveIntervalMs` | int | `30000` | Clamped `[5000, 900000]` |
| `maxResidentTectonicPlates` | int | `96` | Minimum 16 via getter; effective residency also scales by world height and ~60% heap budget (~48 MB reference plate at height 384) with floor 16 |
| `mantleBackpressureWaitMs` | int | `25` | Clamped `[5, 1000]` |
| `mantleBackpressureTimeoutMs` | int | `60000` | Clamped `[5000, 600000]` |
| `moddedPregenInFlight` | int | `0` | `>0`: clamped to max 512; `≤0`: `max(16, min(48, cpu * 2))` for modded pregen concurrency |

`runtimeSchedulerMode` and Paper-like backend modes apply to Bukkit-family pregen routing. `moddedPregenInFlight` is the modded in-flight chunk budget.

## `sentry`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `includeServerId` | boolean | `true` | Include server id in reports |
| `disableAutoReporting` | boolean | `false` | Disable automatic Sentry reporting when true |
| `debug` | boolean | `false` | Sentry debug logging |

## `treeFeller`

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `enabled` | boolean | `false` | Master switch for survival tree feller |
| `durabilityPreservationChance` | int | `0` | Percent chance to preserve axe durability; clamped `[0, 100]` |

Requires permission `iris.treefeller` on Bukkit (and the platform tree-feller permission node on mod loaders). See `04 - Commands & Permissions.md` and `28 - Integrations.md`.

## Bukkit-only: `compat.json`

On Bukkit, Iris loads `plugins/Iris/compat.json` at startup and writes the built-in table to `compat.default.json`. Built-in compatibility mappings remain active; entries from `compat.json` are appended so operators can add fallback blocks and items for content that is unavailable on the running server.

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
| `when` | block and item filters | Unsupported source key to match |
| `supplement` | block and item filters | Replacement key; block replacement can continue through further mappings |
| `exact` | block filters only | When true, match complete block data instead of material only |

Invalid JSON logs the failure and leaves the built-in mappings active. These files are runtime compatibility configuration, not pack resources, and are not used by the modded adapters.

## Modded-only: `modded.json`

Path: `<configDir>/irisworldgen/modded.json`. Written with defaults on first load if missing. Not used by the Bukkit plugin.

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `defaultPack` | string | `"overworld"` | Default create pack; a distinct non-managed value is also prefetched when enabled |
| `autoDownloadDefaultPack` | boolean | `true` | Download missing managed Overworld/Underworld beta packs and any distinct configured default |
| `primaryWorld` | string | `""` | Primary Iris dimension id for player routing |
| `routePlayersToPrimaryWorld` | boolean | `true` | Route players to primary when set |
| `mainWorldPack` | string | `""` | Pack (or `pack:dimensionKey`) for main-world preset |
| `mainWorldSeed` | long | `0` | Seed for main-world preset |
| `mainWorldAutoRestart` | boolean | `false` | Auto-restart after main-world inject when true |

Updated by `/iris world mainworld`, `/iris world replace-overworld`, primary-world clear paths, and related world commands. See `06 - Worlds & Lifecycle.md` and `30 - Platform Differences.md`.

## What is not in these files

- Pack JSON (dimensions, biomes, objects) lives under `packs/<key>/` — see `05 - Concepts & Pack Layout.md`.
- Per-world studio/workspace files are generated under pack roots — see `10 - Studio & VSCode Schemas.md`.
- Locale files and overrides — see `08 - Localization.md`.

## Related

- `01 - Installation & Platforms.md`
- `04 - Commands & Permissions.md`
- `07 - Pregeneration.md`
- `25 - Pack Management.md`
- `30 - Platform Differences.md`
- `33 - Performance Tuning.md`

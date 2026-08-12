# 06 - Worlds & Lifecycle

Iris manages world identity, storage paths, pack installation, creation, persistence, and removal across Bukkit-family servers and the three mod loaders. Bukkit-managed Iris worlds live under the level root as `dimensions/iris/<key>/` with namespace `iris`; modded dimensions persist through `iris-dimensions.json`. Non-Studio worlds carry a frozen pack at `iris/pack`, validated by its exact normalized root rather than the common `pack` folder name, while Studio worlds bind the live packs directory.

See also: `04 - Commands & Permissions.md`, `02 - Getting Started.md`, `05 - Concepts & Pack Layout.md`, `07 - Pregeneration.md`, `10 - Studio & VSCode Schemas.md`, `30 - Platform Differences.md`.

## Tutorial: promote a tested pack to a persistent world

Prerequisites: a validated pack, a fixed test seed, a current backup, and no active lifecycle or pack-publish operation. The commands below use the installed `overworld` pack and disposable world name `release_candidate`; substitute one pack key consistently when promoting a different pack.

### Bukkit-family

1. Validate the live pack: `/iris pack validate pack=overworld`.
2. Open it with `/iris studio open overworld seed=1337`, generate representative terrain, then run `/iris studio close` after the final hotload succeeds.
3. Create a new world with explicit identity: `/iris create release_candidate type=overworld seed=1337`.
4. On Folia, stop and restart after the staging message. On other Bukkit-family servers, continue after `/iris worlds` lists `release_candidate` as loaded.
5. Enter it: `/iris tp release_candidate`.
6. Generate a bounded baseline: `/iris pregen start 352 world=release_candidate center=0,0 gui=false`.
7. Wait for completion, restart cleanly, return with `/iris tp release_candidate`, and generate one new boundary chunk.

The workflow passes when the world reloads with the same seed and dimension, the pregenerated area loads without generation failures, and new terrain still comes from `<world>/iris/pack`. Never replace or delete that snapshot while its world is loaded. Continued edits under `packs/overworld/` affect Studio only; publish deliberately through `25 - Pack Management.md` or create a new world for breaking height/type changes.

### Fabric / Forge / NeoForge

1. Validate the installed pack: `/iris pack validate overworld`.
2. Enable a persistent dimension: `/iris world enable irisworldgen:release_candidate overworld 1337`.
3. Confirm it in `/iris world status`, then enter it with `/iris tp irisworldgen:release_candidate`.
4. Run `/iris pregen start 352 irisworldgen:release_candidate at 0 0` and wait for completion.
5. Restart the server. Confirm `/iris world status` restores the same dimension and pack, then run `/iris info irisworldgen:release_candidate` as a gamemaster to verify seed `1337` from `iris-dimensions.json`.

This workflow passes when the dimension is re-injected after restart and generates normally. `/iris world disable` unloads while retaining persistent data; `/iris world delete` is the destructive removal path.

### Lifecycle recovery

| Symptom | Meaning | Recovery |
|---|---|---|
| Command reports busy | Another `WORLD_MUTATION` or `PACK_MUTATION` lease owns the lifecycle coordinator | Let that operation finish; do not retry concurrent create/remove/update commands |
| Login or create reports startup validation pending/failed/restart-required | External datapacks or dimension-pack validation has not reached a safe state | Fix the first logged failure or complete the requested restart; do not create folders or add `bukkit.yml` entries manually |
| Folia create succeeds but teleport cannot find the world | Creation staged files and registration only | Restart, then load/teleport as instructed by the staging result |
| Bukkit load reports missing or inconsistent data | Managed dimension root, registration, or `iris/pack` snapshot is incomplete | Keep the directory, restore from backup, and reconcile registration before retrying; load never redownloads the snapshot |
| Unload reaches its terminal timeout | World, generator, or scheduler work did not settle within 150 seconds | Allow the requested restart; do not force-delete the live directory |
| Remove returns `DELETE_QUEUED` | Files were quarantined for startup deletion | Restart and confirm the target is gone before reusing its name |
| Modded registry is quarantined as `.broken-<timestamp>` | Whole-file JSON could not be parsed | Keep the backup, recreate or repair each logged id with the original pack/dimension/seed, then verify status |

## Identity and storage

| Item | Rule |
|------|------|
| Managed namespace | `iris` only for Iris-managed create/load/remove targets |
| Logical name | For `iris:foo` the logical name is `foo` |
| Storage root | Level root (`Server#getLevelDirectory` on Paper; else `world-container/level-name`) |
| Dimension folder | `<levelRoot>/dimensions/iris/<key>/` |
| Pack snapshot | `<dimensionRoot>/iris/pack/` |
| Pregen cache dir | `<dimensionRoot>/iris/pregen/` |
| Registry | `worlds.json` in Iris data + `bukkit.yml` worlds section for production worlds |
| Name constraints | Safe single path segment `[a-z0-9_-]+`; no `/`, `\`, `..`; reserved create names `iris` and `benchmark` rejected |

Vanilla main/nether/end map to minecraft keys from `level-name` / `level-name_nether` / `level-name_the_end` and are not Iris-managed dimension folders.

### Modded persistent-dimension registry

Fabric, Forge, and NeoForge persist dynamic Iris worlds in `<world-root>/iris/iris-dimensions.json`:

```json
{
  "dimensions": [
    { "id": "irisworldgen:myworld", "pack": "overworld", "dimension": "overworld", "seed": 1337 }
  ]
}
```

`id` is the registered dimension id, `pack` is the installed pack folder, `dimension` is its dimension load key, and `seed` is the generation seed. Writes use a temporary file plus atomic replacement when the filesystem supports it. Invalid individual entries are logged and preserved verbatim during ordinary updates; duplicate ids keep the first valid entry.

If the whole registry cannot be parsed during startup, Iris moves it to `iris-dimensions.json.broken-<timestamp>`, logs any ids it can recover from the raw text, and continues with no persistent Iris dimensions. Keep the quarantined file, repair or recreate each reported world with `/iris world create`, and verify pack/dimension/seed values before deleting the backup.

## Command surface (Bukkit)

| Command | Effect |
|---------|--------|
| `/iris create <name> [type=default] [seed=1337] [main=false] [overwrite=false]` | Create/Folia-stage a managed world, or stage an exact restart replacement |
| `/iris load <name>` / `/iris import <name>` | Load a disk Iris world via reconciler |
| `/iris unload <world>` | Evacuate → unload → close generator |
| `/iris remove <name> [delete=true]` | Unregister / delete managed world |
| `/iris evacuate <world>` | Move players out of the Iris world |
| `/iris tp <world> [player=<name>]` | Teleport to world spawn |
| `/iris worlds` | List Iris vs non-Iris loaded worlds |

Full permission table: `04 - Commands & Permissions.md`.

### Create parameters

| Param | Default | Notes |
|-------|---------|-------|
| `name` | required | Normally becomes `iris:<logical>`; with overwrite it may also be the exact configured main, `_nether`, or `_the_end` alias |
| `type` | `default` | Pack/dimension selector: `default` → `settings.generator.defaultWorldType` (`overworld`); else pack name or `pack:dimensionKey` |
| `seed` | `1337` | World seed; exact vanilla-slot overwrite preserves the existing level's shared authoritative seed instead |
| `main` | `false` | Schedule main-world promotion on JVM shutdown (Paper path) or promote during Folia staging |
| `overwrite` (`force`) | `false` | Stage a validated exact-slot replacement for the next restart; never deletes a loaded world live |

Create refuses the primary Bukkit thread. Startup datapack validation must be ready and the selected source pack must have a loadable validation result before the lifecycle lease, datapack preparation, dimension folder, pack snapshot, registration, or Bukkit/NMS create path is entered; lifecycle domain `WORLD_MUTATION` / kind `WORLD_CREATE` must then be free or create fails busy.

## Production create flow (non-Folia)

1. Resolve the managed key and dimension without creating the dimension root.
2. Require startup datapack readiness and a loadable validation result for the dimension's owning pack.
3. Ensure datapacks for the dimension types are installed; queue restart if types not yet loaded.
4. Copy the pack into `<world>/iris/pack` (`StudioSVC.installIntoWorld`) — atomic stage → publish; refuses primary thread. Iris invalidates any prior result for that exact root and validates the final published tree before generator creation; failure rolls the publication back.
5. Build `WorldCreator` with Iris generator (`studio=false`).
6. Create the world through `WorldLifecycleService` / NMS async create (timeout 120s; timeout triggers server restart).
7. Register the world in `bukkit.yml` with generator `Iris` dimension key and seed; update the Multiverse link when present.
8. Run optional creation-time pregen if a `PregenTask` was attached by the creator API.

## Folia staging

Runtime world creation is disabled on Folia. `/iris create` instead:

1. Requires startup datapack readiness and a loadable validation result for the selected pack; refusal leaves no dimension folder or registration.
2. Acquires the `WORLD_CREATE` lease.
3. Installs datapacks if changed.
4. Stages the pack into the managed dimension root via `installIntoWorld`; the final published snapshot must pass exact-root validation before registration.
5. Registers the world in `bukkit.yml` (`BukkitWorldConfiguration.register`).
6. If `main=true`, promotes main-world files immediately under lease (failure rolls back bukkit.yml + deletes staged folder).
7. Instructs the operator to restart; generation/load happens on next startup.

`WorldLifecycleStaging` holds staged generators/biome providers for the backend that consumes them at load.

## Exact world-slot replacement

`overwrite=true` uses lifecycle kind `WORLD_REPLACE` and always stages for restart on Bukkit-family servers, including Paper and Folia. It accepts safe `iris:*` keys and only the three exact vanilla slots resolved from the configured level name: `minecraft:overworld`, `minecraft:the_nether`, and `minecraft:the_end`. A vanilla slot requires a matching pack environment (`NORMAL`, `NETHER`, or `THE_END`), and Nether/End replacement requires the server's matching allow setting to be enabled; foreign namespaces, other `minecraft:*` keys, path traversal, links, and special filesystem entries fail closed. `main=true` may accompany overwrite only for the configured main-world name. Minecraft stores one authoritative seed for the existing level, so all three exact vanilla slots preserve that loaded primary-world seed and report when it differs from the command's `seed`; changing the level seed remains the ordinary new-main promotion workflow.

The transaction copies and validates a fresh frozen pack under a same-filesystem sibling stage, fingerprints it, journals the original target state and `bukkit.yml` generator/seed, then compare-and-swaps that one configuration entry. Distinct slots can be queued before one restart. During Iris `STARTUP`, before Bukkit loads worlds, each authorized transaction atomically moves the old exact dimension directory to a retained sibling backup and publishes its stage. There is no chunk merge: old region, entity, POI, and Iris data remain only in the backup, while the target starts with the staged pack snapshot.

The backup is deleted only after `WorldLoad` proves the exact namespaced identity, Iris generator, selected dimension, seed, vanilla-slot environment, and unchanged pack fingerprint. A failed runtime check journals rollback, restores the prior `bukkit.yml` generator/seed with compare-and-swap semantics, requests another restart, and restores the retained directory before that restart loads worlds. A crash between either atomic move or journal write is retried idempotently. Conflicting manual configuration, changed staged bytes, unsafe storage, or corrupt journals block Iris world admission and preserve the stage/backup for operator recovery instead of guessing or deleting.

## Studio create

Studio uses `IrisCreator.studio(true)`:

- Startup datapack validation and the selected pack's validation must be loadable before a Studio project/world folder, snapshot, generator, or Bukkit world is created. Missing validation fails closed.
- Does **not** copy the pack into the world folder (except benchmark).
- Engine data folder is the live pack path; hotloader starts after engine setup.
- Biome Buffet prepares a changed focus before opening the chunk generation session. Its exclusive fair-stage admission downgrades directly to the retained chunk permit, so no other transition can enter between the focus hotload and that chunk.
- Studio worlds are transient: unloaded studio worlds are cleaned; `bukkit.yml` studio entries are removed on shutdown cleanup paths.
- Studio open/close uses `StudioSVC` transition queue (see `10 - Studio & VSCode Schemas.md`).
- Ordinary Studio suppresses native structure starts only while its initial FULL entry chunk is loading, then restores them for later preview chunks. A failed open never unloads or closes the generator while that exact asynchronous entry request remains active; another Studio open is rejected, cleanup begins after it settles, or its transient world is queued for deletion at the next clean startup if it remains active for another 120 seconds.

## Load

`/iris load` / `/iris import`:

1. Parses managed key; requires dimension root directory on disk.
2. `BukkitWorldReconciler.loadWorld(bukkit.yml, worldKey)`.
3. Reports success, busy, restart-required, or failure.

Load does not re-download packs; the world must already have `iris/pack` content and registration data consistent with Iris. Reconciliation checks startup readiness, then lazily validates that world's exact snapshot root before touching `bukkit.yml` or calling a world backend. Results are path-scoped, so separate worlds whose snapshot folders are both named `pack` cannot authorize or reject one another.

## Unload

`/iris unload` (player origin, sync):

1. Requires Iris world; acquires `WORLD_UNLOAD` lease.
2. Marks world maintenance.
3. `IrisToolbelt.evacuateAsync` → `WorldLifecycleService.unloadAsync(world, true)` → `generator.closeAsync()`.
4. Terminal timeout **150 seconds**: if unload has not settled, marks timeout, requests server restart (`ServerConfigurator.restart`), and fails the future.

`WorldUnloadEvent` stops Iris engine maintenance immediately, but it is not treated as proof that Paper's chunk scheduler has drained. Generator close waits for the raw world-lifecycle backend to confirm a successful unload, and the 26.2 noise pipeline retains one generation lease through terrain generation and worldgen-heightmap priming.

## Evacuate

`/iris evacuate` moves all players out of the Iris world into another loaded world (or kicks if none). Used as a step inside unload and removal.

## Remove

`/iris remove <name> [delete=true]` delegates to `IrisWorldRemovalService`:

| Status | Meaning |
|--------|---------|
| `UNREGISTERED` | Unloaded/unregistered; files kept (`delete=false`) |
| `DELETED` | Files deleted |
| `DELETE_QUEUED` | Quarantined for delete at next startup |
| `BUSY` | Another world/pack mutation holds the coordinator |
| `INVALID_IDENTIFIER` / `PROTECTED_WORLD` / `NOT_IRIS_WORLD` / `UNSAFE_PATH` / `NOT_FOUND` | Refused |
| Other failure statuses | Partial registry change without delete; quarantine path may remain |

Only safe `iris` namespace dimension paths are mutable. Phase timeouts use 120s and can request restart on stuck phases.

With `delete=true`, Iris records the exact quarantine name in the durable startup queue before moving the world directory. Immediate cleanup and startup retry both snapshot every directory's direct children before deleting them, reject symbolic links and special filesystem entries, and retain the queue entry with the full error when a concurrent writer or filesystem failure leaves content behind.

## Main world promotion

When create sets `main=true` (non-Folia), a shutdown hook rewrites `server.properties` `level-name` / `level-seed` and publishes files:

1. Stage temp directory under world container.
2. Copy shared `data`, `datapacks`, `players` from current level root.
3. Copy Iris dimension tree into staged overworld dimension path.
4. Atomic move stage → new level root; write `server.properties`.

Promotion requires absent target level folder and refuses symlink world data. Folia with `main=true` performs the same publish during staging instead of deferring to shutdown.

To replace the currently configured main slot in place, name that exact main world and use `overwrite=true`; this keeps the top-level level root, shared datapacks, player data, and non-target dimensions intact. Ordinary `main=true` without overwrite remains the new-level-root promotion workflow above.

## Pack snapshot vs studio (lifecycle view)

| Operation | Pack effect |
|-----------|-------------|
| Production create | Full pack tree installed under world `iris/pack` |
| Studio open | Engine reads live packs root; no world pack install |
| `/iris studio package` | Export only; does not change world |
| `/iris dev update-world` | Replaces world `iris/pack` (unsafe; restart if engine active) |
| Hotload | Studio only; production snapshot stays fixed |

## Concurrent lifecycle guards

`LifecycleOperationCoordinator` serializes domains including `WORLD_MUTATION` and `PACK_MUTATION`. Overlapping create/load/unload/remove/replace/pack-publish returns busy to the operator. Ordinary world create refuses if the dimension root already exists or the world is already loaded; exact replacement uses a separately journaled restart transaction and never relaxes removal-path protection.

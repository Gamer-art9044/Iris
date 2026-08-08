# 06 - Worlds & Lifecycle

Iris manages world identity, storage paths, pack installation, create/load/unload/remove/evacuate, and main-world promotion through Bukkit-family lifecycle services. Managed Iris worlds live under the level root as `dimensions/iris/<key>/` with namespace `iris`. Non-studio worlds carry a frozen pack at `iris/pack`; studio worlds bind the live packs directory.

See also: `04 - Commands & Permissions.md`, `02 - Getting Started.md`, `05 - Concepts & Pack Layout.md`, `07 - Pregeneration.md`, `10 - Studio & VSCode Schemas.md`, `30 - Platform Differences.md`.

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
| `/iris create <name> [type=default] [seed=1337] [main=false]` | Create or Folia-stage a managed world |
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
| `name` | required | Becomes `iris:<logical>`; folder must not already exist |
| `type` | `default` | Pack/dimension selector: `default` → `settings.generator.defaultWorldType` (`overworld`); else pack name or `pack:dimensionKey` |
| `seed` | `1337` | World seed |
| `main` | `false` | Schedule main-world promotion on JVM shutdown (Paper path) or promote during Folia staging |

Create refuses the primary Bukkit thread. Lifecycle domain `WORLD_MUTATION` / kind `WORLD_CREATE` must be free or create fails busy.

## Production create flow (non-Folia)

1. Resolve managed key and empty dimension root.
2. Resolve dimension via `IrisToolbelt.getDimension` (may download pack if missing).
3. Ensure datapacks for the dimension types are installed; queue restart if types not yet loaded.
4. Copy pack into `<world>/iris/pack` (`StudioSVC.installIntoWorld`) — atomic stage → publish; refuses primary thread.
5. Build `WorldCreator` with Iris generator (`studio=false`).
6. Create world through `WorldLifecycleService` / NMS async create (timeout 120s; timeout triggers server restart).
7. Register world in `bukkit.yml` with generator `Iris` dimension key and seed; Multiverse link update when present.
8. Optional creation-time pregen if a `PregenTask` was attached by the creator API.

## Folia staging

Runtime world creation is disabled on Folia. `/iris create` instead:

1. Acquires `WORLD_CREATE` lease.
2. Installs datapacks if changed.
3. Stages pack into the managed dimension root via `installIntoWorld`.
4. Registers the world in `bukkit.yml` (`BukkitWorldConfiguration.register`).
5. If `main=true`, promotes main-world files immediately under lease (failure rolls back bukkit.yml + deletes staged folder).
6. Instructs operator to restart; generation/load happens on next startup.

`WorldLifecycleStaging` holds staged generators/biome providers for the backend that consumes them at load.

## Studio create

Studio uses `IrisCreator.studio(true)`:

- Does **not** copy the pack into the world folder (except benchmark).
- Engine data folder is the live pack path; hotloader starts after engine setup.
- Studio worlds are transient: unloaded studio worlds are cleaned; `bukkit.yml` studio entries are removed on shutdown cleanup paths.
- Studio open/close uses `StudioSVC` transition queue (see `10 - Studio & VSCode Schemas.md`).

## Load

`/iris load` / `/iris import`:

1. Parses managed key; requires dimension root directory on disk.
2. `BukkitWorldReconciler.loadWorld(bukkit.yml, worldKey)`.
3. Reports success, busy, restart-required, or failure.

Load does not re-download packs; the world must already have `iris/pack` content and registration data consistent with Iris.

## Unload

`/iris unload` (player origin, sync):

1. Requires Iris world; acquires `WORLD_UNLOAD` lease.
2. Marks world maintenance.
3. `IrisToolbelt.evacuateAsync` → `WorldLifecycleService.unloadAsync(world, true)` → `generator.closeAsync()`.
4. Terminal timeout **150 seconds**: if unload has not settled, marks timeout, requests server restart (`ServerConfigurator.restart`), and fails the future.

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

## Main world promotion

When create sets `main=true` (non-Folia), a shutdown hook rewrites `server.properties` `level-name` / `level-seed` and publishes files:

1. Stage temp directory under world container.
2. Copy shared `data`, `datapacks`, `players` from current level root.
3. Copy Iris dimension tree into staged overworld dimension path.
4. Atomic move stage → new level root; write `server.properties`.

Promotion requires absent target level folder and refuses symlink world data. Folia with `main=true` performs the same publish during staging instead of deferring to shutdown.

## Pack snapshot vs studio (lifecycle view)

| Operation | Pack effect |
|-----------|-------------|
| Production create | Full pack tree installed under world `iris/pack` |
| Studio open | Engine reads live packs root; no world pack install |
| `/iris studio package` | Export only; does not change world |
| `/iris dev update-world` | Replaces world `iris/pack` (unsafe; restart if engine active) |
| Hotload | Studio only; production snapshot stays fixed |

## Concurrent lifecycle guards

`LifecycleOperationCoordinator` serializes domains including `WORLD_MUTATION` and `PACK_MUTATION`. Overlapping create/load/unload/remove/pack-publish returns busy to the operator. World create also refuses if the dimension root already exists or the world is already loaded.

# 07 - Pregeneration

Pregeneration walks a rectangular (by default square) block radius around a center and forces chunk generation so players do not trigger generation on first visit. Bukkit command `/iris pregen` (alias `pregenerate`) drives a single active `PregeneratorJob` backed by `IrisPregenerator` and a `PregeneratorMethod`. Settings under `settings.json` → `pregen` and `world.globalPregenCache` control timeouts, mantle residency, scheduler mode, and optional durable skip-cache.

See also: `03 - Configuration.md`, `04 - Commands & Permissions.md`, `02 - Getting Started.md`, `06 - Worlds & Lifecycle.md`, `29 - Client HUD & Protocol.md`, `33 - Performance Tuning.md`.

## Tutorial: run a bounded pregen safely

Prerequisites: a disposable or backed-up world, enough free disk for the requested area, ordinary on-demand chunk generation already proven, and no other active pregen job. The commands use `release_candidate` from `06 - Worlds & Lifecycle.md`; substitute the exact loaded world or dimension id if yours differs.

1. Choose a block radius. The standard smoke uses `352` blocks centered at `0,0`, which covers 2,025 chunks.
2. Start without the desktop GUI on a headless server.

   Bukkit-family:

   ```text
   /iris pregen start 352 world=release_candidate center=0,0 gui=false serial=false
   ```

   Fabric / Forge / NeoForge:

   ```text
   /iris pregen start 352 irisworldgen:release_candidate at 0 0
   ```

3. Run `/iris pregen status`. Confirm the target, 2,025 total chunks, generated count, rate, ETA, and failed count.
4. Run `/iris pregen pause`, wait for progress to stop, then `/iris pregen resume` and confirm it continues.
5. Let the run complete. To test cancellation instead, run `/iris pregen stop` once and wait for in-flight work to close before starting another job.
6. Restart the server and visit chunks near the generated boundary.

The workflow passes when status reaches completion without accumulating failures, no job remains active after restart, and the generated boundary loads normally. Change concurrency, scheduler, or cache settings only after this baseline succeeds; compare one change at a time using `33 - Performance Tuning.md`.

### Recovery

| Symptom | Check | Recovery |
|---|---|---|
| Start reports an active job | One job is already server-wide | Inspect `/iris pregen status`; finish it or stop it and wait for closure before retrying |
| Status total is unexpected | Radius is in blocks and center-to-chunk rounding changes bounds | Verify radius and center; use the 352-at-0,0 baseline before larger runs |
| Failed count increases | Chunk load timeout, generation exception, disk failure, or lifecycle interruption | Stop the job, fix the first logged failure, verify ordinary generation, then retry the same small area |
| `serial=true` is rejected | Strict serial generation is unavailable on this Bukkit platform | Use the normal method or run the diagnostic on a Paper-compatible server |
| Desktop GUI does not open | Server is headless or GUI launch is disabled | Use `gui=false` and monitor status, console, boss bar, or client HUD |
| Memory pressure repeatedly pauses progress | Effective mantle/heap cap is being reached | Keep the job stopped while tuning; lower residency/in-flight work before increasing heap-sensitive limits |
| Restart does not skip completed work | Cache wrapper was disabled, Folia routing disabled it, `nocache` was used, or cache files were removed | Treat the rerun as uncached; do not infer corruption from regeneration alone |

## Commands

| Command | Behavior |
|---------|----------|
| `/iris pregen start <radius> [world=<world>] [center=0,0] [gui=true] [serial=false]` | Start job |
| `/iris pregen stop` / `x` | Request stop; finishes in-flight work then cancels |
| `/iris pregen pause` / `resume` | Toggle pause on the active job |
| `/iris pregen status` | Print progress snapshot (chunks, %, speed, ETA, method, failed) |

Only one pregen job instance is active. Starting a new job closes the previous instance.

### `start` parameters

| Param | Default | Notes |
|-------|---------|-------|
| `radius` | required | Blocks from center on X and Z (`radiusX` = `radiusZ`). Must be `> 0`. Reported span is `(radius * 2)` by `(radius * 2)` blocks |
| `world` | contextual | Target world (Iris preferred; non-Iris uses hybrid method without engine cache wrapper when no access) |
| `center` | `0,0` | Block X/Z center; `me` uses player location when supported by director parsing |
| `gui` | `true` | Open desktop pregen GUI when host supports it; headless servers log and continue |
| `serial` | `false` | One chunk at a time via strict serial hybrid method; **requires Paper** (`supportsStrictSerialPregeneration`) |

If the sender is a player without engine access, Iris warns that the world may not be fully loaded.

## Area model

`PregenTask` builds saturating block bounds `center ± radius`, converts to chunk and region ranges, and iterates regions in spiral order with per-region chunk order pulled toward the center.

| Limit | Value |
|-------|-------|
| Max region span per axis | `117189` regions (~±30M blocks Minecraft world limit) |
| Oversized request | `IllegalArgumentException` at construction (does not hang) |

## Generation methods

| Path | Method |
|------|--------|
| Iris world, parallel | `HybridPregenMethod(world, threadCount)` with concurrency from settings parallelism |
| Iris world, `serial=true` | `HybridPregenMethod.strictSerial(world)` |
| Non-Iris world | Hybrid without engine |
| Cached wrapper | `CachedPregenMethod` around method when caching enabled and runtime scheduler mode is **not** Folia |

Other method classes (`AsyncPregenMethod`, `MedievalPregenMethod`, `AsyncOrMedievalPregenMethod`) exist for specialized/API paths; the command path uses hybrid.

## Cache

| Setting | Location | Behavior |
|---------|----------|----------|
| Per-job skip cache | World `iris/pregen/` via `GlobalCacheSVC.createDefault` | Records generated chunks/regions so restarts can skip work when wrapper is active |
| `world.globalPregenCache` | `settings.json` | When true, maintains global per-world caches on world init/chunk load; when false, service stays idle after enable |
| Folia | Runtime scheduler resolved as Folia | Cached wrapper **disabled** for pregen |

Cache write happens on world unload and service disable. Empty cache is used when the service is disabled.

## Mantle and heap caps

Pregen applies mantle backpressure and heap high-water checks so tectonic plates do not exhaust memory.

| Control | Default / rule |
|---------|----------------|
| `pregen.maxResidentTectonicPlates` | Default `96`, minimum effective floor `16` |
| Effective plate cap | `min(baseCap, heightScaledCap, heapBudgetCap)` using world height vs 384 and ~60% of process heap / estimated plate size |
| Backpressure wait | `mantleBackpressureWaitMs` default `25` (clamped 5–1000) |
| Backpressure timeout | `mantleBackpressureTimeoutMs` default `60000` (clamped 5s–600s) |
| Hard cap trigger | Loaded plates `> effectiveCap * 2` forces wait/evict |
| Heap high water | Pause generation while heap used ≥ **92%**; release at **82%** |
| Heap panic | ≥ **96%** requests panic reclaim / GC (throttled) |
| Save interval | `saveIntervalMs` default `30000` (clamped 5s–900s) during pregen loop |

Raising `maxResidentTectonicPlates` increases memory headroom for speed; lowering reduces peak RAM. See `33 - Performance Tuning.md`.

## Other `pregen` settings

| Key | Default | Role |
|-----|---------|------|
| `runtimeSchedulerMode` | `AUTO` | Influences Folia vs paper-like scheduling for pregen cache and related paths |
| `paperLikeBackendMode` | `AUTO` | Paper-like lifecycle backend selection |
| `chunkLoadTimeoutSeconds` | `15` (5–120) | Chunk load timeout during pregen |
| `timeoutWarnIntervalMs` | `500` (≥250) | Warning interval for stalled loads |
| `moddedPregenInFlight` | `0` → auto `max(16, min(48, cpu*2))` | In-flight cap for modded pregen adapters |

## Pause / stop / status

| Action | Behavior |
|--------|----------|
| Pause | `PregeneratorJob.pauseResume()` flips pause; generator loop spins while paused or heap high-water |
| Stop | `shutdownInstance()` closes pregenerator and interrupts worker asynchronously |
| Status | `progressSnapshot()`: percent, generated, total, chunks/s, ETA, elapsed, method name, paused flag, failed count, world name |

Failed chunks are counted separately and shown in status when non-zero.

## HUD / GUI / protocol

| Surface | Behavior |
|---------|----------|
| Desktop GUI | `PregenRenderer` when `gui=true` and GUI host available; colors mark existing, generating, network, generated, cleaned, mantle states |
| Boss bar / loader HUD | Create and some pregen attach paths use HUD slot claims for progress (creation pregen and studio progress reporters) |
| Client protocol | `IrisProtocolServer.broadcastPregenProgress` sends progress to connected Iris client sessions |

Client HUD details: `29 - Client HUD & Protocol.md`. GUI toggles: `settings.gui.useServerLaunchedGuis`, `maximumPregenGuiFPS`.

## Performance profile

Starting pregen applies `PregenPerformanceProfile` to the engine (or global) before the job runs. Studio `profile` command can also apply the pregen performance profile while measuring pack cost.

## Operator notes

- Radius is in **blocks**, not chunks or regions.
- Re-running pregen over the same area is faster when the chunk cache wrapper is active and cache files under `iris/pregen` remain.
- Unload/remove of a world with active pregen should stop the job for that world identity when lifecycle hooks call shutdown-for-world.
- Serial mode is for diagnosis/stability on Paper, not peak throughput.

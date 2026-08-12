# 33 - Performance Tuning

Iris throughput is bounded by four things: how many chunks the platform will let Iris generate at once, how much mantle stays resident in heap, how often pack resources are reloaded from disk, and whether the JVM has the incubator Vector API. This page is organized by the symptom you are looking at, not by settings file order. Every knob lives in `settings.json` under the Iris data directory (`03 - Configuration.md`); pregen operations are in `07 - Pregeneration.md`. Any change here must leave GoldenHash unchanged (`32 - Determinism & Goldenhash.md`).

## Before you turn any knob

Most bad tuning comes from changing three things, seeing a better number once, and keeping all three. Do this instead:

1. Freeze the inputs: Iris artifact, pack bytes, seed, center, radius, JVM flags, and server population.
2. Run one warmup, then three measured runs. Record chunks/second, wall time, peak heap, GC behavior, and failed chunk count.
3. Change exactly one setting. Restart if the setting is read once at startup — thread pools, caches, and SIMD kernel selection all are.
4. Repeat the warmup and three runs over the same area. A comparison across different terrain is not a comparison.
5. Keep the change only if the median improves with no determinism mismatch, no new failures, no unacceptable heap growth, and no worse tick latency.
6. Restore the old value before testing the next knob.

Reach for JProfiler when the numbers move without an obvious cause: stalls, allocation pressure, or scheduler behavior. A faster pregen status line on its own doesn't tell you why.

## Symptom: pregen is slow

Work through these in order. The first two are free; the rest trade something.

1. **Check whether the platform is the limit, not Iris.** On Fabric, Forge, and NeoForge without a parallel chunk system, pregen runs through the vanilla main-thread chunk pipeline and throughput is capped there regardless of settings. Iris logs this at pregen start and names the fix: install C2ME on Fabric, or run Paper if you want Bukkit-level throughput. No Iris setting recovers that gap.
2. **Confirm SIMD is on.** On Bukkit, the startup log prints one of `SIMD: vector kernels enabled (…)`, `SIMD: scalar kernels active; add --add-modules jdk.incubator.vector …`, or `SIMD: vector kernels disabled (performance.simdKernels=false)`. If you see the scalar message, add the JVM flag and restart. See the SIMD section for what it actually accelerates and how small that surface is. Mod loaders never print this line, so check the JVM flag directly there.
3. **Leave concurrency alone unless it is warning at you.** Bukkit pregen concurrency is derived, not configured: Iris sizes it from the detected chunk-system worker pool (or CPU count) times 8, clamped to 16–128 on Paper-like servers and 64–192 on Folia. Raising it is not an option, and the adaptive limiter already lowers it when mantle backpressure engages. The only concurrency lever on Bukkit is `serial=true`, which drops to one chunk in flight — use it for profiling and determinism isolation, never for throughput.
4. **On mod loaders, size `pregen.moddedPregenInFlight` to the chunk system.** Default `0` resolves to `clamp(16, cpu*2, 48)`, and whatever value comes out is floored at 8. Raise it only if the loader has a parallel chunk system and the CPU is not saturated; lower it if you see chunk-load timeouts. Positive values are capped at 512.
5. **Raise the object cache if the same objects keep reloading.** `performance.objectLoaderCacheSize` (default 4096) bounds the loader caches for `.iob` objects, matter objects, and images. Object-heavy packs on large pregens hit this. The tradeoff is retained heap, so only do this if heap has room — see the memory section.
6. **Give the process more heap before touching mantle caps.** Resident mantle plates are budgeted against process memory, so a bigger heap raises the effective plate count without any settings change.

`performance.noiseCacheSize` is not worth tuning for pregen: starting a pregen raises it to at least 4096 in memory, hotloads the engine, and sets `iris.cache.fast` as a system property. Neither is lowered again for the life of the process. The Bukkit plugin already sets `iris.cache.fast` during startup; on mod loaders it only comes on with the first pregen, so pass `-Diris.cache.fast=true` on the JVM command line there if you want it covering ordinary generation too.

## Symptom: TPS dips or chunk-load timeouts while generating

Generation competing with the server tick shows up as timeout warnings, region scheduler complaints, or players reporting lag near the pregen frontier.

| Do this | Effect | Cost |
|---|---|---|
| Run pregen with `serial=true` (Bukkit, Paper-compatible) or `sync` (modded) | One chunk in flight at a time; the tick thread stops competing with a wide generation front | Much slower pregen; this is an isolation tool, not a production mode |
| Lower `pregen.moddedPregenInFlight` (modded only) | Fewer concurrent chunk generations, so the chunk system keeps headroom for player chunks | Proportionally slower pregen |
| Raise `pregen.chunkLoadTimeoutSeconds` (default 15, clamped 5–120) | Iris waits longer before declaring a chunk load stuck and warning | Hides a real stall instead of fixing it; try it last. Modded pregen ignores anything below 120 seconds |
| Raise `pregen.timeoutWarnIntervalMs` (default 500, minimum 250) | Spaces out repeated timeout warnings in console | Log noise only; changes nothing about the stall |
| Raise `pregen.saveIntervalMs` (default 30000, clamped 5000–900000) | Less frequent pregen state flushing, so less periodic IO | More work replayed if the job is interrupted |

`pregen.runtimeSchedulerMode` (`AUTO`, `PAPER_LIKE`, `FOLIA`) and `pregen.paperLikeBackendMode` (`AUTO`, `TICKET`, `SERVICE`) exist for platform mismatches, not throughput. A Folia runtime always resolves to Folia scheduling regardless of the setting, and `AUTO` on Paper-like servers resolves to the ticket backend. Change these only when diagnosing a scheduler-specific defect.

## Symptom: heap pressure, long GC pauses, or OOM risk

Mantle is the largest thing Iris keeps in heap. Iris already reacts to heap pressure on its own: as used heap climbs from 82% to 92%, the idle window before a mantle plate is trimmed shrinks linearly to zero, and above 96% Iris requests a reclaim (at most once every 30 seconds). If you are seeing pressure, that machinery is already running — you are deciding how much less mantle to hold.

1. **Raise heap first if the machine has it.** The resident-plate budget is computed from process memory: roughly 60% of the heap, against a per-plate cost of about 48 MB at a 384-block world height, scaled by your actual dimension height. More heap means more plates without changing a setting.
2. **Lower `pregen.maxResidentTectonicPlates`** (default 96). This is a soft cap on how many mantle tectonic plates stay resident. The effective number is the smaller of that cap, a height-scaled version of it, and the heap budget above — with a hard floor of 16. Taller worlds get fewer plates automatically. Lowering it cuts retained heap at the cost of more mantle reload work.
3. **Lower `performance.mantleKeepAlive`** (default 30). This is how many seconds an idle mantle plate survives before maintenance trims it. Lower means memory comes back sooner; it also means recently-touched regions get re-read more often.
4. **Lower the loader caches** if a heap dump shows retained pack data rather than mantle: `performance.objectLoaderCacheSize` (default 4096) and `performance.resourceLoaderCacheSize` (default 1024).
5. **Slow the pregen down.** Backpressure knobs decide how long a generation thread waits when the mantle plate budget is full: `pregen.mantleBackpressureWaitMs` (default 25, clamped 5–1000) is the wait between retries, and `pregen.mantleBackpressureTimeoutMs` (default 60000, clamped 5000–600000) is how long it waits before giving up on that chunk. Raising the timeout buys a slow job time to finish instead of failing chunks; it does not reduce memory use.

`performance.engineSVC.forceMulticoreWrite` (default false) makes mantle plate unloading use the parallel path all the time instead of only under heap pressure. It returns memory faster during sustained generation and costs CPU that would otherwise go to generating.

## Symptom: Studio memory keeps growing during editing

Studio worlds deliberately skip mantle trimming and per-chunk mantle cleanup, so a long authoring session accumulates mantle that a normal world would have released. Set `performance.trimMantleInStudio` to `true` to make studio worlds maintain mantle like any other world. The cost is that hotloaded pack edits will regenerate more from scratch because less is cached. A/B this in Studio only; it has no effect on production worlds.

## Symptom: the same pack resources reload constantly

`performance.resourceLoaderCacheSize` (default 1024) bounds the cache of parsed JSON pack resources; `performance.objectLoaderCacheSize` (default 4096) bounds `.iob`, matter, and image loaders. If profiling shows repeated parse or disk work for resources you know are in use, raise the one that is actually missing, one at a time. Both trade heap for fewer reloads, and neither changes generation output.

## Reference: `performance` section

| Key | Default | What it does |
|-----|---------|--------------|
| `performance.simdKernels` | `true` | Allows vector kernels when `jdk.incubator.vector` is on the module path; `false` forces scalar. Read once at class initialization, so a restart is required |
| `performance.mantleKeepAlive` | `30` | Seconds an idle mantle plate survives before maintenance trims it. Shrinks toward zero as used heap climbs from 82% to 92% |
| `performance.mantleCleanupDelay` | `200` | Ticks a loaded chunk waits before its mantle cleanup runs (200 = 10 s). Raising it keeps mantle data resident longer after chunk loads; see "03 - Configuration.md" |
| `performance.trimMantleInStudio` | `false` | Whether studio worlds get mantle trimming and per-chunk cleanup at all; false means they keep everything resident |
| `performance.noiseCacheSize` | `1024` | Noise sample cache capacity per engine. Starting a pregen raises it to at least 4096 for the rest of the process |
| `performance.resourceLoaderCacheSize` | `1024` | Parsed pack resource entries held before eviction |
| `performance.objectLoaderCacheSize` | `4096` | `.iob`, matter, and image loader entries held before eviction |
| `performance.engineSVC.useVirtualThreads` | `true` | Maintenance workers run on virtual threads; `false` uses platform threads |
| `performance.engineSVC.forceMulticoreWrite` | `false` | Always unload mantle plates on the parallel path instead of only under heap pressure |
| `performance.engineSVC.priority` | `5` (`Thread.NORM_PRIORITY`) | Priority of maintenance platform threads, clamped to the legal Java range. Ignored entirely when virtual threads are on |
| `performance.engineSVC.parallelism` | `-1` | Size of the engine maintenance worker pool. A positive value is capped at `2 × CPU`; zero or negative means `ceil(sqrt(CPU))` |

`engineSVC` sizes the maintenance service — mantle trimming, plate unloading, periodic saves — not chunk generation. Raising `parallelism` will not generate chunks faster; it makes mantle housekeeping finish sooner and take more CPU while it does. Generation parallelism is derived separately (see below).

## Reference: `pregen` section

| Key | Default | What it does |
|-----|---------|--------------|
| `pregen.runtimeSchedulerMode` | `AUTO` | Which scheduler the Bukkit pregen driver uses: `AUTO`, `PAPER_LIKE`, `FOLIA`. A Folia runtime always resolves to Folia |
| `pregen.paperLikeBackendMode` | `AUTO` | How Paper-like pregen acquires chunks: `AUTO`, `TICKET`, `SERVICE`. `AUTO` resolves to `TICKET` |
| `pregen.chunkLoadTimeoutSeconds` | `15` | How long a pregen worker waits for a chunk before warning. Clamped 5–120; modded pregen raises anything below 120 to 120 |
| `pregen.timeoutWarnIntervalMs` | `500` | Minimum gap between repeated timeout warnings. Minimum 250 |
| `pregen.saveIntervalMs` | `30000` | Gap between pregen progress flushes. Clamped 5000–900000 |
| `pregen.maxResidentTectonicPlates` | `96` | Ceiling on resident mantle plates before the height and heap budgets narrow it further. Never drops below 16 |
| `pregen.mantleBackpressureWaitMs` | `25` | Pause between retries when the plate budget is full. Clamped 5–1000 |
| `pregen.mantleBackpressureTimeoutMs` | `60000` | How long a chunk waits on backpressure before failing. Clamped 5000–600000 |
| `pregen.moddedPregenInFlight` | `0` | Concurrent pregen chunks on mod loaders. `0` resolves to `clamp(16, cpu*2, 48)`; positive values cap at 512; the result is floored at 8 |

Related: `world.globalPregenCache` (default `false`) — see `03 - Configuration.md` and `07 - Pregeneration.md`.

## Reference: derived concurrency

The `concurrency` section in `settings.json` has no writable keys. The values are computed from CPU count at runtime and cannot be overridden from the file:

- Generation burst pool: `max(2, availableProcessors)`
- IO burst pool: `max(2, availableProcessors / 2)`
- Bukkit pregen in-flight cap: worker threads × 8, clamped 16–128 on Paper-like servers and 64–192 on Folia, then lowered adaptively under mantle backpressure down to `max(4, min(16, cap / 4))`

If you need less generation concurrency, use `serial=true` (Bukkit) or `sync` (modded) rather than looking for a knob that does not exist.

## SIMD

What actually uses vector kernels today is narrow: an array rounding path in the chunked double data cache, and array operations in mantle carving. The 2D fractal noise vector kernels (`VectorNoiseKernels2D`) exist and are correct, but nothing in the production worldgen path calls `SimdSupport.noiseKernels2D()` yet. Treat noise SIMD as unfinished and do not size hardware around it.

Selection happens once, at class initialization:

1. `performance.simdKernels` false → scalar kernels.
2. Otherwise, if the `jdk.incubator.vector` module is present and the vector kernel class loads → vector kernels.
3. Otherwise → scalar kernels, with a startup log line telling you to add the flag.

The 2D noise kernels add one more gate: they are only selected when the preferred `double` and `long` vector species have matching lane counts and at least 4 double lanes. Apple Silicon NEON, at 2 lanes, does not qualify.

The JVM flag is required for any vector path:

```
--add-modules jdk.incubator.vector
```

The Iris Gradle build passes it for core compilation and tests and for every `probe` task, and `tools/simd-bench/` passes it in its own scripts. Nothing adds it to a production server's start script — a server operator must add it there.

To A/B on a real server: set `performance.simdKernels` false, restart, measure pregen chunks/second, set it true, restart, measure again, and confirm GoldenHash is unchanged (`32 - Determinism & Goldenhash.md`). `tools/simd-bench/` (`./run.sh` or `run.bat`) measures kernels in isolation and deliberately ignores the profitability gate, so its speedups do not predict end-to-end pregen gains.

## Measurement checklist

Record for every experiment: pack identity, seed, radius, serial/sync flags, JVM version and flags, heap size, CPU, the `performance` and `pregen` excerpts you changed, chunks/second, duration, failed chunks, peak heap, and the GoldenHash combined value. Reject any optimization that changes the hash unless the behavior change was intended and is documented. Release-scale baselines (5k–10k chunks with JProfiler) are tracked in `87 - Maintainer - Release Readiness.md`.

Never tune by editing pack content. Pack edits change terrain, which changes the hash, which means you are no longer comparing the same thing.

## Offline tools

| Tool | Command | Use |
|------|---------|-----|
| Generation probe | `./gradlew :probe:genProbe -PprobePack=…` | Headless engine generation; a correctness signal, not a throughput benchmark |
| Classload probe | `./gradlew :probe:run` | Core-purity gate; fails if `org.bukkit` leaks into engine classes |
| SIMD microbench | `tools/simd-bench/run.sh` | Kernel-only scalar versus vector timing |

Runbooks that combine pregen and GoldenHash: `31 - Operator Runbooks.md`.

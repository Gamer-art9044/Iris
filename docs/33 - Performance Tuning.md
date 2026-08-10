# 33 - Performance Tuning

Iris throughput is dominated by generation threads, mantle residency, pregen in-flight limits, cache sizes, and optional SIMD kernels. All knobs below live in `settings.json` under the Iris data directory unless noted. Settings overview: `03 - Configuration.md`. Pregen operations: `07 - Pregeneration.md`. Determinism must stay intact after tuning — verify with GoldenHash (`32 - Determinism & Goldenhash.md`).

## Tutorial: measure one tuning change

Choose the first knob from observed evidence, not from hardware size alone:

| Observed limit | First controlled change |
|---|---|
| Mantle backpressure or timeout warnings | Lower pregen in-flight concurrency; then test a lower `maxResidentTectonicPlates` if heap pressure remains |
| High retained heap or long GC pauses | Lower loader caches and resident plate cap; do not raise concurrency |
| Repeated object/resource loading with spare heap | Raise only the cache that is missing (`objectLoaderCacheSize` or `resourceLoaderCacheSize`) |
| CPU-bound generation with stable heap | Confirm the Vector API module and A/B `simdKernels`; benchmark `engineSVC.parallelism` only afterward |
| Region scheduler warnings or chunk-load timeouts | Lower pregen concurrency or use serial/sync isolation before increasing timeout values |
| Studio memory growth during repeated edits | A/B `trimMantleInStudio` in Studio only |

1. Freeze the Iris artifact, pack bytes, seed, center, radius, Java flags, and server population.
2. Run one warmup, then record at least three baseline runs with chunk throughput, wall time, peak heap, GC behavior, failed chunks, and GoldenHash.
3. Change one setting from the tables below and restart if that setting constructs a pool, cache, or SIMD kernel.
4. Repeat the same warmup and three measured runs. Reject comparisons that used different generated areas or active plugins.
5. Keep the change only when the median improves without a determinism mismatch, new failure, unacceptable memory growth, or worse tick latency.
6. Restore the previous value before testing the next knob.

Use JProfiler for stalls, allocation pressure, scheduler behavior, or unexplained regressions. A faster pregen status line by itself is not enough evidence for a production tuning recommendation.

## Where settings live

| Platform | Data directory | Settings file |
|----------|----------------|---------------|
| Bukkit-family | `plugins/Iris/` | `settings.json` |
| Fabric / Forge / NeoForge | loader config `iris/` | `settings.json` |

Hotload behavior for settings follows `03 - Configuration.md`. After changing SIMD or thread-pool related keys, restart if values are read once at kernel install / pool creation.

## Performance section (`performance`)

| Key | Default | Role |
|-----|---------|------|
| `performance.simdKernels` | `true` | When true and `jdk.incubator.vector` is available, use vector kernels; otherwise scalar fallbacks |
| `performance.mantleKeepAlive` | `30` | Mantle plate keep-alive window (seconds-scale residency control used by mantle lifecycle) |
| `performance.mantleCleanupDelay` | `200` | Delay before mantle cleanup work |
| `performance.trimMantleInStudio` | `false` | When true, studio worlds trim mantle more aggressively |
| `performance.noiseCacheSize` | `1024` | Noise sample cache capacity |
| `performance.resourceLoaderCacheSize` | `1024` | Pack resource loader cache capacity |
| `performance.objectLoaderCacheSize` | `4096` | Object (`.iob`) loader cache capacity |
| `performance.engineSVC.useVirtualThreads` | `true` | Engine service uses virtual threads when true |
| `performance.engineSVC.forceMulticoreWrite` | `false` | Force multicore write path |
| `performance.engineSVC.priority` | `Thread.NORM_PRIORITY` | Clamped to valid Java thread priorities |
| `performance.engineSVC.parallelism` | `-1` | `>0` caps at `2 * CPU`; `≤0` uses `ceil(sqrt(CPU))` |

Larger loader caches trade heap for fewer pack disk/JSON reloads during generation. Raise `objectLoaderCacheSize` when pregen is object-heavy and the pack is large; lower caches if heap pressure shows retained pack data.

## Pregen section (`pregen`)

| Key | Default | Role |
|-----|---------|------|
| `pregen.runtimeSchedulerMode` | `AUTO` | Bukkit pregen scheduler mode: `AUTO`, `PAPER_LIKE`, `FOLIA` (Folia runtime always resolves to Folia scheduling) |
| `pregen.paperLikeBackendMode` | `AUTO` | Paper-like backend: `AUTO`, `TICKET`, `SERVICE` |
| `pregen.chunkLoadTimeoutSeconds` | `15` | Clamped 5–120 |
| `pregen.timeoutWarnIntervalMs` | `500` | Minimum 250 ms between timeout warnings |
| `pregen.saveIntervalMs` | `30000` | Clamped 5_000–900_000 |
| `pregen.maxResidentTectonicPlates` | `96` | Soft cap (effective floor 16) on resident mantle tectonic plates |
| `pregen.mantleBackpressureWaitMs` | `25` | Clamped 5–1000; wait when mantle backpressure engages |
| `pregen.mantleBackpressureTimeoutMs` | `60000` | Clamped 5_000–600_000 |
| `pregen.moddedPregenInFlight` | `0` | `0` = auto `clamp(16, cpu*2, 48)`; positive values clamp to 1–512 |

Effective resident plates also scale with world height and process heap: higher worlds and smaller heaps reduce the effective plate count (minimum 16). If pregen stalls with mantle pressure, lower concurrency first, then reduce `maxResidentTectonicPlates`, or raise heap so the byte budget allows more plates.

Related world flag: `world.globalPregenCache` (default `false`) — global pregen cache behavior; see `03 - Configuration.md` / `07 - Pregeneration.md`.

## Concurrency helpers (`concurrency`)

`IrisSettingsConcurrency` exposes derived counts (not all are free-form JSON knobs with independent storage in every build path):

- World-gen style parallelism floors at `max(2, availableProcessors)`.
- IO parallelism floors at `max(2, availableProcessors / 2)`.

Prefer pregen in-flight limits and `engineSVC.parallelism` for production tuning rather than inventing extra thread pools outside settings.

## SIMD

**Note:** Broader SIMD coverage (including full noise-kernel wiring through production worldgen) is actively being worked on. Array kernels used on some hot paths already honor `performance.simdKernels` when the incubator Vector API is available; treat noise SIMD as incomplete until that work lands.

Runtime selection (`SimdSupport`) today:

1. If `performance.simdKernels` is false → scalar kernels.
2. Else if module `jdk.incubator.vector` is present and vector kernel classes load → vector kernels for **array ops** used in some generation hot paths (for example `roundToInt` via `ChunkedDoubleDataCache`, carving paths via `MantleCarvingComponent`).
3. Else → scalar kernels; startup log tells the operator to add `--add-modules jdk.incubator.vector`.

2D fractal noise vector kernels exist (`VectorNoiseKernels2D`) and are gated to CPUs where `double` vector width is profitable (≥ 4 lanes). That path is selected by `SimdSupport.noiseKernels2D()` but is **not** the primary wired worldgen path yet.

JVM flag (required for vector API incubator):

```
--add-modules jdk.incubator.vector
```

Server start scripts and Gradle run configs for Iris already pass this where Iris launches the JVM. Standalone microbench: `tools/simd-bench/` (`./run.sh` or `run.bat`). That tool force-measures kernels even when Iris would gate noise SIMD off (for example Apple Silicon 2-lane NEON). Microbench speedups do not guarantee end-to-end pregen gains.

To A/B SIMD on a full server: set `performance.simdKernels` false, restart, measure pregen chunks/s, re-enable, restart, remeasure. Confirm GoldenHash unchanged (`32 - Determinism & Goldenhash.md`).

## Operator pregen modes that affect load

| Mode | Platform | Effect |
|------|----------|--------|
| Default pregen | All | Concurrent generation within platform scheduler limits |
| `serial=true` | Bukkit Paper-compatible only | Strict one-in-flight chunk pregen; rejected on non-Paper serial support |
| `sync` / in-flight flags | Modded | Synchronous or capped async pregen; see `07 - Pregeneration.md` |
| `moddedPregenInFlight` | Modded | Caps concurrent pregen chunk work |

For profiling and determinism isolation, prefer serial/sync one-in-flight runs. For production throughput, use default concurrency and raise heap before raising mantle plate caps.

## Practical tuning order

1. **Heap and GC** — give the process enough heap for pack caches + mantle plates (release smoke used 8 GiB heap on large pregens; size to hardware).
2. **Confirm SIMD module** — check startup log for `SIMD: vector kernels enabled` vs scalar message.
3. **Pregen concurrency** — use default; only lower in-flight / use serial when CPU saturated or region scheduling warns.
4. **Mantle residency** — if backpressure timeouts appear, reduce `maxResidentTectonicPlates` or pregen speed; increase heap if plates thrash.
5. **Caches** — raise object/resource caches when the same objects reload repeatedly; lower if heap retains too much after pregen.
6. **engineSVC.parallelism** — set an explicit positive value only after measuring; `-1` already scales with CPU via `ceil(sqrt(n))`.
7. **Never “tune” by changing pack content** for performance without a GoldenHash re-baseline — pack edits change terrain.

## Measurement checklist

Record for each experiment: pack identity, seed, radius, serial/sync flags, JVM version/flags, heap, CPU, `settings.json` performance/pregen excerpts, chunks/second, duration, failed chunks, peak heap, and GoldenHash combined value. Reject optimizations that change hashes unless the behavior change is intentional and documented. Larger release-style baselines (5k–10k chunks, JProfiler) are tracked in `87 - Maintainer - Release Readiness.md`.

## Offline tools

| Tool | Command | Use |
|------|---------|-----|
| Generation probe | `./gradlew :probe:genProbe -PprobePack=…` | Headless engine generate; not a throughput benchmark |
| Classload probe | `./gradlew :probe:run` | Purity/classload gate |
| SIMD microbench | `tools/simd-bench/./run.sh` | Kernel-only scalar vs vector timing |

Smoke procedures that combine pregen and GoldenHash: `31 - Operator Runbooks & Smoke Tests.md`.

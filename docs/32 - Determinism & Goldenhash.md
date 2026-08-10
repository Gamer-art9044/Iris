# 32 - Determinism & Goldenhash

GoldenHash is the cross-platform determinism gate: it generates chunks into in-memory buffers (no world block writes), hashes blocks and biomes, and either captures a baseline file or verifies against one. Identical pack bytes, Iris seed, radius, center, and height range must produce the same combined hash on Bukkit-family and every mod loader. Operator smoke sequences that use this gate are in `31 - Operator Runbooks & Smoke Tests.md`.

## Tutorial: capture and compare a baseline

Prerequisites: a disposable Iris world, fixed pack bytes, seed `1337`, center chunk `0,0`, and a small radius such as `8` for the first run. Record the Iris artifact, Minecraft version, pack hash, dimension height range, and JVM before capture.

1. On Bukkit, run the same `AUTO` command twice. The first run captures when the file is absent; the second must verify it:

   ```text
   /iris developer goldenhash world=<world> radius=8 threads=1 center-x=0 center-z=0 reset-mantle=true deep=false
   /iris developer goldenhash world=<world> radius=8 threads=1 center-x=0 center-z=0 reset-mantle=true deep=false
   ```

2. On Fabric, Forge, or NeoForge, use explicit modes:

   ```text
   /iris goldenhash 8 1 capture
   /iris goldenhash 8 1 verify
   ```

3. Retain the generated `.hashes` file with the exact artifact and pack hash. Copy that baseline into the platform-specific golden directory on another loader, keep the dimension key, seed, radius, center, and height range identical, then run `verify` or Bukkit `AUTO`.
4. Confirm every platform reports `MATCH` and the same `#combined` value. Visual similarity is not parity evidence.
5. In a disposable copy, change one pack or engine input, rerun verify, and confirm the expected mismatch produces `.new` and a diagnosis file.
6. Restore the original input and confirm `MATCH` again before treating the baseline as a release artifact.

GoldenHash resets mantle according to the platform rules below. Never use a production world merely because block writes are buffered.

### Recovery

| Result | Correction |
|---|---|
| No baseline on Bukkit | Confirm the target is a loaded Iris world; the first `AUTO` run should capture. |
| `VERIFY` says the file is missing | Check the golden directory and exact filename inputs: dimension key, seed, center, and radius. |
| Seed or dimension mismatch | Recreate the disposable world with the recorded input; do not rename metadata to force a comparison. |
| Multi-thread mismatch only | Re-run both sides with `threads=1`; treat continued order dependence as an engine defect. |
| Stable mismatch | Compare pack bytes, height range, Iris artifact, Minecraft version warning, and mantle-reset choice, then inspect `.new` and the first `.diag-*` file. |
| Unstable repeat generation | Preserve the diagnosis artifacts and stop the release comparison; consecutive generation is nondeterministic. |

## What it measures

- **Blocks:** every local column `x,z ∈ [0,15]` and every `y` from engine/world min height (inclusive) to max height (exclusive). Each block state key (for example `minecraft:stone`) is fed into a per-chunk SHA-256 digest.
- **Biomes:** same height span sampled on a 4-block step in x, y, and z (`BIOME_STEP = 4`). Null biome samples hash as `minecraft:plains` (constant `GoldenHashEngine.FALLBACK_BIOME_KEY`).
- **Combined hash:** SHA-256 over the ordered per-chunk lines for the full spiral; stored as `#combined=<hex>` in the golden file.
- **World disk:** buffers only — the Minecraft region files are not written by the scan. **Mantle** (Iris multi-chunk structure/carving cache) **is reset** when `reset-mantle` is true (Bukkit default) or always on modded.

Use disposable test worlds. Mantle reset deletes mantle files under the engine mantle data folder so regeneration starts from a clean mantle state.

## File location and name

| Platform | Golden directory |
|----------|------------------|
| Bukkit-family plugin | Iris data folder `golden/` (for example `plugins/Iris/golden/`) |
| Fabric / Forge / NeoForge | Loader config dir `irisworldgen/golden/` |

Filename pattern:

```
<dimensionLoadKey>-s<seed>-c<centerChunkX>x<centerChunkZ>-r<radius>.hashes
```

Example: `overworld-s1337-c0x0-r22.hashes`.

Format header: `iris-goldenhash v1`. Metadata lines include `#world`, `#dim`, `#seed`, `#mc`, `#minY`/`maxY`, `#center`, `#radius`. Body lines are `chunkX chunkZ <blockSha256> <biomeSha256>`.

On mismatch, the engine also writes:

- `<file>.new` — current scan body + combined hash
- `<file>.diag-c{x}x{z}.txt` — first mismatched chunk diagnosis (repeat-generation stability, mantle-reset comparison, non-air dump)
- Optional deep dumps when `deep=true` (Bukkit only): non-air blockstate listings under a `.deep` / `.deep-verify` sibling directory

## Modes

Shared engine modes: `AUTO`, `CAPTURE`, `VERIFY`.

| Mode | Behavior |
|------|----------|
| `CAPTURE` | Always write a new golden file |
| `VERIFY` | Fail if no golden file; compare and report MATCH/MISMATCH |
| `AUTO` | Capture when the file is missing; verify when it exists |

Bukkit command always uses `AUTO`. Modded commands expose `capture`, `verify`, and default `AUTO`.

Verify rejects seed or dimension mismatches as hard failures. Minecraft version metadata mismatch is a **warning** only (hash comparison still runs).

## Commands

### Bukkit

```
/iris developer goldenhash world=<world> radius=<chunks> threads=<n> center-x=<cx> center-z=<cz> reset-mantle=<bool> deep=<bool>
```

Alias: `gold`. Defaults: `radius=8`, `threads=8`, `center-x=0`, `center-z=0`, `reset-mantle=true`, `deep=false`. Radius must be ≥ 0. Target must be a loaded Iris world with a live engine.

- Seed recorded and used for filename: `World.getSeed()`.
- MC version string: `Bukkit.getBukkitVersion()`.
- Generation path: `engine.generate` into a `TerrainChunk` buffer.

### Modded (Fabric / Forge / NeoForge)

```
/iris goldenhash [radius] [threads] [capture|verify]
```

Alias: `gold`. Defaults: radius `8`, threads `8`, mode `AUTO`. Radius range on the command tree: `0..256`. Threads: `1..64`. Center is always chunk `0,0`. Mantle is always reset. Deep dump is not exposed.

- Seed: Iris engine seed (`engine.getSeedManager().getSeed()`), not a vanilla level-seed quirk.
- MC version: loader-reported Minecraft version string.
- Generation path: `engine.generate` into modded block/biome buffers.
- Only one modded GoldenHash scan may run at a time.

Only one shared-engine scan may be active process-wide (`GoldenHashEngine.isActive()`).

## Determinism rules operators must follow

1. **Same pack bytes** on every platform (same pack key, same files; freeze default overworld downloads to a commit/tag for release baselines).
2. **Same Iris seed** (create with `seed=1337` / positional `1337` as in `02 - Getting Started.md`).
3. **Same radius and center** (modded center is fixed at 0,0 — use `center-x=0 center-z=0` on Bukkit for parity).
4. **Same height range** (dimension min/max Y). Height is part of the hash window.
5. **Prefer `threads=1` for order-dependence checks.** Multi-thread scans must still match the serial result when the engine is deterministic; a multi-thread-only mismatch is a bug.
6. **Reset mantle** between capture and verify when comparing regeneration purity (`reset-mantle=true` / modded always).
7. **Do not treat world pregen order as the hash source** — GoldenHash re-generates into buffers. Pregen is still useful as a stress gate before or after hashing (`07 - Pregeneration.md`, `31 - Operator Runbooks & Smoke Tests.md`).

## Interpreting results

| Result | Meaning |
|--------|---------|
| Captured | New baseline written; path printed |
| MATCH | All chunk lines equal; combined hash short form shown |
| MISMATCH | One or more chunks differ; up to 10 chunk keys listed; `.new` written; diagnosis on first mismatch |
| Aborted | Not all chunks generated successfully; no golden write |
| Wrong world | Golden `#seed` / `#dim` does not match current engine |

Diagnosis labels:

- **Repeat-generation STABLE** — two back-to-back generations of the same chunk agree; divergence is order/state-dependent relative to the golden or mantle, not pure non-determinism per call.
- **Repeat-generation UNSTABLE** — same chunk differs between two consecutive generations without intervening work.
- **Mantle-reset** section compares the first generation to a generation after deleting mantle chunks in the structure radius.

## Offline generation probe (not GoldenHash files)

`./gradlew :probe:genProbe -PprobePack=…` builds an offline engine (dimension key `overworld`, seed `1337`) and prints per-chunk hashes to stdout. It validates the pack and exercises generation without a server; it does **not** read or write `iris-goldenhash v1` files. Use in-game GoldenHash for cross-platform golden files. Probe overview: `31 - Operator Runbooks & Smoke Tests.md`.

## Release gate expectation

Release procedure requires GoldenHash **VERIFY** to pass on all four platforms with the **same** combined hash for the shared pack and seed (`86 - Maintainer - Release Checklist.md`). An unexplained deterministic output change is a release blocker (`87 - Maintainer - Release Readiness.md`).

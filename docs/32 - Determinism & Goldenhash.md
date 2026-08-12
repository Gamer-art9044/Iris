# 32 - Determinism & Goldenhash

GoldenHash is the cross-platform determinism gate. It regenerates a square of chunks into memory buffers, hashes the blocks and biomes, and either writes that fingerprint to a baseline file or compares against one. The same pack bytes, Iris seed, radius, center, and height range must produce the same combined hash on Bukkit-family servers and on every mod loader. The operator runbooks that use this gate are in `31 - Operator Runbooks.md`.

## What determinism means here

Iris generation is a pure function of the pack plus a seed. Feed the same pack and the same seed to the engine and every block and biome must come back identical — same chunk, same platform, next week, other machine. Nothing in that chain is allowed to depend on wall-clock time, hash iteration order, thread scheduling, or which chunks happened to be generated first.

That property is what makes an Iris world portable. A player's base survives a server restart, a Bukkit world can be reproduced on Fabric, and a pack author can hand someone a seed and get the same terrain back. When determinism breaks, it usually breaks quietly: a handful of chunks differ at a biome boundary or inside a cave, nobody notices until a world is regenerated months later and the terrain no longer lines up with the buildings on it.

GoldenHash exists because eyeballing terrain cannot catch that. Two screenshots can look identical while thousands of blocks differ. A hash catches a single changed block state.

## What GoldenHash actually does

1. Optionally deletes the world's mantle so nothing cached from an earlier run can leak into the comparison.
2. Walks a center-out spiral of chunks and calls `engine.generate` for each one into a scratch buffer. The world's region files are never touched.
3. SHA-256s every block state key in the chunk, and separately SHA-256s biome samples taken on a 4-block grid.
4. Sorts the per-chunk lines by chunk coordinate and SHA-256s the whole ordered body into one `#combined` value. Sorting is why thread count does not change the result.
5. Writes that as a baseline, or compares it to an existing one and reports MATCH or MISMATCH.

Because the scan regenerates rather than reading the world, it also proves the engine can reproduce what it already wrote — not just that the world file on disk is stable.

## Capture a baseline

Prerequisites: a disposable Iris world, frozen pack bytes, a known seed (`1337` is the convention across these docs), center chunk `0,0`, and a small radius such as `8` for a first run. Write down the Iris artifact version, Minecraft version, pack hash, dimension height range, and JVM before you start — a baseline without that provenance is not usable later.

1. On Bukkit, run the command twice. The Bukkit command is always `AUTO`: the first run captures because no file exists, the second verifies against it.

   ```text
   /iris developer goldenhash world=<world> radius=8 threads=1 center-x=0 center-z=0 reset-mantle=true deep=false
   /iris developer goldenhash world=<world> radius=8 threads=1 center-x=0 center-z=0 reset-mantle=true deep=false
   ```

   Expected: the first run prints a captured line with the chunk count, a 12-character short hash, and the absolute path of the `.hashes` file. The second prints MATCH with the same short hash.

   If the first run prints nothing but an error about the world, the target is not a loaded Iris world with a live engine. Check `/iris worlds`.

2. On Fabric, Forge, or NeoForge, ask for the modes explicitly:

   ```text
   /iris goldenhash 8 1 capture
   /iris goldenhash 8 1 verify
   ```

   Expected: `capture` writes the file, `verify` reports MATCH. Progress lines appear per chunk while the total is 64 chunks or fewer, then every 32nd chunk above that.

3. Keep the `.hashes` file next to the exact artifact build and pack hash it came from. It is only meaningful with that provenance.

## Compare a second platform

1. Copy the `.hashes` file into the other platform's golden directory (see the table below). Do not rename it — the filename encodes the dimension key, seed, center, and radius the comparison depends on.
2. Create a disposable world there from the same pack bytes and the same seed, with the same dimension height range.
3. Run `verify` (modded) or the same `AUTO` command (Bukkit).
4. Expected: MATCH, and the same `#combined` value on every platform. Screenshots that look alike prove nothing.

Expect one warning on every cross-platform comparison: the `#mc` metadata line differs because Bukkit records `Bukkit.getBukkitVersion()` (for example `26.2-R0.1-SNAPSHOT`) while mod loaders record the plain Minecraft version (`26.2`). That mismatch is a warning only and does not stop the comparison. A seed or dimension mismatch is a hard failure and does stop it.

## Prove the gate can fail

A gate nobody has seen fail is not a gate. Once, in a disposable copy:

1. Change one pack input or one engine input.
2. Re-run verify. Expected: MISMATCH, a `.new` file next to the golden file, and a `.diag-c<x>x<z>.txt` for the first differing chunk.
3. Restore the original input and confirm MATCH returns before you treat the baseline as a release artifact.

## Reading a result

| Result | Meaning |
|--------|---------|
| Captured | No baseline existed; one was written. The path is printed. |
| MATCH | Every chunk line is byte-identical to the baseline. The short combined hash is shown. |
| MISMATCH | At least one chunk differs. Up to 10 chunk keys are listed, then a count of the rest. `.new` and a diagnosis file are written. |
| Aborted | Some chunk failed to generate. Nothing is written; fix the generation failure first. |
| Wrong world | The baseline's `#seed` or `#dim` does not match the live engine. No comparison is attempted. |

The diagnosis file regenerates the first mismatched chunk twice back to back, then a third time after deleting mantle chunks around it, and labels what it found:

- **Repeat-generation STABLE** — two consecutive generations agree. The divergence depends on order or accumulated state, not on the generate call itself.
- **Repeat-generation UNSTABLE** — the same chunk differs between two consecutive calls with nothing in between. That is pure nondeterminism and is always an engine defect.
- **Mantle-reset** — compares the scan result against a generation with the surrounding mantle deleted. A difference here points at mantle carryover rather than the terrain math.

The file ends with a full non-air block dump of the first generation, which is what you diff against the other platform's dump.

## When a check fails

| Symptom | What to do |
|---|---|
| Bukkit run says the target is not an Iris world | The world is not loaded or has no engine. Load it and retry; `AUTO` cannot capture from a dead engine. |
| `verify` says the baseline file is missing | The filename is built from dimension key, seed, center, and radius. One of those differs from the capture. Compare the name in the golden directory against your command. |
| Wrong world (seed or dimension) | Recreate the disposable world with the recorded seed and pack. Never hand-edit the metadata lines to force a comparison. |
| Mismatch only with multiple threads | Re-run both sides with `threads=1`. If serial matches and parallel does not, that is an engine defect, not a tuning problem — the result is sorted before hashing, so thread count must not matter. |
| Stable mismatch on both sides | Compare pack bytes, dimension height range, Iris artifact, and whether both runs reset mantle. Then read `.new` and the first `.diag-*` file. |
| Repeat-generation UNSTABLE | Stop the release comparison. Preserve the diagnosis artifacts; consecutive generation is nondeterministic and no baseline is trustworthy until that is fixed. |

## Rules that keep comparisons honest

1. **Same pack bytes everywhere.** Same pack key, same files. For a release baseline, freeze the default overworld download to a commit or tag.
2. **Same Iris seed.** Create with `seed=1337` (Bukkit) or the positional `1337` (modded), as in `02 - Getting Started.md`.
3. **Same radius and center.** Modded center is fixed at chunk `0,0`, so use `center-x=0 center-z=0` on Bukkit.
4. **Same height range.** The dimension's min and max Y define the hash window; changing height changes the hash by definition.
5. **Use `threads=1` when hunting order dependence.** Multi-threaded scans must still match the serial result.
6. **Reset mantle on both sides** when you are comparing regeneration purity. Bukkit defaults to `reset-mantle=true`; modded always resets.
7. **Do not confuse pregen with hashing.** GoldenHash regenerates into buffers and ignores whatever pregen wrote. Pregen is still worth running as a stress gate before or after (`07 - Pregeneration.md`).
8. **Disposable worlds only.** Buffers mean no block writes, but `reset-mantle` deletes the world's mantle files, and the diagnosis path deletes mantle chunks around the failing chunk.

## Reference

### What is hashed

- **Blocks:** every local column `x,z` in `0..15` and every `y` from the engine or world minimum height (inclusive) to the maximum (exclusive). Each block state key (for example `minecraft:stone`) is fed into a per-chunk SHA-256 digest.
- **Biomes:** the same height span sampled every 4 blocks in x, y, and z (`BIOME_STEP = 4`). A null biome sample hashes as `minecraft:plains` (`GoldenHashEngine.FALLBACK_BIOME_KEY`).
- **Combined:** SHA-256 over the per-chunk lines sorted by packed chunk key, stored as `#combined=<hex>`.
- **Not touched:** Minecraft region files. The scan generates into buffers only.
- **Deleted when `reset-mantle` is true:** every file directly inside the engine's mantle data folder, after a `saveAll`. This is the whole world's mantle, not only the scanned square, despite what the in-game help text implies.

### Golden file location and name

| Platform | Golden directory |
|----------|------------------|
| Bukkit-family plugin | Iris data folder `golden/` (for example `plugins/Iris/golden/`) |
| Fabric / Forge / NeoForge | `<configDir>/irisworldgen/golden/` |

```
<dimensionLoadKey>-s<seed>-c<centerChunkX>x<centerChunkZ>-r<radius>.hashes
```

Example: `overworld-s1337-c0x0-r22.hashes`.

The seed in that name is `World.getSeed()` on Bukkit and the Iris engine seed (`engine.getSeedManager().getSeed()`) on mod loaders. For a world created through `/iris create` with an explicit seed they are the same value.

File layout:

```
#iris-goldenhash v1
#world=<world name>
#dim=<dimension load key>
#seed=<seed>
#mc=<platform Minecraft version string>
#minY=<min> maxY=<max>
#center=<cx>,<cz>
#radius=<chunks>
<chunkX> <chunkZ> <blockSha256> <biomeSha256>
...
#combined=<sha256>
```

On mismatch the engine also writes:

- `<file>.new` — the current body plus its combined hash
- `<file>.diag-c<x>x<z>.txt` — diagnosis of the first mismatched chunk
- `<file>.deep/` or `<file>.deep-verify/` — per-chunk non-air block dumps when `deep=true` (Bukkit only); the `-verify` suffix is used when a golden file already exists

### Modes

| Mode | Behavior |
|------|----------|
| `CAPTURE` | Always write a new golden file |
| `VERIFY` | Fail if no golden file exists; otherwise compare and report MATCH or MISMATCH |
| `AUTO` | Capture when the file is missing, verify when it exists |

The Bukkit command is hard-wired to `AUTO`. Modded exposes `capture` and `verify` literals and falls back to `AUTO`.

### Commands

Bukkit:

```
/iris developer goldenhash world=<world> radius=<chunks> threads=<n> center-x=<cx> center-z=<cz> reset-mantle=<bool> deep=<bool>
```

Alias `gold`. Defaults: `radius=8`, `threads=8`, `center-x=0`, `center-z=0`, `reset-mantle=true`, `deep=false`. Radius must be at least 0. The target must be a loaded Iris world with a live engine. Metadata comes from `World.getSeed()`, `Bukkit.getBukkitVersion()`, and the world's min and max height. Nothing stops a second Bukkit scan from starting while one is running; do not start one.

Modded (Fabric / Forge / NeoForge):

```
/iris goldenhash [radius] [threads] [capture|verify]
```

Alias `gold`. Defaults: radius `8`, threads `8`, mode `AUTO`. The command tree accepts radius `0..256` and threads `1..64`. Center is always chunk `0,0`, mantle is always reset, and deep dumps are not exposed. A second scan is refused while one is running.

While any scan is active, Iris suspends engine mantle maintenance (trimming and plate unloading) process-wide so maintenance cannot perturb the comparison.

### Offline generation probe (not a GoldenHash file)

`./gradlew :probe:genProbe -PprobePack=…` builds an offline engine for dimension key `overworld` at seed `1337`, validates the pack, generates a chunk spiral into buffers, and prints per-chunk hashes to stdout. It never reads or writes `iris-goldenhash v1` files, so it cannot be compared against a baseline. Use it as a headless regression signal; use in-game GoldenHash for cross-platform gates. Probe details: `31 - Operator Runbooks.md`.

### Release gate

Release requires GoldenHash `VERIFY` to pass on all four platforms with the same combined hash for the shared pack and seed (`86 - Maintainer - Release Checklist.md`). An unexplained change in that hash blocks the release (`87 - Maintainer - Release Readiness.md`).

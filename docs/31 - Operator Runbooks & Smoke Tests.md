# 31 - Operator Runbooks & Smoke Tests

Manual verification sequences for operators and maintainers after install, upgrade, pack change, or release candidate build. Each runbook ends when the stated gate passes. Full command trees and permissions live in `04 - Commands & Permissions.md`; pregen options in `07 - Pregeneration.md`; platform differences in `30 - Platform Differences.md`.

## Fixed inputs for parity smoke

Use the same inputs whenever comparing platforms or runs:

| Input | Typical value | Notes |
|-------|---------------|--------|
| Pack | Shipping default `overworld` (or a frozen pack copy) | Byte-identical pack on every platform under test |
| Seed | `1337` | World seed on Bukkit; Iris engine seed on modded |
| GoldenHash radius | `22` chunks (optional smaller `8` for quick smoke) | Chunk count = `(2r+1)²`; radius 22 = 2,025 chunks |
| GoldenHash threads | `1` for strict serial; `8` default for multi-thread smoke | `threads=1` catches order-dependence |
| Pregen radius | `352` blocks for 2,025-chunk square when centered at 0,0 | Radius is in **blocks**, not chunks |

GoldenHash details and file layout: `32 - Determinism & Goldenhash.md`.

## A. Fresh install and first world (Bukkit-family)

1. Install the CraftBukkit-family jar into `plugins/` (Paper, Purpur, Folia, Spigot, Leaf, Canvas as advertised). Require Java 25. See `01 - Installation & Platforms.md`.
2. Start the server once. Confirm Iris enables, default pack download completes when no pack is present, and `settings.json` is written under the Iris data directory.
3. Create a world with a fixed seed and teleport into it:

```
/iris create smoke-ow type=overworld seed=1337
/iris tp smoke-ow
```

4. Join or teleport into the world. Confirm non-empty terrain, surface biomes, and no repeating console stack traces on first chunks.
5. Gate: world is loaded as an Iris world; chunks generate without enable-time crash; console shows no fatal engine init failure.

## B. Fresh install and first world (Fabric / Forge / NeoForge)

1. Install the matching mod jar into `mods/`. Fabric requires Loader ≥ declared floor; Forge/NeoForge require their declared floors. See `01 - Installation & Platforms.md` and `30 - Platform Differences.md`.
2. Start dedicated server (or integrated singleplayer for client-mod smoke). Confirm Iris boots, default pack installs, and datapack/biome registration completes.
3. Create a world with fixed seed (positional mod syntax):

```
/iris create smoke-ow overworld 1337
```

4. Enter the dimension. Confirm non-empty generation and custom-biome registration where the pack defines custom biomes.
5. Gate: same as Bukkit section A for generation health; document intentional capability gaps only via `30 - Platform Differences.md`.

## C. Pack validation smoke

1. With packs installed:

```
/iris pack validate
```

Or a single pack: `/iris pack validate pack=<pack>` on Bukkit, `/iris pack validate <pack>` on modded.

2. Review blocking errors vs warnings. Blocking errors must be fixed before treating the pack as production-ready.
3. Optional: `/iris pack status` replays the last recorded validation result for the session.
4. Gate: target pack is loadable; no unexpected blocking errors on the shipping default pack. Cleanup/restore flows are separate and opt-in (`25 - Pack Management.md`).

## D. Pregeneration control smoke

Radius is always in **blocks**. Prefer a disposable test world.

**Bukkit (keyed optional args):**

```
/iris pregen start 352 world=smoke-ow center=0,0 gui=false
/iris pregen status
/iris pregen pause
/iris pregen status
/iris pregen pause
/iris pregen stop
```

Strict one-chunk-at-a-time mode (Paper-compatible only; not Folia serial gate):

```
/iris pregen start 352 world=smoke-ow center=0,0 gui=false serial=true
```

**Modded (positional / flag composition):**

```
/iris pregen start 352 irisworldgen:smoke-ow at 0 0 sync
/iris pregen status
```

Use `sync` / in-flight caps as documented in `07 - Pregeneration.md`. Pause/resume/stop availability follows the modded pregen command surface.

Gates:

- Start reports the correct world, center, and size.
- Status shows generated/total, percent, speed, and failed count when any.
- Pause freezes progress; second pause resumes.
- Stop cancels without claiming full success when work remains.
- A full serial/sync 2,025-chunk run (radius 352 at 0,0) completes with zero failed chunks for release-level evidence.

Client HUD: with the Iris client mod, pregen progress arrives on channel `irisworldgen:main`; vanilla clients use boss bar / console only (`29 - Client HUD & Protocol.md`).

## E. GoldenHash determinism smoke

Run on a **disposable** Iris world. GoldenHash generates into buffers (does not write world blocks) but **resets mantle** by default — treat the world as expendable.

**Bukkit** (`AUTO`: capture if golden file missing, verify if present):

```
/iris developer goldenhash world=smoke-ow radius=22 threads=1
```

Optional: `center-x=0 center-z=0 reset-mantle=true deep=false`. Defaults: radius `8`, threads `8`, reset-mantle `true`, center `0,0`.

**Modded** (center fixed at chunk 0,0; mantle always reset):

```
/iris goldenhash 22 1 capture
/iris goldenhash 22 1 verify
```

Alias: `/iris gold …`. Defaults without args: radius `8`, threads `8`, mode `AUTO`.

Gates:

- Capture writes a `.hashes` file under the platform golden directory.
- Second run with the same pack/seed/radius/center reports **MATCH** and the same combined hash.
- The same pack+seed+radius+center hash matches across Bukkit, Fabric, Forge, and NeoForge when comparing identical artifacts and pack bytes. Cross-platform rule: `32 - Determinism & Goldenhash.md`.

## F. Restart and existing-world smoke

1. After some pregen or free exploration, stop the server cleanly.
2. Start again without deleting world data.
3. Load the same Iris world; generate new chunks outside the pregenerated area.
4. Gate: world loads; new chunks generate; no blank-chunk regression on restart; pregen cache resume behaves as documented when a job is resumed (`07 - Pregeneration.md`).

## G. Studio smoke (authoring path)

```
/iris studio open overworld seed=1337
```

Edit a pack file on disk (or via the VSCode workspace from `/iris studio vscode dimension=overworld` on Bukkit). Confirm hotload applies without server restart when supported. Close with `/iris studio close` (studio worlds are transient and discarded).

Gate: studio world opens; hotload either applies successfully or fails closed without poisoning the live engine for non-studio worlds. Studio details: `10 - Studio & VSCode Schemas.md`.

## H. Offline probe module (no live server)

From the Iris project root (JDK 25). These are CI-oriented gates, not in-game commands.

| Task | Purpose |
|------|---------|
| `./gradlew :probe:run` (ClassloadProbe) | Loads compiled `core` classes without `org.bukkit` on the runtime classpath; fails on purity violations outside the allowlist |
| `./gradlew :probe:deserializationProbe` | Deserializes fixture entity/spawner/loot JSON through real Iris loaders on a Bukkit-free JVM |
| `./gradlew :probe:genProbe -PprobePack=/path/to/packs/overworld` | Builds a real engine for dimension `overworld`, seed `1337`, generates a chunk spiral into buffers |

`genProbe` properties: `probePack` (required usable pack path), `probeRadius` (default `2`), `probeCenterChunkX` / `probeCenterChunkZ` (default `0`). The task clones the pack into a temp directory, runs `PackValidator`, then generates.

Gate: each probe exits 0. Classload and deserialization probes are part of the release verify job when CI is green (`86 - Maintainer - Release Checklist.md`).

## I. Minimal post-upgrade checklist

After replacing the jar/mod only:

1. Boot on the same world data.
2. `/iris pack validate` on production packs.
3. Generate a few new chunks in an existing Iris world.
4. Optional short GoldenHash verify against a stored baseline if the pack and seed are unchanged (`32 - Determinism & Goldenhash.md`).
5. If pregen was mid-job, confirm status/resume or cancel cleanly (`07 - Pregeneration.md`).

Gate: no enable crash, packs still loadable, generation continues.

## J. Failure triage order

1. Confirm Java 25 and correct platform artifact (`01 - Installation & Platforms.md`).
2. Confirm pack validates and dimension key exists (`25 - Pack Management.md`, `05 - Concepts & Pack Layout.md`).
3. Confirm target is an Iris world/engine (`06 - Worlds & Lifecycle.md`).
4. Capture GoldenHash with `threads=1` and `reset-mantle=true`; if mismatch, read the written `.new` / `.diag-…` files (`32 - Determinism & Goldenhash.md`).
5. For throughput or memory issues, tune settings before changing packs (`33 - Performance Tuning.md`).
6. For release candidates, escalate to maintainer gates (`87 - Maintainer - Release Readiness.md`).

# 31 - Operator Runbooks & Smoke Tests

Manual verification sequences for operators and maintainers after install, upgrade, pack change, or release candidate build. Each runbook ends when the stated gate passes. Full command trees and permissions live in `04 - Commands & Permissions.md`; pregen options in `07 - Pregeneration.md`; platform differences in `30 - Platform Differences.md`.

## How to run these tutorials

Use a purpose-named disposable world and record the exact Iris artifact, platform build, Java version, pack hash, seed, and commands before starting. Run only the sections affected by a local authoring change; run the full platform set for a release candidate.

Do not merge different proof types. A Gradle test is automated evidence, a successful server boot is startup evidence, and a player moving through generated chunks is gameplay evidence. Record each gate separately and clean up only instances/worlds created for the smoke.

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

## D. Bukkit datapack dimension-scope smoke

Use a disposable server with one managed datapack source, a vanilla world, one Iris dimension declaring that source, and one Iris dimension that does not declare it. Install or ingest the datapack, restart so its registries are live, then create both Iris worlds so the scope is applied before their initial spawn chunks load.

1. In each world, run `/locate structure <managed-structure-key>` using a key listed by `/iris structure list <declaring-dimension>`.
2. Generate new chunks in all three worlds; do not use existing chunks as proof because scope changes do not rewrite them.
3. Gate: locate and natural generation retain the managed structure in the declaring Iris world, while the vanilla and nondeclaring Iris worlds neither locate nor generate it.
4. Restart without removing the installed datapack and repeat locate plus new-chunk generation. Gate: the same per-world result remains and no ownership or structure-state failure appears during world initialization.

## E. Pregeneration control smoke

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

## F. GoldenHash determinism smoke

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

## G. Restart and existing-world smoke

1. After some pregen or free exploration, stop the server cleanly.
2. Start again without deleting world data.
3. Load the same Iris world; generate new chunks outside the pregenerated area.
4. Gate: world loads; new chunks generate; no blank-chunk regression on restart; pregen cache resume behaves as documented when a job is resumed (`07 - Pregeneration.md`).

## H. Studio smoke (authoring path)

### General pack Studio

```
/iris studio open overworld seed=1337
```

Edit a pack file on disk (or via the VSCode workspace from `/iris studio vscode dimension=overworld` on Bukkit) while moving through fresh chunks so Moonrise has active generation stages. Confirm hotload applies without server restart, already-admitted stages finish before the transition, and later stages resume afterward. Close with `/iris studio close` while fresh chunks are still queued (studio worlds are transient and discarded).

Gate: studio world opens; hotload and close do not produce a generation-session rejection, partial chunk-stage failure, or chunk-system crash; hotload either applies successfully or fails closed without poisoning the live engine for non-studio worlds. Studio details: `10 - Studio & VSCode Schemas.md`.

### Jigsaw Studio: planar authoring and atomicity (Bukkit)

Use a disposable pack/structure key and the owning builder account. Bukkit has one global Studio project/world and one owning Jigsaw session. Non-owner block edits and recognized mutating commands must be denied throughout this world. This command tree is not registered on Fabric, Forge, or NeoForge.

1. Create a project without optional arguments so the defaults are exercised:

   ```text
   /iris jigsaw create overworld smoke/jigsaw
   /iris jigsaw status
   ```

   Gate: the add-only transaction owns one structure, three pools, six pieces, six objects, and one manifest before Studio opens. The player enters creative above Blank. `status` reports `PLANAR_JIGSAW`, `IRIS_EXTENDED`, six workcells, 15×15×15 for the selected workcell, six variants, no pending autosave, and the seed-`1337` evaluation. The key tab-completes for `open`, `edit`, and `reopen`; the GUI and owned resources show one loaded variant per archetype, theme `variant-1`, terminal End, and mandatory caps off.

2. Inspect the exact Blank, End Cap, Hallway, L Junction, T Junction, Cross Junction layout. Floors are light-gray wool, topology paths are red wool, and canonical endpoints are sea lanterns. There are no orientation, permutation, piece, or derived-rotation cells. Toggle player-local particles:

   ```text
   /iris jigsaw goto workcell/blank
   /iris jigsaw goto workcell/straight
   /iris jigsaw goto workcell/cross
   /iris jigsaw particles false
   /iris jigsaw particles true
   ```

   Gate: the occupied valid cell is aqua, nearby valid bounds are dark gray, and an invalid cell is red. Focused connectors draw 1.75-block direction lines. The Iris scoreboard replaces the general Studio context with Structure, Workcell, Variant, State, and `Triple-sneak for controls`, without orientation/mask fields. All six untouched cells initially report **Autosaved**. Enter End Cap, triple-sneak, and confirm the menu selects End Cap rather than the previously selected cell.

3. Open the same six-row controls three ways: right-click the protected chest, run `/iris jigsaw menu`, and start three sneaks within 1.5 seconds. Select Hallway and click **New Blank Variant**. Wait for its atomic graph result and load, then reopen the controls. Rename the loaded variant and Hallway workcell through their anvil inputs; confirm labels round-trip while the piece key, `straight` stable ID, and solver role stay unchanged. Load End Cap and use **Duplicate This Cell's Variant**, then load Cross Junction and duplicate it as well.

   Gate: the new key follows `smoke/jigsaw/variants/straight/variant-<n>` and loads into Hallway. It has the source piece's complete metadata and exact pool entries but an empty same-sized object. At the default 15×15×15, its two real markers occupy `(7,7,0)` and `(7,7,14)`, face north/south with top `UP_POSITIVE_Y`, show pool `iris:smoke/jigsaw/pieces`, use name/target `iris:planar`, `ALIGNED`, `minecraft:structure_void`, and signed priorities `0`. Mojang's UI is usable after hydration. Break one marker and click **Reset Connector Blocks** before autosave; both saved markers must return while another edited block remains unchanged. Each duplicate copies the active object's bytes, display label, and complete piece metadata. The End Cap duplicate has exact matching entries in both `smoke/jigsaw/pieces` and `smoke/jigsaw/caps`; the Cross Junction duplicate has exact matching entries in both `smoke/jigsaw/start` and `smoke/jigsaw/pieces`. An empty or unassigned workcell refuses both GUI actions and directs the operator to `/iris jigsaw piece create <poolKey> <pieceKey>` instead of choosing a fallback pool.

4. Change one permanent block, one marker field, and one chest inventory inside Hallway. Keep the permanent block and chest within the later 16×3×3 target, such as Y/Z offsets `1,1`. After changing a marker field in Mojang's UI, immediately run `/iris jigsaw status`; change it again and immediately run `/iris jigsaw close`. Also trigger internal inventory transfer or hopper pickup and at least one furnace, brewing-stand, dispenser, or crafter update inside the workcell. Do not flush autosave. Wait at least 40 ticks after the final update, then inspect status:

   ```text
   /iris jigsaw status
   ```

   Gates: the command and close attempt request a final owning-region marker snapshot; close waits behind marker finalization and autosave instead of losing the last UI change. State moves through dirty/saving to clean automatically; the inventory and machine changes also mark it dirty; one complete multi-resource commit occurs; and no partial resource appears. Make six distinct saved block edits, then click **Undo Last Autosave** five times. Confirm each prior block state and manifest hash returns in reverse order, the sixth-oldest state is no longer available, one `.iris/jigsaw-history/key-<sha256>.json` file held the stack, and no transaction debris remains. Make another edit while capture is pending, immediately click **Duplicate This Cell's Variant**, and confirm Iris expedites autosave then performs that one duplicate exactly once without a wait/retry instruction. Repeat with dirty edits in multiple enabled cells and **Duplicate All Enabled Cells as Family**. Invoke **Flush Autosave Now** while capture cannot start and confirm the same ticket remains pending, retries, and eventually becomes clean. Close/reopen, load the variant, and confirm block, marker NBT, inventory, explicit-air final state when used, and `structure_void` absence round-trip. **Flush Autosave Now** and `/iris jigsaw save` are not required.

   On Paper, repeat one dirty edit immediately before plugin disable and confirm the synchronous final drain persists it. On Folia, verify an enabled-world unload or unregister remains deferred and retries until autosave finishes. Record the forced-disable boundary separately: once Folia has disabled the plugin it rejects new region tasks, so a new final cross-region capture cannot be guaranteed. Close Studio or wait for `status` to report no pending autosave before reload or server shutdown.

5. Change Hallway's capacity to 16×3×3 from **Workcell Settings** or `/iris jigsaw bounds 16 3 3`. Gate: only structure capacity metadata changes; every Hallway variant keeps its object bytes and exact dimensions. A capacity shrink below any assigned variant is rejected atomically. Resize one loaded Hallway variant to 16×3×3 from **Variant Size** or `/iris jigsaw variant resize 16 3 3`; confirm only that object changes and reloads in place, its canonical connector payloads and sockets move to `(8,1,0)` and `(8,1,2)`, and sibling Hallway variants keep their prior dimensions and bytes. Resize a second Hallway variant to 3×3×3 after raising capacity if required, proving variants in one cell can differ. Before one shrink, persist a block outside the target; confirm the resize is rejected without an owned-file change, then remove it and retry. Also confirm a shared or read-only object is rejected. **Resize This Variant to Capacity** affects only the selected variant.

6. Open the loaded variant's details. Change one exact pool entry's weight and chance, use **Duplicate This Cell's Variant**, toggle rotation, and use the two-click unlink confirmation. Gate: only that entry changes; chance moves in five-percentage-point steps; the duplicate has a new key, copied label, and independent object; and every stale callback is rejected by request ID.

7. Use **Duplicate All Enabled Cells as Family** to create `variant-2`. Gate: one owned clone is created from the active variant of every enabled workcell, matching pool memberships, labels, and independent object dimensions are duplicated, every clone is atomically loaded and assigned to `variant-2`, and a failure leaves both files and all live bindings unchanged. Seed `1337` selects one complete weighted theme without mixing families. Change a loaded piece's depth/count/terminal rules, theme membership, theme weight, and mandatory caps. Invalid combinations must fail atomically and appear in the automatic evaluation without a manual validation command.

8. Disable Tee. Gate: a red stained-glass display fills its full bounds, the workcell remains editable, and Tee pieces disappear from assembly. Unload and reload the display's origin chunk and confirm the full-volume red display returns once without a stale duplicate. The permanent seed-`1337` preview on the negative-X side updates in place and is protected from players, fluids, pistons, explosions, growth, fire, entities, and redstone; the GUI, scoreboard, or `status` shows its selected theme and piece count. Reach it through both **Go to Preview** and `/iris jigsaw preview goto`. Re-enable Tee and confirm participation returns.

9. Open **Toolbox** and take schema-`2` named sticks, including selection, capacity, per-variant size, variant/workcell rename, duplicate-one/family, preview, membership, rules/themes, caps, variant deletion, and project deletion. Gate: right-click uses the exact bound context, rename sticks open an anvil and sneak-right-click resets the label, other context sticks open the matching GUI, destructive tools require a second use within 10 seconds, and schema-`1` or replaced-Studio sticks are rejected. Confirm the active variant uses a jigsaw icon, a valid evaluation uses emerald, minimum placements does not use dye, and lime dye appears only as an explicitly labeled theme-membership boolean.

10. Have a second player try the chest, triple-sneak controls, a direct block edit, `/setblock`, `/fill`, `/execute run setblock`, `/function`, `/data merge block`, `/item replace block`, and an arbitrary plugin mutation command. Also try to break/move/explode the chest and preview. Gate: non-owner mutations and commands outside the strict informational/communication allowlist are cancelled throughout the Studio world; protected content remains intact; owner edits still work.

11. Test ownership onboarding with prepared fixtures outside an active Studio:

   ```text
   /iris jigsaw adopt inspect overworld smoke/unowned target=auto strategy=auto
   /iris jigsaw adopt apply <reported-plan-uuid>
   ```

   Gates: an exclusive closure reports `IN_PLACE`, apply leaves every resource byte unchanged while atomically adding ownership/receipt, and the target opens editable. A shared closure reports `CLONE_REQUIRED`; `target=auto` chooses a free `-studio` key and rewrites its internal references without changing the source. Mutate a pinned source after inspect and confirm apply reports stale with no write. An auto-ingested `MANAGED_DATAPACK` fixture must block in-place and succeed only as a private named clone; removing/refreshing the source must not remove that editable clone.

12. Convert one live registered jigsaw into an unused target:

   ```text
   /iris jigsaw convert overworld minecraft:village_plains target=smoke/converted-village seed=1337
   ```

   Gate: the command reports piece/pool counts and any fidelity-warning count, writes an owned add-only graph with source provenance, and opens it in compact workcells. A non-jigsaw registered key and occupied target both fail without overwrite. Inspect blocks, connectors, unsupported/native-only losses, and automatic display rotation before treating conversion as faithful.

13. Test variant and project deletion. Create a second variant, load it, and delete the now-inactive first variant through the two-click GUI; the last or currently loaded variant must remain protected. Add an external JSON placement/reference to the project and confirm project deletion is blocked with its owner path/location. Remove the reference, wait for autosave, then use `/iris jigsaw delete confirm=true`; gate: Studio closes and the hash-pinned complete owned closure plus manifest are removed. If removal fails after close, files remain recoverable.

14. On Folia, create a spatial project with one active workcell crossing several chunks/regions:

   ```text
   /iris jigsaw create overworld smoke/jigsaw-folia mode=spatial compatibility=iris width=32 height=24 depth=32 seed=1337
   /iris jigsaw bounds 48 24 32
   /iris jigsaw close
   /iris jigsaw open overworld smoke/jigsaw-folia seed=1337
   /iris jigsaw goto workcell/spatial
   /iris jigsaw piece expand
   ```

   Gate: spatial capacity and its author-facing workcell label persist while the live layout regenerates and rehydrates without reopen. `piece expand` resizes only the active object to 48×24×32; another smaller variant keeps its dimensions. Change blocks in separated chunks of the expanded workcell without manually flushing autosave. With all intersections loaded, automatic capture schedules each intersection on its owning region and commits once after complete validation. Repeat with one intersection unloaded and confirm no owned file changes. Automated coordinator tests are not live Folia proof.

15. Reopen a retained Iris project, attach it to a dimension/region/biome placement with a unique `placementId`, validate the pack, and generate new chunks. Gate natural occurrence separately from Studio preview. For cave work, first generate the mantle, then verify no-anchor chunks skip and actual anchors align as described in `15 - Caves & Carving.md`.

### Jigsaw Studio: strict vanilla export

Create a separate portable project. Its six default planar pieces contain no Iris theme or terminal-rule metadata; keep chance, piece-rule, required-cap, channel, edit, loot, custom-block, and tile metadata absent. Wait for autosave and automatic evaluation to settle:

```text
/iris jigsaw create overworld smoke/jigsaw-portable mode=planar compatibility=vanilla width=16 height=16 depth=16 seed=1337
/iris jigsaw export namespace=smoke output=smoke-jigsaw format=zip replace=false
/iris jigsaw close
```

1. Confirm `<Iris data>/packs/exports/smoke-jigsaw.zip` was published and contains `pack.mcmeta`, biome tag, processor list, template pools, compressed structure templates, jigsaw structure, and structure set.
2. Run the same export with `output=../escape` and confirm it rejects the traversal name without creating an escape artifact or any new output.
3. Stop a disposable unmodded Minecraft 26.2 world, put the zip in its `datapacks/` directory, and restart it. Do not use `/reload` for this gate: it can list the pack as enabled without rebuilding the running world's worldgen registries.
4. Confirm it is enabled without datapack/data errors, then run `/locate structure smoke:smoke/jigsaw` and generate fresh chunks around the result.
5. Gate: the vanilla server loads the pack, locate resolves the exported key, and a natural assembled instance appears. Iris graph tests, successful plugin boot, and NBT decode alone are not vanilla runtime proof.

This is a required manual runtime gate, not a result established by the current automated checks. Record it as untested until the disposable vanilla server or client completes all five steps.

Strict export must reject coherent themes, membership chance, non-default piece rules, required caps, non-portable channels, fixed rotation, structure edits/loot, tile payloads/block entities, custom blocks, retained marker blocks, invalid/duplicate connectors, weights outside `1..150`, depth above `20`, or radius above `8`. Disabled planar archetypes are omitted. Full authoring and recovery details: `21 - Jigsaw Structures.md`.

## I. Offline probe module (no live server)

From the Iris project root (JDK 25). These are CI-oriented gates, not in-game commands.

| Task | Purpose |
|------|---------|
| `./gradlew :probe:run` (ClassloadProbe) | Loads compiled `core` classes without `org.bukkit` on the runtime classpath; fails on purity violations outside the allowlist |
| `./gradlew :probe:deserializationProbe` | Deserializes fixture entity/spawner/loot JSON through real Iris loaders on a Bukkit-free JVM |
| `./gradlew :probe:genProbe -PprobePack=/path/to/packs/overworld` | Builds a real engine for dimension `overworld`, seed `1337`, generates a chunk spiral into buffers |

`genProbe` properties: `probePack` (required usable pack path), `probeRadius` (default `2`), `probeCenterChunkX` / `probeCenterChunkZ` (default `0`). The task clones the pack into a temp directory, runs `PackValidator`, then generates.

Gate: each probe exits 0. Classload and deserialization probes are part of the release verify job when CI is green (`86 - Maintainer - Release Checklist.md`).

## J. Minimal post-upgrade checklist

After replacing the jar/mod only:

1. Boot on the same world data.
2. `/iris pack validate` on production packs.
3. Generate a few new chunks in an existing Iris world.
4. Optional short GoldenHash verify against a stored baseline if the pack and seed are unchanged (`32 - Determinism & Goldenhash.md`).
5. If pregen was mid-job, confirm status/resume or cancel cleanly (`07 - Pregeneration.md`).

Gate: no enable crash, packs still loadable, generation continues.

## K. Failure triage order

1. Confirm Java 25 and correct platform artifact (`01 - Installation & Platforms.md`).
2. Confirm pack validates and dimension key exists (`25 - Pack Management.md`, `05 - Concepts & Pack Layout.md`).
3. Confirm target is an Iris world/engine (`06 - Worlds & Lifecycle.md`).
4. Capture GoldenHash with `threads=1` and `reset-mantle=true`; if mismatch, read the written `.new` / `.diag-…` files (`32 - Determinism & Goldenhash.md`).
5. For throughput or memory issues, tune settings before changing packs (`33 - Performance Tuning.md`).
6. For release candidates, escalate to maintainer gates (`87 - Maintainer - Release Readiness.md`).

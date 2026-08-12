# 31 - Operator Runbooks

Manual verification sequences to run after an install, an upgrade, a pack change, or a release-candidate build. Each runbook is a numbered checklist with the exact command, what you should see, and what to do when you do not see it. Command trees and permissions are in `04 - Commands & Permissions.md`, pregen options in `07 - Pregeneration.md`, platform capability differences in `30 - Platform Differences.md`.

## How to use these

Use a purpose-named disposable world. Before you start, write down the Iris artifact, platform build, Java version, pack hash, and seed — a result without those is not reproducible later.

Run only the sections a local change touched. Run the full platform set for a release candidate.

A passing Gradle test, a clean boot, and a player actually walking through generated chunks tell you different things — don't let one stand in for another. When you're done, delete the worlds and instances you created for the run.

## Fixed inputs for parity work

Use the same values whenever you compare platforms or runs:

| Input | Value | Why |
|-------|-------|-----|
| Pack | Shipping default `overworld`, or a frozen copy | Must be byte-identical on every platform under test |
| Seed | `1337` | World seed on Bukkit, Iris engine seed on modded |
| GoldenHash radius | `22` chunks (`8` for a quick check) | Radius 22 covers `(2×22+1)² = 2,025` chunks |
| GoldenHash threads | `1` strict, `8` for the multi-thread run | `threads=1` is what catches order dependence |
| Pregen radius | `352` blocks centered at `0,0` | Pregen radius is in **blocks**; 352 blocks is 22 chunks, giving the same 2,025-chunk square |

GoldenHash file layout and interpretation: `32 - Determinism & Goldenhash.md`.

## A. Fresh install and first world (Bukkit-family)

1. Drop the CraftBukkit-family jar into `plugins/` on a Java 25 server (Paper, Purpur, Folia, Spigot, Leaf, or Canvas as advertised). See `01 - Installation & Platforms.md`.
2. Start the server once.

   Expect: Iris enables; the managed `overworld` and `underworld` beta packs download when absent; `settings.json` appears in the Iris data directory.

   If Iris does not enable, check Java version and platform artifact before anything else. If the downloads fail, the server has no outbound access — install the packs manually (`25 - Pack Management.md`).

3. Create a world with a fixed seed and go to it:

   ```
   /iris create test-ow type=overworld seed=1337
   /iris tp test-ow
   ```

   Expect: the world is created and loaded as an Iris world, and you land in generated terrain.

4. Walk or fly a few hundred blocks.

   Expect: non-empty terrain, surface biomes, no repeating stack traces on first chunks.

   If chunks come back empty or void, stop and check pack validation (section C) before anything else.

5. **Expected:** the world is an Iris world, chunks generate, and the console shows no fatal engine init failure.

## A.1 Exact vanilla-slot replacement (Paper-family)

Prerequisites: a disposable server whose configured level name is `world`, a valid `NETHER` Iris pack, and a generated vanilla Nether containing a unique marker chunk. Before staging, hash the existing Nether `region`, `entities`, and `poi` files and keep copies of `data/paper/metadata.dat`, `data/paper/level_overrides.dat`, and `data/minecraft/world_gen_settings.dat`.

1. Stage the replacement:

   ```
   /iris create world_nether type=<nether-pack> seed=1337 overwrite=true
   ```

   Expect: the command reports the replacement is staged. The loaded Nether and its files are unchanged, `bukkit.yml` now names `Iris:<dimension>`, and exactly one pending replacement journal plus one sibling stage exists.

2. Optionally stage the configured main name with a `NORMAL` pack and the End alias with a `THE_END` pack.

   Expect: each distinct slot gets its own transaction, and no live dimension folder is moved.

3. Restart normally.

   Expect: Iris publishes before aggregate-datapack compilation and before Bukkit world loading. `minecraft:the_nether` loads with the selected Iris dimension, the prior Paper per-world metadata files, and the authoritative shared level seed. Its frozen `iris/pack` exists, no old `region`, `entities`, or `poi` file was merged into the target, and the marker chunk is gone.

4. Watch the `WorldLoad` boundary.

   Expect: the journal advances to committed cleanup only after identity, environment, seed, dimension, and pack-fingerprint verification all pass. Cleanup then removes the retained sibling backup and journal asynchronously, without stalling the world thread.

5. Restart again and generate fresh Nether chunks.

   Expect: the vanilla identity and the Iris generator both persist, ordinary Nether portals still target `minecraft:the_nether`, and no pending stage, backup, or journal reappears.

6. Repeat once with a deliberately corrupted staged pack or a conflicting `bukkit.yml` value before restart.

   Expect: early Paper bootstrap aborts before registry and world loading, preserves the recoverable artifacts, and never guesses a target. For a failure after publication, Iris journals a rollback, requests the controlled restart, and restores the retained original directory and prior configuration before datapack compilation or world loading.

   If Iris silently proceeds past a corrupted stage, that is a blocking defect — capture the journal and stage directories before touching anything.

## B. Fresh install and first world (Fabric / Forge / NeoForge)

1. Install the matching mod jar into `mods/`. Fabric needs Loader at or above the declared floor; Forge and NeoForge need theirs. See `01 - Installation & Platforms.md` and `30 - Platform Differences.md`.
2. Start the dedicated server, or singleplayer if you are also testing the client mod.

   Expect: Iris boots, both managed beta packs install, and datapack and biome registration complete.

3. Create a world (arguments are positional here, not keyed):

   ```
   /iris create test-ow overworld 1337
   ```

4. Enter the dimension.

   Expect: non-empty generation, and custom-biome registration where the pack defines custom biomes.

5. **Expected:** same generation health as section A. Capability gaps are acceptable only where `30 - Platform Differences.md` documents them.

## C. Pack validation

1. Validate everything installed. On Bukkit the pack argument is required, so pass it with an empty value to cover every pack; on modded, omitting the pack validates all of them:

   ```
   /iris pack validate pack=      (Bukkit)
   /iris pack validate            (modded)
   ```

   Single pack: `/iris pack validate pack=<pack>` on Bukkit, `/iris pack validate <pack>` on modded.

2. Read the output and separate blocking errors from warnings.

   Expect: zero blocking errors for a pack you intend to ship. Warnings are advisory.

   If there are blocking errors, fix them before treating the pack as production-ready — create and load will refuse the pack later anyway.

3. Replay the startup result:

   ```
   /iris pack status
   ```

   Expect: the result published at startup, including a persisted result reused for unchanged content.

4. Restart without changing packs or registry context.

   Expect: the console logs `External datapacks match the persisted startup validation` and does **not** log another external-datapack `Validating` or `Ingesting` pass. Pack validation reuses its persisted result instead of re-parsing, player admission opens only after both phases are ready, and the target stays loadable.

5. Change one byte in a pack and restart.

   Expect: the content fingerprint invalidates the reuse and validation runs in full again.

   Restore the pack before continuing. Cleanup and restore flows are separate and opt-in (`25 - Pack Management.md`).

## D. Bukkit datapack dimension scope

Prerequisites: a disposable server with one managed datapack source, a vanilla world, one Iris dimension that declares that source, and one Iris dimension that does not. Install or ingest the datapack, restart so its registries are live, then create both Iris worlds so scope is applied before their spawn chunks load.

1. In each of the three worlds, locate a managed structure. Get a valid key from `/iris structure list <declaring-dimension>`:

   ```
   /locate structure <managed-structure-key>
   ```

2. Generate new chunks in all three worlds. Checking existing chunks proves nothing — a scope change does not rewrite them.

3. **Expected:** locate and natural generation both keep the managed structure in the declaring Iris world, and neither the vanilla world nor the non-declaring Iris world locates or generates it.

4. Restart with the datapack still installed and repeat locate plus new-chunk generation.

   Expect: identical per-world results and no ownership or structure-state failure during world initialization.

5. Break one required dimension or native-structure reference, restart, and try to create or load that pack.

   Expect: validation blocks before admission completes, create and load report the failure before datapack preparation, and no dimension folder, pack snapshot, `bukkit.yml` registration, registry entry, or loaded world is left behind.

   Restore and revalidate the pack before running the next section.

## E. Pregeneration control

Pregen radius is always in **blocks**. Use a disposable world.

**Bukkit** (radius positional, everything else keyed; `gui` defaults to `true`):

```
/iris pregen start 352 world=test-ow center=0,0 gui=false
/iris pregen status
/iris pregen pause
/iris pregen status
/iris pregen pause
/iris pregen stop
```

Strict one-chunk-at-a-time (Paper-compatible servers only):

```
/iris pregen start 352 world=test-ow center=0,0 gui=false serial=true
```

**Modded** (positional radius, then optional dimension, then literal flags in any order — `at <x> <z>`, `gui`, `sync`, `nocache`):

```
/iris pregen start 352 irisworldgen:test-ow at 0 0 sync
/iris pregen status
```

Expect, in order:

1. Start reports the correct world, center, and size.
2. `status` shows generated/total, percent, chunks per second, ETA, elapsed, method, and a failed count when any chunk failed.
3. The first `pause` freezes progress; the second resumes it (`resume` is an alias for the same toggle).
4. `stop` cancels and finishes active work without claiming completion when chunks remain.
5. For a release candidate, also run a full serial or sync 2,025-chunk run (radius 352 at 0,0) and confirm zero failed chunks.

If `serial=true` is rejected, the server is not Paper-compatible for strict serial pregen — that is expected, not a bug. If chunks fail, capture the console before retrying; failures are the finding, not the retry.

Client HUD: with the Iris client mod, pregen progress arrives on channel `irisworldgen:main`. Vanilla clients get the boss bar and console only (`29 - Client HUD & Protocol.md`).

## F. GoldenHash determinism

Run on a **disposable** world. The scan generates into buffers and never writes world blocks, but it deletes the world's mantle by default.

**Bukkit** (always `AUTO` — captures when the file is missing, verifies when it exists):

```
/iris developer goldenhash world=test-ow radius=22 threads=1
```

Optional: `center-x=0 center-z=0 reset-mantle=true deep=false`. Defaults are radius `8`, threads `8`, center `0,0`, reset-mantle `true`, deep `false`.

**Modded** (center fixed at chunk 0,0, mantle always reset):

```
/iris goldenhash 22 1 capture
/iris goldenhash 22 1 verify
```

Alias `/iris gold …`. With no arguments: radius `8`, threads `8`, mode `AUTO`.

Expect:

1. The capture run writes a `.hashes` file in the platform golden directory and prints its path plus a short combined hash.
2. A second run with the same pack, seed, radius, and center reports **MATCH** with the same hash.
3. The same pack, seed, radius, and center produce the same combined hash on Bukkit, Fabric, Forge, and NeoForge for identical artifacts and pack bytes.

A Minecraft-version warning on cross-platform comparisons is expected and harmless — Bukkit and mod loaders report the version string differently. A seed or dimension mismatch is a hard failure and stops the comparison.

On MISMATCH, read the `.new` file and the first `.diag-*` file before changing anything. Triage table: `32 - Determinism & Goldenhash.md`.

## G. Restart and existing worlds

1. After some pregen or free exploration, stop the server cleanly.
2. Start again without deleting world data.
3. Load the same Iris world and generate new chunks outside the pregenerated area.
4. **Expected:** the world loads, new chunks generate, no blank-chunk regression appears at the restart boundary, and a resumed pregen job behaves as documented (`07 - Pregeneration.md`).

## H. Studio (authoring path)

### General pack Studio

```
/iris studio open overworld seed=1337
```

While moving through fresh chunks so Moonrise has active generation stages, edit a pack file on disk — or through the VSCode workspace opened by `/iris studio vscode dimension=overworld` on Bukkit. Then close while fresh chunks are still queued:

```
/iris studio close
```

Expect: the studio world opens; hotload applies without a server restart; already-admitted stages finish before the transition and later stages resume after it; close discards the transient studio world cleanly.

**Expected:** no generation-session rejection, no partial chunk-stage failure, no chunk-system crash. A failed hotload must fail closed without poisoning the live engine for non-studio worlds. Studio details: `10 - Studio & VSCode Schemas.md`.

### Jigsaw Studio: planar authoring and atomicity (Bukkit)

Use a disposable pack and structure key, and the owning builder account. Bukkit has one global Studio project and world and one owning Jigsaw session. Non-owner block edits and recognized mutating commands must be denied throughout this world. This command tree is not registered on Fabric, Forge, or NeoForge.

1. Create a project with no optional arguments so the defaults are exercised:

   ```text
   /iris jigsaw create overworld test/jigsaw
   /iris jigsaw status
   ```

   Expected: the add-only transaction owns one structure, three pools, six pieces, six objects, and one manifest before Studio opens. On Paper, native-structure scope must report success and reject any active-generator ownership or asynchronous game-rule event error. The player enters creative above Blank. `status` reports `PLANAR_JIGSAW`, `IRIS_EXTENDED`, six workcells, 15×15×15 for the selected workcell, six variants, no pending autosave, and the seed-`1337` evaluation. The key tab-completes for `open`, `edit`, and `reopen`. The GUI and owned resources show one loaded variant per archetype, theme `variant-1`, terminal End, and mandatory caps off.

2. Inspect the Blank, End Cap, Hallway, L Junction, T Junction, and Cross Junction layout. Floors are light-gray wool, topology paths are red wool, and canonical endpoints are sea lanterns. There are no orientation, permutation, piece, or derived-rotation cells. Toggle player-local particles:

   ```text
   /iris jigsaw goto workcell/blank
   /iris jigsaw goto workcell/straight
   /iris jigsaw goto workcell/cross
   /iris jigsaw particles false
   /iris jigsaw particles true
   ```

   Expected: every cell has one physical white-concrete edge cage, no workcell-bound display entity exists, and focused plus nearby particle trails outline the editable bounds inside those cages. Focused connectors draw 1.75-block direction lines while particles are on. The Iris scoreboard replaces the general Studio context with Structure, Workcell, Variant, State, and `Triple-sneak for controls`, with no orientation or mask fields. All six untouched cells report **Autosaved** initially. Enter End Cap, triple-sneak, and confirm the menu selects End Cap rather than the previously selected cell.

3. Open the same six-row controls three ways: right-click the protected chest, run `/iris jigsaw menu`, and sneak three times within 1.5 seconds.

   Expected: each path opens without an `InventoryView` linkage error on the target Paper-family runtime. Select Hallway and click **New Blank Variant**; wait for its atomic graph result and load, then reopen the controls. Rename the loaded variant and the Hallway workcell through their anvil inputs and confirm the labels round-trip while the piece key, `straight` stable ID, and solver role stay unchanged. Load End Cap and use **Duplicate This Cell's Variant**, then load Cross Junction and duplicate it too.

   Expected: the new key follows `test/jigsaw/variants/straight/variant-<n>` and loads into Hallway with the source piece's complete metadata and exact pool entries but an empty same-sized object. At the default 15×15×15 its two real markers sit at `(7,7,0)` and `(7,7,14)`, face north and south with top `UP_POSITIVE_Y`, show pool `iris:test/jigsaw/pieces`, use name and target `iris:planar`, `ALIGNED`, `minecraft:structure_void`, and signed priorities `0`. Mojang's UI is usable after hydration. Break one marker and click **Reset Connector Blocks** before autosave: both saved markers return while another edited block stays as you left it. Each duplicate copies the active object's bytes, display label, and complete piece metadata. The End Cap duplicate has exact matching entries in both `test/jigsaw/pieces` and `test/jigsaw/caps`; the Cross Junction duplicate has them in both `test/jigsaw/start` and `test/jigsaw/pieces`. An empty or unassigned workcell refuses both GUI actions and points at `/iris jigsaw piece create <poolKey> <pieceKey>` instead of picking a fallback pool.

4. Change one permanent block, one marker field, and one chest inventory inside Hallway. Keep the permanent block and the chest inside the later 16×3×3 target, for example Y/Z offsets `1,1`. After changing a marker field in Mojang's UI, immediately run `/iris jigsaw status`; change it again and immediately run `/iris jigsaw close`. Trigger an internal inventory transfer or hopper pickup, and at least one furnace, brewing-stand, dispenser, or crafter update inside the workcell. Do not flush autosave. Wait at least 40 ticks after the last update, then:

   ```text
   /iris jigsaw status
   ```

   Expected: the status command and the close attempt each request a final owning-region marker snapshot, and close waits behind marker finalization and autosave rather than losing the last UI change. State moves dirty → saving → clean on its own; inventory and machine changes also mark it dirty; one complete multi-resource commit occurs; one owner-local bell sounds; no partial resource appears.

   Then make six distinct saved block edits and click **Undo Last Autosave** five times. Expected: each prior block state and manifest hash returns in reverse order, the sixth-oldest state is no longer available, one `.iris/jigsaw-history/key-<sha256>.json` file held the stack, and no transaction debris remains.

   Then make another edit while capture is pending and immediately click **Duplicate This Cell's Variant**. Expected: Iris expedites the autosave and performs that one duplicate exactly once, with no wait-and-retry instruction. Repeat with dirty edits in several enabled cells and **Duplicate All Enabled Cells as Family**. Invoke **Flush Autosave Now** while capture cannot start: the same ticket stays pending, retries, and eventually becomes clean. Close, reopen, load the variant, and confirm blocks, marker NBT, inventory, explicit-air final state where used, and `structure_void` absence all round-trip. **Flush Autosave Now** and `/iris jigsaw save` are conveniences, not requirements.

   On Paper, repeat one dirty edit immediately before plugin disable and confirm the synchronous final drain persists it. On Folia, confirm an enabled-world unload or unregister stays deferred and retries until autosave finishes. Record the forced-disable boundary separately: once Folia has disabled the plugin it rejects new region tasks, so a new final cross-region capture cannot be guaranteed. Close Studio, or wait for `status` to report no pending autosave, before any reload or shutdown.

5. Stage Hallway's capacity as 16×3×3 from **Workcell Settings**, confirm the menu stays open during every axis click, then click **Apply Cell Size** once. Also test the command form:

   ```text
   /iris jigsaw bounds 16 3 3
   ```

   Expected: only structure capacity metadata changes, one live relayout runs after Apply, and every Hallway variant keeps its object bytes and exact dimensions. A capacity shrink below any assigned variant is rejected atomically.

   Then resize one loaded Hallway variant to 16×3×3 from **Variant Size** or `/iris jigsaw variant resize 16 3 3`. Expected: only that object changes and it reloads in place, its canonical connector payloads and sockets move to `(8,1,0)` and `(8,1,2)`, and sibling Hallway variants keep their prior dimensions and bytes. Resize a second Hallway variant to 3×3×3 (raise capacity first if required) to prove variants in one cell can differ. Before one shrink, persist a block outside the target: the resize must be rejected without an owned-file change; remove the block and retry. A shared or read-only object must also be rejected. **Resize This Variant to Capacity** affects only the selected variant.

6. Open the loaded variant's details. Change one exact pool entry's weight and chance, use **Duplicate This Cell's Variant**, toggle rotation, and use the two-click unlink confirmation.

   Expected: only that entry changes, chance moves in five-percentage-point steps, the duplicate has a new key with a copied label and an independent object, and every stale callback is rejected by request ID.

7. Use **Duplicate All Enabled Cells as Family** to create `variant-2`.

   Expected: one owned clone is created from the active variant of every enabled workcell; pool memberships, labels, and independent object dimensions are duplicated; every clone is atomically loaded and assigned to `variant-2`; a failure leaves both files and all live bindings unchanged. Seed `1337` selects one complete weighted theme without mixing families. Change a loaded piece's depth, count, and terminal rules, theme membership, theme weight, and mandatory caps: invalid combinations must fail atomically and surface in the automatic evaluation with no manual validation command.

8. Disable Tee.

   Expected: its white-concrete cage remains, the GUI and scoreboard report Disabled, the workcell stays editable, and Tee pieces disappear from assembly. The permanent seed-`1337` preview on the negative-X side updates in place and is protected from players, fluids, pistons, explosions, growth, fire, entities, and redstone. The GUI, scoreboard, or `status` shows its selected theme and piece count. Reach it through both **Go to Preview** and `/iris jigsaw preview goto`. Re-enable Tee and confirm participation returns.

9. Open **Toolbox** and take the schema-`2` named sticks: selection, capacity, per-variant size, variant and workcell rename, duplicate-one and duplicate-family, preview, membership, rules and themes, caps, variant deletion, and project deletion.

   Expected: right-click uses the exact bound context; rename sticks open an anvil and sneak-right-click resets the label; other context sticks open the matching GUI; destructive tools require a second use within 10 seconds; schema-`1` and replaced-Studio sticks are rejected. The active variant uses a jigsaw icon, a valid evaluation uses emerald, minimum placements does not use dye, and lime dye appears only as an explicitly labeled theme-membership boolean.

10. Have a second player try the chest, triple-sneak controls, a direct block edit, `/setblock`, `/fill`, `/execute run setblock`, `/function`, `/data merge block`, `/item replace block`, and an arbitrary plugin mutation command. Have them try to break, move, or explode the chest and the preview.

    Expected: non-owner mutations and commands outside the strict informational and communication allowlist are cancelled throughout the Studio world, protected content stays intact, and owner edits still work.

11. Test ownership onboarding with prepared fixtures outside an active Studio:

    ```text
    /iris jigsaw adopt inspect overworld test/unowned target=auto strategy=auto
    /iris jigsaw adopt apply <reported-plan-uuid>
    ```

    Expected: an exclusive closure reports `IN_PLACE`, apply leaves every resource byte unchanged while atomically adding ownership and a receipt, and the target opens editable. A shared closure reports `CLONE_REQUIRED`, and `target=auto` picks a free `-studio` key and rewrites its internal references without touching the source. Mutate a pinned source after inspect: apply must report stale and write nothing. An auto-ingested `MANAGED_DATAPACK` fixture must block in-place and succeed only as a private named clone, and removing or refreshing the source must not remove that editable clone.

12. Convert one live registered jigsaw into an unused target:

    ```text
    /iris jigsaw convert overworld minecraft:village_plains target=test/converted-village seed=1337
    ```

    Expected: the command reports piece and pool counts plus any fidelity-warning count, writes an owned add-only graph with source provenance, and opens it in compact workcells. A non-jigsaw registered key and an occupied target both fail without overwriting. Inspect blocks, connectors, unsupported or native-only losses, and automatic display rotation before calling the conversion faithful.

13. Test variant and project deletion. Create a second variant, load it, and delete the now-inactive first variant through the two-click GUI; the last or currently loaded variant must stay protected. Add an external JSON placement or reference to the project and confirm project deletion is blocked with its owner path and location. Remove the reference, wait for autosave, then:

    ```text
    /iris jigsaw delete confirm=true
    ```

    Expected: Studio closes and the hash-pinned complete owned closure plus manifest are removed. If removal fails after close, the files remain recoverable.

14. On Folia, create a spatial project with one active workcell crossing several chunks and regions:

    ```text
    /iris jigsaw create overworld test/jigsaw-folia mode=spatial compatibility=iris width=32 height=24 depth=32 seed=1337
    /iris jigsaw bounds 48 24 32
    /iris jigsaw close
    /iris jigsaw open overworld test/jigsaw-folia seed=1337
    /iris jigsaw goto workcell/spatial
    /iris jigsaw piece expand
    ```

    Expected: spatial capacity and its author-facing workcell label persist while the live layout regenerates and rehydrates without a reopen. `piece expand` resizes only the active object to 48×24×32 while a smaller sibling variant keeps its dimensions. Change blocks in separated chunks of the expanded workcell without manually flushing autosave: with all intersections loaded, automatic capture schedules each intersection on its owning region and commits once after complete validation. Repeat with one intersection unloaded and confirm no owned file changes. The unit tests do not cover this; it has to be checked on a live Folia server.

15. Reopen a retained Iris project, attach it to a dimension, region, or biome placement with a unique `placementId`, validate the pack, and generate new chunks. Gate natural occurrence separately from Studio preview. For cave work, generate the mantle first, then confirm no-anchor chunks skip and actual anchors align as described in `15 - Caves & Carving.md`.

### Jigsaw Studio: strict vanilla export

Create a separate portable project. Its six default planar pieces must carry no Iris theme or terminal-rule metadata, and no chance, piece-rule, required-cap, channel, edit, loot, custom-block, or tile metadata. Wait for autosave and the automatic evaluation to settle.

```text
/iris jigsaw create overworld test/jigsaw-portable mode=planar compatibility=vanilla width=16 height=16 depth=16 seed=1337
/iris jigsaw export namespace=test output=test-jigsaw format=zip replace=false
/iris jigsaw close
```

1. Confirm `<Iris data>/packs/exports/test-jigsaw.zip` was published and contains `pack.mcmeta`, a biome tag, a processor list, template pools, compressed structure templates, the jigsaw structure, and the structure set.
2. Run the same export with `output=../escape`.

   Expect: the traversal name is rejected with no escape artifact and no new output created.

3. Stop a disposable **unmodded** Minecraft 26.2 world, put the zip in its `datapacks/` directory, and restart it. Do not use `/reload` for this gate — it can list the pack as enabled without rebuilding the running world's worldgen registries.
4. Confirm the pack is enabled with no datapack or data errors, then:

   ```text
   /locate structure test:test/jigsaw
   ```

   Generate fresh chunks around the result.

5. **Expected:** the vanilla server loads the pack, locate resolves the exported key, and a natural assembled instance appears. Passing graph tests, a clean plugin boot, and a clean NBT decode do not tell you this — only a real vanilla server does.

This is a required manual runtime gate. Record it as untested until a disposable vanilla server or client completes all five steps.

Strict export must reject coherent themes, membership chance, non-default piece rules, required caps, non-portable channels, fixed rotation, structure edits or loot, tile payloads and block entities, custom blocks, retained marker blocks, invalid or duplicate connectors, weights outside `1..150`, depth above `20`, and radius above `8`. Disabled planar archetypes are omitted from the export. Full authoring and recovery detail: `21 - Jigsaw Structures.md`.

## I. Offline probe module (no live server)

Run from the Iris project root on JDK 25. These are CI gates, not in-game commands.

| Task | What it proves |
|------|----------------|
| `./gradlew :probe:run` | Loads compiled `core` classes with no `org.bukkit` on the runtime classpath; fails on purity violations outside the allowlist |
| `./gradlew :probe:deserializationProbe` | Deserializes fixture entity, spawner, and loot JSON through the real Iris loaders on a Bukkit-free JVM |
| `./gradlew :probe:genProbe -PprobePack=/path/to/packs/overworld` | Builds a real engine for dimension `overworld` at seed `1337` and generates a chunk spiral into buffers |

`genProbe` properties: `probePack` (the pack path — the built-in default is a developer-local path, so always pass your own), `probeRadius` (default `2`), and `probeCenterChunkX` / `probeCenterChunkZ` (default `0`). The task copies the pack to a temp directory, runs `PackValidator`, then generates and prints per-chunk hashes.

**Expected:** each probe exits 0. The classload and deserialization probes are part of the release verify job when CI is green (`86 - Maintainer - Release Checklist.md`).

## J. Minimal post-upgrade checklist

After replacing only the jar or mod:

1. Boot on the same world data.

   Expect: no enable crash.

2. Run `/iris pack validate pack=<pack>` (Bukkit) or `/iris pack validate <pack>` (modded) against production packs.

   Expect: no new blocking errors. New errors after an upgrade mean a schema change — check `03 - Configuration.md` and the relevant content doc before editing packs.

3. Generate a few new chunks in an existing Iris world.
4. Optionally run a short GoldenHash verify against a stored baseline, if the pack and seed are unchanged (`32 - Determinism & Goldenhash.md`).
5. If a pregen job was mid-run, confirm it reports status and resumes, or cancel it cleanly (`07 - Pregeneration.md`).

**Expected:** no enable crash, packs still loadable, generation continues.

## K. Failure triage order

Work top to bottom. Most reported "Iris broke" cases resolve in the first three.

1. Confirm Java 25 and the correct platform artifact (`01 - Installation & Platforms.md`).
2. Confirm the pack validates and the dimension key exists (`25 - Pack Management.md`, `05 - Concepts & Pack Layout.md`).
3. Confirm the target really is an Iris world with a live engine (`06 - Worlds & Lifecycle.md`).
4. Capture GoldenHash with `threads=1` and `reset-mantle=true`. On mismatch, read the written `.new` and `.diag-…` files (`32 - Determinism & Goldenhash.md`).
5. For throughput or memory problems, tune settings before changing packs (`33 - Performance Tuning.md`).
6. For a release candidate, escalate to the maintainer gates (`87 - Maintainer - Release Readiness.md`).

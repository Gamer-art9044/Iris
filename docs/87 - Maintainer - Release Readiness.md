# 87 - Maintainer — Release Readiness

**Internal living tracker.** Engineering checklist for preparing Iris for a public release on Bukkit-family servers, Fabric, Forge, and NeoForge. Complete this checklist before running `86 - Maintainer - Release Checklist.md`. Checkbox state and historical evidence below are maintained as work proceeds; they are not a frozen product manual for operators.

The goal is to correct confirmed defects without silently changing valid pack output, public behavior, or platform parity. A behavior change is acceptable when it fixes a documented defect, is covered by a regression test, and is recorded in `MasterChangelog.MD`.

The current runtime pass prioritizes isolated world creation, deterministic generation, pregeneration, and profiling. Hotload, reload, and shutdown refinement remains in the later lifecycle gates. Automated release builds, tagged bundles, and publishing infrastructure are deferred; public-beta work uses manually built artifacts and focuses on plugin/mod correctness and stability.

Cross-links: GoldenHash (`32 - Determinism & Goldenhash.md`), operator smokes (`31 - Operator Runbooks & Smoke Tests.md`), performance knobs (`33 - Performance Tuning.md`), MC bump (`85 - Maintainer - MC Version Bump.md`).

## How to maintain this tracker

Work from the first incomplete blocking section. For each checked item, preserve the exact commit, platform artifact, input pack/seed, command or workload, and result outside this document; summarize only stable conclusions here. When a fix supersedes an earlier note, rewrite the note instead of stacking contradictory history.

Automated tests, server startup, real-player gameplay, profiler captures, and publishing are distinct gates. Mark only the gate actually observed, and leave client-operated or cross-server checks open until that evidence exists.

## Completion rules

- [ ] Work through the sections in order. A later section does not override a failed earlier gate.
- [ ] Add a failing regression test or deterministic reproduction before each P0/P1 correctness fix.
- [ ] Run the focused test while developing, then run the full gate for the affected platform.
- [ ] Compare fixed-pack, fixed-seed golden hashes before and after every world-generation change.
- [ ] Treat an unexpected deterministic output change as a release blocker until explained.
- [ ] Keep loader-specific behavior behind the platform boundary; reusable behavior belongs in core or SPI.
- [ ] Do not add compatibility shims, temporary adapters, or swallowed failure paths.
- [ ] Preserve full stack traces for engine, lifecycle, persistence, and operator-critical failures.
- [ ] Update `MasterChangelog.MD` as operator-visible fixes become final; merge superseded entries.
- [ ] Do not publish while any required release gate is failed, pending, or waived without an explicit reason.

## 0. Secure and freeze the release baseline

- [ ] Rotate the GitHub credential that was embedded in the local origin URL.
- [x] Replace the local origin with a credential-free SSH or HTTPS URL.
- [ ] Regenerate the dev-server management secret before enabling the management interface.
- [ ] Select and record the exact release commit, Minecraft version, JDK, and loader versions.
- [x] Pin VolmLib to an immutable release/tag/commit rather than `master-SNAPSHOT`.
- [x] Ensure `useLocalVolmLib` and `volmLibCoordinate` propagate into every nested adapter build.
- [x] Make the manual release build disable local VolmLib substitution by default.
- [x] Capture a baseline build and test record, running these as separate invocations:
  - [x] `./gradlew :core:check :spi:build :probe:deserializationProbe -PuseLocalVolmLib=false`
  - [x] `./gradlew :adapters:bukkit:plugin:test --rerun-tasks -PuseLocalVolmLib=false`
  - [x] `./build-all.sh`
- [x] Confirm all four baseline jars pass archive integrity checks.
- [x] Capture baseline golden hashes for the same pack, seed, radius, and thread counts on all platforms.
- [ ] Preserve a copy of the baseline performance results described in section 8.

Gate: the source, dependencies, generated terrain baseline, and test evidence are reproducible on a second clean checkout.

## 1. P0 - Make concurrent generation deterministic

- [x] Fix the reproducible order/state-dependent generation defect. Cave painting relabeled shared, loader-cached biome objects as `CAVE`; shallow cave resolution can return the surface biome, so later height and biome decisions changed until `IrisComplex` was rebuilt. Carving now passes explicit cave context to surface and ceiling decorators without mutating the shared biome, preserving cave fluid behavior. Focused isolation and decorator tests plus a 2,025-chunk warm-sequence reproducer pass.
- [x] Scope the confirmed height-bounds cache to its owning `IrisComplex`. The previous static thread-local cache keyed entries only by grid coordinates and interpolator index, allowing another engine or a hotloaded complex to reuse bounds from a different generator set. Focused coverage protects both cross-complex isolation and same-complex cache reuse.
- [x] Scope the cave carver's scratch cache to each `IrisCaveCarver3D`. The warp cache was thread-local but shared by every cave profile and keyed only by sample coordinates, so a second profile on the same worker could reuse warp values from the first profile's noise generator. Focused same-thread coverage now proves distinct carvers retain their own warp samples while preserving per-carver scratch reuse.
- [x] Make cross-chunk cave-wall painting independent of adjacent mantle load order. All 37 block differences in the two focused mantle-reset diagnoses were on local chunk edges (`x=0`, `x=15`, or `z=15`), where `IrisCarveModifier` paints the neighboring cave wall only when that neighbor's mantle chunk contains carving data. The carving component now declares a minimal one-block radius, which schedules the full adjacent chunk pass through the mantle radius conversion; focused coverage protects that contract. Packaged-runtime regeneration now retains the same fixed-seed hash on every available platform.
- [x] Repeat the fixed-seed, reset-mantle GoldenHash sequence from clean startup and after a complete pregen on every available platform. Paper 26.2-56, Fabric Loader 0.19.3, Forge 65.0.3, and NeoForge 26.2.0.8-beta all produced the exact combined hash `783cf831486858129a3730e93c2823b773a40af78442ba3ebe373425eb80fab4`; every strict single-thread 2,025-chunk pregen completed with zero failures and every post-pregen verification matched. Fabric also matched after a controlled restart. Folia 26.2 remains unavailable from its upstream build endpoint.
- [x] Explain and fix the separate 50-chunk Paper-versus-modded biome-hash difference for byte-identical packs. All 131 differing sampled columns were exactly `minecraft:forest` versus `minecraft:plains`: Bukkit's NMS biome source seeded the shared scatter generator from its first coordinate-derived RNG, while modded generation seeded it from the engine biome seed. Every runtime path now passes its owning engine explicitly, shared registrants cache by canonical engine biome seed in a bounded eight-entry cache, and engine-less tooling preserves supplied-seed behavior. Concurrent interleaved-engine coverage protects exact engine ownership, seed isolation, same-seed reuse, and bounded eviction.
- [x] Sample direct Bukkit/modded biome derivatives at each world column, matching Bukkit NMS resolution. The actuator previously reused the chunk origin for every local column, so scatter selection could differ even after both platforms used the same generator seed. Focused actuator coverage verifies all four coordinates in a two-by-two chunk section are distinct world positions.
- [ ] Add a two-thread barrier test that generates two chunks through the same `IrisEngine` concurrently.
- [ ] Assert each generation observes its own chunk coordinates, `ChunkContext`, and generation session.
- [ ] Add a repeated parallel golden-hash test that fails on any cross-run difference.
- [x] Remove the shared mutable `chunkContext`/session state from the engine-wide `IrisContext` path.
- [x] Give each active generation thread or lease an isolated context with explicit lifetime cleanup.
- [ ] Verify maintenance, pregen, Bukkit multicore, and modded generation use the isolated context.
- [ ] Run sequential and parallel generation for the same seed and assert identical hashes.
- [ ] Run the test under high concurrency and with generation-session close/hotload activity.

Gate: repeated concurrent generation is deterministic, context-isolated, and hash-identical to the single-threaded result.

## 2. P0 - Make hotload and shutdown transactional

This section is retained for the later lifecycle refinement pass and is not part of the current public-beta runtime gate. Controlled restarts remain in scope only for existing-world persistence verification.

- [ ] Add a regression test: malformed dimension edit -> failed hotload -> old engine remains usable.
- [ ] Extend the test: corrected edit -> next hotload succeeds without restarting the server.
- [ ] Build candidate dimension, loader, complex, mode, mantle, and world-manager state privately.
- [ ] Validate the complete candidate before changing the live engine.
- [ ] Seal new generation and drain active leases before clearing or replacing live resources.
- [ ] Publish the validated candidate atomically, then activate the next generation session.
- [ ] Keep the previous engine state intact when candidate loading or setup fails.
- [ ] Make `setupEngine()` fail closed and propagate fatal initialization failures.
- [ ] Route `hotloadComplex()` through the same generation-session and transactional rules.
- [ ] Ensure Bukkit exclusive-control permits are released after success, failure, and interruption.
- [ ] Restructure `IrisEngine.close()` so every cleanup stage runs even when lease draining times out.
- [ ] Add startup, failed-hotload recovery, successful-hotload, close, and restart tests.

Gate: no failed hotload can poison the live engine, admit generation into partial state, leak permits, or skip shutdown cleanup.

## 3. P0 - Make `.iris` packaging complete and lossless

- [ ] Define the complete pack resource graph in one shared traversal used by Bukkit and modded Studio.
- [ ] Traverse dimensions, regions, biomes, generators, blocks, objects, entities, spawners, loot, structures, jigsaw pools/pieces, snippets, and every other referenced registrant.
- [ ] Include objects referenced directly by regions, not only objects reached through biomes.
- [x] Include entity resources referenced only by spawner `initialSpawns` entries, alongside normal `spawns` dependencies, with focused export dependency regression coverage.
- [ ] Fail packaging when any required resource is missing or malformed; never report partial success.
- [ ] Stop obfuscation/export from mutating loader-cached biome or placement objects.
- [ ] Give Bukkit and modded packaging the same graph, validation, and error semantics.
- [ ] Implement the modded import/unpack path or explicitly remove the unsupported claim from the UI/docs.
- [ ] Add a minimal pack fixture containing at least one resource from every supported category.
- [ ] Add Bukkit export -> import -> export round-trip tests.
- [ ] Add modded export -> import -> export round-trip tests.
- [ ] Compare normalized JSON, binary objects, dependency counts, and final resource hashes.

Gate: a complete fixture survives round-trip packaging without missing resources, mutated source state, or unexplained byte/content changes.

## 4. P0 - Make Object Studio Folia-safe and atomic

- [ ] Add a test cell that crosses multiple chunks and multiple Folia regions.
- [ ] Capture each chunk/region snapshot only on its owning region thread.
- [ ] Assemble the final `IrisObject` only after all owned snapshots complete successfully.
- [ ] Serialize the object once and reuse the bytes for hashing and persistence.
- [ ] Write to a temporary file, flush/close it, then atomically move it over the destination.
- [ ] Commit the saved hash only after the atomic move succeeds.
- [ ] Leave the prior hash and file untouched after capture, serialization, or write failure.
- [ ] Confirm a failed write is retried on the next save rather than reported as “no changes.”
- [ ] Test empty cells, unchanged cells, partial chunk availability, failure recovery, and concurrent saves.

Gate: Object Studio performs no cross-region Bukkit access, never exposes a partial file, and can always retry a failed save.

## 5. P1 - Make validation and schemas trustworthy

- [x] Split `PackValidator` into a read-only validator and an explicit cleanup command.
- [x] Make unused-resource cleanup preview changes before moving files.
- [x] Prevent restore from overwriting a newer live file without an explicit conflict decision.
- [ ] Discover nested dimensions and resources using the same key rules as `ResourceLoader`.
- [ ] Parse and validate every referenced dependency rather than checking only file existence.
- [ ] Promote malformed referenced JSON to a blocking validation error.
- [x] Validate nested spawner `spawns` and `initialSpawns` entries against same-pack entity resources, blocking malformed containers and entries, missing files, unsafe paths, and malformed referenced JSON.
- [ ] Validate nested unknown properties where the schema disallows them.
- [ ] Preserve namespaces for non-Minecraft enchantments and potion effects in generated schemas.
- [ ] Add deliberate cross-namespace collision fixtures.
- [ ] Make the schema executor lifecycle-owned and restartable after Bukkit reload and integrated-server stop/start.
- [ ] Add validator tests for nested resources, malformed dependencies, cleanup preview, and restore conflicts.
- [ ] Add schema tests for vanilla shorthand and fully namespaced modded values.

Gate: validation is read-only by default, rejects broken dependency graphs, accepts valid nested packs, and schema completion never changes registry identity.

## 6. P1 - Harden modded generation and lifecycle

- [ ] Make generation-session teardown cancel/retry the chunk stage instead of completing an empty chunk.
- [ ] Add a test proving a sealed engine cannot persist a blank chunk.
- [ ] Bound the modded chunk-generation queue and expose queue/backpressure metrics.
- [ ] Complete or cancel every queued future during shutdown; leave no unresolved chunk pipeline.
- [ ] Stop and await maintenance work before closing the engines it can access.
- [ ] Await executor termination and report tasks that exceed the shutdown deadline.
- [ ] Add negative-min-Y tests for sea level, base height, and base-column stone/water/air spans.
- [ ] Verify custom biome cache invalidation after a successful Studio hotload.
- [ ] Make engine-data persistence synchronized and atomic.
- [ ] Make persisted statistics safe under parallel generation.
- [ ] Test dedicated-server start/stop, integrated-server start/stop/start, and world unload/reload.
- [x] Modded entity spawners enforce time/weather gates and apply AI awareness, spawn effects, and raw commands; parity tests cover these paths and `23 - Loot, Entities, Spawners, Markers.md` documents them.

Gate: modded shutdown/hotload cannot save blank chunks, strand futures, race maintenance, or retain stale world state across a second server lifecycle.

## 7. P1 - Harden pregeneration, Folia, and scheduling

- [ ] Remove direct world/chunk/IO fallback when a Folia region scheduling call fails.
- [ ] Retry, defer, or fail the operation without touching region-owned state from the wrong thread.
- [ ] Wrap pregenerator initialization and total-count calculation in the cleanup lifecycle.
- [ ] Ensure every shutdown step runs even when `generator.close()` fails.
- [ ] Clear `regionPending` and related bookkeeping on every load/generation callback failure.
- [ ] Make the pregen cache executor restartable in the same JVM.
- [x] Distinguish cancelled or aborted partial pregeneration from full completion after the generator drains. Cancellation now reports generated, total, failed, and remaining counts without emitting a successful `Pregen finished` summary; focused tests cover cancellation after the first chunk, normal async-close completion, and completion with a failed chunk.
- [ ] Replace modded scheduler `CallerRunsPolicy` with explicit backpressure that cannot move async work onto the server thread.
- [ ] Add a bounded per-tick main-thread drain budget.
- [ ] Replace full delayed-task scans with a due-time queue or equivalent bounded scheduler.
- [ ] Stress cancellation, pause/resume, failure, shutdown, and restart under Paper and Folia.
- [ ] Verify chunk tickets, regions, files, protocol sessions, and executor threads are released afterward.

Gate: pregeneration remains thread-correct and bounded under saturation, cancellation, failure, and restart.

## 8. Performance and regression proof

### Current isolated smoke evidence

This evidence validates packaged-artifact generation and establishes a profiling candidate. It is not the final 5,000-10,000-chunk performance baseline required by this section.

- [x] Fixed inputs: Iris seed `1337`, GoldenHash radius `22`, one hash thread, and a 352-block serial/sync pregeneration radius covering exactly 2,025 chunks.
- [x] Fixed host: Apple M3 Max, 128 GiB RAM, Temurin 25.0.2, 8 GiB instance heap.
- [x] Paper 26.2-56: serial pregen completed 2,025/2,025 with zero failed chunks; cancellation, pause/status/resume, cache resume, restart persistence, and untouched far-chunk generation passed.
- [x] Fabric Loader 0.19.3: sync pregen completed 2,025/2,025 with zero failed chunks and strict `peakInFlight=1 finalLimit=1`; controls, cache resume, restart persistence, and far generation passed.
- [x] Forge 26.2-65.0.3: sync pregen completed 2,025/2,025 with zero failed chunks and strict `peakInFlight=1 finalLimit=1`; controls, cache resume, restart persistence, and untouched far-chunk generation passed.
- [x] NeoForge 26.2.0.8-beta: sync pregen completed 2,025/2,025 with zero failed chunks; pause/cancel, checkpoint resume, fresh generation, and GoldenHash capture completed against the corrected pack.
- [x] GoldenHash parity/determinism: Paper, Fabric, Forge, and NeoForge all captured the exact block+biome hash `783cf831486858129a3730e93c2823b773a40af78442ba3ebe373425eb80fab4` from the manually built candidate artifacts. Every platform then completed a strict single-thread 2,025-chunk pregen with zero failures and retained that hash; Fabric retained it across restart. The historical divergent hashes are superseded by fixes for cross-complex height bounds, cross-profile cave warp, cave-boundary scheduling, engine-owned biome generation, per-column biome sampling, and shared-biome cave relabeling.
- [x] Paper JProfiler CPU, heap, and GC snapshots captured; explicit post-run GC reduced used heap from a sampled peak near 5.94 GiB to approximately 475 MiB, with no retained-heap leak indicated by this run.
- [x] Fabric JProfiler sampled-allocation snapshot captured; profiling overhead made that run unsuitable for throughput comparison.
- [x] Real content-mod fixture: Fabric, Forge, and NeoForge loaded Nerospace beta.7 with Neroland Core 1.4.0 (plus Fabric API 0.154.2 on Fabric), resolved a custom entity/item/block through Iris, generated the exact named structure chest item, performed once-per-chunk initial spawning with zero players, replaced the entity's death loot, generated seven custom ore blocks in the forced test area, and completed strict synchronous 2,025/2,025 pregeneration with zero failed chunks on every loader.

- [ ] Choose one fixed release pack, seed, world height, radius, JVM configuration, and hardware profile.
- [ ] Warm at least 256 chunks before measuring.
- [ ] Run a 5,000-10,000 chunk pregeneration baseline on Paper.
- [ ] Run the same workload on Fabric; repeat on Forge and NeoForge before final release.
- [ ] Capture JProfiler CPU, allocation, GC, retained-object, thread, and executor-queue evidence.
- [ ] Record chunks/second, total duration, p50/p95 chunk time, allocations/chunk, peak heap, and GC pause time.
- [ ] Profile nested chunk prefill parallelism before changing it.
- [ ] Profile modded block/biome buffer allocation before pooling or changing representation.
- [ ] Profile height-bound sampling, custom biome caches, mantle tasks, and pregen region-drain complexity.
- [ ] Benchmark each optimization against the unchanged baseline with the same inputs.
- [ ] Reject or revise changes that regress median throughput by more than 5% or p95 latency/allocations by more than 10%, unless the correctness benefit and accepted tradeoff are documented.
- [ ] Confirm optimized and baseline runs produce identical golden hashes where behavior should be unchanged.

Gate: representative generation and pregen have repeatable baselines, no unexplained regression, and no unbounded queue, allocation, or retained-memory growth.

## 9. CI and deterministic test infrastructure

Automated build and release-pipeline work in this section is deferred. The current beta pass uses manual artifacts; only correctness tests and deterministic reproducers that directly protect runtime behavior apply.

- [x] Add `:adapters:bukkit:plugin:test` to CI.
- [x] Expand the broad classload probe across all top-level and nested core classfiles, with an exact reviewed class and dependency-category allowlist that rejects new classes, changed dependency namespaces, non-missing-class failures, and stale entries.
- [ ] Move the core Bukkit purity ratchet below its current 182-file ceiling.
- [ ] Give `genProbe` a repository fixture or require an explicit portable pack path.
- [ ] Add a deterministic fixed-seed Iris-world task for Fabric, Forge, and NeoForge.
- [x] Make worldcheck return a failing process result when its internal result is FAIL.
- [x] Prevent `buildAllToOut` nested builds from racing root tasks over `core/build`.
- [x] Verify nested adapter builds honor the selected VolmLib source/coordinate.
- [x] Add packaged-jar server boots; manually assembled Bukkit, Fabric, Forge, and NeoForge artifacts all reached their runtime-ready state in isolated instances, including a real multi-mod classpath.

Gate: a clean CI run proves tests, deterministic generation, packaging, and server startup from the actual release artifacts.

## 10. Full platform acceptance matrix

Use the exact packaged release jars, not development classes.

The current isolated smoke proves fresh non-empty generation, exact fixed-seed block-and-biome parity, and complete serial/sync 2,025-chunk pregeneration on Paper, Fabric, Forge, and NeoForge. A second real content-mod fixture also passes entity, item, block, structure loot, death loot, headless initial-spawn, and 2,025-chunk pregeneration gates on all three mod loaders. It does not yet satisfy the minimum/latest loader, complete Bukkit-family, client, lifecycle, or full pregen-control matrix below.

- [ ] Bukkit-family server matrix:
  - [ ] Paper current target
  - [ ] Purpur current target
  - [ ] Folia current target
  - [ ] Spigot/CraftBukkit if still advertised as supported
- [ ] Mod-loader matrix:
  - [ ] Fabric declared minimum loader
  - [ ] Fabric latest compatible loader
  - [ ] Forge declared minimum loader
  - [ ] Forge latest compatible loader
  - [ ] NeoForge declared minimum loader
  - [ ] NeoForge latest compatible loader
- [ ] On every server target:
  - [ ] Fresh Iris world creation and non-empty chunk generation
  - [ ] Existing Iris world restart and new-chunk generation
  - [ ] Custom biome registration and client synchronization
  - [ ] Structures, objects, loot, spawners, and entities
  - [ ] Golden-hash match for the shared pack/seed
  - [ ] Pregeneration start, pause, resume, cancel, restart, and shutdown
  - [ ] Studio validation, hotload failure recovery, and successful hotload where supported
  - [ ] Clean startup and shutdown without leaked threads or incomplete futures
- [x] Content-mod gate on Fabric, Forge, and NeoForge using Nerospace beta.7 and Neroland Core 1.4.0 with authored `nerospace:meadow_loper`, `nerospace:raw_nerosium`, and `nerospace:nerosium_ore` resources.
- [ ] Client matrix:
  - [ ] Modded Iris server + Iris client mod
  - [ ] Modded Iris server + client without Iris where loader rules permit
  - [ ] Bukkit Iris server + Iris client mod over plugin messaging
  - [ ] Non-Iris server + Iris client mod remains inert
  - [ ] Integrated singleplayer create, leave, and create/join again in the same client process
  - [ ] Pregen HUD, Vision map, cursor overlay, keybinds, and Studio toasts

Gate: every advertised server, loader, client, and content path completes the same acceptance scenario or has a clearly documented intentional capability difference.

## 11. Documentation and repository hygiene

- [x] Make runtime splash/version identity match the artifact version; remove stale `4.0 RC.1.1.6` text.
- [x] Correct README pregen syntax, including the required radius.
- [ ] Document how to select an Iris world preset on each mod loader.
- [ ] Distinguish automatic pack installation from automatic Iris main-world selection.
- [ ] Publish an accurate Bukkit-versus-modded Studio capability matrix.
- [ ] Document intentional entity-spawn and tooling differences that remain.
- [x] Remove tracked generated SIMD benchmark `.class` files and jar outputs.
- [x] Keep generated server worlds, caches, credentials, and build artifacts ignored.
- [ ] Consolidate the Iris section of `MasterChangelog.MD` to the final shipped behavior.
- [ ] Review store/listing copy, screenshots, commands, supported platforms, and Java requirements.
- [ ] Write release notes with upgrade instructions, known limitations, and rollback guidance.

### Confirmed release blockers and follow-ups

- [ ] Publish and retain anonymously downloadable `beta` assets for both managed pack repositories before shipping a build that requires dual bootstrap. Runtime uses the mutable Overworld and Underworld beta release URLs; record each downloaded asset checksum for a release baseline, and move production installs to immutable release inputs when the beta streams are promoted.
- [x] Make modded GoldenHash metadata use the active Iris engine seed. Fabric, Forge, and NeoForge generated identical output from Iris seed `1337`, but filenames and headers recorded each vanilla level seed, preventing one captured baseline file from being reused directly across loaders.
- [x] Correct the default overworld pack's slime spawn category from implicit `MISC` to explicit `MONSTER` in `biomes/vanilla/mangrove_swamp.json`, `biomes/swamp/cambian-drift.json`, `biomes/swamp/cambian-drift-extended.json`, `biomes/swamp/marsh.json`, and `biomes/swamp/marsh-rotten.json`. NeoForge exposes the bad category at startup; all loaders generate the same bad datapack entry, which can affect natural slime spawning and mob-cap accounting.
- [x] Extend `PackValidator` to reject authored custom-biome spawn categories that disagree with the live entity category instead of allowing the bad datapack to reach loader validation.
- [x] Restore exactly the 36 standard entity resources required by the overworld's retained spawner library from their last authored revision, preserving their type/surface values without restoring deleted unique entities, while detaching every regional and spider-infestation ambient spawner so the library remains dormant unless a pack author explicitly references it.
- [x] Delegate ongoing natural spawn tables in custom Iris biomes to each `vanillaDerivative` on Bukkit, Fabric, Forge, and NeoForge; explicit custom entries replace the same native entity type and extend the rest, while structure overrides remain authoritative and cached tables avoid hot-path allocation.
- [x] Add validated custom-biome tag opt-ins and put all five explicit overworld slime biomes in `minecraft:allows_surface_slime_spawns`, allowing Minecraft's native surface-slime checks to succeed.
- [x] Add Minecraft 26.2 default-clock metadata to generated Iris overworld and End dimension types so `/time set`, `/time add`, time queries, and clock controls work in Iris overworld dimensions. An isolated Paper 26.2 runtime loaded a dimension using `iris:overworld`, reported the `minecraft:overworld` clock, accepted day and night time markers, and returned the clock time.
- [x] Make synchronous modded pregen completion diagnostics report meaningful concurrency values. The successful runs reported `peakInFlight=0 finalLimit=32` despite a strict `inFlightCap=1` sync mode.
- [ ] Pin or fix the isolated test harness behavior before treating it as release evidence: setting an instance isolated currently leaves consumer-content symlinks in place. This pass used a fresh, dedicated harness root, so those links pointed only to test-local content and did not contaminate the test, but the isolation flag alone is insufficient.
- [x] Resolve the fixed-seed order/state-dependent block generation and Paper-versus-modded biome-hash difference. The manually built candidate produced one exact full hash before and after 2,025-chunk pregeneration on Paper, Fabric, Forge, and NeoForge; Fabric also retained it after restart.
- [ ] Re-run Folia when an upstream 26.2 server build becomes available. The official 26.2 build endpoint currently returns `version_not_found`; an incompatible 26.1.2 runtime is not acceptable beta evidence.
- [ ] Nerospace beta.7's bundled `nerospace:guide/new_life` advancement uses the obsolete `minecraft:entity_sub_predicate_type`/`minecraft:type` shape and logs one datapack parse error on Fabric, Forge, and NeoForge 26.2. Iris's custom block, item, entity, chest loot, death loot, and pregeneration integration all pass despite that independent content-mod error; update Nerospace before using it as a clean-log beta recommendation.
- [x] Preserve structure-level loot through placement persistence. Newly placed structure containers receive a versioned, delimiter-safe marker containing the piece object, deterministic placement id, and owning structure; `Engine.getObjectPlacement()` reconstructs authored loot in order at weight 1 for the existing Bukkit and modded application paths without overriding global loot. Legacy `object@id` markers remain readable, malformed and unknown-version markers fail safely, and marker writes are storage-container-only.
- [x] Remove the unsupported `IrisStructurePlacement` `rotation`, `translate`, and `scale` fields from beta authoring and generated schemas. Read-only pack validation now blocks those keys specifically inside dimension, region, and biome `structures[]` entries instead of accepting settings with no runtime effect; ordinary object-placement transforms remain valid and are not inspected by this check.

Gate: documentation and distribution metadata describe the behavior users will actually receive.

## 12. Final GO/NO-GO gate

- [x] `unit-tests`: pass.
- [ ] `qa-validation`: pass across the full matrix.
- [ ] `edge-case-review`: pass or all remaining risks explicitly accepted.
- [ ] `perf-regression`: pass against the recorded baseline.
- [ ] `release-dry-run`: pass using final packaged artifacts.
- [ ] `changelog-ready`: pass.
- [x] `manual-smoke`: pass.
- [ ] `docs-updated`: pass.
- [ ] `known-issues-reviewed`: pass.
- [ ] Working tree is clean on the exact release commit.
- [ ] CI is green on that commit and all evidence artifacts are retained.
- [ ] Complete every item in `86 - Maintainer - Release Checklist.md` without rebuilding from different source.

Release decision:

- [ ] **GO** - every required check passes and no unresolved warning remains.
- [ ] **GO-WARN** - every required check passes and each warning is documented and explicitly accepted.
- [x] **NO-GO** - any required check fails or remains pending.

## Fixes already completed in the current working tree

- [x] Concurrent generation binds immutable engine/session/chunk context per worker scope and restores or removes that binding at scope close.
- [x] Context-backed stream caches reject the wrong engine, a stale generation session, and coordinates outside the bound chunk.
- [x] Registry-backed mantle and `.mat` reads bind the owning pack data explicitly, and heightmap object placement no longer depends on ambient generation context.
- [x] Configured Matter placements use the initialized canonical Matter loader instead of a duplicate null field.
- [x] Deterministic barrier, worker-reuse, nested-scope, close-order, and context-cache regression tests pass.
- [x] Bukkit/Paper pregeneration accepts small positive radii and a strict one-in-flight `serial=true` mode without changing normal Paper/Folia concurrency.
- [x] Pregeneration drains the final backend callback before reporting completion, eliminating the observed 2,024/2,025 success summary; delayed final success and failure paths have regression coverage.
- [x] Modded synchronous and asynchronous completion counters count only successful chunks, and final summaries include generated, total, failed, and duration values.
- [x] GoldenHash null-biome fallback is explicitly `minecraft:plains` on Bukkit and modded adapters.
- [x] GoldenHash metadata uses the active Iris seed across every platform, and GitHub pack downloads accept validated immutable commit and tag references in preparation for freezing the default pack.
- [x] Runtime splash identity derives from the packaged artifact version instead of a stale release label.
- [x] Pack validation is read-only; cleanup and restore require explicit preview/apply flows with fresh scans, direct-child containment, conflict refusal, per-pack serialization, truthful rollback reporting, and no-overwrite quarantine handling.
- [x] Custom-biome spawn groups validate against live platform entity categories, including `AXOLOTLS`, and the default overworld slime records are explicitly `MONSTER` with isolated NeoForge proof.
- [x] Spawner entity dependency validation covers both runtime spawn lists, malformed entry/container shapes, missing or malformed referenced entities, nested resource keys, and path containment; the default pack's dormant spawner library resolves to exactly 36 standard entities and no restored unique entities.
- [x] `.iris` packaging collects entity dependencies from both normal and initial spawner lists, so an entity used exclusively during initial chunk spawning remains present after export.
- [x] Newly placed structure containers persist versioned structure ownership and resolve the structure's authored loot through the shared Bukkit/modded placement path without replacing global loot or consuming generation RNG.
- [x] VolmLib is pinned to commit `d9026a7c8ebc391c8109f401ce79a0ce65df3969`; local-development and clean remote-resolution modes propagate through every nested platform build.
- [x] Headless classload validation scans all 1,166 compiled core classes, including all 353 nested classfiles; 331 nested classes initialize without server APIs and the remaining 22 match exact reviewed class and dependency-namespace entries.
- [x] Modded worldcheck uses a daemon coordinator with bounded waits on every server task, stops the server before exiting, and returns nonzero for internal failure, timeout, interruption, thrown checks, and shutdown failure; its exit contract is covered by the Fabric shared-source test gate.
- [x] Fabric protocol startup tolerates the pre-player-list server phase.
- [x] NeoForge registers the shared payload once as bidirectional.
- [x] Fabric distributable metadata declares the bundled transitive access-widener.
- [x] Fabric, Forge, and NeoForge relocate Iris's embedded Sentry runtime so another mod can bundle Sentry without a duplicate-package module-resolution failure; the corrected Forge and NeoForge artifacts boot alongside Neroland Core's jar-in-jar Sentry dependency.
- [x] Fabric, Forge, and NeoForge resolve Minecraft 26.2's supplied OSHI, JNA, JNA Platform, and LZ4 implementations without embedding or relocating them. The distribution gate scans outer classes and nested jars for private rewritten references or duplicate runtime libraries before accepting each artifact.
- [x] Headless force-loaded chunks receive structure loot and initial entity spawning on Bukkit and every mod loader without requiring a player to enter the world. Bukkit target collection is global/region-safe, bounded, rotating, and deduplicated; modded initial-spawn requests retry and recover without caller-runs disk work on the server tick.
- [x] Bukkit world creation preserves explicit `pack:dimensionKey` selection through pack installation and engine creation, matching the modded command behavior and preventing same-key cross-pack collisions.
- [x] Bukkit/Folia world-manager snapshots keep world, player, entity, chunk, and force-load API access on the appropriate global, entity, or region scheduler and refresh saturation before its early-return gate.
- [x] Multicore Perfection waits for isolated worker completion.
- [x] Bukkit exclusive-control permits release after failures and interruptions.
- [x] Modded sea-level/base-column calculations use absolute world Y.
- [x] Low-risk map drawing, post-processing, base-column, and block-buffer loop costs were reduced.
- [x] Core tests, Bukkit plugin tests, all-platform assembly, archive integrity, and fresh Iris-world checks on Fabric, Forge, and NeoForge passed for this fix set.

These completed items remain subject to the final packaged-artifact, Bukkit/Folia, concurrency, and performance gates above.

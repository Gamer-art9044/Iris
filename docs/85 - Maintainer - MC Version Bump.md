# 85 - Maintainer — Minecraft Version Bump

`gradle.properties` `minecraftVersion` is the single source of truth for the target Minecraft version. Most build outputs derive from it. This document lists every edit required to move Iris to a new Minecraft version, in order.

## How to run the bump

Use a dedicated branch or worktree and begin from a green build. Record the old and new Minecraft, Bukkit API, loader, datapack-format, and NMS binding values before editing. Complete the ordered steps without skipping ahead, run the focused gate after each platform boundary, then run the full all-platform build and disposable-server lifecycle smokes.

Stop when an upstream API, mapping, or loader artifact is unavailable; do not hide the gap behind a legacy fallback. The bump is complete only when generated artifact names, metadata ranges, data fixers, NMS selection, tests, and documentation all agree on the same target.

## Source of truth

`gradle.properties`:

- `minecraftVersion` — target MC version (e.g. `26.2`). Drives `BuildConstants.MINECRAFT_VERSION`, the `com.mojang:minecraft` coordinate, all mod-metadata minecraft ranges, and every dist/jar artifact name.
- `apiVersion` — Bukkit plugin `api-version` (e.g. `26.1`). Deliberately decoupled from `minecraftVersion`: it is the lowest Minecraft release line the single plugin artifact loads on (currently `26.1` so one jar serves 26.1.2 and 26.2). Bump it only when dropping support for the older line.
- `fabricLoaderVersion` — Fabric Loader version.
- `forgeVersion` — Forge version (`<mc>-<forge>`).
- `neoForgeVersion` — NeoForge version.
- `irisVersion` — bump the trailing `-<mc>` suffix to match (e.g. `4.0.0-26.2` -> `4.0.0-27.0`).

## Ordered steps

1. Edit `gradle.properties`: update `minecraftVersion`, `fabricLoaderVersion`, `forgeVersion`, `neoForgeVersion`, and the `irisVersion` suffix. Revisit `apiVersion` only if the bump drops support for the oldest Minecraft line the plugin artifact still loads on.

2. Edit `gradle/libs.versions.toml`:
   - `spigot` — the Spigot/Paper API pin used to compile against (`<mc>-R0.1-SNAPSHOT`).
   - `fabricApi-*` — the ten Fabric API module versions, if the new MC requires different Fabric API builds. Each module is versioned independently (`<version>+<build-hash>`). The ten are `base`, `registrySync`, `resourceLoader`, `lifecycleEvents`, `commandApi`, `eventsInteraction`, `networking`, `rendering`, `keyMapping`, `permission`. Every one of them is bundled jar-in-jar and must be declared in `fabric.mod.json` `jars` — see step 7.

3. Edit `core/src/main/java/art/arcane/iris/core/nms/datapack/DataVersion.java` (manual, structural):
   - Append a new enum constant `V<major>_<minor>("<mc>", <packFormat>, <DataFixer>::new)`.
   - `packFormat` comes from https://minecraft.wiki/w/Pack_format.
   - `getLatest()` returns the last enum constant, so append; do not reorder.
   - Add a matching `IDataFixer` implementation under `core/src/main/java/art/arcane/iris/core/nms/datapack/` if the datapack format changed.

4. Register the new Bukkit NMS binding module:
   - `settings.gradle` — add `include(':adapters:bukkit:nms:v<major>_<minor>_R<rev>')`.
   - `build.gradle` — add the binding to the `nmsBindings` map: `v<major>_<minor>_R<rev>: '<spigot-nms-build-version>'` (e.g. `'26.2.build.25-alpha'`).
   - Create the binding sources under `adapters/bukkit/nms/v<major>_<minor>_R<rev>/`.

5. Update loader version-range metadata (manual floors/ranges only; the `minecraft` ranges are templated from `minecraftVersion` and need no edit):
   - `adapters/fabric/src/main/resources/fabric.mod.json` — `minecraft` is `~${minecraftVersion}` (auto). Update the `fabricloader` floor (currently `>=0.19.3`) if the loader minimum changes, and the `jars` list if the bundled Fabric API modules change.
   - `adapters/forge/src/main/resources/META-INF/mods.toml` — `minecraft` versionRange is `[${minecraftVersion}]` (auto). Update `loaderVersion` (currently `[65,)`) and the `forge` dependency versionRange (also `[65,)`) for the new Forge line. Both are hand-maintained.
   - `adapters/neoforge/src/main/resources/META-INF/neoforge.mods.toml` — `minecraft` versionRange is `[${minecraftVersion}]` (auto). `loaderVersion` (currently `[3,)`) is the javafml specification version, not the NeoForge version, and rarely moves. The `neoforge` dependency `versionRange` is **hardcoded** (currently `[26.2,)`) and is *not* templated from `minecraftVersion` — hand-edit it on every bump or the mod will load on the wrong NeoForge line.

6. Re-verify the mapping-coupled files. Six files name Mojang-mapped classes, fields, and method descriptors directly. Nothing templates them, nothing fails fast at build time if a name moved, and a stale entry surfaces as a silent no-op or a load-time crash. Check every one against the new MC jar.

   Access widener (Fabric) — `accessWidener v2 official`, so the names are Mojang-mapped:

   - `adapters/fabric/src/main/resources/irisworldgen.accesswidener`
     - `MinecraftServer.levels` `Ljava/util/Map;`
     - `MinecraftServer.executor` `Ljava/util/concurrent/Executor;`
     - `MinecraftServer.storageSource` `Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;`
     - `PackRepository.sources` `Ljava/util/Set;` (accessible **and** mutable)

     Verify: each field still exists with that exact descriptor. Loom fails the build on an unresolvable AW entry, so a rename shows up as an AW error — read it, do not delete the line.

   Access transformers (Forge and NeoForge) — must stay in sync with each other and with the AW:

   - `adapters/forge/src/main/resources/META-INF/accesstransformer.cfg`
   - `adapters/neoforge/src/main/resources/META-INF/accesstransformer.cfg`
     - both: `public net.minecraft.server.MinecraftServer levels` / `executor` / `storageSource`

     Verify: the three ATs match the first three AW entries. Note the ATs have no `PackRepository` entry — Forge/NeoForge reach the pack sources through their own hooks, so do not add one without a reason. Wired via `minecraft { accessTransformer.from(...) }` (Forge) and `neoForge { accessTransformers.from(...) }` (NeoForge).

   Mixin configs — three JSONs, eight mixin classes, all targeting Mojang-mapped members:

   - `adapters/fabric/src/main/resources/irisworldgen.mixins.json`
     (package `art.arcane.iris.fabric.mixin`, `compatibilityLevel` `JAVA_25`, Fabric only)
     - `BlockItemMixin` -> `BlockItem.placeBlock`, `@At("RETURN")`
     - `BlockMixin` -> `Block.getDrops(...)` with a **full descriptor** (`BlockState, ServerLevel, BlockPos, BlockEntity, Entity, ItemInstance`) — the highest-churn entry in the repo; the parameter list changes across MC versions
     - `PackRepositoryMixin` -> `PackRepository.<init>`, `@At("RETURN")`
   - `adapters/modded-common/src/main/resources/irisworldgen.entity.mixins.json`
     (package `art.arcane.iris.modded.mixin`, `compatibilityLevel` `JAVA_21`, all three loaders)
     - `EntityPersistenceMixin` -> `Entity.shouldBeSaved`
     - `LivingEntityLootMixin` -> `LivingEntity.dropFromLootTable(ServerLevel, DamageSource, boolean)` — full descriptor
     - `MobAwarenessMixin` -> `Mob.serverAiStep`, injecting at a **field target** (`Lnet/minecraft/world/entity/Mob;noActionTime:I`) — verify the field, not just the method
   - `adapters/modded-common/src/main/resources/irisworldgen.client.mixins.json`
     (package `art.arcane.iris.client.mixin`, client-only)
     - `IrisWorldOpenFlowsMixin` -> `WorldOpenFlows.confirmWorldCreation` and `WorldOpenFlows.openWorldCheckWorldStemCompatibility`
     - `IrisWorldTypeEntryMixin` -> `WorldCreationUiState.WorldTypeEntry.describePreset`, plus a `@Shadow` member — shadows break silently if the field is renamed

     The client mixin *config* lives in `modded-common/src/main/resources` but the classes live in `adapters/client-common/src/main/java/art/arcane/iris/client/mixin/`; the modded mixin classes live in `adapters/modded-common/src/main/java/art/arcane/iris/modded/mixin/`. All three adapters add both shared source dirs, so one edit hits every loader.

     Registration differs per loader and each place must list the same configs:
     - Fabric — `fabric.mod.json` `mixins` (all three; the client one gated on `"environment": "client"`).
     - NeoForge — `[[mixins]]` blocks in `neoforge.mods.toml` (entity + client).
     - Forge — no toml entry. The jar manifest attribute `MixinConfigs` in `adapters/forge/build.gradle` plus `--mixin.config` args on the `runClient`/`runServer` configurations (entity + client). Adding a mixin config on Forge means editing the manifest attribute *and* the run args.

     `injectors.defaultRequire` is `1` in all three configs, so a mixin that no longer applies fails the run instead of degrading quietly. Treat any "mixin apply failed" line as a bump blocker, and run both `runClient` and `runServer` per loader — client-only mixins are not exercised by a server run.

7. Reconcile the Fabric jar-in-jar list. `adapters/fabric/build.gradle` adds every Fabric API module to the `jij` configuration, which the `shadowJar` copies into `META-INF/jars` with the version stripped from the filename. The `jij` configuration is `transitive = false`, so the bundled set is exactly the declared set, and `fabric.mod.json` `jars` must list exactly those filenames. After changing the module list, confirm the jar agrees:

   ```
   unzip -l "dist/Iris v<version> [Fabric] <mc>+<loader>.jar" | grep META-INF/jars
   ```

   An entry in `jars` with no matching nested jar makes the loader refuse the mod; a nested jar missing from `jars` is dead weight the loader never mounts.

8. Build and verify:
   - `./gradlew :core:check`
   - `./gradlew buildBukkit`
   - `./gradlew buildFabric`
   - `./gradlew buildForge`
   - `./gradlew buildNeoforge`

9. After a successful bump, re-run operator smoke and GoldenHash parity on all platforms (`31 - Operator Runbooks & Smoke Tests.md`, `32 - Determinism & Goldenhash.md`) and continue with `86 - Maintainer - Release Checklist.md` only after `87 - Maintainer - Release Readiness.md` allows GO or GO-WARN.

## Derived automatically (do not hand-edit on a version bump)

- Bukkit plugin `api-version` — `adapters/bukkit/plugin/build.gradle` reads `apiVersion`.
- `BuildConstants.MINECRAFT_VERSION` — stamped by the `generateTemplates` task in `core/build.gradle` from `minecraftVersion`; consumed by `Tasks.supportedVersions`.
- Mod-metadata `minecraft` version ranges — templated from `minecraftVersion` at `processResources`.
- Dist/jar artifact names and the `com.mojang:minecraft` coordinate — composed from `minecraftVersion` in the build scripts.

## Notes

- `build.gradle`, the adapter `build.gradle` files, and `settings.gradle` carry `.getOrElse('26.2')` defensive defaults for the version properties. `gradle.properties` always overrides them, so a bump does not require touching those fallbacks; refresh them only if the checked-in default should track the current release.
- The Java literal `"26.2"` intentionally remains in `DataVersion.java` (structural enum constant), `core/src/test/java/art/arcane/iris/core/nms/MinecraftVersionTest.java`, and `core/src/test/java/art/arcane/iris/core/lifecycle/PaperLibBootstrapTest.java`. The test files use MC version strings as parser fixtures, not as a version source; update them only when the version string formats they exercise change.

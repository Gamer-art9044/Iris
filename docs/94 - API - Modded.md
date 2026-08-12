# 94 - API - Modded

`art.arcane.iris.modded.api` is the surface a **mod** compiles against on Fabric, Forge, and NeoForge: detect Iris levels, drive pregeneration and mantle storage, and supply custom blocks/items/mobs to packs. It ships only in those mod jars, is absent from the Bukkit plugin jar, and shares no types with `art.arcane.iris.api`. Bukkit terrain, world events, pregen events, and tree-feller services do not exist here — see `90 - API - Getting Started.md` through `93 - API - Tree Feller.md` for the plugin surface.

Assumes Minecraft 26.2, Java 25, and Fabric, Forge, or NeoForge. Mod id is `irisworldgen` on all three.

| Goal | Entry |
|---|---|
| Detect Iris, read engine token, start pregen, mantle R/W | `IrisModdedAPI` |
| Have Iris place your blocks/items/mobs | `ModdedDataProvider` |
| Alias one custom key onto a fixed vanilla state | `IrisModdedAPI.registerCustomBlockData` |

---

## Depending on Iris

**No published Maven artifact for the mod jars.** Root build has no `maven-publish` for them; JitPack for this repo resolves Bukkit-oriented sources, not the modded adapters. Build from source:

```bash
./gradlew buildFabric     # -> dist/Iris v<version> [Fabric] <mc>+<loader>.jar
./gradlew buildForge      # -> dist/Iris v<version> [Forge] <mc>+<loader>.jar
./gradlew buildNeoforge   # -> dist/Iris v<version> [NeoForge] <mc>+<loader>.jar
```

Each adapter is a standalone Gradle build (`adapters/<loader>/settings.gradle` `includeBuild('../..')`). Compile against the jar you run:

```gradle
dependencies {
    compileOnly(files('libs/Iris-fabric.jar'))
}
```

Adapters are not in root `settings.gradle` by default. `-PincludeModdedAdapters=true` is for IDE import only and can close a composite-build cycle Gradle rejects.

### Soft dependency

Fabric (`fabric.mod.json`) — `suggests`, not hard `depends`:

```json
{
  "suggests": { "irisworldgen": "*" }
}
```

NeoForge (`META-INF/neoforge.mods.toml`):

```toml
[[dependencies.yourmod]]
modId = "irisworldgen"
type = "optional"
ordering = "AFTER"
side = "BOTH"
```

Forge (`META-INF/mods.toml`): same with `mandatory = false` instead of `type`.

Do not rely on load order for ServiceLoader discovery — Iris discovers providers itself.

### Detecting Iris

| Question | How |
|---|---|
| Is the mod present? | `FabricLoader.getInstance().isModLoaded("irisworldgen")` or `ModList.get().isLoaded("irisworldgen")` |
| Are Iris classes loadable? | Keep imports behind a presence check / separate class, or `Class.forName("art.arcane.iris.modded.api.IrisModdedAPI")` |
| Is **this** level Iris? | `IrisModdedAPI.isIrisLevel(level)` |

Do not gate on version strings. Probe for the class or method you need. Servers mix Iris and vanilla dimensions freely.

```java
private static final boolean IRIS_PRESENT = irisPresent();

private static boolean irisPresent() {
    try {
        Class.forName("art.arcane.iris.modded.api.IrisModdedAPI");
        return true;
    } catch (Throwable absent) {
        return false;
    }
}
```

---

## `IrisModdedAPI`

All static, null-tolerant: null or non-Iris `ServerLevel` → `false`, `null`, or no-op.

| Method | Behaviour |
|---|---|
| `isIrisLevel(ServerLevel)` | Chunk generator is `IrisModdedChunkGenerator`. Cheapest check |
| `isStudioLevel(ServerLevel)` | Throwaway pack-authoring world. Persist nothing |
| `getEngine(ServerLevel)` | Internal `Engine` or null. See stability note |
| `pregenerate(ServerLevel, int radiusBlocks)` | Cached async pregen around origin |
| `pregenerate(ServerLevel, int, int centerX, int centerZ, boolean sync, boolean cached)` | Same with centre and write mode |
| `getMantleData(ServerLevel, int x, int y, int z, Class<T>)` | Read per-block mantle |
| `setMantleData(ServerLevel, int x, int y, int z, T)` | Write (creates region; can touch disk) |
| `deleteMantleData(ServerLevel, int x, int y, int z, Class<T>)` | Remove type at position (creates region) |
| `retainMantleDataForSlice(Class<?>)` | Keep custom mantle types after trim |
| `registerProvider(ModdedDataProvider)` | Imperative provider registration |
| `registerCustomBlockData(String namespace, String key, String state)` | Static block-state alias |

### Threading (API entry)

| Method | Thread guidance |
|---|---|
| `isIrisLevel`, `isStudioLevel`, `getEngine` | Safe from any thread once the level is loaded |
| Mantle get/set/delete | Safe off server thread but touch engine storage; writes can disk I/O |
| `pregenerate`, `registerProvider` | Server thread / mod setup / command |
| `retainMantleDataForSlice`, `registerCustomBlockData` | Mod setup |

### `Engine` is internal

`getEngine` returns `art.arcane.iris.engine.framework.Engine`. Internal; changes without deprecation. Treat as an opaque token to hand back to Iris. Prefer the wrappers on this class.

Never cache an `Engine`. Pack hotload or level unload replaces it. `getEngine` returns null while binding and during shutdown.

### Pregeneration

Returns as soon as the job is queued. Progress is Iris logging and boss bar only — no caller callback (no modded equivalent of `IrisPregenerationEvent`; see limitations). One job server-wide; returns `false` if a job is already active **or** the level is not Iris — check `isIrisLevel` first to distinguish. Call on the server thread.

`cached = true` uses on-disk pregen cache for resume. `sync = true` writes chunks synchronously (slower; bypasses async write queue). Simple overload is `pregenerate(level, radius, 0, 0, false, true)`.

Operator pregen concepts: `07 - Pregeneration.md`. Platform matrix: `30 - Platform Differences.md`.

### Mantle data

Iris per-block sidecar, independent of chunk NBT.

1. **Coordinates are world-space.** `y` is translated by engine min height internally. Out-of-range `y` reads null / writes no-op.
2. **Reads never create storage; writes do.** `getMantleData` returns null when no mantle region exists for that column. `set`/`delete` create the region (possible disk).
3. **Declare custom types or lose them.**

```java
IrisModdedAPI.retainMantleDataForSlice(MyMarker.class);
```

Registration is by canonical class name, process-wide, irreversible. All three mantle accessors throw `IllegalStateException` if the engine mantle is already closed.

---

## `ModdedDataProvider`

```java
public interface ModdedDataProvider {
    String modId();
    default boolean isReady();                                    // default true
    Collection<Identifier> getTypes(ModdedDataType type);
    boolean isValidProvider(Identifier id, ModdedDataType type);
    default ModdedBlockData getBlockData(Identifier blockId, Map<String, String> state);   // default null
    default void processBlockPlacement(ModdedBlockPlacementContext context);               // default no-op
    default Entity spawnMob(ServerLevel level, double x, double y, double z, Identifier entityId); // default null
    default void init();                                          // default no-op
}
```

`ModdedDataType`: `BLOCK`, `ITEM`, `ENTITY`. May gain constants — use `default` in switch expressions.

### Contract

- `modId()` — identity for de-duplication and logging. Non-null and stable; null aborts discovery.
- `isValidProvider` — gate before every resolution callback on generation threads. Namespace or set lookup only.
- `isReady()` — late registries: Iris **skips** false rather than treating as absent.
- `getTypes` — suggestions/tooling only; empty collection, never null.
- `getBlockData` — `state` is parsed `[prop=value]` map, never null, possibly empty. Null return declines (next provider, then air). `ModdedBlockData.direct(state)` for final; `deferred(placeholder)` for second pass.
- `processBlockPlacement` — finishes deferred placement on server thread with chunk loaded. Only the **first** provider claiming the id runs for a position. Placeholder should match final shape/occlusion.
- `spawnMob` — server thread; null declines.
- `init()` — once after accept.

`ModdedBlockPlacementContext` record: `engine`, `level`, `position`, `blockId`, `state` (defensive copy, unmodifiable), `blockState` (current at position). Immutable; constructed by Iris. `engine` is internal.

`ModdedBlockData`: `direct(BlockState)` / `deferred(BlockState)`; null state rejected.

### Provider threading

| Callback | Thread | Notes |
|---|---|---|
| `isValidProvider`, `getBlockData` | Generation threads, concurrent | Fast; no world mutation |
| `processBlockPlacement` | Server thread, chunk loaded | Safe block/entity writes |
| `spawnMob` | Server thread | |
| `init` | Discovering/registering thread | No server/level assumed |
| `modId`, `isReady`, `getTypes` | Any | |

### ServiceLoader registration

`META-INF/services/art.arcane.iris.modded.api.ModdedDataProvider` — binary names, one per line. Nested classes use `$`. Public no-arg constructor required.

Iris loads with **its** class loader: `ServiceLoader.load(ModdedDataProvider.class, ModdedCustomContentRegistry.class.getClassLoader())`. If the registration log never appears, fall back to `registerProvider`.

### When discovery runs

`ModdedCustomContentRegistry.discover()` runs inside `ModdedEngineBootstrap.bootCommon(...)` at loader entry (`IrisFabricBootstrap.onInitialize`, Forge/NeoForge bootstrap construction) — before chunk-generator registration and long before a server starts.

- ServiceLoader providers are available before any world resolves blocks.
- Discovery once per process; second `discover()` is no-op.
- `init()` must not assume a server, level, or full game registry. Gate late work with `isReady()`.

### Imperative registration

`IrisModdedAPI.registerProvider(provider)` any time during setup. Providers registered after Iris's ServiceLoader pass are fine; after a world has generated, already-resolved blocks are not revisited. Duplicate `modId()` logged and ignored. `init()` runs during the call; throwables from imperative `init` are logged (provider stays registered).

### Logging

Under the `Iris` logger:

```
Iris registered custom content provider 'yourmod'
```

Duplicates: `already registered; ignoring duplicate`.

Per-callback failures are caught, logged against `modId()`, and generation continues:

```
Iris custom content provider 'yourmod' failed resolving block yourmod:thing
Iris custom content provider 'yourmod' failed post-placement for yourmod:thing at BlockPos{...}
Iris custom content provider 'yourmod' failed spawning mob yourmod:critter
Iris custom content provider 'yourmod' failed to initialize
```

ServiceLoader `init` failure is all-or-nothing: aborts discovery, rolls back that pass, rethrows:

```
Iris custom content provider discovery failed at provider 'yourmod' (com.example.yourmod.iris.YourIrisProvider)
```

### Static aliases

```java
IrisModdedAPI.registerCustomBlockData("yourmod", "fancy_log", "minecraft:oak_log[axis=y]");
```

State string uses pack syntax; parsed immediately. Bad state logged and dropped at startup. Aliases take precedence over providers for the same key. Null args ignored.

Go through `IrisModdedAPI`, not `ModdedCustomContentRegistry` resolution methods (public only for Iris internal packages).

---

## Pack paths and forced datapack (modded)

Paths relative to the loader config directory (`config/`):

| Path | Role |
|---|---|
| `config/irisworldgen/packs/<pack>/` | Installed packs (`dimensions/<dimension>.json` required) |
| `config/irisworldgen/generated/datapack/iris/` | Generated forced datapack — Iris-owned |
| `config/irisworldgen/modded.json` | Default pack, auto-download, primary world routing |
| `config/iris/` | Engine data: settings and per-world engine state |

Pack install root is `config/irisworldgen/packs`, not `config/iris`. Missing pack at world open is a hard failure with the expected absolute path (no silent vanilla terrain).

When `autoDownloadDefaultPack` is enabled, startup asynchronously installs missing `overworld` and `underworld` managed beta releases, plus a distinct configured non-managed `defaultPack`. Failures log a pointer to `/iris download <pack>`; managed beta names always resolve to their release assets rather than the requested branch.

Forced datapack contributes presets, dimension types, and biomes under `irisworldgen` (ids from pack/dimension names). Regenerated on pack change / studio hotload. Failure to inject (mixin/event not applied) logs once:

```
Iris forced datapack 'iris_worldgen' was never loaded by this server.
```

That is a loader/environment problem, not a pack problem.

### Commands (modded)

`/iris` (`/ir`, `/irs`), gamemaster level. Full tree: `04 - Commands & Permissions.md`. Notable:

| Command | Role |
|---|---|
| `/iris pack validate [pack]` | Validate packs on a worker |
| `/iris pack status [pack]` | Recorded validation results |
| `/iris pack cleanup` / `restore` | Unused resource preview/apply |
| `/iris datapack status` | Active vs pack dimension-type heights |
| `/iris datapack install` | Write pack dimension type override into world datapacks |
| `/iris download <pack>` | Install pack (`dl` alias) |
| `/iris version` | Version and loader |

`/iris datapack ingest|pull|remove` refuse on modded (Bukkit Modrinth tooling). Native/datapack structure placement works: install into `<world>/datapacks/` and restart. Structures overview: `22 - Native Structures & Datapacks.md`.

---

## Native worldgen passthrough over Iris terrain

Iris replaces the chunk generator. Vanilla/mod worldgen runs only if Iris runs it:

| System | Over Iris? | Notes |
|---|---|---|
| Structures (vanilla, datapack, mod) | **Yes**, on by default | Vertical fit, stilts, vegetation clear. Deny families with `importedStructures.disabled`, one complete key with `disabledExact`, or scale an exact structure set with `frequencyOverrides` |
| Placed features (ores, trees, plants, …) | **Yes**, **off** by default | Dimension `importedFeatures.enabled` |
| Carvers | **Never** | No noise router / aquifer for vanilla carvers |
| Mod biomes as sources | Only as derivative / scatter targets | Iris chooses biomes from the pack |
| Mob spawning (incl. mod mobs) | **Yes** | Merges pack biome table with vanilla derivative |
| Surface builders / rules | **Never** | Pack palettes |

`importedStructures.frequencyOverrides` has Bukkit parity on all three mod loaders. Entries use `{ "structureSet": "namespace:path", "multiplier": 0.01..16 }`; keys are exact registered structure-set keys, last duplicate wins, and changes affect new chunks only. Random-spread sets scale probability first and then derive the nearest legal integer spacing, while concentric rings can scale probability only. Custom placement types outside the affected override and exclusion-zone graph remain untouched; an unsupported placement that must be copied fails level binding rather than applying a partial override. Full semantics and the Nether `1.1` example are in `22 - Native Structures & Datapacks.md`.

### `importedFeatures`

Disabled by default. Absent or `enabled: false` → no feature table; terrain matches long-standing Iris-only output. Biome tags of the vanilla derivative are always inherited on custom biomes (not gated on this flag). Structure tags `#minecraft:has_structure/*` are **not** inherited.

```json
{
  "importedFeatures": {
    "enabled": true,
    "steps": ["UNDERGROUND_ORES"],
    "disabledSteps": ["VEGETAL_DECORATION"],
    "disabled": ["minecraft:ore_diamond", "minecraft:trees"]
  }
}
```

| Field | Meaning |
|---|---|
| `enabled` | Master switch; default `false` |
| `steps` | Allow-list of decoration steps; empty = all |
| `disabledSteps` | Deny-list after `steps` |
| `disabled` | Placed-feature key deny-list; prefix match on namespace/`/`/`_` boundaries |

Vanilla step order: `RAW_GENERATION`, `LAKES`, `LOCAL_MODIFICATIONS`, `UNDERGROUND_STRUCTURES`, `SURFACE_STRUCTURES`, `STRONGHOLDS`, `UNDERGROUND_ORES`, `UNDERGROUND_DECORATION`, `FLUID_SPRINGS`, `VEGETAL_DECORATION`, `TOP_LAYER_MODIFICATION`.

Features come from the biome's **vanilla derivative**. Feature seeds match vanilla derivation so denying one feature does not shift another's seed. Feature pass runs on the worldgen thread owning the chunk (not Iris gen pool), after Iris structures — early-step features can cut into placed structures. Feature-order cycles at bind degrade `importedFeatures` to off with ERROR log (no chunk crash). Same control exists on Bukkit with the same semantics.

### Carvers

`applyCarvers` is empty by design. Use pack `caves` / `carvings`: `15 - Caves & Carving.md`.

### 26.2 note: `pointed_dripstone` / `speleothem`

| Registry | 26.2 key |
|---|---|
| Block | `minecraft:pointed_dripstone` unchanged |
| Placed / configured feature | `minecraft:pointed_dripstone` unchanged |
| Feature **type** | `minecraft:speleothem` renamed |

Pack palette / object / `importedFeatures.disabled` keys using the block or placed-feature id remain correct.

### Biome tags

Custom biomes inherit derivative tag membership plus pack `tags`. Files use `"replace": false`. Details: `13 - Biomes.md`.

---

## What is not supported

- **No published mod artifact** — build from source.
- **Core packages internal** — `engine.*`, `core.*`, `util.*`, `spi.*`. `Engine` is the only internal type exposed (as a token) via `getEngine` / placement context.
- **No event bus** — `IrisPlatform.callEvent` is a no-op on mod loaders. No modded `IrisWorldEngineEvent` / `IrisPregenerationEvent`; poll `isIrisLevel` / `getEngine`.
- **No `art.arcane.iris.api`** in mod jars. No modded terrain-query service yet.
- **No PlaceholderAPI** — `09 - PlaceholderAPI.md` is Bukkit-only.
- **Datapack ingest commands** Bukkit-only.
- **Vanilla carvers and surface rules never run.**
- **`ModdedCustomContentRegistry` resolution APIs** are Iris internals; use `IrisModdedAPI`.

Install and platform notes: `01 - Installation & Platforms.md`, `30 - Platform Differences.md`.

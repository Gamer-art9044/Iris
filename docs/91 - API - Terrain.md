# 91 - API - Terrain

`art.arcane.iris.api.terrain` answers what the Iris generator says about a coordinate: whether a world is Iris-generated, which biome and region the pack places, surface height, and whether that surface is land, shore, ocean, or void. It reads the **generator**, not the world: no chunk load, no forced generation, no placed-block read, and no knowledge of player edits. Reads are non-blocking noise evaluation over a shared per-chunk cache.

Build and service acquisition: `90 - API - Getting Started.md`. Service: `IrisTerrainService`, registered at `ServicePriority.Normal` for Iris's enabled lifetime.

```java
package com.example.integration;

import art.arcane.iris.api.terrain.IrisTerrainService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class TerrainAccess {
    private TerrainAccess() {
    }

    public static IrisTerrainService service() {
        RegisteredServiceProvider<IrisTerrainService> provider =
                Bukkit.getServicesManager().getRegistration(IrisTerrainService.class);
        return provider == null ? null : provider.getProvider();
    }
}
```

Missing registration means Iris is absent or not enabled yet — `null`, not an exception. There is no static `Iris` accessor for this surface.

---

## The read surface

```java
public interface IrisTerrainService {
    boolean isIrisWorld(World world);

    Optional<IrisWorldInfo> worldInfo(World world);

    OptionalInt surfaceHeight(World world, int blockX, int blockZ);

    IrisSurfaceKind surfaceKind(World world, int blockX, int blockZ);

    Optional<String> surfaceBiomeKey(World world, int blockX, int blockZ);

    Optional<String> surfaceBiomeName(World world, int blockX, int blockZ);

    Optional<String> biomeKey(World world, int blockX, int blockY, int blockZ);

    Optional<String> regionKey(World world, int blockX, int blockZ);

    Optional<String> regionName(World world, int blockX, int blockZ);

    int maxSampleColumns();

    int maxSampleChunks();

    boolean sampleColumns(World world, IrisColumnQuery query, IrisColumnSink sink);
}
```

All coordinates are **absolute block coordinates in world space**, including `blockY` and `surfaceHeight`. There is no engine-space offset for the caller.

`*Key` returns a pack load key (`desert/hot-dunes`, `overworld`) — stable, lowercase, store this. `*Name` returns the author's display string (`Hot Desert Dunes`) — for display; it can change when the pack author edits it. Both are empty when the value is absent or the empty string.

---

## Cost and blocking

Iris's generator is procedural noise. Each read evaluates the stack for one column and memoises in a shared per-chunk noise cache. Cold columns run pack noise; warm columns are array reads. Nothing here reads chunk storage, loads a region file, takes a contended lock, waits on a future, or asks the server to generate.

| Call | Cost when cold | Cost when warm | Forces generation | Can block | When data is absent |
|---|---|---|---|---|---|
| `isIrisWorld` | `World#getGenerator()` + `instanceof` | same | No | No | `false` |
| `worldInfo` | field reads off live engine/dimension | same | No | No | `Optional.empty()` |
| `surfaceHeight` | one height sample (region + base-biome streams) | array read | No | No | `OptionalInt.empty()` |
| `surfaceKind` | height sample; surface-biome only when column is above fluid and not void floor | array read | No | No | `IrisSurfaceKind.UNKNOWN` |
| `surfaceBiomeKey` / `surfaceBiomeName` | surface-biome sample (height, base biome, region) | array read | No | No | `Optional.empty()` |
| `biomeKey` at/near surface | as surface biome + height to choose surface vs cave | array read | No | No | `Optional.empty()` |
| `biomeKey` well below surface | above + cave-biome stream and carving resolution | array reads | No | No | `Optional.empty()` |
| `regionKey` / `regionName` | region sample (cheapest biome-family call) | array read | No | No | `Optional.empty()` |
| `maxSampleColumns` / `maxSampleChunks` | settings fields | same | No | No | positive number always |
| `sampleColumns` | one of the above per column, chunk-local order | array reads | No | No | `false`, sink untouched |

**Tight main-thread loops are non-blocking but wasteful.** They can evict the generator's noise cache working set shared with live chunk generation — chunk gen slows, not your loop. Use `sampleColumns` for anything wider than a handful of columns.

**Values are the generator's opinion, not the world's.** `surfaceHeight` is the topmost generated terrain block Y. It excludes objects, decorations, structures, trees, snow, and player edits. For real blocks use Bukkit `World#getHighestBlockYAt` (chunk load cost). For pack intent (pregen planners, map renderers, spawn pickers) use this API.

### Surface height, precisely

`surfaceHeight` returns absolute Y of the **topmost generated terrain block**. Standing height is `surfaceHeight + 1`. Fluid is ignored: under ocean you get the sea floor. Compare with `IrisWorldInfo.fluidHeight()` or use `surfaceKind`.

---

## Threading

**Every read may be called from any thread, including async.**

- Only Bukkit call on your behalf: `World#getGenerator()` on the world object. No chunk, block state, entity, or world-list walk.
- After that: engine-internal noise over concurrent caches; no region-owned state.
- No method takes a lock you can contend on, calls `join`, or schedules onto another thread.

Wide scans belong on your own async executor. On Folia there is no single correct region thread for a multi-region scan.

**`IrisColumnSink.accept` runs on the thread that called `sampleColumns`, inline, once per column.** If that thread is async, the sink must not touch Bukkit state. Collect locally, hop afterward.

---

## Column sampling

`sampleColumns` walks a rectangle at a stride, chunk by chunk, into your sink.

```java
public record IrisColumnQuery(
        int minBlockX,
        int minBlockZ,
        int maxBlockX,
        int maxBlockZ,
        int strideBlocks,
        EnumSet<IrisColumnField> fields) {

    public static IrisColumnQuery rect(
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ,
            int strideBlocks,
            EnumSet<IrisColumnField> fields);

    public long columnCount();

    public long chunkCount();

    public EnumSet<IrisColumnField> fields();
}
```

Bounds are **inclusive on both ends**. Lattice anchors at `(minBlockX, minBlockZ)` and steps by `strideBlocks`.

Constructor rejects with `IllegalArgumentException`:

- empty `fields`,
- `maxBlockX < minBlockX` or `maxBlockZ < minBlockZ`,
- `strideBlocks < 1`.

`fields` is defensively copied on construction and on every `fields()` call. `fields()` allocates a fresh `EnumSet` each call — hoist it out of loops.

`columnCount()` and `chunkCount()` saturate at `Long.MAX_VALUE` on overflow.

### Hard limits

```
maxSampleChunks  = max(64, noiseCacheSize / 4)
maxSampleColumns = maxSampleChunks * 256   (capped at Integer.MAX_VALUE)
```

Default `performance.noiseCacheSize` is `1024` → **256 chunks and 65 536 columns**. One API query may not consume more than a quarter of the live generator cache.

**A query over either limit returns `false` and never calls the sink.** No partial answer, truncation, exception, or log line.

Limits are independent. Example: stride 64 over a 6400×6400 block rectangle can pass the column limit and fail the chunk limit. **`chunkCount()` is the chunk span of the rectangle, not sampled columns** — stride does not reduce it. Tile large areas.

Ask `maxSampleColumns()` / `maxSampleChunks()` every time; they change when the operator edits settings and reloads.

### The sink

```java
@FunctionalInterface
public interface IrisColumnSink {
    void accept(int blockX, int blockZ, int surfaceHeight, IrisSurfaceKind kind, String biomeKey);
}
```

Every column produces one `accept`. Placeholders for unrequested fields are not distinguishable from real data by value alone:

| Field requested | Parameter | If requested | If not |
|---|---|---|---|
| `SURFACE_HEIGHT` | `surfaceHeight` | absolute world Y of topmost terrain | `-1` |
| `SURFACE_KIND` | `kind` | `LAND`, `SHORE`, `OCEAN`, or `VOID` | `IrisSurfaceKind.UNKNOWN` |
| `BIOME_KEY` | `biomeKey` | biome load key | `null` |

`-1` is a legal absolute Y in worlds with negative min height — **never treat `-1` as absent**. Branch on your field set. `biomeKey` may be `null` even when requested if the column has no biome.

Fewer fields cost less. `SURFACE_KIND` alone skips the biome stream for void-floor and at-or-below-fluid columns. `BIOME_KEY` pays for the biome stream every column.

### Visit order

Columns arrive **grouped by chunk**. Chunk walk: Z outer, X inner. Within a chunk: lattice Z outer, X inner. Deterministic for a given query; **not** a pure row-major sweep of the rectangle. Sort or index by `(blockX, blockZ)` if you need raster order.

### Return value

`true` iff every column was delivered. `false` when:

- `world`, `query`, or `sink` is null, or no live Iris engine — sink untouched;
- a limit was exceeded — sink untouched;
- **your sink threw** — walk stops at that column;
- **engine closed mid-walk** — walk stops at that column.

In the last two cases, already-delivered columns stay delivered. Treat `false` as incomplete; discard partial results if completeness is required.

---

## Worked example: flattest buildable spot

Async sample, then hop to the player's entity scheduler (correct on Paper and Folia).

```java
package com.example.settlement;

import art.arcane.iris.api.terrain.IrisColumnField;
import art.arcane.iris.api.terrain.IrisColumnQuery;
import art.arcane.iris.api.terrain.IrisColumnSink;
import art.arcane.iris.api.terrain.IrisSurfaceKind;
import art.arcane.iris.api.terrain.IrisTerrainService;
import art.arcane.iris.api.terrain.IrisWorldInfo;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.Executor;

public final class SettlementSiteFinder {
    private static final int RADIUS_BLOCKS = 512;
    private static final int STRIDE_BLOCKS = 8;

    private final Plugin plugin;
    private final Executor background;

    public SettlementSiteFinder(Plugin plugin, Executor background) {
        this.plugin = plugin;
        this.background = background;
    }

    public void findFor(Player player) {
        World world = player.getWorld();
        Location origin = player.getLocation();
        int centreX = origin.getBlockX();
        int centreZ = origin.getBlockZ();

        background.execute(() -> {
            String result = search(world, centreX, centreZ);
            player.getScheduler().run(plugin, task -> player.sendMessage(result), null);
        });
    }

    private String search(World world, int centreX, int centreZ) {
        IrisTerrainService terrain = service();

        if (terrain == null || !terrain.isIrisWorld(world)) {
            return "That world is not generated by Iris.";
        }

        Optional<IrisWorldInfo> info = terrain.worldInfo(world);

        if (info.isEmpty()) {
            return "The Iris engine for that world is not available right now.";
        }

        IrisColumnQuery query = IrisColumnQuery.rect(
                centreX - RADIUS_BLOCKS,
                centreZ - RADIUS_BLOCKS,
                centreX + RADIUS_BLOCKS,
                centreZ + RADIUS_BLOCKS,
                STRIDE_BLOCKS,
                EnumSet.of(IrisColumnField.SURFACE_HEIGHT, IrisColumnField.SURFACE_KIND));

        if (query.columnCount() > terrain.maxSampleColumns()
                || query.chunkCount() > terrain.maxSampleChunks()) {
            return "That search area is larger than this server allows.";
        }

        int fluidHeight = info.get().fluidHeight();
        Best best = new Best();

        IrisColumnSink sink = (int blockX, int blockZ, int surfaceHeight, IrisSurfaceKind kind, String biomeKey) -> {
            if (kind != IrisSurfaceKind.LAND || surfaceHeight <= fluidHeight) {
                return;
            }

            long score = (long) Math.abs(surfaceHeight - fluidHeight) * 1024L
                    + Math.abs(blockX - centreX) + Math.abs(blockZ - centreZ);

            if (score < best.score) {
                best.score = score;
                best.x = blockX;
                best.y = surfaceHeight;
                best.z = blockZ;
            }
        };

        if (!terrain.sampleColumns(world, query, sink)) {
            return "The terrain scan did not complete. Try again.";
        }

        if (best.score == Long.MAX_VALUE) {
            return "No dry land within " + RADIUS_BLOCKS + " blocks.";
        }

        return "Best site: " + best.x + ", " + (best.y + 1) + ", " + best.z;
    }

    private IrisTerrainService service() {
        RegisteredServiceProvider<IrisTerrainService> provider =
                plugin.getServer().getServicesManager().getRegistration(IrisTerrainService.class);
        return provider == null ? null : provider.getProvider();
    }

    private static final class Best {
        private long score = Long.MAX_VALUE;
        private int x;
        private int y;
        private int z;
    }
}
```

`Best` needs no synchronisation: the sink runs inline on the `sampleColumns` caller thread.

---

## The minimum: one coordinate

```java
IrisTerrainService terrain = service();

String biome = terrain == null
        ? "unknown"
        : terrain.surfaceBiomeName(player.getWorld(), player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()).orElse("unknown");
```

`surfaceBiomeName` returns empty for non-Iris worlds, null worlds, closing engines, or disabled Iris. Call `isIrisWorld` only when you need to distinguish "not Iris" from "Iris has no answer".

---

## What `IrisWorldInfo` tells you

```java
public record IrisWorldInfo(
        String dimensionKey,
        String worldIdentity,
        long seed,
        int minHeight,
        int maxHeight,
        int fluidHeight,
        boolean studio) {

    public int height();
}
```

| Component | Meaning |
|---|---|
| `dimensionKey` | Pack load key of the dimension (e.g. `overworld`) |
| `worldIdentity` | World's namespaced key as string (e.g. `minecraft:overworld`) |
| `seed` | Raw generator seed |
| `minHeight` | Absolute world floor Y (e.g. `-64`) |
| `maxHeight` | Absolute world ceiling Y, exclusive (e.g. `320`) |
| `fluidHeight` | Absolute pack sea level Y (`pack fluid height + minHeight`) |
| `studio` | `true` only for a transient studio world |
| `height()` | `maxHeight - minHeight` |

All height fields are absolute world Y, comparable with `surfaceHeight` and `blockY`. Constructor rejects null `dimensionKey`/`worldIdentity` and non-positive height range.

`worldIdentity` is what Iris persists per-world state under. Outside the three vanilla dimensions the server derives the key from the world folder — renaming the folder changes `worldIdentity` and `World#getName()`.

`studio` worlds exist briefly for pack authoring; skip them for persistence.

`seed` reproduces the entire world offline. Iris does not expose it via PlaceholderAPI (`09 - PlaceholderAPI.md`). Do not put it where players can read it.

---

## Failure policy

| Situation | Behaviour |
|---|---|
| `world` is `null` | Queries answer absent; `sampleColumns` returns `false` |
| World has no Iris generator | Same |
| Iris disabled, or disabled between calls | Same; nothing throws |
| Generator closing, or engine closed | `isIrisWorld` still **`true`**; other queries absent |
| Query throws inside engine | Counted, logged with stack, answered absent |
| `query` or `sink` null | `sampleColumns` returns `false` |
| Query exceeds sample limits | `false`, sink never called, nothing logged |
| Sink throws | Walk aborts, fault logged (throttled), `false`; prior columns delivered |
| Engine closes mid-walk | Walk stops, `false` |

**`isIrisWorld` does not check liveness.** It answers "created by Iris", not "can answer right now". During unload/shutdown you can see `isIrisWorld == true` with empty `worldInfo`. Use `Optional` carefully.

**No caller quarantine.** Fault counters only throttle log lines to at most one report per minute per category; the count is cumulative.

No checked exceptions. Unchecked throws only from `IrisColumnQuery` / `IrisWorldInfo` construction validation.

---

## Configuration

`plugins/Iris/settings.json`:

| Key | Default | Effect |
|---|---|---|
| `performance.noiseCacheSize` | `1024` | Shared noise cache chunk capacity. `maxSampleChunks = max(64, this / 4)`; `maxSampleColumns = maxSampleChunks * 256` |

No on/off switch for the terrain API. Answers for every world with a live Iris engine; absent otherwise.

---

## Enum reference

### `IrisSurfaceKind`

| Constant | Meaning | Test applied (engine space, then reported in absolute terms) |
|---|---|---|
| `LAND` | Dry ground | Surface above fluid height; biome not shore |
| `SHORE` | Beach or bank | Surface above fluid; pack classifies biome as shore |
| `OCEAN` | Under water / sea floor at sea level | Surface at or below fluid height (and above void floor) |
| `VOID` | Nothing generated | Engine surface height ≤ 0 → absolute surface ≤ `minHeight()` |
| `UNKNOWN` | No answer | Not Iris / unavailable / fault / `SURFACE_KIND` not requested |

**`VOID` wins first.** Then fluid check, then shore vs land. Mutually exclusive.

`OCEAN` is inclusive at fluid height. Compare `surfaceHeight` to `fluidHeight` yourself if the one-block boundary matters.

### `IrisColumnField`

| Constant | Fills | Extra work |
|---|---|---|
| `SURFACE_HEIGHT` | `surfaceHeight` | one height sample per column |
| `SURFACE_KIND` | `kind` | height sample; biome only when above void floor and above fluid |
| `BIOME_KEY` | `biomeKey` | biome sample per column, always |

`SURFACE_HEIGHT` and `SURFACE_KIND` share the height sample when both are requested.

Write a `default` arm when switching enums: `90 - API - Getting Started.md`.

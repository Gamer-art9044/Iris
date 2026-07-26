# Iris terrain query API

`art.arcane.iris.api.terrain` answers what the Iris generator says about a coordinate: whether a
world is an Iris world at all, what biome and region the pack places there, how high the terrain
generates and whether that surface is land, shore, ocean or nothing. It is a read of the
**generator**, not of the world. It never loads a chunk, never forces generation, never reads a
placed block, and never tells you what a player has since built.

Everything here is cheap and non-blocking, and this document says exactly how cheap and exactly why
non-blocking, because a terrain API where the reader has to guess is a terrain API that ends up in a
per-tick loop.

---

## Depending on Iris and acquiring the service

See [README.md](README.md#depending-on-iris) for the build and plugin-descriptor setup. The service
is registered with the Bukkit `ServicesManager` at `ServicePriority.Normal` for the duration of the
Iris plugin's enabled lifetime.

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

There is no `Iris` class to import, no static accessor and no reflection. If the registration is
missing, Iris is absent or has not enabled yet; that is a `null` and not an exception.

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

All coordinates are **absolute block coordinates in world space**, including `blockY` and including
the value returned by `surfaceHeight`. There is no engine-space offset for a caller to apply.

`*Key` returns a pack load key — `desert/hot-dunes`, `overworld` — which is stable, lowercase and
what you store. `*Name` returns the author's display string — `Hot Desert Dunes` — which is what you
show and which can change when the pack author edits it. Both are `Optional` and both are empty when
the underlying value is absent or the empty string.

---

## Cost and blocking

This is the whole story. Read it before you write a loop.

Iris's generator is a stack of procedural noise streams. Every read below evaluates that stack for
one column and memoises the result in a shared per-chunk noise cache. A **cold** column runs the
pack's noise; a **warm** column is an array index. Nothing on this page reads chunk storage, reads a
block, loads a region file, takes a lock, waits on a future, or asks the server to generate
anything.

| Call | Cost when cold | Cost when warm | Forces generation | Can block | When the data is not there |
|---|---|---|---|---|---|
| `isIrisWorld` | one `World#getGenerator()` and an `instanceof` | same | No | No | `false` |
| `worldInfo` | field reads off the live engine and dimension | same | No | No | `Optional.empty()` |
| `surfaceHeight` | one height sample, which pulls the region and base-biome streams for that column | array read | No | No | `OptionalInt.empty()` |
| `surfaceKind` | one height sample, plus one surface-biome sample **only** for columns above fluid level | array read | No | No | `IrisSurfaceKind.UNKNOWN` |
| `surfaceBiomeKey` / `surfaceBiomeName` | one surface-biome sample, which pulls height, base biome and region | array read | No | No | `Optional.empty()` |
| `biomeKey` at or near the surface | as `surfaceBiomeKey`, plus one height sample to decide surface vs cave | array read | No | No | `Optional.empty()` |
| `biomeKey` well below the surface | the above, plus the cave-biome stream and the dimension's carving resolution | array reads | No | No | `Optional.empty()` |
| `regionKey` / `regionName` | one region sample — the cheapest of the biome family | array read | No | No | `Optional.empty()` |
| `maxSampleColumns` / `maxSampleChunks` | reads two settings fields | same | No | No | a positive number, always |
| `sampleColumns` | one of the above per column, in chunk-local order | array reads | No | No | `false`, sink untouched |

Two consequences that matter more than the per-call cost:

**Calling in a tight main-thread loop is survivable but wasteful.** Nothing will deadlock and
nothing will stall on I/O. What you will do is evict the generator's own working set: the noise
cache is shared with live chunk generation, and a scan across unrelated coordinates pushes out the
columns the generator was about to reuse. The visible symptom is chunk generation slowing down, not
your loop slowing down. Use `sampleColumns` for anything wider than a handful of columns — it walks
chunk by chunk so each cached chunk is filled and finished with before the next one starts.

**These values are the generator's opinion, not the world's.** `surfaceHeight` is the height of the
generated terrain column. It does not include objects, decorations, structures, trees, snow, or
anything a player has placed or broken since. In an already-generated world the block at that Y may
be different, and in a world that has never generated there you still get an answer, because the
answer comes from noise and not from storage. If you need the real block, use Bukkit's
`World#getHighestBlockYAt` and accept its chunk-loading cost. If you need to know where the pack
*intends* the ground to be — which is the useful question for a pregeneration planner, a map
renderer or a spawn picker — use this.

### Surface height, precisely

`surfaceHeight` returns the absolute Y of the **topmost generated terrain block**. A player stands
at `surfaceHeight + 1`. Fluid is ignored: under an ocean you get the sea floor, not the water
surface. Compare against `IrisWorldInfo.fluidHeight()` to tell the difference, or use
`surfaceKind`, which does exactly that comparison for you.

---

## Threading

**Every read on this interface may be called from any thread, including an async task.** That is an
unusual claim in a Folia-aware suite and it is made deliberately, so here is the justification:

- The only Bukkit call Iris makes on your behalf is `World#getGenerator()`, an accessor on the world
  object itself. No chunk is touched, no block state is read, no entity is looked at, no world list
  is walked.
- Everything after that is engine-internal noise evaluation over concurrent caches. There is no
  region-owned state involved, so there is no region thread with a claim on it.
- No method here takes a lock you can contend on, calls `join`, or schedules onto another thread.

There is nothing to gain from hopping to a region thread first, and on Folia there is no region
thread that would be the *correct* one for a coordinate scan spanning many regions anyway. Run wide
scans on your own async executor.

The one rule: **`IrisColumnSink.accept` runs on the thread that called `sampleColumns`, inline,
once per column.** If you called from an async thread, your sink is on that async thread and must
not touch Bukkit state. If you called from a region thread, your sink is holding that region thread
for the entire walk. Collect into a local structure inside the sink and do the Bukkit work
afterwards.

---

## Column sampling

`sampleColumns` is the bulk read. It walks a rectangle at a stride, chunk by chunk, and pushes each
column into your sink.

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

The bounds are **inclusive on both ends**. The sampled lattice is anchored at
`(minBlockX, minBlockZ)` and steps by `strideBlocks`; a stride of `1` visits every column.

The constructor rejects, with `IllegalArgumentException`:

- an empty `fields` set,
- `maxBlockX < minBlockX` or `maxBlockZ < minBlockZ`,
- `strideBlocks < 1`.

`fields` is defensively copied on the way in and on every call to `fields()`, so a set you mutate
after construction does not change the query, and a set you get back and mutate does not either.
`fields()` allocates a fresh `EnumSet` each call — hoist it out of loops.

`columnCount()` and `chunkCount()` saturate at `Long.MAX_VALUE` instead of overflowing, so a query
over the whole coordinate space reports an absurd number rather than a negative one.

### The hard limits

```java
int maxSampleColumns();
int maxSampleChunks();
```

Both are derived from the generator's noise cache size, so a large-cache server permits larger
queries and a small-cache server permits smaller ones. The rule is fixed:

```
maxSampleChunks  = max(64, noiseCacheSize / 4)
maxSampleColumns = maxSampleChunks * 256
```

With the default `noiseCacheSize` of 1024 that is **256 chunks and 65 536 columns**. The divisor of
four is the point of the whole mechanism: one API query may never consume more than a quarter of the
cache the live generator is using.

**A query that exceeds either limit returns `false` and never calls your sink — not once.** There is
no partial answer, no truncation, no exception, and no log line. If you get `false` before any
column arrives, check the counts.

The two limits are checked independently, and this is where callers get caught:

```java
IrisColumnQuery wide = IrisColumnQuery.rect(
        0, 0, 6399, 6399, 64, EnumSet.of(IrisColumnField.SURFACE_KIND));
```

That query reports `columnCount() == 10_000`, well under the 65 536 column limit, and
`chunkCount() == 160_000`, far over the 256 chunk limit. It is refused.

**`chunkCount()` counts the chunk span of the rectangle, not the chunks you actually sample.**
Striding does not reduce it. A coarse sweep across a large area is refused on chunks even though it
touches very few columns. Split it into tiles, or accept a smaller rectangle:

```java
long maxColumns = terrain.maxSampleColumns();
long maxChunks = terrain.maxSampleChunks();

if (query.columnCount() > maxColumns || query.chunkCount() > maxChunks) {
    return;
}
```

Ask the service every time. Both values change when an operator edits the setting and reloads.

### The sink

```java
@FunctionalInterface
public interface IrisColumnSink {
    void accept(int blockX, int blockZ, int surfaceHeight, IrisSurfaceKind kind, String biomeKey);
}
```

Every column produces exactly one `accept`. What arrives depends on the `fields` you asked for, and
the placeholders for fields you did **not** ask for are not distinguishable from real data:

| Field requested | Parameter | If you asked for it | If you did not |
|---|---|---|---|
| `SURFACE_HEIGHT` | `surfaceHeight` | absolute world Y of the topmost terrain block | `-1` |
| `SURFACE_KIND` | `kind` | `LAND`, `SHORE`, `OCEAN` or `VOID` | `IrisSurfaceKind.UNKNOWN` |
| `BIOME_KEY` | `biomeKey` | the biome load key | `null` |

`-1` is a legal absolute Y in any world with a negative minimum height, so **never treat `-1` as
"absent"**. Branch on your own field set, which you already have. `biomeKey` may also be `null` when
you *did* ask for it, if the column resolves to no biome; test for `null` regardless.

Requesting fewer fields genuinely costs less. `SURFACE_KIND` alone does not evaluate the biome
stream for a column that is at or below fluid level, because the classification is already decided.
Ask for `BIOME_KEY` and every column pays for the biome stream.

### Visit order

Columns arrive grouped by chunk. The walk iterates chunks with Z as the outer loop and X as the
inner loop, and within each chunk iterates its lattice points the same way, Z outer and X inner.
Order is deterministic for a given query, but it is **not** a row-major sweep of the rectangle: you
receive all of one chunk's columns before any of the next chunk's. If your consumer needs raster
order, sort afterwards or index into an array by `(blockX, blockZ)`.

### The return value

`sampleColumns` returns `true` if and only if every column in the query was delivered. It returns
`false` when:

- `world`, `query` or `sink` is `null`, or the world has no live Iris engine — sink untouched;
- a limit was exceeded — sink untouched;
- **your sink threw** — the walk stops at that column;
- **the engine closed underneath the walk** — the walk stops at that column.

In the last two cases the columns already delivered were delivered. `false` does not mean "nothing
happened"; it means "do not trust this result set as complete". Treat a `false` as a signal to
discard the partial data, not as a signal that there is none.

---

## Worked example: finding the flattest buildable spot

A plugin that places a settlement wants the flattest patch of land inside a radius, and wants none
of that work on a region thread. It samples on an async task, then hands the answer to the player's
entity scheduler, which is the correct thread to touch a player on Folia and on Paper alike.

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

`Best` needs no synchronisation: the sink runs inline on the thread that called `sampleColumns`, so
every `accept` for this walk is on the background thread that started it, and no other thread reads
the holder until the walk has returned. The `+ 1` on the reported Y is the standing height, since
`surfaceHeight` is the topmost solid block. `player.getScheduler()` is Paper's entity scheduler and
is the correct hop on both Paper and Folia; on Folia it resumes on whichever region owns the player
at that moment, which may not be the region they were in when the scan started.

---

## The minimum: one coordinate

Most integrations want one biome name at one place. That is three lines and needs none of the above.

```java
IrisTerrainService terrain = service();

String biome = terrain == null
        ? "unknown"
        : terrain.surfaceBiomeName(player.getWorld(), player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()).orElse("unknown");
```

`surfaceBiomeName` on a non-Iris world, a null world, a closing engine or a disabled Iris returns
`Optional.empty()`. You do not need to call `isIrisWorld` first unless you want to distinguish
"not an Iris world" from "Iris has nothing to say".

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

| Component | What it is |
|---|---|
| `dimensionKey` | Pack load key of the dimension, for example `overworld` |
| `worldIdentity` | The world's namespaced key rendered as a string, for example `minecraft:overworld` |
| `seed` | The raw seed the engine was built with |
| `minHeight` | Absolute Y of the world floor, for example `-64` |
| `maxHeight` | Absolute Y of the world ceiling, exclusive, for example `320` |
| `fluidHeight` | Absolute Y of the pack's sea level |
| `studio` | `true` only for a transient studio world |
| `height()` | `maxHeight - minHeight` |

`minHeight`, `maxHeight` and `fluidHeight` are all absolute world Y, directly comparable with
`surfaceHeight` and with `blockY`. The record's own constructor rejects a null `dimensionKey` or
`worldIdentity` with `NullPointerException` and a non-positive height range with
`IllegalArgumentException`, so an instance you receive is always internally consistent.

`worldIdentity` is the string form of the world's `NamespacedKey`, and it is the key Iris itself
persists per-world state under. It is the right key for you to persist too, because it is namespaced
and unambiguous where a bare name is not. It is **not** independent of the world's name: outside the
three vanilla dimensions the server derives the key from the world folder, so renaming that folder
changes `worldIdentity` exactly as it changes `World#getName()`.

`studio` is `true` for a world Iris created for pack authoring — those exist for seconds and are
deleted, so a persistence layer should skip them.

`seed` is the generator seed. Treat it as privileged: it is enough to reproduce the entire world
offline, including every ore vein and structure. Iris deliberately does not expose it through
PlaceholderAPI for that reason. Do not put it anywhere a player can read.

---

## Failure policy

Iris assumes the caller will pass nulls, hand it a world it does not own, keep a stale service
reference, and throw from a sink.

| Situation | What Iris does |
|---|---|
| `world` is `null` | Every query answers absent; `sampleColumns` returns `false` |
| The world has no Iris generator | Same |
| Iris is disabled, or disabled between your two calls | Same. Nothing throws |
| The generator is closing, or the engine is closed | `isIrisWorld` still returns **`true`**; every other query answers absent |
| A query throws inside the engine | Counted, logged with the stack trace, answered as absent |
| `query` or `sink` is `null` | `sampleColumns` returns `false`, sink never called |
| The query exceeds `maxSampleColumns` or `maxSampleChunks` | `sampleColumns` returns `false`, sink never called, nothing logged |
| Your sink throws | Walk aborts at that column, fault counted and logged, `sampleColumns` returns `false`. Columns already delivered stay delivered |
| The engine closes mid-walk | Walk stops at that column, `sampleColumns` returns `false` |

Two deliberate asymmetries worth internalising:

**`isIrisWorld` does not check liveness.** It answers "was this world created by Iris", not "can
Iris answer questions about it right now". During world unload and during plugin shutdown you will
see `isIrisWorld(world) == true` alongside `worldInfo(world).isEmpty()`. That is correct behaviour,
not a race you can win. Code that branches on `isIrisWorld` and then dereferences an
`Optional#get()` will throw eventually; use `orElse` or check the `Optional`.

**Iris never quarantines a caller.** There is no fault limit and no disable-after-N. A sink that
throws on every column will be logged and refused on every call, forever, and will never be muted or
blacklisted. The internal fault counters exist only to throttle the log line to at most one report
per minute per category — the count in that line tells you how many faults have occurred in total,
so a "3 faults" line followed by a "9000 faults" line means you have a loop, not two incidents.

Nothing in this API ever throws a checked exception, and nothing throws an unchecked one except the
argument validation on `IrisColumnQuery` and `IrisWorldInfo` constructors described above.

---

## Configuration

`plugins/Iris/settings.json`:

| Key | Default | Effect on this API |
|---|---|---|
| `performance.noiseCacheSize` | `1024` | The chunk capacity of the shared noise cache. `maxSampleChunks` is `max(64, this / 4)` and `maxSampleColumns` is `maxSampleChunks * 256`. Raising it raises both limits and the memory the generator holds |

There is no on/off switch for the terrain API and no per-world gate. It answers for every world with
a live Iris engine, and answers absent for everything else.

---

## Enum reference

### `IrisSurfaceKind`

Returned by `surfaceKind` and delivered to `IrisColumnSink`.

| Constant | Meaning | Test Iris applies |
|---|---|---|
| `LAND` | Dry ground | Surface above sea level, and the biome is not a shore biome |
| `SHORE` | Beach or bank | Surface above sea level, and the pack classifies the biome as shore |
| `OCEAN` | Under water | Surface at or below `IrisWorldInfo.fluidHeight()` |
| `VOID` | Nothing generated | Surface at or below `IrisWorldInfo.minHeight()` |
| `UNKNOWN` | No answer | Not an Iris world, the engine is unavailable, a query faulted, or `SURFACE_KIND` was not requested |

**`VOID` is tested first and wins.** A column at or below `minHeight()` reports `VOID` whatever the
sea level is; only a column above the floor is then tested against the fluid level, and only a column
above the fluid level is then tested for a shore biome. The four are mutually exclusive.

`OCEAN` is inclusive at the boundary: a column whose topmost terrain block sits exactly at sea level
reports `OCEAN` even though no water block is generated above it. If that one-block distinction
matters, compare `surfaceHeight` against `fluidHeight` yourself.

`UNKNOWN` is overloaded on purpose — it is the single "no data" value, so a caller never has to
handle both a sentinel and an exception. Distinguish the causes with `isIrisWorld` and `worldInfo`
if you need to.

### `IrisColumnField`

Selects what `sampleColumns` computes and passes to the sink. At least one is required.

| Constant | Fills | Extra work |
|---|---|---|
| `SURFACE_HEIGHT` | the `surfaceHeight` parameter | one height sample per column |
| `SURFACE_KIND` | the `kind` parameter | one height sample, plus a biome sample only for columns above sea level |
| `BIOME_KEY` | the `biomeKey` parameter | one biome sample per column, unconditionally |

`SURFACE_HEIGHT` and `SURFACE_KIND` share their height sample — asking for both costs barely more
than asking for either.

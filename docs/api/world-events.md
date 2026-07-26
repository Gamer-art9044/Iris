# Iris world engine and pregeneration events

Two Bukkit events tell you what Iris is doing over time. `IrisWorldEngineEvent` marks the points at
which an Iris world's engine becomes usable, is rebuilt under you, or is about to stop being usable.
`IrisPregenerationEvent` reports the progress of a pregeneration job. Both are pure observation:
neither is cancellable, and nothing you do in a handler changes what Iris does next.

Use `IrisWorldEngineEvent` instead of `WorldLoadEvent` if you care about the *generator* rather than
the world. A world exists before its Iris engine is ready to answer questions, and it still exists
after the engine has been told to close.

---

## Depending on Iris

See [README.md](README.md#depending-on-iris) for the build and plugin-descriptor setup. Events need
no service lookup — register a `Listener` in your `onEnable` as usual and Bukkit unregisters you
when your plugin disables.

Both events have their own `HandlerList`. There is no shared base class and no common interface;
`IrisWorldEngineEvent` and `IrisPregenerationEvent` extend `org.bukkit.event.Event` directly.

Neither implements `Cancellable`. `ignoreCancelled = true` on a handler for either is meaningless
and will not do what you expect.

---

## The world engine lifecycle

```java
public enum IrisWorldPhase {
    ENGINE_READY,
    ENGINE_HOTLOADED,
    ENGINE_CLOSING
}
```

```
ENGINE_READY        the engine for this world is registered and answering.
   |                Terrain queries work from here on.
   |
   +--> ENGINE_HOTLOADED   the pack was edited and the engine rebuilt in place.
   |                       Same world, same engine object, different pack contents.
   |                       Can fire any number of times, or never.
   |
   v
ENGINE_CLOSING      the engine is about to be torn down. Last call.
```

Guarantees Iris makes:

- `ENGINE_READY` fires **at most once per world** for a given registration. It is keyed on the
  world's UUID, so a world that unloads and loads again gets a fresh `ENGINE_READY`.
- `ENGINE_CLOSING` is **never delivered without a preceding `ENGINE_READY`** for that world. If Iris
  never announced a world ready, it never announces it closing.
- `ENGINE_CLOSING` is dispatched **before** Iris starts closing the generator, not after. When your
  handler runs, the engine has not been shut down yet.
- If Iris replaces a world's engine — the generator was swapped out and a new one registered — you
  get `ENGINE_CLOSING` for the old one, and a later `ENGINE_READY` when the replacement finishes
  registering. You never get two consecutive `ENGINE_READY` without a `CLOSING` between them.
- On Iris shutdown, **every** world that was announced ready is announced closing, before Iris drains
  its worker pool and before any generator is closed.
- `ENGINE_HOTLOADED` is not deduplicated and does not participate in the ready/closing pairing. It
  is a notification that the pack data behind a live engine was reloaded and the engine rebuilt
  around it. The world, the world object and the seed are unchanged; the pack contents may not be.
  Treat any pack-derived value you cached at `ENGINE_READY` as stale when it arrives.

### The one thing `ENGINE_CLOSING` does not promise

`ENGINE_CLOSING` is fired before the *generator* closes, but during a full plugin shutdown the
terrain service may already have been withdrawn by the time your handler runs — Iris tears down its
services in an unspecified order. So:

> Do not treat `ENGINE_CLOSING` as a window in which to run terrain queries. Capture whatever you
> need at `ENGINE_READY` and use `ENGINE_CLOSING` only to drop it.

A terrain query in a closing handler does not throw. It returns absent, which is worse, because it
looks like data.

---

## The event

```java
public class IrisWorldEngineEvent extends Event {
    public IrisWorldEngineEvent(World world, IrisWorldPhase phase, IrisWorldInfo info);

    public static HandlerList getHandlerList();

    public World getWorld();

    public IrisWorldPhase getPhase();

    public Optional<IrisWorldInfo> getInfo();

    @Override
    public HandlerList getHandlers();
}
```

`getWorld()` and `getPhase()` are never `null` — the constructor rejects both.

`getInfo()` is `Optional` and can be empty. It is empty when Iris could not describe the engine at
dispatch time: the generator was already closing, the engine was already closed, or building the
description threw (which is logged with a stack trace, and does not suppress the event). Handle the
empty case; do not call `get()` unconditionally.

`IrisWorldInfo` is documented in [terrain.md](terrain.md#what-irisworldinfo-tells-you). The short
version is that it carries the dimension load key, the world's namespaced identity, the seed, the
world height bounds, the pack's sea level, and whether this is a transient studio world.

### Threading

**Handlers always run on the main thread. On Folia, that is the global region thread.**

Iris raises these phases from several places — the world load and unload handlers, its own enable
and disable, and a pack hotload that can originate from a file watcher thread. The dispatch
normalises all of them:

- Raised from the primary thread: the event is called **inline**, before the raising code continues.
  A `WorldLoadEvent` handler of yours that registers state, and an `ENGINE_READY` handler that reads
  it, will see a consistent picture.
- Raised from any other thread: the event is handed to the server scheduler and delivered on the
  main or global region thread on a later tick.

So your handler is always on a thread where touching Bukkit is legal, and never on the file-watcher
or worker thread that caused the phase.

What is forbidden: blocking. These phases run on the thread the server ticks on. No I/O, no
`CompletableFuture#join`, no waiting on another scheduler. If you need to persist something, hand it
to your own executor.

---

## Worked example: caching pack metadata per world

A plugin that shows the dimension a player is in wants that string without asking Iris for it on
every render. It captures it once when the engine is ready and drops it when the engine closes.

```java
package com.example.hud;

import art.arcane.iris.api.terrain.IrisWorldInfo;
import art.arcane.iris.api.world.IrisWorldEngineEvent;
import art.arcane.iris.api.world.IrisWorldPhase;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisWorldRegistry implements Listener {
    private final Map<UUID, String> dimensionKeys = new ConcurrentHashMap<>();

    public String dimensionKeyOf(World world) {
        return dimensionKeys.get(world.getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEngine(IrisWorldEngineEvent event) {
        UUID worldId = event.getWorld().getUID();

        switch (event.getPhase()) {
            case ENGINE_READY, ENGINE_HOTLOADED -> {
                Optional<IrisWorldInfo> info = event.getInfo();

                if (info.isEmpty()) {
                    dimensionKeys.remove(worldId);
                    return;
                }

                dimensionKeys.put(worldId, info.get().dimensionKey());
            }
            case ENGINE_CLOSING -> dimensionKeys.remove(worldId);
            default -> {
            }
        }
    }
}
```

`ENGINE_HOTLOADED` is handled alongside `ENGINE_READY` because a hotload can change the pack's
dimension key. The `default` arm is there because the enum can grow; see
[README.md](README.md#switching-over-the-enums).

The map is a `ConcurrentHashMap` even though the handler is single-threaded, because
`dimensionKeyOf` is read from wherever your HUD renders.

---

## Pregeneration

```java
public enum IrisPregenPhase {
    STARTED,
    TICK,
    PAUSED,
    RESUMED,
    SAVING,
    COMPLETED,
    CANCELLED
}
```

```java
public class IrisPregenerationEvent extends Event {
    public IrisPregenerationEvent(IrisPregenPhase phase, IrisPregenProgress progress);

    public static HandlerList getHandlerList();

    public IrisPregenPhase getPhase();

    public IrisPregenProgress getProgress();

    @Override
    public HandlerList getHandlers();
}
```

Both accessors are never `null`; the constructor rejects both.

### The order phases arrive in

```
STARTED  ->  TICK  ->  TICK  ->  ...  ->  COMPLETED
                        |
                        +-- PAUSED  ->  TICK  ->  ...  ->  RESUMED  ->  TICK  ->  ...
                        |
                        +-- SAVING (once, near the end)
                        |
                        +-- CANCELLED (instead of COMPLETED, if the job was stopped early)
```

- **One job at a time, server-wide.** Iris runs a single pregeneration job per server. There is no
  job identifier on the event because there is nothing to disambiguate; `IrisPregenProgress` names
  the world the running job is working on.
- `STARTED` is dispatched exactly once per job, immediately before that job's first `TICK`, in that
  order.
- `TICK` fires **once per second** while the job runs. It fires while paused too.
- `PAUSED` and `RESUMED` fire on the transition only, each immediately followed by a `TICK`. A job
  that is never paused never emits either.
- `SAVING` fires at most once per job.
- Exactly one of `COMPLETED` or `CANCELLED` is dispatched, and it is terminal. `COMPLETED` means the
  job reached its chunk total; `CANCELLED` means it stopped before that, whether by operator action
  or by shutdown. **No phase is ever dispatched for a job after its terminal phase.**

### Threading

**Handlers always run on the main thread. On Folia, that is the global region thread.**

The pregenerator ticks on its own worker thread, so every pregeneration phase is scheduled rather
than called inline. It arrives on a later tick than the moment the numbers were sampled. For a
progress bar this is invisible; for anything that correlates pregeneration against another timeline,
assume up to one tick of skew.

Do not block. The job does not wait for your handler — the dispatch is fire-and-forget and a
throwing handler is logged and skipped — but you are on the server's tick thread and everything else
does wait for you.

### What `IrisPregenProgress` tells you

```java
public record IrisPregenProgress(
        String worldName,
        String worldIdentity,
        double percent,
        long generatedChunks,
        long totalChunks,
        long remainingChunks,
        long failedChunks,
        double chunksPerSecond,
        long etaMillis,
        long elapsedMillis,
        String method,
        boolean paused) {
}
```

| Component | What it is |
|---|---|
| `worldName` | Never null; falls back to `worldIdentity` |
| `worldIdentity` | The world's namespaced key rendered as a string |
| `percent` | `0.0` to `100.0` |
| `generatedChunks` | Chunks the job has finished |
| `totalChunks` | Chunks in the job |
| `remainingChunks` | Chunks still to do |
| `failedChunks` | Chunks the job could not generate |
| `chunksPerSecond` | Current rate |
| `etaMillis` | Estimated milliseconds remaining |
| `elapsedMillis` | Milliseconds since the job started |
| `method` | Never null; `""` when unknown |
| `paused` | `true` while the job is paused |

The record's constructor sanitises everything before you see it, so you never have to defend against
the generator's arithmetic:

- `percent` is clamped to `0.0 .. 100.0`. `NaN` and infinity become `0.0`.
- `chunksPerSecond` is clamped to at least `0.0`. `NaN` and infinity become `0.0`.
- `generatedChunks`, `totalChunks`, `remainingChunks`, `failedChunks`, `etaMillis` and
  `elapsedMillis` are clamped to at least `0`.
- `worldName` falls back to `worldIdentity` when the world has no name.
- `method` becomes `""` rather than `null`.

The only rejection is a `null` `worldIdentity`, which throws `NullPointerException` at construction —
so an instance delivered to you always identifies a world.

`etaMillis` is an estimate derived from the running rate and is `0` before enough chunks have
completed to compute one. `failedChunks` counts chunks the job could not generate; a non-zero value
on `COMPLETED` means the job finished with holes.

---

## Worked example: mirroring pregeneration into a boss bar

```java
package com.example.pregenbar;

import art.arcane.iris.api.pregen.IrisPregenProgress;
import art.arcane.iris.api.pregen.IrisPregenerationEvent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class PregenBar implements Listener {
    private BossBar bar;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPregen(IrisPregenerationEvent event) {
        IrisPregenProgress progress = event.getProgress();

        switch (event.getPhase()) {
            case STARTED -> open(progress);
            case TICK, PAUSED, RESUMED, SAVING -> update(progress);
            case COMPLETED, CANCELLED -> close();
            default -> {
            }
        }
    }

    private void open(IrisPregenProgress progress) {
        close();
        bar = Bukkit.createBossBar(
                "Pregenerating " + progress.worldName(), BarColor.BLUE, BarStyle.SEGMENTED_10);

        for (Player player : Bukkit.getOnlinePlayers()) {
            bar.addPlayer(player);
        }

        update(progress);
    }

    private void update(IrisPregenProgress progress) {
        if (bar == null) {
            return;
        }

        bar.setProgress(progress.percent() / 100.0D);
        bar.setColor(progress.paused() ? BarColor.YELLOW : BarColor.BLUE);
        bar.setTitle(progress.worldName()
                + " " + progress.generatedChunks() + "/" + progress.totalChunks()
                + " at " + Math.round(progress.chunksPerSecond()) + "/s");
    }

    private void close() {
        if (bar == null) {
            return;
        }

        bar.removeAll();
        bar = null;
    }
}
```

`bar` needs no synchronisation: every phase is delivered on the same thread.

`percent()` is already clamped, so dividing by 100 always yields a legal boss-bar progress value.

---

## The minimum: knowing a world is usable

If all you want is "run this once, when Iris can answer for this world":

```java
@EventHandler
public void onEngine(IrisWorldEngineEvent event) {
    if (event.getPhase() == IrisWorldPhase.ENGINE_READY) {
        prepare(event.getWorld());
    }
}
```

No `switch`, no `Optional`, no service lookup. Do not add `ignoreCancelled = true`; the event is not
cancellable.

---

## Failure policy

| Situation | What Iris does |
|---|---|
| Your handler throws | Logged with the stack trace. The remaining handlers still run, and Iris's own lifecycle continues unaffected |
| Iris cannot describe a world for a phase | The failure is logged and the event is **still delivered**, with `getInfo()` empty |
| The event dispatch itself throws | Logged, naming the phase and world. The engine registration or teardown that raised it proceeds |
| The pregeneration sink is not registered | No `IrisPregenerationEvent` is fired at all. This is the state before Iris finishes enabling and after it starts disabling |
| A pregeneration handler throws | Logged, naming the phase. The job is not slowed, paused or stopped |
| Iris shuts down mid-pregeneration | The job's terminal phase is `CANCELLED` |
| Iris shuts down with worlds registered | Every announced world receives `ENGINE_CLOSING` before the worker pool drains |

Iris does not quarantine a listener. A handler that throws on every event will be logged on every
event, forever. There is no fault limit and no automatic unregistration.

Iris never suppresses a lifecycle phase because a third party misbehaved. A logged failure is always
accompanied by delivery, or by the lifecycle step proceeding without delivery — never by a silent
stall.

---

## Configuration

There are no configuration keys for either event. They are always on when Iris is enabled, cannot be
disabled, and have no per-world gate.

---

## Enum reference

### `IrisWorldPhase`

| Constant | Meaning | Fires |
|---|---|---|
| `ENGINE_READY` | The engine is registered and answering queries | Once per world registration |
| `ENGINE_HOTLOADED` | A live engine's pack data was reloaded in place | Any number of times, or never. It is dispatched straight from the hotload, not through the ready/closing bookkeeping, so it is not paired with either |
| `ENGINE_CLOSING` | The engine is about to be torn down | Once per world registration, always after a `READY` |

### `IrisPregenPhase`

| Constant | Meaning | Fires |
|---|---|---|
| `STARTED` | A job began | Once per job, immediately before its first `TICK` |
| `TICK` | Periodic progress sample | Once per second while the job exists, including while paused |
| `PAUSED` | The job was paused | On the transition only, followed by a `TICK` |
| `RESUMED` | The job was resumed | On the transition only, followed by a `TICK` |
| `SAVING` | The job is flushing to disk | At most once per job |
| `COMPLETED` | The job reached its chunk total | Terminal; mutually exclusive with `CANCELLED` |
| `CANCELLED` | The job stopped before its total | Terminal; mutually exclusive with `COMPLETED` |

Write a `default` arm when switching over either; see
[README.md](README.md#switching-over-the-enums).

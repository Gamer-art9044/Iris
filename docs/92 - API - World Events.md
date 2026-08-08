# 92 - API - World Events

`IrisWorldEngineEvent` reports when an Iris world's engine becomes usable, is rebuilt under you, or is about to stop being usable. `IrisPregenerationEvent` reports pregeneration job progress. Both are pure observation: not cancellable, and handlers cannot change Iris's next step. Prefer `IrisWorldEngineEvent` over `WorldLoadEvent` when you care about the **generator**: a world exists before its Iris engine can answer, and still exists after the engine is told to close.

Build setup: `90 - API - Getting Started.md`. No service lookup — register a `Listener` in `onEnable`; Bukkit unregisters on your disable.

Each event has its own `HandlerList`. No shared base class. Neither implements `Cancellable`; `ignoreCancelled = true` does nothing useful.

---

## World engine lifecycle

```java
public enum IrisWorldPhase {
    ENGINE_READY,
    ENGINE_HOTLOADED,
    ENGINE_CLOSING
}
```

```
ENGINE_READY        engine registered and answering; terrain queries work from here
   |
   +--> ENGINE_HOTLOADED   pack reloaded; same world/engine object, pack contents may change
   |                       any number of times, or never
   v
ENGINE_CLOSING      engine about to tear down; last call
```

Guarantees:

- `ENGINE_READY` fires **at most once per world registration**, keyed on world UUID. Unload + load again gets a new ready.
- `ENGINE_CLOSING` is **never delivered without a prior `ENGINE_READY`** for that world (ledger-gated).
- `ENGINE_CLOSING` is dispatched **before** Iris starts closing the generator.
- Engine replacement: `ENGINE_CLOSING` for the old, later `ENGINE_READY` for the new — never two consecutive ready without closing between them.
- On Iris shutdown, every announced-ready world gets closing before worker pool drain and generator close.
- `ENGINE_HOTLOADED` is not deduplicated and is not part of ready/closing pairing. Treat pack-derived caches from `ENGINE_READY` as stale when it arrives.

### What `ENGINE_CLOSING` does not promise

Closing fires before the generator closes, but during full plugin shutdown the terrain service may already be withdrawn. **Do not run terrain queries in a closing handler.** Capture state at `ENGINE_READY`; use closing only to drop it. Queries during closing return absent without throwing.

---

## The world engine event

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

`getWorld()` and `getPhase()` are never null (constructor rejects nulls).

`getInfo()` may be empty if Iris could not describe the engine at dispatch (generator already closing, engine closed, or describe threw — logged; event still delivered). Do not call `Optional#get()` unconditionally.

`IrisWorldInfo` fields: `91 - API - Terrain.md`.

### Threading

**Handlers always run on the main thread. On Folia, that is the global region thread.**

Dispatch:

- Raised on the primary thread: event called **inline** before the raiser continues.
- Raised off-thread (e.g. file-watcher hotload): scheduled to main/global region on a later tick via Iris's event path.

Blocking is forbidden on this thread: no I/O, no `CompletableFuture#join`, no waiting on another scheduler.

---

## Worked example: cache pack metadata per world

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

`default` is required because enums can grow: `90 - API - Getting Started.md`. Map is concurrent because readers may be off the event thread.

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

Both accessors never null; constructor rejects nulls.

### Phase order

```
STARTED  ->  TICK  ->  TICK  ->  ...  ->  COMPLETED
                        |
                        +-- PAUSED  ->  TICK  ->  ...  ->  RESUMED  ->  TICK  ->  ...
                        |
                        +-- SAVING (at most once, near end)
                        |
                        +-- CANCELLED (instead of COMPLETED if stopped early)
```

- **One job at a time, server-wide.** No job id on the event; `IrisPregenProgress` names the world.
- `STARTED` once per job, immediately before first `TICK`.
- `TICK` once per second while the job exists, including while paused.
- `PAUSED` / `RESUMED` on transition only, each followed by a `TICK`.
- `SAVING` at most once per job.
- Exactly one of `COMPLETED` or `CANCELLED` is terminal. No phase after the terminal.

### Threading

**Handlers always run on the main / Folia global region thread.** Pregen ticks on a worker; phases are scheduled (up to about one tick of skew). Fire-and-forget: a throwing handler is logged and skipped; the job does not wait. Do not block the tick thread.

### `IrisPregenProgress`

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

| Component | Meaning |
|---|---|
| `worldName` | Never null; falls back to `worldIdentity` |
| `worldIdentity` | World's namespaced key string |
| `percent` | `0.0` .. `100.0` |
| `generatedChunks` | Finished chunks |
| `totalChunks` | Job total |
| `remainingChunks` | Still to do |
| `failedChunks` | Could not generate |
| `chunksPerSecond` | Current rate |
| `etaMillis` | Estimated remaining ms |
| `elapsedMillis` | Since job start |
| `method` | Never null; `""` if unknown |
| `paused` | Job paused |

Constructor sanitises:

- `percent` clamped to `0..100`; non-finite → `0`
- `chunksPerSecond` ≥ 0; non-finite → `0`
- chunk and time counters ≥ 0
- null `worldName` → `worldIdentity`; null `method` → `""`
- null `worldIdentity` throws `NullPointerException` at construction — delivered instances always identify a world

`etaMillis` is `0` until enough chunks complete for an estimate. Non-zero `failedChunks` on `COMPLETED` means holes remain.

Operator pregen surface: `07 - Pregeneration.md`.

---

## Worked example: boss bar

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

---

## The minimum: world usable once

```java
@EventHandler
public void onEngine(IrisWorldEngineEvent event) {
    if (event.getPhase() == IrisWorldPhase.ENGINE_READY) {
        prepare(event.getWorld());
    }
}
```

Do not set `ignoreCancelled = true`.

---

## Failure policy

| Situation | Behaviour |
|---|---|
| Your handler throws | Logged; remaining handlers run; Iris lifecycle continues |
| Iris cannot describe world for a phase | Logged; event still delivered with empty `getInfo()` |
| Event dispatch itself throws | Logged with phase and world; registration/teardown proceeds |
| Pregen sink not registered | No `IrisPregenerationEvent` (before enable completes / after disable starts) |
| Pregen handler throws | Logged; job not slowed/paused/stopped |
| Iris shuts down mid-pregen | Terminal phase `CANCELLED` |
| Iris shuts down with worlds registered | Every announced world gets `ENGINE_CLOSING` before worker drain |

No listener quarantine. Iris never silently stalls a lifecycle step because a third party failed.

---

## Configuration

No configuration keys. Events are always on while Iris is enabled; no per-world gate.

---

## Enum reference

### `IrisWorldPhase`

| Constant | Meaning | Fires |
|---|---|---|
| `ENGINE_READY` | Engine registered and answering | Once per world registration |
| `ENGINE_HOTLOADED` | Pack data reloaded in place | Any number of times, or never; not ledger-paired |
| `ENGINE_CLOSING` | Engine about to tear down | Once per registration, always after a ready |

### `IrisPregenPhase`

| Constant | Meaning | Fires |
|---|---|---|
| `STARTED` | Job began | Once, before first `TICK` |
| `TICK` | Progress sample | Once per second while job exists |
| `PAUSED` | Job paused | Transition only + following `TICK` |
| `RESUMED` | Job resumed | Transition only + following `TICK` |
| `SAVING` | Flushing to disk | At most once |
| `COMPLETED` | Reached chunk total | Terminal; exclusive with `CANCELLED` |
| `CANCELLED` | Stopped before total | Terminal; exclusive with `COMPLETED` |

Default arms: `90 - API - Getting Started.md`.

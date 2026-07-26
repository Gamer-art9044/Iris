# Iris tree feller API

`art.arcane.iris.api.tree` lets another plugin **drive** the Iris tree feller and **charge** for it.
The feller removes a whole Iris-generated tree, block by block, when a sneaking survival player
breaks one of its logs with an axe. This API lets you turn it on for a player who would not
otherwise be allowed it, override the durability rules, and take something from that player for each
log removed — with a reservation you can get back if the log turns out not to be removable.

There are two things you can do, and they are independent:

| You want to… | Use |
|---|---|
| start a felling run that Iris would not have started, or price it | `IrisTreeFellerService#tryFell` with `TreeFellerOptions.integrationOverride(...)` |
| avoid double-handling the block breaks Iris generates while felling | `IrisTreeFellerService#isManagedBreak` |
| ask whether a block belongs to an Iris tree at all | `IrisTreeFellerService#isTreeBlock` |

**The tree feller is off by default.** `treeFeller.enabled` in Iris's settings is `false` out of the
box, and the standalone path additionally requires the `iris.treefeller` permission. An
`INTEGRATION_OVERRIDE` request bypasses **both** — that is what the mode is for, and it means your
plugin is now the thing that decides who may fell trees.

---

## Depending on Iris and acquiring the service

See [README.md](README.md#depending-on-iris) for the build and plugin-descriptor setup. The service
is registered with the Bukkit `ServicesManager` at `ServicePriority.Normal`.

```java
package com.example.woodcutting;

import art.arcane.iris.api.tree.IrisTreeFellerService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class FellerAccess {
    private FellerAccess() {
    }

    public static IrisTreeFellerService service() {
        RegisteredServiceProvider<IrisTreeFellerService> provider =
                Bukkit.getServicesManager().getRegistration(IrisTreeFellerService.class);
        return provider == null ? null : provider.getProvider();
    }
}
```

```java
public interface IrisTreeFellerService {
    boolean tryFell(BlockBreakEvent event, TreeFellerOptions options);

    boolean isManagedBreak(BlockBreakEvent event);

    boolean isTreeBlock(Block block);
}
```

---

## The lifecycle

```
your BlockBreakEvent handler
   |
   v
tryFell(event, options)         register a felling request against this break.
   |                            Returns true when YOUR request is pending. Nothing has
   |                            happened yet and no hook has fired.
   |
   |  (Iris re-checks everything at EventPriority.MONITOR)
   v
onActivationAccepted()          the run is real. Fires exactly once, if at all.
   |
   |  (per LOG block, in the order the tree comes apart)
   v
reserveLogCost()   -> false     you refuse. The run ends. Nothing to give back.
   |
   | true
   v
   +--> commitLogCost()         the log is gone. The charge is yours. FINAL.
   +--> refundLogCost()         the log could not be removed. Give it back.
```

Rules Iris guarantees:

- `onActivationAccepted` fires **at most once per run**, and only after Iris has re-validated the
  break at `MONITOR`: the event was not cancelled, the block still resolves to the same Iris tree,
  and no other run already claims that tree.
- `reserveLogCost` is called **once per log block**, not once per run. A twelve-log tree calls it up
  to twelve times. **Leaves never reserve** — they are removed without consulting you.
- `reserveLogCost` is called **before** Iris charges the axe's durability, so a refusal costs the
  player nothing at all.
- Exactly **one** of `commitLogCost` or `refundLogCost` follows a `reserveLogCost` that returned
  `true`, with the one exception described under [Failure policy](#failure-policy).
- **`commitLogCost` is final.** There is no reversal after it, and Iris will not call
  `refundLogCost` for a block it has already committed.
- A `reserveLogCost` that returns `false` ends the whole run immediately. It does not skip that log
  and continue.
- A tree can only be felled by one run at a time, server-wide. A second player breaking the same
  tree while a run is in flight has their break cancelled with drops suppressed, and no hook of
  yours is called for it.

There is **no terminal callback.** `TreeFellerRunHooks` has no "the run finished" method. If your
accounting needs to know when a run ended, count `commitLogCost` and `refundLogCost` calls against
the `onActivationAccepted` that opened the run, and treat a run with no activity as over.

---

## Threading

Three different threads are involved and the distinction matters, because two of them are region
threads on Folia and one is an entity scheduler.

| Call | Thread |
|---|---|
| `tryFell` | You call it. It must be the thread delivering the `BlockBreakEvent` — the region thread that owns the broken block |
| `isManagedBreak` | Any thread. It is a set lookup and touches nothing else |
| `isTreeBlock` | The region thread that owns the block. It reads block state **and** can block on disk — see below |
| `onActivationAccepted` | The region thread that owns the broken block, inline in the `MONITOR` dispatch |
| `reserveLogCost` | The **feller's entity scheduler thread** |
| `commitLogCost` | The feller's entity scheduler thread |
| `refundLogCost` | The feller's entity scheduler thread |

The three cost hooks run on the player's entity scheduler, which is the thread that owns that player
on Folia. Reading and mutating the feller's inventory, experience and effects is legal there. The
player's *world* is not yours on that thread — do not read or write blocks from a cost hook.

`onActivationAccepted` runs on the block's region thread, inline inside the `BlockBreakEvent`
dispatch at `MONITOR`. Blocks and the player are both legal to touch there, but you are inside event
dispatch: return promptly.

**Do not block, in any of the four.** No I/O, no `CompletableFuture#join`, no locks held across the
call. Iris does not interrupt a hook that hangs and does not time it out; the contract is the only
protection. If a cost decision needs remote data, cache it — prime it on `PlayerJoinEvent`.

### `isTreeBlock` is the expensive one

`isTreeBlock` reads Iris's mantle — the generator's persistent per-region metadata store — to find
out whether the block was placed by an Iris tree. If the mantle region covering that block is not
resident in memory, **this call loads it from disk, synchronously, on your thread.** It also reads
the block's type and block data, so the chunk must be loaded and you must be on the region thread
that owns it.

Concretely: on a first touch in a cold area it does a filesystem stat, and possibly a full region
load and decompress, before it answers. On a warm area it is a couple of map lookups.

Do not call it per block in a loop, per tick, or on a large area. Nothing else in this API touches
the mantle; if you are calling `isTreeBlock` speculatively rather than about a block a player just
interacted with, you are using it wrong.

---

## Worked example: charging stamina per log

A plugin with its own stamina pool. It lets players fell trees regardless of Iris's permission and
enabled switch, charges 4 stamina per log, gives it back when a log turns out not to be removable,
and preserves the axe 50% of the time.

### The hooks

```java
package com.example.woodcutting;

import art.arcane.iris.api.tree.TreeFellerRunHooks;

import java.util.UUID;

public final class StaminaFellHooks implements TreeFellerRunHooks {
    private static final int COST_PER_LOG = 4;

    private final StaminaPool pool;
    private final UUID fellerId;

    public StaminaFellHooks(StaminaPool pool, UUID fellerId) {
        this.pool = pool;
        this.fellerId = fellerId;
    }

    @Override
    public void onActivationAccepted() {
        pool.beginRun(fellerId);
    }

    @Override
    public boolean reserveLogCost() {
        return pool.withdraw(fellerId, COST_PER_LOG);
    }

    @Override
    public void commitLogCost() {
        pool.recordSpend(fellerId, COST_PER_LOG);
    }

    @Override
    public void refundLogCost() {
        pool.deposit(fellerId, COST_PER_LOG);
    }
}
```

`TreeFellerRunHooks` declares all four methods and none of them has a default, so an implementation
must provide all four even when three are empty. `TreeFellerRunHooks.NONE` is the shared no-op
implementation whose `reserveLogCost` returns `true`; use it when you want the override behaviour
without a cost.

The hooks instance is **per run**, not per plugin. Build a new one for each `tryFell` call and put
the feller's identity in it — Iris hands the same instance back for every callback of that run and
never inspects it, so it is the natural place to carry run state.

### The listener

```java
package com.example.woodcutting;

import art.arcane.iris.api.tree.IrisTreeFellerService;
import art.arcane.iris.api.tree.TreeFellerOptions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public final class WoodcuttingListener implements Listener {
    private static final int PRESERVE_PERCENT = 50;

    private final StaminaPool pool;

    public WoodcuttingListener(StaminaPool pool) {
        this.pool = pool;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        IrisTreeFellerService feller = FellerAccess.service();

        if (feller == null || feller.isManagedBreak(event)) {
            return;
        }

        Player player = event.getPlayer();

        if (!pool.hasWoodcutting(player.getUniqueId())) {
            return;
        }

        TreeFellerOptions options = TreeFellerOptions.integrationOverride(
                PRESERVE_PERCENT, new StaminaFellHooks(pool, player.getUniqueId()));

        feller.tryFell(event, options);
    }
}
```

The `isManagedBreak` guard is not optional. While a run is in progress Iris fires a
`BlockBreakEvent` for **every block it removes**, so that protection plugins and loggers see the
removals. Without the guard your listener would call `tryFell` on Iris's own break events and your
stamina check would run once per block of the tree.

`EventPriority.HIGH` is a deliberate choice, and it is load-bearing. It is after the priorities a
protection plugin normally uses to cancel, and — the part that matters — strictly before `HIGHEST`,
which is where Iris asks for its own standalone run. A break is claimed by the first `tryFell` that
succeeds against it, so a handler at `HIGHEST` or `MONITOR` can find that Iris has already taken it.
See [What `tryFell` actually promises](#what-tryfell-actually-promises).

Registration is ordinary:

```java
@Override
public void onEnable() {
    getServer().getPluginManager().registerEvents(new WoodcuttingListener(pool), this);
}
```

---

## The minimum: turn the feller on, charge nothing

If you only want players in your woodcutting class to fell trees, with Iris's own durability
behaviour and no cost:

```java
IrisTreeFellerService feller = FellerAccess.service();

if (feller != null && !feller.isManagedBreak(event) && classes.isWoodcutter(event.getPlayer())) {
    feller.tryFell(event, TreeFellerOptions.integrationOverride(0, TreeFellerRunHooks.NONE));
}
```

Put that in a `BlockBreakEvent` handler at a priority earlier than `HIGHEST` — Iris asks for its own
standalone run at `HIGHEST`, and the first request to succeed claims the break.

`TreeFellerRunHooks.NONE` never refuses and never charges. A `durabilityPreservationChance` of `0`
means every log costs one point of axe durability, which is vanilla-equivalent.

`TreeFellerOptions.standalone()` exists for completeness — it is the request Iris makes for itself —
and there is almost never a reason for a third party to pass it. It respects the enabled switch and
the permission, so it can only ever do what Iris would already have done.

---

## What `tryFell` actually promises

```java
boolean tryFell(BlockBreakEvent event, TreeFellerOptions options);
```

`true` means **your felling request is pending against this break**. It does not mean a tree will
fall — Iris re-validates everything at `MONITOR` and can still drop the request there.

**A break is claimed by the first `tryFell` that succeeds against it.** The moment a request is
accepted, Iris marks that `BlockBreakEvent` as managed, and every later `tryFell` for the same event
returns `false` immediately, without looking at your `access` at all. There is no displacement and no
last-writer-wins: whoever asks first, in event-priority order, owns the break.

| State when you call | Your `access` | Result |
|---|---|---|
| Nothing pending | either | Your request becomes pending. Returns `true` |
| A request already accepted for this break | either | The existing one stays. Returns `false` |

That has one consequence you must design around. **Iris makes its own `STANDALONE` request from a
listener at `EventPriority.HIGHEST`.** If your handler runs at `HIGHEST` and happens to be registered
after Iris's, or at `MONITOR`, Iris has already claimed the break and your override is refused. Call
`tryFell` from a handler at a priority strictly earlier than `HIGHEST` — `LOWEST`, `LOW`, `NORMAL` or
`HIGH` — and your `INTEGRATION_OVERRIDE` is the one that lands. `HIGH` is the usual choice.

Two plugins that both want to override the same break resolve the same way: the earlier priority
wins, and the later one gets `false` and knows it lost. Nothing is silently discarded.

`false` means no request of yours is pending. Iris returns `false` when:

- the service is disabled, or `event` or `options` is `null`;
- the event is already cancelled;
- the event is one Iris is already managing — either a break another request has already claimed, or
  one of the per-block probe events Iris fires during a run. `isManagedBreak` answers both;
- `canUse` failed — for `STANDALONE` that means `treeFeller.enabled` is `false` or the player lacks
  `iris.treefeller`; an `INTEGRATION_OVERRIDE` never fails this check;
- the break is not a fellable candidate.

`true` is still not a run. The `MONITOR` re-validation drops the request if the event was cancelled
after you asked, if the block no longer resolves to the same Iris tree, or if another run already
claims that tree — and in none of those cases does a hook fire. Open your run state in
`onActivationAccepted`, not at `tryFell`.

### What makes a break a candidate

An `INTEGRATION_OVERRIDE` bypasses the enabled switch and the permission. It does **not** bypass any
of these, and there is no option to:

- the player is in `GameMode.SURVIVAL`;
- the player is sneaking;
- the broken block is tagged `Tag.LOGS`;
- the item in the player's main hand is an axe;
- the block carries Iris tree provenance in the mantle — it was placed by an Iris tree, has not been
  replaced since, and is not part of a structure.

A tree the player planted with a vanilla sapling is not an Iris tree and will never fell. Neither is
a log a player placed by hand: Iris clears the provenance record for a block as soon as it is broken
or built over.

---

## How a run comes apart

Once activated, Iris discovers the tree by walking the mantle provenance markers outward from the
broken block in all 26 directions, breadth-first. Members are then removed in that discovery order —
the block the player broke first, then outward — with ties broken by Y, then X, then Z.

Discovery is bounded. If any bound is hit the discovery is **incomplete**, and Iris falls back to
removing only the block the player actually broke:

| Bound | Value |
|---|---|
| Members collected | 131 072 |
| Positions visited | 1 000 000 |
| Distance from the broken block on any axis | 256 blocks |

Removal is paced: Iris removes a batch of blocks, then yields for a tick before the next batch, so a
large tree takes several ticks and does not stall a region. Batch size scales with the tree.

A run ends immediately, with no further hooks, when the player:

- stops sneaking,
- changes their held hotbar slot,
- swaps their hands,
- goes offline, leaves survival mode, or changes world,
- breaks their axe (the run ends after the log that broke it is committed),
- or replaces the axe in that slot with a different item.

Each removed block fires its own `BlockBreakEvent`, marked so that `isManagedBreak` returns `true`
for it during dispatch. Other plugins can cancel that event to protect a block. A cancelled probe on
a **log** refunds that log's reservation and ends the run; a cancelled probe on a **leaf** has no
reservation to give back and the run simply carries on to the next member. Drops for each block are
computed with the axe **as it was before that block's durability charge**, so enchantments like Silk
Touch and Fortune apply normally.

The original break event is cancelled by Iris with drops and experience suppressed, because Iris
delivers them itself per block instead.

---

## What the options carry

```java
public record TreeFellerOptions(
        TreeFellerAccess access,
        int durabilityPreservationChance,
        TreeFellerRunHooks runHooks) {

    public static TreeFellerOptions standalone();

    public static TreeFellerOptions integrationOverride(
            int durabilityPreservationChance,
            TreeFellerRunHooks runHooks);
}
```

The canonical constructor throws `NullPointerException` for a null `access` or `runHooks`, and
`IllegalArgumentException` for a `durabilityPreservationChance` outside `0 .. 100`. Both factory
methods go through it, so `TreeFellerOptions.integrationOverride(101, hooks)` throws at the call
site rather than clamping silently.

`durabilityPreservationChance` is a percentage: the chance that removing one log costs the axe no
durability at all. `0` charges every log; `100` never charges. It is rolled independently per log.
An unbreakable axe is never charged whatever the value.

**The value is only honoured for `INTEGRATION_OVERRIDE`.** A `STANDALONE` request ignores whatever
you passed and uses `treeFeller.durabilityPreservationChance` from Iris's settings —
`TreeFellerOptions.standalone()` hard-codes `0` in the record for exactly that reason.

```java
public interface TreeFellerRunHooks {
    TreeFellerRunHooks NONE;

    void onActivationAccepted();

    boolean reserveLogCost();

    void commitLogCost();

    void refundLogCost();
}
```

Iris never calls anything else on your hooks object — not `equals`, not `hashCode`, not `toString`.
It holds the reference for the duration of the run and drops it when the run ends.

---

## Failure policy

Iris assumes a hooks implementation will throw, refuse late, or be handed a player who logs out
mid-run.

| Misbehaviour | What Iris does |
|---|---|
| `onActivationAccepted` throws | Logged with the stack trace. **The run continues** — activation is a notification, not a veto |
| `reserveLogCost` throws | Logged, treated as `false`. The run ends. Nothing is refunded, because nothing was reserved |
| `reserveLogCost` returns `false` | Not a fault. The run ends cleanly at that log |
| `commitLogCost` throws | Logged. The run ends. **The block is already gone and is not restored** |
| `refundLogCost` throws | Logged. The run ends |
| A hook blocks for a long time | Nothing. Iris does not time hooks out, does not warn, and cannot interrupt them |
| `tryFell` is passed a null event or options | Returns `false`. No hook is called |
| Two plugins request an override for one break | The one whose handler ran first wins. The other gets `false` and no hook of its own fires |
| Resolving the candidate throws | Logged. `tryFell` returns `false` |
| `isTreeBlock` throws | Logged. Returns `false` |
| Iris is disabled mid-run | Every active run is finished immediately. **No refund is issued for anything outstanding** |

**Iris does not quarantine a misbehaving integration.** There is no fault limit, no disable-after-N,
and no automatic unregistration. A hooks implementation that throws on every log will be logged on
every log, forever.

### The one place a refund can be missed

A refund is delivered by scheduling onto the feller's entity scheduler. If that scheduling fails —
the player has logged out, or been removed from the world, between the reservation and the failure
that triggers the refund — Iris finishes the run **without calling `refundLogCost`**. The same
applies to a plugin shutdown that ends runs in flight.

The exposure is at most one log's worth of cost per run, and only in the window between reserving a
log and resolving it, which is a single block removal. If a stricter guarantee matters to you, do
not settle the charge inside the hooks: accumulate reservations in your own per-run state keyed by
the feller, and reconcile on `PlayerQuitEvent` and on your own `onDisable`. The hooks tell you what
happened; they are not a transaction log you can rely on being complete across a disconnect.

---

## Configuration

`plugins/Iris/settings.json`:

| Key | Default | Meaning |
|---|---|---|
| `treeFeller.enabled` | `false` | Master switch for the **standalone** path only. When `false`, Iris never fells a tree on its own. An `INTEGRATION_OVERRIDE` request is unaffected |
| `treeFeller.durabilityPreservationChance` | `0` | Percentage chance a log costs no axe durability, for the standalone path only. Clamped to `0 .. 100` on read |

Permission, declared in the plugin descriptor:

| Node | Default | Meaning |
|---|---|---|
| `iris.treefeller` | `op` | Required for the standalone path. An `INTEGRATION_OVERRIDE` request does not check it |

---

## Enum reference

### `TreeFellerAccess`

| Constant | Enabled switch | `iris.treefeller` | `durabilityPreservationChance` source |
|---|---|---|---|
| `STANDALONE` | Required | Required | Iris settings; the value in your options is ignored |
| `INTEGRATION_OVERRIDE` | Bypassed | Bypassed | The value in your options |

Neither mode bypasses the candidate checks — survival, sneaking, an axe, a log, and Iris tree
provenance.

Write a `default` arm when switching over this enum; see
[README.md](README.md#switching-over-the-enums).

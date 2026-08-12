# 93 - API - Tree Feller

`art.arcane.iris.api.tree` lets another plugin **drive** the Iris tree feller and **charge** for it. The feller removes a whole Iris-generated tree when a sneaking survival player breaks one of its logs with an axe. Reach for this package when a skill, class, or economy system should decide who may fell and what each log costs — the integration can start a run Iris would not start, override durability rules, and reserve a cost per log with commit or refund. The feature is **off by default** (`treeFeller.enabled = false`); the standalone path also requires `iris.treefeller`, and `INTEGRATION_OVERRIDE` bypasses both.

Build and service acquisition: `90 - API - Getting Started.md`. Service: `IrisTreeFellerService` at `ServicePriority.Normal`.

This package is Bukkit-only. Mod loaders run the same feller as a player-facing feature (settings plus the `irisworldgen:treefeller` node) but expose no integration API — see `30 - Platform Differences.md` and `94 - API - Modded.md`.

| Goal | Use |
|---|---|
| Start a felling run Iris would not start, or price it | `tryFell` with `TreeFellerOptions.integrationOverride(...)` |
| Avoid double-handling Iris-generated breaks during a run | `isManagedBreak` |
| Ask whether a block belongs to an Iris tree | `isTreeBlock` |

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

## Lifecycle

```
your BlockBreakEvent handler
   |
   v
tryFell(event, options)         register a felling request against this break.
   |                            true = YOUR access is pending (first writer or same-access re-call).
   |                            Nothing removed yet; no hook fired.
   |
   |  (Iris re-checks at EventPriority.MONITOR)
   v
onActivationAccepted()          run is real. At most once, if at all.
   |
   |  (per LOG block, discovery order)
   v
reserveLogCost()   -> false     refuse; run ends; nothing reserved to refund.
   |
   | true
   v
   +--> commitLogCost()         log gone; charge final
   +--> refundLogCost()         log not removed; give cost back
```

Guarantees:

- `onActivationAccepted` fires **at most once per run**, only after MONITOR re-validation: event not cancelled, block still the same Iris tree, tree not already claimed.
- `reserveLogCost` once per **log** (not leaves). Leaves never reserve.
- Reserve runs **before** axe durability charge.
- Exactly one of `commitLogCost` or `refundLogCost` follows a true reserve, except the miss cases under Failure policy.
- **`commitLogCost` is final.** No later refund for that log.
- `reserveLogCost` false ends the **whole** run immediately.
- One tree, one run, server-wide. The claim is keyed by world plus tree marker: a second player on the same tree gets the break cancelled with drops suppressed and no hooks.
- **No terminal callback.** Count commits/refunds against activation if you need end-of-run accounting.

---

## Threading

| Call | Thread |
|---|---|
| `tryFell` | Thread delivering `BlockBreakEvent` (region thread owning the block) |
| `isManagedBreak` | Any thread (set lookup) |
| `isTreeBlock` | Region thread owning the block; can block on disk |
| `onActivationAccepted` | Region thread of the broken block, inline in MONITOR |
| `reserveLogCost` | Player entity path: entity scheduler on Folia; runs inline on Paper when already on the main thread |
| `commitLogCost` | Player entity path; inline when the caller already owns that thread, otherwise scheduled onto the player |
| `refundLogCost` | Same as commit, when a refund is delivered |

Cost hooks may touch the feller's inventory/XP/effects. Do **not** read/write blocks from cost hooks on Folia (entity thread ≠ region).

`onActivationAccepted` is inside event dispatch — return promptly.

**Do not block any of the four hooks.** No I/O, no `join`, no long locks. Iris does not time out hanging hooks. Cache remote data (e.g. on join).

### `isTreeBlock` is expensive

Reads Iris mantle for tree provenance. Cold mantle regions load from disk **synchronously on your thread**. Also reads block type/data — chunk must be loaded; call from the owning region thread.

Do not call per block in a loop, per tick, or over large areas. Use for blocks a player just interacted with.

```java
package com.example.woodcutting;

import art.arcane.iris.api.tree.IrisTreeFellerService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class TreeProbeListener implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void onInspect(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();
        IrisTreeFellerService feller = FellerAccess.service();

        if (block == null || feller == null || !feller.isTreeBlock(block)) {
            return;
        }

        event.getPlayer().sendMessage("Iris tree — sneak and break a log with an axe to fell it.");
    }
}
```

`isTreeBlock` answers provenance only. It does not check gamemode, sneak, or the held item, and it returns true for leaves as well as logs.

---

## Worked example: stamina per log

### Hooks

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

All four methods are required (no defaults). `TreeFellerRunHooks.NONE` is the shared no-op with `reserveLogCost` always true.

Hooks are **per run**, not per plugin. Build a new instance per `tryFell` and store feller identity in it. Iris holds the reference for the run and never inspects it beyond the four methods.

### Listener

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

`isManagedBreak` is required: Iris fires a `BlockBreakEvent` for every block it removes during a run so protection plugins see removals. Without the guard you re-enter `tryFell` on every member.

`EventPriority.HIGH` is the usual choice: after typical protection cancels, before Iris's own standalone request at `HIGHEST`. See pending rules below.

```java
@Override
public void onEnable() {
    getServer().getPluginManager().registerEvents(new WoodcuttingListener(pool), this);
}
```

---

## Minimum: override only, no charge

```java
IrisTreeFellerService feller = FellerAccess.service();

if (feller != null && !feller.isManagedBreak(event) && classes.isWoodcutter(event.getPlayer())) {
    feller.tryFell(event, TreeFellerOptions.integrationOverride(0, TreeFellerRunHooks.NONE));
}
```

`durabilityPreservationChance` of `0` charges every log (vanilla-like). `100` never charges durability for logs. Unbreakable axes are never charged.

`TreeFellerOptions.standalone()` is Iris's own request shape; third parties almost never need it. It still requires `treeFeller.enabled` and `iris.treefeller`.

---

## What `tryFell` promises

```java
boolean tryFell(BlockBreakEvent event, TreeFellerOptions options);
```

`true` means a pending request is associated with this break for your call path — **not** that a tree will fall. Iris re-validates at `MONITOR` and may drop the request with no hooks.

### Pending request precedence

Pending state is keyed by the `BlockBreakEvent` instance (identity, not `equals`).

| Existing pending | New request | Result |
|---|---|---|
| none | any | Accept; store request; return `true` |
| `STANDALONE` | `STANDALONE` | Keep existing; return `true` |
| `STANDALONE` | `INTEGRATION_OVERRIDE` | Replace the pending standalone request; return `true` |
| `INTEGRATION_OVERRIDE` | `INTEGRATION_OVERRIDE` | Keep the first override; return `true` |
| `INTEGRATION_OVERRIDE` | `STANDALONE` | Keep the override; return `false` |
| managed internal probe (no pending, already managed) | any | return `false` |

`INTEGRATION_OVERRIDE` has precedence over Iris's pending standalone request. The first accepted override keeps its hooks and options; later overrides are idempotently accepted without replacing it. Iris registers its standalone request at `EventPriority.HIGHEST` and finalizes at `MONITOR`, so integrations should submit overrides no later than `HIGHEST` and avoid `MONITOR` ordering races.

Open run state in `onActivationAccepted`, not at `tryFell`.

`false` when:

- service disabled, or `event`/`options` null;
- event already cancelled;
- managed break with no pending (Iris probe / already finalizing path);
- `canUse` failed — standalone needs enabled + permission; override never fails this;
- break is not a fellable candidate;
- candidate resolve throws (logged).

### Candidate checks (no bypass)

`INTEGRATION_OVERRIDE` skips enabled switch and permission only. Still required:

- `GameMode.SURVIVAL`
- player sneaking
- broken block tagged `Tag.LOGS`
- main-hand item is an axe (`*_AXE` material name)
- Iris tree provenance in mantle: placed by an Iris tree, not replaced since, not structure-aware

Vanilla saplings and hand-placed logs never fell. Provenance clears when a block is broken or built over.

---

## How a run comes apart

Discovery walks mantle provenance outward from the broken block in 26 directions, BFS, off the main thread. Members remove in discovery order (trigger first; ties Y then X then Z).

Bounds — any hit marks discovery incomplete; Iris falls back to **only the trigger block**:

| Bound | Value |
|---|---|
| Members collected | 131 072 |
| Positions visited | 1 000 000 |
| Distance from trigger on any axis | 256 blocks |

Only a same-marker block found beyond the axis limit aborts discovery; neighbours outside world height are skipped without marking it incomplete. Discovery also falls back to the trigger block if a member's chunk is unloaded during preflight.

Removal is paced in batches with tick yields: Iris targets about 60 pulses per run and clamps the batch to 4–64 blocks per pulse, so batch size scales with tree size.

Run ends immediately (no further hooks) when the player:

- stops sneaking,
- changes held hotbar slot,
- swaps hands,
- goes offline, leaves survival, or changes world,
- breaks the axe (after that log's commit),
- or replaces the axe in that slot with a different item.

Each removed block fires a `BlockBreakEvent` with `isManagedBreak == true`. Cancelled **log** probes refund that log's reservation and end the run; cancelled **leaf** probes continue. Drops use the axe as it was before that block's durability charge (Silk Touch / Fortune apply).

The original break is cancelled with drops and XP suppressed. Iris collects per-block drops and XP instead, merges equal stacks, and delivers them at the feller's feet in batches; if the player logs out or leaves the world, delivery falls back to the location where the run started.

---

## Options

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

Canonical constructor: null `access`/`runHooks` → `NullPointerException`; chance outside `0..100` → `IllegalArgumentException`.

`durabilityPreservationChance` is percent chance a log costs **no** durability. Rolled per log.

**Honoured only for `INTEGRATION_OVERRIDE`.** Standalone uses `treeFeller.durabilityPreservationChance` from settings; `standalone()` hard-codes `0` in the record for that reason.

```java
public interface TreeFellerRunHooks {
    TreeFellerRunHooks NONE;

    void onActivationAccepted();

    boolean reserveLogCost();

    void commitLogCost();

    void refundLogCost();
}
```

Iris never calls `equals`/`hashCode`/`toString` on hooks.

---

## Failure policy

| Misbehaviour | Behaviour |
|---|---|
| `onActivationAccepted` throws | Logged; **run continues** (notification, not veto) |
| `reserveLogCost` throws | Logged as false; run ends; nothing refunded |
| `reserveLogCost` returns false | Run ends cleanly |
| `commitLogCost` throws | Logged; run ends; **block already gone** |
| `refundLogCost` throws | Logged; run ends |
| Hook blocks a long time | Nothing — no timeout |
| null event or options | `tryFell` → false |
| Two overrides for one break | First stored override's hooks stay; later override returns true without replacing |
| Candidate resolve throws | Logged; false |
| `isTreeBlock` throws | Logged; false |
| Iris service disabled mid-run (plugin disable) | Active runs finish immediately; **no refund for outstanding reserves** |

No integration quarantine.

### Missed refund

Refunds schedule onto the feller's entity path. If scheduling fails (logout/removal) or shutdown ends runs, **`refundLogCost` may not run**. Exposure is at most one log cost per run in the reserve→resolve window. For strict accounting, accumulate in your own state and reconcile on quit/`onDisable`.

---

## Configuration

`plugins/Iris/settings.json`:

| Key | Default | Meaning |
|---|---|---|
| `treeFeller.enabled` | `false` | Standalone path only. Override ignores it |
| `treeFeller.durabilityPreservationChance` | `0` | Standalone durability preserve %; clamped `0..100` on read |

| Permission | Default | Meaning |
|---|---|---|
| `iris.treefeller` | `op` | Standalone only. Override does not check it |

Both keys are read per request, so `/iris reload` takes effect on the next break. Turning `treeFeller.enabled` off does not stop a run already in flight on Bukkit.

Related operator surface: `28 - Integrations.md`, `04 - Commands & Permissions.md`.

---

## Enum reference

### `TreeFellerAccess`

| Constant | Enabled switch | `iris.treefeller` | Durability chance source |
|---|---|---|---|
| `STANDALONE` | Required | Required | Settings; value in options ignored |
| `INTEGRATION_OVERRIDE` | Bypassed | Bypassed | Value in options |

Neither mode bypasses survival, sneak, axe, log tag, or mantle provenance.

Default arms: `90 - API - Getting Started.md`.

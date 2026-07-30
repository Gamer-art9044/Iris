# Iris API

`art.arcane.iris.api` is the surface another plugin compiles against. It answers three questions:
what does Iris terrain look like at a coordinate, when does an Iris world engine come up and go
down, and how do I hand an axe-swing to the Iris tree feller and get told what it cost. It is built
from Bukkit types, `java.*` types and its own types only — no VolmLib, no Adventure, no shaded
types — so it links against a plain Spigot or Paper compile classpath. A test in the Iris build
walks every class in the package and fails the build if any exported signature mentions anything
else.

| Package | What it is for | Document |
|---|---|---|
| `art.arcane.iris.api.terrain` | Ask what the generator says about a coordinate: is this an Iris world, what biome, what region, how high is the surface, what kind of surface | [terrain.md](terrain.md) |
| `art.arcane.iris.api.world` | Learn when an engine becomes usable and when it stops being usable | [world-events.md](world-events.md) |
| `art.arcane.iris.api.pregen` | Follow a pregeneration job | [world-events.md](world-events.md) |
| `art.arcane.iris.api.tree` | Drive the tree feller and charge for it | [tree-feller.md](tree-feller.md) |

PlaceholderAPI keys are not a compile surface, but they are a contract an operator depends on:
[placeholders.md](placeholders.md).

Writing a **mod** rather than a plugin? The Fabric, Forge and NeoForge jars carry a different surface,
`art.arcane.iris.modded.api`: [modded.md](modded.md).

Anything outside `art.arcane.iris.api` is internal. `art.arcane.iris.core.*`,
`art.arcane.iris.engine.*`, `art.arcane.iris.util.*` and `art.arcane.iris.spi.*` change without
notice and without a deprecation cycle. If you find yourself importing `Engine`, `IrisBiome` or
`IrisToolbelt`, you are outside the contract.

---

## Platform limitation

`art.arcane.iris.api` ships in the **Bukkit plugin jar only**. The Fabric, Forge and NeoForge mod
jars contain the same generator but not this package — there is no Bukkit `World`, no
`ServicesManager` and no `Event` bus to hang it on.

The mod jars carry a separate surface instead: `art.arcane.iris.modded.api`, documented in
[modded.md](modded.md). It is where a mod detects Iris levels, drives pregeneration, reads and writes
mantle data, and registers a provider so an Iris pack can place the mod's own blocks, items and mobs.
It is absent from the Bukkit plugin jar and shares no types with `art.arcane.iris.api`.

Everything in these documents assumes Paper, Purpur, Leaf, Canvas, Folia or Spigot, Minecraft 26.2,
Java 25.

---

## Depending on Iris

Iris is not published to Maven Central. Two routes work.

**Against the jar you already have.** This is the route that cannot go wrong: the jar you compile
against is the jar you run against.

```gradle
dependencies {
    compileOnly(files('libs/Iris.jar'))
}
```

**Against JitPack.** This is what Volmit's own plugins do. `transitive = false` is required — the
Iris build declares a large dependency graph you do not want on your compile classpath.

```gradle
repositories {
    maven { url = uri('https://jitpack.io') }
}

dependencies {
    compileOnly('com.github.VolmitSoftware:Iris:<tag-or-branch-SNAPSHOT>') {
        changing = true
        transitive = false
    }
}
```

Bukkit plugin (`plugin.yml`):

```yaml
softdepend: [Iris]
```

Paper plugin (`paper-plugin.yml`):

```yaml
dependencies:
  server:
    Iris:
      load: BEFORE
      required: false
      join-classpath: true
```

`join-classpath: true` is mandatory on Paper. Plugin classloaders are isolated, and without it you
get `NoClassDefFoundError` on `art.arcane.iris.api.*` even though the classes ship unrelocated.

Iris declares `load: STARTUP` and registers its services during its own `onEnable`. Do not resolve
an Iris service in a static initialiser or a constructor. Resolve it lazily, at the point of use,
and handle `null` — see below.

---

## Acquiring a service

Two services are registered with the Bukkit `ServicesManager` at `ServicePriority.Normal`:
`IrisTerrainService` and `IrisTreeFellerService`. Both are unregistered on Iris shutdown.

```java
package com.example.integration;

import art.arcane.iris.api.terrain.IrisTerrainService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class IrisLookup {
    private IrisLookup() {
    }

    public static IrisTerrainService terrain() {
        RegisteredServiceProvider<IrisTerrainService> provider =
                Bukkit.getServicesManager().getRegistration(IrisTerrainService.class);
        return provider == null ? null : provider.getProvider();
    }
}
```

Resolve on every use, or cache and invalidate on `PluginDisableEvent`. A cached reference to a
service whose plugin has been disabled does not throw — every terrain query answers "absent" and
every tree-feller call returns `false` — but it will never answer usefully again, and the
replacement instance registered by a later enable is a different object.

Neither service is a functional interface and neither is meant to be implemented by a third party.
`ServicesManager#getRegistration` hands back the highest-priority registration, so registering your
own `IrisTerrainService` above `Normal` shadows Iris's for every other plugin on the server. Do not.
It does not shadow it for Iris — Iris resolves its own services from an internal registry, so its
PlaceholderAPI expansion keeps reading the real one, and the two would then disagree.

---

## The shared library is not relocated

Iris bundles `art.arcane.volmlib` **unrelocated**, at its real package name. Several sibling Volmit
plugins do relocate it — Adapt shades it to `art.arcane.adapt.util.arcane.volmlib`, React to
`art.arcane.react.util.arcane.volmlib`. Three consequences, in order of how likely they are to bite:

1. **You do not need VolmLib to use this API.** No type in `art.arcane.iris.api` mentions it. You
   never import it, never shade it, never declare it.

2. **If you also use VolmLib yourself, shade and relocate your own copy.** Do not compile against
   `art.arcane.volmlib` expecting Iris's copy to satisfy it at runtime. Under Paper's isolated
   classloaders you would need `join-classpath: true` on the Iris dependency and you would be
   binding to whatever VolmLib version Iris happens to ship, which changes on Iris's release
   schedule and not yours. Relocating your copy costs nothing and removes the coupling entirely.

3. **A relocated sibling and Iris do not share those classes.** `art.arcane.adapt.util.arcane.volmlib.X`
   and `art.arcane.volmlib.X` are unrelated types to the JVM. Never pass an object obtained from one
   plugin's shaded copy into another's; the cast fails at runtime, not at compile time.

---

## Threading, at a glance

This suite runs on Folia, where region threads own chunks and entity schedulers own entities. Each
document states its own contract; this is the summary.

| Call | Which thread may call it | Where the callback lands |
|---|---|---|
| Every `IrisTerrainService` read | Any thread, including async | Returns inline |
| `IrisColumnSink.accept` | — | The thread that called `sampleColumns` |
| `IrisTreeFellerService.tryFell` | The region thread delivering the `BlockBreakEvent` | Returns inline |
| `IrisTreeFellerService.isManagedBreak` | Any thread | Returns inline |
| `IrisTreeFellerService.isTreeBlock` | The region thread owning the block; it can also block on disk — see [tree-feller.md](tree-feller.md#istreeblock-is-the-expensive-one) | Returns inline |
| `TreeFellerRunHooks.onActivationAccepted` | — | The region thread that owns the broken block |
| `TreeFellerRunHooks.reserveLogCost` / `commitLogCost` / `refundLogCost` | — | The feller's entity scheduler thread |
| `IrisWorldEngineEvent` handlers | — | Main thread; on Folia, the global region thread |
| `IrisPregenerationEvent` handlers | — | Main thread; on Folia, the global region thread |

"Any thread" is claimed for the terrain reads because they are justified in doing so: they read the
world's generator reference and evaluate cached procedural noise, and touch no chunk, no block
state, no entity and no mantle storage. See [terrain.md](terrain.md#threading) for the full
argument. It is not a claim any other part of this API makes.

---

## Switching over the enums

`IrisSurfaceKind`, `IrisColumnField`, `IrisWorldPhase`, `IrisPregenPhase` and `TreeFellerAccess` may
gain constants in a future release. A `switch` **expression** over them is exhaustive, so it stops
compiling — and throws `IncompatibleClassChangeError` on an already-compiled jar — the moment one is
added.

**Always write a `default` arm** in third-party code:

```java
String label = switch (kind) {
    case LAND -> "land";
    case OCEAN -> "water";
    default -> "";
};
```

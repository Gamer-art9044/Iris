# 90 - API - Getting Started

`art.arcane.iris.api` is the Bukkit plugin surface another plugin compiles against: terrain reads, world-engine and pregen observation, and tree-feller integration. It is built from Bukkit types, `java.*`, and its own types only — no VolmLib, Adventure, or shaded types — so it links against a plain Spigot or Paper compile classpath. A build test walks every class in the package and fails if any exported signature mentions anything else. PlaceholderAPI keys are operator-facing, not compile-time: see `09 - PlaceholderAPI.md`.

| Package | Purpose | Document |
|---|---|---|
| `art.arcane.iris.api.terrain` | Generator opinion at a coordinate: Iris world?, biome, region, surface height/kind | `91 - API - Terrain.md` |
| `art.arcane.iris.api.world` | Engine ready / hotloaded / closing | `92 - API - World Events.md` |
| `art.arcane.iris.api.pregen` | Pregeneration job progress | `92 - API - World Events.md` |
| `art.arcane.iris.api.tree` | Drive and charge the tree feller | `93 - API - Tree Feller.md` |

Writing a **mod** rather than a plugin? Fabric, Forge, and NeoForge jars expose `art.arcane.iris.modded.api` instead: `94 - API - Modded.md`.

Anything outside `art.arcane.iris.api` is internal. `art.arcane.iris.core.*`, `art.arcane.iris.engine.*`, `art.arcane.iris.util.*`, and `art.arcane.iris.spi.*` change without notice. The separately built SPI jar is for Iris platform adapters, not downstream plugin integrations. Importing `Engine`, `IrisBiome`, or `IrisToolbelt` means you are outside the stable contract.

---

## Platform limitation

`art.arcane.iris.api` ships in the **Bukkit plugin jar only**. Fabric, Forge, and NeoForge mod jars carry the generator but not this package — there is no Bukkit `World`, `ServicesManager`, or `Event` bus to hang it on.

The mod jars carry `art.arcane.iris.modded.api` (`94 - API - Modded.md`): detect Iris levels, drive pregeneration, read/write mantle data, and register providers so packs can place mod blocks, items, and mobs. It is absent from the Bukkit jar and shares no types with `art.arcane.iris.api`.

Everything in these API docs assumes Paper, Purpur, Leaf, Canvas, Folia, or Spigot; Minecraft 26.2; Java 25.

---

## Depending on Iris

Iris is not published to Maven Central. Two routes work.

**Against the jar you already have.** The jar you compile against is the jar you run against.

```gradle
dependencies {
    compileOnly(files('libs/Iris.jar'))
}
```

**Against JitPack.** `transitive = false` is required — the Iris build declares a large dependency graph you do not want on your compile classpath.

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

`join-classpath: true` is mandatory on Paper. Plugin classloaders are isolated; without it you get `NoClassDefFoundError` on `art.arcane.iris.api.*` even though the classes ship unrelocated.

Iris declares `load: STARTUP` and registers its services during `onEnable`. Do not resolve an Iris service in a static initialiser or constructor. Resolve lazily at the point of use and handle `null`.

---

## Acquiring a service

Two services are registered with Bukkit `ServicesManager` at `ServicePriority.Normal`: `IrisTerrainService` and `IrisTreeFellerService`. Both are unregistered on Iris shutdown. Iris also registers the same instances in an internal `IrisServices` registry that its own code (including PlaceholderAPI expansion) uses.

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

Resolve on every use, or cache and invalidate on `PluginDisableEvent`. A cached reference after Iris disables does not throw — terrain queries answer absent and tree-feller calls return `false` — but it never becomes useful again, and a later enable registers a different object.

Neither service is a functional interface and neither is meant for third-party implementation. `ServicesManager#getRegistration` returns the highest-priority registration; registering your own `IrisTerrainService` above `Normal` shadows Iris for every other plugin. It does not shadow Iris for Iris itself (internal registry), so PlaceholderAPI would still read the real service while other plugins would not.

---

## The shared library is not relocated

Iris bundles `art.arcane.volmlib` **unrelocated**. Sibling Volmit plugins may relocate it (Adapt → `art.arcane.adapt.util.arcane.volmlib`, React → `art.arcane.react.util.arcane.volmlib`). Consequences:

1. **You do not need VolmLib to use this API.** No type in `art.arcane.iris.api` mentions it.
2. **If you use VolmLib yourself, shade and relocate your own copy.** Do not bind to Iris's version via `join-classpath`.
3. **A relocated sibling and Iris do not share those classes.** Never pass objects across relocated package boundaries.

---

## Threading, at a glance

This suite runs on Folia (region threads own chunks; entity schedulers own entities). Each document states its contract; summary:

| Call | Which thread may call it | Where the callback lands |
|---|---|---|
| Every `IrisTerrainService` read | Any thread, including async | Returns inline |
| `IrisColumnSink.accept` | — | The thread that called `sampleColumns` |
| `IrisTreeFellerService.tryFell` | The region thread delivering the `BlockBreakEvent` | Returns inline |
| `IrisTreeFellerService.isManagedBreak` | Any thread | Returns inline |
| `IrisTreeFellerService.isTreeBlock` | The region thread owning the block; can block on disk — see `93 - API - Tree Feller.md` | Returns inline |
| `TreeFellerRunHooks.onActivationAccepted` | — | Region thread that owns the broken block |
| `TreeFellerRunHooks.reserveLogCost` / `commitLogCost` / `refundLogCost` | — | Player entity scheduler on Folia; may run inline on the server main thread on Paper when already primary |
| `IrisWorldEngineEvent` handlers | — | Main thread; on Folia, the global region thread |
| `IrisPregenerationEvent` handlers | — | Main thread; on Folia, the global region thread |

Terrain reads may use any thread because they only read the world generator reference and evaluate cached procedural noise — no chunk, block state, entity, or mantle storage. See `91 - API - Terrain.md`. That claim does not apply to the rest of this API.

---

## Switching over the enums

`IrisSurfaceKind`, `IrisColumnField`, `IrisWorldPhase`, `IrisPregenPhase`, and `TreeFellerAccess` may gain constants. A `switch` **expression** without `default` stops compiling (and throws `IncompatibleClassChangeError` on an already-compiled jar) when a constant is added.

Always write a `default` arm in third-party code:

```java
String label = switch (kind) {
    case LAND -> "land";
    case OCEAN -> "water";
    default -> "";
};
```

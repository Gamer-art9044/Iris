# 28 - Integrations

Iris hooks into a handful of Bukkit plugins: WorldEdit for selections, Multiverse-Core for world management, nine item/block/entity plugins so packs can place their content, MythicMobs for skill conditions, and PlaceholderAPI for scoreboard values. All of them are optional, and Iris checks whether the plugin is actually enabled before touching it — a soft-depend only fixes load order, it never guarantees anything is there. Tree felling is a separate feature that ships on both Bukkit and the mod loaders. See also `04 - Commands & Permissions.md`, `06 - Worlds & Lifecycle.md`, `09 - PlaceholderAPI.md`, `19 - Objects.md`, and `93 - API - Tree Feller.md`.

## Confirm an integration is actually live

Add integrations one at a time and prove each one before adding the next. Iris failing to link a plugin is usually silent by design, so absence of an error is not evidence of success.

1. Start from a server where Iris alone generates a fresh world cleanly.
2. Install one integration and its own dependencies, then do a **full restart**. A plugin-manager reload does not reproduce real enable order and will give you a false result either way.
3. Watch the startup log. Each external data provider logs `<Plugin> found, loading <Provider>...` followed by `Enabled ExternalDataProvider for <Plugin>.` The other links log nothing on success.
4. Run the positive proof for that boundary from the table below, then the negative control. The negative control is the part people skip and the part that tells you whether Iris is really gating on the plugin or just happening to work.

| Boundary | Positive proof | Negative control |
|---|---|---|
| WorldEdit | Make a cuboid selection with the WorldEdit wand, run `/iris object we`, and save a disposable object | Clear the selection and run `/iris object we` again — Iris must say you have no WorldEdit selection in this world |
| Multiverse-Core | Create a disposable Iris world and confirm Multiverse lists it with generator `Iris:<pack>` | On a separate disposable copy, restart without Multiverse installed. Iris world creation must still succeed |
| Item/block/entity provider | Reference one exact namespaced key from a pack and generate a fresh chunk containing it | Reference a key that does not exist. Iris must log `No matching Provider found` or a missing-resource error and keep generating |
| MythicMobs conditions | `irisbiome{b=<load key>}` returns true inside that biome | The same condition returns false in a vanilla world |
| PlaceholderAPI | Run the parse sequence in `09 - PlaceholderAPI.md` | A player outside an Iris world gets `world.available` = `false` |
| Tree feller | A sneaking survival player with the permission and an axe fells an Iris-generated tree | A tree the player grew from a sapling stays intact — only the broken log drops |

5. Restart and repeat the positive proof once. Some link failures only appear on the second boot, when caches are warm and enable order shifts.

When something misbehaves, collect both plugin versions and the enable order from the log before you start editing pack JSON. Most integration failures are version or ordering problems, not pack problems.

## Load order declarations

Iris ships both a legacy `plugin.yml` and a Paper `paper-plugin.yml`; Paper servers use the latter. The two declare the same relationships in different syntax.

| Plugin | Relation | Role in Iris |
|---|---|---|
| PlaceholderAPI | softdepend | The `%iris_...%` expansion |
| CraftEngine | softdepend | External blocks and items |
| Nexo | softdepend | External blocks and items |
| ItemsAdder | softdepend | External blocks and items |
| SCore | softdepend | Runtime dependency of the ExecutableItems ecosystem. Iris has no provider class for it |
| ExecutableItems | softdepend | External items |
| MythicLib | softdepend | Runtime dependency of the MMOItems ecosystem. Iris has no provider class for it |
| MMOItems | softdepend | External blocks and items |
| eco | softdepend | Runtime dependency of EcoItems. Iris has no provider class for it |
| EcoItems | softdepend | External items |
| MythicMobs | softdepend | External entities, plus the two skill conditions |
| MythicCrucible | softdepend | External blocks and items |
| KGenerators | softdepend | External blocks and items |
| WorldEdit | softdepend | Selection reading for the object and wand workflows |
| Multiverse-Core | `loadbefore` | Iris enables *before* Multiverse so Multiverse can see Iris as a registered generator plugin |

In `paper-plugin.yml` the softdepends appear as `load: BEFORE, required: false` and Multiverse-Core as `load: AFTER`. Every entry joins the classpath except ExecutableItems, which does not.

## WorldEdit

`WorldEditLink` reaches into WorldEdit entirely by reflection, so Iris compiles and runs without it on the classpath.

| Use | Behavior |
|---|---|
| Reading a selection | Returns an Iris `Cuboid` for the player's current WorldEdit selection **in the world they are standing in**. Returns `null` if WorldEdit is absent, the player has no session, or there is no selection in that world |
| Accepting a selection | With `world.worldEditWandCUI` (default `true`), a WorldEdit selection satisfies Iris anywhere Iris asks for one, without holding the Iris wand |
| `/iris object we` | Checks WorldEdit is enabled, reads your current selection, and puts a **new Iris object wand into your inventory** already carrying those two corners |
| Limitation | `position2` will not operate on a WorldEdit-only selection. Run `/iris object we` first so a real Iris wand exists |

Two behaviors worth knowing. First, whether WorldEdit is present is cached the first time Iris asks, so a WorldEdit installed or enabled after that point is not picked up until restart. Second, if a reflective call throws, Iris logs `Could not get selection`, reports the error, and latches the cached answer to "no WorldEdit" for the rest of the process — a stack trace of that shape means the whole link is now off, not just that one call.

WorldEdit is not needed to import `.schem` files. Iris parses schematic NBT itself. See `19 - Objects.md`.

## Multiverse-Core

`MultiverseCoreLink` calls the Multiverse Core API directly, guarded by `isPluginEnabled("Multiverse-Core")`.

| Operation | Behavior |
|---|---|
| World create or update | If Multiverse does not know the world, imports it with generator `Iris:<pack>`, the Bukkit world's environment, and spawn-adjust off. Then sets `autoLoad = false`, forces the generator string if it drifted, and saves the worlds config |
| World remove | Looks the world up, removes it through Multiverse, and saves the worlds config. Throws `IllegalStateException` naming Multiverse's refusal reason if the removal fails |
| Multiverse absent or disabled | Create/update returns without doing anything; remove returns `false` |

`autoLoad = false` is deliberate: Iris owns the load lifecycle of its worlds, and letting Multiverse also load them at startup produces a double-load race.

Correcting a drifted generator string is done by reflection into Multiverse's world config, because the public API has no setter for it. If a Multiverse update breaks that, the symptom is a world that keeps its old generator string rather than a crash.

World creation, removal, and Studio open/close all route through this same link. See `06 - Worlds & Lifecycle.md`.

## External item, block, and entity plugins

`ExternalDataSVC` instantiates a provider for each supported plugin that is enabled, and also listens for `PluginEnableEvent` so a plugin that enables after Iris is still picked up. Packs then reference external content by namespaced id, and Iris routes each lookup to the first active provider that claims that namespace for that data type (`ITEM`, `BLOCK`, or `ENTITY`).

| Plugin id | Provider class | Claims | Types |
|---|---|---|---|
| CraftEngine | `CraftEngineDataProvider` | Any namespace, but only if CraftEngine actually has an item, block, or furniture with that exact key | ITEM, BLOCK |
| Nexo | `NexoDataProvider` | Namespace `nexo` | ITEM, BLOCK |
| ItemsAdder | `ItemAdderDataProvider` | The item and block namespaces ItemsAdder reports at init, tracked separately | ITEM, BLOCK |
| ExecutableItems | `ExecutableItemsDataProvider` | Namespace `executable_items` | ITEM |
| MMOItems | `MMOItemsDataProvider` | Blocks: namespace `mmoitems`. Items: any namespace containing one `_`, read as `<type>_<subtype>` | ITEM, BLOCK |
| EcoItems | `EcoItemsDataProvider` | Namespace `ecoitems` | ITEM |
| MythicMobs | `MythicMobsDataProvider` | Namespace `mythicmobs` | ENTITY |
| MythicCrucible | `MythicCrucibleDataProvider` | Namespace `crucible` | ITEM, BLOCK |
| KGenerators | `KGeneratorsDataProvider` | Namespace `kgenerators` | ITEM, BLOCK |

Block ids may carry a vanilla-style state suffix, `namespace:key[facing=north,waterlogged=true]`. A malformed state entry logs and resolves to nothing rather than throwing into generation.

Failures are contained. An unresolvable key logs `No matching Provider found for modded material "<key>"` or a missing-resource error naming the namespace and key, and the caller gets an empty result. Generation continues. A provider that throws during activation is logged and skipped; the rest still load.

Third parties can add their own with `ExternalDataSVC#registerProvider`. It throws `IllegalArgumentException` if the plugin id belongs to a built-in provider or one already registered, so you cannot silently displace an existing one. A provider that also implements `Listener` is registered as one automatically.

## MythicMobs skill conditions

When MythicMobs is active, its `MythicConditionLoadEvent` gives Iris two location conditions.

| Condition | Fields | What it checks |
|---|---|---|
| `irisbiome` | `biome` / `b` — comma-separated biome load keys. `surface` / `s` — boolean, default `false` | With `s=true`, the surface biome at the target's X/Z. With the default `s=false`, the biome at the target's actual Y, which includes cave and mantle biomes |
| `irisregion` | `region` / `r` — comma-separated region load keys | The region load key at the target's X/Z |

Both compare against **load keys**, not display names, and both return `false` when the target world is not an Iris world or its engine is unavailable. That means a condition can quietly fail while a world is still booting, so avoid making a mob's only spawn gate an Iris condition during startup.

## PlaceholderAPI

Expansion id `iris`, soft-depended. Registration timing, all sixteen keys, and the pre-2.0 migration table are in `09 - PlaceholderAPI.md`.

## Tree feller

Breaking one log of an Iris-generated tree while sneaking with an axe removes the whole tree. This runs on Bukkit-family **and** on Fabric/Forge/NeoForge, from the same settings and the same traversal code — only the permission plumbing differs.

### Settings (`settings.json`)

| Key | Default | Meaning |
|---|---|---|
| `treeFeller.enabled` | `false` | Turns the player-facing feature on. Does not affect other plugins driving the feller through the API |
| `treeFeller.durabilityPreservationChance` | `0` | Percent chance that felling a log costs no axe durability. Clamped to `0..100` when read, so an out-of-range value in the file is harmless |

### Permission

| Platform | Node | Default |
|---|---|---|
| Bukkit-family | `iris.treefeller` | `op` |
| Fabric | `irisworldgen:treefeller` | Permission level GAMEMASTERS (op level 2) |
| Forge / NeoForge | `irisworldgen:treefeller` (Forge `PermissionNode`) | Permission level GAMEMASTERS (op level 2) |

### What has to be true to fell a tree

All of these, on every platform:

- `treeFeller.enabled` is `true`
- The player has the platform's tree-feller permission
- The player is in survival mode
- The player is sneaking
- The broken block is in the vanilla logs tag
- The main-hand item is in the axes tag
- The block carries Iris tree provenance in the mantle, and that provenance is not part of a structure

That last condition is what separates a generated tree from a player-planted one. Iris stamps trees it places and clears the stamp when a player places a block, so saplings grown by players and hand-built trunks are never felled.

On Bukkit, Iris listens for `BlockBreakEvent` at `EventPriority.HIGHEST` to register the request and finalizes at `MONITOR`, where it cancels the vanilla break, suppresses its drops and XP, and starts its own paced run instead.

On Bukkit, other plugins can drive a fell with `TreeFellerAccess.INTEGRATION_OVERRIDE` through `IrisTreeFellerService`. That bypasses the `enabled` switch and the permission check only — every provenance and block-state requirement still applies, and an override request supersedes a standalone one on the same event. That API is Bukkit-only. See `93 - API - Tree Feller.md`.

### Runtime notes for operators

- Discovery is a flood fill over matching mantle provenance markers, bounded at 131,072 members, 1,000,000 visited positions, and 256 blocks on any axis from the broken block. If a tree exceeds any bound, the run degrades to removing only the block the player broke — an unusually large custom tree quietly behaving like vanilla is this, not a bug.
- Removal is paced across ticks and dispatched per region, so it is Folia-safe and does not stall the main thread on a large tree.
- A run ends early if the player stops sneaking, changes hotbar slot, swaps hands, leaves survival or the world, breaks the axe, or swaps to a different axe item.
- Logs consume axe durability, one point each, subject to the preservation chance and to unbreakable items. Leaves cost nothing and skip the durability path entirely.
- A tree already being felled cannot be claimed twice. A second player breaking into the same tree has their break cancelled with no drops rather than starting a competing run.

## Platforms

WorldEdit, Multiverse-Core, the external data providers, the Mythic conditions, and PlaceholderAPI are Bukkit-family only; mod loaders do not use these plugin declarations. The tree feller is the exception — it runs on every platform. See `30 - Platform Differences.md`.

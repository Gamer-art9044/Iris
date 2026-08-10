# 28 - Integrations

Iris integrates with selected Bukkit plugins for world management, selections, external blocks/items/entities, Mythic skill conditions, PlaceholderAPI, and tree felling. Soft-depends declare load order only; Iris still checks `isPluginEnabled` / readiness before use. Integrations are Bukkit-family unless noted. See also `04 - Commands & Permissions.md`, `06 - Worlds & Lifecycle.md`, `09 - PlaceholderAPI.md`, `19 - Objects.md`, and `93 - API - Tree Feller.md`.

## Tutorial: prove an integration boundary

Choose one boundary and one observable result:

| Boundary | Positive proof | Negative control |
|---|---|---|
| WorldEdit | Make a cuboid selection, run `/iris object we`, and save a disposable object | Clear the selection; Iris must report that no area is selected |
| Multiverse-Core | Create or update a disposable Iris world and confirm generator `Iris:<pack>` in Multiverse | In a separate disposable test copy, restart without Multiverse installed; Iris must skip the link without failing its own world lifecycle |
| External item/block/entity provider | Validate one exact namespaced key and generate one consumer in a new chunk | An invalid key logs and resolves empty without crashing generation |
| MythicMobs conditions | `irisbiome` or `irisregion` passes inside the named Iris resource | The same condition returns false outside Iris or with engine access unavailable |
| PlaceholderAPI | Complete the direct parse sequence in `09 - PlaceholderAPI.md` | A player outside Iris receives the documented unavailable values |
| Tree feller | A sneaking survival player with permission and an axe fells a provenanced Iris tree | A player-planted or hand-built tree remains intact |

1. Start from a server where Iris alone passes its fresh-world smoke.
2. Install one integration and its required dependencies; perform a full restart rather than a plugin reload.
3. Confirm enable order and that both plugins report ready without a linkage or missing-class exception.
4. Exercise the smallest read-only path listed in that integration section, then one controlled mutation such as a selection conversion, external item resolution, or managed-world import.
5. Restart and repeat the same path. Test one unavailable/invalid external key so failure behavior is also known.
6. Add the next integration only after the current boundary passes.

Soft-depend presence is not proof that an external provider is ready. Diagnose integration failures with both plugins' versions and startup order before changing an Iris pack.

## Soft-depends and load order (`plugin.yml`)

| Plugin | Relation | Role |
|---|---|---|
| PlaceholderAPI | softdepend | `%iris_...%` expansion |
| CraftEngine | softdepend | External blocks/items |
| Nexo | softdepend | External blocks/items |
| ItemsAdder | softdepend | External blocks/items |
| SCore | softdepend | Dependency of ExecutableItems ecosystems; no Iris provider class |
| ExecutableItems | softdepend | External items |
| MythicLib | softdepend | Dependency of MMOItems ecosystems; no Iris provider class |
| MMOItems | softdepend | External blocks/items |
| eco | softdepend | Dependency of EcoItems; no Iris provider class |
| EcoItems | softdepend | External items |
| MythicMobs | softdepend | External entities + skill conditions |
| MythicCrucible | softdepend | External blocks/items |
| KGenerators | softdepend | External blocks/items |
| WorldEdit | softdepend | Selection for object/wand workflows |
| Multiverse-Core | `loadbefore` (Iris loads first) | World import/generator sync and remove |

Multiverse is listed under `loadbefore`, not softdepend: Iris enables before Multiverse-Core so Multiverse can see Iris as a generator plugin.

## WorldEdit

`WorldEditLink` reflects into WorldEdit when the plugin is enabled.

| Use | Behavior |
|---|---|
| Selection read | Returns an Iris `Cuboid` for the player's current WorldEdit selection in their world, or `null` if none |
| Wand / object tools | With `world.worldEditWandCUI` default `true`, a WorldEdit selection is accepted where Iris wants a selection without holding the Iris wand |
| `/iris object we` | Converts the current WorldEdit selection into a real Iris wand selection |
| Limit | `position2` does not work on a WorldEdit-only selection until `/iris object we` runs |

WorldEdit is not required for `.schem` import: Iris parses schematic NBT itself. See `19 - Objects.md`.

## Multiverse-Core

`MultiverseCoreLink` uses the Multiverse Core API when Multiverse-Core is enabled.

| Operation | Behavior |
|---|---|
| World create/update | Imports or updates the Multiverse world with generator `Iris:<pack>`, `autoLoad=false`, environment from the Bukkit world, spawn adjust off |
| World remove | Removes the Multiverse world config entry and saves worlds config; throws if Multiverse refuses removal |
| Inactive Multiverse | Calls no-op / return `false` |

Studio open/close and world lifecycle paths use the same link when Multiverse is present. See `06 - Worlds & Lifecycle.md`.

## External data providers (item / block / entity plugins)

`ExternalDataSVC` activates built-in providers when their plugins are ready, including late enable via `PluginEnableEvent`. Packs reference external content by namespaced ids resolved through the active provider for that namespace and `DataType` (`ITEM`, `BLOCK`, `ENTITY`).

| Plugin id | Provider | Namespaces / match | Types |
|---|---|---|---|
| CraftEngine | `CraftEngineDataProvider` | Any CraftEngine item/block/furniture key that exists | ITEM, BLOCK |
| Nexo | `NexoDataProvider` | `nexo` | ITEM, BLOCK |
| ItemsAdder | `ItemAdderDataProvider` | Namespaces reported by ItemsAdder for items/blocks | ITEM, BLOCK |
| ExecutableItems | `ExecutableItemsDataProvider` | `executable_items` | ITEM |
| MMOItems | `MMOItemsDataProvider` | Blocks: `mmoitems`; items: two-part type namespace (`type_subtype:id`) | ITEM, BLOCK |
| EcoItems | `EcoItemsDataProvider` | `ecoitems` | ITEM |
| MythicMobs | `MythicMobsDataProvider` | `mythicmobs` | ENTITY |
| MythicCrucible | `MythicCrucibleDataProvider` | `crucible` | ITEM, BLOCK |
| KGenerators | `KGeneratorsDataProvider` | `kgenerators` | ITEM, BLOCK |

Third parties may register additional providers with `ExternalDataSVC#registerProvider` if the plugin id is not already taken. Missing resources log and resolve empty rather than crashing generation.

## MythicMobs skill conditions

When MythicMobs is active, Iris registers location conditions:

| Condition | Fields | Check |
|---|---|---|
| `irisbiome` | `biome`/`b` (comma list of load keys), `surface`/`s` (boolean, default false) | Surface biome or full column biome via Iris engine |
| `irisregion` | `region`/`r` (comma list of load keys) | Region load key at X/Z |

Both return false outside Iris worlds or when engine access is missing.

## PlaceholderAPI

Soft-depend + expansion id `iris`. Full key list and migration: `09 - PlaceholderAPI.md`.

## Tree feller (operator)

Standalone tree felling is Bukkit-only. It removes whole Iris-generated trees when a sneaking survival player breaks a provenanced log with an axe.

### Settings (`settings.json`)

| Key | Default | Meaning |
|---|---|---|
| `treeFeller.enabled` | `false` | Master switch for the **standalone** path only |
| `treeFeller.durabilityPreservationChance` | `0` | Percent chance a log costs no axe durability (standalone path); clamped `0..100` on read |

### Permission

| Node | Default | Meaning |
|---|---|---|
| `iris.treefeller` | `op` | Required for standalone felling |

### Standalone requirements (all required)

- `treeFeller.enabled` is `true`
- Player has `iris.treefeller`
- `GameMode.SURVIVAL`
- Player is sneaking
- Broken block is tagged as a log
- Main-hand item is an axe
- Block has Iris tree provenance in the mantle (Iris-placed tree, not player-planted saplings or hand-placed logs)

Iris listens at `EventPriority.HIGHEST` for its own standalone request. Other plugins can drive felling with `INTEGRATION_OVERRIDE` (bypasses enabled switch and permission only) through `IrisTreeFellerService`; that API is documented in `93 - API - Tree Feller.md`.

### Runtime notes for operators

- Discovery walks mantle provenance (bounds: 131072 members, 1e6 visits, 256-block axis distance); oversize trees fall back to removing only the broken block
- Removal is paced across ticks
- Run ends if the player stops sneaking, changes hotbar slot/hand, leaves survival/world, breaks the axe, or swaps the axe item
- Leaves never cost durability/cost hooks; logs do

## Platforms

WorldEdit, Multiverse, external data providers, Mythic conditions, PlaceholderAPI, and the tree feller are Bukkit-family. Modded loaders do not use these plugin soft-depends. See `30 - Platform Differences.md`.

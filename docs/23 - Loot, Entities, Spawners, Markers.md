# 23 - Loot, Entities, Spawners, Markers

Loot tables fill containers and entity drop inventories. Entities describe what to spawn. Spawners schedule ambient and initial spawns. Markers pin spawners to placed objects. Together they form the pack-side entity and loot systems.

Related: `05 - Concepts & Pack Layout.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `19 - Objects.md`, `20 - Object Placement.md`, `03 - Configuration.md`, `10 - Studio & VSCode Schemas.md`.

## Tutorial: spawn one loot-bearing entity

This workflow adds a zombie that always rolls one iron nugget, spawns it at night in one biome, and proves each resource edge independently. Prerequisites are a validating pack, a working land biome with load key `tutorial/meadow`, and `world.ambientEntitySpawningSystem: true` in `settings.json`.

Create these files:

```
loot/tutorial/zombie-drops.json
entities/tutorial/zombie.json
spawners/tutorial/night-zombies.json
```

`loot/tutorial/zombie-drops.json`:

```json
{
  "name": "Tutorial Zombie Drops",
  "minPicked": 1,
  "maxPicked": 1,
  "maxTries": 1,
  "loot": [
    { "type": "iron_nugget", "rarity": 1, "minAmount": 1, "maxAmount": 1 }
  ]
}
```

`entities/tutorial/zombie.json`:

```json
{
  "type": "minecraft:zombie",
  "loot": {
    "mode": "ADD",
    "tables": ["tutorial/zombie-drops"]
  }
}
```

`spawners/tutorial/night-zombies.json`:

```json
{
  "group": "NORMAL",
  "maxEntitiesPerChunk": 3,
  "timeBlock": { "startHour": 20, "endHour": 5 },
  "weather": "ANY",
  "allowedLightLevels": { "min": 0, "max": 7 },
  "maximumRate": { "amount": 4, "per": { "seconds": 30 } },
  "spawns": [
    { "entity": "tutorial/zombie", "rarity": 1, "minSpawns": 1, "maxSpawns": 2 }
  ]
}
```

Add the spawner key to the existing biome; this is a field excerpt, not a separate JSON file:

```json
{
  "entitySpawners": ["tutorial/night-zombies"]
}
```

1. Validate the pack before opening the test world. The validator must resolve the spawner-to-entity edge and the entity's loot table.
2. On Bukkit, run `/iris studio spawn tutorial/zombie` to prove the entity file loads, then kill it and confirm the nugget drop. On modded, continue directly to ambient spawning because the Studio spawn command is a stub.
3. Open the pack in Studio, focus `tutorial/meadow`, set night, and stand in a dark surface area. Success is an Iris-spawned zombie within the configured rate and chunk cap; killing it rolls the custom table.
4. If the entity loads manually but never spawns, check the ambient-spawning setting, time, block light, biome attachment, and living-entity cap. If validation fails, fix the first unresolved key in the chain rather than changing rarity or rate.
5. Remove biome focus after the chain works. Add effects, markers, gear, passengers, and additional loot only after this minimal path remains repeatable.

## Where files live

| Path | Registrant | Role |
|------|------------|------|
| `loot/<key>.json` | `IrisLootTable` | Weighted item tables |
| `entities/<key>.json` | `IrisEntity` | Entity type, gear, drops, flags |
| `spawners/<key>.json` | `IrisSpawner` | When/where entity keys spawn |
| `markers/<key>.json` | `IrisMarker` | Marker definition that lists spawner keys |

Keys use pack-relative paths without `.json` (for example `standard/hostile/zombie`, `temperate/hostile`, `global-treasure`).

## Wiring

| Source | Field | Loads |
|--------|-------|-------|
| Dimension | `loot` (`IrisLootReference`) | Global tables for the dimension |
| Region | `loot` | Regional tables |
| Biome | `loot` | Biome tables |
| Entity | `loot` | Drop tables when entity is `Lootable` |
| Object placement | `loot` / `vanillaLoot` | Containers in placed objects |
| Dimension / region / biome | `entitySpawners` | Spawner keys for ambient spawning |
| Object placement | `markers` (`IrisObjectMarker[]`) | Places markers on matching blocks |
| Marker | `spawners` | Spawner keys at that marker |

Ambient spawning merges dimension, then region, then surface-biome `entitySpawners` when `settings.json` has `world.ambientEntitySpawningSystem` true (default). Marker spawning uses mantle markers when `world.markerEntitySpawningSystem` is true (default).

The shipping overworld pack includes `entities/standard/...`, `spawners/<climate>/...`, and `loot/...` resources, but its dimensions, regions, and biomes do not currently reference those spawner keys through `entitySpawners`. Ambient Iris spawners only fire after a parent lists them.

## Loot tables (`IrisLootTable`)

Folder: `loot/`.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `name` | string | `""` | Required display name (min length 2) |
| `rarity` | int ≥ 1 | `1` | Table 1-in-N chance factor |
| `minPicked` | int 0..64 | `1` | Min successful picks per roll |
| `maxPicked` | int 1..64 | `5` | Max successful picks per roll |
| `maxTries` | int 1..256 | `10` | Cap on pick attempts |
| `loot` | `IrisLoot[]` | `[]` | Entries; empty table yields no items |

Roll procedure: RNG picks a random entry each try, keeps items that pass entry rarity and match the requested `InventorySlotType`, stops at `maxPicked` successes or `maxTries`.

### Loot entry (`IrisLoot`, snippet type `loot`)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `type` | string | `""` | Required. Vanilla material name such as `diamond` or `DIAMOND_SWORD` (not `minecraft:*` except for namespaced third-party items) |
| `slotTypes` | `InventorySlotType` | `STORAGE` | `STORAGE`, `FUEL`, `FURNACE`, `BLAST_FURNACE`, `SMOKER` |
| `rarity` | int ≥ 1 | `1` | Sub-rarity after table pick |
| `minAmount` / `maxAmount` | int 1..64 | `1` | Stack size |
| `displayName` | string | null | Color codes via `&` |
| `lore` | string[] | `[]` | Wrapped lore lines |
| `minDurability` / `maxDurability` | 0..1 | `0` / `1` | Remaining durability percent |
| `customModel` | int | null | Custom model data float |
| `unbreakable` | boolean | `false` | |
| `itemFlags` | string[] | `[]` | Bukkit `ItemFlag` names |
| `enchantments` | `IrisEnchantment[]` | `[]` | |
| `attributes` | `IrisAttributeModifier[]` | `[]` | |
| `dyeColor` | string | null | `DyeColor` name for colorable items |
| `leatherColor` | string | null | `#RRGGBB` for leather armor |
| `customNbt` | object | null | Platform NBT map; also used for third-party items |

Combined rarity is table rarity × entry rarity. Studio debug lore can show table name and combined chance.

### Loot reference (`IrisLootReference`, snippet type `loot-registry`)

Used on dimensions, regions, biomes, and entities:

```json
{
"loot": {
  "mode": "FALLBACK",
  "multiplier": 0.5,
  "tables": ["temperate/clutter", "temperate/food"]
}
}
```

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `mode` | `IrisLootMode` | `ADD` | `ADD`, `CLEAR`, `REPLACE`, `FALLBACK` |
| `tables` | string[] | `[]` | Loot table keys |
| `multiplier` | double 0..16 | `1` | Scales loot source counts |

- `ADD` — append to parent tables.
- `CLEAR` / `REPLACE` — drop parent tables then add these (same effective clear-then-add).
- `FALLBACK` — use only when no object-level loot already defined.

Overworld dimension example:

```json
{
"loot": {
  "mode": "FALLBACK",
  "tables": ["global-clutter"]
}
}
```

### Object loot

On `IrisObjectPlacement`:

| Field | Notes |
|-------|-------|
| `loot` | `IrisObjectLoot[]` — pack table name + optional block filter + weight |
| `vanillaLoot` | Vanilla loot table bindings |
| `overrideGlobalLoot` | When true, object tables replace dim/region/biome tables for that placement |

`IrisObjectLoot` fields: `name` (loot table key), `weight` (default 1), `filter` (block list, empty = all containers), `exact` (exact block-data match).

Object tile payloads, including authored container NBT, and deferred custom-block identifiers remain in Mantle until the platform post-load materialization iteration completes normally. Generic cleanup and pregeneration cleanup retain these sparse payloads for chunks that have not reached that pass; Bukkit keeps a failed region-scheduled pass retryable without repeating earlier completed passes.

### Real overworld loot sample

From `loot/global-treasure.json` (abbreviated):

```json
{
  "name": "Global Treasure",
  "rarity": 1,
  "maxPicked": 2,
  "minPicked": 0,
  "maxTries": 20,
  "loot": [
    { "type": "diamond", "minAmount": 1, "maxAmount": 3, "rarity": 4, "slotTypes": "STORAGE" },
    { "type": "emerald", "minAmount": 1, "maxAmount": 4, "rarity": 3, "slotTypes": "STORAGE" },
    {
      "type": "enchanted_book",
      "minAmount": 1,
      "maxAmount": 1,
      "rarity": 14,
      "slotTypes": "STORAGE",
      "enchantments": [{ "enchantment": "mending", "minLevel": 1, "maxLevel": 1, "chance": 1.0 }]
    }
  ]
}
```

## Entities (`IrisEntity`)

Folder: `entities/`.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `type` | string | null | Required namespaced entity type (`minecraft:zombie` or bare keys resolved by platform). Use `unknown` with `specialType` for external plugins |
| `specialType` | string | `""` | `PluginName:MobName` via external data service |
| `applySettingsToCustomMobAnyways` | boolean | `false` | Apply Iris gear/flags on custom mobs |
| `reason` | string | null | `CreatureSpawnEvent.SpawnReason` name; default `NATURAL` |
| `customName` / `customNameVisible` | string / bool | `""` / `false` | |
| `aware` / `ai` | boolean | `true` | Mob awareness / AI |
| `glowing` / `gravity` / `invulnerable` / `silent` | boolean | false / true / false / false | |
| `pickupItems` / `removable` / `keepEntity` / `baby` | boolean | false | |
| `helmet` / `chestplate` / `leggings` / `boots` / `mainHand` / `offHand` | `IrisLoot` | null | Gear; each entry uses its own `rarity` |
| `passengers` | `IrisEntity[]` | `[]` | Nested riders |
| `attributes` | `IrisAttributeModifier[]` | `[]` | |
| `loot` | `IrisLootReference` | empty ADD | Drop tables |
| `leashHolder` | `IrisEntity` | null | Spawns holder and leashes |
| `spawnEffect` | `IrisEffect` | null | On-spawn effect |
| `spawnEffectRiseOutOfGround` | boolean | `false` | Rise-from-ground VFX |
| `pandaMainGene` / `pandaHiddenGene` | string | null | Panda genes |
| `surface` | `IrisSurface` | `LAND` | Spawn surface check: `LAND`, `ANIMAL`, `WATER`, `OVERWORLD`, `LAVA` |
| `rawCommands` | `IrisCommand[]` | `[]` | Run with `{x}` `{y}` `{z}` |

Minimal overworld entities are often one field:

```json
{"type": "ZOMBIE"}
```

Path key: `entities/standard/hostile/zombie.json` → load key `standard/hostile/zombie`.

### Entity commands (`IrisCommand`)

`rawCommands` executes console commands after an entity spawns. The same command object is also used by ambient effect command registries.

| Field | Default | Behavior |
|-------|---------|----------|
| `commands` | `[]` | Required command strings; a leading `/` is removed and `{x}`, `{y}`, `{z}` are replaced with block coordinates |
| `delay` | `0` | Delay before first execution, in server ticks; negative values clamp to zero |
| `repeat` | `false` | Repeats indefinitely after the first delay; no cancellation handle is exposed and repeats stop on server restart |
| `repeatDelay` | `100` | Ticks between repeats; values below one clamp to one |
| `timeBlock` | any time | Required world-time window |
| `weather` | `ANY` | Required weather: `NONE`, `DOWNFALL`, `DOWNFALL_WITH_THUNDER`, or `ANY` |

Bukkit and modded entity spawning both apply AI/awareness flags, spawn effects, and raw commands. Spawner time and weather gates are also enforced on both platform families.

## Ambient effects (`IrisEffect`)

Biomes and regions accept `effects[]`. Each entry performs a 1-in-`chance` check no more often than `interval` milliseconds and can combine a potion, sound, particle, and command registry.

| Field | Default / range | Behavior |
|-------|-----------------|----------|
| `interval` | `150` ms, ≥0 | Minimum interval between effect attempts |
| `chance` | `50`, ≥1 | One successful attempt in this many intervals |
| `potionEffect` | `""` | Potion-effect registry key |
| `potionStrength` | `-1` (−1–1024) | Amplifier; `-1` disables potion application |
| `potionTicksMin` / `potionTicksMax` | `75` / `155` | Random potion duration in ticks |
| `sound` | `null` | Sound registry key |
| `soundDistance` | `12` (0–512) | Maximum random sound offset from the player |
| `minPitch` / `maxPitch` | `0.5` / `1.5` | Random pitch interval |
| `volume` | `1.5` | Sound volume |
| `particleEffect` | `null` | Particle registry key; modded supports simple particle types that need no extra particle data |
| `particleOffset` | `0` (−32–32) | Random vertical surface offset |
| `particleCount` | `0` (0–512) | Count; zero lets the alternate XYZ values behave as motion on Bukkit |
| `particleDistance` | `20` (0–64) | Forward sampling distance |
| `particleDistanceWidth` | `24` (0–128) | Side-to-side sampling radius |
| `particleAway` | `5` (0–16) | Minimum forward offset |
| `particleAltX` / `particleAltY` / `particleAltZ` | `0` (−8–8) | Particle offset or motion components |
| `randomAltX` / `randomAltY` / `randomAltZ` | `true` / `false` / `true` | Randomize each alternate component between its negative and positive value |
| `extra` | `0` | Particle-specific extra value |
| `commandRegistry` | `null` | Optional command casting configuration |

`IrisCommandRegistry` fields:

| Field | Default | Behavior |
|-------|---------|----------|
| `rawCommands` | `[]` | `IrisCommand[]` to run |
| `commandOffsetX` / `commandOffsetY` / `commandOffsetZ` | `0` (−8–8) | Coordinate offsets from the player |
| `commandRandomAltX` / `commandRandomAltY` / `commandRandomAltZ` | `true` / `false` / `true` | Randomize each coordinate within the signed offset |
| `commandAllRandomLocations` | `true` | Pick a new random coordinate for each command object; false reuses one coordinate |

Example:

```json
{
  "particleEffect": "minecraft:ash",
  "particleCount": 8,
  "sound": "minecraft:ambient.cave",
  "interval": 1000,
  "chance": 8,
  "commandRegistry": {
    "commandOffsetX": 4,
    "commandOffsetZ": 4,
    "rawCommands": [{ "commands": ["particle minecraft:smoke {x} {y} {z}"] }]
  }
}
```

## Spawners (`IrisSpawner`)

Folder: `spawners/`.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `spawns` | `IrisEntitySpawn[]` | `[]` | Ongoing spawns |
| `initialSpawns` | `IrisEntitySpawn[]` | `[]` | Per-chunk initial pass (`EXECUTES PER CHUNK`) |
| `maxEntitiesPerChunk` | int | `1` | Skip if living entities exceed |
| `timeBlock` | `IrisTimeBlock` | any time | `startHour` / `endHour` in 24h (0–24) |
| `weather` | `IrisWeather` | `ANY` | `NONE`, `DOWNFALL`, `DOWNFALL_WITH_THUNDER`, `ANY` |
| `maximumRate` | `IrisRate` | infinite | Global rate limit |
| `maximumRatePerChunk` | `IrisRate` | infinite | Per-chunk rate limit |
| `allowedLightLevels` | `IrisRange` | `0`..`15` | Inclusive block light |
| `group` | `IrisSpawnGroup` | `NORMAL` | Placement band |

`IrisSpawnGroup`:

| Value | Placement |
|-------|-----------|
| `NORMAL` | Surface land biomes only |
| `CAVE` | Valid for all biome inferred types; uses cave-floor markers when available |
| `UNDERWATER` | Sea biomes, between seafloor and surface |
| `BEACH` | Shore biomes |

`IrisRate`: `amount` (0 = infinite) and `per` (`IrisDuration`). Empty duration means an infinite rate and is always allowed.

`IrisDuration` fields are additive:

| Field | Runtime unit |
|-------|--------------|
| `milliseconds` | 1 ms |
| `minecraftTicks` | 50 ms |
| `seconds` / `minutes` / `hours` / `days` | Real-time units |
| `minecraftHours` | 50 real seconds |
| `minecraftDays` | 20 real minutes |
| `minecraftWeeks` | 7 Minecraft days (2 h 20 min) |
| `minecraftLunarCycles` | 8 Minecraft days (2 h 40 min) |

### Entity spawn entry (`IrisEntitySpawn`, snippet `entity-spawn`)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `entity` | string | `""` | Required entity key |
| `rarity` | int ≥ 1 | `1` | 1-in-N for some spawn paths |
| `minSpawns` / `maxSpawns` | int ≥ 1 | `1` | Count range when selected |

Overworld sample `spawners/temperate/hostile.json`:

```json
{
  "group": "NORMAL",
  "maximumRate": { "amount": 8, "per": { "seconds": 15 } },
  "timeBlock": { "startHour": 20, "endHour": 3 },
  "maxEntitiesPerChunk": 2,
  "weather": "ANY",
  "spawns": [
    { "entity": "standard/hostile/zombie", "rarity": 5, "maxSpawns": 4, "minSpawns": 2 },
    { "entity": "standard/hostile/skeleton", "rarity": 30, "maxSpawns": 2, "minSpawns": 1 }
  ]
}
```

To activate ambient spawning, list the spawner key on a parent:

```json
{
"entitySpawners": ["temperate/hostile", "temperate/passive"]
}
```

## Markers (`IrisMarker`)

Folder: `markers/`.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `spawners` | string[] | `[]` | Spawner keys attached at this marker |
| `removeOnChange` | boolean | `true` | Drop marker when assigned block changes |
| `emptyAbove` | boolean | `true` | Require two air blocks above when placing |
| `exhaustionChance` | double | `0` | Chance to remove marker on use; `<0` never; `≥1` always on first use |

### Placing markers from objects (`IrisObjectMarker`, snippet `object-marker`)

On object placement `markers[]`:

| Field | Type | Notes |
|-------|------|-------|
| `mark` | `IrisBlockData[]` | Required block types to tag |
| `marker` | string | Required marker key |
| `maximumMarkers` | int 1..16 | Default 8 |
| `exact` | boolean | Exact block-data match |

When marker entity spawning is enabled, `MarkerSpawnScanner` loads the marker tag, resolves its spawners, and `WorldEntitySpawner` fires those spawners at the marker position. Marker exhaustion can remove the mantle marker after use.

## Custom block drops (`IrisBlockDrops`)

Dimensions, regions, and biomes accept `blockDrops[]`. On Bukkit, a matching biome provider runs first; unless any matching biome provider has `skipParents: true`, matching region and dimension providers are appended in that order.

| Field | Default | Behavior |
|-------|---------|----------|
| `blocks` | `[]` | Required `IrisBlockData[]` to match |
| `exactBlocks` | `false` | False compares material only; true compares the complete block state |
| `drops` | `[]` | `IrisLoot[]`; each entry's rarity is rolled independently |
| `skipParents` | `false` | On a matching biome provider, prevents region and dimension drop providers from running for that break |
| `replaceVanillaDrops` | `false` | If any matching provider enables it, suppresses vanilla block drops while retaining Iris drops from all selected providers |

```json
{
  "blocks": [{ "block": "minecraft:stone" }],
  "exactBlocks": false,
  "drops": [{ "type": "flint", "rarity": 4 }],
  "skipParents": false,
  "replaceVanillaDrops": false
}
```

The custom block-drop router is a Bukkit runtime feature; modded pack loading preserves the schema but does not install the Bukkit block-break event router.

## Runtime settings that gate spawning

From `settings.json` → `world`:

| Key | Default | Effect |
|-----|---------|--------|
| `ambientEntitySpawningSystem` | `true` | Dim/region/biome `entitySpawners` |
| `markerEntitySpawningSystem` | `true` | Marker-driven spawners |
| `targetSpawnEntitiesPerChunk` | `0.95` | Ambient density target |
| `asyncTickIntervalMS` | `700` | Spawn tick interval |
| `forcePersistEntities` | `true` | Force entity persistence globally |

Studio command `/iris studio loot` previews chest loot at the player position (see `10 - Studio & VSCode Schemas.md`, `04 - Commands & Permissions.md`).

## Reference checklist

1. Write `loot/<key>.json` tables; reference them from dim/region/biome `loot.tables` or object `loot[].name`.
2. Write `entities/<key>.json` with at least `type`.
3. Write `spawners/<key>.json` with `spawns` entity keys and rates.
4. Add spawner keys to `entitySpawners` on dimension, region, and/or biome — or attach via markers on object placements.
5. Optional markers under `markers/` plus placement `markers` arrays for structure-bound spawns.
6. Validate with studio open + hotload; use studio loot preview for chests.

# 23 - Loot, Entities, Spawners, Markers

Loot tables decide what appears inside generated chests and what custom mobs drop. Entities describe a mob and its gear. Spawners decide when and where those entities appear. Markers pin spawners to specific blocks inside placed objects. This page explains how each system fires at runtime, then documents every field.

Related: `05 - Concepts & Pack Layout.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `19 - Objects.md`, `20 - Object Placement.md`, `03 - Configuration.md`, `10 - Studio & VSCode Schemas.md`.

## The mental model

Two independent pipelines share the loot table format.

**Containers.** When a chunk finishes generating, Iris walks the blocks it recorded and fills every storage chest it placed. It builds a list of loot tables for that exact block, then rolls each one and drops the results into the inventory. The list comes from up to four sources, in this order: the object placement that owns the block, then the dimension, region, surface biome, and cave biome the block sits in. Each source can add to the list, wipe it, or only contribute when nothing else did.

**Ambient mobs.** A background loop ticks each Iris world roughly twice a second. Each tick it measures how crowded the world is, and if there's room it picks a handful of loaded chunks and tries one spawn in each. A spawn attempt gathers every spawner the dimension, region, and surface biome list, throws out the ones whose time/weather/rate/crowding gates fail, pools their entries, picks exactly one, and places one to a few mobs.

Markers bolt the second pipeline onto the first: an object placement can tag specific blocks it places, and a marker definition attaches spawners to whatever carries that tag. That's how you get mobs that appear inside a specific ruin rather than anywhere in the biome.

Everything below is deterministic from the world seed and the block position, so the same chest at the same coordinates always contains the same items.

## Walkthrough: put custom loot in a dungeon chest

The goal is a chest inside one placed object that rolls your table and ignores the pack's global tables. Prerequisites: a validating pack, an `.iob` object containing at least one chest, and a biome that places it.

**1. Write the table.** `loot/tutorial/dungeon-cache.json`:

```json
{
  "name": "Tutorial Dungeon Cache",
  "rarity": 1,
  "minPicked": 2,
  "maxPicked": 4,
  "maxTries": 24,
  "loot": [
    { "type": "iron_ingot", "rarity": 1, "minAmount": 2, "maxAmount": 6 },
    { "type": "bread", "rarity": 1, "minAmount": 1, "maxAmount": 4 },
    { "type": "diamond", "rarity": 6, "minAmount": 1, "maxAmount": 2 },
    {
      "type": "iron_sword",
      "rarity": 8,
      "minDurability": 0.4,
      "maxDurability": 0.9,
      "displayName": "&7Rusted Blade",
      "enchantments": [{ "enchantment": "sharpness", "minLevel": 1, "maxLevel": 2, "chance": 0.5 }]
    }
  ]
}
```

`maxTries` needs headroom over `maxPicked`, because a try that lands on a rare entry and fails is spent. With four entries and rare items at 1-in-6 and 1-in-8, 24 tries reliably reaches 2-4 picks.

**2. Bind it to the placement.** In the biome that places your object, on the `objects[]` entry:

```json
{
  "place": ["tutorial/dungeon"],
  "chance": 0.02,
  "overrideGlobalLoot": true,
  "loot": [
    { "name": "tutorial/dungeon-cache", "weight": 1 }
  ]
}
```

`overrideGlobalLoot: true` means chests inside this object use only this table. Drop it if you want the pack's dimension and region tables mixed in.

**3. Verify.** Open the pack in Studio, find a placement, and open the chest. Every chest in that object should hold 2-4 stacks drawn from the four entries, with diamonds and swords showing up in roughly one placement in six and one in eight. To check without hunting for a placement, stand on a block and run `/iris studio loot` — it previews the tables that would fill a chest at your feet and adds debug lore naming the source table and its combined chance. That command is Bukkit and Studio only.

**4. If chests come up empty.** Check that the block is a storage chest — Iris only fills chest-family containers, and only ever requests the `STORAGE` slot type, so `FUEL`/`FURNACE`/`BLAST_FURNACE`/`SMOKER` entries never land in a generated container. Check `world.postLoadBlockUpdates` is on in `settings.json`; the container fill runs as part of that post-load pass. Check that the loot table key resolves — a missing table logs a warning and contributes nothing. Double chests fill from one half only (the one with the lower X, then lower Z), and that half fills the combined inventory, so an empty-looking half is normal.

## Walkthrough: make a custom mob spawn in one biome

The goal is a geared zombie that appears at night in one biome and drops a custom item. Prerequisites: a validating pack, a land biome with load key `tutorial/meadow`, and `world.ambientEntitySpawningSystem: true` (the default).

**1. Drop table.** `loot/tutorial/zombie-drops.json`:

```json
{
  "name": "Tutorial Zombie Drops",
  "minPicked": 1,
  "maxPicked": 1,
  "maxTries": 4,
  "loot": [
    { "type": "iron_nugget", "rarity": 1, "minAmount": 1, "maxAmount": 3 }
  ]
}
```

**2. Entity.** `entities/tutorial/zombie.json`:

```json
{
  "type": "minecraft:zombie",
  "surface": "LAND",
  "customName": "&cMeadow Stalker",
  "helmet": { "type": "leather_helmet", "rarity": 3, "leatherColor": "#3B5323" },
  "loot": { "tables": ["tutorial/zombie-drops"] }
}
```

The entity's `loot` replaces the mob's vanilla drop table outright. Only `tables` is read here — `mode` and `multiplier` on an entity are ignored.

**3. Spawner.** `spawners/tutorial/night-zombies.json`:

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

`timeBlock` hours are clock hours where 6 is sunrise and 18 is sunset, so `20` to `5` is night wrapping past midnight. `maximumRate` of 4 per 30 seconds becomes one attempt every 7.5 seconds for this spawner across the whole world.

**4. Attach it.** In `biomes/tutorial/meadow.json`, add the spawner key. This is a field excerpt, not a new file:

```json
{
  "entitySpawners": ["tutorial/night-zombies"]
}
```

**5. Verify.** Validate the pack first — the validator resolves the spawner-to-entity edge and will name the broken link before you load a world. Then open Studio, focus `tutorial/meadow`, set night, and stand somewhere with block light under 8. Success is named zombies appearing within a few seconds and dropping iron nuggets when killed. On Bukkit you can prove the entity file loads on its own with `/iris studio spawn tutorial/zombie`; on Fabric/Forge/NeoForge that command is not registered and reports that it is Bukkit-only, so go straight to ambient spawning.

**6. If nothing spawns.** Work down the gate list in order rather than raising `rarity` or the rate:

- World-wide crowding. If living entities divided by loaded chunks exceeds `world.targetSpawnEntitiesPerChunk` (0.95 by default, scaled by 1.28), Iris stops spawning for five seconds and logs it under debug. A test world full of mobs will starve your spawner.
- Chunk crowding. `maxEntitiesPerChunk` is compared against the living entities already in that chunk.
- Time and weather. Both are read from the world at attempt time.
- Light. The check only runs when `allowedLightLevels` is narrower than 0-15, and it reads the combined maximum of sky and block light, not block light alone. A `max: 7` spawner will not fire on a surface block in daylight.
- Group versus biome. A `NORMAL` spawner listed on a *dimension* is rejected in sea, shore, and cave biomes. Region- and biome-level spawners skip that check entirely and fire wherever their parent applies, so a mismatched `group` there produces mobs at odd heights rather than no mobs.
- Placement viability. The chosen block's `surface` must match the entity's `surface`, and the entity's bounding box must be clear air.

## Where files live

| Path | Class | Role |
|------|-------|------|
| `loot/<key>.json` | `IrisLootTable` | A pool of items with per-entry rarity, rolled as a unit |
| `entities/<key>.json` | `IrisEntity` | One mob: type, gear, flags, drops, passengers |
| `spawners/<key>.json` | `IrisSpawner` | The gates and rates that decide when entity keys appear |
| `markers/<key>.json` | `IrisMarker` | A tag that attaches spawners to individual blocks |

Keys are the pack-relative path without `.json`, so `loot/tutorial/dungeon-cache.json` is referenced as `tutorial/dungeon-cache`.

## What references what

| Holder | Field | Effect |
|--------|-------|--------|
| Dimension / region / biome | `loot` (`IrisLootReference`) | Contributes tables to every container in that scope |
| Object placement | `loot` (`IrisObjectLoot[]`) | Contributes one table to containers the object placed |
| Object placement | `vanillaLoot` (`IrisObjectVanillaLoot[]`) | Same, but the table is a real Minecraft loot table |
| Object placement | `overrideGlobalLoot` | Discards the dim/region/biome contribution for that placement |
| Entity | `loot` | Replaces the mob's vanilla drop table |
| Dimension / region / biome | `entitySpawners` | Runs those spawners for chunks in that scope |
| Object placement | `markers` (`IrisObjectMarker[]`) | Tags matching blocks the object placed |
| Marker | `spawners` | Runs those spawners at every block carrying the tag |
| Dimension / region / biome | `blockDrops` (`IrisBlockDrops[]`) | Adds or replaces drops when a player breaks a matching block |

Ambient spawning needs `world.ambientEntitySpawningSystem` true; marker spawning needs `world.markerEntitySpawningSystem` true. Both default to true. Iris spawners run alongside vanilla and mod spawning rather than replacing it, and nothing deduplicates between the two — a zombie spawner in your pack adds to whatever the server would have spawned anyway.

The shipping Overworld pack wires its spawners at region scope (`regions/*.json` list `<climate>/cave`, `/hostile`, `/passive`, `/water`) and ships no `markers/` folder. Its dimension-level loot uses `FALLBACK`, so `global-clutter` only reaches chests that nothing else claimed.

## How a container actually gets filled

Worth reading before tuning rarities, because two of these steps surprise people.

1. **Trigger.** After a chunk's mantle materializes tiles and custom blocks, the post-load update pass visits every block Iris flagged. Only storage chests proceed, and only the `STORAGE` slot type is ever requested. Objects placed into an already-live world (Studio placement, WorldEdit-driven placement) fill their chests immediately instead, on the region thread that owns the chunk.

2. **Source list.** Iris asks the object placement that owns the block for at most one table. Candidates are bucketed by how specifically they match: entries with an `exact` block-data filter that matches win outright; failing that, entries whose filter matches the block's material; failing that, entries with no filter. Within the winning bucket the pick is weighted by `weight`. `loot` and `vanillaLoot` entries compete in the same buckets. If the placement sets `overrideGlobalLoot` and a table was picked, that's the entire list.

3. **Environment sources.** Otherwise the dimension, region, and surface biome each inject their tables in that order, and a cave biome injects too when the container is below terrain height and resolves to a different biome than the surface. `ADD` appends. `CLEAR` and `REPLACE` both wipe the list first and then append their own tables — despite the field description, `CLEAR` does not suppress its own tables. `FALLBACK` injects only when nothing already claimed the container, meaning neither the placement's `loot`/`vanillaLoot` nor a pre-existing vanilla loot table on the block.

4. **Multiplier.** The multipliers from every contributing scope are multiplied together, and the resulting factor scales the *length of the table list*, not stack sizes. A factor of 0.5 randomly drops half the tables; a factor of 2 randomly duplicates entries until the list doubles. The list is capped at 256 sources and Iris throws rather than silently truncating past that.

5. **Event hook.** On Bukkit, `art.arcane.iris.core.events.IrisLootEvent` fires with the engine, block, slot type, and the resolved table list. The list is mutable, so a plugin can add, remove, or clear tables before the roll. Iris also bridges the rolled items through Bukkit's `LootGenerateEvent`, so plugins that already listen for vanilla loot generation see Iris chests too. This is not part of the documented public API surface in the `90`-series pages.

6. **Roll.** For each table: pick a random target count between `minPicked` and `maxPicked`, then loop up to `maxTries`. Each try picks a random entry index. The entry only counts if its `slotTypes` is `STORAGE` and it passes a 1-in-(table `rarity` x entry `rarity`) check. That check is derived from the loot seed, the table's key, the entry index, and the block coordinates — not from a running random sequence. **The same entry at the same block always gives the same answer**, so tries that re-roll a rare entry that already failed are wasted. This is why `maxTries` should sit well above `maxPicked` when a table has rare entries.

7. **Scatter.** Items are inserted, then one multi-item stack is split into a free slot and all slots are shuffled, so a chest reads like a hand-placed one instead of a left-packed block of stacks.

Modded servers run the same resolver and the same rarity math, so container contents match Bukkit for a given seed and position. Modded resolves `vanillaLoot` names against the server's loot-table registry directly; Bukkit resolves them through `Bukkit.getLootTable` and delegates the roll to Minecraft.

## Loot tables (`IrisLootTable`)

Folder: `loot/`.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `name` | string | `""` | Human label shown in Studio debug lore. Required, at least 2 characters. Not the lookup key — that's the file path |
| `rarity` | int >= 1 | `1` | Multiplied into every entry's rarity. Raise it to make a whole table rare without editing each entry; leave at 1 and tune entries individually |
| `minPicked` | int 0..64 | `1` | Floor of the random target count. Set to 0 when a chest is allowed to come out empty |
| `maxPicked` | int 1..64 | `5` | Ceiling of the random target count. This is the most items one table can contribute |
| `maxTries` | int 1..256 | `10` | How many entry draws are allowed before the table gives up. Raise it when entries have high rarity, or the table will routinely undershoot `minPicked` |
| `loot` | `IrisLoot[]` | `[]` | The entries. An empty table contributes nothing and is not an error |

### Loot entry (`IrisLoot`, snippet type `loot`)

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `type` | string | `""` | Required. A plain material name (`diamond`, `DIAMOND_SWORD`). Namespaced values are reserved for items from other plugins or mods, resolved through the external-data service — `minecraft:` prefixes are not the pattern here |
| `slotTypes` | `InventorySlotType` | `STORAGE` | Which inventory slot family the entry targets. Generated containers only ever request `STORAGE`, so `FUEL`, `FURNACE`, `BLAST_FURNACE`, and `SMOKER` entries are inert in world generation |
| `rarity` | int >= 1 | `1` | 1-in-N chance for this entry, multiplied by the table's rarity. Use it to make one entry rare inside an otherwise common table |
| `minAmount` / `maxAmount` | int 1..64 | `1` / `1` | Inclusive stack-size range rolled per pick |
| `displayName` | string | null | Item name. `&` colour codes are translated |
| `lore` | string[] | `[]` | Lore lines. Lines over 24 characters are word-wrapped into several lines |
| `minDurability` / `maxDurability` | 0..1 | `0` / `1` | Fraction of durability *remaining*, rolled per pick. `0`/`1` gives anything from nearly broken to pristine; set both to 1 for undamaged gear |
| `customModel` | int | null | Custom model data, written into the item's model-data component as a float. For resource packs that key off model data |
| `unbreakable` | boolean | `false` | Marks the item unbreakable |
| `itemFlags` | string[] | `[]` | Bukkit `ItemFlag` names, for hiding enchantments or attributes in the tooltip. Unrecognised names are skipped silently |
| `enchantments` | `IrisEnchantment[]` | `[]` | Each has its own level range and application chance |
| `attributes` | `IrisAttributeModifier[]` | `[]` | Attribute modifiers baked into the item |
| `dyeColor` | string | null | A `DyeColor` name, applied to items that are colourable |
| `leatherColor` | string | null | `#RRGGBB`, applied to leather armour |
| `customNbt` | object | null | Raw platform NBT merged into the item. Also carries the payload for namespaced third-party items |

When Studio debug is on (`/iris studio loot`, or any roll in a Studio world), each item gains lore naming the source table and the combined 1-in-N chance. That lore is not written in production worlds.

### Loot reference (`IrisLootReference`, snippet type `loot-registry`)

The shape used by dimension, region, biome, and entity `loot` fields:

```json
{
  "loot": {
    "mode": "FALLBACK",
    "multiplier": 0.5,
    "tables": ["temperate/clutter", "temperate/food"]
  }
}
```

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `mode` | `IrisLootMode` | `ADD` | How this scope's tables combine with the scopes above it — see below |
| `tables` | string[] | `[]` | Loot table keys. A key that doesn't resolve is skipped |
| `multiplier` | double 0..16 | `1` | Scales how many tables end up in the final list for containers in this scope. Below 1 randomly drops tables, above 1 randomly duplicates them. Multiplied across every contributing scope |

| Mode | Behaviour |
|------|-----------|
| `ADD` | Append these tables to whatever the outer scopes contributed. The default and the right choice most of the time |
| `REPLACE` | Wipe the list, then append these. Use on a biome that should ignore the dimension's global tables |
| `CLEAR` | Identical to `REPLACE` in code: wipe, then append these. The field description claims it also suppresses its own tables; it does not |
| `FALLBACK` | Only contribute when nothing already claimed the container — no object `loot`, no object `vanillaLoot`, no native loot table on the block. This is how a pack ships a broad filler table without stepping on structure chests |

Entities read only `tables` from this object. Setting `mode` or `multiplier` on an entity's `loot` has no effect.

### Object loot

On `IrisObjectPlacement` (see `20 - Object Placement.md`):

| Field | What it does |
|-------|--------------|
| `loot` | `IrisObjectLoot[]` — Iris tables offered to containers this placement created |
| `vanillaLoot` | `IrisObjectVanillaLoot[]` — Minecraft loot table keys offered the same way |
| `overrideGlobalLoot` | When a table was picked from this placement, stop there and skip dimension, region, and biome tables |

Both entry types share the same fields:

| Field | Default | What it does |
|-------|---------|--------------|
| `name` | required | The Iris loot table key, or for `vanillaLoot` a namespaced Minecraft loot table key such as `minecraft:chests/simple_dungeon` |
| `weight` | `1` | Relative odds inside its match bucket. Entries with weight 0 or below are ignored entirely |
| `filter` | `[]` | Blocks this entry applies to. Empty means every container the object placed |
| `exact` | `false` | Compare the whole block state instead of just the material. An exact match outranks a material match, which outranks an unfiltered entry |

One table is picked per container, not one per entry — the buckets decide which entries are eligible and `weight` decides between them.

Authored container contents and deferred custom-block identifiers stay in the mantle until the platform's post-load materialization pass runs for that chunk. Generic cleanup and pregeneration cleanup preserve those sparse payloads for chunks that have not reached the pass yet, and on Bukkit a region-scheduled pass that fails stays retryable without repeating the passes that already completed.

## Entities (`IrisEntity`)

Folder: `entities/`. A minimal entity is one field:

```json
{ "type": "ZOMBIE" }
```

`entities/standard/hostile/zombie.json` is referenced as `standard/hostile/zombie`.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `type` | string | null | Required. The entity type key (`minecraft:zombie`, `alexsmobs:grizzly_bear`, or a bare name). Set it to `unknown` and fill `specialType` when the mob comes from a plugin instead |
| `specialType` | string | `""` | `PluginName:MobName`, spawned through the external-data service. Mythic Mobs and similar providers plug in here |
| `applySettingsToCustomMobAnyways` | boolean | `false` | By default Iris hands a `specialType` mob straight back to its provider untouched. Turn this on to layer Iris gear, names, and flags on top |
| `reason` | string | null | The `SpawnReason` reported to other plugins. Unset or unrecognised becomes `NATURAL`. Change it when another plugin gates on spawn reason |
| `customName` | string | `""` | Name tag, with `&` colour codes |
| `customNameVisible` | boolean | `false` | Show the name without looking at the mob |
| `aware` | boolean | `true` | Whether the mob reacts to the world. Off makes a decorative mob that stands still but still animates |
| `ai` | boolean | `true` | Whether the mob has AI goals at all. Off is a harder freeze than `aware: false` |
| `glowing` | boolean | `false` | Outline visible through blocks |
| `gravity` | boolean | `true` | Off leaves the mob floating where it spawned |
| `invulnerable` | boolean | `false` | Only creative-mode players can damage it |
| `silent` | boolean | `false` | Suppresses the mob's sounds |
| `pickupItems` | boolean | `false` | Whether it can pick up dropped gear |
| `removable` | boolean | `false` | Whether the server may despawn it when players leave. Off keeps a set-piece mob alive |
| `keepEntity` | boolean | `false` | Forces persistence. Also forced globally by `world.forcePersistEntities` |
| `baby` | boolean | `false` | Spawns the baby variant for ageable types |
| `helmet` / `chestplate` / `leggings` / `boots` / `mainHand` / `offHand` | `IrisLoot` | null | One equipment slot each, built like a loot entry. The entry's own `rarity` is a 1-in-N roll for whether the slot gets filled at all — that's how you get "one in five wears a helmet" |
| `passengers` | `IrisEntity[]` | `[]` | Riders, spawned and mounted after the host. Nests, so a rider can carry a rider |
| `attributes` | `IrisAttributeModifier[]` | `[]` | Attribute modifiers applied to the mob |
| `loot` | `IrisLootReference` | empty | Drop tables. Replaces the mob's vanilla drops. Only `tables` is read |
| `leashHolder` | `IrisEntity` | null | Spawns a second entity and leashes this one to it. No effect on ender dragons, withers, players, or bats |
| `spawnEffect` | `IrisEffect` | null | A one-shot effect fired at the spawn position |
| `spawnEffectRiseOutOfGround` | boolean | `false` | Spawns the mob five blocks lower when a player is nearby and walks it up out of the ground with block-crack particles. The mob is invulnerable and AI-less for up to five seconds while rising |
| `pandaMainGene` / `pandaHiddenGene` | string | null | Panda genes. Unrecognised names fall back to `NORMAL` |
| `surface` | `IrisSurface` | `LAND` | What the block under the spawn point must be — see below. Marker-driven spawns skip this check |
| `rawCommands` | `IrisCommand[]` | `[]` | Console commands run after the mob spawns |

`IrisSurface` values, checked against the block directly below the spawn position:

| Value | Matches |
|-------|---------|
| `LAND` | Any solid block |
| `ANIMAL` | Grass block, dirt, dirt path, coarse dirt, rooted dirt, podzol, mycelium, or snow block. Narrower than `LAND` on purpose, for passive mobs |
| `WATER` | Water, seagrass, kelp, or any waterlogged block |
| `OVERWORLD` | Any solid block or any water block — use it when a spawner should work on shore and in shallows |
| `LAVA` | Lava |

### Entity drops in practice

Both platforms replace the mob's vanilla drop table rather than adding to it, and the tables are rolled at the mob's **spawn** coordinates with the `STORAGE` slot type. Because entry rarity is position-derived, every mob spawned on the same block rolls identical drops. Vary `minAmount`/`maxAmount` if you want visible variation from a single-entry table.

On Bukkit a synthetic loot table is bound to the mob. On modded the mob carries an `iris_loot|…` tag and Iris emits the items on death instead of the base table. Modded also fills chest-carrying vehicles directly at spawn; for any other entity type that exposes no lootable path it logs one warning per type and skips.

### Entity commands (`IrisCommand`, snippet type `command`)

`rawCommands` runs console commands after a mob spawns. The same object is used by ambient effect command registries.

| Field | Default | What it does |
|-------|---------|--------------|
| `commands` | `[]` | Required. Command strings. A leading `/` is stripped, and `{x}`, `{y}`, `{z}` are replaced with the spawn block coordinates |
| `delay` | `0` | Server ticks before the first run. Negative values clamp to zero |
| `repeat` | `false` | Repeat forever after the first run. There is no cancel handle, and repeats do not survive a restart — they only exist for as long as the server stays up after the chunk generated |
| `repeatDelay` | `100` | Server ticks between repeats. Values below 1 clamp to 1 |
| `timeBlock` | any time | World-time window the command is allowed in |
| `weather` | `ANY` | Required weather: `NONE`, `DOWNFALL`, `DOWNFALL_WITH_THUNDER`, or `ANY` |

`timeBlock` and `weather` are evaluated once, when the command object first runs. A repeating command keeps repeating after its window closes.

Bukkit and modded both apply AI and awareness flags, spawn effects, and raw commands, and both enforce spawner time and weather gates.

## Ambient effects (`IrisEffect`, snippet type `effect`)

Biomes and regions accept `effects[]`. Each entry runs at most once per `interval` milliseconds and, when it runs, has a 1-in-`chance` shot at firing. A single entry can apply a potion, play a sound, emit particles, and run commands. The whole system is gated by `world.effectSystem` in `settings.json`.

| Field | Default / range | What it does |
|-------|-----------------|--------------|
| `interval` | `150` ms, >= 0 | Minimum gap between attempts. Raise it for anything expensive or loud |
| `chance` | `50`, >= 1 | One attempt in this many actually fires. Combined with `interval` this is your real frequency |
| `potionEffect` | `""` | Potion effect registry key. An unknown key falls back to `LUCK` and logs a warning |
| `potionStrength` | `-1` (-1..1024) | Amplifier. `-1` disables potion application entirely, which is the default |
| `potionTicksMin` / `potionTicksMax` | `75` / `155` | Random potion duration in ticks |
| `sound` | null | Sound registry key |
| `soundDistance` | `12` (0..512) | How far from the player the sound origin can be offset. Larger values make the source feel distant and directionless |
| `minPitch` / `maxPitch` | `0.5` / `1.5` (0.01..1.99) | Random pitch range |
| `volume` | `1.5` (0.001..512) | Sound volume |
| `particleEffect` | null | Particle registry key. Modded supports particle types that need no extra particle data |
| `particleOffset` | `0` (-32..32) | Random vertical offset from the sampled surface |
| `particleCount` | `0` (0..512) | Particle count. Zero is meaningful: on Bukkit it makes the alt XYZ values behave as velocity instead of spread |
| `particleDistance` | `20` (0..64) | How far ahead of the player particles are sampled |
| `particleDistanceWidth` | `24` (0..128) | Sampling radius left and right of the player |
| `particleAway` | `5` (0..16) | Minimum forward offset, so particles don't spawn in the player's face |
| `particleAltX` / `particleAltY` / `particleAltZ` | `0` (-8..8) | Spread, or velocity when `particleCount` is 0 |
| `randomAltX` / `randomAltY` / `randomAltZ` | `true` / `false` / `true` | Randomize each alt component between its negative and positive value. Y defaults off so vertical drift stays deliberate |
| `extra` | `0` | Particle-specific extra value, meaningful only for some particle types |
| `commandRegistry` | null | Commands to cast alongside the effect |

`IrisCommandRegistry` (snippet type `command-registry`):

| Field | Default | What it does |
|-------|---------|--------------|
| `rawCommands` | `[]` | `IrisCommand[]` to run |
| `commandOffsetX` / `commandOffsetY` / `commandOffsetZ` | `0` (-8..8) | Offsets from the player for the `{x} {y} {z}` substitutions |
| `commandRandomAltX` / `commandRandomAltY` / `commandRandomAltZ` | `true` / `false` / `true` | Randomize each coordinate within its signed offset |
| `commandAllRandomLocations` | `true` | Re-roll the coordinate for each command object. Set false to fire every command at one shared point |

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

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `spawns` | `IrisEntitySpawn[]` | `[]` | The ongoing spawn pool. Every entry competes with entries from every other eligible spawner in the same chunk |
| `initialSpawns` | `IrisEntitySpawn[]` | `[]` | A separate pool used once per chunk, the first time that chunk is maintained. For set dressing that should exist from the moment a chunk appears |
| `maxEntitiesPerChunk` | int | `1` | Skip this spawner when the target chunk already holds this many living entities. The single most common reason a spawner looks dead — the default of 1 means almost any occupied chunk blocks it |
| `timeBlock` | `IrisTimeBlock` | any time | World-time window. Clock hours, 6 = sunrise, 18 = sunset |
| `weather` | `IrisWeather` | `ANY` | `NONE`, `DOWNFALL`, `DOWNFALL_WITH_THUNDER`, or `ANY` |
| `maximumRate` | `IrisRate` | infinite | World-wide throttle for this spawner. Stamped only when a spawn actually succeeds |
| `maximumRatePerChunk` | `IrisRate` | infinite | Same throttle, tracked per chunk. Use it to stop one chunk hogging a generous global rate |
| `allowedLightLevels` | `IrisRange` | `0`..`15` | Inclusive light range. Skipped entirely when left at the full range. Measured as the combined maximum of sky and block light |
| `group` | `IrisSpawnGroup` | `NORMAL` | Where in the column mobs are placed, and which biomes accept this spawner at dimension scope |

`IrisSpawnGroup`:

| Value | Position chosen | Biome check (dimension scope only) |
|-------|-----------------|------------------------------------|
| `NORMAL` | Random x/z in the chunk, one block above the fluid-inclusive surface | Land biomes only |
| `CAVE` | A random `cave_floor` mantle marker in the chunk, one block up | Accepted in every biome type |
| `UNDERWATER` | Random x/z, random Y between the solid top and the water surface | Sea biomes only |
| `BEACH` | Same water-column position as `UNDERWATER` | Shore biomes only |

The biome check only applies to spawners listed on a **dimension**. Region and biome `entitySpawners` bypass it, so a `CAVE`-group spawner listed on a surface biome will still try to find cave floor markers there and quietly do nothing if there are none.

On Folia, `CAVE` group spawners never fire on Bukkit — the cave-floor marker lookup returns nothing off the region thread and the spawn is skipped.

`IrisRate` (snippet type `rate`):

| Field | Default | What it does |
|-------|---------|--------------|
| `amount` | `0` | How many firings the duration allows. The effective cooldown is `per` divided by `amount` (or by 1 when `amount` is 0) |
| `per` | empty | The window. **An empty `per` means unlimited** — that's what makes a rate infinite, not `amount` |

`IrisDuration` (snippet type `duration`) sums every field you fill in:

| Field | Real time per unit |
|-------|--------------------|
| `milliseconds` | 1 ms |
| `minecraftTicks` | 50 ms |
| `seconds` / `minutes` / `hours` / `days` | Real-world units |
| `minecraftHours` | 50 s |
| `minecraftDays` | 20 min |
| `minecraftWeeks` | 2 h 20 min (7 Minecraft days) |
| `minecraftLunarCycles` | 2 h 40 min (8 Minecraft days) |

`IrisTimeBlock` (snippet type `time-block`): `startHour` and `endHour` in 24-hour clock time, where the world's tick 0 reads as hour 6. Setting both to the same value means any time; setting both to `-1` means never. A `startHour` greater than `endHour` wraps past midnight.

### Entity spawn entry (`IrisEntitySpawn`, snippet type `entity-spawn`)

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `entity` | string | `""` | Required. The entity key |
| `rarity` | int >= 1 | `1` | Inverse weight. All eligible entries from all eligible spawners go into one pool and each entry gets `totalRarity / rarity` slots, so low numbers are common and high numbers are rare. Exactly one entry wins per chunk attempt |
| `minSpawns` / `maxSpawns` | int >= 1 | `1` / `1` | Inclusive range of placement attempts once this entry wins. Each attempt can still fail the surface, light, or clearance check, so this is a ceiling not a guarantee |

Modded applies `rarity` twice — once as the pool weight and again as a 1-in-N roll at each candidate position — so high-rarity entries spawn somewhat less often on Fabric/Forge/NeoForge than on Bukkit for the same numbers.

Real Overworld spawner, `spawners/temperate/hostile.json`:

```json
{
  "group": "NORMAL",
  "maximumRate": { "amount": 8, "per": { "seconds": 15 } },
  "timeBlock": { "startHour": 20, "endHour": 3 },
  "maxEntitiesPerChunk": 2,
  "weather": "ANY",
  "spawns": [
    { "entity": "standard/hostile/zombie", "rarity": 5, "maxSpawns": 4, "minSpawns": 2 },
    { "entity": "standard/hostile/skeleton", "rarity": 30, "maxSpawns": 2, "minSpawns": 1 },
    { "entity": "standard/neutral/enderman", "rarity": 60, "maxSpawns": 2, "minSpawns": 1 }
  ]
}
```

Attach it on a dimension, region, or biome:

```json
{
  "entitySpawners": ["temperate/hostile", "temperate/passive"]
}
```

### The ambient tick

The loop runs once per Iris world every `world.asyncTickIntervalMS` milliseconds (700 by default, 3000 when both spawn systems are off). Each pass:

1. Recount living entities in the world, throttled so it doesn't run every tick. If the count can't be completed — the scheduler refuses the task, or it times out — Iris pauses spawning entirely rather than guessing. This is deliberate: an incomplete count must never authorize a spawn.
2. Compute saturation as living entities divided by loaded chunks plus one, scaled by 1.28. Above `world.targetSpawnEntitiesPerChunk` the pass sleeps 5 seconds and returns.
3. Pick between 2 and 12 random loaded chunks and run one spawn attempt in each, on the region thread that owns the chunk.
4. Pregeneration and world maintenance suppress spawning for that world entirely while they run.

In Studio worlds, spawning additionally requires `studio.entitySpawning`.

`initialSpawns` runs from the chunk-maintenance pass, once per chunk, guarded by a mantle flag so it never repeats. That pass returns early when `world.markerEntitySpawningSystem` is off, so `initialSpawns` needs both spawn settings enabled even though it isn't marker-driven.

## Markers (`IrisMarker`)

Folder: `markers/`. A marker is a tag written into the mantle at a block position; the marker file says which spawners fire there.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `spawners` | string[] | `[]` | Spawner keys. One is picked at random each time the marker fires |
| `removeOnChange` | boolean | `true` | Delete the marker when a player breaks the block it sits on. Leave on unless you want a spawn point that survives being mined out |
| `emptyAbove` | boolean | `true` | Require two non-solid blocks above. Checked twice — see below |
| `exhaustionChance` | double | `0` | Odds the marker deletes itself when it fires. `0.25` averages four uses. Anything at or below 0 never exhausts; 1 or higher exhausts on the first use |

`emptyAbove` is checked at two different times against two different things. When the object is placed, Iris asks whether *the object itself* defines blocks one and two above the candidate — so a marker on a floor block under the object's own ceiling is never written. When the marker later fires, the scanner re-checks against the *live world*, and a marker that has since been buried is deleted from the mantle rather than skipped.

### Placing markers from objects (`IrisObjectMarker`, snippet type `object-marker`)

On an object placement's `markers[]`:

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `mark` | `IrisBlockData[]` | required | Block types to tag. Candidate blocks are shuffled, so which matching blocks get tagged varies by placement |
| `marker` | string | required | The marker key to attach |
| `maximumMarkers` | int 1..16 | `8` | Cap for this entry across all its `mark` types. Keep it low — each marker is a per-block mantle write and a per-chunk scan cost |
| `exact` | boolean | `false` | Match the full block state instead of just the material |

```json
{
  "place": ["dungeons/crypt"],
  "chance": 0.01,
  "markers": [
    { "mark": [{ "block": "minecraft:spawner" }], "marker": "crypt-spawns", "maximumMarkers": 4 }
  ]
}
```

When marker spawning is on, each chunk pass reads the mantle markers in that chunk, skips the engine's internal `cave_floor` and `cave_ceiling` tags, loads each `IrisMarker`, drops obstructed ones, picks one of that marker's spawners at random, and fires it at the marker position. Marker spawns bypass the entity's `surface` check and the bounding-box clearance check — the marker is taken as authoritative about the position being valid — but they still honour `allowedLightLevels` and the spawner's time, weather, and rate gates. `exhaustionChance` is rolled once per firing, before the mobs are placed.

## Custom block drops (`IrisBlockDrops`, snippet type `block-drops`)

Dimensions, regions, and biomes accept `blockDrops[]`. When a player breaks a block, matching providers from the biome run first; unless a matching biome provider sets `skipParents`, matching region and then dimension providers are appended.

| Field | Default | What it does |
|-------|---------|--------------|
| `blocks` | `[]` | Required. Block types this rule reacts to |
| `exactBlocks` | `false` | False matches on material alone, so any barrel matches `minecraft:barrel`. True requires the full block state, so `minecraft:barrel[axis=x]` matches only that orientation |
| `drops` | `[]` | `IrisLoot[]`. Each entry rolls its own `rarity` independently — unlike loot tables there's no pick count or try budget, so every entry gets exactly one chance |
| `skipParents` | `false` | On a matching biome provider, stops region and dimension providers running for this break. Use it when a biome needs to fully own a block's drops |
| `replaceVanillaDrops` | `false` | If any matching provider sets this, vanilla drops are suppressed while Iris drops from every selected provider still fire |

```json
{
  "blocks": [{ "block": "minecraft:stone" }],
  "exactBlocks": false,
  "drops": [{ "type": "flint", "rarity": 4 }],
  "skipParents": false,
  "replaceVanillaDrops": false
}
```

This runs on Bukkit and on Fabric, Forge, and NeoForge. Each loader hooks its own block-break path and routes the result through the same rule evaluation.

## Runtime settings that gate these systems

From `settings.json` under `world` (see `03 - Configuration.md`):

| Key | Default | Effect |
|-----|---------|--------|
| `postLoadBlockUpdates` | `true` | Drives the pass that fills generated containers. Off means no chest loot |
| `ambientEntitySpawningSystem` | `true` | Dimension, region, and biome `entitySpawners` |
| `markerEntitySpawningSystem` | `true` | Marker-driven spawners, and the chunk pass that runs `initialSpawns` |
| `effectSystem` | `true` | Biome and region `effects[]` |
| `targetSpawnEntitiesPerChunk` | `0.95` | Saturation ceiling. Lower it on busy servers to stop Iris adding to entity load |
| `asyncTickIntervalMS` | `700` | How often the spawn loop runs per world |
| `forcePersistEntities` | `true` | Marks every Iris-spawned entity persistent regardless of `keepEntity` |

## Checklist

1. Write `loot/<key>.json` tables. Give `maxTries` room above `maxPicked` if any entry is rare.
2. Reference them from `loot.tables` on a dimension, region, or biome, or from `loot[].name` on an object placement.
3. Write `entities/<key>.json` with at least `type`. Set `surface` to match where the mob belongs.
4. Write `spawners/<key>.json`. Raise `maxEntitiesPerChunk` above the default of 1 unless you genuinely want one mob per chunk.
5. Add spawner keys to `entitySpawners` on a dimension, region, or biome, or attach them through markers on an object placement.
6. Validate the pack — the spawner-to-entity edge is a blocking check, so a typo is caught before you load a world.
7. Open Studio and confirm in-world. Use `/iris studio loot` for chest previews on Bukkit.

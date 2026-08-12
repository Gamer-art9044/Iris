# 22 - Native Structures & Datapacks

Structures that come from outside Iris packs: vanilla structures in Iris worlds, datapack structures, the Minecraft structure-block and `.nbt` system, and converting native structures into editable Iris resources. Objects and Iris jigsaws are covered in `19 - Objects.md`, `20 - Object Placement.md`, and `21 - Jigsaw Structures.md`.

Terminology:

- **registered / native structure** — anything in Minecraft's live structure registry (vanilla, mod, or datapack). Keys are namespaced: `minecraft:village_plains`, `towns_and_towers:village_ocean`.
- **Iris structure** — an editable `structures/<key>.json` inside a pack.

Command listings are Bukkit/Paper; modded loaders expose a reduced set.

## Pick a task

The four things people actually want are separate workflows. None of them requires the others.

| Goal | Go to |
|---|---|
| A vanilla structure generates but sits badly in Iris terrain | Task 1 |
| Add a third-party datapack (Terralith-style) to one Iris dimension | Task 2 |
| Stop a structure or a whole namespace from generating | Task 3 |
| Keep a datapack's buildings but choose where they go yourself | Task 4 |
| Edit a registered structure's blocks or graph inside Iris | Task 5 |

Every task below changes newly generated chunks only. Nothing rewrites existing terrain or existing starts.

## Task 1: Make a vanilla structure fit Iris terrain

Native placement stays in charge of where and how often; you only adjust how the structure meets the ground.

Prerequisite: the structure appears in `/iris structure list <dimension>`.

1. Find the registered key with `/iris structure list <dimension>`.
2. Run `/iris structure verify <dimension> radius=48` and confirm the key reports `[native-eligible]`, not `[disabled]` or `[unreachable]`.
3. Merge one narrow `importedStructures.adjustments` entry into the declaring dimension. Start with one exact key and one operation such as `yShift`, `preserveSourceY`, or a terrain mode. This example changes only plains villages:

   ```json
   {
     "importedStructures": {
       "adjustments": [
         {
           "match": ["minecraft:village_plains"],
           "terrain": { "mode": "VACUUM" }
         }
       ]
     }
   }
   ```

4. Validate the pack, reopen Studio or update the test world's pack snapshot, then generate new chunks.
5. Locate the key and inspect several starts.

**Success:** newly generated starts keep their native blocks, entities, processors, and loot, and the requested terrain operation is visible. Existing chunks are unchanged.

Widen from an exact key to a prefix only after the exact-key test passes, because a namespace or family prefix can hit many variants at once. If verify flips to `[disabled]`, remove the matching disable entry. If it says `[unreachable]`, fix the biome derivative mapping (see 1.2) before touching terrain.

Field details are in 1.5.

## Task 2: Install a third-party datapack for one Iris dimension

This Bukkit-family workflow keeps the datapack installed in Minecraft's global registry while Iris scopes the managed structure sets to the dimensions that declare the source.

Prerequisites: a disposable Bukkit-family server, one declaring Iris dimension, one nondeclaring Iris dimension, and a vanilla control world.

1. Add the Modrinth or direct archive URL to `datapackImports` in the declaring dimension only:

   ```json
   {
     "datapackImports": [
       "https://modrinth.com/datapack/towns-and-towers"
     ]
   }
   ```

2. Leave the URL out of the second test dimension, and keep the vanilla world as a control.
3. Validate the pack, then run:

   ```text
   /iris datapack ingest restart=true
   ```

4. After the full restart, run `/iris datapack list`, then `/iris structure list <declaring-dimension>` and pick one registered structure key from that source.
5. Run `/iris structure verify <declaring-dimension> radius=48`. If the key is `[unreachable]`, set a compatible `vanillaDerivative` on an Iris biome before the generation test (see 1.2).
6. Create fresh declaring and nondeclaring Iris worlds. In all three worlds, run `/locate structure <key>` and generate new chunks.

**Success:** the declaring Iris world locates and naturally generates the structure; the nondeclaring Iris world and the vanilla world do neither. Restart without deleting the installed datapack and confirm the same result.

If the key is absent after ingest, check that the managed pack appears in `/iris datapack list` and that the requested restart actually completed. Registry keys are never live on the boot that installs them. Removing a URL changes future per-world scope after a restart; it does not delete existing chunks or generated structures. Declaring the same URL in two Iris dimensions deliberately enables the source in both.

## Task 3: Turn a structure off

1. Get the exact key from `/iris structure list <dimension>`.
2. Add it to `importedStructures.disabled` (family match) or `importedStructures.disabledExact` (that key only) in the declaring dimension:

   ```json
   {
     "importedStructures": {
       "disabled": ["minecraft:village", "minecraft:pillager_outpost"],
       "disabledExact": ["minecraft:ruined_portal"]
     }
   }
   ```

3. Validate the pack, update the world snapshot or open a fresh world, and restart.
4. Run `/iris structure verify <dimension> radius=48`.

**Success:** the key reports `[disabled]`, and `/iris goto structure <key>` answers that it is disabled by this dimension's `importedStructures` settings. New chunks no longer contain it; old chunks keep whatever already generated.

Two things catch people out. A namespace disable needs the trailing colon — `"nova_structures:"` works, `"nova_structures"` does not (see the prefix rules in 1.3). And neither deny list blocks an explicit `nativeStructures` placement, which is exactly what Task 4 relies on.

## Task 4: Place registered structures only where Iris says

Use this when a datapack's buildings should exist but its own structure sets should not decide where. Complete Task 2 first so the source is ingested and the server has restarted.

1. Confirm the keys with `/iris structure list <dimension>`.
2. Merge this into that dimension. The `disabled` entry kills the datapack's own generation; the placement puts the building back on an Iris grid:

   ```json
   {
     "datapackImports": [
       "https://modrinth.com/datapack/dungeons-and-taverns"
     ],
     "importedStructures": {
       "disabled": ["nova_structures:"]
     },
     "structures": [
       {
         "placementId": "tutorial-native-tavern",
         "nativeStructures": [
           { "structure": "nova_structures:tavern_oak", "weight": 1 }
         ],
         "distribution": "RANDOM_SPREAD",
         "spacing": 24,
         "separation": 6,
         "salt": 776215551
       }
     ]
   }
   ```

3. Validate the pack: `/iris pack validate pack=<dimension>`.
4. Open a fresh test world, or update the world's pack snapshot and restart.
5. Run `/iris structure verify <dimension> radius=48`. The tavern must report `[iris-planned]`, not `[disabled]`, because an explicit placement bypasses the deny list.
6. Run `/iris goto structure nova_structures:tavern_oak`, generate the planned chunk, and inspect its native processors, entities, spawners, and loot.
7. Generate several more grid cells.

**Success:** the structure appears at Iris-planned starts with full native fidelity and nowhere else.

If validation cannot resolve the key, the datapack is not live in the registry for that dimension — go back to Task 2 and finish the restart and scope check. If verify reports `[iris-not-found]`, raise the radius or lower the spacing for the test. Existing natural starts stay in old chunks after the namespace is disabled.

Field details are in section 3.

## Task 5: Convert a registered structure for editing

Conversion is deliberately less faithful than Minecraft's native runtime, so use it only when block geometry or graph topology has to change. `nativeStructures` keeps native processors, entities, spawners, loot, and placement behavior; conversion does not.

1. Confirm the registered source generates natively first.
2. Back up the target pack. For one registered jigsaw graph, run:

   ```text
   /iris jigsaw convert <dimension> <namespace:path> target=auto seed=1337
   ```

3. Conversion follows the registered start pool and the reachable template-pool closure, writes a new add-only owned Iris graph, reports the imported piece and pool counts plus the fidelity-warning count, then opens Jigsaw Studio. `target=auto` turns `minecraft:village_plains` into `minecraft_village_plains`; pass `target=<iris-path>` for a deliberate key.
4. Load each variant from the Studio control chest or triple-sneak menu and inspect its real blocks and Mojang marker fields. Once a workcell finishes loading and hydrating, block and container changes autosave; **Save Now** only forces an immediate flush. Review the automatic seed-`1337` evaluation and the permanent read-only block preview before accepting the fidelity.
5. For a non-jigsaw template or a bulk pass, use `/iris structure import <dimension>` instead (section 5). Review every per-structure result: successful bundles can sit alongside failures.

**Success:** the owned copy reopens with `/iris jigsaw open <dimension> <target>` and its preview matches the native original closely enough for your purpose.

`/iris jigsaw convert` accepts only a live registered jigsaw structure and refuses an occupied target. Both it and `/iris structure import` record source provenance and fidelity losses.

### What conversion changes about pools

A native list pool entry stays one weighted choice. Iris keeps its recursively first physical template and outer connectors, and omits later colocated children and their processors with a `LIST_ELEMENTS` warning instead of turning them into separate alternatives.

Every start-pool member stays a physical Iris piece even when it has no connectors. An all-air template with at least one connector stays a non-collidable scaffold so its bounds may overlap attached physical pieces. Every non-start connectorless member in a pool with a distinct fallback also stays physical, regardless of pool size or air content, so weighted primary no-match attempts still reach that fallback; a retained all-air member stays non-collidable.

A singleton all-air connectorless member with no fallback or a self-fallback becomes an explicit empty entry, recorded as `connectorless_all_air_member_normalized_empty`. The observed waystone form is the self-fallback case. The same member in a mixed no/self-fallback pool is omitted instead, recorded as `connectorless_all_air_mixed_member_omitted`, because converting it to empty could terminate the branch before later candidates get a chance; that loss records the changed selection weights and RNG consumption. Other connectorless nonempty members in no/self-fallback non-start pools are omitted as unattachable with `connectorless_non_air_member_omitted`, and that block loss also records exact fallback context plus selection-weight and RNG-consumption drift.

Converted graphs explicitly use `branchFailurePolicy: TERMINATE_BRANCH`: once ordinary primary and direct-fallback candidates are exhausted only that optional branch ends, while required physical fallbacks still fail. Explicit empty members and empty optional primary pools end the branch before the direct fallback is tried.

Native placement settings beyond start pool, maximum depth, and maximum distance may not survive conversion, and neither may feature pool elements, alternate palettes, processors, entities, or other native-only behavior.

## 1. Vanilla structures in Iris worlds

### 1.1 Default: everything generates

Every registered structure generates through its own native placement unless its key is disabled or a dimension-level Iris placement replaces its source. Changes affect newly generated chunks only.

### 1.2 Biome mapping for structure filters

Vanilla tests biomes against structure biome filters. Iris answers per Iris biome:

| Field on biome | Default | Purpose |
|---|---|---|
| `derivative` | `minecraft:the_void` | Vanilla biome this Iris biome reports generally. |
| `vanillaDerivative` | unset | Optional override for structure selection, spawn tables, imported features, and biome tags. Wins when set. |

Refinements: a sea-role biome whose derivative is not ocean- or river-like resolves to `minecraft:the_void`, and a shore-role biome falls back to `minecraft:beach`. **Non-`minecraft:` namespaces pass through** — point `vanillaDerivative` at a datapack or mod biome key that exists in the live registry.

A datapack structure whose filter lists only its own biomes never generates until an Iris biome reports one of those keys through `vanillaDerivative`. `/iris structure verify` reports `[unreachable] <key> needs <biomes>`.

```json
{
  "derivative": "minecraft:plains",
  "vanillaDerivative": "towns_and_towers:some_custom_biome"
}
```

### 1.3 `importedStructures` (dimension)

| Field | Default | Meaning |
|---|---|---|
| `disabled` | `[]` | Structure keys and prefixes to deny. |
| `disabledExact` | `[]` | Complete structure keys to deny without matching related variants. |
| `undergroundYShift` | `0` (-512..512) | Vertical offset for underground-step structures only. Surface structures never use it. |
| `datapackOverrides` | `true` | Whether ingested datapacks may replace `minecraft:`-namespaced structure content (2.5). |
| `frequencyOverrides` | `[]` | Exact structure-set placement-density multipliers (1.4). |
| `adjustments` | `[]` | Per-structure adjustments for structures still generating natively (1.5). |

#### Prefix matching

Used by `disabled` and `adjustments[].match`. Both sides are trimmed and lowercased, the key must start with the pattern, and then:

- Equal length → match.
- Pattern ends with `:`, `/`, or `_` → match (so `"nova_structures:"` disables a namespace).
- Otherwise the next character after the pattern must be `/` or `_`.

`"minecraft:village"` matches every village variant. `"nova_structures"` without the trailing colon does **not** match the namespace.

`disabledExact` trims and lowercases each complete key and then compares for equality only. `"minecraft:ruined_portal"` there disables the Overworld variant while leaving `"minecraft:ruined_portal_nether"` enabled. Use `disabled` when the whole family should go.

### 1.4 `frequencyOverrides[]`

Use this to make a registered native structure set more or less common without converting its structures into explicit Iris placements. Each entry is `{ "structureSet": "namespace:path", "multiplier": 0.01..16 }`. `structureSet` is an exact registered **structure-set key**, not a structure key, and the last normalized duplicate wins. Bukkit/Paper, Fabric, Forge, and NeoForge apply the same dimension-scoped contract to newly generated chunks.

```json
{
  "importedStructures": {
    "frequencyOverrides": [
      { "structureSet": "minecraft:nether_complexes", "multiplier": 1.1 },
      { "structureSet": "minecraft:ruined_portals", "multiplier": 1.1 },
      { "structureSet": "minecraft:nether_fossils", "multiplier": 1.1 }
    ]
  }
}
```

Iris keeps the registered set's entries, weights, biome eligibility, placement algorithm, salt, exclusion zones, structure start and Y logic, processors, entities, mobs, loot, and native locate path. For random-spread placement it first scales Minecraft's placement probability up to `1`, then derives the nearest integer spacing with `round(oldSpacing / sqrt(remainingMultiplier))`, never below `separation + 1`.

Integer rounding means the realized change can land slightly under or over the request. At `1.1`, Nether complexes move from spacing `27` to `26` (about `7.8%` denser), ruined portals from `40` to `38` (about `10.8%` denser), and Nether fossils stay at `2/1` because no smaller legal spacing exists.

Concentric-ring sets can scale only their placement probability, so a ring placement already at probability `1` cannot get denser this way. Minecraft or modded custom placement types outside the affected override and exclusion-zone graph are untouched. An exact override, or an exclusion dependency on an overridden set, that would require copying an unsupported placement fails world binding instead of silently leaving stale exclusion behavior. Existing chunks and existing starts are never rewritten.

### 1.5 `adjustments[]`

Each entry (`match` selects targets by the same prefix rule):

| Field | Default | Meaning |
|---|---|---|
| `match` | `[]` | Keys and prefixes. Empty matches nothing. |
| `yShift` | `0` (-512..512) | Vertical offset; stacks across matches; clamped to build bounds. |
| `yBand` | unset | Absolute world-Y band `{min, max}`: the structure midpoint lands in the band, deterministic per start chunk. |
| `preserveSourceY` | `false` | Skip Iris burial repositioning and keep the vanilla Y. `undergroundYShift` and `yShift` still apply on top. |
| `stilt` | unset | Foundation columns: `maxDepth` (default 64), `palette` (default cobblestone), `spacing`. (`supportNonOccluding` applies to Iris-assembled structures.) |
| `terrain` | unset (= `SOURCE`) | Terrain-integration override. |

Vegetation clearing is automatic: trees intersecting piece envelopes are removed.

**Merge:** `yShift` adds; `preserveSourceY` is OR-ed; `stilt`, `terrain`, and `yBand` are last-match-wins. Put the broad prefix first and the specific overrides after.

**Vertical precedence:**

```
preserveSourceY  >  yBand  >  burial (underground steps)  >  plain yShift
```

Three structures honor only `yShift` among these controls: `minecraft:monument` (aligned 24 below sea level), `minecraft:desert_pyramid` (one block above the lowest surface Y of its footprint), and `minecraft:jungle_pyramid` (one block above the average surface Y).

#### Terrain modes

| Mode | Behavior |
|---|---|
| `SOURCE` (default) | Replay the structure's registered terrain adaptation, including vanilla BURY/ENCAPSULATE fill reimplemented with surrounding terrain material. |
| `PRESERVE` | Disable terrain integration. |
| `BORE` | Clear the padded piece volume (box) before placement. |
| `FORCE_CARVE` | Clear the padded envelope using `shape`: `BOX`, `ROUNDED`, or `ERODED`. |
| `VACUUM` | Raise surface terrain to the structure's ground planes with a fixed 12-block falloff. Never lowers ground. |
| `ENCASE` | Fill the padded volume with solid blocks before placement (air and liquid only). The structure then carves its own interiors. `encasePalette` is optional; defaults are stone/deepslate in the Overworld, netherrack in the Nether, end stone in the End. |

Padding: `horizontalPadding` (0..128), `ceilingPadding` (0..128), `floorPadding` (0..64, where 0 preserves the floor). ERODED adds `erosionStrength` (default 0.8), `erosionFrequency` (0.07), `lobeFrequency`, and `lobeStrength` (0.85).

#### Examples

Stronghold pushed into a deep band and encased:

```json
{
  "match": ["minecraft:stronghold"],
  "yBand": { "min": -120, "max": -20 },
  "terrain": {
    "mode": "ENCASE",
    "horizontalPadding": 4,
    "ceilingPadding": 4,
    "floorPadding": 4,
    "encasePalette": {
      "zoom": 1,
      "palette": [
        { "block": "minecraft:stone_bricks", "weight": 6 },
        { "block": "minecraft:mossy_stone_bricks", "weight": 2 },
        { "block": "minecraft:cracked_stone_bricks", "weight": 2 },
        { "block": "minecraft:cobblestone", "weight": 1 }
      ]
    }
  }
}
```

Shift trial chambers, preserve mineshaft Y, stilt villages:

```json
[
{ "match": ["minecraft:trial_chambers"], "yShift": -64 },
{ "match": ["minecraft:mineshaft"], "preserveSourceY": true },
{
  "match": ["minecraft:village"],
  "stilt": { "maxDepth": 768, "palette": { "palette": [ { "block": "minecraft:cobblestone" } ] } }
}
]
```

Broad then specific (last-match-wins):

```json
[
{ "match": ["towns_and_towers:"], "terrain": { "mode": "VACUUM" } },
{
  "match": [
    "towns_and_towers:mimic_desert",
    "towns_and_towers:pillager_outpost_ocean",
    "towns_and_towers:village_ocean",
    "towns_and_towers:wreckage_ocean"
  ],
  "terrain": { "mode": "PRESERVE" }
}
]
```

## 2. Datapack structures

### 2.1 `datapackImports`

A dimension-file list of datapack sources Iris downloads and installs:

```json
{
"datapackImports": [
  "https://modrinth.com/datapack/towns-and-towers",
  "https://modrinth.com/datapack/dungeons-and-taverns"
]
}
```

A `datapackImports` URL belongs to the dimension that declares it. Bukkit exposes installed resources through the server-wide registry, but before initial chunks load Iris removes disallowed managed structure sets and structure definitions from each world's generation state. Vanilla worlds, and Iris worlds whose active dimension does not declare the source, therefore neither generate nor locate those structures. Declaring the same URL in multiple dimensions deliberately shares its structures. If multiple managed sources claim the same key, every owner must be declared, because the registry winner cannot be inferred safely.

Accepted URL forms:

- **Modrinth project page** — latest datapack version for the server's Minecraft version.
- **Pinned Modrinth version** — any `.../version/<token>` URL.
- **Any other URL** — direct zip download, tracked by ETag and hash.

Downloads are checksum-verified when Modrinth publishes a hash, and size-capped.

### 2.2 Where files land, and when ingest runs

Installed datapacks are real Minecraft datapacks at `<level root>/datapacks/<id>/`, each carrying `.iris-managed.json`. Unmanaged datapacks are never touched, and the id `iris` is reserved. Cache, staging, and manifest live under `plugins/Iris/datapacks/`.

Ingest and recovery run synchronously inside Iris's startup admission gate when `general.autoIngestDatapacks` is enabled (default true). Players and every Iris world and Studio creation path stay locked until that phase is valid.

A persisted manifest, configuration, and content fingerprint lets an unchanged boot skip remote resolution and full revalidation, and Iris refreshes that fingerprint after its own authorized post-start import maintenance. A change to the URL, the Minecraft or Iris version, the override policy, external manifest edits, staging, the transaction, installed content, or cache corruption invalidates reuse and runs the full fail-closed path.

Minecraft builds worldgen registries at server start, so a **newly installed or repaired** datapack needs a clean restart before admission. After that returns, its keys are live only in the per-world structure state of declaring Iris dimensions.

Cache reuse is a local validation decision and does not poll remote sources; run `/iris datapack ingest` when you want an update check. Every successful ingest persists fresh staging and installed-target receipts, so unchanged bootstrap recovery leaves the manifest stable and the next startup can reuse the cached fingerprint.

Scratch validation rejects links, junction-like special files, and real cross-volume entries. On Windows with Java 25, Iris also verifies the drive root and volume serial when the JDK reports unequal `FileStore` identities only because a path crossed the legacy 247-character prefix boundary. Unresolved cleanup, identity, transaction, or validation failures stay blocking and create no world artifacts.

### 2.3 Manual commands

```
/iris datapack ingest [restart=false]    (alias: pull)
/iris datapack list                      (alias: ls)
/iris datapack remove <id>               (alias: rm)
```

`ingest` downloads each distinct URL declared by any loaded dimension while keeping the per-dimension ownership relationship used by generation and locate state. `restart` defaults to false, and Iris tells you a restart is required. `remove` refuses unmanaged datapacks — also delete the URL, or a later startup ingest reinstalls it. Scope changes do not delete installed datapacks, previously generated chunks, or existing structures.

### 2.4 Usage patterns

**(a) Natural generation.** Import, restart. Check with `/iris structure list <dimension>` (which writes `<pack>/.iris/structure-index.json`) and `/iris structure verify <dimension>` (`[native-eligible]` versus `[unreachable]`). Fix unreachable biomes with `vanillaDerivative`, or use (c).

**(b) Replace vanilla.** Disable the vanilla families and let the datapack replacements keep generating:

```json
{
"importedStructures": {
  "datapackOverrides": true,
  "disabled": [
    "minecraft:village",
    "minecraft:pillager_outpost",
    "nova_structures:"
  ],
  "adjustments": [
    { "match": ["towns_and_towers:"], "terrain": { "mode": "VACUUM" } },
    { "match": ["towns_and_towers:mimic_desert", "towns_and_towers:pillager_outpost_ocean",
                 "towns_and_towers:village_ocean", "towns_and_towers:wreckage_ocean"],
      "terrain": { "mode": "PRESERVE" } }
  ]
}
}
```

**(c) Manual placement only.** Disable the datapack namespace, then place specific keys with `nativeStructures` — see 3.2:

```json
{
"structures": [
  {
    "placementId": "dnt-taverns-temperate",
    "nativeStructures": [
      { "structure": "nova_structures:tavern_oak",    "weight": 4 },
      { "structure": "nova_structures:tavern_birch",  "weight": 3 },
      { "structure": "nova_structures:tavern_cherry", "weight": 2 },
      { "structure": "nova_structures:shrine_tower",  "weight": 1 }
    ],
    "distribution": "RANDOM_SPREAD",
    "spacing": 24,
    "separation": 6,
    "salt": 776215551
  }
]
}
```

### 2.5 `datapackOverrides`

When `false`, Iris strips `data/minecraft/worldgen/structure_set|structure|template_pool/` and `data/minecraft/structure/` from every installed copy. This resolves **globally**: one dimension setting it `false` strips for all. Non-`minecraft:` content is unaffected, so disable those keys explicitly.

## 3. Placing specific native structures (`nativeStructures`)

`structures[]` on a dimension, region, or biome hosts two backends, and each placement uses exactly one:

- `structures: ["<iris key>"]` — Iris assemblies (`21 - Jigsaw Structures.md`).
- `nativeStructures: [{ structure, weight, jigsaw }]` — registered structures run through Minecraft's own machinery at Iris-chosen points, with full native fidelity.

### 3.1 Entry fields

| Field | Default | Meaning |
|---|---|---|
| `structure` | required | Registered structure key; must exist live. |
| `weight` | `1` (min 1) | Weighted selection among the sources in this placement. |
| `jigsaw` | unset | Overrides for registered **jigsaw** structures only: `startPool`, `startJigsawName`, `maxDepth` (0..20), `maxDistanceHorizontal` (1..128), `maxDistanceVertical` (1..4064), `useExpansionHack`, `projectStartToHeightmap` (`SOURCE`/`NONE`/heightmap types), `dimensionPaddingBottom` and `dimensionPaddingTop` (nonnegative distance from floor and ceiling), and `liquidSettings`. Null or unset values preserve the registered definition. |

Placement grid fields (`distribution`, `spacing`/`separation`/`salt`, `density`, rings, heights, `underground`, `underwater`, `placementId`) match the **Natural placement** section of `21 - Jigsaw Structures.md`, except that the native backend supports **every** terrain mode including `VACUUM` and `ENCASE`, plus `stilt` (including `spacing`).

Scoping matches Iris placements. Validation requires the structure's effective assembly span to stay inside Minecraft's 128-block (8-chunk) structure reference range.

### 3.2 `disabled` and `disabledExact` never block an explicit placement

The placement injector generates planned starts without consulting either deny list, and it bypasses the structure's own biome filter. Both "disable the namespace, re-place explicitly" and "deny an exact key, replace it explicitly" are supported.

### 3.3 `nativeSuppression: REPLACE_SOURCE`

- **Dimension-level placements only** — anywhere else it is a blocking pack error.
- With `nativeStructures`: suppresses that key's natural generation so it exists only where the placement puts it.
- With Iris `structures`: suppresses each referenced structure's `vanillaSource`. Pack validation demands the graph guarantee output, because there is no native fallback. An Iris-backend `REPLACE_SOURCE` that produces nothing throws at runtime. Native-backend unusable starts are recorded invalid and skipped silently, and the source stays suppressed.

Example — ancient cities replaced by Iris-positioned native starts:

```json
{
  "nativeStructures": [ { "structure": "minecraft:ancient_city" } ],
  "placementId": "ancient-city-native",
  "nativeSuppression": "REPLACE_SOURCE",
  "underground": true,
  "minHeight": -220,
  "maxHeight": -220,
  "distribution": "RANDOM_SPREAD",
  "spacing": 64,
  "separation": 5,
  "salt": 42069,
  "terrain": {
    "mode": "FORCE_CARVE",
    "horizontalPadding": 14,
    "ceilingPadding": 12,
    "shape": "ERODED",
    "erosionStrength": 1.0,
    "erosionFrequency": 0.05
  },
  "stilt": {
    "maxDepth": 768,
    "palette": {
      "palette": [
        { "block": "minecraft:deepslate_bricks", "weight": 6 },
        { "block": "minecraft:cracked_deepslate_bricks", "weight": 1 },
        { "block": "minecraft:deepslate_tiles", "weight": 2 },
        { "block": "minecraft:cracked_deepslate_tiles", "weight": 1 }
      ]
    }
  }
}
```

### 3.4 Tool reporting

| Configuration | `/iris structure verify` | `/iris goto structure <key>` |
|---|---|---|
| Registered, not disabled, not placed | `[native-eligible]` or `[unreachable] ... needs <biomes>` | Vanilla locate |
| Placed via `nativeStructures` (even if also disabled) | `[iris-planned] <key> @ x,y,z` or `[iris-not-found]` | Iris grid search |
| Disabled, not placed | `[disabled]` | "disabled by this dimension's importedStructures settings" |

A key that is both disabled and placed reports as Iris-placed.

## 4. Minecraft structure-block system

Structure blocks save and load `.nbt` templates; jigsaw blocks wire pools together. Iris Jigsaw Studio is the documented in-game workflow for editable Iris graphs, so reach for external vanilla Structure Block and datapack references only when authoring raw `.nbt` assets outside Iris.

How an authored `.nbt` reaches an Iris world:

**(a) Through a datapack (native generation).** Ship it under `data/<ns>/structure/`, add `worldgen/template_pool`, `worldgen/structure`, and `worldgen/structure_set`, zip it, host it or publish to Modrinth, add the URL to `datapackImports`, then `/iris datapack ingest restart=true`. From there use natural generation, `adjustments`, or `nativeStructures`.

**(b) Import into Iris resources.** `/iris structure import <dimension>` (section 5). The template pass enumerates **registered** templates only — loose saves in `<world>/generated/` are not enumerated, so package them into a datapack first.

Template-import fidelity is lossy by design: first palette only; structure voids and structure blocks dropped; jigsaw blocks resolved to `final_state` (the graph is rebuilt by the separate jigsaw pass); entities not converted; block entities captured.

## 5. Importing native structures into Iris resources

You do not need import just to place something — `nativeStructures` places any registered key with full fidelity. Import only when you need Iris object, pool, or piece resources. Manual imports are editable transaction-owned copies; automatic datapack imports stay managed by ingest and must be cloned before Jigsaw Studio will edit them.

### 5.1 `/iris structure import <dimension>`

Four passes, always overwriting its own previous output:

1. **Jigsaw rebuild** — registered jigsaw structures become editable pools, pieces, and objects. Connector `final_state`, signed `selection_priority`, and signed `placement_priority` values are retained in the Iris piece metadata, and the generated root writes `branchFailurePolicy: TERMINATE_BRANCH` so unmatched optional branches keep native termination behavior.
2. **Template import** — registered `.nbt` templates become `objects/<name>.iob` plus a single-piece `jigsaw-pieces/<name>.json`.
3. **Template groups** — fixed multi-template structures (shipwrecks, ruined portals, ocean ruins, nether fossils) become one Iris structure each, with every variant in the pool.
4. **Capture** — only non-jigsaw registry keys for which the first pass found no same-key template are captured through a scratch world. This pass never rewrites a successful or failed jigsaw conversion; the standalone `/iris structure capture <dimension>` command stays unfiltered. Structures spanning more than **48 blocks** on any axis are skipped, so strongholds, mansions, and monuments stay native-only.

Naming: `minecraft:village_plains` becomes `minecraft_village_plains`. Generated structures carry `vanillaSource` for locate and `REPLACE_SOURCE`.

`/iris studio importvanilla <dimension> [variants=3] [structures=true]` also imports vanilla trees and features as objects, plus the structure passes when `structures=true`.

### 5.2 Ownership, manual editing, and `unowned_resource`

Imports use per-bundle ownership manifests under `<pack>/.iris/structure-manifests/`. A failure looks like:

```
Import conflict for '<name>': <path> is unowned_resource. Existing authored files were preserved.
```

Iris found a file it did not write and refused to clobber it. `modified_resource` means Iris wrote it, you edited it, and the hash no longer matches. Rename the target, restore the exact owned bytes, or leave the key native.

A successfully converted or manually imported jigsaw opens directly with `/iris jigsaw open <dimension> <key>` because its ownership manifest is editable. A pre-existing Iris graph with no manifest uses the `adopt inspect` then `adopt apply` workflow in `21 - Jigsaw Structures.md`; no import command is needed for that case.

### 5.3 Automatic datapack import

`general.autoImportDatapackStructures` (default **false**) converts each ingested datapack's structures into pack resources on ingest. Those bundles carry `MANAGED_DATAPACK` provenance: ingest refresh owns them, and removing the source URL may clean them. Jigsaw Studio therefore shows their variants as read-only and refuses an in-place ownership claim. Inspect and apply a private clone before editing:

```text
/iris jigsaw adopt inspect <dimension> <managed-iris-key> target=<editable-key> strategy=clone
/iris jigsaw adopt apply <plan-uuid>
```

Inspect verifies the existing manifest is exactly a managed vanilla or datapack Iris assembly, pins the complete source and target read set, and reports `CLONE_REQUIRED` or a blocking diagnostic. Apply re-hashes under the pack mutation lock, atomically writes a deep clone with deterministic internal reference rewrites plus its ownership receipt, leaves the managed source unchanged, and opens the editable clone. An expired, consumed, or stale plan writes nothing. There is no adoption rollback command, so keep the pack backup you made before converting.

Automatic import is off by default because native generation and `nativeStructures` never need the copies, and conversion can write thousands of files. Deterministic source-content and graph-validation failures retain the bundles that did write and record the attempted source, importer format, and target-pack revision, so the same failures do not repeat every boot; a source update, importer-format change, or different target retries them. Unexpected reflection, I/O, transaction, and runtime failures stay pending and retry. Removing a URL from `datapackImports` cleans only bundles still owned by that managed source, and an adopted editable clone is independent.

Third-party jigsaw templates using the legacy slab property `half=top|bottom`, or the exact known misspelling `minecraft:chisled_polished_blackstone`, are normalized to current Minecraft block data during editable conversion. Other invalid final-state values are recorded as fidelity loss and omitted without internal-error telemetry. Invalid structure graphs stay per-structure failures, and expected graph-contract rejections are reported as concise import results instead of internal Iris stack traces; unexpected reflection, I/O, and runtime failures keep full diagnostic traces.

## 6. Verification and debugging

```
/iris structure list <dimension>            # write + print key index
/iris structure verify <dimension> [radius=48]   # eligibility + placement (alias: locateall)
/iris structure info <dimension> <structure>     # Iris: compile + sample assembly
/iris structure place <dimension> <structure>    # Iris: stamp at feet (player)
/iris goto structure <key>                  # locate + teleport
/iris goto unregistered                     # excluded keys + reasons
```

`structure place` resolves the graph and edit resources from the named dimension pack, then stamps the assembled pieces into the player's current world. The pack's Studio and generation engine do not need to stay open for this explicit placement.

`verify` tags: `[iris-planned]`, `[iris-not-found]`, `[iris-search-limit]`, `[disabled]`, `[unreachable]`, `[native-eligible]`, `[error]`. Placements are checked first, so a disabled-but-placed key shows as `[iris-planned]`.

### Traps

- Worlds snapshot the pack. Push changes with `/iris developer update-world world=<w> pack=<dim> confirm=true`, then restart. Back up first.
- Optional args are keyed: `radius=200`, not a bare `200`.
- New datapack structures need a restart before the registry knows them.
- Only new chunks change.
- Namespace disables need the colon: `"nova_structures:"`.
- `REPLACE_SOURCE` has no fallback, so validate the graph before shipping.
- `datapackOverrides: false` anywhere strips `minecraft:` overrides server-wide.

## Command reference

| Command | Aliases | Parameters |
|---|---|---|
| `/iris datapack ingest` | `pull` | `restart=false` |
| `/iris datapack list` | `ls` | |
| `/iris datapack remove <id>` | `rm` | |
| `/iris structure list <dimension>` | `ls` | |
| `/iris structure import <dimension>` | `import-all`, `reimport`, `imp`, `all` | |
| `/iris structure capture <dimension>` | `cap` | |
| `/iris structure verify <dimension>` | `locateall` | `radius=48` (1..1000 chunks) |
| `/iris structure info <dimension> <structure>` | | |
| `/iris structure place <dimension> <structure>` | `p` | player only |
| `/iris jigsaw convert <dimension> <source>` | `import`, `import-vanilla` | `target=auto seed=1337`; Bukkit player only; source is a registered jigsaw key |
| `/iris jigsaw adopt inspect <dimension> <source>` | | `target=auto strategy=auto`; Bukkit player only; source is an existing Iris graph |
| `/iris jigsaw adopt apply <planId>` | | Bukkit player only; no active or opening Jigsaw Studio |
| `/iris goto structure <key>` | `/iris find structure` | |
| `/iris goto unregistered` | | |
| `/iris developer update-world` | | `world=<w> pack=<dim> confirm=true [fresh-download=false]` — all keyed |

Related dimension fields: `datapackImports`, `importedStructures`, `structures[]`. Settings in `plugins/Iris/settings.json`: `general.autoIngestDatapacks` (default true), `general.autoImportDatapackStructures` (default false).

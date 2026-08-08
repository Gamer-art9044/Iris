# 22 - Native Structures & Datapacks

Structures that originate outside Iris packs: vanilla structures in Iris worlds, datapack structures, the Minecraft structure-block / `.nbt` system, and converting native structures into editable Iris resources. Objects and Iris jigsaws are `19 - Objects.md`, `20 - Object Placement.md`, and `21 - Jigsaw Structures.md`.

Terminology:

- **registered / native structure** — anything in Minecraft's live structure registry (vanilla, mod, datapack). Keys are namespaced (`minecraft:village_plains`, `towns_and_towers:village_ocean`).
- **Iris structure** — editable `structures/<key>.json` inside a pack.

Command listings are Bukkit/Paper; modded loaders expose a reduced set.

## 1. Vanilla structures in Iris worlds

### 1.1 Default: everything generates

Every registered structure generates through its own native placement unless its key is disabled or a dimension-level Iris placement replaces its source. Changes only affect newly generated chunks.

### 1.2 Biome mapping for structure filters

Vanilla tests biomes against structure biome filters. Iris answers per Iris biome:

| Field on biome | Default | Purpose |
|---|---|---|
| `derivative` | `minecraft:the_void` | Vanilla biome this Iris biome reports generally. |
| `vanillaDerivative` | unset | Optional override for structure selection, spawn tables, imported features, biome tags. Wins when set. |

Refinements: a sea-role biome whose derivative is not ocean/river-like resolves to `minecraft:the_void`; shore-role falls back to `minecraft:beach`. **Non-`minecraft:` namespaces pass through** — point `vanillaDerivative` at a datapack/mod biome key that exists in the live registry.

A datapack structure whose filter lists only its own biomes never generates until an Iris biome reports one of those keys via `vanillaDerivative`. `/iris structure verify` reports `[unreachable] <key> needs <biomes>`.

```json
{
  "derivative": "minecraft:plains",
  "vanillaDerivative": "towns_and_towers:some_custom_biome"
}
```

### 1.3 `importedStructures` (dimension)

| Field | Default | Meaning |
|---|---|---|
| `disabled` | `[]` | Structure keys/prefixes to deny. |
| `undergroundYShift` | `0` (-512..512) | Vertical offset for underground-step structures only. Surface structures never use it. |
| `datapackOverrides` | `true` | Whether ingested datapacks may replace `minecraft:`-namespaced structure content (2.5). |
| `adjustments` | `[]` | Per-structure adjustments for structures still generating natively (1.4). |

#### Prefix matching

Used by `disabled` and `adjustments[].match`. Both sides trimmed and lowercased; key must start with pattern; then:

- Equal length → match.
- Pattern ends with `:`, `/`, or `_` → match (e.g. `"nova_structures:"` disables a namespace).
- Otherwise next character after pattern must be `/` or `_`.

`"minecraft:village"` matches village variants; `"nova_structures"` without trailing colon does **not** match the namespace.

```json
{
"importedStructures": {
  "disabled": ["minecraft:village", "minecraft:pillager_outpost"]
}
}
```

### 1.4 `adjustments[]`

Each entry (`match` selects targets by the same prefix rule):

| Field | Default | Meaning |
|---|---|---|
| `match` | `[]` | Keys/prefixes. Empty matches nothing. |
| `yShift` | `0` (-512..512) | Vertical offset; stacks across matches; clamped to build bounds. |
| `yBand` | unset | Absolute world-Y band `{min, max}`: structure midpoint lands in band, deterministic per start chunk. |
| `preserveSourceY` | `false` | Skip Iris burial repositioning; keep vanilla Y. `undergroundYShift` and `yShift` still apply on top. |
| `stilt` | unset | Foundation columns: `maxDepth` (default 64), `palette` (default cobblestone), `spacing`. (`supportNonOccluding` applies to Iris-assembled structures.) |
| `terrain` | unset (= `SOURCE`) | Terrain-integration override. |

Vegetation clearing is automatic (trees intersecting piece envelopes removed).

**Merge:** `yShift` adds; `preserveSourceY` OR-ed; `stilt`, `terrain`, `yBand` last-match-wins. Broad prefix first, specific overrides after.

**Vertical precedence:**

```
preserveSourceY  >  yBand  >  burial (underground steps)  >  plain yShift
```

Three structures honor only `yShift` among these controls: `minecraft:monument` (aligned 24 below sea level), `minecraft:desert_pyramid` (one block above lowest surface Y of footprint), `minecraft:jungle_pyramid` (one block above average surface Y).

#### Terrain modes

| Mode | Behavior |
|---|---|
| `SOURCE` (default) | Replay structure's registered terrain adaptation, including vanilla BURY/ENCAPSULATE fill reimplemented with surrounding terrain material. |
| `PRESERVE` | Disable terrain integration. |
| `BORE` | Clear padded piece volume (box) before placement. |
| `FORCE_CARVE` | Clear padded envelope with `shape`: `BOX` / `ROUNDED` / `ERODED`. |
| `VACUUM` | Raise surface terrain to structure ground planes with fixed 12-block falloff. Never lowers ground. |
| `ENCASE` | Fill padded volume with solid blocks before placement (air/liquid only). Structure carves interiors. `encasePalette` optional (defaults: stone/deepslate overworld, netherrack nether, end stone end). |

Padding: `horizontalPadding` (0..128), `ceilingPadding` (0..128), `floorPadding` (0..64; 0 preserves floor). ERODED: `erosionStrength` (default 0.8), `erosionFrequency` (0.07), `lobeFrequency`, `lobeStrength` (0.85).

#### Examples

Stronghold deep band + encase:

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

Shift trial chambers; preserve mineshaft Y; stilt villages:

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

Dimension-file list of datapack sources Iris downloads and installs:

```json
{
"datapackImports": [
  "https://modrinth.com/datapack/towns-and-towers",
  "https://modrinth.com/datapack/dungeons-and-taverns"
]
}
```

Accepted URL forms:

- **Modrinth project page** — latest datapack version for the server's Minecraft version.
- **Pinned Modrinth version** — any `.../version/<token>` URL.
- **Any other URL** — direct zip download, tracked by ETag/hash.

Checksum-verified when Modrinth publishes a hash; size-capped.

### 2.2 Where files land; when ingest runs

Installed datapacks are real Minecraft datapacks at `<level root>/datapacks/<id>/`, each with `.iris-managed.json`. Unmanaged datapacks are never touched; id `iris` is reserved. Cache/staging/manifest under `plugins/Iris/datapacks/`.

Ingest runs shortly after plugin enable when `general.autoIngestDatapacks` is true (default). Minecraft builds worldgen registries at server start, so a **newly installed** datapack is not registered on the boot that installed it — auto-ingest **restarts the server** when anything changed. After that restart, keys are live. A repair path reinstalls staged datapacks that went missing without re-downloading.

### 2.3 Manual commands

```
/iris datapack ingest [restart=false]    (alias: pull)
/iris datapack list                      (alias: ls)
/iris datapack remove <id>               (alias: rm)
```

`ingest` aggregates `datapackImports` from every dimension of every loaded pack. `restart` defaults false (Iris tells you a restart is required). `remove` refuses unmanaged datapacks — also delete the URL or next ingest reinstalls it.

### 2.4 Usage patterns

**(a) Natural generation.** Import, restart. Check with `/iris structure list <dimension>` (writes `<pack>/.iris/structure-index.json`) and `/iris structure verify <dimension>` (`[native-eligible]` vs `[unreachable]`). Fix unreachable biomes via `vanillaDerivative`, or use (c).

**(b) Replace vanilla.** Disable vanilla families; datapack replacements keep generating:

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

**(c) Manual placement only.** Disable the datapack namespace, then place specific keys with `nativeStructures` — `disabled` never blocks explicit placements (3.2):

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

When `false`, Iris strips `data/minecraft/worldgen/structure_set|structure|template_pool/` and `data/minecraft/structure/` from every installed copy. Resolves **globally** — one dimension setting `false` strips for all. Non-`minecraft:` content is unaffected (disable those keys explicitly).

## 3. Placing specific native structures (`nativeStructures`)

`structures[]` on dimension/region/biome hosts two backends — exactly one per placement:

- `structures: ["<iris key>"]` — Iris assemblies (`21 - Jigsaw Structures.md`).
- `nativeStructures: [{ structure, weight, jigsaw }]` — registered structures via Minecraft machinery at Iris-chosen points, full native fidelity.

### 3.1 Entry fields

| Field | Default | Meaning |
|---|---|---|
| `structure` | required | Registered structure key (must exist live). |
| `weight` | `1` (min 1) | Weighted selection among sources. |
| `jigsaw` | unset | Overrides for registered **jigsaw** structures only: `startPool`, `startJigsawName`, `maxDepth` (0..20), `maxDistanceHorizontal` (1..128), `maxDistanceVertical` (1..4064), `useExpansionHack`, `projectStartToHeightmap` (`SOURCE`/`NONE`/heightmap types), `dimensionPaddingBottom` and `dimensionPaddingTop` (nonnegative distance from floor/ceiling), and `liquidSettings`. Null/unset values preserve the registered definition. |

Placement grid fields (`distribution`, `spacing`/`separation`/`salt`, `density`, rings, heights, `underground`, `underwater`, `placementId`) match `21 - Jigsaw Structures.md` section 3.1, except the native backend supports **every** terrain mode including `VACUUM` and `ENCASE`, plus `stilt` (including `spacing`).

Scoping matches Iris placements. Validation requires the structure's effective assembly span stay inside Minecraft's 128-block (8-chunk) structure reference range.

### 3.2 `disabled` never blocks an explicit placement

The placement injector generates planned starts without consulting `disabled` and bypasses the structure's own biome filter. "Disable namespace, re-place explicitly" is supported.

### 3.3 `nativeSuppression: REPLACE_SOURCE`

- **Dimension-level placements only** — blocking pack error elsewhere.
- With `nativeStructures`: suppresses that key's natural generation so it exists only where the placement puts it.
- With Iris `structures`: suppresses each referenced structure's `vanillaSource`; pack validation demands the graph guarantees output — no native fallback. Iris-backend `REPLACE_SOURCE` that produces nothing throws at runtime. Native-backend unusable starts are recorded invalid and skipped silently (still suppressed).

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
| Placed via `nativeStructures` (even if also disabled) | `[iris-planned] <key> @ x,y,z` / `[iris-not-found]` | Iris grid search |
| Disabled, not placed | `[disabled]` | "disabled by this dimension's importedStructures settings" |

A key that is both disabled and placed reports as Iris-placed.

## 4. Minecraft structure-block system

Structure blocks save/load `.nbt` templates; jigsaw blocks wire pools. Iris does not re-teach vanilla tools — see Minecraft wiki Structure Block and datapack tutorials.

How an authored `.nbt` reaches an Iris world:

**(a) Through a datapack (native generation).** Ship under `data/<ns>/structure/`, add `worldgen/template_pool`, `worldgen/structure`, `worldgen/structure_set`, zip, host or Modrinth, add URL to `datapackImports`, `/iris datapack ingest restart=true`. Then natural generation, `adjustments`, or `nativeStructures`.

**(b) Import into Iris resources.** `/iris structure import <dimension>` (section 5). Template pass enumerates **registered** templates only — loose saves in `<world>/generated/` are not enumerated; package them into a datapack first.

Template-import fidelity (lossy by design): first palette only; structure voids and structure blocks dropped; jigsaw blocks resolved to `final_state` (graph rebuilt by separate jigsaw pass); entities not converted; block entities captured.

## 5. Importing native structures into Iris resources

You do not need import just to place — `nativeStructures` places any registered key with full fidelity. Import when you want to **edit** blocks, pools, pieces.

### 5.1 `/iris structure import <dimension>`

Four passes, always overwriting its own previous output:

1. **Jigsaw rebuild** — registered jigsaw structures → editable pools/pieces/objects.
2. **Template import** — registered `.nbt` templates → `objects/<name>.iob` + single-piece `jigsaw-pieces/<name>.json`.
3. **Template groups** — fixed multi-template structures (shipwrecks, ruined portals, ocean ruins, nether fossils) → one Iris structure each with every variant in the pool.
4. **Capture** — code-generated structures without templates (swamp huts, igloos, ...) via scratch world (also alone as `/iris structure capture <dimension>`). Structures spanning more than **48 blocks** on any axis are skipped (strongholds, mansions, monuments stay native-only).

Naming: `minecraft:village_plains` → `minecraft_village_plains`. Generated structures carry `vanillaSource` for locate and `REPLACE_SOURCE`.

`/iris studio importvanilla <dimension> [variants=3] [structures=true]` also imports vanilla trees/features as objects, plus structure passes when `structures=true`.

### 5.2 Ownership and `unowned_resource`

Imports use per-bundle ownership manifests (`<pack>/.iris/structure-manifests/`). Failure:

```
Import conflict for '<name>': <path> is unowned_resource. Existing authored files were preserved.
```

Iris found a file it did not write and refused to clobber it. `modified_resource` means Iris wrote it, you edited it, hash no longer matches. Rename or leave the key native.

### 5.3 Automatic datapack import

`general.autoImportDatapackStructures` (default **false**) converts each ingested datapack's structures into editable pack resources on ingest. Off by default because native generation and `nativeStructures` never need the copies, and conversion can write thousands of files. If auto-import fails (often `unowned_resource`), the manifest stays pending and import **retries every boot** until resolved or the setting is disabled. Removing a URL from `datapackImports` cleans bundles that import wrote for it.

## 6. Verification and debugging

```
/iris structure list <dimension>            # write + print key index
/iris structure verify <dimension> [radius=48]   # eligibility + placement (alias: locateall)
/iris structure info <dimension> <structure>     # Iris: compile + sample assembly
/iris structure place <dimension> <structure>    # Iris: stamp at feet (player)
/iris goto structure <key>                  # locate + teleport
/iris goto unregistered                     # excluded keys + reasons
```

`verify` tags: `[iris-planned]`, `[iris-not-found]`, `[iris-search-limit]`, `[disabled]`, `[unreachable]`, `[native-eligible]`, `[error]`. Placements checked first — disabled-but-placed shows as `[iris-planned]`.

### Traps

- Worlds snapshot the pack — push with `/iris developer update-world world=<w> pack=<dim> confirm=true`, then restart. Backup first.
- Keyed optional args: `radius=200`, not bare `200`.
- New datapack structures need a restart for registry registration.
- Only new chunks change.
- Namespace disables need the colon: `"nova_structures:"`.
- `REPLACE_SOURCE` has no fallback — validate the graph before shipping.
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
| `/iris goto structure <key>` | `/iris find structure` | |
| `/iris goto unregistered` | | |
| `/iris developer update-world` | | `world=<w> pack=<dim> confirm=true [fresh-download=false]` — all keyed |

Related dimension fields: `datapackImports`, `importedStructures`, `structures[]`. Settings (`plugins/Iris/settings.json`): `general.autoIngestDatapacks` (default true), `general.autoImportDatapackStructures` (default false).

# 16 - Surfaces, Decorators & Deposits

Surface composition is layered block palettes on biomes (and default rock/fluid palettes on the dimension). Decorators scatter plants and props on surfaces, shores, sea floor/ceiling, and cave ceilings. Deposits place underground clumps of ores and stone; variants remap ore ids by Y band.

Related: `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `15 - Caves & Carving.md`, `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`, `20 - Object Placement.md`, `24 - Pack Mods & Snippets.md`.

## Tutorial: build a surface, flower scatter, and deposit

Start with the flat generator from `26 - Example - Minimal Dimension.md`. Save the following complete biome as `biomes/tutorial/surface-test.json`, list `tutorial/surface-test` in one region's `landBiomes`, and temporarily set the dimension `focus` to the same key:

```json
{
  "name": "Surface Test",
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains",
  "generators": [
    { "generator": "flat", "min": 16, "max": 16 }
  ],
  "layers": [
    {
      "minHeight": 1,
      "maxHeight": 1,
      "palette": [{ "block": "minecraft:grass_block" }]
    },
    {
      "minHeight": 3,
      "maxHeight": 3,
      "palette": [{ "block": "minecraft:dirt" }]
    }
  ],
  "decorators": [
    {
      "chance": 0.05,
      "palette": [{ "block": "minecraft:dandelion" }]
    }
  ],
  "deposits": [
    {
      "minHeight": 0,
      "maxHeight": 96,
      "minSize": 3,
      "maxSize": 6,
      "minPerChunk": 1,
      "maxPerChunk": 2,
      "palette": [{ "block": "minecraft:coal_ore" }],
      "varience": 2
    }
  ]
}
```

1. Validate and open Studio on seed `1337`.
2. Generate new chunks and inspect a cross-section. Success is one grass block over three dirt blocks, dandelions scattered above valid surfaces, and coal clumps only inside the configured absolute Y band.
3. If the surface is wrong, remove `decorators` and `deposits` and verify `layers` first. If flowers do not appear, raise `chance` temporarily and confirm `decorate` remains true on the dimension. If deposits do not appear, verify the absolute Y range intersects the generated terrain and keep the code-authoritative spelling `varience`.
4. Remove dimension focus after the biome works, then tune each subsystem independently using the full tables below.

## Surfaces and material layers

### Biome palette layer (`IrisBiomePaletteLayer`)

Biome fields:

| Field | Role |
|-------|------|
| `layers` | Surface-down material stack (grass → dirt → stone). Iris fills remaining depth with dimension rock |
| `seaLayers` | Underwater column layers |
| `caveCeilingLayers` | Materials for cave ceilings |
| `slab` | Post-process slab palette (empty = none) |
| `wall` | Steep wall palette when height jump is large |
| `lockLayers` | Mesa mode: layers descend from max biome height, not surface |
| `lockLayersMax` | Max layer iterations when locked |

Layer fields:

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `palette` | `IrisBlockData[]` | grass | Weighted block list |
| `minHeight` / `maxHeight` | int | `1` | Thickness range |
| `style` | `IrisGeneratorStyle` | `STATIC` | Picks among palette when size > 1 |
| `zoom` | double ≥ 0.0001 | `5` | Palette noise zoom |
| `slopeCondition` | `IrisSlopeClip` | 0..10 | Layer only on slopes in range; thickness grows toward clip center |

`IrisSlopeClip`: `minimumSlope` (default 0), `maximumSlope` (default 10). Default clip accepts all slopes.

### Material palette (`IrisMaterialPalette`)

Shared palette type for rock/fluid, tree trunks, deposits-adjacent systems, procedural accents.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `palette` | `IrisBlockData[]` | stone | Weighted blocks |
| `style` | `IrisGeneratorStyle` | `STATIC` | Spatial pick |
| `zoom` | double ≥ 0.0001 | `5` | Sample scale |

Dimension defaults:

| Field | Default | Role |
|-------|---------|------|
| `rockPalette` | stone | Fill below biome layers |
| `fluidPalette` | water | Ocean/fluid columns and `allowFluid` cave aquifers |
| `rockZoom` | `5` | Rock palette zoom |

`IrisBlockData` entries use `block` (id), optional `weight`, optional `data` blockstate map. They can also reference reusable block aliases as described below.

### Block data and reusable `blocks/*.json`

`IrisBlockData` is used by palettes, decorators, deposits, drop filters, and object replacement rules. A normal inline entry can use a namespaced block id or a vanilla id without the `minecraft:` prefix:

```json
{
  "block": "minecraft:oak_log",
  "weight": 2,
  "data": { "axis": "y" },
  "backup": { "block": "minecraft:spruce_log" },
  "debug": false
}
```

| Field | Default | Behavior |
|-------|---------|----------|
| `block` | `air` | Block id or load key under `blocks/`; required by schema |
| `weight` | `1` (1–1000) | Relative selection weight when the containing palette has multiple entries |
| `data` | `{}` | Block-state property map such as `axis`, `waterlogged`, or `facing` |
| `backup` | `null` | Recursive fallback when the requested state cannot resolve; unresolved entries without a backup become air |
| `debug` | `false` | Logs the resolved state when general debug logging is enabled |
| `tileData` | `{}` | Block-entity data applied only when the resolved state supports a tile entity |

Files under `blocks/<key>.json` use the same shape and act as reusable block-state aliases. Refer to one with `"block": "<key>"`; the referencing entry's `data` properties override properties from the alias. Alias resolution can chain, but aliases must not form cycles.

`tileData` is read from the actual palette entry being placed. It does not inherit from a referenced alias file, so put block-entity data on the referencing entry. For spawners, legacy `data.entitySpawn` is converted into `tileData.SpawnData.entity.id` before placement. Invalid tile data or tile data on a non-tile block produces no tile payload.

### Overworld surface example

`biomes/ocean/shore/beach.json` layers:

```json
{
"layers": [
  {
    "minHeight": 1,
    "maxHeight": 1,
    "palette": [{ "block": "minecraft:grass_block" }]
  },
  {
    "minHeight": 1,
    "maxHeight": 3,
    "palette": [
      { "block": "minecraft:dirt" },
      { "block": "minecraft:coarse_dirt" }
    ]
  }
],
"wall": {
  "palette": [
    { "block": "minecraft:stone" },
    { "block": "minecraft:andesite" }
  ]
}
}
```

## Decorators (`IrisDecorator`)

Biome field: `decorators` (array). Snippet type `decorator`. Evaluated per surface column (chance tested densely; description notes 256 surface hits per chunk). Dimension `decorate` must be true (default).

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `chance` | double 0..1 | `0.1` | Placement probability gate |
| `palette` | `IrisBlockData[]` | grass | Required blocks to place |
| `topPalette` | `IrisBlockData[]` | `[]` | Top of tall stacks (bamboo tip) |
| `topThreshold` | double 0.01..1 | `1` | Stack fraction where top palette begins |
| `style` | style | `STATIC` | Dispersion noise for chance |
| `variance` | style | `STATIC` | Multi-block palette scatter |
| `heightVariance` | style | `STATIC` | Stack height noise |
| `stackMin` / `stackMax` | int 1..2032 | `1` | Vertical stack height |
| `scaleStack` | boolean | `false` | Treat stack min/max as % of cave height |
| `absoluteMaxStack` | int | `30` | Cap when scaleStack is on |
| `partOf` | `IrisDecorationPart` | `NONE` | Surface context filter |
| `forcePlace` | boolean | `false` | Ignore surface block type rules |
| `forceBlock` | `IrisBlockData` | null | Force surface block (implies forcePlace) |
| `whitelist` / `blacklist` | `IrisBlockData[]` | null | Surface allow/deny lists |
| `slopeCondition` | `IrisSlopeClip` | default | Slope gate |

### Decoration parts (`IrisDecorationPart`)

| Value | Placement target |
|-------|------------------|
| `NONE` | Default surface |
| `SHORE_LINE` | Shore (sugar cane) |
| `SEA_SURFACE` | Water surface (lily pads) |
| `SEA_FLOOR` | Entire placement below sea level |
| `CEILING` | Cave/overhang ceilings |

Stacking: `stackMax > 1` enables multi-block height. Height is noise-fit between min/max (+1 in height resolver).

### Overworld decorator examples

Flower scatter (`biomes/ocean/shore/beach.json`):

```json
{
  "chance": 0.2,
  "variance": {
    "style": "CELLULAR",
    "zoom": 0.25,
    "fracture": { "style": "SIMPLEX", "zoom": 0.2, "multiplier": 10 }
  },
  "style": {
    "style": "SIMPLEX",
    "zoom": 0.2,
    "fracture": { "style": "STATIC", "multiplier": 8 }
  },
  "palette": [
    { "block": "minecraft:dandelion" },
    { "block": "minecraft:poppy" }
  ]
}
```

Shore cane:

```json
{
  "partOf": "SHORE_LINE",
  "chance": 0.18,
  "stackMin": 1,
  "stackMax": 4,
  "palette": [{ "block": "minecraft:sugar_cane" }],
  "style": { "style": "NOWHERE", "zoom": 0.65 }
}
```

Snippet `snippet/decorator/bush.json` — reusable bush with slope limit and air-weighted palette:

```json
{
  "chance": 0.03,
  "style": {
    "style": "CLOVER_HERMITE",
    "zoom": 0.52,
    "exponent": 2.5,
    "axialFracturing": true
  },
  "slopeCondition": { "maximumSlope": 5 },
  "palette": [
    { "block": "minecraft:bush", "weight": 1 },
    { "block": "minecraft:air", "weight": 4 }
  ]
}
```

Cave ceiling (`biomes/carving/amethyst.json`): `"partOf": "CEILING"` with downward-facing amethyst buds.

## Deposits (`IrisDepositGenerator`)

Clump-based underground replacements. Defined at **dimension**, **region**, and **biome** (`deposits` arrays stack). Biome ore multipliers only affect deposits whose palette resolves as ore blocks.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `minHeight` / `maxHeight` | int 0..8192 | `1` / `75` | Absolute placement Y band |
| `minSize` / `maxSize` | int 0..8192 | `0` / `128` | Blocks per clump |
| `minPerChunk` / `maxPerChunk` | int 0..2048 | `0` / `3` | Clumps per chunk |
| `spawnChance` | double 0..1 | `1` | Chunk spawn gate |
| `perClumpSpawnChance` | double 0..1 | `1` | Per-clump gate |
| `palette` | `IrisBlockData[]` | required | Clump materials |
| `varience` | int 1..64 | `3` | Pre-baked clump shape count (spelling is code field name) |
| `replaceBedrock` | boolean | `false` | Allow replacing bedrock |

Clumps are deterministic objects up to 11³ bounding cube. Ore detection: any palette block with ore property. Biome fields:

| Field | Default | Notes |
|-------|---------|-------|
| `oreDepositFrequencyMultiplier` | `1` | Scales ore deposit frequency only |
| `oreDepositSizeMultiplier` | `1` | Scales ore clump size only |

### Deposit variants (`IrisDepositVariant`)

Remap ore block ids after placement. Order: biome rules first, then region, then dimension; first matching Y-band wins.

| Field | Type | Notes |
|-------|------|-------|
| `minHeight` / `maxHeight` | int | Inclusive world Y |
| `remap` | map string→string | source material id → replacement id |

Dimension also supports `hideOresForHiddenOre`: replace all generator ore with base stone/deepslate/netherrack for drop-control plugins.

### Dimension ores (`IrisOreGenerator`)

Separate from deposits. Dimension `ores` array:

| Field | Notes |
|-------|-------|
| `palette` | Ore material palette |
| `chanceStyle` | Noise gate style |
| `threshold` | Noise must be ≤ threshold to place (default 0.5) |
| `range` | Y range |
| `generateSurface` | true = surface ore pass, false = underground |

### Overworld deposit examples

Dimension stone blobs (`dimensions/overworld.json`):

```json
{
"deposits": [
  {
    "minHeight": 19,
    "maxHeight": 390,
    "minPerChunk": 1,
    "maxPerChunk": 8,
    "minSize": 25,
    "maxSize": 25,
    "palette": [{ "block": "minecraft:granite" }],
    "varience": 2
  }
]
}
```

Region iron/coal (`regions/temperate.json`):

```json
{
"deposits": [
  {
    "minHeight": 15,
    "maxHeight": 410,
    "minPerChunk": 4,
    "maxPerChunk": 26,
    "minSize": 3,
    "maxSize": 8,
    "palette": [
      { "block": "minecraft:iron_ore" },
      { "block": "minecraft:coal_ore" }
    ],
    "varience": 4
  }
]
}
```

Deepslate remap (`dimensions/overworld.json`):

```json
{
"depositVariants": [
  {
    "minHeight": -64,
    "maxHeight": 0,
    "remap": {
      "minecraft:iron_ore": "minecraft:deepslate_iron_ore",
      "minecraft:diamond_ore": "minecraft:deepslate_diamond_ore"
    }
  }
]
}
```

## Tune each surface-detail subsystem

Start from a focused biome that already produces correct height. Complete and verify each stage before adding the next; this makes a wrong palette, placement filter, or deposit range independently visible.

### Surface

1. Define 1–3 `layers` from top soil to subsoil; leave stone to `rockPalette`.
2. Set `wall` for cliff biomes; set `seaLayers` for oceans.
3. Use `lockLayers` only for mesa stripes.
4. Generate fresh Studio chunks and inspect flat ground, slopes, exposed walls, and underwater columns before continuing.

### Decorators

1. Start with low `chance` and `STATIC` style; switch to wispy styles for patches.
2. Use `partOf` for shore/sea/ceiling-only content.
3. For cactus/bamboo, set `stackMin`/`stackMax` and optional `topPalette`.
4. Extract repeated decorators into `snippet/decorator/*.json` and reference via pack snippets.
5. Verify both places where the decorator should appear and nearby places where its filter should reject it.

### Deposits

1. Put global stone/ore veins on the dimension.
2. Region/biome deposits add local minerals.
3. Use `depositVariants` for deepslate or mod ore remaps by Y.
4. Tune `varience` for clump shape diversity; keep sizes moderate for performance.
5. Inspect multiple Y bands in new chunks. The workflow passes when the surface palette is stable, decorators honor their part/filter rules, and deposits remain inside their configured material and height targets.

## Practical notes

- Decorators do not replace object placements or procedural trees; they are block scatter only.
- Empty decorator palette → no place. Explicit empty whitelist blocks all placement.
- Deposit `varience` is not `variance`; generators use `multiplicitive`, not `multiplicative`.
- Post-processing slabs/walls (`postProcessingSlabs`, `postProcessingWalls` on dimension) are separate from biome `slab`/`wall` palettes but use related surface logic.

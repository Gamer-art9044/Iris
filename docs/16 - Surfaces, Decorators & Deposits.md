# 16 - Surfaces, Decorators & Deposits

Three systems dress the terrain once its shape exists. Biome `layers` decide what the top few blocks of each column are made of, with the dimension `rockPalette` filling everything below. Decorators scatter a single block or stack on top of a surface — grass, flowers, cane, lily pads, cave vines. Deposits stamp pre-baked clumps of ore and stone into already-solid rock underground.

Related: `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `15 - Caves & Carving.md`, `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`, `19 - Objects.md`, `20 - Object Placement.md`, `24 - Pack Mods & Snippets.md`.

## The mental model

The terrain actuator walks each column downward from `max(fluidHeight, terrainHeight)` to bedrock and asks, at every Y:

1. Does a **surface ore** generator claim this cell? If so it wins outright, above or below the waterline.
2. Is this cell above the terrain but below the fluid level? Fill from `seaLayers`, indexed by distance down from the water surface; fall through to the dimension `fluidPalette`.
3. Otherwise it is inside the terrain. Fill from the biome `layers` stack, indexed by distance down from the surface. Once the stack runs out, try **underground ore** generators, and failing that use the dimension `rockPalette`.

Layers are therefore a *depth-indexed list*, not a set of absolute Y bands. The first layer covers the topmost block, the second covers whatever depth the first did not, and so on.

Everything else lands in later stages. Using the `OVERWORLD` pipeline order:

| Stage | What runs |
|---|---|
| 1 | biome actuator, mantle generation, **terrain actuator** (layers, sea layers, ores, rock) |
| 2 | carve modifier — cave biome floor/ceiling/wall materials and cave decorators (`15 - Caves & Carving.md`) |
| 3 | post modifier — biome `slab` and `wall` on surface terrain |
| 4 | floating child biome solids |
| 5 | **deposit modifier**, mantle object insertion, **surface decorator actuator** — these three run concurrently |
| 6 | floating decoration, perfection, custom |

Two consequences worth internalising: deposits go in *after* caves are cut and skip any cell carrying a cavern mark, so veins never hang in open air; and decorators run after carving, so a cave that broke the surface does not get flowers planted over the hole.

## Walkthrough: a surface, a flower scatter, and an ore vein

Start from the flat generator in `26 - Example - Minimal Dimension.md`. Save this as `biomes/tutorial/surface-test.json`, list `tutorial/surface-test` in one region's `landBiomes`, and set the dimension `focus` to the same key so it is the only biome generated.

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

1. Validate the pack and open Studio on seed `1337`.
2. Generate fresh chunks and cut a cross-section. Success is exactly one grass block over three dirt blocks over stone, dandelions scattered on the grass, and coal clumps only in the lower part of the column.
3. If the column is wrong, delete `decorators` and `deposits` and get `layers` right first. Layer thickness is noise-fit between `minHeight` and `maxHeight` per column, so equal min and max is the way to get a guaranteed thickness while debugging.
4. If flowers never appear, raise `chance` to `0.5` temporarily and confirm the dimension has `decorate: true` (the default). `chance` is a noise-field cutoff, not a dice roll, so a low value can genuinely produce nothing within one chunk.
5. If deposits never appear, check the Y band. Deposit `minHeight` and `maxHeight` are **engine-local Y** (0 = bottom of the world), and a clump centre is forced at least 9 blocks below the column's terrain surface. On a surface at engine-local 80, a band of `0..96` really means `0..71`.
6. Remove `focus` once the biome behaves, then tune each system on its own.

Keep the code spelling `varience`. It is the field name.

## Surfaces and material layers

### Biome-level fields

| Field | What it controls |
|-------|------------------|
| `layers` | The land column, top down. Grass, then dirt, then whatever; stone below is the dimension `rockPalette` |
| `seaLayers` | The water column above the seafloor, indexed down from the water surface. Empty means plain fluid |
| `caveCeilingLayers` | Roof materials inside carved space. Applied by the carve modifier, not the terrain actuator |
| `slab` | Palette used by the post pass to soften single-block steps. An empty palette (the default) means no slabs |
| `wall` | Painted onto exposed vertical faces. Used twice: by the post pass on surface cliffs, and by the carve modifier on cave walls. An empty palette (the default) disables both |
| `lockLayers` | Switches to mesa banding, described below |
| `lockLayersMax` | Caps how many blocks deep the banded stack goes. Default `7` |

`layers` and `caveCeilingLayers` both default to a single grass-block layer, so a biome that never declares them still produces grass floors and grass cave roofs.

### Palette layer (`IrisBiomePaletteLayer`)

Snippet key: `biome-palette`.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `palette` | `IrisBlockData[]` | grass block | Blocks this layer may use. With more than one entry, `style` and `zoom` decide which appears where; `weight` on an entry duplicates it in the pick list |
| `minHeight` | int 0..2032 | `1` | Thinnest this layer can be in any column |
| `maxHeight` | int 1..2032 | `1` | Thickest it can be. Iris noise-fits per column between the two, so unequal values give a wandering soil depth |
| `style` | `IrisGeneratorStyle` | `STATIC` | Picks between palette entries. `STATIC` scatters per block; wispy or cellular styles produce visible patches of one material |
| `zoom` | double >= 0.0001 | `5` | Scale of that pick. Larger zoom means larger single-material patches |
| `slopeCondition` | `IrisSlopeClip` | 0..10 | Where the slope is outside this range, the layer is **skipped entirely** for that column. It does not thin gradually — the block below takes over. Use it to strip grass off cliff faces |

`IrisSlopeClip` (snippet `slope-clip`) has `minimumSlope` (default `0`) and `maximumSlope` (default `10`). The default clip accepts every slope and short-circuits before the slope stream is sampled. Slope is measured over a 3-block radius.

`caveCeilingLayers` ignores `slopeCondition` — cave roofs have no meaningful slope.

**`lockLayers` (mesa mode).** Instead of indexing the stack from the surface, Iris builds the full expanded layer stack once and then reads it with an offset derived from the column's own terrain height. Bands therefore stay at fixed world heights across the whole biome and line up horizontally into stripes, and the stack repeats cyclically rather than running out. `lockLayersMax` limits how deep the banded region goes before the rock palette takes over.

### Material palette (`IrisMaterialPalette`)

Snippet key: `palette`. The general-purpose weighted palette used for rock and fluid on the dimension, tree trunks and leaves, formation strata, ruin weathering, and procedural accents.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `palette` | `IrisBlockData[]` | stone | The blocks. Each entry is repeated `weight` times in the pick list |
| `style` | `IrisGeneratorStyle` | `STATIC` | How the pick varies through space. Sampled in 3D, so a palette can band vertically as well as horizontally |
| `zoom` | double >= 0.0001 | `5` | Scale of that variation |

Dimension-level palettes:

| Field | Default | What it does |
|-------|---------|--------------|
| `rockPalette` | stone | Fills every land cell below the biome layer stack. Swap it for deepslate or a stone/granite/andesite blend to change the entire underground |
| `fluidPalette` | water | Ocean and river columns, and cave aquifers when `allowFluid` is on. Setting it to lava turns every ocean and aquifer into lava with no other change |
| `rockZoom` | `5` | Scale of the rock palette variation |

### Block data and reusable `blocks/*.json`

`IrisBlockData` is the entry type used by every palette, plus decorators, deposits, drop filters, and object replacement rules. A vanilla id works with or without the `minecraft:` prefix.

```json
{
  "block": "minecraft:oak_log",
  "weight": 2,
  "data": { "axis": "y" },
  "backup": { "block": "minecraft:spruce_log" },
  "debug": false
}
```

| Field | Default | What it does |
|-------|---------|--------------|
| `block` | `air` | A block id, or the load key of a file under `blocks/`. Required by the schema |
| `weight` | `1` (1–1000) | How many times this entry appears in the containing palette's pick list. Weight 3 against weight 1 is a 3:1 split |
| `data` | `{}` | Block-state properties such as `axis`, `waterlogged`, `facing`, `half` |
| `backup` | `null` | Tried when the requested state does not resolve on this version. Without one, an unresolvable entry becomes air |
| `debug` | `false` | Prints the resolved state to console when general debug logging is on. Use it when a palette silently produces air |
| `tileData` | `{}` | Block-entity payload, applied only when the resolved state actually has a tile entity |

Files under `blocks/<key>.json` use the same shape and act as reusable aliases. Reference one with `"block": "<key>"`; properties on the referencing entry override properties from the alias. Aliases may chain but must not form cycles.

`tileData` is read from the entry being placed and does **not** inherit from a referenced alias, so put block-entity data on the referencing entry. For spawners, a legacy `data.entitySpawn` value is converted into `tileData.SpawnData.entity.id` before placement. Invalid tile data, or tile data on a block with no tile entity, produces no payload rather than an error.

### Surface example

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

Snippet key: `decorator`. Biome field: `decorators`. The dimension must have `decorate: true` (the default).

### How a decorator is chosen

Decorators are bucketed by `partOf`, and each bucket is evaluated independently per column. Within a bucket:

1. Every decorator's noise field is sampled at that column. It passes when `noise(x/zoom, z/zoom)` fitted to 0..1 is at or below `chance`.
2. Among all decorators that passed, **exactly one** is chosen, uniformly, seeded per column.

So `chance` is a share of the noise field, not an independent probability, and two decorators in the same bucket compete rather than stacking. Ten flower decorators at `chance: 0.1` do not carpet the ground — they subdivide roughly the same 10% of columns between them. Give distinct plants distinct `style` fields when you want them in visibly different patches.

### Where the block lands

The block is written one above the surface block (`height + 1`), and only into air. A palette entry carrying a `half` property is treated as a two-block plant: both `height + 1` and `height + 2` must be air or nothing is placed.

By default the surface block must have a sturdy full up-face. `forcePlace: true` skips that test entirely; `forceBlock` replaces the surface block with the given block first and implies `forcePlace`. When not force-placing, `whitelist` and `blacklist` are matched against the surface block. An explicitly empty `whitelist` matches nothing and blocks all placement — omit the field rather than setting it to `[]`.

Vines get their attachment faces recomputed against surrounding blocks. `minecraft:pointed_dripstone` gets `thickness` and `vertical_direction` assigned automatically along a stack (tip at the far end, then frustum, then base).

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `chance` | double 0..1 | `0.1` | Fraction of the noise field that qualifies. Raise it while debugging, then dial back |
| `palette` | `IrisBlockData[]` | grass | Blocks to place. An empty resolved palette places nothing and never even tests the gate |
| `topPalette` | `IrisBlockData[]` | `[]` | Used for the upper part of a stack — bamboo tips, cactus flowers. Empty falls back to `palette` |
| `topThreshold` | double 0.01..1 | `1` | Normalized stack position where `topPalette` takes over. `0.8` gives a tip roughly a fifth of the stack tall |
| `style` | `IrisGeneratorStyle` | `STATIC` | The field that gates `chance`. `STATIC` gives even scatter; wispy or cellular styles give meadows and bare patches |
| `variance` | `IrisGeneratorStyle` | `STATIC` | Chooses between palette entries once a column has passed. Scattered variance mixes flowers per block; wispy variance gives single-species drifts |
| `heightVariance` | `IrisGeneratorStyle` | `STATIC` | Shapes stack height across the terrain when `stackMin` and `stackMax` differ |
| `stackMin` / `stackMax` | int 1..2032 | `1` / `1` | Stack height range. When they differ the resolved height is the noise fit **plus one**, so `1..4` actually produces stacks 2 to 5 tall |
| `scaleStack` | boolean | `false` | Reinterprets `stackMin`/`stackMax` as a percentage of the available vertical space instead of a block count. Meant for cave stalagmites that should scale with cavern height |
| `absoluteMaxStack` | int | `30` | Hard cap when `scaleStack` is on, so a huge cavern does not produce a 60-block column |
| `partOf` | `IrisDecorationPart` | `NONE` | Which pass places this decorator. See the table below |
| `forcePlace` | boolean | `false` | Ignores the sturdy-surface test, the slope clip, the whitelist, and the blacklist |
| `forceBlock` | `IrisBlockData` | `null` | Replaces the surface block before placing. Implies `forcePlace` |
| `whitelist` / `blacklist` | `IrisBlockData[]` | `null` | Allow/deny lists matched against the surface block. Omit rather than empty |
| `slopeCondition` | `IrisSlopeClip` | 0..10 | Rejects columns outside the slope range. Not applied on the sea-surface or ceiling passes |

### Decoration parts (`IrisDecorationPart`)

| Value | Pass, and exactly when it fires |
|-------|--------------------------------|
| `NONE` | The ordinary surface pass, on every column. Also reused by the carve modifier for cave floors |
| `SHORE_LINE` | Only where the terrain height equals `fluidHeight` exactly **and** at least one of the four cardinal neighbours is below the fluid line. This is a one-block-wide waterline ring, which is why sugar cane grows only at the edge |
| `SEA_SURFACE` | Where the terrain is below the fluid line and there is water directly above the seafloor block. Places at `fluidHeight + 1`, so lily pads sit on top of the water |
| `SEA_FLOOR` | Same gate as `SEA_SURFACE`, but writes into the first water block above the seafloor, replacing it. Use it for kelp, seagrass, and coral fans |
| `CEILING` | Applied by the carve modifier at cave and overhang ceilings, stacking downward |

### Examples

Flower scatter with patchy dispersion:

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

Shore cane, 2 to 5 blocks tall:

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

A reusable snippet at `snippet/decorator/bush.json`, using an air-weighted palette to thin the result without lowering `chance`:

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

Weighting air into the palette is a useful trick: the column still wins the bucket contest, it just places nothing. That lets one decorator hold a patch against competing decorators while still reading as sparse.

## Deposits (`IrisDepositGenerator`)

Snippet key: `deposit`. Declared on **dimension**, **region**, and **biome**; all three lists run, so biome deposits add to regional and global ones rather than replacing them.

### What a deposit actually does

For each generator, once per chunk:

1. Roll `spawnChance` for the whole generator.
2. Pick a clump count between `minPerChunk` and `maxPerChunk`. Each clump then rolls `perClumpSpawnChance` on its own.
3. Pick one of `varience` pre-baked clump objects. A clump is a solid-ish blob of up to `maxSize` blocks inside a cube no larger than 11x11x11.
4. Pick a random position in the chunk. The centre Y is drawn from the deposit band, clipped to the column's surface limit.
5. Stamp the clump block by block.

A block is written only when the target is not air, not fluid, not carrying a cavern mark, and (unless `replaceBedrock`) not bedrock. That combination is why deposits never appear inside caves, in water, or floating in the open.

**Depth limits are stricter than the configured band.** The surface limit for any column is `terrainHeight - 7`, and the clump centre must sit at least 9 blocks below the surface. Individual clump blocks above their own column's `terrainHeight - 7` are skipped, which keeps veins from breaking through a slope.

**Deepslate conversion is automatic.** When no `depositVariants` rule matches, Iris converts the ore to the deepslate form of whatever block it is replacing. A vanilla pack usually does not need a manual deepslate remap at all; `depositVariants` exists for modded ores and for deliberate substitutions.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `minHeight` / `maxHeight` | int 0..8192 | `1` / `75` | **Engine-local** Y band for the clump centre, further clamped by the surface limit above |
| `minSize` / `maxSize` | int 0..8192 | `0` / `128` | Blocks per clump. Sizes above about 1300 saturate the 11-cube and produce a solid block instead of a vein |
| `minPerChunk` / `maxPerChunk` | int 0..2048 | `0` / `3` | Clumps attempted per chunk. This is the main frequency knob |
| `spawnChance` | double 0..1 | `1` | Rolled once per chunk for the whole generator. Use it for rare deposits that should be absent from most chunks entirely |
| `perClumpSpawnChance` | double 0..1 | `1` | Rolled per clump, thinning within a chunk rather than between chunks |
| `palette` | `IrisBlockData[]` | required | Clump materials, picked uniformly per block. **`weight` is ignored here**, unlike every other palette — list a block twice to double it |
| `varience` | int 1..64 | `3` | How many distinct clump shapes are baked. Low values make repeated vein silhouettes visible; the field name is spelled this way in code |
| `replaceBedrock` | boolean | `false` | Allows overwriting bedrock |

### Biome ore multipliers

| Field | Range | Default | What it does |
|-------|-------|---------|--------------|
| `oreDepositFrequencyMultiplier` | 0..1 | `1` | Drops that fraction of ore clumps whose centre lands in this biome. `0.4` keeps 40%. It cannot increase frequency — the range stops at 1 |
| `oreDepositSizeMultiplier` | 0.01..16 | `1` | Rescales ore clump block counts in this biome. This one can go up, to 16x |

Both apply only to deposits whose palette resolves to at least one ore block, and both are read from the **cave**-biome lookup at the clump position, which falls back to the surface biome above `caveMinDepthBelowSurface`. A deep cave biome can therefore enrich or starve ore at depth independently of the biome on the surface.

### Deposit variants (`IrisDepositVariant`)

Snippet key: `deposit-variant`. Rewrites ore ids at placement time inside an absolute world-Y band. Available on dimension, region, and biome. Order is biome rules, then region, then dimension; the first band that contains the block's world Y and has a matching source id wins.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `minHeight` / `maxHeight` | int -2048..8192 | `0` / `0` | Inclusive **absolute world Y**, not engine-local. This differs from the deposit's own band |
| `remap` | map string→string | `{}` | Source block id to replacement block id. Source matching is by material only, so properties on the source key are ignored. Unresolvable ids on either side are dropped silently |

```json
{
"depositVariants": [
  {
    "minHeight": -64,
    "maxHeight": 0,
    "remap": {
      "yourmod:iron_ore": "yourmod:deepslate_iron_ore"
    }
  }
]
}
```

The dimension also exposes `hideOresForHiddenOre`. When true, every ore the generator would place — terrain ores, deposits, and ores inside objects — is emitted as its base material instead, so a drop-control plugin such as HiddenOre owns ore rewards. See `28 - Integrations.md`.

## Noise ores (`IrisOreGenerator`)

Separate from deposits and much cheaper: no clumps, no per-chunk budget, just a 3D noise test per cell during the terrain pass. Declared as `ores` on **dimension, region, and biome** (all three, despite the older docs listing only the dimension).

| Field | Default | What it does |
|-------|---------|--------------|
| `palette` | empty | Material palette for the ore. An empty palette makes the generator inert |
| `chanceStyle` | `STATIC` | The 3D field tested against `threshold`. Cellular styles give clustered pockets; static gives evenly sprinkled specks |
| `threshold` | `0.5` | The cell becomes ore when the noise value is at or below this. Higher means more ore |
| `range` | `30..80` | Engine-local Y band. Cells outside it are skipped before the noise sample |
| `generateSurface` | `false` | `false` runs the generator in the underground pass, after the biome layer stack has been exhausted, so it replaces rock. `true` runs it in a pass that executes **before** layers and fluid at every Y in the column, so it can overwrite soil, water, and anything else |

Precedence within a pass is biome, then region, then dimension; the first generator that returns a block wins. Y bands are precomputed, so generators outside the current Y are skipped without evaluating noise.

## Post-processing slabs and walls

The post modifier (stage 3) reads the biome `slab` and `wall` palettes and is gated by the dimension `postProcessingSlabs` and `postProcessingWalls` flags, both true by default.

- **Slabs** are added where at least one cardinal neighbour column is exactly one block higher and that neighbour's top block is a solid non-slab. The `slab` palette's `slopeCondition` is honoured, and a resolved air block cancels the placement. A snow slab is suppressed at or below the fluid line.
- **Walls** are painted where a cardinal neighbour is three or more blocks lower, running down the exposed face until it hits air or water. This is the same `wall` palette the carve modifier uses for cave walls, so a biome that wants different cliff and cave rock needs a dedicated cave biome.

Both palettes default to empty, which disables the corresponding effect for that biome.

## Tuning order

Do these one at a time, on a focused biome that already produces correct height. Each stage makes a wrong palette, filter, or Y band independently visible.

**Surface.** Define one to three `layers` covering soil down to subsoil and leave stone to `rockPalette`. Add `wall` for cliff biomes and `seaLayers` for oceans. Use `lockLayers` only for mesa stripes. Inspect flat ground, a steep slope, an exposed cliff face, and an underwater column before moving on.

**Decorators.** Start with a single decorator, `STATIC` style, low `chance`. Confirm it appears, then switch to a wispy or cellular style to get patches. Add `partOf` variants for shore, sea, and ceiling content. Set `stackMin`/`stackMax` and `topPalette` for cane, cactus, and bamboo, remembering the resolved height is one taller than the fit. Extract repeated definitions into `snippet/decorator/*.json` (see `24 - Pack Mods & Snippets.md`). Always check somewhere the filter should *reject* the decorator, not just somewhere it should accept it.

**Deposits.** Put broad stone blobs and common ores on the dimension, regional minerals on regions, signature ores on biomes. Verify the band actually intersects generated terrain given the engine-local offset and the 9-block surface margin. Raise `varience` if repeated vein shapes are noticeable. Add `depositVariants` last, and only for ids automatic deepslate conversion does not already handle.

## Practical notes

- Decorators place blocks only. Trees, boulders, and structures come from object placements (`20 - Object Placement.md`) and procedural objects (`17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`).
- One decorator per `partOf` bucket places per column. More decorators in a bucket means each appears less often, not more total coverage.
- Deposit `varience` is not `variance`, and generator styles use `multiplicitive`, not `multiplicative`. Both spellings are the actual field names.
- Deposit palettes ignore `weight`; every other palette in the pack honours it.
- The dimension `explodeBiomePalettes` flag inserts barrier blocks between layer groups so you can count layer boundaries in a cross-section. It is a debug aid and should never ship enabled.

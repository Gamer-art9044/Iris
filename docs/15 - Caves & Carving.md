# 15 - Caves & Carving

Iris carves its own caves during mantle generation and never runs the vanilla noise carvers. A cave profile's 3D density field marks which cells become carved space, and a later terrain pass turns those marks into air, dimension fluid, or lava and paints cave-biome floors, ceilings, walls, and decorators into the hollow. Everything is JSON on dimensions, regions, and biomes; there is no `caves/` or `ravines/` registrant type.

Related: `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `16 - Surfaces, Decorators & Deposits.md`, `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`, `20 - Object Placement.md`, `21 - Jigsaw Structures.md`, `22 - Native Structures & Datapacks.md`.

## The mental model

Carving happens in two separate passes, and knowing which one you are looking at explains almost every "why did nothing change" question.

**Pass 1 — mantle carve (`MantleCarvingComponent` + `IrisCaveCarver3D`).** For each chunk Iris resolves which cave profile applies to every column, samples a 3D density field, and for each cell that falls below the carve threshold writes a *cavern mark* into the mantle. The mark carries an intent: plain air, dimension fluid, lava, or forced air. No blocks are touched yet — the mantle is a parallel voxel store that outlives the chunk, which is why caves line up across chunk borders and why cave objects can be anchored before terrain exists.

**Pass 2 — carve modifier (`IrisCarveModifier`).** After the terrain actuator has filled the column with stone and biome layers, this pass walks the chunk's cavern marks and replaces the real blocks: air marks become `cave_air`, fluid marks become the dimension `fluidPalette` block, lava marks become lava, forced-air marks become air even below the lava line. It then groups each column's carved cells into contiguous runs (a "zone"), and for each zone paints the cave biome's `layers` downward from the floor, `caveCeilingLayers` upward from the ceiling, the `wall` palette onto every solid block touching carved space, and finally runs the cave biome's surface and `CEILING` decorators.

Stage order in `OVERWORLD` mode, which is what everything below depends on:

1. biome actuator, mantle generation (carving, then objects), terrain actuator
2. **carve modifier** — caverns become blocks; cave biome materials and decorators land here
3. post modifier — surface slabs and cliff walls
4. floating child biome solids
5. deposit modifier, mantle object insertion, surface decorator actuator
6. floating decoration, perfection, custom

Deposits run *after* carving and explicitly skip any cell that carries a cavern mark, so ore veins never dangle inside a cave. Surface decorators run after carving too, which is why a surface-breaking cave hole does not leave grass floating over the opening.

### Two different Y coordinates

This trips up nearly everyone. Iris generates internally from `0` to `(maxHeight - minHeight)` and shifts down on output. Fields split into two groups:

| Engine-local Y (0 = bottom of the world) | Absolute world Y |
|---|---|
| `caveProfile.verticalRange` | `carving[].worldYRange` |
| `IrisCaveFieldModule.verticalRange` | structure placement `minHeight` / `maxHeight` |
| dimension `caveLavaHeight`, `fluidHeight` | `depositVariants[].minHeight` / `maxHeight` |

With the default `dimensionHeight` of `-64..320`, engine-local `0` is world `-64` and engine-local `64` is world `0`. A profile `verticalRange` of `{ "min": 0, "max": 64 }` therefore covers the deepslate band, not the surface.

## Walkthrough: prove carving works before you tune it

Start from a validating `OVERWORLD` pack whose surface height and fluid level are already correct. The goal of this first pass is a visible void with nothing else changing, so a wrong palette or a leaking aquifer cannot be blamed on the density field.

1. Record seed `1337` and a surface coordinate in Studio before you touch anything, so you can compare the same spot afterwards.

2. Add this to the root object of `dimensions/<key>.json`. It uses production density defaults but seals the surface and disables both liquids:

```json
{
  "carvingEnabled": true,
  "caveProfile": {
    "enabled": true,
    "verticalRange": { "min": 16, "max": 96 },
    "allowSurfaceBreak": false,
    "surfaceClearance": 8,
    "allowFluid": false,
    "allowLava": false
  }
}
```

3. Validate the pack and reopen Studio. Carving is written into the mantle, so only **freshly generated** chunks change; fly out past your previously generated area or use a new Studio world.

4. Dig down between world Y `-48` and `32` (engine-local 16 to 96 with the default height range). Success is open cave volume with intact grass overhead, no water pockets, and no lava at the bottom of the band.

5. If nothing is carved, work down this list before touching noise values: dimension `mode.type` is `OVERWORLD`; `carvingEnabled` and `useMantle` are both true; `CARVED` is not listed in `disabledComponents`; the profile you edited is the one that actually wins for those columns (see resolution order below); the chunks are new.

Once the void is right, turn the other switches back on one at a time — `allowLava`, then `allowFluid`, then `allowSurfaceBreak` — and regenerate between each. Each one changes a distinct, observable thing, and changing two at once makes it impossible to tell which produced the result.

## Walkthrough: give one biome its own cave shape

Profiles resolve per column, and the **last enabled profile in the chain wins**:

```
dimension.caveProfile → region.caveProfile → surface biome.caveProfile → cave biome.caveProfile
```

A disabled profile (`enabled: false`, which is the Java default) is skipped entirely rather than blocking the level above it, so a biome only overrides the dimension when its own profile is explicitly enabled.

To give one surface biome tight, dense tunnels while the rest of the world keeps the dimension's caverns, put an enabled profile on that biome:

```json
{
  "caveProfile": {
    "enabled": true,
    "verticalRange": { "min": 8, "max": 110 },
    "baseDensityStyle": { "style": "SIMPLEX_VASCULAR", "zoom": 0.9 },
    "detailDensityStyle": { "style": "SIMPLEX", "zoom": 0.6 },
    "baseWeight": 1,
    "detailWeight": 0.2,
    "densityThreshold": { "min": -0.22, "max": -0.14 },
    "thresholdBias": 0.16,
    "allowSurfaceBreak": false,
    "allowFluid": false
  }
}
```

Iris blends profiles across the biome edge instead of cutting them off. Every column samples a 7x7 neighbourhood, weights each neighbour by how close it is to the centre, and normalises the result into a per-column weight. Columns below a weight of `0.08` are dropped, and columns between get their carve threshold pulled back proportionally, so a strong profile fades out over roughly three blocks rather than ending on a chunk-shaped seam.

At most **two** blended profiles run per chunk. When more than two are present, the lowest-weight ones are folded into whichever kept profile dominates each column. Profiles pulled in by dimension `carving` entries are added on top of that limit and are not subject to it.

## Walkthrough: paint the inside of a cave

Carving produces empty space and nothing else. Materials, plants, and props come from a **cave biome**, which is an ordinary biome JSON that Iris happens to look up underground.

1. Write `biomes/carving/mossy.json` as a normal biome. Cave biomes typically omit height generators — the carve step already removed solid, and nothing reads their terrain height.

```json
{
  "name": "Mossy Caverns",
  "derivative": "minecraft:lush_caves",
  "vanillaDerivative": "minecraft:lush_caves",
  "layers": [
    { "minHeight": 1, "maxHeight": 1, "palette": [{ "block": "minecraft:moss_block" }] },
    { "minHeight": 1, "maxHeight": 2, "palette": [{ "block": "minecraft:dirt" }] }
  ],
  "caveCeilingLayers": [
    { "minHeight": 1, "maxHeight": 1, "palette": [{ "block": "minecraft:moss_block" }] }
  ],
  "wall": {
    "palette": [
      { "block": "minecraft:stone" },
      { "block": "minecraft:mossy_cobblestone" }
    ]
  },
  "decorators": [
    { "chance": 0.25, "palette": [{ "block": "minecraft:moss_carpet" }] },
    {
      "partOf": "CEILING",
      "chance": 0.12,
      "palette": [{ "block": "minecraft:cave_vines" }]
    }
  ]
}
```

2. List it in a region's `caveBiomes` pool:

```json
{ "caveBiomes": ["carving/mossy"] }
```

3. Regenerate and look inside a cave in that region. Success is a moss floor, a moss ceiling, mixed stone walls, carpets on the floor and vines on the roof.

Two rules decide whether any of this appears:

- **A carved run must be at least 3 blocks tall.** A zone is only processed when its air thickness (`ceiling - floor - 1`) is greater than zero. Two-block-tall crawlspaces get carved but keep raw stone and no decoration.
- **Floor layers only overwrite solid blocks.** They descend from the block below the lowest carved cell. Where the layer stack runs into existing air, it stops. Ore blocks in the floor are converted to the deepslate variant matching the layer instead of being erased, so an iron vein exposed in a deepslate cave floor stays iron.

### How Iris picks the cave biome at a point

For a given `(x, y, z)`, in order:

1. If an enabled dimension `carving[]` entry's `worldYRange` contains the absolute world Y, its biome wins outright. Child entries subdivide that band into patches.
2. Otherwise the region `caveBiomes` pool is sampled by `caveBiomeStyle` and biome `rarity`, zoomed by the dimension `biomeZoom` multiplied by the region's `caveBiomeZoom`.
3. If the sampled cave biome's `caveMinDepthBelowSurface` is deeper than the point actually is, or if the point is at or above the surface, the **surface** biome is used instead.

Results are blended: the resolver samples the centre plus four points three blocks out, and where they disagree it picks the centre half the time and one of the four neighbours otherwise, seeded per block position. That produces a speckled transition band rather than a hard edge between two cave biomes.

`carvingBiome` on a surface biome is **not** part of this lookup. At runtime it only pulls the referenced biome into the pack's reachable-biome closure so its custom biome identity and spawn mappings get registered; it never selects a cave biome during generation. Use region `caveBiomes` or a dimension `carving` band instead.

Enabled dimension `carving` biomes are likewise included in the recursive reachable-biome closure even when no region lists them, so their identities are available wherever the Y band selects them.

## Fluids and lava inside caves

Aquifers and deep lava are two independent mechanisms, and neither changes cave geometry.

**Deep lava** is a straight Y test done while the carve marks are written: any carved cell at or below the dimension `caveLavaHeight` (engine-local, default `8`) is marked lava when `allowLava` is true. When `allowLava` is false those cells are marked *forced air*, which the carve modifier honours explicitly — that is how a dry lava-level cave stays dry. A cavern mark with plain air intent that reaches the carve modifier from some other source (structure boring, for example) below `caveLavaHeight` becomes lava, because plain air is the "use the default for this depth" intent.

**Aquifers** use the dimension `fluidPalette`, which accepts any weighted block palette and defaults to water. Swapping it for lava turns identical Overworld caverns into lava lakes without touching a single density value. A carved cell becomes fluid only when all of the following hold:

- it is at or below `min(fluidHeight, columnSurfaceY - fluidMinDepthBelowSurface)`
- it is not already a lava cell
- a detail-noise sample at that point clears a cutoff that **rises with depth** — about `0.35` at the fluid line, `0.55` some 48 blocks below it, topping out at `0.65` around 72 blocks down, so shallow aquifers are common and deep ones are rare
- with `fluidRequiresFloor` on (the default), the cell sits in a cup: solid directly below, solid two below, and at least four of its five remaining neighbours (four horizontal plus above) solid

Set `allowFluid: false` for a completely dry cave system. `allowWater`, `waterMinDepthBelowSurface`, and `waterRequiresFloor` were removed; pack validation rejects them by name in inline dimension, region, and biome profiles and in `snippet/cave-profile` files, so an old dry-cave setting cannot silently fall back to the new `allowFluid: true` default.

## Surface openings

Whether a cave can reach daylight is decided per column before any density sampling:

```
breakColumn = allowSurfaceBreak && surfaceBreakNoise2D(x, z) >= surfaceBreakNoiseThreshold
```

In a break column, carving is allowed all the way up to the terrain surface, and within `surfaceBreakDepth` blocks of the surface the carve threshold is relaxed by `surfaceBreakThresholdBoost` so the opening actually punches through instead of pinching shut. In every other column, carving stops `surfaceClearance` blocks below the surface.

After materials are applied, an ore block sitting on the surface directly above a carved, unsupported cell is deleted. That prevents a floating ore cap over a cave mouth. Supported surface ores and underground ores are untouched.

For sealed caves: `allowSurfaceBreak: false` plus a larger `surfaceClearance`. For more openings: lower `surfaceBreakNoiseThreshold` (it is a signed noise cutoff, so `0.4` opens far more columns than `0.62`).

## Dimension gates

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `carvingEnabled` | boolean | `true` | Master switch. False adds `CARVED` to the disabled mantle components, so no profile anywhere carves. Use it to A/B a world against a solid version |
| `caveProfile` | `IrisCaveProfile` | disabled | The fallback profile for every column no region or biome overrides. This is where most packs put their cave system |
| `carving` | `IrisDimensionCarvingEntry[]` | `[]` | Absolute-world-Y bands that force a specific cave biome regardless of surface biome. Use for a global deep dark or a magma layer |
| `caveBiomeStyle` | `IrisGeneratorStyle` | cellular iris double | Shape of the patches that pick between a region's `caveBiomes`. Cellular gives blobby cave regions; wispy styles give streaks |
| `caveLavaHeight` | int 0..318 | `8` | Engine-local Y at or below which carved cells fill with lava (or forced air when `allowLava` is false). Raise it for a hellish lower world |
| `requireObjectSurfaceSupport` | boolean | `true` | Refuses to place surface objects and trees over a carve opening. Turn off only if you want trees hanging over cave mouths |
| `objectSurfaceSupportBuffer` | int 0..16 | `2` | Blocks of solid ground required around a surface object's footprint. A placement can ask for more but never less |
| `upperDimensionCarving` | boolean | `false` | Lets caves cut into the inverted ceiling terrain of an `upperDimension`. Off leaves the canopy a solid slab |
| `useMantle` | boolean | `true` | Turning this off disables carving, objects, and structures together |

## Cave profile (`IrisCaveProfile`)

Snippet key: `cave-profile`. Valid on **dimension**, **region**, and **biome**.

### Extent and shape

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `enabled` | boolean | `false` | Nothing carves until this is true. A listed but disabled profile is invisible to the resolver |
| `verticalRange` | `IrisRange` | `0..384` | Engine-local Y window this profile may carve in. Clamp it to keep caves out of the deepslate floor or the sky |
| `verticalEdgeFade` | int 0..128 | `20` | Blocks of smoothstep taper at both ends of `verticalRange`. Without it caves are sliced off flat at the boundary |
| `verticalEdgeFadeStrength` | double 0..1 | `0.18` | How hard the taper pushes toward solid. Raise it if the top and bottom of your cave band still look cut |
| `baseDensityStyle` | `IrisGeneratorStyle` | cellular iris double | The field that decides overall cave layout. Cellular reads as chambers and connecting tunnels; simplex reads as sponge |
| `detailDensityStyle` | `IrisGeneratorStyle` | simplex | Added on top of the base field to roughen walls. Keep its weight low or it dissolves the base structure |
| `warpStyle` | `IrisGeneratorStyle` | flat | Domain warp applied to the sample coordinates. Only has an effect when `warpStrength` is above zero |
| `baseWeight` | double >= 0 | `1` | Contribution of the base field. All weights are normalised, so raising this is equivalent to lowering the others |
| `detailWeight` | double >= 0 | `0.35` | Contribution of the detail field. Above about `0.5` the base layout stops being readable |
| `warpStrength` | double >= 0 | `0` | Block distance the warp displaces samples. Small values (0.2 to 1) bend straight tunnels; large values scramble everything and cost a second noise lookup per sample |
| `densityThreshold` | `IrisStyledRange` | `-0.2..0.2`, cellular iris double | The carve cutoff, itself noise-varied across the world so cave size differs region to region. Set `min` equal to `max` for a constant threshold |
| `thresholdBias` | double 0..1 | `0.16` | Subtracted from the sampled threshold before the test. Lower it for more carved space, raise it for less. This is the single knob to reach for when caves are globally too big or too small |

### Sampling and cost

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `sampleStep` | int 1..8 | `1` | 1 and 2 use exact per-cell evaluation. **3 or higher switches to a lattice pass** that samples one column per 2x2 tile and stamps only two vertical blocks per sample, so values above 2 leave uncarved horizontal bands and blocky 2x2 walls. Treat 3+ as a deliberate low-fidelity mode, not a free speedup |
| `adaptiveSampling` | boolean | `true` | With `sampleStep` 1 or 2, classifies a coarse grid first and only evaluates exactly where the plane is ambiguous. Leave it on; it is the main reason carving is affordable |
| `adaptiveSampleStep` | int 2..4 | `2` | The runtime predictor grid is always 8 regardless of this value. What this field actually changes is the ambiguity margin: each step below 8 adds `0.015` to the margin, so `2` is the most conservative (widest margin, most exact fallback) and `4` is the loosest |
| `adaptiveThresholdMargin` | double 0..1 | `0.04` | Base ambiguity band around the threshold where the predictor refuses to guess. Raise it if adaptive sampling is visibly clipping thin tunnels |

Adaptive classification also falls back to exact evaluation whenever fewer than 16 columns are active in a plane, so sparse chunk edges are never approximated.

### Surface interaction

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `surfaceClearance` | int 0..64 | `4` | Solid blocks kept below the terrain surface in non-breaking columns. This is your roof thickness |
| `allowSurfaceBreak` | boolean | `true` | Master switch for cave mouths. False makes every column obey `surfaceClearance` |
| `surfaceBreakStyle` | `IrisGeneratorStyle` | simplex, zoom `0.08` | 2D field that decides which columns may break through. Its zoom sets how large a single opening is |
| `surfaceBreakNoiseThreshold` | double -1..1 | `0.62` | Signed cutoff on that field. Lower means more and wider openings |
| `surfaceBreakDepth` | int 0..64 | `18` | How far below the surface the relaxed threshold applies. Too small and openings pinch shut just under the grass |
| `surfaceBreakThresholdBoost` | double 0..1 | `0.2` | How much easier carving gets inside that depth window |

### Cave object anchoring

These apply to `.iob` placements and procedural objects marked `carvingSupport: CARVING_ONLY`. See `20 - Object Placement.md` and `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `objectMinDepthBelowSurface` | int 0..64 | `6` | Cave objects will not anchor closer than this to the surface, so props do not appear inside a cave mouth |
| `defaultObjectAnchor` | `IrisCaveAnchorMode` | `FLOOR` | Anchor used by any cave placement that leaves its own anchor at `PROFILE_DEFAULT` |
| `defaultObjectPlaceMode` | `ObjectPlaceMode` | unset | Overrides the place mode of cave placements, **but only for placements still on the default `CENTER_HEIGHT`**. Set it to `FAST_MIN_STILT` or `ORGANIC_STILT` so props tile down to the cave floor instead of hovering |
| `anchorScanStep` | int 1..8 | `1` | Vertical step while scanning a column for anchors. Above 1 it can step over one-block ledges |
| `anchorSearchAttempts` | int 1..64 | `6` | Random columns tried per chunk before giving up on a cave placement. Raise it when caves are sparse and props rarely appear |

### Liquids

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `allowFluid` | boolean | `true` | Enables aquifers from the dimension `fluidPalette` |
| `fluidMinDepthBelowSurface` | int 0..64 | `12` | Aquifers stay at least this far below the terrain surface, which keeps water from bleeding out of a hillside |
| `fluidRequiresFloor` | boolean | `true` | Requires the cup test described above. Turning it off gives far more fluid and far more of it pouring down shafts |
| `allowLava` | boolean | `true` | When false, carved cells at or below `caveLavaHeight` are marked forced air rather than lava |

### Density module (`IrisCaveFieldModule`)

Snippet key: `cave-field-module`. Modules are extra density layers summed into the base and detail fields inside their own Y window. Adding a module with a distinct style is how you get two *kinds* of cave in one profile — wide chambers from the base field plus a wormy tunnel network from a module — which raising `detailWeight` alone cannot do.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `style` | `IrisGeneratorStyle` | cellular iris double | The module's own field. Pick something structurally different from `baseDensityStyle` or the module just adds noise |
| `weight` | double >= 0 | `1` | Contribution relative to the base and detail fields. Start around `0.1` |
| `threshold` | double -1..1 | `0` | Offset subtracted from the module sample before weighting, shifting the module toward carving or toward solid |
| `verticalRange` | `IrisRange` | `0..384` | Engine-local Y window where the module contributes at all. Use it for a tunnel layer that exists only in the mid-depths |
| `invert` | boolean | `false` | Flips the module's sign, so it *fills* where it would have carved. Useful for punching solid pillars and ribs through an otherwise open cavern |

### Anchor modes (`IrisCaveAnchorMode`)

| Value | Anchor it selects |
|-------|-------------------|
| `PROFILE_DEFAULT` | Defer to the active profile's `defaultObjectAnchor`; resolves to `FLOOR` if that is also unset |
| `FLOOR` | Carved cell with solid directly below — standing props, stalagmites, chests |
| `CEILING` | Carved cell with solid directly above — hanging roots, stalactites, `CEILING_HANG` objects |
| `CENTER` | Carved cell with no solid immediately above or below — floating props in open air |
| `ANY` | Any carved cell |

A placement using `ObjectPlaceMode.CEILING_HANG` is forced to the `CEILING` anchor regardless of what its anchor field says.

## Dimension carving entries (`IrisDimensionCarvingEntry`)

Snippet key: `dimension-carving-entry`. These override the cave biome inside an absolute world-Y band, independent of what the surface biome above is. Every enabled entry whose biome has an enabled `caveProfile` also contributes that profile as an extra carving pass restricted to the band, on top of the two-blended-profile budget.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `id` | string | `""` | Stable identifier. Other entries reference it through `children`, and floating child biomes can reference it by id |
| `enabled` | boolean | `true` | Disabled entries are skipped and drop out of the reachable-biome closure |
| `biome` | biome key | `""` | The cave biome applied throughout the band |
| `worldYRange` | `IrisRange` | `-64..320` | **Absolute** world Y, unlike everything else on the profile |
| `children` | string[] | `[]` | Ids of entries that carve patches inside this one. Cycles back to a parent id are allowed and bounded by depth |
| `childShrinkFactor` | double | `1.5` | Larger values make child patches smaller relative to the parent band |
| `childStyle` | `IrisGeneratorStyle` | cellular iris double | Shape of the child patches |
| `childRecursionDepth` | int | `3` | How many levels of children are resolved before the walk stops |

```json
{
  "carving": [
    {
      "id": "global-deepdark-band",
      "enabled": true,
      "biome": "carving/standard-deepdark",
      "worldYRange": { "min": -250, "max": -175 }
    }
  ]
}
```

## Cave biome content reference

| Mechanism | Lives on | What it contributes |
|-----------|----------|---------------------|
| `caveBiomes` | region | The pool `caveBiomeStyle` samples from, weighted by biome `rarity` |
| `caveBiomeZoom` | region | Multiplies the dimension `biomeZoom` for cave patches only, so cave regions can be a different size from surface biomes |
| `caveMinDepthBelowSurface` | cave biome | Above this depth the surface biome is used instead, keeping cave materials out of shallow overhangs |
| `caveProfile` | any biome | Local density override, only when enabled |
| `layers` | cave biome | Floor materials, applied downward from the block below the lowest carved cell |
| `caveCeilingLayers` | cave biome | Roof materials, applied upward from the block above the highest carved cell |
| `wall` | cave biome | Painted onto every solid block horizontally adjacent to carved space that is below the terrain surface. The same palette also paints surface cliffs in the post pass, so a cave biome usually wants its own `wall` |
| `decorators` | cave biome | Default-part decorators land on the cave floor; `partOf: CEILING` decorators hang from the roof |
| `objects` / `proceduralObjects` | cave biome | Props, gated by `carvingSupport: CARVING_ONLY` and the profile's anchor settings |

Cave biomes still accept height generators, but nothing reads them underground. Leave them out or use a trivial filler.

## Cave-anchored jigsaw structures

Editable Iris jigsaws can resolve their start against carved space in the mantle instead of the surface or a blind Y band. Put the placement in `structures[]` on a dimension, region, surface biome, or cave biome and use one of the explicit cave anchors; a cave-biome `structures[]` list contributes cave anchors only.

```json
{
  "structures": [
    {
      "structures": ["stronghold/demo"],
      "placementId": "stronghold-demo-cave-floor",
      "distribution": "RANDOM_SPREAD",
      "spacing": 24,
      "separation": 8,
      "salt": 984211,
      "anchor": "CAVE_FLOOR",
      "minHeight": -48,
      "maxHeight": 80,
      "caveBiomes": ["carving/deep"],
      "caveAnchorAttempts": 12,
      "caveAnchorScanStep": 1,
      "caveMinimumClearance": 5,
      "terrain": {"mode": "PRESERVE"}
    }
  ]
}
```

| Field | Default | Runtime behavior |
|---|---|---|
| `anchor` | `LEGACY` | `CAVE_FLOOR`, `CAVE_CEILING`, `CAVE_CENTER`, or `CAVE_ANY` search carved cells instead of terrain height |
| `minHeight` / `maxHeight` | `-2032` / `2032` | Inclusive absolute world-Y scan band, clipped to one block inside the dimension's usable height |
| `caveBiomes` | empty | Allowlist rechecked against the cave biome at each candidate anchor; keys are trimmed, case-normalized, and may include or omit the namespace |
| `caveAnchorAttempts` | `8` | Unique columns tested inside the start chunk, clamped to `1..64`. Columns are visited by a seeded odd stride so no column repeats |
| `caveAnchorScanStep` | `1` | Vertical scan increment, clamped to `1..16`. Above one it can skip valid single-block anchors |
| `caveMinimumClearance` | `3` | Required contiguous vertical carved run, clamped to `1..64` |
| `underwater` | `false` | For cave anchors, requires a dry cavern cell: ordinary cavern air must be above `caveLavaHeight`, explicit fluid and lava cells are rejected, and forced-air cavern matter counts as dry even below that line. `true` permits fluid cavern cells |

Geometry and alignment:

| Anchor | Candidate test | Alignment after assembly |
|---|---|---|
| `CAVE_FLOOR` | Candidate is carved, the cell below is not, and the clearance run continues upward | Lowest structure bound shifted to the anchor Y |
| `CAVE_CEILING` | Candidate is carved, the cell above is not, and the clearance run continues downward | Highest structure bound shifted to the anchor Y |
| `CAVE_CENTER` | Candidate is a midpoint of its contiguous carved run, and that run meets the clearance requirement | Bounding-box midpoint shifted to the anchor Y |
| `CAVE_ANY` | A clearance-sized carved run is centred on the candidate | Bounding-box midpoint shifted to the anchor Y |

Selection is deterministic for the world seed, placement identity, and start chunk. Iris visits at most 64 of the chunk's 256 columns, stops at the first column with any match, and picks deterministically among every valid anchor in that column. When no candidate passes, the placement is skipped — there is no fallback to a surface or height-band start.

The test reads a single vertical cavern column. It proves local clearance, not that the assembled footprint fits. `SOURCE` and `PRESERVE` can therefore leave pieces embedded in surrounding rock; use `BORE` or `FORCE_CARVE` when the structure must make its own room.

Scope is decided at chunk centre: surface-biome, cave-biome, region, and dimension lists available there all contribute candidate placements. Cave lookup requires already-materialized mantle data, so a locator cannot resolve a distant ungenerated cave anchor until terrain generation has produced that mantle.

Cave anchors count as underground placement. Iris skips the surface-burial shift and does not clear intersecting surface trees. Piece placement resolves to `STRUCTURE_PIECE` underground except for authored `ORGANIC_STILT` and `CEILING_HANG` modes. The `anchor` field is rejected on `nativeStructures`. Full authoring detail is in `21 - Jigsaw Structures.md`.

## Vanilla carvers never run

Iris does not implement Minecraft `NoiseGeneratorSettings` carver sampling. The biome definitions Iris emits carry empty carver entries, and on the Fabric, Forge, and NeoForge adapters `applyCarvers` is an explicit no-op. Pack authors must use `caveProfile` and cave biomes; datapack carver features have no effect on Iris terrain. See `30 - Platform Differences.md`.

## Tuning quick reference

| Goal | Change |
|------|--------|
| Bigger caverns everywhere | Raise `thresholdBias` toward `0.25`, or widen `densityThreshold` downward |
| Thinner tunnels | Lower `thresholdBias`, lower `detailWeight`, add an inverted module to fill the middles |
| Two distinct cave types in one profile | Add a module with a different `style` and its own `verticalRange` |
| Fewer surface holes | Raise `surfaceBreakNoiseThreshold`, lower `surfaceBreakDepth`, or `allowSurfaceBreak: false` with a larger `surfaceClearance` |
| Cave props stop floating | Set `defaultObjectPlaceMode` to a stilt mode and raise `objectMinDepthBelowSurface` |
| Completely dry caves | `allowFluid: false` and `allowLava: false` |
| Lava-filled caverns instead of water | Change the dimension `fluidPalette` to lava; leave the profile alone |
| Cheaper carving | Keep `adaptiveSampling` on and simplify the styles. Prefer this over raising `sampleStep`, which degrades shape |

## Practical notes

- `enabled: false` is the Java default on every cave profile, including the dimension's. Listing cave biomes without enabling a profile produces no caves at all.
- Carving is written into the mantle. Existing chunks never gain caves from a settings change; only fresh chunks do.
- Cave biome layers do not create voids. They only replace blocks that carving already exposed.
- Upper-dimension carving is off by default, which leaves an `upperDimension` ceiling as an untouched solid mass.

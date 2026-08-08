# 15 - Caves & Carving

Iris carves caves itself during mantle generation via `MantleCarvingComponent` and `IrisCaveCarver3D`. Density fields from `IrisCaveProfile` decide solid vs air/water/lava. Cave biomes paint floors, ceilings, decorators, and objects inside carved space. Vanilla and mod noise carvers never run over Iris terrain.

Related: `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `16 - Surfaces, Decorators & Deposits.md`, `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`, `20 - Object Placement.md`, `22 - Native Structures & Datapacks.md`.

## Architecture (author-relevant)

1. Dimension `carvingEnabled` must be true (default).
2. Per column, Iris resolves a cave profile from biome → region → dimension (`enabled` profiles only).
3. Profiles blend across neighbors; `IrisCaveCarver3D` samples 3D density and writes carve flags into the mantle.
4. Cave biomes (region `caveBiomes`, dimension `carving` Y-band overrides, surface biome `carvingBiome`) supply materials and content for carved voxels.
5. Fluid placement inside caves follows profile water/lava rules and surface-clearance guards.

Empty pack folders such as `caves/` or `ravines/` are not separate registrant types. Carving is profile-driven JSON on dimensions/biomes/regions, not standalone cave files.

## Vanilla carvers never run

Iris does not implement Minecraft `NoiseGeneratorSettings` carver sampling. Generated biome JSON keeps empty `carvers` arrays. `applyCarvers` on the Iris chunk generator is a no-op for Iris-owned terrain. Pack authors must use `caveProfile` (and related cave biomes), not vanilla carver JSON or datapack carver features. See also platform notes in `30 - Platform Differences.md` / API matrix.

## Dimension gates

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `carvingEnabled` | boolean | `true` | Master switch for all profile carving |
| `caveProfile` | `IrisCaveProfile` | disabled defaults | Global/default profile |
| `carving` | `IrisDimensionCarvingEntry[]` | `[]` | Absolute world-Y cave biome bands |
| `caveBiomeStyle` | `IrisGeneratorStyle` | cellular | Picks among region cave biomes |
| `requireObjectSurfaceSupport` | boolean | `true` | Refuse surface objects over carve openings |
| `objectSurfaceSupportBuffer` | int 0..16 | `2` | Minimum solid buffer for surface objects |
| `upperDimensionCarving` | boolean | `false` | Carve through ceiling/upper terrain when set |
| `useMantle` | boolean | `true` | Mantle required for carving/objects |

## Cave profile (`IrisCaveProfile`)

Snippet key: `cave-profile`. Appears on **dimension**, **region**, and **biome**. Resolution prefers the most specific enabled profile in the mantle path (biome/region/dimension blend).

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | boolean | `false` | Must be true to carve |
| `verticalRange` | `IrisRange` | `0..384` | Global carve Y band for the profile |
| `verticalEdgeFade` | int 0..128 | `20` | Soft edge near min/max |
| `verticalEdgeFadeStrength` | double 0..1 | `0.18` | Fade strength |
| `baseDensityStyle` | `IrisGeneratorStyle` | cellular iris double | Primary density field |
| `detailDensityStyle` | `IrisGeneratorStyle` | simplex | Detail field |
| `warpStyle` | `IrisGeneratorStyle` | flat | Coordinate warp |
| `baseWeight` | double ≥ 0 | `1` | Base field multiplier |
| `detailWeight` | double ≥ 0 | `0.35` | Detail multiplier |
| `warpStrength` | double ≥ 0 | `0` | Warp amount |
| `densityThreshold` | `IrisStyledRange` | ±0.2 cellular | Carve cutoff band |
| `thresholdBias` | double 0..1 | `0.16` | Extra bias subtracted before tests |
| `sampleStep` | int 1..8 | `1` | Vertical density step |
| `adaptiveSampling` | boolean | `true` | Coarse predictor then refine |
| `adaptiveSampleStep` | int 2..4 | `2` | Horizontal predictor grid |
| `adaptiveThresholdMargin` | double 0..1 | `0.04` | Ambiguity margin |
| `surfaceClearance` | int 0..64 | `4` | Min solid below terrain before carve |
| `allowSurfaceBreak` | boolean | `true` | Permit selected surface openings |
| `surfaceBreakStyle` | style | simplex zoomed | Where openings may occur |
| `surfaceBreakNoiseThreshold` | double -1..1 | `0.62` | Min noise for break columns |
| `surfaceBreakDepth` | int 0..64 | `18` | Depth window for break logic |
| `surfaceBreakThresholdBoost` | double 0..1 | `0.2` | Easier carve near surface break |
| `objectMinDepthBelowSurface` | int 0..64 | `6` | Cave-object depth gate |
| `modules` | `IrisCaveFieldModule[]` | `[]` | Extra density layers |
| `defaultObjectAnchor` | `IrisCaveAnchorMode` | `FLOOR` | Cave object anchor default |
| `defaultObjectPlaceMode` | `ObjectPlaceMode` | null | Prefer stilt modes for cave props |
| `anchorScanStep` | int 1..8 | `1` | Vertical anchor search step |
| `anchorSearchAttempts` | int 1..64 | `6` | Random column retries per chunk |
| `allowWater` | boolean | `true` | Cave water below fluid height |
| `waterMinDepthBelowSurface` | int 0..64 | `12` | Depth before cave water |
| `waterRequiresFloor` | boolean | `true` | Solid floor under water |
| `allowLava` | boolean | `true` | Cave lava by lava height rules |

### Density module (`IrisCaveFieldModule`)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `style` | `IrisGeneratorStyle` | cellular | Module density |
| `weight` | double ≥ 0 | `1` | Contribution |
| `threshold` | double -1..1 | `0` | Pre-blend offset |
| `verticalRange` | `IrisRange` | `0..384` | Module Y window |
| `invert` | boolean | `false` | Invert before weighting |

### Anchor modes (`IrisCaveAnchorMode`)

| Value | Meaning |
|-------|---------|
| `PROFILE_DEFAULT` | Use profile default |
| `FLOOR` | Solid support below carved cell |
| `CEILING` | Solid support above |
| `CENTER` | No immediate floor/ceiling support |
| `ANY` | Any carved anchor |

## Dimension carving entries (`IrisDimensionCarvingEntry`)

Absolute world-Y cave biome overrides independent of surface biome.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `id` | string | `""` | Stable id (child references) |
| `enabled` | boolean | `true` | Toggle |
| `biome` | biome key | `""` | Cave biome applied in band |
| `worldYRange` | `IrisRange` | `-64..320` | Absolute world Y |
| `children` | string[] | `[]` | Child entry ids (cycles allowed, depth-limited) |
| `childShrinkFactor` | double | `1.5` | Child patch scale |
| `childStyle` | style | cellular | Child patch shape |
| `childRecursionDepth` | int | `3` | Max child resolve depth |

## Cave biomes (content)

Cave biomes are normal biome JSON used only underground:

| Mechanism | Location | Role |
|-----------|----------|------|
| Region `caveBiomes` | region JSON | Pool selected by `caveBiomeStyle` |
| Biome `carvingBiome` | surface biome | Optional fixed carve biome under that surface |
| Biome `caveMinDepthBelowSurface` | surface biome | Min depth before that carve biome applies |
| Dimension `carving[]` | dimension | Y-band force biomes |
| Biome `caveProfile` | any biome | Local carve density override when enabled |
| `layers` / `caveCeilingLayers` / `wall` | cave biome | Floor / ceiling / wall materials |
| `decorators` with `partOf: CEILING` | cave biome | Hang from ceilings |
| `objects` / `proceduralObjects` | cave biome | Cave props (`carvingSupport: CARVING_ONLY`) |

Surface biomes still provide height generators; cave biomes typically omit height generators or use fillers—the carve step removes solid first.

## Overworld examples

Dimension switch and deepdark band (`dimensions/overworld.json`):

```json
{
"carvingEnabled": true,
"caveProfile": {
  "enabled": true,
  "verticalRange": { "min": 6, "max": 700 },
  "baseDensityStyle": { "style": "PERLIN_IRIS", "zoom": 0.72 },
  "detailDensityStyle": { "style": "SIMPLEX", "zoom": 0.54 },
  "warpStyle": { "style": "FRACTAL_WATER", "zoom": 0.5 },
  "baseWeight": 0.9,
  "detailWeight": 0.11,
  "warpStrength": 0.24,
  "densityThreshold": {
    "min": -0.14,
    "max": -0.06,
    "style": { "style": "SIMPLEX", "zoom": 0.74 }
  },
  "thresholdBias": 0.14,
  "sampleStep": 3,
  "surfaceClearance": 5,
  "allowSurfaceBreak": true,
  "surfaceBreakStyle": { "style": "SIMPLEX", "zoom": 0.88 },
  "surfaceBreakNoiseThreshold": 0.6,
  "surfaceBreakDepth": 16,
  "surfaceBreakThresholdBoost": 0.1,
  "objectMinDepthBelowSurface": 14,
  "defaultObjectAnchor": "FLOOR",
  "defaultObjectPlaceMode": "ORGANIC_STILT",
  "anchorSearchAttempts": 12,
  "allowWater": true,
  "waterMinDepthBelowSurface": 20,
  "waterRequiresFloor": true,
  "allowLava": true,
  "modules": [
    {
      "style": { "style": "SIMPLEX_VASCULAR", "zoom": 1.08 },
      "weight": 0.08,
      "threshold": 0.03,
      "verticalRange": { "min": 24, "max": 660 },
      "invert": false
    }
  ],
  "verticalEdgeFade": 24,
  "verticalEdgeFadeStrength": 0.18
},
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

Region cave pool (`regions/temperate.json`):

```json
{
"caveBiomes": [
  "carving/rocky-cavebiome",
  "carving/deep",
  "carving/drip",
  "carving/chalk-gardens",
  "carving/moss-pillars"
]
}
```

Cave biome content (`biomes/carving/amethyst.json` excerpt): floor/wall amethyst, floor buds, ceiling-facing clusters via `"partOf": "CEILING"`, `caveCeilingLayers` for roof materials.

## Authoring workflow

1. Enable dimension `caveProfile` with a vertical range covering playable Y.
2. Add `modules` for tunnels/rooms instead of raising `detailWeight` alone.
3. List themed biomes under each region's `caveBiomes` (and optional dimension `carving` bands).
4. Paint cave biomes with `layers`, `caveCeilingLayers`, `wall`, ceiling/floor decorators, and cave-only objects.
5. For surface sinkholes, keep `allowSurfaceBreak` true and tune `surfaceBreak*` noise; for sealed caves raise `surfaceClearance` and disable surface break.
6. Place cave objects with `carvingSupport: CARVING_ONLY` and stilt place modes (`FAST_MIN_STILT` / `ORGANIC_STILT`) to avoid floating props.
7. Verify: studio regen, check openings, waterfalls (`waterRequiresFloor`), and lava depth.

## Tuning knobs (quick)

| Goal | Adjust |
|------|--------|
| Larger caverns | Lower `densityThreshold` band / raise bias toward carve |
| Thinner tunnels | Raise threshold, lower `detailWeight`, add inverted modules |
| Fewer surface holes | Raise `surfaceBreakNoiseThreshold`, lower `surfaceBreakDepth`, or `allowSurfaceBreak: false` |
| Safer cave props | Raise `objectMinDepthBelowSurface`, set place mode + anchor |
| Dry caves | `allowWater: false` |
| Performance | Higher `sampleStep`, keep adaptive sampling on, simpler styles |

## Practical notes

- Profile `enabled: false` (the Java default) produces no profile carving even if cave biomes are listed.
- Cave biome layers still need solid carve first; they do not create voids alone.
- Upper-dimension carving is optional and off in overworld.
- Pack JSON may contain unknown keys; only fields on `IrisCaveProfile` apply.

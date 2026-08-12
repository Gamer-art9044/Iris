# 15 - Caves & Carving

Iris carves caves itself during mantle generation via `MantleCarvingComponent` and `IrisCaveCarver3D`. Density fields from `IrisCaveProfile` decide solid vs air, the dimension's configured fluid palette, or deep lava. Cave biomes paint floors, ceilings, decorators, and objects inside carved space. Vanilla and mod noise carvers never run over Iris terrain.

Related: `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `16 - Surfaces, Decorators & Deposits.md`, `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`, `20 - Object Placement.md`, `22 - Native Structures & Datapacks.md`.

## Tutorial: carve a controlled test volume

Start with a validating `OVERWORLD` pack whose surface and fluid height are already correct. Add this complete field set to the root object in `dimensions/<key>.json`; it uses the production defaults for density but confines the first test and prevents surface or liquid openings:

```json
{
  "carvingEnabled": true,
  "caveProfile": {
    "enabled": true,
    "verticalRange": { "min": 0, "max": 64 },
    "allowSurfaceBreak": false,
    "allowFluid": false,
    "allowLava": false
  }
}
```

1. Record seed `1337` and surface coordinates in a Studio world before enabling the profile.
2. Add the fields above to the existing dimension JSON, validate the pack, and reopen Studio if the change is not accepted by the running engine.
3. Generate new chunks and inspect below the surface. Success is carved air within the configured vertical range, an intact surface, and no fluid-palette blocks or deep lava placed by the cave profile.
4. If no caves appear, confirm dimension `mode.type` is `OVERWORLD`, `useMantle` and `carvingEnabled` are true, and the effective biome or region profile is not overriding this dimension profile. Test only fresh chunks.
5. Once the void shape is proven, add one biome key to a region's `caveBiomes`, then add cave layers and decorators. Enable dimension fluid, deep lava, or surface breaks one setting at a time so each change remains observable.

## Architecture (author-relevant)

1. Dimension `carvingEnabled` must be true (default).
2. Per column, Iris resolves a cave profile from biome → region → dimension (`enabled` profiles only).
3. Profiles blend across neighbors; `IrisCaveCarver3D` samples 3D density and writes carve flags into the mantle.
4. Cave biomes (region `caveBiomes`, dimension `carving` Y-band overrides, surface biome `carvingBiome`) supply materials and content for carved voxels.
5. Aquifers sample the dimension `fluidPalette` below `fluidHeight`; deep lava remains a separate profile rule controlled by `allowLava` and `caveLavaHeight`.

Enabled dimension `carving` biome graphs are included in the dimension's recursive reachable-biome closure even when no region lists them, so their custom biome identities and spawn mappings are available wherever the Y-band selects them.

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

Snippet key: `cave-profile`. Appears on **dimension**, **region**, and **biome**. Resolution prefers the most specific enabled profile in the mantle path (biome/region/dimension blend). The dimension-level `fluidPalette` supplies aquifer material and accepts any weighted block palette; its default is water. A lava `fluidPalette` therefore turns otherwise identical Overworld aquifers into lava without changing cave geometry.

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
| `allowFluid` | boolean | `true` | Place dimension `fluidPalette` aquifers below fluid height |
| `fluidMinDepthBelowSurface` | int 0..64 | `12` | Minimum surface burial before aquifer placement |
| `fluidRequiresFloor` | boolean | `true` | Require a supported cup beneath aquifer blocks |
| `allowLava` | boolean | `true` | Place vanilla lava at or below `caveLavaHeight` |

`allowWater`, `waterMinDepthBelowSurface`, and `waterRequiresFloor` were removed. Pack validation rejects them with their replacement names so an old dry-cave setting cannot silently fall back to the new `allowFluid: true` default.

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

## Cave-anchored jigsaw structures

Editable Iris jigsaws can resolve starts against the carved-space mantle instead of the surface or a blind Y band. Put the placement in `structures[]` on a dimension, region, surface biome, or cave biome and use one of the explicit cave anchors; cave-biome `structures[]` ignores non-cave placements.

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
| `anchor` | `LEGACY` | Use `CAVE_FLOOR`, `CAVE_CEILING`, `CAVE_CENTER`, or `CAVE_ANY` to search carved cells |
| `minHeight` / `maxHeight` | `-2032` / `2032` | Inclusive world-Y scan band, clipped inside the dimension's usable height |
| `caveBiomes` | empty | Optional allowlist checked against the cave/mantle biome at the candidate anchor; keys are trimmed, case-normalized, and may include or omit the namespace |
| `caveAnchorAttempts` | `8` | Deterministic unique X/Z columns tested inside the selected start chunk; runtime clamps to `1..64` |
| `caveAnchorScanStep` | `1` | Vertical scan increment; runtime clamps to `1..16`; values above one can skip valid one-block anchors |
| `caveMinimumClearance` | `3` | Required contiguous vertical carved run; runtime clamps to `1..64` |
| `underwater` | `false` | For cave anchors, require a dry cavern cell: ordinary cavern air must be above `caveLavaHeight`, explicit palette-fluid/deep-lava cells are rejected, and forced-air cavern matter remains dry below that threshold; `true` permits fluid cavern cells |

Geometry and alignment are exact:

| Anchor | Candidate test | Alignment after assembly |
|---|---|---|
| `CAVE_FLOOR` | Candidate is carved, cell below is not carved, and clearance continues upward | Lowest structure bound is shifted to anchor Y |
| `CAVE_CEILING` | Candidate is carved, cell above is not carved, and clearance continues downward | Highest structure bound is shifted to anchor Y |
| `CAVE_CENTER` | Candidate is the actual midpoint of its contiguous carved cavern run, and that run meets the clearance requirement | Bounding-box midpoint is shifted to anchor Y |
| `CAVE_ANY` | A clearance-sized carved run is centered around the candidate | Bounding-box midpoint is shifted to anchor Y |

Selection is deterministic for the world seed, placement identity, and start chunk. Iris visits at most 64 unique columns from the chunk's 256 columns, stops at the first column with matches, and chooses deterministically among every valid anchor found in that column. When no candidate passes, the placement is skipped; Iris does not fall back to a surface or height-band start.

The cave-anchor `underwater` gate reads `MatterCavern` at the actual anchor, not ocean height at the surface. A null or non-cavern cell is never an anchor. With `underwater: false`, explicit palette-fluid/deep-lava cells and ordinary cavern air at or below the dimension's `caveLavaHeight` are rejected, while forced-air cavern matter is accepted even below that threshold. With `underwater: true`, fluid cavern cells are allowed but the cell must still be carved cavern matter.

The test reads one vertical `MatterCavern` column. It proves local clearance only, not that the complete assembled footprint fits the cave. `SOURCE` and `PRESERVE` can therefore leave pieces intersecting surrounding walls. Use `BORE` or `FORCE_CARVE` when the structure must make room, or inspect the complete volume in gameplay when preserving the cavern.

Scope is decided at chunk center: the surface-biome, cave-biome, region, and dimension lists available there contribute candidate placements, but a cave-biome list contributes cave anchors only. A non-empty placement `caveBiomes` allowlist is then rechecked at each actual X/Y/Z anchor candidate. Cave lookup requires already materialized Iris mantle data; a locator cannot resolve an ungenerated distant cave anchor until terrain generation has produced that mantle.

Cave anchors are treated as underground placement. Iris does not perform the separate surface-burial shift or clear intersecting surface trees. Piece placement normally resolves to `STRUCTURE_PIECE` underground, except authored `ORGANIC_STILT` and `CEILING_HANG` modes retain their special behavior. Use `terrain.mode: PRESERVE` to keep the cave, `BORE` for a rectangular clearance envelope, or `FORCE_CARVE` for the configured box/rounded/eroded envelope.

The anchor field is rejected for `nativeStructures`; it applies to editable Iris `structures` only. Complete jigsaw placement and authoring behavior is in `21 - Jigsaw Structures.md`.

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
  "allowFluid": true,
  "fluidMinDepthBelowSurface": 20,
  "fluidRequiresFloor": true,
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

## Extend the controlled cave test

1. Record a fixed seed and coordinates where the surface, fluid level, and bedrock are already correct.
2. Enable the dimension `caveProfile` with a narrow vertical range inside playable Y and no cave-biome decoration yet.
3. Add one tunnel or room module. Generate new chunks and verify void shape, surface clearance, fluid-palette handling, and lava depth.
4. Add modules for other shapes instead of raising `detailWeight` alone. Change one density or threshold value per comparison.
5. List one themed biome under one region's `caveBiomes`; paint its floor, ceiling, and walls before adding objects.
6. Add cave-only objects with `carvingSupport: CARVING_ONLY` and an appropriate stilt mode so props do not float.
7. Decide explicitly whether caves may break the surface. Tune `surfaceBreak*` for openings or disable surface break and raise clearance for sealed caves.
8. Revisit the recorded surface coordinates and generate fresh cave areas. The tutorial passes when surface terrain is unchanged outside intentional openings and cave content remains inside carved space.

## Tuning knobs (quick)

| Goal | Adjust |
|------|--------|
| Larger caverns | Lower `densityThreshold` band / raise bias toward carve |
| Thinner tunnels | Raise threshold, lower `detailWeight`, add inverted modules |
| Fewer surface holes | Raise `surfaceBreakNoiseThreshold`, lower `surfaceBreakDepth`, or `allowSurfaceBreak: false` |
| Safer cave props | Raise `objectMinDepthBelowSurface`, set place mode + anchor |
| No aquifers | `allowFluid: false` |
| Performance | Higher `sampleStep`, keep adaptive sampling on, simpler styles |

## Practical notes

- Profile `enabled: false` (the Java default) produces no profile carving even if cave biomes are listed.
- Cave biome layers still need solid carve first; they do not create voids alone.
- Upper-dimension carving is optional and off in overworld.
- The three removed water-specific cave-profile keys are blocking pack errors in inline dimension, region, and biome profiles and in `snippet/cave-profile` files; other unknown keys remain subject to the normal pack validation rules.

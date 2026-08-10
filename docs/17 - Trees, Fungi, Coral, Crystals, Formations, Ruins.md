# 17 - Trees, Fungi, Coral, Crystals, Formations, Ruins

Procedural objects are baked from JSON settings into deterministic block blobs and scattered like `.iob` placements. They live under biome or region `proceduralObjects` and do not require object files. Separate systems: sapling tree overrides (`IrisTree` on object placements) and dimension `treeSettings` for growth replacement mode.

Related: `13 - Biomes.md`, `12 - Regions.md`, `15 - Caves & Carving.md`, `16 - Surfaces, Decorators & Deposits.md`, `18 - Structures Overview.md`, `19 - Objects.md`, `20 - Object Placement.md`.

## Tutorial: generate a procedural tree from biome JSON

Use a validating `OVERWORLD` pack with mantle and decoration enabled. Save this complete test biome as `biomes/tutorial/tree-test.json`, list `tutorial/tree-test` as a region land biome, and temporarily focus the dimension on it:

```json
{
  "name": "Procedural Tree Test",
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains",
  "layers": [
    { "palette": [{ "block": "minecraft:grass_block" }] }
  ],
  "generators": [
    { "generator": "flat", "min": 16, "max": 16 }
  ],
  "proceduralObjects": {
    "trees": [
      {
        "name": "tutorial-oak",
        "chance": 1,
        "density": 2,
        "variants": 4,
        "seed": 9001,
        "trunk": "minecraft:oak_log",
        "leaves": "minecraft:oak_leaves",
        "profile": "OAK",
        "heightMin": 7,
        "heightMax": 11,
        "plausible": true
      }
    ]
  }
}
```

1. Reuse `generators/flat.json` from the minimal dimension walkthrough, validate the pack, and open Studio on seed `1337`.
2. Generate several new chunks. Success is a repeatable set of oak variants anchored to the grass surface, with leaves that use normal decay-distance data because `plausible` is true.
3. If no trees appear, confirm the biome is focused, new chunks were generated, dimension `decorate` and `useMantle` are true, and the console did not reject every baked variant. Keep `chance: 1` until placement is proven.
4. Tune height, profile, trunk shape, and canopy before lowering chance or increasing density. Remove focus and reduce chance only after the same seed reproduces the shapes across a Studio reopen.

## Container (`IrisProceduralObjects`)

| Field | Type | Contents |
|-------|------|----------|
| `trees` | `IrisProceduralTree[]` | Procedural trees |
| `fungi` | `IrisFungus[]` | Mushrooms / shelf fungi |
| `coral` | `IrisCoral[]` | Underwater coral forms |
| `crystals` | `IrisCrystal[]` | Cave crystal clusters |
| `formations` | `IrisFormation[]` | Rock landmarks |
| `ruins` | `IrisRuin[]` | Crumbling man-made shapes |

Attach on biomes and regions:

```json
{
"proceduralObjects": {
  "trees": [ { "...": "..." } ],
  "crystals": [ { "...": "..." } ]
}
}
```

Shipping overworld content currently relies on `.iob` tree objects more than procedural trees; the fields and generators are fully available for pack authors.

## Shared placement fields

All procedural types implement placement via `asPlacement()` → `IrisObjectPlacement`-compatible settings:

| Field | Typical default | Notes |
|-------|-----------------|-------|
| `name` | type-specific | Log key / variant key prefix |
| `chance` | 0.02–0.4 | Per-chunk attempt probability |
| `density` | `1` | Attempts if chance passes |
| `variants` | 6–8 | Pre-baked shape count (1..64) |
| `seed` | `1337` | Deterministic bake seed |
| `mode` | `CENTER_HEIGHT` (ruins: `MIN_HEIGHT`) | Terrain anchor mode |
| `rotation` | object rotation | Placement rotation |
| `clamp` | height limits | Min/max place height |
| `carvingSupport` | `SURFACE_ONLY` (crystals: `CARVING_ONLY`) | Surface vs cave |
| `underwater` | type-specific | Coral default true |
| `translate` | zero | XYZ offset |
| `stiltSettings` / `vacuumSettings` | optional | For stilt/vacuum modes |

Variant objects load as keys like `procedural/<name>#<i>` (trees: `procedural/tree/<name>#<i>`). Same seed + settings always bake the same variants.

`CarvingMode`: `SURFACE_ONLY`, `CARVING_ONLY`, `ANYWHERE`.

Common `ObjectPlaceMode` values: `CENTER_HEIGHT`, `MAX_HEIGHT`, `MIN_HEIGHT`, `FAST_*` variants, `STILT`, `MIN_STILT`, `FAST_STILT`, `CENTER_STILT`, `ERODE_STILT`, `ORGANIC_STILT`, vacuum modes. Cave defaults often use organic/fast stilt to prevent floating.

## Procedural trees (`IrisProceduralTree`)

Baked by `ProceduralTreeGenerator`.

### Core materials and size

| Field | Default | Notes |
|-------|---------|-------|
| `trunk` / `trunkPalette` | oak log | Palette wins |
| `leaves` / `leavesPalette` | oak leaves | Palette wins; real leaves get decay distances when `plausible` |
| `plausible` | `true` | Vanilla leaf distance / non-persistent |
| `heightMin` / `heightMax` | 8 / 12 | Trunk height range across variants |
| `trunkWidth` | `1` | Base trunk thickness |
| `profile` | `OAK` | Named canopy silhouette |

### Profiles (`IrisTreeProfile`)

`OAK`, `BIRCH`, `SPRUCE`, `JUNGLE`, `ACACIA`, `DARK_OAK`, `DARK_OAK_FLAT`, `DARK_OAK_FLAT_WIDE`, `CHERRY`, `PALM`, `WILLOW`, `COLUMNAR`, `BUSH`, `MEGA_SPRUCE`.

### Trunk shaping

| Field | Default | Notes |
|-------|---------|-------|
| `trunkShape` | `CONSTANT` | `IrisTreeFunction` over trunk width by height |
| `shapeStart` / `shapeEnd` | `1` / `1` | Linear width multipliers |
| `shapeSteepness` | `5` | Sigmoid steepness |
| `shapeBase` | `2.718281828` | Logarithmic base |
| `shapePeriod` / `shapeAmplitude` | `1` / `0.2` | Sine width controls |
| `shapePeakOffset` / `shapeFloor` | `0.5` / `0.5` | Parabolic waist position and minimum fraction |
| `leanAzimuth` / `leanAngle` | `0` / `0` | Compass direction and degrees from vertical |
| `trunkCurve` / `curveSteepness` | `LINEAR` / `8` | Lean accumulation and sigmoid steepness |
| `leanAzimuthMode` | `CONSTANT` | How lean direction changes over height |
| `azimuthStart` / `azimuthEnd` | `0` / `0` | Linear azimuth endpoints |
| `azimuthTurns` | `1` | Full turns for `SPIRAL` |
| `azimuthAmplitude` / `azimuthPeriod` / `azimuthOffset` | `90` / `1` / `0` | Sine azimuth controls |
| `azimuthScale` | `1` | Noise azimuth scale |
| `azimuthWhorlCount` | `5` | Branches per ring for `WHORL` |
| `trunkForks` / `forkHeight` / `forkAngle` | `1` / `0.5` / `25` | Fork count, normalized split height, and outward angle |
| `secondaryTrunk` / `secondaryTrunkPalette` | null | Optional band material; palette wins |
| `secondaryTrunkStart` / `secondaryTrunkEnd` | `0.5` / `1` | Normalized secondary band bounds |
| `roots` / `rootStyle` | `true` / `BUTTRESS` | Enable roots and select `TAPROOT`, `BUTTRESS`, or `STILT` |
| `rootDepth` / `rootFlare` | `0` / `0` | Explicit values; zero uses automatic scaling |

`IrisTreeFunction`: `CONSTANT`, `LINEAR`, `SIGMOID`, `LOG`, `SINE`, `PARABOLIC`, `EXPONENTIAL`, `SQRT`, `STEP`, `BELL`, `EASE_IN_OUT`.

`IrisTreeAzimuthMode`: `CONSTANT`, `LINEAR`, `SPIRAL`, `SINE`, `NOISE`, `RANDOM`, `GOLDEN_ANGLE`, `ALTERNATING`, `WHORL`, `ZIGZAG`.

### Canopy (`IrisTreeCanopy`)

| Field | Default | Notes |
|-------|---------|-------|
| `startAngle` | `90` | Disc elevation (90 flat, lower = dome, higher = umbrella) |
| `squish` | `1` | Vertical scale |
| `mode` | `TRIMMED` | Leaf fill mode |
| `leafDensity` | `0.85` | For density/noise modes |
| `crownStretchX` / `crownStretchZ` | `1` | Ellipse crown |
| `layers` | `[]` | Explicit discs override profile |
| `branches` | null | Branch system drives canopy when set |

`IrisTreeLayer`: `yOffset`, `radius`.

`IrisTreeLeafMode`: `TRIMMED`, `FILLED`, `DENSITY`, `NOISE`, `HOLLOW`, `GRADIENT`, `CLUMPED`, `TATTERED`, `SPARSE`.

### Branches (`IrisTreeBranches`)

Probability functions (`IrisTreeBranchProbability`): `CONSTANT`, `LINEAR`, `SIGMOID`, `TOP_HEAVY`, `GAUSSIAN`, `NOISE`, `BOTTOM_HEAVY`, `PERIODIC`, `BAND`, `INVERSE_GAUSSIAN`, `EXPONENTIAL_DECAY`.

| Field | Default | Role |
|-------|---------|------|
| `probabilityFunction` | `TOP_HEAVY` | Branch chance curve over normalized trunk height |
| `probabilityConstant` | `0.5` | `CONSTANT` chance |
| `probabilityBase` / `probabilityCrown` | `0` / `1` | `LINEAR` endpoints |
| `probabilitySteepness` / `probabilityMidpoint` | `10` / `0.7` | `SIGMOID` controls |
| `probabilityExponent` | `2` | `TOP_HEAVY` exponent |
| `probabilityMean` / `probabilityStd` | `0.7` / `0.15` | Gaussian center and deviation |
| `probabilityScale` | `1` | Noise scale |
| `probabilityPeriods` | `5` | Periodic whorl count |
| `lengthFunction` | `LINEAR` | Branch-length curve over trunk height |
| `lengthBase` / `lengthCrown` | `1` / `4` | Linear endpoints |
| `lengthConstant` / `lengthMax` | `3` / `4` | Constant value and nonlinear maximum |
| `lengthSteepness` | `5` | Sigmoid length steepness |
| `azimuthMode` / `azimuth` | `RANDOM` / `0` | Direction selection and fixed direction for `CONSTANT` |
| `elevation` / `sag` | `0` / `0` | Initial elevation and catenary droop |
| `branchDepth` | `1` | Recursive branch levels, 0–6 |
| `leafStartUp` | `false` | Clamp primary branches so they do not droop below horizontal |
| `clusterRadius` / `clusterMode` / `clusterDensity` | `2` / `TRIMMED` / `0.85` | Primary tip leaf cluster |
| `subBranches` | `null` | Optional `IrisTreeSubBranches` configuration |

`subBranches` fields: `count` (`1`), `pitchDelta` (`0`), `yawDelta` (`45`), `lengthScale` (`0.5`), `sag` (`0`), `clusterRadius` (`1`), `clusterMode` (`TRIMMED`), and `clusterDensity` (`0.85`).

### Secondary leaves and accents

| Field | Notes |
|-------|-------|
| `secondaryLeaves` | Single accent block |
| `weightedSecondaryLeaves` | Weighted list (`block`, `weight`) |
| `secondaryLeavesPalette` | Wins over both |
| `secondaryLeafFraction` | Fraction replaced |
| `decorators` | `IrisTreeDecorator[]` post accents |

### Tree decorator (`IrisTreeDecorator`)

| Field | Default | Notes |
|-------|---------|-------|
| `target` | `BRANCH_TIP` | Placement locus |
| `block` / `palette` | required block | Palette wins |
| `chance` | `0.5` | Per candidate |
| `length` | `1` | Max hang for `CANOPY_HANG` |
| `axisAware` | `false` | Orient facing away from trunk |

Targets: `BRANCH_TIP`, `TRUNK_SURFACE`, `CANOPY_TOP`, `CANOPY_BOTTOM`, `TRUNK_BASE`, `LEAF_SURFACE`, `CANOPY_HANG`, `BRANCH_SURFACE`, `TRUNK_TOP`, `GROUND_SCATTER`.

### Minimal tree example

```json
{
  "name": "oak-plains",
  "chance": 0.35,
  "density": 2,
  "variants": 8,
  "seed": 9001,
  "trunk": "minecraft:oak_log",
  "leaves": "minecraft:oak_leaves",
  "profile": "OAK",
  "heightMin": 7,
  "heightMax": 11,
  "plausible": true,
  "roots": true,
  "rootStyle": "BUTTRESS",
  "canopy": {
    "mode": "TRIMMED",
    "startAngle": 88,
    "squish": 0.9
  }
}
```

## Sapling overrides (`IrisTree` + `IrisTreeSettings`) — not procedural trees

`IrisTree` on **object placements** (`IrisObjectPlacement.trees`) maps grown sapling types/sizes to that object:

| Field | Notes |
|-------|-------|
| `treeTypes` | Bukkit `TreeType` names |
| `anyTree` | Match any type |
| `sizes` | `IrisTreeSize` width×depth sapling footprints |
| `anySize` | Match any size |

Dimension `treeSettings`: `enabled`, `mode` (`FIRST` biome→region→dimension, or `ALL` pool). This is growth replacement, not worldgen scatter.

## Fungi (`IrisFungus`)

| Field | Default | Notes |
|-------|---------|-------|
| `stem` / `stemPalette` | mushroom_stem | Palette wins |
| `cap` / `capPalette` | red_mushroom_block | Palette wins |
| `stemHeightMin` / `Max` | 5 / 9 | Stem height |
| `stemWidth` | `1` | 1–3 column |
| `stemCurve` / `stemLeanAzimuth` / `stemWaveAmplitude` / `stemWavePeriods` | lean/wave | Organic stem |
| `capShape` | `DOME` | `DOME`, `FLAT`, `FUNNEL`, `CONICAL`, `FLAT_WIDE` |
| `capRadiusMin` / `Max` | 3 / 5 | Cap size |
| `capThickness` / `capSquish` / `capDroop` / `capOverhang` | shape controls | |
| `gillBlock` / `gillPalette` / `gillChance` | underside | |
| `spotBlock` / `spotPalette` / `spotChance` | top speckles | |
| `shelf` / `shelfRadius` | false / 3 | Sideways polypore mode |

## Coral (`IrisCoral`)

Defaults: `underwater: true`, `waterlogged: true`, `mode: CENTER_HEIGHT`.

| Field | Notes |
|-------|-------|
| `form` | `BRANCHING`, `FAN`, `BRAIN`, `PILLAR`, `TENDRIL` |
| `block` / `blockPalette` | Structure body |
| `tipBlock` / `tipPalette` / `tipChance` | Fans/pickles |
| `heightMin` / `heightMax` | Overall height |
| `spread` / `sway` | Horizontal reach / wobble |
| Branching-only | `branchCount`, `branchLength`, `branchElevation`, `branchAzimuth` (`GOLDEN_ANGLE`/`EVEN`/`RANDOM`), `subBranches`, `subBranchCount`, `subBranchScale`, `tipClusterRadius` |
| Brain-only | `brainRadius`, `brainRoughness` |
| Pillar-only | `pillarRadius` |
| Fan-only | `fanWidth` |
| Tendril-only | `tendrilCount` |

## Crystals (`IrisCrystal`)

Defaults: `carvingSupport: CARVING_ONLY` (cave-first).

| Field | Notes |
|-------|-------|
| `growthSurface` | `FLOOR`, `CEILING`, `WALL` (orients bake only) |
| `block` / `blockPalette` | Shard body |
| `tipBlock` / `tipPalette` / `tipChance` | Tip accents |
| `glow` / `glowBlock` | Auto glow tips if no tip block |
| `baseBlock` / `basePalette` / `baseRadius` / `baseNoise` | Budding base blob |
| `shardCountMin` / `Max` | Shard count |
| `shardLengthMin` / `Max` | Shard length |
| `shardBaseRadius` / `shardTaper` | Taper geometry |
| `spreadAngle` | Cone half-angle degrees |
| `distribution` | `RANDOM` or `GOLDEN_ANGLE` |
| `jitter` | Angular noise |

## Formations (`IrisFormation`)

Natural landmarks. Extra field `surfaceSupportBuffer` (default 3) for foundation solidity.

| Field | Notes |
|-------|-------|
| `form` | `SPIRE`, `HOODOO`, `ARCH`, `SEA_STACK`, `BOULDER`, `BASALT_COLUMN` |
| `block` / `blockPalette` | Body |
| `capBlock` / `capPalette` | Caprock |
| `strataPalette` / `strataThickness` | Horizontal bands |
| `heightMin` / `heightMax` | Height |
| `baseWidthMin` / `baseWidthMax` / `topWidth` | Radii |
| `profile` | `CONSTANT`, `LINEAR`, `TAPER`, `PARABOLIC`, `BULGE` |
| `profileWaist` / `profileWaistFloor` | Parabolic waist |
| `lean` / `leanAzimuth` | Tilt |
| `roughness` / `jitter` | Surface erosion |
| Hoodoo | `hoodooCapRadius`, `hoodooCapHeight` |
| Arch | `archSpan`, `archThickness` |
| Basalt | `basaltColumns`, `basaltColumnRadius`, `basaltHeightVariance` |

## Ruins (`IrisRuin`)

| Field | Default | Notes |
|-------|---------|-------|
| `form` | `PILLAR` | `PILLAR`, `WALL`, `ARCH`, `FLOOR_SLAB`, `RUBBLE` |
| `block` / `blockPalette` | cobblestone | Structure body |
| `heightMin` / `Max` | 4 / 9 | Height band |
| `widthMin` / `Max` | 1 / 3 | Footprint X |
| `lengthMin` / `Max` | 3 / 7 | Footprint Z |
| `weatheredBlock` / `weatheringPalette` | mossy_cobblestone | Weather remap |
| `mossiness` / `weatheringScale` | 0.45 / 1 | Weather intensity/scale |
| `erosion` / `erosionScale` | 0.25 / 1.5 | Missing-block noise |
| `buriedFraction` | 0.2 | Fraction sunk below surface |
| `accents` | `[]` | `IrisRuinDecorator` list |

### Ruin decorator (`IrisRuinDecorator`)

| Field | Default | Notes |
|-------|---------|-------|
| `target` | `TOP` | `TOP`, `SURFACE`, `BASE_SCATTER` |
| `block` / `palette` | required | Palette wins |
| `chance` | `0.4` | Per candidate |
| `scatterRadius` | `2` | BASE_SCATTER extent |

## Extend to other procedural families

1. Choose system: trees for forests, fungi for mushroom biomes, coral for warm oceans, crystals for cave biomes, formations for deserts/coasts, ruins for sparse land.
2. Add one entry using the minimal tree example above or the smallest equivalent for that family. Use one material, low `chance`, low `density`, and a fixed seed.
3. Focus the target biome, validate, and generate enough new chunks to observe both placements and empty space.
4. Tune dimensions and silhouette before raising density. Keep `variants` at 4–12; variants are baked during first use/cache warmup.
5. Add palettes and decorators after shape is stable. Match `carvingSupport` to the environment; crystals and cave props use `CARVING_ONLY`.
6. For cave props, prefer stilt place modes and the cave profile's `defaultObjectPlaceMode`.
7. Remove biome focus and verify the family remains limited to its intended biome/region scope.

The tutorial passes when the same seed reproduces the family, placement density leaves the intended negative space, and no invalid variant is skipped in the log. Prefer `.iob` objects instead when exact hand-authored geometry matters more than variation.

## Practical notes

- Procedural content is separate from `objects` placements and from structure jigsaws.
- `plausible: true` on trees enables natural leaf decay; false forces persistent leaves.
- Formation `surfaceSupportBuffer` interacts with dimension `objectSurfaceSupportBuffer` (max of requirements).
- Do not confuse `IrisTree` (sapling→object) with `IrisProceduralTree` (worldgen bake).
- Empty variant bake (invalid sizes) skips that entry; check logs if nothing places.

# 17 - Trees, Fungi, Coral, Crystals, Formations, Ruins

Procedural objects are structures Iris builds from JSON parameters instead of loading from `.iob` files. Each entry bakes a small pool of deterministic variant objects at engine start, then scatters them exactly like an object placement. Six families exist — trees, fungi, coral, crystals, formations, ruins — and they all live under `proceduralObjects` on a biome or a region.

Related: `12 - Regions.md`, `13 - Biomes.md`, `15 - Caves & Carving.md`, `16 - Surfaces, Decorators & Deposits.md`, `18 - Structures Overview.md`, `19 - Objects.md`, `20 - Object Placement.md`.

## The mental model

Two things happen at completely different times.

**Bake.** The first time an entry is touched — normally during the engine's cache warm-up — Iris runs the family's generator `variants` times and produces that many in-memory `IrisObject` instances. The bake is a pure function of the entry's fields and its `seed`, so the same JSON always produces byte-identical variants on every platform and every restart. An entry that bakes nothing (impossible dimensions, an empty palette) is silently skipped at placement time, so a family that never appears is worth checking in the logs first.

**Place.** During mantle object generation, per chunk, for each entry in scope:

1. Roll `chance` once, with a small ±0.005 jitter, for the whole entry.
2. If it passes, make `density` attempts.
3. Each attempt picks a random baked variant, picks a random X/Z inside the chunk (or searches for a cave anchor, see below), and hands the variant to the ordinary object placer with the entry's `mode`, `rotation`, `clamp`, `translate`, `underwater`, and stilt or vacuum settings.

So `chance` is per chunk and `density` is per chunk-that-passed. `chance: 0.5, density: 4` gives you four objects in half the chunks and none in the rest — clumpier than `chance: 1, density: 2`, which gives two everywhere.

Placement runs in the same mantle stage as `.iob` object placement, before the terrain blocks exist, which is why procedural objects can be anchored to carved cave space and why they respect the same surface-support rules as regular objects (`20 - Object Placement.md`).

**Scope.** Three lists are read per chunk, all resolved at the chunk centre: the surface biome's `proceduralObjects`, the region's, and the cave biome's (only when it differs from the surface biome). Everything in all three is evaluated; they add rather than override.

**Cost.** The mantle object component's radius grows to cover the largest baked variant across the whole pack. One 60-block formation therefore widens the generation footprint for every chunk in the world, not just the biome that uses it. Keep large shapes rare and large *entries* rarer.

## Walkthrough: procedural trees in a biome

Use a validating `OVERWORLD` pack with `useMantle` and `decorate` on. Save this as `biomes/tutorial/tree-test.json`, list `tutorial/tree-test` as a region land biome, and temporarily set the dimension `focus` to the same key.

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

1. Reuse `generators/flat.json` from `26 - Example - Minimal Dimension.md`, validate the pack, and open Studio on seed `1337`.
2. Fly out and generate fresh chunks. Success is two oaks per chunk, drawn from four distinct silhouettes, rooted on the grass, with leaves carrying normal decay distances.
3. If nothing appears: confirm the biome is focused and the chunks are new; confirm `useMantle` is true; check the console for bake failures. Keep `chance: 1` until you have seen a tree.
4. Break a leaf block and watch the canopy. With `plausible: true`, leaves more than six blocks from wood are permanently persistent, and everything closer decays normally when you cut the trunk.
5. Tune height, profile, trunk shape, and canopy before touching `chance` and `density`. Reopen Studio and confirm the same seed reproduces the same four shapes, then drop `chance` to something forest-like and remove `focus`.

Variant heights are not random per variant. Iris spreads them evenly across `heightMin..heightMax` and adds up to ±30% of one step of jitter, so four variants over `7..11` give roughly 7, 8, 10, 11 rather than four coin flips. Raising `variants` therefore fills in the height range rather than just adding randomness.

## Container (`IrisProceduralObjects`)

Snippet key: `procedural-objects`. Valid on biomes and regions.

| Field | Type | Family |
|-------|------|--------|
| `trees` | `IrisProceduralTree[]` | Trunk plus canopy, optionally branches, roots and forks |
| `fungi` | `IrisFungus[]` | Stem plus cap, or sideways shelf brackets |
| `coral` | `IrisCoral[]` | Waterlogged reef structures in five forms |
| `crystals` | `IrisCrystal[]` | Budding base with tapered shards; cave-first |
| `formations` | `IrisFormation[]` | Natural rock landmarks with strata and erosion |
| `ruins` | `IrisRuin[]` | Man-made shapes with weathering, erosion and burial |

```json
{
  "proceduralObjects": {
    "trees": [ { "...": "..." } ],
    "crystals": [ { "...": "..." } ]
  }
}
```

## Shared placement fields

Every family carries this same block of fields and converts them into an `IrisObjectPlacement` at placement time. Defaults differ per family, so the values below are noted where they diverge.

| Field | Default | What it does |
|-------|---------|--------------|
| `name` | family name | Used in logs and as the variant load key. Must be unique within a pack if you want to identify variants in debug output |
| `chance` | 0.4 (trees, fungi, coral), 0.2 (crystals), 0.05 (ruins), 0.02 (formations) | Probability the entry attempts anything at all in a given chunk |
| `density` | `1` | Attempts once the chance roll passes. Raising this clusters objects; raising `chance` spreads them |
| `variants` | 8 (trees), 6 (all others) | How many distinct shapes to bake, 1 to 64. Below about 4 the repetition is visible; above about 16 you are paying memory for variation nobody sees |
| `seed` | `1337` | Bake seed. Change it to get an entirely different set of shapes from identical settings |
| `mode` | `CENTER_HEIGHT`, except ruins `MIN_HEIGHT` | Terrain anchor mode. `MIN_HEIGHT` plants the lowest footprint corner, good for slabs and rubble on slopes; `CENTER_HEIGHT` averages, good for tall pillars |
| `rotation` | identity | Rotates placements so variants do not all face the same direction |
| `clamp` | unlimited | Min and max terrain height at which the entry may place |
| `carvingSupport` | `SURFACE_ONLY`, except crystals `CARVING_ONLY` | `SURFACE_ONLY` places on terrain, `CARVING_ONLY` searches carved cave space, `ANYWHERE` uses the surface path without the surface-only rejection |
| `underwater` | `false`, except coral `true` | Anchors on terrain height ignoring the water surface, so the object grows from the seafloor instead of the waterline |
| `translate` | zero | XYZ offset. A negative Y sinks the object into the ground |
| `stiltSettings` | unset | Configuration for `STILT`, `MIN_STILT`, `FAST_STILT`, `FAST_MIN_STILT`, `CENTER_STILT`, `ERODE_STILT`, `ORGANIC_STILT` |
| `vacuumSettings` | unset | Configuration for `VACUUM`, `VACUUM_HIGH`, `VACUUM_FAST`, `VACUUM_ORGANIC`, `VACUUM_WAVY` |
| `surfaceSupportBuffer` | `3` (formations only) | Solid ground required around the footprint. Iris uses the larger of this and the dimension `objectSurfaceSupportBuffer` |

Variant load keys are `procedural/tree/<name>#<i>` for trees and `procedural/<name>#<i>` for every other family.

Only `CARVING_ONLY` entries take the cave path. Those search the chunk for an anchor using the active cave profile's `defaultObjectAnchor`, `anchorScanStep`, `anchorSearchAttempts`, and `objectMinDepthBelowSurface`; an entry that finds no anchor is skipped for that attempt. The profile's `defaultObjectPlaceMode` overrides the entry's `mode`, but only when the entry left `mode` at the default `CENTER_HEIGHT`. See `15 - Caves & Carving.md`.

`plausible` is a **tree-only** field. Every other family reports `false`, which means their objects are placed with decay prevention active. This matters only for blocks that have leaf-style decay properties.

## Procedural trees (`IrisProceduralTree`)

Snippet key: `procedural-tree`. Built by the trunk builder (which returns one or more limbs), then a canopy per limb, then decorators, then roots, then leaf plausibility.

### Materials and size

| Field | Default | What it does |
|-------|---------|--------------|
| `trunk` | `minecraft:oak_log` | The log block. Ignored entirely when `trunkPalette` is set |
| `trunkPalette` | unset | Noise-driven palette for the trunk, for streaked or mixed-species wood |
| `leaves` | `minecraft:oak_leaves` | The leaf block. Ignored when `leavesPalette` is set |
| `leavesPalette` | unset | Noise-driven leaf palette. Only entries that are genuinely leaves receive decay distances |
| `plausible` | `true` | See below |
| `heightMin` / `heightMax` | `8` / `12` | Trunk height range spread across the variant pool. Heights below 2 are clamped up |
| `trunkWidth` | `1` | Base thickness. 1 is a single column, 2 is a 2x2, 3 a 3x3 |
| `profile` | `OAK` | Named silhouette driving default crown radii and layer placement |

`IrisTreeProfile`: `OAK`, `BIRCH`, `SPRUCE`, `JUNGLE`, `ACACIA`, `DARK_OAK`, `DARK_OAK_FLAT`, `DARK_OAK_FLAT_WIDE`, `CHERRY`, `PALM`, `WILLOW`, `COLUMNAR`, `BUSH`, `MEGA_SPRUCE`.

**What `plausible` actually does.** With `true`, Iris runs a breadth-first search from the trunk through the leaf volume. Leaves within 6 steps of wood get `persistent=false` and their real `distance`, so vanilla decay works exactly as it would on a grown tree. Leaves 7 or more steps away, or unreachable entirely, get `persistent=true` and `distance=7` so they never vanish. It also runs a support pass that prunes orphaned leaf clumps. With `false`, every leaf is forced `persistent=true, distance=1` — a raw dump that never decays and reads as built rather than grown.

### Trunk shaping

| Field | Default | What it does |
|-------|---------|--------------|
| `trunkShape` | `CONSTANT` | Function mapping normalized height to a trunk width multiplier. `TAPER`-like effects come from `LINEAR` with `shapeEnd` below 1 |
| `shapeStart` / `shapeEnd` | `1` / `1` | Width multipliers at base and top for `LINEAR` |
| `shapeSteepness` | `5` | Transition sharpness for `SIGMOID` |
| `shapeBase` | `2.718281828` | Logarithm base for `LOG` |
| `shapePeriod` / `shapeAmplitude` | `1` / `0.2` | Wobble frequency and depth for `SINE`, for lumpy or knotted trunks |
| `shapePeakOffset` / `shapeFloor` | `0.5` / `0.5` | For `PARABOLIC`: where the waist sits (0 base, 1 top) and how thin it gets |
| `leanAngle` | `0` | Degrees off vertical. Non-zero produces a leaning trunk; combine with rotation for wind-swept stands |
| `leanAzimuth` | `0` | Compass direction of the lean |
| `trunkCurve` | `LINEAR` | How lean accumulates over height. `SIGMOID` bends mostly in the middle; `CONSTANT` shears uniformly |
| `curveSteepness` | `8` | Sharpness of that bend for `SIGMOID` |
| `leanAzimuthMode` | `CONSTANT` | Lets the lean *direction* change with height, which is what turns a lean into a spiral or a wander |
| `azimuthStart` / `azimuthEnd` | `0` / `0` | Endpoints for `LINEAR` azimuth |
| `azimuthTurns` | `1` | Full rotations over the trunk for `SPIRAL` |
| `azimuthAmplitude` / `azimuthPeriod` / `azimuthOffset` | `90` / `1` / `0` | Sine azimuth wobble controls, for S-curved trunks |
| `azimuthScale` | `1` | Noise scale for `NOISE` azimuth |
| `azimuthWhorlCount` | `5` | Positions per ring for `WHORL` azimuth |
| `trunkForks` | `1` (1–6) | Splits the trunk into this many limbs above `forkHeight`. Each limb gets its own canopy |
| `forkHeight` | `0.5` | Normalized height of the split |
| `forkAngle` | `25` | Degrees each fork leans outward |
| `secondaryTrunk` | unset | Optional second wood block for a banded trunk. Ignored when `secondaryTrunkPalette` is set |
| `secondaryTrunkPalette` | unset | Noise palette for that band |
| `secondaryTrunkStart` / `secondaryTrunkEnd` | `0.5` / `1` | Normalized band bounds |
| `roots` | `true` | Builds a root system so the tree meets uneven ground instead of hovering |
| `rootStyle` | `BUTTRESS` | `TAPROOT` drives one thick root down, `BUTTRESS` flares several out at the base, `STILT` lifts the trunk on legs |
| `rootDepth` | `0` | Explicit reach in blocks; 0 scales automatically with tree height |
| `rootFlare` | `0` | Explicit flare radius; 0 scales automatically |

`IrisTreeFunction`: `CONSTANT`, `LINEAR`, `SIGMOID`, `LOG`, `SINE`, `PARABOLIC`, `EXPONENTIAL`, `SQRT`, `STEP`, `BELL`, `EASE_IN_OUT`.

`IrisTreeAzimuthMode`: `CONSTANT`, `LINEAR`, `SPIRAL`, `SINE`, `NOISE`, `RANDOM`, `GOLDEN_ANGLE`, `ALTERNATING`, `WHORL`, `ZIGZAG`.

### Canopy (`IrisTreeCanopy`)

Snippet key: `tree-canopy`. Stacked discs, sized by the profile unless you override them.

| Field | Default | What it does |
|-------|---------|--------------|
| `startAngle` | `90` | Elevation of each disc in degrees. Exactly 90 is a flat disc, below 90 domes downward toward a sphere, above 90 flares out into an umbrella |
| `squish` | `1` | Vertical scale of the crown volume. Below 1 flattens it |
| `mode` | `TRIMMED` | How each disc fills with leaves |
| `leafDensity` | `0.85` | Fill probability for the `DENSITY` and `NOISE` modes |
| `crownStretchX` / `crownStretchZ` | `1` / `1` | Elliptical crowns for wind-shaped or asymmetric trees |
| `layers` | `[]` | Explicit discs. Any entry here replaces the profile-driven layers entirely |
| `branches` | unset | When set, branches build most of the canopy and only the topmost profile disc is still placed |

`IrisTreeLayer` (snippet `tree-layer`) is `yOffset` (blocks above the trunk base, default `0`) and `radius` (default `2`).

`IrisTreeLeafMode`: `TRIMMED`, `FILLED`, `DENSITY`, `NOISE`, `HOLLOW`, `GRADIENT`, `CLUMPED`, `TATTERED`, `SPARSE`.

### Branches (`IrisTreeBranches`)

Snippet key: `tree-branches`. Adding a `branches` object switches the tree from a stack of leaf discs to real limbs with leaf balls at their tips — the difference between a vanilla oak and an old-growth silhouette.

| Field | Default | What it does |
|-------|---------|--------------|
| `probabilityFunction` | `TOP_HEAVY` | Where branches spawn along the trunk. `TOP_HEAVY` for a crown, `PERIODIC` for conifer whorls, `BAND` for a single tier |
| `probabilityConstant` | `0.5` | Chance for `CONSTANT` |
| `probabilityBase` / `probabilityCrown` | `0` / `1` | Endpoints for `LINEAR` |
| `probabilitySteepness` / `probabilityMidpoint` | `10` / `0.7` | `SIGMOID` sharpness and where the crown starts |
| `probabilityExponent` | `2` | `TOP_HEAVY` bias; higher pushes branches further up |
| `probabilityMean` / `probabilityStd` | `0.7` / `0.15` | `GAUSSIAN` centre and spread, for a single dense tier |
| `probabilityScale` | `1` | Noise scale for `NOISE` |
| `probabilityPeriods` | `5` | Number of whorl rings for `PERIODIC` |
| `lengthFunction` | `LINEAR` | How branch length varies with height. `LINEAR` with a large `lengthCrown` gives the classic wide top |
| `lengthBase` / `lengthCrown` | `1` / `4` | Endpoints for `LINEAR` |
| `lengthConstant` / `lengthMax` | `3` / `4` | Value for `CONSTANT`, and the ceiling for `SIGMOID`, `LOG` and `PARABOLIC` |
| `lengthSteepness` | `5` | `SIGMOID` length sharpness |
| `azimuthMode` | `RANDOM` | Compass distribution of branches. `GOLDEN_ANGLE` gives even spiral phyllotaxis, `WHORL` gives rings |
| `azimuth` | `0` | Fixed direction when `azimuthMode` is `CONSTANT` |
| `elevation` | `0` | Starting angle from horizontal. Positive points up, negative droops |
| `sag` | `0` | Catenary droop along the branch. Small values read as weight; large values give willow arcs |
| `branchDepth` | `1` (0–6) | Recursion levels. 2 and above produce fractal branching and a much larger block count |
| `leafStartUp` | `false` | Clamps primary branches so they never droop below horizontal |
| `clusterRadius` | `2` | Leaf ball radius at each branch tip |
| `clusterMode` | `TRIMMED` | Fill mode for that ball |
| `clusterDensity` | `0.85` | Fill probability when `clusterMode` is density- or noise-based |
| `subBranches` | unset | One extra level of sub-branches from each tip |

`IrisTreeBranchProbability`: `CONSTANT`, `LINEAR`, `SIGMOID`, `TOP_HEAVY`, `GAUSSIAN`, `NOISE`, `BOTTOM_HEAVY`, `PERIODIC`, `BAND`, `INVERSE_GAUSSIAN`, `EXPONENTIAL_DECAY`.

`IrisTreeSubBranches` (snippet `tree-sub-branches`): `count` (`1`), `pitchDelta` (`0`, positive bends up), `yawDelta` (`45`, horizontal fan spread), `lengthScale` (`0.5` of the parent), `sag` (`0`), `clusterRadius` (`1`), `clusterMode` (`TRIMMED`), `clusterDensity` (`0.85`).

### Accents

| Field | Default | What it does |
|-------|---------|--------------|
| `secondaryLeaves` | unset | A single accent block scattered through the canopy — blossoms, shroomlight, berries |
| `weightedSecondaryLeaves` | `[]` | Weighted list of accent blocks (`block` plus `weight`), overriding the single block |
| `secondaryLeavesPalette` | unset | Noise palette, overriding both of the above |
| `secondaryLeafFraction` | `0.35` | Share of leaves replaced by the accent. Values near 1 recolour the whole crown |
| `decorators` | `[]` | `IrisTreeDecorator` entries applied after the tree is built |

### Tree decorator (`IrisTreeDecorator`)

Snippet key: `tree-decorator`.

| Field | Default | What it does |
|-------|---------|--------------|
| `target` | `BRANCH_TIP` | Which set of positions is eligible |
| `block` | required | Block id to place. Ignored when `palette` is set |
| `palette` | unset | Noise palette, wins over `block` |
| `chance` | `0.5` | Per eligible position. Use low values for sparse fruit, 1 for full coverage such as snow on the crown |
| `length` | `1` | Maximum downward strand length for `CANOPY_HANG`; each column picks 1 to `length` |
| `axisAware` | `false` | Orients the block's facing away from the trunk, for fences, gates and banners mounted on wood |

Targets: `BRANCH_TIP`, `TRUNK_SURFACE`, `CANOPY_TOP`, `CANOPY_BOTTOM`, `TRUNK_BASE`, `LEAF_SURFACE`, `CANOPY_HANG`, `BRANCH_SURFACE`, `TRUNK_TOP`, `GROUND_SCATTER`.

Branch endpoints are only collected when at least one decorator exists, so an empty `decorators` list costs nothing.

### A complete tree

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

## Fungi (`IrisFungus`)

Snippet key: `fungus`. A stem column with a cap grown on top, or a sideways shelf bracket.

| Field | Default | What it does |
|-------|---------|--------------|
| `stem` / `stemPalette` | `minecraft:mushroom_stem` | Stem material; palette wins |
| `cap` / `capPalette` | `minecraft:red_mushroom_block` | Cap material; palette wins |
| `stemHeightMin` / `stemHeightMax` | `5` / `9` | Stem height range spread over the variant pool |
| `stemWidth` | `1` (1–3) | 1 is a single column, 3 a chunky trunk |
| `stemCurve` | `0` | Degrees of lean off vertical |
| `stemLeanAzimuth` | `0` | Direction of that lean |
| `stemWaveAmplitude` | `0.4` | Blocks of sideways wobble up the stem, so it is not a ruler |
| `stemWavePeriods` | `1` | Full sine wobbles over the stem height |
| `capShape` | `DOME` | `DOME`, `FLAT`, `FUNNEL`, `CONICAL`, `FLAT_WIDE` |
| `capRadiusMin` / `capRadiusMax` | `3` / `5` | Cap radius from centre to rim |
| `capThickness` | `1` (1–3) | Shell thickness. 1 is a thin skin, 3 a fleshy slab |
| `capSquish` | `0.4` | Vertical flatten, 0 full height and 1 a flat disc |
| `capDroop` | `20` | Degrees the rim curls toward the ground |
| `capOverhang` | `2` | Blocks the cap extends past the stem before the rim begins |
| `gillBlock` / `gillPalette` | unset | Underside layer — gills, or shroomlight for a glowing cap |
| `gillChance` | `0.85` | Share of underside blocks replaced when a gill block is set |
| `spotBlock` / `spotPalette` | unset | Speckles across the cap top |
| `spotChance` | `0.18` | Share of top blocks replaced, selected by value noise so spots cluster |
| `shelf` | `false` | Switches to a bracket polypore: a flat sideways fan off a very short or absent stem |
| `shelfRadius` | `3` | Fan radius in shelf mode |

## Coral (`IrisCoral`)

Snippet key: `coral`. Defaults to `underwater: true` and `waterlogged: true`, so it grows from the seafloor and stays alive.

| Field | Default | What it does |
|-------|---------|--------------|
| `waterlogged` | `true` | Forces every waterloggable block in the structure waterlogged. Set false for dead, dry coral on a beach |
| `form` | `BRANCHING` | `BRANCHING`, `FAN`, `BRAIN`, `PILLAR`, `TENDRIL`. Each runs a different generator |
| `block` / `blockPalette` | `minecraft:tube_coral_block` | Structural body; a palette mixes tube/brain/bubble/fire/horn tones across one reef |
| `tipBlock` / `tipPalette` | unset | Placed at branch tips and the top — fans, sea pickles |
| `tipChance` | `0.6` | Per eligible tip position |
| `heightMin` / `heightMax` | `4` / `8` | Overall height |
| `spread` | `3` | Horizontal reach. Arm length for `BRANCHING`, base footprint for the others |
| `sway` | `0.5` | Lateral wobble, 0 ruler-straight and 1 heavily wandering |
| `branchCount` | `4` (1–12) | `BRANCHING`: arms off the central stalk |
| `branchLength` | `3` | `BRANCHING`: arm length before the tip |
| `branchElevation` | `55` | `BRANCHING`: degrees up from horizontal. 90 is straight up |
| `branchAzimuth` | `GOLDEN_ANGLE` | `BRANCHING`: `GOLDEN_ANGLE`, `EVEN`, or `RANDOM` distribution around the stalk |
| `subBranches` | `true` | `BRANCHING`: split each arm once for a bushier reef |
| `subBranchCount` | `2` (1–5) | Sub-arms per arm |
| `subBranchScale` | `0.5` | Sub-arm length as a fraction of the parent |
| `tipClusterRadius` | `1` (0–4) | `BRANCHING` and `PILLAR`: tip cluster size |
| `brainRadius` | `3` (1–8) | `BRAIN`: blob radius |
| `brainRoughness` | `0.35` | `BRAIN`: surface wrinkling. 0 is a smooth dome |
| `pillarRadius` | `1` (1–12) | `PILLAR`: column radius |
| `fanWidth` | `3` (1–8) | `FAN`: half-width of the upright plane |
| `tendrilCount` | `4` (1–12) | `TENDRIL`: number of thin wavy stalks |

## Crystals (`IrisCrystal`)

Snippet key: `crystal`. A budding base blob with tapered shards radiating from it. Defaults to `carvingSupport: CARVING_ONLY` and `chance: 0.2`, so it needs carved cave space to place at all — see `15 - Caves & Carving.md` for the anchor settings that govern it.

| Field | Default | What it does |
|-------|---------|--------------|
| `growthSurface` | `FLOOR` | `FLOOR` points shards up, `CEILING` down, `WALL` outward. This orients the baked geometry only; use the cave anchor mode to actually land it on a ceiling |
| `block` / `blockPalette` | `minecraft:amethyst_block` | Shard body. A palette mixes amethyst, calcite and tinted glass into one prismatic cluster |
| `tipBlock` / `tipPalette` | unset | Different block at the very point of each shard |
| `tipChance` | `0.6` | Per shard |
| `glow` | `false` | With no tip block set, sprinkles `glowBlock` among the tips instead |
| `glowBlock` | `minecraft:glowstone` | The light source used by `glow` |
| `baseBlock` / `basePalette` | `minecraft:budding_amethyst` | The blob the shards grow from |
| `baseRadius` | `1.6` | Blob radius. 0 makes shards spring from a single point |
| `baseNoise` | `0.35` | Surface lumpiness of the blob, so it is not a clean sphere |
| `shardCountMin` / `shardCountMax` | `5` / `11` | Shards per cluster |
| `shardLengthMin` / `shardLengthMax` | `3` / `8` | Shard length from base to tip |
| `shardBaseRadius` | `1.4` | Thickness at the shard's base end |
| `shardTaper` | `0.85` | How aggressively it narrows. 0 is a near-constant column, 1 a sharp spike. Every shard ends in a single block regardless |
| `spreadAngle` | `45` | Half-angle of the cone the shards fan within. 0 makes them all parallel; large values give a starburst |
| `distribution` | `GOLDEN_ANGLE` | `GOLDEN_ANGLE` for an evenly spaced rosette, `RANDOM` for a chaotic clump |
| `jitter` | `0.25` | Angular randomness on top of the distribution, so the cluster never looks mechanical |

## Formations (`IrisFormation`)

Snippet key: `formation`. Natural rock landmarks. Default `chance: 0.02` — these are meant to be rare, and they are the family most likely to widen the pack's mantle radius.

| Field | Default | What it does |
|-------|---------|--------------|
| `form` | `SPIRE` | `SPIRE`, `HOODOO`, `ARCH`, `SEA_STACK`, `BOULDER`, `BASALT_COLUMN` |
| `block` / `blockPalette` | `minecraft:stone` | Main rock body |
| `capBlock` / `capPalette` | unset | Caprock on the crown, and the overhanging cap for `HOODOO`. Unset means the main rock everywhere |
| `strataPalette` | unset | Horizontal colour bands. Every `strataThickness` blocks the palette advances, which is what produces the badlands look |
| `strataThickness` | `3` (1–32) | Band thickness |
| `heightMin` / `heightMax` | `14` / `26` | Total height |
| `baseWidthMin` / `baseWidthMax` | `3` / `6` | Base radius |
| `topWidth` | `0` | Radius at the very top before the profile applies. 0 tapers to a point |
| `profile` | `TAPER` | `CONSTANT`, `LINEAR`, `TAPER`, `PARABOLIC`, `BULGE` — how the radius changes with height |
| `profileWaist` | `0.55` | Normalized height of the pinch for `PARABOLIC`, used by hoodoos |
| `profileWaistFloor` | `0.35` | Minimum radius fraction at that waist. Lower pinches tighter |
| `lean` | `0` | Degrees off vertical; the whole body is sheared |
| `leanAzimuth` | `0` | Lean direction |
| `roughness` | `0.3` | 3D noise perturbation of the radius, 0 clean and 1 heavily eroded. This is the main "does it look like rock" control |
| `jitter` | `0.15` | Per-block surface noise that adds and removes isolated edge blocks |
| `surfaceSupportBuffer` | `3` (0–16) | Solid ground required beyond the lowest footprint blocks. Iris takes the larger of this and the dimension `objectSurfaceSupportBuffer` |
| `hoodooCapRadius` | `3` | `HOODOO`: extra cap radius for the mushroom overhang. 0 disables it |
| `hoodooCapHeight` | `3` (1–6) | `HOODOO`: cap slab thickness |
| `archSpan` | `10` | `ARCH`: gap width between the legs |
| `archThickness` | `3` | `ARCH`: leg and span thickness |
| `basaltColumns` | `5` (2–12) | `BASALT_COLUMN`: columns per cluster |
| `basaltColumnRadius` | `1` | `BASALT_COLUMN`: radius of each column |
| `basaltHeightVariance` | `0.45` | `BASALT_COLUMN`: how much column heights differ, 0 all equal and 1 highly varied |

## Ruins (`IrisRuin`)

Snippet key: `ruin`. Man-made shapes that are weathered, eroded, and partly buried. Default `chance: 0.05` and `mode: MIN_HEIGHT`.

| Field | Default | What it does |
|-------|---------|--------------|
| `form` | `PILLAR` | `PILLAR` broken column, `WALL` gapped segment, `ARCH` two legs and a span, `FLOOR_SLAB` foundation patch, `RUBBLE` low scattered pile |
| `block` / `blockPalette` | `minecraft:cobblestone` | Bulk material before weathering |
| `heightMin` / `heightMax` | `4` / `9` | Structure height, or slab thickness and mound height for the flat forms |
| `widthMin` / `widthMax` | `1` / `3` | Footprint along X |
| `lengthMin` / `lengthMax` | `3` / `7` | Footprint along Z |
| `weatheredBlock` | `minecraft:mossy_cobblestone` | The weathered swap, used when no palette is set |
| `weatheringPalette` | unset | Palette of weathered variants, overriding the single block |
| `mossiness` | `0.45` | Share of the structure that weathers. The mask is noise-driven and biased toward lower rows, so moss climbs from the ground |
| `weatheringScale` | `1.0` (0–8) | Weathering noise scale. Higher gives busy speckles, lower gives broad mossy zones |
| `erosion` | `0.25` | How crumbled it is. Blocks below this noise threshold are deleted. The bottom row and core legs are never eroded, so the shape does not collapse into confetti |
| `erosionScale` | `1.5` (0–8) | Erosion noise scale. Higher knocks out small holes, lower carves large missing chunks |
| `buriedFraction` | `0.2` | Fraction of the height that sits below the surface, so the ruin reads as settled |
| `accents` | `[]` | `IrisRuinDecorator` entries applied after erosion |

### Ruin decorator (`IrisRuinDecorator`)

Snippet key: `ruin-decorator`.

| Field | Default | What it does |
|-------|---------|--------------|
| `target` | `TOP` | `TOP` sits on the highest block of each column, `SURFACE` clings to air-facing vertical faces, `BASE_SCATTER` rings the ground around the base |
| `block` | required | Block id. Ignored when `palette` is set |
| `palette` | unset | Noise palette, wins over `block` |
| `chance` | `0.4` | Per candidate position |
| `scatterRadius` | `2` | For `BASE_SCATTER`, how far beyond the footprint the ring extends |

## Sapling overrides (`IrisTree`) — a different system

`IrisTree` lives on **object placements** (`IrisObjectPlacement.trees`), not under `proceduralObjects`. It maps a grown sapling to that placement's objects. This is a gameplay growth replacement, not worldgen scatter, and it never touches procedural trees.

| Field | What it does |
|-------|--------------|
| `treeTypes` | Bukkit `TreeType` names this placement replaces, matched case-insensitively |
| `sizes` | `IrisTreeSize` entries (`width` by `depth`) describing the sapling footprints it applies to. Width and depth are matched either way round, so a 1x2 entry also matches 2x1 |
| `anyTree` | Declared but never read at runtime. Matching is by `treeTypes` only |
| `anySize` | Declared but never read at runtime. Matching is by `sizes` only |

Dimension `treeSettings` gates the whole feature:

| Field | Default | What it does |
|-------|---------|--------------|
| `enabled` | `false` | Off by default. Nothing replaces grown trees until this is true |
| `mode` | `FIRST` | `FIRST` uses biome matches and only falls back to region matches when the biome has none. `ALL` pools biome and region matches and picks randomly from the combined list. Dimension-level object placements are not consulted in either mode |

## Extending to the other families

1. Pick the family that matches the shape: trees for forests, fungi for mushroom biomes, coral for warm oceans, crystals for cave biomes, formations for deserts and coastlines, ruins for sparse land.
2. Add one entry with a single material, `chance: 1`, `density: 1`, `variants: 4`, and a fixed `seed`. Focus the biome and generate.
3. Get the silhouette right before touching frequency. Dimensions, profile, and roughness all change what the thing *is*; chance and density only change how often you meet it.
4. Add palettes, accents, and decorators once the shape holds up from several angles.
5. Match `carvingSupport` to the environment. Cave props also want a stilt place mode, either on the entry or through the cave profile's `defaultObjectPlaceMode`.
6. Drop `chance` to production values, remove the dimension `focus`, and verify the family stays inside the biomes and regions that declare it.

The pass condition: the same seed reproduces the same shapes across a Studio restart, the placement leaves believable negative space, and no variant is skipped in the log. When exact hand-authored geometry matters more than variation, use an `.iob` object instead (`19 - Objects.md`).

## Practical notes

- Procedural objects are independent of `objects` placements, decorators, and jigsaw structures. Nothing is shared except the placement machinery.
- `plausible` exists only on trees. The other five families always place with decay prevention active.
- `IrisTree` (sapling to object) and `IrisProceduralTree` (worldgen bake) are unrelated types with similar names.
- An entry whose bake produces no blocks is skipped silently at placement time. If a family never appears and `chance: 1` did not help, look for bake warnings in the console.
- Every baked variant enlarges the mantle object radius for the entire engine. A single very large formation costs generation time everywhere, not just where it spawns.

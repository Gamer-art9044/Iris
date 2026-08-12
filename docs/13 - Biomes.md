# 13 - Biomes

A biome is where terrain height, surface materials, decoration and placement all come together. Files live at `biomes/<loadKey>.json`. Regions list root biomes; roots can nest children, swap themselves out under carvings, and publish custom datapack biomes for colours, tags and mob spawns.

Related: see `12 - Regions.md`, `14 - Generators & Noise.md`, `15 - Caves & Carving.md`, `16 - Surfaces, Decorators & Deposits.md`, `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`, `19 - Objects.md`, `20 - Object Placement.md`, `23 - Loot, Entities, Spawners, Markers.md`.

## The mental model

A biome file answers two separate questions, and they fail in different ways.

**Where does this biome appear?** Not from anything in the file. The region lists it, the role (land, sea, shore, cave) comes from which list it was in, and a noise value picks between the siblings in that list weighted by `1 / rarity`. See `12 - Regions.md`.

**What does the world look like where it appears?** That is the whole rest of the file, and it runs top to bottom per column:

```
column (x, z)
  |
  generators[]  ->  terrain height Y   (each link maps 0..1 noise into min..max, relative to fluidHeight)
  |
  layers[]      ->  block stack downward from Y
  |
  remaining depth below the layers  ->  dimension rock palette
  |
  if Y < fluidHeight: seaLayers[] fill downward from the water surface, remainder is fluid
  |
  decorators, objects, structures, procedural content go on top
  |
  derivative / customDerivitives  ->  what Minecraft calls this biome (colours, mobs, structure eligibility)
```

Three of those steps regularly surprise people:

- **Height is relative to `fluidHeight`, not to Y=0.** `min: 4, max: 10` means "4 to 10 blocks above the water line". Negative values put the surface under water, which is how ocean floors and river beds are made.
- **A biome has no `type` field.** `carving/drip` is a cave biome only because a region put it in `caveBiomes`. The same file placed in `landBiomes` would generate as land.
- **The role can be corrected after height is known.** If a land biome's height lands below the water line, Iris swaps in a sea biome from the same region; if it lands in the shore band, a shore biome. So a "land" biome with a negative generator will simply never render as itself.

### Children

`children` lets one biome dissolve into variants without adding entries to the region. At each column, Iris runs a second noise pass over the parent's children **plus the parent itself**, then repeats on whatever it picked, up to four times in total. The chain usually ends early because re-picking the parent stops it.

Child weighting is not the same as list rarity. Each candidate gets `(highestRarityInTheGroup + 1) - rarity` slots:

| Parent rarity | Child rarity | Parent slots | Child slots | Result |
|---|---|---|---|---|
| 1 | 1 | 1 | 1 | Half and half |
| 1 | 4 | 4 | 1 | Child covers a fifth of the parent |
| 1 | 3 and 3 (two children) | 3 | 1 each | Parent 60%, each child 20% |

Because the weights are relative to the highest rarity present, setting every candidate to the same number (all `1`, all `9`) gives a uniform split. Only differences matter.

`childShrinkFactor` scales the child selection noise coordinates, so higher values make each child patch smaller inside the parent. `childStyle` decides the patch shape.

## Walkthrough: add a biome to a region and see it

Prerequisites: a validating dimension, a region it lists, and `generators/flat.json` (see `26 - Example - Minimal Dimension.md`).

1. Save this as `biomes/tutorial/meadow.json`:

```json
{
  "name": "Tutorial Meadow",
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains",
  "generators": [{ "generator": "flat", "min": 96, "max": 96 }],
  "layers": [
    { "palette": [{ "block": "minecraft:grass_block" }] },
    { "minHeight": 2, "maxHeight": 4, "palette": [{ "block": "minecraft:dirt" }] }
  ]
}
```

`min` equal to `max` gives a dead-flat surface at 96 blocks above `fluidHeight`, which makes any height problem obvious later.

2. Add `"tutorial/meadow"` to that region's `landBiomes`.

3. Set `"focus": "tutorial/meadow"` on the dimension, validate, and open Studio on seed `1337`.

4. Fly into new chunks and run `/iris what biome`.

Success: the load key is `tutorial/meadow`, the surface is grass over 2-4 dirt over stone, the terrain is perfectly flat, and there are no unresolved generator warnings.

If nothing generates, compare the region entry, the file path and the `focus` string character for character. If the biome resolves but sits on void, the generator link is wrong — check `generators/flat.json` exists and the key matches.

5. Remove `focus`, reopen Studio, and travel until the biome turns up naturally. Only then add decorators, objects and children.

While `focus` is set, the focused biome is forced into the land role for the whole world, so sea and shore correction never runs. A sea biome under `focus` will render as if it were land.

## Walkthrough: make it hilly, then flatten part of it

The generator supplies the shape; the biome supplies the height band. To get hills, point at a generator with real relief and open the band:

```json
{ "generators": [{ "generator": "plain", "min": 4, "max": 40 }] }
```

Observable result: terrain now rolls between 4 and 40 blocks above the water line, with the shape coming from `generators/plain.json`.

To make one biome a plateau while its neighbours stay hilly, set `min` equal to `max` on that biome only:

```json
{ "generators": [{ "generator": "plain", "min": 22, "max": 22 }] }
```

Observable result: a flat table at 22, blending into its neighbours across the generator's interpolation range. The transition width is the generator's `interpolator.horizontalScale`, not anything on the biome.

To stack a rare feature on top of a base shape, use two links with different generators:

```json
{
  "generators": [
    { "generator": "smooth-dunes", "max": 12, "min": 5 },
    { "generator": "rare-hills", "max": 40, "min": 0 }
  ]
}
```

Observable result: rolling dunes 5-12 above water, with occasional hills adding up to another 40. The bands add, so the biome's full range is 5 to 52. How the two shapes combine depends on their generators' interpolators — see `14 - Generators & Noise.md`.

## Walkthrough: turn it into an ocean floor

Same biome, negative band, added to `seaBiomes` instead of `landBiomes`:

```json
{
  "name": "Temperate Ocean",
  "derivative": "minecraft:lukewarm_ocean",
  "vanillaDerivative": "minecraft:ocean",
  "generators": [{ "min": -32, "max": -10, "generator": "mountain" }],
  "layers": [{ "minHeight": 3, "maxHeight": 5, "palette": [{ "block": "minecraft:sand" }] }]
}
```

Observable result: the surface sits 10 to 32 blocks below the water line and the column above it fills with the dimension's fluid palette. `derivative` gives the water its warm colour; `vanillaDerivative` being an exact ocean key is what keeps ocean monuments and shipwrecks eligible here (see "Structure eligibility" below).

## Walkthrough: add a child variant

```json
{
  "name": "Oak Forest",
  "derivative": "minecraft:forest",
  "vanillaDerivative": "minecraft:forest",
  "children": ["temperate/oak-forest-extended"],
  "childShrinkFactor": 1.5,
  "childStyle": { "style": "CELLULAR_IRIS_DOUBLE" }
}
```

Create `biomes/temperate/oak-forest-extended.json` as an ordinary biome file and do **not** add it to any region list. Observable result: patches of the child appear inside the parent's footprint, sized by `childShrinkFactor` and shaped by `childStyle`, at roughly half the parent's area when both rarities are `1`.

Raise the child's `rarity` to shrink its share. Raise `childShrinkFactor` to break it into smaller patches without changing its share.

## Load key

| Rule | Detail |
|------|--------|
| Folder | `biomes/` |
| Key | Path relative to `biomes/` with `.json` stripped |
| Examples | `starter` -> `biomes/starter.json`; `temperate/plains` -> `biomes/temperate/plains.json` |

## Field reference (`IrisBiome`)

### Identity

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `name` | string | `"Subterranean Land"` | Display name in tooling and `/iris what biome`. Required, minimum 2 characters. It is mixed into the biome's own noise seed, so renaming shifts scatter and custom-biome selection patterns. |
| `rarity` | int 1-512 | `1` | Divides this biome's share of its region list: `4` gives a quarter the area of a `1`. Also used, on a different scale, when this biome competes with its own children (see "Children"). |
| `color` | string | `null` | Hex colour for the studio map. Set it when debugging biome distribution visually. |

### Minecraft derivatives

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `derivative` | biome key | `"minecraft:the_void"` | The Minecraft biome this one presents as: grass and water tint, ambient sound, mob spawning, temperature effects. Required — leaving it at the default gives a void-tinted world. Bare names are namespaced automatically, so `plains` becomes `minecraft:plains`. |
| `vanillaDerivative` | biome key | `null` (falls back to `derivative`) | The biome used when Minecraft asks "may this structure generate here". Set it when you want a decorative `derivative` (`lukewarm_ocean`) but a structure-standard one (`ocean`). |
| `biomeScatter` | string[] | empty | Alternative derivatives mixed across the biome for the underground portion of the column. One entry is used as-is; several are picked per position by `biomeStyle` noise. Use it to break up flat colour. |
| `biomeSkyScatter` | string[] | empty | Alternative derivatives for the surface and above. When this list is non-empty it takes over the visible biome for the column; when it is empty the column falls back to `biomeScatter`, then to `derivative`. |
| `biomeStyle` | `IrisGeneratorStyle` | `SIMPLEX` | The noise that disperses the scatter lists and picks between multiple `customDerivitives`. Change its `zoom` to make the colour patches larger or smaller. |

Where the split between "underground" and "surface" applies depends on the generation path. On platforms where Iris supplies a 3D biome source to native worldgen, positions below the terrain surface resolve through the cave biome and `biomeScatter`, and positions above resolve through `biomeSkyScatter`. On the path where Iris writes biomes into the chunk itself, one biome is written for the whole column using the sky resolution. Either way, setting only `biomeSkyScatter` changes what players see; setting only `biomeScatter` may not.

### Structure eligibility

Native and datapack structures are filtered by the biome Minecraft sees, so Iris enforces the generated role before handing the key over:

| Situation | Key handed to structure selection |
|---|---|
| Land or cave role | `vanillaDerivative`, else `derivative` |
| Sea role, key contains `ocean` or ends in `river` | unchanged |
| Sea role, any other `minecraft:` key | `minecraft:the_void`, so no native structure is eligible |
| Shore role, key ends in `beach` or `shore` | unchanged |
| Shore role, any other `minecraft:` key | `minecraft:beach` |
| Non-`minecraft:` namespace (mod biomes) | unchanged, always authoritative |

That is why a sea biome with `vanillaDerivative: "minecraft:plains"` gets no ocean structures at all. See `22 - Native Structures & Datapacks.md`.

### Children and carving

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `children` | string[] | empty | Biome keys that portions of this biome morph into. Cycles are allowed; a column resolves at most four child hops. Do not also list these in a region. |
| `childShrinkFactor` | double | `1.5` | Scales the child selection noise. Higher means smaller child patches inside the parent. Useful range is roughly 1 to 3. |
| `childStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Shape of the child patches. Cellular styles give distinct blobs; simplex gives soft gradients. |
| `carvingBiome` | string | `""` | Biome key used instead of this one under a carving. Reachability indexes follow it, so the referenced biome is loaded and registered even if no region lists it. |
| `caveMinDepthBelowSurface` | int 0-256 | `0` | When this biome is used as a cave biome, columns less than this many blocks below the terrain surface fall back to the surface biome instead. Raise it to keep a deep-cave palette out of shallow openings. |

### Height (`generators`)

Type: `IrisBiomeGeneratorLink`, available as the `generator-layer` snippet.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `generator` | string | `"default"` | Load key under `generators/`. A missing or blank key resolves to `default`; an unresolvable key falls back to an empty generator, which contributes zero height. |
| `min` | int -2032..2032 | `0` | Bottom of this link's height band, in blocks relative to `fluidHeight`. Required. |
| `max` | int -2032..2032 | `0` | Top of the band. Required. |

Each link clamps its generator's output to 0..1 and maps it into `min`..`max`. Multiple links add, so a biome's total band is the sum of its links' bands. The final column height is clamped to the dimension's usable range.

The generator's raw 0..1 shape and the band are combined through the generator's interpolator, which is also what blends this biome's heights into its neighbours'. Generators sharing an interpolator are averaged together; generators with distinct interpolators add as independent layers. That behaviour and its tuning live in `14 - Generators & Noise.md`.

### Layers (block palettes)

Type: `IrisBiomePaletteLayer`, available as the `biome-palette` snippet.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `palette` | `IrisBlockData[]` | one grass block | The blocks this layer may use. Required, at least one entry. With several entries the choice is made per block by `style`. |
| `minHeight` | int 0-2032 | `1` | Thinnest this layer can be at a column. `0` lets the layer vanish in places. |
| `maxHeight` | int 1-2032 | `1` | Thickest it can be. Iris picks a per-column thickness between min and max using noise. |
| `style` | `IrisGeneratorStyle` | `STATIC` | How multi-block palettes are distributed. `STATIC` is white noise, which reads as speckle; a coherent style like `IRIS` gives patches. |
| `zoom` | double >= 0.0001 | `5` | Horizontal scale for both the thickness noise and the palette noise. Larger makes broader, smoother patches. |
| `slopeCondition` | `IrisSlopeClip` | min `0`, max `10` | When narrowed, this layer is skipped entirely at columns whose slope falls outside the range. Use it for snow caps that avoid cliffs, or gravel that only appears on steep ground. The default range is inert. |

`IrisBlockData` entries:

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `block` | string | `"air"` | Block id. Namespaced or bare. Required. |
| `weight` | int 1-1000 | `1` | Duplicates this entry in the palette, so `weight: 3` makes it three times as likely as a `weight: 1` sibling. |
| `data` | map | empty | Block state properties, e.g. `{"waterlogged": true}`. |
| `tileData` | map | empty | Tile-entity data for blocks that carry it. |
| `backup` | `IrisBlockData` | null | Used when `block` does not exist on this Minecraft version. |
| `debug` | boolean | `false` | Prints the resolved block to the console when Iris debug is on. Diagnostic only. |

The stacks:

| Field | What it fills |
|-------|---------------|
| `layers` | The column downward from the terrain surface. First entry is the top. Anything below the stack becomes the dimension's rock palette (or an ore, if an ore generator claims that block). Required; the default is a single grass layer. |
| `seaLayers` | The water column, indexed **downward from the water surface**, not upward from the sea floor. Index 0 sits at `fluidHeight`. Anything the stack does not cover becomes the dimension fluid. This is how you get a layer of ice or a band of murky water on top of an ocean. |
| `caveCeilingLayers` | The underside of carved ceilings, downward from the ceiling. Defaults to one grass layer, which is almost never what you want in a cave — set it explicitly. |
| `slab` | Palette for the half-slabs the post processor adds on single-block steps. Default is an empty palette, meaning no slabs. |
| `wall` | Palette for the vertical faces the post processor paints when a neighbouring column is more than two blocks lower. Default is empty. Set it to stone/andesite to stop cliffs showing dirt. |
| `lockLayers` | When true, the stack repeats as horizontal bands keyed to world height instead of following the surface, giving mesa striping. |
| `lockLayersMax` | Depth cap, in blocks, for locked layers. Default `7`. |

`caveCeilingLayers` reuses the per-layer thickness generators built from `layers`, so it must not have more entries than `layers` does. Give the biome at least as many surface layers as ceiling layers.

Slabs and walls only appear when the dimension has `postProcessing`, `postProcessingSlabs` and `postProcessingWalls` enabled. See `11 - Dimensions.md`.

### Custom biomes (`customDerivitives`)

The JSON key really is `customDerivitives`. The misspelling is baked into the engine field; `customDerivatives` is silently ignored.

Type: `IrisBiomeCustom`, available as the `custom-biome` snippet. Iris compiles these into a datapack and registers them as `<dimensionLoadKey>:<id>`.

When a biome has any custom derivative, that custom biome becomes the visible biome for the column and `derivative` / `biomeScatter` / `biomeSkyScatter` stop driving what players see. `vanillaDerivative` still drives structure eligibility and tag inheritance. With several entries, `biomeStyle` picks between them per position.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `id` | string | `""` | Resource path, lowercased on read. Must be unique in the pack. Required. |
| `category` | `IrisBiomeCustomCategory` | `plains` | Vanilla category written into the biome JSON. Required. |
| `temperature` | double -3..3 | `0.8` | Vanilla temperature: drives snow versus rain, water freezing and some mob behaviour. |
| `humidity` | double -3..3 | `0.4` | Written as vanilla `downfall`. Affects foliage tint and fire spread. |
| `downfallType` | `IrisBiomeCustomPrecipType` | `rain` | `none`, `rain` or `snow`. `none` also clears the `has_precipitation` flag. |
| `spawnRarity` | int 0-20 | `0` | Written straight into `creature_spawn_probability`. Leave at `0` unless you are also supplying `spawns`. |
| `spawns` | `IrisBiomeCustomSpawn[]` | empty | Mob spawn entries grouped by category. Only meaningful together with `spawnRarity`. |
| `tags` | string[] | empty | Extra biome tags, e.g. `minecraft:allows_surface_slime_spawns`. |
| `ambientParticle` | `IrisBiomeCustomParticle` | `null` | Client-rendered ambient particle. No server cost. |
| `skyColor` | hex | `#79a8e1` | Upper sky colour. |
| `fogColor` | hex | `#c0d8e1` | Horizon fog colour. |
| `waterColor` | hex | `#3f76e4` | Water surface tint. |
| `waterFogColor` | hex | `#050533` | Underwater fog tint. |
| `grassColor` | hex | `""` | Forces a grass tint. Empty means "leave it to the category", which is usually what you want unless you are matching a specific look. |
| `foliageColor` | hex | `""` | Same for leaves. Empty means inherit. |

On Minecraft 26.2, sky, fog, water-fog and ambient-particle values are published through the biome environment-attribute registry while water, grass and foliage stay biome effects. The conversion is automatic; the pack fields are unchanged.

Effective tags are your `tags` plus the direct tag membership of the vanilla derivative, deduplicated. Structure tags (`has_structure/*`) are deliberately not inherited, because native structure placement already resolves through the structure derivative and inheriting them would place structures twice.

Custom spawn entry (`IrisBiomeCustomSpawn`):

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `type` | entity key | `minecraft:cow` | Entity to spawn. Bare names are namespaced. |
| `minCount` / `maxCount` | int >= 1 | `2` / `5` | Pack size range. |
| `weight` | int 1-1000 | `1` | Relative chance against other entries in the same group. |
| `group` | `IrisBiomeCustomSpawnType` | `MISC` | Vanilla spawn category, which also decides the mob cap the spawn counts against. |

Spawn groups: `MONSTER`, `CREATURE`, `AMBIENT`, `AXOLOTLS`, `UNDERGROUND_WATER_CREATURE`, `WATER_CREATURE`, `WATER_AMBIENT`, `MISC`.

Categories (`IrisBiomeCustomCategory`): `beach`, `desert`, `extreme_hills`, `forest`, `icy`, `jungle`, `mesa`, `mushroom`, `nether`, `none`, `ocean`, `plains`, `river`, `savanna`, `swamp`, `taiga`, `the_end`.

Ambient particle (`IrisBiomeCustomParticle`):

| Field | Default | What it does |
|-------|---------|--------------|
| `particle` | `minecraft:flash` | Particle id, namespaced automatically. |
| `rarity` | `35` (1-10000) | Written as probability `1 / rarity`, so higher means fewer particles. |

Custom biomes are installed by datapack compilation, so a world usually has to be reopened (sometimes the server restarted) before newly added ids resolve. If a custom biome does not appear, check for a leftover `derivative` typo before blaming the datapack.

### Content attached to the biome

| Field | Type | What it does |
|-------|------|--------------|
| `decorators` | `IrisDecorator[]` | Grass, flowers, cactus, kelp and similar surface scatter, bucketed by `partOf` (surface, ceiling, shore line, sea surface, sea floor). See `16 - Surfaces, Decorators & Deposits.md`. |
| `objects` | `IrisObjectPlacement[]` | `.iob` placements. Split at runtime into surface and carving sets by each placement's `carvingSupport`. See `20 - Object Placement.md`. |
| `proceduralObjects` | `IrisProceduralObjects` | Trees, coral, fungi, crystals, ruins and formations generated from parameters. See `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`. |
| `structures` | `IrisStructurePlacement[]` | Jigsaw and native placements evaluated where this biome owns the chunk centre. |
| `floatingChildBiomes` | `IrisFloatingChildBiomes[]` | Floating islands above this biome's columns, drawn using another biome's materials. See below. |
| `mergeFloatingChildBiomes` | boolean | When true every floating entry samples independently and islands can overlap; when false (default) one entry is chosen per column. |
| `deposits` | `IrisDepositGenerator[]` | Blob deposits added on top of regional and dimension deposits. |
| `depositVariants` | `IrisDepositVariant[]` | Y-banded ore remaps. This is the first tier evaluated, ahead of region and dimension; first match in the tier wins. |
| `oreDepositFrequencyMultiplier` | double 0-1 | Scales how many ore veins have their centre in this biome. `0.4` keeps 40% of them. Non-ore deposits are untouched. Use it to make a biome ore-poor without editing the global generators. |
| `oreDepositSizeMultiplier` | double 0.01-16 | Scales the block count of those veins. Use it for a biome with rare-but-huge veins (`frequency` down, `size` up). |
| `ores` | `IrisOreGenerator[]` | Vein generators owned by this biome, each flagged surface or underground. |
| `entitySpawners` | string[] | `IrisSpawner` keys replenished over time while a player is here. |
| `effects` | `IrisEffect[]` | Per-player packet ambience. |
| `loot` | `IrisLootReference` | Loot tables for containers generated here. |
| `blockDrops` | `IrisBlockDrops[]` | Custom drops for blocks broken here. |
| `caveProfile` | `IrisCaveProfile` | Overrides the region's cave profile for this biome. |

Placements are gathered per chunk from the biome at the chunk centre, the cave biome at the same point, the region and the dimension. A surface biome contributes all of its `structures[]`; the cave biome contributes only placements whose resolved anchor is `CAVE_FLOOR`, `CAVE_CEILING`, `CAVE_CENTER` or `CAVE_ANY`. Surface and height-band placements written into cave-biome files are ignored. A placement's own `caveBiomes` list is an additional allowlist rechecked at each candidate anchor. See `15 - Caves & Carving.md` and `21 - Jigsaw Structures.md`.

## Floating child biomes (`IrisFloatingChildBiomes`)

`floatingChildBiomes` builds islands in the air above columns owned by this biome. Each entry names a target biome whose generators, layers, derivative, decorators and objects supply the island's look, while the entry's own fields control size, shape, altitude, rarity and internal water. With `mergeFloatingChildBiomes: false` (the default), `pickerStyle` and `rarity` choose one entry per column; with it true, every entry samples independently and islands may intersect.

Reachability follows region roots, dimension carving biomes, ordinary children, carving replacements, floating targets and floating `carving` references, recursively and deduplicated, so every biome that generation can reach is registered for spawns, placements, structures and lookups. Custom-biome datapack installation still scans the pack's complete authored biome set, not just the reachable ones.

### Target, footprint and altitude

| Field | Default / range | What it does |
|-------|-----------------|--------------|
| `biome` | `""` | Target biome key. Empty, missing, or the parent's own key means reuse the parent. |
| `rarity` | `1` (1-512) | Relative share when several entries compete for a column. Lower is more common. |
| `footprintStyle` | `SIMPLEX` | 2D outline noise. `CELLULAR` gives angular shards, `VASCULAR` gives branching strips, `FRACTAL_FBM_SIMPLEX` gives large irregular blankets. Fracture it for swirled silhouettes. |
| `footprintThreshold` | `0.5` (0-1) | Minimum footprint sample that counts as island. `0.0` is a continuous sky blanket, `0.8` is sparse scattered islands, `1.0` produces nothing. |
| `pickerStyle` | `SIMPLEX` | Chooses which entry owns a column when entries are not merged. Use a large zoom so each entry owns broad coherent regions. |
| `altitudeStyle` | `SIMPLEX` | Varies the island base between the two height bounds. Large zoom keeps one island at one altitude. |
| `minHeightAboveSurface` / `maxHeightAboveSurface` | `160` / `210` (0-2032) | Despite the names, these are absolute world Y bounds for the island base, independent of the terrain below. |
| `minAbsoluteY` | `null` | Optional clamp that pushes the base up so the hanging tail stays above this Y. |
| `maxAbsoluteY` | `null` | Optional clamp that pulls the island top down. |

### Edge, top and underside shape

| Field | Default / range | What it does |
|-------|-----------------|--------------|
| `edgeTaperWidth` | `10` (2-32) | Width in blocks of the rounded transition from the outline to full thickness. Small values give a hard rim, large values a broad domed underside. |
| `edgeTaperExponent` | `1.0` (0.25-4) | Curve of that transition. Below 1 fills the rim out; above 1 keeps it thin. |
| `edgeTaperVariationStyle` | `SIMPLEX` at zoom `0.18` | Varies the taper width coherently without moving the outline. |
| `edgeTaperVariationAmplitude` | `0` (0-8) | How much local widening or narrowing that style applies. `0` disables it. The runtime keeps the resulting width inside 2-32 so rims stay connected. |
| `topShapeMode` | `BIOME` | `BIOME` runs the target biome's own generators, so a mountains target grows real peaks. `NOISE` uses `topShapeStyle` as a heightmap. `FLAT` is a constant slab. |
| `maxTopHeight` | `40` (0-512) | Ceiling on how far the top rises above the base. |
| `topShapeStyle` | `SIMPLEX` | Heightmap used when the mode is `NOISE`. |
| `topShapeAmp` | `1` (0-1) | Scales that noise-driven profile down. |
| `bottomStyle` | `SIMPLEX` | Underside noise. `VASCULAR` gives drippy roots, `FRACTAL_RM_SIMPLEX` crystalline spikes, `PERLIN` smooth bowls. |
| `bottomDepthMin` / `bottomDepthMax` | `4` / `20` (0-512) | Tail depth range below the base. |
| `bottomExponent` | `1` (0.1-8) | Bias on tail depth. Above 1 makes deep tails rare spikes; below 1 makes most of the underside deep. |
| `maxThickness` | `96` (1-512) | Hard cap on total top-to-bottom thickness. |
| `wallWarpStyle` | `null` | Optional 3D noise that offsets the footprint sample per Y layer, so walls meander instead of extruding straight. |
| `wallWarpAmplitude` | `6` (0-64) | Maximum wall displacement. Ignored without `wallWarpStyle`. |

### Materials, fluids and carving

| Field | Default | What it does |
|-------|---------|--------------|
| `bottomPaletteMode` | `DEPTH` | `DEPTH` runs normal top-down layers. `MIRROR_TOP` mirrors the shallow palette onto the underside. `CUSTOM` uses `bottomPalette` near the underside and the target palette near the top. |
| `bottomPalette` | `[]` | Layers used only by `CUSTOM`. |
| `localFluidHeight` | `null` | Fluid surface relative to the island base. Set it to fill dips in the top profile with ponds. `null` means no internal water. |
| `fluidBlock` | `minecraft:water` | Block used for those pools. |
| `carveStyle` | `null` | Direct 3D pocket noise inside the island. |
| `carving` | `""` | Dimension carving-entry id or biome key. Dimension entries resolve first, and their cave profile overrides `carveStyle`. |
| `carveThreshold` | `1` (0-1) | With `carveStyle`, noise above this becomes air (`1` means no carving, `0.55` heavy swiss cheese). With `carving`, it biases the referenced cave profile instead. |

### Decoration and objects

| Field | Default | What it does |
|-------|---------|--------------|
| `inheritDecorators` | `true` | Apply the target biome's decorators to the island top. |
| `inheritObjects` | `true` | Allow the target biome's surface objects on the island top. |
| `objectShrinkFactor` | `1` (0.01-1) | Uniform scale for inherited, extra and free-floating objects. Drop it to about `0.5` so full-size trees do not dwarf a small island. |
| `extraObjects` | `[]` | Extra placements anchored to the island top. |
| `floatingObjects` | `[]` | Placements generated independently in mid-air, forced to floating placement mode. |
| `topObjectMode` | `INHERIT_ONLY` | `INHERIT_ONLY` ignores `topObjectOverrides`; `MERGE` appends them after the inherited set; `REPLACE` uses only the overrides. |
| `topObjectOverrides` | `[]` | Consumed according to `topObjectMode`. |
| `bottomObjectMode` | `INHERIT_ONLY` | `INHERIT_ONLY` places nothing on the underside. `MERGE` and `REPLACE` behave identically because there is no inherited bottom set. |
| `bottomObjectOverrides` | `[]` | Placements flipped 180 degrees around X and set flush against the lowest solid face. Directional blocks (stairs, doors, slabs) will not survive the flip; use logs, leaves, stone, ice or glass. |
| `color` | `null` | Studio visualisation colour. |

```json
{
  "floatingChildBiomes": [{
    "biome": "temperate/plains",
    "rarity": 2,
    "footprintStyle": { "style": "SIMPLEX", "zoom": 0.8 },
    "footprintThreshold": 0.7,
    "minHeightAboveSurface": 160,
    "maxHeightAboveSurface": 210,
    "topShapeMode": "BIOME",
    "bottomDepthMin": 6,
    "bottomDepthMax": 28,
    "objectShrinkFactor": 0.6,
    "inheritDecorators": true,
    "inheritObjects": true
  }]
}
```

## Overworld samples

### Land biome — `biomes/temperate/plains.json`

```json
{
  "name": "Plains",
  "color": "#42A616",
  "rarity": 2,
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains",
  "generators": [{ "min": 4, "max": 10, "generator": "plain" }],
  "biomeStyle": { "style": "SIMPLEX" },
  "wall": { "palette": [{ "block": "minecraft:stone" }, { "block": "minecraft:andesite" }] },
  "layers": [
    { "palette": [{ "block": "minecraft:grass_block" }] },
    { "minHeight": 2, "maxHeight": 2, "palette": [{ "block": "minecraft:dirt" }] },
    { "minHeight": 1, "maxHeight": 3, "palette": [{ "block": "minecraft:dirt" }, { "block": "minecraft:coarse_dirt" }] },
    { "minHeight": 6, "maxHeight": 18, "style": { "style": "STATIC" },
      "palette": [{ "block": "minecraft:dirt" }, { "block": "minecraft:stone" }] }
  ]
}
```

A shallow 4-10 band, four layers ending in a thick speckled dirt/stone blend so the transition to bedrock rock is not a hard line, and a stone/andesite `wall` so cliff faces do not show dirt. The real file also carries `decorators` and `objects`.

### Parent with a child and a custom biome — `biomes/temperate/oak-forest.json`

```json
{
  "name": "Oak Forest",
  "derivative": "minecraft:forest",
  "vanillaDerivative": "minecraft:forest",
  "customDerivitives": [{
    "id": "oak_forest",
    "category": "forest",
    "grassColor": "#77A620",
    "foliageColor": "#64B233"
  }],
  "children": ["temperate/oak-forest-extended"],
  "generators": [
    { "generator": "smooth-dunes", "max": 12, "min": 5 },
    { "generator": "rare-hills", "max": 40, "min": 0 }
  ]
}
```

The custom derivative only changes colours; `derivative` and `vanillaDerivative` stay on `minecraft:forest` so forest structures and forest tags still apply.

### Colour-only custom biome — `biomes/vanilla/sunflower_plains.json` (excerpt)

```json
{
  "customDerivitives": [{
    "category": "plains",
    "id": "sunflower_plains",
    "grassColor": "#91BD59",
    "foliageColor": "#77AB2F",
    "waterColor": "#44AFF5",
    "downfallType": "none"
  }]
}
```

## Minimal biome JSON

```json
{
  "name": "Starter Plains",
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains",
  "generators": [{ "generator": "flat", "min": 96, "max": 96 }],
  "layers": [{ "palette": [{ "block": "minecraft:grass_block" }] }]
}
```

Needs `generators/flat.json` to exist. Everything else in the file has a working default.

## Checklist for a new biome

1. Create `biomes/<path>/<name>.json`. The path is the load key regions will reference, so pick it before wiring anything.
2. Set `name`, `derivative` and `vanillaDerivative`.
3. Add one `generators` link and make sure the referenced generator file exists.
4. Define `layers` from the top down: surface, subsoil, then a blend into stone.
5. Add the key to exactly one region role first: land, sea, shore or cave.
6. Set the dimension's `focus` to the key, validate, open Studio, and confirm surface blocks, terrain Y and the relationship to the water line.
7. Add `wall` if the biome makes cliffs, then decorators, then objects, one group at a time.
8. For variants, create the child file and list it in the parent's `children` — never in a region.
9. For colours, tags or mob spawns, add `customDerivitives` with a unique `id` and `category`, then reopen the world so the datapack installs.
10. Remove `focus` and confirm the biome still appears through ordinary region selection.

## Common mistakes

| Mistake | What you will see |
|---------|-------------------|
| `derivative` left at `minecraft:the_void` | Void colours, no mob spawning, no structure eligibility |
| Generator key that does not resolve | The link silently contributes zero height; terrain flattens instead of erroring |
| Child biome also listed in a region | The child generates as a full-size root, so the nesting disappears |
| Spelling `customDerivatives` | Field ignored entirely; the engine key is `customDerivitives` |
| Sea biome with positive `min`/`max` | It generates above water, then gets replaced by a land biome anyway |
| Sea biome with a non-ocean `vanillaDerivative` | No native ocean structures generate there |
| Empty `palette` on the first layer | No surface block; the rock palette shows through |
| More `caveCeilingLayers` entries than `layers` entries | Ceiling generation fails at that biome |
| Expecting a `type` field on the biome | Role comes from the region list that selected it |
| Expecting `slopeCondition` to thin a layer gradually | Out-of-range columns skip the layer entirely; there is no taper |
| Judging changes in already-generated chunks | Biome and layer edits only apply to new chunks |

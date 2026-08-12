# 13 - Biomes

A biome is the primary surface/authoring unit for terrain height, block layers, decorations, objects, and Minecraft biome derivatives. Files live under `biomes/<loadKey>.json`. Regions reference root biomes; biomes may nest children and optional custom datapack biomes.

Related: see `12 - Regions.md`, `14 - Generators & Noise.md`, `16 - Surfaces, Decorators & Deposits.md`, `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`, `19 - Objects.md`, `20 - Object Placement.md`, `23 - Loot, Entities, Spawners, Markers.md`.

## Tutorial outcome

Create a visible, selectable biome with a known surface and height before adding variants or decoration. Use the minimal JSON near the end of this guide, attach it to one focused region, and keep the seed fixed while testing.

### Prerequisites and file placement

Use a validating dimension, a region listed by that dimension, and `generators/flat.json` from `26 - Example - Minimal Dimension.md`. Save the complete **Minimal Biome JSON** below as `biomes/tutorial/meadow.json`, reference `tutorial/meadow` from the region's `landBiomes`, and temporarily set dimension `focus` to the same key.

### Build and verify

1. Keep both derivative fields at `minecraft:plains`, one grass surface layer, and the flat generator link until the resource graph works.
2. Validate the pack and open Studio on seed `1337`.
3. Generate new chunks, then run `/iris what biome`. Success is the `tutorial/meadow` load key, a grass surface, and a constant terrain height with no unresolved generator warnings.
4. If the biome does not appear, compare the region entry, biome path, and dimension `focus` character-for-character. If the biome appears over void terrain, validate `generators/flat.json` and its link before changing height values.
5. Remove `focus`, reopen Studio, and confirm the biome can be selected naturally. Add children, decorators, objects, and custom derivatives only after this baseline passes.

## Role

| Layer | Responsibility |
|-------|----------------|
| Region lists | Choose which root biomes can appear |
| Biome `generators` | Height relative to dimension `fluidHeight` |
| Biome `layers` | Surface and subsurface material stacks |
| `derivative` / `vanillaDerivative` | Minecraft biome for colors and structure eligibility |
| `customDerivitives` | Optional custom datapack biomes (field spelling is intentional in code) |
| Objects / structures / decorators | Placement and decoration on this biome |

`InferredType` (`LAND`, `SEA`, `SHORE`, `CAVE`) is assigned from which region list selected the biome, not from a JSON field on the biome itself.

## Load Key

| Rule | Detail |
|------|--------|
| Folder | `biomes/` |
| Key | Relative path without `.json` |
| Examples | `starter` → `biomes/starter.json`; `temperate/plains` → `biomes/temperate/plains.json`; `carving/drip` → `biomes/carving/drip.json` |

## Core Fields (`IrisBiome`)

### Identity

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `name` | string | `"Subterranean Land"` | Required human-readable name (not the load key) |
| `rarity` | int | `1` | 1–512; rarity among sibling biomes in a region list |
| `color` | string | `null` | Map color, e.g. `#42A616` |

### Minecraft derivatives (required for generation)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `derivative` | string (biome key) | `"minecraft:the_void"` | **Required.** Vanilla/mod biome used for Iris terrain/color resolution |
| `vanillaDerivative` | string | `null` → falls back to `derivative` | Structure selection derivative; land/sea/shore eligibility rules apply for vanilla namespaces |
| `biomeScatter` | string[] | empty | Extra derivatives for color scatter |
| `biomeSkyScatter` | string[] | empty | Derivatives above terrain (3D biome colors) |
| `biomeStyle` | `IrisGeneratorStyle` | `SIMPLEX` | Scatter dispersion when multiple derivatives |

Use namespaced keys (`minecraft:plains`) or bare vanilla paths accepted by `NamespacedKey` resolution.

### Children and carving

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `children` | string[] | empty | Child biome load keys; portions of this biome morph into children |
| `childShrinkFactor` | double | `1.5` | Child size vs parent (docs suggest ~1–3) |
| `childStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Child shape noise |
| `carvingBiome` | string | `""` | Biome used under carving instead of this one when set |
| `caveMinDepthBelowSurface` | int | `0` | Min depth below surface before this cave biome can be picked |

Cyclic child graphs are supported; Iris stops walking children after a depth limit (annotation: nine biomes down the tree).

### Generators (height)

Type: `IrisBiomeGeneratorLink` (`@Snippet("generator-layer")`).

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `generator` | string | `"default"` | Load key under `generators/` |
| `min` | int | `0` | Height offset min relative to fluid height (−2032…2032) |
| `max` | int | `0` | Height offset max relative to fluid height |

Height is lerped from generator noise in \[0,1\] into \[min, max\], then added relative to fluid height. Negative min/max produce ocean floors.

Multiple generator links mix with other biomes’ generators as expected when interpolation sizes differ.

### Layers (block palettes)

Type: `IrisBiomePaletteLayer` (`@Snippet("biome-palette")`).

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `palette` | `IrisBlockData[]` | grass_block | **Required.** Weighted blocks |
| `minHeight` | int | `1` | Min layer thickness (0–2032) |
| `maxHeight` | int | `1` | Max layer thickness (1–2032) |
| `style` | `IrisGeneratorStyle` | `STATIC` | Multi-block palette noise |
| `zoom` | double | `5` | Palette noise zoom |
| `slopeCondition` | `IrisSlopeClip` | empty | Optional slope gate/growth |

`IrisBlockData` entries:

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `block` | string | `"air"` | Block id, e.g. `minecraft:grass_block` |
| `weight` | int | `1` | Relative pick weight |
| `data` | map | empty | Block state properties |
| `backup` | block data | optional | Fallback if block missing |
| `debug` | boolean | false | Console debug when Iris debug enabled |

Biome layer stacks:

| Field | Role |
|-------|------|
| `layers` | Surface-down stack (required; default one empty grass layer) |
| `seaLayers` | Underwater surface layers |
| `caveCeilingLayers` | Cave ceiling material stack |
| `slab` | Default slab layer for post slabs (default empty/zero palette) |
| `wall` | Steep-face wall palette (default empty/zero) |
| `lockLayers` | When true, layers descend from max biome height (mesa style) |
| `lockLayersMax` | Max layers when locked (default `7`) |

Below authored layers, Iris fills with the dimension rock palette.

### Custom biomes (`customDerivitives`)

**JSON field name is `customDerivitives`** (misspelling of “derivatives” preserved in `IrisBiome`).

Type: `IrisBiomeCustom` (`@Snippet("custom-biome")`). Installed via datapack compilation.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `id` | string | `""` | **Required.** Resource path id (lowercased), e.g. `oak_forest` |
| `category` | `IrisBiomeCustomCategory` | `plains` | **Required.** Vanilla category enum |
| `temperature` | double | `0.8` | −3…3 |
| `humidity` | double | `0.4` | −3…3 (downfall amount) |
| `downfallType` | `IrisBiomeCustomPrecipType` | `rain` | `none`, `rain`, `snow` |
| `spawnRarity` | int | `0` | 0–20 creature spawn probability |
| `spawns` | `IrisBiomeCustomSpawn[]` | empty | Custom mob spawns |
| `tags` | string[] | empty | Explicit biome tags |
| `ambientParticle` | `IrisBiomeCustomParticle` | null | Client particle |
| `skyColor` | hex string | `#79a8e1` | |
| `fogColor` | hex string | `#c0d8e1` | |
| `waterColor` | hex string | `#3f76e4` | |
| `waterFogColor` | hex string | `#050533` | |
| `grassColor` | hex string | `""` (omit if empty) | |
| `foliageColor` | hex string | `""` | |

On Minecraft 26.2, Iris publishes sky, fog, water-fog, and ambient-particle values through the biome environment-attribute registry. Water, grass, and foliage colors remain biome effects. This conversion is automatic; pack fields do not change.

Tag inheritance: effective tags = authored `tags` plus non-structure tags of the vanilla derivative. Structure tags (`has_structure/*`) are **not** inherited so native structures are not double-placed.

#### Custom spawn entry (`IrisBiomeCustomSpawn`)

| Field | Type | Default |
|-------|------|---------|
| `type` | entity key | `minecraft:cow` |
| `minCount` | int | `2` |
| `maxCount` | int | `5` |
| `weight` | int | `1` |
| `group` | `IrisBiomeCustomSpawnType` | `MISC` |

Spawn groups: `MONSTER`, `CREATURE`, `AMBIENT`, `AXOLOTLS`, `UNDERGROUND_WATER_CREATURE`, `WATER_CREATURE`, `WATER_AMBIENT`, `MISC`.

#### Custom categories (`IrisBiomeCustomCategory`)

`beach`, `desert`, `extreme_hills`, `forest`, `icy`, `jungle`, `mesa`, `mushroom`, `nether`, `none`, `ocean`, `plains`, `river`, `savanna`, `swamp`, `taiga`, `the_end`.

#### Ambient particle (`IrisBiomeCustomParticle`)

| Field | Default |
|-------|---------|
| `particle` | `minecraft:flash` |
| `rarity` | `35` (higher = rarer; probability `1/rarity` in datapack JSON) |

### Decorators, objects, structures, ores

| Field | Type | Notes |
|-------|------|-------|
| `decorators` | `IrisDecorator[]` | Tall grass, cactus, kelp-style placements (see `16 - Surfaces, Decorators & Deposits.md`) |
| `objects` | `IrisObjectPlacement[]` | `.iob` placements |
| `proceduralObjects` | `IrisProceduralObjects` | Procedural trees/coral/etc. |
| `structures` | `IrisStructurePlacement[]` | Jigsaw/native placements; cave-biome lists contribute only editable Iris placements using a resolved cave anchor |
| `floatingChildBiomes` | `IrisFloatingChildBiomes[]` | Floating islands using another biome’s visuals |
| `mergeFloatingChildBiomes` | boolean | When true, all floating entries sample independently |
| `deposits` | `IrisDepositGenerator[]` | Biome deposits |
| `depositVariants` | `IrisDepositVariant[]` | Ore remaps (first of biome tier) |
| `oreDepositFrequencyMultiplier` | double | 0–1 scale ore vein frequency (default `1`) |
| `oreDepositSizeMultiplier` | double | 0.01–16 scale ore size (default `1`) |
| `ores` | `IrisOreGenerator[]` | Biome ores |
| `entitySpawners` | string[] | Spawner keys |
| `effects` | `IrisEffect[]` | Ambient effects |
| `loot` | `IrisLootReference` | Biome loot |
| `blockDrops` | `IrisBlockDrops[]` | Custom drops |
| `caveProfile` | `IrisCaveProfile` | Biome cave profile override |

A surface biome contributes all of its `structures[]` placements when it owns the start chunk center. The cave biome sampled at that center contributes only placements whose resolved anchor is `CAVE_FLOOR`, `CAVE_CEILING`, `CAVE_CENTER`, or `CAVE_ANY`; surface/height-band placements in cave-biome JSON are ignored. `caveBiomes` on the placement is an additional allowlist rechecked against the cave/mantle biome at each actual anchor candidate. See `15 - Caves & Carving.md` and `21 - Jigsaw Structures.md`.

## Floating child biomes (`IrisFloatingChildBiomes`)

`floatingChildBiomes` builds floating terrain above columns owned by the parent biome. Each entry can reuse the parent or reference another biome for its generators, layers, derivative, decorators, and surface objects. With `mergeFloatingChildBiomes: false` (default), `pickerStyle` and `rarity` select one entry per column; with it true, every entry samples independently and islands may overlap.

Biome reachability follows configured region roots, enabled dimension-carving biomes, ordinary children and carving replacements, floating targets, and floating `carving` references recursively. Floating carving-entry ids resolve before direct biome keys, matching generation; cycles are deduplicated, and every generation-reachable biome participates in runtime spawn, placement, structure, and lookup indexes. Custom-biome datapack installation continues to scan the pack's complete authored biome set.

### Target, footprint, and altitude

| Field | Default / range | Behavior |
|-------|-----------------|----------|
| `biome` | `""` | Target biome key; empty, missing, or the parent key falls back to the parent biome |
| `rarity` | `1` (1–512) | Relative selection rarity; lower values are more common |
| `footprintStyle` | `SIMPLEX` | 2D island-outline noise; style zoom and fracture control scale and warping |
| `footprintThreshold` | `0.5` (0–1) | Minimum footprint sample; higher values produce less coverage |
| `pickerStyle` | `SIMPLEX` | Coherent per-column entry selection when entries are not merged |
| `altitudeStyle` | `SIMPLEX` | Varies the island base between the configured heights |
| `minHeightAboveSurface` / `maxHeightAboveSurface` | `160` / `210` (0–2032) | Absolute world-Y range for the base despite the historical field names |
| `minAbsoluteY` | `null` | Optional lower clamp for the base/tail |
| `maxAbsoluteY` | `null` | Optional upper clamp for the island top |

### Edge, top, and underside shape

| Field | Default / range | Behavior |
|-------|-----------------|----------|
| `edgeTaperWidth` | runtime default (2–32) | Width of the rounded contour-to-full-thickness transition |
| `edgeTaperExponent` | runtime default (0.25–4) | Below 1 makes a fuller edge; above 1 keeps the rim thinner |
| `edgeTaperVariationStyle` | broad `SIMPLEX` | Coherently varies taper width without changing the footprint |
| `edgeTaperVariationAmplitude` | `0` (0–8) | Local widening/narrowing; runtime clamps the resulting width to 2–32 |
| `topShapeMode` | `BIOME` | `BIOME` uses target generators; `NOISE` uses `topShapeStyle`; `FLAT` uses a fixed top |
| `maxTopHeight` | `40` (0–512) | Maximum height above the island base |
| `topShapeStyle` | `SIMPLEX` | Top heightmap when mode is `NOISE` |
| `topShapeAmp` | `1` (0–1) | Multiplier for the noise-driven top profile |
| `bottomStyle` | `SIMPLEX` | 2D noise for the hanging underside/tail |
| `bottomDepthMin` / `bottomDepthMax` | `4` / `20` (0–512) | Tail depth range below the base |
| `bottomExponent` | `1` (0.1–8) | Power curve for tail depth; above 1 makes deep tails sparser |
| `maxThickness` | `96` (1–512) | Hard cap on top-to-bottom column thickness |
| `wallWarpStyle` | `null` | Optional 3D noise that shifts X/Z footprint samples by Y layer |
| `wallWarpAmplitude` | `6` (0–64) | Maximum wall-warp displacement; ignored without `wallWarpStyle` |

### Materials, fluids, and carving

| Field | Default | Behavior |
|-------|---------|----------|
| `bottomPaletteMode` | `DEPTH` | `DEPTH` uses normal top-down layers; `MIRROR_TOP` mirrors the shallow palette; `CUSTOM` uses `bottomPalette` near the underside |
| `bottomPalette` | `[]` | `IrisBiomePaletteLayer[]` used only by `CUSTOM` |
| `localFluidHeight` | `null` | Fluid surface relative to the island base; null disables internal pools |
| `fluidBlock` | `minecraft:water` | Block used for internal pools |
| `carveStyle` | `null` | Optional direct 3D pocket noise |
| `carving` | `""` | Optional dimension carving-entry id or biome key; dimension entries resolve first and their cave profile overrides `carveStyle` |
| `carveThreshold` | `1` (0–1) | Direct noise above this value becomes air; with `carving`, tunes the referenced cave profile |

### Decoration and objects

| Field | Default | Behavior |
|-------|---------|----------|
| `inheritDecorators` | `true` | Apply target-biome decorators to the island top |
| `inheritObjects` | `true` | Allow target-biome surface objects on the island top |
| `objectShrinkFactor` | `1` (0.01–1) | Uniform scale for inherited, extra, and free-floating objects |
| `extraObjects` | `[]` | Additional `IrisObjectPlacement` entries anchored to the island top |
| `floatingObjects` | `[]` | Additional placements generated independently in air with floating placement mode |
| `topObjectMode` | `INHERIT_ONLY` | `INHERIT_ONLY`, `MERGE`, or `REPLACE` for inherited top objects versus overrides |
| `topObjectOverrides` | `[]` | Top placements consumed according to `topObjectMode` |
| `bottomObjectMode` | `INHERIT_ONLY` | Enables `bottomObjectOverrides`; `MERGE` and `REPLACE` are equivalent because there is no inherited bottom set |
| `bottomObjectOverrides` | `[]` | Placements attached upside-down to the lowest solid face; directional blocks may not survive the flip correctly |
| `color` | `null` | Iris Studio visualization color |

Example:

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
    "inheritDecorators": true,
    "inheritObjects": true
  }]
}
```

## Overworld Samples

### Land plains — `biomes/temperate/plains.json`

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
    { "minHeight": 2, "maxHeight": 2, "palette": [{ "block": "minecraft:dirt" }] }
  ]
}
```

(File continues with more layers, objects, and placements.)

### Parent with children and custom biome — `biomes/temperate/oak-forest.json`

```json
{
  "name": "Oak Forest",
  "derivative": "minecraft:forest",
  "vanillaDerivative": "minecraft:forest",
  "customDerivitives": [{
    "id": "oak_forest",
    "foliageColor": "#64B233",
    "grassColor": "#77A620",
    "category": "forest"
  }],
  "children": ["temperate/oak-forest-extended"],
  "generators": [
    { "generator": "smooth-dunes", "max": 12, "min": 5 },
    { "generator": "rare-hills", "max": 40, "min": 0 }
  ]
}
```

### Sea biome heights — `biomes/temperate/sea/ocean.json` (excerpt)

```json
{
  "name": "Temperate Ocean",
  "derivative": "minecraft:lukewarm_ocean",
  "vanillaDerivative": "minecraft:ocean",
  "generators": [{ "min": -32, "max": -10, "generator": "mountain" }]
}
```

Negative generator min/max place the surface below fluid height.

### Custom-only colors — `biomes/vanilla/sunflower_plains.json` (excerpt)

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

## Minimal Biome JSON

Studio starter:

```json
{
  "name": "Starter Plains",
  "layers": [{ "palette": [{ "block": "minecraft:grass_block" }] }],
  "generators": [{ "generator": "flat", "min": 96, "max": 96 }],
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains"
}
```

Requires a matching generator file under `generators/` (starter uses `generators/flat.json`).

## How To: Make a Biome

1. Add `biomes/<path>/<name>.json`. Choose load key path carefully; regions will reference it exactly.
2. Set `name`, `derivative`, `vanillaDerivative`.
3. Add at least one `generators` link and a generator JSON under `generators/`.
4. Define `layers` from topsoil down (grass → dirt → stone blend).
5. Attach the biome to exactly one region role: land → `landBiomes`, ocean floor → `seaBiomes`, beach → `shoreBiomes`, cave → `caveBiomes`.
6. Set dimension `"focus": "<biome-key>"`, validate, open Studio, and inspect newly generated chunks. Confirm surface blocks, terrain Y, fluid relationship, and vanilla structure eligibility.
7. Optionally set `wall` for cliffs, then add decorators and objects one group at a time.
8. For variants inside a parent, create a child biome file and list its key in the parent's `children`; do not also list the child as a region root.
9. For custom colors, tags, or mobs, add `customDerivitives` with a unique `id` and `category`, then reopen the world if the generated biome registry changed.
10. Remove `focus` and verify the biome appears through ordinary region selection.

Success means the biome is visible through Iris inspection tools, uses the intended derivative, and appears both focused and naturally selected without unresolved keys.

## Generator Link How-To

1. Create or reuse `generators/<id>.json` (noise composite + interpolator; see `14 - Generators & Noise.md`).
2. On the biome:

```json
{
"generators": [
  { "generator": "plain", "min": 4, "max": 10 }
]
}
```

3. Land: positive min/max above fluid. Sea: negative min/max. Flat plateaus: min == max.

## Custom Biome How-To

1. Add:

```json
{
"customDerivitives": [
  {
    "id": "my_plains",
    "category": "plains",
    "temperature": 0.8,
    "humidity": 0.4,
    "downfallType": "rain",
    "grassColor": "#91BD59",
    "foliageColor": "#77AB2F"
  }
]
}
```

2. Keep `derivative` / `vanillaDerivative` set to a close vanilla biome for structure eligibility and tag inheritance.
3. Open studio or recreate the world so datapack custom biomes install (create/open may require restart when datapacks change).
4. Do not invent field names like `customDerivatives` — the engine field is `customDerivitives`.

## Common Author Mistakes

| Mistake | Result |
|---------|--------|
| Missing `derivative` | Terrain/biome resolution fails or voids |
| Wrong generator key | Falls back to empty default generator behavior |
| Listing child biomes on the region | Breaks parent/child hierarchy intent |
| `customDerivatives` spelling | Field ignored; use `customDerivitives` |
| Sea biome with positive generators | “Ocean” generates as land relative to fluid |
| Empty `layers` palette | Missing surface blocks |
| Expecting biome `type` field | Role comes from region list membership (`InferredType`) |

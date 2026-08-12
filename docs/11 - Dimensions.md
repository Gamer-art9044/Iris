# 11 - Dimensions

A dimension is the root object of a pack: it decides how tall the world is, where sea level sits, which regions supply biomes, and which engine stages run. It lives at `dimensions/<loadKey>.json` and everything else in the pack hangs off it. Some of its fields are a permanent contract with the world folder and some are free to change every save — this page separates those two groups and explains what each knob actually does to generated terrain.

Related: see `05 - Concepts & Pack Layout.md`, `10 - Studio & VSCode Schemas.md`, `12 - Regions.md`, `14 - Generators & Noise.md`, `15 - Caves & Carving.md`, `18 - Structures Overview.md`, `22 - Native Structures & Datapacks.md`, `26 - Example - Minimal Dimension.md`.

## Decide these before you create a world

Iris pins four values when a world binds to an engine: the dimension type key (derived from the dimension load key), the minimum Y, the total height (`max - min`), and `logicalHeight`. That record is `IrisDimensionRuntimeContract`. Hotload compares the record before and after every pack reload and refuses the reload if any of the four changed, so a height edit in a running Studio world is rejected until you close and reopen. On a production world the values are baked into the generated Minecraft dimension type, so changing them means recreating the world.

| Field | Why it is a contract |
|-------|----------------------|
| Dimension file name (load key) | Becomes the dimension type key `iris:<sanitized load key>`; renaming the file makes the world look for a dimension type that no longer exists |
| `dimensionHeight` | Min Y and total height are written into the Minecraft dimension type and into every stored mantle/chunk coordinate |
| `logicalHeight` | Written into the same dimension type |
| `environment` | Selects which vanilla dimension template the generated type is built from, which changes the type's identity |

Everything else — regions, zooms, noise styles, palettes, ores, deposits, caves, structures, decoration, loot — reloads live in Studio and applies to newly generated chunks. Iterate on those freely; already-generated chunks keep whatever they were built with.

## File location and load key

The load key is the path under `dimensions/` with `.json` removed. `dimensions/overworld.json` is key `overworld`; `dimensions/foo/bar.json` is key `foo/bar`. The world create and studio open commands take that key.

The key is also sanitized into the dimension type key: lowercased, path separators and any character outside `a-z0-9_-./` replaced with `_`. Two dimensions whose keys sanitize to the same string collide on the same generated dimension type, so keep keys distinct in more than punctuation.

`/iris studio create name=mypack` writes `dimensions/mypack.json` with a matching `name`. Keep the file name and the load key stable once a world exists.

## Role in the pack graph

```
Dimension → regions[] → Region → land/sea/shore/cave biomes[] → Biome → generators[]
          → ores, deposits, depositVariants, overlayNoise
          → caveProfile, carving[], structures[], importedStructures, importedFeatures
          → loot, entitySpawners, blockDrops
```

The dimension never names a biome directly (outside `focus` and carving entries). Biomes are reached through regions, so an unreferenced region file is dead weight and a biome missing from every region list never generates.

## Engine mode

`mode.type` picks which stages the engine registers. This is the single biggest behavioral switch in the file: three of the four modes register only a terrain pass and a biome pass, so caves, objects, decorations, deposits and post-processing do not run at all in them. Do not reach for `ISLANDS` or `ENCLOSURE` expecting a themed world — today they generate exactly what `SUPERFLAT` does.

```json
{
  "mode": { "type": "OVERWORLD" }
}
```

| Type | Stages actually registered | Use it when |
|------|----------------------------|-------------|
| `OVERWORLD` | Biome, mantle matter generation, terrain, carve, post-process, floating child biome solids, deposits, matter insert, decoration, floating child biome decoration, perfection, custom modifiers | Any world that needs caves, objects, structures, decorations or entities — this is the only complete mode |
| `SUPERFLAT` | Terrain and biome only | Fast noise/biome-layout previews, or a genuinely bare world; nothing else generates |
| `ENCLOSURE` | Terrain and biome only | Nothing yet; the nether-style ceiling/floor treatment is not implemented |
| `ISLANDS` | Terrain and biome only | Nothing yet; floating-island terrain comes from biome `floatingChildBiomes` in `OVERWORLD` mode, not from this |

`mode` is marked required in the schema, but Gson supplies a default `IrisDimensionMode` when the field is absent, so an omitted `mode` runs `OVERWORLD`. The shipping overworld pack omits it. If the mode factory throws, the engine logs the failure once, warns that it is falling back, and builds `OVERWORLD` instead.

`IrisDimensionMode` is a snippet type (`dimension-mode`), so `"mode": "snippet/dimension-mode/overworld"` is also valid.

## Vertical layout: height, sea level, bedrock

Iris generates internally from `0` to `dimensionHeight.max - dimensionHeight.min`, then shifts the finished chunk down by `dimensionHeight.min` on output. Almost every Y number the engine handles internally is in that shifted space, but the numbers you write in the dimension JSON are not all in the same space, which is the most common source of confusion in this file:

- `dimensionHeight.min` / `dimensionHeight.max` are **world Y**.
- `fluidHeight` is **world Y**. `IrisDimension.getFluidHeight()` returns `fluidHeight - dimensionHeight.min`, which is what the engine uses internally, so sea level ends up back at the world Y you wrote. The shipping overworld sets `fluidHeight` 50 with `min` -256, and its ocean surface is at world Y 50.
- `caveLavaHeight` is **internal Y**. World Y = `caveLavaHeight + dimensionHeight.min`. The default 8 with a min of -64 puts the cave lava ceiling at world Y -56.
- Bedrock is written at internal Y 0, which is world Y `dimensionHeight.min`.

Biome generator heights are relative to sea level: a biome generator entry with `min` 4 / `max` 10 produces terrain 4 to 10 blocks above `fluidHeight`, clamped to the dimension's height range.

```json
{
  "dimensionHeight": { "min": -64, "max": 320 },
  "logicalHeight": 384,
  "fluidHeight": 63,
  "bedrock": true,
  "caveLavaHeight": 8
}
```

That fragment is a vanilla-shaped world: build floor at -64, ceiling at 320, sea at 63, bedrock at -64, cave lava below -56.

Minecraft imposes hard rules on the generated dimension type, and Iris fails when they are broken rather than clamping:

- `dimensionHeight.max - dimensionHeight.min` must be a multiple of 16, and between 16 and 4064.
- `dimensionHeight.min` must be a multiple of 16, and between -2032 and 2031.
- `logicalHeight` must be between 0 and the total height.

`/iris pack validate` does not check these — it only checks that structure placements fit inside the declared range. A bad height passes validation and then fails when Iris compiles the dimension type, at boot or at studio open. Check the arithmetic yourself before creating the world.

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `dimensionHeight` | `IrisRange` | `{ "min": -64, "max": 320 }` | The world's build floor and ceiling in world Y. Raise `max` for tall mountain packs, lower `min` for deep-cave packs. Contract field: pick it once per world |
| `logicalHeight` | int | `256` | The vanilla logical height of the generated dimension type — the ceiling gameplay teleports respect (nether portal search, chorus fruit). Usually set equal to the total height. Contract field |
| `fluidHeight` | int | `63` | World Y of the ocean surface, and the baseline every biome generator height is measured from. Lowering it makes the same biome generators produce relatively taller land; raising it drowns low biomes. Not a contract field, but changing it on a live world leaves a visible seam between old and new chunks |
| `bedrock` | boolean | `true` | Writes a bedrock layer at the build floor. Turn it off for void-bottom or stacked-dimension packs |
| `caveLavaHeight` | int | `8` | Internal Y at or below which carved cave space fills with lava instead of air. Raise it to flood deep caves, set it to 0 for dry caves. Explicit fluid intent from a carver overrides this |
| `name` | string | `"A Dimension"` | Display name shown by commands and the studio scoreboard. Cosmetic |
| `version` | int | `1` | A stamp you control. Iris does not act on it; it exists so pack updates can be recognized and so operators do not silently swap incompatible pack generations under an existing world |

## Environment and dimension-type options

`environment` picks which vanilla dimension template Iris starts from when it generates this dimension's type; `dimensionOptions` then overrides individual attributes of that template. Together they control sky, fog, ambient light, portal scale, whether beds work, whether raids can start, and the rest of the dimension-type surface. They do not change terrain.

```json
{
  "environment": "NETHER",
  "dimensionOptions": {
    "ultrawarm": "TRUE",
    "skylight": "FALSE",
    "ceiling": "TRUE",
    "coordinateScale": 8,
    "ambientLight": 0.1
  }
}
```

| `environment` | Base template |
|---------------|---------------|
| `NORMAL` | Overworld |
| `NETHER` | Nether |
| `THE_END` | End |
| `CUSTOM` | Overworld — the enum value exists but resolves through the same default branch as `NORMAL` |

`fullbright` is a shortcut: when true, Iris copies `dimensionOptions` and forces `ambientLight` to `1.0` before generating the type. On both supported Minecraft versions (26.1.2 and 26.2 share the same datapack fixer) a resolved ambient light of `1` also emits `minecraft:visual/ambient_light_color` `#ffffff`, so a fullbright world reads as flat white rather than merely bright.

Tri-state options are `DEFAULT`, `TRUE`, or `FALSE`; `DEFAULT` inherits from the base template. Numeric options use `-1` as "unset".

| `dimensionOptions` field | Default | Effect |
|--------------------------|---------|--------|
| `ultrawarm` | `DEFAULT` | Water evaporates, sponges dry, snow golems melt, lava spreads fast and thin, dripstone drips lava. Set `TRUE` for nether-like packs |
| `natural` | `DEFAULT` | When false, compasses spin and beds cannot set spawn or be slept in; when true, nether portals spawn zombified piglins and creaking hearts activate |
| `piglinSafe` | `DEFAULT` | When false, piglins and hoglins zombify here |
| `respawnAnchorWorks` | `DEFAULT` | When false, a respawn anchor explodes instead of setting spawn |
| `bedWorks` | `DEFAULT` | When false, beds explode instead of sleeping |
| `raids` | `DEFAULT` | Whether Bad Omen can start a raid here |
| `skylight` | `DEFAULT` | Whether the dimension receives sky light at all. `FALSE` makes a permanently dark world regardless of the terrain ceiling |
| `ceiling` | `DEFAULT` | Declares a logical bedrock ceiling to the client and to gameplay rules. Independent of whether terrain actually has a roof |
| `coordinateScale` | `-1` (unset) | Portal coordinate multiplier when travelling out of this dimension. `8` reproduces nether-style compression |
| `ambientLight` | `-1` (unset) | 0 to 1. Raises the light floor so caves are never fully black. A resolved `1` also forces white ambient light color |
| `fixedTime` | `-1` (unset) | Locks the time of day to this tick value. Leave unset for a normal day cycle |
| `cloudHeight` | `-1` (unset) | Y of the cloud layer, between -2032 and 2031. `null` disables clouds |
| `monsterSpawnBlockLightLimit` | `-1` (unset) | 0 to 15. Maximum block light at which hostile mobs may spawn |

## Regions and spatial layout

`regions` is the only mandatory content link in the file. Region selection happens first, per column: `regionStyle` picks the region, `continentalStyle` and `landChance` decide land versus sea inside it, then the land/sea/shore/cave biome styles pick a biome from that region's lists. The zoom fields scale the noise inputs — higher zoom means larger, slower-changing features.

```json
{
  "regions": ["temperate", "frozen", "tropical"],
  "landChance": 0.69,
  "regionZoom": 16.15,
  "biomeZoom": 1.0,
  "coordFractureDistance": 20,
  "coordFractureZoom": 8,
  "dimensionAngleDeg": 12
}
```

Tune this group in Studio with a fixed seed and compare the same coordinates between reloads. Every field here is safe to hotload.

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `regions` | string[] | empty | The region load keys this dimension may place. Required — an empty list produces a world with no biomes to select. Add a region here after creating its file, or the file never generates |
| `landChance` | double | `0.625` | Fraction of continental noise that becomes land. Push toward 1.0 for a continental world, toward 0.0 for an archipelago or ocean world |
| `regionZoom` | double | `1` | Scales region cells. Small values give many small climate patches; the shipping overworld uses `16.15` for continent-sized climate bands |
| `landZoom` | double | `1` | Scales the land-biome selection space independently of regions. Raise it to make each land biome patch bigger without changing where regions sit |
| `seaZoom` | double | `1` | Same, for ocean biomes |
| `continentZoom` | double | `1` | Scales the land/sea mask. Raise it for fewer, larger continents |
| `biomeZoom` | double | `1` | Global multiplier over every biome selection stream. The quickest way to make all biomes uniformly bigger or smaller |
| `regionStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Noise style that shapes region borders. Change it when region edges look too regular or too noisy |
| `continentalStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Noise style for the land/sea mask — this is what coastlines look like |
| `landBiomeStyle` / `seaBiomeStyle` / `shoreBiomeStyle` / `caveBiomeStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Per-category biome border shapes. Cellular styles give patchwork borders; simplex-family styles give organic blobs |
| `coordFractureDistance` | double | `20` | How far, in blocks, coordinate warping can displace a sample. This is what produces Iris's characteristic swirls. Set to 0 for straight, unwarped borders |
| `coordFractureZoom` | double | `8` | Frequency of that warping. Lower values warp more rapidly and more violently; the shipping overworld uses `0.15` |
| `dimensionAngleDeg` | double | `0` | Rotates every input coordinate by this angle. Breaks up axis-aligned artifacts. Pick something off 45 and 90 — the shipping overworld uses 69 |
| `focus` | string | `""` | Forces the whole world to one biome load key. Testing only; remove before packaging |
| `focusRegion` | string | `""` | Forces the whole world to one region load key. Testing only; remove before packaging |

## Rock, fluid, and overlay noise

`rockPalette` is the material the terrain column is filled with below the biome's own surface layers, and `fluidPalette` is what fills ocean columns and any cave aquifer that allows fluid. Both are full weighted palettes, so "stone" can be a blend of stone, andesite and tuff, and "water" can be lava, or a custom mod fluid.

```json
{
  "rockPalette": {
    "palette": [
      { "block": "minecraft:stone", "weight": 6 },
      { "block": "minecraft:andesite", "weight": 2 },
      { "block": "minecraft:tuff" }
    ]
  },
  "fluidPalette": { "palette": [{ "block": "minecraft:water" }] }
}
```

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `rockPalette` | `IrisMaterialPalette` | `stone` | Subsurface fill for every column that a biome layer does not claim. Change it for a themed world (deepslate planet, sandstone desert world). Biomes and regions can override it locally |
| `fluidPalette` | `IrisMaterialPalette` | `water` | Blocks used for ocean columns and for cave aquifers that allow fluid. Set it to lava for a magma world |
| `overlayNoise` | `IrisShapedGeneratorStyle[]` | empty | Extra height noise summed on top of the interpolated biome height, everywhere, ignoring biome boundaries. Use it for a global roughness or a world-wide swell that must not follow biome edges |
| `rockZoom` | double | `5` | Declared but never read by the engine. Leave it alone; palette scaling comes from the palette's own `zoom` |

## Ores and deposits

Iris has two independent ways to put ore in the ground, and they behave differently.

**Ores** (`ores`) are noise-threshold generators evaluated per block while terrain is written. Each generator declares its own Y `range` and a `generateSurface` flag; Iris keeps two separate lists and only consults the matching one, so a generator with `generateSurface: false` never appears in the exposed surface layer. Ores also exist at region and biome scope; all three scopes are consulted, dimension last.

**Deposits** (`deposits`) are blob placements written through the mantle, closer to vanilla ore veins, with per-chunk counts and sizes.

**Deposit variants** (`depositVariants`) rewrite the block that any of the above would have placed, inside a world-Y band. This is how the shipping pack turns `minecraft:iron_ore` into `minecraft:deepslate_iron_ore` below Y 0 without duplicating every generator.

```json
{
  "ores": [
    {
      "palette": { "palette": [{ "block": "minecraft:bedrock" }] },
      "chanceStyle": { "style": "FLAT" },
      "threshold": 1,
      "range": { "min": 0, "max": 0 }
    }
  ],
  "depositVariants": [
    {
      "minHeight": -64,
      "maxHeight": 0,
      "remap": { "minecraft:iron_ore": "minecraft:deepslate_iron_ore" }
    }
  ]
}
```

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `ores` | `IrisOreGenerator[]` | empty | Noise-driven ore placement across the whole dimension. Each entry has a palette, a `chanceStyle`, a `threshold`, a Y `range`, and `generateSurface`. Use dimension scope for ores that must exist everywhere regardless of biome |
| `deposits` | `IrisDepositGenerator[]` | empty | Blob deposits with per-chunk min/max counts and blob sizes. The shipping pack uses these for granite, andesite, diorite, gravel and the classic ores |
| `depositVariants` | `IrisDepositVariant[]` | empty | Source-to-replacement block remaps inside a world-Y band, applied after biome and region rules; the first matching dimension rule wins. Source matching ignores block properties |
| `hideOresForHiddenOre` | boolean | `false` | Replaces every ore the generator would write — terrain ores, deposits, and ores baked into objects — with the surrounding base material. Turn it on only when a drop-control plugin such as HiddenOre is supplying ores at break time instead |

## Caves and carving

Three fields at dimension scope decide whether caves exist and what they look like.

`caveProfile` is the 3D cave configuration. The same object exists on regions and biomes, and the most specific enabled profile wins: dimension, then region, then surface biome, then cave biome. So a dimension-level profile is the default cave system and a region can replace it wholesale for its own climate.

`carving` maps absolute world-Y bands to cave biomes, which is how the shipping pack puts a deep-dark biome between Y -250 and -175 without touching surface biome selection. Entries can nest through `children` for patchy sub-regions, bounded by `childRecursionDepth`.

`carvingEnabled: false` is implemented by adding the `CARVED` mantle flag to the disabled set — it is exactly equivalent to listing `CARVED` in `disabledComponents`.

```json
{
  "carvingEnabled": true,
  "caveProfile": { "enabled": true },
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

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `carvingEnabled` | boolean | `true` | Master switch for carving. Setting it false disables the `CARVED` mantle component, so no cave, canyon or carver output is written anywhere. Use it to isolate a terrain problem from a cave problem |
| `caveProfile` | `IrisCaveProfile` | disabled default object | The dimension's default 3D cave system: density styles, vertical range, threshold, surface clearance. Regions and biomes override it when they enable their own. Full field reference in `15 - Caves & Carving.md` |
| `carving` | `IrisDimensionCarvingEntry[]` | empty | Cave-biome overrides keyed to absolute world-Y bands, each with a stable `id`, a `biome`, a `worldYRange`, optional `children`, `childStyle`, `childShrinkFactor`, and `childRecursionDepth`. Use it for depth-banded cave themes such as a deep dark layer |
| `useMantle` | boolean | `true` | Disables the entire mantle when false: no objects, jigsaw structures, features, entities or deferred block updates. Terrain and decoration still run. Only useful for isolating mantle cost or debugging |
| `disabledComponents` | mantle flag strings | empty | Turns off individual mantle components by flag. The registered components are `OBJECT`, `JIGSAW`, `CARVED`, and `FLOATING_OBJECT`. Cheaper than `useMantle: false` when you only need to silence one subsystem |

## Objects, decoration, and post-processing

These fields gate the passes that run after terrain and carving. All of them hotload.

```json
{
  "decorate": true,
  "postProcessing": true,
  "postProcessingSlabs": true,
  "postProcessingWalls": true,
  "requireObjectSurfaceSupport": true,
  "objectSurfaceSupportBuffer": 2,
  "preventLeafDecay": false
}
```

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `decorate` | boolean | `true` | Runs the decorator pass (grass, flowers, seafloor clutter, ceiling growths). Turn it off to look at bare terrain shape |
| `postProcessing` | boolean | `true` | Master switch for the post pass that smooths block-level artifacts |
| `postProcessingSlabs` | boolean | `true` | Lets the post pass place slabs to soften one-block terrain steps |
| `postProcessingWalls` | boolean | `true` | Lets the post pass paint wall blocks against exposed faces |
| `requireObjectSurfaceSupport` | boolean | `true` | Refuses to place surface objects and trees that would hang over a carved opening. Turning it off produces floating buildings above caves; the per-placement flag can only opt out further, never override this on |
| `objectSurfaceSupportBuffer` | int | `2` | Minimum solid blocks required beneath a surface placement, 0 to 16. The effective value is the larger of this and the placement's own buffer, so raising it hardens every placement in the dimension at once |
| `preventLeafDecay` | boolean | `false` | Marks generated leaves persistent so they never decay when the supporting log is removed. Turn it on for packs whose custom trees have unusual leaf-to-log distances |
| `treeSettings` | `IrisTreeSettings` | disabled default | Overrides vanilla sapling growth with pack objects. See `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md` |

## Structures and datapacks

Dimension-level `structures` entries are Iris placements considered everywhere in the dimension, independent of any biome or region placement. `importedStructures` and `importedFeatures` control what vanilla, mod, and ingested-datapack content is allowed to generate on top of Iris terrain.

`anchor` is what makes a placement's vertical contract explicit. `LEGACY` preserves the historical `underground` boolean; the named anchors do not depend on it. Cave anchors read Iris carved-space data, so they apply only to editable `structures` and never to the `nativeStructures` backend.

```json
{
  "structures": [
    {
      "placementId": "ancient-city-native",
      "nativeStructures": [{ "structure": "minecraft:ancient_city" }],
      "nativeSuppression": "REPLACE_SOURCE",
      "minHeight": -220,
      "maxHeight": -220
    }
  ],
  "importedStructures": {
    "datapackOverrides": true,
    "disabled": [],
    "adjustments": [
      { "match": ["minecraft:mineshaft"], "preserveSourceY": true }
    ]
  }
}
```

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `structures` | `IrisStructurePlacement[]` | empty | Iris structure placements at dimension scope. Use this for content that must exist regardless of biome, such as a global stronghold analogue or a native structure you are re-anchoring |
| `importedStructures` | `IrisImportedStructureControl` | default | Allow/deny and Y-adjustment rules for every registered native structure. Every registered structure generates by default; `disabled` is the only deny list. `adjustments` can shift, band, encase, or stilt a structure into Iris terrain |
| `importedFeatures` | `IrisImportedFeatureControl` | disabled | Off by default: leaving it out generates exactly the terrain Iris always has. Setting `enabled` true runs the vanilla placed-feature decoration pass (ores, trees, plants, springs, geodes) over Iris terrain, filterable by `disabled` keys, `steps`, and `disabledSteps`. Carvers are never imported |
| `datapackImports` | string[] | empty | External datapack URLs this dimension owns. Their structure sets and definitions generate and locate only in dimensions that declare the same source. Replacing native generation still requires a placement with `nativeSuppression: REPLACE_SOURCE`; declaring the source alone never disables anything |

Anchor values for editable placements: `LEGACY`, `SURFACE`, `HEIGHT_BAND`, `CAVE_FLOOR`, `CAVE_CEILING`, `CAVE_CENTER`, `CAVE_ANY`. Details in `18 - Structures Overview.md`, `21 - Jigsaw Structures.md`, and `22 - Native Structures & Datapacks.md`.

## Upper dimension (inverted ceiling terrain)

Set `upperDimension` to another dimension's load key (or this dimension's own key) and Iris generates that dimension's terrain upside-down against the world ceiling, nether-style. `"none"` or an empty string disables it; the shipping overworld ships with `""`.

```json
{
  "upperDimension": "overworld",
  "upperDimensionGap": 32,
  "upperDimensionCarving": false,
  "upperDimensionObjects": false
}
```

If the referenced key cannot be loaded, Iris warns and skips upper terrain rather than failing the world.

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `upperDimension` | string | `"none"` | Load key of the dimension whose terrain becomes the ceiling. Self-reference is allowed and produces a mirrored world |
| `upperDimensionGap` | int | `32` | Minimum air blocks kept between the lower surface and the upper surface, 0 to 256. Raise it if the two halves close up in high terrain |
| `upperDimensionCarving` | boolean | `false` | Lets cave carving cut through the ceiling mass. False leaves it solid |
| `upperDimensionObjects` | boolean | `false` | Lets mantle objects place in the upper zone. False protects the ceiling from trees and structures |
| `upperObjectsForcePlace` | boolean | `false` | Upper objects ignore slope, underwater, clamp, collision, and carving restrictions. Lower-dimension objects always place first, so enabling this lets upper objects clip through them |

## Loot, spawners, and block drops

These are dimension-wide fallbacks. Regions and biomes layer on top of them.

```json
{
  "loot": { "mode": "FALLBACK", "tables": ["global-clutter"] },
  "entitySpawners": ["swamp/passive"],
  "blockDrops": []
}
```

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `loot` | `IrisLootReference` | empty, mode `ADD`, multiplier `1` | Loot tables applied to containers generated anywhere in the dimension. `ADD` stacks this scope's tables onto the parent list; `CLEAR` and `REPLACE` both drop the parent tables first; `FALLBACK` supplies tables only when the object itself declared none. `multiplier` scales item counts 0 to 16. Use dimension scope for global clutter, region scope for climate flavor |
| `entitySpawners` | string[] | empty | `IrisSpawner` load keys that continually replenish mobs like vanilla does. Dimension scope means "everywhere in this world" |
| `blockDrops` | `IrisBlockDrops[]` | empty | Custom drop overrides for specific blocks in this dimension |

## Studio and debug fields

These exist to help you look at the generator, not to ship. `studioMode` is applied only by the Bukkit chunk generator; on Fabric, Forge and NeoForge the field is ignored.

| Field | Type | Default | What it does and when to change it |
|-------|------|---------|------------------------------------|
| `studioMode` | `StudioMode` | `NORMAL` | Swaps in a debug generator. `BIOME_BUFFET_1x1`, `_3x3`, `_5x5`, `_9x9`, `_18x18`, `_36x36` lay every biome out in a grid of that cell size; `OBJECT_BUFFET` lays out objects. `REGION_BUFFET` currently installs no generator and behaves exactly like `NORMAL`. Remove before packaging |
| `debugChunkCrossSections` | boolean | `false` | Deletes whole chunks on a grid so you can walk up and read the terrain column like a diagram |
| `debugCrossSectionsMod` | int | `3` | The X/Z modulus that decides which chunks get cut, 2 to 16. Larger values cut fewer chunks |
| `explodeBiomePalettes` | boolean | `false` | Inserts air gaps between palette layers so you can count and identify them visually |
| `explodeBiomePaletteSize` | int | `3` | Size of those gaps, 1 to 16 |
| `debugSmartBore` | boolean | `false` | Fills the air volume objects carve for themselves with cobweb, making object footprints visible |
| `disableExplorerMaps` | boolean | `false` | Declared but never read by the engine today |
| `forceConvertTo320Height` | boolean | `false` | Declared but never read by the engine today |

## Annotations are editor hints, not runtime validation

`@Required`, `@MinNumber`, and `@MaxNumber` are consumed only by the schema generator. Nothing enforces them at load time, so a value outside the documented range loads without complaint and produces whatever the engine does with it. The exceptions are the dimension-type constraints listed under vertical layout, which throw during dimension-type compilation. Treat the ranges in the tables above as design guidance backed by editor warnings, and verify unusual values in Studio.

## A complete minimal dimension

```json
{
  "name": "mypack",
  "version": 1,
  "mode": { "type": "OVERWORLD" },
  "regions": ["starter"],
  "environment": "NORMAL",
  "dimensionHeight": { "min": -64, "max": 320 },
  "logicalHeight": 384,
  "fluidHeight": 63
}
```

This is the studio starter with `mode`, `environment` and `fluidHeight` written out. It needs `regions/starter.json`, a biome, and a generator to actually produce terrain; the four-file walkthrough is in `26 - Example - Minimal Dimension.md`.

## What the shipping overworld sets

Path: `packs/overworld/dimensions/overworld.json` under the platform data directory (the same file ships in the Fabric, Forge and NeoForge run configs).

| Field | Overworld value | Why it is interesting |
|-------|-----------------|-----------------------|
| `name` / `version` | `"Overworld"` / `4000` | Large version stamp so pack generations are distinguishable |
| `environment` | `"NORMAL"` | Overworld base template |
| `dimensionHeight` | `{ "min": -256, "max": 512 }` | 768 blocks tall, both bounds multiples of 16 |
| `logicalHeight` | `512` | Below the 768 total, so it is a legal contract |
| `fluidHeight` | `50` | Sea level at world Y 50, well below the vanilla 63 — biome generator values are measured from here |
| `landChance` | `0.69` | Land-heavy world |
| `regionZoom` | `16.15` | Continent-sized climate regions |
| `coordFractureZoom` | `0.15` | Aggressive coordinate warping — this is where the swirl comes from |
| `dimensionAngleDeg` | `69` | Off-axis rotation to break up straight borders |
| `regions` | `frozen`, `hot`, `terralost`, `mushroom`, `forests`, `tundra`, `magnetics`, `temperate`, `estranged`, `tropical`, `swamp`, `prismatics` | Twelve climate regions |
| `mode` | omitted | Runs `OVERWORLD` by default |
| `preventLeafDecay` | `true` | Custom trees keep their canopies |
| `caveProfile` | `enabled: true` | 3D caves on by default, overridden per region |
| `carving` | one deep-dark band, world Y -250 to -175 | Depth-banded cave biome |
| `ores` / `deposits` / `depositVariants` | 11 / 20 / 1 | Bedrock and band generators, classic ore blobs, one deepslate remap below Y 0 |
| `importedStructures` | adjustments for stronghold, trial chambers, mineshaft, village | Native structures re-fitted to Iris terrain |
| `structures` | ancient city with `nativeSuppression: REPLACE_SOURCE` | Vanilla placement replaced by an Iris-anchored one |

## Build a dimension, step by step

1. Create the pack: `/iris studio create name=mypack` on Bukkit writes a four-file starter skeleton; `/iris studio create mypack` on a mod loader copies the `example` template instead. Either way you get a loadable pack to edit.
2. Open `dimensions/mypack.json` and set the contract fields deliberately: `dimensionHeight`, `logicalHeight`, `fluidHeight`, `environment`, and `mode.type`. Check the multiple-of-16 rules now, not after the world exists.
3. Make sure every key in `regions` has a file under `regions/`, and that region has at least one land biome with at least one generator (`12 - Regions.md`, `13 - Biomes.md`).
4. Validate: `/iris pack validate pack=mypack` on Bukkit, `/iris pack validate mypack` on modded. Fix blocking errors before opening Studio — Studio refuses to open a pack whose validation is not loadable.
5. Open Studio on a fixed seed: `/iris studio open mypack seed=1337` on Bukkit, `/iris studio open mypack 1337` on modded.
6. Walk into fresh chunks and check the baseline: solid terrain, the build floor where you put it, fluid at the right Y, the expected biome from `/iris what biome`, no unresolved-key errors in console. Fix this before touching noise.
7. Isolate while tuning: set `"focusRegion": "starter"` or `"focus": "starter"`, save, and generate a new area. Remove both before packaging.
8. Tune land/sea and zoom, then add subsystems one at a time — caves, then ores and deposits, then objects, then structures — validating after each new resource edge so a broken key is attributable.
9. Close and reopen Studio after editing `dimensionHeight`, `logicalHeight`, `environment`, or the dimension file name. Hotload rejects those by design.
10. Create the production world only after Studio is clean: `/iris create mypack-test type=mypack seed=1337`. Recreate the world rather than editing its height contract later.

The baseline passes when Studio opens clean, validation reports no blocking errors, and the same seed reproduces the same terrain after a close and reopen.

## Common author mistakes

| Mistake | What actually happens |
|---------|-----------------------|
| Empty or unresolvable `regions` | No biome can be selected, so the dimension has nothing to place |
| Region file exists but is not listed in `regions` | It never generates; nothing warns you |
| Treating `fluidHeight` as an offset from the build floor | It is world Y. The engine converts it to internal Y by subtracting `dimensionHeight.min` |
| `dimensionHeight` span or `min` not a multiple of 16 | Passes `pack validate`, then fails when Iris compiles the dimension type |
| `logicalHeight` greater than `max - min` | Rejected when the dimension type is constructed |
| Editing height, logical height, environment, or the dimension file name mid-Studio | Hotload is refused by the runtime contract; close and reopen |
| Expecting decoration or caves from `SUPERFLAT`, `ENCLOSURE`, or `ISLANDS` | Those modes register only terrain and biome stages |
| Leaving `focus` or `focusRegion` set when packaging | The shipped pack generates exactly one biome or region |
| Changing pack files and expecting an existing world to change | Production worlds run from `<world>/iris/pack/`; see `27 - Example - Configuring Overworld.md` |

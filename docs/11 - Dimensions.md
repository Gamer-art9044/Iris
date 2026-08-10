# 11 - Dimensions

A dimension is the root pack object for a world type. File location is `dimensions/<loadKey>.json` (folder name from `IrisDimension.getFolderName()`). Regions, height, fluid, noise zooms, ores, caves, structures, and engine mode all hang off this object.

Related: see `05 - Concepts & Pack Layout.md`, `10 - Studio & VSCode Schemas.md`, `12 - Regions.md`, `14 - Generators & Noise.md`, `15 - Caves & Carving.md`, `22 - Native Structures & Datapacks.md`, `26 - Example - Minimal Dimension.md`.

## Tutorial outcome

Build the smallest dimension graph first: one dimension, one region, one biome, and one generator. Open that graph in Studio and confirm solid terrain before enabling caves, structures, deposits, or external datapacks. The complete four-file example is in `26 - Example - Minimal Dimension.md`; the field reference below explains how to extend it.

Dimension height and environment are world contracts, not ordinary visual tuning. Decide them before creating a production world and use a disposable Studio world for revisions.

### Prerequisites and file placement

Start with a writable pack directory and operator or gamemaster access. Place the root object at `plugins/Iris/packs/mypack/dimensions/mypack.json` on Bukkit, or `config/irisworldgen/packs/mypack/dimensions/mypack.json` on a mod loader. The minimal complete graph also needs `regions/starter.json`, `biomes/starter.json`, and `generators/flat.json`; copy the exact four files from `26 - Example - Minimal Dimension.md` before loading the pack.

### Build and verify

1. Set the dimension file to the minimal JSON under **Minimal Dimension JSON** below. Keep its dimension load key (`mypack`) equal to the file name.
2. Create the three referenced resources from the minimal walkthrough and validate: Bukkit `/iris pack validate pack=mypack`; modded `/iris pack validate mypack`.
3. Open the live authoring pack with `/iris studio open mypack seed=1337` on Bukkit or `/iris studio open mypack 1337` on modded.
4. Generate new chunks and inspect the build floor, terrain top, fluid level, sky, and biome. A successful baseline has solid terrain, the `starter` biome, and no unresolved resource keys in validation or the console.
5. If the Studio world is empty, confirm all four file paths and exact keys before changing noise. If height or environment changes are rejected, close and reopen Studio; those values are bound to the running dimension contract.

## Role in the Pack Graph

```
Dimension → regions[] → Region → land/sea/shore/cave biomes[] → Biome
         → generators (via biomes)
         → objects / structures / ores / deposits / caves
```

World create/open selects a pack dimension load key. Studio and production both load `IrisDimension` from that file.

## Load Key and File Name

| Rule | Detail |
|------|--------|
| Load key | Path relative to `dimensions/` without `.json` |
| Typical | `dimensions/overworld.json` → key `overworld` |
| Nested | `dimensions/foo/bar.json` → key `foo/bar` if used (shipping overworld uses a single top-level file matching the pack name) |
| Starter create | Writes `dimensions/<projectName>.json` with matching `name` |

## Engine Modes (`mode`)

Object type: `IrisDimensionMode` (`@Snippet("dimension-mode")`).

| JSON path | Type | Default | Meaning |
|-----------|------|---------|---------|
| `mode.type` | `IrisDimensionModeType` | `OVERWORLD` | Selects the engine mode factory |

Enum `IrisDimensionModeType`:

| Type | Annotation summary | Runtime stages (code) |
|------|--------------------|------------------------|
| `OVERWORLD` | Full biome world with fluid height | Biomes, mantle matter, terrain, carving, post, floating child biomes, deposits, matter insert, decoration, perfection, custom modifiers (`ModeOverworld`) |
| `SUPERFLAT` | Ultra fast; terrain & biomes only | Terrain + biome actuators only (`ModeSuperFlat`); no decoration/mantle/carve/deposit stages registered |
| `ENCLOSURE` | Ceiling & floor carved style (nether-like intent) | Same stage registration as SuperFlat today (`ModeEnclosure`): terrain + biome only |
| `ISLANDS` | Floating islands intent | Same stage registration as SuperFlat today (`ModeIslands`): terrain + biome only |

JSON shape:

```json
{
"mode": {
  "type": "OVERWORLD"
}
}
```

The field is annotated `@Required`. Gson still applies the default `IrisDimensionMode` when omitted. The shipping overworld pack does not set `mode` and therefore runs as `OVERWORLD`.

If mode construction fails, the engine logs a warning and falls back to `OVERWORLD`.

## Height, Fluid, and Environment

| Field | Type | Default | Constraints / notes |
|-------|------|---------|---------------------|
| `name` | string | `"A Dimension"` | Required human-readable name (`@MinNumber(2)` on length via annotation min) |
| `version` | int | `1` | Bump to discourage accidental pack upgrades on existing worlds |
| `logicalHeight` | int | `256` | 1–2032; player teleport height budget; part of hotload contract |
| `dimensionHeight` | `IrisRange` | min `-64`, max `320` | World min/max Y. Iris generates internal height `max - min`, then shifts by min on output |
| `fluidHeight` | int | `63` | Required; 0–1024. Fluid column top in **internal** Y (0 = bottom of dimension height). World Y ≈ `fluidHeight + dimensionHeight.min` |
| `environment` | `IrisEnvironment` | `NORMAL` | `NORMAL`, `NETHER`, `THE_END`, `CUSTOM` — selects base datapack dimension template (overworld/nether/end) |
| `fullbright` | boolean | `false` | Forces maximum ambient lighting when true |
| `bedrock` | boolean | `true` | Places bedrock at internal Y 0 when true |
| `caveLavaHeight` | int | `8` | Subterrain fluid layer height (0–318) |

### Environment enum

| Value | Base dimension type mapping |
|-------|-----------------------------|
| `NORMAL` | Overworld-style |
| `NETHER` | Nether-style |
| `THE_END` | End-style |
| `CUSTOM` | Treated as overworld base in `getBaseDimension()` default branch |

### Height contract (hotload / world bind)

`IrisDimensionRuntimeContract` locks: namespaced type key, min height, total height (`max - min`), and `logicalHeight`. Changing these on a running world requires restart, not hotload.

## Regions and Spatial Zoom

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `regions` | string[] | empty | **Required.** Region load keys under `regions/` |
| `regionStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Region placement noise |
| `continentalStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Land/sea placement |
| `landBiomeStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Land biome placement |
| `shoreBiomeStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Shore biome placement |
| `seaBiomeStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Sea biome placement |
| `caveBiomeStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_DOUBLE` | Cave biome placement |
| `landChance` | double | `0.625` | 0–1 land vs sea chance |
| `regionZoom` | double | `1` | Region size multiplier |
| `landZoom` | double | `1` | Land space zoom |
| `seaZoom` | double | `1` | Ocean biome zoom |
| `continentZoom` | double | `1` | Continent zoom |
| `biomeZoom` | double | `1` | Global biome size (higher = bigger) |
| `coordFractureDistance` | double | `20` | Coordinate warping distance (blocks) |
| `coordFractureZoom` | double | `8` | Coordinate warping frequency |
| `dimensionAngleDeg` | double | `0` | Rotate entire dimension input coordinates (degrees) |
| `focus` | string | `""` | Force a single biome load key (testing) |
| `focusRegion` | string | `""` | Force a single region load key (testing) |

## Materials, Ores, Deposits

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `rockPalette` | `IrisMaterialPalette` | stone | Subsurface “stone” fill palette |
| `fluidPalette` | `IrisMaterialPalette` | water | Fluid block palette |
| `rockZoom` | double | `5` | Rock palette noise zoom |
| `ores` | `IrisOreGenerator[]` | empty | Dimension-wide ore generators (surface vs underground via generator flags) |
| `deposits` | `IrisDepositGenerator[]` | empty | Global deposit blobs |
| `depositVariants` | `IrisDepositVariant[]` | empty | Source→replacement ore remaps; applied after biome/region rules |
| `overlayNoise` | `IrisShapedGeneratorStyle[]` | empty | Extra height overlay noise |
| `hideOresForHiddenOre` | boolean | `false` | Replace ore placements with base rock for drop-control plugins |

## Caves, Carving, Mantle, Decoration

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `carvingEnabled` | boolean | `true` | Master carving toggle |
| `carving` | `IrisDimensionCarvingEntry[]` | empty | Absolute-Y cave biome carving overrides |
| `caveProfile` | `IrisCaveProfile` | default object | Profile-driven 3D caves (see `15 - Caves & Carving.md`) |
| `requireObjectSurfaceSupport` | boolean | `true` | Refuse surface objects over carved openings |
| `objectSurfaceSupportBuffer` | int | `2` | Min surface-support buffer (0–16) |
| `useMantle` | boolean | `true` | Objects, entities, features, updates |
| `decorate` | boolean | `true` | Decorators |
| `postProcessing` | boolean | `true` | Post-process pass |
| `postProcessingSlabs` | boolean | `true` | Slab painting |
| `postProcessingWalls` | boolean | `true` | Wall painting |
| `preventLeafDecay` | boolean | `true`/`false` default `false` | Creative-like leaf persistence |
| `treeSettings` | `IrisTreeSettings` | default | Tree growth overrides |
| `disabledComponents` | mantle flag strings | empty | Disable mantle components by flag |

## Upper Dimension (canopy)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `upperDimension` | string | `"none"` | Dimension load key for inverted ceiling terrain; `"none"` or empty disables |
| `upperDimensionGap` | int | `32` | Min air gap between lower and upper surfaces (0–256) |
| `upperDimensionCarving` | boolean | `false` | Allow carving through upper terrain |
| `upperDimensionObjects` | boolean | `false` | Allow mantle objects in upper zone |
| `upperObjectsForcePlace` | boolean | `false` | Force-place upper objects ignoring normal restrictions |

## Structures, Features, Datapacks

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `structures` | `IrisStructurePlacement[]` | empty | Dimension-level Iris/native structure placements; editable Iris structures support surface, height-band, and cave anchors |
| `importedStructures` | `IrisImportedStructureControl` | default | Vanilla/mod/datapack structure allow/deny and adjustments |
| `importedFeatures` | `IrisImportedFeatureControl` | default off | Optional vanilla feature decoration pass |
| `datapackImports` | string[] | empty | External datapack URLs owned by this dimension; managed structures generate and locate only in dimensions declaring the source |

Structure placement and native control details: see `18 - Structures Overview.md`, `22 - Native Structures & Datapacks.md`.

Dimension-level placements are considered throughout the dimension. `anchor: LEGACY` preserves the historical `underground` switch; explicit `SURFACE`, `HEIGHT_BAND`, `CAVE_FLOOR`, `CAVE_CEILING`, `CAVE_CENTER`, and `CAVE_ANY` make the vertical contract unambiguous. Cave anchors use Iris carved-space data and apply only to editable `structures`, not the `nativeStructures` backend; see `15 - Caves & Carving.md` and `21 - Jigsaw Structures.md`.

## Loot, Spawns, Drops, Studio Debug

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `loot` | `IrisLootReference` | empty | Dimension loot tables |
| `entitySpawners` | string[] | empty | `IrisSpawner` load keys |
| `blockDrops` | `IrisBlockDrops[]` | empty | Custom block drops |
| `studioMode` | `StudioMode` | `NORMAL` | Studio-only generator overrides |
| `debugChunkCrossSections` | boolean | `false` | Cut chunks for cross-section viewing |
| `debugCrossSectionsMod` | int | `3` | X/Z modulus for cross-section cuts |
| `explodeBiomePalettes` | boolean | `false` | Vertical palette visualization |
| `explodeBiomePaletteSize` | int | `3` | Palette explosion spacing |
| `debugSmartBore` | boolean | `false` | Fill object voids with cobweb |
| `forceConvertTo320Height` | boolean | `false` | Height conversion flag |
| `disableExplorerMaps` | boolean | `false` | Disable cartographer explorer maps |
| `dimensionOptions` | `IrisDimensionTypeOptions` | defaults | Minecraft dimension-type datapack options (ultrawarm, natural, skylight, coordinate scale, …) |

### `dimensionOptions` fields (`IrisDimensionTypeOptions`)

Tri-state fields use `DEFAULT` | `TRUE` | `FALSE` (follow base dimension when `DEFAULT`):

| Field | Default | Role |
|-------|---------|------|
| `ultrawarm` | `DEFAULT` | Nether-like water/lava behavior |
| `natural` | `DEFAULT` | Beds/compasses/portal piglins |
| `piglinSafe` | `DEFAULT` | Piglin zombification |
| `respawnAnchorWorks` | `DEFAULT` | Respawn anchor |
| `bedWorks` | `DEFAULT` | Beds |
| `raids` | `DEFAULT` | Bad Omen raids |
| `skylight` | `DEFAULT` | Has skylight |
| `ceiling` | `DEFAULT` | Logical bedrock ceiling |
| `coordinateScale` | `-1` (unset) | Portal scale |
| `ambientLight` | `-1` (unset) | 0–1 ambient |
| `fixedTime` | `-1` sentinel | Fixed day time when set |
| `cloudHeight` | `-1` sentinel | Cloud Y or null to disable |
| `monsterSpawnBlockLightLimit` | `-1` (unset) | 0–15 |

## Overworld Pack Sample

Path: `adapters/fabric/run/config/irisworldgen/packs/overworld/dimensions/overworld.json` (also present under forge/neoforge run configs).

Selected values from that file:

| Field | Overworld value |
|-------|-----------------|
| `name` | `"Overworld"` |
| `version` | `4000` |
| `environment` | `"NORMAL"` |
| `fluidHeight` | `50` |
| `logicalHeight` | `512` |
| `dimensionHeight` | `{ "min": -256, "max": 512 }` |
| `landChance` | `0.69` |
| `regionZoom` | `16.15` |
| `dimensionAngleDeg` | `69` |
| `regions` | `frozen`, `hot`, `terralost`, `mushroom`, `forests`, `tundra`, `magnetics`, `temperate`, `estranged`, `tropical`, `swamp`, `prismatics` |
| `mode` | omitted → `OVERWORLD` |
| `ores` | bedrock + deepslate band generators |
| `deposits` | granite/andesite/diorite/gravel + ore deposits |
| `importedStructures` | adjustments for stronghold, trial chambers, mineshaft, villages |
| `structures` | e.g. ancient city native placement with `REPLACE_SOURCE` |
| `caveProfile` | enabled 3D cave profile |

## Minimal Dimension JSON

Matches studio starter plus an explicit mode (recommended):

```json
{
  "name": "mypack",
  "version": 1,
  "mode": { "type": "OVERWORLD" },
  "regions": ["starter"],
  "fluidHeight": 63,
  "logicalHeight": 384,
  "dimensionHeight": { "min": -64, "max": 320 },
  "environment": "NORMAL"
}
```

## How To: Make a Dimension

1. Create a starter pack with `/iris studio create name=mypack` or copy a known-good template.
2. Open `dimensions/mypack.json`. Set `name`, `regions`, `dimensionHeight`, `logicalHeight`, `fluidHeight`, `environment`, and `mode.type` explicitly.
3. Create every referenced region under `regions/`; start with one region and one land biome (`12 - Regions.md`).
4. Validate the pack, then open Studio with `/iris studio open mypack seed=1337`.
5. Confirm the build floor, fluid level, sky/environment behavior, and solid terrain in newly generated chunks. Fix this baseline before changing zoom or adding content.
6. Set `"focusRegion": "starter"` or `"focus": "starter"` while isolating one region or biome. Remove the focus field before packaging.
7. Tune land/sea styles and zooms. Add caves, ores, deposits, and structures one subsystem at a time, validating after each new resource edge.
8. Close and reopen Studio after changing height, logical height, environment-derived dimension type, or another runtime-contract field.
9. Create a permanent world only after the Studio baseline and pack validation pass (`06 - Worlds & Lifecycle.md`). Recreate the production world rather than changing its dimension type or height contract in place.

Success means a clean Studio open, no unresolved region/biome/generator keys, and repeatable terrain on seed `1337` after reopening the project.

## Common Author Mistakes

| Mistake | Result |
|---------|--------|
| Empty `regions` | Dimension cannot place content |
| Region keys that do not resolve | Missing regions at runtime |
| Changing `dimensionHeight` / `logicalHeight` mid-studio | Hotload rejected; restart studio |
| Expecting SuperFlat/Islands/Enclosure decoration | Those modes currently register only terrain+biome stages |
| Treating `fluidHeight` as world Y | It is internal Y; world Y = fluidHeight + min height |
| Forgetting to reference regions listed in dimension | Orphan region files never spawn |

# 12 - Regions

A region is a mid-level spatial unit inside a dimension. File location is `regions/<loadKey>.json`. Each region lists root biomes for land, sea, shore, and optional cave roles, plus regional rarity, zooms, shores, ores, objects, and caves.

Related: see `05 - Concepts & Pack Layout.md`, `11 - Dimensions.md`, `13 - Biomes.md`, `16 - Surfaces, Decorators & Deposits.md`, `20 - Object Placement.md`, `15 - Caves & Carving.md`.

## Tutorial outcome

Add one region to an already working dimension and make one biome fill that region. Keep sea, shore, cave, object, and structure lists empty until the land path resolves; this separates region selection problems from content-placement problems.

### Prerequisites and file placement

Use a pack whose dimension and one biome already validate. Create `regions/tutorial.json` with the complete **Minimal Region JSON** below, replace `starter` with the exact load key of the existing biome, and add `"tutorial"` to the dimension's `regions` array.

### Build and verify

1. Keep `rarity` at `1` and put one root biome in `landBiomes`; do not list a child biome here.
2. Temporarily set `"focusRegion": "tutorial"` on the dimension.
3. Validate the pack, then open Studio on seed `1337` using the platform command in `10 - Studio & VSCode Schemas.md`.
4. Generate new chunks and run `/iris what region` while standing in them. Success is a consistent `tutorial` region whose biome resolves without warnings.
5. If another region appears, verify the dimension reference and the `focusRegion` spelling. If terrain is missing, verify the biome key and its generator; region rarity and zoom cannot repair a broken resource edge.
6. Remove `focusRegion`, reopen Studio, and sample new chunks before adding sea, shore, or cave lists.

## Role

Dimensions pick regions by noise (`regionStyle` / `regionZoom` / region `rarity`). Within a region, land/sea/shore/cave biome lists pick biomes (also rarity-weighted). Child biomes are **not** listed on the region; only root parents go in the region arrays. Children are declared on the parent biome (`children` field).

Inferred surface roles (`InferredType`): `LAND`, `SEA`, `SHORE`, `CAVE`.

## Load Key

| Rule | Detail |
|------|--------|
| Folder | `regions/` |
| Key | Path relative to `regions/` without `.json` |
| Shipping overworld | Flat files: `temperate.json` → key `temperate` |
| Dimension reference | Dimension `regions` array uses those keys |

## Field Reference (`IrisRegion`)

### Identity and rarity

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `name` | string | `"A Region"` | Required display name |
| `rarity` | int | `1` | 1–128; higher = rarer when competing among dimension regions |
| `color` | string | `null` | Map visualization color, e.g. `#9BEE61` |

### Biome lists

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `landBiomes` | string[] | **Yes** | Root land biome load keys |
| `seaBiomes` | string[] | No | Root sea biomes; empty allowed for land-only worlds |
| `shoreBiomes` | string[] | No | Root shore biomes; empty allowed for land-only worlds |
| `caveBiomes` | string[] | No (array type allows empty) | Root cave biomes for carving/cave selection |

Keys are biome load keys under `biomes/` (e.g. `temperate/plains`, `carving/drip`).

### Biome and shore zooms

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `landBiomeZoom` | double | `1` | Land biome size in this region |
| `shoreBiomeZoom` | double | `1` | Shore biome size |
| `seaBiomeZoom` | double | `1` | Sea biome size |
| `caveBiomeZoom` | double | `1` | Cave biome size |
| `shoreHeightMin` | double | `1.2` | Min shore height contribution |
| `shoreHeightMax` | double | `3.2` | Max shore height contribution |
| `shoreHeightZoom` | double | `3.14` | Shore height noise zoom |

### Rivers and lakes (style)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `riverStyle` | `IrisGeneratorStyle` | `VASCULAR_THIN` zoomed `7.77` | River placement style |
| `lakeStyle` | `IrisGeneratorStyle` | `CELLULAR_IRIS_THICK` | Lake placement style |

### Content attachments

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `objects` | `IrisObjectPlacement[]` | empty | Region-wide `.iob` placements |
| `proceduralObjects` | `IrisProceduralObjects` | empty | Trees/ruins/formations/coral/fungi/crystals generated procedurally |
| `structures` | `IrisStructurePlacement[]` | empty | Region-scoped jigsaw/native placements; editable Iris structures may use explicit cave anchors |
| `entitySpawners` | string[] | empty | `IrisSpawner` load keys |
| `effects` | `IrisEffect[]` | empty | Packet ambient effects (potions, sounds, particles) |
| `loot` | `IrisLootReference` | empty | Region loot |
| `blockDrops` | `IrisBlockDrops[]` | empty | Custom drops |
| `deposits` | `IrisDepositGenerator[]` | empty | Regional deposits added to global |
| `depositVariants` | `IrisDepositVariant[]` | empty | Ore remaps after biome, before dimension |
| `ores` | `IrisOreGenerator[]` | empty | Regional ores (surface vs underground flags) |
| `caveProfile` | `IrisCaveProfile` | default | Region cave profile |

Deposit precedence (documented on fields): biome variants → region variants → dimension variants; first match wins at each tier.

Region `structures[]` is evaluated where that region owns the start chunk center. A cave anchor searches existing carved-space mantle data inside that chunk and can further restrict the actual anchor with `caveBiomes`; it does not require the placement to be duplicated on every cave biome. See `21 - Jigsaw Structures.md` for distribution and anchor fields.

## Overworld Sample: Temperate

Path: `…/packs/overworld/regions/temperate.json`

| Field | Value |
|-------|-------|
| `name` | `Temperate` |
| `color` | `#9BEE61` |
| `rarity` | `1` |
| `landBiomes` | Many temperate + mountain + vanilla roots (e.g. `temperate/plains`, `vanilla/cherry_grove`) |
| `shoreBiomes` | Beaches including `vanilla/stony_shore` |
| `seaBiomes` | Oceans/rivers (`ocean/deep`, `temperate/sea/river`, …) |
| `caveBiomes` | `carving/rocky-cavebiome`, `carving/deep`, `carving/drip`, … |
| `landBiomeZoom` | `3.5` |
| `seaBiomeZoom` | `6` |
| `shoreBiomeZoom` | `0.15` |
| `caveBiomeZoom` | `3.3` |
| `shoreHeightMin` / `Max` / `Zoom` | `1` / `5.2` / `1.14` |
| `deposits` | Iron/coal band example |
| `loot` | `FALLBACK` mode, temperate tables |
| `caveProfile` | Enabled with density/threshold/surface settings |

Shipping overworld region keys (from dimension `regions` list): `frozen`, `hot`, `terralost`, `mushroom`, `forests`, `tundra`, `magnetics`, `temperate`, `estranged`, `tropical`, `swamp`, `prismatics`.

## Minimal Region JSON

```json
{
  "name": "Starter",
  "rarity": 1,
  "landBiomes": ["starter"],
  "seaBiomes": ["starter"],
  "shoreBiomes": ["starter"]
}
```

Land-only dimension (no ocean shoreline generated):

```json
{
  "name": "Highlands",
  "rarity": 2,
  "landBiomes": ["highlands/plateau"],
  "seaBiomes": [],
  "shoreBiomes": []
}
```

## How To: Make a Region

1. Create `regions/<key>.json` from the minimal region above.
2. Set `name`, keep `rarity: 1`, and list one existing **root** biome under `landBiomes`.
3. Add the region key to the dimension's `regions` array.
4. Set dimension `"focusRegion": "<key>"`, validate, and open Studio on seed `1337`.
5. Generate new chunks until the biome appears consistently. If it does not, verify the exact biome file path before tuning rarity or noise.
6. Add sea and shore biomes together, then cave biomes, validating each path separately.
7. Remove `focusRegion`; add a second region and only then tune rarity and region zoom while sampling broad new areas.
8. Add regional deposits, ores, objects, structures, and cave profiles after selection is proven.

The tutorial passes when the focused region generates, the unfocused dimension selects it among peers, and validation reports no missing biome keys.

## Resolution Notes

- `getAllBiomeIds()` unions land, cave, sea, and shore lists.
- Child expansion walks each biome’s `children` and `carvingBiome` through the pack loader (cyclic graphs stop after depth limit on biomes; region walks keep collecting until the name set empties).
- Shore height at a column uses noise fitted between `shoreHeightMin` and `shoreHeightMax` with `shoreHeightZoom`.
- Object lists are filtered into surface vs carving support by placement `carvingSupport`.

## Common Author Mistakes

| Mistake | Result |
|---------|--------|
| Listing child biomes on the region | Children should be on the parent biome; listing children as roots duplicates or skips intended nesting |
| Region not listed on dimension | Never selected |
| Empty `landBiomes` | Invalid region for normal overworld generation |
| Wrong biome key path | `temperate/plains` must match `biomes/temperate/plains.json` |
| Relying on region rarity alone | Dimension also uses noise style/zoom; sample with `/iris studio regions` |

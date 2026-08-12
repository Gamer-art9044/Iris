# 12 - Regions

A region is the middle layer of a pack: the dimension picks a region for every column, and the region decides which biomes are allowed there. Files live at `regions/<loadKey>.json`. A region carries four biome lists (land, sea, shore, cave), a zoom for each list, the shoreline band width, and any content you want scoped to that part of the world.

Related: see `05 - Concepts & Pack Layout.md`, `11 - Dimensions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `15 - Caves & Carving.md`, `16 - Surfaces, Decorators & Deposits.md`, `20 - Object Placement.md`.

## The mental model

Every column in the world runs through the same chain. Regions sit in the middle of it:

```
column (x, z)
  |
  regionStyle noise, zoomed by regionZoom   ->  one number in 0..1
  |
  weighted pick over the dimension's regions ->  REGION
  |
  continentalStyle noise vs landChance       ->  LAND or SEA
  |
  <role>BiomeStyle noise, zoomed by biomeZoom * roleZoom * region's <role>BiomeZoom
  |
  weighted pick over that region's biome list for the role -> BIOME
  |
  height is computed, then the role is corrected against the water line
  (see "The shore band" below), and children are resolved
```

Two things follow from this that trip up most new pack authors:

- The region does not decide *where* the sea is. The dimension's `continentalStyle` and `landChance` do that. A region only supplies the candidate biomes once the role is known. If a region has no `seaBiomes`, columns that fall below the water line have nothing to pick and the world will look broken there — see "Land-only dimensions" below.
- A region's zooms multiply the dimension's zooms; they do not replace them. `landBiomeZoom: 3.5` in a region means land biomes there are 3.5x the size they would be at the dimension's base scale.

### How the weighted pick works

Region selection and biome selection use the same routine. Each candidate gets a weight of `1 / rarity`, the weights are laid out as contiguous bands across the 0..1 noise value in list order, and the noise value at the column picks the band.

That has three practical consequences:

- Rarity is a divisor, not a percentage. `rarity: 2` gets half the space of `rarity: 1`; `rarity: 10` gets a tenth. Region rarity is capped at 128, biome rarity at 512.
- List order matters for adjacency. Entries next to each other in the array occupy neighbouring noise bands, so they tend to end up as neighbours in the world. Reordering a list changes which biomes border which.
- Rarity alone cannot make a region appear where the noise never reaches its band. If a region never shows up, check `regionStyle` and `regionZoom` on the dimension before touching rarity. `/iris studio regions` samples an area and reports the measured share per region.

### The shore band

Shores are not chosen by noise. After the height for a column is known, Iris compares it to the dimension's `fluidHeight` and to the region's shore height at that column, then swaps the biome role if it disagrees:

| Column height (relative to `fluidHeight`) | Resulting role |
|---|---|
| below `fluidHeight` | sea |
| exactly `fluidHeight` | shore |
| `fluidHeight - 1` up to `fluidHeight + shoreHeight` | shore |
| above `fluidHeight + shoreHeight` | land |

`shoreHeight` is per column: noise fitted between `shoreHeightMin` and `shoreHeightMax`, sampled at `x / shoreHeightZoom, z / shoreHeightZoom`. So the beach is the vertical slice of the world from one block below the water line up to a few blocks above it, and the width of the beach on the ground is however far that slice stretches across your terrain slope. Flat coastline plus a large `shoreHeightMax` gives wide beaches; a cliff gives none regardless of the setting.

The shore-height noise is seeded from the region name length, `landBiomeZoom` and the number of land biomes — not from the world seed. Renaming a region or adding a land biome changes the shoreline wobble pattern.

## Walkthrough: add a region and prove it generates

Prerequisites: a dimension that validates, and one biome that already validates and is listed as a root somewhere. Keep the new region content-free until selection works — that separates selection problems from placement problems.

1. Create `regions/tutorial.json`:

```json
{
  "name": "Tutorial",
  "rarity": 1,
  "color": "#9BEE61",
  "landBiomes": ["starter"],
  "seaBiomes": [],
  "shoreBiomes": []
}
```

Replace `starter` with the exact load key of your existing biome. Root parents only — never list a biome that is already someone's child.

2. Add `"tutorial"` to the dimension's `regions` array.

3. Set `"focusRegion": "tutorial"` on the dimension. This pins every column to that region so nothing else can be blamed.

4. Validate the pack, then open Studio on seed `1337` (`/iris studio open <pack> 1337`; see `10 - Studio & VSCode Schemas.md` for the modded equivalent).

5. Fly into freshly generated chunks and run `/iris what region`.

Success: it reports `tutorial` everywhere, `/iris what biome` reports `starter`, and validation logs no unresolved keys.

If a different region appears, `focusRegion` does not match the file name. If the region resolves but terrain is missing, the problem is the biome or its generator, not the region — region rarity and zoom cannot repair a broken resource reference.

6. Remove `focusRegion`, reopen Studio, and travel until `tutorial` shows up on its own. Now add sea and shore biomes together, then cave biomes, validating after each group.

## Walkthrough: make biomes bigger in one region only

Suppose the tutorial region's biomes are too small and choppy, but the rest of the dimension is fine.

```json
{
  "name": "Tutorial",
  "rarity": 1,
  "landBiomes": ["starter", "starter-hills"],
  "landBiomeZoom": 3.5
}
```

Observable result: individual land biome patches in this region become roughly 3.5x wider. Nothing outside the region changes, and the dimension's `biomeZoom` still applies on top. The shipping `temperate` region uses `3.5` for land, `6` for sea and `0.15` for shore — shores are deliberately zoomed *down* so beach variants change every few dozen blocks along a coastline instead of running for hundreds of blocks.

Change one zoom at a time and regenerate a fresh area between comparisons. Zooms do not affect already-generated chunks.

## Walkthrough: widen the beaches

```json
{
  "shoreHeightMin": 1,
  "shoreHeightMax": 5.2,
  "shoreHeightZoom": 1.14
}
```

Observable result: the shore role now claims everything from one block under the water line up to about 5 blocks above it, so gently sloped coasts get much wider sand. Lowering `shoreHeightZoom` makes the beach width vary more rapidly along the coast; raising it produces long, uniform stretches.

If your beaches stay one block wide no matter what, the coastline is too steep. That is a generator problem, not a shore problem — see `14 - Generators & Noise.md`.

## Land-only dimensions

`seaBiomes` and `shoreBiomes` are optional in the schema. Leaving them empty is only safe when no column can ever land at or below `fluidHeight` — that means every biome in the region uses positive generator `min` values, and the dimension's `landChance` keeps the continental noise on land. Otherwise the sea/shore role has an empty candidate list at those columns.

```json
{
  "name": "Highlands",
  "rarity": 2,
  "landBiomes": ["highlands/plateau"],
  "seaBiomes": [],
  "shoreBiomes": []
}
```

`landBiomes` is always required.

## Load key

| Rule | Detail |
|------|--------|
| Folder | `regions/` |
| Key | Path relative to `regions/` with `.json` stripped |
| Example | `regions/temperate.json` -> key `temperate` |
| Referenced from | The dimension's `regions` array, using that key |

## Field reference (`IrisRegion`)

### Identity and selection

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `name` | string | `"A Region"` | Display name shown by `/iris what region` and the studio map. It is also mixed into the shore-height noise seed, so renaming a region shifts its coastline wobble. Required, minimum 2 characters. |
| `rarity` | int 1-128 | `1` | Divides this region's share of the selection noise: `2` gives half the area of a `1`, `8` gives an eighth. Raise it for a region you want as an occasional surprise, leave it at `1` for the backbone of the world. |
| `color` | string | `null` | Hex colour (`#9BEE61`) used by the studio map and `/iris studio map`. Set it when you are visually debugging region distribution; without it Iris derives a colour from the land biomes' vanilla derivatives. |

### Biome lists

| Field | Type | Required | What it does |
|-------|------|----------|--------------|
| `landBiomes` | string[] | Yes | Candidates for columns whose height ends up above the shore band. This is the list that defines the character of the region. |
| `seaBiomes` | string[] | No | Candidates for columns below the water line. Empty is only valid if no column in this region can ever fall below `fluidHeight`. |
| `shoreBiomes` | string[] | No | Candidates for the vertical band around the water line. Usually beaches and stony shores. |
| `caveBiomes` | string[] | No | Candidates for the biome used underground for layers, decorators and cave-anchored structures. Empty means carved space keeps the surface biome's data. |

List root parents only. Child biomes are declared on their parent via `children` (see `13 - Biomes.md`); listing a child here makes it compete as a root as well and breaks the nesting you intended. Keys are load keys relative to `biomes/`, so `temperate/plains` means `biomes/temperate/plains.json`.

A biome does not declare its own role. The role (`LAND`, `SEA`, `SHORE`, `CAVE`) comes from which list selected it, and the same biome file can appear in more than one list, taking a different role in each.

### Zooms and the shore band

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `landBiomeZoom` | double >= 0.0001 | `1` | Multiplies the land biome patch size in this region only. Raise it for continent-scale biomes, lower it for a busy patchwork. |
| `seaBiomeZoom` | double >= 0.0001 | `1` | Same for ocean-floor biomes. Oceans usually want a larger value than land so a single ocean type does not change every 200 blocks. |
| `shoreBiomeZoom` | double >= 0.0001 | `1` | Same for beach variants. Values well below 1 give a coastline that alternates between beach types frequently, which reads as natural variety. |
| `caveBiomeZoom` | double >= 0.0001 | `1` | Same for cave biomes. Larger values give long stretches of one cave character. |
| `shoreHeightMin` | double >= 0 | `1.2` | Lower bound, in blocks above the water line, of the shore band. |
| `shoreHeightMax` | double >= 0 | `3.2` | Upper bound of the shore band. Raise both to get taller, and therefore usually wider, beaches. |
| `shoreHeightZoom` | double >= 0.0001 | `3.14` | Horizontal scale of the noise that picks the band height per column. Small values make the beach width vary rapidly along the coast; large values make it uniform. |

### Content attached to the region

Everything here applies anywhere this region is selected, on top of what the biome contributes.

| Field | Type | Default | What it does |
|-------|------|---------|--------------|
| `objects` | `IrisObjectPlacement[]` | empty | `.iob` placements that should exist across the whole region rather than in one biome — regional landmarks, scattered wrecks. Split at runtime into surface and carving sets by each placement's `carvingSupport`. See `20 - Object Placement.md`. |
| `proceduralObjects` | `IrisProceduralObjects` | empty | Trees, ruins, formations, coral, fungi and crystals generated from parameters rather than `.iob` files. See `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`. |
| `structures` | `IrisStructurePlacement[]` | empty | Jigsaw and native structure placements evaluated where this region owns the chunk centre. Use this instead of copying a placement onto every biome in the region. See `21 - Jigsaw Structures.md`. |
| `entitySpawners` | string[] | empty | `IrisSpawner` keys that keep replenishing mobs while a player is in this region. See `23 - Loot, Entities, Spawners, Markers.md`. |
| `effects` | `IrisEffect[]` | empty | Client-side ambience (potion effects, sounds, particles) delivered per player by packet. Use for regional mood; no two players see each other's effects. |
| `loot` | `IrisLootReference` | empty | Loot tables that apply to containers generated in this region. |
| `blockDrops` | `IrisBlockDrops[]` | empty | Overrides what blocks drop when broken inside this region. |
| `deposits` | `IrisDepositGenerator[]` | empty | Blob-style deposits added on top of the dimension's deposits. Use for regional stone variants and ore pockets. |
| `depositVariants` | `IrisDepositVariant[]` | empty | Remaps deposit blocks inside a Y band. Evaluated after the biome's variants and before the dimension's; first matching rule in this tier wins. |
| `ores` | `IrisOreGenerator[]` | empty | Vein-style ores. Each generator declares whether it is a surface or underground generator and Iris keeps two separate lists. |
| `caveProfile` | `IrisCaveProfile` | default profile | Cave density, thresholds and surface behaviour for this region. Biome profiles override this. See `15 - Caves & Carving.md`. |

Deposit precedence across tiers: biome variants, then region variants, then dimension variants. First match wins within each tier.

### Fields the generator does not read

| Field | Status |
|-------|--------|
| `riverStyle` | Accepted by the schema and the editor, never read at generation time. Rivers come from biomes (for example `temperate/sea/river`) with negative generator heights, not from this field. |
| `lakeStyle` | Same. Setting it has no effect on generation. |

They are still valid JSON and will not fail validation, so do not spend time tuning them.

## Overworld sample: `temperate`

Path in the shipping pack: `packs/overworld/regions/temperate.json`.

| Field | Value | Why |
|-------|-------|-----|
| `name` / `color` | `Temperate` / `#9BEE61` | |
| `rarity` | `1` | Most common region; the world's default character. |
| `landBiomes` | 28 keys, e.g. `temperate/plains`, `temperate/cherry-blossom-forest`, `vanilla/cherry_grove` | A large list keeps a single region visually varied without needing many regions. |
| `seaBiomes` | 8 keys, e.g. `ocean/deep`, `temperate/sea/ocean`, `temperate/sea/river` | Rivers are sea biomes here, not a separate system. |
| `shoreBiomes` | 5 keys, e.g. `temperate/shore/beach`, `vanilla/stony_shore` | |
| `caveBiomes` | 5 keys, e.g. `carving/drip`, `carving/moss-pillars` | |
| `landBiomeZoom` / `seaBiomeZoom` | `3.5` / `6` | Oceans stay recognisable across long swims. |
| `shoreBiomeZoom` / `caveBiomeZoom` | `0.15` / `3.3` | Beaches change type often; caves keep one character for a while. |
| `shoreHeightMin` / `Max` / `Zoom` | `1` / `5.2` / `1.14` | Noticeably wide beaches with rapidly varying width. |
| `deposits`, `loot`, `caveProfile` | present | Iron/coal bands, `FALLBACK` loot mode, an enabled cave profile. |

The file sets no `objects`, `structures`, `ores`, `entitySpawners` or `effects` — all of that lives on the biomes.

Region keys listed by the shipping overworld dimension: `frozen`, `hot`, `terralost`, `mushroom`, `forests`, `tundra`, `magnetics`, `temperate`, `estranged`, `tropical`, `swamp`, `prismatics`.

## Resolution details worth knowing

- `getAllBiomeIds()` is the union of the four lists. It is what the dimension uses to decide which biomes exist for a region.
- Expanding a region to its full biome set walks each listed biome's `children` and its `carvingBiome`, then repeats until no new names appear. Cycles are safe; the walk stops when the pending name set empties.
- Objects declared on the region are pre-split into a surface list and a carving list by each placement's `carvingSupport`, so a placement that only supports carving never gets evaluated on the surface.
- Structure placements are gathered per chunk from the biome, the cave biome and the region at the chunk centre (block `chunkX*16+8`, `chunkZ*16+8`), plus the dimension. Cave biomes contribute only placements whose resolved anchor is a cave anchor.

## Common mistakes

| Mistake | What you will see |
|---------|-------------------|
| Listing a child biome as a region root | The child generates as a full-size root as well as inside its parent, so the intended nesting disappears |
| Region file not added to the dimension's `regions` array | The region never generates; validation may still pass |
| Empty `landBiomes` | Invalid region; land columns have no candidates |
| Wrong biome key path | `temperate/plains` must be `biomes/temperate/plains.json`, case and folder included |
| Tuning region `rarity` when the region never appears | Selection also depends on the dimension's `regionStyle` and `regionZoom`; measure first with `/iris studio regions` |
| Expecting `riverStyle` / `lakeStyle` to do something | They are read by nothing; make rivers and lakes as sea biomes with negative generator heights |
| Empty `seaBiomes` in a dimension whose terrain dips below `fluidHeight` | Sea columns have no candidate biome |
| Comparing changes in already-generated chunks | Region and zoom changes only affect newly generated chunks; always fly to fresh terrain |

# 27 - Example - Configuring Overworld

The shipping overworld pack is the default Iris dimension pack. This guide shows where it lives, how worlds snapshot it, how to edit safely with studio, and how to push changes into production worlds with `update-world`.

Related: `05 - Concepts & Pack Layout.md`, `06 - Worlds & Lifecycle.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `23 - Loot, Entities, Spawners, Markers.md`, `24 - Pack Mods & Snippets.md`, `25 - Pack Management.md`, `04 - Commands & Permissions.md`, `02 - Getting Started.md`.

## Pack locations

| Platform | Authoritative packs root |
|----------|--------------------------|
| Bukkit / Paper / Folia / Purpur | `plugins/Iris/packs/overworld/` |
| Fabric | `config/irisworldgen/packs/overworld/` |
| Forge / NeoForge | `config/irisworldgen/packs/overworld/` |

Worlds created from a pack store a **copy** at:

```
<world>/iris/pack/
```

`StudioSVC.installIntoWorld` and `replaceIntoWorld` copy the source pack tree into that directory. Runtime generation for a normal world reads the world copy, not the global `packs/` tree. Studio worlds hotload the pack under `packs/` directly.

First install often downloads the default overworld release into `packs/` (`downloadDefaultOverworld` / `/iris download` flows — see `02 - Getting Started.md`, `25 - Pack Management.md`).

## High-level layout (shipping overworld)

```
overworld/
  dimensions/overworld.json      # root dimension (load key: overworld)
  regions/*.json                 # frozen, hot, temperate, tropical, ...
  biomes/<folder>/*.json         # temperate/, hot/, carving/, vanilla/, ...
  generators/*.json              # plain, mountain, ocean, flat, ...
  loot/...                       # global-clutter, temperate/food, ...
  entities/standard/...
  spawners/<climate>/...
  objects/...                    # .iob schematics
  structures/, jigsaw-*, ...
  snippet/decorator/, snippet/style/
```

Dimension load key is `overworld` (`dimensions/overworld.json`).

## Dimension snapshot (real keys)

From `dimensions/overworld.json` (selected fields):

| Field | Shipping value (probe) |
|-------|------------------------|
| `name` | `"Overworld"` |
| `version` | `4000` |
| `fluidHeight` | `50` |
| `logicalHeight` | `512` |
| `dimensionHeight` | `min` -256, `max` 512 |
| `landChance` | `0.69` |
| `regionZoom` | `16.15` |
| `environment` | `NORMAL` |
| `regions` | `frozen`, `hot`, `terralost`, `mushroom`, `forests`, `tundra`, `magnetics`, `temperate`, `estranged`, `tropical`, `swamp`, `prismatics` |
| `loot` | mode `FALLBACK`, tables `["global-clutter"]` |
| `preventLeafDecay` | `true` |
| `useMantle` | `true` |
| `carvingEnabled` / `decorate` | `true` |

Also present: continental/region/biome styles, deposits, depositVariants, caveProfile, carving band entries, imported structure controls, structure placements. Do not invent biome or region keys; list directories under `regions/` and `biomes/` when adding content.

## Region and biome paths

Example region: `regions/temperate.json`

- `landBiomes` includes keys such as `temperate/plains`, `temperate/oak-forest`, `vanilla/cherry_grove`, `mountain/plains`
- `shoreBiomes` e.g. `temperate/shore/beach`
- `seaBiomes` e.g. `ocean/deep`, `temperate/sea/river`
- `caveBiomes` e.g. `carving/rocky-cavebiome`, `carving/drip`
- `loot`: mode `FALLBACK`, tables `temperate/clutter`, `temperate/food`

Example biome: `biomes/temperate/plains.json`

- `derivative` / `vanillaDerivative`: `minecraft:plains`
- `generators`: `[{ "generator": "plain", "min": 4, "max": 10 }]`
- `layers`: grass → dirt → stone stack
- `objects`: placements referencing `clutter/...` object keys

Generator referenced by that biome: `generators/plain.json` (composite IRIS_DOUBLE noise + bilinear starcast interpolator).

## Safe editing workflow

### Prefer studio for authoring

1. Ensure overworld exists under `packs/overworld/`.
2. Open studio: `/iris studio open overworld` (optional seed).
3. Edit files under `packs/overworld/` with VSCode workspace / schemas (`10 - Studio & VSCode Schemas.md`).
4. Hotload picks up JSON changes in the studio world. Regenerate or move to see new terrain.
5. Use focus fields on the dimension for isolation:
   - `"focus": "temperate/plains"` — only that biome
   - `"focusRegion": "temperate"` — only that region
6. Close studio when finished: `/iris studio close`.

Studio is the live pack. Production worlds still run on their `iris/pack` snapshot until updated.

### Do not edit the world copy as the source of truth

Editing `<world>/iris/pack/` only affects that world and is overwritten by pack install/update. Keep authoring in `packs/overworld/` (or a forked pack folder).

### Fork if you will diverge permanently

```
/iris studio create name=my-overworld template=overworld
```

Copies the overworld pack into a new pack key. Create worlds with `my-overworld` so upstream overworld updates do not clobber custom work.

## Applying changes to production worlds

World create installs a pack copy once. Changing `packs/overworld/` does **not** automatically update existing worlds.

### Bukkit: `/iris dev update-world`

```
/iris dev update-world world=<world> pack=overworld confirm=true
```

Optional: `fresh-download` re-downloads the pack before install.

Behavior (`CommandDeveloper.updateWorld` → `StudioSVC.replaceIntoWorld`):

1. Requires `confirm=true` (otherwise prints warning only).
2. Optionally re-downloads the pack.
3. Replaces `<world>/iris/pack/` with a fresh copy of the source pack.
4. Marked **UNSAFE** in the command description — already-generated chunks keep old terrain; only newly generated chunks use the new pack content for most features. Backup the world first.

### When to use update-world vs new world

| Goal | Approach |
|------|----------|
| Live design iteration | Studio open on `packs/` |
| Ship pack changes to existing survival world | Backup → `update-world ... confirm` |
| Guaranteed clean terrain | New world with the updated pack |
| Partial experimental changes | Fork pack (`studio create`) |

## Practical edit recipes

### Change sea level

In `dimensions/overworld.json` set `fluidHeight` (shipping `50`). Height is relative to `dimensionHeight.min` as documented on `IrisDimension`. Restart or hotload; expect shoreline shifts on new chunks only.

### Add a biome to temperate

1. Create `biomes/temperate/my-biome.json` with required `name`, `derivative`, `layers`, `generators` (see `26 - Example - Minimal Dimension.md`, `13 - Biomes.md`).
2. Append `"temperate/my-biome"` to `regions/temperate.json` → `landBiomes` (or sea/shore/cave lists as appropriate).
3. Studio hotload; sample locations with what/teleport tools.

Never invent keys that do not exist as files. Region lists must match real biome load keys.

### Tweak plains height

Edit `biomes/temperate/plains.json` generators min/max, or edit shared `generators/plain.json` (affects every biome using `plain`).

### Loot

- Dimension fallback: `dimensions/overworld.json` → `loot.tables`
- Region: e.g. `regions/temperate.json` → `loot`
- Tables live under `loot/` (`global-clutter`, `global-treasure`, `temperate/food`, …)

### Decorators via snippets

Reuse `snippet/decorator/*` and `snippet/style/*` as in `24 - Pack Mods & Snippets.md`. Example references already appear in `biomes/vanilla/old_growth_birch_forest.json` and dimension ore `chanceStyle` fields.

### Entities and spawners

Overworld ships `entities/standard/**` and `spawners/**`. Ambient Iris spawning requires listing keys on `entitySpawners` of dimension, region, or biome. Marker-based spawning requires markers + object placement `markers` arrays. See `23 - Loot, Entities, Spawners, Markers.md`.

## Validation and packaging

| Task | Command |
|------|---------|
| Validate pack | Bukkit: `/iris pack validate pack=overworld`; modded: `/iris pack validate overworld` |
| Cleanup unused resources | Bukkit: `/iris pack cleanup overworld mode=preview`, then `mode=apply`; modded uses `preview`/`apply` literals |
| Package for distribution | Bukkit: `/iris studio package dimension=overworld`; modded: `/iris studio package overworld` |
| Version stamp | Dimension `version` field (overworld uses large ints such as `4000`) |

## Checklist before production update

1. Edit and verify in studio, not only by reading JSON.
2. Run pack validate; fix broken keys.
3. Backup the target world folder.
4. Run `update-world` with `confirm` (and optional fresh download).
5. Explore **new** chunks for expected results; do not expect wholesale remesh of old chunks.
6. Record operator-facing changes in workspace changelog when releasing.

## Cross-links

- Minimal greenfield pack: `26 - Example - Minimal Dimension.md`
- Dimension field reference: `11 - Dimensions.md`
- Commands matrix: `04 - Commands & Permissions.md`
- Pack download/validate/package: `25 - Pack Management.md`

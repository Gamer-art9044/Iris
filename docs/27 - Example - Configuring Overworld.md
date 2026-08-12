# 27 - Example - Configuring Overworld

The shipping overworld pack is the default Iris dimension pack. This guide shows where it lives, how worlds snapshot it, how to edit safely with studio, and how to push changes into production worlds with `update-world`.

Related: `05 - Concepts & Pack Layout.md`, `06 - Worlds & Lifecycle.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `23 - Loot, Entities, Spawners, Markers.md`, `24 - Pack Mods & Snippets.md`, `25 - Pack Management.md`, `04 - Commands & Permissions.md`, `02 - Getting Started.md`.

## Tutorial result

Fork the shipping pack, add one visible biome, prove it in Studio and a disposable world, and leave the original `overworld` pack untouched. This is the recommended first Overworld customization because it exercises references, hotload, snapshots, and rollback without changing dimension height or native registries.

Prerequisites:

- The `overworld` pack is installed and validates.
- You have operator access on Bukkit or gamemaster access on a mod loader.
- The keys `my-overworld`, `overworld-test`, and `tutorial/meadow` are unused.
- You can keep the fork in source control or make a filesystem backup before production use.

## End-to-end tutorial: add a temperate meadow

### 1. Fork and open the pack

Create the fork with Bukkit `/iris studio create name=my-overworld template=overworld` or modded `/iris studio create my-overworld overworld`. Wait for the command to report the completed project path; pack creation runs asynchronously.

Validate with Bukkit `/iris pack validate pack=my-overworld` or modded `/iris pack validate my-overworld`. Then open Studio with Bukkit `/iris studio open my-overworld seed=1337` or modded `/iris studio open my-overworld 1337`.

### 2. Add the biome file

Save this complete biome as `plugins/Iris/packs/my-overworld/biomes/tutorial/meadow.json` on Bukkit or `config/irisworldgen/packs/my-overworld/biomes/tutorial/meadow.json` on a mod loader:

```json
{
  "name": "Tutorial Meadow",
  "rarity": 1,
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains",
  "layers": [
    {
      "minHeight": 1,
      "maxHeight": 1,
      "palette": [{ "block": "minecraft:grass_block" }]
    },
    {
      "minHeight": 3,
      "maxHeight": 3,
      "palette": [{ "block": "minecraft:dirt" }]
    }
  ],
  "generators": [
    { "generator": "plain", "min": 18, "max": 24 }
  ],
  "decorators": ["snippet/decorator/wildflowers"]
}
```

The fork already contains `generators/plain.json` and `snippet/decorator/wildflowers.json`. Do not copy this biome into the original `overworld` folder.

### 3. Attach and focus the biome

Append `"tutorial/meadow"` to `landBiomes` in `regions/temperate.json`. In `dimensions/my-overworld.json`, temporarily add:

```json
{
  "focusRegion": "temperate",
  "focus": "tutorial/meadow"
}
```

These are field excerpts: merge them into the existing region and dimension objects instead of replacing either file. Validate again after both edits.

### 4. Prove the authoring result

Generate untouched Studio chunks and run `/iris what region` and `/iris what biome`. Success is the `temperate` region, the `tutorial/meadow` biome, a grass-over-dirt surface, visibly higher rolling terrain than shipping plains, and wildflower decoration with no missing-key errors.

If validation cannot resolve the biome, compare `tutorial/meadow` against the file path and region entry character-for-character. If terrain is empty, confirm `generators/plain.json` still exists in the fork. If flowers are missing, confirm `snippet/decorator/wildflowers.json` exists; remove the decorator reference until the terrain baseline passes.

### 5. Prove natural selection and restart behavior

Remove `focus` and `focusRegion`, close Studio, and reopen with seed `1337`. On Bukkit, `/iris find biome tutorial/meadow` can locate the naturally selected biome after it appears; the same command is available on modded.

Create a disposable world with Bukkit `/iris create overworld-test type=my-overworld seed=1337` or modded `/iris create overworld-test my-overworld 1337`. Teleport with Bukkit `/iris tp overworld-test` or modded `/iris tp irisworldgen:overworld-test`, generate new chunks, stop cleanly, restart, and verify another new area. On Folia, honor the required restart immediately after the create command before teleporting.

The tutorial passes when validation is loadable, focused and natural selection both work, the disposable world contains `<world>/iris/pack/`, and the world reloads without pack or registry errors.

### 6. Package or recover

Package with Bukkit `/iris studio package dimension=my-overworld` or modded `/iris studio package my-overworld`. Keep the validated fork as the source of truth; the `.iris` export and world snapshot are outputs.

| Failure | Recovery |
|---------|----------|
| Fork creation fails or is partial | Move only the newly created incomplete `my-overworld` folder aside, then rerun after confirming the source pack validates |
| Studio still shows old content | Generate untouched chunks; close and reopen Studio after dimension-contract or registry changes |
| Natural selection cannot find the biome | Confirm it remains in `regions/temperate.json`, remove focus fields, and sample a broader new area |
| Disposable world differs from Studio | Inspect `<world>/iris/pack/`; recreate the disposable world from the current validated fork |
| Production update would change height, registries, or large terrain systems | Do not update in place; create a new world and migrate intentionally |

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

First install downloads the managed Overworld and Underworld beta releases into `packs/`; `/iris download overworld` uses the same Overworld asset (see `02 - Getting Started.md`, `25 - Pack Management.md`).

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

| Field | Shipping value |
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
2. Fork it: `/iris studio create name=my-overworld template=overworld`.
3. Open Studio: `/iris studio open my-overworld seed=1337`.
4. Edit the fork under `packs/my-overworld/` with the generated VSCode workspace and schemas (`10 - Studio & VSCode Schemas.md`).
5. Hotload picks up JSON changes in the Studio world. Generate new chunks to see terrain changes.
6. Use focus fields on the forked dimension for isolation:
   - `"focus": "temperate/plains"` — only that biome
   - `"focusRegion": "temperate"` — only that region
7. Change `biomes/temperate/plains.json` generator `min` or `max` by a small amount, validate, and compare the same seed in fresh chunks.
8. Restore/remove focus, close Studio, create a disposable world from `my-overworld`, and restart-test it.

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

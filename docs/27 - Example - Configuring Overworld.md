# 27 - Example - Configuring Overworld

The shipping `overworld` pack is what Iris downloads on first boot and what most servers generate from. This is a guided build: you will fork it, add one visible biome, prove the biome in Studio and in a disposable world, and leave the original pack untouched. It exercises the parts of the workflow that actually bite — references, hotload, world snapshots, and rollback — without touching height or registries.

Related: `05 - Concepts & Pack Layout.md`, `06 - Worlds & Lifecycle.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `23 - Loot, Entities, Spawners, Markers.md`, `24 - Pack Mods & Snippets.md`, `25 - Pack Management.md`, `04 - Commands & Permissions.md`, `02 - Getting Started.md`.

Prerequisites:

- The `overworld` pack is installed and validates.
- Operator access on Bukkit, or gamemaster access on a mod loader.
- The keys `my-overworld`, `overworld-test`, and `tutorial/meadow` are unused.
- The fork is in source control or has a filesystem backup before you rely on it.

## Where everything lives before you start

| Platform | Authoritative packs root |
|----------|--------------------------|
| Bukkit / Paper / Folia / Purpur | `plugins/Iris/packs/overworld/` |
| Fabric / Forge / NeoForge | `config/irisworldgen/packs/overworld/` |

A world created from a pack stores its own **copy** at `<world>/iris/pack/`. `StudioSVC.installIntoWorld` and `replaceIntoWorld` write that copy; normal world generation reads it and never looks at the global `packs/` tree again. Studio worlds are the exception — they run directly off `packs/<key>/`, which is why Studio is where authoring happens.

On first install Iris downloads the managed Overworld and Underworld beta releases into `packs/`. `/iris download overworld` pulls the same Overworld asset (see `02 - Getting Started.md`, `25 - Pack Management.md`).

The pack's shape:

```
overworld/
  dimensions/overworld.json      # root dimension, load key: overworld
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

## 1. Fork the pack

**What you do.**

- Bukkit: `/iris studio create name=my-overworld template=overworld`
- Modded: `/iris studio create my-overworld overworld`

Wait for the command to report the completed project path — pack creation runs asynchronously and may report that a restart is required before the new pack can be opened.

Then validate and open:

- Bukkit: `/iris pack validate pack=my-overworld`, then `/iris studio open my-overworld seed=1337`
- Modded: `/iris pack validate my-overworld`, then `/iris studio open my-overworld 1337`

**Why.** Forking copies the whole tree under a new pack key so upstream Overworld updates cannot clobber your work, and so a mistake is one folder deletion away from being undone. Create your worlds from the fork, not from `overworld`.

**What you should see.** A `my-overworld` folder next to `overworld` with the same structure, a loadable validation result, and a Studio world that looks exactly like the shipping overworld.

## 2. Add the biome file

**What you do.** Save this complete biome as `packs/my-overworld/biomes/tutorial/meadow.json`:

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

**Why.** Every piece of this is chosen so the result is unmistakable in game:

- `generators` reuses the fork's existing `generators/plain.json` (an `IRIS_DOUBLE` composite behind a `BILINEAR_STARCAST_9` interpolator) but at `min` 18 / `max` 24 instead of the 4-to-10 band the shipping plains uses. Those numbers are offsets from `fluidHeight`, which the overworld sets to 50, so this meadow sits roughly 68 to 74 blocks up while ordinary plains sit around 54 to 60. The height difference is what makes it visible from a distance.
- `layers` are **thicknesses**, not Y coordinates: one block of grass over three blocks of dirt, with the dimension's rock palette filling everything below.
- `decorators` uses a snippet reference. Any field whose type is a snippet type accepts the string form `snippet/<type>/<name>`, and Iris loads `snippet/decorator/wildflowers.json` in its place at parse time. The fork already contains that file.
- `rarity` 1 makes it as common as the region's other biomes so you do not have to search for it later.

Do not copy this file into the original `overworld` folder.

**What you should see.** With the workspace open, the editor should autocomplete `generator` values against the fork's real generator keys and flag a typo in `derivative` immediately. If it does not, run `/iris studio update dimension=my-overworld` (see `10 - Studio & VSCode Schemas.md`).

## 3. Attach it and focus on it

**What you do.** Append `"tutorial/meadow"` to `landBiomes` in `regions/temperate.json`, then merge these two fields into the existing object in `dimensions/my-overworld.json`:

```json
{
  "focusRegion": "temperate",
  "focus": "tutorial/meadow"
}
```

These are field excerpts. Merge them into the existing files; do not replace either file with the fragment. Validate again after both edits.

**Why.** A biome file that no region lists never generates — nothing warns you about it, it just never gets picked. `regions/temperate.json` already carries 28 land biomes, so a new one would be rare enough to be annoying to find; the two focus fields force the entire world to that region and biome so you can confirm the biome itself is correct before worrying about selection frequency.

**What you should see.** Validation still loadable. If it cannot resolve the biome, compare `tutorial/meadow` against the actual path and the region entry character for character — the folder prefix is part of the key.

## 4. Prove the authoring result

**What you do.** Generate untouched Studio chunks and run `/iris what region` and `/iris what biome`.

**What you should see.** Region `Temperate`, biome `Tutorial Meadow`, a grass-over-dirt surface, terrain visibly higher than the surrounding shipping plains, wildflower decoration, and no missing-key errors in console.

If terrain is empty, confirm `generators/plain.json` still exists in the fork. If flowers are missing, confirm `snippet/decorator/wildflowers.json` exists and remove the decorator reference until the terrain baseline passes — one variable at a time.

## 5. Prove natural selection and restart behavior

**What you do.**

1. Remove `focus` and `focusRegion`, close Studio, and reopen on seed `1337`.
2. Locate the biome naturally: `/iris find biome tutorial/meadow` (available on Bukkit and on mod loaders; `/iris goto biome <key>` is the same command on modded).
3. Create a disposable world: Bukkit `/iris create overworld-test type=my-overworld seed=1337`, modded `/iris create overworld-test my-overworld 1337`.
4. Teleport: Bukkit `/iris tp overworld-test`, modded `/iris tp irisworldgen:overworld-test`. On Folia, honor the required restart immediately after the create command before teleporting.
5. Generate new chunks, stop the server cleanly, restart, and verify another new area.

**Why.** Focus mode proves the biome renders; only unfocused generation proves it is actually reachable through region selection. The disposable world proves the pack snapshot works outside Studio, and the restart proves the generated dimension type and custom biomes survive a registry reload.

**What you should see.** The meadow appearing naturally in temperate regions, `<world>/iris/pack/` present in the world folder, and a clean restart with no pack or registry errors.

## 6. Package or recover

**What you do.** Package with Bukkit `/iris studio package dimension=my-overworld` or modded `/iris studio package my-overworld`.

**Why.** The validated fork under `packs/` is the source of truth. The `.iris` export and the world snapshot are outputs, and both are reproducible from it.

| Failure | Recovery |
|---------|----------|
| Fork creation fails or is partial | Move only the newly created incomplete `my-overworld` folder aside, confirm the source pack validates, then rerun |
| Studio still shows old content | Generate untouched chunks; close and reopen after a dimension-contract or registry change |
| Natural selection cannot find the biome | Confirm it is still in `regions/temperate.json`, that both focus fields are gone, and sample a broader new area |
| Disposable world differs from Studio | Inspect `<world>/iris/pack/`; recreate the world from the current validated fork |
| A production update would change height, registries, or large terrain systems | Do not update in place; create a new world and migrate deliberately |

## What the shipping dimension actually sets

From `dimensions/overworld.json`:

| Field | Shipping value | Why it matters when you edit |
|-------|----------------|------------------------------|
| `name` / `version` | `"Overworld"` / `4000` | Bump `version` on your fork so pack generations stay distinguishable |
| `dimensionHeight` | `min` -256, `max` 512 | 768 blocks tall. Contract field — do not change it on a fork that already has worlds |
| `logicalHeight` | `512` | Contract field |
| `fluidHeight` | `50` | World Y of sea level, and the baseline every biome generator band is measured from. Change it and every biome's apparent height moves |
| `environment` | `NORMAL` | Contract field |
| `landChance` | `0.69` | Land-heavy world |
| `regionZoom` | `16.15` | Continent-sized climate regions |
| `coordFractureZoom` | `0.15` | Aggressive coordinate warping; this is the source of the swirled borders |
| `dimensionAngleDeg` | `69` | Off-axis rotation that hides grid artifacts |
| `regions` | `frozen`, `hot`, `terralost`, `mushroom`, `forests`, `tundra`, `magnetics`, `temperate`, `estranged`, `tropical`, `swamp`, `prismatics` | The twelve climate regions your biome must be attached to one of |
| `loot` | mode `FALLBACK`, tables `["global-clutter"]` | Fallback only — objects that declare their own loot keep it |
| `preventLeafDecay` | `true` | Custom trees keep their canopies |
| `useMantle` / `carvingEnabled` / `decorate` | `true` | All content passes on |
| `caveProfile` | enabled | Dimension-wide 3D caves, overridden per region |
| `carving` | one deep-dark band at world Y -250 to -175 | Depth-banded cave biome |
| `mode` | omitted | Runs `OVERWORLD` |

Also present: region/continental/biome noise styles, 11 ore generators, 20 deposits, one deposit variant that remaps ores to their deepslate forms below Y 0, imported-structure adjustments for stronghold, trial chambers, mineshaft and village, and one ancient-city structure placement with `nativeSuppression: REPLACE_SOURCE`. Field-by-field meanings are in `11 - Dimensions.md`.

Do not invent region or biome keys. List the directories under `regions/` and `biomes/` and use what is actually there.

## Reading the region and biome graph

`regions/temperate.json` is a representative region:

- `landBiomes` — 28 keys including `temperate/plains`, `temperate/oak-forest`, `mountain/plains`, `vanilla/cherry_grove`
- `shoreBiomes` — `temperate/shore/beach`, `ocean/shore/beach`, `vanilla/stony_shore`, others
- `seaBiomes` — `ocean/deep`, `temperate/sea/ocean`, `temperate/sea/river`, others
- `caveBiomes` — `carving/rocky-cavebiome`, `carving/drip`, `carving/deep`, others
- `loot` — mode `FALLBACK`, multiplier `0.5`, tables `temperate/clutter` and `temperate/food`
- Per-category zooms (`landBiomeZoom` 3.5, `seaBiomeZoom` 6, `shoreBiomeZoom` 0.15, `caveBiomeZoom` 3.3) and its own enabled `caveProfile`

`biomes/temperate/plains.json` is a representative biome:

- `derivative` and `vanillaDerivative` are both `minecraft:plains`
- `generators` is `[{ "generator": "plain", "min": 4, "max": 10 }]` — 4 to 10 blocks above sea level
- `layers` is one block of grass over two blocks of dirt; the dimension's rock palette fills below
- `objects` places `clutter/...` keys in `PAINT` mode at fractions of a percent per column
- `decorators` place flowers with a `TRIOCTAVE_SIMPLEX` variance and a fractured `STATIC` style

`generators/plain.json` is the height source both that biome and your meadow use: a single `IRIS_DOUBLE` composite layer behind a `BILINEAR_STARCAST_9` interpolator at horizontal scale 12.

## Editing safely

### Author in Studio, on a fork

1. Confirm `overworld` exists under `packs/overworld/`.
2. Fork it: `/iris studio create name=my-overworld template=overworld`.
3. Open Studio: `/iris studio open my-overworld seed=1337`.
4. Edit under `packs/my-overworld/` with the generated VSCode workspace and schemas (`10 - Studio & VSCode Schemas.md`).
5. Save; hotload picks the change up. Generate new chunks to see it — existing blocks are never rewritten.
6. Isolate with `"focus": "temperate/plains"` or `"focusRegion": "temperate"` while testing, and remove both afterwards.
7. Make one small change at a time — nudge `biomes/temperate/plains.json` generator `min`/`max` by a few blocks, validate, and compare the same seed in fresh chunks.
8. Close Studio, create a disposable world from the fork, and restart-test it before touching anything real.

### Do not treat the world copy as the source

Editing `<world>/iris/pack/` changes only that world and is overwritten by the next pack install or update. Author under `packs/`.

## Practical recipes

### Change sea level

Set `fluidHeight` in `dimensions/my-overworld.json` — shipping value `50`. It is world Y, and every biome generator band is measured from it, so lowering it lowers the sea while leaving relative terrain heights intact and raising it drowns low biomes. Only newly generated chunks change, so expect a visible shoreline seam on an existing world.

### Add a biome to a region

1. Create `biomes/temperate/my-biome.json` with at least `name`, `derivative`, `layers`, and `generators` (`26 - Example - Minimal Dimension.md`, `13 - Biomes.md`).
2. Append `"temperate/my-biome"` to the appropriate list in `regions/temperate.json` — `landBiomes`, `seaBiomes`, `shoreBiomes`, or `caveBiomes`.
3. Hotload, then sample with `/iris what biome` and `/iris find biome`.

Region lists must match real biome load keys. A key that does not resolve is a blocking validation error; a biome file that no region lists is silently dead.

### Change plains height

Edit `generators` `min`/`max` on `biomes/temperate/plains.json` to affect only that biome, or edit `generators/plain.json` to affect every biome that references `plain` — which is a lot of them. Prefer the biome-level change unless you mean the global one.

### Loot

- Dimension fallback: `dimensions/overworld.json` → `loot.tables`
- Region: `regions/temperate.json` → `loot`
- Tables live under `loot/` (`global-clutter`, `global-treasure`, `temperate/food`, …)

Mode `FALLBACK` only supplies tables when the object itself declared none; `ADD` stacks onto the parent scopes; `CLEAR` and `REPLACE` drop them.

### Decorators via snippets

Reuse `snippet/decorator/*` and `snippet/style/*` by string reference as in `24 - Pack Mods & Snippets.md`. Existing examples: `biomes/vanilla/old_growth_birch_forest.json` and the dimension's ore `chanceStyle` fields.

### Entities and spawners

The pack ships `entities/standard/**` and `spawners/**`. Ambient Iris spawning requires listing spawner keys on `entitySpawners` at dimension, region or biome scope. Marker-based spawning needs markers plus a `markers` array on an object placement. See `23 - Loot, Entities, Spawners, Markers.md`.

## Pushing changes into an existing world

World creation installs the pack copy once. Changing `packs/` does **not** update existing worlds.

### `/iris dev update-world` (Bukkit)

```
/iris dev update-world world=<world> pack=my-overworld confirm=true
```

Optional `fresh-download=true` re-downloads the pack first. Behavior (`CommandDeveloper.updateWorld` → `StudioSVC.replaceIntoWorld`):

1. Without `confirm=true` it prints the warning and does nothing.
2. Optionally re-downloads the pack.
3. Replaces `<world>/iris/pack/` with a fresh copy of the source pack.
4. It is described as UNSAFE in the command itself. Already-generated chunks keep their old terrain; for most features only newly generated chunks use the new content. Back the world up first.

### Choosing between update-world and a new world

| Goal | Approach |
|------|----------|
| Live design iteration | Studio open on `packs/` |
| Ship pack changes into an existing survival world | Back up, then `update-world … confirm=true` |
| Guaranteed consistent terrain | New world from the updated pack |
| Experimental or partial changes | Fork the pack with `studio create` |

Never use `update-world` for a change to `dimensionHeight`, `logicalHeight`, `environment`, or the dimension file name. Those are the world contract; the world will not load against a different one.

## Validation and packaging

| Task | Command |
|------|---------|
| Validate | Bukkit `/iris pack validate pack=my-overworld`; modded `/iris pack validate my-overworld` |
| Preview unused-resource cleanup | Bukkit `/iris pack cleanup my-overworld mode=preview`, then `mode=apply`; modded uses the same `preview`/`apply` literals |
| Package for distribution | Bukkit `/iris studio package dimension=my-overworld`; modded `/iris studio package my-overworld` |
| Version stamp | The dimension `version` field; the shipping pack uses large integers such as `4000` |

## Checklist before a production update

1. Verify in Studio, not by reading JSON.
2. Run pack validate and fix every broken key.
3. Back up the target world folder.
4. Run `update-world` with `confirm=true`, plus `fresh-download=true` if the source should be re-pulled.
5. Explore **new** chunks; do not expect existing terrain to change.
6. Record operator-facing changes in the workspace changelog when releasing.

## Cross-links

- Minimal greenfield pack: `26 - Example - Minimal Dimension.md`
- Dimension field reference: `11 - Dimensions.md`
- Commands and permissions: `04 - Commands & Permissions.md`
- Download, validate, package: `25 - Pack Management.md`

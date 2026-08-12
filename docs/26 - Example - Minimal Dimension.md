# 26 - Example - Minimal Dimension

This is a guided build of the smallest pack Iris will actually generate: one dimension, one region, one biome, one generator. You will write four files, validate them, prove them in Studio on a fixed seed, and then prove them again in a real world across a server restart. Keep this four-file state as a rollback point before adding anything else.

Related: `05 - Concepts & Pack Layout.md`, `02 - Getting Started.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `25 - Pack Management.md`, `04 - Commands & Permissions.md`.

Prerequisites:

- Iris is running and its data folders exist.
- Operator access on Bukkit, or gamemaster access on a mod loader.
- No pack or world already uses the keys `minimal` or `minimal-test`.
- You can watch the server console while validation, Studio open, world create, and restart run.

Do not add objects, caves, structures, custom biomes, or datapacks until this baseline generates and reloads cleanly. Every one of those introduces its own failure mode, and they are far easier to diagnose one at a time.

## 1. Create the pack root

**What you do.** Make this tree under the platform packs root:

```
minimal/
  dimensions/minimal.json
  regions/starter.json
  biomes/starter.json
  generators/flat.json
```

- Bukkit-family packs root: `plugins/Iris/packs/`
- Fabric / Forge / NeoForge packs root: `config/irisworldgen/packs/`

**Why.** The folder name is the pack key. The dimension file name without `.json` is the dimension load key — here both are `minimal`, which is the convention worth keeping because commands take the key, not the path. Iris resolves every other resource by its path under its type folder, so `biomes/starter.json` is biome key `starter` and `biomes/plains/dry.json` would be key `plains/dry`.

**What you should see.** Nothing yet; Iris does not notice new folders until something loads the pack.

You can also have Iris write the skeleton for you:

| Platform / method | Command or action |
|-------------------|-------------------|
| Bukkit starter (no template) | `/iris studio create name=minimal` |
| Bukkit template copy | `/iris studio create name=minimal template=overworld` |
| Modded (always uses a template; `example` by default) | `/iris studio create minimal` |
| Modded template copy | `/iris studio create minimal overworld` |

The Bukkit starter writes the same four resource types described below. Modded studio create always copies a template, so create the tree by hand when you want exactly this four-file baseline on a mod loader.

## 2. Write the dimension

**What you do.** Save `dimensions/minimal.json`:

```json
{
  "name": "minimal",
  "version": 1,
  "mode": { "type": "OVERWORLD" },
  "regions": ["starter"],
  "environment": "NORMAL",
  "dimensionHeight": { "min": -64, "max": 320 },
  "logicalHeight": 384,
  "fluidHeight": 63
}
```

**Why each field is here:**

| Field | Why |
|-------|-----|
| `name` | Display name used by commands and the studio scoreboard. Marked required in the schema; the file name is what actually identifies the dimension |
| `regions` | The only mandatory content link. Without at least one loadable region key, no biome can ever be selected |
| `mode` | `OVERWORLD` is the only mode that registers caves, objects, decoration and deposits. The other three register terrain and biome only. Omitting the field also yields `OVERWORLD`, but writing it out makes the choice visible |
| `environment` | Picks the vanilla dimension template the generated dimension type is built from — sky, fog and gameplay attributes, not terrain |
| `dimensionHeight` | Build floor -64, ceiling 320. The span (384) and the minimum (-64) are both multiples of 16, which Minecraft requires for the generated dimension type |
| `logicalHeight` | 384, equal to the total height. It must not exceed the total height or dimension-type construction throws |
| `fluidHeight` | World Y of sea level — 63, the vanilla value. Biome generator heights are measured from here |
| `version` | A stamp you control so pack generations are distinguishable. Iris never acts on it |

`dimensionHeight`, `logicalHeight`, `environment`, and the file name are the world contract. Once a world exists on this pack, changing any of them means recreating the world; Studio hotload refuses them outright. Everything else in this guide is safe to iterate on.

Useful while testing, and removed before shipping: `"focus": "starter"` forces a single biome and `"focusRegion": "starter"` forces a single region.

## 3. Write the region

**What you do.** Save `regions/starter.json`:

```json
{
  "name": "Starter",
  "landBiomes": ["starter"],
  "seaBiomes": ["starter"],
  "shoreBiomes": ["starter"]
}
```

**Why.** A region is the biome pool for one area of the world. Iris decides land versus sea first, then picks from the matching list, so a region with an empty `seaBiomes` cannot fill an ocean column. Listing the same biome in all three lists means every column resolves no matter which category the terrain lands in — exactly what you want for a first test world.

| Field | Why |
|-------|-----|
| `name` | Required display name |
| `landBiomes` | Required. Root-level biome keys only |
| `seaBiomes` / `shoreBiomes` | Optional for genuinely land-only packs; included here so no column can fail to resolve |
| `caveBiomes` | Optional; not needed until caves are enabled |

List only root parents here. Child biomes are declared on their parent biome's `children`, not on the region.

**What you should see.** Nothing yet — but if you validate now, an unresolvable biome key is reported as a blocking error, which is the fastest way to catch a typo.

## 4. Write the biome

**What you do.** Save `biomes/starter.json`:

```json
{
  "name": "Starter Plains",
  "derivative": "minecraft:plains",
  "vanillaDerivative": "minecraft:plains",
  "layers": [
    {
      "palette": [{ "block": "minecraft:grass_block" }]
    }
  ],
  "generators": [
    {
      "generator": "flat",
      "min": 96,
      "max": 96
    }
  ]
}
```

**Why.** The biome supplies two things: a height, from its generator links, and a surface material stack, from its layers.

| Field | Why |
|-------|-----|
| `name` | Required display name; `/iris what biome` prints it |
| `derivative` | Required. The vanilla biome this maps to for client-side coloring, mob spawning tables and vanilla feature eligibility. The engine default is `minecraft:the_void`, which generates nothing useful, so always set it |
| `vanillaDerivative` | The derivative used for native structure selection. When left undefined it falls back to `derivative`; set it explicitly when a biome should look like one thing and attract another thing's structures |
| `layers` | Required. The surface stack from the top down. Each layer's `minHeight`/`maxHeight` are **thickness in blocks**, not Y coordinates, and default to 1. Everything below the declared layers is filled with the dimension's rock palette |
| `generators` | Links to `generators/<key>.json` with a height band. `min` and `max` are offsets **from `fluidHeight`**, not absolute Y |

With `fluidHeight` 63 and `min` = `max` = 96, every column resolves to exactly 96 above sea level, so the surface lands at world Y 159 — a high flat plateau with the ocean far below it. That is deliberate: it makes the terrain obviously generated rather than accidentally matching vanilla. For plains near sea level use small values instead; the shipping overworld's plains biome uses `min` 4 / `max` 10 on generator `plain`.

## 5. Write the generator

**What you do.** Save `generators/flat.json`:

```json
{
  "interpolator": { "function": "NONE", "horizontalScale": 1 },
  "seed": 310,
  "composite": [
    {
      "seed": 310,
      "style": { "style": "FLAT" }
    }
  ]
}
```

**Why.** A generator turns coordinates into a 0-to-1 noise value, which the biome's `min`/`max` band then maps into a height. `FLAT` returns a constant, and because `min` equals `max` the mapping is constant anyway, so the result is a perfectly level surface. The interpolator controls how neighbouring biomes blend their heights together; `NONE` gives hard edges, which is what you want while proving the plumbing. This file matches the shipping overworld's `generators/flat.json` and the studio starter byte for byte.

| Field | Why |
|-------|-----|
| `seed` | Required. Changing it re-rolls this generator's noise independently of the world seed |
| `interpolator` | Required. `NONE` for a hard flat baseline; swap to a bilinear or starcast function once real terrain matters |
| `composite` | The noise layers that are summed into the final value. One `FLAT` layer here |

## 6. Validate

**What you do.**

- Bukkit: `/iris pack validate pack=minimal`
- Modded: `/iris pack validate minimal`

**Why.** Studio refuses to open a pack whose validation result is not loadable, and fails closed if validation never ran. Catching a broken key here costs seconds; catching it after a world exists costs a world.

**What you should see.** No blocking errors. If validation reports a missing region or biome, the key in the parent file and the file path under the type folder disagree — compare them character for character, including the folder prefix.

Validation checks that the dimension load key resolves, that every region key in `regions` loads, that every biome key on a region loads, and that structure placements fit inside the declared height range. It does **not** check the multiple-of-16 rule on `dimensionHeight`; a bad height passes here and fails later when Iris compiles the dimension type.

## 7. Prove it in Studio

**What you do.**

1. Open: Bukkit `/iris studio open minimal seed=1337`, modded `/iris studio open minimal 1337`.
2. Walk into chunks that have never generated.
3. Run `/iris what region` and `/iris what biome`.
4. Close Studio, reopen it on the same seed, and generate another new area.

**Why.** Studio runs directly off `packs/minimal/`, so it is the only place where an edit is visible without recreating anything. The fixed seed is what makes step 4 meaningful.

**What you should see.** A uniform grass surface at world Y 159, region `Starter`, biome `Starter Plains`, and no missing-resource or parse errors in console. After the reopen, the terrain in a fresh area must be identical to what the same coordinates produced before — if it is not, something in the pack is reading a non-deterministic input.

## 8. Prove it in a real world

**What you do.**

1. Create: Bukkit `/iris create minimal-test type=minimal seed=1337`, modded `/iris create minimal-test minimal 1337`. On Folia, creation stages the world and requires the instructed restart before you can enter it.
2. Teleport: Bukkit `/iris tp minimal-test`, modded `/iris tp irisworldgen:minimal-test`.
3. Generate ordinary new chunks and confirm the same flat grass result you saw in Studio.
4. Stop the server cleanly, start it again, teleport back, and generate another new area.
5. Confirm `<world>/iris/pack/` contains the four-file snapshot.

**Why.** World creation copies the pack into the world folder. From then on that world generates from its own copy, so later edits under `packs/minimal/` do not reach it. The restart in step 4 is what proves the generated dimension type survives a registry reload, which is the most common way a height or environment mistake surfaces.

**What you should see.** Identical terrain in Studio and in the world, no pack or registry errors on the restart, and a real `iris/pack/` directory inside the world folder.

The walkthrough passes only when validation, the Studio reopen, world creation, teleport, and the server restart all succeed.

## 9. Extend without breaking the baseline

Add one thing at a time and re-validate after each, so a broken key is always attributable to the last edit.

| Add | Where |
|-----|-------|
| Second biome | New `biomes/*.json`, then append its key to `regions/starter.json` → `landBiomes` |
| Sea and shore variety | Distinct biome keys on `seaBiomes` / `shoreBiomes` |
| Real terrain | Replace `generators/flat.json` with a composite noise generator, or add a second generator and give the biome a wider `min`/`max` band (`14 - Generators & Noise.md`) |
| Caves | `caveProfile` on the dimension, plus cave biomes on the region's `caveBiomes` (`15 - Caves & Carving.md`) |
| Decorators | Biome `decorators` array, inline or `snippet/decorator/...` (`16 - Surfaces, Decorators & Deposits.md`) |
| Loot | `loot/*.json` plus a `loot` reference on the dimension, region or biome (`23 - Loot, Entities, Spawners, Markers.md`) |
| Objects | Biome or region `objects` placements plus `objects/*.iob` (`19 - Objects.md`, `20 - Object Placement.md`) |
| Entity spawning | `entities/`, `spawners/`, then `entitySpawners` on the dimension, region or biome |

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Pack is not listed | Platform packs root, the `minimal/` folder name, and `dimensions/minimal.json` |
| Validation reports a missing region | `dimensions/minimal.json` must reference `starter` and `regions/starter.json` must exist |
| Validation reports a missing biome | Every region list entry must match a file under `biomes/` with the `.json` removed, including any folder prefix |
| Terrain is empty or at the wrong height | Confirm the biome's generator key is `flat`, that `generators/flat.json` parses, and that `min`/`max` are the offsets from `fluidHeight` you intended |
| The world is all void | `derivative` is probably still the `minecraft:the_void` default on some biome |
| Studio shows old terrain | Move to untouched chunks; close and reopen after a contract change |
| Dimension type fails to compile | `dimensionHeight` span or minimum is not a multiple of 16, or `logicalHeight` exceeds the span |
| Production world ignores your edits | It runs from `<world>/iris/pack/`; create a new world, or follow the backed-up update procedure in `25 - Pack Management.md` |
| Baseline stops working | Restore these exact four files and validate before reintroducing extensions |

## Next steps

- Full dimension field reference: `11 - Dimensions.md`
- Region zooms, deposits, caves: `12 - Regions.md`
- Layers, decorators, children: `13 - Biomes.md`
- Noise composites and interpolators: `14 - Generators & Noise.md`
- Editing the full shipping overworld: `27 - Example - Configuring Overworld.md`

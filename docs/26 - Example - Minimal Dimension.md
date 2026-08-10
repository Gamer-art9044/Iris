# 26 - Example - Minimal Dimension

This walkthrough builds a loadable pack with one dimension, one region, one biome, and one generator using real field names from `IrisDimension`, `IrisRegion`, `IrisBiome`, and `IrisGenerator`. The skeleton matches `StudioSVC.createStarterProject` and is expanded with required mode and fluid height for explicit authoring.

Related: `05 - Concepts & Pack Layout.md`, `02 - Getting Started.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `25 - Pack Management.md`, `04 - Commands & Permissions.md`.

## Tutorial result

You will create four files under `packs/minimal/`, validate them, open them in Studio on seed `1337`, and create a disposable production world. Do not add objects, caves, structures, custom biomes, or datapacks until this exact baseline generates and reloads.

Prerequisites:

- Iris is running and its default data folders exist.
- You have operator access on Bukkit or gamemaster access on a mod loader.
- No pack or world already uses the keys `minimal` or `minimal-test`.
- You can inspect the server console while validation, Studio open, world create, and restart run.

## 1. Create the pack root

Create this tree relative to the platform packs root:

```
minimal/
  dimensions/minimal.json
  regions/starter.json
  biomes/starter.json
  generators/flat.json
```

The pack folder name is the pack key. The dimension file name without `.json` is the dimension load key (`minimal`).

### Creation methods

| Platform / method | Command or action |
|-------------------|-------------------|
| Bukkit Studio starter | `/iris studio create name=minimal` |
| Modded default-template copy | `/iris studio create minimal` — copies the `example` template |
| Bukkit template copy | `/iris studio create name=minimal template=overworld` |
| Modded template copy | `/iris studio create minimal overworld` |
| Manual | Create the four folders and JSON files under the platform packs root |

On Bukkit, Studio create without a template writes a starter project with the same four resource types. Modded Studio create defaults to the installed or downloadable `example` template. Create the tree manually when you need the exact four-file baseline on every platform; use the create commands when extra template content is acceptable.

Platform packs roots (same layout):

- Bukkit-family: `plugins/Iris/packs/`
- Fabric / Forge / NeoForge: `config/irisworldgen/packs/`

## 2. Write the four resources

### `dimensions/minimal.json`

```json
{
  "name": "minimal",
  "version": 1,
  "mode": { "type": "OVERWORLD" },
  "regions": ["starter"],
  "fluidHeight": 63,
  "logicalHeight": 384,
  "dimensionHeight": { "min": -64, "max": 320 }
}
```

Required / load-bearing fields:

| Field | Why |
|-------|-----|
| `name` | Human-readable name (`@Required`, min length 2) |
| `regions` | At least one region load key |
| `mode` | `IrisDimensionMode` (`type`: `OVERWORLD`, `SUPERFLAT`, `ENCLOSURE`, `ISLANDS`) |
| `fluidHeight` | Sea level relative to dimension min (default 63 if omitted) |
| `dimensionHeight` | World Y bounds; default `-64`..`320` if omitted |
| `version` | Pack version stamp; change to discourage accidental upgrades |

Optional but useful for testing: `"focus": "starter"` forces a single biome; `"focusRegion": "starter"` forces one region.

### `regions/starter.json`

```json
{
  "name": "Starter",
  "landBiomes": ["starter"],
  "seaBiomes": ["starter"],
  "shoreBiomes": ["starter"]
}
```

| Field | Why |
|-------|-----|
| `name` | Required region name |
| `landBiomes` | Required root land biome keys |
| `seaBiomes` / `shoreBiomes` | Optional for land-only packs; starter includes them for full land/sea/shore coverage |
| `caveBiomes` | Optional list for cave biomes |

Do not list child biomes here — only root parents.

### `biomes/starter.json`

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

| Field | Why |
|-------|-----|
| `name` | Required display name |
| `derivative` | Required vanilla biome key for coloring / vanilla structure eligibility |
| `vanillaDerivative` | Structure selection derivative; falls back to `derivative` when null |
| `layers` | Surface material stack; remaining depth fills with stone |
| `generators` | Links to `generators/<key>.json` with height relative to fluid height |

`min`/`max` of 96 with fluid height 63 produce high flat land. For near-sea plains use smaller values (overworld plains use roughly `min` 4 / `max` 10 on generator `plain`).

### `generators/flat.json`

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

| Field | Why |
|-------|-----|
| `seed` | Required generator seed |
| `interpolator` | Cross-biome height blend; `NONE` for hard flat |
| `composite` | Noise layers; `FLAT` style yields constant mid-value height |

This matches shipping overworld `generators/flat.json` and the studio starter.

## Studio create vs this skeleton

`StudioSVC.createStarterProject` writes the same four files with pack name substituted for the dimension file/name. It omits explicit `mode` and `fluidHeight` (code defaults: mode `OVERWORLD`, fluid height `63`). The JSON above adds those fields so authors see the required contract.

## 3. Validate and open Studio

1. Ensure the pack sits under `minimal/` in the platform packs root with `dimensions/minimal.json`.
2. Validate with Bukkit `/iris pack validate pack=minimal` or modded `/iris pack validate minimal`. Do not open the pack while validation reports a blocking error.
3. Open Studio with Bukkit `/iris studio open minimal seed=1337` or modded `/iris studio open minimal 1337`.
4. Generate fresh chunks and run `/iris what region` and `/iris what biome`. The expected result is the `starter` region and biome over a uniform grass surface, with no missing-resource or parse errors in the console.
5. Close Studio, reopen it with the same seed, and generate another new area. The terrain height and surface must reproduce.

World create copies the pack into the world folder at `iris/pack/` (see `06 - Worlds & Lifecycle.md`). Studio worlds hotload the live pack under `packs/` — prefer studio for authoring.

## 4. Create and restart-test a disposable world

1. Create the world with Bukkit `/iris create minimal-test type=minimal seed=1337` or modded `/iris create minimal-test minimal 1337`. On Folia, creation stages the world and requires the instructed server restart before it can be entered.
2. Teleport with Bukkit `/iris tp minimal-test` or modded `/iris tp irisworldgen:minimal-test`.
3. Generate ordinary new chunks and confirm the same flat grass result seen in Studio.
4. Stop the server cleanly, start it again, teleport back, and generate another new area.
5. Confirm `<world>/iris/pack/` contains the four-file snapshot. Production generation reads this copy, so later authoring changes under `packs/minimal/` do not change the existing world automatically.

The tutorial passes only when validation, Studio reopen, production create, teleport, and server restart all succeed. Keep this four-file version as a rollback checkpoint before extending the pack.

## 5. Extend without breaking the minimal set

| Add | Where |
|-----|-------|
| Second biome | New `biomes/*.json`, append key to `regions/starter.json` `landBiomes` |
| Sea variety | Distinct biome keys on `seaBiomes` / `shoreBiomes` |
| Loot | `loot/*.json` + dimension/region/biome `loot` reference (`23 - Loot, Entities, Spawners, Markers.md`) |
| Decorators | Biome `decorators` array (inline or `snippet/decorator/...`) |
| Objects | Biome/region `objects` placements + `objects/*.iob` (`19 - Objects.md`, `20 - Object Placement.md`) |
| Entity spawn | `entities/`, `spawners/`, then `entitySpawners` on dim/region/biome |

## Troubleshooting and recovery

| Symptom | Check / recovery |
|---------|------------------|
| Pack is not listed | Confirm the platform packs root, `minimal/` folder, and `dimensions/minimal.json` |
| Validation reports a missing region | `dimensions/minimal.json` must reference `starter`, and `regions/starter.json` must exist |
| Validation reports a missing biome | Every region list entry must match a file under `biomes/` without `.json` |
| Terrain is empty or at the wrong height | Confirm the biome generator key is `flat`, the generator parses, and biome `min` / `max` remain `96` |
| Studio shows old terrain | Generate untouched chunks; close and reopen Studio after contract changes |
| Production world ignores edits | It uses `<world>/iris/pack/`; create a new world or follow the backed-up update procedure in `25 - Pack Management.md` |
| Baseline no longer works | Restore the four exact files in this guide and validate before reintroducing extensions |

Validation invariants:

- Dimension load key must match a file under `dimensions/`.
- Every region key in `regions` must load.
- Every biome key listed on a region must load.
- Every `generators[].generator` key must load or the engine falls back to an empty default generator.
- `derivative` must be a known biome registry key such as `minecraft:plains`.

## Cross-links for next steps

- Full dimension options: `11 - Dimensions.md`
- Region zooms, deposits, caves: `12 - Regions.md`
- Layers, decorators, structures: `13 - Biomes.md`
- Noise composite detail: `14 - Generators & Noise.md`
- Editing the full overworld pack: `27 - Example - Configuring Overworld.md`

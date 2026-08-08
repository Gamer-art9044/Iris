# 26 - Example - Minimal Dimension

This walkthrough builds a loadable pack with one dimension, one region, one biome, and one generator using real field names from `IrisDimension`, `IrisRegion`, `IrisBiome`, and `IrisGenerator`. The skeleton matches `StudioSVC.createStarterProject` and is expanded with required mode and fluid height for explicit authoring.

Related: `05 - Concepts & Pack Layout.md`, `02 - Getting Started.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `25 - Pack Management.md`, `04 - Commands & Permissions.md`.

## Goal pack layout

```
packs/minimal/
  dimensions/minimal.json
  regions/starter.json
  biomes/starter.json
  generators/flat.json
```

Pack folder name is the pack key. Dimension file name without `.json` is the dimension load key (`minimal`).

## Create options

| Method | Command / action |
|--------|------------------|
| Studio create (code template) | `/iris studio create name=minimal` — writes starter files under `packs/` |
| Studio create from template | `/iris studio create name=minimal template=overworld` — copies existing pack |
| Manual | Create folders and JSON under the platform packs directory |

Studio create without a template writes the starter project shown below (dimension/region/biome/generator only). After create, open studio: `/iris studio open minimal`.

Platform packs roots (same layout):

- Bukkit-family: `plugins/Iris/packs/`
- Fabric / Forge / NeoForge: `config/irisworldgen/packs/`

## File contents

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

## Run the pack

1. Ensure the pack sits under `packs/minimal/` with `dimensions/minimal.json`.
2. Validate: `/iris pack validate pack=minimal` (Bukkit).
3. Create a world: `/iris create myworld type=minimal` (Bukkit) or `/iris create myworld minimal` (modded).
4. Or open studio: `/iris studio open minimal` for hotload editing.

World create copies the pack into the world folder at `iris/pack/` (see `06 - Worlds & Lifecycle.md`). Studio worlds hotload the live pack under `packs/` — prefer studio for authoring.

## Extend without breaking the minimal set

| Add | Where |
|-----|-------|
| Second biome | New `biomes/*.json`, append key to `regions/starter.json` `landBiomes` |
| Sea variety | Distinct biome keys on `seaBiomes` / `shoreBiomes` |
| Loot | `loot/*.json` + dimension/region/biome `loot` reference (`23 - Loot, Entities, Spawners, Markers.md`) |
| Decorators | Biome `decorators` array (inline or `snippet/decorator/...`) |
| Objects | Biome/region `objects` placements + `objects/*.iob` (`19 - Objects.md`, `20 - Object Placement.md`) |
| Entity spawn | `entities/`, `spawners/`, then `entitySpawners` on dim/region/biome |

## Validation notes

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

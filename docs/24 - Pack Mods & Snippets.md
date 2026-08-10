# 24 - Pack Mods & Snippets

Snippets are active reusable JSON fragments for types annotated `@Snippet`; fields accept either an inline object or a path under `snippet/<type>/`. Iris also loads the legacy `IrisMod` JSON schema from `mods/`, but no engine path applies those injector or replacer fields at runtime.

Related: `05 - Concepts & Pack Layout.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `20 - Object Placement.md`, `25 - Pack Management.md`.

## Tutorial: reuse one active decorator snippet

Snippets are the executable reuse mechanism on this page. Start with a validating pack and a biome that already generates correctly. Save this complete decorator as `snippet/decorator/tutorial-wildflowers.json`:

```json
{
  "chance": 0.08,
  "palette": [
    { "block": "minecraft:dandelion" },
    { "block": "minecraft:poppy" }
  ],
  "slopeCondition": { "maximumSlope": 4 }
}
```

Reference it from the existing biome's `decorators` array without `.json`:

```json
{
  "decorators": ["snippet/decorator/tutorial-wildflowers"]
}
```

1. Validate the pack and open it in Studio on a fixed seed.
2. Generate new chunks in the target biome. Success is both flower types appearing only on slopes accepted by the snippet, with no missing-snippet error.
3. If the field resolves to null, confirm the singular `snippet/` folder, the exact `decorator` type folder, and the suffix-free reference. If the snippet loads but does not place, raise `chance` temporarily and verify dimension `decorate` is true.
4. Reuse the same string in another biome only after the first placement works. Generate the VSCode workspace so schema completion exposes valid snippet paths.

Do not implement this workflow with `mods/*.json`. Pack-mod files remain parseable schema data but are not applied by engine creation or Studio hotload.

## Pack mod schema (`IrisMod`, inactive)

Folder: `mods/`. The loader key is the path under `mods/` without `.json`. `IrisData` can parse and expose these registrants to schema and tooling paths, but engine creation and Studio hotload do not consume them. Treat the fields below as an inactive schema, not a supported way to modify a dimension.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `name` | string | `"A Pack Modification"` | Required human name (min length 2) |
| `forDimension` | string | `""` | Optional dimension load key; empty = any dimension |
| `overrideFluidHeight` | int -1..512 | `-1` | `-1` leaves fluid height unchanged |
| `removeBiomes` | string[] | `[]` | Biome keys to remove |
| `removeObjects` | string[] | `[]` | Object keys to remove |
| `removeRegions` | string[] | `[]` | Region keys to remove |
| `injectRegions` | string[] | `[]` | Region keys to inject into the dimension |
| `biomeInjectors` | `IrisModBiomeInjector[]` | `[]` | Inject biomes into a region |
| `biomeReplacers` | `IrisModBiomeReplacer[]` | `[]` | Swap biomes |
| `objectReplacers` | `IrisModObjectReplacer[]` | `[]` | Swap object keys |
| `biomeObjectPlacementInjectors` | `IrisModObjectPlacementBiomeInjector[]` | `[]` | Inject object placements into a biome |
| `regionObjectPlacementInjectors` | `IrisModObjectPlacementRegionInjector[]` | `[]` | Inject object placements into a region |
| `regionReplacers` | `IrisModRegionReplacer[]` | `[]` | Swap regions |
| `blockReplacers` | `IrisObjectReplace[]` | `[]` | Block find/replace rules (same shape as object material replacers) |
| `styleReplacers` | `IrisModNoiseStyleReplacer[]` | `[]` | Replace `NoiseStyle` usages |

### Injector and replacer shapes

**Biome injector** (`@Snippet("biome-injector")`):

```json
{ "region": "temperate", "inject": ["temperate/meadows"] }
```

**Biome replacer** (`biome-replacer`):

```json
{ "find": ["temperate/plains"], "replace": "temperate/lush-plains" }
```

**Region replacer** (`region-replacer`):

```json
{ "find": ["temperate"], "replace": "forests" }
```

**Object replacer** (`object-replacer`):

```json
{ "find": ["clutter/camp1"], "replace": "clutter/camp3" }
```

**Object placement biome injector** (`object-placement-biome-injector`):

```json
{
  "biome": "temperate/plains",
  "place": [{ "chance": 0.01, "place": ["clutter/camp1"] }]
}
```

**Object placement region injector** (`object-placement-region-injector`): field name is `biome` in code but the registry type is `IrisRegion` (region load key):

```json
{
  "biome": "temperate",
  "place": [{ "chance": 0.01, "place": ["clutter/camp1"] }]
}
```

**Noise style replacer** (`noise-style-replacer`):

| Field | Notes |
|-------|-------|
| `find` | `NoiseStyle` enum value to match |
| `replaceTypeOnly` | When true, only swap the style type and keep other style fields |
| `replace` | Full `IrisGeneratorStyle` replacement |

**Block replacer** (reuses `IrisObjectReplace`, snippet `object-block-replacer`): `find` block list, `replace` palette, optional `exact`, `chance` 0..1.

### Schema example

`mods/example-swap.json`:

```json
{
  "name": "Example Temperate Swap",
  "forDimension": "overworld",
  "biomeReplacers": [
    {
      "find": ["temperate/plains"],
      "replace": "temperate/meadows"
    }
  ],
  "biomeInjectors": [
    {
      "region": "temperate",
      "inject": ["temperate/shattered-plains"]
    }
  ]
}
```

The example is parseable as `IrisMod`, but it has no effect on generated terrain. Apply equivalent changes directly to the target dimension, region, biome, generator, or object-placement JSON.

## Snippets

### Mechanism

1. Many nested pack types carry `@Snippet("type-name")`.
2. Gson type adapters in `IrisData` intercept those types on read.
3. A field may be either:
   - an inline JSON object of that type, or
   - a **string** `"snippet/<type-name>/<path>"` that loads `snippet/<type-name>/<path>.json` from the pack root.
4. If the string starts with `snippet/` but uses a different type folder, the loader rewrites to the expected `snippet/<type-name>/` prefix for that field.
5. Missing snippet files log an error and yield null for that value.

Studio schemas (`SchemaBuilder`) expose every snippet as `anyOf` object-or-string and list files under `snippet/<type>/` in the workspace enum.

### Disk layout

```
pack/
  snippet/
    decorator/
      bush.json
      dry_grass.json
      ...
    style/
      bedrock.json
      deepslate.json
```

Folder is singular `snippet/`, not `snippets/`. Subfolders match the `@Snippet` value exactly.

### Overworld usage

Dimension ores reference style snippets:

```json
{
"chanceStyle": "snippet/style/bedrock"
}
```

`snippet/style/bedrock.json`:

```json
{ "style": "STATIC" }
```

Biome decorators accept snippet strings in arrays:

```json
{
"decorators": [
  "snippet/decorator/wildflowers",
  "snippet/decorator/bush"
]
}
```

`snippet/decorator/bush.json`:

```json
{
  "chance": 0.03,
  "style": {
    "style": "CLOVER_HERMITE",
    "zoom": 0.52,
    "exponent": 2.5,
    "axialFracturing": true
  },
  "slopeCondition": { "maximumSlope": 5 },
  "palette": [
    { "block": "minecraft:bush", "weight": 1 },
    { "block": "minecraft:air", "weight": 4 }
  ]
}
```

`biomes/dev.json` uses the same pattern for a minimal decorator list.

### `@Snippet` type names (engine/object)

Each value is the folder name under `snippet/` and the string prefix after `snippet/`:

| Snippet value | Class (representative) |
|---------------|------------------------|
| `attribute-modifier` | `IrisAttributeModifier` |
| `axis-rotation` | `IrisAxisRotationClamp` |
| `biome-injector` | `IrisModBiomeInjector` |
| `biome-palette` | `IrisBiomePaletteLayer` |
| `biome-replacer` | `IrisModBiomeReplacer` |
| `block-drops` | `IrisBlockDrops` |
| `cave-field-module` | `IrisCaveFieldModule` |
| `cave-profile` | `IrisCaveProfile` |
| `color` | `IrisColor` |
| `command` | `IrisCommand` |
| `command-registry` | `IrisCommandRegistry` |
| `coral` | `IrisCoral` |
| `crystal` | `IrisCrystal` |
| `custom-biome` | `IrisBiomeCustom` |
| `custom-biome-particle` | `IrisBiomeCustomParticle` |
| `custom-biome-spawn` | `IrisBiomeCustomSpawn` |
| `decorator` | `IrisDecorator` |
| `deposit` | `IrisDepositGenerator` |
| `deposit-variant` | `IrisDepositVariant` |
| `dimension-carving-entry` | `IrisDimensionCarvingEntry` |
| `dimension-mode` | `IrisDimensionMode` |
| `duration` | `IrisDuration` |
| `effect` | `IrisEffect` |
| `enchantment` | `IrisEnchantment` |
| `entity-spawn` | `IrisEntitySpawn` |
| `expression-function` | `IrisExpressionFunction` |
| `expression-load` | `IrisExpressionLoad` |
| `floating-child-biome` | `IrisFloatingChildBiomes` |
| `formation` | `IrisFormation` |
| `fungus` | `IrisFungus` |
| `generator` | `IrisNoiseGenerator` |
| `generator-layer` | `IrisBiomeGeneratorLink` |
| `image-map` | `IrisImageMap` |
| `loot` | `IrisLoot` |
| `loot-registry` | `IrisLootReference` |
| `noise-style-replacer` | `IrisModNoiseStyleReplacer` |
| `object-block-replacer` | `IrisObjectReplace` |
| `object-limit` | `IrisObjectLimit` |
| `object-loot` | `IrisObjectLoot` |
| `object-marker` | `IrisObjectMarker` |
| `object-placement-biome-injector` | `IrisModObjectPlacementBiomeInjector` |
| `object-placement-region-injector` | `IrisModObjectPlacementRegionInjector` |
| `object-placer` | `IrisObjectPlacement` |
| `object-replacer` | `IrisModObjectReplacer` |
| `object-rotator` | `IrisObjectRotation` |
| `object-scale` | `IrisObjectScale` |
| `object-translator` | `IrisObjectTranslate` |
| `object-vanilla-loot` | `IrisObjectVanillaLoot` |
| `palette` | `IrisMaterialPalette` |
| `position-3d` | `IrisPosition` |
| `potion-effect` | `IrisPotionEffect` |
| `procedural-objects` | `IrisProceduralObjects` |
| `procedural-tree` | `IrisProceduralTree` |
| `range` | `IrisRange` |
| `rate` | `IrisRate` |
| `region-replacer` | `IrisModRegionReplacer` |
| `ruin` | `IrisRuin` |
| `ruin-decorator` | `IrisRuinDecorator` |
| `shaped-style` | `IrisShapedGeneratorStyle` |
| `slope-clip` | `IrisSlopeClip` |
| `stilt-settings` | `IrisStiltSettings` |
| `style` | `IrisGeneratorStyle` |
| `style-range` | `IrisStyledRange` |
| `time-block` | `IrisTimeBlock` |
| `tree` | `IrisTree` |
| `tree-branches` | `IrisTreeBranches` |
| `tree-canopy` | `IrisTreeCanopy` |
| `tree-decorator` | `IrisTreeDecorator` |
| `tree-layer` | `IrisTreeLayer` |
| `tree-secondary-leaf` | `IrisTreeSecondaryLeaf` |
| `tree-settings` | `IrisTreeSettings` |
| `tree-size` | `IrisTreeSize` |
| `tree-sub-branches` | `IrisTreeSubBranches` |
| `vacuum-settings` | `IrisVacuumSettings` |

Registrants that are whole files (dimensions, regions, biomes, generators, loot tables, entities, spawners, markers, mods, objects, structures) are not snippet types; only nested field types listed above are.

### Registered schemas without a production authoring path

Schema registration alone does not prove a runtime consumer. The following types are discoverable by loaders or Studio schema generation but are not supported pack features:

| Surface | Current status |
|---------|----------------|
| `potion-effect` / `IrisPotionEffect` | Snippet schema exists, but no production field consumes this type; use the potion fields on `IrisEffect` instead |
| `matter/` resources | A loader exists for Matter binaries, but generation and runtime code do not consume pack `matter/` resources |
| `IrisObjectPlacement.translateCenter` | Serialized and copied by `toPlacement`, but no placement path reads the value |

The `mods/*.json` family is likewise schema/tooling-only as documented above.

### Tutorial: author and verify a snippet

1. Copy one working inline value into `snippet/<type>/<name>.json`; the folder must match the field's `@Snippet` type.
2. Replace one original value with `"snippet/<type>/<name>"` (no `.json` suffix).
3. Validate and open Studio. Confirm schema completion lists the path and fixed-seed output matches the inline version.
4. Replace the second duplicate only after the first call site passes.
5. Change one value inside the snippet and confirm both call sites change on newly generated chunks, then restore the intended value.

Use snippets for values genuinely shared across biomes, such as decorators, styles, and palettes. A missing or wrong-type snippet resolves to null after an error, so treat validation and console output as required gates.

## Related commands

- Pack validation: `/iris pack validate` — see `25 - Pack Management.md`, `04 - Commands & Permissions.md`.
- Studio open/hotload: `10 - Studio & VSCode Schemas.md`.

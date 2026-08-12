# 24 - Pack Mods & Snippets

Snippets let you write a nested JSON value once and reference it by path from as many places as you like. Any pack type annotated `@Snippet` accepts either an inline object or a string pointing at a file under `snippet/<type>/`. Iris also still loads the older `IrisMod` schema from `mods/`, but nothing in the engine applies those injectors or replacers — treat that folder as dead weight.

Related: `05 - Concepts & Pack Layout.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `20 - Object Placement.md`, `25 - Pack Management.md`.

## The mental model

Most of a pack is nested objects: a decorator inside a biome, a noise style inside a generator, a palette inside a decorator. When two biomes want the same decorator you'd normally copy the JSON, and then you have two copies to keep in sync.

Snippets fix that at the deserializer level. Iris registers a Gson type adapter for every class carrying `@Snippet("some-name")`. When that adapter reads a field and finds a **string** instead of an object, it treats the string as a path, opens `snippet/some-name/<path>.json`, and parses the file's contents as the field's value. Nothing else changes: the biome still ends up holding a real decorator object, the engine never knows the difference, and the value is resolved once at load time rather than looked up per chunk.

Two consequences worth internalising:

- **Snippets are load-time only.** Editing a snippet file does nothing until the pack reloads — Studio hotload, world reload, or a restart. There is no live indirection.
- **Snippets vanish on serialization.** When Iris writes a pack back out (Studio saves, the Bukkit packager), the adapter writes the resolved object, not the string. Snippet references get inlined. See "Packaging" below.

## Walkthrough: share a palette across biomes

The goal is one decorator definition placing wildflowers in several biomes, with a single file to edit. Prerequisites: a validating pack and a biome that already generates.

**1. Write the snippet.** The folder name must match the `@Snippet` value of the field you'll use it in — a decorator field wants `snippet/decorator/`. Save `snippet/decorator/tutorial-wildflowers.json`:

```json
{
  "chance": 0.08,
  "style": {
    "style": "CLOVER_HERMITE",
    "zoom": 0.52,
    "exponent": 2.5
  },
  "slopeCondition": { "maximumSlope": 4 },
  "palette": [
    { "block": "minecraft:dandelion", "weight": 2 },
    { "block": "minecraft:poppy", "weight": 1 },
    { "block": "minecraft:air", "weight": 4 }
  ]
}
```

**2. Reference it.** In the biome, replace the inline decorator with the path. No `.json` suffix:

```json
{
  "decorators": ["snippet/decorator/tutorial-wildflowers"]
}
```

**3. Verify one call site.** Validate the pack, then open Studio on a fixed seed and generate fresh chunks in that biome. Success is both flowers appearing only on slopes the snippet allows, with no "Couldn't find snippet" line in the console. If the field resolves to null, the console names the path it tried — compare it against the file on disk.

**4. Add the second call site.** Only once the first one works. Paste the same string into another biome's `decorators`.

**5. Prove they're linked.** Change one value inside the snippet — raise `chance` to `0.3` — hotload, and generate fresh chunks in both biomes. Both should get denser. Restore the value afterwards. That round trip is the actual test that you have one definition and not two.

Generate the VSCode workspace (`/iris studio vscode`) so schema completion offers valid snippet paths for each field. See `10 - Studio & VSCode Schemas.md`.

## How resolution works

1. Iris registers a type adapter for every class annotated `@Snippet("type-name")`.
2. On read, if the JSON token is an object, the adapter parses it normally. Nothing snippet-specific happens.
3. If the token is a **string**, the adapter treats it as a path:
   - The string must start with `snippet/`. If it doesn't, the field resolves to **null with no error message** — this is the failure mode that looks like the field was ignored.
   - The literal `snippet/` prefix is stripped and replaced with `snippet/<type-name>/` for the field being read. Only the prefix is rewritten; the rest of the path is kept verbatim. So writing `"snippet/style/bedrock"` in a `decorator` field becomes a lookup for `snippet/decorator/style/bedrock.json`, which won't exist. The rewrite is a convenience for the common `snippet/<correct-type>/…` case, not a search across type folders.
   - The file is read from the pack root. A missing file logs `Couldn't find snippet <path>` and the field resolves to null.
4. Snippet files are parsed with the same adapters, so a snippet can reference other snippets in its own nested fields.
5. Files may sit in subfolders under the type folder; the path in the reference is everything after `snippet/<type>/`, with forward slashes.

Studio schema generation exposes every snippet type as an `anyOf` of "object or string", and fills the string branch's enum from the files actually present under `snippet/<type>/`.

### Disk layout

```
pack/
  snippet/
    decorator/
      bush.json
      dry_grass.json
      forest/
        fern.json
    style/
      bedrock.json
      deepslate.json
```

The folder is singular `snippet/`, not `snippets/`. Subfolder names must match the `@Snippet` value exactly.

### Overworld usage

Dimension ore deposits reference a style snippet:

```json
{
  "chanceStyle": "snippet/style/bedrock"
}
```

`snippet/style/bedrock.json`:

```json
{ "style": "STATIC" }
```

Biome decorator lists take snippet strings as array elements, mixed freely with inline objects:

```json
{
  "decorators": [
    "snippet/decorator/wildflowers",
    "snippet/decorator/bush",
    { "chance": 0.01, "palette": [{ "block": "minecraft:sweet_berry_bush" }] }
  ]
}
```

`biomes/dev.json` in the shipping pack uses the same pattern for a minimal decorator list.

## When to use a snippet

Use one when the same value genuinely appears in more than one place and should change in lockstep: decorators shared across a climate's biomes, a noise style reused by several generators, a palette that defines a pack's stone. That's what the shipping Overworld pack uses them for.

Skip it when a value appears once. A snippet reference costs a file open and adds a place to look when something goes wrong, and it buys nothing if there's a single call site. It also makes the failure mode worse: a wrong-type or missing snippet resolves to null after logging, so a field that should have had a value silently has none. Treat pack validation and a clean console as required gates whenever you add or move snippet files.

## Packaging and snippets

Because the adapter writes resolved objects rather than strings, exported packs handle snippets differently per platform:

- **Bukkit `/iris studio package`** re-serializes the loaded object graph, so snippet references are inlined into the dimension, region, biome, and generator JSON. The export has no `snippet/` folder and doesn't need one.
- **Modded `/iris studio package`** copies the source JSON files verbatim and does **not** copy `snippet/`, so snippet references in a modded export are dangling.

See `25 - Pack Management.md` for the full export contents and the gaps in both compilers.

## `@Snippet` type names

Each value is the folder name under `snippet/` and the required prefix for references to that field.

| Snippet value | Class |
|---------------|-------|
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

Whole-file registrants — dimensions, regions, biomes, generators, loot tables, entities, spawners, markers, mods, objects, structures — are not snippet types. They already have their own folders and are referenced by key. Only nested field types appear above.

## Pack mods (`IrisMod`) — schema only, not applied

Folder: `mods/`. The load key is the path under `mods/` without `.json`. `IrisData` registers a loader for these files, so they parse, appear in tooling, and show up in generated schemas — but no engine path reads them. Neither world creation nor Studio hotload consumes an `IrisMod`. A `mods/*.json` file that looks correct will change nothing about the terrain you generate.

To get the same effect, edit the target dimension, region, biome, generator, or object placement directly. If you need the same edit applied to several packs, keep the edits in version control rather than expecting the mod schema to layer them at runtime.

The fields below are documented because they still appear in schema completion and because packs in the wild contain them, not because they work.

| Field | Type | Default | Intended meaning |
|-------|------|---------|------------------|
| `name` | string | `"A Pack Modification"` | Human name, at least 2 characters |
| `forDimension` | string | `""` | Dimension load key to scope to; empty means any |
| `overrideFluidHeight` | int -1..512 | `-1` | Fluid height override; `-1` leaves it alone |
| `removeBiomes` | string[] | `[]` | Biome keys to strip |
| `removeObjects` | string[] | `[]` | Object keys to strip |
| `removeRegions` | string[] | `[]` | Region keys to strip |
| `injectRegions` | string[] | `[]` | Region keys to add to the dimension |
| `biomeInjectors` | `IrisModBiomeInjector[]` | `[]` | Add biomes to a region |
| `biomeReplacers` | `IrisModBiomeReplacer[]` | `[]` | Swap one biome for another |
| `objectReplacers` | `IrisModObjectReplacer[]` | `[]` | Swap object keys |
| `biomeObjectPlacementInjectors` | `IrisModObjectPlacementBiomeInjector[]` | `[]` | Add object placements to a biome |
| `regionObjectPlacementInjectors` | `IrisModObjectPlacementRegionInjector[]` | `[]` | Add object placements to a region |
| `regionReplacers` | `IrisModRegionReplacer[]` | `[]` | Swap regions |
| `blockReplacers` | `IrisObjectReplace[]` | `[]` | Block find/replace, same shape as object material replacers |
| `styleReplacers` | `IrisModNoiseStyleReplacer[]` | `[]` | Replace `NoiseStyle` usages |

Shapes of the nested types, all of which are also registered snippet types:

```json
{ "region": "temperate", "inject": ["temperate/meadows"] }
```
```json
{ "find": ["temperate/plains"], "replace": "temperate/lush-plains" }
```
```json
{ "find": ["temperate"], "replace": "forests" }
```
```json
{ "find": ["clutter/camp1"], "replace": "clutter/camp3" }
```
```json
{ "biome": "temperate/plains", "place": [{ "chance": 0.01, "place": ["clutter/camp1"] }] }
```

`IrisModObjectPlacementRegionInjector` uses the field name `biome` even though the value is a region load key. `IrisModNoiseStyleReplacer` takes `find` (a `NoiseStyle` enum value), `replace` (a full `IrisGeneratorStyle`), and `replaceTypeOnly` (swap only the style type and keep the rest of the style's fields).

## Other registered schemas with no runtime consumer

Schema registration alone doesn't prove there's a consumer. These are visible to loaders or schema generation but are not supported pack features:

| Surface | Status |
|---------|--------|
| `mods/*.json` (`IrisMod`) | Parsed and registered, never applied. See above |
| `potion-effect` / `IrisPotionEffect` | The snippet type exists, but no production field is typed as `IrisPotionEffect`. Use the `potionEffect`, `potionStrength`, and `potionTicks*` fields on `IrisEffect` instead |
| `matter/` resources | A loader exists for Matter binaries, but no generation or runtime path reads pack `matter/` resources |
| `IrisObjectPlacement.translateCenter` | Serialized and carried through placement copies, but no placement path reads the value |

## Related commands

- Pack validation: `/iris pack validate` — see `25 - Pack Management.md` and `04 - Commands & Permissions.md`.
- Studio open, hotload, and VSCode schema generation: `10 - Studio & VSCode Schemas.md`.

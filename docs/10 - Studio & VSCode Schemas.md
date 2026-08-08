# 10 - Studio & VSCode Schemas

Studio is Iris’s live pack-authoring workflow: open a pack as a transient world, edit JSON under `packs/<key>/`, and hotload changes without a full server restart. VSCode (or IntelliJ) gets JSON Schema bindings generated from the Java models so field names, enums, and pack resource keys autocomplete against the real loaders.

Related: see `04 - Commands & Permissions.md`, `05 - Concepts & Pack Layout.md`, `02 - Getting Started.md`, `25 - Pack Management.md`, `30 - Platform Differences.md`.

## What Studio Is

| Concept | Behavior |
|---------|----------|
| Pack workspace | Packs live under the platform data directory folder named `packs` (`StudioSVC.WORKSPACE_NAME`). |
| Studio world | Opened from a pack dimension key; uses a studio chunk generator with live file watching. |
| Hotload | On studio worlds only: a low-priority looper polls pack files; when content changes, `EngineHotloader` reloads the pack data and rebuilds engine runtime under a lifecycle lock. |
| Hotload contract | `IrisDimensionRuntimeContract` refuses hotload if dimension type key, min height, total height, or logical height change. Restart the world after those edits. |
| Non-studio worlds | No pack file watcher looper; production worlds keep the pack snapshot installed at create/update time. |

Studio settings in `settings.json` → `studio` (`IrisSettings.IrisSettingsStudio`):

| Key | Default | Meaning |
|-----|---------|---------|
| `openVSCode` | `true` | When true and the JVM is not headless, `open` / `vscode` may launch the desktop opener on the pack’s `*.code-workspace` file. |
| `disableTimeAndWeather` | `true` | Studio world time/weather lock preference. |
| `entitySpawning` | `true` | Whether studio entity spawning is allowed. |
| `autoStartDefaultStudio` | `false` | Auto-open default studio on boot when enabled. |

## Commands (Bukkit)

Root: `/iris studio` (aliases `std`, `s`). Implemented by `CommandStudio` + `StudioSVC`.

| Subcommand | Aliases | What it does |
|------------|---------|--------------|
| `open <dimension> [seed=1337]` | `o` | Close any open studio, open pack as studio world. Blocks if pack validation has blocking errors. |
| `close` | `x` | Close the active studio project/world. |
| `create [name=studio] [template=<dimension>]` | `+` | Create a new pack under `packs/<name>`. Optional template is another pack dimension key; without template, writes the starter skeleton (see below). |
| `vscode [dimension=default]` | `vsc` | Open the pack’s VSCode workspace (generates it if missing). |
| `update [dimension=default]` | | Rewrite `<pack>/<name>.code-workspace` and regenerate `.iris/schema/*` mappings. |
| `version [dimension=default]` | | Print dimension `version` field. |
| `package [dimension=default] [obfuscate=false] [minify=true]` | `pkg` | Compile pack into a distributable archive. |
| `importvanilla <dimension> [variants=3] [structures=true]` | `importv`, `iv` | Capture vanilla features/structures into the pack (Bukkit NMS). |
| `scoreboard` | `board`, `sidebar`, `sb` | Toggle studio debug scoreboard (player, must be in studio world). |
| `noise [generator=<key>] [seed=12345]` | `nmap` | External noise explorer GUI. |
| `map [world=<world>]` | `render` | External biome/terrain map GUI for an Iris world. |
| `regions [radius=500]` | | Sample region rarity over a chunk spiral (player in Iris world). |
| `loot [fast=false] [add=true]` | | Open a virtual chest with loot tables for the block under the player (studio). |
| `profile [dimension=default]` | | Write a pack performance profile report. |
| `spawn` / `summon` | | Spawn a pack entity definition at the player. |
| `stp` | | Teleport to the active studio world spawn in creative. |
| `objects` / `find-objects` | | Capture nearby chunk object placement report. |

Permissions and the full `/iris` tree: see `04 - Commands & Permissions.md`.

## Commands (Modded)

`/iris studio` on Fabric/Forge/NeoForge is implemented by `ModdedStudioCommands`. Supported: `create`/`+`, `open`/`o`, `close`/`x`, `tpstudio`/`stp`, `status`, `vscode`/`vsc`, `update`, `version`, `package`/`pkg`, `regions`, `noise`/`nmap`, `map`/`render`.

Bukkit-only (modded replies with a fixed message): `importvanilla`, `loot`, `profile`, `spawn`/`summon`, `objects`/`find-objects`.

## Creating a Pack (Starter Skeleton)

`/iris studio create name=mypack` (no template) writes:

```
packs/mypack/
  dimensions/mypack.json
  regions/starter.json
  biomes/starter.json
  generators/flat.json
  mypack.code-workspace
```

Starter dimension JSON (from `StudioSVC.createStarterProject`):

```json
{
  "name": "mypack",
  "version": 1,
  "regions": ["starter"],
  "logicalHeight": 384,
  "dimensionHeight": {"min": -64, "max": 320}
}
```

Starter region lists the same biome for land/sea/shore. Starter biome uses generator `flat`, layers with `minecraft:grass_block`, and derivatives `minecraft:plains`. Project names must normalize to safe pack folder names; reserved name `studio` is auto-renamed to a free suffix.

With a template: `/iris studio create name=mypack template=overworld` copies that pack tree (after optional download if missing).

## Studio Open Workflow

1. Resolve pack folder `packs/<dimensionKey>/` with a loadable `dimensions/<key>.json`.
2. Pack validation must not report blocking errors (`PackValidationRegistry`).
3. Close existing studio if open.
4. `IrisProject.open` creates a studio world bound to that pack folder (not a permanent production install copy for authoring).
5. Optional VSCode launch when `studio.openVSCode` is true.
6. Datapack install may require restart after create; message tells you to re-run `open` after restart when needed.

## Hotload Details

- Watcher runs only when `PlatformChunkGenerator.isStudio()` is true (`BukkitChunkGenerator` looper).
- On change: load a new `IrisData` from the same folder, reload the dimension key, validate hotload contract, build new engine runtime, retire previous data, refresh workspace/schemas, reload datapacks when a platform world is bound, broadcast client studio-hotload toast on failure/success.
- Complex-only rebuild (`hotloadComplex`) rebuilds `IrisComplex` without full pack reopen.
- Failed hotload rolls runtime back when possible and reports the error.

Do not change `dimensionHeight`, `logicalHeight`, or the dimension load/type key mid-session if you need live reload; restart the studio world after those edits.

## VSCode / JSON Schemas

`IrisCodeWorkspace` writes `<pack>/<packName>.code-workspace` with:

| Workspace setting | Value / purpose |
|-------------------|-----------------|
| `folders` | `[{ "path": "." }]` — pack root |
| `workbench.colorTheme` | `Monokai` |
| `files.autoSave` | `onFocusChange` |
| `[json]` editor options | bracket indent, smart enter, trim whitespace, string quick suggestions |
| `json.maxItemsComputed` | `30000` |
| `json.schemas` | Array of `{ fileMatch, url }` entries |

### Schema generation

`SchemaBuilder` reflects a registrant or snippet class and emits JSON Schema draft-07:

- `$schema`: `http://json-schema.org/draft-07/schema#`
- `$id`: `https://volmit.com/iris-schema/<classname>.json`
- Field docs from `@Desc`, ranges from `@MinNumber`/`@MaxNumber`, arrays from `@ArrayType`, required from `@Required`
- Enumerations for platform registries (blocks, biomes, entities, structures, …) and pack resource lists from `@RegistryListResource` / related annotations
- Snippet types (classes annotated `@Snippet`) get schemas under `.iris/schema/snippet/<snippet>-schema.json`

`ResourceLoader.buildSchema()` for each loader that `supportsSchemas()`:

| Pack folder pattern | Schema URL (relative to pack) |
|---------------------|--------------------------------|
| `/<folder>/**/*.json` (up to 7 depth levels) | `./.iris/schema/<folder>-schema.json` |

Example folders with schemas (from loaders / workspace sample): `dimensions`, `regions`, `biomes`, `generators`, `loot`, `entities`, `spawners`, `structures`, `jigsaw-pieces`, `jigsaw-pools`, `expressions`, `blocks`, and others registered on `IrisData`. Object/image/matter loaders may disable schemas.

Snippet paths: `/snippet/<type>/**/*.json` → `./.iris/schema/snippet/<type>-schema.json`.

IntelliJ: workspace update also merges mappings into `.idea/jsonSchemas.xml` when that project file exists.

### Commands that refresh schemas

| Command | Effect |
|---------|--------|
| `/iris studio update dimension=<dim>` | Rewrite workspace + queue schema writes |
| Studio open / create | Builds workspace config including schemas |
| Hotload workspace refresh | Platform hook may refresh workspace after successful hotload |

Schema files under `.iris/schema/` are generated artifacts for editors; pack content is the JSON under type folders, not the schema files.

## How To: Edit a Pack in Studio

1. Ensure the pack is under the Iris data `packs/` directory (shipping overworld is typically downloaded as pack key `overworld`).
2. Run `/iris studio open overworld` (or your pack key). Enter the studio world.
3. Run `/iris studio vscode dimension=overworld` (or open the pack folder’s `*.code-workspace` in VSCode/Cursor with JSON schema support).
4. Edit `dimensions/`, `regions/`, `biomes/`, etc. Save. Studio hotloads when the file watcher detects the change.
5. Use `/iris studio map` or the debug scoreboard to inspect regions/biomes. Use `focus` / `focusRegion` on the dimension JSON for isolation while testing (see `11 - Dimensions.md`).
6. `/iris studio close` when finished. Promote pack changes into production worlds with pack install / world update flows (`06 - Worlds & Lifecycle.md`, `25 - Pack Management.md`).

## Studio Dimension Modes (author testing)

Dimension field `studioMode` (`StudioMode` enum) can force special studio generators:

| Value | Effect |
|-------|--------|
| `NORMAL` | Default generation |
| `BIOME_BUFFET_1x1` … `BIOME_BUFFET_36x36` | Biome buffet grid of given cell size |
| `REGION_BUFFET` | Region buffet |
| `OBJECT_BUFFET` | Object studio generator |

These are dimension JSON fields for studio testing, not production world modes (production engine mode is `mode.type`; see `11 - Dimensions.md`).

## Platform Notes

| Platform | Studio |
|----------|--------|
| Paper/Purpur/Folia (Bukkit plugin) | Full `CommandStudio` + file-watch hotload on studio worlds |
| Fabric / Forge / NeoForge | Studio open/create/workspace/package; subset of tooling; no Bukkit-only importers/GUIs that need Bukkit inventory |

Pack JSON contracts are shared across platforms. Schemas are built from the same core models.

# 05 - Concepts & Pack Layout

An Iris pack is a directory of JSON, binary objects, and optional assets under the platform packs root (`packs/<key>/` on Bukkit-family; `config/irisworldgen/packs/<key>/` on Fabric/Forge/NeoForge). `IrisData` is the pack loader: it registers one `ResourceLoader` per registrant type, resolves keys to files, caches loads, and expands snippet references during JSON parse. Production worlds use a copied pack snapshot under the world folder; studio worlds load the live pack directory with hotload.

See also: `00 - Overview.md`, `01 - Installation & Platforms.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `24 - Pack Mods & Snippets.md`, `25 - Pack Management.md`.

## Tutorial: trace one resource through a pack

Prerequisites: a loadable pack under the correct platform packs root, command permission, and an editor that preserves JSON syntax. Use this exercise before authoring a large pack:

1. Validate the pack before editing: `/iris pack validate pack=overworld` on Bukkit, or `/iris pack validate overworld` on a mod loader. Substitute your pack key consistently when tracing another pack.
2. Open `dimensions/<dimension>.json` and pick one key from its `regions` array.
3. Open `regions/<key>.json` and pick one root key from `landBiomes`.
4. Open `biomes/<key>.json` and follow its first generator, object, decorator, or structure reference to the matching registrant folder.
5. Confirm every key is the file path relative to that registrant folder with the extension removed.
6. Open the pack in Studio and focus that region or biome while editing. Save one valid change, wait for hotload, then rerun pack validation before creating a production snapshot.

The exercise passes when every reference resolves without guessing a namespace or filename, Studio hotload succeeds, and validation has no blocking errors. If a file exists but never appears, work backward from the dimension graph; unreferenced files are valid but unreachable.

### Resource-resolution recovery

| Symptom | Likely cause | Recovery |
|---|---|---|
| File exists but its key is unresolved | Extension or type-folder prefix was included, path case differs, or the reference starts from the wrong registrant root | Rebuild the key as the exact relative path under the type folder, without extension |
| File validates but never generates | It is not reachable from the active dimension → region → biome graph, or its chance/filter excludes it | Trace references from the dimension root and test with Studio focus/buffet modes |
| Studio schema does not list a new resource | Workspace schema/resource enums are stale | Run `/iris studio update dimension=<pack>` on Bukkit or `/iris studio update <pack>` on modded |
| Two files appear to share a key | Dotted variants or same-base-name candidates are ambiguous | Keep one canonical filename; Iris warns and otherwise selects the sorted first match |
| Production world ignores a corrected resource | It is reading its copied snapshot | Validate in Studio, then use the explicit world-update workflow or create a new world |

## Content model

| Concept | Role |
|---------|------|
| Pack | Directory under the packs root; pack folder name is the pack key |
| Dimension | Root world type under `dimensions/`; at least one is required |
| Region | Spatial zone listing biomes and region content |
| Biome | Terrain layers, surface, decorations, objects, structures, spawns |
| Generator | Height / noise generator definitions |
| Object | Placed block models (`.iob`) |
| Structure | Iris multi-piece / native structure graphs under `structures/` |
| Jigsaw pool / piece | Pool and piece JSON for Iris jigsaw assembly |
| Entity / spawner / marker / loot | Entity definitions, spawn rules, markers, loot tables |
| Mod schema | Inactive injector/replacer documents under `mods/`; loaded for schema/tooling but not applied by the engine |
| Expression / block / image | Expressions, custom blocks, PNG sampling maps |
| Snippet | Reusable JSON fragments under `snippet/<type>/` |
| Studio | Transient authoring world bound to the live pack with file hotload |
| World pack snapshot | Frozen copy at `<world>/iris/pack` used by non-studio worlds |

## Pack roots

| Context | Path |
|---------|------|
| Authoring / download target | Platform data dir `packs/<packKey>/` |
| Production world (non-studio) | World dimension root `iris/pack/` (see `06 - Worlds & Lifecycle.md`) |
| Studio world | Same as authoring pack path; no world copy unless benchmark |
| Prefetch cache | Platform `prefetch/` (loader key indexes) |
| Schemas (studio) | Pack-local `.iris/schema/` |

Pack folder names listed by Iris must be visible directories (not hidden names starting with `.`). Symbolic-link pack trees are rejected for download/replace and validation safety checks.

## Registrant folders

Every pack type that `IrisData` registers maps to a folder name returned by the registrant’s `getFolderName()`. Nested subfolders under a type root are allowed; the load key is the path relative to that root without the file extension.

| Folder | Type | Files | Required |
|--------|------|-------|----------|
| `dimensions/` | Dimension | `*.json` | **Yes** — pack is not loadable without at least one |
| `regions/` | Region | `*.json` | Optional |
| `biomes/` | Biome | `*.json` | Optional |
| `generators/` | Generator | `*.json` | Optional |
| `objects/` | Object | `*.iob` | Optional |
| `matter/` | Matter object | matter binary (loader-specific) | Optional; loader only, no runtime consumer |
| `structures/` | Structure | `*.json` | Optional |
| `jigsaw-pools/` | Jigsaw pool | `*.json` | Optional |
| `jigsaw-pieces/` | Jigsaw piece | `*.json` | Optional |
| `entities/` | Entity | `*.json` | Optional |
| `spawners/` | Spawner | `*.json` | Optional |
| `markers/` | Marker | `*.json` | Optional |
| `loot/` | Loot table | `*.json` | Optional |
| `mods/` | Pack mod schema | `*.json` | Optional; no runtime application path |
| `blocks/` | Block data | `*.json` | Optional |
| `expressions/` | Expression | `*.json` | Optional |
| `images/` | Image | `*.png` | Optional |
| `snippet/` | Snippet library | `snippet/<type>/**.json` | Optional (not a registrant loader) |

Live overworld also contains authoring-only or empty trees that loaders do not register as types (for example empty `caves/`, `ravines/`, `jigsaw-structures/`, plus pack-local `README.md`, workspace files, and `Schema.json`). Those names are not keys in `IrisData`.

## Key rules

- **Load key** is the path under the type folder without extension. Example: `biomes/temperate/plains.json` → key `temperate/plains`.
- Exact `name + extension` wins over dotted variants (`plains.json` beats `plains.disabled.json` when both match the base name rule).
- Ambiguous same-base-name matches log a warning and pick the sorted first file.
- Literal key `"null"` is refused.
- Cross-references between resources use these load keys (region biome lists, structure placements, spawner entity ids, and similar).
- Pack dimension selectors for world create accept `pack`, `pack:dimensionKey`, or `default` (resolves to `settings.generator.defaultWorldType`, default `overworld`). See `04 - Commands & Permissions.md` and `06 - Worlds & Lifecycle.md`.
- Download destination pack keys must match `[a-z0-9_-]+` and are taken from the single dimension load key in the archive.

## Dimensions required

`PackValidator` fails a pack as not loadable when:

1. The pack folder is missing or not a directory.
2. `dimensions/` is missing.
3. `dimensions/` has no `*.json` files.

A downloaded archive without an expected key is rejected unless it contains exactly one loadable dimension, whose load key becomes the install key. Managed-release and listing downloads carry an exact expected primary key, so their archive may retain additional dimension resources; the expected dimension selects the folder key and the entire pack still validates before publication. Presence of a pack on disk is defined as a safe pack directory with at least one non-symlink `dimensions/*.json` file.

## Snippets

Types annotated with `@Snippet("<type>")` may be written inline as JSON objects **or** as a string reference to a snippet file.

| Rule | Behavior |
|------|----------|
| Path form | `"snippet/<type>/<name>"` (optional shorter forms are normalized onto `snippet/<type>/`) |
| On-disk file | `snippet/<type>/<name>.json` under the pack root |
| Nested snippet keys | Subfolders under `snippet/<type>/` are allowed; listed keys keep the `snippet/` prefix |
| Schema | Studio schema builder exposes `snippet/<type>/…` enums and pattern matches under `.iris/schema/snippet/` |
| Shipping example | Overworld uses `snippet/decorator/*` and `snippet/style/*` |

Snippet parse failures log errors and yield `null` for that field; full pack validation treats broken graphs as blocking or warning depending on the validator.

## Studio pack vs world pack snapshot

| Mode | Pack path used by engine | Hotload | Copy on create |
|------|--------------------------|---------|----------------|
| Studio (`studio=true`) | Live `packs/<key>/` (or studio project path) | Yes — polls pack for `.json`/`.iob` changes (~1s latch), excludes `.iris` | No pack install into world (unless benchmark) |
| Production create | Installs full pack tree into `<world>/iris/pack` | No | Atomic copy via `StudioSVC.installIntoWorld` |
| Benchmark | Installs into world pack path | Studio flag still drives transient cleanup rules | Yes when `benchmark` |

Hotload opens a new `IrisData` runtime from the same folder, reloads the dimension key, rebuilds engine runtime under a lifecycle lock, retires the previous data, and refreshes workspace/datapacks. Production engines load only the world snapshot; editing `packs/` does not affect existing non-studio worlds until the snapshot is replaced (see `25 - Pack Management.md` `update-world`).

## Minimal pack layout

```text
packs/myworld/
  dimensions/
    myworld.json
  regions/
    main.json
  biomes/
    plains.json
  generators/
    plain.json
```

A pack with only `dimensions/*.json` is structurally valid for presence and basic validation; generation quality depends on the dimension’s referenced regions/biomes/generators. For a walkthrough see `26 - Example - Minimal Dimension.md`.

## Live overworld folder map (shipping pack)

Adapter run configurations use these additional paths (`…/packs/overworld/`):

| Path | Contents (summary) |
|------|--------------------|
| `dimensions/overworld.json` | Root dimension |
| `regions/*.json` | Climate / biome zones |
| `biomes/**` | Nested biome sets (temperate, hot, frozen, ocean, …) |
| `generators/**` | Terrain generators |
| `objects/**` | `.iob` trees, structures, clutter, vanilla imports |
| `structures/*.json` | Structure graphs (including minecraft_* graphs) |
| `jigsaw-pieces/**`, `jigsaw-pools/**` | Jigsaw assembly data |
| `entities/standard/**`, `spawners/**`, `loot/**` | Entities, spawners, loot |
| `images/*.png` | Noise / map images |
| `snippet/decorator/**`, `snippet/style/**` | Shared snippets |

Related feature docs: `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `18 - Structures Overview.md`, `19 - Objects.md`, `21 - Jigsaw Structures.md`, `23 - Loot, Entities, Spawners, Markers.md`.

# 05 - Concepts & Pack Layout

A pack is a folder of JSON files, binary objects, and images that fully describes one or more worlds. Iris loads it through `IrisData`, which registers one loader per resource type, turns short string keys into files on disk, and caches what it reads. Every world you create gets its own frozen copy of the pack; only Studio worlds read the folder you are editing.

See also: `00 - Overview.md`, `01 - Installation & Platforms.md`, `10 - Studio & VSCode Schemas.md`, `11 - Dimensions.md`, `24 - Pack Mods & Snippets.md`, `25 - Pack Management.md`.

## What a pack actually is

There is no manifest file, no registry, and no build step. A pack is a directory whose subfolder names tell Iris what type each file is. `biomes/plains.json` is a biome because it sits in `biomes/`. Move that same file to `regions/` and Iris will try to parse it as a region.

The pack folder's own name is the pack key. A folder called `packs/myworld/` is the pack `myworld`. Rename the folder and you have renamed the pack.

The only hard requirement is at least one `.json` file directly inside `dimensions/`. Everything else is optional, and a folder you never create simply has no resources of that type.

## A pack you can read in one screen

```text
packs/myworld/
  dimensions/
    myworld.json        -> key "myworld"
  regions/
    main.json           -> key "main"
  biomes/
    plains.json         -> key "plains"
    hills/
      rolling.json      -> key "hills/rolling"
  generators/
    plain.json          -> key "plain"
```

Five files. `dimensions/myworld.json` lists `"main"` in its `regions` array. `regions/main.json` lists `"plains"` and `"hills/rolling"` in `landBiomes`. Each biome names `"plain"` as a generator. That chain is the whole pack.

Note `hills/rolling`. Subfolders are yours to organize however you like — they become part of the key, and nothing else changes.

## Keys: the one rule

**A key is the file's path under its type folder, with the extension removed.**

| File on disk | Type folder | Key you write in JSON |
|---|---|---|
| `biomes/plains.json` | `biomes/` | `plains` |
| `biomes/temperate/plains.json` | `biomes/` | `temperate/plains` |
| `objects/trees/oak/big.iob` | `objects/` | `trees/oak/big` |
| `snippet/style/soft.json` | (snippets, see below) | `snippet/style/soft` |

There is no namespace and no type prefix. You never write `biomes/plains` or `iris:plains` — the field you are filling in already knows it wants a biome, so it searches `biomes/` for you. Cross-references everywhere (region biome lists, object placements, spawner entity ids, structure piece pools) use exactly these keys.

### What happens when the exact file is missing

Iris first tries `<typeFolder>/<key>.json` and returns it if it exists. That is the normal path and the only one that works for nested keys.

If there is no exact hit, Iris scans the type folder's own files (not subfolders) for any name whose first dot-segment equals the key. This is what makes `plains.disabled.json` still load for key `plains` — useful for parking a variant, surprising if you forgot you did it. If two files match, Iris logs `Ambiguous <type> <key> in <folder>: ...` and takes the alphabetically first one. Keep one canonical filename per key and this never bites you.

The literal string `"null"` is refused with a warning by direct file lookups and by warning-enabled loads. Silent loads (the cross-pack fallback search) do not refuse it and will look for `null.json`. Do not name a file `null.json`.

## How the pieces relate

Generation walks a graph, and the graph starts at exactly one place: the dimension you named when you created the world.

```text
dimension  ->  regions  ->  biomes  ->  generators   (terrain height/noise)
                                    ->  objects      (.iob models)
                                    ->  decorators   (surface clutter)
                                    ->  structures   (jigsaw / native)
                                    ->  spawners     -> entities
                                    ->  loot
```

- **Dimension** — the root. Sets world height, environment, seed behavior, and which regions exist. One dimension equals one world type.
- **Region** — a spatial zone. Regions decide which biomes can appear where, and can carry their own objects and structures that span biome edges.
- **Biome** — the workhorse. Block layers, surface treatment, decorations, object placements, structures, and mob spawns.
- **Generator** — noise and height math. Biomes reference generators to get terrain shape; several biomes can share one.
- **Object** — a `.iob` block model with its own placement rules.
- **Structure / jigsaw pool / jigsaw piece** — multi-piece assemblies, either Iris-native or bridged to vanilla structures.

The practical consequence: **a file that nothing references is inert.** It parses, it validates, it never generates. When a resource you wrote is not showing up, the first question is not "is the JSON wrong" but "is it reachable from the dimension." Work forward from `dimensions/<key>.json` and find where the chain breaks.

## Trace one reference end to end

Do this once on a pack you did not write. It takes two minutes and makes everything above concrete.

Prerequisites: a loadable pack under the packs root, `iris.all` (Bukkit) or gamemaster (modded), and an editor that will not reformat your JSON.

1. Validate first, so you know a later failure is yours: `/iris pack validate pack=overworld` on Bukkit, `/iris pack validate overworld` on a mod loader.
2. Open `dimensions/overworld.json`. Pick one key out of the `regions` array.
3. Open `regions/<that key>.json`. Pick one key out of `landBiomes`.
4. Open `biomes/<that key>.json`. Follow its first generator, object, decorator, or structure reference into the matching type folder.
5. At each hop, confirm the key is the path under the type folder with the extension removed — nothing more.
6. Open the pack in Studio, focus that biome, save one valid edit, and wait for hotload. Re-validate.

You are done when every reference resolved without guessing at a namespace or filename, hotload succeeded, and validation reports no blocking errors.

## Snippets

A snippet is a JSON fragment you write once and reference from many places. Types tagged `@Snippet("<type>")` in the engine accept either an inline object or a string pointing at a snippet file.

```json
"style": "snippet/style/soft-hills"
```

resolves to `<packRoot>/snippet/style/soft-hills.json`.

| Rule | Actual behavior |
|---|---|
| Trigger | Only a JSON **string** value. Inline objects parse normally and never touch the snippet path |
| Required prefix | The string must start with `snippet/`. Anything else resolves to `null` **with no log line at all** — the most common silent snippet failure |
| Re-rooting | If the string starts with `snippet/` but not `snippet/<thisType>/`, Iris strips `snippet/` and re-roots the remainder under this field's own type. So `snippet/decorator/foo` on a style field becomes `snippet/style/decorator/foo`, not an error |
| On-disk path | `<packRoot>/snippet/<type>/<name>.json`, resolved from the pack root, not from the type folder |
| Subfolders | Allowed; `<name>` may contain `/`. Discovery walks the tree recursively |
| Missing file | Logs `Couldn't find snippet <path> in <file>` and yields `null` for that field |
| Unreadable file | Logs `Couldn't read snippet <path> in <file> (<message>)` and yields `null` |
| Inline parse failure | Different path: logs `Failed to read <type>... faking objects a little`, then substitutes a **default-constructed instance**, not `null` |
| Schema | Studio writes `.iris/schema/snippet/<type>-schema.json` so the editor offers completions for `snippet/<type>/…` |

The shipping overworld uses `snippet/decorator/*` and `snippet/style/*`.

## Two copies of every pack

This is the concept that causes the most confusion, so it is worth being blunt about.

**The pack you edit and the pack a world generates from are different files.**

When you create a non-Studio world, Iris copies the entire pack tree into `<world>/iris/pack` and the world's engine reads only that copy for the rest of its life. Editing `packs/overworld/` afterwards changes nothing about that world. This is deliberate: a world's terrain must stay reproducible even if you keep authoring.

Studio worlds are the exception. A Studio world's engine points directly at the live pack folder and watches it for changes, which is what makes hotload possible.

| Mode | Pack the engine reads | Hotload | Copied into the world? |
|---|---|---|---|
| Studio (`studio=true`) | Live `packs/<key>/` (or the studio project path) | Yes | No |
| Production create | `<world>/iris/pack` | No | Yes, atomic stage then publish via `StudioSVC.installIntoWorld` |
| Benchmark | `<world>/iris/pack` | Studio flag still governs transient cleanup | Yes |

Hotload opens a fresh `IrisData` on the same folder, reloads the dimension by its key, builds a replacement engine runtime under the lifecycle lock, publishes it, retires the old `IrisData`, then refreshes the editor workspace and datapacks in the background. If any step fails it rolls back to the previous runtime and reports the error.

The watcher polls every 250 ms but only checks the folder about once per second, backing off to once per 4 s during maintenance or within 2 s of chunk generation. It watches `.json` and `.iob` and ignores anything under `.iris`. It runs only while the world is a Studio world that is not closing and not in jigsaw-studio mode.

To push pack edits into an existing production world, see `update-world` in `25 - Pack Management.md`, or just create a new world — which is the right answer for any change to height or dimension type.

## Where packs live

| What | Bukkit-family | Fabric / Forge / NeoForge |
|---|---|---|
| Packs you author and download into | `plugins/Iris/packs/<key>/` | `config/irisworldgen/packs/<key>/` |
| Platform data dir (`settings.json`, languages, caches) | `plugins/Iris/` | `config/iris/` |
| A world's frozen snapshot | `<dimensionRoot>/iris/pack/` | same, under the modded world root |
| Prefetch key indexes | `<platform data dir>/prefetch/<dimId>/<hash>.ipfch` | same |
| Studio schemas | `<packRoot>/.iris/schema/` | same |

On mod loaders the pack root and the platform data dir are two different folders — packs go under `config/irisworldgen/`, everything else under `config/iris/`. If you are hand-placing a pack on a modded server, `config/irisworldgen/packs/` is the one that matters.

Folders whose names start with `.` are skipped when Iris lists packs, which is why `.iris/` inside a pack is invisible to the pack listing. Pack listing itself follows symbolic links; the stricter check (`requireSafePackTree`, used when installing a pack into a world) refuses a symlinked root, any symlink anywhere in the tree, and any non-regular file, and skips hidden subtrees.

## Registrant folders

`IrisData` registers 17 loaders. Each one owns exactly one folder name and one file extension.

| Folder | Extension | What lives here and when you touch it |
|---|---|---|
| `dimensions/` | `.json` | World roots. Height, environment, region list, imports. **Required** — a pack with none is not loadable |
| `regions/` | `.json` | Which biomes appear in which climate zone, plus region-wide objects and structures |
| `biomes/` | `.json` | Where most authoring time goes: layers, surface, decorators, objects, structures, spawns |
| `generators/` | `.json` | Reusable noise/height math that biomes point at. Edit here to change terrain shape across many biomes at once |
| `objects/` | `.iob` | Binary block models saved from the wand or imported from schematics. Referenced by placements, never edited as text |
| `structures/` | `.json` | Structure graphs, including the `minecraft_*` graphs that bridge vanilla structures |
| `jigsaw-pools/` | `.json` | Weighted sets of pieces a jigsaw connector can pick from |
| `jigsaw-pieces/` | `.json` | One placeable piece: its object, its connectors, its rules |
| `entities/` | `.json` | Entity definitions with equipment, attributes, and custom data, used by spawners and markers |
| `spawners/` | `.json` | When and where entities spawn — time, block, biome, and rate rules |
| `markers/` | `.json` | Named points Iris records during generation so spawners and other systems can find them later |
| `loot/` | `.json` | Iris loot tables applied to generated containers |
| `blocks/` | `.json` | Named custom block states you can reference instead of repeating long block data strings |
| `expressions/` | `.json` | Math expressions callable from generators and placement rules |
| `images/` | `.png` | PNG maps sampled as noise or as direct biome/height input |
| `matter/` | `.mat` | Matter binaries. The loader exists and resolves keys, but no runtime system consumes them |
| `mods/` | `.json` | Injector/replacer documents. Loaded so schemas and tooling see them; the engine has no path that applies them |

Anything else in a pack directory is not a resource type. The shipping overworld ships empty `caves/`, `ravines/`, and `jigsaw-structures/` folders plus `README.md`, `Schema.json`, and a `.code-workspace` file — none of those names are keys, and none are loaded.

A reduced init path used by the datapack compiler registers only `biomes` and `dimensions`; that is internal and not something a pack author configures.

## What makes a pack loadable

`PackValidator` fails fast on three structural problems, in order:

1. The pack folder is missing or is not a directory.
2. There is no `dimensions/` directory.
3. There are no `*.json` files **directly inside** `dimensions/`. Nested dimension files do not count toward this check.

Passing those three does not mean the pack is loadable. `PackValidator` then runs roughly ten content validators — dimension, cave profile, loot, object/surface, structure graph, native structure, spawn, and content-key checks — and any blocking error from those also makes the pack not loadable. Content-key problems are blocking only under strict content mode. Read the first blocking error and fix that one; the rest are usually downstream.

Presence on disk is a weaker notion than loadability: a pack "exists" if its directory is safe and holds at least one non-symlink `dimensions/*.json`.

### Download key rules

Downloaded pack keys must match `[a-z0-9_-]+`, and the check applies both to a caller-supplied expected key and to the key Iris derives from the archive.

An archive with no expected key must contain exactly one dimension; its load key becomes the install folder name. Managed-release and listing downloads carry an exact expected key, so their archive may hold extra dimensions — the expected key picks the folder name, and the whole pack is validated before publication.

## When a resource does not resolve

| Symptom | Likely cause | Fix |
|---|---|---|
| File exists, key does not resolve | You included the extension or the type folder in the key, the case differs, or you counted the path from the wrong root | Rebuild the key as the exact relative path under the type folder, extension removed |
| Nested dotted variant not found | The dotted-name fallback only scans the type folder's own files, never subfolders | Give nested files their exact key name, or move the variant to the type folder root |
| File validates but never generates | Nothing in the dimension → region → biome chain references it, or a chance/filter excludes it | Trace forward from the dimension root; test with Studio focus or buffet mode |
| Snippet silently becomes null | The string does not start with `snippet/` — this failure logs nothing | Write the full `snippet/<type>/<name>` form |
| Snippet loaded the wrong file | A `snippet/<otherType>/…` string was re-rooted under this field's own type | Use the type that matches the field |
| Studio does not offer a new resource in completions | Workspace schema enums are stale | `/iris studio update dimension=<pack>` on Bukkit, `/iris studio update <pack>` on modded |
| Console warns "Ambiguous \<type\> \<key\>" | Two files share a base name before the first dot | Keep one canonical filename; Iris took the alphabetically first |
| Production world ignores your fix | It is reading `<world>/iris/pack`, not your live pack | Validate in Studio, then run the explicit world-update workflow or create a new world |

## The shipping overworld pack

For orientation when reading `packs/overworld/`:

| Path | What is in it |
|---|---|
| `dimensions/overworld.json` | The single root dimension |
| `regions/*.json` | Climate zones that partition the biome set |
| `biomes/**` | Nested biome sets — temperate, hot, frozen, ocean, and so on |
| `generators/**` | Shared terrain generators |
| `objects/**` | `.iob` trees, structures, clutter, and vanilla imports |
| `structures/*.json` | Structure graphs, including `minecraft_*` bridges |
| `jigsaw-pieces/**`, `jigsaw-pools/**` | Jigsaw assembly data |
| `entities/standard/**`, `spawners/**`, `loot/**` | Mob and loot content |
| `images/*.png` | Noise and map images |
| `snippet/decorator/**`, `snippet/style/**` | Shared fragments referenced across biomes |

Feature-level detail: `12 - Regions.md`, `13 - Biomes.md`, `14 - Generators & Noise.md`, `18 - Structures Overview.md`, `19 - Objects.md`, `21 - Jigsaw Structures.md`, `23 - Loot, Entities, Spawners, Markers.md`.

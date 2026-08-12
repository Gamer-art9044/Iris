# 10 - Studio & VSCode Schemas

Studio is the live pack-authoring loop: open a pack as a throwaway world, edit its JSON in an editor that autocompletes against schemas generated from the Java models, save, and watch the running engine rebuild itself. This page walks the loop end to end first, then documents the commands, the hotload rules, and how the schemas are produced.

Related: see `04 - Commands & Permissions.md`, `05 - Concepts & Pack Layout.md`, `02 - Getting Started.md`, `11 - Dimensions.md`, `21 - Jigsaw Structures.md`, `25 - Pack Management.md`, `30 - Platform Differences.md`.

## The edit loop

Prerequisites: a writable packs directory, operator access on Bukkit or gamemaster access on a mod loader, and VSCode/Cursor (or IntelliJ) on the machine that holds the pack folder. Keep the server console visible — hotload reports success and failure there.

### Bukkit-family

1. **Create a project.** `/iris studio create name=tutorial`
   Writes `packs/tutorial/` with a dimension, region, biome, generator, and a `tutorial.code-workspace`. The command reports the completed project path; creation runs asynchronously and may report that a restart is needed before the pack can be opened.
2. **Open it as a world.** `/iris studio open tutorial seed=1337`
   You are teleported into a transient world generated from the live pack folder. A fixed seed matters — you will be comparing the same coordinates across reloads.
3. **Open the editor workspace.** `/iris studio vscode dimension=tutorial`
   Generates `.iris/schema/*` if missing and opens the `*.code-workspace`. On a headless server nothing launches; copy the pack folder to your machine and open the workspace file yourself.
   *Success condition:* typing `"` inside any object in `biomes/starter.json` offers field names, and hovering a field shows its description, type, and default value. If it does not, the workspace was not opened or the schemas were never written — run `/iris studio update dimension=tutorial`.
4. **Make one change.** Edit `packs/tutorial/biomes/starter.json` and change only its display `name`. Save once.
5. **Wait for the hotload result** in console before saving anything else. A failed hotload leaves the previous runtime active and reports the error; stacking more edits on top makes the first failure hard to find.
6. **Verify in fresh terrain.** Walk into chunks that have never generated and run `/iris what biome`. The new display name appears there. Hotload never rewrites blocks that already exist, so standing still and expecting the world to change is the usual false negative.
7. **Validate.** `/iris pack validate pack=tutorial` — no blocking errors.
8. **Close.** `/iris studio close`

### Fabric / Forge / NeoForge

Same loop, positional arguments, and the modded studio create always copies a template (`example` by default):

1. `/iris studio create tutorial example`
2. `/iris studio open tutorial 1337`
3. `/iris studio vscode tutorial`
4. Trace the active dimension to one referenced biome, change one display or palette value, save once.
5. Wait for the hotload result, then enter newly generated terrain and check it with `/iris what biome`.
6. `/iris pack validate tutorial`, then `/iris studio close`.

The loop passes when the editor binds the generated schema, hotload succeeds, validation reports no blocking errors, and newly generated chunks show the change. Create a production world only after that gate.

A rejected height or dimension-type change is not evidence that hotload is broken — those are refused by design. See **Hotload rules** below.

### When something goes wrong

| Symptom | Meaning | Recovery |
|---|---|---|
| `open` reports startup validation pending, missing, failed, restart-required, or blocking pack errors | Datapacks or the pack graph cannot safely build an engine | Complete the requested restart or run the platform's `pack validate` form, fix the first blocking error, and retry; do not bypass validation |
| Save reports hotload failure | The new data or runtime build failed; the previous runtime may remain active | Fix the first console error and save again before making unrelated edits |
| Height, logical height, or dimension type change is rejected | The edit violates `IrisDimensionRuntimeContract` | Close Studio and reopen; on modded, restart when regenerated dimension-type datapacks require a registry reload |
| A valid change is invisible | The chunks you are standing in are already materialized, or the edited resource is unreachable from the active dimension | Move to new chunks; trace dimension → region → biome to confirm the resource is actually referenced; use `focus`/`focusRegion` or a buffet studio mode to isolate |
| No autocomplete, or resource keys are stale | Schemas were not generated or refreshed, or the editor never opened the workspace | Run `/iris studio update`, then open the pack's `.code-workspace`; on headless servers open it manually |
| The Studio world disappears after a restart | Studio worlds are transient and purged on purpose | Reopen the pack; `packs/<key>/` is the source of truth, not the world folder |

## What Studio is

| Concept | Behavior |
|---------|----------|
| Pack workspace | Packs live under the platform data directory in the folder named `packs` (`StudioSVC.WORKSPACE_NAME`) |
| Studio world | Opened from a pack dimension key; uses a studio chunk generator bound to the live pack folder with file watching |
| Hotload | Studio worlds only. A low-priority looper polls pack files; on change, `EngineHotloader` waits for already-admitted top-level Bukkit chunk stages, reloads the pack data, and rebuilds the engine runtime under exclusive generator control. Fair stage admission keeps later chunk stages behind the waiting transition, and Studio close uses the same drain boundary. Biome Buffet resolves its chunk focus and completes any required complex hotload under exclusive admission before that noise stage opens a generation session, then downgrades directly to one ordinary stage permit |
| Hotload contract | `IrisDimensionRuntimeContract` refuses hotload if the dimension type key, min height, total height, or logical height change |
| Non-studio worlds | No pack file watcher. Production worlds keep the pack snapshot installed at create or update time |

Studio settings live in `settings.json` under `studio` (`IrisSettings.IrisSettingsStudio`):

| Key | Default | Meaning |
|-----|---------|---------|
| `openVSCode` | `true` | When true and the JVM is not headless, `open`/`vscode` may launch the desktop opener on the pack's `*.code-workspace`. Set false on servers where a desktop launch would be pointless or unwanted |
| `entitySpawning` | `true` | Only affects Studio worlds. False stops Iris ambient entity spawning there; production worlds always spawn regardless of this key |
| `disableTimeAndWeather` | `true` | Present in the settings model but not read by any code path today |
| `autoStartDefaultStudio` | `false` | Present in the settings model but not read by any code path today |

## Hotload rules

- The watcher runs only when `PlatformChunkGenerator.isStudio()` is true, the world is not closing, and no Jigsaw Studio session is active. Jigsaw Studio deliberately suppresses ordinary pack-file hotload.
- On change: load a new `IrisData` from the same folder, reload the dimension key, check the hotload contract, build a new engine runtime, retire the previous data, refresh the workspace and schemas, reload datapacks when a platform world is bound, and broadcast a client studio-hotload toast on success or failure.
- `hotloadComplex` is a narrower rebuild that reconstructs `IrisComplex` without reopening the pack.
- A failed hotload rolls the runtime back where possible and reports the error.

Four values are pinned for the life of the world and cannot hotload: the dimension type key (derived from the dimension load key), min height, total height, and logical height. Editing `dimensionHeight`, `logicalHeight`, `environment`, or the dimension file name means closing and reopening Studio. See `11 - Dimensions.md`.

## Commands (Bukkit)

Root: `/iris studio`, aliases `std` and `s`. Implemented by `CommandStudio` and `StudioSVC`. Keyed arguments; the first column shows the primary subcommand name.

| Subcommand | Aliases | What it does |
|------------|---------|--------------|
| `open <dimension> [seed=1337]` | `o` | Closes any open studio and opens the pack as a studio world. Blocked unless startup datapack validation is ready and the selected pack has a loadable validation result |
| `close` | `x` | Closes the active studio project and world |
| `create [name=studio] [template=<dimension>]` | `+` | Creates a pack under `packs/<name>` after startup validation is ready. With a template, that template must itself validate as loadable and is downloaded first if missing; without one, writes the starter skeleton below |
| `vscode [dimension=default]` | `vsc` | Opens the pack's VSCode workspace, generating it if missing |
| `update [dimension=default]` | | Rewrites `<pack>/<name>.code-workspace` and queues regeneration of `.iris/schema/*` |
| `version [dimension=default]` | | Prints the dimension's `version` field |
| `pkg [dimension=default] [obfuscate=false] [minify=true]` | `package` | Compiles the pack into a distributable archive |
| `importvanilla <dimension> [variants=3] [structures=true]` | `importv`, `iv` | Captures vanilla features and structures into the pack through Bukkit NMS |
| `scoreboard` | `board`, `sidebar`, `sb` | Toggles the studio debug scoreboard; player must be in the studio world |
| `noise [generator=<key>] [seed=12345]` | `nmap` | Opens the external noise explorer GUI |
| `map [world=<world>]` | `render` | Opens the external biome/terrain map GUI for an Iris world |
| `regions [radius=500]` | | Samples region rarity over a chunk spiral; player must be in an Iris world |
| `loot [fast=false] [add=true]` | | Opens a virtual chest showing loot tables for the block under the player |
| `profile [dimension=default]` | | Writes a pack performance profile report |
| `spawn` | `summon` | Spawns a pack entity definition at the player |
| `tpstudio` | `stp` | Teleports to the active studio world spawn in creative |
| `objects` | `find-objects` | Captures a nearby-chunk object placement report |

Permissions and the full `/iris` tree: see `04 - Commands & Permissions.md`.

## Commands (Modded)

`/iris studio` on Fabric, Forge and NeoForge is implemented by `ModdedStudioCommands` with positional arguments. Supported: `create`/`+`, `package`/`pkg`, `version`, `regions`, `open`/`o`, `close`/`x`, `tpstudio`/`stp`, `status`, `vscode`/`vsc`, `update`, `noise`/`nmap`, `map`/`render`.

`create` with no arguments creates a project named `studio` from the `example` template; `create <name>` uses the same template; `create <name> <template>` picks another. `open <pack> [seed]` defaults the seed to `1337`.

These subcommands are registered on modded but only report why they are unavailable: `importvanilla`/`importv`/`iv` (vanilla capture needs throwaway Bukkit worlds via NMS), `loot` (needs a Bukkit chest inventory GUI), `profile`, `spawn`/`summon` (Bukkit entity pipeline), and `objects`/`find-objects` (reads Bukkit chunk data).

## Creating a pack (starter skeleton)

`/iris studio create name=mypack` with no template writes:

```
packs/mypack/
  dimensions/mypack.json
  regions/starter.json
  biomes/starter.json
  generators/flat.json
  mypack.code-workspace
```

Starter dimension JSON, verbatim from `StudioSVC.createStarterProject`:

```json
{
  "name": "mypack",
  "version": 1,
  "regions": ["starter"],
  "logicalHeight": 384,
  "dimensionHeight": {"min": -64, "max": 320}
}
```

The starter region lists the same `starter` biome for land, sea and shore. The starter biome uses generator `flat` at `min` 96 / `max` 96, one grass-block layer, and `minecraft:plains` as both `derivative` and `vanillaDerivative`. The full four-file listing is in `26 - Example - Minimal Dimension.md`.

Project names are lowercased and must match `a-z`, `0-9`, `_`, `-`, up to 64 characters. The name `studio` is reserved and auto-renamed to the next free suffix. If the target folder already exists, nothing is changed.

With a template — `/iris studio create name=mypack template=overworld` — Iris downloads the template pack if it is not present, requires it to validate as loadable, and copies its tree into the new pack key.

## Studio open workflow

1. Resolve pack folder `packs/<dimensionKey>/` with a loadable `dimensions/<key>.json`.
2. Pack validation must not report blocking errors (`PackValidationRegistry`); an unvalidated pack fails closed.
3. Close the existing studio if one is open.
4. `IrisProject.open` creates a studio world bound to that pack folder — not to a production install copy.
5. Optionally launch VSCode when `studio.openVSCode` is true.
6. Datapack installation may require a restart after create; the message tells you to re-run `open` after restarting.

Both ordinary and Jigsaw Studio reuse Iris's startup-loaded datapack runtime only while its pinned compiler-input fingerprint still matches every live `dimensions`, `biomes`, and `snippet` JSON input, the compiler build, and the vanilla-height policy. A changed input, unavailable registry, failed startup recovery, or changed/failed external datapack ingest or removal invalidates reuse and falls back to recovery, compilation, publication, and the existing restart gate; a verified no-change ingest or recovery check restores the prior pin. Object, structure, jigsaw, pool, and ownership edits do not affect generated dimension types or custom biomes and therefore do not force that fallback.

Ordinary Studio still resolves and teleports through its standard safe entry, may launch the pack workspace, prepares the complete mantle radius, and preserves native structures for generation previews. On Paper 26.2, WorldInit publishes the filtered native-structure placement state once but leaves it uninitialized while native starts, locates, and object-collision volume queries are gated; injection verifies that Paper's canonical chunk-generator getter owns the new Iris generator before native structure state is published. After the exact FULL entry-chunk request and retention ticket settle and the standard safe-entry teleport step succeeds when applicable, the global scheduler claims that exact level, chunk map, generator, and state, starts its placement initialization, registers the exact concentric-ring futures, enables collision-volume queries, and then lowers the structure gate. The final Studio callback also returns to the server scheduler before applying game rules or committing a Jigsaw session. The ring searches finish in the background rather than delaying entry or extending Studio ready time, while normal close, full hotload, and complex hotload wait up to 120 seconds for their exact aggregate before mutating or sealing the engine; a synchronous partial-start failure permanently rejects those transitions for that engine because a complete drain cannot be proven.

Jigsaw Studio publishes an initialized empty native-structure state even when no managed datapack scope exists, never retains or activates the filtered full state, and keeps starts, references, locates, and native collision-volume queries disabled. Its dedicated open kind also skips the standard-entry teleport, workspace launch, procedural generation-cache warm, complete mantle-radius preparation, and ordinary pack-file hotloader before sending the owner once through the selected workcell destination. Jigsaw graph transactions directly invalidate, reload, evaluate, and rematerialize their owned resources; close and reopen Jigsaw Studio to apply unrelated external pack edits.

Paper-family entry chunks are requested through the urgent asynchronous chunk API before Iris retains them with a plugin ticket; Folia retains its nonblocking ticket bootstrap and confirms the owning region before entry resolution. If an open fails while that exact request remains active, Iris reports the failure without unloading or closing the generator, rejects another Studio open until cleanup succeeds, and queues the transient world for deletion at the next clean startup if it remains active for another 120 seconds. Closing a Studio stops Iris engine maintenance at `WorldUnloadEvent`, waits for the raw backend unload completion and any tracked native ring preparation before sealing the generator, and the 26.2 noise pipeline keeps its generation lease through terrain and heightmap completion. Forced process termination cannot drain in-process ring futures.

Every Bukkit Studio open writes one `[Studio timing]` line per lifecycle phase with the transient world, `standard` or `jigsaw` kind, phase duration, and cumulative duration where available. The measured phases separate loaded-runtime reuse, external-datapack recovery, compiler-input fingerprinting, datapack compilation/publication, generator preparation, Bukkit world creation, entry-chunk loading, safe-entry resolution, standard teleport, and finalization. Studio engine timing additionally separates prefetch loading, runtime construction, and the generation-cache warm or its Jigsaw-only skip so a slow open can be correlated with a profiler capture.

## VSCode workspace

`IrisCodeWorkspace` writes `<pack>/<packName>.code-workspace`:

| Workspace setting | Value / purpose |
|-------------------|-----------------|
| `folders` | `[{ "path": "." }]` — the pack root |
| `workbench.colorTheme` | `Monokai` (dark preference `Solarized Dark`) |
| `files.autoSave` | `onFocusChange` — so switching windows triggers a hotload |
| `[json]` editor options | Bracket indent, smart accept-on-enter, trim trailing whitespace and final newlines, quick suggestions inside strings, replace-mode insert, keyword/snippet/word suggestions off so only schema entries are offered |
| `json.maxItemsComputed` | `30000` — large enough that big registry enums still complete |
| `json.schemas` | Array of `{ fileMatch, url }` entries, sorted by url |

The same call also merges the mappings into `<pack>/.idea/jsonSchemas.xml` so IntelliJ picks up the schemas, writing that file whenever there is a mapping it does not already contain. The workspace file is only rewritten when its rendered content changes; if it is unparseable, Iris deletes and recreates it, losing hand-edited workspace settings but never pack content.

## Schema generation

`SchemaBuilder` reflects a registrant or snippet class and emits JSON Schema draft-07:

- `$schema` is `http://json-schema.org/draft-07/schema#`.
- `$id` is `https://volmit.com/iris-schema/<lowercased class simple name>.json`.
- Every property's `description` is assembled from the field name, its `@Desc` text, the type name, the type's own `@Desc`, a snippet hint where applicable, and the field's **default value**, read by instantiating the owning class. That is why hovering a field in the editor tells you what it defaults to without opening the source.
- `@MinNumber`/`@MaxNumber` become `minimum`/`maximum` on numeric fields and `minLength`/`maxLength` on string fields. `@Required` fills the `required` array. `@ArrayType` supplies the array item schema.
- Registry annotations become `enum` lists: `@RegistryListResource` for pack resource keys of a given type, `@RegistryListFunction` for computed lists such as mantle component flags, and the platform registry annotations (`@RegistryListBlockType`, `@RegistryListBiome`, `@RegistryListEntityType`, `@RegistryListItemType`, `@RegistryListStructure`, `@RegistryListVanillaStructure`, `@RegistryListVanillaStructureSet`, `@RegistryListNativeJigsawPool`, `@RegistryListPotionEffect`, `@RegistryListEnchantment`, `@RegistryListSpecialEntity`, `@RegistryListFont`, `@RegistryMapBlockState`) for live server registries.
- A field with no `@Desc` still emits, with the description `No Field Description`, and the builder logs a warning naming the field and class when the schema is generated. A type with no `@Desc` logs a similar warning.

**These annotations are editor hints only.** Nothing validates `@Required`, `@MinNumber` or `@MaxNumber` at load time. The schema will underline an out-of-range value in your editor; the engine will load it anyway. The dimension-type height rules in `11 - Dimensions.md` are the exception — those are enforced in code and throw.

### Snippets

Classes annotated `@Snippet("<type>")` get their own schema at `.iris/schema/snippet/<type>-schema.json`, and every field of a snippet type is emitted as an `anyOf` of the inline object and a string. The string alternative is itself an `anyOf` of an enum of the snippet files that currently exist and the pattern `^snippet/<type>/`, so both existing and not-yet-created snippet paths validate.

At load time a Gson type adapter resolves any string in a snippet-typed position: `"snippet/decorator/wildflowers"` reads `<pack>/snippet/decorator/wildflowers.json` and parses it in place. This works anywhere the type appears, including inside arrays. A string that does not start with `snippet/` resolves to null, and a missing snippet file logs an error naming the reference and the JSON path.

### File matching

`ResourceLoader.buildSchema()` runs for each loader that reports `supportsSchemas()`, and emits seven glob patterns per folder so nested resource keys are covered:

| Pack folder pattern | Schema URL (relative to pack) |
|---------------------|--------------------------------|
| `/<folder>/*.json` through `/<folder>/*/*/*/*/*/*/*.json` (7 depth levels) | `./.iris/schema/<folder>-schema.json` |
| `/snippet/<type>/*.json` through 7 levels | `./.iris/schema/snippet/<type>-schema.json` |

Folders with schemas: `dimensions`, `regions`, `biomes`, `generators`, `loot`, `entities`, `spawners`, `markers`, `blocks`, `expressions`, `mods`, `structures`, `jigsaw-pools`, `jigsaw-pieces`. The object, image, and matter loaders return false from `supportsSchemas()` because their content is binary, so `objects/`, `images/` and `matter/` get no schema.

Files under `.iris/schema/` are generated editor artifacts. They are safe to delete and are rewritten on the next workspace update; pack content is the JSON under the type folders.

### What refreshes schemas

| Trigger | Effect |
|---------|--------|
| `/iris studio update dimension=<dim>` | Rewrites the workspace and queues schema writes |
| `/iris studio open` or `create` | Builds the workspace config, including schemas |
| Successful hotload | The platform hook may refresh the workspace |

Registry-backed enums are captured from the live server, so a schema generated on a server without a mod installed will not offer that mod's blocks. Regenerate after changing the server's mod or datapack set.

## Studio dimension modes (author testing)

The dimension field `studioMode` swaps in a debug generator. It is applied by the Bukkit chunk generator only; Fabric, Forge and NeoForge ignore it.

| Value | Effect |
|-------|--------|
| `NORMAL` | Default generation |
| `BIOME_BUFFET_1x1`, `_3x3`, `_5x5`, `_9x9`, `_18x18`, `_36x36` | Lays every biome out in a grid of that cell size so palettes and decorators can be compared side by side |
| `OBJECT_BUFFET` | Object studio generator; also forced automatically while an object studio session is active |
| `REGION_BUFFET` | Installs no studio generator today and behaves exactly like `NORMAL` |

These are testing fields, not production world modes — the production engine mode is `mode.type` (see `11 - Dimensions.md`). Remove `studioMode` before packaging.

Jigsaw Studio does not add a `studioMode` value; `/iris jigsaw open` and `create` select its generator transiently for one Studio activation.

## Jigsaw Studio (Bukkit)

`/iris jigsaw` opens one selected structure graph through the transient Studio lifecycle, but chooses `JigsawStudioGenerator` for that activation without persisting a special dimension mode. The owner enters in creative, `spawn_mobs` is disabled, and natural creature-spawn events are cancelled. Planar Studio has six rotation-independent workcells in a compact three-column by two-row layout: Blank, End Cap, Hallway, L Junction, T Junction, and Cross Junction. Spatial Studio places every variant in its own dedicated cell in one horizontal row. New spatial projects seed seven 15×15×15 cells labeled 0 through 6 connectors, with one additional north, south, east, west, up, then down face-center connector in each successive cell. All seven pieces belong to the start pool; the 1-through-6 connector pieces also belong to the generated pieces pool with an explicit empty terminator and a 16-placement default per piece. Their seed-`1337` preview is a connected blob raised 48 blocks above the editing floor without making the intentionally connectorless piece an unreachable child. There is no orientation, permutation, or derived-rotation gallery.

Each planar floor is light-gray wool with a red canonical topology glyph and sea-lantern caps at its face-center connector positions. Every workcell has an independent width, height, depth, enabled state, and optional author label. Those dimensions are capacity only: changing one never rewrites a variant object, and the complete change is rejected if any existing variant would no longer fit. Each owned variant has its own exact width, height, depth, and optional label, so one End Cap can be a `16×3×3` longhouse while another End Cap in the same workcell remains `3×3×3`. Per-variant growth or lossless shrink preserves in-bounds canonical content and moves canonical connector payloads and sockets to the new face centers; cropped stored content, connector collisions, or shared/read-only objects reject the transaction. Workcell Settings stages width, height, and depth clicks in the open menu and performs one live regeneration only after **Apply Cell Size**; resizing the loaded variant still reloads that cell in place. Every enabled or disabled cell uses a physical white-concrete edge cage with particle trails drawn inside its editable bounds, and no workcell-bound display entity is spawned. Existing planar variants are rotated into the archetype's canonical display orientation and inverse-rotated during capture, while their piece resources, dimensions, labels, and pool entries remain distinct.

Create a default planar, Iris-native graph with:

```text
/iris jigsaw create <dimension> <key>
```

`key` is the structure's internal resource path: `village/demo` writes `structures/village/demo.json` and becomes the key used by Iris placements and later editing. Named arguments `structure=` and `name=` are aliases for `key=`; they do not select a separate vanilla structure or template. Omitted options default to `mode=planar`, `compatibility=iris`, `width=15`, `height=15`, `depth=15`, and `seed=1337`; `mode=` completes `planar` or `spatial`, while `compatibility=` completes `iris` or `vanilla`. Existing Iris keys tab-complete for `open`, `edit`, and `reopen`.

New Iris-compatible planar projects contain one owned piece for every archetype, assign every piece to the weighted `variant-1` structure theme, and mark the End piece terminal. New vanilla-compatible projects contain the same six owned pieces but omit Iris theme and terminal-rule metadata. Open an owned graph with `/iris jigsaw open <dimension> <key>` or the equivalent `edit`/`reopen` alias. Existing unowned Iris graphs use `adopt inspect` then `adopt apply`; managed datapack imports must be cloned. Registered vanilla or datapack jigsaws use `convert`, which creates a separate owned Iris graph.

The owner can open the six-row control GUI by right-clicking its protected chest, running `/iris jigsaw menu`, or starting three sneaks within 1.5 seconds. Walking into a workcell also makes that physical cell the owner's next menu selection, while left-clicking a workcell selects it and teleports the owner to its horizontal center. The GUI selects workcells, loads and creates variants, independently resizes variants, changes workcell capacity or enabled state, toggles per-workcell connector blocks, restores broken connector blocks from the saved variant, rewinds the latest autosave, adjusts exact pool-entry weights and chances, edits theme membership and piece rules, toggles mandatory caps, navigates to the live preview, and deletes inactive variants or the complete project. Destructive actions require a second confirmation within 10 seconds. **New Blank Variant** clones the active owned piece's complete metadata and every exact pool membership but creates an empty object with the same dimensions; **Duplicate This Cell's Variant** preserves the same metadata and memberships while copying only that source object's bytes. **Duplicate All Enabled Cells as Family** atomically clones the loaded owned variant in every enabled workcell and rebinds the complete family together. All duplication uses service-generated keys and requires active owned variants with owned pool memberships. A duplication clicked during dirty or in-flight autosave is queued once, expedites autosave, and continues automatically only while the request, session, and source variants still match. Iris never chooses a first or lexicographically sorted pool as a fallback; use `/iris jigsaw piece create <poolKey> <pieceKey>` for an empty or unassigned workcell.

The **Toolbox** page gives the player named stick items bound to the current Studio request and the selected workcell, variant, pool entry, or action. Variant/workcell rename sticks are renamed in an anvil, right-clicked to apply the 64-code-point label, and sneak-right-clicked to reset; control characters and section-sign formatting are rejected. Right-clicking another valid tool performs its action or opens the exact GUI context needed for capacity, per-variant size, themes, or rules. Bound tools use schema `2`; schema-`1` tools and sticks from a replaced or closed Studio are rejected. The active variant uses a jigsaw-block icon, valid evaluation uses an emerald, and lime dye is reserved for the explicitly labeled theme-membership toggle. Destructive stick tools also require a second right-click within 10 seconds.

Building, marker, container, and machine changes inside a loaded owned workcell autosave after a 40-tick quiet period. Before each changed graph commit, Iris retains the prior complete ownership-manifest closure; identical resource blobs are deduplicated and the newest five iterations remain in one atomic `.iris/jigsaw-history/key-<sha256>.json` sidecar. **Undo Last Autosave** restores the newest entry through the same ownership writer, then reloads the affected active variant; repeated clicks rewind until the five-entry stack is empty. Project creation clears stale same-key history and project deletion removes it. Fresh untouched workcells report **Autosaved**. Later edits replace the pending capture identity, and a busy autosave retries until the current save/load/graph barrier permits it. When the owning player runs `/iris studio open` while Jigsaw Studio is active, Iris expedites and waits for these barriers, claims the close, and continues opening the ordinary Studio; console and non-owner replacement remain blocked. Opening Mojang's jigsaw-block UI starts a persistent owning-region NBT watch; changed tile data marks the workcell dirty, and commands, tools, teleport/world changes, quit, graph operations, **Flush Autosave Now**, close, and enabled-world unload request a final tile snapshot before proceeding. Tracked events include block placement, breakage, buckets, growth, fluids, pistons, redstone, explosions, block-state interactions, recognized mutating commands, inventory click/drag/close plus internal move/pickup, and furnace, brewing-stand, dispenser, and crafter activity. **Flush Autosave Now** and `/iris jigsaw save` only request an immediate flush; if a barrier or scheduler prevents capture from starting, the same pending autosave remains queued for retry. They are not a required authoring step. Paper drains pending work synchronously during disable. A forced Folia plugin disable occurs after Folia rejects new region tasks, so close Studio or wait for `status` to report no pending autosave before a reload or server stop; that late disable hook cannot guarantee a new final cross-region capture. An external integration that bypasses Bukkit events must call `JigsawStudioService.markDirty(...)` or `markAllDirty(...)`.

Each committed graph is compiled and assembled automatically with seed `1337`. The GUI reports `PENDING`, `VALID`, `WARNING`, `INVALID`, or `STALE`, the selected theme, piece count, and current detail. Iris keeps the assembled blocks on the negative-X side for the active Studio session, replaces them after later commits, and protects the complete preview bounds from edits, fluids, pistons, fire, growth, explosions, entities, and redstone. The live renderer accepts at most 250,000 explicit blocks; a larger result becomes `INVALID` with the render-limit diagnostic. **Go to Preview** or `/iris jigsaw preview goto` teleports above it. This live block preview is separate from `/iris jigsaw preview assemble`, which remains a temporary player-local particle diagnostic for an arbitrary seed.

Structure themes select one weighted family before assembly. **Duplicate All Enabled Cells as Family** allocates the next `variant-<n>` theme by default, clones the currently loaded owned variant from every enabled workcell with its exact object size and label, duplicates their pool memberships, assigns the new pieces to that family, and atomically loads the new family across those workcells. Individual loaded owned variants can join one or more declared themes; an empty theme list makes a piece available to every selected theme. Pool membership `chance` is an independent `0..1` eligibility gate applied before its positive relative weight. Piece rules constrain minimum/maximum depth, minimum/maximum placements, and terminal status. With mandatory caps enabled, an unresolved open connector must use its direct fallback to place a compatible terminal piece; failure rejects that assembly. Themes, chance gates, piece rules, and mandatory caps are Iris-only and block `VANILLA_PORTABLE` compilation or export when used.

Connector blocks are hidden per workcell by default and can be shown from **Workcell Settings**. **Reset Connector Blocks** rewrites every saved connector coordinate in the selected workcell from the active on-disk variant while leaving every other edited block unchanged; it restores jigsaw orientation and NBT while visible, or the exact final block and tile NBT while hidden. If an autosave already committed a deleted connector, use **Undo Last Autosave** first. Hidden capture retains each connector's pool, identity, orientation, priorities, channel, and authored order while the ordinary block and tile NBT at that coordinate become its exact final state; visible mode exposes Mojang's jigsaw UI for pool, name, target, joint, final state, and both signed priorities. `/iris jigsaw connector channel <channel|none>` changes the saved Iris-only channel for the exact targeted visible connector. Particle trails outline nearby and focused editable bounds inside the white-concrete cages; connector diagnostics remain red for incomplete identity, lime for a valid connector without a channel, and deterministic by channel otherwise. The Iris scoreboard switches automatically to Jigsaw context and shows the structure, author workcell label, canonical solver role when the label differs, loaded variant label, state, and `Triple-sneak for controls`; `/iris studio scoreboard` retains its session-only toggle behavior. Every successful atomic workcell save plays one owner-local bell.

Bukkit has one global Studio project/world and one owning Jigsaw session. Only that owner can control or mutate it. Non-owner edits are cancelled, non-owner commands use a strict informational/communication allowlist, and the control chest plus live preview are protected. Autosave, variant switching, graph changes, opening, closing, and deletion share operation barriers. Close waits for clean state unless `discard=true`; discard is only for deliberately losing pending work.

Dimensions are capped at 128 blocks on X/Z, 192 on Y, and 2,097,152 blocks in total; planar variant and capacity width/depth must each be at least 3. A workcell capacity change persists only structure metadata, verifies every variant fits, leaves all object bytes unchanged, and atomically regenerates the affected white-concrete cage, objects, connector view, and block-entity hydration before editing resumes. Workcell Settings batches all three staged axes into that one transaction; **Discard Size Changes** cancels the menu-local values without writing. `variant resize` and the **Variant Size** screen change only the selected owned object; lossless growth and shrink preserve in-bounds canonical content, reject cropped explicit air/blocks/tiles/connectors, and relocate planar canonical sockets. The loaded variant reloads in place; inactive planar variants remain untouched, while a spatial variant's dedicated cell moves with the live row. **Resize to Capacity** or `/iris jigsaw piece expand` is a convenience for setting one selected object exactly to its current capacity. On Folia, each intersecting object chunk is read on its owning region and one graph write begins only after the full snapshot validates.

Deleting a planar variant is limited to an owned, inactive variant when another variant remains in that workcell. A spatial variant is always active in its dedicated cell, so deletion removes that cell when at least one other spatial variant remains. Project deletion first verifies ownership hashes and scans the pack for external JSON or ownership-manifest references; any reverse reference blocks deletion. A clear result closes Studio and removes the complete owned resource set through a hash-pinned transaction. If the post-close delete fails, the project files remain on disk for recovery.

This command tree is Bukkit-only. Saved `PLANAR_JIGSAW` and `SPATIAL_JIGSAW` pack resources run in the shared core on Fabric, Forge, and NeoForge, and strict `VANILLA_PORTABLE` graphs can be exported as Minecraft 26.2 datapacks. The complete workflow, commands, marker rules, portability blockers, and recovery steps are in `21 - Jigsaw Structures.md`.

## Platform notes

| Platform | Studio |
|----------|--------|
| Paper / Purpur / Folia (Bukkit plugin) | Full `CommandStudio` plus file-watch hotload on studio worlds; `studioMode` honored; Jigsaw Studio available |
| Fabric / Forge / NeoForge | Studio open/create/workspace/package and a subset of tooling; no Bukkit-only importers or inventory GUIs; `studioMode` ignored; no Jigsaw Studio authoring commands |

Pack JSON contracts are shared across every platform, and schemas are built from the same core models, so a pack authored on one platform loads on all of them.

# 10 - Studio & VSCode Schemas

Studio is Iris’s live pack-authoring workflow: open a pack as a transient world, edit JSON under `packs/<key>/`, and hotload changes without a full server restart. VSCode (or IntelliJ) gets JSON Schema bindings generated from the Java models so field names, enums, and pack resource keys autocomplete against the real loaders.

Related: see `04 - Commands & Permissions.md`, `05 - Concepts & Pack Layout.md`, `02 - Getting Started.md`, `21 - Jigsaw Structures.md`, `25 - Pack Management.md`, `30 - Platform Differences.md`.

## Tutorial: use the Studio edit loop

Prerequisites: a writable packs directory, command permission, a fixed seed, and VSCode/Cursor with JSON Schema support or IntelliJ with an existing project. Keep the server console visible while editing.

### Bukkit-family starter pack

1. Create the project: `/iris studio create name=tutorial`.
2. Open its transient world: `/iris studio open tutorial seed=1337`.
3. Generate/open the workspace: `/iris studio vscode dimension=tutorial`.
4. Edit `packs/tutorial/biomes/starter.json` and change only its display `name`.
5. Save once and wait for the hotload result before making another change.
6. In newly generated Studio terrain, run `/iris what biome` and confirm the new display name. Existing blocks are not rewritten by hotload.
7. Validate the project: `/iris pack validate pack=tutorial`.
8. Close the transient world: `/iris studio close`.

### Fabric / Forge / NeoForge project

1. Create from the modded default template explicitly: `/iris studio create tutorial example`.
2. Open it: `/iris studio open tutorial 1337`.
3. Generate/open schemas: `/iris studio vscode tutorial`.
4. Trace the active dimension to one referenced biome, change one low-risk display or palette value, and save once.
5. Wait for hotload, enter newly generated terrain, and inspect it with `/iris what biome`.
6. Validate with `/iris pack validate tutorial`, then close with `/iris studio close`.

The loop passes when the editor binds the generated schema, hotload succeeds, pack validation has no blocking errors, and newly generated chunks show the change. Create a separate production world only after this gate. A rejected runtime-contract change such as dimension height requires closing and reopening Studio; it is not evidence that hotload is broken.

### Recovery

| Symptom | Meaning | Recovery |
|---|---|---|
| `open` reports blocking validation errors | Pack graph cannot safely build an engine | Run the platform's `pack validate` form, fix the first blocking error, and retry; do not bypass validation |
| Save reports hotload failure | New data/runtime build failed and the previous runtime may remain active | Fix the first console error and save again before making unrelated edits |
| Height, logical height, or dimension type change is rejected | Change violates `IrisDimensionRuntimeContract` | Close Studio and reopen; on modded, restart when regenerated dimension-type datapacks require registry reload |
| Valid change is invisible | Existing chunks are already materialized or the edited resource is unreachable | Move to new chunks and trace the active dimension graph; use focus/buffet modes for isolation |
| Workspace has no autocomplete or stale resource keys | Schemas were not generated/refreshed or the editor did not open the workspace | Run `studio update`, then open the pack's `.code-workspace`; on headless servers open it manually |
| Studio world disappears after restart | Studio worlds are intentionally transient and purged | Reopen the pack; content under `packs/<key>/` remains the source of truth |

## What Studio Is

| Concept | Behavior |
|---------|----------|
| Pack workspace | Packs live under the platform data directory folder named `packs` (`StudioSVC.WORKSPACE_NAME`). |
| Studio world | Opened from a pack dimension key; uses a studio chunk generator with live file watching. |
| Hotload | On ordinary Studio worlds only: a low-priority looper polls pack files; when content changes, `EngineHotloader` waits for already-admitted top-level Bukkit chunk stages, reloads the pack data, and rebuilds engine runtime under exclusive generator control. Fair stage admission keeps later chunk stages behind the waiting transition, and Studio close uses the same drain boundary. Biome Buffet resolves its chunk focus and completes any required complex hotload under exclusive admission before that noise stage opens a generation session, then downgrades directly to one ordinary stage permit. |
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

## Jigsaw Studio (Bukkit)

`/iris jigsaw` opens one selected structure graph through the transient Studio lifecycle, but chooses `JigsawStudioGenerator` for that activation without persisting a special dimension mode. The owner enters in creative. Planar Studio has six rotation-independent workcells in a compact three-column by two-row layout: Blank, End Cap, Hallway, L Junction, T Junction, and Cross Junction. Spatial Studio has one workcell. There is no orientation, permutation, piece, or derived-rotation gallery.

Each planar floor is light-gray wool with a red canonical topology glyph and sea-lantern caps at its face-center connector positions. Every workcell has an independent width, height, depth, enabled state, and optional author label. Those dimensions are capacity only: changing one never rewrites a variant object, and the complete change is rejected if any existing variant would no longer fit. Each owned variant has its own exact width, height, depth, and optional label, so one End Cap can be a `16×3×3` longhouse while another End Cap in the same workcell remains `3×3×3`. Per-variant growth or lossless shrink preserves in-bounds canonical content and moves canonical connector payloads and sockets to the new face centers; cropped stored content, connector collisions, or shared/read-only objects reject the transaction. Capacity changes regenerate and rehydrate the compact layout in place, while resizing the loaded variant reloads that cell in place. A disabled planar workcell remains editable but is excluded from assembly and vanilla export; a full-volume red stained-glass display marks it and is recreated when its origin chunk reloads. Existing planar variants are rotated into the archetype's canonical display orientation and inverse-rotated during capture, while their piece resources, dimensions, labels, and pool entries remain distinct.

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

Connector blocks are hidden per workcell by default and can be shown from **Workcell Settings**. **Reset Connector Blocks** rewrites every saved connector coordinate in the selected workcell from the active on-disk variant while leaving every other edited block unchanged; it restores jigsaw orientation and NBT while visible, or the exact final block and tile NBT while hidden. If an autosave already committed a deleted connector, use **Undo Last Autosave** first. Hidden capture retains each connector's pool, identity, orientation, priorities, channel, and authored order while the ordinary block and tile NBT at that coordinate become its exact final state; visible mode exposes Mojang's jigsaw UI for pool, name, target, joint, final state, and both signed priorities. `/iris jigsaw connector channel <channel|none>` changes the saved Iris-only channel for the exact targeted visible connector. Aqua particles mark the occupied workcell, dark gray marks nearby valid bounds, red marks invalid bounds or incomplete connector identity, lime marks a valid connector without a channel, and a channel receives a deterministic color. The Iris scoreboard switches automatically to Jigsaw context and shows the structure, author workcell label, canonical solver role when the label differs, loaded variant label, state, and `Triple-sneak for controls`; `/iris studio scoreboard` retains its session-only toggle behavior.

Bukkit has one global Studio project/world and one owning Jigsaw session. Only that owner can control or mutate it. Non-owner edits are cancelled, non-owner commands use a strict informational/communication allowlist, and the control chest plus live preview are protected. Autosave, variant switching, graph changes, opening, closing, and deletion share operation barriers. Close waits for clean state unless `discard=true`; discard is only for deliberately losing pending work.

Dimensions are capped at 128 blocks on X/Z, 192 on Y, and 2,097,152 blocks in total; planar variant and capacity width/depth must each be at least 3. A workcell capacity change persists only structure metadata, verifies every variant fits, leaves all object bytes unchanged, and atomically regenerates the affected live cages, objects, connector view, and block-entity hydration before editing resumes. `variant resize` and the **Variant Size** screen change only the selected owned object; lossless growth and shrink preserve in-bounds canonical content, reject cropped explicit air/blocks/tiles/connectors, and relocate planar canonical sockets. The loaded variant reloads in place; inactive variants remain untouched. **Resize to Capacity** or `/iris jigsaw piece expand` is a convenience for setting one selected object exactly to its current capacity. On Folia, each intersecting object chunk is read on its owning region and one graph write begins only after the full snapshot validates.

Deleting a variant is limited to an owned, inactive variant when another variant remains in that workcell. Project deletion first verifies ownership hashes and scans the pack for external JSON or ownership-manifest references; any reverse reference blocks deletion. A clear result closes Studio and removes the complete owned resource set through a hash-pinned transaction. If the post-close delete fails, the project files remain on disk for recovery.

This command tree is Bukkit-only. Saved `PLANAR_JIGSAW` and `SPATIAL_JIGSAW` pack resources run in the shared core on Fabric, Forge, and NeoForge, and strict `VANILLA_PORTABLE` graphs can be exported as Minecraft 26.2 datapacks. The complete workflow, commands, marker rules, portability blockers, and recovery steps are in `21 - Jigsaw Structures.md`.

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

Both ordinary and Jigsaw Studio reuse Iris's startup-loaded datapack runtime only while its pinned compiler-input fingerprint still matches every live `dimensions`, `biomes`, and `snippet` JSON input, the compiler build, and the vanilla-height policy. A changed input, unavailable registry, failed startup recovery, or changed/failed external datapack ingest or removal invalidates reuse and falls back to recovery, compilation, publication, and the existing restart gate; a verified no-change ingest or recovery check restores the prior pin. Object, structure, jigsaw, pool, and ownership edits do not affect generated dimension types or custom biomes and therefore do not force that fallback.

Ordinary Studio still resolves and teleports through its standard safe entry, may launch the pack workspace, prepares the complete mantle radius, and preserves native structures for generation previews. On Paper 26.2, WorldInit publishes the filtered native-structure placement state once but leaves it uninitialized while native starts, locates, and object-collision volume queries are gated; after the exact FULL entry-chunk request and retention ticket settle and the standard safe-entry teleport step succeeds when applicable, the global scheduler claims that exact level, chunk map, generator, and state, starts its placement initialization, registers the exact concentric-ring futures, enables collision-volume queries, and then lowers the structure gate. The ring searches finish in the background rather than delaying entry or extending Studio ready time, while normal close, full hotload, and complex hotload wait up to 120 seconds for their exact aggregate before mutating or sealing the engine; a synchronous partial-start failure permanently rejects those transitions for that engine because a complete drain cannot be proven.

Jigsaw Studio publishes an initialized empty native-structure state even when no managed datapack scope exists, never retains or activates the filtered full state, and keeps starts, references, locates, and native collision-volume queries disabled. Its dedicated open kind also skips the standard-entry teleport, workspace launch, procedural generation-cache warm, complete mantle-radius preparation, and ordinary pack-file hotloader before sending the owner once through the selected workcell destination. Jigsaw graph transactions directly invalidate, reload, evaluate, and rematerialize their owned resources; close and reopen Jigsaw Studio to apply unrelated external pack edits.

Paper-family entry chunks are requested through the urgent asynchronous chunk API before Iris retains them with a plugin ticket; Folia retains its nonblocking ticket bootstrap and confirms the owning region before entry resolution. If an open fails while that exact request remains active, Iris reports the failure without unloading or closing the generator, rejects another Studio open until cleanup succeeds, and queues the transient world for deletion at the next clean startup if it remains active for another 120 seconds. Closing a Studio stops Iris engine maintenance at `WorldUnloadEvent`, waits for the raw backend unload completion and any tracked native ring preparation before sealing the generator, and the 26.2 noise pipeline keeps its generation lease through terrain and heightmap completion. Forced process termination cannot drain in-process ring futures.

Every Bukkit Studio open writes one `[Studio timing]` line per lifecycle phase with the transient world, `standard` or `jigsaw` kind, phase duration, and cumulative duration where available. The measured phases separate loaded-runtime reuse, external-datapack recovery, compiler-input fingerprinting, datapack compilation/publication, generator preparation, Bukkit world creation, entry-chunk loading, safe-entry resolution, standard teleport, and finalization. Studio engine timing additionally separates prefetch loading, runtime construction, and the generation-cache warm or its Jigsaw-only skip so a slow open can be correlated with a profiler capture.

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

Jigsaw Studio does not add a `studioMode` enum value. `/iris jigsaw open` and `create` select its generator transiently for that one Studio activation.

## Platform Notes

| Platform | Studio |
|----------|--------|
| Paper/Purpur/Folia (Bukkit plugin) | Full `CommandStudio` + file-watch hotload on studio worlds |
| Fabric / Forge / NeoForge | Studio open/create/workspace/package; subset of tooling; no Bukkit-only importers/GUIs that need Bukkit inventory |

Jigsaw Studio authoring commands are part of the Bukkit row only. Cross-loader pack runtime remains shared; see `21 - Jigsaw Structures.md` and `30 - Platform Differences.md`.

Pack JSON contracts are shared across platforms. Schemas are built from the same core models.

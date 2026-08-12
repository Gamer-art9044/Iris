# 04 - Commands & Permissions

Iris exposes one root command, `/iris` (aliases `/ir`, `/irs`), on every platform. Bukkit uses VolmLib Director, where optional arguments are always `key=value`; Fabric, Forge, and NeoForge register a Brigadier tree with positional arguments and literal flags. This page is the complete command reference; platform gaps are marked **Bukkit-only** or **modded-only**. See `30 - Platform Differences.md` for the platform matrix and `03 - Configuration.md` for what `/iris reload` re-reads.

## Everyday commands

Four workflows cover most operator use. The Bukkit and modded forms are separate command trees, not translations of each other, so do not port `key=value` tokens to a mod loader.

### Create a world and enter it

```
/iris create tutorial type=overworld seed=1337        # Bukkit
/iris create tutorial overworld 1337                  # modded
```

`type` (aliases `dimension`, `pack`) takes a pack key or `pack:dimensionKey`. Left at its default `default`, it resolves to `generator.defaultWorldType`. Bukkit refuses the names `iris` and `benchmark`, and refuses any name whose dimension folder already exists.

When it works, Bukkit prints `Successfully created your world!` and the world is immediately teleportable with `/iris tp tutorial`. On Folia the world is staged instead and the message tells you to restart before it exists. On mod loaders the dimension appears in `/iris world list`, and you enter it with `/iris tp irisworldgen:tutorial`.

If the pack is missing you get `Could not find or download dimension …` plus a `/iris download <pack>` hint — the world is not created.

### Pregenerate an area

Radius is in **blocks** and measured from the center, so `352` covers a 704x704 block square.

```
/iris pregen start 352 world=tutorial center=0,0 gui=false          # Bukkit
/iris pregen start 352 irisworldgen:tutorial at 0 0                 # modded
```

On Bukkit, `world` resolves from the sender's current world when omitted and is hidden from the in-game usage line, but `world=` still works — use it from console. `center=me` uses your own position. On modded, `at` must come after the dimension, never before it, and the flags `gui`, `sync`, `nocache` are literals in any order. The resumable checkpoint cache is on unless you pass `nocache`.

Confirm with `/iris pregen status`. A running job prints the target world, generated and total chunks, percent, chunks/s, ETA, elapsed time, the generation method, and a failure count. No output line for failures means nothing has failed. `/iris pregen pause` toggles, and `/iris pregen stop` finishes in-flight work before cancelling. Only one job runs server-wide. Detail: `07 - Pregeneration.md`.

### Open a studio for pack authoring

```
/iris studio open overworld seed=1337        # Bukkit
/iris studio open overworld 1337             # modded
```

A transient studio world opens and you are teleported into it; saving any pack file hotloads the change into that world. `/iris studio close` (alias `x`) discards the world. `/iris studio vscode` regenerates the `.code-workspace` and JSON schemas, and opens it in the desktop editor unless `studio.openVSCode` is false. Detail: `10 - Studio & VSCode Schemas.md`.

### Check a pack before you rely on it

```
/iris pack validate pack=overworld     # Bukkit — pack is required, see the note below
/iris pack validate                    # modded — empty means every pack
```

A clean pack reports no blocking errors; the all-packs form finishes with a broken-pack count out of the total scanned. `/iris pack status` reprints the last recorded result without revalidating. Warnings do not block world creation, blocking errors do. Detail: `25 - Pack Management.md`.

**Bukkit quirk:** `/iris pack validate` and `/iris pack status` describe `pack` as optional ("leave empty for all"), but the parameter is declared with a blank default, which Director treats as *no* default. Both commands therefore reject a bare invocation with a missing-argument error, and the all-packs branch in the code is unreachable from Bukkit chat. Pass a pack name explicitly.

### Other common goals

| Goal | Bukkit-family | Fabric / Forge / NeoForge | Detailed guide |
|---|---|---|---|
| Replace the vanilla Nether slot | `/iris create world_nether type=underworld seed=1337 overwrite=true`, then restart | Not available | `06 - Worlds & Lifecycle.md` |
| Create an in-game jigsaw project | `/iris jigsaw create overworld village/demo` | Not available; author on Bukkit and copy the saved pack | `21 - Jigsaw Structures.md` |
| Inspect an Iris jigsaw graph | `/iris structure info overworld <structure>` | `/iris structure info <structure>` while in its Iris dimension | `21 - Jigsaw Structures.md` |
| Remove a disposable Iris world | Evacuate players, `/iris unloadWorld <world>`, then `/iris remove <world>` | `/iris world delete <dimension>` | `06 - Worlds & Lifecycle.md` |

If a command fails before doing work, check in this order: platform syntax, permission, sender type (player versus console), exact pack/world key, then lifecycle busy state. A parse error is not evidence that the underlying feature failed.

## Syntax

### Bukkit (Director)

- Root: `/iris` / `/ir` / `/irs`. Subcommand and group names come from the method or class name unless `@Director(name=…)` overrides it, so several commands read differently than you would guess — see the reference tables.
- **Required parameters are positional; optional parameters are never positional.** `/iris pregen start 500 true` is a parse error; write `/iris pregen start 500 gui=true`.
- A parameter declared with a blank default counts as required even when the description says otherwise.
- Names and aliases match case-insensitively.
- Help uses the Director mini-menu: required renders as `<name>`, optional as `[name=…]`.
- **Contextual** parameters (world, dimension, pack, location, generator, template on many nodes) resolve from the sender's current world or look target. They are hidden from the usage line and from tab completion, but they still accept `name=value`, which is how you drive them from console.
- Tab completion is not permission-gated; only execution is.

### Modded (Brigadier)

- Same root and aliases; `ir` and `irs` are redirect nodes onto `iris`.
- Arguments are ordered literals and typed arguments, never free-form `key=value`.
- `/iris` and `/iris help [section]` open the help browser: a paginated clickable UI for players (17 entries per page, trailing page number accepted in the section string) and a flat text list for console. Bare group nodes route into the same help.
- Flags are literals where used (pregen `gui`, `sync`, `nocache`; download `force`).

## Permissions

### Bukkit

| Permission | Declared in | Default | Gate |
|------------|-------------|---------|------|
| `iris.all` | `plugin.yml` and `paper-plugin.yml` | `op` | `CommandSVC.executeRoot` rejects every `/iris` execution without it |
| `iris.treefeller` | `plugin.yml` and `paper-plugin.yml` | `op` | Survival tree feller only (`TreeFellerSVC`); also requires `treeFeller.enabled` in settings |

`iris.all` is code-gated as `ROOT_PERMISSION` in `CommandSVC`. There are no per-subcommand permission nodes: a sender either has the whole tree or none of it. Custom-biome restart warnings also notify online players who are op **or** hold `iris.all`.

### Modded

| Gate | Brigadier level | Applies to |
|------|-----------------|------------|
| Gamemaster | `Commands.LEVEL_GAMEMASTERS` (2) | Everything that mutates: create/world, studio, object tools, pregen, download, debug, reload, evacuate, teleport, seed, edit, find/goto, structure, datapack, pack, developer, regen, goldenhash, accesslist |
| Read-only | `Commands.LEVEL_ALL` (0) | `help`, `version`, `info` (seed field omitted unless gamemaster), `worlds`, `height`, `metrics`, and the entire `what` subtree |

The root `iris` literal itself carries no requirement, so any player can run it and reach the help browser; the help marks itself as restricted when the source fails the gamemaster check.

Tree feller on mod loaders uses the platform permission API with node `irisworldgen:treefeller` (Fabric `Identifier`; Forge and NeoForge `PermissionNode`), defaulting to gamemaster level on all three. It is not a Bukkit permission string.

---

## Root: `/iris`

Bukkit names below are the names Director actually registers. Where that differs from what you would expect, the method name is what you type.

| Command | Aliases | Platforms | Params (Bukkit-style) | Description |
|---------|---------|-----------|------------------------|-------------|
| (empty) / help | | Both | `[section]` (modded) | Open help; modded supports a section path and page number |
| `version` | | Both | — | Print Iris/platform/Minecraft version and engine count |
| `info` | | **Modded** | `[dimension]` (substring filter) | List Iris dimensions and pack details; seed only for gamemasters |
| `create` | `c` | Both | **Bukkit:** `<name> [type=default] [seed=1337] [main=false] [overwrite=false]` (`name` alias `world-name`; `type` aliases `dimension`,`pack`; `main` alias `main-world`; `overwrite` alias `force`). **Modded:** `<name> [pack=overworld] [seed=1337]` | Create an Iris world/dimension; Bukkit `overwrite=true` stages an exact slot replacement for restart |
| `teleport` | `tp` | Both | **Bukkit:** `<world> [player]` (defaults to the sender). **Modded:** `<dimension> [player]` | Teleport self or a named player into an Iris world/dimension |
| `evacuate` | | Both | **Bukkit:** `<world>`, player origin. **Modded:** `[dimension]` | Move players out of an Iris world to fallback/primary |
| `height` | | Both | — | Print world height; player origin on Bukkit |
| `worlds` | `accesslist` (Bukkit) | Both | — | **Bukkit:** access list of worlds. **Modded:** two separate nodes — `worlds` is read-only and takes no argument, `accesslist` needs gamemaster; both print the `info` listing |
| `remove` | `rm` | **Bukkit** | `<world> [delete=true]` | Remove a managed Iris world; disk deletion defaults to true. `world` is a name, so worlds that exist only on disk are accepted |
| `loadWorld` | `import` | **Bukkit** | `<world>`, player origin | Load a managed Iris world |
| `unloadWorld` | | **Bukkit** | `<world>`, player origin | Unload an Iris world |
| `debug` | | Both | — | Toggle `general.debug` and save settings |
| `download` | `dl` | Both | `<pack> [branch=stable] [overwrite=false]` (`pack` alias `project`; `overwrite` alias `force`) | Download a pack; `overworld` and `underworld` resolve to managed beta release ZIPs |
| `metrics` | `measure` | Both | — | Generation metrics; player origin on Bukkit |
| `reload` | | Both | — | Reload `settings.json` and locale; modded also schedules forced datapack regeneration |
| `seed` | | **Modded** | — | Print world and engine seeds |
| `regen` | `rg` | **Modded** root; Bukkit under `Developer` | `[radius]` — modded default `0`, range `0..64` | Delete and regenerate nearby chunks |
| `goldenhash` | `gold` | **Modded** root; Bukkit under `Developer` | `[radius=8] [threads=8] [capture\|verify]`, radius `0..256`, threads `1..64` | Deterministic buffer hashes |
| `wand` | | **Modded** root (+ `object`) | — | Give object wand |
| `dust` | `d` | **Modded** root (+ `object`) | — | Give reveal dust |
| `find` | `goto` | Both | see Find | Locate biome/region/object/structure/POI |
| `what` | | Both | see What | Inspect context |
| `edit` | | Both | see Edit | Open pack JSON in the desktop editor |
| `pregen` | `pregenerate` | Both | see Pregen | Pregeneration control |
| `object` | `o` | Both | see Object | Object tools |
| `studio` | `std`, `s` | Both | see Studio | Studio / pack authoring |
| `jigsaw` | `jig`, `jgs` | **Bukkit** | see Jigsaw | Transaction-owned planar/spatial Jigsaw Studio |
| `pack` | `pk` | Both | see Pack | Validate/cleanup/restore/status |
| `structure` | `struct`, `str` | Both | see Structure | Structure index/import/place |
| `datapack` | `datapacks`, `dp` | Both | see Datapack | Datapack helpers |
| `Developer` | `dev` | Both | see Developer | Diagnostics; the group name is registered with a capital `D`, but matching is case-insensitive |
| `world` | `w` | **Modded** | see World | Runtime dimension enable/disable |

---

On Paper-family servers, `overwrite=true` is deliberately restart-only; Spigot rejects it because it has no pre-registry plugin bootstrap. The exact target dimension folder must already exist; use ordinary `/iris create` for a new world. The name may resolve to a safe `iris:*` world or exactly the configured main, `_nether`, or `_the_end` alias; arbitrary `minecraft:*` and foreign namespaces are rejected. Iris stages and validates a fresh pack snapshot, compare-and-swaps only that world's `bukkit.yml` generator and seed, and retains the existing dimension folder as a rollback backup until the restarted world proves its Iris identity, pack, dimension, environment, and seed. Multiple distinct slots may be staged before one restart. `main=true` is valid with overwrite only when the name is the configured main-world name. Exact vanilla slots preserve the authoritative seed shared by the existing level, regardless of the supplied `seed`; this keeps Overworld/Nether/End coordinate generation aligned, and Iris tells you which seed it used instead. Use ordinary new-main promotion when a new level seed is required.

---

## Find: `/iris find` (`goto`)

**Origin:** player (Bukkit). **Modded:** gamemaster gate.

| Command | Params | Description |
|---------|--------|-------------|
| `biome` | **Bukkit:** `<biome> [teleport=true]`. **Modded:** `<key>` | Find an Iris biome; teleport defaults to true on Bukkit |
| `region` | **Bukkit:** `<region> [teleport=true]`. **Modded:** `<key>` | Find an Iris region |
| `object` | **Bukkit:** `<object> [teleport=true]`. **Modded:** `<key>` | Find an object placement (Bukkit may teleport to the object studio first) |
| `structure` | **Bukkit:** `<structure>`, runs sync. **Modded:** `<key>` | Find a vanilla/datapack/Iris structure |
| `poi` | **Bukkit:** `<type> [teleport=true]`. **Modded:** `<type>` | Find a supported point of interest |
| `unregistered` | — | Print structures excluded from goto completion, and the rejection reasons, to console |

---

## What: `/iris what`

**Bukkit origin:** player only, and there is no bare or `here` form. On modded the whole subtree is `LEVEL_ALL`, so any player can use it.

| Command | Platforms | Params | Description |
|---------|-----------|--------|-------------|
| (empty) / `here` | **Modded** | — | Full inspect at the player position |
| `biome` | Both | — | Current Iris biome |
| `region` | Both | — | Current Iris region |
| `block` | Both | — | Target block |
| `hand` | Both | — | Held item |
| `markers` | Both | `<marker>` | Reveal nearby markers (for example `cave_floor`, `cave_ceiling`, `object`) |

---

## Edit: `/iris edit`

**Bukkit origin:** player. Opens pack JSON in the desktop editor. The same tree is also mounted at `/iris studio edit …` on Bukkit.

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `biome` | `b` | **Bukkit:** `<biome>`. **Modded:** `[key]` | Open biome JSON (modded: omit the key for the current biome) |
| `region` | `r` | **Bukkit:** `<region>`. **Modded:** `[key]` | Open region JSON |
| `dimension` | `d` | **Bukkit:** `<dimension>`. **Modded:** — | Open dimension JSON (modded: current pack) |

---

## Pregen: `/iris pregen` (`pregenerate`)

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `start` | | **Bukkit:** `<radius> [world] [center=0,0] [gui=true] [serial=false]` (`radius` alias `size`, `center` alias `middle`, `me` for the player position; `world` is contextual with no default). **Modded:** `<radius> [dimension] [at <x> <z>] [gui] [sync] [nocache]`, radius `1..100000` | Start pregen; radius in **blocks**; the resumable checkpoint cache is on by default on modded unless `nocache` |
| `stop` | `x` | — | Stop the active pregen after in-flight work closes |
| `pause` | `resume` | — | Toggle pause/resume |
| `status` | | — | Progress, chunks/s, ETA, elapsed, method, failures |

**Bukkit:** `serial=true` requires a Paper-compatible server (strict serial chunk generation) and is rejected elsewhere. **Modded:** `sync` is the serial-like flag; `gui` opens the boss-bar/GUI path when available.

See `07 - Pregeneration.md`.

---

## Object: `/iris object` (`o`)

**Bukkit:** group origin is player. Root `wand`/`dust` also exist on modded.

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `wand` | | Both | — | Give the Iris object wand |
| `dust` | `d` | Both | — | Give reveal dust |
| `save` | | Both | **Bukkit:** `<name> [overwrite=false] [legacy=true]` (`overwrite` alias `force`; a contextual `dimension` is resolved from your world, or passed as `dimension=`). **Modded:** `[overwrite] <name>` | Save the wand selection as `.iob` |
| `paste` | | Both | **Bukkit:** `<object> [edit=false] [rotate=0] [scale=1]`. **Modded:** `[at x y z] [rotate degrees] <key>` | Paste an object |
| `expand` | | **Modded** | `[amount=1]`, range `1..256` | Expand the selection along your look direction |
| `contract` | `-` | Both | `[amount=1]` | Contract the selection along your look direction |
| `shift` | | Both | `[amount=1]` | Shift the selection along your look direction |
| `position1` | `p1` | Both | **Bukkit:** `[here=true]` uses the block under your feet; `here=false` uses your look target. **Modded:** feet by default, `look` switches to the look target | Set one selection corner; requires the Iris wand in hand |
| `position2` | `p2` | Both | same | Set the other selection corner |
| `x+y` | `xpy` (modded) | Both | — | Autoselect up and out |
| `x&y` | `xay` (modded) | Both | — | Autoselect up, down, and out |
| `analyze` | | Both | `<object>` | Composition stats |
| `shrink` | | Both | `<object>` | Shrink the object to its minimum bounds |
| `plausibilize` | | Both | **Bukkit:** `<target> [dryrun=false] [reach=12]`; `target` accepts a key or a `prefix/`. **Modded:** greedy `<args>` parsed as `key [dryrun] [reach]`, same defaults | Grow branches so leaves survive vanilla decay |
| `undo` | `u` | Both | `[amount=1]` | Undo pastes |
| `we` | | **Bukkit**; modded stub | — | Wand plus import of the WorldEdit selection |
| `studio` | | **Bukkit**; modded stub | `[dimension] [seed=1337]` | Object studio grid world |
| `convert` | | **Bukkit**; modded stub | — | Convert `convert/` folder `.schem` to `.iob` |

---

## Studio: `/iris studio` (`std`, `s`)

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `open` | `o` | Both | **Bukkit:** `<dimension> [seed=1337]` (`dimension` alias `dim`, `seed` alias `s`). **Modded:** `<pack> [seed]` | Open a temporary studio dimension; the owning player may replace an active Jigsaw Studio, and Iris waits for its autosave and active-operation barriers before closing it |
| `close` | `x` | Both | — | Close the studio and discard the world; Bukkit requires `/iris jigsaw close` for an active Jigsaw Studio |
| `tpstudio` | `stp` | Both | — | Teleport into the open studio |
| `status` | | **Modded** | — | Show the open studio and pack |
| `create` | `+` | Both | **Bukkit:** `[name=studio] [template]`. **Modded:** `[name] [template=example]` | Create a pack project |
| `pkg` | `package` | Both | **Bukkit:** `[dimension=default] [obfuscate=false] [minify=true]`. **Modded:** `[pack]` | Zip and package a pack. The Bukkit command name is `pkg`; `package` is the alias |
| `version` | | Both | **Bukkit:** `[dimension=default]`. **Modded:** `[pack]` | Pack version |
| `regions` | | Both | **Bukkit:** `[radius=500]`, player origin. **Modded:** `[radius]`, default 500 | Nearby region distribution |
| `noise` | `nmap` | Both | **Bukkit:** `[generator] [seed=12345]`. **Modded:** `[generator] [seed]` | Noise explorer GUI |
| `map` | `render` | Both | **Bukkit:** contextual `world`, required. **Modded:** — | Vision map GUI |
| `vscode` | `vsc` | Both | **Bukkit:** `[dimension=default]`. **Modded:** `[pack]` | Generate and open the code workspace |
| `update` | | Both | same pack argument as `vscode` | Regenerate the workspace only |
| `importvanilla` | `importv`, `iv` | **Bukkit**; modded stub | `<dimension> [variants=3] [structures=true]` | Import vanilla trees/objects/structures into a pack |
| `scoreboard` | `board`, `sidebar`, `sb` | **Bukkit** | — | Toggle the studio debug scoreboard |
| `loot` | | **Bukkit**; modded stub | `[fast=false] [add=true]` | Simulate chest loot in a GUI |
| `profile` | | **Bukkit**; modded stub | `[dimension=default]` | Pack performance profile |
| `spawn` | `summon` | **Bukkit**; modded stub | `<entity> <location>` (`location` is contextual) | Spawn an Iris entity |
| `objects` | `find-objects` | **Bukkit**; modded stub | — | IGenData chunk report for nearby chunks |

See `10 - Studio & VSCode Schemas.md`.

---

## Jigsaw: `/iris jigsaw` (`jig`, `jgs`)

**Bukkit-only; player origin.** This opens a transient Jigsaw Studio through the same single active Studio lifecycle. Saved Iris jigsaw resources run through the shared core on every platform, but Fabric/Forge/NeoForge do not register this authoring command tree.

| Command | Params | Description |
|---|---|---|
| `create` | `<dimension> <key> [mode=planar] [compatibility=iris] [width=15] [height=15] [depth=15] [seed=1337]` | Add-only atomic graph creation followed by open; `key` aliases `structure` and `name`; `mode` completes `planar`/`spatial`, compatibility completes `iris`/`vanilla`; planar X/Z `3..128`, spatial X/Z `1..128`, Y `1..192`, volume `<=2,097,152` |
| `convert` | `<dimension> <source> [target=auto] [seed=1337]` | Add-only conversion of one live registered vanilla/datapack jigsaw into an owned Iris graph, then open it; aliases `import`, `import-vanilla` |
| `adopt inspect` | `<dimension> <source> [target=auto] [strategy=auto]` | Asynchronously inspect an existing Iris closure and issue a hash-pinned `IN_PLACE`, `CLONE_REQUIRED`, or `BLOCKED` plan; strategy completes `auto`, `in-place`, `clone` |
| `adopt apply` | `<planId>` | Revalidate and atomically apply that player's unexpired plan, then open the target at seed `1337`; an active or opening Jigsaw Studio is rejected |
| `open` | `<dimension> <key> [seed=1337]` | Open an existing graph in compact workcells; aliases `edit`, `reopen`; existing Iris structure keys tab-complete; owner, autosave, and operation barriers protect replacement |
| `close` | `[discard=false]` | Close Studio; refuse active autosave/load/graph work or a pending dirty capture unless deliberately discarded |
| `status` | — | Show project/workcell state and the current automatic seed-`1337` evaluation, theme, piece count, and diagnostic |
| `menu` | — | Open the six-row controls also opened by the generated chest or three sneaks within 1.5 seconds |
| `select` | — | Select the workcell containing the player |
| `goto` | `<bay>` | Select and teleport above a stable workcell ID; alias `teleport` |
| `particles` | `<visible>` | Toggle player-local workcell-bound, connector, and temporary assembly-preview particle trails |
| `save` | `[bay=selected]` | Flush the selected dirty workcell's automatic capture now; normal block and container updates already autosave |
| `connector channel` | `<channel\|none>` | Look at a saved marker in the active owned workcell within 8 blocks and set/clear its Iris-only channel at the inverse-mapped source position |
| `bounds` | `<width> <height> <depth>` | Set the selected workcell capacity without resizing any variant object; every existing variant must fit, and the compact Studio layout regenerates in place; aliases `cell`, `resize` |
| `workcell capacity` | `<width> <height> <depth>` | Explicit nested form of `bounds` (group alias `cell`); planar capacities are per canonical archetype and spatial capacity is the shared envelope for its one-row variant cells |
| `workcell label` | `<displayName>` | Set the selected planar or spatial workcell's author label; quote spaces; solver identity remains canonical |
| `workcell label-reset` | — | Reset the selected workcell to its canonical solver label; alias `reset-label` |
| `pool create` | `<poolKey> [fallbackPoolKey=none]` | Atomically create an empty owned pool, optionally using an existing owned direct fallback |
| `piece create` | `<poolKey> <pieceKey> [weight=1]` | Create and load an owned variant; planar derives connectors from the contextual canonical workcell |
| `piece add` | `<poolKey> <pieceKey> [weight=1]` | Re-add and load an existing piece/object already owned by this project |
| `piece remove` | `<poolKey>` | Remove the active variant from a pool without deleting its owned resources |
| `piece rotatable` | `<rotatable>` | Persist cardinal rotation for the active variant; portable sessions reject `false` |
| `piece expand` | — | Resize the active planar or spatial owned variant exactly to workcell capacity; planar canonical sockets move to the new faces |
| `variant weight` | `<poolKey> <weight>` | Set the active variant's positive weight in an owned pool |
| `variant resize` | `<width> <height> <depth>` | Resize only the active owned variant within its workcell capacity; safe shrink rejects cropped content and the active cell reloads in place |
| `variant label` | `<displayName>` | Set the active variant's author label; quote spaces |
| `variant label-reset` | — | Reset the active variant to its resource-key fallback; alias `reset-label` |
| `variant duplicate` | — | Copy the active variant's object, metadata, and exact pool memberships into one new variant in this workcell |
| `variant duplicate-family` | `[themeKey=next]` | Atomically clone every enabled workcell's active owned variant into one coherent Iris family and load the whole family; alias `family` |
| `rules limits` | `<maxDepth> <maxSizeChunks>` | Set depth `1..30` and radius `1..32` (group alias `rule`); `VANILLA_PORTABLE` is restricted to `<=20` and `<=8` |
| `rules fallback` | `<poolKey> <fallbackPoolKey>` | Set or clear one owned pool's direct fallback after compiling the complete graph; pass `none` to clear. Unlike `pool create`, this argument is required |
| `preview goto` | — | Teleport above the permanent seed-`1337` block preview; alias `teleport` |
| `preview assemble` | `[seed=1337]` | Compute a deterministic read-only assembly at the player, report its complete piece count, and show in-range bounds as purple particle boxes for 10 seconds within the shared particle budget; places no blocks |
| `export` | `[namespace=iris] [output=jigsaw-export] [format=zip] [replace=false]` | Start a background strict Minecraft 26.2 vanilla datapack export as one direct artifact under the Studio packs `exports/` folder |
| `delete` | `[confirm=false]` | With `confirm=true`, scan reverse references, close Studio, and hash-pinned-delete the complete owned project; alias `remove` |

There is no Jigsaw Studio undo command, adoption rollback command, or mod-loader authoring command. **Undo Last Autosave** in Workcell Settings restores the newest of five previous saved graph iterations retained in one `.iris/jigsaw-history/key-<sha256>.json` file. **Reset Connector Blocks** restores the selected workcell's saved connector blocks without replacing its other edited blocks. Planar Studio always has six independently capacitated/enabled canonical workcells. Spatial Studio places every variant in a dedicated one-row cell; a new project seeds seven 15×15×15 variants with 0 through 6 cumulative face-center connectors. All cells retain one clear block of separation and use physical white-concrete edge cages with player-local particle trails inside them; Jigsaw Studio spawns no display entities for workcell bounds. Every variant retains its own exact dimensions and optional display label. Workcell and variant rename tools are renamed in an anvil, right-clicked to apply, and sneak-right-clicked to reset. A catalog may contain at most 512 variants. Natural creature spawning is disabled in the transient Studio world. The seed-`1337` assembly is evaluated automatically and rendered as a permanent protected block preview; spatial previews form an elevated connected assembly, while `preview assemble` remains the separate temporary arbitrary-seed particle diagnostic. See `21 - Jigsaw Structures.md` for GUI/toolbox controls, whole-assembly theme chances, independent pool-entry chances, rules/caps, markers, ownership, placement, export, and recovery.

Bukkit has one global Studio project/world and the Jigsaw session belongs to one owning player. Only that owner can control, load, or mutate it; entering a workcell makes that physical cell the owner's next menu selection. Non-owner edits are cancelled and non-owner commands use a strict informational/communication allowlist. Block and inventory changes in loaded owned workcells autosave after a 40-tick quiet period. Duplicate-one and duplicate-family actions queue once behind pending autosave, expedite it, and continue automatically against the same request and source variants. The chest and live preview are protected; schema-1 or otherwise stale toolbox sticks are rejected. A later mutation after capture starts remains dirty for another capture. Plugins that bypass covered events must call `JigsawStudioService.markDirty(...)` or `markAllDirty(...)`.

---

## Pack: `/iris pack` (`pk`)

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `validate` | `v` | **Bukkit:** `<pack>` (required despite the "leave empty for all" description). **Modded:** `[pack]`; empty means all | Validate pack(s) and publish results |
| `cleanup` | `c` | **Bukkit:** `<pack> [mode=preview]`. **Modded:** `<pack> [apply]` | Preview or quarantine unused resources |
| `restore` | `r` | same pattern as `cleanup` | Preview or restore the latest quarantine |
| `status` | `s` | **Bukkit:** `<pack>` (required, same blank-default trap). **Modded:** `[pack]` | Startup-published validation status, including persisted unchanged results |

See `25 - Pack Management.md`.

---

## Structure: `/iris structure` (`struct`, `str`)

Bukkit `dimension` parameters all carry the alias `dim`.

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `list` | `ls` | Both | **Bukkit:** `<dimension>`. **Modded:** current engine pack | Write `structure-index.json` |
| `info` | | Both | **Bukkit:** `<dimension> <structure>`. **Modded:** `<key>` | Resolve jigsaw graph bounds |
| `place` | `p` | Both | **Bukkit:** `<dimension> <structure>`, player origin. **Modded:** `<key>` | Assemble and place at the player; Bukkit reports the exact changed-block count and rejects air-only or already-identical no-op results |
| `import` | `import-all`, `reimport`, `imp`, `all` | **Bukkit**; modded stub | `<dimension>` | Import all vanilla/datapack structures as editable Iris resources (overwrites) |
| `capture` | `cap` | **Bukkit**; modded stub | `<dimension>` | Capture code-only structures via a scratch world |
| `verify` | `locateall` | Both | **Bukkit:** `<dimension> [radius=48]`, clamped to `1..1000`. **Modded:** `[key]` | Native/Iris structure reachability report |

See `18 - Structures Overview.md`, `21 - Jigsaw Structures.md`, `22 - Native Structures & Datapacks.md`.

---

## Datapack: `/iris datapack` (`datapacks`, `dp`)

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `ingest` | `pull` | **Bukkit**; modded stub | `[restart=false]` | Download and install Modrinth `datapackImports` into world datapacks |
| `list` | `ls` | Both | — | **Bukkit:** configured imports plus installed. **Modded:** configured/installed world datapacks |
| `remove` | `rm` | **Bukkit**; modded stub | `<id>` | Remove an installed datapack by id |
| `status` | | **Modded** | — | Check Iris dimension-type overrides against pack heights |
| `install` | | **Modded** | — | Install the dimension-type override datapack for loaded Iris dimensions |

See `22 - Native Structures & Datapacks.md`.

---

## World (modded-only group): `/iris world` (`w`)

Bukkit uses root `create` / `loadWorld` / `unloadWorld` / `remove` / `evacuate` instead. `[seed]` accepts a number or the literal `random`; blank means `1337`.

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `enable` | `create` | `<dimension> <pack\|pack:dimensionKey> [seed\|random]` | Create/inject a persistent Iris dimension (downloads the pack if missing) |
| `replace-overworld` | | `<pack\|pack:dimensionKey> [seed\|random]` | Inject primary world routing |
| `mainworld` | | `<pack\|pack:dimensionKey\|off> [seed\|random]` | Configure the main-world preset in `modded.json` |
| `disable` | | `<dimension>` | Evacuate and unload; keep disk data |
| `delete` | `remove`, `rm` | `<dimension>` | Disable and wipe chunk/mantle data |
| `list` | `ls` | — | List loaded Iris dimensions |
| `status` | | — | Loaded dimensions plus primary world config |

---

## Developer: `/iris Developer` (`dev`)

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `EngineStatus` | | **Bukkit** | — | Loaded tectonic plate count |
| `Sentry` | `sentry` (modded) | Both | — | Send a test exception to the error reporter |
| `genhash` | | **Bukkit** | `[radius=4] [centerX=0] [centerZ=0]`, contextual `world` | Hash generated blocks in a fixed area. The center parameters are `centerX`/`centerZ` here, not the hyphenated `goldenhash` names |
| `update-world` | `^world` | **Bukkit** | `[confirm=false] [fresh-download=false]`, contextual `world` and `pack` (`pack` alias `dimension`; `confirm` alias `c`; `fresh-download` aliases `fresh`, `new`) | Unsafe pack swap into a world |
| `mantle` | | **Bukkit** | `[plate=false] [name=21474836474]` | Dump a mantle section or plate under the dump folder |
| `packBenchmark` | | **Bukkit** | `[dimension=overworld] [radius=2048] [gui=false]` (`dimension` alias `pack`) | Pack benchmark |
| `upgrade` | | **Bukkit** | `[version=latest]` | Data version upgrade helper |
| `mca` | | **Bukkit** | `<world>` (a world folder path) | Scan MCA region files |
| `delete-chunk` | `dc` | **Bukkit** | `[radius=0]`, player origin | Delete nearby chunk blocks for regen testing |
| `network` | `ip` | Both | — | List network interfaces |
| `regen` | `rg` | **Bukkit** (modded root) | `[radius=5]`, player origin | Delete and regenerate nearby chunks |
| `goldenhash` | `gold` | **Bukkit** (modded root) | `[radius=8] [center-x=0] [center-z=0] [reset-mantle=true] [threads=8] [deep=false]`, contextual `world` | Buffer golden hash capture/verify |

The modded developer group implements only `sentry` and `network`/`ip`; its help section still advertises a region file scan that has no command node.

---

## Platform gap summary

| Feature | Bukkit | Modded |
|---------|--------|--------|
| Root permission node | `iris.all` (code-gated, whole tree) | `LEVEL_GAMEMASTERS` / `LEVEL_ALL` per node |
| World lifecycle | `create`, `loadWorld`, `unloadWorld`, `remove` | `world enable/disable/delete`, `create`, `mainworld` |
| Seed print | — | `/iris seed` |
| Object expand | — | `/iris object expand` |
| Object WE / studio / convert | yes | help stubs only |
| Studio loot/profile/spawn/objects/scoreboard/importvanilla | yes | stubs or messages |
| Jigsaw Studio create/edit/autosave/export commands and GUI | yes | no; copy a Bukkit-authored Iris pack |
| Structure import/capture | yes | messages (run on Bukkit, copy the pack) |
| Datapack Modrinth ingest/remove | yes | messages |
| Datapack status/install (dimension types) | — | yes |
| `regen` / `goldenhash` | under `Developer` | root |
| Pregen flags | `serial`, `gui`, center string | `sync`, `gui`, `nocache`, `at x z` |
| `pack validate` / `status` with no pack | rejected (blank default is required) | validates all packs |
| Tree feller permission | `iris.treefeller` | `irisworldgen:treefeller` via the loader permission API |

---

## Related

- `03 - Configuration.md`
- `02 - Getting Started.md`
- `06 - Worlds & Lifecycle.md`
- `07 - Pregeneration.md`
- `10 - Studio & VSCode Schemas.md`
- `21 - Jigsaw Structures.md`
- `25 - Pack Management.md`
- `28 - Integrations.md`
- `30 - Platform Differences.md`
- `32 - Determinism & Goldenhash.md`

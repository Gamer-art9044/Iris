# 04 - Commands & Permissions

Iris exposes one root command: `/iris` (aliases `/ir`, `/irs`). Bukkit uses VolmLib Director (named parameters, optional `key=value`). Fabric, Forge, and NeoForge register a Brigadier tree with the same root aliases. This is the complete command reference; platform gaps are marked **Bukkit-only** or **modded-only**. See `30 - Platform Differences.md` for a matrix and `03 - Configuration.md` for `/iris reload` targets.

## Syntax

### Bukkit (Director)

- Root: `/iris` / `/ir` / `/irs`.
- Subcommands and nested groups use method names (or `@Director(name=...)`) and aliases.
- Required parameters appear as positionals; optional parameters with defaults accept `name=value` (or short aliases from `@Param`).
- Help uses Director mini-menu: required shown as `<name>`, optional with default as `[name=default]`.
- Example: `/iris create myworld type=overworld seed=42 main=false`
- Example: `/iris pregen start 5000 world=world center=me gui=true serial=false`
- Contextual params (world, dimension, location) often resolve from the sender’s current world or look target when omitted.

### Modded (Brigadier)

- Same root and most names; arguments are ordered literals/arguments, not free-form `key=value`.
- `/iris` and `/iris help [section]` print the help browser (player paginated UI; console text list).
- Flags are literals where used (e.g. pregen `gui`, `sync`, `nocache`; download `force`).

## Permissions

### Bukkit

| Permission | Declared in `plugin.yml` / `paper-plugin.yml` | Default | Gate |
|------------|-----------------------------------------------|---------|------|
| `iris.all` | Declared in `plugin.yml` / `paper-plugin.yml` (`default: op`) | Operators receive it by default; otherwise grant explicitly | `CommandSVC` rejects every `/iris` execution without `iris.all` |
| `iris.treefeller` | Yes | `op` | Survival tree feller only (`TreeFellerSVC`); requires `treeFeller.enabled` in settings |

`iris.all` is code-gated as `ROOT_PERMISSION` in `CommandSVC` and declared on the plugin with `default: op`. Without it, the sender gets a permission-denied message and no subcommand runs.

Custom-biome restart warnings also notify online players who are op **or** hold `iris.all`.

### Modded

| Gate | Brigadier level | Applies to |
|------|-----------------|------------|
| Gamemaster | `Commands.LEVEL_GAMEMASTERS` | Mutating commands: create/world, studio, object tools, pregen, download, debug, reload, evacuate, seed, structure place, edit, developer, etc. |
| Read-only | `Commands.LEVEL_ALL` | `version`, `info`/`worlds` (seed field omitted unless gamemaster), `height`, `metrics`, `what` (relaxed at root), `help` |

Tree feller on mod loaders uses platform permission APIs (`irisworldgen:treefeller` on Fabric; PermissionAPI nodes on Forge/NeoForge), not Bukkit permission strings.

---

## Root: `/iris`

| Command | Aliases | Platforms | Params (Bukkit-style) | Description |
|---------|---------|-----------|------------------------|-------------|
| (empty) / help | | Both | `[section]` (modded) | Open help; modded supports section path |
| `version` | | Both | — | Print Iris/platform/Minecraft version and engine count |
| `info` | | **Modded** (see `worlds`) | `[dimension]` | List Iris dimensions and pack details; seed only for gamemasters |
| `create` | `c` | Both | **Bukkit:** `<name> [type=default] [seed=1337] [main=false]` (`type` aliases `dimension`,`pack`). **Modded:** `<name> [pack=overworld] [seed=1337]` | Create Iris world/dimension |
| `teleport` | `tp` | Both | **Bukkit:** `<world> [player=<name>]`. **Modded:** `<dimension> [player]` | Teleport self or named player into Iris world/dimension |
| `evacuate` | | Both | **Bukkit:** `<world>` (player origin). **Modded:** `[dimension]` | Move players out of Iris world to fallback/primary |
| `height` | | Both | — | Print world height (player on Bukkit) |
| `worlds` | `accesslist` | Both | — | **Bukkit:** access list of worlds. **Modded:** same as `info` (read-only); `accesslist` requires gamemaster |
| `remove` | `rm` | **Bukkit** | `<world> [delete=true]` | Remove managed Iris world; disk deletion defaults to true |
| `load` | `import` | **Bukkit** | `<world>` | Load managed Iris world |
| `unload` | | **Bukkit** | `<world>` | Unload Iris world |
| `debug` | | Both | — | Toggle `general.debug` and save settings |
| `download` | `dl` | Both | `<pack> [branch=stable] [overwrite=false]` (`overwrite` alias `force`) | Download pack project |
| `metrics` | `measure` | Both | — | Generation metrics (player / current Iris level) |
| `reload` | | Both | — | Reload `settings.json` and locale; modded also schedules forced datapack regeneration |
| `seed` | | **Modded** | — | Print world/engine seeds (gamemaster) |
| `regen` | `rg` | **Modded** root; Bukkit under `developer` | `[radius]` | Delete/regenerate nearby chunks |
| `goldenhash` | `gold` | **Modded** root; Bukkit under `developer` | `[radius] [threads] [capture\|verify]` | Deterministic buffer hashes |
| `wand` | | **Modded** root (+ object) | — | Give object wand |
| `dust` | `d` | **Modded** root (+ object) | — | Give reveal dust |
| `find` | `goto` | Both | see Find | Locate biome/region/object/structure/POI |
| `what` | | Both | see What | Inspect context |
| `edit` | | Both | see Edit | Open JSON in desktop editor |
| `pregen` | `pregenerate` | Both | see Pregen | Pregeneration control |
| `object` | `o` | Both | see Object | Object tools |
| `studio` | `std`, `s` | Both | see Studio | Studio / pack authoring |
| `pack` | `pk` | Both | see Pack | Validate/cleanup/restore/status |
| `structure` | `struct`, `str` | Both | see Structure | Structure index/import/place |
| `datapack` | `datapacks`, `dp` | Both | see Datapack | Datapack helpers |
| `developer` | `dev` | Both | see Developer | Diagnostics |
| `world` | `w` | **Modded** | see World | Runtime dimension enable/disable |

---

## Find: `/iris find` (`goto`)

**Origin:** player (Bukkit). **Modded:** gamemaster gate.

| Command | Params | Description |
|---------|--------|-------------|
| `biome` | **Bukkit:** `<biome> [teleport=true]`. **Modded:** `<key>` | Find Iris biome; teleport default true on Bukkit |
| `region` | **Bukkit:** `<region> [teleport=true]`. **Modded:** `<key>` | Find Iris region |
| `object` | **Bukkit:** `<object> [teleport=true]`. **Modded:** `<key>` | Find object placement (Bukkit may teleport to object studio first) |
| `structure` | **Bukkit:** `<structure>` (sync). **Modded:** `<key>` | Find vanilla/datapack/Iris structure |
| `poi` | **Bukkit:** `<type> [teleport=true]`. **Modded:** `<type>` | Find supported point of interest |
| `unregistered` | — | Print structures excluded from goto completion and rejection reasons to console |

---

## What: `/iris what`

**Bukkit origin:** player only. No bare `here` command on Bukkit.

| Command | Platforms | Params | Description |
|---------|-----------|--------|-------------|
| (empty) / `here` | **Modded** | — | Full inspect at player position |
| `biome` | Both | — | Current Iris biome |
| `region` | Both | — | Current Iris region |
| `block` | Both | — | Target block |
| `hand` | Both | — | Held item |
| `markers` | Both | `<marker>` | Reveal nearby markers (e.g. `cave_floor`, `cave_ceiling`, `object`) |

---

## Edit: `/iris edit`

**Bukkit origin:** player. Opens pack JSON in the desktop editor.

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `biome` | `b` | **Bukkit:** `<biome>`. **Modded:** `[key]` | Open biome JSON (modded: omit key for current) |
| `region` | `r` | **Bukkit:** `<region>`. **Modded:** `[key]` | Open region JSON |
| `dimension` | `d` | **Bukkit:** `<dimension>`. **Modded:** — | Open dimension JSON (modded: current pack) |

---

## Pregen: `/iris pregen` (`pregenerate`)

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `start` | | **Bukkit:** `<radius> [world=<world>] [center=0,0] [gui=true] [serial=false]` (`radius` alias `size`, `center` alias `middle`, use `me` for player). **Modded:** `<radius> [dimension] [at <x> <z>] [gui] [sync] [nocache]` | Start pregen; radius in **blocks**; resumable checkpoint cache on by default on modded unless `nocache` |
| `stop` | `x` | — | Stop active pregen |
| `pause` | `resume` | — | Toggle pause/resume |
| `status` | — | — | Progress, CPS, ETA, method, failures |

**Bukkit:** `serial=true` requires a Paper-compatible server (strict serial chunk generation). **Modded:** `sync` is the serial-like flag; `gui` opens boss-bar/GUI path when available.

See `07 - Pregeneration.md`.

---

## Object: `/iris object` (`o`)

**Bukkit:** group origin player. Root `wand`/`dust` also exist on modded.

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `wand` | | Both | — | Give Iris object wand |
| `dust` | `d` | Both | — | Give reveal dust |
| `save` | | Both | **Bukkit:** `[dimension=<key>] <name> [overwrite=false] [legacy=true]`. **Modded:** `[overwrite] <name>` | Save wand selection as `.iob` |
| `paste` | | Both | **Bukkit:** `<object> [edit=false] [rotate=0] [scale=1]`. **Modded:** `[at x y z] [rotate degrees] <key>` | Paste object |
| `expand` | | **Modded** | `[amount]` (default `1`) | Expand selection along look |
| `contract` | `-` | Both | `[amount=1]` | Contract selection along look |
| `shift` | | Both | `[amount=1]` | Shift selection along look |
| `position1` | `p1` | Both | **Bukkit:** `[here=true]` (look vs feet). **Modded:** `[look]` | Set selection point 1 |
| `position2` | `p2` | Both | same | Set selection point 2 |
| `x+y` | `xpy` (modded) | Both | — | Autoselect up and out |
| `x&y` | `xay` (modded) | Both | — | Autoselect up, down, and out |
| `analyze` | | Both | `<object\|key>` | Composition stats |
| `shrink` | | Both | `<object\|key>` | Shrink object to minimum bounds |
| `plausibilize` | | Both | **Bukkit:** `<key\|prefix/> [dryrun=false] [reach=12]`. **Modded:** greedy args `key [dryrun=true] [reach=N]` | Grow branches so leaves survive vanilla decay |
| `undo` | `u` | Both | `[amount=1]` | Undo pastes |
| `we` | | **Bukkit**; modded stub | — | Wand + import WorldEdit selection |
| `studio` | | **Bukkit**; modded stub | `[dimension=null] [seed=1337]` | Object studio grid world |
| `convert` | | **Bukkit**; modded stub | — | Convert `convert/` folder `.schem` → `.iob` |

---

## Studio: `/iris studio` (`std`, `s`)

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `open` | `o` | Both | **Bukkit:** `<dimension> [seed=1337]`. **Modded:** `<pack> [seed]` | Open temporary studio dimension |
| `close` | `x` | Both | — | Close studio and discard world |
| `tpstudio` | `stp` | Both | — | Teleport into open studio |
| `status` | | **Modded** (Bukkit uses other paths) | — | Show open studio and pack |
| `create` | `+` | Both | **Bukkit:** `[name=studio] [template=<dimension>]`. **Modded:** `[name] [template=example]` | Create pack project |
| `package` | `pkg` (Bukkit method `pkg`, alias `package`) | Both | **Bukkit:** `[dimension=default] [obfuscate=false] [minify=true]`. **Modded:** `[pack]` | Zip/package pack |
| `version` | | Both | **Bukkit:** `[dimension=default]`. **Modded:** `[pack]` | Pack version |
| `regions` | | Both | **Bukkit:** `[radius=500]` (player). **Modded:** `[radius]` default 500 | Nearby region distribution |
| `noise` | `nmap` | Both | **Bukkit:** `[generator=<key>] [seed=12345]`. **Modded:** `[generator] [seed]` | Noise explorer GUI |
| `map` | `render` | Both | **Bukkit:** `[world=<world>]`. **Modded:** — | Vision map GUI |
| `vscode` | `vsc` | Both | **Bukkit:** `[dimension=default]`. **Modded:** `[pack]` | Generate/open code workspace |
| `update` | | Both | same as vscode pack | Regenerate workspace only |
| `importvanilla` | `importv`, `iv` | **Bukkit** functional; **modded message** | `<dimension> [variants=3] [structures=true]` | Import vanilla trees/objects/structures into pack |
| `scoreboard` | `board`, `sidebar`, `sb` | **Bukkit** | — | Toggle studio debug scoreboard |
| `loot` | | **Bukkit**; modded stub | `[fast=false] [add=true]` | Simulate chest loot GUI |
| `profile` | | **Bukkit**; modded stub | `[dimension=default]` | Pack performance profile |
| `spawn` | `summon` | **Bukkit**; modded stub | `<entity> [location=<x,y,z>]` | Spawn Iris entity |
| `objects` | `find-objects` | **Bukkit**; modded stub | — | IGenData chunk report for nearby chunks |

See `10 - Studio & VSCode Schemas.md`.

---

## Pack: `/iris pack` (`pk`)

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `validate` | `v` | **Bukkit:** `[pack=<key>]`. **Modded:** `[pack]`; empty = all | Validate pack(s) and publish results |
| `cleanup` | `c` | **Bukkit:** `<pack> [mode=preview]`. **Modded:** `<pack> [apply]` | Preview/quarantine unused resources |
| `restore` | `r` | same pattern | Preview/restore latest quarantine |
| `status` | `s` | **Bukkit:** `[pack=<key>]`. **Modded:** `[pack]` | Cached validation status |

See `25 - Pack Management.md`.

---

## Structure: `/iris structure` (`struct`, `str`)

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `list` | `ls` | Both | **Bukkit:** `<dimension>`. **Modded:** current engine pack | Write `structure-index.json` |
| `info` | | Both | **Bukkit:** `<dimension> <structure>`. **Modded:** `<key>` | Resolve jigsaw graph bounds |
| `place` | `p` | Both | **Bukkit:** `<dimension> <structure>` (player). **Modded:** `<key>` | Assemble and place structure at player |
| `import` | `import-all`, `reimport`, `imp`, `all` | **Bukkit**; modded message | `<dimension>` | Import all vanilla/datapack structures as editable Iris resources (overwrites) |
| `capture` | `cap` | **Bukkit**; modded message | `<dimension>` | Capture code-only structures via scratch world |
| `verify` | `locateall` | Both | **Bukkit:** `<dimension> [radius=48]`. **Modded:** `[key]` | Native/Iris structure reachability report |

See `18 - Structures Overview.md`, `21 - Jigsaw Structures.md`, `22 - Native Structures & Datapacks.md`.

---

## Datapack: `/iris datapack` (`datapacks`, `dp`)

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `ingest` | `pull` | **Bukkit**; modded message | `[restart=false]` | Download/install Modrinth `datapackImports` into world datapacks |
| `list` | `ls` | Both | — | **Bukkit:** configured imports + installed. **Modded:** configured/installed world datapacks |
| `remove` | `rm` | **Bukkit**; modded message | `<id>` | Remove installed datapack by id |
| `status` | | **Modded** | — | Check Iris dimension-type overrides vs pack heights |
| `install` | | **Modded** | — | Install dimension-type override datapack for loaded Iris dimensions |

See `22 - Native Structures & Datapacks.md`.

---

## World (modded-only group): `/iris world` (`w`)

Bukkit uses root `create` / `load` / `unload` / `remove` / `evacuate` instead.

| Command | Aliases | Params | Description |
|---------|---------|--------|-------------|
| `enable` | `create` | `<dimension> <pack\|pack:dimensionKey> [seed\|random]` | Create/inject persistent Iris dimension (downloads pack if missing) |
| `replace-overworld` | | `<pack\|pack:dimensionKey> [seed\|random]` | Inject primary world routing |
| `mainworld` | | `<pack\|pack:dimensionKey\|off> [seed\|random]` | Configure main-world preset in `modded.json` |
| `disable` | | `<dimension>` | Evacuate and unload; keep disk data |
| `delete` | `remove`, `rm` | `<dimension>` | Disable and wipe chunk/mantle data |
| `list` | `ls` | — | List loaded Iris dimensions |
| `status` | | — | Loaded dimensions + primary world config |

---

## Developer: `/iris developer` (`dev`)

| Command | Aliases | Platforms | Params | Description |
|---------|---------|-----------|--------|-------------|
| `EngineStatus` | | **Bukkit** | — | Loaded tectonic plate count |
| `Sentry` | `sentry` (modded) | Both | — | Send test exception to error reporter |
| `genhash` | | **Bukkit** | `[world] [radius=4] [center-x=0] [center-z=0]` | Hash generated blocks in fixed area |
| `update-world` | `^world` | **Bukkit** | `[world=<world>] [pack=<dimension>] [confirm=false] [fresh-download=false]` | Unsafe pack swap into world |
| `mantle` | | **Bukkit** | `[plate=false] [name=…]` | Dump mantle section/plate under dump folder |
| `packBenchmark` | | **Bukkit** | `[pack=overworld] [radius=2048] [gui=false]` | Pack benchmark |
| `upgrade` | | **Bukkit** | `[version=latest]` | Data version upgrade helper |
| `mca` | | **Bukkit** | `<world folder>` | Scan MCA region files |
| `delete-chunk` | `dc` | **Bukkit** | `[radius=0]` | Delete nearby chunk blocks (regen testing) |
| `network` | `ip` | Both | — | List network interfaces |
| `regen` | `rg` | **Bukkit** (modded root) | `[radius=5]` | Delete and regenerate nearby chunks |
| `goldenhash` | `gold` | **Bukkit** (modded root) | `[world] [radius=8] [center-x=0] [center-z=0] [reset-mantle=true] [threads=8] [deep=false]` | Buffer golden hash capture/verify |

Modded developer group currently implements only `sentry` and `network`/`ip`.

---

## Platform gap summary

| Feature | Bukkit | Modded |
|---------|--------|--------|
| Root permission node | `iris.all` (code) | Gamemaster / all levels |
| World lifecycle | `create`, `load`, `unload`, `remove` | `world enable/disable/delete`, `create`, `mainworld` |
| Seed print | — | `/iris seed` |
| Object expand | — | `/iris object expand` |
| Object WE / studio / convert | yes | help stubs only |
| Studio loot/profile/spawn/objects/scoreboard/importvanilla | yes | stubs or messages |
| Structure import/capture | yes | messages (run on Bukkit, copy pack) |
| Datapack Modrinth ingest/remove | yes | messages |
| Datapack status/install (dimension types) | — | yes |
| `regen` / `goldenhash` | under `developer` | root |
| Pregen flags | `serial`, `gui`, center string | `sync`, `gui`, `nocache`, `at x z` |
| Tree feller permission | `iris.treefeller` | loader-specific node |

---

## Related

- `03 - Configuration.md`
- `02 - Getting Started.md`
- `06 - Worlds & Lifecycle.md`
- `07 - Pregeneration.md`
- `10 - Studio & VSCode Schemas.md`
- `25 - Pack Management.md`
- `28 - Integrations.md`
- `30 - Platform Differences.md`
- `32 - Determinism & Goldenhash.md`

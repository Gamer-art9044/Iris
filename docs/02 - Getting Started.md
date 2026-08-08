# 02 - Getting Started

This page walks through creating an Iris world, teleporting into it, running a short pregeneration, and opening a studio pack workspace. Command argument style differs by platform: Bukkit uses Director keyed optional parameters; modded uses Brigadier positional arguments and flag literals.

Full command trees and permissions: `04 - Commands & Permissions.md`. World lifecycle detail: `06 - Worlds & Lifecycle.md`. Studio detail: `10 - Studio & VSCode Schemas.md`.

## Prerequisites

- Iris installed per `01 - Installation & Platforms.md`
- Java 25 server or mod instance running
- Operator / gamemaster access (`iris` commands; modded mutating commands require permission level 2 / gamemasters)
- Default pack present (auto-downloaded on first boot) or an installed pack under the platform packs directory

## Argument style

| Platform | Required args | Optional args | Example |
|---|---|---|---|
| Plugin (Bukkit) | Positional in declaration order | Must be `key=value` | `/iris create myworld type=overworld seed=1337` |
| Mod (Fabric / Forge / NeoForge) | Positional | Further positional tokens or literal flags | `/iris create myworld overworld 1337` |

On Bukkit, a bare extra token that is not a known key is a hard error. On modded, pregen flags are combinable literals (`gui`, `sync`, `nocache`) after the radius / dimension / center.

## 1. Create a world

### Plugin

```
/iris create <name> [type=…] [seed=…] [main=true|false]
```

| Parameter | Aliases | Default | Meaning |
|---|---|---|---|
| `name` | `world-name` | (required) | World name |
| `type` | `dimension`, `pack` | `default` → `generator.defaultWorldType` (`overworld`) | Pack/dimension load key |
| `seed` | — | `1337` | World seed |
| `main` | `main-world` | `false` | If true, register a shutdown hook to promote this world as `level-name` in `server.properties` |

Aliases for the create command itself: `c`.

**Reserved names (plugin):** `iris` and `benchmark` are rejected (case-insensitive). Iris suggests using another name (for example `irisworld`).

**Already exists:** if the managed dimension root already exists, create aborts.

**Folia:** runtime create is disabled. Iris stages world files, installs the pack snapshot, registers `bukkit.yml`, and tells you to **restart** the server. After restart the world can load. See `01 - Installation & Platforms.md`.

**Non-Folia:** create builds the world immediately via `IrisToolbelt.createWorld()` (production, not studio).

```
/iris create myworld type=overworld seed=1337
```

### Mod

```
/iris create <name> [pack] [seed]
```

| Parameter | Default | Meaning |
|---|---|---|
| `name` | (required) | Dimension id fragment; normalized under namespace `irisworldgen` when not fully qualified |
| `pack` | `overworld` | Pack key (optional `pack:dimension` form when the pack’s dimension key differs) |
| `seed` | `1337` | Long seed |

Aliases: `c`. Equivalent world management lives under `/iris world create|enable` with the same enable path.

If the pack is not installed, create starts an async download of `IrisDimensions/<pack>` then injects the dimension. On success the dimension is live and re-injected on later startups.

```
/iris create myworld overworld 1337
```

There is no separate “load” step on modded after a successful create.

## 2. Load a world (plugin only)

```
/iris load <world>
```

Aliases: `import`. Requires an existing managed dimension directory on disk. Origin: player (Director `PLAYER`). Loads through `BukkitWorldReconciler` and registers the world with the server.

Modded worlds created with `/iris create` or `/iris world enable` are already injected; use teleport instead of load.

## 3. Teleport

### Plugin

```
/iris teleport <world> [player=…]
```

Aliases: `tp`. Teleports the target (or the executing player) to the world spawn asynchronously when possible.

```
/iris tp myworld
```

### Mod

```
/iris teleport <dimension> [player]
/iris tp <dimension> [player]
```

Dimension is a loaded level argument (tab-completes Iris dimensions). Console must name a player. Teleport target is a fixed spawn-like position in the Iris dimension (engine-managed placement).

```
/iris tp irisworldgen:myworld
```

## 4. Pregenerate

Radius is in **blocks**. One pregeneration job runs server-wide.

### Plugin

```
/iris pregen start <radius> [world=…] [center=x,z|me] [gui=true|false] [serial=true|false]
```

| Parameter | Default | Notes |
|---|---|---|
| `radius` | (required) | Blocks; must be > 0 |
| `world` | contextual (sender’s world) | Target world |
| `center` | `0,0` | Or `me` for player position; aliases `middle` |
| `gui` | `true` | Open pregen GUI when available |
| `serial` | `false` | One chunk at a time; requires Paper-compatible server |

Control:

```
/iris pregen stop
/iris pregen pause
/iris pregen status
```

Example:

```
/iris pregen start 352 world=myworld center=0,0 gui=false
```

### Mod

```
/iris pregen start <radius> [dimension] [at <x> <z>] [gui] [sync] [nocache]
```

| Piece | Meaning |
|---|---|
| `radius` | 1–100000 blocks |
| `dimension` | Optional level; defaults to current dimension |
| `at x z` | Optional center (default 0, 0) |
| `gui` | Request progress map window on the server display when GUI is launchable |
| `sync` | Synchronous chunk writes |
| `nocache` | Disable resumable checkpoint cache (default is cached / resumable) |

Flags are optional and combinable in any order after the radius/dimension/center prefix.

```
/iris pregen start 352 irisworldgen:myworld at 0 0 sync
```

Control: `/iris pregen stop`, `pause` / `resume`, `status`. Progress: client mod HUD when present, otherwise boss bar / console.

## 5. Studio (first authoring steps)

Studio worlds are transient: closed on command, purged at startup. They read the **live** pack and hotload JSON/object edits into newly generated chunks. Production worlds do not (see pitfalls below).

### Plugin

```
/iris studio create [name=studio] [template=…]
/iris studio open <dimension> [seed=1337]
/iris studio vscode [dimension=default]
/iris studio close
```

| Command | Aliases | Notes |
|---|---|---|
| `create` | `+` | Omitting template scaffolds a **starter** pack (minimal dimension/region/biome/generator). Providing a template copies an existing packs entry (or downloads it) |
| `open` | `o` | Temporary studio world for the pack |
| `vscode` | `vsc` | Write / open a `.code-workspace` with live registry schemas |
| `close` | `x` | Discard studio world |

Default create name is `studio`; if that folder already exists, Iris picks the next free name.

### Mod

```
/iris studio create [name] [template]
/iris studio open <pack> [seed]
/iris studio vscode [pack]
/iris studio update [pack]
/iris studio close
```

| Command | Notes |
|---|---|
| `create` / `+` | Defaults: name `studio`, template **`example`** (differs from Bukkit starter-pack path when template omitted) |
| `open` / `o` | Pack required; seed default `1337` |
| `vscode` / `vsc` | Generate workspace |
| `update` | Regenerate schemas only |
| `close` / `x` | Discard studio |

Some Bukkit studio tools (importvanilla feature capture, loot GUI, profile, etc.) refuse or redirect on modded with an explicit message; capture vanilla features on Bukkit and copy the pack folder if needed.

```
/iris studio open overworld
/iris studio vscode dimension=overworld
```

## Suggested first-session flow

1. Confirm pack: ensure `overworld` (or your pack) exists under the platform packs directory.
2. Create world (plugin or mod forms above).
3. On Folia plugin: restart after staging, then load if needed.
4. Teleport into the world.
5. Optional: `/iris pregen start 352 …` for a small square (~704×704 blocks).
6. Optional: `/iris studio open <pack>` to edit live; use VSCode schemas for autocomplete of blocks/items/entities (mod content included on mod loaders).

## Common pitfalls

| Pitfall | What happens | What to do |
|---|---|---|
| World name `iris` or `benchmark` (plugin) | Create rejected | Use another name |
| Editing `packs/<pack>` after production create | **No effect** on existing worlds | Production engines read `<world>/iris/pack` snapshot. Push with `/iris developer update-world world=<world> pack=<dimension> confirm=true` (Bukkit, all keyed) and restart; or only new chunks after update. Studio reads live packs |
| Expecting pack edits in old chunks | Only new chunks use new config | Fly to unexplored terrain, pregen fresh radius, or use studio |
| Folia: create then teleport immediately | World not live yet | Restart after staging message, then load/teleport |
| Mod: new pack heights/biomes missing | Forced datapack not yet applied | Restart after installing pack |
| `/iris load` on modded | No equivalent subcommand | Use create/enable + teleport |
| Bukkit optional args without `key=` | Parse error | Use `seed=1337`, not a bare second number for optional params |
| Mod pregen while another job runs | Start fails | `/iris pregen stop` then start again |
| Studio closed mid-edit | World discarded | Edits on disk in `packs/` remain; reopen studio |
| Default pack download blocked | Create/open fails missing pack | Allow HTTPS or `/iris download overworld` offline install of a pack tree |
| `type=default` vs pack key | Resolves via `generator.defaultWorldType` | Prefer explicit `type=overworld` or your pack key |

## Quick reference

**Plugin**

```
/iris create myworld type=overworld seed=1337
/iris tp myworld
/iris pregen start 352 world=myworld center=0,0 gui=false
/iris studio open overworld seed=1337
/iris studio close
```

**Mod**

```
/iris create myworld overworld 1337
/iris tp irisworldgen:myworld
/iris pregen start 352 irisworldgen:myworld at 0 0
/iris studio open overworld 1337
/iris studio close
```

Next: pack structure in `05 - Concepts & Pack Layout.md`, configuration in `03 - Configuration.md`.

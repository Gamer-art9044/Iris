# 09 - PlaceholderAPI

Iris registers a PlaceholderAPI expansion with id `iris` on Bukkit-family servers, publishing sixteen read-only values: one service flag, six terrain readings scoped to a player's position, and nine global pregeneration readings. It exists so scoreboard, chat, and HUD plugins can show Iris state without writing Java. Plugins that need the same data with real types and no string parsing should use `90 - API - Getting Started.md`, `91 - API - Terrain.md`, and `92 - API - World Events.md` instead. There is no PlaceholderAPI on Fabric/Forge/NeoForge; see `07 - Pregeneration.md` and `28 - Integrations.md` for the surrounding runtime.

## Put an Iris value on a scoreboard

Work outward: prove the placeholder resolves in PlaceholderAPI itself before you touch the plugin that will display it. Half of all "the placeholder is broken" reports are a formatting mistake in the consumer.

Prerequisites: Bukkit-family Iris, PlaceholderAPI installed *before* Iris starts, a full server restart, and a player standing in a loaded Iris world.

1. `/papi info iris` — the expansion must be listed with author `Volmit Software` and version `2.0.0`, along with all sixteen paths. If it is not listed at all, skip to the recovery table; nothing else will work.
2. `/papi parse me %iris_available%` — expect `true`. This only means Iris registered its terrain service, not that you are in an Iris world.
3. `/papi parse me %iris_world.available%` — expect `true`. This is the guard you will use in the board template.
4. `/papi parse me %iris_world.biome-key%` — expect a load key such as `desert/hot-dunes`. A `---` here means Iris has no reading for you yet; see the recovery table.
5. Start a job to test the pregen family: `/iris pregen start 352 center=0,0 gui=false`, then `/papi parse me %iris_pregen.percent%`. Expect a bare number between `0.00` and `100.00` with no percent sign.
6. `/iris pregen stop`, then `/papi parse me %iris_pregen.available%`. Expect `false`, and every other `pregen.*` key to read `---`.
7. Now paste the exact string you verified into the consumer plugin, wrap it in whatever guard that plugin offers, and reload it.

Success is the board showing the same text `/papi parse` showed. If step 7 disagrees with step 4, the bug is in the consumer's template or refresh interval, not in Iris.

### Recovery

| Symptom | What actually happened | Fix |
|---|---|---|
| `/papi info iris` reports no such expansion | PlaceholderAPI was not enabled at the moment Iris ran its registration task, one tick after enable | Full server restart with both plugins present. There is no late retry on `PluginEnableEvent`, and `/papi reload` will not make Iris try again |
| The placeholder renders literally as `%iris_...%` | The path is not one Iris publishes. Unknown paths return null to PlaceholderAPI, which leaves the text alone | Copy an exact path from `/papi info iris` or the table below. Pre-2.0 underscore names are gone |
| A `world.*` key reads `---` | No player context (console or offline player), the player has no tracked position yet, the player is not in an Iris world, or the terrain service returned nothing for that column | Parse as a named online player who is standing in a loaded Iris world |
| `world.available` is `true` but the biome lags your movement | The per-player world view has a one-second TTL | Wait a second, or teleport — teleports publish immediately. Do not go looking for a bug in the consumer first |
| A `pregen.*` key reads `---` | No job snapshot is currently published | Guard the board template on `pregen.available` rather than testing the value keys for `---` |
| The board shows `47.5%%` or `47.5` with no sign | `pregen.percent` deliberately returns a bare number | Put the literal `%` in the consumer's format string |

## Registration

| Item | Value |
|---|---|
| Expansion id | `iris` |
| Version | `2.0.0` |
| Author | `Volmit Software` |
| Required plugin | `Iris` |
| Declared in `plugin.yml` | `softdepend: PlaceholderAPI` |
| `persist()` | `true` — the expansion survives `/papi reload` without restarting Iris |

Iris checks `isPluginEnabled("PlaceholderAPI")` inside a task scheduled just after its own enable, and gives up silently if the answer is no. Registration also installs a listener; if that listener fails to attach, Iris unregisters the expansion again and logs a warning, so you never end up with an expansion publishing stale positions.

Soft-depend only affects load order. It does not install or load PlaceholderAPI.

## Value grammar

| Rule | Detail |
|---|---|
| Path form | Dot-separated, lowercase `a-z`, `0-9`, and `-`. The path is lowercased before lookup, so `%iris_WORLD.BIOME%` resolves, but write it lowercase |
| Plain text only | No color codes, no unit suffixes, no `%` character in any value, `.` as the decimal separator, no thousands separators |
| Pack-name scrubbing | Section-sign sequences and `%` characters inside pack-authored names are removed before the value is returned, so a mischievous biome name cannot inject formatting into a scoreboard |
| Genuine zero | Returned as `0`, or `0.00` for two-decimal values. Never `---` |

Every key answers in one of three ways:

| Answer | When | What the board shows |
|---|---|---|
| The value | Known path, data available | The value |
| `---` | Known path, nothing to report right now | `---` |
| Null | Unknown path | The literal `%iris_...%` |

Unknown paths stay visible on purpose. There is no blanket empty-string fallback that would hide a typo.

## Key reference

### World family

Everything except `%iris_available%` needs an online player with a tracked position.

| Placeholder | What it reports |
|---|---|
| `%iris_available%` | `true` when Iris has registered its terrain service on this server. Works from the console. Says nothing about the player's world |
| `%iris_world.available%` | `true` when the reading player's tracked position is in a world Iris generates. The guard for every other `world.*` key |
| `%iris_world.biome%` | Display name of the surface biome at the player's X/Z column, for example `Hot Desert Dunes` |
| `%iris_world.biome-key%` | Load key of that same biome, for example `desert/hot-dunes`. This is what a pack file is named after |
| `%iris_world.region%` | Display name of the region covering the player's X/Z |
| `%iris_world.region-key%` | Load key of that region |
| `%iris_world.dimension%` | Load key of the dimension the player's world generates from, for example `overworld`. This is the dimension file's key, which is usually but not necessarily the pack folder name |

From the console, for an offline player, or before a player's first tracked position: `world.available` is `false` and the rest are `---`.

### Pregeneration family

One job runs per server, so these are global. Every player and the console see identical values.

| Placeholder | What it reports |
|---|---|
| `%iris_pregen.available%` | `true` while a job snapshot is published. Use this as the guard |
| `%iris_pregen.world%` | Name of the world being pregenerated |
| `%iris_pregen.percent%` | Completion from `0.00` to `100.00`, two decimals, no `%` character |
| `%iris_pregen.eta%` | Whole seconds remaining |
| `%iris_pregen.eta-text%` | The same estimate formatted for humans: `45s` under a minute, `2m 5s` under an hour, `1h 30m` above it |
| `%iris_pregen.chunks%` | Chunks finished so far |
| `%iris_pregen.total%` | Chunks the job will generate in total |
| `%iris_pregen.chunks-per-second%` | Current generation rate, two decimals |
| `%iris_pregen.paused%` | `true` while the job is paused |

The snapshot is republished on the `STARTED`, `TICK`, `PAUSED`, `RESUMED`, and `SAVING` pregen phases, and cleared on `COMPLETED` and `CANCELLED`. After the clear, `pregen.available` is `false` and every value key is `---`. Before the job has run long enough to estimate, `eta` reads `0` and `eta-text` reads `0s`.

### Paths as `/papi info iris` prints them

```
available
pregen.available
pregen.chunks
pregen.chunks-per-second
pregen.eta
pregen.eta-text
pregen.paused
pregen.percent
pregen.total
pregen.world
world.available
world.biome
world.biome-key
world.dimension
world.region
world.region-key
```

Prefix each with `%iris_` and suffix with `%`.

## What "surface" means, and what a board costs

`world.biome`, `world.biome-key`, `world.region`, and `world.region-key` are **surface column** readings: whatever the generator places at ground level for that X/Z. Y is not part of the query. A player 60 blocks down in a cave still reads the surface biome overhead, not the cave biome. If you need the biome at an actual Y, that is a terrain API call, not a placeholder — see `91 - API - Terrain.md`.

### When a position is published

| Event | Timing |
|---|---|
| Walking (`PlayerMoveEvent`) | At most once per second, and skipped entirely while the player stays inside the same block column |
| Join, respawn, world change, portal, and every teleport — `/iris goto`, `/tp`, ender pearls, random-TP plugins | Immediately, bypassing the one-second interval |
| Quit | The player's tracked position and cached view are released |

Because teleports publish immediately, a player who arrives somewhere and stands still never reads a stale column from where they came from.

### View rebuild cost

A player's world view is rebuilt at most once per second (`VIEW_TTL_MS = 1000`), and only when something actually reads a `world.*` key. Three consequences worth knowing before you design a board:

- A board with six `world.*` keys costs one rebuild per player per second, not six.
- Values can trail a sprinting player by up to a second.
- A board nobody is reading costs nothing. Iris does not poll terrain in the background for this.

Pregen values are not polled per player either — they come from one global snapshot updated by `IrisPregenerationEvent`.

## Permissions

No placeholder is permission-gated. Anything sensitive is simply not published: there is no seed key, no file path key, and no key that mutates engine state on read.

## Failure policy

| Situation | Result |
|---|---|
| Unknown path | Null to PlaceholderAPI, so the literal `%iris_...%` stays on screen |
| Known path, no data | `---` |
| No player context on a `world.*` key | `---`, and `world.available` is `false` |
| Player outside an Iris world | `---`, and `world.available` is `false` |
| Terrain service not registered | `---`, `world.available` is `false`, `%iris_available%` is `false` |
| No pregen job | `---`, and `pregen.available` is `false` |
| A resolver throws | `---`, plus one logged warning for that path. Logging stops after 64 distinct paths have warned |

A key that threw is not quarantined. It keeps being called and keeps answering `---` until whatever was wrong resolves itself.

## Migration from pre-2.0 keys

The old underscore keys are gone with no aliases and no dual-accept window. They now render literally, which is deliberate — a silently empty scoreboard line is worse than a visibly broken one.

| Old key | New key | Why |
|---|---|---|
| `%iris_biome_name%` | `%iris_world.biome%` | Dot grammar |
| `%iris_biome_id%` | `%iris_world.biome-key%` | `id` was always the load key; the name now says so |
| `%iris_region_name%` | `%iris_world.region%` | Dot grammar |
| `%iris_region_id%` | `%iris_world.region-key%` | Same as `biome_id` |
| `%iris_biome_file%` | removed | Leaked absolute server paths, and threw whenever the biome had no backing file |
| `%iris_region_file%` | removed | Same problem |
| `%iris_world_seed%` | removed | A scoreboard has no permission context. Read `IrisWorldInfo.seed()` from the terrain API if a plugin genuinely needs it |
| `%iris_terrain_height%` | removed | Reported generated height before objects and player edits, so it regularly disagreed with the block under the player's feet |
| `%iris_terrain_slope%` | removed | A pack-authoring diagnostic, far too expensive to run once per player per board refresh |
| `%iris_world_mode%` | removed | Studio versus production is not something a live board needs |
| `%iris_world_speed%` | removed | Mutated engine rate-window state as a side effect of being read. Use `%iris_pregen.chunks-per-second%` |

One behavior change hides inside the renames: the old biome and region keys sampled two blocks above the player's feet, so they picked up cave and overhang biomes. The new keys are always the surface column. `%iris_world.dimension%` has no pre-2.0 equivalent.

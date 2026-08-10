# 09 - PlaceholderAPI

Iris registers the `iris` PlaceholderAPI expansion on Bukkit-family servers when PlaceholderAPI is enabled at Iris enable time. It publishes sixteen keys: seven world-family readings for the player and nine global pregeneration keys. This is an operator board contract, not a Java API; plugins that need the same data with more precision use `90 - API - Getting Started.md`, `91 - API - Terrain.md`, and `92 - API - World Events.md`. PlaceholderAPI is not available on Fabric/Forge/NeoForge; related runtime and integration details are in `07 - Pregeneration.md` and `28 - Integrations.md`.

## Tutorial: verify a placeholder before using it in another plugin

Prerequisites: Bukkit-family Iris, PlaceholderAPI installed before Iris enables, a full server restart, and a player in a loaded Iris world.

1. Confirm registration: `/papi info iris`. The output must list expansion id `iris` and its published paths.
2. Confirm the service: `/papi parse me %iris_available%`. Expect `true` while Iris terrain service is live.
3. Confirm player context: `/papi parse me %iris_world.available%`. Expect `true` while the named player is in an Iris world.
4. Parse a concrete terrain value: `/papi parse me %iris_world.biome-key%`. Expect a load key such as `desert/hot-dunes`, not `---`.
5. While standing in the Iris world, run `/iris pregen start 352 center=0,0 gui=false`, then parse `/papi parse me %iris_pregen.percent%`. Expect a numeric value from `0.00` through `100.00` with no percent sign.
6. Stop with `/iris pregen stop` or let the job finish, then run `/papi parse me %iris_pregen.available%`. Expect `false`; other pregen values return `---` after the snapshot clears.
7. Copy the exact verified placeholder into the scoreboard, chat, or HUD plugin and test that consumer once more.

The workflow passes when registration, player-scoped terrain, and global pregen values each produce their documented value shape. Do not debug formatting in the consuming plugin until direct `/papi parse` succeeds.

### Recovery

| Symptom | Meaning | Recovery |
|---|---|---|
| `/papi info iris` has no expansion | PlaceholderAPI was unavailable when Iris scheduled registration | Perform a full restart with both plugins installed; `/papi reload` alone does not trigger Iris registration |
| Placeholder remains literal | Path is unknown or uses a removed pre-2.0 name | Copy an exact path from `/papi info iris` or the full table below |
| World value is `---` | No online player context, player is outside Iris, or terrain service has no reading | Parse as a named online player after entering a loaded Iris world |
| `world.available` is true but biome lags movement | Player view cache is within its one-second TTL | Wait one second or trigger an immediate publish by teleport/world change before diagnosing the consumer |
| Pregen value is `---` | No global job snapshot is active | Start a job and wait for its first event; use `pregen.available` as the guard in board templates |
| Scoreboard adds `%` twice | `pregen.percent` deliberately omits the suffix | Add one literal `%` in the consumer format, not in the placeholder |

## Registration

| Item | Value |
|---|---|
| Expansion id | `iris` |
| Expansion version | `2.0.0` |
| Author string | `Volmit Software` |
| Required plugin | `Iris` |
| Soft-depend | `PlaceholderAPI` in `plugin.yml` |
| `persist()` | `true` — survives `/papi reload` without Iris restart |

Iris schedules setup after enable. If PlaceholderAPI is not enabled then, the expansion is not registered and there is no late `PluginEnableEvent` re-attempt. Soft-depend alone does not load PlaceholderAPI.

List published paths with `/papi info iris`.

## Value grammar

| Rule | Detail |
|---|---|
| Path form | Dot-separated, lowercase, no underscores. Iris lowercases the path before resolve, so `%iris_WORLD.BIOME%` works, but write lowercase |
| Plain text only | No color codes, no unit suffixes, no `%` in values, `.` as decimal separator, no thousands grouping |
| Pack name scrubbing | Section-sign sequences and `%` characters inside pack-authored names are stripped before return |
| Real zero | `0` (or `0.00` for two-decimal numbers), never `---` |

Three answers:

| Answer | When | Board shows |
|---|---|---|
| A value | Known key with data | The value |
| `---` | Known key with no data right now | `---` |
| Nothing (null to PAPI) | Unknown path | Literal `%iris_...%` |

Unknown paths stay visible on purpose. There is no catch-all blank fallback.

## Full key table

### World family

| Placeholder | Value |
|---|---|
| `%iris_available%` | `true` when the Iris terrain service is live, `false` otherwise |
| `%iris_world.available%` | `true` when the reading player is in an Iris world and a reading exists |
| `%iris_world.biome%` | Surface biome display name at the player column (example: `Hot Desert Dunes`) |
| `%iris_world.biome-key%` | Surface biome load key (example: `desert/hot-dunes`) |
| `%iris_world.region%` | Region display name at the player column |
| `%iris_world.region-key%` | Region load key |
| `%iris_world.dimension%` | Dimension (pack) load key of the player's world |

`%iris_available%` does not need a player. Every other `world.*` key needs a tracked online player. Console, offline player, or untracked position: `world.available` is `false` and the rest are `---`.

### Pregeneration family

| Placeholder | Value |
|---|---|
| `%iris_pregen.available%` | `true` while a pregeneration job is running |
| `%iris_pregen.world%` | World name the running job is pregenerating |
| `%iris_pregen.percent%` | Completion `0.00`–`100.00`, no `%` character |
| `%iris_pregen.eta%` | Estimated seconds remaining, whole number |
| `%iris_pregen.eta-text%` | Same estimate as `45s`, `2m 5s`, or `1h 30m` |
| `%iris_pregen.chunks%` | Chunks generated so far |
| `%iris_pregen.total%` | Chunks in the job |
| `%iris_pregen.chunks-per-second%` | Current rate, two decimal places |
| `%iris_pregen.paused%` | `true` while the job is paused |

`pregen.*` is global (one job per server). Values match for every player and the console. Snapshot is published on pregen events (`STARTED`, `TICK`, `PAUSED`, `RESUMED`, `SAVING`) and cleared on `COMPLETED` or `CANCELLED`. After clear, `pregen.available` is `false` and other `pregen.*` keys are `---`. Before enough chunks exist for an ETA, `eta`/`eta-text` read `0` / `0s`.

### Paths as reported by `/papi info iris`

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

## Surface readings and cache

`world.biome`, `world.biome-key`, `world.region`, and `world.region-key` are **surface** column readings: the biome/region the generator places at ground level for that X/Z. A player in a cave under an overhang still reads the surface biome above, not the cave biome.

### Position tracking

| Event | Publish |
|---|---|
| Walking (`PlayerMoveEvent`) | At most once per second per player; skipped while the player stays in the same block column |
| Join, respawn, world change, portal, any teleport (including `/iris goto`, `/tp`, ender pearl, random TP) | Immediate |

Standing still never keeps a stale column from a previous place after an immediate publish. Quit releases the player's position and world view.

### View rebuild TTL

World views rebuild at most **once per second per player** (`VIEW_TTL_MS = 1000`), and only when something reads a `world.*` key that needs the view. Consequences:

- A board full of `world.*` keys costs one rebuild per player per second
- Values can lag a sprinting player by up to one second
- An unread board costs no terrain queries

### Pregen snapshot

Pregen values come from a single global snapshot updated by `IrisPregenerationEvent`, not per-player polling.

## Permissions

Iris never gates a placeholder on a permission. Values that should not be public (for example world seed) are not published.

## Failure policy

| Situation | Shown |
|---|---|
| Unknown path | Nothing (literal `%iris_...%`) |
| Known path, no data | `---` |
| No player context on `world.*` | `---` and `world.available` = `false` |
| Player not in an Iris world | `---` and `world.available` = `false` |
| Terrain service not registered | `---` / `world.available` = `false` / `%iris_available%` = `false` |
| No pregen job | `---` / `pregen.available` = `false` |
| Resolver throws | `---`; one warning per distinct path, max 64 distinct paths |

Failed keys are not quarantined; they keep answering `---`.

## Migration from pre-2.0 keys

Pre-2.0 underscore keys are gone. No alias and no dual-accept window. Old keys render literally.

| Old key | New key | Notes |
|---|---|---|
| `%iris_biome_name%` | `%iris_world.biome%` | Dot grammar |
| `%iris_biome_id%` | `%iris_world.biome-key%` | `id` was always the load key |
| `%iris_region_name%` | `%iris_world.region%` | Dot grammar |
| `%iris_region_id%` | `%iris_world.region-key%` | `id` was always the load key |
| `%iris_biome_file%` | removed | Exposed absolute server paths; threw without a backing file |
| `%iris_region_file%` | removed | Same as `biome_file` |
| `%iris_world_seed%` | removed | No permission context on scoreboards; use terrain API `IrisWorldInfo.seed()` when a plugin needs seed |
| `%iris_terrain_height%` | removed | Generated height before objects/edits; disagreed with the block underfoot |
| `%iris_terrain_slope%` | removed | Expensive pack-authoring diagnostic |
| `%iris_world_mode%` | removed | Studio vs production is not a live-board concern |
| `%iris_world_speed%` | removed | Mutated engine rate-window state on read; use `%iris_pregen.chunks-per-second%` for pregen rate |

Behavior change inside the renames: old keys sampled two blocks above the player's feet (cave/overhang Y). New keys are always surface for the column. `%iris_world.dimension%` is new and has no pre-2.0 equivalent.

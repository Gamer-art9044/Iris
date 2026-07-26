# Iris placeholders

Iris registers the `iris` PlaceholderAPI expansion when PlaceholderAPI is enabled. It publishes
sixteen keys: seven in the world family, describing the generator around the reading player, and
nine in the pregeneration family, describing the server's running pregeneration job.

This is an operator-facing contract, not a compile surface. Nothing here needs a dependency, a
`softdepend`, or a line of Java. If you are writing a plugin rather than a scoreboard, the same data
is available with more precision through the [terrain API](terrain.md) and the
[pregeneration events](world-events.md).

**The pre-2.0 underscore keys are gone.** There is no alias and no dual-accept window. If you are
upgrading an existing board, go straight to the [migration table](#migration-from-the-pre-20-keys).

---

## The value grammar

Every key follows the same rules, so a board never has to special-case Iris:

- Paths are **dot-separated and lowercase** and never contain an underscore. Iris lowercases the
  path before resolving it, so `%iris_WORLD.BIOME%` works, but write it lowercase.
- Values are **plain text**: no colour codes, no unit suffixes, no `%` character, `.` as the decimal
  separator, no thousands grouping.
- Any section sign or `%` character that appears inside a pack-authored name — a biome display name,
  a world name — is stripped before you see it, so a pack cannot inject formatting or a nested
  placeholder into your board.

There are exactly three possible answers:

| Answer | When | What PlaceholderAPI shows |
|---|---|---|
| A value | The key is known and has data | The value |
| `---` | The key is known and has no data right now | `---` |
| Nothing | The key is not one Iris publishes | The literal `%iris_...%` |

The third row is deliberate. A typo stays visible on the board instead of quietly rendering as
blank, which is why there is no catch-all fallback.

A real zero is `0`, never `---`. `---` means "no reading", not "zero".

---

## World keys

| Placeholder | Value |
|---|---|
| `%iris_available%` | `true` when the Iris terrain service is live, `false` otherwise |
| `%iris_world.available%` | `true` when the reading player is in an Iris world and a reading exists |
| `%iris_world.biome%` | Surface biome display name at the player, for example `Hot Desert Dunes` |
| `%iris_world.biome-key%` | Surface biome load key, for example `desert/hot-dunes` |
| `%iris_world.region%` | Region display name at the player |
| `%iris_world.region-key%` | Region load key |
| `%iris_world.dimension%` | Dimension (pack) load key of the player's world |

`%iris_available%` is the only world-family key that does not need a player. It answers for the
console and for an offline player.

Every other `world.*` key needs a tracked online player. For the console, an offline player, or a
player Iris has no position for yet, `world.available` is `false` and the rest are `---`.

### They are surface readings, and they are cached

`world.biome`, `world.biome-key`, `world.region` and `world.region-key` describe the **surface** at
the player's block column — the biome and region the generator places at ground level. A player
standing in a cave under an overhang reads the biome of the sky above them, not the cave they are
in. That is what a board reader means by "what biome am I in".

The reading is rebuilt at most **once per second per player**, and only when something actually reads
one of these keys. Consequences:

- A whole board of `world.*` keys costs one rebuild per player per second, however many of them are
  on it.
- A value can lag a sprinting player by up to a second.
- A board that nobody is looking at costs nothing.

Position tracking has two speeds. Walking republishes a player's column at most once per second, and
not at all while they stand still. Anything that is not walking — joining, respawning, changing
world, stepping through a portal, or **any** teleport including `/iris goto`, `/tp`, an ender pearl
and a random teleport — publishes immediately. A player who arrives somewhere and stops moving
therefore never keeps showing the biome of where they came from.

---

## Pregeneration keys

| Placeholder | Value |
|---|---|
| `%iris_pregen.available%` | `true` while a pregeneration job is running |
| `%iris_pregen.world%` | World name the running job is pregenerating |
| `%iris_pregen.percent%` | Completion, `0.00` to `100.00`, with no `%` character |
| `%iris_pregen.eta%` | Estimated seconds remaining, whole number |
| `%iris_pregen.eta-text%` | The same estimate as `45s`, `2m 5s` or `1h 30m` |
| `%iris_pregen.chunks%` | Chunks generated so far |
| `%iris_pregen.total%` | Chunks in the job |
| `%iris_pregen.chunks-per-second%` | Current rate, two decimal places |
| `%iris_pregen.paused%` | `true` while the job is paused |

`pregen.*` is **global**, not per player. Iris runs one pregeneration job per server, so these keys
read the same for everyone, including the console. `%iris_pregen.world%` says which world it is.

The snapshot is published when the job reports progress, once per second, and cleared the moment the
job completes or is cancelled. After that every `pregen.*` key except `pregen.available` reads `---`,
and `pregen.available` reads `false`. There is no lingering "last job" state to mistake for a running
one.

`pregen.eta` and `pregen.eta-text` are two renderings of the same estimate: use `eta` for arithmetic
and `eta-text` for display. Both read `0` and `0s` respectively before the job has generated enough
chunks to estimate from.

---

## Availability

The expansion is registered only if PlaceholderAPI is enabled when Iris starts. It sets `persist()`,
so it survives `/papi reload` without Iris restarting.

`%iris_available%` distinguishes "Iris is installed but its terrain service is not up" from "Iris is
not installed at all" — in the second case the expansion does not exist, no key resolves, and every
`%iris_...%` on the board renders literally. Gate a conditional board on `%iris_available%` if you
want it to disappear cleanly rather than show `---` rows on a server where Iris is present but still
starting.

Iris never gates a placeholder on a permission. A placeholder has no permission context to check
against — the player reading a scoreboard is not necessarily the player the value describes — so
values that should not be public are not published at all. That is why there is no seed key.

---

## Migration from the pre-2.0 keys

The old underscore keys are gone. There is no alias and no dual-accept window: an old key now
renders literally, so it is visible rather than silently wrong. This table is complete — every key
the old expansion published appears in it.

| Old key | New key | Why |
|---|---|---|
| `%iris_biome_name%` | `%iris_world.biome%` | Renamed onto the dot grammar |
| `%iris_biome_id%` | `%iris_world.biome-key%` | Renamed; `id` was always the load key |
| `%iris_region_name%` | `%iris_world.region%` | Renamed onto the dot grammar |
| `%iris_region_id%` | `%iris_world.region-key%` | Renamed; `id` was always the load key |
| `%iris_biome_file%` | removed | Rendered an absolute server path into player-visible text, and threw on packs with no backing file |
| `%iris_region_file%` | removed | Same as `biome_file` |
| `%iris_world_seed%` | removed | Handed the world seed to anyone who could read a scoreboard, and a placeholder has no permission context to gate on |
| `%iris_terrain_height%` | removed | Reported the *generated* height, before objects and player edits, so it disagreed with the block under the player's feet |
| `%iris_terrain_slope%` | removed | Three extra noise samples per read for an unformatted pack-authoring diagnostic |
| `%iris_world_mode%` | removed | Studio or Production; a studio world exists for seconds during authoring and is never on a live board |
| `%iris_world_speed%` | removed | Mutated engine rate-window state every time it was read. `%iris_pregen.chunks-per-second%` answers the same question from a snapshot |

There is one behaviour change inside the four renames, and it will be visible on a board that has
been in service for a while. The old keys sampled **two blocks above the player's feet** and asked
for the biome at that exact Y, which meant a player standing under an overhang or inside a cave read
the *cave* biome. `%iris_world.biome%` and `%iris_world.biome-key%` are always the surface biome for
the column. If your board is checked against a screenshot from before the rename, expect
underground readings to differ.

`%iris_world.dimension%` is new. It has no pre-2.0 equivalent.

The three removals worth a replacement plan:

- **`world_seed`** has no replacement and will not get one. A plugin that legitimately needs the seed
  can read it from `IrisWorldInfo.seed()` through the [terrain API](terrain.md), where there is a
  caller to hold responsible.
- **`terrain_height`** has no replacement. If you want the ground height for a coordinate, use
  `IrisTerrainService#surfaceHeight`, which is the same number with its limitations documented. If
  you want the block under the player, use the player's own Y.
- **`world_speed`** is replaced by `%iris_pregen.chunks-per-second%` for the pregeneration case,
  which is what it was almost always used for. There is no per-world live generation rate key.

---

## Failure policy

| Situation | What Iris shows |
|---|---|
| An unknown path | Nothing. PlaceholderAPI re-emits the literal `%iris_...%` |
| A known path with no data | `---` |
| No player context, on a `world.*` key | `---`, and `world.available` is `false` |
| Player is not in an Iris world | `---`, and `world.available` is `false` |
| The terrain service is not registered | `---`, `world.available` is `false`, `%iris_available%` is `false` |
| No pregeneration job running | `---`, and `pregen.available` is `false` |
| A resolver throws | `---`, and one warning is logged naming the exact placeholder |

A resolver that throws is logged **once per distinct path**, up to 64 distinct paths, so a broken
key cannot flood the console from a scoreboard that re-renders every tick. The value shown is always
`---` — a failure never renders a stack trace, a class name, or an empty string.

Iris does not disable a placeholder after repeated failures. There is no fault limit and no
quarantine; a key that fails keeps being asked and keeps answering `---`.

---

## Key reference

The full published list, as PlaceholderAPI reports it under `/papi info iris`:

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

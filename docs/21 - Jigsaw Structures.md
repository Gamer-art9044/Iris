# 21 - Jigsaw Structures

Iris multi-piece structures: pieces connect through named connectors drawn from weighted pools, assembled deterministically per start chunk. Every piece is an Iris object (`.iob`). Authoring pieces, pools, and structures and placing them is covered here. Vanilla/datapack structures and import are `22 - Native Structures & Datapacks.md`.

## 0. Resource model

| Resource | Pack folder | Role |
|---|---|---|
| Structure | `structures/` | Entry point: start pool and assembly caps |
| Jigsaw pool (`IrisJigsawPool`) | `jigsaw-pools/` | Weighted pieces + one fallback pool |
| Jigsaw piece (`IrisJigsawPiece`) | `jigsaw-pieces/` | One object + connectors |
| Connector | inline in a piece | Position, facing, target pool, name |

Keys are file paths under the folder minus `.json`. A structure attaches to the world via an `IrisStructurePlacement` in `structures[]` on a biome, region, or dimension — placement is separate from the structure resource.

Older packs may still have a `jigsaw-structures/` folder. Nothing reads it; live folders are the three above.

## 1. Resources

### 1.1 `structures/<key>.json`

| Field | Default | Meaning |
|---|---|---|
| `startPool` | required | Pool the assembler draws the start piece from. |
| `maxDepth` | `7` (1..30) | Maximum recursion depth. |
| `maxSizeChunks` | `8` (1..32) | Hard radius in chunks around start: candidate whose box leaves `maxSizeChunks * 16` blocks from start origin is rejected. Y is not bounded by it. |
| `placeMode` | `STRUCTURE_PIECE` | Object place mode when stamping pieces. Other modes change structure-piece anchoring (3.6). |
| `edit` | `[]` | Find-and-replace on every piece (same syntax as object placements). |
| `loot` | `[]` | Loot-table keys applied to piece containers (each weight 1, non-overriding). |
| `vanillaSource` | `""` | Provenance key from import. Locate alias for `/iris goto structure` and `verify`. Empty for hand-authored structures. |

No terrain adaptation on the structure — terrain lives on the placement.

Example (imported graph shape):

```json
{
  "startPool": "minecraft_village_plains/pool/minecraft_village_plains_town_centers",
  "maxDepth": 6,
  "maxSizeChunks": 8,
  "placeMode": "STRUCTURE_PIECE",
  "vanillaSource": "minecraft:village_plains"
}
```

### 1.2 `jigsaw-pools/<key>.json` (`IrisJigsawPool`)

| Field | Default | Meaning |
|---|---|---|
| `pieces` | min 1 entry | Weighted entries: `{ "piece": "<key>", "weight": 1 }` or `{ "empty": true, "weight": 3 }`. |
| `fallback` | `""` | Pool tried after this one — and used **alone** once `maxDepth` is reached. Exactly one level deep: a fallback's own fallback is never consulted. Empty = stop expanding at max depth. |

An `empty: true` entry terminates the branch without placing. Both `empty` and `piece` in one entry is a validation error; weights must be ≥ 1; fallback cycles are blocked.

```json
{
  "pieces": [
    { "piece": "mypack/watchtower/corridor", "weight": 5 },
    { "piece": "mypack/watchtower/corridor_short", "weight": 2 },
    { "weight": 3, "empty": true }
  ],
  "fallback": "mypack/watchtower/pool/terminators"
}
```

### 1.3 `jigsaw-pieces/<key>.json` (`IrisJigsawPiece`)

| Field | Default | Meaning |
|---|---|---|
| `object` | required | Object (`objects/<key>.iob`) for this piece. |
| `connectors` | optional | Connection points. For a terminal cap, **omit the key** — runtime and pack validation accept an empty list; generated VSCode schema requires at least one entry when the key is present. |
| `rotatable` | `true` | Whether the assembler may Y-rotate the piece. |

### 1.4 Connectors

| Field | Default | Meaning |
|---|---|---|
| `position` | required | Block coordinate **inside the object**, 0-based from lowest corner: `0..W-1 / 0..H-1 / 0..D-1`. |
| `direction` | required | Face this connector points out of. |
| `top` | `UP_POSITIVE_Y` | Authored "up" for `ALIGNED` roll lock. Write it explicitly. |
| `pool` | required | Pool for the connecting piece. |
| `name` | `""` | Identity this connector **exposes**. |
| `targetName` | `""` | `name` this connector wants on the other piece. |
| `joint` | `ROLLABLE` | `ROLLABLE` (free roll) or `ALIGNED` (roll locked — doorways/streets). |

`direction` / `top` values:

```
UP_POSITIVE_Y  DOWN_NEGATIVE_Y  NORTH_NEGATIVE_Z  SOUTH_POSITIVE_Z  EAST_POSITIVE_X  WEST_NEGATIVE_X
```

Unlike vanilla jigsaw blocks:

- Connectors are **JSON metadata, not blocks**. The block at the connector cell stays — no `final_state`. Jigsaw blocks inside an object are stripped on load.
- Matching is target-to-name: candidate attaches when its `name` equals the source's `targetName`, directions oppose after trial rotation, and for `ALIGNED` sources rotated `top` matches. Names are exact, case-sensitive.

## 2. Assembly

Constants: hard piece cap **512**, max depth **30**, max size **32 chunks**.

1. **Start.** Weighted-pick from `startPool` (`empty` → nothing), random cardinal rotation if `rotatable`, place at origin, queue connectors at depth 0.
2. **BFS.** Pop open connector; resolve its `pool`. Below `maxDepth` try primary then direct fallback; at `maxDepth` only fallback.
3. **Candidates** in weighted-random order. For each piece, test connectors (name match, opposed direction) across rotations — `{0,90,180,270}` shuffled for `ROLLABLE`, fixed for `ALIGNED`, `{0}` when `rotatable: false`.
4. **Geometry.** New piece positioned so connector cells are adjacent along source facing.
5. **Rejection.** Discard if box leaves `maxSizeChunks * 16` radius or intersects a placed piece (boxes may touch, not overlap).
6. **Success** places the piece and queues remaining connectors at depth + 1.

**Authoring rule:** a connector at depth **below** `maxDepth` that cannot be satisfied drops the **whole** assembly for that chunk, silently, with no retry. At `maxDepth` and beyond, unsatisfied connectors are tolerated.

Give every pool a terminating option — `empty: true` or a `fallback` of connector-less caps.

Loud failures (missing pool/piece/object, malformed connector, non-positive weight, 512-piece cap with connectors still open) indicate a broken graph and are caught by pack validation.

**Determinism.** Assembly is a pure function of `(mantle seed, chunk X, chunk Z, placement identity)`.

## 3. Placement

`structures[]` on a **biome**, **region**, or **dimension**. The same array hosts native placements (`nativeStructures` — `22 - Native Structures & Datapacks.md`); each placement must declare exactly one of `structures` / `nativeStructures`.

### 3.1 Fields (Iris backend)

| Field | Default | Meaning |
|---|---|---|
| `structures` | `[]` | Iris structure keys. One picked uniformly per start chunk. Duplicates are a validation error. |
| `placementId` | `""` | Stable identity. Empty derives identity from content — reordering does not move structures; changing settings does. Set when retuning spacing/heights without re-rolling positions, or when two placements would otherwise be identical. |
| `distribution` | `RANDOM_SPREAD` | `RANDOM_SPREAD` / `DENSITY` / `CONCENTRIC_RINGS`. |
| `spacing` | `32` (1..4096) | RANDOM_SPREAD grid cell size in chunks. |
| `separation` | `8` | RANDOM_SPREAD minimum chunk separation; must be **smaller than** `spacing`. |
| `salt` | `165745296` | Mixed into placement RNG. |
| `density` | `0.02` (0..1) | DENSITY per-chunk start probability. |
| `ringCount` / `ringDistance` / `ringSpread` | `128` / `32` / `3` | CONCENTRIC_RINGS around origin. |
| `minHeight` / `maxHeight` | `-2032` / `2032` | Surface: pass/fail gate on surface Y. Underground: Y band. |
| `underground` | `false` | Start at deterministic random Y in band, then shift down under terrain across footprint. |
| `underwater` | `false` | If false, submerged origins skipped. |
| `terrain` | `{mode: SOURCE}` | Iris backend: `SOURCE`, `PRESERVE`, `BORE`, `FORCE_CARVE`. **`VACUUM` and `ENCASE` are rejected** for Iris assemblies (native only). |
| `stilt` | unset | Foundation columns under assembly bottom cells: `maxDepth` (default 64), `palette` (default cobblestone), `supportNonOccluding`. `spacing` is honored on the **native** backend only. Placed only when every piece succeeded. |
| `nativeSuppression` | `NONE` | `REPLACE_SOURCE` suppresses each structure's `vanillaSource` native generation. Dimension-level only; failures then throw (no native fallback). |

`terrain` sub-fields for `BORE` / `FORCE_CARVE`: `horizontalPadding` (0..128), `ceilingPadding` (0..128), `floorPadding` (0..64; 0 preserves floor). `FORCE_CARVE`: `shape` (`BOX`/`ROUNDED`/`ERODED`) and for `ERODED` the erosion/lobe knobs. `BORE` always clears a box.

### 3.2 Scoping

Per chunk, Iris samples biome and region at chunk center and collects that biome's placements, that region's placements, and all dimension placements. Biome-level placements fire only where the center lands in that biome.

### 3.3 Height

- Surface: surface height at origin is anchor Y, gated by `minHeight`/`maxHeight`. With default `STRUCTURE_PIECE`, pieces stamp **centered** on that Y (start piece midpoint at surface). Assemblies with non-`STRUCTURE_PIECE`, non-`FLOATING` place mode re-anchor so base sits at surface.
- Underground: seeded random Y in band, then burial shift so envelope (including carve padding) stays under surface; if it cannot fit, chunk skipped.
- No `yBand` on this path — that belongs to native adjustments.

### 3.4 Worked example

```json
{
  "structures": [
    {
      "placementId": "ruined-watchtower",
      "structures": ["mypack/watchtower"],
      "distribution": "RANDOM_SPREAD",
      "spacing": 48,
      "separation": 12,
      "salt": 918273645,
      "minHeight": 62,
      "maxHeight": 140,
      "terrain": {
        "mode": "FORCE_CARVE",
        "shape": "ROUNDED",
        "horizontalPadding": 4,
        "ceilingPadding": 6,
        "floorPadding": 0
      },
      "stilt": {
        "maxDepth": 48,
        "palette": { "palette": [ { "block": "minecraft:cobblestone" } ] }
      }
    }
  ]
}
```

### 3.5 Distribution modes

- **`RANDOM_SPREAD`** — world cut into `spacing`-chunk cells, one candidate per cell, offset by up to `spacing - separation`.
- **`DENSITY`** — independent per-chunk roll. Low densities make `/iris goto` searches expensive.
- **`CONCENTRIC_RINGS`** — `ringCount` placements total, rings `ringDistance` chunks apart, `ringSpread` per ring. Chunk 0,0 is never a start.

### 3.6 Place modes for pieces

Structure `placeMode` matters most for single-piece structures: a terrain-following mode (e.g. `PAINT`, stilts) places through the normal object placer at the surface. Multi-piece assemblies stamp as `STRUCTURE_PIECE` at assembled coordinates; `FLOATING` is downgraded to `STRUCTURE_PIECE`. `underground` placements always stamp `STRUCTURE_PIECE`, with `ORGANIC_STILT` / `CEILING_HANG` as the exception. If in doubt, leave `STRUCTURE_PIECE`.

## 4. Authoring workflow

### 4.1 No in-game jigsaw editor

There is no `/iris jigsaw` command or connector wand. Build piece objects in-game and author piece, pool, and structure JSON directly. `/iris studio vscode [dimension=<pack>]` (alias `vsc`) provides JSON-schema autocomplete for all three folders.

### 4.2 End to end

**1. Studio and build**

```
/iris studio open <pack>
/iris object wand
```

Note object-local coordinates of connector cells — local `(0,0,0)` is the selection corner with lowest X, Y, Z. Leave connector cells as air (or the keep block).

**2. Save object** (`19 - Objects.md`):

```
/iris object save dimension=<pack> mypack/watchtower/base overwrite=true
```

**3. Piece JSON** — `jigsaw-pieces/mypack/watchtower/base.json`:

```json
{
  "object": "mypack/watchtower/base",
  "rotatable": true,
  "connectors": [
    {
      "position": { "x": 4, "y": 1, "z": 0 },
      "direction": "NORTH_NEGATIVE_Z",
      "top": "UP_POSITIVE_Y",
      "pool": "mypack/watchtower/pool/corridors",
      "name": "mypack:tower_side",
      "targetName": "mypack:corridor_end",
      "joint": "ALIGNED"
    }
  ]
}
```

Matching corridor connector faces the opposite way and names itself `mypack:corridor_end`.

Connector geometry:

- Connector cell is **inside** the object, on the outermost layer the neighbor butts against. Neighbor matching cell lands adjacent along `direction`.
- Vertical connectors with `joint: ROLLABLE` suit toppers; horizontal doorways/streets want `ALIGNED`.

**4. Pools** — every reachable pool needs a terminating option.

**5. Structure** — `structures/mypack/watchtower.json`:

```json
{
  "startPool": "mypack/watchtower/pool/starts",
  "maxDepth": 5,
  "maxSizeChunks": 4,
  "placeMode": "STRUCTURE_PIECE",
  "loot": ["mypack/watchtower_chest"]
}
```

**6. Placement** in biome, region, or dimension (3.4).

**7. Validate, inspect, place, iterate** (section 5).

### 4.3 Reload

Studio hotloads into newly generated chunks. Outside studio, close and reopen the world after changes. Files written by `/iris structure import` are ownership-tracked; hand edits make later imports refuse to overwrite those files.

## 5. Testing and debugging

**Pack validation** — use `/iris pack validate pack=<pack>` on Bukkit or `/iris pack validate <pack>` on modded. It resolves references, connector bounds, weight/enum/range, `separation` ≥ `spacing`, duplicate `placementId`s, VACUUM/ENCASE on Iris backend; compiles graphs with reachability; and runs 16 seeded sample assemblies per structure. Common hand-author diagnostic: `NO_COMPATIBLE_CONNECTOR`.

**`/iris structure info <dimension> <structure>`** — compile + one sample assembly (piece count, footprint). No world needed. "assembled 0 pieces" means start pool resolved empty.

**`/iris structure place <dimension> <structure>`** (player) — stamp at your location (raw geometry; no carve/stilts/loot).

**`/iris structure verify <dimension> [radius=48]`** — Iris placement plans in range (`[iris-planned]` / `[iris-not-found]`) plus native statuses.

**`/iris goto structure <key>`** (`/iris find structure`) — locate nearest planned instance; Iris keys and `vanillaSource` aliases. Iris search up to 1024 chunks; native locate 100 chunks. `/iris goto unregistered` dumps excluded keys and reasons.

At world load, graph diagnostics log with `[StructureGraph:<key>]`. A structure without a runtime-viable graph refuses to place.

### Failure modes

| Symptom | Likely cause |
|---|---|
| Only start piece | Every connector drew `empty` first, or all candidates rejected at max depth with no fallback. |
| Nothing, no error | Start pool rolled `empty`; submerged with `underwater: false`; surface Y outside band; grid did not select chunk. |
| Appears some seeds, vanishes others | Connector below `maxDepth` unsatisfiable for that seed. Add `empty` / terminator fallbacks. |
| `references missing connector pool` | Pool key typo or file not under `jigsaw-pools/`. |
| `exceeded the hard piece cap of 512` | Runaway recursion: always more connectors, no empty weight, depth too high. |
| Pieces visually clip | Bounding-box collision; decorative overhang inside box still "fits". `/iris object shrink` pieces. |
| VACUUM/ENCASE validate or runtime error | Native-only modes; use BORE / FORCE_CARVE, or place via `nativeStructures`. |
| `REPLACE_SOURCE ... failed in chunk` | Suppression without guaranteed output; dimension-level placement, valid `vanillaSource`, graph must guarantee output. |

## 6. Mapping from vanilla datapack jigsaws

| Vanilla | Iris |
|---|---|
| `worldgen/structure` type jigsaw | `structures/*.json` |
| `start_pool` | `startPool` |
| `size` | `maxDepth` |
| `max_distance_from_center` | `maxSizeChunks` (chunks, hard bound) |
| `worldgen/template_pool` | `jigsaw-pools/*.json` |
| `elements[]` | `pieces[]` |
| `minecraft:empty_pool_element` | `"empty": true` |
| `fallback` | same idea, one level deep |
| template `.nbt` | object + `jigsaw-pieces/*.json` |
| jigsaw block pool/name/target/joint/facing/top | connector `pool`/`name`/`targetName`/`joint`/`direction`/`top` |
| `final_state` | none — block at cell stays |
| structure set `random_spread` | `RANDOM_SPREAD` |
| structure set `concentric_rings` | `CONCENTRIC_RINGS` |
| (none) | `DENSITY` |

`terrain_adaptation`, `start_height`, `projection`, and per-element settings have no direct equivalents; nearest are placement height/underground fields and `terrain.mode`.

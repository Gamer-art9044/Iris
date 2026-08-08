# 20 - Object Placement

Object placements wire a saved object (`objects/<key>.iob`) into biome or region JSON so the generator stamps it. Creating objects is `19 - Objects.md`; multi-piece assemblies are `21 - Jigsaw Structures.md`.

## 1. Where placements go

`objects[]` exists on exactly two resource types:

- **Biome** (`biomes/**.json`) — where that biome generates.
- **Region** (`regions/**.json`) — every biome in the region.

**Dimensions do not have `objects[]`.** An `objects` array on a dimension is ignored at generation time. Dimensions carry surface-support knobs (`requireObjectSurfaceSupport`, `objectSurfaceSupportBuffer`, section 7) and upper-dimension object controls (`upperDimensionObjects`, `upperObjectsForcePlace`), but no placements of their own.

### Scope and frequency

Per chunk, biome and region are sampled **once at the chunk center** (block 8,8), then every placement entry in the biome list, then the region list, rolls independently.

- Biome vs region differ only in breadth; chance/density math is identical; both can fire in the same chunk.
- A biome that only occupies a corner contributes nothing to that chunk; the center biome's objects can spill slightly into neighbors.
- Each entry belongs to the surface list, the cave list, or both via `carvingSupport` (`SURFACE_ONLY` default, `CARVING_ONLY`, `ANYWHERE`). `ANYWHERE` is in both lists and rolls chance twice per chunk. Cave placements probe for a cave biome below the surface and fall back to the surface biome.

### Minimal entry

```json
{
  "objects": [
    {
      "place": ["clutter/boulder1"],
      "chance": 0.05
    }
  ]
}
```

`place` entries are object keys (path under `objects/` without `.iob`). A whole placement can also be a snippet reference (`"snippet/object-placer/<name>"`).

## 2. Frequency: `chance`, `density`, `densityStyle`

| Field | Default | Meaning |
|---|---|---|
| `chance` | `1` | Rolled **once per chunk per entry** (0..1). Surface entries add ±0.005 jitter, so use omission — not `chance: 0` — to disable a surface entry. Cave entries use the raw chance (no jitter). |
| `density` | `1` | Placement **attempts** in the chunk once chance passes. Each attempt picks a random object from `place` and a random column. Attempts can still be vetoed — density is not a guarantee. |
| `densityStyle` | unset | Noise-driven range that **replaces** `density` when present. Class defaults are min 16 / max 32, so an empty `densityStyle: {}` means 16–32 attempts per chunk. |

Expected objects per chunk ≈ `chance × density`, before rejections. `chance: 0.002` is roughly one per 500 chunks.

**`place` has no weights** — uniform pick. To bias one object, list its key multiple times or split entries. An unresolvable key skips the attempt; `ObjectResourceLoader` logs a warning for each failed lookup.

## 3. Placement modes

`mode` (default `CENTER_HEIGHT`) decides how the object meets terrain. All 21 values:

**Height sampling**

| Mode | Meaning |
|---|---|
| `CENTER_HEIGHT` | One height sample at the center; whole object uses it. Default. |
| `MAX_HEIGHT` / `FAST_MAX_HEIGHT` | Highest sample across footprint (FAST = 4 samples). Never buried; floats off cliffs. |
| `MIN_HEIGHT` / `FAST_MIN_HEIGHT` | Lowest sample. Never overhangs; buries into slopes. |
| `PAINT` | Every column pushed to terrain surface — melts the object over terrain. Common for ground clutter. |

**Stilts** (extend bottom blocks down; tuned by `stiltSettings`)

| Mode | Meaning |
|---|---|
| `STILT` / `FAST_STILT` | `MAX_HEIGHT` + stilt columns (FAST cheaper, less accurate — combine with `overStilt`). |
| `MIN_STILT` / `FAST_MIN_STILT` | `MIN_HEIGHT` + stilts. |
| `CENTER_STILT` | `CENTER_HEIGHT` + stilts. Cheapest generally useful stilt mode. |
| `ERODE_STILT` | Cone-tapered stilts: center deepest, edges drop off, lower parts randomly broken. |
| `ORGANIC_STILT` | Scans down to first solid (cave floor or terrain); fills with object's bottom blocks, noise-varied. For cave floor connection. Tune with `organicMaxScan`, `organicJitter`, `organicScratch`. |
| `CEILING_HANG` | Flips object and anchors to cave ceiling with organic stilt into the roof. |

**Terrain shaping** (modify terrain; tuned by `vacuumSettings`)

| Mode | Meaning |
|---|---|
| `VACUUM` | Anchors like `CENTER_HEIGHT`, bends surrounding terrain flush with base (default radius 12, parabolic). |
| `VACUUM_HIGH` | Radius 20, finest blend. More expensive. |
| `VACUUM_FAST` | Radius 8, coarser sampling. |
| `VACUUM_ORGANIC` | Falloff radius perturbed per column. |
| `VACUUM_WAVY` | Bend modulated by simplex noise (`waveAmplitude`, `waveScale`). |

**Special**

| Mode | Meaning |
|---|---|
| `FLOATING` | Pure-air placement: Y from rotated object center height plus `translate.y` (+ `yRandom`). Skips terrain, water, and cave checks. Floating islands, sky structures. |
| `STRUCTURE_PIECE` | Raw stamp at caller coordinates. **Internal** for structure pieces. Do not author in `objects[]`. |

## 4. Field reference

### Anchoring and water

| Field | Default | Meaning |
|---|---|---|
| `carvingSupport` | `SURFACE_ONLY` | `SURFACE_ONLY` / `CARVING_ONLY` / `ANYWHERE`. |
| `caveAnchorMode` | `PROFILE_DEFAULT` | Cave: `FLOOR`, `CEILING`, `CENTER`, `ANY`, or carving profile default. |
| `heightmap` | unset | Noise generator used as a **virtual** heightmap instead of terrain. |
| `bottom` | `false` | With explicit Y (cave/structure/tree paths), place from ground up. Incompatible with X/Z rotation. |
| `fromBottom` | `false` | Place from world bottom up. Incomplete path — avoid. |
| `underwater` | `false` | Place on terrain height, ignoring water surface. Without it (or `onwater`), submerged surface placements are rejected. |
| `onwater` | `false` | Place on fluid surface (boats). |
| `waterloggable` | `false` | Waterlog placed blocks that sit in water. |
| `isDolphinTarget` | `false` | With `underwater`, marks placed storage chests as buried-treasure POI for dolphins. |

### Terrain interaction

| Field | Default | Meaning |
|---|---|---|
| `bore` | `false` | Clear bounding cuboid to air before placing. `boreExtendMaxY` / `boreExtendMinY` expand the box. |
| `smartBore` | `false` | Raytraced interior fill — clears rooms/pockets. Slows object loading, not warmed-up generation. |
| `meld` | `false` | Place only where blocks already exist. Expensive. |
| `warp` | flat | Generator style warping placement coordinates per block. |
| `snow` | `0` | 0..1 — snow layers on top of placed columns. |
| `edit` | `[]` | Find-and-replace at placement: `find[]`, `replace` (palette), `exact`, `chance`. |

### Collisions and force

| Field | Default | Meaning |
|---|---|---|
| `forbiddenCollisions` | `[]` | Object keys this object may not intersect; match inside bounding box rejects (unless also in `allowedCollisions`). |
| `allowedCollisions` | `[]` | Exceptions to the above. |
| `forcePlace` | `false` | JSON also accepts `"force"`. Bypasses slope, surface-support, underwater, clamp, bedrock, and collision guards. Does **not** bypass native-structure veto (objects never overwrite native structure pieces). |

### Slope

| Field | Default | Meaning |
|---|---|---|
| `slopeCondition` | `{minimumSlope: 0, maximumSlope: 10}` | Slope gate, 3-block radius. Defaults mean no condition; tighten `maximumSlope` (e.g. 2) to keep buildings off hills. |
| `rotateTowardsSlope` | `false` | Adds downhill direction (rounded to 90°) to Y rotation. |

### `rotation`

Default already enables Y free 90° steps — objects get random cardinal rotation with no config. Common pack form:

```json
{
"rotation": { "enabled": true, "yAxis": { "enabled": true, "min": 0, "max": 270, "interval": 90 } }
}
```

Per-axis (`xAxis` / `yAxis` / `zAxis`, each `{enabled, min, max, interval}`):

- `min == max == 0` — any multiple of `interval` (free spin).
- `min == max != 0` — locked to that angle.
- otherwise — multiple of `interval` clipped into `[min, max]`.
- `interval: 0` fully free only in free-spin case; in a clipped range always set non-zero `interval`. Non-90° intervals usually look bad at block resolution.

Disable with `"rotation": {"enabled": false}`. X/Z rotation is incompatible with `bottom: true`.

### `translate` and `scale`

```json
{
"translate": { "x": 0, "y": -1, "z": 0, "yRandom": 0 }
}
```

`translate.y: -1` seats clutter into the ground. Translate rotates with the object. `yRandom` adds `rand(0..yRandom)` height per placement.

```json
{
"scale": { "size": 1, "minimumScale": 0.75, "maximumScale": 1.25, "variations": 7, "interpolation": "TRILINEAR" }
}
```

Defaults: `size`, `minimumScale`, `maximumScale` all `1`; `interpolation` `NONE` — inert until you set `size` or widen min/max. `size != 1` is a fixed multiplier and overrides the range; at `size: 1` a random scale is picked from the range, quantized into `variations` (default 7) cached variants. Upscale interpolators: `NONE`, `TRILINEAR`, `TRICUBIC`, `TRIHERMITE`.

### `clamp`

```json
{
"clamp": { "minimumHeight": 40, "maximumHeight": 225 }
}
```

Rejects placements whose resolved bottom/top fall outside the band. Field defaults `-2048` / `2048`. Heights are engine-internal Y (0..dimension height), not necessarily world Y.

### `stiltSettings` (all `*_STILT` modes and `CEILING_HANG`)

| Field | Default | Meaning |
|---|---|---|
| `yMax` | `0` | Max stilt height before overstilt/random range. |
| `yRand` | `0` | Extra random stilt depth. |
| `overStilt` | `0` | Extra depth into ground (useful with FAST_STILT). |
| `palette` | unset | Column palette; default repeats object's bottom block (grass-family bottoms substitute dirt). Stops at first fluid. |
| `organicMaxScan` | `48` | ORGANIC_STILT / CEILING_HANG max scan. |
| `organicJitter` | `3` | Random per-column shortening. |
| `organicScratch` | `0.55` | Fraction of deepest part randomly broken. |

### `vacuumSettings` (all `VACUUM*` modes)

| Field | Default | Meaning |
|---|---|---|
| `radius` | `0` (auto: 12 / 20 high / 8 fast) | Deformation extent past footprint. |
| `falloff` | `2.0` | Easing: 1 = cone, 2 = parabolic, higher = flatter near object. |
| `organicJitter` | `4` | VACUUM_ORGANIC radius perturbation. |
| `waveAmplitude` | `3` | VACUUM_WAVY wave height. |
| `waveScale` | `5.0` | VACUUM_WAVY frequency (~100/waveScale blocks wavelength). |

## 5. Loot

Two arrays, matched against container blocks inside the placed object. Loot injects lazily when a chest is first opened, from the placement recorded in the mantle — **only storage chests** receive it.

```json
{
"loot": [
  { "name": "global-treasure", "filter": [{ "block": "minecraft:chest" }], "exact": false, "weight": 3 },
  { "name": "global-tools", "weight": 1 }
],
"vanillaLoot": [
  { "name": "minecraft:chests/simple_dungeon", "weight": 1 }
],
"overrideGlobalLoot": false
}
```

- `loot[].name` is a pack `loot/` key; `vanillaLoot[].name` is a vanilla or datapack loot-table key.
- Per chest: `exact: true` full block-data match beats material match beats no `filter`. One table picked by `weight`.
- `overrideGlobalLoot: true` suppresses dimension/region/biome loot for containers this placement matched.
- Unresolvable loot name logs `Couldn't find loot table <name>`.

Independent of this, a chest saved into the `.iob` with a vanilla loot table already on it keeps that table (`19 - Objects.md`).

## 6. Markers: entity spawns on placed objects

Placements have no direct entity field. `markers[]` tags matching blocks with a marker resource; the marker (`markers/`) carries `spawners[]`:

```json
{
"markers": [
  { "mark": [{ "block": "minecraft:mossy_cobblestone" }], "marker": "camp-spawns", "maximumMarkers": 4, "exact": false }
]
}
```

`markers/camp-spawns.json` references spawner resources; `emptyAbove` (default true) requires two air blocks above the marked block.

## 7. Surface support

Iris refuses surface objects that roof over, bridge, or overhang a carved opening. It rasterizes the object's lowest solid layer, dilates by `surfaceSupportBuffer`, and requires every column in that stencil to have `surfaceSupportDepth` blocks of un-carved, surface-solid ground. Failure rejects **with no log line** — common cause of "object never appears" near caves and canyon rims.

| Field | Where | Default | Meaning |
|---|---|---|---|
| `requireSurfaceSupport` | placement | `true` | Guard off for this placement. |
| `surfaceSupportBuffer` | placement | `2` (0..16) | Extra solid ring around footprint. |
| `surfaceSupportDepth` | placement | `2` (1..16) | Required un-carved ground thickness. |
| `requireObjectSurfaceSupport` | dimension | `true` | `false` disables guard pack-wide. |
| `objectSurfaceSupportBuffer` | dimension | `2` | Floor for every placement's buffer — can widen, never narrow. |

Skipped for: `force`, `fromBottom`, `mode: FLOATING`, `STRUCTURE_PIECE`, `underwater`, `onwater`, cave-anchored placements, and `requireSurfaceSupport: false`.

Escape hatches: `surfaceSupportBuffer: 0` → `surfaceSupportDepth: 1` → `requireSurfaceSupport: false` → `force: true` → dimension-wide off.

A related guard rejects surface placements resolving to y ≤ 1 in bedrock dimensions and **does** log (throttled): `Implausible object placement rejected`.

## 8. Worked examples

Rare surface camp:

```json
{
  "place": ["clutter/camp1"],
  "chance": 0.00175,
  "rotation": { "enabled": true, "yAxis": { "enabled": true, "min": 0, "max": 270, "interval": 90 } }
}
```

Trees with slope gate, warp, snow:

```json
{
  "place": ["trees/spruce/pine1", "trees/spruce/pine2", "trees/spruce/pine3"],
  "chance": 0.6,
  "density": 1,
  "slopeCondition": { "maximumSlope": 2 },
  "rotation": { "enabled": true, "yAxis": { "enabled": true, "interval": 90, "min": 0, "max": 270 } },
  "warp": { "style": "IRIS_DOUBLE", "exponent": 1.2, "zoom": 0.4 },
  "snow": 0.1
}
```

Ruins on stilts with loot:

```json
{
  "place": ["structures/ruin-small-a", "structures/ruin-small-b"],
  "chance": 0.08,
  "density": 2,
  "mode": "CENTER_STILT",
  "stiltSettings": { "yMax": 4, "yRand": 1, "overStilt": 1 },
  "translate": { "y": -1 },
  "slopeCondition": { "maximumSlope": 3 },
  "loot": [
    { "name": "global-treasure", "filter": [{ "block": "minecraft:chest" }], "weight": 2 },
    { "name": "global-clutter", "weight": 1 }
  ]
}
```

Cave floor clutter:

```json
{
  "chance": 0.21,
  "density": 6,
  "carvingSupport": "CARVING_ONLY",
  "caveAnchorMode": "PROFILE_DEFAULT",
  "translate": { "y": -1 },
  "place": ["clutter/stoneclutt4", "clutter/stoneclutt5"],
  "bottom": true,
  "force": true,
  "scale": { "size": 0.75 }
}
```

Ground-hugging carpet:

```json
{
  "chance": 0.15,
  "density": 2,
  "mode": "PAINT",
  "translate": { "y": -1 },
  "rotation": { "enabled": true, "yAxis": { "enabled": true, "min": 0, "max": 270, "interval": 90 } },
  "place": ["trees/mushroom/mushclut1", "trees/mushroom/mushclut2"]
}
```

## 9. Trees

When a sapling grows, Iris scans the biome's (then region's) `objects[]` for placements whose `trees[]` matches the grown tree type and sapling square size, picks one, and stamps it. A placement can serve both generation and sapling override. Procedural tree generation (`proceduralObjects`) is separate.

## 10. Troubleshooting

**Never appears**

1. Wrong key — `place` paths are case-sensitive. Missing keys skip the placement attempt and log a loader warning.
2. Chance too low — prove wiring with `"chance": 1, "density": 4`, then dial back.
3. Not actually in that biome — chunk-center sample decides.
4. Wrong list — `CARVING_ONLY` never places on surface; `SURFACE_ONLY` (default) never places in caves.
5. Surface support rejection (section 7) — if it appears with `force: true` but not without, a guard is the cause.
6. Underwater — submerged surface placement needs `underwater` / `onwater`.
7. `clamp` too tight — internal Y, not world Y.
8. `slopeCondition` too strict — `maximumSlope` below ~1 excludes most terrain.
9. Native structure veto — objects never place into native structure pieces, even with `force`.
10. Console: `Implausible object placement rejected` (bedrock y≤1); loot failures: `Couldn't find loot table`.

**Floats or clips**

- Floats off cliffs → `MAX_HEIGHT`; use a stilt mode or `VACUUM`.
- Buried → `MIN_HEIGHT` or too much negative `translate.y`.
- Floating in a cave → `ORGANIC_STILT` (floor) or `CEILING_HANG` (roof) with `carvingSupport: "CARVING_ONLY"`.
- Flat stilt disc → `VACUUM` / `VACUUM_ORGANIC` / `VACUUM_WAVY`.
- Rides on grass → `translate.y: -1` or `PAINT`.
- Interior filled with terrain → `bore` or `smartBore`.

**Loot not filling**

- Only storage chests receive placement loot.
- Console: `Couldn't find loot table`.
- `exact: true` with mismatched block data — drop to `exact: false`.

**Iterating quickly**

- Studio worlds hotload pack edits (JSON and `.iob`) within about a second — newly generated chunks only.
- Non-studio worlds do not hotload.
- Separate object vs placement: `/iris object paste` first.
- Deep forensics: write `chunkX,chunkZ[,radius]` into `plugins/Iris/goldendebug.txt` — logs every attempt, pick, and rejection for those chunks. Extremely verbose; use radius 0.

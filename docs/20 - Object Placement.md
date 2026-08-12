# 20 - Object Placement

An object placement is one entry in a biome's or region's `objects[]` array. It names the objects to stamp, how often to try, where they are allowed to land, and how they should meet the terrain. Building the objects themselves is `19 - Objects.md`; multi-piece assemblies are `21 - Jigsaw Structures.md`.

## Tutorial: get one object into the world

Prerequisites: a saved object such as `objects/tutorial/lookout.iob`, a biome or region the target dimension actually uses, and a Studio or disposable test world. Placements only affect chunks generated after the JSON existed, so every check below needs fresh terrain.

Merge this into one focused biome, keeping the biome's other fields:

```json
{
  "objects": [
    {
      "place": ["tutorial/lookout"],
      "chance": 1,
      "density": 1,
      "mode": "CENTER_HEIGHT",
      "rotation": { "enabled": false }
    }
  ]
}
```

1. Paste the object by hand first: `/iris object paste tutorial/lookout`. If the geometry or the origin is wrong, fix the object before you touch placement JSON.
2. Validate the pack: `/iris pack validate pack=<pack>` on Bukkit, `/iris pack validate <pack>` on a modded loader.
3. Open or hotload Studio and fly into fresh chunks whose center column uses the edited biome. At `chance: 1` you get one in nearly every such chunk.
4. Confirm ownership: run `/iris find object tutorial/lookout`, or get `/iris object dust` and right-click a placed block. Iris names the placement that owns that block.
5. Pick the terrain mode that fixes what you see: `PAINT` for ground-hugging clutter, a stilt mode for support over uneven ground, `CEILING_HANG` for cave roofs, a `VACUUM` mode to pull the terrain up to a flat base.
6. Test the negative cases: steep slopes, water, cave mouths, and the neighbouring biomes where the object should not appear at all.
7. Drop `chance` and `density` to production values, validate again, generate one more fresh area.

The placement is done when a manual paste and natural generation agree on orientation, the object sits on the ground the way you want, and it stays absent outside its configured scope.

If validation cannot resolve the object, the `place` key does not match the path under `objects/`. If nothing generates, work section 10 top to bottom. If a non-Studio world still generates the old placement, it is reading its own pack snapshot — see `18 - Structures Overview.md`.

## 1. Which files carry placements

`objects[]` exists on exactly two resource types:

- **Biome** (`biomes/**.json`) — wherever that biome generates.
- **Region** (`regions/**.json`) — every biome in the region.

**A dimension has no `objects[]`.** Adding one to a dimension file does nothing. Dimensions do carry two knobs that tighten every placement underneath them (`requireObjectSurfaceSupport`, `objectSurfaceSupportBuffer`, section 4) and two that gate the inverted upper dimension (`upperDimensionObjects`, `upperObjectsForcePlace`), but no placements of their own.

Per chunk, Iris samples the biome and region once at the chunk center (block 8,8), then walks four lists in this fixed order: biome surface, biome cave, region surface, region cave. Every entry rolls independently, so a biome entry and a region entry can both fire in the same chunk. A biome that only clips the corner of a chunk contributes nothing there, and the center biome's objects can spill a few blocks into its neighbours.

A whole placement can be replaced by a snippet reference — `"objects": ["snippet/object-placer/my-camp"]` loads `snippet/object-placer/my-camp.json`.

Minimal entry:

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

`place` entries are object keys: the path under `objects/` without `.iob`.

## 2. How often does it spawn?

Two numbers do the work. `chance` decides whether the entry runs at all in this chunk; `density` decides how many stamps it then attempts.

```json
{
  "place": ["clutter/boulder1", "clutter/boulder2"],
  "chance": 0.05,
  "density": 3
}
```

Roughly `chance x density` objects per chunk, before rejections. `chance: 0.002` is about one chunk in five hundred.

Each of the `density` attempts independently picks a random object out of `place` and a random column inside the chunk, then runs every gate in sections 3 to 5. Density is a budget of tries, not a guarantee of placements.

`place` is unweighted — a uniform pick. To bias one object, list its key more than once or split the entry in two. A key that does not resolve costs the attempt and logs `Couldn't find Object: <key>` once per lookup.

Surface entries add uniform `+/- 0.005` jitter to `chance` before rolling; cave entries roll the raw value. Two consequences, each worth about one chunk in eight hundred: `chance: 1` occasionally fails, and `chance: 0` occasionally succeeds. Omit an entry to disable it; do not set it to zero.

`densityStyle` replaces `density` with a noise-driven count, so density varies across the world instead of being flat:

```json
{
  "densityStyle": { "min": 1, "max": 4, "style": { "style": "IRIS_THICK", "zoom": 8 } }
}
```

Always set `min` and `max`. The class defaults are 16 and 32, so a bare `"densityStyle": {}` asks for 16 to 32 attempts in every chunk that passes the chance roll.

## 3. Where is it allowed to land?

By default a placement is surface-only, unlimited in height, unlimited in slope, and refuses to sit over a cave mouth. Each field below narrows one of those.

**Surface or cave.** `carvingSupport` sorts the entry into the surface list, the cave list, or both.

```json
{ "carvingSupport": "CARVING_ONLY", "caveAnchorMode": "FLOOR" }
```

`SURFACE_ONLY` (the default) rejects any anchor that lands in carved space. `CARVING_ONLY` requires carved space at the anchor or within three blocks below it, and the engine hunts for an anchor Y inside the cave column instead of using the terrain surface. `ANYWHERE` sits in both lists, which means it rolls `chance` twice per chunk — once for the surface pass and once for the cave pass. Cave passes resolve their biome by sampling 48, 80, and 112 blocks below the surface and taking the deepest sample that differs from the surface biome and has carving objects; if none does, the surface biome is used.

`caveAnchorMode` picks which carved cells count: `FLOOR` needs solid support below, `CEILING` needs solid above, `CENTER` needs neither, `ANY` takes anything carved, and `PROFILE_DEFAULT` defers to the cave profile (`15 - Caves & Carving.md`). `CEILING_HANG` overrides this to `CEILING` regardless of what you wrote.

**Height band.** `clamp` rejects placements whose resolved top or bottom leaves the band.

```json
{ "clamp": { "minimumHeight": 40, "maximumHeight": 225 } }
```

These are engine-internal Y values — 0 to (dimension height), not world Y. In a `-64..320` dimension, world Y 0 is internal Y 64.

**Slope.** `slopeCondition` gates on the terrain slope sampled around the anchor column.

```json
{ "slopeCondition": { "minimumSlope": 0, "maximumSlope": 2 } }
```

The default (`0` to `10`) is treated as "no condition" and skips the check entirely. `maximumSlope: 2` keeps buildings off hillsides; going below about 1 excludes nearly all natural terrain. `rotateTowardsSlope: true` finds whichever of the four footprint edges sits lowest and rotates the object to face that way, in 90 degree steps, adding the `yAxis` `min` — it discards `yAxis` `max` and `interval`.

**Neighbours.** `forbiddenCollisions` lists object keys this object refuses to intersect; if any block of an already-placed object with that key falls inside this object's bounding box, the attempt is dropped. `allowedCollisions` names exceptions that win over the forbidden list. Both are empty by default, and the check only runs when at least one of them is non-empty.

**Overrides.** `forcePlace: true` (JSON also accepts `"force"`) skips the slope gate, the carving-anchor check, surface support, the underwater rejection, the fluid-height and cave-height checks, `clamp`, the bedrock guard, and the collision lists. It does **not** skip the native-structure veto: an object whose blocks would land inside a vanilla or datapack structure piece is always rejected, forced or not.

## 4. Surface support: the silent rejection

Iris refuses surface objects that roof over, bridge, or overhang a carved opening. It takes the object's lowest solid non-foliage layer, rasterizes those columns, dilates the stencil by `surfaceSupportBuffer`, and requires every column in the result to have `surfaceSupportDepth` blocks of un-carved, surface-solid ground. A failure drops the placement with **no log line at all**, which makes it the usual cause of "my object never appears" near caves, canyon rims, and ravines.

```json
{ "requireSurfaceSupport": true, "surfaceSupportBuffer": 2, "surfaceSupportDepth": 2 }
```

The dimension acts as a floor, not a ceiling: the effective buffer is `max(placement, dimension)` and the guard is on only if both the placement and the dimension ask for it. A dimension can therefore widen the ring or force the guard on pack-wide, never loosen it. `surfaceSupportDepth` is not merged — the placement value is used as written.

The check is skipped entirely for `forcePlace`, `fromBottom`, `mode: FLOATING`, `mode: STRUCTURE_PIECE`, `underwater`, `onwater`, cave-anchored placements, and `requireSurfaceSupport: false`.

Loosen in this order, stopping as soon as the object appears: `surfaceSupportBuffer: 0`, then `surfaceSupportDepth: 1`, then `requireSurfaceSupport: false`, then `force: true`, and only then the dimension-wide switch.

A second guard rejects surface-anchored placements that resolve to y <= 1 in a bedrock dimension. That one does log, throttled to one line per object and mode every five seconds: `Implausible object placement rejected`.

## 5. How does it sit on the terrain?

`mode` decides how the object's Y is chosen and whether the terrain moves to meet it. The default, `CENTER_HEIGHT`, takes a single height sample at the anchor column and uses it for the whole object — cheap, and fine for anything small or anything on flat ground.

**Height sampling.** These four modes only change which sample wins.

```json
{ "mode": "MAX_HEIGHT" }
```

`MAX_HEIGHT` samples every column in the footprint and takes the highest, so nothing gets buried but the object floats off cliffs. `MIN_HEIGHT` takes the lowest, so nothing overhangs but slopes swallow it. The `FAST_` variants sample four points instead of the full footprint. `PAINT` is the outlier: it drops each column of the object to that column's own surface height, melting the object over the terrain rather than placing it as a rigid block. Vines are exempt so they keep hanging.

**Stilts.** Stilt modes take a height mode, then repeat the object's bottom blocks downward until they hit ground.

```json
{
  "mode": "CENTER_STILT",
  "stiltSettings": { "yMax": 4, "yRand": 1, "overStilt": 1 }
}
```

`STILT` is `MAX_HEIGHT` plus columns, `MIN_STILT` is `MIN_HEIGHT` plus columns, `CENTER_STILT` is `CENTER_HEIGHT` plus columns and is the cheapest one worth using. The `FAST_` variants are cheaper and less accurate, so pair them with `overStilt` to drive the legs further under the surface. `ERODE_STILT` tapers the legs like a cone — deepest at the footprint centroid, dropping off toward the edges, with the lower portion randomly broken up.

Only occluding blocks stilt, and stairs, slabs, and dirt paths are excluded. Grass, mycelium, podzol, and dirt-path bottoms are substituted with dirt so you do not get grass columns; a `palette` overrides the column material entirely. A column stops as soon as it hits a fluid, so stilts never punch through a lake floor.

`ORGANIC_STILT` and `CEILING_HANG` are for caves. `ORGANIC_STILT` scans down to the first solid block and fills the gap with noise-varied roots; `CEILING_HANG` flips the object vertically, anchors its top to the roof, and grows the same organic column up into the ceiling. Both read `organicMaxScan`, `organicJitter`, and `organicScratch`.

**Terrain shaping.** The vacuum modes bend the terrain instead of extending the object.

```json
{
  "mode": "VACUUM_ORGANIC",
  "vacuumSettings": { "radius": 14, "falloff": 2.0, "organicJitter": 4 }
}
```

They anchor like `CENTER_HEIGHT`, then raise or carve every column out to a radius so the surface meets the object's lowest placed block, easing off by `falloff` (1 is a cone, 2 a parabolic bowl, higher stays flat near the object then drops). Raised columns are filled with the biome's rock; lowered columns are cleared to air, and inside the footprint the carve never eats into the object. `VACUUM` uses radius 12, `VACUUM_HIGH` 20, `VACUUM_FAST` 8 with every other column sampled. `VACUUM_ORGANIC` jitters the radius per column for a ragged edge; `VACUUM_WAVY` adds a smooth simplex wave that fades to zero under the object and at the rim.

**Two cases where Iris overrides your `mode`.** An object whose key contains `imports/` — anything brought in by `/iris structure import` or `/iris studio importvanilla` — is forced to `FAST_MIN_STILT` unless you asked for `FLOATING` or `STRUCTURE_PIECE`. And a cave placement left at the default `CENTER_HEIGHT` takes the active cave profile's `defaultObjectPlaceMode` instead, if the profile sets one. Write any other mode and it is honoured as-is.

**Special.** `FLOATING` ignores terrain entirely: Y comes from the rotated object center plus `translate.y` and `translate.yRandom`, and the terrain, water, cave-anchor, and surface-support checks are all skipped. Use it for sky islands and anything that must not fall to the ground. `STRUCTURE_PIECE` is a raw stamp at caller-supplied coordinates used internally for native structure pieces — do not write it into `objects[]`.

**Fine positioning.** `translate` shifts the object after rotation, so the offsets rotate with it.

```json
{ "translate": { "x": 0, "y": -1, "z": 0, "yRandom": 0 } }
```

`translate.y: -1` seats clutter one block into the ground and is the standard fix for objects riding on top of grass. `yRandom` adds a random `0..yRandom` per placement; a negative value randomizes downward instead.

`rotation` is on by default, and its default is exactly what most packs want: free Y spin in 90 degree steps.

```json
{ "rotation": { "enabled": true, "yAxis": { "enabled": true, "min": 0, "max": 270, "interval": 90 } } }
```

Per axis (`xAxis`, `yAxis`, `zAxis`, each `{enabled, min, max, interval}`): `min == max == 0` means any multiple of `interval`; `min == max` at some other value locks the object to that angle; anything else picks a multiple of `interval` and clips it into `[min, max]`. In the free case an `interval` below 1 is treated as 1 (one-degree steps); in a clipped range always set a real `interval`. Non-90-degree angles look bad at block resolution. Turn rotation off with `"rotation": { "enabled": false }`. X and Z rotation are incompatible with `bottom: true`.

`scale` is inert until you ask for something other than 1.

```json
{ "scale": { "size": 1, "minimumScale": 0.75, "maximumScale": 1.25, "variations": 7, "interpolation": "TRILINEAR" } }
```

`size` is a fixed multiplier and overrides the range when it is not 1. With `size: 1` and a min/max spread, Iris pre-builds `variations` evenly spaced copies across the range and picks one per placement — the copies are cached and shared, so a large `variations` costs memory. `interpolation` only matters when scaling up: `NONE` gives blocky output, `TRILINEAR` smooths it, `TRICUBIC` and `TRIHERMITE` are smoother and much slower.

`heightmap` replaces terrain height sampling with a noise generator, so the object seats against a virtual surface. Surface support still samples the real terrain.

## 6. Water, snow, and air pockets

**Water.** A surface placement whose anchor column is submerged is rejected unless you opt in.

```json
{ "underwater": true, "waterloggable": true, "isDolphinTarget": true }
```

`underwater: true` places on the terrain floor and ignores the water surface — and additionally rejects the placement if the resolved Y is at or above the fluid height, so it really is an underwater-only switch. `onwater: true` places on the fluid surface instead, for boats and docks. `waterloggable: true` waterlogs any placed block that can be waterlogged and lands in water; `underwater` implies the same behavior. `isDolphinTarget: true` combined with `underwater` marks placed storage chests as buried-treasure points of interest so dolphins swim players to them.

**Snow.** `snow` caps the snow layer depth dripped over the top of every column the object writes.

```json
{ "snow": 0.5 }
```

The value scales to vanilla's eight layers: each column gets a random count from 0 to `floor(snow * 7)`, placed one block above the object's highest block in that column. Small values are effectively fixed — `snow: 0.1` is always a single layer.

**Air pockets and interiors.** By default the object only writes its own blocks, so terrain left standing inside a hollow object stays there.

```json
{ "bore": true, "boreExtendMaxY": 4, "boreExtendMinY": 0 }
```

`bore: true` clears the whole bounding cuboid to air before the object writes, which is blunt but predictable; `boreExtendMaxY` and `boreExtendMinY` grow that box upward and downward. `smartBore: true` instead raytraces the volume on three axes and fills only the enclosed interior, so a house keeps its rooms clear without erasing the trees around it. Smart boring is a one-time cost per object at load, not per placement.

`meld: true` inverts the rule — the object only writes where a solid block already exists, which carves the object into terrain rather than adding to it. It is expensive; the placer samples the world per block.

`edit` rewrites materials at placement time, so one saved object can serve several biomes:

```json
{
  "edit": [
    { "find": [{ "block": "minecraft:oak_planks" }], "replace": { "palette": ["minecraft:spruce_planks"] }, "chance": 1, "exact": false }
  ]
}
```

`exact: false` matches on material alone; `exact: true` requires a full block-data match. When the replacement resolves to the *same* material as the block it matched, Iris merges the two block states rather than overwriting, so facing and other properties survive; a different material replaces outright. `chance` is rolled once per rule per block.

`warp` displaces each block's X and Z through a noise field, so a rigid object ripples like a flag. The displacement range is `+/- multiplier / 2` and is truncated to whole blocks, so the default `multiplier: 1` gives at most a one-block jitter — raise `multiplier` for a warp you can actually see.

```json
{ "warp": { "style": "IRIS_DOUBLE", "zoom": 0.4, "multiplier": 6 } }
```

## 7. Loot, markers, and saplings

**Loot.** Two arrays attach loot tables to container blocks inside the placed object. Only storage chests receive them.

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

`loot[].name` is a key under the pack's `loot/` folder; `vanillaLoot[].name` is a vanilla or datapack loot-table key. For each chest, an `exact: true` full block-data match wins over a material match, which wins over an entry with no `filter` at all; among the survivors one table is picked by `weight`. `overrideGlobalLoot: true` makes the placement's table the only one, suppressing dimension, region, and biome loot for the containers it matched. An unresolvable name logs `Couldn't find loot table <name>` and is skipped.

Iris fills these chests during the post-generation chunk update pass, reading the placement recorded in the mantle at that block — not when a player opens the chest. Separately, a chest saved into the `.iob` with a vanilla loot table already on it keeps that table (`19 - Objects.md`).

**Markers.** Placements have no entity field. `markers[]` tags matching blocks with a marker resource, and the marker carries the spawners.

```json
{
  "markers": [
    { "mark": [{ "block": "minecraft:mossy_cobblestone" }], "marker": "camp-spawns", "maximumMarkers": 4, "exact": false }
  ]
}
```

Candidate blocks are shuffled, so which ones get marked varies per placement, and `maximumMarkers` (default 8, hard max 16) caps the count. `markers/camp-spawns.json` lists the spawner resources; its `emptyAbove` (default true) requires two air blocks above the marked block inside the object.

**Saplings.** When a sapling grows, Iris scans the biome's `objects[]` for placements whose `trees[]` matches the grown tree type and the sapling square size, falls back to the region's list if the biome had no match, picks one, and stamps it. A single placement can serve both natural generation and sapling override. Procedural tree generation (`proceduralObjects`) is a separate system — see `17 - Trees, Fungi, Coral, Crystals, Formations, Ruins.md`.

## 8. Reference

### Frequency

| Field | Default | Effect |
|---|---|---|
| `place` | required | Object keys to stamp; one is picked at random per attempt, with no weighting |
| `chance` | `1` | Probability that the entry runs in a given chunk; surface entries jitter by `+/- 0.005`, cave entries do not |
| `density` | `1` | Stamp attempts once the entry runs, each at its own random column with its own random object |
| `densityStyle` | unset | Noise-driven attempt count that supersedes `density`; class defaults are 16 to 32 |

### Scope and rejection

| Field | Default | Effect |
|---|---|---|
| `carvingSupport` | `SURFACE_ONLY` | Whether the entry runs on the terrain surface, inside carved space, or in both passes |
| `caveAnchorMode` | `PROFILE_DEFAULT` | Which carved cells qualify: floor, ceiling, open middle, anything, or whatever the cave profile says |
| `clamp` | `-2048` / `2048` | Height band the resolved object must fit inside, in engine-internal Y |
| `slopeCondition` | `0` / `10` | Terrain steepness window; the default pair disables the check outright |
| `forbiddenCollisions` | `[]` | Object keys already in the world that veto this placement when they fall inside its bounding box |
| `allowedCollisions` | `[]` | Keys exempted from the veto above |
| `requireSurfaceSupport` | `true` | Whether to refuse to bridge or roof a carved opening |
| `surfaceSupportBuffer` | `2` (0..16) | How far past the footprint the ground must stay solid and un-carved |
| `surfaceSupportDepth` | `2` (1..16) | How thick that un-carved ground must be under each column |
| `forcePlace` | `false` | Skips every gate above plus water and bedrock guards; never skips the native-structure veto |
| `heightmap` | unset | Substitutes a noise field for terrain height when resolving Y |

### Terrain fit

| Field | Default | Effect |
|---|---|---|
| `mode` | `CENTER_HEIGHT` | How Y is chosen and whether the terrain moves — see section 5 |
| `translate` | `0,0,0` | Post-rotation offset; `y: -1` is the usual fix for clutter riding on grass |
| `translate.yRandom` | `0` | Random vertical spread per placement, downward if negative |
| `rotation` | Y free, 90 degree steps | Random orientation per placement |
| `rotateTowardsSlope` | `false` | Turns the object to face downhill, in 90 degree steps |
| `scale` | `1` | Resizes the object; a min/max spread pre-builds `variations` cached copies |
| `bottom` | `false` | On explicit-Y paths (cave, structure, sapling), seats the object bottom-up instead of centered |
| `fromBottom` | `false` | Anchors near the world floor; an unfinished code path, avoid it |
| `warp` | flat | Noise displacement of each block's X and Z; inert until `multiplier` is raised |

### Terrain interaction

| Field | Default | Effect |
|---|---|---|
| `bore` | `false` | Empties the whole bounding cuboid before writing |
| `boreExtendMaxY` / `boreExtendMinY` | `0` | Grows that emptied box upward and downward |
| `smartBore` | `false` | Raytraces the object once at load and clears only its enclosed interior |
| `meld` | `false` | Writes only where solid terrain already exists, carving the object in rather than adding it |
| `edit` | `[]` | Material find-and-replace applied as the object is written |
| `snow` | `0` | Depth cap for a snow dusting laid over the object's top blocks |
| `underwater` | `false` | Ignores the water surface and seats on the sea floor; also refuses to place above fluid height |
| `onwater` | `false` | Seats on the fluid surface instead of the floor |
| `waterloggable` | `false` | Waterlogs placed blocks that end up in water |
| `isDolphinTarget` | `false` | With `underwater`, registers placed chests as buried treasure for dolphins |

### `stiltSettings`

| Field | Default | Effect |
|---|---|---|
| `yMax` | `0` | Caps how far a leg extends before the random and over-stilt terms apply |
| `yRand` | `0` | Random extra leg length per column |
| `overStilt` | `0` | Pushes every leg this much further under the surface; use it with the `FAST_` modes |
| `palette` | unset | Material for the legs; without it Iris repeats the object's bottom block and swaps grass-family blocks for dirt |
| `organicMaxScan` | `48` | How far `ORGANIC_STILT` and `CEILING_HANG` search for solid rock before giving up |
| `organicJitter` | `3` | Random shortening per column, so the underside is ragged instead of a flat disc |
| `organicScratch` | `0.55` | Fraction of the deepest part randomly punched out for a broken, rooty tip |

### `vacuumSettings`

| Field | Default | Effect |
|---|---|---|
| `radius` | `0` (mode default: 12 / 20 high / 8 fast) | How far past the footprint the terrain is bent before returning to its natural height |
| `falloff` | `2.0` | Shape of that blend: 1 is a straight cone, 2 a gentle bowl, higher stays flat near the object then drops sharply |
| `organicJitter` | `4` | Per-column wobble in the meeting edge, for `VACUUM_ORGANIC` |
| `waveAmplitude` | `3` | Height of the rolling wave in `VACUUM_WAVY`; 0 removes it |
| `waveScale` | `5.0` | Tightness of that wave; wavelength is roughly `100 / waveScale` blocks |

### `mode` values

| Mode | In-world result |
|---|---|
| `CENTER_HEIGHT` | One height sample under the middle carries the whole object; cheap, and correct on flat ground |
| `MAX_HEIGHT` / `FAST_MAX_HEIGHT` | Nothing gets buried, but the object hangs off the downhill side of cliffs |
| `MIN_HEIGHT` / `FAST_MIN_HEIGHT` | Nothing overhangs, but slopes swallow the uphill side |
| `PAINT` | Every column drops to its own surface, so the object melts over whatever it lands on |
| `STILT` / `FAST_STILT` | Highest-point seating with legs dropped to the ground under every bottom block |
| `MIN_STILT` / `FAST_MIN_STILT` | Lowest-point seating with the same legs |
| `CENTER_STILT` | Center seating with legs; the cheapest stilt worth using |
| `ERODE_STILT` | Legs taper away from the centroid and break up near the tips, like eroded rock |
| `ORGANIC_STILT` | Legs grow down to the first solid block with noise-varied lengths, connecting cave-floor objects to the floor |
| `CEILING_HANG` | Object is flipped, hung from the cave roof, and rooted upward into it |
| `VACUUM` | Terrain around the object is pulled up or cut down to meet its base, easing back out to radius 12 |
| `VACUUM_HIGH` | Same, blended out to radius 20 for the smoothest transition |
| `VACUUM_FAST` | Same at radius 8 with every other column sampled, for bulk use |
| `VACUUM_ORGANIC` | Same with a per-column jittered edge, so the bowl reads as natural ground |
| `VACUUM_WAVY` | Same with a smooth wave rolling across the slope, flat under the object and at the rim |
| `FLOATING` | Placed in mid-air at an absolute height; terrain, water, cave, and support checks all skipped |
| `STRUCTURE_PIECE` | Raw stamp at caller coordinates; internal to native structure routing, not for `objects[]` |

## 9. Worked examples

Rare surface camp:

```json
{
  "place": ["clutter/camp1"],
  "chance": 0.00175,
  "rotation": { "enabled": true, "yAxis": { "enabled": true, "min": 0, "max": 270, "interval": 90 } }
}
```

Pine forest, kept off the steep ground and lightly dusted:

```json
{
  "place": ["trees/spruce/pine1", "trees/spruce/pine2", "trees/spruce/pine3"],
  "chance": 0.6,
  "density": 1,
  "slopeCondition": { "maximumSlope": 2 },
  "rotation": { "enabled": true, "yAxis": { "enabled": true, "interval": 90, "min": 0, "max": 270 } },
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
  "place": ["clutter/stoneclutt4", "clutter/stoneclutt5"],
  "chance": 0.21,
  "density": 6,
  "carvingSupport": "CARVING_ONLY",
  "caveAnchorMode": "PROFILE_DEFAULT",
  "translate": { "y": -1 },
  "bottom": true,
  "force": true,
  "scale": { "size": 0.75 }
}
```

Ground-hugging mushroom carpet:

```json
{
  "place": ["trees/mushroom/mushclut1", "trees/mushroom/mushclut2"],
  "chance": 0.15,
  "density": 2,
  "mode": "PAINT",
  "translate": { "y": -1 },
  "rotation": { "enabled": true, "yAxis": { "enabled": true, "min": 0, "max": 270, "interval": 90 } }
}
```

## 10. Troubleshooting

**It never appears.** Work these in order.

1. Wrong key. `place` paths are case-sensitive and relative to `objects/`. A miss logs `Couldn't find Object: <key>` and burns the attempt.
2. Chance too low to see. Prove the wiring with `"chance": 1, "density": 4` first, then dial back.
3. Not actually in that biome. The chunk-center sample decides, not what you are standing on.
4. Wrong list. `CARVING_ONLY` never places on the surface; the default `SURFACE_ONLY` never places in caves.
5. Surface support (section 4). This one is silent. If the object appears with `force: true` but not without, a guard rejected it, and surface support is the likely one.
6. Water. A submerged surface placement needs `underwater` or `onwater`.
7. `clamp` too tight, or written in world Y instead of engine-internal Y.
8. `slopeCondition` too strict — `maximumSlope` under about 1 excludes most terrain.
9. Native structure overlap. Objects never write into a vanilla or datapack structure piece, not even with `force`.
10. Console lines worth grepping: `Implausible object placement rejected` (bedrock-row anchor) and `Couldn't find loot table`.

**It appears but sits wrong.**

- Floating off a cliff: you are on `MAX_HEIGHT`. Switch to a stilt mode or a `VACUUM` mode.
- Buried in a hillside: `MIN_HEIGHT`, or too much negative `translate.y`.
- Hovering in a cave: `ORGANIC_STILT` for the floor or `CEILING_HANG` for the roof, with `carvingSupport: "CARVING_ONLY"`.
- Standing on a flat disc of stilt blocks: use `VACUUM`, `VACUUM_ORGANIC`, or `VACUUM_WAVY` instead.
- Riding one block above the grass: `translate.y: -1`, or `PAINT` for clutter.
- Interior packed with terrain: `bore` for a blunt clear, `smartBore` to keep only the rooms.
- `warp` does nothing: raise `warp.multiplier`; at the default of 1 the displacement truncates to roughly nothing.

**Loot never fills.** Only storage chests receive placement loot. Check the console for `Couldn't find loot table`, and drop `exact: true` if the filter's block data does not match the saved block exactly.

**Iterating quickly.**

- Studio worlds hotload both JSON and `.iob` edits within about a second, into newly generated chunks only. Non-studio worlds do not hotload at all.
- Separate the object from the placement: `/iris object paste` proves geometry, natural generation proves the placement.
- Deep forensics: write `chunkX,chunkZ[,radius]` into `plugins/Iris/goldendebug.txt` (or set `-Diris.goldendebug=`), then restart — the target is read once at startup. Every attempt, height query, pick, and rejection in those chunks is logged. Extremely verbose; use radius 0.

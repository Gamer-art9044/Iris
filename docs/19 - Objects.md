# 19 - Objects

An Iris object is a sparse voxel volume (block states plus block-entity data) stored as `.iob` under a pack's `objects/` folder. This guide covers creating, importing, and editing objects. Generation wiring is `20 - Object Placement.md`; jigsaw pieces are `21 - Jigsaw Structures.md`.

## 1. What an object is

An object stores bounding box (`w × h × d`), a sparse block map, and a sparse tile-data map. Origin is always the **center** of the bounding box (`w/2, h/2, d/2`, integer division) — derived from dimensions, never stored, recomputed on load.

Stored:

- Every block except plain `minecraft:air`. `cave_air` and `void_air` **are** stored (use them to author carve-outs that clear terrain when placed).
- Full block-entity NBT for tiles: chest inventories, sign text, spawners, furnaces, banners, decorated pots. A chest with a vanilla loot table keeps the table key and loot seed.

Not stored:

- **Entities** (armor stands, item frames, paintings, mobs) — dropped on save. Runtime entity spawns use placement markers (`20 - Object Placement.md`).
- **Biomes.**
- Jigsaw blocks, structure blocks, and structure voids — stripped when the `.iob` is **read**. Iris jigsaw connectors are JSON metadata, not blocks (`21 - Jigsaw Structures.md`).

### Format limits

`.iob` V2 uses short-typed centered coordinates (±32,767 per axis) and a short-counted palette capped at 32,767 distinct block states. Pre-V2 files still load via a legacy reader. No explicit size cap on save; practical limits are memory and wand scan budget (~30 ms/tick).

### Where objects live

```
plugins/Iris/packs/<pack>/objects/**/*.iob
```

Object key = path relative to `objects/`, `/`-separated, no extension:

```
objects/light.iob                  ->  "light"
objects/trees/oak/big_oak_1.iob    ->  "trees/oak/big_oak_1"
```

Keys resolve within the current pack. Outside an Iris world, lookups scan every visible pack — bare keys shared across packs are ambiguous; use distinctive subfolder paths.

## 2. Making objects in-game

### 2.1 Build surfaces

- **Pack studio** — world generated from your pack: `/iris studio open <dimension> [seed=1337]`. Close with `/iris studio close`.
- **Object studio** — flat gallery with every object in the pack on a grid and click-to-save editing (2.6): `/iris object studio [dimension=<dim>] [seed=1337]`. Omitting `dimension` aggregates objects from every visible pack.

You can also build in any flat world. `/iris object save` resolves `dimension` automatically inside an Iris world; outside one, pass `dimension=<pack>`.

### 2.2 The wand

```
/iris object wand
```

Blaze Rod named "Wand of Iris". Selection corners live on the item; dropping or replacing the wand loses the selection. Two wands are two independent selections.

| Action | Effect |
|---|---|
| **Left click** a block | Set corner 1 |
| **Right click** a block | Set corner 2 |

Main hand only; clicks cancel so you do not break or place. Selection particles draw when corners are within 64 blocks of each other. Setting a corner in a different world clears the other corner.

**WorldEdit interop.** With `worldEditWandCUI` true (default in settings), a WorldEdit selection is accepted anywhere Iris wants a selection. `/iris object we` converts the current WorldEdit selection into an Iris wand. `position2` does not work with a WorldEdit-only selection — run `/iris object we` first.

### 2.3 Adjusting the selection

| Command | Alias | Effect |
|---|---|---|
| `/iris object position1 [here=true]` | `p1` | Corner to feet (`here=true`) or look-at block (`here=false`) |
| `/iris object position2 [here=true]` | `p2` | Other corner |
| `/iris object shift [amount=1]` | | Move whole selection opposite facing |
| `/iris object contract [amount=1]` | `-` | Inset on the face you look at |
| `/iris object expand [amount]` | modded only | Expand the face you look at |
| `/iris object x&y` | | Expand up **and** down until clear, then contract sides inward |
| `/iris object x+y` | | Expand up only, then contract sides |

`x&y` / `x+y` wrap a build tightly: rough-select the base, then run one of them.

### 2.4 Saving

```
/iris object save [dimension=<pack>] <name> [overwrite=false] [legacy=true]
```

- `name` is required and positional — path under `objects/`, `/` allowed for subfolders.
- `dimension` resolves from the Iris world; pass `dimension=<pack>` otherwise.
- Without `overwrite=true` (alias `force=true`), an existing file aborts. No backup on overwrite.
- Saved volume is the **full selection box** — no shrinkwrap. Deliberate air padding shifts the center.

File path: `plugins/Iris/packs/<dimension load key>/objects/<name>.iob`.

**Footgun:** the target pack folder is the **dimension's load key**, not necessarily the pack folder the dimension came from. A pack in `packs/mypack/` with `dimensions/overworld.json` saves objects into `packs/overworld/` if that load key differs. Keep the dimension JSON filename equal to the pack folder name.

Example:

```
/iris object wand
# left-click one corner, right-click the other
/iris object x+y
/iris object save trees/birch/tall_birch_1
# -> plugins/Iris/packs/<dimension load key>/objects/trees/birch/tall_birch_1.iob
```

### 2.5 Pasting, previewing, editing

```
/iris object paste <object> [edit=false] [rotate=0] [scale=1]
```

Pastes at the block you look at. `rotate` is Y degrees; `scale` rescales with tricubic interpolation (clamped for large objects). Pastes are undoable:

```
/iris object undo [amount=1]        (alias: u — reverts pastes, not hand edits)
```

**Edit existing:** `paste` with `edit=true` hands a wand fitted to the pasted bounds; modify, then re-save with `overwrite=true`.

Inspection and maintenance:

- `/iris object analyze <object>` — dimensions, block count, top materials. Read-only.
- `/iris object shrink <object>` — shrinkwraps and **overwrites in place**, no confirmation. Re-centers; deliberate off-center padding shifts.
- `/iris object plausibilize <target> [dryrun=false] [reach=12]` — tree-specific: organic branch connections so leaves survive vanilla decay. `target` accepts a key, a `prefix/` ending in `/`, or a path.
- `/iris object dust` (alias `d`) — "Dust of Revealing". Right-click a block in an Iris world to highlight the placement that owns it.
- `/iris find object <object> [teleport=true]` (also `/iris goto object`) — teleports to the grid cell in object studio, or locates a generated instance in a normal Iris world.

### 2.6 Object studio: click-to-save

Inside `/iris object studio`, left- or right-clicking a block in a grid cell writes that cell back to its `.iob`:

- Saved volume is the cell's original bounding box (center preserved; no shrinkwrap).
- Content hash per cell; no-op if unchanged ("no changes").
- Aborts if any covering chunk is unloaded — walk closer and click again.

## 3. Importing `.schem` files

### 3.1 `/iris object convert`

```
/iris object convert
```

1. Drop `.schem` files into `plugins/Iris/convert/` (created on first use).
2. Run the command. Each `foo.schem` becomes `foo.iob` **in that folder**, shrinkwrapped.
3. Move the `.iob` into a pack yourself — convert never writes into a pack.

Support and losses:

- **Sponge Schematic v2 and v3 only.** MCEdit `.schematic`, `.litematic`, and Sponge v1 are rejected. No WorldEdit/FAWE required — Iris parses NBT itself.
- Block palette and indices only. **Block entities, entities, and biomes are not carried**: chests empty, signs blank, spawners default.
- Source `.schem` is **deleted** after successful conversion.
- Empty folder or wrong extension produces no output.

### 3.2 High-fidelity path: paste, then wand

For schematics with chests, signs, or spawners:

1. Open a build world (`/iris studio open <pack>` or flat world).
2. `//schem load <name>` and `//paste` with WorldEdit/FAWE.
3. Keep the WorldEdit selection (accepted directly) or `/iris object we`, or select with the wand.
4. Tighten with `/iris object x+y` or `contract`.
5. `/iris object save <name> [overwrite=true]`.

Wand save reads live blocks with full block-entity NBT. This is the only import route that preserves block entities.

### 3.3 Vanilla `.nbt` templates

- `/iris structure import <dimension>` — imports registered structures and templates into the pack as objects plus jigsaw graphs.
- `/iris studio importvanilla <dimension> [variants=3] [structures=true]` — imports vanilla trees/features under `objects/vanilla/`, plus structure passes when `structures=true`.

Details: `22 - Native Structures & Datapacks.md`.

## 4. Practical details

**Center and anchoring.** Center is always bounding-box middle. Wand saves keep selection box (asymmetric padding survives); `convert` and `shrink` shrinkwrap and re-center. At placement, the object is centered on the column in X/Z and lifted so its bottom sits on the anchor; beyond that, placement `mode` applies (`20 - Object Placement.md`).

**Rotation is runtime, never baked.** A `.iob` stores one orientation; rotation ranges live on the placement. `paste rotate=90` rotates a throwaway copy.

**Loot: two mechanisms.** A chest saved with a vanilla loot table keeps that table inside the `.iob`. Pack loot tables attach at placement (`loot`, `vanillaLoot`, `overrideGlobalLoot`) — `20 - Object Placement.md`.

**Stale caches.** Objects are cached per pack. Non-studio worlds may not pick up on-disk `.iob` edits until pack reload; studio worlds hotload within about a second (new chunks only).

## 5. Common failure modes

1. **"You need to hold your wand!"** — no wand selection and no WorldEdit selection.
2. **"File already exists."** — pass `overwrite=true`.
3. **Save says missing `dimension`** — not in a loaded Iris world; pass `dimension=<pack>`.
4. **Objects saved into the wrong pack folder** — dimension load key ≠ pack folder name (2.4).
5. **`convert` does nothing** — files not in `plugins/Iris/convert/` or not ending in `.schem`.
6. **Converted objects lack tile data** — converter never reads block entities; use paste-then-wand.
7. **Converter deleted the schematic** — by design after success; keep a copy elsewhere.
8. **Selection lost** — corners live on the wand item; `paste edit=true` overwrites the held wand selection.
9. **`position2` does nothing** — WorldEdit-only selection; run `/iris object we` first.
10. **Entities vanished** — never stored in objects.
11. **Jigsaw/structure-void blocks vanished** — stripped on read; connectors are JSON.

## Command reference

All under `/iris object` (alias `/iris o`). Optional parameters must be `key=value`.

| Command | Aliases | Parameters |
|---|---|---|
| `studio` | | `dimension=<dim>` (optional), `seed=1337` |
| `wand` | | |
| `we` | | |
| `position1` / `position2` | `p1` / `p2` | `here=true` |
| `x&y` / `x+y` | | |
| `shift` | | `amount=1` |
| `contract` | `-` | `amount=1` |
| `save` | | `dimension` (contextual), `<name>`, `overwrite=false` (alias `force`), `legacy=true` |
| `paste` | | `<object>`, `edit=false`, `rotate=0`, `scale=1` |
| `undo` | `u` | `amount=1` |
| `analyze` | | `<object>` |
| `shrink` | | `<object>` |
| `plausibilize` | | `<target>`, `dryrun=false`, `reach=12` |
| `convert` | | |
| `dust` | `d` | |

Related: `/iris studio open <dimension> [seed=]`, `/iris studio close`, `/iris object studio`, `/iris find object <object> [teleport=true]`.

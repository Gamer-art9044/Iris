# 19 - Objects

An Iris object is a sparse voxel volume — block states plus block-entity data — stored as a `.iob` file under a pack's `objects/` folder. You build it in a world, select it with a wand, and save it into the pack; nothing about the object itself says where it generates. Wiring it into generation is `20 - Object Placement.md`; using objects as jigsaw pieces is `21 - Jigsaw Structures.md`.

## Capture and save an object

Prerequisites: a writable pack, operator access on a Bukkit-family server, and something built to capture. A Studio world is the shortest path because it hotloads pack edits and starts you in creative.

```text
/iris studio open <pack> seed=1337
/iris object wand
```

1. **Select.** Left-click one corner of the build, right-click the opposite corner. The selection lives on the wand item, and particles outline the box out to 256 blocks from you.
2. **Tighten.** Run `/iris object x+y`. It walks the selection upward until the slab is empty air, backs off one, then pulls the four side faces in until each touches a block. Use `/iris object x&y` instead if the selection also needs to find its own floor. The saved volume is exactly the selection box, so any air you leave in it moves the object's origin.
3. **Save into the pack.** `/iris object save tutorial/lookout`. Inside an Iris world the target pack resolves automatically; anywhere else pass `dimension=<pack>`. Add `overwrite=true` to replace an existing file — there is no backup. If the build contains chests, signs, banners, or spawners you care about, add `legacy=false` (section 3).
   Success looks like: a chat line naming the pack and the object, and a new file at `<data>/packs/<dimension load key>/objects/tutorial/lookout.iob`.
4. **Verify it loads.** `/iris object analyze tutorial/lookout` reads the file back and reports width x height x depth, total block count, and the ten most common materials. If those numbers match what you selected, the file is good.
5. **Verify it pastes.** `/iris object paste tutorial/lookout edit=true` stamps a copy where you are looking and hands you a wand already fitted to it. Walk the copy: check the orientation, check that chests still have contents and signs still have text, fix anything wrong in place, then re-save the same key with `overwrite=true`. `/iris object undo` removes the pasted copy.
6. **Prove it survives a reload.** Close and reopen Studio, then paste again. Block states and block-entity data must come back identical; if a chest is empty now, the object was captured through a path that drops tile data (section 6).

The object is finished when `analyze` reports the dimensions you expect, a fresh paste lines up on the target block, and a save/reopen cycle changes nothing. Then continue with `20 - Object Placement.md`.

Use a throwaway key like `scratch/test1` until the bounds and origin are right. Overwriting an object does not rewrite copies already generated into existing chunks.

## 1. What an object is

An object stores a bounding box (`w x h x d`), a sparse map of block states, and a sparse map of tile data. The origin is always the **center** of the bounding box — `w/2, h/2, d/2` with integer division. It is derived from the dimensions, never written to the file, and recomputed every load.

Stored:

- Every block in the selection except plain `minecraft:air`.
- Block-entity data, at one of two fidelities. `/iris object save` defaults to `legacy=true`, which writes a reduced record for the block types it has a handler for: a sign keeps its front four lines and color and **loses its back side**, a spawner keeps only the entity type it spawns, a banner keeps its patterns and base color, and a container with a vanilla loot table keeps the table key and loot seed. Anything without a matching handler — including a hand-filled chest, a furnace with contents, a decorated pot — falls through to the full block-entity NBT. Pass `legacy=false` to serialize everything in full. Object studio click-to-save always writes full NBT.

Not stored:

- **Entities.** Armor stands, item frames, paintings, and mobs are dropped on save. Runtime entity spawns come from placement markers instead (`20 - Object Placement.md`).
- **Biomes.**
- **Jigsaw blocks, structure blocks, and structure voids.** These are filtered out when the `.iob` is *read*, not when it is written, so they can sit in a file and still never reach the world. Only the block is dropped; a tile-data entry saved at that position still loads. Iris jigsaw connectors are JSON metadata, not blocks (`21 - Jigsaw Structures.md`).

**Air is not one thing.** `minecraft:air` is skipped at capture. `cave_air` and `void_air` are both captured — but at placement time Iris skips `air` and `cave_air` blocks and only writes `void_air`. So `void_air` is the block to use when you want an object to carve terrain away, and `cave_air` inside an object is dead weight in every mode except the internal `STRUCTURE_PIECE` path.

### Format limits

`.iob` V2 writes an `Iris V2 IOB;` header, then short-typed centered coordinates and a short-counted palette. That gives a working range of +/- 32,767 blocks per axis from the center and 32,767 distinct block states per object. Neither limit is checked on save — exceed either and the shorts wrap silently, so the file writes and then reads back wrong. Files written before V2 still load through a legacy reader, tried automatically when the V2 header is missing. Nothing caps the block count; the practical limits are memory and the wand's scan budget, which processes 30 ms of blocks per tick by default (`-Diris.ms_per_tick`).

### Where objects live and how they are named

```
<platform data folder>/packs/<pack>/objects/**/*.iob
```

On a Bukkit-family server that is `plugins/Iris/packs/<pack>/objects/`. The object key is the path under `objects/`, slash-separated, without the extension:

```
objects/light.iob                  ->  "light"
objects/trees/oak/big_oak_1.iob    ->  "trees/oak/big_oak_1"
```

Keys resolve within the current pack first. Outside an Iris world, lookups fall back to scanning every visible pack and take the **first** one that resolves the key, with no warning that others also matched. That matters most for `/iris object shrink`, which resolves this way and then overwrites whatever it found. Give objects distinctive subfolder paths.

## 2. Building and selecting in-game

### Build surfaces

- **Pack studio** — a world generated from your pack, so you can build against real terrain: `/iris studio open <dimension> [seed=1337]`, closed with `/iris studio close`.
- **Object studio** — a flat gallery laying out every object in the pack on a grid, with click-to-save editing (section 5): `/iris object studio [dimension=<dim>] [seed=1337]`. Omit `dimension` and it aggregates the objects of every visible pack.

Any flat world works too. `/iris object save` only resolves the target pack automatically inside an Iris world; elsewhere pass `dimension=<pack>`.

### The wand

`/iris object wand` gives a Blaze Rod named "Wand of Iris". The two corners are written into the item's lore, so dropping or replacing the wand loses the selection, and two wands are two independent selections.

| Action | Effect |
|---|---|
| Left-click a block | Sets corner 1 |
| Right-click a block | Sets corner 2 |

Main hand only. Both clicks are cancelled so you never break or place while selecting. Setting a corner in a different world clears the other corner rather than producing a cross-world box. Outline particles are drawn for the selection while you are in the same world, out to 256 blocks from you, thinning out with distance.

**WorldEdit interop.** With the `worldEditWandCUI` setting on (the default), a live WorldEdit selection stands in for an Iris wand across the `/iris object` selection and save commands. `/iris object we` converts the current WorldEdit selection into a real Iris wand, and it works whether or not that setting is on. `position2` is the exception: it needs an actual Iris wand, so run `/iris object we` first if you have only a WorldEdit selection.

### Adjusting the selection

| Command | Alias | Effect |
|---|---|---|
| `/iris object position1 [here=true]` | `p1` | Moves a corner to the block under your feet, or to the block you are looking at with `here=false` |
| `/iris object position2 [here=true]` | `p2` | Same for the other corner; requires an Iris wand |
| `/iris object shift [amount=1]` | | Slides the whole box one step per `amount` along the axis you are facing |
| `/iris object contract [amount=1]` | `-` | Pulls the face you are facing inward by `amount` |
| `/iris object expand [amount=1]` | modded only | Pushes that face outward; the Bukkit command tree has no `expand` |
| `/iris object x&y` | | Finds the empty air above and below the selection to set its top and bottom, then pulls the four sides in until they touch blocks |
| `/iris object x+y` | | Same, but only searches upward — the current bottom is kept |

Rough-select the base of a build, then run `x+y` to wrap it tightly.

## 3. Saving

```
/iris object save [dimension=<pack>] <name> [overwrite=false] [legacy=true]
```

- `name` is required and positional. It is the path under `objects/`, and `/` creates subfolders.
- `dimension` resolves from the Iris world you are standing in; pass `dimension=<pack>` anywhere else.
- Without `overwrite=true` (alias `force=true`) an existing file aborts the save. There is no backup.
- `legacy` defaults to **true**, which writes reduced tile records for signs, spawners, banners, and loot containers (section 1). Pass `legacy=false` when full block-entity fidelity matters — most obviously for double-sided signs.
- The saved volume is the **full selection box**. Nothing is shrinkwrapped, so deliberate air padding is preserved — and moves the center.

The file lands at `<data>/packs/<dimension load key>/objects/<name>.iob`.

**Footgun:** the target folder is the **dimension's load key**, not the folder the dimension came from. A pack in `packs/mypack/` whose dimension file is `dimensions/overworld.json` writes its objects into `packs/overworld/`. Keep the dimension JSON filename equal to the pack folder name and this never bites.

```
/iris object wand
# left-click one corner, right-click the other
/iris object x+y
/iris object save trees/birch/tall_birch_1
```

## 4. Pasting, editing, and inspecting

```
/iris object paste <object> [edit=false] [rotate=0] [scale=1]
```

The paste lands on the block you are looking at, with the object's bottom resting on it — small foliage (grass, snow layers, vines, torches, dead bushes, poppies, dandelions) is ignored by the raycast so you target the ground, not the plant. `rotate` is degrees around Y. `scale` resizes with tricubic interpolation and is clamped down for large objects, so a big object silently pastes at a smaller factor than you asked for.

```
/iris object undo [amount=1]
```

Alias `u`. It reverts pastes, not blocks you placed by hand.

`paste ... edit=true` additionally hands you a wand fitted to the pasted bounds, which is the normal way to edit an existing object: paste it, change it, re-save the same key with `overwrite=true`.

Inspection and maintenance:

- `/iris object analyze <object>` — dimensions, block count, and the top ten materials with their most common block-data variant. Read-only, and the fastest check that a file loads at all.
- `/iris object shrink <object>` — shrinkwraps to the tightest box and **overwrites the file in place with no confirmation**. It re-centers, so any deliberate off-center padding is lost.
- `/iris object plausibilize <target> [dryrun=false] [reach=12]` — tree-specific. It grows organic branch connections through the canopy so leaves survive vanilla decay; leaf clusters farther than `reach` blocks from wood are pinned persistent instead, and `reach=0` grows without a limit. `target` accepts an object key, a folder prefix ending in `/`, or a filesystem path. `dryrun=true` reports and writes nothing.
- `/iris object dust` (alias `d`) — gives Glowstone Dust named "Dust of Revealing". Right-click a block in an Iris world and Iris names the placement that owns it.
- `/iris find object <object> [teleport=true]` — `/iris goto object` is the same command under an alias. You have to be standing in an Iris world. In an object studio it teleports to that object's grid cell; otherwise it spirals outward looking for a generated instance, giving up after 120 seconds. `teleport=false` prints the coordinates instead of moving you.

## 5. Object studio: click-to-save

Inside `/iris object studio`, left- or right-clicking a block in a grid cell writes that cell straight back to its `.iob`. It is the quickest loop for touching up a library of small objects.

- The saved volume is the cell's original bounding box, so the center is preserved and nothing is shrinkwrapped. Tile data is always written in full, unlike `/iris object save`.
- Each cell carries a content hash; clicking a cell you did not change reports no changes and writes nothing. The hashes are in memory only, so the first click on any cell after a world load always writes.
- The save aborts **silently** if any chunk covering the cell is unloaded. If a click seems to do nothing, walk closer and click again.

## 6. Importing existing builds

### 6.1 `.schem` files: `/iris object convert`

1. Drop Sponge `.schem` files into `<data>/convert/` (created on first use).
2. Run `/iris object convert`.
3. Each `foo.schem` becomes `foo.iob` **in that same folder**, shrinkwrapped. Move it into a pack yourself — convert never writes into a pack.

What survives and what does not:

- **Sponge Schematic v2 and v3 only.** Anything else — MCEdit `.schematic`, `.litematic`, Sponge v1 — is rejected. No WorldEdit or FAWE needed; Iris parses the NBT itself.
- **Blocks only.** The converter reads the palette and block indices and nothing else, so **block entities, entities, and biomes are all lost**: chests come out empty, signs blank, spawners default.
- The source `.schem` is **deleted** after a successful conversion. Keep a copy elsewhere.
- Files outside that folder, or not ending in `.schem`, produce no output at all.

### 6.2 Keeping block entities: paste, then wand

The converter cannot preserve tile data, so anything with chests, signs, or spawners has to go through the world:

1. Open a build world — `/iris studio open <pack>` or any flat world.
2. `//schem load <name>` and `//paste` with WorldEdit or FAWE.
3. Keep the WorldEdit selection (Iris accepts it directly) or run `/iris object we`, or re-select with the Iris wand.
4. Tighten with `/iris object x+y` or `contract`.
5. `/iris object save <name> [overwrite=true]`.

The wand save reads live blocks, so it captures full block-entity NBT. This is the only import route that preserves it.

### 6.3 Vanilla `.nbt` templates

- `/iris structure import <dimension>` — imports registered structures and their templates into the pack as objects plus jigsaw graphs.
- `/iris studio importvanilla <dimension> [variants=3] [structures=true]` (aliases `importv`, `iv`) — captures vanilla trees, mushrooms, and object features into `objects/vanilla/`, taking `variants` samples of each, and also imports vanilla and datapack structures and jigsaws when `structures=true`.

Details are in `22 - Native Structures & Datapacks.md`.

## 7. Practical details

**Center and anchoring.** The center is always the middle of the bounding box. Wand saves keep the selection box, so asymmetric padding survives and shifts the center; `convert` and `shrink` shrinkwrap and re-center. At generation the object is centered on its column in X and Z and lifted so its bottom sits on the anchor — everything past that is the placement's `mode` (`20 - Object Placement.md`).

**Rotation is never baked in.** A `.iob` stores exactly one orientation. Rotation ranges belong to the placement; `paste rotate=90` rotates a throwaway copy and does not touch the file.

**Two separate loot mechanisms.** A chest saved into the `.iob` with a vanilla loot table on it keeps that table. Pack loot tables are attached by the placement instead (`loot`, `vanillaLoot`, `overrideGlobalLoot`) — see `20 - Object Placement.md`.

**Caches and hotload.** Objects are cached per pack. Studio worlds watch the pack folder for `.iob` and `.json` changes and hotload at most once a second, backing off to about four seconds while the world is busy generating or running maintenance. A hotload swaps the engine's whole pack runtime, so already-generated chunks are untouched and only later ones see the edit. Ordinary worlds never hotload; they serve the cached copy until the pack reloads.

## 8. Common failure modes

1. **"You need to hold your wand!"** — no Iris wand selection and no WorldEdit selection.
2. **"File already exists."** — pass `overwrite=true` (or `force=true`).
3. **Save complains about a missing `dimension`** — you are not standing in a loaded Iris world; pass `dimension=<pack>`.
4. **Objects landed in the wrong pack folder** — the dimension's load key is not the pack folder name (section 3).
5. **`convert` did nothing** — the files are not in `<data>/convert/`, or do not end in `.schem`.
6. **Converted objects have empty chests** — the converter never reads block entities. Use the paste-then-wand route (6.2).
7. **The converter ate the schematic** — that is by design after a successful conversion.
8. **The selection vanished** — corners live on the wand item, and `paste edit=true` overwrites the held wand's selection.
9. **`position2` does nothing** — you have a WorldEdit-only selection. Run `/iris object we` first.
10. **Entities are gone** — objects never store entities. Use placement markers.
11. **Jigsaw or structure-void blocks are gone** — they are filtered out when the file is read. Connectors are JSON, not blocks.
12. **A paste is offset from where you expected** — the origin is the bounding-box center, so air padding inside the selection moves it. Re-select tightly or run `shrink`.
13. **A sign lost its back side, or a spawner lost its settings** — saved with the default `legacy=true`. Re-save with `legacy=false`.
14. **`shrink` rewrote a file in a pack you were not thinking about** — outside an Iris world, a bare key resolves to the first visible pack that has it, silently (section 1).

## Command reference

All under `/iris object` (alias `/iris o`). On Bukkit, optional parameters are `key=value`; the modded loaders expose the same commands as brigadier literals instead (`/iris object save overwrite <name>`, `/iris object paste rotate <degrees> <key>`, `/iris object paste at <x> <y> <z> <key>`).

| Command | Aliases | Parameters |
|---|---|---|
| `studio` | | `dimension=<dim>` (optional), `seed=1337` |
| `wand` | | |
| `we` | | Bukkit only |
| `position1` / `position2` | `p1` / `p2` | `here=true` |
| `x&y` / `x+y` | `xay` / `xpy` | |
| `shift` | | `amount=1` |
| `contract` | `-` | `amount=1` |
| `expand` | | `amount=1`, modded only |
| `save` | | `dimension` (contextual), `<name>`, `overwrite=false` (alias `force`), `legacy=true` |
| `paste` | | `<object>`, `edit=false`, `rotate=0`, `scale=1` |
| `undo` | `u` | `amount=1` |
| `analyze` | | `<object>` |
| `shrink` | | `<object>` |
| `plausibilize` | | `<target>`, `dryrun=false`, `reach=12` |
| `convert` | | Bukkit only |
| `dust` | `d` | |

Related: `/iris studio open <dimension> [seed=]`, `/iris studio close`, `/iris studio importvanilla <dimension> [variants=3] [structures=true]` (aliases `importv`, `iv`), `/iris find object <object> [teleport=true]` (alias `/iris goto object`), `/iris structure import <dimension>`.

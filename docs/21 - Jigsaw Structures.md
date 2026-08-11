# 21 - Jigsaw Structures

Iris Jigsaw Studio is the Bukkit in-game authoring path for multi-piece structures. It supports planar connector-topology projects for village-like layouts and freeform spatial projects for strongholds, towers, and rooms, while the shared Iris assembler runs the saved resources on every supported platform. Studio-created projects are transaction-owned, dynamically evaluated, and can be exported to a strict Minecraft 26.2 vanilla datapack when they use the `VANILLA_PORTABLE` contract.

This page replaces the former in-game jigsaw instructions. General Studio behavior is in `10 - Studio & VSCode Schemas.md`, placement context is in `18 - Structures Overview.md`, and native/datapack structures are in `22 - Native Structures & Datapacks.md`.

## Tutorial: create a planar village kit

Prerequisites: a Bukkit-family Iris server, a writable pack under the Iris `packs/` directory, a player with `iris.all`, and no other Studio world being opened or closed. Bukkit has one global Studio project/world lifecycle and one owning Jigsaw player session. Non-owner block edits and recognized mutating commands are cancelled throughout the active Jigsaw Studio world. Use a disposable pack or a version-controlled copy until the complete smoke test passes.

1. Create a transaction-owned project and open its transient Studio world:

   ```text
   /iris jigsaw create overworld village/demo
   ```

   `village/demo` is the new structure key: it writes `structures/village/demo.json`, identifies the graph in Iris placements, and is the key used to reopen it later. `structure=` and `name=` are named aliases for `key=`, not references to a separate vanilla structure. With no optional arguments, creation defaults to planar mode, Iris-native compatibility, 15×15×15 initial workcells, and Studio seed `1337`. `mode=` tab-completes `planar` or `spatial`; `compatibility=` completes `iris` or `vanilla`; existing Iris structure keys complete for `open`, `edit`, and `reopen`. Planar width and depth must each be at least `3`, X/Z cannot exceed `128`, Y must stay within `1..192`, and one workcell cannot exceed `2,097,152` blocks. Width and depth may differ. Creation is add-only: Iris refuses any occupied or conflicting target.

   A planar project begins with one owned variant for each archetype, three owned pools, one `variant-1` theme set, and one ownership manifest:

   ```text
   structures/village/demo.json
   jigsaw-pools/village/demo/start.json
   jigsaw-pools/village/demo/pieces.json
   jigsaw-pools/village/demo/caps.json
   jigsaw-pieces/village/demo/{blank,end,straight,corner,tee,cross}.json
   objects/village/demo/{blank,end,straight,corner,tee,cross}.iob
   .iris/structure-manifests/key-<sha256>.json
   ```

   The start pool selects Cross Junction at weight `1`; the pieces pool contains End Cap, Hallway, L Junction, T Junction, and Cross Junction; its direct fallback is the caps pool; and the caps pool contains End Cap plus an empty termination entry. Their resource keys remain `end`, `straight`, `corner`, `tee`, and `cross`. Every default piece is rotatable and has weight/chance `1` where it is a pool member. In the default Iris-compatible project, every piece belongs to theme `variant-1`, End Cap is terminal, mandatory caps are off, and unresolved optional branches fail the assembly. A vanilla-compatible project omits both Iris theme and terminal-rule metadata and terminates only the unresolved optional branch. Studio opens with the player in creative above Blank, and all six workcells have their default variant loaded.

   Jigsaw Studio does not run the ordinary Studio entry teleport or open a VSCode workspace before entering the workcell. It reuses the startup-loaded datapack runtime only while the pinned compiler-input fingerprint still matches all dimension, biome, and snippet JSON plus the compiler build and height policy. Jigsaw's structure, pool, piece, object, and ownership writes do not alter those generated registries; a relevant input edit, unavailable registry, or changed/failed external datapack ingest or removal invalidates reuse and falls back to the normal recovery and installation check, while a verified no-change check restores the prior pin. Its dedicated synthetic generator skips the procedural generation-cache warm, complete mantle-radius preparation, and native structure-start generation, Paper requests the one entry chunk urgently and asynchronously, and the owner is teleported once through the Jigsaw destination path after that chunk is retained and ready. The transient world sets `spawn_mobs=false` and independently cancels natural creature-spawn events; explicitly summoned test entities remain available.

2. Inspect the compact Studio and its context displays:

   ```text
   /iris jigsaw status
   /iris jigsaw particles true
   ```

   Planar Studio has exactly six rotation-independent workcells in a three-column by two-row grid: Blank, End Cap, and Hallway on the first row; L Junction, T Junction, and Cross Junction on the second. Their stable IDs remain `blank`, `end`, `straight`, `corner`, `tee`, and `cross`. Neighboring capacity columns and rows retain at least one clear block even when their sizes differ. Each floor is light-gray wool, the canonical connector path is red wool, and every canonical face-center socket is capped with a sea lantern. There is no orientation, permutation, authored-piece, or derived-rotation gallery.

   Every workcell is enclosed by a physical white-concrete edge cage one block outside its editable capacity. Player-local particle trails outline focused and nearby editable bounds inside those cages and show each focused connector's 1.75-block direction line: lime for complete metadata with no Iris channel, red for incomplete identity metadata, or a deterministic channel color. Jigsaw Studio spawns no display entities for workcell bounds. `/iris jigsaw particles <true|false>` controls the workcell, connector, and temporary assembly diagnostics. The existing Iris scoreboard switches automatically to Jigsaw context and reports the structure, workcell, variant, and Loading, Saving, Disabled, Read-only, Invalid, Unsaved, or Saved state. `/iris studio scoreboard` toggles that sidebar for the current login.

3. Right-click the generated control chest with the main hand, run `/iris jigsaw menu`, or start three sneaks within 1.5 seconds. The six-row GUI shows the six workcells and pages only the variants belonging to the selected rotational archetype. Entering a workcell selects it for the owner's next menu open; left-clicking a workcell selects it, closes the menu, and teleports the owner to its horizontal center. Left-click **Hallway**, then click **New Blank Variant**. Iris clones the active owned piece's complete metadata and every exact pool-entry membership into a service-named owned piece, creates an empty object with the source object's dimensions, closes the GUI while the graph transaction runs, and loads the new variant into Hallway. Reopen the menu after the completion message.

   New keys are deterministic, such as `village/demo/variants/straight/variant-1`. **Rename This Variant** and **Rename This Workcell** use an anvil text input; labels are author-facing only and do not change piece keys, stable workcell IDs, or solver archetypes. **Duplicate This Cell's Variant** clones the same complete piece metadata and every exact membership while copying the source object's bytes and its author-facing label. An End Cap clone therefore keeps both its pieces- and caps-pool entries, and a Cross Junction clone keeps both its start- and pieces-pool entries, including each entry's weight, chance, and other fields. Neither action guesses a first or lexicographically sorted owned pool. Both require an active owned variant with at least one owned membership; for an empty or unassigned workcell, use `/iris jigsaw piece create <poolKey> <pieceKey>` to select the pool explicitly. A non-owned variant cannot be duplicated or mutated.

4. Enter Hallway and build inside its white-concrete cage and particle bounds. The workcell displays the active object's real blocks with connector blocks hidden by default. **Workcell Settings** toggles real `minecraft:jigsaw` overlays when marker editing is needed. If a shown connector is broken, **Reset Connector Blocks** restores all saved connector coordinates without touching other edited blocks. An existing planar piece authored in another direction is rotated automatically into canonical orientation; block states, connectors, positions, and final states rotate with it. Capture applies the inverse rotation so the source resources stay coherent.

5. Configure each `minecraft:jigsaw` marker through Mojang's block UI. For a newly generated Hallway variant, the north and south markers already use:

   | Mojang field | Generated planar value |
   |---|---|
   | Name | `iris:planar` |
   | Target name | `iris:planar` |
   | Pool shown in the marker UI | `iris:village/demo/pieces` |
   | Joint | `ALIGNED` |
   | Final state | `minecraft:structure_void` |
   | Selection priority | `0` |
   | Placement priority | `0` |

   The jigsaw block's `orientation` block state supplies its front and top directions. Studio marker pools must use `iris:<owned-pool-key>` in Mojang's UI; capture verifies that namespace and stores the internal pool key without `iris:`. Do not move a generated planar marker away from its face-center socket. For `VANILLA_PORTABLE`, use vanilla-valid namespaced connector identities and leave the Iris-only channel empty.

6. Change one block, then wait two seconds without another workcell update. Iris marks the workcell dirty immediately and schedules capture after a 40-tick quiet period. A later change replaces the pending capture identity, and a busy capture retries until the save/load/graph barrier permits it. Container inventory click, drag, and close events, internal inventory move and hopper pickup, and furnace, brewing-stand, dispenser, and crafter activity are captured along with block, fluid, growth, piston, redstone, explosion, interaction, and recognized command changes. Opening Mojang's jigsaw-block UI starts a five-tick owning-region NBT poll; a detected tile change marks the workcell dirty, while a subsequent command, tool use, teleport/world change, quit, graph operation, **Flush Autosave Now**, close, or enabled-world unload first requests a final tile snapshot.

   ```text
   /iris jigsaw status
   /iris jigsaw save
   ```

   With connector blocks visible, autosave preserves the authored connector order for every marker that remains at the same source-local position, including markers whose metadata or orientation changed there. Removed markers disappear; new or moved markers append in deterministic X/Y/Z order. With connector blocks hidden, connector identity and order remain fixed while the exact ordinary block state and tile NBT placed at that coordinate are captured into the object and become that connector's final state. Duplicate source or captured positions reject the save.

   `status` reports whether an autosave is pending. `/iris jigsaw save` and the GUI's **Flush Autosave Now** action request an immediate flush; if the final marker snapshot, another operation, or scheduler availability prevents capture from starting, the same pending ticket is retained and retried. Every successful atomic workcell save plays one short bell for the owning player. Each changed autosave retains the previous complete owned closure in one per-project history file; content blobs are deduplicated and only the newest five iterations remain. **Undo Last Autosave** restores and removes the newest retained iteration through the atomic writer, so it can be clicked repeatedly to rewind up to five saves. Persistent validation or atomic-writer failures leave that mutation dirty, emit one console report with request, structure, workcell, and piece context, and retry after 2, 4, 8, 16, then at most every 30 seconds. A planar connector-topology mismatch is expected authoring validation: it names the required and edited shape without a stack trace and directs the author to **Reset Connector Blocks**. A later edit resets that failure state; a manual flush attempts immediately without discarding it. Pending tickets resolve their workcell by stable ID after every committed graph reload, so one workcell save cannot strand sibling autosaves on replaced layout objects. Neither manual action is required in the normal loop. Fresh untouched workcells report **Autosaved**, not pending. Capture reads the active owned variant across its exact displayed dimensions, converts jigsaw blocks into connector metadata, writes each connector's `final_state` into the object cell, replaces only the piece JSON connector array so omitted defaults and extension fields remain intact, compiles the complete owned graph, then commits the JSON, `.iob`, and manifest together. The Jigsaw service directly invalidates, reloads, evaluates, and rematerializes these graph resources without running ordinary Studio's full-engine pack hotloader. If an object crosses chunks, Iris snapshots every intersection on that chunk's owning region and begins the write only after the complete capture validates. A failed or incomplete capture writes nothing.

7. Inspect the session-persistent preview. Every committed mutation triggers a background compile and seed-`1337` assembly. The menu reports `PENDING`, `VALID`, `WARNING`, `INVALID`, or `STALE`, plus selected theme, piece count, and the current diagnostic. Iris renders the assembled blocks on the negative-X side of the workcells, keeps them until replacement or Studio close, and updates that read-only area after each later commit. Planar previews sit on the editing floor; spatial previews are lifted 48 blocks above it so their connected three-dimensional piece blob remains visually separate from the one-row editor. Click **Go to Preview** or run `/iris jigsaw preview goto` to teleport above it. The preview bounds are protected from players, fluids, pistons, explosions, growth, fire, entities, and redstone. The renderer accepts at most 250,000 explicit blocks; a larger assembly becomes `INVALID` with the render-limit diagnostic and is not rendered.

   For an additional arbitrary-seed diagnostic, use:

   ```text
   /iris jigsaw preview assemble seed=4242
   ```

   This separate command places no blocks and draws bounded purple particle boxes for 10 seconds. It does not replace the automatic seed-`1337` evaluation or permanent block preview.

8. Configure variation from the GUI:

   - Each exact pool membership has a positive relative weight and an independent `0%..100%` eligibility chance. GUI chance adjustments use five-percentage-point steps. Chance is tested before weighted selection.
   - **Themes & Piece Rules** sets a loaded owned variant's theme membership, allowed depth `0..30`, required/maximum placement count `0..512` (`0` maximum means unbounded), and terminal role.
   - **Duplicate All Enabled Cells as Family** allocates the next `variant-<n>` family, clones the currently loaded owned variant from every enabled workcell, duplicates their pool memberships, and atomically loads and assigns all clones to that one new theme. The operation either commits and rebinds the complete family or changes nothing. The structure selects exactly one theme per assembly by positive theme weight, so an assembly can be entirely `variant-1` or entirely `variant-2`; pieces do not mix unless a piece belongs to both or has an empty theme list. **Structure Themes & Caps** shows each family's current whole-assembly percentage and adjusts its relative weight. Pool membership chance remains independent and is rolled per candidate after family selection.
   - **Mandatory Caps** requires every unresolved connector pool to use its direct fallback and place a compatible piece marked terminal. The default End piece is terminal and the default pieces pool points directly to the caps pool, so a new Iris-compatible project can enable this rule without first editing End.

   Piece themes, non-default chance, piece rules, and mandatory caps are Iris-only metadata. A graph using them is not `VANILLA_PORTABLE`.

9. Resize or disable workcells as needed. Open **Workcell Settings** and stage capacity width, height, or depth by 1 or 8 without closing the menu. Click **Apply Cell Size** after all three values are ready; Iris then performs one live regeneration of moved white-concrete cages and active variants, keeps one clear block between capacity rows and columns or spatial cells, rehydrates tile data, and moves the owner with the selected workcell. **Discard Size Changes** cancels the staged menu values without writing. Reopening is only the recovery path if live regeneration fails. Every planar workcell persists its own capacity; changing it never rewrites a variant object. A capacity cannot shrink below any variant already assigned to that cell. Open **Variant Size** to give the selected owned variant its own exact width, height, and depth within that capacity. Growth adds air; a safe shrink preserves in-bounds blocks and moves canonical connector payloads and sockets to the new face centers, while cropping or collision rejects the transaction without writes. **Resize This Variant to Capacity** is the one-click exact-size shortcut. A loaded resized variant reloads in place after commit; sibling variants keep their independent dimensions and bytes.

   Disabling a planar workcell removes all pieces of that archetype from assembly and vanilla export but preserves its size and variants for later editing. Its translucent cuboid changes from light blue to red; Iris removes the tracked display when the origin chunk unloads and recreates it after that chunk loads again. Re-enable the workcell from the same settings page to restore participation.

10. Use the **Toolbox** page when repeated actions should be available without reopening the chest. Clicking an entry gives a named stick bound to the current Studio request and its workcell, variant, pool entry, or action. Right-click to use it. Resize, themes, and rules sticks open the relevant GUI context; other sticks run their exact bound action. A stick from a closed or replaced Studio is rejected. Destructive sticks require two right-clicks within 10 seconds.

11. Set the graph's expansion limits through the ownership-aware command:

   ```text
   /iris jigsaw rules limits 12 8
   ```

   Extended graphs accept depth `1..30` and radius `1..32` chunks. A `VANILLA_PORTABLE` Studio session restricts this command to depth at most `20` and radius at most `8` chunks.

12. Attach the structure to a dimension, region, or biome with a `structures[]` placement, validate the pack, and generate new chunks. A complete placement example is under **Natural placement**.

13. Close the transient world when authoring is complete:

   ```text
   /iris jigsaw close
   ```

   Wait for autosave, variant load, evaluation, or graph-update messages before replacing or closing Studio. Pending autosave blocks conflicting variant and graph operations. `close` refuses tracked work unless it is clean; `discard=true` deliberately abandons pending edits. Non-owners can use only Iris's strict informational and communication command allowlist. An integration that bypasses Bukkit mutation events must call `JigsawStudioService.markDirty(...)` or `markAllDirty(...)`.

The tutorial passes when autosave commits the edited object and marker data, the automatic evaluation reaches `VALID` or an understood `WARNING`, the permanent seed-`1337` preview renders the expected family, pack validation succeeds, and a natural instance appears in newly generated chunks.

### Re-edit an existing Studio jigsaw

Do not run `create` again; creation is add-only. Reopen a Studio-owned graph by its original dimension and structure key:

```text
/iris jigsaw open overworld village/demo
```

`/iris jigsaw edit overworld village/demo` and `/iris jigsaw reopen overworld village/demo` are aliases. `open`, `edit`, and `reopen` reconstruct workcell capacities and labels, enabled states, variant dimensions and labels, themes, rules, and pool memberships from the saved graph. Changes inside loaded owned variants autosave; **Flush Autosave Now** only requests an immediate recovery flush and leaves blocked work queued for retry. The automatic seed-`1337` evaluation and permanent preview rebuild after each committed change. A loaded variant without editable ownership is visibly Read-only and cannot be changed.

### Adopt an existing Iris graph

An existing Iris graph without an ownership manifest must be inspected and claimed before editing:

```text
/iris jigsaw adopt inspect overworld legacy/village target=auto strategy=auto
/iris jigsaw adopt apply <plan-uuid>
```

`inspect` reads the complete structure, pool, piece, object, and referenced loot closure asynchronously. It reports a plan UUID, target, resource and byte counts, structured warnings/errors, and one of `IN_PLACE`, `CLONE_REQUIRED`, or `BLOCKED`. The default `auto` strategy claims an exclusive unowned closure in place; if any resource is shared with another structure, it plans a private clone instead. `target=auto` names that clone `<source>-studio`, then tries numbered suffixes without overwriting an existing target. Use `strategy=in-place` to require a claim with no resource-byte rewrites, or `strategy=clone target=<new-key>` to require a specific private copy.

Plans belong to the inspecting player, remain in memory for 15 minutes, and are consumed once. Close any active or opening Jigsaw Studio before `apply`. Apply takes the pack mutation lock, re-hashes the pinned source and target read set, rejects an expired or stale plan without writes, and atomically commits the ownership manifest plus adoption receipt. A successful result opens the owned target at Studio seed `1337`. Adoption metadata records source and target hashes and mappings for provenance; it does not provide a rollback command or promise a restorable preimage.

Automatic datapack imports have `MANAGED_DATAPACK` ownership because removing or refreshing the source may clean or replace them. Iris detects that provenance during inspect, forbids in-place adoption, and plans a private clone while leaving the managed graph unchanged:

```text
/iris jigsaw adopt inspect overworld imported/key target=my-edits/key strategy=clone
/iris jigsaw adopt apply <plan-uuid>
```

### Convert a registered vanilla or datapack jigsaw

Raw registered structures are not Iris graph files and cannot enter `adopt` directly. Convert one registered jigsaw structure into a new add-only owned Iris graph, then open it automatically:

```text
/iris jigsaw convert overworld minecraft:village_plains target=village/plains seed=1337
```

The source must be a live namespaced registry key and a jigsaw structure. With `target=auto`, `minecraft:village_plains` becomes `minecraft_village_plains`. Conversion follows the registered start pool, reachable template pools, templates, connectors, weights, empty entries, and fallbacks; it stores source provenance and fidelity warnings in the ownership manifest. A native list pool entry remains one weighted choice: Iris retains its recursively first physical template and outer connectors, while additional colocated children and their processors are omitted and recorded as `LIST_ELEMENTS` fidelity loss. A captured template containing no non-air states is marked `collidable: false`, allowing its connector-scaffold bounds to overlap an attached physical piece; nonempty converted pieces remain collidable. It does not preserve native placement settings beyond start pool, depth, and maximum distance, and feature pool elements, palette alternatives, processors, entities, or other native-only behavior can be omitted or reported. Keep the source native when those capabilities matter. Conversion is add-only and refuses occupied or conflicting targets rather than overwriting them.

## Tutorial: create a spatial stronghold kit

Spatial projects use the same lifecycle without planar cell constraints:

```text
/iris jigsaw create overworld stronghold/demo mode=spatial width=32 height=24 depth=32
```

New spatial projects begin with seven owned 15×15×15 variants displayed left-to-right in one horizontal row: **0 Connectors**, then **1 Connector** through **6 Connectors**. The connector sequence is cumulative north, south, east, west, up, and down, so each adjacent cell adds exactly one face-center socket. The first cell uses `workcell/spatial`; later cells use `workcell/spatial/<piece-key>`. Every cell is one clear block from the next, and adding, deleting, or resizing variants regenerates the live row without reopening Studio. The start pool contains all seven variants. The generated pieces pool contains variants 1 through 6 plus an explicit empty terminator; the connectorless editing piece is excluded because it cannot be reached as a child. Each generated piece defaults to at most 16 placements, so seed `1337` renders a bounded connected blob in the elevated spatial preview instead of one isolated piece.

Right-click the control chest or triple-sneak to select a cell, create another service-named variant, or duplicate the active owned variant. Build inside that variant's dedicated cell and configure its doorway, stair, shaft, floor, or ceiling connectors as needed. Connector blocks are hidden by default, so ordinary blocks and block-entity data remain directly editable at the socket; **Workcell Settings** can show or reset the saved jigsaw blocks. Spatial connectors may use all 12 front/top orientations supported by the jigsaw block. Studio sizes the shared capacity to contain every reachable object and the horizontal footprint of its cardinal rotations, but automatic capture and per-variant resize preserve each variant's independent exact dimensions. Use **Resize to Capacity** or `/iris jigsaw piece expand` when only the selected object should become the full workcell size. Spatial workcell and variant labels are author metadata only; `cellSize` and labels do not constrain runtime assembly.

Use `ROLLABLE` when candidate top direction should not constrain the join and `ALIGNED` when it must match the source top after rotation. Iris still tries only cardinal Y rotations. A piece with `rotatable: false` is tried only at its authored rotation. The control-chest details view toggles this property; Studio does not render separate rotation cells. Vanilla-portable variants must remain rotatable, so their GUI toggle is disabled once rotation is enabled.

Create additional owned pools before targeting them from new spatial markers or planar variants:

```text
/iris jigsaw pool create stronghold/demo/rooms
/iris jigsaw pool create stronghold/demo/end fallbackPoolKey=none
/iris jigsaw rules fallback stronghold/demo/rooms stronghold/demo/end
```

`pool create` creates an empty pool and can point it at an already owned direct fallback. `rules fallback <pool> none` clears a fallback. Every change compiles the full owned graph before commit, so a missing pool or fallback cycle is rejected. **New Blank Variant** and **Duplicate This Cell's Variant** copy every exact owned pool entry assigned to the loaded source variant; the first creates empty same-sized geometry and the second copies the source object bytes. They do not select a first or lexicographically sorted fallback pool. If the workcell has no active owned assigned variant, use `/iris jigsaw piece create <poolKey> <pieceKey>` to choose the pool explicitly.

## Studio workcells and canonical planar display

The surrounding platform uses a four-block checker pattern. Every complete workcell capacity is surrounded by a physical white-concrete edge cage one block outside the editable volume; enabled and disabled planar cells use the same material, while their participation state remains visible in the GUI and scoreboard. Player-local particle trails outline focused and nearby editable bounds inside those cages, connector direction lines, the permanent live-preview bounds, and the explicit temporary arbitrary-seed diagnostic. No workcell-bound display entity is created. The first workcell origin is `(16, 65, 16)`; every workcell's bounds begin at Y 65, one block above its floor, and that origin is the displayed object's lowest unsigned corner. Planar projects use six cells in this exact three-by-two order. Each column uses the widest workcell in that column, each row uses the deepest workcell in that row, and adjacent column and row envelopes retain one clear block. A smaller workcell can have additional open space beside it because its row and column remain aligned to the largest workcell in that envelope:

| Row | Workcell | Stable ID | Canonical open sides |
|---|---|---|---|
| 1 | Blank | `workcell/blank` | none |
| 1 | End Cap | `workcell/end` | north |
| 1 | Hallway | `workcell/straight` | north and south |
| 2 | L Junction | `workcell/corner` | north and east |
| 2 | T Junction | `workcell/tee` | north, east, and west |
| 2 | Cross Junction | `workcell/cross` | north, east, south, and west |

Every planar footprint at Y 64 is light-gray wool. A one-block-wide red-wool glyph runs from its center toward each canonical side, and the endpoint on that workcell face is a sea lantern. The Blank workcell has no red path or connector cap. A disabled workcell retains this floor while its existing translucent cuboid turns red; it remains selectable and editable but contributes no pieces to assembly or export. Spatial Studio lays every variant out as a dedicated cell in one row, has no topology glyph or enable toggle, and retains `workcell/spatial` for its first cell.

The GUI groups every planar piece by rotational topology kind. For example, west, east, south, and north end pieces are variants of the one End Cap workcell; east-west and north-south pieces are variants of Hallway. When a variant is loaded, its source orientation is rotated clockwise into the archetype's canonical display, including directional block states, connector orientation and position, and connector final state. Capture applies the inverse rotation before writing the original piece and object resources. Pool memberships, weights, dimensions, labels, and the separate underlying piece resources are not merged by this display compaction.

`/iris jigsaw goto <workcell>` accepts one of these stable IDs or the workcell name case-insensitively. `/iris jigsaw select` selects the cell containing the player, and simply entering a cell updates the owning player's next menu selection. Fresh untouched cells report **Autosaved**. Autosave captures the active owned variant after a quiet period; `/iris jigsaw save [bay=selected]` or **Flush Autosave Now** requests an immediate flush and retains the pending ticket for retry when capture cannot start. An empty workcell, a read-only variant, an invalid render, incomplete marker hydration, a conflicting operation, or a stale Studio request is not capturable.

### Canonical planar sockets

For a planar piece whose source object dimensions are `X × Y × Z`, every connector must be horizontal, every connector top must be `UP_POSITIVE_Y`, and the canonically rotated object must fit its archetype workcell. Width and depth need not be equal. Integer division is floor division.

| Side | Position | Direction | Top |
|---|---|---|---|
| North | `(X / 2, Y / 2, 0)` | `NORTH_NEGATIVE_Z` | `UP_POSITIVE_Y` |
| East | `(X - 1, Y / 2, Z / 2)` | `EAST_POSITIVE_X` | `UP_POSITIVE_Y` |
| South | `(X / 2, Y / 2, Z - 1)` | `SOUTH_POSITIVE_Z` | `UP_POSITIVE_Y` |
| West | `(0, Y / 2, Z / 2)` | `WEST_NEGATIVE_X` | `UP_POSITIVE_Y` |

New blank planar variants inherit the active source variant's exact dimensions and use those dimensions for these positions. Workcell capacity changes never rewrite sockets or object bytes. The **Variant Size** screen or `variant resize` changes only the selected owned object and moves its canonical sockets to the new face centers. **Resize to Capacity** is the one-click convenience for making that object exactly match its capacity. Planar mode is a horizontal topology and validation contract, not a global wave-function-collapse solver, and it does not backtrack across an entire map.

## Marker capture and connector rules

Jigsaw markers are real `minecraft:jigsaw` blocks while editing. Saving reads their tile data and orientation, then stores connectors in `jigsaw-pieces/<key>.json`; the marker itself is not retained as a jigsaw block in the `.iob`.

| Connector field | Studio source | Runtime rule |
|---|---|---|
| `position` | Marker offset from the workcell origin, inverse-rotated to source orientation during planar capture | Must be inside the object's unsigned `0..size-1` bounds |
| `direction` | Jigsaw block front | Candidate must face the reverse direction after rotation |
| `top` | Jigsaw block top | Must also match after rotation when the source joint is `ALIGNED` |
| `pool` | Mojang Pool | UI value must be `iris:<owned-pool-key>`; Studio strips `iris:` and stores the internal pool used to choose the next piece |
| `name` | Mojang Name | Identity exposed to a source connector |
| `targetName` | Mojang Target name | Must equal the candidate connector's stored `name` exactly; matching is case- and whitespace-sensitive at runtime, while Studio marker capture trims both values |
| `channel` | `/iris jigsaw connector channel <channel\|none>` on a saved marker's exact local position | Values match exactly, including case and whitespace; empty matches only empty, and any non-empty value blocks vanilla export |
| `joint` | Mojang Joint | `ROLLABLE` ignores candidate top; `ALIGNED` requires it to match |
| `finalState` | Mojang Final state | Canonical block state written into the `.iob` at the marker cell; `minecraft:structure_void` leaves the cell absent, while explicit air remains an authored block state |
| `selectionPriority` | Mojang Selection priority | Signed integer; higher-priority connectors within one piece are processed first; ties preserve authored order |
| `placementPriority` | Mojang Placement priority | Signed integer on the source connector; higher-priority attached child pieces expand first; ties preserve attachment order |

The ordinary Mojang jigsaw UI does not expose Iris `channel`. Let autosave capture the marker, look directly at it from within eight blocks in the loaded workcell, then run `/iris jigsaw connector channel <channel|none>`. The command maps the canonical display coordinate back to the source piece coordinate and transactionally updates that exact saved connector. It rejects a workcell without an active owned variant, a missing connector offset, whitespace inside a channel, and channels longer than 128 characters. The command trims outer whitespace, `none` clears the channel, and all remaining characters retain their exact case. Runtime matching never trims either saved side, so whitespace in schema-authored data remains significant even though this command cannot author it. Reopen Studio to refresh the workcell and particle diagnostics. A non-empty update is rejected without a write in `VANILLA_PORTABLE`; vanilla marker fields remain owned by Mojang's UI and ordinary capture.

`final_state` must be a valid canonical Minecraft block state. Use `minecraft:structure_void` for an absent cell in a portable template; use the exact solid block state when the connector should leave a block behind. A jigsaw final state of air is accepted and retained explicitly by Studio capture, so it is different from an absent cell.

### How assembly chooses pieces

1. Iris selects one declared structure theme by positive relative weight. With no declared themes, the assembly is unthemed. A piece with no theme list is eligible for every selected theme.
2. It filters the start pool by enabled planar workcell, selected theme, depth and placement rules, then rolls each exact pool membership's independent chance. No passing membership is an intentional empty result; an explicit `empty: true` winner also produces no structure.
3. Iris chooses one positively weighted passing start entry and applies a random cardinal rotation when the piece is rotatable. A terminal start is placed but does not expand.
4. It processes connectors on the current piece in descending `selectionPriority` order. For each connector, it filters the primary pool by enabled workcell, theme, depth, maximum placements, terminal requirement, and chance, then tries passing entries in weighted random order. An eligible piece still may fail because its connectors are incompatible, it collides, or it exceeds bounds.
5. When any eligible entry still needs its declared minimum placement count, those required entries take precedence over other entries. After expansion, an unmet graph-wide minimum produces `FAILED_RULES` rather than silently accepting the assembly.
6. A candidate connector is compatible when source `targetName` exactly equals candidate `name`, source `channel` exactly equals candidate `channel` with case and whitespace preserved, faces oppose after rotation, and an `ALIGNED` source also has matching top direction.
7. Two pieces whose `collidable` values are both `true` may not have overlapping bounding boxes. A piece with `collidable: false` does not block or become blocked by another piece, while every piece still must stay inside `maxSizeChunks × 16` blocks from the assembly origin. Attached children are queued by the source connector's signed `placementPriority`; Iris finishes one piece's connectors before expanding its children.
8. Before maximum depth, Iris tries the primary pool and then that pool's one direct fallback; at maximum depth it skips the primary and tries only the direct fallback. An allowed explicit empty entry or empty primary pool ends the branch immediately and does not continue into the fallback. If structure `requireCaps` or pool `mandatoryFallback` is true, the direct fallback must place a compatible terminal piece and an empty entry cannot satisfy it. Otherwise ordinary primary-plus-fallback exhaustion returns `FAILED_UNCAPPED` under `FAIL_ASSEMBLY` or ends only that connector branch under `TERMINATE_BRANCH`; a fallback's own fallback is never traversed in the same selection. The runtime hard cap is 512 pieces.

The compiler reports missing resources, invalid workcells/bounds/connectors/themes/chances/rules, fallback cycles, unreachable resources, uncappable required connectors, incompatible candidates, and sampled hard-cap failures. Studio reevaluates automatically after open and every committed mutation; `status`, the scoreboard, and the control GUI expose the current evaluation instead of requiring a separate validation action.

## Commands and transactional ownership

`/iris jigsaw` aliases are `/iris jig` and `/iris jgs`. This tree is player-only and Bukkit-only; all commands use the root `iris.all` permission.

The create/open `<key>` is the root structure's internal lowercase resource path, not a display name or namespaced ID. For example, `village/demo` maps to `structures/village/demo.json`, is referenced as `"village/demo"` by Iris placements, and is reused by `open`, `edit`, or `reopen`. Pool and piece keys follow the same path grammar. Use one or more slash-separated segments containing only `a-z`, `0-9`, `.`, `_`, or `-`, such as `village/demo/hall`; the marker UI alone adds the required `iris:` namespace to pool keys.

| Command | Behavior |
|---|---|
| `create <dimension> <key> [mode=planar] [compatibility=iris] [width=15] [height=15] [depth=15] [seed=1337]` | Add-only atomic creation of a complete owned graph followed by an open request; mode completes `planar`/`spatial`, compatibility completes `iris`/`vanilla`, existing keys complete for `open`/`edit`/`reopen`, planar X/Z are `3..128`, spatial X/Z are `1..128`, Y is `1..192`, and one workcell volume is at most `2,097,152` |
| `convert <dimension> <registered-key> [target=auto] [seed=1337]` | Add-only conversion of one live registered vanilla/datapack jigsaw into an owned Iris graph, followed by Studio open; aliases `import`, `import-vanilla` |
| `adopt inspect <dimension> <source> [target=auto] [strategy=auto]` | Asynchronously inspect a complete existing Iris closure and issue a 15-minute, hash-pinned `IN_PLACE`, `CLONE_REQUIRED`, or `BLOCKED` plan; strategy completes `auto`, `in-place`, or `clone` |
| `adopt apply <planId>` | Revalidate and atomically apply a plan owned by that player, then open the target with seed `1337`; no Studio may be active or opening |
| `open <dimension> <key> [seed=1337]` | Map an existing graph into compact workcells; aliases `edit` and `reopen`; another owner, dirty work, or a conflicting lifecycle operation blocks replacement |
| `close [discard=false]` | Close the transient Studio; refuses active autosave/load/graph work or a pending dirty capture unless `discard=true` deliberately abandons it |
| `status` | Show structure, mode, compatibility, selected workcell dimensions/enabled state, variant count, whether autosave is pending, and the seed-`1337` evaluation/theme/piece result |
| `menu` | Open the same workcell/variant/rules/toolbox GUI as the generated control chest or triple-sneak gesture |
| `select` | Select the workcell containing the player |
| `goto <workcell>` | Select and teleport above a stable workcell ID; alias `teleport` |
| `particles <visible>` | Toggle player-local workcell-bound, connector, live-preview, and temporary assembly-preview particle trails |
| `save [bay=selected]` | Flush automatic capture now for one dirty ready workcell; ordinary block and container changes already schedule this operation |
| `connector channel <channel\|none>` | Look at a saved marker in the active owned workcell within 8 blocks and set/clear its Iris-only channel at the inverse-mapped source position; reopen to refresh the workcell and particles |
| `bounds <width> <height> <depth>` | Set the selected workcell capacity without rewriting any variant object; all variants must fit, and the live aligned layout regenerates and rehydrates without close/reopen; aliases `cell`, `resize` |
| `workcell capacity <width> <height> <depth>` | Explicit nested form of `bounds`; planar capacity belongs to one canonical archetype and spatial capacity is the shared envelope for its one-row variant cells |
| `workcell label <displayName>` | Set the selected planar or spatial workcell's author-facing label; quote spaces; canonical solver identity remains unchanged |
| `workcell label-reset` | Reset the selected workcell to its canonical solver label; alias `reset-label` |
| `pool create <poolKey> [fallbackPoolKey=none]` | Create a new empty owned pool; a non-`none` fallback must already be owned by this project |
| `piece create <poolKey> <pieceKey> [weight=1]` | Create and load a new owned variant; planar derives canonical connectors from the contextual workcell, while spatial creates a connectorless blank |
| `piece add <poolKey> <pieceKey> [weight=1]` | Re-add and load an existing piece/object already owned by this project |
| `piece remove <poolKey>` | Remove the active variant from that pool without deleting its owned piece/object resources |
| `piece rotatable <true\|false>` | Persist whether the active variant may use cardinal rotations; portable sessions reject `false` |
| `piece expand` | Resize only the selected planar or spatial owned variant exactly to workcell capacity; planar sockets move to the resized faces |
| `variant weight <poolKey> <weight>` | Set every matching entry for the active variant in that owned pool; weight must be positive |
| `variant resize <width> <height> <depth>` | Resize only the active owned variant within workcell capacity; safe shrink rejects cropped content and the active cell reloads in place |
| `variant label <displayName>` | Set the active variant's author-facing label; quote spaces |
| `variant label-reset` | Reset the active variant to its resource-key fallback; alias `reset-label` |
| `variant duplicate` | Copy the active variant's object bytes, metadata, and exact pool memberships into one new variant in this workcell |
| `variant duplicate-family [themeKey=next]` | Atomically clone every enabled workcell's active owned variant into one coherent Iris family and load the complete family; alias `family` |
| `rules limits <maxDepth> <maxSizeChunks>` | Atomically set expansion depth and horizontal radius; portable sessions enforce `<=20` and `<=8` |
| `rules fallback <poolKey> <fallbackPoolKey\|none>` | Atomically set or clear one owned pool's direct fallback after compiling the complete graph |
| `preview goto` | Teleport above the permanent seed-`1337` block preview; alias `teleport` |
| `preview assemble [seed=1337]` | Compute a deterministic, read-only assembly at the player's coordinates; report its complete piece count and show in-range bounds as purple particles for 10 seconds within the shared particle budget, without placing blocks |
| `export [namespace=iris] [output=jigsaw-export] [format=zip] [replace=false]` | Start a background strict export of the clean on-disk graph as a Minecraft 26.2 directory or zip; completion is reported with the originating structure key |
| `delete [confirm=false]` | With `confirm=true`, inspect reverse references, close Studio, and atomically remove the complete hash-pinned owned project; external references or changed ownership bytes block deletion; alias `remove` |

The control chest is the primary workflow. Right-click it, run `menu`, or triple-sneak within 1.5 seconds. Its six-row GUI rechecks the exact Studio request before every callback. It manages independent workcell capacities and labels, per-variant dimensions and labels, enabled states, rotation, exact pool-entry weights/chances, coherent themes, piece rules, mandatory caps, automatic evaluation, preview navigation, toolbox sticks, and destructive deletion. **Duplicate This Cell's Variant** creates one independent variant; **Duplicate All Enabled Cells as Family** clones and atomically loads one matching variant across every enabled cell. Accepted asynchronous actions close the GUI while work runs. Variant geometry/details are editable only for owned variants, and building/capture applies only to the loaded variant.

The Toolbox issues schema-`2` named sticks bound to the exact request/workcell/variant/membership. Rename a variant/workcell stick in an anvil, right-click to apply its trimmed label, or sneak-right-click to reset. Labels allow at most 64 Unicode code points and reject control characters plus section-sign formatting. Schema-`1` sticks and bindings from a closed/replaced request are stale. The active variant icon is a jigsaw block, a valid evaluation is an emerald, and lime dye is used only for the explicitly labeled theme-membership toggle.

The ownership manifest stores the exact resource set, content hashes, source provenance, capabilities, and fidelity losses. Each mutation loads the complete owned graph, verifies current files against ownership, applies the change, compiles the result, stages backups, and commits the graph and updated manifest together. Persisted graph mutations wait for autosave to clear dirty work so a pool, rule, size, theme, or variant transaction cannot erase blocks. Duplicate-one and duplicate-family requests clicked during dirty or active autosave queue once, expedite capture, and resume automatically only if the pinned request/session/source variants still match. Project deletion resolves a symbolic pack root to its real directory before both reverse-reference scans and hash-pinned removal; the final ownership and reference scan holds the same in-process and cross-process authoring locks through removal, so a coordinated write cannot add a dangling reference between validation and commit. A symbolic JSON resource or symbolic directory inside that pack fails the safety scan instead of hiding references. A hash mismatch or outside edit produces an ownership conflict and leaves authored files unchanged. Do not hand-edit transaction-owned resources between Studio transactions.

The session tracks dirty state per active owned workcell variant. Bukkit coverage includes block place/break/multi-place, buckets, inventory click/drag/close and internal move/pickup, furnace cook/burn/smelt, brewing start/fuel/complete, dispenser and crafter activity, block/entity explosions, entity block changes, right-click and physical interactions, redstone, liquid movement, form/grow/spread/fade/burn, pistons, structure growth, and recognized mutating vanilla or WorldEdit-like commands. A persistent owning-region watch compares jigsaw tile NBT after Mojang's UI opens; transition commands and enabled lifecycle operations request one final snapshot and wait behind that watch before graph mutation or clean close. Interaction coverage intentionally prefers a harmless dirty false positive over losing a door, marker, container, machine, or switch edit. Each workcell has a mutation generation: an autosave clears only the captured generation, and a later edit stays dirty for the next capture. Paper drains pending work synchronously during plugin disable. A forced Folia plugin disable is too late to schedule a new cross-region capture, so operators must close Studio or wait for a clean `status` before reload or shutdown. External plugins that bypass these events must call `JigsawStudioService.markDirty(world, x, y, z)` or `markAllDirty(world)`.

Jigsaw Studio is globally single-project on Bukkit and belongs to one owning player session. Only that owner can open controls, switch variants, mutate the graph, or flush autosave; entering a workcell changes that owner's selected menu cell. Non-owner direct block edits and recognized mutating commands are cancelled across the whole active Studio world. The control chest and permanent preview are protected against players, explosions, pistons, entities, fluids, growth, fire, and redstone. Generic Studio lifecycle calls cannot bypass the Jigsaw owner transition. Autosave, load, graph-mutation, open, close, and deletion barriers reject conflicts; capture is project-global, so concurrent workcells cannot produce stale full-graph lost updates.

There is no world-edit undo command for Jigsaw Studio. A successful graph transaction is persistent; recover it from version control or a pack backup if the authored result was wrong.

### Capacity and per-variant object size

`bounds` and `workcell capacity` target the selected workcell. Planar workcells persist independent width, height, depth, enabled state, and display label; spatial mode persists one shared `cellSize` plus `spatialWorkcellDisplayName`. Planar capacity width/depth are `3..128`, spatial width/depth are `1..128`, height is `1..192`, and volume is at most `2,097,152`. Capacity is an upper bound for every variant in that workcell. A successful capacity change updates only structure JSON, verifies the complete graph, leaves every object byte unchanged, regenerates and rehydrates the affected live layout, and teleports the owner to the selected cell's new horizontal center when that cell moves. A failed live regeneration restores the prior layout and requires reopen only as an explicit recovery boundary.

`variant resize` and the **Variant Size** screen target one owned variant. The exact requested width, height, and depth must fit its workcell capacity. Growth and shrink preserve blocks and tiles at their in-bounds canonical coordinates, account for rectangular source rotations, and relocate each planar canonical connector plus its stored block payload to the new face center. Shrink is lossless only: any stored block, including explicit air, or tile outside the target; a connector destination collision; connector tile data that cannot move safely; a read-only object; or an object shared by another piece rejects the transaction before any authored file changes. New growth volume is air. A loaded variant reloads in place after commit; siblings keep their dimensions and bytes. Marker block-entity data is applied on its owning region before Iris verifies either the candidate or its rollback, so live resize cannot reject a valid marker merely because its NBT merge was deferred to the next tick.

Spatial capacity changes only the shared workcell envelope and rejects dimensions that do not contain every variant object. **Resize to Capacity** or `/iris jigsaw piece expand` changes one selected spatial or planar object exactly to that envelope/capacity. The exact-size editor also permits safe lossless shrink. Non-connector air and `minecraft:structure_void` cells are omitted from block entries; explicit authored air and connector final-state air remain distinct and are preserved.

Capture may cross chunks. Iris schedules each chunk intersection on its owning region, rejects unloaded or incomplete snapshots, aggregates them deterministically, then validates and performs one atomic owned-graph write. A scheduling failure, Studio replacement/unload, marker or tile read failure, duplicate/missing snapshot, or graph validation error aborts the whole capture before authored files are changed. This path has automated chunk-intersection/coordinator coverage; live multi-region Folia gameplay validation remains required.

For a rotated planar variant, capture moves each block-entity payload back to the inverse-rotated object coordinate while inverse-rotating the block state. The payload itself remains unchanged, matching Iris object placement: modern Bukkit capture omits source position metadata and applies the payload at the explicit destination block. Directional behavior stored in block data rotates normally; semantic values inside a tile payload remain author data.

## Resource reference

### Structure: `structures/<key>.json`

```json
{
  "startPool": "village/demo/start",
  "maxDepth": 7,
  "maxSizeChunks": 8,
  "mode": "PLANAR_JIGSAW",
  "compatibility": "IRIS_EXTENDED",
  "branchFailurePolicy": "FAIL_ASSEMBLY",
  "cellSize": {"x": 16, "y": 16, "z": 16},
  "spatialWorkcellDisplayName": "",
  "planarWorkcells": [
    {"displayName": "", "archetype": "BLANK", "width": 3, "height": 3, "depth": 3, "enabled": true},
    {"displayName": "Village Entrances", "archetype": "END", "width": 16, "height": 8, "depth": 16, "enabled": true},
    {"displayName": "", "archetype": "STRAIGHT", "width": 16, "height": 3, "depth": 3, "enabled": true},
    {"displayName": "", "archetype": "CORNER", "width": 3, "height": 3, "depth": 3, "enabled": true},
    {"displayName": "", "archetype": "TEE", "width": 3, "height": 3, "depth": 3, "enabled": true},
    {"displayName": "", "archetype": "CROSS", "width": 3, "height": 3, "depth": 3, "enabled": true}
  ],
  "themeSets": [
    {"key": "variant-1", "weight": 1}
  ],
  "requireCaps": false,
  "placeMode": "STRUCTURE_PIECE",
  "edit": [],
  "loot": []
}
```

| Field | Default / range | Meaning |
|---|---|---|
| `startPool` | required | Pool used for the first piece |
| `maxDepth` | `7`, range `1..30` | Maximum recursive connector depth |
| `maxSizeChunks` | `8`, range `1..32` | Horizontal assembly radius in chunks |
| `mode` | Hand-authored schema fallback `SPATIAL_JIGSAW`; Studio `create` default `PLANAR_JIGSAW` | `PLANAR_JIGSAW` enables strict cell validation; `SPATIAL_JIGSAW` is freeform |
| `compatibility` | `IRIS_EXTENDED` | `VANILLA_PORTABLE` enables portable connector restrictions and is required for export |
| `branchFailurePolicy` | `FAIL_ASSEMBLY` | `FAIL_ASSEMBLY` rejects an ordinary unresolved optional branch before maximum depth; `TERMINATE_BRANCH` ends only that branch and is required for vanilla portability |
| `cellSize` | `16 × 16 × 16`; Studio X/Z `1..128`, Y `1..192`, volume `<=2,097,152` | Spatial workcell capacity; legacy uniform fallback when a planar graph has no `planarWorkcells` |
| `spatialWorkcellDisplayName` | empty | Optional 64-code-point author label for `workcell/spatial`; empty displays `Spatial` |
| `planarWorkcells` | Six unique archetypes; width/depth `3..128`, height `1..192`, volume `<=2,097,152`; `displayName` empty | Independent planar capacity, author label, and assembly/export enabled state for Blank, End Cap, Hallway, L Junction, T Junction, and Cross Junction; empty labels use the canonical name |
| `themeSets` | Empty means implicit unthemed; positive unique key weights | One coherent theme is selected per assembly; an Iris Studio project starts with `variant-1`, while a vanilla-compatible project omits themes |
| `requireCaps` | `false` | Require every unresolved connector pool to place a physical terminal piece through its direct fallback; Iris-only |
| `placeMode` | `STRUCTURE_PIECE` | Object placement mode used for each piece |
| `edit` | empty | Structure-wide Iris block replacements; not portable |
| `loot` | empty | Iris loot injection for piece containers; not portable |
| `vanillaSource` | empty | Import provenance; not an authoring target |

`rules limits` owns `maxDepth` and `maxSizeChunks`; `rules fallback` owns direct pool fallback; the GUI owns workcell capacity/labels, theme weights, `requireCaps`, per-piece size/labels/themes/rules, chance, rotation, and deletion; `connector channel` owns the saved connector's Iris-only channel. Rules without an in-game control, including `branchFailurePolicy`, `placeMode`, structure `edit`, structure `loot`, pool `mandatoryFallback`, and empty entries, remain schema-backed JSON fields. Transaction-owned projects reject outside resource edits on the next mutation; use the Studio controls or recreate/adopt the project through an ownership-aware workflow.

### Pool: `jigsaw-pools/<key>.json`

```json
{
  "pieces": [
    {"piece": "village/demo/hall", "weight": 4, "chance": 0.75, "empty": false},
    {"weight": 1, "chance": 1.0, "empty": true}
  ],
  "fallback": "village/demo/end",
  "mandatoryFallback": false
}
```

`weight` must be positive. `chance` is finite `0..1` and independently gates that exact membership before weighting; zero never passes and one always passes. An `empty: true` entry canonically omits `piece`; omitted and blank piece keys are both accepted for existing graphs. It terminates its branch only when empty termination is allowed and stops later primary or fallback candidates. Native conversion never rewrites a start-pool member as empty or omits it solely because it has no connectors. Every non-start connectorless member in a pool with a distinct fallback also remains physical so weighted failed primary attachments reach that fallback. Conversion emits `empty: true` only when a non-start pool has one all-air connectorless source member and no fallback or a self-fallback. The same all-air connectorless member in a mixed no/self-fallback pool is omitted with an explicit selection-weight and RNG-consumption fidelity loss rather than becoming an empty choice that could cut off later valid candidates; other connectorless nonempty members in no/self-fallback non-start pools are omitted as inert with exact block, fallback, selection-weight, and RNG-consumption loss. Converted native graphs set `branchFailurePolicy: TERMINATE_BRANCH`, so ordinary optional candidate exhaustion ends only that connector branch. A pool with no entries terminates when no fallback is required and does not continue into its declared fallback. `fallback` is one direct pool tried after ordinary primary failure or at maximum depth; its own fallback is not chained into the same selection. `mandatoryFallback: true` applies the physical-terminal requirement to this pool even when structure `requireCaps` is false.

### Piece: `jigsaw-pieces/<key>.json`

```json
{
  "object": "village/demo/hall",
  "displayName": "Market Hall",
  "connectors": [
    {
      "position": {"x": 8, "y": 8, "z": 0},
      "direction": "NORTH_NEGATIVE_Z",
      "top": "UP_POSITIVE_Y",
      "pool": "village/demo/start",
      "name": "iris:planar",
      "targetName": "iris:planar",
      "channel": "",
      "joint": "ALIGNED",
      "finalState": "minecraft:structure_void",
      "selectionPriority": 0,
      "placementPriority": 0
    }
  ],
  "rotatable": true,
  "collidable": true,
  "themes": ["variant-1"],
  "rules": {
    "minimumDepth": 0,
    "maximumDepth": 30,
    "minimumPlacements": 0,
    "maximumPlacements": 0,
    "terminal": false
  }
}
```

Positions are unsigned object coordinates: `(0,0,0)` is the object's minimum corner. The referenced `.iob` remains the geometry source and owns this variant's exact width, height, and depth; Studio materializes connector markers only in the authoring world. `displayName` is optional 64-code-point author metadata and falls back to the piece key's final segment. `collidable` defaults to `true`; use `false` only when an intentional connector scaffold must share its stored bounds with physical pieces. `themes: []` makes the piece available to every selected theme. Depth is `0..30`; placement counts are `0..512`, and `maximumPlacements: 0` means unbounded within the 512-piece safety cap. A terminal piece is placed but never expands its connectors.

## Natural placement

Place an Iris jigsaw by adding an `IrisStructurePlacement` object to `structures[]` on a dimension, region, or biome. Surface-biome placements apply where that surface biome owns the start chunk. A cave biome contributes only placements whose resolved anchor is one of the cave modes. Region and dimension placements remain broader scopes.

```json
{
  "structures": [
    {
      "structures": ["village/demo"],
      "placementId": "village-demo-surface",
      "distribution": "RANDOM_SPREAD",
      "spacing": 32,
      "separation": 8,
      "salt": 165745296,
      "anchor": "SURFACE",
      "minHeight": -64,
      "maxHeight": 320,
      "terrain": {"mode": "SOURCE"},
      "underwater": false
    }
  ]
}
```

| Placement rule | Fields | Behavior |
|---|---|---|
| Random spread | `spacing`, `separation`, `salt` | One deterministic attempt per spacing grid cell; `spacing` must exceed `separation` |
| Density | `density` | Independent deterministic per-chunk probability `0..1` |
| Concentric rings | `ringCount`, `ringDistance`, `ringSpread` | Stronghold-like deterministic rings around world origin |
| Surface | `anchor: SURFACE` | Surface Y must pass the inclusive `minHeight..maxHeight` gate |
| Height band | `anchor: HEIGHT_BAND` | Deterministic random Y inside the inclusive band |
| Legacy | `anchor: LEGACY` | `underground=false` resolves to `SURFACE`; `underground=true` resolves to `HEIGHT_BAND` |

`placementId` is the stable authored identity for distribution. Set it when multiple placements share the same structure or when you want unrelated field/list reordering not to move starts. A placement with several `structures` keys chooses one uniformly; pool weights control pieces inside the chosen graph, not world-level start frequency.

Only newly generated chunks use a changed placement. Direct `/iris structure place` and Jigsaw Studio preview do not prove spacing, biome scope, height gates, or natural generation.

### Cave anchors

```json
{
  "structures": [
    {
      "structures": ["stronghold/demo"],
      "placementId": "stronghold-demo-deep-caves",
      "distribution": "RANDOM_SPREAD",
      "spacing": 24,
      "separation": 8,
      "salt": 984211,
      "anchor": "CAVE_FLOOR",
      "minHeight": -48,
      "maxHeight": 80,
      "caveBiomes": ["carving/deep"],
      "caveAnchorAttempts": 12,
      "caveAnchorScanStep": 1,
      "caveMinimumClearance": 5,
      "terrain": {"mode": "PRESERVE"}
    }
  ]
}
```

| Anchor | Required carved-space geometry | Assembly alignment |
|---|---|---|
| `CAVE_FLOOR` | Solid/non-carved cell immediately below plus upward carved run | Lowest assembled piece bound moves to the anchor Y |
| `CAVE_CEILING` | Solid/non-carved cell immediately above plus downward carved run | Highest assembled piece bound moves to the anchor Y |
| `CAVE_CENTER` | Candidate is the actual midpoint of its contiguous carved cavern run, which must meet the clearance requirement | Assembly bounding-box midpoint moves to the anchor Y |
| `CAVE_ANY` | A clearance-sized carved run is centered around the candidate | Assembly bounding-box midpoint moves to the anchor Y |

Iris tests up to `caveAnchorAttempts` deterministic, unique X/Z columns in the start chunk and scans the clipped `minHeight..maxHeight` band in increments of `caveAnchorScanStep`. It stops at the first column with matches and chooses deterministically among all valid anchors in that column. Runtime clamps attempts to `1..64`, scan step to `1..16`, and clearance to `1..64`; at most 64 of the chunk's 256 columns are visited. `caveMinimumClearance` is the required vertical carved run. Empty `caveBiomes` accepts any resolved cave biome; otherwise trimmed, case-normalized keys with or without a namespace are rechecked against the cave/mantle biome at the actual X/Y/Z anchor.

For cave anchors, `underwater` checks `MatterCavern` at the actual anchor rather than the surface ocean height. A null or non-cavern cell never qualifies. With `underwater: false`, ordinary cavern air must be above the dimension's `caveLavaHeight`, explicit water/lava is rejected, and forced-air cavern matter remains dry even below that threshold. With `underwater: true`, fluid cavern cells are allowed but the cell must still be carved cavern matter.

Cave placement scope is sampled at the start chunk's center. A cave-biome `structures[]` list contributes cave anchors only; region and dimension placements remain broader, and a placement-level `caveBiomes` list revalidates the actual anchor. Lookup uses existing Iris carved-space mantle data, so a locator cannot resolve an ungenerated distant cave anchor until terrain generation has produced that mantle.

The anchor test reads one vertical `MatterCavern` column, not the complete assembled volume. `SOURCE` and `PRESERVE` can therefore leave pieces intersecting cave walls. Use `BORE` or `FORCE_CARVE` when the structure must create a reliable envelope, or inspect the full volume in gameplay when preserving the cave. Cave anchors apply to editable Iris `structures`, not the `nativeStructures` backend.

## Vanilla datapack export

Create the project with `compatibility=vanilla`, then keep the graph within the strict subset below. An existing graph is exportable only when its saved compatibility is `VANILLA_PORTABLE` and its branch policy is `TERMINATE_BRANCH`; Studio has no compatibility-toggle or branch-policy control. A vanilla-compatible Studio project writes that policy and omits the default Iris theme and terminal-rule metadata. Export reads the committed graph, not pending workcell blocks, so wait for autosave to finish and confirm the automatic evaluation is no longer `PENDING`, `STALE`, or `INVALID`:

```text
/iris jigsaw export namespace=demo output=village-demo format=zip replace=false
```

Output is written under `<Iris data>/packs/exports/`. Compilation, NBT encoding, compression, and publication run off the server thread; wait for the final result rather than treating the initial background-start message as success. One player cannot start a second export while their first is running, and the same normalized output cannot be published by two concurrent commands. Completion names the originating structure even if that Studio was closed or replaced while export was running. `output` is one direct artifact name: the supplied value must be 1–128 characters, start with a letter, number, `_`, or `-`, and then use only letters, numbers, `.`, `_`, or `-`. Leading/trailing whitespace, `.`, absolute paths, slash or backslash, nested paths, and traversal names are rejected before export. `format=zip` adds `.zip` when needed. The publisher stages the complete directory or zip and replaces the destination atomically only when `replace=true`; existing output is otherwise rejected.

The command emits a Minecraft 26.2 datapack whose `pack.mcmeta` uses `min_format: [107, 1]` and `max_format: 107`, plus a default `minecraft:plains` biome tag, an empty processor list, template pools, compressed structure-template NBT, one jigsaw worldgen structure, and one random-spread structure set. Command-level export defaults are:

| Vanilla setting | Export default |
|---|---|
| Biomes | `minecraft:plains` |
| Start height | absolute `0`, projected to `WORLD_SURFACE_WG` |
| Generation step | `surface_structures` |
| Terrain adaptation | `none` |
| Expansion hack | `false` |
| Maximum vertical distance | `4064` |
| Structure-set placement | random spread: spacing `32`, separation `8`, salt `0`, frequency `1`, linear spread |

### Strict export blockers

Export fails instead of dropping or approximating any of these features:

- Structure compatibility is not `VANILLA_PORTABLE`.
- Structure `branchFailurePolicy` is not `TERMINATE_BRANCH`.
- Structure themes, piece theme membership, non-default depth/placement/terminal rules, structure `requireCaps`, pool `mandatoryFallback`, or membership `chance` other than `1` are present.
- `placeMode` is not `STRUCTURE_PIECE`, or structure-wide `edit` or `loot` is non-empty.
- `maxDepth` is outside `1..20`, or `maxSizeChunks × 16` exceeds Minecraft's 128-block horizontal limit.
- A piece has `rotatable: false`.
- A piece has `collidable: false`; vanilla templates have no equivalent per-piece collision flag.
- A pool weight is outside `1..150`.
- A resource key, connector name/target, namespace, orientation, block state, or final state is not vanilla-valid.
- A connector has a non-empty Iris channel, duplicates another connector position, or its `finalState` does not exactly match the `.iob` block at that cell (`minecraft:structure_void` for an absent cell).
- An object contains tile payloads, a block entity, a custom-content block, or retained `jigsaw`, `structure_block`, or `structure_void` marker blocks.

This exporter does not export tile/block-entity NBT. A chest, spawner, sign, or other tile-bearing object therefore blocks strict export even when it works intrinsically in Iris. The command exposes only namespace, one direct output filename, directory/zip format, and replacement choice; biome, height projection, generation step, terrain adaptation, and structure-set placement remain the fixed defaults above. Edit the emitted datapack after export if those defaults are not the desired vanilla placement.

Test the exported artifact on an unmodded Minecraft 26.2 server or client: stop the disposable world, install it in that world's `datapacks/`, restart so the worldgen registries load it, confirm it is enabled without data errors, locate `<namespace>:<resourcePath>`, and generate fresh chunks around the located start. `/reload` can list a newly copied pack as enabled without registering its worldgen structure in the already running world, so it is not a substitute for this restart. Iris validation and NBT round-trip tests do not substitute for the vanilla load and generation check.

## Failure recovery

| Symptom | Meaning | Recovery |
|---|---|---|
| Create reports occupied/conflicting files | Add-only ownership refused to overwrite existing resources | Choose a new structure key or deliberately remove/migrate the old graph outside this workflow |
| Create reports success but Studio does not open | The complete graph was created before the follow-up open request encountered another owner, pending autosave, or lifecycle transition | Resolve the active Studio guard, then run `open` for the newly created structure; do not rerun `create` against its now-owned files |
| A loaded variant is Read-only | Its graph is unowned or has managed datapack provenance | Close Studio, run `adopt inspect`, review the disposition/diagnostics, and apply the plan; managed input must use a clone target |
| Adoption plan is expired, unknown, or stale | Its 15-minute in-memory plan was consumed/expired, or a pinned source/target changed | Run `adopt inspect` again and review the new plan; no stale plan is written |
| Conversion refuses the source | The key is absent, is not a live registered jigsaw, has an incomplete graph, or the add-only target is occupied | Keep it native, choose a valid registered jigsaw, repair its source datapack, or choose a new target; use `/iris structure import` for non-jigsaw templates |
| Ownership conflict on capture/edit | An owned file changed outside the last committed transaction | Restore the exact owned graph from version control/backup; Studio will not overwrite the mismatch |
| Close refuses with pending work | An owned workcell is dirty or autosave/graph work is running | Wait for autosave, use **Flush Autosave Now** to expedite it, or use `discard=true` only when losing pending edits is deliberate |
| An external plugin edit is not captured | The plugin bypassed Bukkit's covered mutation events | Have the integration call `JigsawStudioService.markDirty(...)` for affected coordinates or `markAllDirty(...)`; autosave then follows normally |
| Autosave has no active/editable variant | The workcell is empty or its loaded variant is read-only | Load an owned variant, or adopt/clone the graph first |
| Autosave reports Loading, Invalid, or not hydrated | Variant materialization or real jigsaw block-entity hydration is incomplete/failed | Wait for completion, reopen or reload the variant, and do not build until the scoreboard reports a stable state |
| Capacity succeeds but live regeneration reports a failure | The metadata committed, but one owning-region repaint or hydration step failed | Close and reopen Studio before editing; the persisted capacity remains authoritative |
| Autosave says a chunk is not loaded | Part of the capture volume is unloaded | Visit/load the whole workcell; the autosave retry remains pending, or use **Flush Autosave Now** after loading it |
| Multi-chunk autosave aborts | One owning-region schedule/snapshot failed, a chunk unloaded, Studio changed, marker/tile capture failed, or aggregation was incomplete/invalid | Keep the complete capture volume loaded and fix the reported cause; no graph file is written from a partial capture |
| Marker capture fails | Marker NBT is incomplete, final state is invalid, or active NMS cannot serialize the tile | Fix the named marker field or use the matching supported Bukkit/NMS build |
| The chest GUI closes after an action | The accepted operation is asynchronous and the GUI intentionally does not live-refresh | Wait for its player message, then right-click the chest again |
| A named stick stops working | It uses schema `1`, its request ID belongs to a closed/replaced Studio, or the bound workcell/variant/pool entry changed | Discard the stale stick and take a schema-`2` replacement from the current Toolbox |
| A queued duplicate cancels | The Studio request/session or one pinned source variant changed before autosave completed | Reopen the current controls, confirm the intended loaded source variants, and request the duplicate again |
| Another player cannot edit or run a mutating command | The active Jigsaw Studio belongs to its activation owner | Have the owner perform the work or close the Studio; do not bypass world protection |
| Evaluation is `STALE` | A workcell edit is waiting for autosave | Wait for capture; evaluation reruns from the new committed graph automatically |
| Evaluation is `INVALID` | Compilation or the seed-`1337` assembly failed | Fix the displayed first diagnostic; wrong pool/name/facing, impossible rules, or an uncappable required fallback are common causes |
| Permanent preview is empty | Evaluation is pending/invalid or seed `1337` intentionally produced no structure | Read the evaluation detail; fix invalid data or change chance/start rules if an empty result was not intended |
| Project deletion is blocked | Another JSON resource or ownership manifest still references a resource owned by the project | Remove or repoint the reported external reference, let autosave finish, then inspect deletion again |
| Studio closes but project deletion fails | The hash-pinned removal failed after a successful close | The project files remain on disk; reopen or back them up before retrying |
| Transaction reports cleanup required | Authored graph committed but staging cleanup failed | Preserve console output and remove/recover only the named transaction with operator care; do not re-author blindly |
| Export is rejected | At least one strict portability blocker remains | Fix each reported diagnostic; do not bypass by deleting diagnostics or assuming Iris runtime success proves vanilla fidelity |
| Export output name is rejected | The value is not one direct safe artifact name | Remove whitespace, separators, traversal, and unsupported characters; keep the supplied name within 128 characters |

## Precise smoke-test checklist

Run this in a purpose-named disposable pack/world and record each gate separately.

1. **Creation:** create a planar `IRIS_EXTENDED` project without optional mode, compatibility, dimensions, or seed. Confirm planar/Iris/15×15×15/1337 defaults, one structure, three pools, six pieces, six objects, one ownership manifest, tab completion of its key for `open`/`edit`/`reopen`, and no partial files after a duplicate-create rejection.
2. **Default catalog:** confirm all six workcells have one loaded owned variant, `variant-1` is the selected theme family, End is terminal, and mandatory caps are initially off.
3. **Workcell layout:** verify Blank/End Cap/Hallway then L Junction/T Junction/Cross Junction, one clear block between capacity rows and columns, light-gray floors, red canonical glyphs, sea-lantern endpoints, and no orientation/permutation gallery.
4. **Controls and context:** confirm every untouched workcell starts **Autosaved**. Walk outside and into End Cap; verify the Iris scoreboard context and `Triple-sneak for controls`, then open the menu and confirm End Cap is selected. Rename its workcell and active variant sticks in an anvil, apply them, verify the scoreboard shows the author names plus canonical role, then reset both labels.
5. **Autosave:** change a solid block, a marker field, and container contents. Immediately click **Duplicate This Cell's Variant**; confirm autosave is expedited and the duplicate runs once automatically without a wait/retry instruction. Repeat with edits in multiple enabled cells and **Duplicate All Enabled Cells as Family**. Wait for the final clean state, reopen Studio, and verify all authored changes plus both clone operations round-trip.
6. **Capacity and independent sizes:** stage Hallway capacity `16×3×3` in the open Workcell Settings menu, apply it once, and make another workcell capacity `16×8×16`; confirm no existing object byte changes and the live relayout moves only the white-concrete cages without close/reopen. In the larger workcell, resize one variant to `16×3×16` and another to `3×3×3`; confirm exact independent dimensions, live reload of the loaded variant, and unchanged siblings. Confirm cropped authored content, connector collision, and shared/read-only objects each reject the single-variant resize without writes.
7. **Disable:** disable Tee, confirm its white-concrete cage remains while the GUI and scoreboard report Disabled, and confirm seed-`1337` evaluation excludes Tee pieces. Re-enable it and confirm participation returns; test export filtering separately on the portable fixture.
8. **Dynamic preview:** confirm evaluation moves through pending/stale to valid or an understood warning, reports theme/piece count, and renders the same protected block assembly on the negative-X side after reopen. Reach it through both **Go to Preview** and `/iris jigsaw preview goto`; verify edits, fluids, pistons, explosions, growth, fire, entities, and redstone cannot alter it.
9. **Variants and rules:** create a blank variant and duplicate one active variant; adjust one exact weight and chance; create `variant-2` through the all-enabled family action and confirm one exact-size clone per enabled workcell, duplicated memberships, and atomic active-family rebind. Change theme membership, depth/count rules, terminal status, and mandatory caps. Confirm only selected resources change and invalid rules fail atomically.
10. **Toolbox:** take schema-`2` named sticks for selection, capacity, per-variant size, labels, duplicate-one/family, preview, Flush Autosave, themes/rules, membership changes, caps, and deletion. Confirm bindings target the named context, active/valid icons are jigsaw/emerald, lime dye only labels theme membership, destructive tools require two uses, and schema-`1` or replaced-Studio tools are rejected.
11. **Deletion:** delete one owned inactive planar variant only after another remains; a spatial variant removes its dedicated active cell as long as another spatial variant remains. Add an external placement/reference and confirm project deletion is blocked; remove it, confirm deletion, and verify the complete owned closure plus manifest are removed.
12. **Ownership protection:** have a second player attempt a direct edit, chest use, `/setblock`, `/fill`, and WorldEdit-style mutation. Confirm each is denied across the active Studio world and the owner remains able to edit.
13. **Adoption:** apply an exclusive unowned graph in place without changing resource bytes; require a clone for a shared graph; reject a stale plan without writes; and clone a managed datapack import without changing the managed source.
14. **Registered conversion:** convert one registered jigsaw to an unused target, review fidelity warnings/provenance, and open the owned target. A non-jigsaw source and occupied target must fail without overwrite.
15. **Folia multi-region boundary:** use a workcell crossing chunks/regions. A fully loaded capture commits once; an unloaded intersection aborts the entire write. Automated coordinator coverage is not live Folia proof.
16. **Natural Iris placement:** attach the graph with a unique `placementId`, generate and inspect one natural start, restart, then repeat in new chunks. For cave placement, verify each requested anchor and a no-anchor skip.
17. **Vanilla export:** create a separate `VANILLA_PORTABLE` graph, keep all Iris-only themes/chance/rules/caps absent, export to zip, restart a clean Minecraft 26.2 world with it, locate the key, and inspect a natural instance. Do not substitute `/reload`.
18. **Platform runtime:** copy the saved Iris pack to Fabric, Forge, and NeoForge, validate it, and prove natural shared-core assembly. Bukkit-only authoring controls are not expected on those loaders.

Automated tests, plugin startup, Bukkit gameplay, cross-loader generation, and vanilla datapack loading are separate evidence. Report exactly which gates ran.

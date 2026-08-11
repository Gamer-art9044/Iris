# 22 - Native Structures & Datapacks

Structures that originate outside Iris packs: vanilla structures in Iris worlds, datapack structures, the Minecraft structure-block / `.nbt` system, and converting native structures into editable Iris resources. Objects and Iris jigsaws are `19 - Objects.md`, `20 - Object Placement.md`, and `21 - Jigsaw Structures.md`.

Terminology:

- **registered / native structure** — anything in Minecraft's live structure registry (vanilla, mod, datapack). Keys are namespaced (`minecraft:village_plains`, `towns_and_towers:village_ocean`).
- **Iris structure** — editable `structures/<key>.json` inside a pack.

Command listings are Bukkit/Paper; modded loaders expose a reduced set.

## Tutorial paths

Choose one path. Native placement does not require editable import, and installing a datapack does not require converting its structures into Iris objects.

### Path A: keep a native structure but adapt it to Iris terrain

Prerequisite: the structure appears in `/iris structure list <dimension>`. Use `/iris structure verify <dimension> radius=48` to confirm it reports `[native-eligible]` rather than `[disabled]` or `[unreachable]`. Merge a narrow adjustment into the declaring dimension; for example, this changes only plains villages:

```json
{
  "importedStructures": {
    "adjustments": [
      {
        "match": ["minecraft:village_plains"],
        "terrain": { "mode": "VACUUM" }
      }
    ]
  }
}
```

1. Find the registered key with `/iris structure list <dimension>`.
2. Run `/iris structure verify <dimension> radius=48` and confirm the key is eligible before changing it.
3. Add one `importedStructures.adjustments` entry in the dimension. Start with a narrow exact key and one operation such as `yShift`, `preserveSourceY`, or a terrain mode.
4. Validate the pack, reopen Studio or update the test-world snapshot, and generate new chunks.
5. Locate the key and inspect several starts. Existing chunks do not move.

The path passes when newly generated starts keep native blocks, entities, processors, and loot while the requested terrain operation is visible. Widen a prefix only after the exact-key test passes; a namespace or family prefix may affect many variants. If verify changes to `[disabled]`, remove the matching disable; if it is `[unreachable]`, fix the biome derivative mapping before tuning terrain.

### Path B: install a datapack for one Iris dimension

This Bukkit-family workflow keeps the datapack installed in Minecraft's global registry while scoping Iris-managed structure sets to dimensions that declare the source.

Prerequisites: a disposable Bukkit-family server, one declaring Iris dimension, one nondeclaring Iris dimension, and a vanilla control world. Merge the source into the declaring dimension only:

```json
{
  "datapackImports": [
    "https://modrinth.com/datapack/towns-and-towers"
  ]
}
```

1. Add the Modrinth or direct archive URL to `datapackImports` in the declaring dimension only.
2. Leave the URL out of a second test dimension. Keep one vanilla world available as a control.
3. Validate the pack, then run:

   ```text
   /iris datapack ingest restart=true
   ```

4. After the full restart, run `/iris datapack list`, then `/iris structure list <declaring-dimension>` and choose one registered structure key from that source.
5. Run `/iris structure verify <declaring-dimension> radius=48`. If the key is `[unreachable]`, set a compatible `vanillaDerivative` on an Iris biome before the generation test.
6. Create fresh declaring and nondeclaring Iris worlds. In all three worlds, use `/locate structure <key>` and generate new chunks.
7. Pass condition: the declaring Iris world locates and naturally generates the structure; the nondeclaring Iris world and vanilla world do neither. Restart without deleting the installed datapack and repeat the check.

If the key is absent after ingest, confirm the managed pack appears in `/iris datapack list` and that the requested restart completed; registry keys are not live on the installation boot. Removing a URL changes future per-world scope after restart; it does not delete existing chunks or generated structures. Declaring the same URL in two Iris dimensions intentionally enables the source in both.

### Path C: convert a registered structure for editing

1. Confirm the registered source generates natively first, because conversion is intentionally less faithful than Minecraft's native runtime.
2. Back up the target pack. For one registered jigsaw graph, run:

   ```text
   /iris jigsaw convert <dimension> <namespace:path> target=auto seed=1337
   ```

3. Conversion follows the registered start pool and reachable template-pool closure, writes a new add-only owned Iris graph, reports the imported piece/pool counts and fidelity-warning count, then opens Jigsaw Studio. `target=auto` changes a key such as `minecraft:village_plains` to `minecraft_village_plains`; use `target=<iris-path>` for a deliberate target.
4. Load each variant from the Studio control chest or triple-sneak menu and inspect its real blocks plus Mojang marker fields. After a workcell finishes loading/hydrating, block and container changes autosave; **Save Now** is only an immediate flush. Review the automatic seed-`1337` evaluation and permanent read-only block preview before accepting fidelity. The owned copy can be reopened with `/iris jigsaw open <dimension> <target>`.
5. For a non-jigsaw template or a bulk pass, use `/iris structure import <dimension>` instead. Review every per-structure result: successful bundles may coexist with failures.

`/iris jigsaw convert` accepts only a live registered jigsaw structure and refuses an occupied target. `/iris structure import` handles the broader importer passes described below. Both record source provenance and fidelity losses. A native list pool entry stays one weighted choice whose recursively first physical template and outer connectors are retained; later colocated children and their processors are omitted with a `LIST_ELEMENTS` warning instead of becoming separate alternatives. Every start-pool member remains a physical Iris piece even when it has no connectors, and an all-air template with at least one connector remains a non-collidable scaffold so its bounds can overlap attached physical pieces. Every non-start connectorless member in a pool with a distinct fallback also remains physical, regardless of pool size or air content, so weighted primary no-match attempts reach that fallback; an all-air retained member remains non-collidable. A singleton all-air connectorless member with no fallback or a self-fallback becomes an explicit empty entry with `connectorless_all_air_member_normalized_empty`; the observed waystone form uses the self-fallback case. The same member in a mixed no/self-fallback pool is omitted with `connectorless_all_air_mixed_member_omitted` because converting it to empty could terminate before later candidates; the loss records changed selection weights and RNG consumption. Other connectorless nonempty members in no/self-fallback non-start pools are omitted as unattachable with `connectorless_non_air_member_omitted`; that block loss also records exact fallback context plus selection-weight and RNG-consumption drift. Converted graphs explicitly use `branchFailurePolicy: TERMINATE_BRANCH`: after ordinary primary and direct-fallback candidates are exhausted, only that optional branch ends, while required physical fallbacks still fail. Explicit empty members and empty optional primary pools end the branch before the direct fallback. Native placement settings beyond start pool, maximum depth, and maximum distance, feature pool elements, alternate palettes, processors, entities, or other native-only behavior may not survive conversion. Use this path only when block geometry or graph topology must change; `nativeStructures` retains native processors, entities, spawners, loot, and placement behavior.

### Path D: put registered structures only on an Iris grid

Use this when the datapack should not generate from its own structure sets. First ingest and restart with the source URL as in Path B. After `/iris structure list <dimension>` confirms the keys, merge this fragment into that dimension:

```json
{
  "datapackImports": [
    "https://modrinth.com/datapack/dungeons-and-taverns"
  ],
  "importedStructures": {
    "disabled": ["nova_structures:"]
  },
  "structures": [
    {
      "placementId": "tutorial-native-tavern",
      "nativeStructures": [
        { "structure": "nova_structures:tavern_oak", "weight": 1 }
      ],
      "distribution": "RANDOM_SPREAD",
      "spacing": 24,
      "separation": 6,
      "salt": 776215551
    }
  ]
}
```

1. Validate the pack after the restart: `/iris pack validate pack=<dimension>`.
2. Open a fresh test world or update the world's pack snapshot and restart.
3. Run `/iris structure verify <dimension> radius=48`; the tavern must report `[iris-planned]`, not `[disabled]`, because explicit placement bypasses the disable list.
4. Use `/iris goto structure nova_structures:tavern_oak`, generate the planned chunk, and inspect native processors, entities, spawners, and loot.
5. Generate several new grid cells and confirm the structure does not appear away from Iris-planned starts.

If validation cannot resolve the key, the datapack is not live in the registry for that dimension; return to Path B and complete the restart and scope check. If verify reports `[iris-not-found]`, increase the radius or reduce spacing for the test. Existing natural starts remain in old chunks after the namespace is disabled.

## 1. Vanilla structures in Iris worlds

### 1.1 Default: everything generates

Every registered structure generates through its own native placement unless its key is disabled or a dimension-level Iris placement replaces its source. Changes only affect newly generated chunks.

### 1.2 Biome mapping for structure filters

Vanilla tests biomes against structure biome filters. Iris answers per Iris biome:

| Field on biome | Default | Purpose |
|---|---|---|
| `derivative` | `minecraft:the_void` | Vanilla biome this Iris biome reports generally. |
| `vanillaDerivative` | unset | Optional override for structure selection, spawn tables, imported features, biome tags. Wins when set. |

Refinements: a sea-role biome whose derivative is not ocean/river-like resolves to `minecraft:the_void`; shore-role falls back to `minecraft:beach`. **Non-`minecraft:` namespaces pass through** — point `vanillaDerivative` at a datapack/mod biome key that exists in the live registry.

A datapack structure whose filter lists only its own biomes never generates until an Iris biome reports one of those keys via `vanillaDerivative`. `/iris structure verify` reports `[unreachable] <key> needs <biomes>`.

```json
{
  "derivative": "minecraft:plains",
  "vanillaDerivative": "towns_and_towers:some_custom_biome"
}
```

### 1.3 `importedStructures` (dimension)

| Field | Default | Meaning |
|---|---|---|
| `disabled` | `[]` | Structure keys/prefixes to deny. |
| `undergroundYShift` | `0` (-512..512) | Vertical offset for underground-step structures only. Surface structures never use it. |
| `datapackOverrides` | `true` | Whether ingested datapacks may replace `minecraft:`-namespaced structure content (2.5). |
| `adjustments` | `[]` | Per-structure adjustments for structures still generating natively (1.4). |

#### Prefix matching

Used by `disabled` and `adjustments[].match`. Both sides trimmed and lowercased; key must start with pattern; then:

- Equal length → match.
- Pattern ends with `:`, `/`, or `_` → match (e.g. `"nova_structures:"` disables a namespace).
- Otherwise next character after pattern must be `/` or `_`.

`"minecraft:village"` matches village variants; `"nova_structures"` without trailing colon does **not** match the namespace.

```json
{
"importedStructures": {
  "disabled": ["minecraft:village", "minecraft:pillager_outpost"]
}
}
```

### 1.4 `adjustments[]`

Each entry (`match` selects targets by the same prefix rule):

| Field | Default | Meaning |
|---|---|---|
| `match` | `[]` | Keys/prefixes. Empty matches nothing. |
| `yShift` | `0` (-512..512) | Vertical offset; stacks across matches; clamped to build bounds. |
| `yBand` | unset | Absolute world-Y band `{min, max}`: structure midpoint lands in band, deterministic per start chunk. |
| `preserveSourceY` | `false` | Skip Iris burial repositioning; keep vanilla Y. `undergroundYShift` and `yShift` still apply on top. |
| `stilt` | unset | Foundation columns: `maxDepth` (default 64), `palette` (default cobblestone), `spacing`. (`supportNonOccluding` applies to Iris-assembled structures.) |
| `terrain` | unset (= `SOURCE`) | Terrain-integration override. |

Vegetation clearing is automatic (trees intersecting piece envelopes removed).

**Merge:** `yShift` adds; `preserveSourceY` OR-ed; `stilt`, `terrain`, `yBand` last-match-wins. Broad prefix first, specific overrides after.

**Vertical precedence:**

```
preserveSourceY  >  yBand  >  burial (underground steps)  >  plain yShift
```

Three structures honor only `yShift` among these controls: `minecraft:monument` (aligned 24 below sea level), `minecraft:desert_pyramid` (one block above lowest surface Y of footprint), `minecraft:jungle_pyramid` (one block above average surface Y).

#### Terrain modes

| Mode | Behavior |
|---|---|
| `SOURCE` (default) | Replay structure's registered terrain adaptation, including vanilla BURY/ENCAPSULATE fill reimplemented with surrounding terrain material. |
| `PRESERVE` | Disable terrain integration. |
| `BORE` | Clear padded piece volume (box) before placement. |
| `FORCE_CARVE` | Clear padded envelope with `shape`: `BOX` / `ROUNDED` / `ERODED`. |
| `VACUUM` | Raise surface terrain to structure ground planes with fixed 12-block falloff. Never lowers ground. |
| `ENCASE` | Fill padded volume with solid blocks before placement (air/liquid only). Structure carves interiors. `encasePalette` optional (defaults: stone/deepslate overworld, netherrack nether, end stone end). |

Padding: `horizontalPadding` (0..128), `ceilingPadding` (0..128), `floorPadding` (0..64; 0 preserves floor). ERODED: `erosionStrength` (default 0.8), `erosionFrequency` (0.07), `lobeFrequency`, `lobeStrength` (0.85).

#### Examples

Stronghold deep band + encase:

```json
{
  "match": ["minecraft:stronghold"],
  "yBand": { "min": -120, "max": -20 },
  "terrain": {
    "mode": "ENCASE",
    "horizontalPadding": 4,
    "ceilingPadding": 4,
    "floorPadding": 4,
    "encasePalette": {
      "zoom": 1,
      "palette": [
        { "block": "minecraft:stone_bricks", "weight": 6 },
        { "block": "minecraft:mossy_stone_bricks", "weight": 2 },
        { "block": "minecraft:cracked_stone_bricks", "weight": 2 },
        { "block": "minecraft:cobblestone", "weight": 1 }
      ]
    }
  }
}
```

Shift trial chambers; preserve mineshaft Y; stilt villages:

```json
[
{ "match": ["minecraft:trial_chambers"], "yShift": -64 },
{ "match": ["minecraft:mineshaft"], "preserveSourceY": true },
{
  "match": ["minecraft:village"],
  "stilt": { "maxDepth": 768, "palette": { "palette": [ { "block": "minecraft:cobblestone" } ] } }
}
]
```

Broad then specific (last-match-wins):

```json
[
{ "match": ["towns_and_towers:"], "terrain": { "mode": "VACUUM" } },
{
  "match": [
    "towns_and_towers:mimic_desert",
    "towns_and_towers:pillager_outpost_ocean",
    "towns_and_towers:village_ocean",
    "towns_and_towers:wreckage_ocean"
  ],
  "terrain": { "mode": "PRESERVE" }
}
]
```

## 2. Datapack structures

### 2.1 `datapackImports`

Dimension-file list of datapack sources Iris downloads and installs:

```json
{
"datapackImports": [
  "https://modrinth.com/datapack/towns-and-towers",
  "https://modrinth.com/datapack/dungeons-and-taverns"
]
}
```

A `datapackImports` URL belongs to the dimension that declares it. Bukkit exposes installed resources through the server-wide registry, but before initial chunks load Iris removes disallowed managed structure sets and structure definitions from each world's generation state. Vanilla worlds and Iris worlds whose active dimension does not declare the source therefore neither generate nor locate those structures. Declaring the same URL in multiple dimensions deliberately shares its structures; if multiple managed sources claim the same key, every owner must be declared because the registry winner cannot be inferred safely.

Accepted URL forms:

- **Modrinth project page** — latest datapack version for the server's Minecraft version.
- **Pinned Modrinth version** — any `.../version/<token>` URL.
- **Any other URL** — direct zip download, tracked by ETag/hash.

Checksum-verified when Modrinth publishes a hash; size-capped.

### 2.2 Where files land; when ingest runs

Installed datapacks are real Minecraft datapacks at `<level root>/datapacks/<id>/`, each with `.iris-managed.json`. Unmanaged datapacks are never touched; id `iris` is reserved. Cache/staging/manifest under `plugins/Iris/datapacks/`.

Ingest and recovery run synchronously in Iris's startup admission gate when `general.autoIngestDatapacks` is enabled (default true); players and every Iris world/Studio creation path remain locked until that phase is valid. A persisted manifest/configuration/content fingerprint lets an unchanged boot skip remote resolution and full revalidation, and Iris refreshes that fingerprint after its own authorized post-start import maintenance; URL, Minecraft/Iris version, override policy, external manifest edits, staging, transaction, installed content, or cache corruption still invalidates reuse and runs the full fail-closed path. Minecraft builds worldgen registries at server start, so a **newly installed or repaired** datapack requires a clean restart before admission; after it returns, keys are live only in the per-world structure state of declaring Iris dimensions.

Scratch validation rejects links, junction-like special files, and real cross-volume entries. On Windows/Java 25, Iris also verifies the drive root and volume serial when the JDK reports unequal `FileStore` identities only because a path crossed the legacy 247-character prefix boundary; unresolved cleanup, identity, transaction, or validation failures remain blocking and create no world artifacts.

### 2.3 Manual commands

```
/iris datapack ingest [restart=false]    (alias: pull)
/iris datapack list                      (alias: ls)
/iris datapack remove <id>               (alias: rm)
```

`ingest` downloads each distinct URL declared by any loaded dimension while retaining the per-dimension ownership relationship used by the generation and locate state. `restart` defaults false (Iris tells you a restart is required). `remove` refuses unmanaged datapacks — also delete the URL or a later startup ingest reinstalls it. Scope changes do not delete installed datapacks, previously generated chunks, or existing structures.

### 2.4 Usage patterns

**(a) Natural generation.** Import, restart. Check with `/iris structure list <dimension>` (writes `<pack>/.iris/structure-index.json`) and `/iris structure verify <dimension>` (`[native-eligible]` vs `[unreachable]`). Fix unreachable biomes via `vanillaDerivative`, or use (c).

**(b) Replace vanilla.** Disable vanilla families; datapack replacements keep generating:

```json
{
"importedStructures": {
  "datapackOverrides": true,
  "disabled": [
    "minecraft:village",
    "minecraft:pillager_outpost",
    "nova_structures:"
  ],
  "adjustments": [
    { "match": ["towns_and_towers:"], "terrain": { "mode": "VACUUM" } },
    { "match": ["towns_and_towers:mimic_desert", "towns_and_towers:pillager_outpost_ocean",
                 "towns_and_towers:village_ocean", "towns_and_towers:wreckage_ocean"],
      "terrain": { "mode": "PRESERVE" } }
  ]
}
}
```

**(c) Manual placement only.** Disable the datapack namespace, then place specific keys with `nativeStructures` — see **`disabled` never blocks an explicit placement** below:

```json
{
"structures": [
  {
    "placementId": "dnt-taverns-temperate",
    "nativeStructures": [
      { "structure": "nova_structures:tavern_oak",    "weight": 4 },
      { "structure": "nova_structures:tavern_birch",  "weight": 3 },
      { "structure": "nova_structures:tavern_cherry", "weight": 2 },
      { "structure": "nova_structures:shrine_tower",  "weight": 1 }
    ],
    "distribution": "RANDOM_SPREAD",
    "spacing": 24,
    "separation": 6,
    "salt": 776215551
  }
]
}
```

### 2.5 `datapackOverrides`

When `false`, Iris strips `data/minecraft/worldgen/structure_set|structure|template_pool/` and `data/minecraft/structure/` from every installed copy. Resolves **globally** — one dimension setting `false` strips for all. Non-`minecraft:` content is unaffected (disable those keys explicitly).

## 3. Placing specific native structures (`nativeStructures`)

`structures[]` on dimension/region/biome hosts two backends — exactly one per placement:

- `structures: ["<iris key>"]` — Iris assemblies (`21 - Jigsaw Structures.md`).
- `nativeStructures: [{ structure, weight, jigsaw }]` — registered structures via Minecraft machinery at Iris-chosen points, full native fidelity.

### 3.1 Entry fields

| Field | Default | Meaning |
|---|---|---|
| `structure` | required | Registered structure key (must exist live). |
| `weight` | `1` (min 1) | Weighted selection among sources. |
| `jigsaw` | unset | Overrides for registered **jigsaw** structures only: `startPool`, `startJigsawName`, `maxDepth` (0..20), `maxDistanceHorizontal` (1..128), `maxDistanceVertical` (1..4064), `useExpansionHack`, `projectStartToHeightmap` (`SOURCE`/`NONE`/heightmap types), `dimensionPaddingBottom` and `dimensionPaddingTop` (nonnegative distance from floor/ceiling), and `liquidSettings`. Null/unset values preserve the registered definition. |

Placement grid fields (`distribution`, `spacing`/`separation`/`salt`, `density`, rings, heights, `underground`, `underwater`, `placementId`) match the **Natural placement** section of `21 - Jigsaw Structures.md`, except the native backend supports **every** terrain mode including `VACUUM` and `ENCASE`, plus `stilt` (including `spacing`).

Scoping matches Iris placements. Validation requires the structure's effective assembly span stay inside Minecraft's 128-block (8-chunk) structure reference range.

### 3.2 `disabled` never blocks an explicit placement

The placement injector generates planned starts without consulting `disabled` and bypasses the structure's own biome filter. "Disable namespace, re-place explicitly" is supported.

### 3.3 `nativeSuppression: REPLACE_SOURCE`

- **Dimension-level placements only** — blocking pack error elsewhere.
- With `nativeStructures`: suppresses that key's natural generation so it exists only where the placement puts it.
- With Iris `structures`: suppresses each referenced structure's `vanillaSource`; pack validation demands the graph guarantees output — no native fallback. Iris-backend `REPLACE_SOURCE` that produces nothing throws at runtime. Native-backend unusable starts are recorded invalid and skipped silently (still suppressed).

Example — ancient cities replaced by Iris-positioned native starts:

```json
{
  "nativeStructures": [ { "structure": "minecraft:ancient_city" } ],
  "placementId": "ancient-city-native",
  "nativeSuppression": "REPLACE_SOURCE",
  "underground": true,
  "minHeight": -220,
  "maxHeight": -220,
  "distribution": "RANDOM_SPREAD",
  "spacing": 64,
  "separation": 5,
  "salt": 42069,
  "terrain": {
    "mode": "FORCE_CARVE",
    "horizontalPadding": 14,
    "ceilingPadding": 12,
    "shape": "ERODED",
    "erosionStrength": 1.0,
    "erosionFrequency": 0.05
  },
  "stilt": {
    "maxDepth": 768,
    "palette": {
      "palette": [
        { "block": "minecraft:deepslate_bricks", "weight": 6 },
        { "block": "minecraft:cracked_deepslate_bricks", "weight": 1 },
        { "block": "minecraft:deepslate_tiles", "weight": 2 },
        { "block": "minecraft:cracked_deepslate_tiles", "weight": 1 }
      ]
    }
  }
}
```

### 3.4 Tool reporting

| Configuration | `/iris structure verify` | `/iris goto structure <key>` |
|---|---|---|
| Registered, not disabled, not placed | `[native-eligible]` or `[unreachable] ... needs <biomes>` | Vanilla locate |
| Placed via `nativeStructures` (even if also disabled) | `[iris-planned] <key> @ x,y,z` / `[iris-not-found]` | Iris grid search |
| Disabled, not placed | `[disabled]` | "disabled by this dimension's importedStructures settings" |

A key that is both disabled and placed reports as Iris-placed.

## 4. Minecraft structure-block system

Structure blocks save/load `.nbt` templates; jigsaw blocks wire pools. Iris Jigsaw Studio is the documented in-game workflow for editable Iris graphs; use external vanilla Structure Block and datapack references only when authoring raw `.nbt` assets outside Iris.

How an authored `.nbt` reaches an Iris world:

**(a) Through a datapack (native generation).** Ship under `data/<ns>/structure/`, add `worldgen/template_pool`, `worldgen/structure`, `worldgen/structure_set`, zip, host or Modrinth, add URL to `datapackImports`, `/iris datapack ingest restart=true`. Then natural generation, `adjustments`, or `nativeStructures`.

**(b) Import into Iris resources.** `/iris structure import <dimension>` (section 5). Template pass enumerates **registered** templates only — loose saves in `<world>/generated/` are not enumerated; package them into a datapack first.

Template-import fidelity (lossy by design): first palette only; structure voids and structure blocks dropped; jigsaw blocks resolved to `final_state` (graph rebuilt by separate jigsaw pass); entities not converted; block entities captured.

## 5. Importing native structures into Iris resources

You do not need import just to place — `nativeStructures` places any registered key with full fidelity. Import only when you need Iris object, pool, or piece resources. Manual imports are editable transaction-owned copies; automatic datapack imports remain managed by ingest and must be cloned before Jigsaw Studio editing.

### 5.1 `/iris structure import <dimension>`

Four passes, always overwriting its own previous output:

1. **Jigsaw rebuild** — registered jigsaw structures → editable pools/pieces/objects. Connector `final_state`, signed `selection_priority`, and signed `placement_priority` values are retained in the Iris piece metadata, and the generated root writes `branchFailurePolicy: TERMINATE_BRANCH` so unmatched optional branches preserve native termination behavior.
2. **Template import** — registered `.nbt` templates → `objects/<name>.iob` + single-piece `jigsaw-pieces/<name>.json`.
3. **Template groups** — fixed multi-template structures (shipwrecks, ruined portals, ocean ruins, nether fossils) → one Iris structure each with every variant in the pool.
4. **Capture** — only non-jigsaw registry keys for which the first pass found no same-key template are captured via a scratch world. This pass never rewrites a successful or failed jigsaw conversion. The standalone `/iris structure capture <dimension>` command remains unfiltered. Structures spanning more than **48 blocks** on any axis are skipped (strongholds, mansions, monuments stay native-only).

Naming: `minecraft:village_plains` → `minecraft_village_plains`. Generated structures carry `vanillaSource` for locate and `REPLACE_SOURCE`.

`/iris studio importvanilla <dimension> [variants=3] [structures=true]` also imports vanilla trees/features as objects, plus structure passes when `structures=true`.

### 5.2 Ownership, manual editing, and `unowned_resource`

Imports use per-bundle ownership manifests (`<pack>/.iris/structure-manifests/`). Failure:

```
Import conflict for '<name>': <path> is unowned_resource. Existing authored files were preserved.
```

Iris found a file it did not write and refused to clobber it. `modified_resource` means Iris wrote it, you edited it, and the hash no longer matches. Rename the target, restore the exact owned bytes, or leave the key native. A successfully converted/manual-imported jigsaw can be opened directly with `/iris jigsaw open <dimension> <key>` because its ownership manifest is editable. A separate pre-existing Iris graph with no manifest uses the `adopt inspect` then `adopt apply` workflow in `21 - Jigsaw Structures.md`; no import command is required for that case.

### 5.3 Automatic datapack import

`general.autoImportDatapackStructures` (default **false**) converts each ingested datapack's structures into pack resources on ingest. These bundles carry `MANAGED_DATAPACK` provenance: ingest refresh owns them, and removing the source URL may clean them. Jigsaw Studio therefore displays their variants as read-only and forbids an in-place ownership claim. Inspect and apply a private clone before editing:

```text
/iris jigsaw adopt inspect <dimension> <managed-iris-key> target=<editable-key> strategy=clone
/iris jigsaw adopt apply <plan-uuid>
```

Inspect verifies the existing manifest is exactly a managed vanilla/datapack Iris assembly, pins the complete source and target read set, and reports `CLONE_REQUIRED` or a blocking diagnostic. Apply re-hashes under the pack mutation lock, atomically writes a deep clone with deterministic internal reference rewrites plus its ownership receipt, leaves the managed source unchanged, and opens the editable clone. An expired, consumed, or stale plan writes nothing. There is no adoption rollback command; keep the pack backup made before conversion.

Automatic import is off by default because native generation and `nativeStructures` never need the copies, and conversion can write thousands of files. Deterministic source-content and graph-validation failures retain successfully written bundles and record the attempted source, importer-format, and target-pack revision, so the same failures do not repeat every boot; a source update, importer-format change, or different target retries them. Unexpected reflection, I/O, transaction, and runtime failures remain pending and retry. Removing a URL from `datapackImports` cleans only bundles still owned by that managed source; an adopted editable clone is independent.

Third-party jigsaw templates that use the legacy slab property `half=top|bottom` or the exact known misspelling `minecraft:chisled_polished_blackstone` are normalized to current Minecraft block data during editable conversion. Other invalid final-state values are recorded as fidelity loss and omitted without internal-error telemetry. Invalid structure graphs remain per-structure failures, but expected graph-contract rejections are reported as concise import results instead of internal Iris stack traces; unexpected reflection, I/O, and runtime failures retain full diagnostic traces.

## 6. Verification and debugging

```
/iris structure list <dimension>            # write + print key index
/iris structure verify <dimension> [radius=48]   # eligibility + placement (alias: locateall)
/iris structure info <dimension> <structure>     # Iris: compile + sample assembly
/iris structure place <dimension> <structure>    # Iris: stamp at feet (player)
/iris goto structure <key>                  # locate + teleport
/iris goto unregistered                     # excluded keys + reasons
```

`structure place` resolves the graph and edit resources from the named dimension pack, then stamps the assembled pieces into the player's current world. The pack's Studio and generation engine do not need to remain open for this explicit placement.

`verify` tags: `[iris-planned]`, `[iris-not-found]`, `[iris-search-limit]`, `[disabled]`, `[unreachable]`, `[native-eligible]`, `[error]`. Placements checked first — disabled-but-placed shows as `[iris-planned]`.

### Traps

- Worlds snapshot the pack — push with `/iris developer update-world world=<w> pack=<dim> confirm=true`, then restart. Backup first.
- Keyed optional args: `radius=200`, not bare `200`.
- New datapack structures need a restart for registry registration.
- Only new chunks change.
- Namespace disables need the colon: `"nova_structures:"`.
- `REPLACE_SOURCE` has no fallback — validate the graph before shipping.
- `datapackOverrides: false` anywhere strips `minecraft:` overrides server-wide.

## Command reference

| Command | Aliases | Parameters |
|---|---|---|
| `/iris datapack ingest` | `pull` | `restart=false` |
| `/iris datapack list` | `ls` | |
| `/iris datapack remove <id>` | `rm` | |
| `/iris structure list <dimension>` | `ls` | |
| `/iris structure import <dimension>` | `import-all`, `reimport`, `imp`, `all` | |
| `/iris structure capture <dimension>` | `cap` | |
| `/iris structure verify <dimension>` | `locateall` | `radius=48` (1..1000 chunks) |
| `/iris structure info <dimension> <structure>` | | |
| `/iris structure place <dimension> <structure>` | `p` | player only |
| `/iris jigsaw convert <dimension> <source>` | `import`, `import-vanilla` | `target=auto seed=1337`; Bukkit player only; source is a registered jigsaw key |
| `/iris jigsaw adopt inspect <dimension> <source>` | | `target=auto strategy=auto`; Bukkit player only; source is an existing Iris graph |
| `/iris jigsaw adopt apply <planId>` | | Bukkit player only; no active/opening Jigsaw Studio |
| `/iris goto structure <key>` | `/iris find structure` | |
| `/iris goto unregistered` | | |
| `/iris developer update-world` | | `world=<w> pack=<dim> confirm=true [fresh-download=false]` — all keyed |

Related dimension fields: `datapackImports`, `importedStructures`, `structures[]`. Settings (`plugins/Iris/settings.json`): `general.autoIngestDatapacks` (default true), `general.autoImportDatapackStructures` (default false).

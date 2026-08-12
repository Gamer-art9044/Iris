# 25 - Pack Management

This page covers the lifecycle of a pack outside the editor: pulling one down from GitHub, validating it, clearing out resources nothing references, packaging it for distribution, and — carefully — swapping the pack snapshot inside a world that already exists. Authoring packs live under the platform packs root; production worlds hold their own copy at `<world>/iris/pack`.

See also: `03 - Configuration.md`, `04 - Commands & Permissions.md`, `05 - Concepts & Pack Layout.md`, `06 - Worlds & Lifecycle.md`, `10 - Studio & VSCode Schemas.md`, `24 - Pack Mods & Snippets.md`, `27 - Example - Configuring Overworld.md`.

## The mental model

A pack exists in up to three places at once, and confusing them is the usual source of "my edit did nothing":

- **The authoring copy**, at `packs/<key>/`. This is what Studio edits and what `/iris create` copies from.
- **The world snapshot**, at `<world>/iris/pack`. Every Iris world holds a full copy of the pack it was created with. It is frozen at creation time. Editing the authoring copy never touches an existing world.
- **The export**, at `exports/<key>.iris`. A zip of the dimension's dependency closure, for handing to somebody else.

Validation runs against a directory, not a key, so a pack can be valid in the workspace and stale in a world. Iris caches startup validation results and re-uses them only when the pack bytes, the visible pack set, the platform, and the relevant game registries all still match — otherwise it revalidates.

## Walkthrough: take a pack from workspace to release

Run this after the pack works in Studio and before you create or update a production world.

**1. Place the authoring tree.** `packs/<key>/` must contain at least one `dimensions/*.json`. On Bukkit that's the plugin data folder's `packs/`; on Fabric/Forge/NeoForge it's `config/irisworldgen/packs/`.

**2. Validate and read the result.**

```
# Bukkit
/iris pack validate pack=<key>
/iris pack status pack=<key>

# Modded
/iris pack validate <key>
/iris pack status <key>
```

`validate` re-runs every check and republishes the result. `status` prints the currently published result, which may be a reused startup result — run `validate` first if you've edited files. Continue only when the pack reports loadable with zero blocking errors. Warnings are informational, but read them: unresolved content keys become blocking the moment strict content mode is on.

**3. Preview cleanup without writing anything.**

```
# Bukkit
/iris pack cleanup <key> mode=preview

# Modded
/iris pack cleanup <key>
```

Preview is the default on both platforms and touches nothing. Read every candidate. Cleanup finds resources with no inbound reference, which includes resources you load dynamically or reference from something it doesn't scan — if a candidate is intentional, stop here and leave cleanup unapplied.

**4. Apply cleanup only if the preview was clean.**

```
# Bukkit
/iris pack cleanup <key> mode=apply

# Modded
/iris pack cleanup <key> apply
```

Files move into `<pack>/.iris-trash/<timestamp>/` rather than being deleted, and the pack's cached validation result is dropped. Validate again afterwards. If cleanup took something you needed, `/iris pack restore <key> mode=apply` (Bukkit) or `/iris pack restore <key> apply` (modded) moves the most recent quarantine dump back.

**5. Package the closure.**

```
# Bukkit
/iris studio package dimension=<key>

# Modded
/iris studio package <key>
```

Success is `exports/<key>.iris` plus a completion message. The source pack and every world snapshot are untouched. **Read the "What the package actually contains" section below before shipping** — neither compiler writes `spawners/` or `markers/`, so a pack that uses ambient spawning needs those folders added back by hand.

**6. Test on a disposable world.** Create a fresh world from the release pack, walk it, restart the server, and walk it again. For breaking pack changes, ship a new world rather than updating an old one.

**7. Only then consider replacing an existing world's snapshot.** Take a backup first and use the update-world procedure at the bottom of this page.

The loop passes when the source closure validates, the package command produces the expected export, a fresh world from that export reloads cleanly, and the world's snapshot matches what you intended to ship. If your release process distributes the `.iris` file rather than the source tree, unpack it and validate it separately — the packager's output is not automatically validated.

## Pack workspace

| Item | Rule |
|------|------|
| Packs root | Bukkit: plugin data folder `packs/`. Modded: `config/irisworldgen/packs/` |
| Visible packs | Non-hidden directories directly under the packs root |
| "Present" | The directory resolves inside the packs root, passes a safe-tree check (no symlinks escaping), has a real `dimensions/` directory, and contains at least one regular `.json` file in it. Content is never parsed, so a pack with broken JSON is still "present" and will not be re-downloaded |
| Safe key | Download destination folder names must match `[a-z0-9_-]+` |

## Download

| Command | Syntax |
|---------|--------|
| Bukkit | `/iris download <pack> [branch=stable] [overwrite=false]` (alias `/iris dl`) |
| Modded | `/iris download <pack> [branch] [overwrite]` (alias `/iris dl`) |

| Param | Default | What it does |
|-------|---------|--------------|
| `pack` | required | The folder key, or the repository short name under the `IrisDimensions` GitHub org |
| `branch` | `stable` | The Git ref to pull. Ignored for the managed beta packs, which always use their release zip |
| `overwrite` | `false` | Replace a pack that is already present. Without it, a present pack short-circuits before any network call. Alias `force` on Bukkit |

`overworld` and `underworld` are **managed beta packs**: they resolve to fixed release zips rather than branch archives.

| Pack | Source |
|------|--------|
| `overworld` | `https://github.com/IrisDimensions/overworld/releases/download/beta/overworld.zip` |
| `underworld` | `https://github.com/IrisDimensions/underworld/releases/download/beta/underworld.zip` |

Anything else resolves to `IrisDimensions/<pack>/<branch>` and downloads the GitHub archive for that ref.

### Install pipeline (`PackDownloader`)

1. Take a per-key/per-ref lock, so a startup bootstrap and a manual command can't fetch the same pack twice.
2. If the pack is already present and `overwrite` is off, return without touching the network.
3. Download the zip under hard limits: archive at most 512 MiB, at most 100,000 entries, at most 2 GiB total uncompressed, at most 256 MiB per file. Exceeding any of these aborts the install.
4. Unpack into a temporary staging directory and require exactly one pack home directory inside the archive.
5. Open the staging tree with the datapack-compiler loader and pick the dimension. A plain download with no expected key requires exactly one dimension in the archive. A managed or listing-driven download names its expected key, requires exactly one dimension matching it, and keeps any additional dimension resources in the pack.
6. Run `PackValidator.validate` against the staging tree. Any blocking error aborts the install and prints the errors — nothing is published.
7. Publish atomically into `packs/<key>/`, refusing symlinked targets and refusing a key that conflicts with a different folder's dimension key. The validation result is published to the registry as part of the same step.

Startup treats the two managed packs independently: an operator who has edited or symlinked one of them keeps their version, and that doesn't stop the other from updating.

## Validate

| Command | Syntax |
|---------|--------|
| Bukkit | `/iris pack validate [pack=<key>]` (alias `v`) |
| Modded | `/iris pack validate [<pack>]` (alias `v`) |
| Bukkit | `/iris pack status [pack=<key>]` (alias `s`) |
| Modded | `/iris pack status [<pack>]` (alias `s`) |

Omitting the pack validates every visible pack and reports how many are broken. `status` reads the published result instead of re-running anything, so after editing files `status` can be stale until you run `validate`.

### What gets checked (`PackValidator`)

| Check | Blocking or warning |
|-------|---------------------|
| Pack folder missing, `dimensions/` missing, or no dimension JSON in it | Blocking, and stops the rest of validation |
| Dimension JSON integrity | Blocking errors and warnings, as emitted |
| Legacy cave-profile field names, in dimensions/regions/biomes and in `snippet/cave-profile/` | Blocking, with the replacement name named |
| Loot graph — every referenced loot table resolves | Blocking |
| Removed worldgen fields (currently `fluidBodies`) | Blocking |
| Object surface support | Blocking |
| `rotation` / `translate` / `scale` on surfaces that don't support them | Blocking |
| Structure graph and compiled structure graph | Errors blocking, warnings advisory |
| Native structure replacement envelopes | Blocking |
| Spawner entries pointing at entities that exist, across both `spawns` and `initialSpawns` | Blocking |
| Custom biome spawn category resolution | Blocking |
| Content keys and block properties | Blocking when `general.strictContentKeys` is on or `-Diris.strictContent` is set, otherwise warnings. Palette-sourced findings stay advisory either way |

A pack is loadable when it has zero blocking errors. `status` prints the blocking count and up to ten warnings plus a "more" count.

### The validation cache

Bukkit persists both successful and failed startup validation results and reuses them only when everything below still matches:

- the exact set of visible pack names,
- a content fingerprint over the pack bytes,
- the validator's own schema version,
- strict-content mode,
- the platform name, Minecraft version, and Iris version,
- the sorted key sets of the live block, biome, item, and entity registries,
- the sorted key sets of the live structure, jigsaw-structure, template-pool, structure-set, and object-feature hooks.

Any mismatch — changed bytes, a mod added or removed, a version bump, a missing or extra pack, a malformed or oversized cache file, or a manual `validate` — discards the cache. Cached *failures* stay blocking, so a pack that failed validation at startup will not authorize world or Studio creation until it validates clean.

## Cleanup (unused resources)

| Command | Mode | Behaviour |
|---------|------|-----------|
| Bukkit `/iris pack cleanup <pack> [mode=preview]` (alias `c`) | `preview` (default) | List candidates. No writes |
| | `apply` | Move candidates to quarantine |
| Modded `/iris pack cleanup <pack> [apply]` (alias `c`) | no literal (default) | Preview |
| | `apply` | Move candidates to quarantine |

Folders scanned for unreferenced JSON: `biomes`, `regions`, `entities`, `spawners`, `loot`, `generators`, `expressions`, `markers`, `blocks`, `mods`.

Excluded from the reference corpus entirely: `.iris-trash`, `datapack-imports`, `externaldatapacks`, `internaldatapacks`, `datapacks`, `cache`, `objects`, `.iris`.

Applying re-scans from scratch rather than trusting an earlier preview, so a preview you ran an hour ago can't quarantine something you've since started using. Quarantined files land under `<pack>/.iris-trash/<yyyyMMdd-HHmmss-SSS>/`. A failed apply rolls back what it can and reports any paths that are still quarantined so you can restore them by hand. A successful apply drops the pack's cached validation result.

## Restore

| Command | Mode | Behaviour |
|---------|------|-----------|
| Bukkit `/iris pack restore <pack> [mode=preview]` (alias `r`) | `preview` (default) | List the files in the most recent quarantine dump, plus any destination conflicts |
| | `apply` | Move them back |
| Modded `/iris pack restore <pack> [apply]` (alias `r`) | no literal (default) | Preview |
| | `apply` | Move them back |

Restore operates on the **latest** dump only. It refuses the whole operation when any destination path already exists and reports the conflict list instead of merging — resolve those by hand first. With no quarantine dump present, nothing is restored and nothing is reported as an error.

## Package (export)

| Command | Syntax |
|---------|--------|
| Bukkit | `/iris studio package [dimension=default] [obfuscate=false] [minify=true]` (method alias `pkg`) |
| Modded | `/iris studio package [<pack>]` (alias `pkg`) |

| Param | Default | What it does |
|-------|---------|--------------|
| `dimension` | contextual, else `default` | The dimension to package. The closure is walked from here |
| `obfuscate` | `false` | Rename every object to a random UUID in the export and rewrite placement references to match. Bukkit only |
| `minify` | `true` | Write JSON with no indentation. Bukkit only; the modded packager always minifies |

Output is `exports/<dimensionKey>.iris` — under the plugin data folder on Bukkit, under `config/irisworldgen/exports/` on modded. The staging folder is deleted after zipping (compression level 9). Neither the source pack nor any world snapshot is modified.

### What the package actually contains

Both compilers walk the dimension, its regions, their biomes, and collect generators, loot tables, entity keys, object keys, and the structure closure. Both write `package.json` with a content hash, a timestamp, and the dimension's `version`.

Written to the export:

`dimensions/`, `regions/`, `biomes/`, `generators/`, `blocks/` (all block definitions in the pack, not just referenced ones), `loot/`, `entities/`, `objects/`, the structure closure, `package.json`.

**Not written by either compiler:**

- `spawners/` — spawner keys are resolved only to collect the entity keys they reference. The spawner JSON files themselves never reach the export, so a pack that uses ambient spawning produces an export whose `entitySpawners` references all dangle.
- `markers/` — never collected or written.
- `mods/` — never collected or written. Harmless, since nothing applies them (see `24 - Pack Mods & Snippets.md`).
- `expressions/`, `caves/`, `images/` and other folders outside the collected set.

Platform differences beyond that:

- Bukkit re-serializes from the loaded object graph, which inlines snippet references. Modded copies the source JSON verbatim and does not copy `snippet/`, so snippet references in a modded export dangle too.
- Bukkit collects entity keys from both `spawns` and `initialSpawns`; modded collects from `spawns` only.

If you distribute the `.iris` artifact, unpack it, add the missing folders, and validate the unpacked tree before you publish. If you distribute the source pack directory, none of this applies.

## Developer update-world (unsafe)

| Command |
|---------|
| `/iris developer update-world world=<world> pack=<dimension> confirm=true [fresh-download=false]` |

Aliases: the command group is `/iris developer` or `/iris dev`; the subcommand is `update-world` or `^world`. `pack` also accepts the alias `dimension`, `confirm` accepts `c`, and `fresh-download` accepts `fresh` or `new`.

| Param | Default | What it does |
|-------|---------|--------------|
| `world` | contextual | The world whose `iris/pack` snapshot gets replaced |
| `pack` | contextual | The source dimension, resolved from the live packs root |
| `confirm` | `false` | Required. Without it the command only prints the warning and exits |
| `fresh-download` | `false` | Re-download the pack from GitHub before installing |

What it does:

1. Refuse unless `confirm=true`.
2. Optionally re-download the pack.
3. Take a `PACK_MUTATION` / `PACK_PUBLISH` lease, so it can't race another pack publish. If the lease is busy it reports that and stops.
4. Copy the pack into a staging directory next to the target, confirm the dimension loads from staging, then publish atomically over `<world>/iris/pack`.
5. Invalidate the previous validation result for that exact root and validate the newly published snapshot. **If validation fails, the publication rolls back** and the world keeps its old pack.
6. If an engine is still holding the old pack data, restart the server with the reason `"An active Iris world pack was replaced."`

This is unsafe for production without a backup for reasons the command can't fix: chunks that already exist keep their old terrain. Only future generation and pack-driven systems — loot, spawners, effects, block drops — see the new content. A pack change that alters terrain shape leaves a visible seam at the edge of the generated region. Prefer staging a new world whenever the pack's terrain contract changes.

## Related operations

| Task | Where |
|------|-------|
| Create a studio project from a template | `/iris studio create` — `10 - Studio & VSCode Schemas.md` |
| Open VSCode with generated schemas | `/iris studio vscode` — `10 - Studio & VSCode Schemas.md` |
| Import vanilla objects and structures into a pack | `/iris studio importvanilla` — `19 - Objects.md` |
| Structure import and conversion | `/iris structure …` — `22 - Native Structures & Datapacks.md` |
| Strict content key enforcement | `settings.general.strictContentKeys` — `03 - Configuration.md` |
| Datapack bootstrap and install | `/iris datapack` — `22 - Native Structures & Datapacks.md` |

## Checklist

1. Place or download the pack under `packs/<key>/` with at least one `dimensions/*.json`.
2. Validate until loadable: `/iris pack validate pack=<key>` on Bukkit, `/iris pack validate <key>` on modded.
3. Optionally preview cleanup, review every candidate, then apply and validate again. Restore if it took something needed.
4. Create a world with `/iris create …`, which copies the pack into the world, or open Studio for live editing.
5. Package with `/iris studio package dimension=<key>` (Bukkit) or `/iris studio package <key>` (modded), then add back `spawners/` and `markers/` if the pack uses them.
6. Replace an existing world's snapshot only after a backup, with `/iris dev update-world world=<world> pack=<dimension> confirm=true`.

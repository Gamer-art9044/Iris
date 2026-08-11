# 25 - Pack Management

Pack management covers download/install into the packs workspace, validation, unused-resource cleanup and restore, packaging for distribution, and unsafe replacement of a live world’s pack snapshot. Authoring packs live under the platform packs root; production worlds copy that tree into `<world>/iris/pack` (see `05 - Concepts & Pack Layout.md` and `06 - Worlds & Lifecycle.md`).

See also: `03 - Configuration.md`, `04 - Commands & Permissions.md`, `10 - Studio & VSCode Schemas.md`, `24 - Pack Mods & Snippets.md`, `27 - Example - Configuring Overworld.md`.

## Tutorial: take a pack from workspace to production

Use this loop after a pack works in Studio and before creating or updating a production world. It produces a validated export while keeping cleanup and live-world replacement separate, reviewable decisions.

1. Place the authoritative authoring tree under `packs/<key>/` and confirm it contains at least one `dimensions/*.json`.
2. Validate and read the result: Bukkit `/iris pack validate pack=<key>` then `/iris pack status pack=<key>`; modded `/iris pack validate <key>` then `/iris pack status <key>`. Continue only when the pack is loadable and every blocking error is resolved.
3. Preview unused resources without writing: Bukkit `/iris pack cleanup <key> mode=preview`; modded `/iris pack cleanup <key>`. Review every candidate before applying cleanup.
4. If cleanup is approved, apply it with Bukkit `mode=apply` or the modded `apply` literal, validate again, and use `pack restore` if a required resource was quarantined.
5. Package the validated closure: Bukkit `/iris studio package dimension=<key>`; modded `/iris studio package <key>`. Success is `exports/<key>.iris` plus a completed command message; the source pack and world snapshots remain unchanged.
6. Create a new disposable world from the release pack and run fresh-world and restart smokes. Prefer a new production world for breaking pack changes.
7. Replace an existing `<world>/iris/pack` snapshot only after a world backup and explicit maintenance decision; use the **Developer update-world (unsafe)** procedure below.

If validation reports a missing edge, restore or repair that resource before packaging. If cleanup preview names an intentional dynamically loaded resource, leave cleanup unapplied. The workflow passes when the source closure validates, the package command creates the expected export, the disposable world reloads, and its world snapshot matches the intended release pack. Validate an unpacked export separately before distributing it when the release process consumes the `.iris` artifact rather than the source tree.

## Pack workspace

| Item | Path / rule |
|------|-------------|
| Packs root | Bukkit: plugin data `packs/`; modded: `config/irisworldgen/packs/` (platform data folder) |
| Visible packs | Non-hidden directories listed by `PackDirectoryResolver` |
| Presence | Pack exists if it has safe tree + at least one `dimensions/*.json` (parse failures do not trigger redownload) |
| Safe key | Download destination keys: `[a-z0-9_-]+` |

## Download

### Commands

| Command | Behavior |
|---------|----------|
| Bukkit: `/iris download <pack> [branch=stable] [overwrite=false]`; modded: `/iris download <pack> [branch] [force]` | Download into packs root |
| Default overworld special case | Pack name `overworld` uses IrisDimensions overworld **beta release zip** (`…/releases/download/beta/overworld.zip`), not an arbitrary branch zip |
| Other packs | `IrisDimensions/<pack>/<branch>` GitHub archive search via `StudioSVC.downloadSearch` |

| Param | Default | Notes |
|-------|---------|-------|
| `pack` | required | Folder/key or repo short name |
| `branch` | `stable` | GitHub ref when not default overworld |
| `overwrite` | `false` | Force replace existing present pack |

### Install pipeline (`PackDownloader`)

1. Per-key/ref download lock (concurrent startup and commands do not double-fetch).
2. If pack present and not force → skip network.
3. Download zip (size/entry limits: archive ≤512MiB, ≤100k entries, total uncompressed budget, per-file cap).
4. Unpack to temp; require single pack home directory.
5. Open as datapack-compiler `IrisData`; require **exactly one** dimension; key = that dimension load key.
6. Run `PackValidator.validate`; blocking errors abort install.
7. Publish into `packs/<key>/` with conflict checks (refuses symlink targets; detects dimension-key conflicts with other folders).

Default overworld repository constant: `IrisDimensions/overworld`.

## Validate

| Command | Behavior |
|---------|----------|
| Bukkit: `/iris pack validate [pack=<key>]`; modded: `/iris pack validate [pack]` | Validate one pack or all visible packs; publish into `PackValidationRegistry` |
| Bukkit: `/iris pack status [pack=<key>]`; modded: `/iris pack status [pack]` | Show the startup-published registry result, including a reused persisted result; run validate to refresh after edits |

Bukkit persists successful and failed startup validation results and reuses them only when the exact visible pack set, pack-content fingerprint, validator schema, strict-content mode, platform/Minecraft/Iris context, and relevant live registries still match. Cached failures remain blocking; changed bytes, context, registry keys, missing/extra packs, malformed cache state, or a manual validation refresh prevents stale success from authorizing world or Studio creation.

### Checks performed (`PackValidator`)

| Check | Blocking vs warning |
|-------|---------------------|
| Missing pack / missing `dimensions/` / no dimension JSON | Blocking |
| Dimension JSON integrity (`PackDimensionValidator`) | Blocking / warnings as emitted |
| Loot graph (`PackLootValidator`) | Blocking |
| Removed worldgen fields (e.g. `fluidBodies`) | Blocking |
| Object surface support | Blocking |
| Unsupported structure transforms (`rotation` / `translate` / `scale` on forbidden surfaces) | Blocking |
| Structure graph + compiled graph validator | Errors blocking; warnings advisory |
| Native structure replacement envelopes | Blocking |
| Spawner → entity references | Blocking |
| Custom biome spawns category resolution | Blocking |
| Content keys / bad block properties (`ContentKeyValidator`) | Blocking when `general.strictContentKeys` or `-Diris.strictContent`; else warnings (palette-sourced stay advisory) |

`isLoadable()` is false when any blocking error exists. Status reports blocking count and up to 10 warnings (plus “more” count).

## Cleanup (unused resources)

| Command | Mode | Behavior |
|---------|------|----------|
| Bukkit: `/iris pack cleanup <pack> [mode=preview]`; modded: `/iris pack cleanup <pack> [apply]` | `preview` (default) | List unused candidates; no writes |
| | `apply` | Quarantine candidates under pack `.iris-trash/<timestamp>/` |

Managed folders scanned for unreferenced JSON resources: `biomes`, `regions`, `entities`, `spawners`, `loot`, `generators`, `expressions`, `markers`, `blocks`, `mods`.

Excluded from cleanup corpus: `.iris-trash`, `datapack-imports`, `externaldatapacks`, `internaldatapacks`, `datapacks`, `cache`, `objects`, `.iris`.

Cleanup re-scans on apply (not a blind apply of an old preview). Failed apply may leave paths still quarantined and reports them.

## Restore

| Command | Mode | Behavior |
|---------|------|----------|
| Bukkit: `/iris pack restore <pack> [mode=preview]`; modded: `/iris pack restore <pack> [apply]` | `preview` | List latest quarantine dump files and conflicts |
| | `apply` | Move files back from latest dump if destinations free |

Restore **refuses** when destination paths already exist (conflict list). Nothing restored when no quarantine dump exists.

## Package (export)

| Command | Behavior |
|---------|----------|
| Bukkit: `/iris studio package [dimension=default] [obfuscate=false] [minify=true]`; modded: `/iris studio package [pack]` | Compile dimension closure to a zip |

| Param | Default | Notes |
|-------|---------|-------|
| `dimension` | contextual / `default` | Dimension in packs |
| `obfuscate` | `false` | Obfuscate packaged content when true |
| `minify` | `true` | Compact JSON (indent 0) |

Pipeline (`IrisPackageCompiler`):

1. Load dimension and walk regions → biomes → generators, loot, entities, spawners, structures/objects closure.
2. Stage under Iris data `exports/<dimensionKey>/`.
3. Write `package.json` with hash, time, version.
4. Zip to `exports/<dimensionKey>.iris` (compression level 9); delete staging folder.

Does not modify the source pack or any world snapshot.

## Developer update-world (unsafe)

| Command | Behavior |
|---------|----------|
| `/iris developer update-world world=<world> pack=<dimension> confirm=true [fresh-download=false]` | Replace the world’s pack snapshot |

| Param | Default | Notes |
|-------|---------|-------|
| `world` | contextual | Target world folder |
| `pack` / dimension | contextual | Source dimension (live packs root) |
| `confirm` | `false` | Required true; otherwise prints warning only |
| `fresh-download` | `false` | Re-download pack before install |

Implementation:

1. Requires `confirm=true`.
2. Optional `StudioSVC.downloadSearch` when `fresh-download`.
3. Acquires `PACK_MUTATION` / `PACK_PUBLISH` lease.
4. `StudioSVC.replaceIntoWorld` → install into `worldFolder/iris/pack` with `replaceExisting=true` (atomic stage/publish).
5. If an engine still holds that pack data, Iris **restarts the server** after commit (`"An active Iris world pack was replaced."`).

This is intentionally unsafe for production without backups: existing chunks keep old terrain; only future generation and pack-driven systems see new content. Prefer staging a new world when pack contracts change.

## Related operations

| Task | Where |
|------|-------|
| Create studio project from template | `/iris studio create` (`10 - Studio & VSCode Schemas.md`) |
| Open VSCode + schemas | `/iris studio vscode` |
| Import vanilla objects/structures into pack | `/iris studio importvanilla` |
| Structure import | `/iris structure …` |
| Strict content keys | `settings.general.strictContentKeys` (`03 - Configuration.md`) |
| Datapack bootstrap / install | Server configurator + `/iris datapack` (see platform docs) |

## Quick reference checklist

1. Download or place pack under `packs/<key>/` with `dimensions/*.json`.
2. On Bukkit, run `/iris pack validate pack=<key>` until loadable. Modded uses `/iris pack validate <key>`.
3. On Bukkit, optionally run `/iris pack cleanup <key> mode=preview`, then `mode=apply` after review; restore if needed. Modded uses the `apply` literal.
4. Create world with `/iris create …` (copies pack) or open studio for live edit.
5. To ship on Bukkit: `/iris studio package dimension=<dimension>`.
6. To refresh an existing world pack only after backup: `/iris dev update-world world=<world> pack=<dimension> confirm=true`.

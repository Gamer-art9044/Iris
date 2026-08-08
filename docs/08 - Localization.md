# 08 - Localization

Iris localizes command, Studio, runtime, HUD, and UI strings through typed Java message catalogs and optional locale overlays. Server locale is selected by `general.language` in `settings.json`. Client keybind labels use Minecraft lang assets under `assets/irisworldgen/lang/`. See also `03 - Configuration.md`, `04 - Commands & Permissions.md`, and `29 - Client HUD & Protocol.md`.

## English and catalogs

Canonical English is code-owned in `core/.../localization` (`IrisMessages` and the surface catalogs it assembles). Iris does not ship an English server translation file. English locale id is `en_US` (`VolmitLocales.ENGLISH`).

Catalog surfaces:

| Catalog | Surface |
|---|---|
| `IrisMessages` | Shared command deny / reload / modded help keys |
| `BukkitCommandMessages`, `BukkitCommandMessagesExtended` | Bukkit `/iris` feedback |
| `DirectorCommandMessages` | Director parameter/help copy (Bukkit command tree) |
| `ModdedCommandMessages`, `ModdedHelpMessages` | Fabric/Forge/NeoForge command and help |
| `RuntimeUiMessages`, `RuntimeProgressMessages`, `BukkitRuntimeMessages` | Pregen, chunk jobs, runtime status |
| `PackDownloadMessages` | Pack download progress |
| `ClientUiMessages` | Client Vision, What overlay, pregen HUD, toasts, create-world gates |
| `BukkitUiMessages`, `DesktopUiMessages` | Bukkit/desktop UI strings |

Resolution entry points: `IrisLanguage.text(...)` (color codes allowed) and `IrisLanguage.plain(...)` (legacy section colors stripped). Argument-free `plain` results are memoized per locale snapshot for hot UI paths.

## Selecting a locale

| Setting | Default | Location |
|---|---|---|
| `general.language` | `en_US` | `plugins/Iris/settings.json` (plugin) or Iris data-folder `settings.json` (mod) |

Locale names must match `[A-Za-z0-9_-]+`. Invalid values are rejected and the previous active locale continues. `/iris reload` (and settings hotload) reloads settings and locale; success/failure messages report the requested and active locale ids.

## Bundled server locales

Complete non-English server bundles ship as jar resources under `/languages/<locale>.json`. Bundled locale ids:

| Locale id | Language |
|---|---|
| `de_DE` | German |
| `es_ES` | Spanish |
| `fi_FI` | Finnish |
| `fr_FR` | French |
| `he_IL` | Hebrew |
| `it_IT` | Italian |
| `ja-JP` | Japanese (hyphen in the server locale id) |
| `ko_KR` | Korean |
| `lt_LT` | Lithuanian |
| `nl_NL` | Dutch |
| `pl_PL` | Polish |
| `pt_PT` | Portuguese |
| `ru_RU` | Russian |
| `tr_TR` | Turkish |
| `vi_VI` | Vietnamese |
| `zh_CN` | Simplified Chinese |
| `zh_TW` | Traditional Chinese |

Bundled file size is capped at 2 MiB. A missing bundle for a locale listed in `VolmitLocales` is a hard load failure; an unknown locale with no bundle falls through to English catalog text (with fallback warnings counted at load).

## Override files

Path: `<Iris data folder>/languages/overrides/<locale>.json`.

Iris creates `languages/overrides/` on locale load. Overrides are optional partial files: omitted keys resolve from the bundled overlay (if any), then from code-owned English.

Shape:

```json
{
  "locale": "de_DE",
  "messages": {
    "iris.command.unknown": "Unbekannter Iris-Befehl"
  }
}
```

Rules:

| Rule | Behavior |
|---|---|
| Root keys | Only `locale` and `messages` are allowed |
| `locale` | If present, must equal the file's locale id after normalize |
| Values | String (text), string array (lines), or object of plural forms for plural keys |
| Nesting | Objects nest into dotted keys; keys must exist in the message catalog |
| Size | Max 2 MiB |
| Hotload | Override file mtime/size is watched; change triggers locale reload without a full restart when settings hotload runs |

Rejected reloads leave the previous locale active and log up to 12 validation errors.

## Resolution order

For non-`en_US` locales: operator override overlay → bundled `/languages/<locale>.json` → English catalog defaults. For `en_US`: override overlay only (no English server bundle).

Template placeholders use `{name}` tokens. Trusted arguments may contain color codes; untrusted arguments strip legacy section codes and rewrite `&`, `<`, `>`.

`&` color codes in templates are translated to section-sign codes before send (`0-9a-f`, `k-o`, `r`, `x`).

## Client language assets

Minecraft client assets live at `assets/irisworldgen/lang/<mc_code>.json` inside the mod jar. `en_us.json` is required and currently holds keybind category and key names only:

| Key | English |
|---|---|
| `key.categories.irisworldgen.iris` | Iris |
| `key.irisworldgen.toggle_pregen_hud` | Toggle Pregen HUD |
| `key.irisworldgen.open_vision_map` | Open Iris Vision Map |
| `key.irisworldgen.toggle_what_overlay` | Toggle Iris What Overlay |

Minecraft codes are derived from server locale ids by replacing `-` with `_` and lowercasing (`ja-JP` → `ja_jp`). Matching translated client assets ship for every non-English bundled locale. Server HUD/Vision/toast strings still resolve through `IrisLanguage` / `ClientUiMessages` on the process that renders them, not through these four Minecraft keys.

## Platforms

Localization runs on Bukkit-family and modded (Fabric/Forge/NeoForge). Client keybind lang assets apply only where the client mod is installed. PlaceholderAPI and Bukkit-only command catalogs do not affect mod command trees; modded uses the modded catalogs. See `30 - Platform Differences.md`.

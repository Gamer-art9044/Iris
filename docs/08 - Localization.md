# 08 - Localization

Iris localizes command, Studio, runtime, HUD, and UI strings through typed Java message catalogs and optional locale overlays. Server locale is selected by `general.language` in `settings.json`. Client keybind labels use Minecraft lang assets under `assets/irisworldgen/lang/`. See also `03 - Configuration.md`, `04 - Commands & Permissions.md`, and `29 - Client HUD & Protocol.md`.

## Tutorial: select a locale and verify an override

Prerequisites: write access to the Iris data folder, a backup of `settings.json`, and an operator account that can run `/iris reload`.

1. Set `general.language` in `settings.json` to an exact bundled id, for example `de_DE`.
2. Create `<Iris data folder>/languages/overrides/de_DE.json` with one unmistakable local override:

   ```json
   {
     "locale": "de_DE",
     "messages": {
       "iris.command.unknown": "Lokaler Test: unbekannter Iris-Befehl"
     }
   }
   ```

3. Run `/iris reload` and confirm the response reports `de_DE` as the active locale.
4. Run `/iris help`, then run `/iris locale-override-test` to exercise the overridden unknown-command key.
5. Confirm the local override appears, other messages come from the bundled German overlay, and any omitted key falls back to canonical English instead of printing a raw identifier.
6. Edit the override text, save it, and confirm the hotload path picks up the change. Remove the test override when verification is complete.

The workflow passes when the selected locale remains active across a clean restart and the partial override wins only for its named key. When authoring a new locale, validate a small command group before translating the full catalog. Server locale files do not change client keybind labels; client assets are a separate surface.

### Recovery

| Symptom | Meaning | Recovery |
|---|---|---|
| Requested locale is rejected | Id is invalid, file id differs, JSON is malformed, or overlay validation failed | Keep the previous locale active, fix the logged validation errors, and reload again |
| Raw message key appears | The key is not in the typed catalog or the calling surface bypassed localization | Verify the catalog key first; adding an arbitrary override key cannot create a new message definition |
| Override is ignored | Wrong data folder, wrong locale filename/id, or unchanged watched file | Confirm `<data>/languages/overrides/<locale>.json`, update its contents, then run `/iris reload` explicitly |
| Formatting or placeholders break | Override changed `{name}` tokens or the value type | Match the English key's placeholders and text/lines/plural shape exactly |
| Server text changes but keybind labels do not | Client assets are independent | Update/install the matching `assets/irisworldgen/lang/<mc_code>.json` client resource |

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

# 08 - Localization

Iris ships its command, Studio, runtime, HUD, and UI text as typed Java message catalogs, with translated overlays for seventeen languages and an operator-editable override file per locale. You pick the server language with `general.language` in `settings.json`; you change individual strings by dropping a partial JSON file into `languages/overrides/`. Client keybind labels are a separate surface and live in the mod jar's Minecraft lang assets. See also `03 - Configuration.md`, `04 - Commands & Permissions.md`, and `29 - Client HUD & Protocol.md`.

## Change one message

Say you want German, but you dislike the wording Iris uses when someone mistypes a subcommand. You need two things: the locale setting, and an override file that redefines exactly that one key.

Prerequisites: write access to the Iris data folder, a backup of `settings.json`, and an account that can run `/iris reload`.

1. Set `general.language` to `de_DE` in `settings.json` (`plugins/Iris/settings.json` on Bukkit-family, `<data folder>/settings.json` on a mod loader).
2. Create `<Iris data folder>/languages/overrides/de_DE.json`. Iris creates the `languages/overrides/` folder itself on the first locale load, so it should already exist.

   ```json
   {
     "locale": "de_DE",
     "messages": {
       "iris.command.unknown": "Kenn ich nicht. Probier /iris help"
     }
   }
   ```

3. Run `/iris reload`. A green `Hotloaded settings and locale de_DE.` means the settings and the locale both applied. A yellow `Settings were reloaded, but locale ... was rejected` means the overlay failed validation and the previous locale is still active — read the errors in the console before going further.
4. Run `/iris` with a subcommand that does not exist, for example `/iris zzz`.

Success looks like your override text appearing verbatim. Everything else in the same session — help output, pregen status, Studio messages — should be in German from the bundled `de_DE` overlay, and any key that neither file defines falls back to the built-in English rather than printing a raw key id.

Edit the file again and save it. The settings hotload poll calls `IrisLanguage.update()`, which compares the override file's path, modification time, and length against what it last loaded, so an edit is picked up on the next poll with no command and no restart. Delete the test override when you are done.

If you are authoring a whole new locale, translate one command group first and confirm it loads before you translate the rest. A single bad key rejects the entire file.

### Recovery

| Symptom | What actually happened | Fix |
|---|---|---|
| `Rejected locale setting '...'` in the log | The value does not match `[A-Za-z0-9_-]+`, so it never reached the loader | Correct the string in `settings.json`; the previously active locale keeps running in the meantime |
| `Rejected locale reload for <locale>` | The overlay failed validation. The console then lists up to 12 concrete errors and a count of any it omitted | Fix the listed keys and reload. Nothing partial is applied — the previous locale stays active in full |
| `Locale overlay key is not declared by the message catalog` | You invented a key name. Overrides can only redefine keys that already exist in code | Copy the exact key id from the bundled locale file for your language |
| `Expected [x, y] but found [x]` | Your text dropped or renamed a `{name}` placeholder | Match the English template's placeholder set exactly. Order and surrounding words are free; the set of names is not |
| `Expected 5 lines but found 4` / `Expected plural forms [...]` | A lines key needs the same line count as English, and a plural key needs the same form names | Restore the missing entries |
| Override edits do nothing | Wrong data folder, or the filename does not match the active locale id | The file must be `<data>/languages/overrides/<active locale>.json`, spelled exactly as `general.language`. Run `/iris reload` to force it |
| Server text is translated but keybind labels are still English | Those labels come from the mod jar's client assets, not from the server locale | See "Client language assets" below |

## Where English comes from

Canonical English is owned by code in `core/.../localization`. `IrisMessages` assembles the catalog from every surface class plus the shared Director command keys from VolmLib. There is no English server translation file, and there does not need to be — English is the fallback layer under every locale.

| Catalog | Covers |
|---|---|
| `DirectorMessages` (VolmLib) | Shared command framework text: parameter errors, argument prompts, help chrome |
| `IrisMessages` | Permission denials, unknown command, player-only, "not an Iris world", reload results, modded help chrome |
| `BukkitCommandMessages`, `BukkitCommandMessagesExtended` | Feedback from the Bukkit `/iris` command tree |
| `DirectorCommandMessages` | Per-command and per-parameter descriptions shown in `/iris help` |
| `ModdedCommandMessages`, `ModdedHelpMessages` | The Fabric/Forge/NeoForge command tree and its help pages |
| `RuntimeUiMessages`, `RuntimeProgressMessages`, `BukkitRuntimeMessages` | Pregen headers and boss bar titles, chunk job progress, runtime status lines |
| `PackDownloadMessages` | Pack download progress and results |
| `ClientUiMessages` | Client mod strings: Vision map, What overlay, pregen HUD stats, toasts, create-world gates |
| `BukkitUiMessages`, `DesktopUiMessages` | Bukkit inventory UI and desktop pregen window strings |

Code resolves text through `IrisLanguage.text(...)` when color codes should survive, or `IrisLanguage.plain(...)` when they should be stripped. Argument-free `plain` calls are memoized per locale snapshot because HUD code calls them several times per frame; a locale reload publishes a new snapshot and throws the whole memo away.

## Selecting a locale

| Setting | Default | Location |
|---|---|---|
| `general.language` | `en_US` | `plugins/Iris/settings.json` (plugin) or `<data folder>/settings.json` (mod) |

The value must match `[A-Za-z0-9_-]+`. Anything else is rejected outright and the previously active locale keeps running. Both `/iris reload` and the automatic settings hotload re-read the setting and reload the locale; the command reports the requested and the active id so you can tell a successful switch from a silent no-op.

On a successful load Iris logs `Loaded locale <id> with N fallback entries.` That count is the number of catalog keys each overlay did not define, summed across overlays. A one-key override file therefore produces a very large number. It is informational, not an error.

## Bundled server locales

Complete translations ship inside the jar as `/languages/<locale>.json`.

| Locale id | Language |
|---|---|
| `de_DE` | German |
| `es_ES` | Spanish |
| `fi_FI` | Finnish |
| `fr_FR` | French |
| `he_IL` | Hebrew |
| `it_IT` | Italian |
| `ja-JP` | Japanese (hyphen, not underscore — this one id is irregular) |
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

A bundled file is capped at 2 MiB. If one of these ids is configured but its jar resource is missing, the load throws — that is a build defect, not an operator problem. An id outside this list with no bundle is not an error: Iris skips the bundled layer and every string falls through to English, which is what makes a fully custom locale possible from an override file alone.

## Override files

Path: `<Iris data folder>/languages/overrides/<locale>.json`, created as a folder on locale load.

Overrides are partial by design. Define only the keys you want to change; the rest resolve from the bundled overlay, then from English.

```json
{
  "locale": "de_DE",
  "messages": {
    "iris.command.unknown": "Unbekannter Iris-Befehl"
  }
}
```

| Rule | Behavior |
|---|---|
| Root keys | Only `locale` and `messages`. Any other root key throws and the reload is rejected |
| `locale` | Optional. If present it must equal the filename's locale id after trimming, otherwise the file is rejected |
| Key existence | Every key must already exist in the catalog. An unknown key is an ERROR that rejects the whole file — you cannot mint new messages from an override |
| Value shapes | A string for text keys, a string array for lines keys, an object of plural forms for plural keys. Using the wrong shape rejects the file |
| Nesting | Nested objects flatten into dotted keys, so `{"iris": {"command": {"unknown": "..."}}}` is the same as `"iris.command.unknown"`. The exception is a plural key, where an object is read as the plural forms |
| Placeholders | The set of `{name}` tokens must match the English template exactly. A lines key must also match the English line count, and each line's placeholder set |
| Size | Max 2 MiB |
| Hotload | The file's path, mtime, and length are watched. A change reloads the locale on the next settings-hotload poll |

Validation is all-or-nothing. A rejected reload leaves the previous locale fully intact and logs the first 12 errors plus a count of the remainder.

## Resolution order

For a non-`en_US` locale a key resolves as: operator override → bundled `/languages/<locale>.json` → English catalog default. For `en_US` the bundled layer is skipped entirely, so it is: operator override → English catalog default.

Templates use `{name}` tokens. Arguments are classified as trusted or untrusted at the call site. Trusted arguments may carry color codes. Untrusted arguments — player names, world names, pack-authored strings, exception text — have legacy section codes stripped and have `&`, `<`, and `>` rewritten to lookalike characters so they cannot inject formatting.

`&` codes in the template itself are translated to section-sign codes before send, for `0-9`, `a-f`, `k-o`, `r`, and `x`.

## Client language assets

The Minecraft client reads its own lang files from `assets/irisworldgen/lang/<mc_code>.json` inside the mod jar. These are not the server catalogs and are not affected by `general.language`. They currently define only the keybind category and the three key names:

| Key | English |
|---|---|
| `key.categories.irisworldgen.iris` | Iris |
| `key.irisworldgen.toggle_pregen_hud` | Toggle Pregen HUD |
| `key.irisworldgen.open_vision_map` | Open Iris Vision Map |
| `key.irisworldgen.toggle_what_overlay` | Toggle Iris What Overlay |

`en_us.json` is required; a translated file ships for every bundled locale. The Minecraft code is the server locale id with `-` replaced by `_` and lowercased, so `ja-JP` becomes `ja_jp`.

Everything else the client draws — HUD stats, Vision map labels, What overlay rows, toasts — resolves through `IrisLanguage` and `ClientUiMessages` on whichever process renders it, not through these four keys. That is why a translated boss bar and an English keybind label can coexist.

## Platforms

Localization works the same on Bukkit-family and on Fabric/Forge/NeoForge, from the same catalogs and the same override file. Only the surfaces differ: the modded command tree uses `ModdedCommandMessages`/`ModdedHelpMessages`, the Bukkit tree uses the Bukkit catalogs, and each ignores the other's keys. Keybind lang assets apply only where the client mod is installed. See `30 - Platform Differences.md`.

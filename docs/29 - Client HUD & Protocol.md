# 29 - Client HUD & Protocol

The Iris client mod (Fabric/Forge/NeoForge jar on the client) adds a native pregeneration HUD, Vision map, What overlay, studio toasts, and singleplayer world-type entries. It talks to Iris servers over the shared channel `irisworldgen:main`. Vanilla clients ignore the channel and use server-side fallbacks. See also `07 - Pregeneration.md`, `08 - Localization.md`, `10 - Studio & VSCode Schemas.md`, and `30 - Platform Differences.md`.

## Tutorial: verify the client/server path

1. Join the same Iris server once with a vanilla client and once with a matching Iris client mod.
2. Start a small pregen. On a modded server, confirm the vanilla client receives the boss-bar fallback. On Bukkit-family servers, confirm progress through console/status or the configured Bukkit HUD path. In both cases, the client with Iris must receive the native HUD.
3. On the modded client, toggle the HUD, open Vision, and toggle What using rebound keys if defaults conflict.
4. Move between an Iris world and a non-Iris world. Confirm Vision/What report Iris data only where the handshake and world state allow it.
5. Reconnect and repeat one action to prove the handshake is not relying on stale client state.
6. Check the server log for payload decode, version, or channel errors.

The protocol smoke passes only when both client types follow the server-family behavior in the matrix below. A working boss bar or Bukkit status path proves server progress, not the Iris client payload path. If the native HUD remains absent, verify matching Iris/Minecraft versions, reconnect to force a new handshake, and check the server log for an unsupported protocol version or rejected capability frame.

## When the client mod does something

| Server | Client without Iris | Client with Iris mod |
|---|---|---|
| Modded Iris | Boss bar / status for pregen | Native HUD over custom payloads |
| Bukkit/Paper Iris | Console/status and any Bukkit HUD lanes; no Iris client protocol features | Same native HUD/Vision/What over plugin messaging on `irisworldgen:main` |
| Non-Iris server | N/A | Client mod is inert after hello fails / no Iris |

Singleplayer: installed packs appear as selectable World Types; the integrated server runs the same engine.

## Keybinds

Category: **Iris** (`key.categories.irisworldgen.iris`). Defaults:

| Action | Default key | Translation key |
|---|---|---|
| Toggle pregen HUD | `H` | `key.irisworldgen.toggle_pregen_hud` |
| Open Iris Vision map | `M` | `key.irisworldgen.open_vision_map` |
| Toggle Iris What overlay | `J` | `key.irisworldgen.toggle_what_overlay` |

Keys are rebindable in Controls under the Iris category. HUD visibility defaults on (`hudVisible = true`). What overlay defaults off. F1 hide-gui still advances toasts via a separate tick so they are not stranded.

## Pregen HUD

Top-left panel (`ORIGIN` 6,6) while a live pregen job is tracked and not expired:

| Element | Content |
|---|---|
| Title | Localized pregen header |
| Stats | `done / total (percent%)` |
| Bar | Green while running, yellow while paused, muted gray when stale |
| Tail | Rate, optional ETA, `PAUSED`, or “no updates for Ns” when stale |
| Minimap | Optional region grid when region deltas exist (pending / generating / done cells) |

Stale and expire timers (client-side, from last received progress frame):

| Threshold | Value | Effect |
|---|---|---|
| Stale | 5 s | Panel mutes colors and shows stale label |
| Expire | 30 s | Panel stops drawing |

`PregenEnd` clears the job immediately. Hide-gui (F1) skips layered HUD draw; toasts still pump.

## Boss bar fallback

Modded servers: `ModdedPregenBossBar` shows a green (running) / yellow (paused) boss bar to the player who started pregen **only if** that player does not already have a ready protocol session with `CAPABILITY_PREGEN`. Clients with a working pregen HUD skip the boss bar. Bar title uses localized `iris.runtime.pregen.bossbar.*` strings; progress updates every 10 ticks.

Bukkit-family servers: players without the client mod do not get this modded boss-bar path; use `/iris pregen status`, logging, and any server HUD lanes. Clients with the mod still receive protocol pregen frames over plugin messaging.

## Vision map and What overlay

| Feature | Capability | Notes |
|---|---|---|
| Vision map (`M`) | `CAPABILITY_VISION` | Full-screen map; drag pan, scroll zoom, Esc close; needs ready session + Iris dimension |
| What overlay (`J`) | `CAPABILITY_CURSOR` | Cursor column query: biome, region, cave biome, height |
| Studio toasts | Client advertises `CAPABILITY_STUDIO` | Hotload/toast frames when the server sends them |
| Dimension status | Always after hello | Pack/dimension/height bounds; non-Iris worlds clear tiles/markers |

Vision tiles arrive chunked (max payload per chunk 24000 bytes; header 25 bytes). Markers capped at 256 per frame.

## Protocol channel

| Constant | Value |
|---|---|
| Channel | `irisworldgen:main` |
| Protocol version | `1` |
| Transport (modded) | Custom payloads on the play channel |
| Transport (Bukkit) | Plugin messaging in/out on the same channel name |
| Max frame | 24576 bytes |
| Max inbound frames / client / s | 32 |
| Max vision tile requests / s | 8 |
| Max cursor info requests / s | 4 |
| Max query `|block|` coordinate | 29_999_999 |

Internal wire types (`IrisProtocol.TYPE_*`):

| Id | Direction | Message |
|---|---|---|
| 1 | C→S | `ClientHello` (version, capabilities) |
| 2 | S→C | `ServerHello` (version, capabilities, brand, irisActive) |
| 3 | S→C | `PregenProgress` |
| 4 | S→C | `PregenEnd` |
| 5 | S→C | `DimensionStatus` |
| 6 | C→S | `CursorInfoRequest` |
| 7 | S→C | `CursorInfo` |
| 8 | C→S | `VisionTileRequest` |
| 9 | S→C | `VisionTile` (chunked) |
| 10 | S→C | `VisionMarkers` |
| 11 | S→C | `PregenRegionDelta` |
| 12 | S→C | `StudioHotload` |
| 13 | S→C | `Toast` |

Capability bits:

| Bit | Name | Meaning |
|---|---|---|
| `1 << 0` | `CAPABILITY_PREGEN` | Pregen progress / end / region deltas |
| `1 << 1` | `CAPABILITY_VISION` | Vision tiles and markers |
| `1 << 2` | `CAPABILITY_CURSOR` | Cursor column lookups |
| `1 << 3` | `CAPABILITY_STUDIO` | Studio hotload notifications |

Client hello advertises all four. Bukkit and modded servers grant `PREGEN | VISION | CURSOR | STUDIO`. Negotiated capabilities are the intersection of what the client advertises and what the server grants.

## Handshake

1. Client joins world → sends `ClientHello` with protocol version `1` and client capabilities.
2. Retries every 2 s, max 5 attempts; failure → session `UNSUPPORTED`.
3. Version mismatch → session `INCOMPATIBLE` (UI can report mismatch).
4. Match → session `READY`; dimension status and feature frames follow.
5. Disconnect clears session and world-local client state (pregen, tiles, markers, cursor, toasts).

Server drops frames before hello, rate-limits, rejects oversized/malformed frames, and rejects out-of-bounds cursor queries without clamping.

## Localization touchpoints

Server-side locale (`general.language`) drives boss bar and many shared UI strings. Client keybind labels use `assets/irisworldgen/lang/*.json`. Vision/What/pregen HUD strings use `ClientUiMessages` through `IrisLanguage`. See `08 - Localization.md`.

## Operator verification

- Modded server + Iris client: pregen progress on HUD; boss bar absent for that player when protocol pregen capability is ready
- Bukkit Iris + Iris client: same HUD over plugin messaging
- Vanilla client on either server: no protocol traffic effects; modded boss bar path as above
- Non-Iris server + Iris client: mod inert
- H toggles HUD; M opens Vision when available; J toggles What when available

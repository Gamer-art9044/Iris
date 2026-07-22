# Iris

Iris is a world generation engine for Minecraft servers and mod loaders. It generates terrain,
biomes, caves, structures, objects, and entities from editable JSON packs, with a full in-game
studio authoring workflow. The same engine runs as a Bukkit-family plugin and as a Fabric, Forge,
or NeoForge server mod, and produces bit-identical terrain on every platform for the same pack and
seed. The master branch targets the current Minecraft version (26.2).

# [Support](https://discord.gg/3xxPTpT) **|** [Documentation](https://docs.volmit.com/iris/) **|** [Git](https://github.com/IrisDimensions)

Consider supporting development by buying Iris on Spigot.

## Language and localization

Canonical English is defined in the typed Java catalogs under `core/src/main/java/art/arcane/iris/core/localization`, next to the command, Studio, runtime, and UI surfaces that use it. Iris does not ship an English server translation file; the required Minecraft client asset remains at `assets/irisworldgen/lang/en_us.json`. Complete server bundles and matching client assets are included for German, Spanish, Finnish, French, Hebrew, Italian, Japanese, Korean, Lithuanian, Dutch, Polish, Portuguese, Russian, Turkish, Vietnamese, Simplified Chinese, and Traditional Chinese. Set `language` in Iris settings to select one. A JSON file at `languages/overrides/<locale>.json` can override only selected server messages; omitted entries resolve from the bundle and then code-owned English.

## Platforms

| Platform | Artifact | Minecraft | Notes |
|---|---|---|---|
| Paper / Purpur / Leaf / Canvas | plugin jar | 26.2 | Full feature set |
| Folia | plugin jar | 26.2 | Region-safe scheduling throughout |
| Spigot / CraftBukkit | plugin jar | 26.2 | Full feature set |
| Fabric | mod jar | 26.2 | Server worldgen + client HUD; requires Fabric Loader 0.19.3+ |
| Forge | mod jar | 26.2 | Server worldgen + client HUD; requires Forge 65.0.4+ |
| NeoForge | mod jar | 26.2 | Server worldgen + client HUD; requires NeoForge 26.2.0.12-beta+ |

Java 25 is required on every platform.

The modded feature set matches the plugin wherever the operation is not Bukkit-bound: worldgen
from Iris packs, authoring with mod blocks/items/entities (with validation suggestions), studio
workspaces with schema autocomplete over the server's live registries, entity spawning parity
including death loot, pregeneration with a boss bar or client HUD, the `/iris` command tree, and
the goldenhash determinism gate, which is interchangeable across all four platforms.

## Install

**Plugin (Paper/Purpur/Leaf/Canvas/Folia/Spigot):** drop the plugin jar into `plugins/` and start
the server. On first boot Iris downloads the default `overworld` pack automatically.

**Mod (Fabric/Forge/NeoForge):** drop the mod jar into `mods/` and start the server. The jar is
self-contained (core, SPI, and required Fabric API modules are bundled). On first boot Iris
downloads the default `overworld` pack before the worldgen datapack is written, so the default
pack is fully active immediately. Packs installed later register their custom dimension types
(height ranges) and custom biomes through the forced datapack at server start — restart once after
adding a pack so worlds get its full heights and biomes; worlds created before that restart run
with fallback heights.

**Singleplayer (modded clients):** installed Iris packs appear as selectable World Types on the
Create New World screen; the integrated server runs the same engine.

## The client mod

Installing the mod jar on a client adds a native pregeneration HUD: a top-left panel with a
progress bar, chunks done/total, percent, chunks per second, and ETA, turning yellow while the
pregen is paused. The `H` key (rebindable, under the "Iris" controls category) toggles it.

The HUD works against modded Iris servers and against Bukkit/Paper Iris servers, both over the
`irisworldgen:main` channel (custom payloads on modded, plugin messaging on Bukkit). Vanilla
clients are unaffected and get the server-side boss bar instead; on non-Iris servers the client
mod is inert.

## Quickstart

Create and enter an Iris world.

Plugin (optional arguments are keyed):

```
/iris create myworld type=overworld seed=1337
/iris load myworld
```

Mod (positional arguments):

```
/iris create myworld overworld 1337
```

Pregeneration requires a radius in blocks. On the plugin, optional arguments are keyed; on modded
servers they are positional and composable:

```
/iris pregen start 352 world=myworld center=0,0 gui=false
/iris pregen start 352 irisworldgen:myworld at 0 0 sync
```

Players with the client mod see the native HUD; everyone else gets a boss bar (modded) or
console/status output. `/iris pregen status` reports progress on the plugin.

## Studio and VSCode workspace

The studio is the pack authoring environment, available on all platforms. Studio worlds are
transient — they are deleted on close and purged at startup.

```
/iris studio create <name> [template]   scaffold a new pack (default template: example)
/iris studio open <pack> [seed]         open a temporary studio world for live editing
/iris studio vscode [pack]              write a .code-workspace with JSON schemas
/iris studio update [pack]              regenerate the workspace schemas
/iris studio close                      close and discard the studio world
```

As in the quickstart, optional arguments are keyed on the plugin (`seed=1234`) and positional on
the mod.

The generated VSCode workspace wires per-type JSON schemas (dimensions, biomes, regions, objects,
loot, entities, snippets) for full autocomplete. Schemas are generated from the server's live
registries, so on modded servers block, item, entity, enchantment, and potion-effect completion
includes installed mod content (for example `create:brass_ingot`). Editing an open studio's pack
files hotloads the changes and regenerates the schemas.

## Building from source

Requirements: JDK 25 (set `JAVA_HOME` to it). The Gradle wrapper handles everything else.

```
./gradlew buildAllToOut
```

builds every platform artifact into `dist/`:

```
Iris v<version> [CraftBukkit] <mc>.jar
Iris v<version> [Fabric] <mc>+<loader>.jar
Iris v<version> [Forge] <mc>+<loader>.jar
Iris v<version> [NeoForge] <mc>+<loader>.jar
```

Per-platform tasks: `./gradlew buildBukkit`, `buildFabric`, `buildForge`, `buildNeoforge`. The
developer SPI jar (the pure-JVM platform API contract) is built to `spi/build/libs/` by
`./gradlew :spi:jar`.

If you need help compiling as a developer or contributor, ask in the Discord. Do not come to the
Discord asking for free copies or a compile tutorial.

## Maintainer docs

- [Minecraft version bump checklist](docs/mc-version-bump.md)
- [Release checklist](docs/release-checklist.md)

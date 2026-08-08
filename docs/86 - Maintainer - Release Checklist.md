# 86 - Maintainer — Release Checklist

Manual release procedure. There is no release automation by design: every step below is run by a person and verified by eye. Work top to bottom; do not skip the verify gates.

Before starting this publication procedure, complete `87 - Maintainer - Release Readiness.md`. It contains the engineering remediation, determinism, performance, CI, and full platform-acceptance gates. This checklist starts only after those gates produce GO or an explicitly accepted GO-WARN decision.

Reference values below assume the current `gradle.properties`: `irisVersion=4.0.0-26.2`, `minecraftVersion=26.2`, `fabricLoaderVersion=0.19.3`, `forgeVersion=26.2-65.0.4`, `neoForgeVersion=26.2.0.12-beta`. For a Minecraft version bump, do `85 - Maintainer - MC Version Bump.md` first, then start this checklist.

## a. Preflight

- [ ] Working tree clean on the exact commit you intend to tag (`git status` shows nothing to commit).
- [ ] CI is green on that commit. The `verify` job (`.github/workflows/ci.yml`) runs core checks, Bukkit and shared modded tests, the SPI build, the deserialization probe, the modded artifact-verifier tests, and guarded Fabric, Forge, and NeoForge artifact builds on JDK 25. Do not release on a red or stale run.
- [ ] `MasterChangelog.MD` Iris section is coherent: one consolidated entry set, deduplicated, no date-sliced headers, and it describes the current shipped state (not superseded intermediate work).
- [ ] Version fields correct in `gradle.properties`: `irisVersion` is the release version and its trailing `-<mc>` suffix matches `minecraftVersion`. For a Minecraft bump, confirm every step in `85 - Maintainer - MC Version Bump.md` is done (loader ranges, `DataVersion`, NMS binding).
- [ ] JDK 25 is the active toolchain locally (`java -version` reports 25).

## b. Build

- [ ] From the Iris project root: `./build-all.sh`. This disables local VolmLib substitution, uses the immutable coordinate from `gradle.properties`, and serializes the all-platform build.
- [ ] `dist/` contains the four platform jars (exact names for this release):
  - [ ] `Iris v4.0.0-26.2 [CraftBukkit] 26.2.jar` (Bukkit/Paper/Purpur/Spigot/Folia plugin)
  - [ ] `Iris v4.0.0-26.2 [Fabric] 26.2+0.19.3.jar`
  - [ ] `Iris v4.0.0-26.2 [Forge] 26.2+65.0.4.jar`
  - [ ] `Iris v4.0.0-26.2 [NeoForge] 26.2+26.2.0.12-beta.jar`
  - Naming pattern: `Iris v<irisVersion> [<Platform>] <mc>[+<loaderDisplay>].jar`.
- [ ] The SPI jar is built by the same run at `spi/build/libs/iris-spi-4.0.0-26.2.jar`. It is the adapter/platform contract, not the stable downstream plugin API; it is not copied into `dist/` or uploaded to mod portals.
- [ ] Each mod jar bundles Iris core, SPI, and Iris-owned shaded libraries. LZ4, OSHI, JNA, and JNA Platform are supplied by the Minecraft 26.2 runtime and must not be bundled or relocated.

## c. Verify (release gates)

- [ ] `:core:check` and `:probe:deserializationProbe` passed in CI on the tag commit (a. covers this).
- [ ] Golden-hash determinism VERIFY passes on all four platforms and matches the same hash (see `32 - Determinism & Goldenhash.md`):
  - [ ] Bukkit plugin: `/iris developer goldenhash world=<world> radius=<radius> threads=<threads>` (automatically verifies when the matching capture already exists)
  - [ ] Fabric mod: `/iris goldenhash verify <radius> <threads>`
  - [ ] Forge mod: `/iris goldenhash verify <radius> <threads>`
  - [ ] NeoForge mod: `/iris goldenhash verify <radius> <threads>`
  - The hash is interchangeable across platforms: all four MUST report identical output for the same pack and seed. Any mismatch blocks the release.
- [ ] Live modded content-mod gate: on each loader, boot the mod jar alongside a real content mod (e.g. Create) and generate an Iris world. Confirm no load-time rejection, no class-loader crash, and that modded blocks/items/entities author and generate.
  - [ ] Fabric + content mod
  - [ ] Forge + content mod
  - [ ] NeoForge + content mod
- [ ] Client-mod matrix: install the mod on the client (keybind `H` toggles the pregen HUD) and confirm:
  - [ ] Modded server + modded client: HUD receives pregen progress over `irisworldgen:main`.
  - [ ] Modded server + vanilla client: server generates normally; vanilla client is unaffected.
  - [ ] Paper (Bukkit) server + modded client: HUD receives pregen progress over vanilla plugin messaging.
  - [ ] Folia smoke: plugin loads and an Iris world generates on Folia.
  - [ ] Non-Iris server + modded client: client is inert, no errors.

Operator-oriented sequences that support these gates: `31 - Operator Runbooks & Smoke Tests.md`. Client channel details: `29 - Client HUD & Protocol.md`.

## d. Publish (all manual, no automation)

- [ ] Modrinth: upload the three mod jars and the plugin jar. Tag loaders `fabric` / `forge` / `neoforge` on the mod files; mark the environment server + client; set game version 26.2.
- [ ] CurseForge: upload the three mod jars with the matching loader tags and game version 26.2.
- [ ] Existing plugin distribution channels: publish the plugin jar (`Iris v4.0.0-26.2 [CraftBukkit] 26.2.jar`) where the plugin already ships.
- [ ] Sentry: add a release note / mark the release so incoming reports map to this version (the mod version string is the Sentry release tag).
- [ ] Storepage / `listing.json` staleness review: check the listing copy for pre-4.0 content (Bukkit-only framing, old feature lists, screenshots). Flag anything stale for update before or right after launch. (Review only; this checklist does not change store copy.)

## e. Post

- [ ] Tag the release commit (`v<irisVersion>`) and push the tag. Archive the already verified `dist/` bundle with the release record; no tag-triggered bundle automation is configured.
- [ ] Announce the release on the community channels once the portals show the new files live.

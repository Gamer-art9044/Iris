# 86 - Maintainer — Release Checklist

Manual release procedure. Nothing publishes, tags, or announces automatically by design: every step below is run by a person and verified by eye. Work top to bottom; do not skip the verify gates.

Before starting this publication procedure, complete `87 - Maintainer - Release Readiness.md`. It contains the engineering remediation, determinism, performance, CI, and full platform-acceptance gates. This checklist starts only after those gates produce GO or an explicitly accepted GO-WARN decision.

Reference values below assume the current `gradle.properties`: `irisVersion=4.0.0-26.2`, `minecraftVersion=26.2`, `fabricLoaderVersion=0.19.3`, `forgeVersion=26.2-65.0.4`, `neoForgeVersion=26.2.0.12-beta`. For a Minecraft version bump, do `85 - Maintainer - MC Version Bump.md` first, then start this checklist.

## How to execute this checklist

Use one immutable commit and one JDK 25 environment from preflight through publication. Track build, tests, startup, gameplay, determinism, and publication as separate results; a later pass does not erase an earlier unexplained failure. Stop at the first failed required item, fix it on a new candidate commit, and restart the checklist from preflight.

Publication remains manual. Commands in this document produce local artifacts until the explicit publish section; do not upload, tag, or announce from an unclean or differently tested tree.

## a. Preflight

- [ ] Working tree clean on the exact commit you intend to tag (`git status` shows nothing to commit).
- [ ] CI is green on that commit. The single `verify` job (`.github/workflows/ci.yml`) runs on JDK 25: `:core:check`, the Bukkit plugin and NMS binding tests, `:spi:build`, the probe tests plus `:probe:run` and `:probe:deserializationProbe`, the shared modded tests on all three loaders, the `buildSrc` artifact-verifier tests, then `verifyBukkitArtifact` and `verifyModdedArtifacts` (which build all four artifacts). Do not release on a red or stale run.
- [ ] `MasterChangelog.MD` Iris section is coherent: one consolidated entry set, deduplicated, no date-sliced headers, and it describes the current shipped state (not superseded intermediate work). The file lives at the workspace root, one level above this repo (`../MasterChangelog.MD`); the section header is `## Plugin: Iris`.
- [ ] Version fields correct in `gradle.properties`: `irisVersion` is the release version and its trailing `-<mc>` suffix matches `minecraftVersion`. For a Minecraft bump, confirm every step in `85 - Maintainer - MC Version Bump.md` is done (loader ranges, `DataVersion`, NMS binding).
- [ ] JDK 25 is the active toolchain locally (`java -version` reports 25).

## b. Build

- [ ] From the Iris project root: `./build-all.sh`. It runs `buildAllToOut --no-parallel -PuseLocalVolmLib=false`, so local VolmLib substitution is off, the immutable `volmLibCoordinate` from `gradle.properties` is used, and the all-platform build is serialized. Each platform task runs its artifact verifier first, so the run either fails or prints a `Verified …` line per jar plus the `=== Iris buildAllToOut -> dist/ ===` summary.
- [ ] `dist/` contains the four platform jars (exact names for this release):
  - [ ] `Iris v4.0.0-26.2 [CraftBukkit] 26.1.2-26.2.jar` (Bukkit/Paper/Purpur/Spigot/Folia plugin)
  - [ ] `Iris v4.0.0-26.2 [Fabric] 26.2+0.19.3.jar`
  - [ ] `Iris v4.0.0-26.2 [Forge] 26.2+65.0.4.jar`
  - [ ] `Iris v4.0.0-26.2 [NeoForge] 26.2+26.2.0.12-beta.jar`
  - Naming pattern: `Iris v<irisVersion> [<Platform>] <target>.jar`. For the loader jars `<target>` is `<minecraftVersion>+<loaderDisplay>`; for CraftBukkit it is `bukkitMinecraftRange` (the supported Minecraft *range*, currently `26.1.2-26.2`), not `minecraftVersion`.
- [ ] The SPI jar is built by the same run at `spi/build/libs/iris-spi-4.0.0-26.2.jar`. It is the adapter/platform contract, not the stable downstream plugin API; it is not copied into `dist/` or uploaded to mod portals.
- [ ] Each mod jar bundles Iris core, SPI, and Iris-owned shaded libraries. LZ4, OSHI, JNA, and JNA Platform are supplied by the Minecraft 26.2 runtime and must not be bundled or relocated; `verifyModdedArtifacts` scans outer classes and nested jars for those packages and their relocated forms, so trust its pass rather than re-inspecting by hand.

## c. Verify (release gates)

- [ ] `:core:check` and `:probe:deserializationProbe` passed in CI on the tag commit (a. covers this).
- [ ] Golden-hash determinism VERIFY passes on all four platforms and matches the same hash (see `32 - Determinism & Goldenhash.md`):
  - [ ] Bukkit plugin: `/iris developer goldenhash world=<world> radius=<radius> threads=<threads>` (mode is hard-wired to `AUTO`, so it verifies when the matching capture already exists)
  - [ ] Fabric mod: `/iris goldenhash <radius> <threads> verify`
  - [ ] Forge mod: `/iris goldenhash <radius> <threads> verify`
  - [ ] NeoForge mod: `/iris goldenhash <radius> <threads> verify`
  - The modded mode literal comes last, after radius and threads; a bare `/iris goldenhash <radius> <threads>` runs `AUTO` instead.
  - The hash is interchangeable across platforms: all four MUST report identical output for the same pack and seed. Any mismatch blocks the release.
- [ ] Live modded content-mod gate: on each loader, boot the mod jar alongside a real content mod (e.g. Create) and generate an Iris world. Confirm no load-time rejection, no class-loader crash, and that modded blocks/items/entities author and generate.
  - [ ] Fabric + content mod
  - [ ] Forge + content mod
  - [ ] NeoForge + content mod
- [ ] Client-mod matrix: install the mod on the client (keybind `H` toggles the pregen HUD) and confirm:
  - [ ] Modded server + modded client: HUD receives pregen progress over `irisworldgen:main`.
  - [ ] Modded server + vanilla client: server generates normally; vanilla client is unaffected.
  - [ ] Paper (Bukkit) server + modded client: HUD receives pregen progress over vanilla plugin messaging.
  - [ ] Folia: plugin loads and an Iris world generates on Folia.
  - [ ] Non-Iris server + modded client: client is inert, no errors.

Operator-oriented sequences that support these gates: `31 - Operator Runbooks.md`. Client channel details: `29 - Client HUD & Protocol.md`.

## d. Publish (all manual, no automation)

- [ ] Modrinth: upload the three mod jars and the plugin jar. Tag loaders `fabric` / `forge` / `neoforge` on the mod files; mark the environment server + client. Game version is 26.2 for the three loader jars; the plugin jar covers the whole `bukkitMinecraftRange` (26.1.2 and 26.2), so tag both.
- [ ] CurseForge: upload the three mod jars with the matching loader tags and game version 26.2.
- [ ] Existing plugin distribution channels: publish the plugin jar (`Iris v4.0.0-26.2 [CraftBukkit] 26.1.2-26.2.jar`) where the plugin already ships.
- [ ] Sentry: register the release so incoming reports map to this version (`irisVersion` is the Sentry release tag). `./gradlew release` does this against `sentry.volmit.com` — it downloads `sentry-cli`, runs `releases new` and `releases set-commits --auto`, and needs `SENTRY_AUTH_TOKEN` (or `-Psentry.auth.token=`). It does not finalize the release; do that by hand if needed.
- [ ] Storepage staleness review: check the portal listing copy and the `storepage/` images for pre-4.0 content (Bukkit-only framing, old feature lists, stale screenshots). Flag anything stale for update before or right after launch. (Review only; this checklist does not change store copy.)

## e. Post

- [ ] Tag the release commit and push the tag. Existing tags in this repo carry no `v` prefix and encode the supported MC span (`3.9.2-1.20.1-1.21.11`), so pick the tag deliberately rather than assuming a format. Archive the already verified `dist/` bundle with the release record; no tag-triggered bundle automation is configured.
- [ ] Announce the release on the community channels once the portals show the new files live.

/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded;

import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.framework.StructureVerticalBounds;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.nativegen.NativeStructurePostProcessor;
import art.arcane.volmlib.util.json.JSONObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ModdedWorldCheck {
    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAILURE = 1;
    private static final int MAX_FOOTPRINT_CHUNKS = 96;
    private static final int MAX_START_REFERENCE_CHUNKS = 16;
    private static final int MAX_STRUCTURE_CANDIDATES = 1024;
    private static final long SERVER_WAIT_TIMEOUT_MILLIS = 600000L;
    private static final long SERVER_WAIT_INTERVAL_MILLIS = 250L;
    private static final List<StructureCheck> STRUCTURE_CHECKS = List.of(
            new StructureCheck("stronghold", List.of("minecraft:stronghold"), 256),
            new StructureCheck("trial_chambers", List.of("minecraft:trial_chambers"), 128),
            new StructureCheck("mansion", List.of("minecraft:mansion"), 256),
            new StructureCheck("village", List.of(
                    "minecraft:village_plains",
                    "minecraft:village_desert",
                    "minecraft:village_savanna",
                    "minecraft:village_snowy",
                    "minecraft:village_taiga"), 128),
            new StructureCheck("monument", List.of("minecraft:monument"), 128)
    );
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final ProcessExit PROCESS_EXIT = Runtime.getRuntime()::exit;
    private static volatile MinecraftServer startedServer;

    private ModdedWorldCheck() {
    }

    public static void schedule() {
        coordinatorThread(() -> waitAndRun(PROCESS_EXIT)).start();
    }

    public static void serverStarted(MinecraftServer server) {
        startedServer = server;
    }

    public static void serverStopped(MinecraftServer server) {
        if (startedServer == server) {
            startedServer = null;
        }
    }

    static Thread coordinatorThread(Runnable coordinator) {
        Thread thread = new Thread(coordinator, "Iris World Check");
        thread.setDaemon(false);
        return thread;
    }

    private static void waitAndRun(ProcessExit processExit) {
        long start = System.currentTimeMillis();
        MinecraftServer server = null;
        int exitCode = EXIT_FAILURE;
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        try {
            while (System.currentTimeMillis() - start < SERVER_WAIT_TIMEOUT_MILLIS) {
                MinecraftServer candidate = startedServer;
                if (candidate != null) {
                    server = candidate;
                    break;
                }
                Thread.sleep(SERVER_WAIT_INTERVAL_MILLIS);
            }

            if (server == null) {
                LOGGER.error("[worldcheck] server did not finish starting within 10 minutes");
                return;
            }

            MinecraftServer serverRef = server;
            WorldCheckPreparation preparation = serverRef.submit(() -> run(serverRef)).join();
            exitCode = serverRef.submit(() -> runAndRequestStop(
                    () -> completeWorldCheck(preparation),
                    () -> {
                        stopRequested.set(true);
                        serverRef.halt(false);
                    }
            )).join();
        } catch (InterruptedException e) {
            LOGGER.error("[worldcheck] coordinator interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            LOGGER.error("[worldcheck] check failed", e);
        } finally {
            int resultCode = exitCode;
            LOGGER.info("[worldcheck] shutting down dev server (result={})", resultCode == EXIT_PASS ? "PASS" : "FAIL");
            MinecraftServer serverRef = server;
            if (serverRef != null && stopRequested.get()) {
                awaitStopAndExit(() -> serverRef.halt(true), resultCode, processExit);
            } else {
                processExit.exit(EXIT_FAILURE);
            }
        }
    }

    static int runAndRequestStop(BooleanSupplier check, Runnable requestStop) {
        int exitCode = EXIT_FAILURE;
        try {
            exitCode = check.getAsBoolean() ? EXIT_PASS : EXIT_FAILURE;
        } catch (Throwable e) {
            LOGGER.error("[worldcheck] check failed", e);
        }
        try {
            requestStop.run();
        } catch (Throwable e) {
            LOGGER.error("[worldcheck] server stop request failed", e);
            return EXIT_FAILURE;
        }
        return exitCode;
    }

    static void awaitStopAndExit(Runnable awaitStop, int requestedExitCode, ProcessExit processExit) {
        int exitCode = requestedExitCode;
        boolean interrupted = Thread.interrupted();
        if (interrupted) {
            exitCode = EXIT_FAILURE;
        }
        try {
            awaitStop.run();
        } catch (Throwable e) {
            exitCode = EXIT_FAILURE;
            LOGGER.error("[worldcheck] waiting for server shutdown failed", e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        processExit.exit(exitCode);
    }

    private static WorldCheckPreparation run(MinecraftServer server) {
        ServerLevel level = targetLevel(server);
        if (level == null) {
            LOGGER.error("[worldcheck] no Iris dimension is loaded");
            return new WorldCheckPreparation(false, false, false, false,
                    new NativeStructureGate(false, 0, false, null));
        }

        String levelId = level.dimension().identifier().toString();
        String generatorClass = level.getChunkSource().getGenerator().getClass().getName();
        LOGGER.info("[worldcheck] {} generator: {}", levelId, generatorClass);
        IrisModdedChunkGenerator generator = level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator iris
                ? iris : null;
        boolean irisGenerator = generator != null;
        if (!irisGenerator) {
            LOGGER.error("[worldcheck] {} is NOT using IrisModdedChunkGenerator", levelId);
        }
        boolean dimensionTypeOk = generator != null && checkDimensionType(level, generator);

        BlockPos spawn = level.getRespawnData().pos();
        LOGGER.info("[worldcheck] spawn: {} {} {} (minY={} height={})", spawn.getX(), spawn.getY(), spawn.getZ(), level.getMinY(), level.getHeight());

        MessageDigest digest = sha256();
        List<String> samples = new ArrayList<>();
        Set<String> surfaceKeys = new LinkedHashSet<>();
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                int x = spawn.getX() + (dx - 2) * 16 + 8;
                int z = spawn.getZ() + (dz - 2) * 16 + 8;
                level.getChunk(x >> 4, z >> 4);
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockState surface = level.getBlockState(new BlockPos(x, y - 1, z));
                String key = BuiltInRegistries.BLOCK.getKey(surface.getBlock()).toString();
                String line = x + " " + (y - 1) + " " + z + " " + key;
                samples.add(line);
                surfaceKeys.add(key);
                digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }

        for (int i = 0; i < Math.min(6, samples.size()); i++) {
            LOGGER.info("[worldcheck] surface sample: {}", samples.get(i));
        }
        LOGGER.info("[worldcheck] surface digest: {} ({} columns, {} distinct surface blocks: {})",
                HexFormat.of().formatHex(digest.digest()).substring(0, 12), samples.size(), surfaceKeys.size(), surfaceKeys);

        ChunkAccess zeroChunk = level.getChunk(0, 0);
        int nonEmptySections = 0;
        for (LevelChunkSection section : zeroChunk.getSections()) {
            if (!section.hasOnlyAir()) {
                nonEmptySections++;
            }
        }
        Set<String> columnKeys = new LinkedHashSet<>();
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
        for (int y = level.getMinY(); y < surfaceY; y += 16) {
            BlockState state = zeroChunk.getBlockState(new BlockPos(8, y, 8));
            if (!state.isAir()) {
                columnKeys.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }
        }
        LOGGER.info("[worldcheck] chunk 0,0: {} non-empty sections of {}; column blocks at (8,*,8): {}",
                nonEmptySections, zeroChunk.getSections().length, columnKeys);

        boolean sectionsOk = nonEmptySections >= 4;
        boolean varietyOk = columnKeys.size() >= 2 || surfaceKeys.size() >= 2;
        if (!sectionsOk) {
            LOGGER.error("[worldcheck] chunk 0,0 looks empty/vanilla-flat ({} non-empty sections)", nonEmptySections);
        }
        if (!varietyOk) {
            LOGGER.error("[worldcheck] generated terrain has no block variety (flat-world signature)");
        }

        boolean entityMixinsOk = checkEntityMixins(level);
        NativeStructureGate structureGate = generator == null
                ? new NativeStructureGate(false, 0, false, null)
                : checkNativeStructures(level, generator, spawn);
        boolean terrainOk = sectionsOk && varietyOk;
        boolean nonStructurePass = irisGenerator && dimensionTypeOk && terrainOk && entityMixinsOk;
        return new WorldCheckPreparation(nonStructurePass, terrainOk, dimensionTypeOk,
                entityMixinsOk, structureGate);
    }

    private static boolean completeWorldCheck(WorldCheckPreparation preparation) {
        NativeStructureGate structureGate = preparation.structureGate();
        boolean poiOk = false;
        PendingVillagePoi pendingPoi = structureGate.pendingPoi();
        if (pendingPoi != null) {
            PoiAudit poi = auditStructurePois(pendingPoi.level(), pendingPoi.start());
            poiOk = villagePoiPass(poi.inBounds(), poi.outOfBounds());
            qaEvent("village_poi_metric", "village", poiOk,
                    "inBounds=" + poi.inBounds() + ",outOfBounds=" + poi.outOfBounds());
            if (!poiOk) {
                LOGGER.error("[worldcheck] village POI audit failed: inBounds={} outOfBounds={}",
                        poi.inBounds(), poi.outOfBounds());
            }
        } else {
            qaEvent("village_poi_metric", "village", false, "skipped=structure");
        }
        int passed = structureGate.nonVillagePassed()
                + (structureGate.villagePassBeforePoi() && poiOk ? 1 : 0);
        boolean structurePass = structureGate.passBeforePoi() && poiOk;
        LOGGER.info("[worldcheck] native structure gate: {}/{} passed", passed, STRUCTURE_CHECKS.size());
        qaEvent("structure_aggregate", "all", structurePass,
                "passed=" + passed + ",total=" + STRUCTURE_CHECKS.size());
        boolean pass = preparation.nonStructurePass() && structurePass;
        LOGGER.info("[worldcheck] {}", pass ? "PASS" : "FAIL");
        qaEvent("worldcheck_final", "all", pass,
                "structures=" + STRUCTURE_CHECKS.size() + ",terrain=" + preparation.terrainOk()
                        + ",dimensionType=" + preparation.dimensionTypeOk()
                        + ",entityMixins=" + preparation.entityMixinsOk());
        return pass;
    }

    private static boolean checkDimensionType(ServerLevel level, IrisModdedChunkGenerator generator) {
        try {
            IrisDimension dimension = generator.commandEngine().getDimension();
            DimensionContract expected = expectedDimensionContract(dimension);
            DimensionContract actual = runtimeDimensionContract(level.dimensionType());
            boolean pass = matchesDimensionContract(level.getMinY(), level.getHeight(), expected, actual);
            String detail = "expected=" + expected + ",actual=" + actual
                    + ",levelMinY=" + level.getMinY() + ",levelHeight=" + level.getHeight();
            qaEvent("dimension_type", dimension.getLoadKey(), pass, detail);
            if (!pass) {
                LOGGER.error("[worldcheck] dimension type mismatch for {}: {}", dimension.getLoadKey(), detail);
            } else {
                LOGGER.info("[worldcheck] dimension type contract: {}", detail);
            }
            return pass;
        } catch (Throwable error) {
            LOGGER.error("[worldcheck] could not validate the Iris dimension type contract", error);
            qaEvent("dimension_type", generator.activeDimensionKey(), false,
                    "validationError=" + error.getClass().getSimpleName() + ":" + error.getMessage());
            return false;
        }
    }

    static DimensionContract expectedDimensionContract(IrisDimension dimension) {
        JSONObject json = new JSONObject(dimension.getDimensionType().toJson(DataVersion.getLatest().get()));
        return new DimensionContract(
                json.getInt("min_y"),
                json.getInt("height"),
                json.getInt("logical_height"),
                json.getDouble("coordinate_scale"),
                (float) json.getDouble("ambient_light"),
                json.getBoolean("has_skylight"),
                json.getBoolean("has_ceiling"),
                json.getBoolean("has_ender_dragon_fight"),
                json.getInt("monster_spawn_block_light_limit"));
    }

    static DimensionContract runtimeDimensionContract(DimensionType dimensionType) {
        return new DimensionContract(
                dimensionType.minY(),
                dimensionType.height(),
                dimensionType.logicalHeight(),
                dimensionType.coordinateScale(),
                dimensionType.ambientLight(),
                dimensionType.hasSkyLight(),
                dimensionType.hasCeiling(),
                dimensionType.hasEnderDragonFight(),
                dimensionType.monsterSpawnBlockLightLimit());
    }

    static boolean matchesDimensionContract(int levelMinY, int levelHeight,
                                            DimensionContract expected, DimensionContract actual) {
        return levelMinY == expected.minY()
                && levelHeight == expected.height()
                && actual.equals(expected);
    }

    private static boolean checkEntityMixins(ServerLevel level) {
        ItemEntity item = new ItemEntity(level, 0D, level.getMinY(), 0D, Items.COBBLESTONE.getDefaultInstance());
        boolean vanillaSave = item.shouldBeSaved();
        ModdedEntityPersistence.configure(item, false);
        boolean suppressed = !item.shouldBeSaved();
        ModdedEntityPersistence.configure(item, true);
        boolean restored = item.shouldBeSaved();
        boolean pass = vanillaSave && suppressed && restored;
        qaEvent("entity_mixin", "persistence", pass,
                "vanilla=" + vanillaSave + ",suppressed=" + suppressed + ",restored=" + restored);
        if (!pass) {
            LOGGER.error("[worldcheck] shared entity mixins are not active on this loader");
        }
        return pass;
    }

    private static NativeStructureGate checkNativeStructures(ServerLevel level,
                                                             IrisModdedChunkGenerator generator,
                                                             BlockPos origin) {
        boolean pass = true;
        int nonVillagePassed = 0;
        boolean villagePass = false;
        PendingVillagePoi pendingPoi = null;
        for (StructureCheck check : STRUCTURE_CHECKS) {
            StructureCheckResult result = checkNativeStructure(level, generator, origin, check);
            if (!result.pass()) {
                pass = false;
            }
            if (check.label().equals("village")) {
                villagePass = result.pass();
                pendingPoi = result.pendingPoi();
            } else if (result.pass()) {
                nonVillagePassed++;
            }
        }
        return new NativeStructureGate(pass, nonVillagePassed, villagePass, pendingPoi);
    }

    private static StructureCheckResult checkNativeStructure(ServerLevel level,
                                                             IrisModdedChunkGenerator generator,
                                                             BlockPos origin,
                                                             StructureCheck check) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> registered = new ArrayList<>(check.registryKeys().size());
        LinkedHashSet<String> registeredKeys = new LinkedHashSet<>();
        for (String key : check.registryKeys()) {
            Identifier identifier = Identifier.tryParse(key);
            if (identifier == null) {
                continue;
            }
            Optional<Holder.Reference<Structure>> resolved = registry.get(identifier);
            if (resolved.isPresent()) {
                registered.add(resolved.get());
                registeredKeys.add(identifier.toString());
            }
        }
        boolean registryOk = registered.size() == check.registryKeys().size();
        LOGGER.info("[worldcheck] {} registry: {}/{} resolved {}", check.label(), registered.size(),
                check.registryKeys().size(), registeredKeys);
        qaEvent("structure_registry", check.label(), registryOk,
                "resolved=" + registered.size() + ",expected=" + check.registryKeys().size()
                        + ",keys=" + String.join("|", registeredKeys));
        if (!registryOk) {
            LOGGER.error("[worldcheck] {} registry resolution failed; expected {}", check.label(), check.registryKeys());
            emitSkipped(check, "registry", "structure_reachability", "structure_locate",
                    "structure_start_reference", "structure_footprint", "structure_material",
                    "structure_block_entity");
            return new StructureCheckResult(false, null);
        }

        List<Holder<Structure>> reachable = new ArrayList<>(registered.size());
        LinkedHashSet<String> reachableKeys = new LinkedHashSet<>();
        for (Holder<Structure> holder : registered) {
            if (!generator.isNativeStructureReachable(holder)) {
                continue;
            }
            reachable.add(holder);
            Identifier key = registry.getKey(holder.value());
            if (key != null) {
                reachableKeys.add(key.toString());
            }
        }
        boolean reachableOk = !reachable.isEmpty();
        LOGGER.info("[worldcheck] {} biome-reachable through Iris: {}", check.label(), reachableKeys);
        qaEvent("structure_reachability", check.label(), reachableOk,
                "reachable=" + reachable.size() + ",registered=" + registered.size()
                        + ",keys=" + String.join("|", reachableKeys));
        if (!reachableOk) {
            LOGGER.error("[worldcheck] {} cannot generate in any biome produced by this Iris pack", check.label());
            emitSkipped(check, "reachability", "structure_locate", "structure_start_reference",
                    "structure_footprint", "structure_material", "structure_block_entity");
            return new StructureCheckResult(false, null);
        }

        long locateStart = System.nanoTime();
        Pair<BlockPos, Holder<Structure>> found = findGeneratedStructureCandidate(
                level, reachable, origin, check.locateRadius());
        long locateMillis = (System.nanoTime() - locateStart) / 1_000_000L;
        Identifier foundKey = found == null ? null : registry.getKey(found.getSecond().value());
        boolean locateOk = found != null && foundKey != null && reachableKeys.contains(foundKey.toString());
        qaEvent("structure_locate", check.label(), locateOk,
                "method=placement_candidates,millis=" + locateMillis + ",radius=" + check.locateRadius()
                        + ",result=" + (foundKey == null ? "none" : foundKey));
        if (found == null) {
            LOGGER.error("[worldcheck] {} native placement candidates produced no valid start within {} rings after {}ms",
                    check.label(), check.locateRadius(), locateMillis);
            emitSkipped(check, "locate", "structure_start_reference", "structure_footprint",
                    "structure_material", "structure_block_entity");
            return new StructureCheckResult(false, null);
        }

        BlockPos position = found.getFirst();
        LOGGER.info("[worldcheck] {} generated candidate: {} {} {} in {}ms (radius={}, result={})",
                check.label(), position.getX(), position.getY(), position.getZ(), locateMillis,
                check.locateRadius(), foundKey);
        if (!locateOk) {
            LOGGER.error("[worldcheck] {} candidate scan returned unexpected structure {}", check.label(), foundKey);
            emitSkipped(check, "locate", "structure_start_reference", "structure_footprint",
                    "structure_material", "structure_block_entity");
            return new StructureCheckResult(false, null);
        }

        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;
        ChunkAccess targetChunk = level.getChunk(chunkX, chunkZ);
        Structure structure = found.getSecond().value();
        StructureStart start = resolveStructureStart(level, targetChunk, structure);
        boolean validStart = start != null && start.isValid();
        int references = targetChunk.getReferencesForStructure(structure).size();
        boolean startReferenceOk = hasNativeStructureEvidence(validStart, references);
        LOGGER.info("[worldcheck] {} target chunk {},{}: valid start={}, references={}",
                check.label(), chunkX, chunkZ, validStart, references);
        qaEvent("structure_start_reference", check.label(), startReferenceOk,
                "chunk=" + chunkX + "," + chunkZ + ",validStart=" + validStart
                        + ",references=" + references);
        if (!startReferenceOk || !validStart) {
            LOGGER.error("[worldcheck] {} located at chunk {},{} but no resolvable valid start was generated",
                    check.label(), chunkX, chunkZ);
            emitSkipped(check, "start_reference", "structure_footprint", "structure_material",
                    "structure_block_entity");
            return new StructureCheckResult(false, null);
        }

        IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(
                generator.commandEngine(), foundKey.toString(),
                NativeStructurePostProcessor.isUndergroundStep(structure.step()));
        Integer appliedShift = generator.worldCheckStructureShift(foundKey.toString(), start.getChunkPos());
        BoundingBox shiftedBounds = start.getBoundingBox();
        boolean verticalShiftOk = verticalShiftMatches(
                decision.yShift(), appliedShift, shiftedBounds.minY(), shiftedBounds.maxY(),
                level.getMinY(), level.getMaxY());
        qaEvent("structure_vertical_shift", check.label(), verticalShiftOk,
                "configured=" + decision.yShift() + ",applied="
                        + (appliedShift == null ? "unrecorded" : appliedShift));
        if (!verticalShiftOk) {
            LOGGER.error("[worldcheck] {} expected vertical shift {} but generation recorded {}",
                    check.label(), decision.yShift(), appliedShift);
        }

        FootprintAudit footprint = auditFootprint(level, structure, start, check, foundKey);
        boolean footprintOk = footprint.inspectedChunks() > 0
                && footprint.evidenceChunks() == footprint.inspectedChunks()
                && footprint.coveredPieces() == footprint.totalPieces();
        LOGGER.info("[worldcheck] {} footprint: chunks={}/{} evidence={} pieces={}/{}",
                check.label(), footprint.inspectedChunks(), footprint.availableChunks(),
                footprint.evidenceChunks(), footprint.coveredPieces(), footprint.totalPieces());
        qaEvent("structure_footprint", check.label(), footprintOk,
                "inspected=" + footprint.inspectedChunks() + ",available=" + footprint.availableChunks()
                        + ",evidence=" + footprint.evidenceChunks() + ",coveredPieces="
                        + footprint.coveredPieces() + ",totalPieces=" + footprint.totalPieces());

        boolean materialOk = hasCharacteristicMaterialEvidence(footprint.characteristicBlocks(),
                footprint.characteristicChunks(), footprint.materialScannedChunks());
        LOGGER.info("[worldcheck] {} material: blocks={} chunks={}/{}",
                check.label(), footprint.characteristicBlocks(), footprint.characteristicChunks(),
                footprint.materialScannedChunks());
        qaEvent("structure_material", check.label(), materialOk,
                "blocks=" + footprint.characteristicBlocks() + ",chunks=" + footprint.characteristicChunks()
                        + ",scanned=" + footprint.materialScannedChunks());

        boolean vegetationOk = true;
        if (check.label().equals("mansion")) {
            boolean overlap = footprint.vegetationBlocks() > 0;
            vegetationOk = mansionVegetationPass(footprint.vegetationBlocks());
            LOGGER.info("[worldcheck] mansion vegetation metric: remaining log/leaf blocks={} columns={} overlap={}",
                    footprint.vegetationBlocks(), footprint.vegetationColumns(), overlap);
            qaEvent("mansion_vegetation_metric", check.label(), vegetationOk,
                    "remainingLogsOrLeaves=" + footprint.vegetationBlocks() + ",columns="
                            + footprint.vegetationColumns() + ",overlap=" + overlap);
        }

        boolean foundationOk = true;
        PendingVillagePoi pendingPoi = null;
        if (check.label().equals("village")) {
            foundationOk = villageFoundationPass(footprint.foundationGapColumns());
            LOGGER.info("[worldcheck] village foundation metric: bases={} cobblestone={} columns={} unsupported={}",
                    footprint.foundationBaseColumns(), footprint.foundationBlocks(),
                    footprint.foundationColumns(), footprint.foundationGapColumns());
            qaEvent("village_foundation_metric", check.label(), foundationOk,
                    "bases=" + footprint.foundationBaseColumns() + ",cobblestoneBelowBase="
                            + footprint.foundationBlocks() + ",columns="
                            + footprint.foundationColumns() + ",unsupported=" + footprint.foundationGapColumns());
            pendingPoi = new PendingVillagePoi(level, start);
        }

        boolean blockEntityOk = footprint.blockEntityStates() == footprint.blockEntitiesPresent();
        LOGGER.info("[worldcheck] {} block entities: state blocks={}, present={}, missing={}",
                check.label(), footprint.blockEntityStates(), footprint.blockEntitiesPresent(),
                footprint.blockEntityStates() - footprint.blockEntitiesPresent());
        qaEvent("structure_block_entity", check.label(), blockEntityOk,
                "states=" + footprint.blockEntityStates() + ",present=" + footprint.blockEntitiesPresent()
                        + ",missing=" + (footprint.blockEntityStates() - footprint.blockEntitiesPresent()));
        if (!footprintOk) {
            LOGGER.error("[worldcheck] {} structure footprint is incomplete", check.label());
        }
        if (!materialOk) {
            LOGGER.error("[worldcheck] {} has no distributed characteristic structure material", check.label());
        }
        if (!blockEntityOk) {
            LOGGER.error("[worldcheck] {} generated block-entity states without matching block entities", check.label());
        }
        if (!vegetationOk) {
            LOGGER.error("[worldcheck] mansion vegetation still intersects the generated structure footprint");
        }
        if (!foundationOk) {
            LOGGER.error("[worldcheck] village has unsupported foundation columns after stilt placement");
        }
        boolean pass = verticalShiftOk && footprintOk && materialOk && blockEntityOk
                && vegetationOk && foundationOk;
        return new StructureCheckResult(pass, pass ? pendingPoi : null);
    }

    private static StructureStart resolveStructureStart(ServerLevel level, ChunkAccess targetChunk,
                                                        Structure structure) {
        StructureStart direct = targetChunk.getStartForStructure(structure);
        if (direct != null && direct.isValid()) {
            return direct;
        }
        int checked = 0;
        for (long packed : targetChunk.getReferencesForStructure(structure)) {
            if (checked++ >= MAX_START_REFERENCE_CHUNKS) {
                break;
            }
            ChunkAccess referencedChunk = level.getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
            StructureStart referenced = referencedChunk.getStartForStructure(structure);
            if (referenced != null && referenced.isValid()) {
                return referenced;
            }
        }
        return direct;
    }

    private static Pair<BlockPos, Holder<Structure>> findGeneratedStructureCandidate(
            ServerLevel level, List<Holder<Structure>> structures, BlockPos origin, int maxRadius) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        Set<Long> attempted = new LinkedHashSet<>();
        for (Holder<Structure> structure : structures) {
            for (StructurePlacement placement : state.getPlacementsForStructure(structure)) {
                if (!(placement instanceof ConcentricRingsStructurePlacement rings)) {
                    continue;
                }
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions == null) {
                    continue;
                }
                List<ChunkPos> sorted = new ArrayList<>(positions);
                sorted.sort(Comparator.comparingLong(position -> distanceSquared(origin, position)));
                for (ChunkPos position : sorted) {
                    Pair<BlockPos, Holder<Structure>> found = inspectStructureCandidate(
                            level, structures, placement, position, attempted);
                    if (found != null) {
                        return found;
                    }
                    if (attempted.size() >= MAX_STRUCTURE_CANDIDATES) {
                        return null;
                    }
                }
            }
        }

        int originChunkX = origin.getX() >> 4;
        int originChunkZ = origin.getZ() >> 4;
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (Holder<Structure> structure : structures) {
                for (StructurePlacement placement : state.getPlacementsForStructure(structure)) {
                    if (!(placement instanceof RandomSpreadStructurePlacement randomSpread)) {
                        continue;
                    }
                    for (int x = -radius; x <= radius; x++) {
                        boolean xEdge = x == -radius || x == radius;
                        for (int z = -radius; z <= radius; z++) {
                            if (!xEdge && z != -radius && z != radius) {
                                continue;
                            }
                            int sectorX = originChunkX + randomSpread.spacing() * x;
                            int sectorZ = originChunkZ + randomSpread.spacing() * z;
                            ChunkPos candidate = randomSpread.getPotentialStructureChunk(
                                    state.getLevelSeed(), sectorX, sectorZ);
                            if (!placement.isStructureChunk(state, candidate.x(), candidate.z())) {
                                continue;
                            }
                            Pair<BlockPos, Holder<Structure>> found = inspectStructureCandidate(
                                    level, structures, placement, candidate, attempted);
                            if (found != null) {
                                return found;
                            }
                            if (attempted.size() >= MAX_STRUCTURE_CANDIDATES) {
                                return null;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Pair<BlockPos, Holder<Structure>> inspectStructureCandidate(
            ServerLevel level, List<Holder<Structure>> structures, StructurePlacement placement,
            ChunkPos candidate, Set<Long> attempted) {
        if (!attempted.add(candidate.pack())) {
            return null;
        }
        ChunkAccess chunk = level.getChunk(candidate.x(), candidate.z());
        for (Holder<Structure> structure : structures) {
            StructureStart start = resolveStructureStart(level, chunk, structure.value());
            if (start == null || !start.isValid()) {
                continue;
            }
            BlockPos locate = placement.getLocatePos(start.getChunkPos());
            BlockPos resolved = new BlockPos(locate.getX(), start.getBoundingBox().minY(), locate.getZ());
            return Pair.of(resolved, structure);
        }
        return null;
    }

    private static long distanceSquared(BlockPos origin, ChunkPos position) {
        long x = (long) position.getMinBlockX() - origin.getX();
        long z = (long) position.getMinBlockZ() - origin.getZ();
        return x * x + z * z;
    }

    private static FootprintAudit auditFootprint(ServerLevel level, Structure structure, StructureStart start,
                                                 StructureCheck check, Identifier structureKey) {
        List<StructurePiece> pieces = start.getPieces();
        BoundingBox bounds = start.getBoundingBox();
        int availableChunks = footprintChunkCount(bounds);
        List<ChunkPos> selected = selectFootprintChunks(start, MAX_FOOTPRINT_CHUNKS);
        int evidenceChunks = 0;
        int blockEntityStates = 0;
        int blockEntitiesPresent = 0;
        int materialScannedChunks = 0;
        int characteristicBlocks = 0;
        int characteristicChunks = 0;
        int vegetationBlocks = 0;
        int vegetationColumns = 0;
        int foundationBaseColumns = 0;
        int foundationBlocks = 0;
        int foundationColumns = 0;
        int foundationGapColumns = 0;
        BitSet visited = new BitSet(level.getHeight() << 8);
        int[] maximumPieceY = new int[256];
        for (ChunkPos chunkPos : selected) {
            ChunkAccess chunk = level.getChunk(chunkPos.x(), chunkPos.z());
            StructureStart localStart = chunk.getStartForStructure(structure);
            boolean validStart = localStart != null && localStart.isValid();
            int references = chunk.getReferencesForStructure(structure).size();
            if (hasNativeStructureEvidence(validStart, references)) {
                evidenceChunks++;
            }
            BlockEntityAudit blockEntities = auditBlockEntities(level, chunk);
            blockEntityStates += blockEntities.states();
            blockEntitiesPresent += blockEntities.present();
            StructureMaterialAudit material = auditStructureMaterial(level, chunk, start, check,
                    structureKey, visited, maximumPieceY);
            if (material.scanned()) {
                materialScannedChunks++;
            }
            characteristicBlocks += material.characteristicBlocks();
            if (material.characteristicBlocks() > 0) {
                characteristicChunks++;
            }
            vegetationBlocks += material.vegetationBlocks();
            vegetationColumns += material.vegetationColumns();
            foundationBaseColumns += material.foundationBaseColumns();
            foundationBlocks += material.foundationBlocks();
            foundationColumns += material.foundationColumns();
            foundationGapColumns += material.foundationGapColumns();
        }
        int coveredPieces = 0;
        for (StructurePiece piece : pieces) {
            boolean covered = false;
            for (ChunkPos chunkPos : selected) {
                if (intersectsChunk(piece.getBoundingBox(), chunkPos)) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                coveredPieces++;
            }
        }
        return new FootprintAudit(selected.size(), availableChunks, evidenceChunks,
                coveredPieces, pieces.size(), blockEntityStates, blockEntitiesPresent,
                materialScannedChunks, characteristicBlocks, characteristicChunks,
                vegetationBlocks, vegetationColumns, foundationBaseColumns, foundationBlocks,
                foundationColumns, foundationGapColumns);
    }

    private static StructureMaterialAudit auditStructureMaterial(ServerLevel level, ChunkAccess chunk,
                                                                  StructureStart start,
                                                                  StructureCheck check,
                                                                  Identifier structureKey,
                                                                  BitSet visited,
                                                                  int[] maximumPieceY) {
        List<StructurePiece> pieces = start.getPieces();
        visited.clear();
        Arrays.fill(maximumPieceY, Integer.MIN_VALUE);
        int characteristicBlocks = 0;
        int minimumWorldY = level.getMinY();
        int maximumWorldY = level.getMaxY() - 1;
        int minimumChunkX = chunk.getPos().getMinBlockX();
        int maximumChunkX = chunk.getPos().getMaxBlockX();
        int minimumChunkZ = chunk.getPos().getMinBlockZ();
        int maximumChunkZ = chunk.getPos().getMaxBlockZ();
        boolean scanned = false;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (StructurePiece piece : pieces) {
            BoundingBox bounds = piece.getBoundingBox();
            int minimumX = Math.max(minimumChunkX, bounds.minX());
            int maximumX = Math.min(maximumChunkX, bounds.maxX());
            int minimumY = Math.max(minimumWorldY, bounds.minY());
            int maximumY = Math.min(maximumWorldY, bounds.maxY());
            int minimumZ = Math.max(minimumChunkZ, bounds.minZ());
            int maximumZ = Math.min(maximumChunkZ, bounds.maxZ());
            if (minimumX > maximumX || minimumY > maximumY || minimumZ > maximumZ) {
                continue;
            }
            scanned = true;
            for (int z = minimumZ; z <= maximumZ; z++) {
                int localZ = z - minimumChunkZ;
                for (int x = minimumX; x <= maximumX; x++) {
                    int column = (localZ << 4) | (x - minimumChunkX);
                    maximumPieceY[column] = Math.max(maximumPieceY[column], maximumY);
                }
            }
            for (int y = minimumY; y <= maximumY; y++) {
                int verticalIndex = (y - minimumWorldY) << 8;
                for (int z = minimumZ; z <= maximumZ; z++) {
                    int localZ = z - minimumChunkZ;
                    for (int x = minimumX; x <= maximumX; x++) {
                        int column = (localZ << 4) | (x - minimumChunkX);
                        int index = verticalIndex | column;
                        if (visited.get(index)) {
                            continue;
                        }
                        visited.set(index);
                        BlockState state = chunk.getBlockState(position.set(x, y, z));
                        Identifier blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                        if (isCharacteristicMaterial(check.label(), structureKey, blockKey)) {
                            characteristicBlocks++;
                        }
                    }
                }
            }
        }

        int vegetationBlocks = 0;
        int vegetationColumns = 0;
        if (check.label().equals("mansion")) {
            for (int column = 0; column < maximumPieceY.length; column++) {
                int highestPieceY = maximumPieceY[column];
                if (highestPieceY == Integer.MIN_VALUE || highestPieceY >= maximumWorldY) {
                    continue;
                }
                boolean vegetationColumn = false;
                int x = minimumChunkX + (column & 15);
                int z = minimumChunkZ + (column >> 4);
                for (int y = highestPieceY + 1; y <= maximumWorldY; y++) {
                    BlockState state = chunk.getBlockState(position.set(x, y, z));
                    boolean vegetation = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
                    if (!mansionVegetationAbovePiece(vegetation, y, highestPieceY)) {
                        continue;
                    }
                    vegetationBlocks++;
                    vegetationColumn = true;
                }
                if (vegetationColumn) {
                    vegetationColumns++;
                }
            }
        }

        int foundationBaseColumns = 0;
        int foundationBlocks = 0;
        int foundationColumns = 0;
        int foundationGapColumns = 0;
        if (check.label().equals("village")) {
            BoundingBox area = new BoundingBox(
                    minimumChunkX, minimumWorldY, minimumChunkZ,
                    maximumChunkX, maximumWorldY, maximumChunkZ);
            NativeStructurePostProcessor.StiltSupportAudit foundation =
                    NativeStructurePostProcessor.auditStiltSupport(
                            level, area, start, Blocks.COBBLESTONE.defaultBlockState());
            foundationBaseColumns = foundation.baseColumns();
            foundationBlocks = foundation.stiltBlocks();
            foundationColumns = foundation.stiltColumns();
            foundationGapColumns = foundation.unsupportedColumns();
        }

        return new StructureMaterialAudit(scanned, characteristicBlocks, vegetationBlocks,
                vegetationColumns, foundationBaseColumns, foundationBlocks, foundationColumns,
                foundationGapColumns);
    }

    private static List<ChunkPos> selectFootprintChunks(StructureStart start, int limit) {
        LinkedHashSet<ChunkPos> selected = new LinkedHashSet<>();
        addBounded(selected, start.getChunkPos(), limit);
        List<ChunkPos> pieceAnchors = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox bounds = piece.getBoundingBox();
            pieceAnchors.add(new ChunkPos((bounds.minX() + bounds.maxX()) >> 5,
                    (bounds.minZ() + bounds.maxZ()) >> 5));
            pieceAnchors.add(new ChunkPos(bounds.minX() >> 4, bounds.minZ() >> 4));
            pieceAnchors.add(new ChunkPos(bounds.maxX() >> 4, bounds.maxZ() >> 4));
        }
        pieceAnchors.sort(Comparator.comparingInt((ChunkPos chunkPos) ->
                chunkPos.distanceSquared(start.getChunkPos())));
        for (ChunkPos chunkPos : pieceAnchors) {
            addBounded(selected, chunkPos, limit);
        }
        for (ChunkPos chunkPos : boundedFootprintChunks(start.getBoundingBox(), start.getChunkPos(), limit)) {
            if (intersectsAnyPiece(start.getPieces(), chunkPos)) {
                addBounded(selected, chunkPos, limit);
            }
        }
        return List.copyOf(selected);
    }

    static List<ChunkPos> boundedFootprintChunks(BoundingBox bounds, ChunkPos origin, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        long width = (long) maxChunkX - minChunkX + 1L;
        long depth = (long) maxChunkZ - minChunkZ + 1L;
        long total = width * depth;
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        addBounded(chunks, origin, limit);
        if (total <= limit) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    addBounded(chunks, new ChunkPos(chunkX, chunkZ), limit);
                }
            }
            return List.copyOf(chunks);
        }
        addBounded(chunks, new ChunkPos(minChunkX, minChunkZ), limit);
        addBounded(chunks, new ChunkPos(maxChunkX, minChunkZ), limit);
        addBounded(chunks, new ChunkPos(minChunkX, maxChunkZ), limit);
        addBounded(chunks, new ChunkPos(maxChunkX, maxChunkZ), limit);
        int samplesPerAxis = Math.max(2, (int) Math.floor(Math.sqrt(limit)));
        for (int sampleZ = 0; sampleZ < samplesPerAxis; sampleZ++) {
            int chunkZ = sampleCoordinate(minChunkZ, maxChunkZ, sampleZ, samplesPerAxis);
            for (int sampleX = 0; sampleX < samplesPerAxis; sampleX++) {
                int chunkX = sampleCoordinate(minChunkX, maxChunkX, sampleX, samplesPerAxis);
                addBounded(chunks, new ChunkPos(chunkX, chunkZ), limit);
            }
        }
        return List.copyOf(chunks);
    }

    private static int footprintChunkCount(BoundingBox bounds) {
        long width = (long) (bounds.maxX() >> 4) - (bounds.minX() >> 4) + 1L;
        long depth = (long) (bounds.maxZ() >> 4) - (bounds.minZ() >> 4) + 1L;
        long total = width * depth;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static int sampleCoordinate(int minimum, int maximum, int index, int samples) {
        if (samples <= 1 || minimum == maximum) {
            return minimum;
        }
        double progress = (double) index / (double) (samples - 1);
        return minimum + (int) Math.round((maximum - minimum) * progress);
    }

    private static void addBounded(Set<ChunkPos> chunks, ChunkPos chunkPos, int limit) {
        if (chunks.size() < limit) {
            chunks.add(chunkPos);
        }
    }

    private static boolean intersectsAnyPiece(List<StructurePiece> pieces, ChunkPos chunkPos) {
        for (StructurePiece piece : pieces) {
            if (intersectsChunk(piece.getBoundingBox(), chunkPos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsChunk(BoundingBox bounds, ChunkPos chunkPos) {
        return bounds.maxX() >= chunkPos.getMinBlockX()
                && bounds.minX() <= chunkPos.getMaxBlockX()
                && bounds.maxZ() >= chunkPos.getMinBlockZ()
                && bounds.minZ() <= chunkPos.getMaxBlockZ();
    }

    private static BlockEntityAudit auditBlockEntities(ServerLevel level, ChunkAccess chunk) {
        int states = 0;
        int present = 0;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (!section.maybeHas(BlockState::hasBlockEntity)) {
                continue;
            }
            int sectionMinY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (!state.hasBlockEntity()) {
                            continue;
                        }
                        states++;
                        position.set(chunk.getPos().getBlockX(localX), sectionMinY + localY,
                                chunk.getPos().getBlockZ(localZ));
                        if (level.getBlockEntity(position) != null) {
                            present++;
                        }
                    }
                }
            }
        }
        return new BlockEntityAudit(states, present);
    }

    private static PoiAudit auditStructurePois(ServerLevel level, StructureStart start) {
        int inBounds = 0;
        int outOfBounds = 0;
        PoiManager poiManager = level.getPoiManager();
        for (ChunkPos chunkPos : selectFootprintChunks(start, MAX_FOOTPRINT_CHUNKS)) {
            List<PoiRecord> records = poiManager.getInChunk(
                    holder -> true, chunkPos, PoiManager.Occupancy.ANY).toList();
            for (PoiRecord record : records) {
                BlockPos position = record.getPos();
                if (position.getY() < level.getMinY() || position.getY() >= level.getMaxY()) {
                    outOfBounds++;
                    continue;
                }
                if (insideAnyPiece(start.getPieces(), position)) {
                    inBounds++;
                }
            }
        }
        return new PoiAudit(inBounds, outOfBounds);
    }

    private static boolean insideAnyPiece(List<StructurePiece> pieces, BlockPos position) {
        for (StructurePiece piece : pieces) {
            if (piece.getBoundingBox().isInside(position)) {
                return true;
            }
        }
        return false;
    }

    private static void emitSkipped(StructureCheck check, String reason, String... events) {
        for (String event : events) {
            qaEvent(event, check.label(), false, "skipped=" + reason);
        }
    }

    private static void qaEvent(String event, String structure, boolean pass, String detail) {
        LOGGER.info(qaEventJson(event, structure, pass, detail));
    }

    static String qaEventJson(String event, String structure, boolean pass, String detail) {
        return "QA_EVT {\"event\":\"" + jsonEscape(event)
                + "\",\"structure\":\"" + jsonEscape(structure)
                + "\",\"pass\":" + pass
                + ",\"detail\":\"" + jsonEscape(detail) + "\"}";
    }

    static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 32) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(character);
                        escaped.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    static boolean hasNativeStructureEvidence(boolean validStart, int references) {
        return validStart || references > 0;
    }

    static boolean hasCharacteristicMaterialEvidence(int blocks, int chunksWithMaterial, int scannedChunks) {
        if (blocks <= 0 || chunksWithMaterial <= 0 || scannedChunks <= 0
                || chunksWithMaterial > scannedChunks) {
            return false;
        }
        return scannedChunks == 1 || chunksWithMaterial > 1;
    }

    static boolean verticalShiftMatches(int configuredShift, Integer appliedShift, int shiftedMinY,
                                        int shiftedMaxY, int worldMinY, int worldMaxYExclusive) {
        try {
            if (appliedShift == null) {
                return configuredShift == 0 && StructureVerticalBounds.clampOffset(
                        shiftedMinY, shiftedMaxY, 0, worldMinY, worldMaxYExclusive) == 0;
            }
            int originalMinY = Math.subtractExact(shiftedMinY, appliedShift);
            int originalMaxY = Math.subtractExact(shiftedMaxY, appliedShift);
            int expectedShift = StructureVerticalBounds.clampOffset(
                    originalMinY, originalMaxY, configuredShift, worldMinY, worldMaxYExclusive);
            return appliedShift == expectedShift;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean mansionVegetationPass(int remainingVegetationBlocks) {
        return remainingVegetationBlocks == 0;
    }

    static boolean mansionVegetationAbovePiece(boolean vegetation, int blockY, int highestPieceY) {
        return vegetation && blockY > highestPieceY;
    }

    static boolean villageFoundationPass(int unsupportedColumns) {
        return unsupportedColumns == 0;
    }

    static boolean villagePoiPass(int inBounds, int outOfBounds) {
        return inBounds > 0 && outOfBounds == 0;
    }

    static boolean isCharacteristicMaterial(String structureLabel, Identifier structureKey, Identifier blockKey) {
        if (structureKey == null || blockKey == null || !blockKey.getNamespace().equals("minecraft")) {
            return false;
        }
        String block = blockKey.getPath();
        return switch (structureLabel) {
            case "stronghold" -> isStrongholdMaterial(block);
            case "trial_chambers" -> isTrialChamberMaterial(block);
            case "mansion" -> isWoodConstructionMaterial(block, "dark_oak")
                    || isWoodConstructionMaterial(block, "birch")
                    || isCobblestoneConstructionMaterial(block);
            case "village" -> isVillageMaterial(structureKey.getPath(), block);
            case "monument" -> isMonumentMaterial(block);
            default -> false;
        };
    }

    private static boolean isStrongholdMaterial(String block) {
        return block.equals("stone_bricks")
                || block.equals("cracked_stone_bricks")
                || block.equals("mossy_stone_bricks")
                || block.equals("infested_stone_bricks")
                || block.equals("infested_cracked_stone_bricks")
                || block.equals("infested_mossy_stone_bricks")
                || block.equals("stone_brick_stairs")
                || block.equals("stone_brick_slab")
                || block.equals("stone_brick_wall");
    }

    private static boolean isTrialChamberMaterial(String block) {
        return block.contains("tuff_brick")
                || block.equals("polished_tuff")
                || block.equals("chiseled_tuff")
                || block.endsWith("copper_grate")
                || block.equals("trial_spawner")
                || block.equals("vault");
    }

    private static boolean isVillageMaterial(String structure, String block) {
        if (isCobblestoneConstructionMaterial(block)) {
            return true;
        }
        return switch (structure) {
            case "village_plains" -> isWoodConstructionMaterial(block, "oak");
            case "village_desert" -> block.equals("cut_sandstone")
                    || block.equals("smooth_sandstone")
                    || block.equals("cut_sandstone_slab")
                    || block.equals("smooth_sandstone_slab")
                    || block.equals("smooth_sandstone_stairs")
                    || block.equals("sandstone_stairs")
                    || block.equals("sandstone_slab")
                    || block.equals("sandstone_wall");
            case "village_savanna" -> isWoodConstructionMaterial(block, "acacia");
            case "village_snowy", "village_taiga" -> isWoodConstructionMaterial(block, "spruce");
            default -> false;
        };
    }

    private static boolean isWoodConstructionMaterial(String block, String wood) {
        if (block.startsWith(wood)) {
            int suffixOffset = wood.length();
            if (matchesSuffix(block, suffixOffset, "_planks")
                    || matchesSuffix(block, suffixOffset, "_stairs")
                    || matchesSuffix(block, suffixOffset, "_slab")
                    || matchesSuffix(block, suffixOffset, "_fence")
                    || matchesSuffix(block, suffixOffset, "_fence_gate")
                    || matchesSuffix(block, suffixOffset, "_door")
                    || matchesSuffix(block, suffixOffset, "_trapdoor")) {
                return true;
            }
        }
        int strippedOffset = "stripped_".length();
        if (!block.startsWith("stripped_")
                || !block.regionMatches(strippedOffset, wood, 0, wood.length())) {
            return false;
        }
        int suffixOffset = strippedOffset + wood.length();
        return matchesSuffix(block, suffixOffset, "_log")
                || matchesSuffix(block, suffixOffset, "_wood");
    }

    private static boolean isCobblestoneConstructionMaterial(String block) {
        return block.equals("cobblestone")
                || block.equals("cobblestone_stairs")
                || block.equals("cobblestone_slab")
                || block.equals("cobblestone_wall")
                || block.equals("mossy_cobblestone")
                || block.equals("mossy_cobblestone_stairs")
                || block.equals("mossy_cobblestone_slab")
                || block.equals("mossy_cobblestone_wall");
    }

    private static boolean matchesSuffix(String value, int offset, String suffix) {
        return value.length() == offset + suffix.length()
                && value.regionMatches(offset, suffix, 0, suffix.length());
    }

    private static boolean isMonumentMaterial(String block) {
        return block.equals("prismarine")
                || block.equals("prismarine_bricks")
                || block.equals("dark_prismarine")
                || block.equals("sea_lantern");
    }

    private static ServerLevel targetLevel(MinecraftServer server) {
        String target = System.getProperty("iris.worldcheck.dimension");
        if (target != null && !target.isBlank()) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().identifier().toString().equals(target.trim())) {
                    return level;
                }
            }
            LOGGER.error("[worldcheck] requested dimension '{}' is not loaded", target);
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                return level;
            }
        }
        return null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface ProcessExit {
        void exit(int status);
    }

    record DimensionContract(int minY, int height, int logicalHeight, double coordinateScale,
                             float ambientLight, boolean hasSkyLight, boolean hasCeiling,
                             boolean hasEnderDragonFight, int monsterSpawnBlockLightLimit) {
    }

    private record StructureCheck(String label, List<String> registryKeys, int locateRadius) {
    }

    private record WorldCheckPreparation(boolean nonStructurePass, boolean terrainOk,
                                         boolean dimensionTypeOk, boolean entityMixinsOk,
                                         NativeStructureGate structureGate) {
    }

    private record NativeStructureGate(boolean passBeforePoi, int nonVillagePassed,
                                       boolean villagePassBeforePoi, PendingVillagePoi pendingPoi) {
    }

    private record StructureCheckResult(boolean pass, PendingVillagePoi pendingPoi) {
    }

    private record PendingVillagePoi(ServerLevel level, StructureStart start) {
    }

    private record FootprintAudit(int inspectedChunks, int availableChunks, int evidenceChunks,
                                  int coveredPieces, int totalPieces, int blockEntityStates,
                                  int blockEntitiesPresent, int materialScannedChunks,
                                  int characteristicBlocks, int characteristicChunks,
                                  int vegetationBlocks, int vegetationColumns,
                                  int foundationBaseColumns, int foundationBlocks, int foundationColumns,
                                  int foundationGapColumns) {
    }

    private record BlockEntityAudit(int states, int present) {
    }

    private record StructureMaterialAudit(boolean scanned, int characteristicBlocks,
                                          int vegetationBlocks, int vegetationColumns,
                                          int foundationBaseColumns, int foundationBlocks,
                                          int foundationColumns,
                                          int foundationGapColumns) {
    }

    private record PoiAudit(int inBounds, int outOfBounds) {
    }
}

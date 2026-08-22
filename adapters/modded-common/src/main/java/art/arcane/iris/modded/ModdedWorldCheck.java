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

import art.arcane.iris.modded.WorldCheckStructureAudit.NativeStructureGate;
import art.arcane.iris.modded.WorldCheckStructureAudit.PendingVillagePoi;
import art.arcane.iris.modded.WorldCheckStructureAudit.PoiAudit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ModdedWorldCheck {
    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAILURE = 1;
    private static final long SERVER_WAIT_TIMEOUT_MILLIS = 600000L;
    private static final long SERVER_WAIT_INTERVAL_MILLIS = 250L;
    private static final long SERVER_TASK_TIMEOUT_MILLIS = 900000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    // halt, not exit: awaitStopAndExit already waited for MinecraftServer.halt(true), and exit() would run the
    // shutdown hooks and block behind the server thread it just stopped, so a finished check could hang forever.
    private static final ProcessExit PROCESS_EXIT = Runtime.getRuntime()::halt;
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
        // Daemon: every wait below is bounded and the coordinator exits the process itself, so this thread
        // must never be the reason a crashed dev server keeps the JVM alive.
        thread.setDaemon(true);
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
            WorldCheckPreparation preparation = serverRef.submit(() -> run(serverRef))
                    .get(SERVER_TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            exitCode = serverRef.submit(() -> runAndRequestStop(
                    () -> completeWorldCheck(preparation),
                    () -> {
                        stopRequested.set(true);
                        serverRef.halt(false);
                    }
            )).get(SERVER_TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("[worldcheck] coordinator interrupted", e);
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            LOGGER.error("[worldcheck] server task did not finish within {}ms", SERVER_TASK_TIMEOUT_MILLIS);
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
        boolean dimensionTypeOk = generator != null
                && WorldCheckDimensionContract.checkDimensionType(level, generator);

        BlockPos spawn = level.getRespawnData().pos();
        LOGGER.info("[worldcheck] spawn: {} {} {} (minY={} height={})", spawn.getX(), spawn.getY(), spawn.getZ(), level.getMinY(), level.getHeight());

        MessageDigest digest = WorldCheckPredicates.sha256();
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

        boolean entityMixinsOk = WorldCheckDimensionContract.checkEntityMixins(level);
        NativeStructureGate structureGate = generator == null
                ? new NativeStructureGate(false, 0, false, null)
                : WorldCheckStructureAudit.checkNativeStructures(level, generator, spawn);
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
            PoiAudit poi = WorldCheckStructureAudit.auditStructurePois(pendingPoi.level(), pendingPoi.start());
            poiOk = WorldCheckPredicates.villagePoiPass(poi.inBounds(), poi.outOfBounds());
            WorldCheckPredicates.qaEvent("village_poi_metric", "village", poiOk,
                    "inBounds=" + poi.inBounds() + ",outOfBounds=" + poi.outOfBounds());
            if (!poiOk) {
                LOGGER.error("[worldcheck] village POI audit failed: inBounds={} outOfBounds={}",
                        poi.inBounds(), poi.outOfBounds());
            }
        } else {
            WorldCheckPredicates.qaEvent("village_poi_metric", "village", false, "skipped=structure");
        }
        int passed = structureGate.nonVillagePassed()
                + (structureGate.villagePassBeforePoi() && poiOk ? 1 : 0);
        boolean structurePass = structureGate.passBeforePoi() && poiOk;
        LOGGER.info("[worldcheck] native structure gate: {}/{} passed", passed,
                WorldCheckStructureAudit.STRUCTURE_CHECKS.size());
        WorldCheckPredicates.qaEvent("structure_aggregate", "all", structurePass,
                "passed=" + passed + ",total=" + WorldCheckStructureAudit.STRUCTURE_CHECKS.size());
        boolean pass = preparation.nonStructurePass() && structurePass;
        LOGGER.info("[worldcheck] {}", pass ? "PASS" : "FAIL");
        WorldCheckPredicates.qaEvent("worldcheck_final", "all", pass,
                "structures=" + WorldCheckStructureAudit.STRUCTURE_CHECKS.size()
                        + ",terrain=" + preparation.terrainOk()
                        + ",dimensionType=" + preparation.dimensionTypeOk()
                        + ",entityMixins=" + preparation.entityMixinsOk());
        return pass;
    }

    private static ServerLevel targetLevel(MinecraftServer server) {
        String target = System.getProperty("iris.worldcheck.dimension");
        if (target != null && !target.isBlank()) {
            Identifier identifier = Identifier.tryParse(target.trim());
            ServerLevel requested = identifier == null
                    ? null
                    : ModdedServerLevels.level(server, ResourceKey.create(Registries.DIMENSION, identifier));
            if (requested != null) {
                return requested;
            }
            LOGGER.error("[worldcheck] requested dimension '{}' is not loaded", target);
            return null;
        }

        for (ServerLevel level : ModdedServerLevels.levels(server)) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                return level;
            }
        }
        return null;
    }

    @FunctionalInterface
    interface ProcessExit {
        void exit(int status);
    }

    private record WorldCheckPreparation(boolean nonStructurePass, boolean terrainOk,
                                         boolean dimensionTypeOk, boolean entityMixinsOk,
                                         NativeStructureGate structureGate) {
    }
}

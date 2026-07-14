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

import art.arcane.iris.core.gui.GuiHost;
import art.arcane.iris.engine.decorator.DecoratorPlatformHooks;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineEffectsProvider;
import art.arcane.iris.engine.framework.EnginePlatformHooks;
import art.arcane.iris.engine.framework.EngineWorldManagerProvider;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.object.BlockDataMergeSupport;
import art.arcane.iris.engine.object.IrisObjectRotation;
import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.modded.api.ModdedCustomContentRegistry;
import art.arcane.iris.modded.command.ModdedGuiHost;
import art.arcane.iris.modded.command.ModdedObjectUndo;
import art.arcane.iris.modded.command.ModdedPregenBossBar;
import art.arcane.iris.modded.command.ModdedPregenJob;
import art.arcane.iris.modded.command.ModdedStudioCommands;
import art.arcane.iris.modded.command.ModdedWandService;
import art.arcane.iris.modded.service.ModdedChunkUpdateService;
import art.arcane.iris.modded.service.ModdedEngineMaintenanceService;
import art.arcane.iris.modded.service.ModdedEntitySpawnService;
import art.arcane.iris.modded.service.ModdedLogFilterService;
import art.arcane.iris.modded.service.ModdedPreservationService;
import art.arcane.iris.modded.service.ModdedSettingsHotloadService;
import art.arcane.iris.modded.service.ModdedStudioHotloadService;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModdedEngineBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final String[] CORE_SELF_TEST_CLASSES = {
        "art.arcane.iris.engine.IrisEngine",
        "art.arcane.iris.util.common.data.B",
        "art.arcane.iris.core.loader.IrisData"
    };
    private static final Object LOCK = new Object();
    private static final ModdedServiceManager SERVICE_MANAGER = new ModdedServiceManager();
    private static volatile ModdedLoader loader;
    private static volatile ModdedPlatform platform;
    private static volatile MinecraftServer currentServer;
    private static volatile MinecraftServer spawnCaptureServer;
    private static volatile boolean initialSpawnWasDefault;

    private ModdedEngineBootstrap() {
    }

    public static ModdedServiceManager services() {
        return SERVICE_MANAGER;
    }

    public static ModdedScheduler schedulerOrNull() {
        ModdedPlatform bound = platform;
        return bound == null ? null : bound.moddedScheduler();
    }

    public static void tick(MinecraftServer server) {
        ModdedScheduler.tick(server);
        ModdedStartup.runOnce(server);
        ModdedPrimaryWorldRouter.tick(server);
        SERVICE_MANAGER.tick(server);
        ModdedPregenBossBar.tick(server);
        ModdedProtocolHandler.tickDimensionSync(server);
    }

    public static void start(MinecraftServer server) {
        captureInitialSpawn(server);
        currentServer = server;
        bind();
        bindWorldGenerators(server);
        ModdedStartup.reset();
        ModdedScheduler scheduler = schedulerOrNull();
        if (scheduler != null) {
            scheduler.reset();
        }
        IrisModdedChunkGenerator.startGenPool();
        SERVICE_MANAGER.enableAll();
        ModdedProtocolHandler.start(server);
        ModdedSentry.start(loader());
    }

    private static void bindWorldGenerators(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator) {
                generator.bindLevel(level);
            }
        }
    }

    public static void serverAboutToStart(MinecraftServer server) {
        captureInitialSpawn(server);
    }

    public static void serverStarted(MinecraftServer server) {
        bindWorldGenerators(server);
        reconcileSpawn(server);
        ModdedWorldCheck.serverStarted(server);
    }

    public static void levelLoaded(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator) {
            generator.bindLevel(level);
        }
    }

    public static void stop() {
        MinecraftServer stoppingServer = currentServer;
        ModdedWorldCheck.serverStopped(stoppingServer);
        ModdedProtocolHandler.stop();
        ModdedPregenJob.shutdown();
        ModdedObjectUndo.clearAll();
        ModdedWandService.clearAll();
        ModdedBlockBreakHandler.clear();
        ModdedStudioCommands.clear();
        ModdedWorldEngines.shutdown();
        ModdedPrimaryWorldRouter.clear();
        SERVICE_MANAGER.disableAll();
        ModdedDimensionManager.clear();
        ModdedScheduler scheduler = schedulerOrNull();
        if (scheduler != null) {
            scheduler.shutdown();
        }
        IrisModdedChunkGenerator.shutdownGenPool();
        ModdedSentry.flush();
        ModdedStartup.reset();
        currentServer = null;
        spawnCaptureServer = null;
        initialSpawnWasDefault = false;
    }

    private static void captureInitialSpawn(MinecraftServer server) {
        if (spawnCaptureServer == server) {
            return;
        }
        LevelData.RespawnData respawnData = server.getRespawnData();
        initialSpawnWasDefault = respawnData == null || LevelData.RespawnData.DEFAULT.equals(respawnData);
        spawnCaptureServer = server;
    }

    private static void reconcileSpawn(MinecraftServer server) {
        LevelData.RespawnData current = server.getRespawnData();
        if (current == null) {
            return;
        }
        ServerLevel level = server.getLevel(current.dimension());
        if (level == null || !(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator)) {
            return;
        }
        String dimensionId = level.dimension().identifier().toString();
        boolean studio = dimensionId.startsWith("irisworldgen:studio_");
        if (!shouldReconcileSpawn(initialSpawnWasDefault, studio, current.pos().getX(), current.pos().getZ())) {
            return;
        }
        LevelChunk originChunk = level.getChunk(0, 0);
        int surfaceY = originChunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
        BlockPos position = reconciledSpawnPosition(surfaceY, level.getMinY(), level.getHeight());
        server.setRespawnData(LevelData.RespawnData.of(
                level.dimension(), position, current.yaw(), current.pitch()));
        LOGGER.info("Iris spawn reconciled for {} at {},{},{}", dimensionId,
                position.getX(), position.getY(), position.getZ());
    }

    static boolean shouldReconcileSpawn(boolean initialDefault, boolean studio, int currentX, int currentZ) {
        return initialDefault || studio || currentX == 0 && currentZ == 0;
    }

    static BlockPos reconciledSpawnPosition(int surfaceY, int minY, int height) {
        int y = Math.max(minY + 1, Math.min(minY + height - 2, surfaceY));
        return new BlockPos(0, y, 0);
    }

    public static void bootCommon(ModdedLoader moddedLoader, String loaderDescription, Runnable chunkGeneratorRegistration) {
        loader = moddedLoader;
        ModdedIrisLog.info("Iris " + moddedLoader.modVersion() + " bootstrapping on Minecraft " + moddedLoader.minecraftVersion() + " (" + loaderDescription + ")");
        selfTest(moddedLoader.getClass().getClassLoader());
        bind();
        MainWorldService.reconcileEarly();
        chunkGeneratorRegistration.run();
        ModdedIrisLog.info("Iris chunk generator registered as irisworldgen:iris");
        armParityProbe();
        armWorldCheck();
    }

    private static void armParityProbe() {
        String parity = System.getProperty("iris.parity");
        if (parity == null) {
            return;
        }
        ModdedIrisLog.info("Iris parity probe armed: " + parity);
        ModdedParityProbe.schedule(parity);
    }

    private static void armWorldCheck() {
        if (System.getProperty("iris.worldcheck") == null) {
            return;
        }
        ModdedIrisLog.info("Iris world check armed");
        ModdedWorldCheck.schedule();
    }

    public static ModdedLoader loader() {
        ModdedLoader bound = loader;
        if (bound == null) {
            throw new IllegalStateException("Iris modded loader is not initialized; the loader bootstrap must call ModdedEngineBootstrap.bootCommon first");
        }
        return bound;
    }

    public static MinecraftServer currentServer() {
        MinecraftServer tracked = currentServer;
        return tracked != null ? tracked : loader().currentServer();
    }

    private static void selfTest(ClassLoader classLoader) {
        int loadedClasses = 0;
        for (String className : CORE_SELF_TEST_CLASSES) {
            try {
                Class.forName(className, true, classLoader);
                loadedClasses++;
            } catch (Throwable error) {
                LOGGER.error("Iris core self-test failed to initialize {}", className, error);
            }
        }

        if (loadedClasses != CORE_SELF_TEST_CLASSES.length) {
            throw new IllegalStateException("Iris core self-test failed: only " + loadedClasses + " of " + CORE_SELF_TEST_CLASSES.length + " engine classes initialized");
        }

        ModdedIrisLog.info("Iris core loaded (" + loadedClasses + " classes ok)");
    }

    public static ModdedPlatform bind() {
        ModdedPlatform bound = platform;
        if (bound != null) {
            return bound;
        }
        synchronized (LOCK) {
            if (platform != null) {
                return platform;
            }
            ModdedLoader boundLoader = loader();
            GuiHost.suppressDesktop(boundLoader.clientEnvironment());
            ModdedPlatform created = new ModdedPlatform(boundLoader);
            IrisPlatforms.bind(created);
            ModdedDimensionManager.bindAccess(new ModdedServerLevels());
            IrisObjectRotation.bindFallbackRotator(new ModdedStateRotator());
            BlockDataMergeSupport.bindFallbackMerger(new ModdedStateMerger());
            TileData.bindFallbackReader(new ModdedTileReader(boundLoader::currentServer));
            TileData.bindFallbackFactory(ModdedTileData::fromProperties);
            ModdedGuiHost.install();
            ModdedDecoratorHooks decoratorHooks = new ModdedDecoratorHooks();
            DecoratorPlatformHooks.bind(decoratorHooks, decoratorHooks);
            ModdedPreservationService preservation = SERVICE_MANAGER.register(ModdedPreservationService.class, new ModdedPreservationService());
            SERVICE_MANAGER.register(ModdedLogFilterService.class, new ModdedLogFilterService());
            SERVICE_MANAGER.register(ModdedEngineMaintenanceService.class, new ModdedEngineMaintenanceService());
            SERVICE_MANAGER.register(ModdedSettingsHotloadService.class, new ModdedSettingsHotloadService());
            ModdedStudioHotloadService studioHotloadService = SERVICE_MANAGER.register(ModdedStudioHotloadService.class, new ModdedStudioHotloadService());
            SERVICE_MANAGER.register(ModdedChunkUpdateService.class, new ModdedChunkUpdateService());
            SERVICE_MANAGER.register(ModdedEntitySpawnService.class, new ModdedEntitySpawnService());
            IrisServices.register(PreservationRegistry.class, preservation);
            IrisServices.register(EngineEffectsProvider.class, (EngineEffectsProvider) ModdedEngineEffects::new);
            IrisServices.register(EnginePlatformHooks.class, studioHotloadService);
            IrisServices.register(EngineWorldManagerProvider.class, (EngineWorldManagerProvider) (Engine engine) -> new ModdedWorldManager(engine));
            ModdedCustomContentRegistry.discover();
            platform = created;
            SERVICE_MANAGER.enableAll();
            if (boundLoader.clientEnvironment()) {
                ModdedStartup.prefetchDefaultPack();
            }
            ModdedIrisSplash.print(boundLoader);
            return created;
        }
    }
}

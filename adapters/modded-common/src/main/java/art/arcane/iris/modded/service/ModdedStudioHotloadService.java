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

package art.arcane.iris.modded.service;

import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.tools.WorldMaintenance;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EnginePlatformHooks;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionRuntimeContract;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedDimensionManager;
import art.arcane.iris.modded.ModdedForcedDatapack;
import art.arcane.iris.modded.ModdedWorkspaceGenerator;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.io.ReactiveFolder;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModdedStudioHotloadService implements ModdedTickableService, EnginePlatformHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final String STUDIO_DIMENSION_PREFIX = "irisworldgen:studio_";
    private static final long POLL_MILLIS = 250L;
    private static final long CHECK_LATCH_MILLIS = 1_000L;
    private static final long RECENT_GENERATION_HOLDOFF_MILLIS = 2_000L;

    private final ConcurrentHashMap<String, Watch> watches = new ConcurrentHashMap<>();
    private volatile ExecutorService executor;
    private long lastPollAt;

    @Override
    public void onEnable() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadExecutor((Runnable task) -> {
            Thread thread = new Thread(task, "Iris Studio Hotload");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        lastPollAt = 0L;
    }

    @Override
    public void onDisable() {
        ExecutorService active = executor;
        executor = null;
        if (active != null) {
            active.shutdownNow();
        }
        watches.clear();
    }

    @Override
    public void refreshWorkspace(Engine engine) {
        writeWorkspace(engine, "workspace refresh");
    }

    @Override
    public void refreshDatapackWorkspace(Engine engine) {
        IrisData data = engine.getData();
        KList<IrisDimension> dimensions = data.getDimensionLoader().loadAll(data.getDimensionLoader().getPossibleKeys());
        if (hasDatapackImports(dimensions)) {
            writeWorkspace(engine, "datapack workspace refresh");
        }
    }

    @Override
    public void reloadDatapacks(Engine engine) {
        ModdedForcedDatapack.regenerate();
    }

    @Override
    public void validateDimensionHotload(Engine engine, IrisDimension replacement) {
        IrisDimensionRuntimeContract.requireHotloadCompatible(
                "Modded Studio level '" + engine.getWorld().identity() + "'",
                engine.getDimension(),
                replacement,
                "irisworldgen");
    }

    @Override
    public void onServerTick(MinecraftServer server) {
        ExecutorService active = executor;
        if (active == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPollAt < POLL_MILLIS) {
            return;
        }
        lastPollAt = now;
        List<ModdedDimensionManager.Handle> handles = ModdedDimensionManager.handles();
        Set<String> seen = null;
        for (ModdedDimensionManager.Handle handle : handles) {
            String dimensionId = handle.dimensionId();
            if (!dimensionId.startsWith(STUDIO_DIMENSION_PREFIX)) {
                continue;
            }
            IrisModdedChunkGenerator generator = handle.generator();
            if (generator == null) {
                continue;
            }
            Engine engine = generator.engineIfBound();
            if (engine == null || engine.isClosed()) {
                continue;
            }
            if (seen == null) {
                seen = new HashSet<>();
            }
            seen.add(dimensionId);
            Watch watch = watches.computeIfAbsent(dimensionId, (String key) -> new Watch());
            if (throttled(generator, engine, now)) {
                continue;
            }
            if (!watch.latch.flip()) {
                continue;
            }
            if (!watch.busy.compareAndSet(false, true)) {
                continue;
            }
            try {
                active.execute(() -> {
                    try {
                        poll(dimensionId, watch, generator, engine);
                    } finally {
                        watch.busy.set(false);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                watch.busy.set(false);
            }
        }
        if (!watches.isEmpty()) {
            Set<String> keep = seen == null ? Set.of() : seen;
            watches.keySet().removeIf((String key) -> !keep.contains(key));
        }
    }

    @Override
    public boolean isPregeneratorActive(Engine engine) {
        IrisWorld world = engine.getWorld();
        if (world == null) {
            return false;
        }
        PregeneratorJob pregeneratorJob = PregeneratorJob.getInstance();
        return pregeneratorJob != null && pregeneratorJob.targetsWorldIdentity(world.identity());
    }

    @Override
    public void shutdownPregenerator(Engine engine) {
        PregeneratorJob.shutdownInstance();
    }

    @Override
    public boolean shouldSkipMantleCleanup(Engine engine) {
        IrisWorld world = engine.getWorld();
        return world != null
                && WorldMaintenance.isWorldMaintenanceActive(world.identity())
                && !isPregeneratorActive(engine);
    }

    private boolean throttled(IrisModdedChunkGenerator generator, Engine engine, long now) {
        if (now - generator.lastChunkGenAt() < RECENT_GENERATION_HOLDOFF_MILLIS) {
            return true;
        }
        return isPregeneratorActive(engine);
    }

    private void poll(String dimensionId, Watch watch, IrisModdedChunkGenerator generator, Engine engine) {
        try {
            if (watch.engine != engine) {
                watch.engine = engine;
                watch.folder = new ReactiveFolder(
                        engine.getData().getDataFolder(),
                        (KList<File> created, KList<File> changed, KList<File> deleted) -> hotload(dimensionId, generator, engine),
                        new KList<>(".iob", ".json"),
                        new KList<>(".iris"),
                        new KList<>());
                return;
            }
            ReactiveFolder folder = watch.folder;
            if (folder != null) {
                folder.check();
            }
        } catch (Throwable e) {
            LOGGER.error("Iris studio hotload check failed for {}", dimensionId, e);
        }
    }

    private void hotload(String dimensionId, IrisModdedChunkGenerator generator, Engine engine) {
        if (engine.isClosed()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            engine.hotloadSilently();
            generator.onHotload();
            LOGGER.info("Iris studio hotload {} pack={} {}ms", dimensionId, engine.getDimension().getLoadKey(), System.currentTimeMillis() - start);
        } catch (Throwable e) {
            LOGGER.error("Iris studio hotload failed for {}", dimensionId, e);
        }
    }

    static boolean hasDatapackImports(Iterable<IrisDimension> dimensions) {
        if (dimensions == null) {
            return false;
        }
        for (IrisDimension dimension : dimensions) {
            if (dimension != null
                    && dimension.getDatapackImports() != null
                    && !dimension.getDatapackImports().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void writeWorkspace(Engine engine, String operation) {
        try {
            IrisData data = engine.getData();
            ModdedWorkspaceGenerator.writeWorkspace(data, data.getDataFolder());
        } catch (Throwable e) {
            LOGGER.error("Iris {} failed for {}", operation, engine.getDimension().getLoadKey(), e);
        }
    }

    private static final class Watch {
        private final ChronoLatch latch = new ChronoLatch(CHECK_LATCH_MILLIS, false);
        private final AtomicBoolean busy = new AtomicBoolean(false);
        private volatile Engine engine;
        private volatile ReactiveFolder folder;
    }
}

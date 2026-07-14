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

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.pregenerator.MantleHeapPressure;
import art.arcane.iris.core.runtime.GoldenHashEngine;
import art.arcane.iris.core.tools.WorldMaintenance;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.modded.ModdedWorldEngines;
import art.arcane.iris.spi.IrisLogging;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModdedEngineMaintenanceService implements ModdedTickableService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final long TRIM_PERIOD_MILLIS = 2_000L;
    private static final long SAVE_PERIOD_MILLIS = 60_000L;

    private final AtomicInteger tectonicLimit = new AtomicInteger(30);
    private final Set<Engine> inFlight = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private volatile ExecutorService service;
    private long lastMaintenanceAt;
    private long lastSaveAt;

    @Override
    public void onEnable() {
        if (service != null) {
            return;
        }
        IrisSettings.IrisSettingsPerformance settings = IrisSettings.get().getPerformance();
        IrisSettings.IrisSettingsEngineSVC engineSettings = settings.getEngineSVC();
        ThreadFactory factory = (engineSettings.isUseVirtualThreads()
                ? Thread.ofVirtual()
                : Thread.ofPlatform().priority(engineSettings.getPriority()))
                .name("Iris EngineSVC-", 0)
                .factory();
        service = Executors.newThreadPerTaskExecutor(factory);
        tectonicLimit.set(settings.getTectonicPlateSize());
        lastMaintenanceAt = 0L;
        lastSaveAt = System.currentTimeMillis();
    }

    @Override
    public void onDisable() {
        ExecutorService active = service;
        service = null;
        if (active != null) {
            active.shutdown();
        }
        inFlight.clear();
    }

    @Override
    public void onServerTick(MinecraftServer server) {
        ExecutorService active = service;
        if (active == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMaintenanceAt < TRIM_PERIOD_MILLIS) {
            return;
        }
        lastMaintenanceAt = now;
        boolean flush = now - lastSaveAt >= SAVE_PERIOD_MILLIS;
        if (flush) {
            lastSaveAt = now;
        }
        Collection<Engine> engines = ModdedWorldEngines.activeEngines();
        int share = tectonicLimit.get() / Math.max(engines.size(), 1);
        for (Engine engine : engines) {
            if (!inFlight.add(engine)) {
                continue;
            }
            try {
                active.execute(() -> {
                    try {
                        maintain(engine, share, flush);
                    } finally {
                        inFlight.remove(engine);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                inFlight.remove(engine);
            }
        }
    }

    private void maintain(Engine engine, int share, boolean flush) {
        if (engine == null || engine.isClosed() || engine.getMantle().getMantle().isClosed()) {
            return;
        }
        if (pregenTargets(engine)) {
            return;
        }
        if (flush) {
            try {
                engine.save();
            } catch (Throwable e) {
                if (isMantleClosed(e)) {
                    return;
                }
                IrisLogging.reportError(e);
                LOGGER.error("Iris engine save failed for {}", engine.getWorld().name(), e);
            }
        }
        if (!shouldReduce(engine) || shouldSkipForMaintenance(engine) || GoldenHashEngine.isActive()) {
            return;
        }
        try {
            if (pregenTargets(engine) && MantleHeapPressure.overHighWater()) {
                engine.getMantle().trim(0L, 0);
            } else {
                engine.getMantle().trim(TimeUnit.SECONDS.toMillis(IrisSettings.get().getPerformance().getMantleKeepAlive()), activeTectonicLimit(engine, share));
            }
            long unloadStart = System.currentTimeMillis();
            boolean heapPressure = pregenTargets(engine) && MantleHeapPressure.overHighWater();
            int unloadLimit = (heapPressure || IrisSettings.get().getPerformance().getEngineSVC().forceMulticoreWrite) ? 0 : activeTectonicLimit(engine, share);
            int count = engine.getMantle().unloadTectonicPlate(unloadLimit);
            if (heapPressure && MantleHeapPressure.overPanicWater()) {
                MantleHeapPressure.requestPanicReclaim();
            }
            if (count > 0) {
                LOGGER.debug("Iris unloaded {} tectonic plates in {}ms for {}", count, System.currentTimeMillis() - unloadStart, engine.getWorld().name());
            }
        } catch (Throwable e) {
            if (isMantleClosed(e)) {
                return;
            }
            IrisLogging.reportError(e);
            LOGGER.error("Iris engine maintenance failed for {}", engine.getWorld().name(), e);
        }
    }

    private boolean shouldReduce(Engine engine) {
        if (!engine.isStudio() || IrisSettings.get().getPerformance().isTrimMantleInStudio()) {
            return true;
        }
        return pregenTargets(engine);
    }

    private boolean shouldSkipForMaintenance(Engine engine) {
        if (engine.getWorld() == null || !WorldMaintenance.isWorldMaintenanceActive(engine.getWorld().identity())) {
            return false;
        }
        return !pregenTargets(engine);
    }

    private boolean pregenTargets(Engine engine) {
        if (engine.getWorld() == null) {
            return false;
        }
        PregeneratorJob job = PregeneratorJob.getInstance();
        return job != null && job.targetsWorldIdentity(engine.getWorld().identity());
    }

    private int activeTectonicLimit(Engine engine, int share) {
        if (!pregenTargets(engine)) {
            return share;
        }
        return Math.max(share, IrisSettings.get().getPregen().getEffectiveResidentTectonicPlates(engine.getHeight()));
    }

    private static boolean isMantleClosed(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("mantle is closed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

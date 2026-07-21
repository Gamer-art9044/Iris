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

package art.arcane.iris.modded.command;

import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.pregenerator.PregenPerformanceProfile;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.pregenerator.cache.PregenCache;
import art.arcane.iris.core.pregenerator.methods.CachedPregenMethod;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.math.Position2;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ModdedPregenJob {
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 10_000L;
    private static final AtomicReference<ActivePregen> ACTIVE = new AtomicReference<>();
    private static volatile String dimension = "?";

    private ModdedPregenJob() {
    }

    public static boolean start(MinecraftServer server, ServerLevel level, Engine engine, int radiusBlocks, int centerBlockX, int centerBlockZ, boolean gui, boolean sync, boolean cached) {
        if (PregeneratorJob.getInstance() != null) {
            return false;
        }

        PregenPerformanceProfile.apply(engine);
        PregenTask task = PregenTask.builder()
                .gui(gui)
                .center(new Position2(centerBlockX, centerBlockZ))
                .radiusX(radiusBlocks)
                .radiusZ(radiusBlocks)
                .build();
        ModdedPregenMethod moddedMethod = new ModdedPregenMethod(level, engine, sync);
        PregeneratorMethod method = moddedMethod;
        if (cached) {
            method = new CachedPregenMethod(method, PregenCache.create(cacheDirectory(level)).sync(), task);
        }
        ActivePregen active = new ActivePregen(engine, moddedMethod);
        ACTIVE.set(active);
        dimension = level.dimension().identifier().toString();
        try {
            PregeneratorJob job = new PregeneratorJob(task, method, engine);
            job.whenDone(() -> {
                if (!moddedMethod.hasPendingFinalSave()) {
                    ACTIVE.compareAndSet(active, null);
                }
            });
            return true;
        } catch (Throwable failure) {
            ACTIVE.compareAndSet(active, null);
            throw propagate(failure);
        }
    }

    private static File cacheDirectory(ServerLevel level) {
        File worldFolder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).toFile();
        return new File(worldFolder, "iris" + File.separator + "pregen");
    }

    public static boolean stop() {
        return PregeneratorJob.shutdownInstance();
    }

    public static void shutdown() {
        ActivePregen active = ACTIVE.get();
        shutdownAndSave(active, () -> PregeneratorJob.shutdownAndWait(SHUTDOWN_TIMEOUT_MILLIS));
    }

    public static boolean shutdownForWorld(String worldIdentity) {
        PregeneratorJob job = PregeneratorJob.getInstance();
        if (job == null || !job.targetsWorldIdentity(worldIdentity)) {
            return false;
        }
        ActivePregen active = matchingActive(worldIdentity);
        return shutdownAndSave(active, () -> PregeneratorJob.shutdownInstanceForWorld(worldIdentity));
    }

    public static Boolean pauseResume() {
        if (PregeneratorJob.getInstance() == null) {
            return null;
        }
        PregeneratorJob.pauseResume();
        return PregeneratorJob.isPaused();
    }

    public static Component statusComponent() {
        PregeneratorJob.PregenProgress progress = PregeneratorJob.progressSnapshot();
        if (progress == null) {
            return null;
        }

        MutableComponent status = Component.empty();
        status.append(ModdedCommandFeedback.header("Iris Pregen"));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.text("Dimension ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(dimension, ModdedCommandFeedback.PARAMETER_ALT));
        status.append(ModdedCommandFeedback.text(" · Method ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(progress.method(), ModdedCommandFeedback.PARAMETER));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.progressBar(progress.percent(), 32));
        status.append(ModdedCommandFeedback.text(" " + String.format("%.1f", progress.percent()) + "%", ModdedCommandFeedback.USAGE));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.text("Chunks ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.f(progress.generated()) + "/" + Form.f(progress.totalChunks()), ModdedCommandFeedback.VALUE));
        status.append(ModdedCommandFeedback.text(" · Speed ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.f((int) progress.chunksPerSecond()) + "/s", ModdedCommandFeedback.VALUE));
        if (progress.failed() > 0) {
            status.append(ModdedCommandFeedback.text(" · Failed ", ModdedCommandFeedback.DARK_GREEN));
            status.append(ModdedCommandFeedback.text(Form.f(progress.failed()), ModdedCommandFeedback.REQUIRED));
        }
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.text("ETA ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.duration(progress.eta(), 2), ModdedCommandFeedback.VALUE));
        status.append(ModdedCommandFeedback.text(" · Elapsed ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.duration(progress.elapsed(), 2), ModdedCommandFeedback.VALUE));
        if (progress.paused()) {
            status.append(ModdedCommandFeedback.text(" · PAUSED", ModdedCommandFeedback.REQUIRED, true, false));
        }
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.button("Pause/Resume", "/iris pregen pause", "Toggle pregeneration pause state", true));
        status.append(ModdedCommandFeedback.text("  ", ModdedCommandFeedback.OPTIONAL));
        status.append(ModdedCommandFeedback.button("Stop", "/iris pregen stop", "Finish the current region and stop pregeneration", true));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.footer());
        return status;
    }

    private static ActivePregen matchingActive(String worldIdentity) {
        ActivePregen active = ACTIVE.get();
        return active != null && active.targets(worldIdentity) ? active : null;
    }

    private static boolean shutdownAndSave(ActivePregen active, BooleanSupplier shutdown) {
        boolean deferred = active != null && active.method().deferFinalSaveToServerThread();
        boolean stopped = false;
        boolean result = false;
        Throwable failure = null;
        try {
            result = shutdown.getAsBoolean();
            stopped = result;
        } catch (Throwable shutdownFailure) {
            failure = shutdownFailure;
        }

        if (deferred) {
            try {
                if (stopped) {
                    active.method().completeDeferredFinalSave();
                } else {
                    active.method().cancelDeferredFinalSave();
                }
            } catch (Throwable saveFailure) {
                if (failure == null) {
                    failure = saveFailure;
                } else if (saveFailure != failure) {
                    failure.addSuppressed(saveFailure);
                }
            }
        }

        if (failure == null && (stopped || PregeneratorJob.getInstance() == null)) {
            ACTIVE.compareAndSet(active, null);
        }
        if (failure != null) {
            throw propagate(failure);
        }
        return result;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error fatalError) {
            throw fatalError;
        }
        return new IllegalStateException("Iris modded pregenerator shutdown failed", failure);
    }

    private record ActivePregen(Engine engine, ModdedPregenMethod method) {
        private boolean targets(String worldIdentity) {
            return engine != null
                    && engine.getWorld() != null
                    && Objects.equals(engine.getWorld().identity(), worldIdentity);
        }
    }
}

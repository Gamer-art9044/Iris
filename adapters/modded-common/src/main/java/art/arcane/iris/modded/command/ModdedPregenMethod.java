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

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregenMantleBackpressure;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ModdedPregenMethod implements PregeneratorMethod {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final TicketType PREGEN_TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final int ADAPTIVE_TIMEOUT_STEP = 3;
    private static final long ADAPTIVE_RECOVERY_INTERVAL = 64L;
    private static final long FINAL_SAVE_TIMEOUT_MILLIS = 10_000L;
    private static final long FINAL_SAVE_POLL_MILLIS = 50L;
    private static final boolean PARALLEL_CHUNK_SYSTEM = detectParallelChunkSystem();

    private final ServerLevel level;
    private final Engine engine;
    private final boolean sync;
    private final int maxInFlight;
    private final int minInFlight;
    private final Semaphore semaphore;
    private final Object permitMonitor = new Object();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger inFlightPeak = new AtomicInteger();
    private final AtomicInteger adaptiveLimit;
    private final AtomicInteger timeoutStreak = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicBoolean finalSaveOnServerThread = new AtomicBoolean(false);
    private final AtomicBoolean finalSaveDeferred = new AtomicBoolean(false);
    private final AtomicBoolean finalSaveCompleted = new AtomicBoolean(false);
    private final AtomicReference<FinalSaveRequest> queuedFinalSave = new AtomicReference<>();
    private final int timeoutSeconds;
    private final PregenMantleBackpressure backpressure;

    public ModdedPregenMethod(ServerLevel level, Engine engine) {
        this(level, engine, false);
    }

    public ModdedPregenMethod(ServerLevel level, Engine engine, boolean sync) {
        this.level = level;
        this.engine = engine;
        this.sync = sync;
        IrisSettings.IrisSettingsPregen pregen = IrisSettings.get().getPregen();
        this.maxInFlight = Math.max(8, pregen.getModdedPregenInFlight());
        this.minInFlight = Math.max(4, Math.min(16, maxInFlight / 4));
        this.semaphore = new Semaphore(maxInFlight, true);
        this.adaptiveLimit = new AtomicInteger(sync ? 1 : maxInFlight);
        this.timeoutSeconds = Math.max(120, pregen.getChunkLoadTimeoutSeconds());
        this.backpressure = new PregenMantleBackpressure(
                this::getMantle,
                pregen.getEffectiveResidentTectonicPlates(engine.getHeight()),
                pregen.getMantleBackpressureWaitMs(),
                pregen.getMantleBackpressureTimeoutMs(),
                () -> {
                },
                () -> "dim=" + level.dimension().identifier());
    }

    @Override
    public void init() {
        LOGGER.info("Iris modded pregen init: dim={} mode={} inFlightCap={} timeout={}s workerPool={} parallelChunkSystem={}",
                level.dimension().identifier(),
                sync ? "sync" : "async",
                sync ? 1 : maxInFlight,
                timeoutSeconds,
                describeWorkerPool(),
                PARALLEL_CHUNK_SYSTEM ? "yes" : "no");
        if (!sync && !PARALLEL_CHUNK_SYSTEM) {
            LOGGER.info("Iris pregen note: this loader uses the vanilla main-thread chunk system, which caps pregen throughput. For Bukkit-level speed on Fabric install C2ME (Concurrent Chunk Management Engine); on servers use Paper.");
        }
    }

    @Override
    public void close() {
        if (!sync) {
            try {
                semaphore.tryAcquire(maxInFlight, 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOGGER.info("Iris modded pregen done: dim={} completed={} peakInFlight={} finalLimit={}",
                level.dimension().identifier(), completed.get(), inFlightPeak.get(), adaptiveLimit.get());
        if (deferFinalSaveIfRequested()) {
            return;
        }
        saveLevel(true);
    }

    @Override
    public void save() {
        saveLevel(false);
    }

    boolean deferFinalSaveToServerThread() {
        if (!level.getServer().isSameThread()) {
            return false;
        }
        finalSaveOnServerThread.set(true);
        return true;
    }

    void completeDeferredFinalSave() {
        requireServerThreadForFinalSave();
        cancelQueuedFinalSave();
        if (!finalSaveCompleted.get()) {
            saveLevelOnServerThread();
            finalSaveCompleted.set(true);
        }
        finalSaveDeferred.set(false);
        finalSaveOnServerThread.set(false);
    }

    void cancelDeferredFinalSave() {
        requireServerThreadForFinalSave();
        finalSaveOnServerThread.set(false);
        if (finalSaveDeferred.get() || queuedFinalSave.get() != null) {
            cancelQueuedFinalSave();
            if (!finalSaveCompleted.get()) {
                saveLevelOnServerThread();
                finalSaveCompleted.set(true);
            }
            finalSaveDeferred.set(false);
        }
    }

    boolean hasPendingFinalSave() {
        return finalSaveOnServerThread.get()
                || finalSaveDeferred.get()
                || queuedFinalSave.get() != null;
    }

    private void saveLevel(boolean wait) {
        if (wait) {
            saveFinalLevel();
            return;
        }
        MinecraftServer server = level.getServer();
        if (server.isSameThread()) {
            saveLevelOnServerThread();
            return;
        }
        server.execute(this::saveLevelOnServerThread);
    }

    private void saveFinalLevel() {
        MinecraftServer server = level.getServer();
        if (server.isSameThread()) {
            saveLevelOnServerThread();
            finalSaveCompleted.set(true);
            return;
        }
        if (deferFinalSaveIfRequested()) {
            return;
        }

        FinalSaveRequest request = new FinalSaveRequest();
        if (!queuedFinalSave.compareAndSet(null, request)) {
            throw new IllegalStateException("Iris pregen final save is already queued for "
                    + level.dimension().identifier());
        }
        try {
            server.execute(() -> executeFinalSave(request));
        } catch (RuntimeException | Error failure) {
            queuedFinalSave.compareAndSet(request, null);
            request.fail(failure);
            throw failure;
        }
        awaitFinalSave(request);
    }

    private void executeFinalSave(FinalSaveRequest request) {
        if (!request.claim()) {
            return;
        }
        try {
            saveLevelOnServerThread();
            finalSaveCompleted.set(true);
            request.complete();
        } catch (RuntimeException | Error failure) {
            request.fail(failure);
            throw failure;
        } finally {
            queuedFinalSave.compareAndSet(request, null);
        }
    }

    private void awaitFinalSave(FinalSaveRequest request) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINAL_SAVE_TIMEOUT_MILLIS);
        while (true) {
            if (deferFinalSaveIfRequested()) {
                return;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                LOGGER.warn("Iris pregen level save did not complete in time for {}", level.dimension().identifier());
                return;
            }
            long waitMillis = Math.max(1L, Math.min(FINAL_SAVE_POLL_MILLIS,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                request.completion().get(waitMillis, TimeUnit.MILLISECONDS);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (TimeoutException e) {
                continue;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                LOGGER.error("Iris pregen level save failed for {}", level.dimension().identifier(), cause);
                throw new IllegalStateException("Iris pregen level save failed for "
                        + level.dimension().identifier(), cause);
            }
        }
    }

    private boolean deferFinalSaveIfRequested() {
        if (!finalSaveOnServerThread.get()) {
            return false;
        }
        finalSaveDeferred.set(true);
        if (finalSaveOnServerThread.get()) {
            return true;
        }
        finalSaveDeferred.set(false);
        return false;
    }

    private void cancelQueuedFinalSave() {
        FinalSaveRequest request = queuedFinalSave.getAndSet(null);
        if (request != null) {
            request.cancel();
        }
    }

    private void saveLevelOnServerThread() {
        level.save(null, false, false);
    }

    private void requireServerThreadForFinalSave() {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Iris pregen final save must run on the Minecraft server thread for "
                    + level.dimension().identifier());
        }
    }

    @Override
    public boolean supportsRegions(int x, int z, PregenListener listener) {
        return false;
    }

    @Override
    public String getMethod(int x, int z) {
        return "Modded";
    }

    @Override
    public boolean isAsyncChunkMode() {
        return !sync;
    }

    @Override
    public void generateRegion(int x, int z, PregenListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void generateChunk(int x, int z, PregenListener listener) {
        backpressure.apply();
        if (sync) {
            generateChunkSync(x, z, listener);
            return;
        }
        generateChunkAsync(x, z, listener);
    }

    private void generateChunkSync(int x, int z, PregenListener listener) {
        listener.onChunkGenerating(x, z);
        ChunkPos pos = new ChunkPos(x, z);
        CompletableFuture<?> loadFuture = CompletableFuture
                .supplyAsync(() -> level.getChunkSource().addTicketAndLoadWithRadius(PREGEN_TICKET, pos, 0), level.getServer())
                .thenCompose((CompletableFuture<?> inner) -> inner);
        markSubmitted();
        try {
            Object result = loadFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            if (result instanceof ChunkResult<?> chunkResult && !chunkResult.isSuccess()) {
                LOGGER.warn("Iris pregen chunk {},{} returned no chunk: {}", x, z, chunkResult.getError());
                listener.onChunkFailed(x, z);
                return;
            }
            markCompleted();
            listener.onChunkGenerated(x, z);
            cleanupMantleChunk(x, z);
            listener.onChunkCleaned(x, z);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException e) {
            LOGGER.warn("Iris pregen chunk {},{} failed: {}", x, z, e.toString());
            listener.onChunkFailed(x, z);
        } finally {
            markFinished();
            level.getServer().execute(() -> level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, pos, 0));
        }
    }

    private void generateChunkAsync(int x, int z, PregenListener listener) {
        listener.onChunkGenerating(x, z);
        try {
            synchronized (permitMonitor) {
                while (inFlight.get() >= adaptiveLimit.get()) {
                    permitMonitor.wait(500L);
                }
            }
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        markSubmitted();

        ChunkPos pos = new ChunkPos(x, z);
        CompletableFuture<?> loadFuture = CompletableFuture
                .supplyAsync(() -> level.getChunkSource().addTicketAndLoadWithRadius(PREGEN_TICKET, pos, 0), level.getServer())
                .thenCompose((CompletableFuture<?> inner) -> inner);

        loadFuture.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((Object result, Throwable error) -> {
            level.getServer().execute(() -> level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, pos, 0));
            try {
                if (error != null) {
                    if (unwrap(error) instanceof TimeoutException) {
                        onTimeout();
                    }
                    LOGGER.warn("Iris pregen chunk {},{} failed: {}", x, z, error.toString());
                    listener.onChunkFailed(x, z);
                    return;
                }
                if (result instanceof ChunkResult<?> chunkResult && !chunkResult.isSuccess()) {
                    LOGGER.warn("Iris pregen chunk {},{} returned no chunk: {}", x, z, chunkResult.getError());
                    listener.onChunkFailed(x, z);
                    return;
                }
                onSuccess();
                markCompleted();
                listener.onChunkGenerated(x, z);
                cleanupMantleChunk(x, z);
                listener.onChunkCleaned(x, z);
            } finally {
                markFinished();
                semaphore.release();
            }
        });
    }

    private void markSubmitted() {
        int current = inFlight.incrementAndGet();
        inFlightPeak.accumulateAndGet(current, Math::max);
    }

    private void markFinished() {
        inFlight.decrementAndGet();
        if (sync) {
            return;
        }
        synchronized (permitMonitor) {
            permitMonitor.notifyAll();
        }
    }

    private void markCompleted() {
        completed.incrementAndGet();
    }

    private void onTimeout() {
        if (timeoutStreak.incrementAndGet() % ADAPTIVE_TIMEOUT_STEP == 0) {
            adjustAdaptiveLimit(-1);
        }
    }

    private void onSuccess() {
        int streak = timeoutStreak.get();
        if (streak > 0) {
            timeoutStreak.compareAndSet(streak, Math.max(0, streak - 2));
            return;
        }
        if ((completed.get() & (ADAPTIVE_RECOVERY_INTERVAL - 1L)) == 0L) {
            adjustAdaptiveLimit(1);
        }
    }

    private void adjustAdaptiveLimit(int direction) {
        while (true) {
            int current = adaptiveLimit.get();
            int next;
            if (direction < 0) {
                next = Math.max(minInFlight, current - 1);
            } else {
                int deficit = maxInFlight - current;
                int step = deficit > (maxInFlight / 2) ? Math.max(2, maxInFlight / 8) : 1;
                next = Math.min(maxInFlight, current + step);
            }
            if (next == current) {
                return;
            }
            if (adaptiveLimit.compareAndSet(current, next)) {
                synchronized (permitMonitor) {
                    permitMonitor.notifyAll();
                }
                return;
            }
        }
    }

    private void cleanupMantleChunk(int x, int z) {
        try {
            engine.getMantle().forceCleanupChunk(x, z);
        } catch (Throwable ignored) {
        }
    }

    private String describeWorkerPool() {
        try {
            Field field = MinecraftServer.class.getDeclaredField("executor");
            field.setAccessible(true);
            Object exec = field.get(level.getServer());
            if (exec == null) {
                return "unknown";
            }
            if (exec instanceof ThreadPoolExecutor tpe) {
                return "ThreadPoolExecutor(core=" + tpe.getCorePoolSize() + ",max=" + tpe.getMaximumPoolSize() + ")";
            }
            if (exec instanceof ForkJoinPool fjp) {
                return "ForkJoinPool(parallelism=" + fjp.getParallelism() + ")";
            }
            return exec.getClass().getSimpleName();
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error != null && error.getCause() != null ? error.getCause() : error;
    }

    private static boolean detectParallelChunkSystem() {
        String[] markers = {
                "com.ishland.c2me.base.ModProperties",
                "com.ishland.c2me.base.common.config.C2MEConfig",
                "com.ishland.c2me.opts.chunkio.ModProperties",
                "ca.spottedleaf.moonrise.common.util.MoonriseCommon"
        };
        for (String marker : markers) {
            try {
                Class.forName(marker, false, ModdedPregenMethod.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Override
    public Mantle getMantle() {
        return engine.getMantle().getMantle();
    }

    private static final class FinalSaveRequest {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private boolean claim() {
            return active.compareAndSet(true, false);
        }

        private void complete() {
            completion.complete(null);
        }

        private void fail(Throwable failure) {
            active.set(false);
            completion.completeExceptionally(failure);
        }

        private void cancel() {
            active.set(false);
            completion.complete(null);
        }

        private CompletableFuture<Void> completion() {
            return completion;
        }
    }
}

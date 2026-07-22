/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.engine;

import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.ClientUiMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineEffects;
import art.arcane.iris.engine.framework.EngineEffectsProvider;
import art.arcane.iris.engine.framework.EngineMetrics;
import art.arcane.iris.engine.framework.EngineMode;
import art.arcane.iris.engine.framework.EnginePlatformHooks;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.framework.EngineWorldManager;
import art.arcane.iris.engine.framework.EngineWorldManagerProvider;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.framework.GenerationSessionLease;
import art.arcane.iris.engine.framework.GenerationSessionManager;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.framework.SeedManager;
import art.arcane.iris.engine.framework.StructureReachability;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomePaletteLayer;
import art.arcane.iris.engine.object.IrisDecorator;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionMode;
import art.arcane.iris.engine.object.IrisDimensionModeType;
import art.arcane.iris.engine.object.IrisEngineData;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.spi.IrisLogging;
import com.google.common.util.concurrent.AtomicDouble;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.protocol.IrisMessage;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.protocol.IrisProtocolServer;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.nms.container.BlockPos;
import art.arcane.iris.core.nms.container.Pair;
import art.arcane.iris.core.structure.StructureIndexService;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.atomics.AtomicRollingSequence;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.documentation.BlockCoordinates;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.MatterStructurePOI;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class IrisEngine implements Engine {
    private static final long SESSION_DRAIN_TIMEOUT_MILLIS = 15000L;
    private static final long BACKGROUND_TASK_TIMEOUT_MILLIS = 15000L;
    private static final Object TICK_LOCK = new Object();
    private static final Set<IrisEngine> TICK_ENGINES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static int sharedTickTask = -1;

    private final AtomicInteger bud;
    private final AtomicInteger buds;
    private final AtomicInteger generated;
    private final AtomicInteger generatedLast;
    private final AtomicDouble perSecond;
    private final AtomicLong lastGPS;
    private final EngineTarget target;
    private final EngineMantle mantle;
    private final ChronoLatch perSecondLatch;
    private final ChronoLatch perSecondBudLatch;
    private final EngineMetrics metrics;
    private final boolean studio;
    private final AtomicRollingSequence wallClock;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Object lifecycleLock = new Object();
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Object backgroundTaskLock = new Object();
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Object engineDataLock = new Object();
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final List<TrackedBackgroundTask> backgroundTasks = new ArrayList<>();
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final ThreadLocal<RuntimeAssembly> runtimeAssembly = new ThreadLocal<>();
    private final AtomicBoolean cleaning;
    private final ChronoLatch cleanLatch;
    private final SeedManager seedManager;
    private final GenerationSessionManager generationSessions;
    private final EnginePlatformHooks platformHooks;
    private final AtomicBoolean closing;
    @Setter(AccessLevel.NONE)
    private volatile IrisEngineData engineData;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile EngineRuntime runtime;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile EngineTarget publishedTarget;
    private volatile int parallelism;
    private volatile boolean failing;
    private volatile boolean closed;
    private boolean runtimeReleased;
    private boolean targetReleased;
    private boolean mantleReleased;
    private boolean engineDataReleased;
    private boolean preservationReleased;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile LifecycleState lifecycleState;
    private boolean backgroundTaskAdmission;
    private final AtomicBoolean modeFallbackLogged;
    private final AtomicBoolean prefetchSaveStarted;

    public IrisEngine(EngineTarget target, boolean studio) {
        this.studio = studio;
        this.target = target;
        this.publishedTarget = target;
        this.platformHooks = IrisServices.get(EnginePlatformHooks.class);
        this.generationSessions = new GenerationSessionManager();
        this.closing = new AtomicBoolean(true);
        this.lifecycleState = LifecycleState.INITIALIZING;
        this.closed = false;
        this.failing = false;
        getEngineData();
        verifySeed();
        this.seedManager = new SeedManager(target.getWorld().getRawWorldSeed());
        bud = new AtomicInteger(0);
        buds = new AtomicInteger(0);
        metrics = new EngineMetrics(32);
        cleanLatch = new ChronoLatch(10000);
        generatedLast = new AtomicInteger(0);
        perSecond = new AtomicDouble(0);
        perSecondLatch = new ChronoLatch(1000, false);
        perSecondBudLatch = new ChronoLatch(1000, false);
        wallClock = new AtomicRollingSequence(32);
        lastGPS = new AtomicLong(M.ms());
        generated = new AtomicInteger(0);
        long _t0 = M.ms();
        mantle = new IrisEngineMantle(this);
        IrisLogging.debug("[IrisEngine timing] new IrisEngineMantle=" + (M.ms() - _t0) + "ms");
        cleaning = new AtomicBoolean(false);
        modeFallbackLogged = new AtomicBoolean(false);
        prefetchSaveStarted = new AtomicBoolean(false);
        sealInitialGenerationSession();

        try {
            if (studio) {
                _t0 = M.ms();
                getData().dump();
                getData().clearLists();
                IrisDimension replacement = getData().getDimensionLoader().load(getDimension().getLoadKey());
                if (replacement == null) {
                    throw new IllegalStateException("Studio engine could not load Iris dimension '" + getDimension().getLoadKey() + "'");
                }
                publishedTarget = new EngineTarget(getWorld(), replacement, getData());
                IrisLogging.debug("[IrisEngine timing] dump+clearLists+reload=" + (M.ms() - _t0) + "ms");
            }
            getData().registerEngine(this);
            _t0 = M.ms();
            getData().loadPrefetch(this);
            IrisLogging.debug("[IrisEngine timing] loadPrefetch=" + (M.ms() - _t0) + "ms");
            try {
                StructureIndexService.writeOnce(getData());
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                e.printStackTrace();
            }
            IrisLogging.info("Engine init: " + target.getWorld().name() + "/" + target.getDimension().getLoadKey() + " seed=" + getSeedManager().getSeed());
            _t0 = M.ms();
            EngineRuntime initialRuntime = buildRuntime();
            publishRuntime(initialRuntime, null);
            IrisLogging.debug("[IrisEngine timing] setupEngine total=" + (M.ms() - _t0) + "ms");
            _t0 = M.ms();
            GenerationCacheWarmer.warm(this);
            IrisLogging.debug("[IrisEngine timing] cache warm total=" + (M.ms() - _t0) + "ms");
            registerTicking(this);
        } catch (Throwable e) {
            cleanupFailedConstruction(e);
            throw new IllegalStateException("Failed to initialize Iris engine for world '" + target.getWorld().name() + "'.", e);
        }
        IrisLogging.debug("Engine Initialized " + getCacheID());
    }

    private void verifySeed() {
        if (getEngineData().getSeed() != null && getEngineData().getSeed() != target.getWorld().getRawWorldSeed()) {
            target.getWorld().setRawWorldSeed(getEngineData().getSeed());
        }
    }

    private void tickRandomPlayer() {
        if (closing.get() || closed) {
            return;
        }
        recycle();
        if (perSecondBudLatch.flip()) {
            buds.set(bud.get());
            bud.set(0);
        }

        EngineEffects currentEffects = getEffects();
        if (currentEffects != null) {
            currentEffects.tickRandomPlayer();
        }
    }

    private static void registerTicking(IrisEngine engine) {
        synchronized (TICK_LOCK) {
            TICK_ENGINES.add(engine);
            if (sharedTickTask == -1) {
                sharedTickTask = J.ar(IrisEngine::tickEngines, 1);
            }
        }
    }

    private static void unregisterTicking(IrisEngine engine) {
        synchronized (TICK_LOCK) {
            TICK_ENGINES.remove(engine);
            if (TICK_ENGINES.isEmpty() && sharedTickTask != -1) {
                J.car(sharedTickTask);
                sharedTickTask = -1;
            }
        }
    }

    private static void tickEngines() {
        List<IrisEngine> engines;
        synchronized (TICK_LOCK) {
            engines = List.copyOf(TICK_ENGINES);
        }
        for (IrisEngine engine : engines) {
            try {
                engine.tickRandomPlayer();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                e.printStackTrace();
            }
        }
    }

    private void sealInitialGenerationSession() {
        try {
            generationSessions.sealAndAwait("initialization", 0L);
        } catch (GenerationSessionException e) {
            throw new IllegalStateException("Failed to seal the initial Iris generation session.", e);
        }
    }

    private void sealForTransition(String reason, boolean teardown) {
        closing.set(true);
        closeBackgroundTaskAdmission();
        try {
            generationSessions.sealAndAwait(reason, SESSION_DRAIN_TIMEOUT_MILLIS, teardown);
            BackgroundTaskDrain backgroundDrain = drainBackgroundTasks(reason);
            backgroundDrain.requireComplete(reason);
        } catch (GenerationSessionException e) {
            throw new IllegalStateException("Failed to drain Iris generation for " + reason + ".", e);
        }
    }

    private EngineRuntime buildRuntime() {
        return buildRuntime(getTarget());
    }

    private EngineRuntime buildRuntime(EngineTarget runtimeTarget) {
        RuntimeAssembly assembly = new RuntimeAssembly(RNG.r.nextInt(), runtimeTarget);
        runtimeAssembly.set(assembly);
        try (IrisContext.Scope ignored = IrisContext.open(this, generationSessions.currentSessionId(), null)) {
            IrisLogging.debug("Setup Engine " + assembly.cacheId);
            long started = M.ms();
            assembly.complex = new IrisComplex(this);
            IrisLogging.debug("[IrisEngine timing] complex=" + (M.ms() - started) + "ms");
            started = M.ms();
            assembly.upperContext = buildUpperContext();
            IrisLogging.debug("[IrisEngine timing] buildUpperContext=" + (M.ms() - started) + "ms");
            started = M.ms();
            assembly.effects = IrisServices.get(EngineEffectsProvider.class).create(this);
            if (assembly.effects == null) {
                throw new IllegalStateException("Engine effects provider returned null");
            }
            IrisLogging.debug("[IrisEngine timing] EngineEffects=" + (M.ms() - started) + "ms");
            assembly.hash32 = new CompletableFuture<>();
            started = M.ms();
            mantle.hotload();
            IrisLogging.debug("[IrisEngine timing] mantle.hotload=" + (M.ms() - started) + "ms");
            started = M.ms();
            assembly.mode = createMode();
            IrisLogging.debug("[IrisEngine timing] setupMode=" + (M.ms() - started) + "ms");
            started = M.ms();
            assembly.worldManager = IrisServices.get(EngineWorldManagerProvider.class).create(this);
            if (assembly.worldManager == null) {
                throw new IllegalStateException("Engine world manager provider returned null");
            }
            IrisLogging.debug("[IrisEngine timing] IrisWorldManager=" + (M.ms() - started) + "ms");
            BiomeMaxes biomeMaxes = computeBiomeMaxes();
            return assembly.freeze(biomeMaxes);
        } catch (Throwable e) {
            Throwable cleanupFailure = closeAssembly(assembly, e);
            if (cleanupFailure != e) {
                e.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Failed to build a complete Iris engine runtime.", e);
        } finally {
            runtimeAssembly.remove();
        }
    }

    private void publishRuntime(EngineRuntime next, EngineRuntime previous) {
        Throwable retirementFailure = closeRuntime(previous, null);
        if (retirementFailure != null) {
            retirementFailure = closeRuntime(next, retirementFailure);
            lifecycleState = LifecycleState.FAILED;
            throw new IllegalStateException("Failed to retire the previous Iris engine runtime.", retirementFailure);
        }
        if (runtime == previous) {
            runtime = null;
        }

        try {
            next.worldManager.start();
        } catch (Throwable e) {
            Throwable cleanupFailure = closeRuntime(next, e);
            if (cleanupFailure != e) {
                e.addSuppressed(cleanupFailure);
            }
            lifecycleState = LifecycleState.FAILED;
            throw new IllegalStateException("Failed to start the Iris world manager.", e);
        }

        runtime = next;
        publishedTarget = next.target;
        generationSessions.activateNextSession();
        lifecycleState = LifecycleState.RUNNING;
        closing.set(false);
        openBackgroundTaskAdmission();
        scheduleRuntimeTasks(next);
        IrisLogging.debug("Engine Setup Complete " + next.cacheId);
    }

    private void scheduleRuntimeTasks(EngineRuntime engineRuntime) {
        try {
            if (!scheduleTrackedTask(() -> {
                try {
                    File[] roots = getData().getLoaders()
                            .values()
                            .stream()
                            .map(ResourceLoader::getFolderName)
                            .map(name -> new File(getData().getDataFolder(), name))
                            .filter(File::exists)
                            .filter(File::isDirectory)
                            .toArray(File[]::new);
                    engineRuntime.hash32.complete(IO.hashRecursiveMeta(roots));
                } catch (Throwable e) {
                    engineRuntime.hash32.completeExceptionally(e);
                    throw propagate(e);
                }
            })) {
                throw new IllegalStateException("Iris background task admission closed before pack hashing.");
            }
        } catch (Throwable e) {
            engineRuntime.hash32.completeExceptionally(e);
            IrisLogging.reportError(e);
            e.printStackTrace();
        }
        try {
            if (!scheduleTrackedTask(() -> platformHooks.refreshDatapackWorkspace(this))) {
                throw new IllegalStateException("Iris background task admission closed before datapack workspace refresh.");
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
        }
    }

    private boolean scheduleTrackedTask(Runnable task) {
        synchronized (backgroundTaskLock) {
            backgroundTasks.removeIf(tracked -> tracked.completion.isDone()
                    && !tracked.completion.isCompletedExceptionally());
            if (!backgroundTaskAdmission) {
                return false;
            }
            TrackedBackgroundTask tracked = new TrackedBackgroundTask();
            Future<Void> future = J.a(() -> {
                tracked.started.set(true);
                try {
                    task.run();
                    tracked.completion.complete(null);
                    return null;
                } catch (Throwable exception) {
                    tracked.completion.completeExceptionally(exception);
                    throw propagate(exception);
                }
            });
            if (future == null) {
                throw new IllegalStateException("Iris background task scheduler returned no task handle.");
            }
            tracked.future = future;
            backgroundTasks.add(tracked);
            return true;
        }
    }

    private BackgroundTaskDrain drainBackgroundTasks(String reason) {
        List<TrackedBackgroundTask> tasks;
        synchronized (backgroundTaskLock) {
            tasks = List.copyOf(backgroundTasks);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BACKGROUND_TASK_TIMEOUT_MILLIS);
        Throwable failure = null;
        for (TrackedBackgroundTask task : tasks) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                cancelBackgroundTask(task, reason);
                failure = appendFailure(failure, new TimeoutException("Timed out waiting for Iris background tasks during " + reason + "."));
                continue;
            }
            try {
                task.completion.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelBackgroundTask(task, reason);
                failure = appendFailure(failure, e);
            } catch (ExecutionException | TimeoutException e) {
                cancelBackgroundTask(task, reason);
                failure = appendFailure(failure, e);
            }
        }
        boolean complete;
        synchronized (backgroundTaskLock) {
            backgroundTasks.removeIf(tracked -> tracked.completion.isDone());
            complete = backgroundTasks.isEmpty();
        }
        return new BackgroundTaskDrain(failure, complete);
    }

    private void cancelBackgroundTask(TrackedBackgroundTask task, String reason) {
        Future<?> future = task.future;
        if (future != null && future.cancel(true) && !task.started.get()) {
            task.completion.completeExceptionally(
                    new IllegalStateException("Iris background task was cancelled before starting during " + reason + "."));
        }
    }

    private void openBackgroundTaskAdmission() {
        synchronized (backgroundTaskLock) {
            backgroundTaskAdmission = true;
        }
    }

    private void closeBackgroundTaskAdmission() {
        synchronized (backgroundTaskLock) {
            backgroundTaskAdmission = false;
        }
    }

    private void cancelBackgroundTasks(String reason) {
        List<TrackedBackgroundTask> tasks;
        synchronized (backgroundTaskLock) {
            tasks = List.copyOf(backgroundTasks);
        }
        for (TrackedBackgroundTask task : tasks) {
            cancelBackgroundTask(task, reason);
        }
    }

    private UpperDimensionContext buildUpperContext() {
        IrisDimension dim = getDimension();
        if (!dim.hasUpperDimension()) {
            return null;
        }
        String upperKey = dim.getUpperDimension();
        IrisDimension upperDim = upperKey.equals(dim.getLoadKey())
                ? dim
                : IrisData.loadAnyDimension(upperKey, getData());
        if (upperDim != null) {
            UpperDimensionContext ctx = UpperDimensionContext.create(this, upperDim);
            IrisLogging.info("Upper dimension enabled: " + upperKey
                    + (ctx.isSelfReferencing() ? " (self-referencing)" : " (cross-referencing)"));
            return ctx;
        }
        IrisLogging.warn("Upper dimension '" + upperKey + "' could not be resolved, skipping upper terrain.");
        return null;
    }

    private EngineMode createMode() {
        Throwable configuredFailure = null;
        try {
            IrisDimensionMode configuredMode = getDimension().getMode();
            if (configuredMode == null) {
                configuredMode = new IrisDimensionMode();
                getDimension().setMode(configuredMode);
            }
            EngineMode configured = configuredMode.create(this);
            if (configured == null) {
                throw new IllegalStateException("Dimension mode factory returned null");
            }
            return configured;
        } catch (Throwable e) {
            configuredFailure = e;
            IrisLogging.reportError(e);
            e.printStackTrace();
            if (modeFallbackLogged.compareAndSet(false, true)) {
                IrisLogging.warn("Failed to initialize configured dimension mode for " + getDimension().getLoadKey() + ", falling back to OVERWORLD mode.");
            }
        }

        try {
            EngineMode fallback = IrisDimensionModeType.OVERWORLD.create(this);
            if (fallback == null) {
                throw new IllegalStateException("OVERWORLD mode factory returned null");
            }
            return fallback;
        } catch (Throwable fallbackFailure) {
            fallbackFailure.addSuppressed(configuredFailure);
            throw new IllegalStateException("Both configured and fallback Iris engine modes failed.", fallbackFailure);
        }
    }

    @Override
    public void generateMatter(int x, int z, boolean multicore, ChunkContext context) {
        try (GenerationSessionLease lease = acquireGenerationLease("matter_generate");
             IrisContext.Scope ignored = IrisContext.open(this, lease.sessionId(), context)) {
            IrisComplex activeComplex = getComplex();
            if (context == null || context.getComplex() != activeComplex) {
                throw new IllegalStateException("Matter generation context does not belong to the active Iris runtime.");
            }
            if (context.getGenerationSessionId() != 0L
                    && context.getGenerationSessionId() != lease.sessionId()) {
                throw new IllegalStateException("Matter generation context belongs to Iris session "
                        + context.getGenerationSessionId() + " instead of " + lease.sessionId() + ".");
            }
            getMantle().generateMatter(x, z, multicore, context);
        } catch (GenerationSessionException e) {
            throw new IllegalStateException("Matter generation was rejected by the Iris lifecycle.", e);
        }
    }

    @Override
    public Set<String> getObjectsAt(int x, int z) {
        return getMantle().getObjectComponent().guess(x, z);
    }

    @Override
    public Set<Pair<String, BlockPos>> getPOIsAt(int chunkX, int chunkY) {
        Set<Pair<String, BlockPos>> pois = new HashSet<>();
        getMantle().getMantle().iterateChunk(chunkX, chunkY, MatterStructurePOI.class, (x, y, z, d) -> pois.add(new Pair<>(d.getType(), new BlockPos(x, y, z))));
        return pois;
    }

    private void warmupChunk(int x, int z) {
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int xx = x + (i << 4);
                int zz = z + (z << 4);
                getComplex().getTrueBiomeStream().get(xx, zz);
                getComplex().getHeightStream().get(xx, zz);
            }
        }
    }

    @Override
    public void hotload() {
        hotloadSilently();
        platformHooks.fireHotloadEvent(this);
    }

    public void hotloadComplex() {
        synchronized (lifecycleLock) {
            requireRunning("rebuild the biome complex");
            lifecycleState = LifecycleState.HOTLOADING;
            EngineRuntime previous = runtime;
            IrisComplex nextComplex = null;
            try {
                sealForTransition("complex hotload", false);
                RuntimeAssembly assembly = new RuntimeAssembly(RNG.r.nextInt(), previous.target);
                runtimeAssembly.set(assembly);
                EngineRuntime next;
                try (IrisContext.Scope ignored = IrisContext.open(this, generationSessions.currentSessionId(), null)) {
                    assembly.complex = new IrisComplex(this);
                    nextComplex = assembly.complex;
                    assembly.upperContext = buildUpperContext();
                    BiomeMaxes biomeMaxes = computeBiomeMaxes();
                    next = previous.withComplex(assembly.cacheId, assembly.complex, assembly.upperContext, biomeMaxes);
                } finally {
                    runtimeAssembly.remove();
                }
                Throwable retirementFailure = runCleanup(null, previous.complex::close);
                if (retirementFailure != null) {
                    retirementFailure = runCleanup(retirementFailure, nextComplex::close);
                    nextComplex = null;
                    lifecycleState = LifecycleState.FAILED;
                    throw new IllegalStateException("Failed to retire the previous Iris biome complex.", retirementFailure);
                }
                runtime = next;
                generationSessions.activateNextSession();
                lifecycleState = LifecycleState.RUNNING;
                closing.set(false);
                openBackgroundTaskAdmission();
            } catch (Throwable e) {
                if (nextComplex != null && nextComplex != previous.complex) {
                    Throwable cleanupFailure = runCleanup(null, nextComplex::close);
                    if (cleanupFailure != null) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
                if (lifecycleState != LifecycleState.FAILED) {
                    restoreRuntimeAfterFailedTransition(previous);
                }
                throw new IllegalStateException("Failed to rebuild the Iris biome complex.", e);
            }
        }
    }

    public void hotloadSilently() {
        synchronized (lifecycleLock) {
            requireRunning("hotload");
            lifecycleState = LifecycleState.HOTLOADING;
            EngineRuntime previousRuntime = runtime;
            IrisDimension previousDimension = getDimension();
            IrisData previousData = getData();
            IrisData replacementData = null;
            boolean published = false;
            try {
                sealForTransition("hotload", false);
                replacementData = IrisData.openRuntime(previousData.getDataFolder());
                IrisDimension replacement = replacementData.getDimensionLoader().load(previousDimension.getLoadKey());
                if (replacement == null) {
                    throw new IllegalStateException("Studio hotload could not reload Iris dimension '" + previousDimension.getLoadKey() + "'");
                }
                platformHooks.validateDimensionHotload(this, replacement);
                replacementData.registerEngine(this);
                IrisStructureLocator.invalidate(this);
                StructureReachability.invalidate(this);
                EngineTarget replacementTarget = new EngineTarget(getWorld(), replacement, replacementData);
                EngineRuntime nextRuntime = buildRuntime(replacementTarget);
                publishRuntime(nextRuntime, previousRuntime);
                published = true;
                previousData.unregisterEngine(this);
                Throwable previousDataFailure = runCleanup(null, previousData::close);
                if (previousDataFailure != null) {
                    IrisLogging.error("Failed to completely release the previous Iris data runtime.");
                    IrisLogging.reportError(previousDataFailure);
                    previousDataFailure.printStackTrace();
                }
                prefetchSaveStarted.set(false);
                getEngineData().getStatistics().hotloaded();
                if (getWorld().hasPlatformWorld()) {
                    if (!scheduleTrackedTask(() -> {
                        platformHooks.refreshWorkspace(this);
                        platformHooks.reloadDatapacks(this);
                    })) {
                        throw new IllegalStateException("Iris background task admission closed before workspace refresh.");
                    }
                }
                broadcastStudioHotload(false, "");
            } catch (Throwable e) {
                if (!published) {
                    if (replacementData != null) {
                        replacementData.unregisterEngine(this);
                        Throwable replacementDataFailure = runCleanup(null, replacementData::close);
                        if (replacementDataFailure != null) {
                            e.addSuppressed(replacementDataFailure);
                        }
                    }
                    IrisStructureLocator.invalidate(this);
                    StructureReachability.invalidate(this);
                    if (lifecycleState != LifecycleState.FAILED) {
                        Throwable rollbackFailure = runCleanup(null, getMantle()::hotload);
                        if (rollbackFailure == null) {
                            restoreRuntimeAfterFailedTransition(previousRuntime);
                        } else {
                            runtime = previousRuntime;
                            lifecycleState = LifecycleState.FAILED;
                            e.addSuppressed(rollbackFailure);
                        }
                    }
                }
                broadcastStudioHotload(true, e.getClass().getSimpleName() + ": " + e.getMessage());
                if (e instanceof Error error) {
                    throw error;
                }
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Iris hotload failed.", e);
            }
        }
    }

    private void broadcastStudioHotload(boolean failed, String message) {
        IrisProtocolServer protocolServer = IrisServices.getOrNull(IrisProtocolServer.class);
        if (protocolServer == null) {
            return;
        }
        IrisDimension dimension = getDimension();
        String packKey = dimension == null ? "" : dimension.getLoadKey();
        protocolServer.broadcastStudioHotload(packKey, 0, failed, message);
        protocolServer.broadcastToast(
                failed ? IrisMessage.Toast.KIND_ERROR : IrisMessage.Toast.KIND_SUCCESS,
                IrisLanguage.plain(ClientUiMessages.TOAST_STUDIO_HOTLOAD),
                failed ? IrisLanguage.plain(ClientUiMessages.TOAST_PACK_FAILED, MessageArgument.untrusted("pack", packKey)) : packKey);
    }

    @Override
    public IrisEngineData getEngineData() {
        IrisEngineData loaded = engineData;
        if (loaded != null) {
            return loaded;
        }
        synchronized (engineDataLock) {
            loaded = engineData;
            if (loaded != null) {
                return loaded;
            }
            File f = new File(getWorld().worldFolder(), "iris/engine-data/" + getDimension().getLoadKey() + ".json");
            if (f.exists()) {
                try {
                    loaded = new Gson().fromJson(IO.readAll(f), IrisEngineData.class);
                    if (loaded == null) {
                        throw new IllegalStateException("Engine data file contains no JSON object: " + f.getAbsolutePath());
                    }
                } catch (IOException | JsonParseException e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                    throw new IllegalStateException("Failed to read Iris engine data without modifying it: " + f.getAbsolutePath(), e);
                }
            }

            if (loaded == null) {
                loaded = new IrisEngineData();
                loaded.getStatistics().setVersion(IrisPlatforms.get().irisVersionNumber());
                loaded.getStatistics().setMCVersion(IrisPlatforms.get().minecraftVersionNumber());
                loaded.getStatistics().setUpgradedVersion(IrisPlatforms.get().irisVersionNumber());
                if (loaded.getStatistics().getVersion() == -1 || loaded.getStatistics().getMCVersion() == -1) {
                    IrisLogging.error("Failed to setup Engine Data!");
                }
                try {
                    writeEngineDataAtomically(f, loaded);
                } catch (IOException e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                    throw new IllegalStateException("Failed to create Iris engine data: " + f.getAbsolutePath(), e);
                }
            }
            engineData = loaded;
            return loaded;
        }
    }

    @Override
    public int getGenerated() {
        return generated.get();
    }

    @Override
    public double getGeneratedPerSecond() {
        if (perSecondLatch.flip()) {
            double g = generated.get() - generatedLast.get();
            generatedLast.set(generated.get());

            if (g == 0) {
                return 0;
            }

            long dur = M.ms() - lastGPS.get();
            lastGPS.set(M.ms());
            perSecond.set(g / ((double) (dur) / 1000D));
        }

        return perSecond.get();
    }

    @Override
    public boolean isStudio() {
        return studio;
    }

    private BiomeMaxes computeBiomeMaxes() {
        double objectDensity = 0D;
        double layerDensity = 0D;
        double decoratorDensity = 0D;
        for (IrisBiome i : getDimension().getReachableBiomes(this)) {
            double density = 0;

            for (IrisObjectPlacement j : i.getObjects()) {
                density += j.getDensity() * j.getChance();
            }

            objectDensity = Math.max(objectDensity, density);
            density = 0;

            for (IrisDecorator j : i.getDecorators()) {
                density += Math.max(j.getStackMax(), 1) * j.getChance();
            }

            decoratorDensity = Math.max(decoratorDensity, density);
            density = 0;

            for (IrisBiomePaletteLayer j : i.getLayers()) {
                density++;
            }

            layerDensity = Math.max(layerDensity, density);
        }
        return new BiomeMaxes(objectDensity, layerDensity, decoratorDensity);
    }

    @Override
    public int getBlockUpdatesPerSecond() {
        return buds.get();
    }

    public void printMetrics(VolmitSender sender) {
        KMap<String, Double> totals = new KMap<>();
        KMap<String, Double> weights = new KMap<>();
        double masterWallClock = wallClock.getAverage();
        KMap<String, Double> timings = getMetrics().pull();
        double totalWeight = 0;
        double wallClock = getMetrics().getTotal().getAverage();

        for (double j : timings.values()) {
            totalWeight += j;
        }

        for (String j : timings.k()) {
            weights.put(getName() + "." + j, (wallClock / totalWeight) * timings.get(j));
        }

        totals.put(getName(), wallClock);

        double mtotals = 0;

        for (double i : totals.values()) {
            mtotals += i;
        }

        for (String i : totals.k()) {
            totals.put(i, (masterWallClock / mtotals) * totals.get(i));
        }

        double v = 0;

        for (double i : weights.values()) {
            v += i;
        }

        for (String i : weights.k()) {
            weights.put(i, weights.get(i) / v);
        }

        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_ENGINE_TOTAL, MessageArgument.untrusted("value", String.valueOf(Form.duration(masterWallClock, 0)))));

        for (String i : totals.k()) {
            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_ENGINE_ENGINE, MessageArgument.untrusted("i", String.valueOf(i)), MessageArgument.untrusted("value", String.valueOf(Form.duration(totals.get(i), 0)))));
        }

        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_ENGINE_DETAILS));

        for (String i : weights.sortKNumber().reverse()) {
            String befb = C.UNDERLINE + "" + C.GREEN + "" + i.split("\\Q[\\E")[0] + C.RESET + C.GRAY + "[";
            String num = C.GOLD + i.split("\\Q[\\E")[1].split("]")[0] + C.RESET + C.GRAY + "].";
            String afb = C.ITALIC + "" + C.AQUA + i.split("\\Q]\\E")[1].substring(1) + C.RESET + C.GRAY;

            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_ENGINE_MESSAGE, MessageArgument.untrusted("befb", String.valueOf(befb)), MessageArgument.untrusted("num", String.valueOf(num)), MessageArgument.untrusted("afb", String.valueOf(afb)), MessageArgument.untrusted("value", String.valueOf(Form.pc(weights.get(i), 0)))));
        }
    }

    @Override
    public void close() {
        Throwable failure;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            lifecycleState = LifecycleState.CLOSING;
            closing.set(true);
            closeBackgroundTaskAdmission();
            unregisterTicking(this);
            platformHooks.shutdownPregenerator(this);
            try {
                generationSessions.sealAndAwait("close", SESSION_DRAIN_TIMEOUT_MILLIS, true);
            } catch (GenerationSessionException e) {
                throw new IllegalStateException("Failed to drain Iris generation for close.", e);
            }

            BackgroundTaskDrain backgroundDrain = drainBackgroundTasks("close");
            failure = backgroundDrain.failure;
            if (!backgroundDrain.allowsResourceRelease() && failure == null) {
                failure = new IllegalStateException("Iris background tasks remain active during close.");
            }

            if (backgroundDrain.allowsResourceRelease()) {
                Throwable prefetchFailure = runCleanup(null, this::savePrefetchOnce);
                Throwable engineDataFailure = runCleanup(null, this::saveEngineData);
                failure = appendFailure(failure, prefetchFailure);
                failure = appendFailure(failure, engineDataFailure);
                failure = releaseRuntime(failure);
                if (runtimeReleased) {
                    failure = releaseTarget(failure);
                }
                if (targetReleased) {
                    failure = releaseMantle(failure);
                }
                if (prefetchFailure == null
                        && engineDataFailure == null
                        && runtimeReleased
                        && targetReleased
                        && mantleReleased) {
                    failure = releaseEngineDataForShutdown(failure);
                }
                if (engineDataReleased) {
                    failure = releasePreservation(failure);
                }
            }
            if (failure == null
                    && runtimeReleased
                    && targetReleased
                    && mantleReleased
                    && engineDataReleased
                    && preservationReleased) {
                closed = true;
                lifecycleState = LifecycleState.CLOSED;
                IrisLogging.debug("Engine Fully Shutdown!");
            }
        }
        if (failure != null) {
            IrisLogging.error("Iris engine shutdown remains incomplete after cleanup failures for " + getWorld().name() + ".");
            IrisLogging.reportError(failure);
            failure.printStackTrace();
            throw new IllegalStateException("Iris engine shutdown remains incomplete after cleanup failures.", failure);
        }
    }

    private boolean isPregeneratorActiveForThisWorld() {
        return platformHooks.isPregeneratorActive(this);
    }

    private void savePrefetchOnce() {
        if (prefetchSaveStarted.compareAndSet(false, true)) {
            try {
                getData().savePrefetch(this);
            } catch (Throwable e) {
                prefetchSaveStarted.set(false);
                throw propagate(e);
            }
        }
    }

    @Override
    public IrisComplex getComplex() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null && assembly.complex != null) {
            return assembly.complex;
        }
        EngineRuntime current = runtime;
        return current == null ? null : current.complex;
    }

    @Override
    public EngineTarget getTarget() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null) {
            return assembly.target;
        }
        EngineRuntime current = runtime;
        return current == null ? publishedTarget : current.target;
    }

    @Override
    public EngineMode getMode() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null && assembly.mode != null) {
            return assembly.mode;
        }
        EngineRuntime current = requireRuntime("access the engine mode");
        return current.mode;
    }

    @Override
    public EngineEffects getEffects() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null && assembly.effects != null) {
            return assembly.effects;
        }
        EngineRuntime current = runtime;
        return current == null ? null : current.effects;
    }

    @Override
    public EngineWorldManager getWorldManager() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null && assembly.worldManager != null) {
            return assembly.worldManager;
        }
        EngineRuntime current = runtime;
        return current == null ? null : current.worldManager;
    }

    @Override
    public UpperDimensionContext getUpperContext() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null) {
            return assembly.upperContext;
        }
        EngineRuntime current = runtime;
        return current == null ? null : current.upperContext;
    }

    @Override
    public CompletableFuture<Long> getHash32() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null && assembly.hash32 != null) {
            return assembly.hash32;
        }
        EngineRuntime current = requireRuntime("access the pack hash");
        return current.hash32;
    }

    @Override
    public double getMaxBiomeObjectDensity() {
        EngineRuntime current = runtime;
        return current == null ? 0D : current.biomeMaxes.objectDensity;
    }

    @Override
    public double getMaxBiomeLayerDensity() {
        EngineRuntime current = runtime;
        return current == null ? 0D : current.biomeMaxes.layerDensity;
    }

    @Override
    public double getMaxBiomeDecoratorDensity() {
        EngineRuntime current = runtime;
        return current == null ? 0D : current.biomeMaxes.decoratorDensity;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isClosing() {
        return closing.get();
    }

    @Override
    public void recycle() {
        if (closing.get() || closed) {
            return;
        }
        if (!cleanLatch.flip()) {
            return;
        }

        if (cleaning.get()) {
            cleanLatch.flipDown();
            return;
        }

        cleaning.set(true);

        if (!scheduleTrackedTask(() -> {
            try {
                getData().getObjectLoader().clean();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                IrisLogging.error("Cleanup failed! Enable debug to see stacktrace.");
            }

            cleaning.lazySet(false);
        })) {
            cleaning.set(false);
        }
    }

    @BlockCoordinates
    @Override
    public void generate(int x, int z, Hunk<PlatformBlockState> vblocks, Hunk<PlatformBiome> vbiomes, boolean multicore) throws WrongEngineBroException {
        try (GenerationSessionLease lease = acquireGenerationLease("chunk_generate");
             IrisContext.Scope generationScope = IrisContext.open(this, lease.sessionId(), null)) {
            getEngineData().getStatistics().generatedChunk();
            PrecisionStopwatch p = PrecisionStopwatch.start();
            Hunk<PlatformBlockState> blocks = vblocks.listen((xx, y, zz, t) -> catchBlockUpdates(x + xx, y, z + zz, t));

            if (getDimension().isDebugChunkCrossSections() && ((x >> 4) % getDimension().getDebugCrossSectionsMod() == 0 || (z >> 4) % getDimension().getDebugCrossSectionsMod() == 0)) {
                PlatformBlockState crossSection = B.getState("CRYING_OBSIDIAN");
                for (int i = 0; i < 16; i++) {
                    for (int j = 0; j < 16; j++) {
                        blocks.set(i, 0, j, crossSection);
                    }
                }
            } else {
                EngineMode activeMode = getMode();
                activeMode.generate(x, z, blocks, vbiomes, multicore, lease.sessionId());
            }

            boolean skipRealFlag = platformHooks.shouldBypassMantleStages(this);
            if (!skipRealFlag) {
                getMantle().getMantle().flag(x >> 4, z >> 4, MantleFlag.REAL, true);
            }
            getMetrics().getTotal().put(p.getMilliseconds());
            generated.incrementAndGet();

            if (generated.get() == 661 && !isPregeneratorActiveForThisWorld()) {
                scheduleTrackedTask(this::savePrefetchOnce);
            }
        } catch (GenerationSessionException e) {
            throw e;
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            fail("Failed to generate " + x + ", " + z, e);
            if (e instanceof WrongEngineBroException wrongEngine) {
                throw wrongEngine;
            }
            throw new WrongEngineBroException("Failed to generate chunk at " + x + ", " + z + ".", e);
        }
    }

    @Override
    public GenerationSessionManager getGenerationSessions() {
        return generationSessions;
    }

    @Override
    public void saveEngineData() {
        synchronized (engineDataLock) {
            File f = new File(getWorld().worldFolder(), "iris/engine-data/" + getDimension().getLoadKey() + ".json");
            try {
                writeEngineDataAtomically(f, getEngineData());
                IrisLogging.debug("Saved Engine Data");
            } catch (IOException e) {
                IrisLogging.error("Failed to save Engine Data");
                IrisLogging.reportError(e);
                e.printStackTrace();
                throw new IllegalStateException("Failed to save Iris engine data: " + f.getAbsolutePath(), e);
            }
        }
    }

    @Override
    public void blockUpdatedMetric() {
        bud.incrementAndGet();
    }

    @Override
    public IrisBiome getFocus() {
        if (getDimension().getFocus() == null || getDimension().getFocus().trim().isEmpty()) {
            return null;
        }

        return getData().getBiomeLoader().load(getDimension().getFocus());
    }

    @Override
    public IrisRegion getFocusRegion() {
        if (getDimension().getFocusRegion() == null || getDimension().getFocusRegion().trim().isEmpty()) {
            return null;
        }

        return getData().getRegionLoader().load(getDimension().getFocusRegion());
    }

    @Override
    public void fail(String error, Throwable e) {
        failing = true;
        IrisLogging.error(error);
        IrisLogging.reportError(e);
        e.printStackTrace();
    }

    @Override
    public boolean hasFailed() {
        return failing;
    }

    @Override
    public int getCacheID() {
        RuntimeAssembly assembly = runtimeAssembly.get();
        if (assembly != null) {
            return assembly.cacheId;
        }
        EngineRuntime current = runtime;
        return current == null ? -1 : current.cacheId;
    }

    private void requireRunning(String operation) {
        if (closed || closing.get() || lifecycleState != LifecycleState.RUNNING || runtime == null) {
            throw new IllegalStateException("Cannot " + operation + " while Iris engine " + getWorld().name()
                    + " is " + lifecycleState.name().toLowerCase() + ".");
        }
    }

    private void restoreRuntimeAfterFailedTransition(EngineRuntime previous) {
        runtime = previous;
        if (generationSessions.activeLeases() == 0) {
            generationSessions.activateNextSession();
            lifecycleState = LifecycleState.RUNNING;
            closing.set(false);
            openBackgroundTaskAdmission();
            return;
        }
        lifecycleState = LifecycleState.FAILED;
    }

    private EngineRuntime requireRuntime(String operation) {
        EngineRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("Cannot " + operation + " without an active Iris runtime for "
                    + getWorld().name() + ".");
        }
        return current;
    }

    private void releaseEngineData() {
        IrisData data = getData();
        data.unregisterEngine(this);
        if (data.getEngines().isEmpty()) {
            data.close();
            data.clearLists();
        }
    }

    private void cleanupFailedConstruction(Throwable original) {
        closing.set(true);
        closeBackgroundTaskAdmission();
        unregisterTicking(this);
        lifecycleState = LifecycleState.FAILED;
        Throwable cleanupFailure = null;
        try {
            generationSessions.sealAndAwait("failed initialization", 0L, true);
        } catch (Throwable e) {
            cleanupFailure = appendFailure(cleanupFailure, e);
        }
        cancelBackgroundTasks("failed initialization");
        BackgroundTaskDrain backgroundDrain = drainBackgroundTasks("failed initialization");
        cleanupFailure = appendFailure(cleanupFailure, backgroundDrain.failure);
        if (!backgroundDrain.allowsResourceRelease()) {
            cleanupFailure = appendFailure(cleanupFailure,
                    new IllegalStateException("Iris background tasks remain active after failed initialization."));
            if (cleanupFailure != original) {
                original.addSuppressed(cleanupFailure);
            }
            return;
        }
        cleanupFailure = closeRuntime(runtime, cleanupFailure);
        runtime = null;
        cleanupFailure = runCleanup(cleanupFailure, getTarget()::close);
        cleanupFailure = runCleanup(cleanupFailure, getMantle()::close);
        cleanupFailure = runCleanup(cleanupFailure, this::releaseEngineData);
        closed = true;
        cleanupFailure = runCleanup(cleanupFailure, () -> {
            PreservationRegistry registry = IrisServices.getOrNull(PreservationRegistry.class);
            if (registry != null) {
                registry.dereference();
            }
        });
        if (cleanupFailure != null && cleanupFailure != original) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private Throwable closeAssembly(RuntimeAssembly assembly, Throwable failure) {
        if (assembly == null) {
            return failure;
        }
        failure = runCleanup(failure, () -> {
            if (assembly.worldManager != null) {
                assembly.worldManager.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.effects != null) {
                assembly.effects.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.mode != null) {
                assembly.mode.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.complex != null) {
                assembly.complex.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.hash32 != null) {
                assembly.hash32.cancel(true);
            }
        });
        return failure;
    }

    private Throwable closeRuntime(EngineRuntime engineRuntime, Throwable failure) {
        if (engineRuntime == null) {
            return failure;
        }
        failure = runCleanup(failure, engineRuntime.worldManager::close);
        failure = runCleanup(failure, engineRuntime.effects::close);
        failure = runCleanup(failure, engineRuntime.mode::close);
        failure = runCleanup(failure, engineRuntime.complex::close);
        failure = runCleanup(failure, () -> engineRuntime.hash32.cancel(true));
        return failure;
    }

    private Throwable releaseRuntime(Throwable failure) {
        if (runtimeReleased) {
            return failure;
        }
        Throwable runtimeFailure = closeRuntime(runtime, null);
        if (runtimeFailure != null) {
            return appendFailure(failure, runtimeFailure);
        }
        runtime = null;
        runtimeReleased = true;
        return failure;
    }

    private Throwable releaseTarget(Throwable failure) {
        if (targetReleased) {
            return failure;
        }
        Throwable targetFailure = runCleanup(null, getTarget()::close);
        if (targetFailure != null) {
            return appendFailure(failure, targetFailure);
        }
        targetReleased = true;
        return failure;
    }

    private Throwable releaseMantle(Throwable failure) {
        if (mantleReleased) {
            return failure;
        }
        Throwable mantleFailure = runCleanup(null, getMantle()::close);
        if (mantleFailure != null) {
            return appendFailure(failure, mantleFailure);
        }
        mantleReleased = true;
        return failure;
    }

    private Throwable releaseEngineDataForShutdown(Throwable failure) {
        if (engineDataReleased) {
            return failure;
        }
        Throwable dataFailure = runCleanup(null, this::releaseEngineData);
        if (dataFailure != null) {
            return appendFailure(failure, dataFailure);
        }
        engineDataReleased = true;
        return failure;
    }

    private Throwable releasePreservation(Throwable failure) {
        if (preservationReleased) {
            return failure;
        }
        Throwable preservationFailure = runCleanup(null, () -> {
            PreservationRegistry registry = IrisServices.getOrNull(PreservationRegistry.class);
            if (registry != null) {
                registry.dereference();
            }
        });
        if (preservationFailure != null) {
            return appendFailure(failure, preservationFailure);
        }
        preservationReleased = true;
        return failure;
    }

    private static Throwable runCleanup(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable e) {
            return appendFailure(failure, e);
        }
        return failure;
    }

    private static Throwable appendFailure(Throwable failure, Throwable additional) {
        if (additional == null) {
            return failure;
        }
        if (failure == null) {
            return additional;
        }
        if (failure != additional) {
            failure.addSuppressed(additional);
        }
        return failure;
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(throwable);
    }

    static void writeEngineDataAtomically(File file, IrisEngineData data) throws IOException {
        Path output = file.toPath();
        Path parent = output.getParent();
        if (parent == null) {
            throw new IOException("Engine data path has no parent: " + output);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean EngineSafe() {
        // Todo: this has potential if done right
        int EngineMCVersion = getEngineData().getStatistics().getMCVersion();
        int EngineIrisVersion = getEngineData().getStatistics().getVersion();
        int MinecraftVersion = IrisPlatforms.get().minecraftVersionNumber();
        int IrisVersion = IrisPlatforms.get().irisVersionNumber();
        if (EngineIrisVersion != IrisVersion) {
            return false;
        }
        if (EngineMCVersion != MinecraftVersion) {
            return false;
        }
        return true;
    }

    private enum LifecycleState {
        INITIALIZING,
        RUNNING,
        HOTLOADING,
        CLOSING,
        CLOSED,
        FAILED
    }

    private record BiomeMaxes(double objectDensity, double layerDensity, double decoratorDensity) {
    }

    private record EngineRuntime(
            int cacheId,
            EngineTarget target,
            IrisComplex complex,
            UpperDimensionContext upperContext,
            EngineEffects effects,
            EngineMode mode,
            EngineWorldManager worldManager,
            CompletableFuture<Long> hash32,
            BiomeMaxes biomeMaxes
    ) {
        private EngineRuntime withComplex(
                int nextCacheId,
                IrisComplex nextComplex,
                UpperDimensionContext nextUpperContext,
                BiomeMaxes nextBiomeMaxes
        ) {
            return new EngineRuntime(
                    nextCacheId,
                    target,
                    nextComplex,
                    nextUpperContext,
                    effects,
                    mode,
                    worldManager,
                    hash32,
                    nextBiomeMaxes);
        }
    }

    private static final class RuntimeAssembly {
        private final int cacheId;
        private final EngineTarget target;
        private IrisComplex complex;
        private UpperDimensionContext upperContext;
        private EngineEffects effects;
        private EngineMode mode;
        private EngineWorldManager worldManager;
        private CompletableFuture<Long> hash32;

        private RuntimeAssembly(int cacheId, EngineTarget target) {
            this.cacheId = cacheId;
            this.target = target;
        }

        private EngineRuntime freeze(BiomeMaxes biomeMaxes) {
            if (complex == null || effects == null || mode == null || worldManager == null || hash32 == null) {
                throw new IllegalStateException("Cannot publish an incomplete Iris engine runtime.");
            }
            return new EngineRuntime(
                    cacheId,
                    target,
                    complex,
                    upperContext,
                    effects,
                    mode,
                    worldManager,
                    hash32,
                    biomeMaxes);
        }
    }

    private static final class TrackedBackgroundTask {
        private final AtomicBoolean started = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile Future<?> future;
    }

    record BackgroundTaskDrain(Throwable failure, boolean complete) {
        boolean allowsResourceRelease() {
            return complete;
        }

        private void requireComplete(String reason) {
            if (failure != null) {
                throw new IllegalStateException("Iris background tasks failed to drain during " + reason + ".", failure);
            }
            if (!complete) {
                throw new IllegalStateException("Iris background tasks remain active during " + reason + ".");
            }
        }
    }

}

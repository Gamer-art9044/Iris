package art.arcane.iris.core.service;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.pregenerator.MantleHeapPressure;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineTelemetrySnapshot;
import art.arcane.iris.engine.framework.MeteredCache;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.iris.util.project.stream.utility.CachedDoubleStream2D;
import art.arcane.iris.util.project.stream.utility.CachedStream2D;
import art.arcane.iris.util.project.stream.utility.CachedStream3D;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.format.Form;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class IrisEngineSVC implements IrisService {
    private static final long MAINTENANCE_PERIOD_MILLIS = 2_000L;
    private static final long METRICS_PERIOD_MILLIS = 1_000L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;

    private final Object registrationLock = new Object();
    private final Set<ClosingGenerator> closingGenerators = new HashSet<>();
    private final AtomicReference<IrisEngineStatus.MaintenanceMetrics> metrics =
            new AtomicReference<>(IrisEngineStatus.MaintenanceMetrics.EMPTY);
    private final AtomicReference<IrisTelemetrySnapshot> telemetry =
            new AtomicReference<>(IrisTelemetrySnapshot.EMPTY);
    private final Map<World, CompletableFuture<Void>> pendingRegistrations = new HashMap<>();
    private final Map<World, Registered> worlds = new ConcurrentHashMap<>();
    private volatile ScheduledThreadPoolExecutor service;
    private volatile ScheduledFuture<?> metricsTask;

    @Override
    public void onEnable() {
        ScheduledThreadPoolExecutor current = service;
        if (current != null && !current.isShutdown()) {
            return;
        }

        IrisSettings.IrisSettingsEngineSVC settings = IrisSettings.get().getPerformance().getEngineSVC();
        ThreadFactory threadFactory = (settings.isUseVirtualThreads()
                ? Thread.ofVirtual()
                : Thread.ofPlatform().priority(settings.getPriority()))
                .name("Iris EngineSVC-", 0)
                .factory();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                EngineMaintenance.workerParallelism(), threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        service = executor;

        for (World world : Bukkit.getWorlds()) {
            add(world);
        }
        metricsTask = executor.scheduleWithFixedDelay(
                this::updateMetricsSafely,
                0L,
                METRICS_PERIOD_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void onDisable() {
        ScheduledThreadPoolExecutor activeService = service;
        service = null;

        ScheduledFuture<?> activeMetricsTask = metricsTask;
        metricsTask = null;
        if (activeMetricsTask != null) {
            activeMetricsTask.cancel(false);
        }

        List<Registered> registeredWorlds;
        List<ClosingGenerator> reservedCloses = new ArrayList<>();
        List<CompletableFuture<Void>> generatorCloses;
        synchronized (registrationLock) {
            registeredWorlds = List.copyOf(worlds.values());
            worlds.clear();
            pendingRegistrations.clear();
            for (Registered registered : registeredWorlds) {
                registered.close();
                reservedCloses.add(reserveClose(registered));
            }
            generatorCloses = new ArrayList<>(closingGenerators.size());
            for (ClosingGenerator closing : closingGenerators) {
                generatorCloses.add(closing.completion());
            }
        }

        shutdownAndDrain(activeService);
        for (int index = 0; index < registeredWorlds.size(); index++) {
            startClose(registeredWorlds.get(index), reservedCloses.get(index));
        }
        awaitGeneratorShutdown(generatorCloses);
        resetMetrics();
    }

    public void engineStatus(VolmitSender sender) {
        ScheduledThreadPoolExecutor activeService = service;
        ScheduledFuture<?> activeMetricsTask = metricsTask;
        boolean serviceRunning = activeService != null && !activeService.isShutdown();
        boolean metricsRunning = activeMetricsTask != null
                && !activeMetricsTask.isDone()
                && !activeMetricsTask.isCancelled();

        IrisEngineStatus.send(sender, new IrisEngineStatus.Snapshot(
                serviceRunning,
                metricsRunning,
                MAINTENANCE_PERIOD_MILLIS,
                serviceRunning ? activeService.getCorePoolSize() : 0,
                TimeUnit.SECONDS.toMillis(IrisSettings.get().getPerformance().getMantleKeepAlive()),
                MantleHeapPressure.usedFraction(),
                metrics.get()));
    }

    IrisTelemetrySnapshot telemetrySnapshot() {
        return telemetry.get();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        remove(event.getWorld());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        add(event.getWorld());
    }

    private void add(World world) {
        ScheduledThreadPoolExecutor activeService = service;
        if (world == null || activeService == null || activeService.isShutdown() || !isCurrentWorld(world)) {
            return;
        }

        PlatformChunkGenerator access = IrisToolbelt.access(world);
        if (access == null) {
            return;
        }

        RegistrationIdentity registrationIdentity = RegistrationIdentity.from(world);
        Registered replaced = null;
        ClosingGenerator replacementClose = null;
        CompletableFuture<Void> retryAfter = null;
        try {
            synchronized (registrationLock) {
                if (service != activeService || activeService.isShutdown() || !isCurrentWorld(world)) {
                    return;
                }

                Registered current = worlds.get(world);
                if (current != null && !current.isClosed() && current.uses(access)) {
                    pendingRegistrations.remove(world);
                    return;
                }
                if (current != null) {
                    worlds.remove(world, current);
                    current.close();
                    replaced = current;
                    replacementClose = reserveClose(current);
                    retryAfter = replacementClose.completion();
                } else {
                    ClosingGenerator closing = findClosingGenerator(registrationIdentity);
                    if (closing != null) {
                        retryAfter = closing.completion();
                    } else if (!access.isClosing() && !hasRegisteredConflict(registrationIdentity)) {
                        Registration registration = new Registration(world.getName(), access, registrationIdentity);
                        worlds.put(world, new Registered(registration, activeService));
                        pendingRegistrations.remove(world);
                    }
                }
            }
        } catch (RejectedExecutionException exception) {
            if (!activeService.isShutdown()) {
                reportFailure("Failed to register engine maintenance for " + world.getName(), exception);
            }
        }

        if (replacementClose != null) {
            retryRegistrationAfterClose(world, retryAfter);
            startClose(replaced, replacementClose);
            return;
        }
        if (retryAfter != null) {
            retryRegistrationAfterClose(world, retryAfter);
        }
    }

    private void remove(World world) {
        if (world == null) {
            return;
        }

        Registered registered;
        ClosingGenerator closing = null;
        synchronized (registrationLock) {
            pendingRegistrations.remove(world);
            registered = worlds.remove(world);
            if (registered != null) {
                registered.close();
                closing = reserveClose(registered);
            }
        }
        if (closing != null) {
            startClose(registered, closing);
        }
    }

    private ClosingGenerator reserveClose(Registered registered) {
        ClosingGenerator closing = new ClosingGenerator(
                registered.registrationIdentity(),
                new CompletableFuture<>());
        closingGenerators.add(closing);
        return closing;
    }

    private void startClose(Registered registered, ClosingGenerator closing) {
        try {
            CompletableFuture<Void> close = registered.closeGenerator();
            if (close == null) {
                throw new IllegalStateException("Generator close returned no completion future for " + registered.name());
            }
            close.whenComplete((ignored, failure) -> completeClose(closing, failure));
        } catch (Throwable exception) {
            reportFailure("Failed to start generator close for " + registered.name(), exception);
            completeClose(closing, exception);
        }
    }

    private void completeClose(ClosingGenerator closing, Throwable failure) {
        if (failure == null) {
            synchronized (registrationLock) {
                closingGenerators.remove(closing);
            }
            closing.completion().complete(null);
        } else {
            closing.completion().completeExceptionally(failure);
        }
    }

    private ClosingGenerator findClosingGenerator(RegistrationIdentity registrationIdentity) {
        for (ClosingGenerator closing : closingGenerators) {
            if (closing.registrationIdentity().conflictsWith(registrationIdentity)) {
                return closing;
            }
        }
        return null;
    }

    private boolean hasRegisteredConflict(RegistrationIdentity registrationIdentity) {
        for (Registered registered : worlds.values()) {
            if (!registered.isClosed() && registered.registrationIdentity().conflictsWith(registrationIdentity)) {
                return true;
            }
        }
        return false;
    }

    private void retryRegistrationAfterClose(World world, CompletableFuture<Void> completion) {
        synchronized (registrationLock) {
            if (service == null || pendingRegistrations.get(world) == completion) {
                return;
            }
            pendingRegistrations.put(world, completion);
        }

        completion.whenComplete((ignored, failure) -> {
            synchronized (registrationLock) {
                if (pendingRegistrations.get(world) != completion) {
                    return;
                }
                pendingRegistrations.remove(world);
            }
            if (failure != null) {
                return;
            }

            ScheduledThreadPoolExecutor activeService = service;
            if (activeService == null || activeService.isShutdown()) {
                return;
            }
            try {
                J.s(() -> add(world), 1);
            } catch (Throwable exception) {
                if (service != null) {
                    reportFailure("Failed to retry engine maintenance registration for " + world.getName(), exception);
                }
            }
        });
    }

    private static boolean isCurrentWorld(World world) {
        return Bukkit.getWorld(world.getUID()) == world;
    }

    private void updateMetricsSafely() {
        try {
            updateMetrics();
        } catch (Throwable exception) {
            reportFailure("Failed to update engine maintenance metrics", exception);
        }
    }

    private void updateMetrics() {
        long now = System.currentTimeMillis();
        List<EngineTelemetrySnapshot> worldSnapshots = new ArrayList<>(worlds.size());
        int maintenanceTasks = 0;
        int irisWorlds = 0;

        for (Registered registered : worlds.values()) {
            if (registered.isClosed()) {
                continue;
            }

            irisWorlds++;
            if (registered.maintenanceInFlight()) {
                maintenanceTasks++;
            }

            try {
                Engine engine = registered.getEngine();
                if (engine == null) {
                    continue;
                }
                if (engine.isClosing() && !engine.isClosed()) {
                    continue;
                }
                if (engine.isClosed() || engine.getMantle().getMantle().isClosed()) {
                    registered.close();
                    continue;
                }

                double chunksPerSecond = registered.sampleChunksPerSecond(engine.getGenerated(), now);
                worldSnapshots.add(EngineTelemetrySnapshot.capture(engine, chunksPerSecond, now));
                registered.clearMetricsFailure();
            } catch (Throwable exception) {
                if (EngineMaintenance.isMantleClosed(exception)) {
                    if (registered.generatorClosing()) {
                        registered.close();
                    }
                } else {
                    registered.reportMetricsFailure(exception);
                }
            }
        }

        EngineTelemetrySnapshot.Aggregate aggregate = EngineTelemetrySnapshot.aggregate(worldSnapshots);
        metrics.set(new IrisEngineStatus.MaintenanceMetrics(
                aggregate.mantleResidentPlates(),
                aggregate.mantleQueuedPlates(),
                aggregate.loadedChunks(),
                irisWorlds,
                maintenanceTasks,
                aggregate.mantleIdleAverageMs(),
                aggregate.mantleIdleMaxMs(),
                aggregate.mantleIdleMinMs()));

        ScheduledThreadPoolExecutor activeService = service;
        int workers = activeService == null || activeService.isShutdown()
                ? 0
                : activeService.getCorePoolSize();
        int closingCount;
        int pendingCount;
        synchronized (registrationLock) {
            closingCount = closingGenerators.size();
            pendingCount = pendingRegistrations.size();
        }
        double heapUsage = MantleHeapPressure.usedFraction();
        telemetry.set(new IrisTelemetrySnapshot(
                now,
                worldSnapshots,
                aggregate,
                maintenanceTasks,
                workers,
                closingCount,
                pendingCount,
                heapUsage,
                MantleHeapPressure.reclaimUrgency(heapUsage),
                summarizeCaches(),
                pregeneratorSnapshot()));
    }

    private IrisTelemetrySnapshot.CacheSnapshot summarizeCaches() {
        IrisTelemetrySnapshot.CacheBucket resource = IrisTelemetrySnapshot.CacheBucket.EMPTY;
        IrisTelemetrySnapshot.CacheBucket stream2d = IrisTelemetrySnapshot.CacheBucket.EMPTY;
        IrisTelemetrySnapshot.CacheBucket stream3d = IrisTelemetrySnapshot.CacheBucket.EMPTY;
        IrisTelemetrySnapshot.CacheBucket other = IrisTelemetrySnapshot.CacheBucket.EMPTY;
        PreservationSVC preservation = IrisServices.get(PreservationSVC.class);
        List<MeteredCache> caches = preservation == null ? List.of() : preservation.getCaches();

        for (MeteredCache cache : caches) {
            IrisTelemetrySnapshot.CacheBucket sample = new IrisTelemetrySnapshot.CacheBucket(
                    1,
                    cache.getSize(),
                    cache.getMaxSize());
            if (cache instanceof ResourceLoader<?>) {
                resource = resource.plus(sample);
            } else if (cache instanceof CachedStream2D<?> || cache instanceof CachedDoubleStream2D) {
                stream2d = stream2d.plus(sample);
            } else if (cache instanceof CachedStream3D<?>) {
                stream3d = stream3d.plus(sample);
            } else {
                other = other.plus(sample);
            }
        }

        IrisTelemetrySnapshot.CacheBucket total = resource.plus(stream2d).plus(stream3d).plus(other);
        return new IrisTelemetrySnapshot.CacheSnapshot(total, resource, stream2d, stream3d, other);
    }

    private IrisTelemetrySnapshot.PregenSnapshot pregeneratorSnapshot() {
        PregeneratorJob.PregenProgress progress = PregeneratorJob.progressSnapshot();
        if (progress == null) {
            return IrisTelemetrySnapshot.PregenSnapshot.INACTIVE;
        }
        return new IrisTelemetrySnapshot.PregenSnapshot(
                true,
                progress.worldIdentity(),
                progress.worldName(),
                progress.paused(),
                progress.percent(),
                progress.generated(),
                progress.totalChunks(),
                progress.chunksRemaining(),
                progress.chunksPerSecond(),
                progress.eta(),
                progress.elapsed(),
                progress.failed());
    }

    private void shutdownAndDrain(ScheduledThreadPoolExecutor activeService) {
        if (activeService == null) {
            return;
        }

        activeService.shutdown();
        try {
            if (!activeService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                activeService.shutdownNow();
            }
        } catch (InterruptedException exception) {
            activeService.shutdownNow();
            Thread.currentThread().interrupt();
            reportFailure("Interrupted while draining engine maintenance", exception);
        }
    }

    private void awaitGeneratorShutdown(List<CompletableFuture<Void>> closes) {
        if (closes.isEmpty()) {
            return;
        }

        try {
            CompletableFuture.allOf(closes.toArray(new CompletableFuture<?>[0]))
                    .get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            reportFailure("Interrupted while closing Iris generators", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            reportFailure("Unexpected generator close aggregation failure", cause);
        } catch (TimeoutException exception) {
            reportFailure("Timed out while closing Iris generators", exception);
        }
    }

    private void resetMetrics() {
        metrics.set(IrisEngineStatus.MaintenanceMetrics.EMPTY);
        telemetry.set(IrisTelemetrySnapshot.EMPTY);
    }

    private static void reportFailure(String message, Throwable exception) {
        IrisLogging.reportError(exception);
        IrisLogging.error("EngineSVC: " + message);
        exception.printStackTrace();
    }

    private final class Registered {
        private final String name;
        private final PlatformChunkGenerator access;
        private final RegistrationIdentity registrationIdentity;
        private final AtomicBoolean metricsFailureReported = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<Void>> activeMaintenance = new AtomicReference<>();
        private volatile ScheduledFuture<?> maintenance;
        private volatile boolean closed;
        private long lastGeneratedSampleAtMs;
        private int lastGeneratedCount;

        private Registered(Registration registration, ScheduledThreadPoolExecutor executor) {
            name = registration.name();
            access = registration.access();
            registrationIdentity = registration.registrationIdentity();
            long offset = ThreadLocalRandom.current().nextLong(MAINTENANCE_PERIOD_MILLIS);
            maintenance = executor.scheduleWithFixedDelay(
                    this::maintain,
                    offset,
                    MAINTENANCE_PERIOD_MILLIS,
                    TimeUnit.MILLISECONDS);
        }

        private void maintain() {
            if (closed) {
                return;
            }
            CompletableFuture<Void> completion = new CompletableFuture<>();
            if (!activeMaintenance.compareAndSet(null, completion)) {
                return;
            }
            try {
                if (closed) {
                    return;
                }
                Engine engine = getEngine();
                if (engine == null) {
                    return;
                }
                if (!EngineMaintenance.isAvailable(engine)) {
                    return;
                }

                boolean pregeneratorTargetsWorld = EngineMaintenance.pregeneratorTargets(engine);
                if (pregeneratorTargetsWorld || !EngineMaintenance.shouldRun(engine)) {
                    return;
                }

                EngineMaintenance.Outcome outcome = EngineMaintenance.run(engine);
                if (outcome.unloadedTectonicPlates() > 0) {
                    IrisLogging.debug(C.GOLD + "Unloaded " + C.YELLOW
                            + outcome.unloadedTectonicPlates() + " TectonicPlates in " + C.RED
                            + Form.duration(outcome.unloadDurationMillis(), 2) + " for " + name);
                }
            } catch (Throwable exception) {
                if (EngineMaintenance.isMantleClosed(exception)) {
                    if (generatorClosing()) {
                        close();
                    }
                    return;
                }
                reportFailure("Failed maintenance for " + name, exception);
            } finally {
                completion.complete(null);
                activeMaintenance.compareAndSet(completion, null);
            }
        }

        private boolean uses(PlatformChunkGenerator candidate) {
            return access == candidate;
        }

        private String name() {
            return name;
        }

        private RegistrationIdentity registrationIdentity() {
            return registrationIdentity;
        }

        private boolean generatorClosing() {
            return access.isClosing();
        }

        private boolean isClosed() {
            return closed;
        }

        private boolean maintenanceInFlight() {
            return activeMaintenance.get() != null;
        }

        private double sampleChunksPerSecond(int generatedCount, long sampledAtMs) {
            int safeGeneratedCount = Math.max(0, generatedCount);
            long previousAt = lastGeneratedSampleAtMs;
            int previousCount = lastGeneratedCount;
            lastGeneratedSampleAtMs = sampledAtMs;
            lastGeneratedCount = safeGeneratedCount;
            if (previousAt <= 0L || sampledAtMs <= previousAt) {
                return 0D;
            }
            int generatedDelta = Math.max(0, safeGeneratedCount - previousCount);
            return generatedDelta * 1000D / (sampledAtMs - previousAt);
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;

            ScheduledFuture<?> activeMaintenance = maintenance;
            maintenance = null;
            if (activeMaintenance != null) {
                activeMaintenance.cancel(false);
            }
        }

        private CompletableFuture<Void> closeGenerator() {
            CompletableFuture<Void> runningMaintenance = activeMaintenance.get();
            if (runningMaintenance == null) {
                return invokeGeneratorClose();
            }
            return runningMaintenance.thenCompose(ignored -> invokeGeneratorClose());
        }

        private CompletableFuture<Void> invokeGeneratorClose() {
            try {
                CompletableFuture<Void> future = access.closeAsync();
                if (future == null) {
                    IllegalStateException exception = new IllegalStateException(
                            "Generator close returned no completion future for " + name);
                    reportFailure("Failed to close generator for " + name, exception);
                    return CompletableFuture.failedFuture(exception);
                }
                return future.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        reportFailure("Failed to close generator for " + name, cause);
                    }
                });
            } catch (Throwable exception) {
                reportFailure("Failed to close generator for " + name, exception);
                return CompletableFuture.failedFuture(exception);
            }
        }

        private Engine getEngine() {
            return closed ? null : access.getEngine();
        }

        private void clearMetricsFailure() {
            metricsFailureReported.set(false);
        }

        private void reportMetricsFailure(Throwable exception) {
            if (metricsFailureReported.compareAndSet(false, true)) {
                reportFailure("Failed to sample metrics for " + name, exception);
            }
        }
    }

    private record Registration(String name, PlatformChunkGenerator access,
                                RegistrationIdentity registrationIdentity) {
    }

    record RegistrationIdentity(String worldIdentity, Path worldFolder) {
        RegistrationIdentity {
            worldIdentity = Objects.requireNonNull(worldIdentity);
            worldFolder = Objects.requireNonNull(worldFolder).toAbsolutePath().normalize();
        }

        private static RegistrationIdentity from(World world) {
            return new RegistrationIdentity(
                    WorldIdentity.serialize(world),
                    world.getWorldFolder().toPath());
        }

        boolean conflictsWith(RegistrationIdentity candidate) {
            return worldIdentity.equals(candidate.worldIdentity)
                    || worldFolder.equals(candidate.worldFolder);
        }
    }

    private record ClosingGenerator(RegistrationIdentity registrationIdentity,
                                    CompletableFuture<Void> completion) {
    }
}

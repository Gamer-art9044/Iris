package art.arcane.iris.core.runtime;

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.link.MultiverseCoreLink;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.pack.AtomicDirectoryPublisher;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.project.IrisCodeWorkspace;
import art.arcane.iris.core.tools.IrisCreator;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StudioOpenCoordinator {
    private static final long STUDIO_CLOSE_TIMEOUT_SECONDS = 120L;
    private static final long STUDIO_ENTRY_LOAD_TIMEOUT_SECONDS = 30L;
    private static final long STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS = 30L;
    private static final long STUDIO_ENTRY_CLEANUP_BOUNDARY_SECONDS = 120L;
    private static volatile StudioOpenCoordinator instance;
    private final EntryLoadRegistry entryLoads;

    private StudioOpenCoordinator() {
        entryLoads = new EntryLoadRegistry();
    }

    public static StudioOpenCoordinator get() {
        StudioOpenCoordinator current = instance;
        if (current != null) {
            return current;
        }

        synchronized (StudioOpenCoordinator.class) {
            if (instance != null) {
                return instance;
            }

            instance = new StudioOpenCoordinator();
            return instance;
        }
    }

    public CompletableFuture<StudioOpenResult> open(StudioOpenRequest request) {
        CompletableFuture<StudioOpenResult> future = new CompletableFuture<>();
        J.aBukkit(() -> executeOpen(request, future));
        return future;
    }

    public CompletableFuture<StudioCloseResult> closeProject(IrisProject project) {
        if (project == null) {
            return CompletableFuture.completedFuture(new StudioCloseResult(null, true, true, false, null));
        }

        PlatformChunkGenerator provider = project.getActiveProvider();
        if (provider == null) {
            return CompletableFuture.completedFuture(new StudioCloseResult(null, true, true, false, null));
        }

        World world = BukkitWorldBinding.world(provider.getTarget().getWorld());
        String worldName = world == null
                ? IrisWorldStorage.logicalName(WorldIdentity.parse(provider.getTarget().getWorld().identity()))
                : IrisWorldStorage.logicalName(world);
        return closeWorldCoordinated(provider, worldName, world, true, project);
    }

    private void executeOpen(StudioOpenRequest request, CompletableFuture<StudioOpenResult> future) {
        World world = null;
        PlatformChunkGenerator provider = null;
        CompletableFuture<Void> entryLoadFuture = null;
        try {
            long openStart = System.nanoTime();
            long t = openStart;
            entryLoads.rejectNewOpen();
            updateStage(request, "resolve_dimension", 0.04D);
            if (IrisToolbelt.getDimension(request.dimensionKey()) == null) {
                throw new IrisException("Dimension cannot be found for id " + request.dimensionKey() + ".");
            }

            updateStage(request, "prepare_world_pack", 0.10D);
            cleanupStaleTransientWorlds(request.worldName());
            t = logStudioPhase(request, "resolve_dimension_and_cleanup", t, openStart);

            updateStage(request, "install_datapacks", 0.18D);
            IrisCreator creator = IrisToolbelt.createWorld()
                    .seed(request.seed())
                    .sender(request.sender())
                    .studio(true)
                    .name(request.worldName())
                    .dimension(request.dimensionKey())
                    .datapackPreparation(request.openKind().datapackPreparation())
                    .studioProgressConsumer((progress, stage) -> updateStage(request, mapCreatorStage(stage), progress))
                    .studioTimingConsumer((phase, duration) -> logMeasuredStudioPhase(request, phase, duration));
            world = creator.create();
            t = logStudioPhase(request, "create_world_total", t, openStart);
            provider = IrisToolbelt.access(world);
            if (provider == null) {
                throw new IllegalStateException("Studio runtime provider is unavailable for world \"" + request.worldName() + "\".");
            }

            updateStage(request, "apply_world_rules", 0.72D);
            final World rulesWorld = world;
            CompletableFuture<Boolean> rulesApplied =
                    J.sfut(() -> WorldRuntimeControlService.get().applyStudioWorldRules(rulesWorld));
            if (rulesApplied != null) {
                rulesApplied.get(15L, TimeUnit.SECONDS);
            }
            t = logStudioPhase(request, "apply_world_rules", t, openStart);

            updateStage(request, "prepare_generator", 0.78D);
            if (request.openKind().prepareGeneratorState()) {
                WorldRuntimeControlService.get().prepareGenerator(world);
            }
            t = logStudioPhase(request, "prepare_generator", t, openStart);

            Location entryAnchor = WorldRuntimeControlService.get().resolveEntryAnchor(world);
            if (entryAnchor == null) {
                throw new IllegalStateException("Studio entry anchor could not be resolved.");
            }
            t = logStudioPhase(request, "resolve_entry_anchor", t, openStart);

            updateStage(request, "load_entry_chunk", 0.80D);
            int entryChunkX = entryAnchor.getBlockX() >> 4;
            int entryChunkZ = entryAnchor.getBlockZ() >> 4;
            try {
                entryLoadFuture = loadEntryChunk(world, entryChunkX, entryChunkZ);
                entryLoads.register(request.worldName(), entryLoadFuture);
                entryLoadFuture.get(STUDIO_ENTRY_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Studio entry chunk did not load in time at "
                        + entryChunkX + "," + entryChunkZ + " — chunk system may be stalled.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Studio entry chunk load was interrupted at "
                        + entryChunkX + "," + entryChunkZ + ".", e);
            }
            t = logStudioPhase(request, "load_entry_chunk", t, openStart);

            updateStage(request, "resolve_safe_entry", 0.84D);
            Location safeEntry;
            try {
                safeEntry = WorldRuntimeControlService.get().resolveSafeEntry(world, entryAnchor)
                        .get(5L, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Studio entry point resolution timed out — region thread may be stalled.");
            }
            if (safeEntry == null) {
                throw new IllegalStateException("Studio entry point could not be resolved for world \"" + request.worldName() + "\".");
            }
            t = logStudioPhase(request, "resolve_safe_entry", t, openStart);

            if (request.openKind().teleportThroughStandardEntry()
                    && request.playerName() != null
                    && !request.playerName().isBlank()) {
                updateStage(request, "teleport_player", 0.96D);
                Player player = resolvePlayer(request.playerName());
                if (player == null) {
                    throw new IllegalStateException("Player \"" + request.playerName() + "\" is not online.");
                }

                Boolean teleported;
                try {
                    teleported = WorldRuntimeControlService.get().teleport(player, safeEntry).get(60L, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new IllegalStateException("Studio teleport timed out — destination region may still be generating.");
                }
                if (!Boolean.TRUE.equals(teleported)) {
                    throw new IllegalStateException("Studio teleport did not complete successfully.");
                }
                t = logStudioPhase(request, "teleport_standard_entry", t, openStart);
            }

            endStudioEntryBootstrap(world, provider);

            updateStage(request, "finalize_open", 1.00D);
            if (request.project() != null) {
                request.project().setActiveProvider(provider);
            }
            if (request.openKind().openWorkspace() && request.project() != null) {
                new IrisCodeWorkspace(request.project()).openVSCode(request.sender());
            }
            runOpenFinalizer(request.onDone(), world);
            t = logStudioPhase(request, "finalize_open", t, openStart);

            IrisLogging.info("Studio open: " + world.getName() + " ready in "
                    + elapsedMillis(openStart) + "ms");
            entryLoads.release(request.worldName(), entryLoadFuture);
            future.complete(new StudioOpenResult(world, safeEntry));
        } catch (Throwable e) {
            abandonStudioEntryBootstrap(world, e);
            IrisLogging.reportError("Studio open failed for world \"" + request.worldName() + "\".", e);
            if (!request.retainOnFailure()) {
                updateStage(request, "cleanup", 1.00D);
                if (LifecycleOperationCoordinator.get()
                        .active(LifecycleOperationCoordinator.Domain.SERVER_LIFECYCLE)
                        .isPresent()) {
                    deferFailedOpenCleanupToRestart(
                            provider,
                            request.worldName(),
                            world,
                            entryLoadFuture);
                } else if (requiresDeferredEntryCleanup(entryLoadFuture)) {
                    deferFailedOpenCleanup(
                            entryLoadFuture,
                            provider,
                            request.worldName(),
                            world,
                            request.project());
                } else {
                    try {
                        CompletableFuture<Void> cleanup = cleanupFailedOpen(
                                provider,
                                request.worldName(),
                                world,
                                request.project());
                        entryLoads.releaseAfterSuccessfulCompletion(
                                request.worldName(),
                                entryLoadFuture,
                                cleanup);
                        cleanup.get(45L, TimeUnit.SECONDS);
                    } catch (Throwable cleanupError) {
                        IrisLogging.reportError("Studio cleanup failed for world \""
                                + request.worldName() + "\".", unwrapFailure(cleanupError));
                    }
                }
            }
            future.completeExceptionally(e);
        }
    }

    private void deferFailedOpenCleanupToRestart(
            PlatformChunkGenerator provider,
            String worldName,
            World world,
            CompletableFuture<Void> entryLoadFuture
    ) {
        if (provider != null || world != null || transientWorldStorageExists(worldName)) {
            queueStartupCleanup(
                    worldName,
                    new IllegalStateException("Studio cleanup deferred across the queued server restart."));
        }
        entryLoads.release(worldName, entryLoadFuture);
    }

    private boolean transientWorldStorageExists(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        try {
            File root = IrisWorldStorage.requireSafeManagedDimensionRoot(
                    IrisWorldStorage.managedKeyFromName(worldName));
            return Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private long logStudioPhase(StudioOpenRequest request, String phase, long t, long openStart) {
        long now = System.nanoTime();
        IrisLogging.info("[Studio timing] world=%s kind=%s phase=%s duration=%dms cumulative=%dms",
                request.worldName(),
                request.openKind().name().toLowerCase(Locale.ROOT),
                phase,
                TimeUnit.NANOSECONDS.toMillis(now - t),
                TimeUnit.NANOSECONDS.toMillis(now - openStart));
        return now;
    }

    private void runOpenFinalizer(Consumer<World> finalizer, World world)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (finalizer == null) {
            return;
        }
        if (J.isPrimaryThread()) {
            finalizer.accept(world);
            return;
        }

        CompletableFuture<Void> completion = J.sfut(() -> finalizer.accept(world));
        completion.get(STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void logMeasuredStudioPhase(StudioOpenRequest request, String phase, long duration) {
        IrisLogging.info("[Studio timing] world=%s kind=%s phase=%s duration=%dms",
                request.worldName(),
                request.openKind().name().toLowerCase(Locale.ROOT),
                phase,
                duration);
    }

    private CompletableFuture<Void> loadEntryChunk(World world, int chunkX, int chunkZ) {
        if (!J.isFolia()) {
            return loadEntryChunkAsync(world, chunkX, chunkZ);
        }
        return scheduleEntryChunkRetention(world, chunkX, chunkZ);
    }

    private CompletableFuture<Void> loadEntryChunkAsync(World world, int chunkX, int chunkZ) {
        CompletableFuture<Chunk> requested;
        try {
            requested = WorldRuntimeControlService.get().requestChunkAsync(
                    world,
                    chunkX,
                    chunkZ,
                    true,
                    true);
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
        if (requested == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Entry-chunk async request did not return a future at " + chunkX + "," + chunkZ + "."));
        }
        return requested.thenCompose(chunk -> {
            if (chunk == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Entry-chunk async request returned no chunk at " + chunkX + "," + chunkZ + "."));
            }
            return scheduleEntryChunkRetention(world, chunkX, chunkZ);
        });
    }

    private CompletableFuture<Void> scheduleEntryChunkRetention(World world, int chunkX, int chunkZ) {
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        try {
            J.s(() -> retainAndConfirmEntryChunk(world, chunkX, chunkZ)
                    .whenComplete((ignored, throwable) -> complete(loaded, throwable)));
        } catch (Throwable throwable) {
            loaded.completeExceptionally(throwable);
        }
        return loaded;
    }

    private CompletableFuture<Void> retainAndConfirmEntryChunk(World world, int chunkX, int chunkZ) {
        CompletableFuture<Void> confirmed = new CompletableFuture<>();
        try {
            world.addPluginChunkTicket(
                    chunkX,
                    chunkZ,
                    BukkitPlatform.plugin());
        } catch (Throwable throwable) {
            confirmed.completeExceptionally(throwable);
            return confirmed;
        }
        if (!J.runRegion(world, chunkX, chunkZ, () -> confirmed.complete(null))) {
            confirmed.completeExceptionally(new IllegalStateException(
                    "Failed to confirm entry-chunk region at " + chunkX + "," + chunkZ + "."));
        }
        return confirmed;
    }

    private void complete(CompletableFuture<Void> target, Throwable throwable) {
        if (throwable == null) {
            target.complete(null);
            return;
        }
        target.completeExceptionally(throwable);
    }

    private void endStudioEntryBootstrap(World world, PlatformChunkGenerator provider) {
        if (!(provider instanceof BukkitChunkGenerator bukkitGenerator)) {
            throw new IllegalStateException("Studio runtime provider cannot finish its entry bootstrap.");
        }
        AtomicBoolean activationClaim = new AtomicBoolean(true);
        CompletableFuture<Void> activation = J.sfut(() -> {
            if (!activationClaim.compareAndSet(true, false)) {
                INMS.get().abandonStudioStructureBootstrap(world);
                return;
            }
            try {
                INMS.get().completeStudioStructureBootstrap(world);
                bukkitGenerator.endStudioEntryBootstrap();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Studio native structure state could not be activated after entry bootstrap.", e);
            }
        });
        try {
            activation.get(STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            activationClaim.compareAndSet(true, false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Studio native structure activation was interrupted.", e);
        } catch (ExecutionException e) {
            activationClaim.compareAndSet(true, false);
            throw new IllegalStateException("Studio native structure activation did not complete.",
                    unwrapFailure(e));
        } catch (TimeoutException e) {
            if (!activationClaim.compareAndSet(true, false)) {
                try {
                    activation.get(5L, TimeUnit.SECONDS);
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Studio native structure activation was interrupted.", interrupted);
                } catch (ExecutionException | TimeoutException settlementFailure) {
                    throw new IllegalStateException(
                            "Studio native structure activation did not settle after claiming completion.",
                            unwrapFailure(settlementFailure));
                }
            }
            throw new IllegalStateException("Studio native structure activation did not complete.", e);
        }
    }

    private void abandonStudioEntryBootstrap(World world, Throwable failure) {
        if (world == null) {
            return;
        }
        if (J.isPrimaryThread()) {
            try {
                INMS.get().abandonStudioStructureBootstrap(world);
            } catch (Throwable abandonmentFailure) {
                failure.addSuppressed(abandonmentFailure);
            }
            return;
        }
        CompletableFuture<Void> abandonment = J.sfut(
                () -> INMS.get().abandonStudioStructureBootstrap(world));
        try {
            abandonment.get(STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(new IllegalStateException(
                    "Studio native structure abandonment was interrupted.", e));
        } catch (ExecutionException | TimeoutException e) {
            failure.addSuppressed(new IllegalStateException(
                    "Studio native structure abandonment did not complete.", unwrapFailure(e)));
        }
    }

    private void deferFailedOpenCleanup(
            CompletableFuture<Void> entryLoadFuture,
            PlatformChunkGenerator provider,
            String worldName,
            World world,
            IrisProject project
    ) {
        CompletableFuture<Void> cleanup = deferCleanupUntilEntrySettlement(
                entryLoadFuture,
                () -> cleanupFailedOpen(provider, worldName, world, project));
        entryLoads.releaseAfterSuccessfulCompletion(worldName, entryLoadFuture, cleanup);
        cleanup.whenComplete((ignored, cleanupFailure) -> {
            if (cleanupFailure != null) {
                IrisLogging.reportError("Deferred Studio cleanup failed for world \""
                        + worldName + "\".", unwrapFailure(cleanupFailure));
            }
        });
        observeDeferredEntryCleanupBoundary(entryLoadFuture, worldName);
    }

    static CompletableFuture<Void> deferCleanupUntilEntrySettlement(
            CompletableFuture<?> entryLoadFuture,
            Supplier<CompletableFuture<Void>> cleanup
    ) {
        Objects.requireNonNull(entryLoadFuture, "Studio entry-load future");
        Objects.requireNonNull(cleanup, "Studio cleanup");
        return entryLoadFuture.handle((ignored, entryFailure) -> null)
                .thenCompose(ignored -> invokePhase(cleanup));
    }

    static boolean requiresDeferredEntryCleanup(CompletableFuture<?> entryLoadFuture) {
        return entryLoadFuture != null && !entryLoadFuture.isDone();
    }

    private CompletableFuture<Void> cleanupFailedOpen(
            PlatformChunkGenerator provider,
            String worldName,
            World world,
            IrisProject project
    ) {
        return closeWorldCoordinated(provider, worldName, world, true, project)
                .thenCompose(result -> result.failureCause() == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(result.failureCause()));
    }

    private void observeDeferredEntryCleanupBoundary(
            CompletableFuture<?> entryLoadFuture,
            String worldName
    ) {
        CompletableFuture.delayedExecutor(
                STUDIO_ENTRY_CLEANUP_BOUNDARY_SECONDS,
                TimeUnit.SECONDS).execute(() -> {
            if (entryLoadFuture.isDone()) {
                return;
            }
            TimeoutException timeout = new TimeoutException(
                    "Studio entry generation remained active for "
                            + STUDIO_ENTRY_CLEANUP_BOUNDARY_SECONDS
                            + " seconds after the open timeout for \"" + worldName + "\".");
            boolean queued = queueStartupCleanup(worldName, timeout);
            String recovery = queued
                    ? " The transient world is queued for deletion at the next clean startup."
                    : " The transient world could not be queued for startup deletion.";
            IrisLogging.reportError(
                    "Studio world \"" + worldName
                            + "\" remains loaded because its entry generation is still active;"
                            + " Iris did not unload or close its generator."
                            + recovery,
                    timeout);
        });
    }

    private CompletableFuture<StudioCloseResult> closeWorldCoordinated(
            PlatformChunkGenerator provider,
            String worldName,
            World world,
            boolean deleteFolder,
            IrisProject project
    ) {
        String operationTarget = worldName == null || worldName.isBlank() ? "unknown-studio-world" : worldName;
        LifecycleOperationCoordinator.Lease lease;
        try {
            lease = LifecycleOperationCoordinator.get().acquire(
                    LifecycleOperationCoordinator.Domain.WORLD_MUTATION,
                    LifecycleOperationCoordinator.OperationKind.STUDIO_CLOSE,
                    operationTarget
            );
        } catch (Throwable failure) {
            boolean queued = deleteFolder && queueStartupCleanup(worldName, failure);
            return CompletableFuture.completedFuture(new StudioCloseResult(
                    worldName,
                    false,
                    false,
                    queued,
                    failure
            ));
        }

        CompletableFuture<StudioCloseResult> closeFuture;
        try {
            closeFuture = closeWorldReserved(provider, worldName, world, deleteFolder, project);
        } catch (Throwable failure) {
            boolean queued = deleteFolder && queueStartupCleanup(worldName, failure);
            closeFuture = CompletableFuture.completedFuture(new StudioCloseResult(
                    worldName,
                    false,
                    false,
                    queued,
                    failure
            ));
        }
        return closeFuture.whenComplete((result, throwable) -> lease.close());
    }

    private CompletableFuture<StudioCloseResult> closeWorldReserved(
            PlatformChunkGenerator provider,
            String worldName,
            World world,
            boolean deleteFolder,
            IrisProject project
    ) {
        AtomicBoolean unloadConfirmed = new AtomicBoolean(false);
        AtomicBoolean folderDeleted = new AtomicBoolean(!deleteFolder);
        AtomicBoolean terminalTimeout = new AtomicBoolean(false);
        if (world != null) {
            IrisToolbelt.beginWorldMaintenance(world, "studio-close", true);
        }

        CompletableFuture<Void> sequence = sequenceStudioClose(
                () -> evacuateWorldFamily(worldName, world),
                () -> unloadWorldFamily(worldName, world).thenRun(() -> {
                    if (terminalTimeout.get()) {
                        throw new CompletionException(new TimeoutException(
                                "Studio close stopped after its terminal timeout."));
                    }
                    unloadConfirmed.set(true);
                    if (project != null) {
                        project.setActiveProvider(null);
                    }
                }),
                () -> provider == null ? CompletableFuture.completedFuture(null) : provider.closeAsync(),
                () -> deleteFolder
                        ? deleteWorldFamily(worldName).thenRun(() -> folderDeleted.set(true))
                        : CompletableFuture.completedFuture(null),
                terminalTimeout::get
        );
        CompletableFuture<StudioCloseResult> operation = guardCloseCompletion(
                sequence,
                terminalTimeout,
                worldName)
                .thenApply(ignored -> new StudioCloseResult(
                        worldName,
                        true,
                        folderDeleted.get(),
                        false,
                        null
                ))
                .exceptionally(throwable -> {
                    Throwable failure = unwrapFailure(throwable);
                    boolean queued = deleteFolder && queueStartupCleanup(worldName, failure);
                    return new StudioCloseResult(
                            worldName,
                            unloadConfirmed.get(),
                            folderDeleted.get(),
                            queued,
                            failure
                    );
                });
        return operation.whenComplete((result, throwable) -> {
            if (world != null) {
                IrisToolbelt.endWorldMaintenance(world, "studio-close", true);
            }
        });
    }

    static CompletableFuture<Void> sequenceStudioClose(
            Supplier<CompletableFuture<Void>> evacuate,
            Supplier<CompletableFuture<Void>> unload,
            Supplier<CompletableFuture<Void>> closeGenerator,
            Supplier<CompletableFuture<Void>> deleteFolders
    ) {
        return sequenceStudioClose(evacuate, unload, closeGenerator, deleteFolders, () -> false);
    }

    static CompletableFuture<Void> sequenceStudioClose(
            Supplier<CompletableFuture<Void>> evacuate,
            Supplier<CompletableFuture<Void>> unload,
            Supplier<CompletableFuture<Void>> closeGenerator,
            Supplier<CompletableFuture<Void>> deleteFolders,
            BooleanSupplier terminalTimeout
    ) {
        return invokePhase(evacuate)
                .thenCompose(ignored -> invokePhaseUnlessTimedOut(unload, terminalTimeout))
                .thenCompose(ignored -> invokePhaseUnlessTimedOut(closeGenerator, terminalTimeout))
                .thenCompose(ignored -> invokePhaseUnlessTimedOut(deleteFolders, terminalTimeout));
    }

    private static CompletableFuture<Void> invokePhase(Supplier<CompletableFuture<Void>> phase) {
        try {
            CompletableFuture<Void> future = phase.get();
            if (future == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Studio close phase returned no completion future."));
            }
            return future;
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static CompletableFuture<Void> invokePhaseUnlessTimedOut(
            Supplier<CompletableFuture<Void>> phase,
            BooleanSupplier terminalTimeout
    ) {
        if (terminalTimeout.getAsBoolean()) {
            return CompletableFuture.failedFuture(new TimeoutException(
                    "Studio close stopped after its terminal timeout."));
        }
        return invokePhase(phase);
    }

    private CompletableFuture<Void> guardCloseCompletion(
            CompletableFuture<Void> source,
            AtomicBoolean terminalTimeout,
            String worldName
    ) {
        CompletableFuture<Void> guarded = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        source.whenComplete((ignored, throwable) -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            if (throwable == null) {
                guarded.complete(null);
            } else {
                guarded.completeExceptionally(throwable);
            }
        });
        CompletableFuture.delayedExecutor(STUDIO_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            terminalTimeout.set(true);
            TimeoutException timeout = new TimeoutException(
                    "Studio close did not settle within " + STUDIO_CLOSE_TIMEOUT_SECONDS
                            + " seconds for \"" + worldName + "\".");
            ServerConfigurator.restart("Studio close timed out for \"" + worldName + "\".");
            guarded.completeExceptionally(timeout);
        });
        return guarded;
    }

    private CompletableFuture<Void> evacuateWorldFamily(String worldName, World primaryWorld) {
        List<World> loadedWorlds = loadedWorldFamily(worldName, primaryWorld);
        if (loadedWorlds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        ArrayList<CompletableFuture<Void>> evacuations = new ArrayList<>(loadedWorlds.size());
        for (World loadedWorld : loadedWorlds) {
            CompletableFuture<Void> evacuation = J.sfut(() -> IrisToolbelt.evacuateAsync(loadedWorld))
                    .thenCompose(evacuationFuture -> evacuationFuture)
                    .thenCompose(evacuated -> Boolean.TRUE.equals(evacuated)
                            ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(new IllegalStateException(
                                    "Studio player evacuation failed for \"" + loadedWorld.getName() + "\".")));
            evacuations.add(evacuation);
        }
        return CompletableFuture.allOf(evacuations.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> unloadWorldFamily(String worldName, World primaryWorld) {
        List<World> loadedWorlds = loadedWorldFamily(worldName, primaryWorld);
        ArrayList<CompletableFuture<Boolean>> unloads = new ArrayList<>(loadedWorlds.size());
        for (World loadedWorld : loadedWorlds) {
            CompletableFuture<Boolean> unload = J.sfut(() ->
                            IrisServices.get(MultiverseCoreLink.class)
                                    .removeIfPresent(loadedWorld))
                    .thenCompose(ignored -> WorldLifecycleService.get().unloadAsync(loadedWorld, false));
            unloads.add(unload);
        }

        return CompletableFuture.allOf(unloads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            for (CompletableFuture<Boolean> unload : unloads) {
                if (!Boolean.TRUE.equals(unload.join())) {
                    throw new CompletionException(new IllegalStateException(
                            "Studio world family unload returned false for \"" + worldName + "\"."));
                }
            }
            if (isWorldFamilyLoaded(worldName)) {
                throw new CompletionException(new IllegalStateException(
                        "Studio world family remained loaded after confirmed unload for \"" + worldName + "\"."));
            }
            return null;
        });
    }

    private List<World> loadedWorldFamily(String worldName, World primaryWorld) {
        LinkedHashSet<World> worlds = new LinkedHashSet<>();
        if (primaryWorld != null) {
            worlds.add(primaryWorld);
        }
        if (worldName == null || worldName.isBlank()) {
            return List.copyOf(worlds);
        }
        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            WorldIdentity.resolve(IrisWorldStorage.keyFromName(familyWorldName)).ifPresent(worlds::add);
        }
        return List.copyOf(worlds);
    }

    private CompletableFuture<Void> deleteWorldFamily(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (isWorldFamilyLoaded(worldName)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Refusing to delete a loaded studio world family for \"" + worldName + "\"."));
        }

        return CompletableFuture.runAsync(() -> {
            for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
                try {
                    if (isWorldFamilyLoaded(worldName)) {
                        throw new IOException("Studio world family became loaded before deletion for \""
                                + worldName + "\".");
                    }
                    File folder = IrisWorldStorage.requireSafeManagedDimensionRoot(
                            IrisWorldStorage.managedKeyFromName(familyWorldName));
                    AtomicDirectoryPublisher.deleteTree(folder.toPath());
                } catch (IOException | IllegalArgumentException failure) {
                    throw new CompletionException(failure);
                }
            }
        });
    }

    private boolean queueStartupCleanup(String worldName, Throwable failure) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        try {
            IrisServices.get(WorldDeletionQueue.class).queueFamilyForStartupDeletion(Collections.singleton(worldName));
            return true;
        } catch (Throwable queueFailure) {
            if (failure != null) {
                failure.addSuppressed(queueFailure);
            }
            IrisLogging.reportError("Failed to queue deferred deletion for world \"" + worldName + "\".", queueFailure);
            return false;
        }
    }

    private void cleanupStaleTransientWorlds(String worldName) {
        LinkedHashSet<String> staleWorldNames = collectSafeTransientWorldNames();
        String requestedBaseName = TransientWorldCleanupSupport.transientStudioBaseWorldName(worldName);
        if (requestedBaseName != null) {
            staleWorldNames.add(requestedBaseName);
        }

        for (String staleWorldName : staleWorldNames) {
            if (entryLoads.isFenced(staleWorldName)) {
                IrisLogging.warn("Skipping stale Studio cleanup for \"" + staleWorldName
                        + "\" because its open or deferred cleanup is still active.");
                continue;
            }
            try {
                StudioCloseResult cleanupResult = closeWorldCoordinated(
                        null,
                        staleWorldName,
                        null,
                        true,
                        null
                ).get(30L, TimeUnit.SECONDS);
                if (cleanupResult.failureCause() != null) {
                    IrisLogging.reportError("Stale studio world cleanup failed for \"" + staleWorldName + "\".", cleanupResult.failureCause());
                }
            } catch (Throwable failure) {
                IrisLogging.reportError("Stale studio world cleanup failed for \"" + staleWorldName + "\".", unwrapFailure(failure));
            }
        }
    }

    private LinkedHashSet<String> collectSafeTransientWorldNames() {
        LinkedHashSet<String> worldNames = new LinkedHashSet<>();
        Path irisNamespace = IrisWorldStorage.levelRoot()
                .toPath()
                .toAbsolutePath()
                .normalize()
                .resolve("dimensions")
                .resolve("iris");
        if (!Files.exists(irisNamespace, LinkOption.NOFOLLOW_LINKS)) {
            return worldNames;
        }
        if (Files.isSymbolicLink(irisNamespace) || !Files.isDirectory(irisNamespace, LinkOption.NOFOLLOW_LINKS)) {
            IrisLogging.warn("Skipping stale studio cleanup because Iris dimension storage is unsafe: " + irisNamespace);
            return worldNames;
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(irisNamespace)) {
            for (Path child : children) {
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String transientName = TransientWorldCleanupSupport.transientStudioBaseWorldName(
                        child.getFileName().toString());
                if (transientName != null) {
                    worldNames.add(transientName);
                }
            }
        } catch (IOException failure) {
            IrisLogging.reportError("Failed to inspect stale studio worlds in \"" + irisNamespace + "\".", failure);
        }
        return worldNames;
    }

    private void updateStage(StudioOpenRequest request, String stage, double progress) {
        if (request.progressConsumer() != null) {
            request.progressConsumer().accept(new StudioOpenProgress(progress, stage));
        }
    }

    private String mapCreatorStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "create_world";
        }

        String normalized = stage.trim().toLowerCase();
        return switch (normalized) {
            case "resolve_dimension", "resolving dimension" -> "resolve_dimension";
            case "prepare_world_pack", "preparing world pack" -> "prepare_world_pack";
            case "install_datapacks", "installing datapacks", "datapacks ready" -> "install_datapacks";
            case "create_world", "creating world", "world created" -> "create_world";
            default -> normalized.replace(' ', '_');
        };
    }

    private Throwable unwrapFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor instanceof CompletionException || cursor instanceof ExecutionException) {
            if (cursor.getCause() == null) {
                break;
            }

            cursor = cursor.getCause();
        }

        return cursor;
    }

    private Player resolvePlayer(String playerName) {
        Player exact = Bukkit.getPlayerExact(playerName);
        if (exact != null) {
            return exact;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }

        return null;
    }

    private boolean isWorldFamilyLoaded(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            if (WorldIdentity.resolve(IrisWorldStorage.keyFromName(familyWorldName)).isPresent()) {
                return true;
            }
        }

        return false;
    }

    public record StudioOpenRequest(
            String dimensionKey,
            IrisProject project,
            VolmitSender sender,
            long seed,
            String worldName,
            String playerName,
            StudioOpenKind openKind,
            boolean retainOnFailure,
            Consumer<StudioOpenProgress> progressConsumer,
            Consumer<World> onDone
    ) {
        public StudioOpenRequest {
            openKind = Objects.requireNonNull(openKind, "Studio open kind");
        }

        public static StudioOpenRequest studioProject(
                IrisProject project,
                VolmitSender sender,
                long seed,
                StudioOpenKind openKind,
                Consumer<StudioOpenProgress> progressConsumer,
                Consumer<World> onDone
        ) {
            String playerName = sender != null && sender.isPlayer() && sender.player() != null ? sender.player().getName() : null;
            return new StudioOpenRequest(
                    project.getName(),
                    project,
                    sender,
                    seed,
                    "iris-" + UUID.randomUUID(),
                    playerName,
                    openKind,
                    false,
                    progressConsumer,
                    onDone
            );
        }
    }

    public enum StudioOpenKind {
        STANDARD(
                true,
                true,
                IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY),
        JIGSAW(
                false,
                false,
                IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY);

        private final boolean teleportThroughStandardEntry;
        private final boolean openWorkspace;
        private final IrisCreator.DatapackPreparation datapackPreparation;

        StudioOpenKind(
                boolean teleportThroughStandardEntry,
                boolean openWorkspace,
                IrisCreator.DatapackPreparation datapackPreparation
        ) {
            this.teleportThroughStandardEntry = teleportThroughStandardEntry;
            this.openWorkspace = openWorkspace;
            this.datapackPreparation = Objects.requireNonNull(
                    datapackPreparation,
                    "Studio datapack preparation");
        }

        public boolean teleportThroughStandardEntry() {
            return teleportThroughStandardEntry;
        }

        public boolean openWorkspace() {
            return openWorkspace;
        }

        public boolean prepareGeneratorState() {
            return this == STANDARD;
        }

        public IrisCreator.DatapackPreparation datapackPreparation() {
            return datapackPreparation;
        }
    }

    public record StudioOpenProgress(double progress, String stage) {
    }

    public record StudioOpenResult(World world, Location entryLocation) {
    }

    public record StudioCloseResult(
            String worldName,
            boolean unloadCompletedLive,
            boolean folderDeletionCompletedLive,
            boolean startupCleanupQueued,
            Throwable failureCause
    ) {
        public boolean successful() {
            return failureCause == null;
        }
    }

    static final class EntryLoadRegistry {
        private final ConcurrentHashMap<String, CompletableFuture<?>> entryLoads;

        EntryLoadRegistry() {
            entryLoads = new ConcurrentHashMap<>();
        }

        void register(String worldName, CompletableFuture<?> entryLoadFuture) {
            Objects.requireNonNull(worldName, "Studio world name");
            Objects.requireNonNull(entryLoadFuture, "Studio entry-load future");
            CompletableFuture<?> existing = entryLoads.putIfAbsent(worldName, entryLoadFuture);
            if (existing != null) {
                throw new IllegalStateException("Studio open or deferred cleanup is already active for \""
                        + worldName + "\".");
            }
        }

        void release(
                String worldName,
                CompletableFuture<?> entryLoadFuture
        ) {
            if (worldName == null || entryLoadFuture == null) {
                return;
            }
            entryLoads.remove(worldName, entryLoadFuture);
        }

        void releaseAfterSuccessfulCompletion(
                String worldName,
                CompletableFuture<?> entryLoadFuture,
                CompletableFuture<?> completion
        ) {
            Objects.requireNonNull(completion, "Studio lifecycle completion");
            completion.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    release(worldName, entryLoadFuture);
                }
            });
        }

        void rejectNewOpen() {
            ArrayList<String> activeWorlds = new ArrayList<>();
            for (Map.Entry<String, CompletableFuture<?>> entry : entryLoads.entrySet()) {
                activeWorlds.add(entry.getKey());
            }
            if (activeWorlds.isEmpty()) {
                return;
            }
            Collections.sort(activeWorlds);
            throw new IllegalStateException("A previous Studio open or deferred cleanup is still active for "
                    + String.join(", ", activeWorlds)
                    + ". Wait for it to settle before opening another Studio; a reported cleanup failure"
                    + " requires the queued clean restart.");
        }

        boolean isFenced(String worldName) {
            return entryLoads.containsKey(worldName);
        }
    }
}

package art.arcane.iris.core.runtime;

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.link.MultiverseCoreLink;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.pack.AtomicDirectoryPublisher;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.project.IrisCodeWorkspace;
import art.arcane.iris.core.tools.IrisCreator;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.Bukkit;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StudioOpenCoordinator {
    private static final long STUDIO_CLOSE_TIMEOUT_SECONDS = 120L;
    private static volatile StudioOpenCoordinator instance;

    private StudioOpenCoordinator() {
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
        try {
            long openStart = System.currentTimeMillis();
            long t = openStart;
            IrisLogging.debug("[Studio timing] ===== studio open START: " + request.worldName() + " =====");
            updateStage(request, "resolve_dimension", 0.04D);
            if (IrisToolbelt.getDimension(request.dimensionKey()) == null) {
                throw new IrisException("Dimension cannot be found for id " + request.dimensionKey() + ".");
            }

            updateStage(request, "prepare_world_pack", 0.10D);
            cleanupStaleTransientWorlds(request.worldName());
            t = logStudioPhase("resolveDimension + cleanupStaleWorlds", t, openStart);

            updateStage(request, "install_datapacks", 0.18D);
            IrisCreator creator = IrisToolbelt.createWorld()
                    .seed(request.seed())
                    .sender(request.sender())
                    .studio(true)
                    .name(request.worldName())
                    .dimension(request.dimensionKey())
                    .studioProgressConsumer((progress, stage) -> updateStage(request, mapCreatorStage(stage), progress));
            world = creator.create();
            t = logStudioPhase("createWorld (datapacks + bukkit world + engine setup)", t, openStart);
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
            t = logStudioPhase("applyStudioWorldRules", t, openStart);

            updateStage(request, "prepare_generator", 0.78D);
            WorldRuntimeControlService.get().prepareGenerator(world);
            t = logStudioPhase("prepareGenerator", t, openStart);

            Location entryAnchor = WorldRuntimeControlService.get().resolveEntryAnchor(world);
            if (entryAnchor == null) {
                throw new IllegalStateException("Studio entry anchor could not be resolved.");
            }
            t = logStudioPhase("resolveEntryAnchor", t, openStart);

            updateStage(request, "load_entry_chunk", 0.80D);
            int entryChunkX = entryAnchor.getBlockX() >> 4;
            int entryChunkZ = entryAnchor.getBlockZ() >> 4;
            try {
                loadEntryChunk(world, entryChunkX, entryChunkZ).get(30L, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Studio entry chunk did not load in time at "
                        + entryChunkX + "," + entryChunkZ + " — chunk system may be stalled.");
            }
            t = logStudioPhase("loadEntryChunk (generate spawn chunk to FULL)", t, openStart);

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
            t = logStudioPhase("resolveSafeEntry (generates/loads spawn chunk to FULL)", t, openStart);

            if (request.playerName() != null && !request.playerName().isBlank()) {
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
                t = logStudioPhase("teleportPlayer", t, openStart);
            }

            updateStage(request, "finalize_open", 1.00D);
            if (request.project() != null) {
                request.project().setActiveProvider(provider);
            }
            if (request.openWorkspace() && request.project() != null) {
                new IrisCodeWorkspace(request.project()).openVSCode(request.sender());
            }
            if (request.onDone() != null) {
                request.onDone().accept(world);
            }
            t = logStudioPhase("finalize + openVSCode", t, openStart);

            IrisLogging.info("Studio open: " + world.getName() + " ready in " + (System.currentTimeMillis() - openStart) + "ms");
            future.complete(new StudioOpenResult(world, safeEntry));
        } catch (Throwable e) {
            IrisLogging.reportError("Studio open failed for world \"" + request.worldName() + "\".", e);
            if (!request.retainOnFailure()) {
                try {
                    updateStage(request, "cleanup", 1.00D);
                    StudioCloseResult cleanupResult = closeWorldCoordinated(
                            provider,
                            request.worldName(),
                            world,
                            true,
                            request.project()
                    ).get(45L, TimeUnit.SECONDS);
                    if (cleanupResult.failureCause() != null) {
                        throw cleanupResult.failureCause();
                    }
                } catch (Throwable cleanupError) {
                    IrisLogging.reportError("Studio cleanup failed for world \"" + request.worldName() + "\".", cleanupError);
                }
            }
            future.completeExceptionally(e);
        }
    }

    private long logStudioPhase(String phase, long t, long openStart) {
        long now = System.currentTimeMillis();
        IrisLogging.debug("[Studio timing] " + phase + " = " + (now - t) + "ms  (cumulative " + (now - openStart) + "ms)");
        return now;
    }

    private CompletableFuture<Void> loadEntryChunk(World world, int chunkX, int chunkZ) {
        // A freshly created studio world has no ticking region at the entry
        // chunk. On Folia getChunkAtAsync only works from the owning region
        // thread, and RegionScheduler.execute never fires for a chunk no region
        // owns yet — which is why resolveSafeEntry (a region task) would stall
        // and time out. A plugin chunk ticket force-loads the chunk and creates
        // its ticking region; we then confirm via a region task that the region
        // is live before resolving the safe entry / teleporting into it.
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        J.s(() -> {
            try {
                world.addPluginChunkTicket(chunkX, chunkZ, art.arcane.iris.platform.bukkit.BukkitPlatform.plugin());
            } catch (Throwable t) {
                loaded.completeExceptionally(t);
                return;
            }

            if (!J.runRegion(world, chunkX, chunkZ, () -> loaded.complete(null))) {
                loaded.completeExceptionally(new IllegalStateException(
                        "Failed to confirm entry-chunk region at " + chunkX + "," + chunkZ + "."));
            }
        });
        return loaded;
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
                IrisToolbelt.endWorldMaintenance(world, "studio-close");
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
                                    .removeFromConfig(loadedWorld))
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
            boolean openWorkspace,
            boolean retainOnFailure,
            Consumer<StudioOpenProgress> progressConsumer,
            Consumer<World> onDone
    ) {
        public static StudioOpenRequest studioProject(IrisProject project, VolmitSender sender, long seed, Consumer<StudioOpenProgress> progressConsumer, Consumer<World> onDone) {
            String playerName = sender != null && sender.isPlayer() && sender.player() != null ? sender.player().getName() : null;
            return new StudioOpenRequest(
                    project.getName(),
                    project,
                    sender,
                    seed,
                    "iris-" + UUID.randomUUID(),
                    playerName,
                    true,
                    false,
                    progressConsumer,
                    onDone
            );
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
}

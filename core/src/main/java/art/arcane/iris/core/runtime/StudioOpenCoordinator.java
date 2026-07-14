package art.arcane.iris.core.runtime;

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.tools.IrisCreator;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.io.IO;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class StudioOpenCoordinator {
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
        CompletableFuture<StudioCloseResult> future = new CompletableFuture<>();
        J.aBukkit(() -> future.complete(executeClose(project)));
        return future;
    }

    private StudioCloseResult executeClose(IrisProject project) {
        if (project == null) {
            return new StudioCloseResult(null, true, true, false, null);
        }

        PlatformChunkGenerator provider = project.getActiveProvider();
        if (provider == null) {
            return new StudioCloseResult(null, true, true, false, null);
        }

        World world = BukkitWorldBinding.world(provider.getTarget().getWorld());
        String worldName = world == null ? provider.getTarget().getWorld().name() : world.getName();
        try {
            return closeWorld(provider, worldName, world, true, project);
        } catch (Throwable e) {
            project.setActiveProvider(null);
            return new StudioCloseResult(worldName, false, false, false, e);
        }
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
                request.project().openVSCode(request.sender());
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
                    closeWorld(provider, request.worldName(), world, true, request.project());
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

    private StudioCloseResult closeWorld(
            PlatformChunkGenerator provider,
            String worldName,
            World world,
            boolean deleteFolder,
            IrisProject project
    ) {
        Throwable failure = null;
        boolean unloadCompletedLive = world == null || !isWorldFamilyLoaded(worldName);
        boolean folderDeletionCompletedLive = !deleteFolder;
        boolean startupCleanupQueued = false;
        CompletableFuture<Void> closeFuture = CompletableFuture.completedFuture(null);

        if (world != null) {
            try {
                evacuatePlayers(world);
            } catch (Throwable e) {
                failure = e;
            }
        }

        if (world != null) {
            IrisToolbelt.beginWorldMaintenance(world, "studio-close", true);
        }

        try {
            if (project != null) {
                project.setActiveProvider(null);
            }
            if (provider != null) {
                closeFuture = provider.closeAsync();
            }

            if (worldName != null && !worldName.isBlank()) {
                requestWorldFamilyUnload(worldName);
            }

            if (worldName != null && !worldName.isBlank()) {
                long unloadDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20L);
                CompletableFuture<Void> unloadFuture = waitForWorldFamilyUnload(worldName, unloadDeadline);
                try {
                    unloadFuture.get(Math.max(1000L, unloadDeadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    unloadCompletedLive = true;
                } catch (TimeoutException e) {
                    unloadCompletedLive = !isWorldFamilyLoaded(worldName);
                } catch (Throwable e) {
                    failure = failure == null ? unwrapFailure(e) : failure;
                }
            }

            try {
                closeFuture.get(20L, TimeUnit.SECONDS);
            } catch (Throwable e) {
                Throwable cause = unwrapFailure(e);
                if (failure == null) {
                    failure = cause;
                }
            }

            if (deleteFolder && worldName != null && !worldName.isBlank()) {
                WorldFamilyDeleteResult deleteResult = deleteWorldFamily(worldName, unloadCompletedLive);
                folderDeletionCompletedLive = deleteResult.liveDeleted();
                startupCleanupQueued = deleteResult.startupCleanupQueued();
            }
        } finally {
            if (world != null) {
                IrisToolbelt.endWorldMaintenance(world, "studio-close");
            }
        }

        return new StudioCloseResult(worldName, unloadCompletedLive, folderDeletionCompletedLive, startupCleanupQueued, failure);
    }

    private void evacuatePlayers(World world) throws Exception {
        if (world == null) {
            return;
        }

        CompletableFuture<Void> future = J.sfut(() -> {
            IrisToolbelt.evacuate(world);
            return null;
        });
        if (future != null) {
            future.get(10L, TimeUnit.SECONDS);
        }
    }

    private void requestWorldFamilyUnload(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }

        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            World familyWorld = WorldIdentity.resolve(IrisWorldStorage.keyFromLegacyName(familyWorldName)).orElse(null);
            if (familyWorld == null) {
                continue;
            }

            IrisServices.get(art.arcane.iris.core.link.MultiverseCoreLink.class).removeFromConfig(familyWorld);
            WorldLifecycleService.get().unload(familyWorld, false);
        }
    }

    private WorldFamilyDeleteResult deleteWorldFamily(String worldName, boolean unloadCompletedLive) {
        if (worldName == null || worldName.isBlank()) {
            return new WorldFamilyDeleteResult(true, false);
        }

        boolean liveDeleted = true;
        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            File folder = IrisWorldStorage.dimensionRoot(familyWorldName);
            if (!folder.exists()) {
                continue;
            }

            try {
                deleteWorldFolderAsync(folder, 40).get(15L, TimeUnit.SECONDS);
            } catch (Throwable e) {
                liveDeleted = false;
                IrisLogging.reportError("Studio folder deletion retries failed for \"" + folder.getAbsolutePath() + "\".", unwrapFailure(e));
            }

            if (folder.exists()) {
                liveDeleted = false;
            }
        }

        if (liveDeleted) {
            return new WorldFamilyDeleteResult(true, false);
        }

        try {
            IrisServices.get(WorldDeletionQueue.class).queueForStartupDeletion(Collections.singleton(worldName));
            return new WorldFamilyDeleteResult(false, true);
        } catch (IOException e) {
            if (unloadCompletedLive) {
                IrisLogging.reportError("Failed to queue deferred deletion for world \"" + worldName + "\".", e);
            }
            return new WorldFamilyDeleteResult(false, false);
        }
    }

    private void cleanupStaleTransientWorlds(String worldName) {
        LinkedHashSet<String> staleWorldNames = TransientWorldCleanupSupport.collectTransientStudioWorldNames(IrisWorldStorage.levelRoot());
        String requestedBaseName = TransientWorldCleanupSupport.transientStudioBaseWorldName(worldName);
        if (requestedBaseName != null) {
            staleWorldNames.add(requestedBaseName);
        }

        for (String staleWorldName : staleWorldNames) {
            if (WorldIdentity.resolve(IrisWorldStorage.keyFromLegacyName(staleWorldName)).isPresent()) {
                continue;
            }

            deleteWorldFamily(staleWorldName, true);
        }
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

    private CompletableFuture<Void> waitForWorldFamilyUnload(String worldName, long deadline) {
        if (worldName == null || !isWorldFamilyLoaded(worldName) || System.currentTimeMillis() >= deadline) {
            return CompletableFuture.completedFuture(null);
        }

        return delayFuture(100L).thenCompose(ignored -> waitForWorldFamilyUnload(worldName, deadline));
    }

    private CompletableFuture<Void> deleteWorldFolderAsync(File folder, int attemptsRemaining) {
        if (folder == null || !folder.exists()) {
            return CompletableFuture.completedFuture(null);
        }

        IO.delete(folder);
        if (!folder.exists()) {
            return CompletableFuture.completedFuture(null);
        }

        if (attemptsRemaining <= 1) {
            return CompletableFuture.failedFuture(new IllegalStateException("World folder still exists after deletion retries: " + folder.getAbsolutePath()));
        }

        return delayFuture(250L).thenCompose(ignored -> deleteWorldFolderAsync(folder, attemptsRemaining - 1));
    }

    private CompletableFuture<Void> delayFuture(long delayMillis) {
        long safeDelay = Math.max(0L, delayMillis);
        return CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(safeDelay, TimeUnit.MILLISECONDS));
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
            if (WorldIdentity.resolve(IrisWorldStorage.keyFromLegacyName(familyWorldName)).isPresent()) {
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

    private record WorldFamilyDeleteResult(boolean liveDeleted, boolean startupCleanupQueued) {
    }
}

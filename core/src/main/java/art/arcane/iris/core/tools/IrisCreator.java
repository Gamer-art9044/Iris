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

package art.arcane.iris.core.tools;

import art.arcane.iris.core.runtime.TransientWorldCleanupSupport;
import art.arcane.iris.core.runtime.WorldRuntimeControlService;
import com.google.common.util.concurrent.AtomicDouble;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.link.MultiverseCoreLink;
import art.arcane.iris.core.IrisRuntimeSchedulerMode;
import art.arcane.iris.core.IrisStartupValidation;
import art.arcane.iris.core.DatapackInstallResult;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.lifecycle.WorldLifecycleCaller;
import art.arcane.iris.core.lifecycle.WorldLifecycleRequest;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeProgressMessages;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.pack.AtomicDirectoryPublisher;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.runtime.WorldDeletionQueue;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSlotClaim;
import art.arcane.volmlib.util.hud.HudSlotRequest;
import art.arcane.volmlib.util.hud.HudSurface;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import lombok.Data;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static art.arcane.iris.util.common.misc.ServerProperties.BUKKIT_YML;

/**
 * Makes it a lot easier to setup an engine, world, studio or whatever
 */
@Data
@Accessors(fluent = true, chain = true)
public class IrisCreator {
    private static final long WORLD_CREATE_TIMEOUT_SECONDS = 120L;
    private static final long ROLLBACK_PHASE_TIMEOUT_SECONDS = 120L;

    /**
     * Specify an area to pregenerate during creation
     */
    private PregenTask pregen;
    /**
     * Specify a sender to get updates & progress info + tp when world is created.
     */
    private VolmitSender sender;
    /**
     * The seed to use for this generator
     */
    private long seed = 1337;
    /**
     * The dimension to use. This can be any online dimension, or a dimension in the
     * packs folder
     */
    private String dimension = IrisSettings.get().getGenerator().getDefaultWorldType();
    /**
     * The name of this world.
     */
    private String name = "irisworld";
    /**
     * Studio mode makes the engine hotloadable and uses the dimension in
     * your Iris/packs folder instead of copying the dimension files into
     * the world itself. Studio worlds are deleted when they are unloaded.
     */
    private boolean studio = false;
    /**
     * Benchmark mode
     */
    private boolean benchmark = false;
    private BiConsumer<Double, String> studioProgressConsumer;
    private BiConsumer<String, Long> studioTimingConsumer;
    private DatapackPreparation datapackPreparation = DatapackPreparation.INSTALL_IF_CHANGED;

    public static boolean removeFromBukkitYml(String name) throws IOException {
        return BukkitWorldConfiguration.remove(BUKKIT_YML, name);
    }

    public static int removeTransientStudioWorldsFromBukkitYml() throws IOException {
        return BukkitWorldConfiguration.removeMatching(
                BUKKIT_YML,
                TransientWorldCleanupSupport::isTransientStudioWorldName);
    }
    public static boolean worldLoaded(){
        return true;
    }

    /**
     * Create the IrisAccess (contains the world)
     *
     * @return the IrisAccess
     * @throws IrisException shit happens
     */

    public World create() throws IrisException {
        if (Bukkit.isPrimaryThread()) {
            throw new IrisException("You cannot invoke create() on the main thread.");
        }
        NamespacedKey worldKey;
        try {
            worldKey = IrisWorldStorage.managedKeyFromName(name);
        } catch (IllegalArgumentException e) {
            throw new IrisException(e.getMessage(), e);
        }
        name = IrisWorldStorage.logicalName(worldKey);

        LifecycleOperationCoordinator coordinator = LifecycleOperationCoordinator.get();
        LifecycleOperationCoordinator.Lease worldLease = null;
        try {
            reportStudioProgress(0.02D, "resolve_dimension");
            IrisDimension resolvedDimension = IrisToolbelt.getDimension(dimension());
            if (resolvedDimension == null) {
                throw new IrisException("Dimension cannot be found for id " + dimension());
            }
            IrisStartupValidation.requireWorldCreationReady();
            PackValidationRegistry.requireLoadable(
                    resolvedDimension.getLoader().getDataFolder().getName());
            worldLease = coordinator.acquire(
                    LifecycleOperationCoordinator.Domain.WORLD_MUTATION,
                    LifecycleOperationCoordinator.OperationKind.WORLD_CREATE,
                    worldKey.toString());
            return createReserved(worldKey, resolvedDimension);
        } catch (LifecycleOperationCoordinator.BusyException e) {
            throw new IrisException(e.getMessage(), e);
        } finally {
            if (worldLease != null) {
                worldLease.close();
            }
        }
    }

    private World createReserved(NamespacedKey worldKey, IrisDimension resolvedDimension) throws IrisException {
        File dimensionRoot;
        try {
            dimensionRoot = IrisWorldStorage.requireSafeManagedDimensionRoot(worldKey);
        } catch (IllegalArgumentException e) {
            throw new IrisException(e.getMessage(), e);
        }
        if (Files.exists(dimensionRoot.toPath()) || WorldIdentity.resolve(worldKey).isPresent()) {
            throw new IrisException("World \"" + name + "\" already exists or is loaded.");
        }
        if (sender == null) {
            sender = BukkitPlatform.console();
        }

        World world = null;
        boolean bukkitRegistered = false;
        try {
            reportStudioProgress(0.08D, "resolve_dimension");
            reportStudioProgress(0.16D, "prepare_world_pack");
            DatapackInstallResult datapackResult = prepareDatapacks(resolvedDimension);
            if (!datapackResult.succeeded()) {
                throw new IrisException("Failed to compile datapacks for dimension \"" + dimension() + "\".");
            }
            if (datapackResult.restartRequired() || !ServerConfigurator.verifyDataPackInstalled(resolvedDimension)) {
                ServerConfigurator.restart();
                throw new IrisException("The dimension types for pack \"" + dimension() + "\" are not loaded yet. "
                        + "Iris queued a restart; run the command again after the server returns.");
            }

            IrisDimension installedDimension = resolvedDimension;
            if (!studio() || benchmark) {
                installedDimension = IrisServices.get(StudioSVC.class)
                        .installIntoWorld(sender, resolvedDimension, dimensionRoot);
                if (installedDimension == null) {
                    throw new IrisException("Failed to install dimension pack for " + dimension());
                }
                dimension = installedDimension.getLoadKey();
            }
            if (studio()) {
                IrisRuntimeSchedulerMode runtimeSchedulerMode = IrisRuntimeSchedulerMode.resolve(IrisSettings.get().getPregen());
                IrisLogging.debug("Studio create scheduling: mode=" + runtimeSchedulerMode.name().toLowerCase(Locale.ROOT)
                        + ", regionizedRuntime=" + FoliaScheduler.isRegionizedRuntime(Bukkit.getServer()));
            }

            reportStudioProgress(0.28D, "install_datapacks");
            AtomicDouble pp = new AtomicDouble(0);
            AtomicBoolean done = new AtomicBoolean(false);
            long generatorPrepareStart = System.nanoTime();
            WorldCreator wc = new IrisWorldCreator()
                    .dimension(installedDimension)
                    .name(name)
                    .seed(seed)
                    .studio(studio)
                    .create();
            reportStudioTiming("prepare_studio_generator", generatorPrepareStart);
            reportStudioProgress(0.40D, "install_datapacks");

            PlatformChunkGenerator access = (PlatformChunkGenerator) wc.generator();
            if (access == null) {
                throw new IrisException("Access is null. Something bad happened.");
            }
            HudSlotClaim createClaim = !benchmark && studioProgressConsumer == null && sender.isPlayer()
                    ? openLoaderClaim("iris:world-create")
                    : null;
            AtomicInteger createProgressTask = startCreateProgressReporter(access, done, createClaim);

            reportStudioProgress(0.46D, "create_world");
            long nmsStartNanos = System.nanoTime();
            try {
                WorldLifecycleCaller callerKind = benchmark ? WorldLifecycleCaller.BENCHMARK : studio() ? WorldLifecycleCaller.STUDIO : WorldLifecycleCaller.CREATE;
                WorldLifecycleRequest request = WorldLifecycleRequest.fromCreator(wc, studio(), benchmark, callerKind);
                world = J.sfut(() -> INMS.get().createWorldAsync(wc, request))
                        .thenCompose(Function.identity())
                        .get(WORLD_CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Throwable e) {
                done.set(true);
                cancelRepeatingTask(createProgressTask);
                releaseLoaderClaim(createClaim, "iris:world-create");
                if (e instanceof TimeoutException) {
                    ServerConfigurator.restart("World creation timed out for \"" + name + "\".");
                }
                if (J.isFolia() && containsCreateWorldUnsupportedOperation(e)) {
                    throw new IrisException("Runtime world creation is blocked and the selected world lifecycle backend could not create the world.", e);
                }
                if (containsMissingDimensionTypes(e)) {
                    ServerConfigurator.restart();
                    throw new IrisException("The dimension types for pack \"" + dimension() + "\" are not loaded on this server yet. "
                            + "Iris queued a restart; run the command again after the server returns.", e);
                }
                throw new IrisException("Failed to create world with backend family " + WorldLifecycleService.get().capabilities().serverFamily().id() + "!", e);
            } finally {
                reportStudioTiming("create_bukkit_world", nmsStartNanos);
            }

            done.set(true);
            cancelRepeatingTask(createProgressTask);
            releaseLoaderClaim(createClaim, "iris:world-create");
            reportStudioProgress(0.86D, "create_world");

            if (!studio && !benchmark) {
                BukkitWorldConfiguration.register(BUKKIT_YML, name, dimension, seed);
                bukkitRegistered = true;
                World createdWorld = world;
                CompletableFuture<Void> multiverseRegistration = J.sfut(
                        () -> IrisServices.get(MultiverseCoreLink.class).updateWorld(createdWorld, dimension)
                );
                if (multiverseRegistration == null) {
                    throw new IrisException("Failed to schedule Multiverse registration for world \"" + name + "\".");
                }
                try {
                    multiverseRegistration.get(30L, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    ServerConfigurator.restart("Multiverse registration timed out for \"" + name + "\".");
                    throw e;
                }
            }
            awaitSenderTeleport(world);

            if (pregen != null) {
                CompletableFuture<Boolean> ff = new CompletableFuture<>();
                IrisToolbelt.pregenerate(pregen, access)
                        .onProgress(pp::set)
                        .whenDone(() -> ff.complete(true));

                AtomicBoolean dx = new AtomicBoolean(false);
                HudSlotClaim pregenClaim = sender.isPlayer() ? openLoaderClaim("iris:pregen") : null;
                AtomicInteger pregenProgressTask = startPregenProgressReporter(pp, dx, pregenClaim);
                try {
                    ff.get();
                    dx.set(true);
                    cancelRepeatingTask(pregenProgressTask);
                    releaseLoaderClaim(pregenClaim, "iris:pregen");
                } catch (Throwable e) {
                    dx.set(true);
                    cancelRepeatingTask(pregenProgressTask);
                    releaseLoaderClaim(pregenClaim, "iris:pregen");
                    IrisLogging.reportError(e);
                }
            }
            return world;
        } catch (Throwable failure) {
            rollbackWorldCreation(worldKey, world, dimensionRoot, bukkitRegistered, failure);
            if (failure instanceof IrisException irisException) {
                throw irisException;
            }
            throw new IrisException("Failed to create world \"" + name + "\".", failure);
        }
    }

    static Player createTeleportTarget(VolmitSender sender, boolean studio, boolean benchmark) {
        if (studio || benchmark || sender == null || !sender.isPlayer()) {
            return null;
        }
        return sender.player();
    }

    static CompletableFuture<Boolean> teleportSenderToCreatedWorld(
            Player player,
            World world,
            WorldRuntimeControlService runtimeControl
    ) {
        Player requiredPlayer = Objects.requireNonNull(player, "player");
        World requiredWorld = Objects.requireNonNull(world, "world");
        WorldRuntimeControlService requiredRuntime = Objects.requireNonNull(runtimeControl, "runtimeControl");
        Location entryAnchor = requiredRuntime.resolveEntryAnchor(requiredWorld);
        if (entryAnchor == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Unable to resolve the entry anchor for world \"" + requiredWorld.getName() + "\"."));
        }

        int chunkX = entryAnchor.getBlockX() >> 4;
        int chunkZ = entryAnchor.getBlockZ() >> 4;
        return requiredRuntime.requestChunkAsync(requiredWorld, chunkX, chunkZ, true)
                .thenCompose(chunk -> requiredRuntime.resolveSafeEntry(requiredWorld, entryAnchor))
                .thenCompose(safeEntry -> {
                    if (safeEntry == null) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Unable to resolve a safe entry for world \"" + requiredWorld.getName() + "\"."));
                    }
                    return requiredRuntime.teleport(requiredPlayer, safeEntry);
                });
    }

    private void awaitSenderTeleport(World world) {
        Player player = createTeleportTarget(sender, studio, benchmark);
        if (player == null) {
            return;
        }

        CompletableFuture<Boolean> teleportFuture;
        try {
            teleportFuture = teleportSenderToCreatedWorld(player, world, WorldRuntimeControlService.get());
        } catch (Throwable e) {
            reportSenderTeleportFailure(player, world, e);
            return;
        }

        try {
            Boolean teleported = teleportFuture.get(60L, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(teleported)) {
                reportSenderTeleportFailure(player, world, new IllegalStateException(
                        "The runtime teleport operation returned false for player \"" + player.getName() + "\"."));
            }
        } catch (TimeoutException e) {
            ServerConfigurator.restart("World entry teleport timed out for \"" + world.getName() + "\".");
            reportSenderTeleportFailure(player, world, e);
        } catch (Throwable e) {
            reportSenderTeleportFailure(player, world, e);
        }
    }

    private void reportSenderTeleportFailure(Player player, World world, Throwable throwable) {
        IrisLogging.reportError("World \"" + world.getName()
                + "\" was created, but automatic teleport failed for player \"" + player.getName() + "\".", throwable);
        J.runEntity(player, () -> new VolmitSender(player).sendMessage(IrisLanguage.text(
                RuntimeProgressMessages.WORLD_CREATE_TELEPORT_FAILED,
                MessageArgument.untrusted("world", IrisWorldStorage.logicalName(world))
        )));
    }

    private void reportStudioProgress(double progress, String stage) {
        BiConsumer<Double, String> consumer = studioProgressConsumer;
        if (consumer == null) {
            return;
        }

        double clamped = Math.max(0D, Math.min(1D, progress));
        try {
            consumer.accept(clamped, stage);
        } catch (Throwable e) {
            IrisLogging.reportError("Studio progress consumer failed for world \"" + name() + "\".", e);
        }
    }

    private void reportStudioTiming(String phase, long startedAtNanos) {
        BiConsumer<String, Long> consumer = studioTimingConsumer;
        if (consumer == null) {
            return;
        }

        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        try {
            consumer.accept(phase, duration);
        } catch (Throwable e) {
            IrisLogging.reportError("Studio timing consumer failed for world \"" + name() + "\".", e);
        }
    }

    private DatapackInstallResult prepareDatapacks(IrisDimension resolvedDimension) {
        DatapackPreparation preparation = Objects.requireNonNull(
                datapackPreparation,
                "Datapack preparation mode");
        boolean runtimeReady = preparation == DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY
                && ServerConfigurator.isLoadedDatapackRuntimeReady(resolvedDimension);
        if (!preparation.requiresInstall(runtimeReady)) {
            long reuseStart = System.nanoTime();
            reportStudioTiming("datapack_reuse_loaded_runtime", reuseStart);
            return DatapackInstallResult.unchangedResult();
        }
        return ServerConfigurator.installDataPacksIfChanged(true, studioTimingConsumer);
    }

    private AtomicInteger startCreateProgressReporter(PlatformChunkGenerator access, AtomicBoolean done, HudSlotClaim claim) {
        AtomicInteger taskId = new AtomicInteger(-1);
        if (benchmark) {
            return taskId;
        }

        IntSupplier generatedSupplier = () -> {
            if (access.getEngine() == null) {
                return 0;
            }
            return access.getEngine().getGenerated();
        };
        AtomicLong lastResolveMs = new AtomicLong(0L);
        access.getSpawnChunks().whenComplete((required, throwable) -> {
            if (throwable != null) {
                IrisLogging.reportError("Failed to resolve studio spawn chunk target for world \"" + name() + "\".", throwable);
                return;
            }

            if (done.get() || required == null || required <= 0) {
                return;
            }

            int interval = studioProgressConsumer != null || sender.isPlayer() ? 1 : 20;
            taskId.set(J.ar(() -> {
                if (done.get()) {
                    cancelRepeatingTask(taskId);
                    return;
                }

                int generated = generatedSupplier.getAsInt();
                if (generated >= required) {
                    cancelRepeatingTask(taskId);
                    return;
                }

                double progress = (double) generated / required;
                if (studioProgressConsumer != null) {
                    reportStudioProgress(0.40D + (0.42D * progress), "create_world");
                    return;
                }

                int percent = (int) Math.round(progress * 100.0D);
                int remaining = required - generated;
                if (sender.isPlayer() && claim != null) {
                    HudSurface surface = resolveThrottled(claim, lastResolveMs);
                    if (surface == HudSurface.ACTION_BAR) {
                        BukkitPlatform.hudLanes().hide(sender.player(), "iris:world-create");
                        int barWidth = 44;
                        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * barWidth);
                        StringBuilder bar = new StringBuilder(barWidth * 3 + 4);
                        bar.append(C.DARK_GRAY).append("[");
                        for (int bi = 0; bi < barWidth; bi++) {
                            bar.append(bi < filled ? C.GREEN : C.DARK_GRAY).append("|");
                        }
                        bar.append(C.DARK_GRAY).append("]");
                        sender.sendAction(IrisLanguage.text(
                                RuntimeProgressMessages.WORLD_CREATE_ACTION,
                                MessageArgument.trusted("bar", bar.toString()),
                                MessageArgument.trusted("percent", percent),
                                MessageArgument.trusted("generated", Form.f(generated)),
                                MessageArgument.trusted("required", Form.f(required))
                        ));
                    } else if (surface == HudSurface.BOSS_BAR) {
                        BukkitPlatform.hudLanes().show(sender.player(), "iris:world-create", IrisLanguage.text(
                                RuntimeProgressMessages.WORLD_CREATE_ACTION,
                                MessageArgument.trusted("bar", ""),
                                MessageArgument.trusted("percent", percent),
                                MessageArgument.trusted("generated", Form.f(generated)),
                                MessageArgument.trusted("required", Form.f(required))
                        ), progress, BarColor.GREEN, BarStyle.SOLID, 4000L);
                    }
                    return;
                }

                sender.sendMessage(IrisLanguage.text(
                        RuntimeProgressMessages.WORLD_CREATE_CONSOLE,
                        MessageArgument.trusted("percent", percent),
                        MessageArgument.trusted("generated", Form.f(generated)),
                        MessageArgument.trusted("required", Form.f(required)),
                        MessageArgument.trusted("remaining", remaining)
                ));
            }, interval));
        });
        return taskId;
    }

    private AtomicInteger startPregenProgressReporter(AtomicDouble progress, AtomicBoolean done, HudSlotClaim claim) {
        AtomicInteger taskId = new AtomicInteger(-1);
        AtomicLong lastResolveMs = new AtomicLong(0L);
        int interval = sender.isPlayer() ? 1 : 20;
        taskId.set(J.ar(() -> {
            if (done.get()) {
                cancelRepeatingTask(taskId);
                return;
            }

            double p = progress.get();
            int percent = (int) Math.round(p * 100.0D);
            if (sender.isPlayer() && claim != null) {
                HudSurface surface = resolveThrottled(claim, lastResolveMs);
                if (surface == HudSurface.ACTION_BAR) {
                    BukkitPlatform.hudLanes().hide(sender.player(), "iris:pregen");
                    int barWidth = 44;
                    int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, p)) * barWidth);
                    StringBuilder bar = new StringBuilder(barWidth * 3 + 4);
                    bar.append(C.DARK_GRAY).append("[");
                    for (int bi = 0; bi < barWidth; bi++) {
                        bar.append(bi < filled ? C.GREEN : C.DARK_GRAY).append("|");
                    }
                    bar.append(C.DARK_GRAY).append("]");
                    sender.sendAction(IrisLanguage.text(
                            RuntimeProgressMessages.WORLD_PREGEN_ACTION,
                            MessageArgument.trusted("bar", bar.toString()),
                            MessageArgument.trusted("percent", percent)
                    ));
                } else if (surface == HudSurface.BOSS_BAR) {
                    BukkitPlatform.hudLanes().show(sender.player(), "iris:pregen", IrisLanguage.text(
                            RuntimeProgressMessages.WORLD_PREGEN_ACTION,
                            MessageArgument.trusted("bar", ""),
                            MessageArgument.trusted("percent", percent)
                    ), p, BarColor.GREEN, BarStyle.SOLID, 4000L);
                }
                return;
            }

            sender.sendMessage(IrisLanguage.text(
                    RuntimeProgressMessages.WORLD_PREGEN_CONSOLE,
                    MessageArgument.trusted("percent", percent)
            ));
        }, interval));
        return taskId;
    }

    private HudSlotClaim openLoaderClaim(String purpose) {
        return BukkitPlatform.hudSlots().open(sender.player(), new HudSlotRequest(
                purpose,
                HudPriority.PROGRESS,
                1200L,
                List.of(HudSurface.ACTION_BAR, HudSurface.BOSS_BAR)
        ));
    }

    private void releaseLoaderClaim(HudSlotClaim claim, String laneId) {
        if (claim == null) {
            return;
        }
        claim.release();
        BukkitPlatform.hudLanes().hide(sender.player(), laneId);
    }

    private static HudSurface resolveThrottled(HudSlotClaim claim, AtomicLong lastResolveMillis) {
        long now = System.currentTimeMillis();
        if (now - lastResolveMillis.get() >= 250L) {
            lastResolveMillis.set(now);
            return claim.resolve();
        }
        HudSurface granted = claim.granted();
        return granted == null ? claim.resolve() : granted;
    }

    private void cancelRepeatingTask(AtomicInteger taskId) {
        if (taskId == null) {
            return;
        }

        int id = taskId.getAndSet(-1);
        if (id >= 0) {
            J.car(id);
        }
    }

    private static boolean containsCreateWorldUnsupportedOperation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof UnsupportedOperationException) {
                for (StackTraceElement element : cursor.getStackTrace()) {
                    if ("org.bukkit.craftbukkit.CraftServer".equals(element.getClassName())
                            && "createWorld".equals(element.getMethodName())) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static boolean containsMissingDimensionTypes(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof IllegalStateException && String.valueOf(cursor.getMessage()).contains("Missing dimension types")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void rollbackWorldCreation(
            NamespacedKey worldKey,
            World createdWorld,
            File dimensionRoot,
            boolean bukkitRegistered,
            Throwable failure
    ) {
        World activeWorld = createdWorld == null ? WorldIdentity.resolve(worldKey).orElse(null) : createdWorld;
        boolean safeToDelete = activeWorld != null || !containsTimeout(failure);
        if (activeWorld != null) {
            IrisToolbelt.beginWorldMaintenance(activeWorld, "world-create-rollback", true);
            try {
                PlatformChunkGenerator generator = IrisToolbelt.access(activeWorld);
                boolean evacuated = Boolean.TRUE.equals(IrisToolbelt.evacuateAsync(activeWorld)
                        .get(ROLLBACK_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                if (!evacuated) {
                    safeToDelete = false;
                    failure.addSuppressed(new IllegalStateException(
                            "Rollback could not evacuate world \"" + name + "\"."));
                }
                boolean unloaded = safeToDelete && Boolean.TRUE.equals(WorldLifecycleService.get()
                        .unloadAsync(activeWorld, true)
                        .get(ROLLBACK_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                if (!unloaded) {
                    safeToDelete = false;
                    failure.addSuppressed(new IllegalStateException("Rollback could not unload world \"" + name + "\"."));
                }
                if (safeToDelete && generator != null) {
                    generator.closeAsync().get(ROLLBACK_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (Throwable rollbackFailure) {
                Throwable cause = unwrapFailure(rollbackFailure);
                failure.addSuppressed(cause);
                safeToDelete = false;
                if (cause instanceof TimeoutException) {
                    ServerConfigurator.restart("World creation rollback timed out for \"" + name + "\".");
                }
            } finally {
                IrisToolbelt.endWorldMaintenance(activeWorld, "world-create-rollback");
            }
        }

        if (bukkitRegistered) {
            try {
                CompletableFuture<Boolean> multiverseRemoval = J.sfut(
                        () -> IrisServices.get(MultiverseCoreLink.class).removeFromConfig(name)
                );
                if (multiverseRemoval == null) {
                    throw new IllegalStateException("Failed to schedule Multiverse rollback for \"" + name + "\".");
                }
                try {
                    multiverseRemoval.get(30L, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    ServerConfigurator.restart("Multiverse rollback timed out for \"" + name + "\".");
                    throw e;
                }
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            try {
                BukkitWorldConfiguration.remove(BUKKIT_YML, name);
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
        try {
            IrisWorlds.get().remove(worldKey.toString());
        } catch (Throwable rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        if (!safeToDelete) {
            queueRollbackDeletion(name, failure);
            return;
        }
        try {
            AtomicDirectoryPublisher.deleteTree(dimensionRoot.toPath());
        } catch (Throwable rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            queueRollbackDeletion(name, failure);
        }
    }

    private void queueRollbackDeletion(String worldName, Throwable failure) {
        try {
            IrisServices.get(WorldDeletionQueue.class).queueExactForStartupDeletion(List.of(worldName));
        } catch (Throwable queueFailure) {
            failure.addSuppressed(queueFailure);
        }
    }

    private static boolean containsTimeout(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof TimeoutException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static Throwable unwrapFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor instanceof CompletionException || cursor instanceof ExecutionException) {
            if (cursor.getCause() == null) {
                break;
            }
            cursor = cursor.getCause();
        }
        return cursor;
    }

    public enum DatapackPreparation {
        INSTALL_IF_CHANGED(false),
        REUSE_LOADED_RUNTIME_IF_READY(true);

        private final boolean reusesLoadedRuntime;

        DatapackPreparation(boolean reusesLoadedRuntime) {
            this.reusesLoadedRuntime = reusesLoadedRuntime;
        }

        boolean requiresInstall(boolean runtimeReady) {
            return !reusesLoadedRuntime || !runtimeReady;
        }
    }
}

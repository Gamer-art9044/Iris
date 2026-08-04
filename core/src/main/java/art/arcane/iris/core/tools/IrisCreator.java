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
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.WorldCreatorCompat;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.WorldLifecycleCaller;
import art.arcane.iris.core.lifecycle.WorldLifecycleRequest;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeProgressMessages;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.pregenerator.PregenTask;
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
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import lombok.Data;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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

    public static boolean removeFromBukkitYml(String name) throws IOException {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        ConfigurationSection section = yml.getConfigurationSection("worlds");
        if (section == null) {
            return false;
        }
        section.set(name, null);
        if (section.getValues(false).keySet().stream().noneMatch(k -> section.get(k) != null)) {
            yml.set("worlds", null);
        }
        yml.save(BUKKIT_YML);
        return true;
    }

    public static int removeTransientStudioWorldsFromBukkitYml() throws IOException {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        ConfigurationSection section = yml.getConfigurationSection("worlds");
        if (section == null) {
            return 0;
        }
        int removed = 0;
        for (String name : new java.util.ArrayList<>(section.getKeys(false))) {
            if (TransientWorldCleanupSupport.isTransientStudioWorldName(name)) {
                section.set(name, null);
                removed++;
            }
        }
        if (removed > 0) {
            if (section.getKeys(false).isEmpty()) {
                yml.set("worlds", null);
            }
            yml.save(BUKKIT_YML);
        }
        return removed;
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
        name = IrisWorldStorage.logicalName(IrisWorldStorage.keyFromName(name));

        long createStart = System.currentTimeMillis();
        reportStudioProgress(0.02D, "resolve_dimension");
        reportStudioProgress(0.08D, "resolve_dimension");
        IrisDimension d = IrisToolbelt.getDimension(dimension());

        if (d == null) {
            throw new IrisException("Dimension cannot be found null for id " + dimension());
        }

        if (sender == null)
            sender = BukkitPlatform.console();

        reportStudioProgress(0.16D, "prepare_world_pack");
        if (!studio() || benchmark) {
            d = IrisServices.get(StudioSVC.class).installIntoWorld(sender, d, IrisWorldStorage.dimensionRoot(name()));
            if (d == null) {
                throw new IrisException("Failed to install dimension pack for " + dimension());
            }
            dimension = d.getLoadKey();
        }
        if (studio()) {
            IrisRuntimeSchedulerMode runtimeSchedulerMode = IrisRuntimeSchedulerMode.resolve(IrisSettings.get().getPregen());
            IrisLogging.debug("Studio create scheduling: mode=" + runtimeSchedulerMode.name().toLowerCase(Locale.ROOT)
                    + ", regionizedRuntime=" + FoliaScheduler.isRegionizedRuntime(Bukkit.getServer()));
        }

        reportStudioProgress(0.28D, "install_datapacks");
        AtomicDouble pp = new AtomicDouble(0);
        AtomicBoolean done = new AtomicBoolean(false);
        WorldCreator wc = new IrisWorldCreator()
                .dimension(d)
                .name(name)
                .seed(seed)
                .studio(studio)
                .create();
        if (!studio()) {
            IrisWorlds.get().put(WorldCreatorCompat.keyOf(wc).toString(), dimension());
        }
        ServerConfigurator.installDataPacksIfChanged(!studio());
        IrisLogging.debug("[Studio timing]   create.packPrep + datapacks = " + (System.currentTimeMillis() - createStart) + "ms (cumulative in create)");
        reportStudioProgress(0.40D, "install_datapacks");

        PlatformChunkGenerator access = (PlatformChunkGenerator) wc.generator();
        if (access == null) throw new IrisException("Access is null. Something bad happened.");
        HudSlotClaim createClaim = !benchmark && studioProgressConsumer == null && sender.isPlayer()
                ? openLoaderClaim("iris:world-create")
                : null;
        AtomicInteger createProgressTask = startCreateProgressReporter(access, done, createClaim);


        World world;
        reportStudioProgress(0.46D, "create_world");
        long nmsStart = System.currentTimeMillis();
        try {
            WorldLifecycleCaller callerKind = benchmark ? WorldLifecycleCaller.BENCHMARK : studio() ? WorldLifecycleCaller.STUDIO : WorldLifecycleCaller.CREATE;
            WorldLifecycleRequest request = WorldLifecycleRequest.fromCreator(wc, studio(), benchmark, callerKind);
            world = J.sfut(() -> INMS.get().createWorldAsync(wc, request))
                    .thenCompose(Function.identity())
                    .get();
            IrisLogging.debug("[Studio timing]   create.createWorldAsync (NMS bukkit world load + spawn prep) = " + (System.currentTimeMillis() - nmsStart) + "ms");
        } catch (Throwable e) {
            done.set(true);
            cancelRepeatingTask(createProgressTask);
            releaseLoaderClaim(createClaim, "iris:world-create");
            if (J.isFolia() && containsCreateWorldUnsupportedOperation(e)) {
                throw new IrisException("Runtime world creation is blocked and the selected world lifecycle backend could not create the world.", e);
            }
            if (containsMissingDimensionTypes(e)) {
                throw new IrisException("The dimension types for pack \"" + dimension() + "\" are not loaded on this server yet. "
                        + "Iris installed its datapack files - restart the server, then run the command again.", e);
            }
            throw new IrisException("Failed to create world with backend family " + WorldLifecycleService.get().capabilities().serverFamily().id() + "!", e);
        }

        done.set(true);
        cancelRepeatingTask(createProgressTask);
        releaseLoaderClaim(createClaim, "iris:world-create");
        reportStudioProgress(0.86D, "create_world");

        if (!studio && !benchmark) {
            addToBukkitYml();
            J.s(() -> IrisServices.get(MultiverseCoreLink.class).updateWorld(world, dimension));
        }
        scheduleSenderTeleport(world);

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
                e.printStackTrace();
            }
        }
        return world;
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

    private void scheduleSenderTeleport(World world) {
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

        teleportFuture.whenComplete((success, throwable) -> {
            if (throwable != null) {
                reportSenderTeleportFailure(player, world, throwable);
                return;
            }
            if (!Boolean.TRUE.equals(success)) {
                reportSenderTeleportFailure(player, world, new IllegalStateException(
                        "The runtime teleport operation returned false for player \"" + player.getName() + "\"."));
            }
        });
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

    private void addToBukkitYml() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        String gen = "Iris:" + dimension;
        ConfigurationSection section = yml.contains("worlds") ? yml.getConfigurationSection("worlds") : yml.createSection("worlds");
        if (!section.contains(name)) {
            section.createSection(name).set("generator", gen);
            try {
                yml.save(BUKKIT_YML);
                IrisLogging.info("Registered \"" + name + "\" in bukkit.yml");
            } catch (IOException e) {
                IrisLogging.error("Failed to update bukkit.yml!");
                IrisLogging.reportError(e);
                e.printStackTrace();
            }
        }
    }
}

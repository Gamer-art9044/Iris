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

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.IrisRuntimeSchedulerMode;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pregenerator.PregenPerformanceProfile;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.pregenerator.cache.PregenCache;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.pregenerator.methods.CachedPregenMethod;
import art.arcane.iris.core.pregenerator.methods.HybridPregenMethod;
import art.arcane.iris.core.service.GlobalCacheSVC;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Something you really want to wear if working on Iris. Shit gets pretty hectic down there.
 * Hope you packed snacks & road sodas.
 */
public class IrisToolbelt {
    @ApiStatus.Internal
    public static Map<String, Boolean> toolbeltConfiguration = new HashMap<>();
    private static final Method BUKKIT_IS_STOPPING_METHOD = resolveBukkitIsStoppingMethod();

    /**
     * Will find / download / search for the dimension or return null
     * <p>
     * - You can provide a dimenson in the packs folder by the folder name
     * - You can provide a github repo by using (assumes branch is master unless specified)
     * - GithubUsername/repository
     * - GithubUsername/repository/branch
     *
     * @param dimension the dimension id such as overworld or flat
     * @return the IrisDimension or null
     */
    public static IrisDimension getDimension(String dimension) {
        if (dimension == null) {
            return null;
        }

        PackReference reference = parsePackReference(dimension);
        if (reference == null) {
            return null;
        }

        File packsFolder = IrisPlatforms.get().dataFolder("packs");
        File pack = new File(packsFolder, reference.pack());
        if (!pack.exists()) {
            File found = findCaseInsensitivePack(packsFolder, reference.pack());
            if (found != null) {
                pack = found;
            }
        }

        if (!pack.exists()) {
            IrisServices.get(StudioSVC.class).downloadSearch(new VolmitSender(Bukkit.getConsoleSender(), BukkitPlatform.volmitPlugin().getTag()), reference.pack(), false);
            File found = findCaseInsensitivePack(packsFolder, reference.pack());
            if (found != null) {
                pack = found;
            }
        }

        if (!pack.exists()) {
            return null;
        }

        IrisData data = IrisData.get(pack);
        IrisDimension resolved = data.getDimensionLoader().load(reference.dimension(), false);
        if (resolved != null) {
            return resolved;
        }
        if (reference.explicitDimension()) {
            return null;
        }

        String packName = pack.getName();
        if (!packName.equals(reference.pack())) {
            resolved = data.getDimensionLoader().load(packName, false);
            if (resolved != null) {
                return resolved;
            }
        }

        for (String key : data.getDimensionLoader().getPossibleKeys()) {
            if (!key.equalsIgnoreCase(reference.pack()) && !key.equalsIgnoreCase(packName)) {
                continue;
            }

            resolved = data.getDimensionLoader().load(key, false);
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }

    static PackReference parsePackReference(String value) {
        if (value == null) {
            return null;
        }
        String requested = value.trim();
        if (requested.isEmpty()) {
            return null;
        }
        int separator = requested.indexOf(':');
        if (separator < 0) {
            return new PackReference(requested, requested, false);
        }
        String pack = requested.substring(0, separator).trim();
        String dimension = requested.substring(separator + 1).trim();
        if (pack.isEmpty() || dimension.isEmpty()) {
            return null;
        }
        return new PackReference(pack, dimension, true);
    }

    private static File findCaseInsensitivePack(File packsFolder, String requested) {
        File[] children = packsFolder.listFiles();
        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (child.isDirectory() && child.getName().equalsIgnoreCase(requested)) {
                return child;
            }
        }

        return null;
    }

    /**
     * Create a world with plenty of options
     *
     * @return the creator builder
     */
    public static IrisCreator createWorld() {
        return new IrisCreator();
    }

    /**
     * Checks if the given world is an Iris World (same as access(world) != null)
     *
     * @param world the world
     * @return true if it is an Iris Access world
     */
    public static boolean isIrisWorld(World world) {
        if (world == null) {
            return false;
        }

        if (world.getGenerator() instanceof PlatformChunkGenerator f) {
            if (f instanceof BukkitChunkGenerator bukkit) {
                bukkit.touch(world);
            }
            return true;
        }

        return false;
    }

    public static boolean isIrisStudioWorld(World world) {
        return isIrisWorld(world) && access(world).isStudio();
    }

    /**
     * Get the Iris generator for the given world
     *
     * @param world the given world
     * @return the IrisAccess or null if it's not an Iris World
     */
    public static PlatformChunkGenerator access(World world) {
        if (world == null) {
            return null;
        }

        if (isIrisWorld(world)) {
            return ((PlatformChunkGenerator) world.getGenerator());
        }

        StudioSVC studioService = IrisServices.getOrNull(StudioSVC.class);
        if (studioService != null && studioService.isProjectOpen()) {
            IrisProject activeProject = studioService.getActiveProject();
            if (activeProject != null) {
                PlatformChunkGenerator activeProvider = activeProject.getActiveProvider();
                if (activeProvider != null) {
                    World activeWorld = BukkitWorldBinding.world(activeProvider.getTarget().getWorld());
                    if (activeWorld != null && WorldIdentity.key(activeWorld).equals(WorldIdentity.key(world))) {
                        if (activeProvider instanceof BukkitChunkGenerator bukkit) {
                            bukkit.touch(world);
                        }
                        return activeProvider;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Start a pregenerator task
     *
     * @param task   the scheduled task
     * @param method the method to execute the task
     * @return the pregenerator job (already started)
     */
    public static PregeneratorJob pregenerate(PregenTask task, PregeneratorMethod method, Engine engine) {
        return pregenerate(task, method, engine, true);
    }

    /**
     * Start a pregenerator task
     *
     * @param task   the scheduled task
     * @param method the method to execute the task
     * @return the pregenerator job (already started)
     */
    public static PregeneratorJob pregenerate(PregenTask task, PregeneratorMethod method, Engine engine, boolean cached) {
        applyPregenPerformanceProfile(engine);
        boolean useCachedWrapper = false;
        if (cached && engine != null) {
            IrisRuntimeSchedulerMode runtimeSchedulerMode = IrisRuntimeSchedulerMode.resolve(IrisSettings.get().getPregen());
            useCachedWrapper = runtimeSchedulerMode != IrisRuntimeSchedulerMode.FOLIA;
        }
        return new PregeneratorJob(task, useCachedWrapper ? new CachedPregenMethod(method, resolvePregenCache(engine.getWorld()), task) : method, engine);
    }

    private static PregenCache resolvePregenCache(IrisWorld world) {
        String worldIdentity = world.identity();
        if (worldIdentity == null) {
            throw new IllegalStateException("Pregeneration requires a namespaced world identity.");
        }
        PregenCache cache = IrisServices.get(GlobalCacheSVC.class).get(worldIdentity);
        if (cache == null) {
            IrisLogging.debug("Could not find existing cache for " + worldIdentity + " creating fallback");
            cache = GlobalCacheSVC.createDefault(worldIdentity);
        }
        return cache;
    }

    public static boolean applyPregenPerformanceProfile() {
        return PregenPerformanceProfile.apply();
    }

    public static void applyPregenPerformanceProfile(Engine engine) {
        PregenPerformanceProfile.apply(engine);
    }

    public static boolean supportsStrictSerialPregeneration() {
        return PaperLib.isPaper();
    }

    /**
     * Start a pregenerator task. If the supplied generator is headless, headless mode is used,
     * otherwise Hybrid mode is used.
     *
     * @param task the scheduled task
     * @param gen  the Iris Generator
     * @return the pregenerator job (already started)
     */
    public static PregeneratorJob pregenerate(PregenTask task, PlatformChunkGenerator gen) {
        World world = BukkitWorldBinding.world(gen.getEngine().getWorld());
        int threads = IrisSettings.getThreadCount(IrisSettings.get().getConcurrency().getParallelism());
        PregeneratorMethod method = new HybridPregenMethod(world, threads);
        return pregenerate(task, method, gen.getEngine());
    }

    public static PregeneratorJob pregenerateSerial(PregenTask task, PlatformChunkGenerator gen) {
        World world = BukkitWorldBinding.world(gen.getEngine().getWorld());
        PregeneratorMethod method = HybridPregenMethod.strictSerial(world);
        return pregenerate(task, method, gen.getEngine());
    }

    /**
     * Start a pregenerator task. If the supplied generator is headless, headless mode is used,
     * otherwise Hybrid mode is used.
     *
     * @param task  the scheduled task
     * @param world the World
     * @return the pregenerator job (already started)
     */
    public static PregeneratorJob pregenerate(PregenTask task, World world) {
        if (isIrisWorld(world)) {
            return pregenerate(task, access(world));
        }

        int threads = IrisSettings.getThreadCount(IrisSettings.get().getConcurrency().getParallelism());
        PregeneratorMethod method = new HybridPregenMethod(world, threads);
        return pregenerate(task, method, null);
    }

    public static PregeneratorJob pregenerateSerial(PregenTask task, World world) {
        if (isIrisWorld(world)) {
            return pregenerateSerial(task, access(world));
        }

        PregeneratorMethod method = HybridPregenMethod.strictSerial(world);
        return pregenerate(task, method, null);
    }

    /**
     * Evacuate all players from the world into literally any other world.
     * If there are no other worlds, kick them! Not the best but what's mine is mine sometimes...
     *
     * @param world the world to evac
     */
    public static boolean evacuate(World world) {
        if (world == null || isServerStopping()) {
            return false;
        }

        for (World i : Bukkit.getWorlds()) {
            if (!WorldIdentity.key(i).equals(WorldIdentity.key(world))) {
                for (Player j : new ArrayList<>(world.getPlayers())) {
                    new VolmitSender(j, BukkitPlatform.volmitPlugin().getTag()).sendMessage("You have been evacuated from this world.");
                    Location target = i.getSpawnLocation();
                    Runnable teleportTask = () -> teleportAsyncSafely(j, target);
                    if (!J.runEntity(j, teleportTask)) {
                        teleportTask.run();
                    }
                }

                return true;
            }
        }

        return false;
    }

    /**
     * Evacuate all players from the world
     *
     * @param world the world to leave
     * @param m     the message
     * @return true if it was evacuated.
     */
    public static boolean evacuate(World world, String m) {
        if (world == null || isServerStopping()) {
            return false;
        }

        for (World i : Bukkit.getWorlds()) {
            if (!WorldIdentity.key(i).equals(WorldIdentity.key(world))) {
                for (Player j : new ArrayList<>(world.getPlayers())) {
                    new VolmitSender(j, BukkitPlatform.volmitPlugin().getTag()).sendMessage("You have been evacuated from this world. " + m);
                    Location target = i.getSpawnLocation();
                    Runnable teleportTask = () -> teleportAsyncSafely(j, target);
                    if (!J.runEntity(j, teleportTask)) {
                        teleportTask.run();
                    }
                }
                return true;
            }
        }

        return false;
    }

    public static boolean isStudio(World i) {
        if (!isIrisWorld(i)) {
            return false;
        }

        PlatformChunkGenerator generator = access(i);
        return generator != null && generator.isStudio();
    }

    private static void teleportAsyncSafely(Player player, Location target) {
        if (player == null || target == null || isServerStopping()) {
            return;
        }

        try {
            CompletableFuture<Boolean> teleportFuture = PaperLib.teleportAsync(player, target);
            if (teleportFuture != null) {
                teleportFuture.exceptionally(throwable -> {
                    if (!isServerStopping()) {
                        IrisLogging.reportError(throwable);
                    }
                    return false;
                });
            }
        } catch (Throwable throwable) {
            if (!isServerStopping()) {
                IrisLogging.reportError(throwable);
            }
        }
    }

    public static boolean isServerStopping() {
        Method method = BUKKIT_IS_STOPPING_METHOD;
        if (method != null) {
            try {
                Object value = method.invoke(null);
                if (value instanceof Boolean) {
                    return (Boolean) value;
                }
            } catch (Throwable ignored) {
            }
        }

        return !BukkitPlatform.hasPlugin() || !BukkitPlatform.plugin().isEnabled();
    }

    private static Method resolveBukkitIsStoppingMethod() {
        try {
            return Bukkit.class.getMethod("isStopping");
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void beginWorldMaintenance(World world, String reason) {
        beginWorldMaintenance(world, reason, false);
    }

    public static void beginWorldMaintenance(World world, String reason, boolean bypassMantleStages) {
        if (world == null) {
            return;
        }

        beginWorldMaintenance(WorldIdentity.serialize(world), reason, bypassMantleStages);
    }

    public static void beginWorldMaintenance(String worldName, String reason) {
        beginWorldMaintenance(worldName, reason, false);
    }

    public static void beginWorldMaintenance(String worldName, String reason, boolean bypassMantleStages) {
        WorldMaintenance.beginWorldMaintenance(worldName, reason, bypassMantleStages);
    }

    public static void endWorldMaintenance(World world, String reason) {
        if (world == null) {
            return;
        }

        endWorldMaintenance(WorldIdentity.serialize(world), reason);
    }

    public static void endWorldMaintenance(String worldName, String reason) {
        WorldMaintenance.endWorldMaintenance(worldName, reason);
    }

    public static boolean isWorldMaintenanceActive(World world) {
        return world != null && isWorldMaintenanceActive(WorldIdentity.serialize(world));
    }

    public static boolean isWorldMaintenanceActive(String worldName) {
        return WorldMaintenance.isWorldMaintenanceActive(worldName);
    }

    public static boolean isWorldMaintenanceBypassingMantleStages(World world) {
        return world != null && isWorldMaintenanceBypassingMantleStages(WorldIdentity.serialize(world));
    }

    public static boolean isWorldMaintenanceBypassingMantleStages(String worldName) {
        return WorldMaintenance.isWorldMaintenanceBypassingMantleStages(worldName);
    }

    public static void retainMantleDataForSlice(String className) {
        WorldMaintenance.retainMantleDataForSlice(className);
    }

    public static boolean isRetainingMantleDataForSlice(String className) {
        return WorldMaintenance.isRetainingMantleDataForSlice(className);
    }

    public static <T> T getMantleData(World world, int x, int y, int z, Class<T> of) {
        PlatformChunkGenerator e = access(world);
        if (e == null) {
            return null;
        }
        return e.getEngine().getMantle().getMantle().get(x, y - world.getMinHeight(), z, of);
    }

    public static <T> void deleteMantleData(World world, int x, int y, int z, Class<T> of) {
        PlatformChunkGenerator e = access(world);
        if (e == null) {
            return;
        }
        e.getEngine().getMantle().getMantle().remove(x, y - world.getMinHeight(), z, of);
    }

    public static boolean removeWorld(World world) throws IOException {
        return IrisCreator.removeFromBukkitYml(IrisWorldStorage.logicalName(world));
    }

    record PackReference(String pack, String dimension, boolean explicitDimension) {
    }
}

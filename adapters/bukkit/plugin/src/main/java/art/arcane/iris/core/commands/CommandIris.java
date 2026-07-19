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

package art.arcane.iris.core.commands;

import art.arcane.iris.Iris;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.iris.util.common.director.specialhandlers.NullablePlayerHandler;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.io.IO;
import art.arcane.iris.util.common.misc.ServerProperties;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.iris.core.service.EditSVC.deletingWorld;
import static art.arcane.iris.util.common.misc.ServerProperties.BUKKIT_YML;
import static org.bukkit.Bukkit.getServer;

@Director(name = "iris", aliases = {"ir", "irs"}, description = "Basic Command")
public class CommandIris implements DirectorExecutor {
    private CommandStudio studio;
    private CommandPregen pregen;
    private CommandObject object;
    private CommandStructure structure;
    private CommandWhat what;
    private CommandEdit edit;
    private CommandDeveloper developer;
    private CommandPack pack;
    private CommandFind find;
    private CommandDatapack datapack;
    public static boolean worldCreation = false;
    private static final AtomicReference<Thread> mainWorld = new AtomicReference<>();
    String WorldEngine;
    String worldNameToCheck = "YourWorldName";
    VolmitSender sender = Iris.getSender();

    @Director(description = "Create a new world", aliases = {"c"})
    public void create(
            @Param(aliases = "world-name", description = "The name of the world to create")
            String name,
            @Param(
                    aliases = {"dimension", "pack"},
                    description = "The dimension/pack to create the world with",
                    defaultValue = "default",
                    customHandler = PackDimensionTypeHandler.class
            )
            String type,
            @Param(description = "The seed to generate the world with", defaultValue = "1337")
            long seed,
            @Param(aliases = "main-world", description = "Whether or not to automatically use this world as the main world", defaultValue = "false")
            boolean main
    ) {
        String worldName = IrisWorldStorage.logicalName(IrisWorldStorage.keyFromName(name));
        if (worldName.equalsIgnoreCase("iris")) {
            sender().sendMessage(C.RED + "You cannot use the world name \"iris\" for creating worlds as Iris uses this directory for studio worlds.");
            sender().sendMessage(C.RED + "May we suggest the name \"IrisWorld\" instead?");
            return;
        }

        if (worldName.equalsIgnoreCase("benchmark")) {
            sender().sendMessage(C.RED + "You cannot use the world name \"benchmark\" for creating worlds as Iris uses this directory for Benchmarking Packs.");
            sender().sendMessage(C.RED + "May we suggest the name \"IrisWorld\" instead?");
            return;
        }

        if (IrisWorldStorage.dimensionRoot(worldName).exists()) {
            sender().sendMessage(C.RED + "That folder already exists!");
            return;
        }

        String resolvedType = type.equalsIgnoreCase("default")
                ? IrisSettings.get().getGenerator().getDefaultWorldType()
                : type;

        IrisDimension dimension = IrisToolbelt.getDimension(resolvedType);
        if (dimension == null) {
            sender().sendMessage(C.RED + "Could not find or download dimension \"" + resolvedType + "\".");
            sender().sendMessage(C.YELLOW + "Try one of: overworld, vanilla, flat, theend");
            sender().sendMessage(C.YELLOW + "Or download manually: /iris download " + resolvedType);
            return;
        }

        if (J.isFolia()) {
            if (stageFoliaWorldCreation(worldName, dimension, seed, main)) {
                sender().sendMessage(C.GREEN + "World staging completed. Restart the server to generate/load \"" + worldName + "\".");
            }
            return;
        }

        try {
            worldCreation = true;
            IrisToolbelt.createWorld()
                    .dimension(resolvedType)
                    .name(worldName)
                    .seed(seed)
                    .sender(sender())
                    .studio(false)
                    .create();
            if (main) {
                Runtime.getRuntime().addShutdownHook(mainWorld.updateAndGet(old -> {
                    if (old != null) Runtime.getRuntime().removeShutdownHook(old);
                    return new Thread(() -> updateMainWorld(worldName));
                }));
            }
        } catch (Throwable e) {
            sender().sendMessage(C.RED + "Exception raised during creation. See the console for more details.");
            Iris.reportError("Exception raised during world creation for \"" + worldName + "\".", e);
            worldCreation = false;
            return;
        }

        worldCreation = false;
        sender().sendMessage(C.GREEN + "Successfully created your world!");
        if (main) sender().sendMessage(C.GREEN + "Your world will automatically be set as the main world when the server restarts.");
    }

    private boolean updateMainWorld(String newName) {
        try {
            File oldLevelRoot = IrisWorldStorage.levelRoot();
            File worldContainer = oldLevelRoot.getParentFile();
            Properties data = ServerProperties.DATA;
            try (FileInputStream in = new FileInputStream(ServerProperties.SERVER_PROPERTIES)) {
                data.load(in);
            }

            File sourceDimensionRoot = IrisWorldStorage.dimensionRoot(IrisWorldStorage.keyFromName(newName));
            if (!sourceDimensionRoot.isDirectory()) {
                throw new IllegalStateException("Source dimension folder does not exist: " + sourceDimensionRoot.getAbsolutePath());
            }

            File newLevelRoot = new File(worldContainer, newName);
            if (!newLevelRoot.exists() && !newLevelRoot.mkdirs()) {
                throw new IllegalStateException("Could not create target level folder: " + newLevelRoot.getAbsolutePath());
            }

            for (String sub : List.of("data", "datapacks", "players")) {
                File source = new File(oldLevelRoot, sub);
                if (!source.exists()) {
                    continue;
                }

                IO.copyDirectory(source.toPath(), new File(newLevelRoot, sub).toPath());
            }

            File targetDimensionRoot = IrisWorldStorage.dimensionRoot(newLevelRoot, NamespacedKey.minecraft("overworld"));
            IO.copyDirectory(sourceDimensionRoot.toPath(), targetDimensionRoot.toPath());

            World sourceWorld = WorldIdentity.resolve(IrisWorldStorage.keyFromName(newName)).orElse(null);
            Long stagedSeed = IrisWorlds.readBukkitWorldSeed(newName);
            if (sourceWorld == null && stagedSeed == null) {
                throw new IllegalStateException("Cannot determine the promoted world's seed.");
            }
            long promotedSeed = sourceWorld == null ? stagedSeed : sourceWorld.getSeed();
            data.setProperty("level-name", newName);
            data.setProperty("level-seed", Long.toString(promotedSeed));
            try (FileOutputStream out = new FileOutputStream(ServerProperties.SERVER_PROPERTIES)) {
                data.store(out, null);
            }
            return true;
        } catch (Throwable e) {
            Iris.error("Failed to update server.properties main world to \"" + newName + "\"");
            Iris.reportError(e);
            return false;
        }
    }

    private boolean stageFoliaWorldCreation(String name, IrisDimension dimension, long seed, boolean main) {
        sender().sendMessage(C.YELLOW + "Runtime world creation is disabled on Folia.");
        sender().sendMessage(C.YELLOW + "Preparing world files and bukkit.yml for next startup...");

        File worldFolder = IrisWorldStorage.dimensionRoot(name);
        IrisDimension installed = Iris.service(StudioSVC.class).installIntoWorld(sender(), dimension, worldFolder);
        if (installed == null) {
            sender().sendMessage(C.RED + "Failed to stage world files for dimension \"" + dimension.getLoadKey() + "\".");
            return false;
        }

        if (!registerWorldInBukkitYml(name, dimension.getLoadKey(), seed)) {
            return false;
        }

        if (main) {
            if (updateMainWorld(name)) {
                sender().sendMessage(C.GREEN + "Updated server.properties level-name to \"" + name + "\".");
            } else {
                sender().sendMessage(C.RED + "World was staged, but failed to update server.properties main world.");
                return false;
            }
        }

        sender().sendMessage(C.GREEN + "Staged Iris world \"" + name + "\" with generator Iris:" + dimension.getLoadKey() + " and seed " + seed + ".");
        if (main) {
            sender().sendMessage(C.GREEN + "This world is now configured as main for next restart.");
        }
        return true;
    }

    private boolean registerWorldInBukkitYml(String worldName, String dimension, Long seed) {
        String logicalWorldName = IrisWorldStorage.logicalName(IrisWorldStorage.keyFromName(worldName));
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        ConfigurationSection worlds = yml.getConfigurationSection("worlds");
        if (worlds == null) {
            worlds = yml.createSection("worlds");
        }
        ConfigurationSection worldSection = worlds.getConfigurationSection(logicalWorldName);
        if (worldSection == null) {
            worldSection = worlds.createSection(logicalWorldName);
        }

        String generator = "Iris:" + dimension;
        worldSection.set("generator", generator);
        if (seed != null) {
            worldSection.set("seed", seed);
        }

        try {
            yml.save(BUKKIT_YML);
            Iris.info("Registered \"" + logicalWorldName + "\" in bukkit.yml");
            return true;
        } catch (IOException e) {
            sender().sendMessage(C.RED + "Failed to update bukkit.yml: " + e.getMessage());
            Iris.error("Failed to update bukkit.yml!");
            Iris.reportError(e);
            return false;
        }
    }

    @Director(description = "Teleport to another world", aliases = {"tp"}, sync = true)
    public void teleport(
            @Param(description = "World to teleport to")
            World world,
            @Param(description = "Player to teleport", defaultValue = "---", customHandler = NullablePlayerHandler.class)
            Player player
    ) {
        if (player == null && sender().isPlayer())
            player = sender().player();

        final Player target = player;
        if (target == null) {
            sender().sendMessage(C.RED + "The specified player does not exist.");
            return;
        }

        final Location spawn = world.getSpawnLocation();
        final Runnable teleportTask = () -> {
            BukkitPlatform.teleportAsync(target, spawn);
            new VolmitSender(target).sendMessage(C.GREEN + "You have been teleported to " + world.getName() + ".");
        };
        if (!J.runEntity(target, teleportTask)) {
            teleportTask.run();
        }
    }

    @Director(description = "Print version information")
    public void version() {
        sender().sendMessage(C.GREEN + "Iris v" + Iris.instance.getDescription().getVersion() + " by Volmit Software");
    }

    /*
    /todo
    @Director(description = "Benchmark a pack", origin = DirectorOrigin.CONSOLE)
    public void packbenchmark(
            @Param(description = "Dimension to benchmark")
            IrisDimension type
    ) throws InterruptedException {

         BenchDimension = type.getLoadKey();

        IrisPackBenchmarking.runBenchmark();
    } */

    @Director(description = "Print world height information", origin = DirectorOrigin.PLAYER)
    public void height() {
        if (sender().isPlayer()) {
            sender().sendMessage(C.GREEN + "" + sender().player().getWorld().getMinHeight() + " to " + sender().player().getWorld().getMaxHeight());
            sender().sendMessage(C.GREEN + "Total Height: " + (sender().player().getWorld().getMaxHeight() - sender().player().getWorld().getMinHeight()));
        } else {
            World mainWorld = getServer().getWorlds().get(0);
            Iris.info(C.GREEN + "" + mainWorld.getMinHeight() + " to " + mainWorld.getMaxHeight());
            Iris.info(C.GREEN + "Total Height: " + (mainWorld.getMaxHeight() - mainWorld.getMinHeight()));
        }
    }

    @Director(description = "Check access of all worlds.", aliases = {"accesslist"})
    public void worlds() {
        KList<World> IrisWorlds = new KList<>();
        KList<World> BukkitWorlds = new KList<>();

        for (World w : Bukkit.getServer().getWorlds()) {
            try {
                Engine engine = IrisToolbelt.access(w).getEngine();
                if (engine != null) {
                    IrisWorlds.add(w);
                }
            } catch (Exception e) {
                BukkitWorlds.add(w);
            }
        }

        if (sender().isPlayer()) {
            sender().sendMessage(C.BLUE + "Iris Worlds: ");
            for (World IrisWorld : IrisWorlds.copy()) {
                sender().sendMessage(C.IRIS + "- " +IrisWorld.getName());
            }
            sender().sendMessage(C.GOLD + "Bukkit Worlds: ");
            for (World BukkitWorld : BukkitWorlds.copy()) {
                sender().sendMessage(C.GRAY + "- " +BukkitWorld.getName());
            }
        } else {
            Iris.info(C.BLUE + "Iris Worlds: ");
            for (World IrisWorld : IrisWorlds.copy()) {
                Iris.info(C.IRIS + "- " +IrisWorld.getName());
            }
            Iris.info(C.GOLD + "Bukkit Worlds: ");
            for (World BukkitWorld : BukkitWorlds.copy()) {
                Iris.info(C.GRAY + "- " +BukkitWorld.getName());
            }
            
        }
    }

    @Director(description = "Remove an Iris world", aliases = {"rm"}, sync = true)
    public void remove(
            @Param(description = "The world to remove")
            World world,
            @Param(description = "Whether to also remove the folder (if set to false, just does not load the world)", defaultValue = "true")
            boolean delete
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world. Iris worlds: " + String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()));
            return;
        }
        sender().sendMessage(C.GREEN + "Removing world: " + world.getName());

        if (!IrisToolbelt.evacuate(world)) {
            sender().sendMessage(C.RED + "Failed to evacuate world: " + world.getName());
            return;
        }

        if (!WorldLifecycleService.get().unload(world, false)) {
            sender().sendMessage(C.RED + "Failed to unload world: " + world.getName());
            return;
        }

        try {
            if (IrisToolbelt.removeWorld(world)) {
                sender().sendMessage(C.GREEN + "Successfully removed " + world.getName() + " from bukkit.yml");
            } else {
                sender().sendMessage(C.YELLOW + "Looks like the world was already removed from bukkit.yml");
            }
        } catch (IOException e) {
            sender().sendMessage(C.RED + "Failed to save bukkit.yml because of " + e.getMessage());
            Iris.reportError("Failed to remove world \"" + world.getName() + "\" from bukkit.yml.", e);
        }
        IrisToolbelt.evacuate(world, "Deleting world");
        deletingWorld = true;
        if (!delete) {
            deletingWorld = false;
            return;
        }
        VolmitSender sender = sender();
        J.a(() -> {
            int retries = 12;

            if (deleteDirectory(world.getWorldFolder())) {
                sender.sendMessage(C.GREEN + "Successfully removed world folder");
            } else {
                while(true){
                    if (deleteDirectory(world.getWorldFolder())){
                        sender.sendMessage(C.GREEN + "Successfully removed world folder");
                        break;
                    }
                    retries--;
                    if (retries == 0){
                        sender.sendMessage(C.RED + "Failed to remove world folder");
                        break;
                    }
                    J.sleep(3000);
                }
            }
            deletingWorld = false;
        });
    }

    public static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            for (int i = 0; i < children.length; i++) {
                boolean success = deleteDirectory(children[i]);
                if (!success) {
                    return false;
                }
            }
        }
        return dir.delete();
    }

    @Director(description = "Toggle debug")
    public void debug() {
        boolean to = !IrisSettings.get().getGeneral().isDebug();
        IrisSettings.get().getGeneral().setDebug(to);
        IrisSettings.get().forceSave();
        sender().sendMessage(C.GREEN + "Set debug to: " + to);
    }

    @Director(description = "Download a project.", aliases = "dl")
    public void download(
            @Param(name = "pack", description = "The pack to download", aliases = "project")
            String pack,
            @Param(name = "branch", description = "The branch to download from", defaultValue = "stable")
            String branch,
            @Param(name = "overwrite", description = "Whether or not to overwrite the pack with the downloaded one", aliases = "force", defaultValue = "false")
            boolean overwrite
    ) {
        if (PackDownloader.isDefaultOverworld(pack)) {
            sender().sendMessage(C.GREEN + "Downloading pack: " + pack + " (beta release)" + (overwrite ? " overwriting" : ""));
            Iris.service(StudioSVC.class).downloadDefaultOverworld(sender(), overwrite);
        } else {
            sender().sendMessage(C.GREEN + "Downloading pack: " + pack + "/" + branch + (overwrite ? " overwriting" : ""));
            Iris.service(StudioSVC.class).downloadSearch(sender(), "IrisDimensions/" + pack + "/" + branch, overwrite);
        }
        ServerConfigurator.installDataPacksIfChanged(true);
    }

    @Director(description = "Get metrics for your world", aliases = "measure", origin = DirectorOrigin.PLAYER)
    public void metrics() {
        if (!IrisToolbelt.isIrisWorld(world())) {
            sender().sendMessage(C.RED + "You must be in an Iris world");
            return;
        }
        sender().sendMessage(C.GREEN + "Sending metrics...");
        engine().printMetrics(sender());
    }

    @Director(description = "Reload configuration file (this is also done automatically)")
    public void reload() {
        IrisSettings.invalidate();
        IrisSettings.get();
        sender().sendMessage(C.GREEN + "Hotloaded settings");
    }


    @Director(description = "Unload an Iris World", origin = DirectorOrigin.PLAYER, sync = true)
    public void unloadWorld(
            @Param(description = "The world to unload")
            World world
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world. Iris worlds: " + String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()));
            return;
        }
        sender().sendMessage(C.GREEN + "Unloading world: " + world.getName());
        try {
            IrisToolbelt.evacuate(world);
            boolean unloaded = WorldLifecycleService.get().unload(world, false);
            if (unloaded) {
                sender().sendMessage(C.GREEN + "World unloaded successfully.");
            } else {
                sender().sendMessage(C.RED + "Failed to unload the world.");
            }
        } catch (Exception e) {
            sender().sendMessage(C.RED + "Failed to unload the world: " + e.getMessage());
            Iris.reportError("Failed to unload world \"" + world.getName() + "\".", e);
        }
    }

    @Director(description = "Load an Iris World", origin = DirectorOrigin.PLAYER, sync = true, aliases = {"import"})
    public void loadWorld(
            @Param(description = "The name of the world to load")
            String world
    ) {
        String logicalWorldName = IrisWorldStorage.logicalName(IrisWorldStorage.keyFromName(world));
        worldNameToCheck = logicalWorldName;
        boolean worldExists = doesWorldExist(worldNameToCheck);
        WorldEngine = logicalWorldName;

        if (!worldExists) {
            sender().sendMessage(C.YELLOW + logicalWorldName + " Doesnt exist on the server.");
            return;
        }

        File directory = new File(IrisWorldStorage.packRoot(IrisWorldStorage.keyFromName(logicalWorldName)), "dimensions");

        String dimension = null;
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        String fileName = file.getName();
                        if (fileName.endsWith(".json")) {
                            dimension = fileName.substring(0, fileName.length() - 5);
                            sender().sendMessage(C.BLUE + "Generator: " + dimension);
                        }
                    }
                }
            }
        } else {
            sender().sendMessage(C.GOLD + logicalWorldName + " is not an iris world.");
            return;
        }

        if (dimension == null) {
            sender().sendMessage(C.RED + "Could not determine Iris dimension for " + logicalWorldName + ".");
            return;
        }

        sender().sendMessage(C.GREEN + "Loading world: " + logicalWorldName);

        if (!registerWorldInBukkitYml(logicalWorldName, dimension, null)) {
            return;
        }

        if (J.isFolia()) {
            sender().sendMessage(C.YELLOW + "Folia cannot load new worlds at runtime. Restart the server to load \"" + logicalWorldName + "\".");
            return;
        }

        Iris.instance.checkForBukkitWorlds(logicalWorldName::equals);
        sender().sendMessage(C.GREEN + logicalWorldName + " loaded successfully.");
    }
    @Director(description = "Evacuate an iris world", origin = DirectorOrigin.PLAYER, sync = true)
    public void evacuate(
            @Param(description = "Evacuate the world")
            World world
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world. Iris worlds: " + String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()));
            return;
        }
        sender().sendMessage(C.GREEN + "Evacuating world" + world.getName());
        IrisToolbelt.evacuate(world);
    }

    boolean doesWorldExist(String worldName) {
        File worldDirectory = IrisWorldStorage.dimensionRoot(worldName);
        return worldDirectory.exists() && worldDirectory.isDirectory();
    }

    public static class PackDimensionTypeHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            Set<String> options = new LinkedHashSet<>();
            options.add("default");

            File packsFolder = Iris.instance.getDataFolder("packs");
            File[] packs = packsFolder.listFiles();
            if (packs != null) {
                for (File pack : packs) {
                    if (pack == null || !pack.isDirectory()) {
                        continue;
                    }

                    options.add(pack.getName());

                    try {
                        IrisData data = IrisData.get(pack);
                        for (String key : data.getDimensionLoader().getPossibleKeys()) {
                            options.add(key);
                            options.add(pack.getName() + ":" + key);
                        }
                    } catch (Throwable ex) {
                        Iris.warn("Failed to read dimension keys from pack %s: %s%s",
                                pack.getName(),
                                ex.getClass().getSimpleName(),
                                ex.getMessage() == null ? "" : " - " + ex.getMessage());
                        Iris.reportError(ex);
                    }
                }
            }

            return new KList<>(options);
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.trim().isEmpty()) {
                throw new DirectorParsingException("World type cannot be empty");
            }

            return in.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}

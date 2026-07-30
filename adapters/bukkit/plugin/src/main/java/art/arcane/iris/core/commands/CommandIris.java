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

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.IrisMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.iris.core.localization.BukkitCommandMessages;
import art.arcane.iris.core.localization.BukkitCommandMessagesExtended;
import art.arcane.iris.core.localization.RuntimeUiMessages;
@Director(name = "iris", aliases = {"ir", "irs"}, description = "Basic Command", descriptionKey = "iris.director.commandiris.director.basic_command")
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

    @Director(description = "Create a new world", descriptionKey = "iris.director.commandiris.director.create_new_world", aliases = {"c"})
    public void create(
            @Param(aliases = "world-name", description = "The name of the world to create", descriptionKey = "iris.director.commandiris.param.name_world_create")
            String name,
            @Param(
                    aliases = {"dimension", "pack"},
                    description = "The dimension/pack to create the world with", descriptionKey = "iris.director.commandiris.param.dimension_pack_create_world_with",
                    defaultValue = "default",
                    customHandler = PackDimensionTypeHandler.class
            )
            String type,
            @Param(description = "The seed to generate the world with", descriptionKey = "iris.director.commandiris.param.seed_generate_world_with", defaultValue = "1337")
            long seed,
            @Param(aliases = "main-world", description = "Whether or not to automatically use this world as the main world", descriptionKey = "iris.director.commandiris.param.whether_not_automatically_use_this_world_as_main_world", defaultValue = "false")
            boolean main
    ) {
        String worldName = IrisWorldStorage.logicalName(IrisWorldStorage.keyFromName(name));
        if (worldName.equalsIgnoreCase("iris")) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_YOU_CANNOT_USE_WORLD_NAME_IRIS_CREATING_WORLDS_AS_IRIS));
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_MAY_WE_SUGGEST_NAME_IRISWORLD_INSTEAD));
            return;
        }

        if (worldName.equalsIgnoreCase("benchmark")) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_YOU_CANNOT_USE_WORLD_NAME_BENCHMARK_CREATING_WORLDS_AS_IRIS));
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_MAY_WE_SUGGEST_NAME_IRISWORLD_INSTEAD_2));
            return;
        }

        if (IrisWorldStorage.dimensionRoot(worldName).exists()) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_THAT_FOLDER_ALREADY_EXISTS));
            return;
        }

        String resolvedType = type.equalsIgnoreCase("default")
                ? IrisSettings.get().getGenerator().getDefaultWorldType()
                : type;

        IrisDimension dimension = IrisToolbelt.getDimension(resolvedType);
        if (dimension == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_COULD_NOT_FIND_DOWNLOAD_DIMENSION, MessageArgument.untrusted("resolvedType", resolvedType)));
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_TRY_ONE_OVERWORLD_VANILLA_FLAT_THEEND));
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_DOWNLOAD_MANUALLY_IRIS_DOWNLOAD, MessageArgument.untrusted("resolvedType", resolvedType)));
            return;
        }

        if (J.isFolia()) {
            if (stageFoliaWorldCreation(worldName, dimension, seed, main)) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_WORLD_STAGING_COMPLETED_RESTART_SERVER_GENERATE_LOAD, MessageArgument.untrusted("worldName", worldName)));
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
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_EXCEPTION_RAISED_DURING_CREATION_SEE_CONSOLE_MORE_DETAILS));
            Iris.reportError("Exception raised during world creation for \"" + worldName + "\".", e);
            worldCreation = false;
            return;
        }

        worldCreation = false;
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_SUCCESSFULLY_CREATED_YOUR_WORLD));
        if (main) sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_YOUR_WORLD_WILL_AUTOMATICALLY_BE_SET_AS_MAIN_WORLD_WHEN));
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
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_RUNTIME_WORLD_CREATION_IS_DISABLED_ON_FOLIA));
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_PREPARING_WORLD_FILES_BUKKIT_YML_NEXT_STARTUP));

        File worldFolder = IrisWorldStorage.dimensionRoot(name);
        IrisDimension installed = Iris.service(StudioSVC.class).installIntoWorld(sender(), dimension, worldFolder);
        if (installed == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_STAGE_WORLD_FILES_DIMENSION, MessageArgument.untrusted("value", dimension.getLoadKey())));
            return false;
        }

        if (!registerWorldInBukkitYml(name, dimension.getLoadKey(), seed)) {
            return false;
        }

        if (main) {
            if (updateMainWorld(name)) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_UPDATED_SERVER_PROPERTIES_LEVEL_NAME, MessageArgument.untrusted("name", name)));
            } else {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_WORLD_WAS_STAGED_BUT_FAILED_UPDATE_SERVER_PROPERTIES_MAIN_WORLD));
                return false;
            }
        }

        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_STAGED_IRIS_WORLD_WITH_GENERATOR_IRIS_SEED, MessageArgument.untrusted("name", name), MessageArgument.untrusted("value", dimension.getLoadKey()), MessageArgument.untrusted("seed", seed)));
        if (main) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_THIS_WORLD_IS_NOW_CONFIGURED_AS_MAIN_NEXT_RESTART));
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
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UPDATE_BUKKIT_YML, MessageArgument.untrusted("value", String.valueOf(e.getMessage()))));
            Iris.error("Failed to update bukkit.yml!");
            Iris.reportError(e);
            return false;
        }
    }

    @Director(description = "Teleport to another world", descriptionKey = "iris.director.commandiris.director.teleport_another_world", aliases = {"tp"}, sync = true)
    public void teleport(
            @Param(description = "World to teleport to", descriptionKey = "iris.director.commandiris.param.world_teleport")
            World world,
            @Param(description = "Player to teleport", descriptionKey = "iris.director.commandiris.param.player_teleport", defaultValue = "---", customHandler = NullablePlayerHandler.class)
            Player player
    ) {
        if (player == null && sender().isPlayer())
            player = sender().player();

        final Player target = player;
        if (target == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_SPECIFIED_PLAYER_DOES_NOT_EXIST));
            return;
        }

        final Location spawn = world.getSpawnLocation();
        final Runnable teleportTask = () -> {
            BukkitPlatform.teleportAsync(target, spawn);
            new VolmitSender(target).sendMessage(C.GREEN + IrisLanguage.text(
                    RuntimeUiMessages.TELEPORTED_TO_WORLD,
                    MessageArgument.untrusted("world", world.getName())
            ));
        };
        if (!J.runEntity(target, teleportTask)) {
            teleportTask.run();
        }
    }

    @Director(description = "Print version information", descriptionKey = "iris.director.commandiris.director.print_version_information")
    public void version() {
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_IRIS_V_BY_VOLMIT_SOFTWARE, MessageArgument.untrusted("value", Iris.instance.getDescription().getVersion())));
    }

    @Director(description = "Print world height information", descriptionKey = "iris.director.commandiris.director.print_world_height_information", origin = DirectorOrigin.PLAYER)
    public void height() {
        if (sender().isPlayer()) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_TO, MessageArgument.untrusted("value", sender().player().getWorld().getMinHeight()), MessageArgument.untrusted("value2", sender().player().getWorld().getMaxHeight())));
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_TOTAL_HEIGHT, MessageArgument.untrusted("value", (sender().player().getWorld().getMaxHeight() - sender().player().getWorld().getMinHeight()))));
        } else {
            World mainWorld = getServer().getWorlds().get(0);
            Iris.info(C.GREEN + "" + mainWorld.getMinHeight() + " to " + mainWorld.getMaxHeight());
            Iris.info(C.GREEN + "Total Height: " + (mainWorld.getMaxHeight() - mainWorld.getMinHeight()));
        }
    }

    @Director(description = "Check access of all worlds.", descriptionKey = "iris.director.commandiris.director.check_access_all_worlds", aliases = {"accesslist"})
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
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_IRIS_WORLDS));
            for (World IrisWorld : IrisWorlds.copy()) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_MESSAGE, MessageArgument.untrusted("value", IrisWorld.getName())));
            }
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_BUKKIT_WORLDS));
            for (World BukkitWorld : BukkitWorlds.copy()) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_MESSAGE_2, MessageArgument.untrusted("value", BukkitWorld.getName())));
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

    @Director(description = "Remove an Iris world", descriptionKey = "iris.director.commandiris.director.remove_iris_world", aliases = {"rm"}, sync = true)
    public void remove(
            @Param(description = "The world to remove", descriptionKey = "iris.director.commandiris.param.world_remove")
            World world,
            @Param(description = "Whether to also remove the folder (if set to false, just does not load the world)", descriptionKey = "iris.director.commandiris.param.whether_also_remove_folder_if_set_false_just_does_not_load_world", defaultValue = "true")
            boolean delete
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_THIS_IS_NOT_IRIS_WORLD_IRIS_WORLDS, MessageArgument.untrusted("value", String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()))));
            return;
        }
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_REMOVING_WORLD, MessageArgument.untrusted("value", world.getName())));

        if (!IrisToolbelt.evacuate(world)) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_EVACUATE_WORLD, MessageArgument.untrusted("value", world.getName())));
            return;
        }

        if (!WorldLifecycleService.get().unload(world, false)) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UNLOAD_WORLD, MessageArgument.untrusted("value", world.getName())));
            return;
        }

        try {
            if (IrisToolbelt.removeWorld(world)) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_SUCCESSFULLY_REMOVED_FROM_BUKKIT_YML, MessageArgument.untrusted("value", world.getName())));
            } else {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_LOOKS_LIKE_WORLD_WAS_ALREADY_REMOVED_FROM_BUKKIT_YML));
            }
        } catch (IOException e) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_SAVE_BUKKIT_YML_BECAUSE, MessageArgument.untrusted("value", String.valueOf(e.getMessage()))));
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
                sender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_IRIS_SUCCESSFULLY_REMOVED_WORLD_FOLDER));
            } else {
                while(true){
                    if (deleteDirectory(world.getWorldFolder())){
                        sender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_IRIS_SUCCESSFULLY_REMOVED_WORLD_FOLDER_2));
                        break;
                    }
                    retries--;
                    if (retries == 0){
                        sender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_IRIS_FAILED_REMOVE_WORLD_FOLDER));
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

    @Director(description = "Toggle debug", descriptionKey = "iris.director.commandiris.director.toggle_debug")
    public void debug() {
        boolean to = !IrisSettings.get().getGeneral().isDebug();
        IrisSettings.get().getGeneral().setDebug(to);
        IrisSettings.get().forceSave();
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_SET_DEBUG, MessageArgument.untrusted("to", to)));
    }

    @Director(description = "Download a project.", descriptionKey = "iris.director.commandiris.director.download_project", aliases = "dl")
    public void download(
            @Param(name = "pack", description = "The pack to download", descriptionKey = "iris.director.commandiris.param.pack_download", aliases = "project")
            String pack,
            @Param(name = "branch", description = "The branch to download from", descriptionKey = "iris.director.commandiris.param.branch_download_from", defaultValue = "stable")
            String branch,
            @Param(name = "overwrite", description = "Whether or not to overwrite the pack with the downloaded one", descriptionKey = "iris.director.commandiris.param.whether_not_overwrite_pack_with_downloaded_one", aliases = "force", defaultValue = "false")
            boolean overwrite
    ) {
        if (PackDownloader.isDefaultOverworld(pack)) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_DOWNLOADING_PACK_BETA_RELEASE, MessageArgument.untrusted("pack", pack), MessageArgument.trusted("value", overwrite ? IrisLanguage.text(RuntimeUiMessages.DOWNLOAD_OVERWRITE_SUFFIX) : "")));
            Iris.service(StudioSVC.class).downloadDefaultOverworld(sender(), overwrite);
        } else {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_DOWNLOADING_PACK, MessageArgument.untrusted("pack", pack), MessageArgument.untrusted("branch", branch), MessageArgument.trusted("value", overwrite ? IrisLanguage.text(RuntimeUiMessages.DOWNLOAD_OVERWRITE_SUFFIX) : "")));
            Iris.service(StudioSVC.class).downloadSearch(sender(), "IrisDimensions/" + pack + "/" + branch, overwrite);
        }
        ServerConfigurator.installDataPacksIfChanged(true);
    }

    @Director(description = "Get metrics for your world", descriptionKey = "iris.director.commandiris.director.get_metrics_your_world", aliases = "measure", origin = DirectorOrigin.PLAYER)
    public void metrics() {
        if (!IrisToolbelt.isIrisWorld(world())) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_YOU_MUST_BE_IRIS_WORLD));
            return;
        }
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_SENDING_METRICS));
        engine().printMetrics(sender());
    }

    @Director(description = "Reload configuration file (this is also done automatically)", descriptionKey = "iris.director.commandiris.director.reload_configuration_file_this_is_also_done_automatically")
    public void reload() {
        IrisSettings.invalidate();
        IrisSettings.get();
        boolean localeLoaded = IrisLanguage.reload();
        if (localeLoaded) {
            sender().sendMessage(C.GREEN + IrisLanguage.text(
                    IrisMessages.COMMAND_RELOAD_SUCCESS,
                    MessageArgument.trusted("locale", IrisLanguage.activeLocale())
            ));
            return;
        }
        sender().sendMessage(C.YELLOW + IrisLanguage.text(
                IrisMessages.COMMAND_RELOAD_FAILED,
                MessageArgument.untrusted("locale", IrisSettings.get().getGeneral().getLanguage()),
                MessageArgument.trusted("activeLocale", IrisLanguage.activeLocale())
        ));
    }


    @Director(description = "Unload an Iris World", descriptionKey = "iris.director.commandiris.director.unload_iris_world", origin = DirectorOrigin.PLAYER, sync = true)
    public void unloadWorld(
            @Param(description = "The world to unload", descriptionKey = "iris.director.commandiris.param.world_unload")
            World world
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_THIS_IS_NOT_IRIS_WORLD_IRIS_WORLDS_2, MessageArgument.untrusted("value", String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()))));
            return;
        }
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_UNLOADING_WORLD, MessageArgument.untrusted("value", world.getName())));
        try {
            IrisToolbelt.evacuate(world);
            boolean unloaded = WorldLifecycleService.get().unload(world, false);
            if (unloaded) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_WORLD_UNLOADED_SUCCESSFULLY));
            } else {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UNLOAD_WORLD_2));
            }
        } catch (Exception e) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UNLOAD_WORLD_3, MessageArgument.untrusted("value", String.valueOf(e.getMessage()))));
            Iris.reportError("Failed to unload world \"" + world.getName() + "\".", e);
        }
    }

    @Director(description = "Load an Iris World", descriptionKey = "iris.director.commandiris.director.load_iris_world", origin = DirectorOrigin.PLAYER, sync = true, aliases = {"import"})
    public void loadWorld(
            @Param(description = "The name of the world to load", descriptionKey = "iris.director.commandiris.param.name_world_load")
            String world
    ) {
        String logicalWorldName = IrisWorldStorage.logicalName(IrisWorldStorage.keyFromName(world));
        worldNameToCheck = logicalWorldName;
        boolean worldExists = doesWorldExist(worldNameToCheck);
        WorldEngine = logicalWorldName;

        if (!worldExists) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_DOESNT_EXIST_ON_SERVER, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
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
                            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_GENERATOR, MessageArgument.untrusted("dimension", dimension)));
                        }
                    }
                }
            }
        } else {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_IS_NOT_IRIS_WORLD, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
            return;
        }

        if (dimension == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_COULD_NOT_DETERMINE_IRIS_DIMENSION, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
            return;
        }

        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_LOADING_WORLD, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));

        if (!registerWorldInBukkitYml(logicalWorldName, dimension, null)) {
            return;
        }

        if (J.isFolia()) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FOLIA_CANNOT_LOAD_NEW_WORLDS_AT_RUNTIME_RESTART_SERVER_LOAD, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
            return;
        }

        Iris.instance.worldReconciler().checkForBukkitWorlds(logicalWorldName::equals);
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_LOADED_SUCCESSFULLY, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
    }
    @Director(description = "Evacuate an iris world", descriptionKey = "iris.director.commandiris.director.evacuate_iris_world", origin = DirectorOrigin.PLAYER, sync = true)
    public void evacuate(
            @Param(description = "Evacuate the world", descriptionKey = "iris.director.commandiris.param.evacuate_world")
            World world
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_THIS_IS_NOT_IRIS_WORLD_IRIS_WORLDS_3, MessageArgument.untrusted("value", String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()))));
            return;
        }
        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_EVACUATING_WORLD, MessageArgument.untrusted("value", world.getName())));
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

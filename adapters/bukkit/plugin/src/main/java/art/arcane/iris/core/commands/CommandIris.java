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
import art.arcane.iris.core.BukkitWorldReconciler;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisStartupValidation;
import art.arcane.iris.core.DatapackInstallResult;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.PendingWorldReplacementManager;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration;
import art.arcane.iris.core.lifecycle.IrisWorldRemovalService;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.pack.AtomicDirectoryPublisher;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.pack.PackDirectoryResolver;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
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
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static art.arcane.iris.util.common.misc.ServerProperties.BUKKIT_YML;
import static org.bukkit.Bukkit.getServer;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.IrisMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.iris.core.localization.BukkitCommandMessagesExtended;
import art.arcane.iris.core.localization.RuntimeUiMessages;
@Director(name = "iris", aliases = {"ir", "irs"}, description = "Basic Command", descriptionKey = "iris.director.commandiris.director.basic_command")
public class CommandIris implements DirectorExecutor {
    private static final String NO_DOWNLOAD_SOURCE = "__none__";
    private static final long WORLD_UNLOAD_TIMEOUT_SECONDS = 150L;

    private CommandStudio studio;
    private CommandPregen pregen;
    private CommandObject object;
    private CommandStructure structure;
    private CommandJigsaw jigsaw;
    private CommandWhat what;
    private CommandEdit edit;
    private CommandDeveloper developer;
    private CommandPack pack;
    private CommandFind find;
    private CommandDatapack datapack;
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
            long seed
    ) {
        NamespacedKey worldKey;
        try {
            worldKey = IrisWorldStorage.managedKeyFromName(name);
            IrisWorldStorage.requireSafeManagedDimensionRoot(worldKey);
        } catch (IllegalArgumentException e) {
            sender().sendMessage(C.RED + e.getMessage());
            return;
        }
        String worldName = IrisWorldStorage.logicalName(worldKey);
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
            sender().sendMessage("Could not find dimension '" + resolvedType + "'.");
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_TRY_ONE_OVERWORLD_VANILLA_FLAT_THEEND));
            sender().sendMessage("Install its pack with " + PackDownloader.downloadCommandFor(resolvedType)
                    + " and restart the server.");
            return;
        }

        if (J.isFolia()) {
            boolean staged = stageFoliaWorldCreation(worldName, dimension, seed);
            if (!staged) {
                return;
            }
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_WORLD_STAGING_COMPLETED_RESTARTING_SERVER_GENERATE_LOAD, MessageArgument.untrusted("worldName", worldName)));
            ServerConfigurator.restart("Iris staged Folia world \"" + worldName + "\" for startup.");
            return;
        }

        try {
            IrisToolbelt.createWorld()
                    .dimension(resolvedType)
                    .name(worldName)
                    .seed(seed)
                    .sender(sender())
                    .studio(false)
                    .create();
        } catch (Throwable e) {
            if (reportExpectedCreationInterruption(e)) {
                return;
            }
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_EXCEPTION_RAISED_DURING_CREATION_SEE_CONSOLE_MORE_DETAILS));
            Iris.reportError("Exception raised during world creation for \"" + worldName + "\".", e);
            return;
        }

        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_SUCCESSFULLY_CREATED_YOUR_WORLD));
    }

    @Director(
            description = "Replace an existing world with Iris generation on the next restart",
            descriptionKey = "iris.director.commandiris.param.replace_exact_existing_world_slot_next_restart",
            aliases = {"override", "overwrite"}
    )
    public void replace(
            @Param(
                    name = "target",
                    aliases = "world-name",
                    description = "The exact existing world slot to replace",
                    descriptionKey = "iris.director.commandiris.param.replace_exact_existing_world_slot_next_restart"
            )
            String target,
            @Param(
                    aliases = {"dimension", "pack"},
                    description = "The dimension/pack to replace the world with",
                    defaultValue = "default",
                    customHandler = PackDimensionTypeHandler.class
            )
            String type
    ) {
        NamespacedKey worldKey;
        try {
            worldKey = Iris.instance.pendingWorldReplacements().resolveRequestedWorldKey(target);
        } catch (IllegalArgumentException e) {
            sender().sendMessage(C.RED + e.getMessage());
            return;
        }

        String resolvedType = type.equalsIgnoreCase("default")
                ? IrisSettings.get().getGenerator().getDefaultWorldType()
                : type;
        IrisDimension dimension = IrisToolbelt.getDimension(resolvedType);
        if (dimension == null) {
            sender().sendMessage("Could not find dimension '" + resolvedType + "'.");
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_TRY_ONE_OVERWORLD_VANILLA_FLAT_THEEND));
            sender().sendMessage("Install its pack with " + PackDownloader.downloadCommandFor(resolvedType)
                    + " and restart the server.");
            return;
        }

        try {
            PendingWorldReplacementManager.StagedReplacement staged = Iris.instance
                    .pendingWorldReplacements()
                    .stageReplacement(sender(), worldKey, dimension);
            sender().sendMessage(C.GREEN + "Staged Iris replacement for " + staged.worldKey()
                    + ". Restart once to publish it. The current dimension is retained until Iris verifies the replacement.");
        } catch (Throwable failure) {
            Iris.reportError("Failed to stage Iris world replacement for " + worldKey + ".", failure);
            String detail = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            sender().sendMessage(C.RED + "Could not stage the world replacement: " + detail);
        }
    }

    private boolean stageFoliaWorldCreation(String name, IrisDimension dimension, long seed) {
        try {
            IrisStartupValidation.requireWorldCreationReady();
            PackValidationRegistry.requireLoadable(
                    dimension.getLoader().getDataFolder().getName());
        } catch (RuntimeException exception) {
            sender().sendMessage(C.RED + exception.getMessage());
            return false;
        }
        NamespacedKey worldKey = IrisWorldStorage.managedKeyFromName(name);
        LifecycleOperationCoordinator.Lease worldLease = null;
        File worldFolder = IrisWorldStorage.requireSafeManagedDimensionRoot(worldKey);
        Path stagedWorld = null;
        try {
            LifecycleOperationCoordinator coordinator = LifecycleOperationCoordinator.get();
            worldLease = coordinator.acquire(
                    LifecycleOperationCoordinator.Domain.WORLD_MUTATION,
                    LifecycleOperationCoordinator.OperationKind.WORLD_CREATE,
                    worldKey.toString());
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_RUNTIME_WORLD_CREATION_IS_DISABLED_ON_FOLIA));
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_PREPARING_WORLD_FILES_BUKKIT_YML_NEXT_STARTUP));
            if (worldFolder.exists()) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_THAT_FOLDER_ALREADY_EXISTS));
                return false;
            }

            DatapackInstallResult datapackResult = ServerConfigurator.installDataPacksIfChanged(true);
            if (!datapackResult.succeeded()) {
                sender().sendMessage(C.RED + "Failed to compile the Iris datapack. No world files were staged.");
                return false;
            }

            Path targetWorld = worldFolder.toPath().toAbsolutePath().normalize();
            Path namespaceRoot = targetWorld.getParent();
            if (namespaceRoot == null) {
                throw new IOException("Iris world target has no namespace directory: " + targetWorld);
            }
            Files.createDirectories(namespaceRoot);
            stagedWorld = Files.createTempDirectory(namespaceRoot, ".iris-create-" + worldKey.getKey() + "-");
            Path sourceOverworld = IrisWorldStorage.dimensionRoot(
                    IrisWorldStorage.levelRoot(),
                    NamespacedKey.minecraft("overworld")
            ).toPath();
            INMS.get().writeCurrentPaperWorldData(sourceOverworld, stagedWorld, seed);

            IrisDimension installed = Iris.service(StudioSVC.class).installIntoWorld(
                    sender(),
                    dimension,
                    stagedWorld.toFile()
            );
            if (installed == null) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_STAGE_WORLD_FILES_DIMENSION, MessageArgument.untrusted("value", dimension.getLoadKey())));
                return false;
            }

            try (AtomicDirectoryPublisher.Publication publication = AtomicDirectoryPublisher.publishAbsent(
                    stagedWorld,
                    targetWorld
            )) {
                stagedWorld = null;
                if (!registerWorldInBukkitYml(worldKey, dimension.getLoadKey(), seed)) {
                    return false;
                }
                publication.commit();
            }

            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_STAGED_IRIS_WORLD_WITH_GENERATOR_IRIS_SEED, MessageArgument.untrusted("name", name), MessageArgument.untrusted("value", dimension.getLoadKey()), MessageArgument.untrusted("seed", seed)));
            return true;
        } catch (LifecycleOperationCoordinator.BusyException e) {
            sender().sendMessage(C.YELLOW + e.getMessage());
            return false;
        } catch (Throwable e) {
            sender().sendMessage(C.RED + "Failed to stage the complete Iris world: " + e.getMessage());
            Iris.reportError("Failed to stage complete Folia world \"" + worldKey + "\".", e);
            return false;
        } finally {
            if (stagedWorld != null) {
                deleteDirectorySafely(stagedWorld.toFile());
            }
            if (worldLease != null) {
                worldLease.close();
            }
        }
    }

    private boolean registerWorldInBukkitYml(NamespacedKey worldKey, String dimension, Long seed) {
        String configuredWorldName = IrisWorldStorage.configuredWorldName(
                worldKey,
                IrisWorldStorage.levelRoot().getName()
        );
        try {
            BukkitWorldConfiguration.register(BUKKIT_YML, configuredWorldName, dimension, seed);
            Iris.info("Registered \"" + configuredWorldName + "\" in bukkit.yml");
            return true;
        } catch (IOException e) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UPDATE_BUKKIT_YML, MessageArgument.untrusted("value", String.valueOf(e.getMessage()))));
            Iris.error("Failed to update bukkit.yml!");
            Iris.reportError(e);
            return false;
        }
    }

    private void deleteDirectorySafely(File directory) {
        try {
            AtomicDirectoryPublisher.deleteTree(directory.toPath());
        } catch (IOException e) {
            Iris.reportError("Failed to roll back staged world folder \"" + directory.getAbsolutePath() + "\".", e);
        }
    }

    private boolean reportExpectedCreationInterruption(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof LifecycleOperationCoordinator.BusyException) {
                sender().sendMessage(C.YELLOW + current.getMessage());
                return true;
            }
            current = current.getCause();
        }
        String message = failure.getMessage();
        if (message != null && message.contains("queued a restart")) {
            sender().sendMessage(C.YELLOW + message);
            return true;
        }
        return false;
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
            @Param(description = "The loaded or disk-only Iris world to remove", descriptionKey = "iris.director.commandiris.param.world_remove", customHandler = ManagedWorldNameHandler.class)
            String world,
            @Param(description = "Whether to also remove the folder (if set to false, just does not load the world)", descriptionKey = "iris.director.commandiris.param.whether_also_remove_folder_if_set_false_just_does_not_load_world", defaultValue = "true")
            boolean delete
    ) {
        VolmitSender responseSender = sender();
        responseSender.sendMessage(C.GRAY + "Removing Iris world '" + world + "'...");
        IrisWorldRemovalService.get().remove(world, delete).whenComplete((result, throwable) -> {
            Runnable response = () -> reportRemovalResult(responseSender, world, result, throwable);
            if (responseSender.isPlayer() && J.runEntity(responseSender.player(), response)) {
                return;
            }
            J.s(response);
        });
    }

    private void reportRemovalResult(
            VolmitSender responseSender,
            String requestedWorld,
            IrisWorldRemovalService.RemovalResult result,
            Throwable throwable
    ) {
        if (throwable != null || result == null) {
            Throwable failure = throwable == null
                    ? new IllegalStateException("World removal returned no result.")
                    : throwable;
            responseSender.sendMessage(C.RED + "World removal failed unexpectedly; nothing further was deleted.");
            Iris.reportError("Unexpected world removal failure for \"" + requestedWorld + "\".", failure);
            return;
        }

        switch (result.status()) {
            case UNREGISTERED -> responseSender.sendMessage(C.GREEN + "Unloaded and unregistered '"
                    + result.target().logicalName() + "'; its files were preserved.");
            case DELETED -> responseSender.sendMessage(C.GREEN + "Removed Iris world '"
                    + result.target().logicalName() + "' and deleted its folder.");
            case DELETE_QUEUED -> responseSender.sendMessage(C.YELLOW + "Removed Iris world '"
                    + result.target().logicalName() + "'; its quarantined folder will be deleted at startup.");
            case BUSY -> responseSender.sendMessage(C.YELLOW + "World changes are busy with "
                    + result.blockingOperation().kind().name().toLowerCase(Locale.ROOT) + " for '"
                    + result.blockingOperation().target() + "'. Try again when it completes.");
            case INVALID_IDENTIFIER, PROTECTED_WORLD, NOT_IRIS_WORLD, UNSAFE_PATH, NOT_FOUND ->
                    responseSender.sendMessage(C.RED + removalFailureDetail(result));
            default -> {
                responseSender.sendMessage(C.RED + "World removal stopped at "
                        + result.status().name().toLowerCase(Locale.ROOT) + ": " + removalFailureDetail(result));
                if (result.quarantineDirectory() != null) {
                    responseSender.sendMessage(C.YELLOW + "The recoverable world folder is "
                            + result.quarantineDirectory().toAbsolutePath() + ".");
                } else if (result.configurationChanged() || result.registryChanged()) {
                    responseSender.sendMessage(C.YELLOW + "Removal changed registration state before stopping; "
                            + "the original world folder was not deleted.");
                }
                if (result.failure() != null) {
                    Iris.reportError("World removal failed for \"" + requestedWorld + "\" at "
                            + result.status().name() + ".", result.failure());
                }
            }
        }
    }

    private String removalFailureDetail(IrisWorldRemovalService.RemovalResult result) {
        Throwable failure = result.failure();
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return result.status().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return failure.getMessage();
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
            @Param(name = "pack", description = "The built-in pack to download", descriptionKey = "iris.director.commandiris.param.pack_download", defaultValue = NO_DOWNLOAD_SOURCE, customHandler = DownloadPackHandler.class)
            String pack,
            @Param(name = "link", description = "A direct HTTP or HTTPS zip link", defaultValue = NO_DOWNLOAD_SOURCE)
            String link
    ) {
        String builtInPack = NO_DOWNLOAD_SOURCE.equals(pack) ? null : pack;
        String directLink = NO_DOWNLOAD_SOURCE.equals(link) ? null : link;
        if ((builtInPack == null) == (directLink == null)) {
            sender().sendMessage("Use exactly one source: /iris download pack=overworld, /iris download pack=underworld, or /iris download link=<zip-url>.");
            return;
        }
        if (builtInPack != null) {
            sender().sendMessage("Downloading built-in Iris pack '" + builtInPack + "' from its beta release.");
            Iris.service(StudioSVC.class).downloadBuiltIn(sender(), builtInPack);
            return;
        }
        if (!PackDownloader.isDirectZipUrl(directLink)) {
            sender().sendMessage("Iris requires link= to contain a valid HTTP or HTTPS .zip URL.");
            return;
        }
        sender().sendMessage("Downloading Iris pack from " + directLink + ".");
        Iris.service(StudioSVC.class).downloadUrl(sender(), directLink);
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
        VolmitSender responseSender = sender();
        responseSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_UNLOADING_WORLD, MessageArgument.untrusted("value", world.getName())));
        LifecycleOperationCoordinator.Lease lease;
        try {
            lease = LifecycleOperationCoordinator.get().acquire(
                    LifecycleOperationCoordinator.Domain.WORLD_MUTATION,
                    LifecycleOperationCoordinator.OperationKind.WORLD_UNLOAD,
                    WorldIdentity.serialize(world)
            );
        } catch (LifecycleOperationCoordinator.BusyException e) {
            responseSender.sendMessage(C.YELLOW + e.getMessage());
            return;
        }
        PlatformChunkGenerator generator = IrisToolbelt.access(world);
        IrisToolbelt.beginWorldMaintenance(world, "world-unload", true);
        try {
            AtomicBoolean terminalTimeout = new AtomicBoolean(false);
            CompletableFuture<Boolean> sequence = IrisToolbelt.evacuateAsync(world)
                    .thenCompose(evacuated -> {
                        if (terminalTimeout.get()) {
                            return CompletableFuture.failedFuture(new TimeoutException(
                                    "World unload stopped after its terminal timeout."));
                        }
                        if (!Boolean.TRUE.equals(evacuated)) {
                            return CompletableFuture.completedFuture(false);
                        }
                        return WorldLifecycleService.get().unloadAsync(world, true);
                    })
                    .thenCompose(unloaded -> {
                        if (terminalTimeout.get()) {
                            return CompletableFuture.failedFuture(new TimeoutException(
                                    "World unload stopped after its terminal timeout."));
                        }
                        if (!Boolean.TRUE.equals(unloaded) || generator == null) {
                            return CompletableFuture.completedFuture(Boolean.TRUE.equals(unloaded));
                        }
                        return generator.closeAsync().thenApply(ignored -> true);
                    });
            guardUnloadCompletion(sequence, terminalTimeout, world.getName())
                    .whenComplete((unloaded, throwable) -> {
                        IrisToolbelt.endWorldMaintenance(world, "world-unload", true);
                        lease.close();
                        Runnable response = () -> reportUnloadResult(responseSender, world, unloaded, throwable);
                        if (responseSender.isPlayer() && J.runEntity(responseSender.player(), response)) {
                            return;
                        }
                        J.s(response);
                    });
        } catch (Exception e) {
            IrisToolbelt.endWorldMaintenance(world, "world-unload", true);
            lease.close();
            responseSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UNLOAD_WORLD_3, MessageArgument.untrusted("value", String.valueOf(e.getMessage()))));
            Iris.reportError("Failed to unload world \"" + world.getName() + "\".", e);
        }
    }

    private CompletableFuture<Boolean> guardUnloadCompletion(
            CompletableFuture<Boolean> source,
            AtomicBoolean terminalTimeout,
            String worldName
    ) {
        CompletableFuture<Boolean> guarded = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        source.whenComplete((unloaded, throwable) -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            if (throwable == null) {
                guarded.complete(Boolean.TRUE.equals(unloaded));
            } else {
                guarded.completeExceptionally(throwable);
            }
        });
        CompletableFuture.delayedExecutor(WORLD_UNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            terminalTimeout.set(true);
            TimeoutException timeout = new TimeoutException(
                    "World unload did not settle within " + WORLD_UNLOAD_TIMEOUT_SECONDS
                            + " seconds for \"" + worldName + "\".");
            ServerConfigurator.restart("World unload timed out for \"" + worldName + "\".");
            guarded.completeExceptionally(timeout);
        });
        return guarded;
    }

    private void reportUnloadResult(VolmitSender responseSender, World world, Boolean unloaded, Throwable throwable) {
        if (throwable != null) {
            responseSender.sendMessage(IrisLanguage.text(
                    BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UNLOAD_WORLD_3,
                    MessageArgument.untrusted("value", String.valueOf(throwable.getMessage()))
            ));
            Iris.reportError("Failed to unload world \"" + world.getName() + "\".", throwable);
            return;
        }
        if (Boolean.TRUE.equals(unloaded)) {
            responseSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_WORLD_UNLOADED_SUCCESSFULLY));
        } else {
            responseSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_FAILED_UNLOAD_WORLD_2));
        }
    }

    @Director(description = "Load an Iris World", descriptionKey = "iris.director.commandiris.director.load_iris_world", origin = DirectorOrigin.PLAYER, sync = true, aliases = {"import"})
    public void loadWorld(
            @Param(
                    description = "The name of the world to load",
                    descriptionKey = "iris.director.commandiris.param.name_world_load",
                    customHandler = ManagedWorldNameHandler.class)
            String world
    ) {
        NamespacedKey worldKey;
        try {
            worldKey = IrisWorldStorage.managedKeyFromName(world);
            IrisWorldStorage.requireSafeManagedDimensionRoot(worldKey);
        } catch (IllegalArgumentException failure) {
            sender().sendMessage(C.RED + failure.getMessage());
            return;
        }
        String logicalWorldName = IrisWorldStorage.logicalName(worldKey);
        boolean worldExists = doesWorldExist(logicalWorldName);

        if (!worldExists) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_DOESNT_EXIST_ON_SERVER, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
            return;
        }

        VolmitSender responseSender = sender();
        responseSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_IRIS_LOADING_WORLD, MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
        Iris.instance.worldReconciler()
                .loadWorld(BUKKIT_YML, worldKey.toString())
                .whenComplete((result, failure) -> {
                    Runnable response = () -> reportLoadWorldResult(
                            responseSender,
                            logicalWorldName,
                            result,
                            failure);
                    if (responseSender.isPlayer() && J.runEntity(responseSender.player(), response)) {
                        return;
                    }
                    J.s(response);
                });
    }

    private void reportLoadWorldResult(
            VolmitSender responseSender,
            String logicalWorldName,
            BukkitWorldReconciler.LoadResult result,
            Throwable failure
    ) {
        if (failure != null) {
            responseSender.sendMessage(C.RED + "Failed to load Iris world \"" + logicalWorldName + "\": " + failure.getMessage());
            Iris.reportError("Failed to load Iris world \"" + logicalWorldName + "\".", failure);
            return;
        }
        if (result == null) {
            IllegalStateException missingResult = new IllegalStateException("World load completed without a result.");
            responseSender.sendMessage(C.RED + missingResult.getMessage());
            Iris.reportError("Failed to load Iris world \"" + logicalWorldName + "\".", missingResult);
            return;
        }
        if (result.succeeded()) {
            responseSender.sendMessage(IrisLanguage.text(
                    BukkitCommandMessagesExtended.COMMAND_IRIS_LOADED_SUCCESSFULLY,
                    MessageArgument.untrusted("logicalWorldName", logicalWorldName)));
            return;
        }

        C color = result.status() == BukkitWorldReconciler.ReconciliationStatus.BUSY
                || result.status() == BukkitWorldReconciler.ReconciliationStatus.RESTART_REQUIRED
                ? C.YELLOW
                : C.RED;
        responseSender.sendMessage(color + result.message());
        Throwable resultFailure = result.failure();
        if (resultFailure != null
                && result.status() != BukkitWorldReconciler.ReconciliationStatus.BUSY
                && result.status() != BukkitWorldReconciler.ReconciliationStatus.RESTART_REQUIRED) {
            Iris.reportError("Failed to load Iris world \"" + logicalWorldName + "\".", resultFailure);
        }
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

    public static class ManagedWorldNameHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            Set<String> options = new LinkedHashSet<>();
            for (World world : Bukkit.getWorlds()) {
                if (IrisToolbelt.isIrisWorld(world)) {
                    options.add(IrisWorldStorage.logicalName(world));
                }
            }
            for (String identity : IrisWorlds.get().getWorlds().keySet()) {
                try {
                    options.add(IrisWorldStorage.logicalName(WorldIdentity.parse(identity)));
                } catch (IllegalArgumentException ignored) {
                }
            }

            File namespace = new File(IrisWorldStorage.levelRoot(), "dimensions/iris");
            File[] diskWorlds = namespace.listFiles(File::isDirectory);
            if (diskWorlds != null) {
                for (File diskWorld : diskWorlds) {
                    if (!Files.isSymbolicLink(diskWorld.toPath())
                            && diskWorld.getName().matches("[a-z0-9_-]+")) {
                        options.add(diskWorld.getName());
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
            if (in == null || in.isBlank()) {
                throw new DirectorParsingException("World identifier cannot be empty");
            }
            return in.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }

    public static class PackDimensionTypeHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            Set<String> options = new LinkedHashSet<>();
            options.add("default");

            File packsFolder = Iris.instance.getDataFolder("packs");
            for (File pack : PackDirectoryResolver.listVisiblePackDirectories(packsFolder)) {
                options.add(pack.getName());

                try {
                    IrisData data = IrisData.get(pack);
                    for (String key : data.getDimensionLoader().getPossibleKeys()) {
                        options.add(packDimensionOption(pack.getName(), key));
                    }
                } catch (Throwable ex) {
                    Iris.warn("Failed to read dimension keys from pack %s: %s%s",
                            pack.getName(),
                            ex.getClass().getSimpleName(),
                            ex.getMessage() == null ? "" : " - " + ex.getMessage());
                    Iris.reportError(ex);
                }
            }

            return new KList<>(options);
        }

        static String packDimensionOption(String packName, String dimensionKey) {
            return packName.equalsIgnoreCase(dimensionKey)
                    ? packName
                    : packName + ":" + dimensionKey;
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

    public static class DownloadPackHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            return new KList<>(PackDownloader.builtInPacks());
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (NO_DOWNLOAD_SOURCE.equals(in)) {
                return null;
            }
            String pack = in == null ? "" : in.trim().toLowerCase(Locale.ROOT);
            if (!PackDownloader.isBuiltInPack(pack)) {
                throw new DirectorParsingException("Pack must be 'overworld' or 'underworld'");
            }
            return pack;
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }

}

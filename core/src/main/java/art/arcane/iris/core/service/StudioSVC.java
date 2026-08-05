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

package art.arcane.iris.core.service;

import com.google.gson.JsonSyntaxException;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.DatapackInstallResult;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.AtomicDirectoryPublisher;
import art.arcane.iris.core.pack.PackDirectoryResolver;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.project.IrisPackageCompiler;
import art.arcane.iris.core.project.IrisCodeWorkspace;
import art.arcane.iris.core.project.IrisProjectCopier;
import art.arcane.iris.core.runtime.StudioOpenCoordinator;
import art.arcane.iris.core.runtime.TransientWorldCleanupSupport;
import art.arcane.iris.core.runtime.WorldDeletionQueue;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.PackDownloadMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
public class StudioSVC implements IrisService {
    public static final String LISTING = "https://raw.githubusercontent.com/IrisDimensions/_listing/main/listing-v2.json";
    public static final String WORKSPACE_NAME = "packs";
    private static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final AtomicCache<Integer> counter = new AtomicCache<>();
    private final KMap<String, String> cacheListing = null;
    private final StudioTransitionQueue studioTransitions = new StudioTransitionQueue();
    private volatile IrisProject activeProject;
    private volatile CompletableFuture<StudioOpenCoordinator.StudioOpenResult> activeOpen;

    @Override
    public void onEnable() {
        J.a(() -> {
            String pack = IrisSettings.get().getGenerator().getDefaultWorldType();

            // Presence means a non-empty pack folder: an empty leftover folder must still
            // trigger the install instead of shadowing it forever.
            if (!PackDownloader.isPackPresent(getWorkspaceFolder(), pack)) {
                if (PackDownloader.isDefaultOverworld(pack)) {
                    IrisLogging.info("Downloading Default Pack " + pack + " (beta release)");
                    IrisServices.get(StudioSVC.class).downloadDefaultOverworld(BukkitPlatform.console(), false);
                } else {
                    IrisLogging.warn("Default pack '" + pack + "' is not installed. Please download it manually with /iris download " + pack);
                }
            }
        });
    }

    @Override
    public void onDisable() {
        IrisLogging.debug("Studio Mode Active: Closing Projects");
        boolean stopping = IrisToolbelt.isServerStopping();
        LinkedHashSet<String> worldNamesToDelete = new LinkedHashSet<>(TransientWorldCleanupSupport.collectTransientStudioWorldNames(IrisWorldStorage.levelRoot()));

        if (activeProject != null) {
            PlatformChunkGenerator activeProvider = activeProject.getActiveProvider();
            if (activeProvider != null) {
                String activeWorldName = IrisWorldStorage.logicalName(
                        WorldIdentity.parse(activeProvider.getTarget().getWorld().identity()));
                if (activeWorldName != null && !activeWorldName.isBlank()) {
                    worldNamesToDelete.add(activeWorldName);
                }
            }
        }

        for (World i : Bukkit.getWorlds()) {
            if (!IrisToolbelt.isIrisWorld(i) || !IrisToolbelt.isStudio(i)) {
                continue;
            }

            worldNamesToDelete.add(IrisWorldStorage.logicalName(i));
            PlatformChunkGenerator generator = IrisToolbelt.access(i);
            if (!stopping) {
                destroyStudioWorld(i, generator);
                continue;
            }

            if (generator != null) {
                try {
                    generator.close();
                } catch (Throwable e) {
                    IrisLogging.reportError("Failed to close studio generator for \"" + i.getName() + "\" during shutdown.", e);
                }
            }
        }

        activeProject = null;

        try {
            art.arcane.iris.core.tools.IrisCreator.removeTransientStudioWorldsFromBukkitYml();
        } catch (Throwable e) {
            IrisLogging.reportError("Failed to unregister transient studio worlds from bukkit.yml during shutdown.", e);
        }

        queueStudioWorldDeletionOnStartup(worldNamesToDelete);
    }

    public IrisDimension installIntoWorld(VolmitSender sender, IrisDimension dimension, File folder) {
        return installIntoDirectory(sender, dimension, new File(folder, "iris/pack"), false);
    }

    public IrisDimension replaceIntoWorld(VolmitSender sender, IrisDimension dimension, File folder) {
        return installIntoDirectory(sender, dimension, new File(folder, "iris/pack"), true);
    }

    public IrisDimension replaceIntoPackDirectory(VolmitSender sender, IrisDimension dimension, File folder) {
        return installIntoDirectory(sender, dimension, folder, true);
    }

    private IrisDimension installIntoDirectory(
            VolmitSender sender,
            IrisDimension dimension,
            File folder,
            boolean replaceExisting
    ) {
        if (J.isPrimaryThread()) {
            sender.sendMessage("Iris refused to copy a pack on the Bukkit primary thread.");
            return null;
        }
        String dimensionKey = dimension.getLoadKey();
        Path source;
        try {
            source = resolveSafePackSource(dimension.getLoader().getDataFolder());
        } catch (IOException e) {
            IrisLogging.reportError("Failed to inspect source dimension pack '" + dimensionKey + "'.", e);
            sender.sendMessage("Failed to install studio pack '" + dimensionKey + "': " + errorDetail(e));
            return null;
        }
        Path target = folder.toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        Path stage = null;
        AtomicDirectoryPublisher.Publication publication = null;
        IrisData previousData = IrisData.getLoaded(target.toFile()).orElse(null);
        IrisData createdData = null;
        boolean refreshedPreviousData = false;
        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_INSTALLING_PACKAGE, MessageArgument.untrusted("name", String.valueOf(source.getFileName())), MessageArgument.untrusted("loadKey", String.valueOf(dimensionKey))));
        try {
            if (parent == null) {
                throw new IOException("World pack target has no parent: " + target);
            }
            Files.createDirectories(parent);
            if (!replaceExisting
                    && (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))) {
                throw new FileAlreadyExistsException(target.toString());
            }

            stage = Files.createTempDirectory(parent, ".pack.installing-");
            copyPackTree(source, stage);
            IrisData stagedData = IrisData.openDatapackCompiler(stage.toFile());
            try {
                IrisDimension stagedDimension = stagedData.getDimensionLoader().load(dimensionKey);
                if (stagedDimension == null) {
                    throw new IOException("Copied pack does not contain a loadable dimension '" + dimensionKey + "'.");
                }
            } finally {
                stagedData.close();
            }
            publication = AtomicDirectoryPublisher.publish(stage, target);
            stage = null;

            IrisData installedData;
            boolean activeRuntime = previousData != null && !previousData.getEngines().isEmpty();
            if (previousData == null) {
                createdData = IrisData.get(target.toFile());
                installedData = createdData;
            } else if (!activeRuntime) {
                previousData.hotloaded();
                refreshedPreviousData = true;
                installedData = previousData;
            } else {
                installedData = previousData;
            }
            IrisDimension installedDimension = activeRuntime
                    ? dimension
                    : installedData.getDimensionLoader().load(dimensionKey);
            if (installedDimension == null) {
                throw new IOException("Published pack does not contain a loadable dimension '" + dimensionKey + "'.");
            }
            publication.commit();
            try {
                publication.cleanupBackup();
            } catch (IOException cleanupFailure) {
                IrisLogging.warn("World pack was committed but its backup could not be removed: "
                        + cleanupFailure.getMessage());
            }
            if (activeRuntime) {
                ServerConfigurator.restart("An active Iris world pack was replaced.");
            }
            return installedDimension;
        } catch (Throwable e) {
            rollbackFailedPublication(createdData, publication, e);
            if (refreshedPreviousData) {
                try {
                    previousData.hotloaded();
                } catch (Throwable restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            IrisLogging.reportError("Failed to install dimension pack '" + dimensionKey + "' into " + folder.getPath(), e);
            sender.sendMessage("Failed to install studio pack '" + dimensionKey + "': " + errorDetail(e));
            return null;
        } finally {
            if (stage != null) {
                try {
                    AtomicDirectoryPublisher.deleteTree(stage);
                } catch (IOException cleanupFailure) {
                    IrisLogging.reportError("Failed to clean staged world pack " + stage, cleanupFailure);
                }
            }
        }
    }

    static void rollbackFailedPublication(
            IrisData createdData,
            AtomicDirectoryPublisher.Publication publication,
            Throwable failure
    ) {
        if (createdData != null) {
            try {
                createdData.close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (publication != null) {
            try {
                publication.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    static Path resolveSafePackSource(File sourceFolder) throws IOException {
        PackDirectoryResolver.requireSafePackTree(sourceFolder);
        Path source = sourceFolder.toPath().toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("Source pack is missing or unsafe: " + sourceFolder);
        }
        return source;
    }

    public IrisDimension installInto(VolmitSender sender, String type, File folder) {
        if (J.isPrimaryThread()) {
            sender.sendMessage("Iris refused to download or copy a pack on the Bukkit primary thread.");
            return null;
        }
        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_LOOKING_PACKAGE, MessageArgument.untrusted("type", String.valueOf(type))));
        IrisDimension dimension = IrisData.loadAnyDimension(type, null);
        if (dimension == null) {
            sender.sendMessage("Iris cannot repair world pack '" + type
                    + "' because no installed source contains it.");
            return null;
        }
        return replaceIntoPackDirectory(sender, dimension, folder);
    }

    public void downloadSearch(VolmitSender sender, String key) {
        downloadSearch(sender, key, false);
    }

    public void downloadSearch(VolmitSender sender, String key, boolean forceOverwrite) {
        runPackMutation(sender, LifecycleOperationCoordinator.OperationKind.PACK_DOWNLOAD, key, () -> {
            DownloadOutcome outcome = downloadSearchLocked(sender, key, forceOverwrite);
            return finishStandalonePackMutation(sender, outcome);
        }, IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_DOWNLOAD, MessageArgument.untrusted("key", String.valueOf(key))));
    }

    public void downloadDefaultOverworld(VolmitSender sender, boolean forceOverwrite) {
        String key = PackDownloader.defaultOverworldPack();
        runPackMutation(sender, LifecycleOperationCoordinator.OperationKind.PACK_DOWNLOAD, key, () -> {
            DownloadOutcome outcome = downloadDefaultOverworldLocked(sender, forceOverwrite);
            return finishStandalonePackMutation(sender, outcome);
        }, IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_DOWNLOAD_IRISDIMENSIONS_OVERWORLD_BETA_RELEASE));
    }

    public void downloadBranch(VolmitSender sender, String repo, String branch, boolean forceOverwrite) {
        runPackMutation(sender, LifecycleOperationCoordinator.OperationKind.PACK_DOWNLOAD, repo + "/" + branch, () -> {
            DownloadOutcome outcome = downloadLocked(sender, repo, branch, forceOverwrite, false, null);
            return finishStandalonePackMutation(sender, outcome);
        }, IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_DOWNLOAD_BRANCH, MessageArgument.untrusted("repo", String.valueOf(repo)), MessageArgument.untrusted("branch", String.valueOf(branch))));
    }

    public void download(VolmitSender sender, String repo, String branch) throws JsonSyntaxException, IOException {
        download(sender, repo, branch, false, false);
    }

    public void download(VolmitSender sender, String repo, String branch, boolean forceOverwrite, boolean directUrl) throws JsonSyntaxException, IOException {
        download(sender, repo, branch, forceOverwrite, directUrl, null);
    }

    public void download(VolmitSender sender, String repo, String branch, boolean forceOverwrite, boolean directUrl, String expectedKey) throws JsonSyntaxException, IOException {
        String target = expectedKey == null ? repo + "/" + branch : expectedKey;
        runPackMutation(sender, LifecycleOperationCoordinator.OperationKind.PACK_DOWNLOAD, target, () -> {
            DownloadOutcome outcome = downloadLocked(sender, repo, branch, forceOverwrite, directUrl, expectedKey);
            return finishStandalonePackMutation(sender, outcome);
        }, "Failed to download Iris pack '" + target + "'.");
    }

    private DownloadOutcome downloadSearchLocked(VolmitSender sender, String key, boolean forceOverwrite) throws IOException {
        if (PackDownloader.isDefaultOverworld(key)) {
            return downloadDefaultOverworldLocked(sender, forceOverwrite);
        }

        String descriptor = key.contains("/") ? key : getListing(false).get(key);
        if (descriptor == null) {
            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_PACK_WAS_NOT_FOUND_PACK_LISTING, MessageArgument.untrusted("key", String.valueOf(key))));
            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_USE_IRIS_DOWNLOAD_PACK_BRANCH_BRANCH_DOWNLOAD_MANUALLY));
            return DownloadOutcome.notChanged();
        }

        PackListingReference reference = resolvePackListingReference(key, descriptor);
        IrisLogging.info("Resolved pack '" + key + "' to " + reference.repository() + "/" + reference.ref());
        return downloadLocked(
                sender,
                reference.repository(),
                reference.ref(),
                forceOverwrite,
                false,
                reference.expectedKey()
        );
    }

    static PackListingReference resolvePackListingReference(String key, String descriptor) {
        String[] nodes = descriptor.split("\\Q/\\E");
        String repository = nodes.length == 1 ? "IrisDimensions/" + nodes[0] : nodes[0] + "/" + nodes[1];
        String ref = nodes.length > 2 ? nodes[2] : "HEAD";
        String expectedKey = key.contains("/") && nodes.length > 1 ? nodes[1] : key;
        return new PackListingReference(repository, ref, expectedKey);
    }

    private DownloadOutcome downloadDefaultOverworldLocked(VolmitSender sender, boolean forceOverwrite) throws IOException {
        String expectedKey = PackDownloader.defaultOverworldPack();
        if (!forceOverwrite && PackDownloader.isPackPresent(getWorkspaceFolder(), expectedKey)) {
            sender.sendMessage(IrisLanguage.text(PackDownloadMessages.ALREADY_INSTALLED, MessageArgument.untrusted("key", expectedKey)));
            return DownloadOutcome.notChanged();
        }

        PackDownloader.PackInstallResult result = PackDownloader.downloadDefaultOverworld(
                getWorkspaceFolder(),
                forceOverwrite,
                sender::sendMessage
        );
        return DownloadOutcome.from(result);
    }

    private DownloadOutcome downloadLocked(
            VolmitSender sender,
            String repo,
            String branch,
            boolean forceOverwrite,
            boolean directUrl,
            String expectedKey
    ) throws IOException {
        if (!forceOverwrite && PackDownloader.isPackPresent(getWorkspaceFolder(), expectedKey)) {
            sender.sendMessage(IrisLanguage.text(PackDownloadMessages.ALREADY_INSTALLED, MessageArgument.untrusted("key", expectedKey)));
            return DownloadOutcome.notChanged();
        }

        PackDownloader.PackInstallResult result = PackDownloader.download(
                getWorkspaceFolder(),
                repo,
                branch,
                forceOverwrite,
                directUrl,
                expectedKey,
                sender::sendMessage
        );
        return DownloadOutcome.from(result);
    }

    public KMap<String, String> getListing(boolean cached) {
        JSONObject a;

        if (cached) {
            a = new JSONObject(art.arcane.iris.util.common.misc.WebCache.getCached("cachedlisting", LISTING));
        } else {
            a = new JSONObject(art.arcane.iris.util.common.misc.WebCache.getNonCached(true + "listing", LISTING));
        }

        KMap<String, String> l = new KMap<>();

        for (String i : a.keySet()) {
            if (a.get(i) instanceof String)
                l.put(i, a.getString(i));
        }

        return l;
    }

    public boolean isProjectOpen() {
        return activeProject != null && activeProject.isOpen();
    }

    public void open(VolmitSender sender, String dimm) {
        open(sender, 1337, dimm);
    }

    public void open(VolmitSender sender, long seed, String dimm) {
        try {
            open(sender, seed, dimm, (w) -> {
            });
        } catch (Exception e) {
            IrisLogging.reportError("Failed to open studio world \"" + dimm + "\".", e);
            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_OPEN_STUDIO_WORLD, MessageArgument.untrusted("error", String.valueOf(e.getMessage()))));
        }
    }

    private static boolean blockIfPackBroken(VolmitSender sender, String dimm) {
        PackValidationResult validation = PackValidationRegistry.get(dimm);
        if (validation == null || validation.isLoadable()) {
            return false;
        }
        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_CANNOT_OPEN_STUDIO_PACK_HAS_BLOCKING_ERRORS, MessageArgument.untrusted("dimm", String.valueOf(dimm))));
        for (String reason : validation.getBlockingErrors()) {
            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_MESSAGE, MessageArgument.untrusted("reason", String.valueOf(reason))));
        }
        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_FIX_PACK_RUN_IRIS_PACK_VALIDATE_REVALIDATE, MessageArgument.untrusted("dimm", String.valueOf(dimm))));
        return true;
    }

    public void open(VolmitSender sender, long seed, String dimm, Consumer<World> onDone) throws IrisException {
        if (blockIfPackBroken(sender, dimm)) {
            return;
        }
        studioTransitions.submit(() -> replaceActiveProject(sender, seed, dimm, onDone))
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null) {
                        return;
                    }
                    IrisLogging.reportError("Failed to replace the active studio project with \"" + dimm + "\".", throwable);
                    J.s(() -> sender.sendMessage(IrisLanguage.text(
                            BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_OPEN_STUDIO_WORLD_2,
                            MessageArgument.untrusted("error", String.valueOf(errorDetail(throwable))))));
                });
    }

    private CompletableFuture<Void> replaceActiveProject(
            VolmitSender sender,
            long seed,
            String dimension,
            Consumer<World> onDone
    ) {
        return closeActiveProject().handle((closeResult, closeThrowable) -> {
            if (closeThrowable != null) {
                IrisLogging.reportError("Failed while closing an existing studio project before opening \"" + dimension + "\".", closeThrowable);
                J.s(() -> sender.sendMessage(IrisLanguage.text(
                        BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_CLOSE_EXISTING_STUDIO_PROJECT,
                        MessageArgument.untrusted("error", String.valueOf(errorDetail(closeThrowable))))));
                return false;
            }
            if (closeResult == null) {
                IllegalStateException failure = new IllegalStateException("Studio close completed without a result.");
                IrisLogging.reportError("Failed while closing an existing studio project before opening \"" + dimension + "\".", failure);
                J.s(() -> sender.sendMessage(IrisLanguage.text(
                        BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_CLOSE_EXISTING_STUDIO_PROJECT_2,
                        MessageArgument.untrusted("error", String.valueOf(errorDetail(failure))))));
                return false;
            }
            if (closeResult.failureCause() != null) {
                Throwable failure = closeResult.failureCause();
                IrisLogging.reportError("Failed while closing an existing studio project before opening \"" + dimension + "\".", failure);
                J.s(() -> sender.sendMessage(IrisLanguage.text(
                        BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_CLOSE_EXISTING_STUDIO_PROJECT_2,
                        MessageArgument.untrusted("error", String.valueOf(errorDetail(failure))))));
                return false;
            }
            return true;
        }).thenCompose(closed -> closed
                ? beginStudioOpen(sender, seed, dimension, onDone)
                : CompletableFuture.completedFuture(null));
    }

    private CompletableFuture<Void> beginStudioOpen(
            VolmitSender sender,
            long seed,
            String dimension,
            Consumer<World> onDone
    ) {
        IrisProject project = new IrisProject(new File(getWorkspaceFolder(), dimension));
        activeProject = project;
        CompletableFuture<StudioOpenCoordinator.StudioOpenResult> opening;
        try {
            opening = project.open(sender, seed, onDone);
        } catch (IrisException e) {
            if (activeProject == project) {
                activeProject = null;
            }
            J.s(() -> sender.sendMessage(IrisLanguage.text(
                    BukkitRuntimeMessages.STUDIO_S_V_C_FAILED_OPEN_STUDIO_WORLD_2,
                    MessageArgument.untrusted("error", String.valueOf(errorDetail(e))))));
            return CompletableFuture.completedFuture(null);
        }

        activeOpen = opening;
        return opening.handle((result, throwable) -> {
            if (activeOpen == opening) {
                activeOpen = null;
            }
            if (throwable != null && activeProject == project && !project.isOpen()) {
                activeProject = null;
            }
            return null;
        });
    }

    public void openVSCode(VolmitSender sender, String dim) {
        new IrisCodeWorkspace(new IrisProject(new File(getWorkspaceFolder(), dim))).openVSCode(sender);
    }

    public File getWorkspaceFolder(String... sub) {
        return art.arcane.iris.platform.bukkit.BukkitPlatform.volmitPlugin().getDataFolderList(WORKSPACE_NAME, sub);
    }

    public File getWorkspaceFile(String... sub) {
        return art.arcane.iris.platform.bukkit.BukkitPlatform.volmitPlugin().getDataFileList(WORKSPACE_NAME, sub);
    }

    public CompletableFuture<StudioOpenCoordinator.StudioCloseResult> close() {
        return studioTransitions.submit(this::closeActiveProject);
    }

    private CompletableFuture<StudioOpenCoordinator.StudioCloseResult> closeActiveProject() {
        IrisProject project = activeProject;
        if (project == null) {
            return CompletableFuture.completedFuture(new StudioOpenCoordinator.StudioCloseResult(
                    null,
                    true,
                    true,
                    false,
                    null
            ));
        }

        IrisLogging.debug("Closing Active Project");
        CompletableFuture<StudioOpenCoordinator.StudioCloseResult> closing = project.close();
        return closing.whenComplete((result, throwable) -> {
            if (throwable == null
                    && result != null
                    && result.failureCause() == null
                    && activeProject == project) {
                activeProject = null;
            }
        });
    }

    private void destroyStudioWorld(World world, PlatformChunkGenerator generator) {
        IrisToolbelt.beginWorldMaintenance(world, "studio-disable", true);
        try {
            IrisToolbelt.evacuate(world);
        } catch (Throwable e) {
            IrisLogging.reportError("Failed to evacuate studio world \"" + world.getName() + "\" during shutdown cleanup.", e);
        }
        try {
            WorldLifecycleService.get().unloadAsync(world, false)
                    .thenCompose(unloaded -> {
                        if (!Boolean.TRUE.equals(unloaded) || generator == null) {
                            return CompletableFuture.completedFuture(Boolean.TRUE.equals(unloaded));
                        }
                        return generator.closeAsync().thenApply(ignored -> true);
                    })
                    .whenComplete((unloaded, throwable) -> {
                        IrisToolbelt.endWorldMaintenance(world, "studio-disable");
                        if (throwable != null) {
                            IrisLogging.reportError("Failed to unload studio world \"" + world.getName()
                                    + "\" during disable cleanup; startup deletion remains queued.", throwable);
                        } else if (!Boolean.TRUE.equals(unloaded)) {
                            IrisLogging.warn("Studio world \"" + world.getName()
                                    + "\" remained loaded during disable cleanup; startup deletion remains queued.");
                        }
                    });
        } catch (Throwable e) {
            IrisToolbelt.endWorldMaintenance(world, "studio-disable");
            IrisLogging.reportError("Failed to unload studio world \"" + world.getName() + "\" during shutdown cleanup.", e);
        }
    }

    private void queueStudioWorldDeletionOnStartup(LinkedHashSet<String> worldNamesToDelete) {
        if (worldNamesToDelete.isEmpty()) {
            return;
        }

        LinkedHashSet<String> normalizedNames = new LinkedHashSet<>();
        for (String worldName : worldNamesToDelete) {
            String baseWorldName = TransientWorldCleanupSupport.transientStudioBaseWorldName(worldName);
            if (baseWorldName != null) {
                normalizedNames.add(baseWorldName);
                continue;
            }

            if (worldName != null && !worldName.isBlank()) {
                normalizedNames.add(worldName);
            }
        }

        if (normalizedNames.isEmpty()) {
            return;
        }

        try {
            IrisServices.get(WorldDeletionQueue.class).queueFamilyForStartupDeletion(List.copyOf(normalizedNames));
        } catch (IOException e) {
            IrisLogging.reportError("Failed to queue studio world deletion on startup.", e);
        }
    }

    public File compilePackage(VolmitSender sender, String d, boolean obfuscate, boolean minify) {
        return new IrisPackageCompiler(new IrisProject(new File(getWorkspaceFolder(), d))).compilePackage(sender, obfuscate, minify);
    }

    private void createFrom(File sourcePack, String sourceDimensionKey, String newName) throws IOException {
        if (J.isPrimaryThread()) {
            throw new IOException("Studio project copying cannot run on the Bukkit primary thread.");
        }
        String sourceKey = normalizeProjectName(sourceDimensionKey);
        String targetName = normalizeProjectName(newName);
        File workspace = requireSafeWorkspace(getWorkspaceFolder());
        File newPack = new File(workspace, targetName);
        IrisProjectCopier.copyProject(sourcePack, workspace, sourceKey, targetName);

        try {
            IrisProject project = new IrisProject(newPack);
            JSONObject workspaceConfiguration = new IrisCodeWorkspace(project).createCodeWorkspaceConfig();
            writeWorkspaceAtomically(newPack.toPath(), targetName, workspaceConfiguration.toString(0));
        } catch (Throwable e) {
            try {
                AtomicDirectoryPublisher.deleteTree(newPack.toPath());
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new IOException("Failed to create the editor workspace for project '" + targetName + "'.", e);
        }
    }

    public void create(VolmitSender sender, String s, String downloadable) {
        Runnable work = () -> createProject(sender, s, downloadable, null);
        runOffPrimaryThread(work);
    }

    public void create(VolmitSender sender, String name, IrisDimension template) {
        IrisData loader = template == null ? null : template.getLoader();
        File sourcePack = loader == null ? null : loader.getDataFolder();
        String sourceKey = template == null ? null : template.getLoadKey();
        Runnable work = () -> createProject(sender, name, sourceKey, sourcePack);
        runOffPrimaryThread(work);
    }

    public void create(VolmitSender sender, String s) {
        Runnable work = () -> createProject(sender, s, null, null);
        runOffPrimaryThread(work);
    }

    private void createProject(VolmitSender sender, String requestedName, String requestedTemplate, File selectedTemplatePack) {
        String normalizedName;
        String templateName;
        File workspace;
        try {
            normalizedName = normalizeProjectName(requestedName);
            templateName = requestedTemplate == null ? null : normalizeProjectName(requestedTemplate);
            workspace = requireSafeWorkspace(getWorkspaceFolder());
        } catch (IOException e) {
            sender.sendMessage("Studio project creation refused: " + errorDetail(e));
            return;
        }

        LifecycleOperationCoordinator.Lease lease;
        try {
            lease = LifecycleOperationCoordinator.get().acquire(
                    LifecycleOperationCoordinator.Domain.PACK_MUTATION,
                    LifecycleOperationCoordinator.OperationKind.PACK_CREATE,
                    normalizedName
            );
        } catch (LifecycleOperationCoordinator.BusyException e) {
            sendBusy(sender, e);
            return;
        }

        String projectName = normalizedName;
        File createdPack = null;
        boolean projectPublished = false;
        CreationOutcome outcome = CreationOutcome.FAILED;
        try {
            if ("studio".equals(projectName)) {
                projectName = nextAvailableProjectName(workspace, projectName);
            }

            File newPack = new File(workspace, projectName);
            createdPack = newPack;
            if (Files.exists(newPack.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(newPack.toPath())) {
                sender.sendMessage("Studio project '" + projectName + "' already exists; nothing was changed.");
                return;
            }

            if (templateName == null) {
                createStarterProject(workspace, projectName);
            } else {
                File importPack = selectedTemplatePack == null ? new File(workspace, templateName) : selectedTemplatePack;
                if (selectedTemplatePack == null && !hasLoadableDimensionFile(importPack, templateName)) {
                    downloadSearchLocked(sender, templateName, false);
                }
                if (!hasLoadableDimensionFile(importPack, templateName)) {
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_COULDN_T_FIND_PACK_CREATE_NEW_DIMENSION_FROM));
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_MISSING_IMPORTED_DIMENSION_FILE));
                    return;
                }

                sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.STUDIO_S_V_C_IMPORTING_INTO_NEW_PROJECT, MessageArgument.untrusted("downloadable", String.valueOf(templateName)), MessageArgument.untrusted("s", String.valueOf(projectName))));
                createFrom(importPack, templateName, projectName);
            }
            projectPublished = true;

            DatapackInstallResult installResult = ServerConfigurator.installDataPacksIfChanged(true);
            CreationOutcome installOutcome = switch (installResult.status()) {
                case FAILED -> CreationOutcome.FAILED;
                case RESTART_REQUIRED -> CreationOutcome.RESTART;
                case READY, UNCHANGED -> CreationOutcome.OPEN;
            };
            if (installOutcome == CreationOutcome.FAILED) {
                rollbackCreatedProject(sender, newPack, "Datapack installation failed; the new project was rolled back.");
                return;
            }

            sender.sendMessage("Created studio project '" + projectName + "' at " + newPack.getAbsolutePath() + ".");
            if (installOutcome == CreationOutcome.RESTART) {
                sender.sendMessage("The project is complete, but Iris must restart before opening it. After restart, run /iris studio open " + projectName + ".");
            }
            outcome = installOutcome;
        } catch (Throwable e) {
            if (projectPublished && createdPack != null) {
                rollbackCreatedProject(sender, createdPack, "Studio project creation failed; the new project was rolled back.");
            }
            IrisLogging.reportError("Failed to create studio project '" + projectName + "'.", e);
            sender.sendMessage("Studio project creation failed: " + errorDetail(e));
        } finally {
            closeLease(lease);
        }

        if (outcome == CreationOutcome.RESTART) {
            ServerConfigurator.restart();
        } else if (outcome == CreationOutcome.OPEN) {
            String completedProjectName = projectName;
            LifecycleOperationCoordinator.get().whenIdle(() -> open(sender, completedProjectName));
        }
    }

    private void runPackMutation(
            VolmitSender sender,
            LifecycleOperationCoordinator.OperationKind operationKind,
            String target,
            PackMutation mutation,
            String failureMessage
    ) {
        Runnable work = () -> {
            String operationTarget = target == null || target.isBlank() ? "unspecified-pack" : target.trim();
            LifecycleOperationCoordinator.Lease lease;
            try {
                lease = LifecycleOperationCoordinator.get().acquire(
                        LifecycleOperationCoordinator.Domain.PACK_MUTATION,
                        operationKind,
                        operationTarget
                );
            } catch (LifecycleOperationCoordinator.BusyException e) {
                sendBusy(sender, e);
                return;
            }

            boolean restartRequired;
            try {
                restartRequired = mutation.run();
            } catch (Throwable e) {
                IrisLogging.reportError(failureMessage, e);
                sender.sendMessage(failureMessage + " " + errorDetail(e));
                return;
            } finally {
                closeLease(lease);
            }

            if (restartRequired) {
                sender.sendMessage("Iris must restart before the new pack data can be used.");
                ServerConfigurator.restart();
            }
        };
        runOffPrimaryThread(work);
    }

    private boolean finishStandalonePackMutation(VolmitSender sender, DownloadOutcome outcome) {
        if (!outcome.changed()) {
            return false;
        }

        DatapackInstallResult installResult = ServerConfigurator.installDataPacksIfChanged(true);
        return switch (installResult.status()) {
            case FAILED -> {
                sender.sendMessage("The pack was downloaded, but Iris could not install its datapack output.");
                yield false;
            }
            case RESTART_REQUIRED -> true;
            case READY, UNCHANGED -> outcome.restartRequired();
        };
    }

    private void runOffPrimaryThread(Runnable work) {
        if (J.isPrimaryThread()) {
            J.a(work);
            return;
        }
        work.run();
    }

    static String normalizeProjectName(String value) throws IOException {
        if (value == null) {
            throw new IOException("Project name cannot be empty.");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 64 || !PROJECT_NAME.matcher(normalized).matches()) {
            throw new IOException("Invalid project name '" + value + "' (allowed: lowercase a-z, 0-9, _ and -).");
        }
        return normalized;
    }

    static File requireSafeWorkspace(File workspaceFolder) throws IOException {
        if (workspaceFolder == null) {
            throw new IOException("Pack workspace is unavailable.");
        }
        Path workspace = workspaceFolder.toPath().toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pack workspace is missing or unsafe: " + workspace);
        }
        return workspace.toFile();
    }

    static String nextAvailableProjectName(File workspace, String baseName) throws IOException {
        String normalizedBase = normalizeProjectName(baseName);
        Path root = requireSafeWorkspace(workspace).toPath();
        String candidate = normalizedBase;
        int suffix = 2;
        while (Files.exists(root.resolve(candidate), LinkOption.NOFOLLOW_LINKS)) {
            candidate = normalizedBase + suffix++;
        }
        return candidate;
    }

    static void createStarterProject(File workspace, String projectName) throws IOException {
        File safeWorkspace = requireSafeWorkspace(workspace);
        String safeName = normalizeProjectName(projectName);
        Path target = safeWorkspace.toPath().resolve(safeName);
        Path stage = Files.createTempDirectory(safeWorkspace.toPath(), ".iris-starter-" + safeName + "-");
        boolean published = false;
        try {
            Files.createDirectories(stage.resolve("dimensions"));
            Files.createDirectories(stage.resolve("regions"));
            Files.createDirectories(stage.resolve("biomes"));
            Files.createDirectories(stage.resolve("generators"));
            Files.writeString(stage.resolve("dimensions/" + safeName + ".json"), """
                    {
                      "name": "%s",
                      "version": 1,
                      "regions": ["starter"],
                      "logicalHeight": 384,
                      "dimensionHeight": {"min": -64, "max": 320}
                    }
                    """.formatted(safeName), StandardCharsets.UTF_8);
            Files.writeString(stage.resolve("regions/starter.json"), """
                    {
                      "name": "Starter",
                      "landBiomes": ["starter"],
                      "seaBiomes": ["starter"],
                      "shoreBiomes": ["starter"]
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(stage.resolve("biomes/starter.json"), """
                    {
                      "name": "Starter Plains",
                      "layers": [{"palette": [{"block": "minecraft:grass_block"}]}],
                      "generators": [{"generator": "flat", "min": 96, "max": 96}],
                      "derivative": "minecraft:plains",
                      "vanillaDerivative": "minecraft:plains"
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(stage.resolve("generators/flat.json"), """
                    {
                      "interpolator": {"function": "NONE", "horizontalScale": 1},
                      "seed": 310,
                      "composite": [{"seed": 310, "style": {"style": "FLAT"}}]
                    }
                    """, StandardCharsets.UTF_8);
            publishNewDirectory(stage, target);
            published = true;
            IrisProject project = new IrisProject(target.toFile());
            JSONObject workspaceConfiguration = new IrisCodeWorkspace(project).createCodeWorkspaceConfig();
            writeWorkspaceAtomically(target, safeName, workspaceConfiguration.toString(0));
        } catch (Throwable failure) {
            if (published) {
                Throwable cleanupFailure = rollbackCreatedProjectFiles(target.toFile());
                if (cleanupFailure != null && cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("Failed to create starter project '" + safeName + "'.", failure);
        } finally {
            AtomicDirectoryPublisher.deleteTree(stage);
        }
    }

    private static boolean hasLoadableDimensionFile(File pack, String key) {
        Path packPath = pack.toPath().toAbsolutePath().normalize();
        Path dimension = packPath.resolve("dimensions").resolve(key + ".json").normalize();
        try {
            PackDirectoryResolver.requireSafePackTree(pack);
        } catch (IOException exception) {
            return false;
        }
        return dimension.startsWith(packPath)
                && Files.isDirectory(packPath)
                && !Files.isSymbolicLink(dimension)
                && Files.isRegularFile(dimension, LinkOption.NOFOLLOW_LINKS);
    }

    private static void rollbackCreatedProject(VolmitSender sender, File project, String message) {
        Throwable cleanupFailure = rollbackCreatedProjectFiles(project);
        if (cleanupFailure == null) {
            sender.sendMessage(message);
            return;
        }
        IrisLogging.reportError("Failed to fully roll back studio project " + project.getPath(), cleanupFailure);
        sender.sendMessage(message + " Cleanup was incomplete; check the console and "
                + project.getAbsolutePath() + ".");
    }

    static Throwable rollbackCreatedProjectFiles(File project) {
        File target = project.toPath().toAbsolutePath().normalize().toFile();
        Throwable cleanupFailure = null;
        IrisData loadedData = IrisData.getLoaded(target).orElse(null);
        if (loadedData != null) {
            try {
                loadedData.close();
            } catch (Throwable closeFailure) {
                cleanupFailure = closeFailure;
            }
        }
        try {
            AtomicDirectoryPublisher.deleteTree(target.toPath());
        } catch (Throwable deleteFailure) {
            if (cleanupFailure == null) {
                cleanupFailure = deleteFailure;
            } else if (cleanupFailure != deleteFailure) {
                cleanupFailure.addSuppressed(deleteFailure);
            }
        }
        return cleanupFailure;
    }

    private static void sendBusy(VolmitSender sender, LifecycleOperationCoordinator.BusyException busy) {
        LifecycleOperationCoordinator.ActiveOperation operation = busy.currentOperation();
        sender.sendMessage("Iris pack changes are busy with " + operation.kind().name().toLowerCase(Locale.ROOT)
                + " for '" + operation.target() + "'. Try again when it completes.");
    }

    private static void closeLease(LifecycleOperationCoordinator.Lease lease) {
        try {
            lease.close();
        } catch (Throwable e) {
            IrisLogging.reportError("Lifecycle idle callback failed after a studio pack operation.", e);
        }
    }

    private static String errorDetail(Throwable failure) {
        Throwable detail = failure;
        while (detail.getCause() != null && detail.getCause() != detail) {
            detail = detail.getCause();
        }
        String message = detail.getMessage();
        return message == null || message.isBlank() ? detail.getClass().getSimpleName() : message;
    }

    static void copyPackTree(Path source, Path target) throws IOException {
        try (Stream<Path> entries = Files.walk(source)) {
            for (Path entry : entries.sorted(Comparator.naturalOrder()).toList()) {
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("Pack contains a symbolic link: " + entry);
                }
                Path destination = target.resolve(source.relativize(entry)).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Pack entry escapes its installation stage: " + entry);
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(entry, destination, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    throw new IOException("Pack contains an unsupported entry: " + entry);
                }
            }
        }
    }

    static void publishNewDirectory(Path stage, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try {
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(stage, target);
        }
    }

    private static void writeWorkspaceAtomically(Path project, String projectName, String content) throws IOException {
        Path target = project.resolve(projectName + ".code-workspace");
        Path stage = Files.createTempFile(project, "." + projectName + ".workspace-", ".tmp");
        IOException operationFailure = null;
        try {
            Files.writeString(stage, content, StandardCharsets.UTF_8);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            try {
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stage, target);
            }
        } catch (IOException e) {
            operationFailure = e;
            throw e;
        } finally {
            try {
                Files.deleteIfExists(stage);
            } catch (IOException cleanupFailure) {
                if (operationFailure != null) {
                    operationFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    static final class StudioTransitionQueue {
        private final Object monitor;
        private CompletableFuture<Void> tail;

        StudioTransitionQueue() {
            monitor = new Object();
            tail = CompletableFuture.completedFuture(null);
        }

        <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> operation) {
            Supplier<CompletableFuture<T>> queuedOperation = Objects.requireNonNull(operation, "operation");
            CompletableFuture<T> result = new CompletableFuture<>();
            synchronized (monitor) {
                tail = tail.handle((ignored, previousFailure) -> null)
                        .thenCompose(ignored -> execute(queuedOperation, result));
            }
            return result;
        }

        private <T> CompletableFuture<Void> execute(
                Supplier<CompletableFuture<T>> operation,
                CompletableFuture<T> result
        ) {
            CompletableFuture<T> running;
            try {
                running = Objects.requireNonNull(operation.get(), "Studio transition returned no future.");
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                return CompletableFuture.completedFuture(null);
            }
            return running.handle((value, failure) -> {
                if (failure == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(failure);
                }
                return null;
            });
        }
    }

    @FunctionalInterface
    private interface PackMutation {
        boolean run() throws Exception;
    }

    private record DownloadOutcome(boolean changed, boolean restartRequired) {
        private static DownloadOutcome notChanged() {
            return new DownloadOutcome(false, false);
        }

        private static DownloadOutcome from(PackDownloader.PackInstallResult result) {
            return result == null
                    ? notChanged()
                    : new DownloadOutcome(result.changed(), result.restartRequired());
        }
    }

    private enum CreationOutcome {
        FAILED,
        OPEN,
        RESTART
    }

    record PackListingReference(String repository, String ref, String expectedKey) {
    }

    public IrisProject getActiveProject() {
        return activeProject;
    }

    public void updateWorkspace() {
        if (isProjectOpen()) {
            new IrisCodeWorkspace(activeProject).updateWorkspace();
        }
    }
}

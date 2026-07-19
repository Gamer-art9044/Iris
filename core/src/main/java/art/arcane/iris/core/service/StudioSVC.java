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
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.IrisPack;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.project.IrisProjectCopier;
import art.arcane.iris.core.runtime.TransientWorldCleanupSupport;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONException;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class StudioSVC implements IrisService {
    public static final String LISTING = "https://raw.githubusercontent.com/IrisDimensions/_listing/main/listing-v2.json";
    public static final String WORKSPACE_NAME = "packs";
    private static final AtomicCache<Integer> counter = new AtomicCache<>();
    private final KMap<String, String> cacheListing = null;
    private IrisProject activeProject;
    private CompletableFuture<art.arcane.iris.core.runtime.StudioOpenCoordinator.StudioCloseResult> activeClose;

    @Override
    public void onEnable() {
        J.a(() -> {
            String pack = IrisSettings.get().getGenerator().getDefaultWorldType();
            File f = IrisPack.packsPack(pack);

            if (!f.exists()) {
                if (PackDownloader.isDefaultOverworld(pack)) {
                    IrisLogging.info("Downloading Default Pack " + pack + " (beta release)");
                    IrisServices.get(StudioSVC.class).downloadDefaultOverworld(BukkitPlatform.console(), false);
                    ServerConfigurator.installDataPacksIfChanged(true);
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
        File target = new File(folder, "iris/pack");
        File source = dimension.getLoader().getDataFolder();
        sender.sendMessage("Installing Package: " + source.getName() + ":" + dimension.getLoadKey());
        try {
            FileUtils.copyDirectory(source, target);
        } catch (IOException e) {
            IrisLogging.reportError(e);
            return null;
        }
        return IrisData.get(target).getDimensionLoader().load(dimension.getLoadKey());
    }

    public IrisDimension installInto(VolmitSender sender, String type, File folder) {
        sender.sendMessage("Looking for Package: " + type);
        IrisDimension dim = IrisData.loadAnyDimension(type, null);

        if (dim == null) {
            File[] workspaceFiles = getWorkspaceFolder().listFiles();
            if (workspaceFiles != null) {
                for (File i : workspaceFiles) {
                    if (i.isFile() && i.getName().equals(type + ".iris")) {
                        sender.sendMessage("Found " + type + ".iris in " + WORKSPACE_NAME + " folder");
                        ZipUtil.unpack(i, folder);
                        break;
                    }
                }
            }
        } else {
            sender.sendMessage("Found " + type + " dimension in " + WORKSPACE_NAME + " folder. Repackaging");
            File f = new IrisProject(new File(getWorkspaceFolder(), type)).getPath();

            try {
                FileUtils.copyDirectory(f, folder);
            } catch (IOException e) {
                IrisLogging.reportError(e);
            }
        }

        File dimensionFile = new File(folder, "dimensions/" + type + ".json");

        if (!dimensionFile.exists() || !dimensionFile.isFile()) {
            downloadSearch(sender, type, false);
            File downloaded = getWorkspaceFolder(type);
            File[] files = downloaded.listFiles();

            if (files != null) {
                for (File i : files) {
                    if (i.isFile()) {
                        try {
                            FileUtils.copyFile(i, new File(folder, i.getName()));
                        } catch (IOException e) {
                            e.printStackTrace();
                            IrisLogging.reportError(e);
                        }
                    } else {
                        try {
                            FileUtils.copyDirectory(i, new File(folder, i.getName()));
                        } catch (IOException e) {
                            e.printStackTrace();
                            IrisLogging.reportError(e);
                        }
                    }
                }

                IO.delete(downloaded);
            }
        }

        if (!dimensionFile.exists() || !dimensionFile.isFile()) {
            sender.sendMessage("Can't find the " + dimensionFile.getName() + " in the dimensions folder of this pack! Failed!");
            return null;
        }

        IrisData dm = IrisData.get(folder);
        dm.hotloaded();
        dim = dm.getDimensionLoader().load(type);

        if (dim == null) {
            sender.sendMessage("Can't load the dimension! Failed!");
            return null;
        }

        sender.sendMessage(folder.getName() + " type installed. ");
        return dim;
    }

    public void downloadSearch(VolmitSender sender, String key) {
        downloadSearch(sender, key, false);
    }

    public void downloadSearch(VolmitSender sender, String key, boolean forceOverwrite) {
        try {
            String url = getListing(false).get(key);

            if (url == null) {
                sender.sendMessage("Pack '" + key + "' was not found in the pack listing.");
                sender.sendMessage("Use /iris download <pack> branch=<branch> to download manually.");
                return;
            }

            IrisLogging.info("Resolved pack '" + key + "' to " + url);
            String[] nodes = url.split("\\Q/\\E");
            String repo = nodes.length == 1 ? "IrisDimensions/" + nodes[0] : nodes[0] + "/" + nodes[1];
            String branch = nodes.length > 2 ? nodes[2] : "stable";
            download(sender, repo, branch, forceOverwrite, false);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
            sender.sendMessage("Failed to download '" + key + "'.");
        }
    }

    public void downloadDefaultOverworld(VolmitSender sender, boolean forceOverwrite) {
        try {
            String key = PackDownloader.downloadDefaultOverworld(getWorkspaceFolder(), forceOverwrite, sender::sendMessage);
            if (key != null) {
                ServerConfigurator.installDataPacks(true);
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
            sender.sendMessage("Failed to download the IrisDimensions/overworld beta release.");
        }
    }

    public void downloadBranch(VolmitSender sender, String repo, String branch, boolean forceOverwrite) {
        try {
            download(sender, repo, branch, forceOverwrite, false);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
            sender.sendMessage("Failed to download '" + repo + "' (branch " + branch + ").");
        }
    }

    public void download(VolmitSender sender, String repo, String branch) throws JsonSyntaxException, IOException {
        download(sender, repo, branch, false, false);
    }

    public void download(VolmitSender sender, String repo, String branch, boolean forceOverwrite, boolean directUrl) throws JsonSyntaxException, IOException {
        String key = PackDownloader.download(getWorkspaceFolder(), repo, branch, forceOverwrite, directUrl, sender::sendMessage);

        if (key == null) {
            return;
        }

        ServerConfigurator.installDataPacks(true);
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
            sender.sendMessage("Failed to open studio world: " + e.getMessage());
        }
    }

    private static boolean blockIfPackBroken(VolmitSender sender, String dimm) {
        PackValidationResult validation = PackValidationRegistry.get(dimm);
        if (validation == null || validation.isLoadable()) {
            return false;
        }
        sender.sendMessage("Cannot open studio '" + dimm + "' - pack has blocking errors:");
        for (String reason : validation.getBlockingErrors()) {
            sender.sendMessage(" - " + reason);
        }
        sender.sendMessage("Fix the pack and run /iris pack validate " + dimm + " to revalidate.");
        return true;
    }

    public void open(VolmitSender sender, long seed, String dimm, Consumer<World> onDone) throws IrisException {
        if (blockIfPackBroken(sender, dimm)) {
            return;
        }
        CompletableFuture<art.arcane.iris.core.runtime.StudioOpenCoordinator.StudioCloseResult> pendingClose = close();
        pendingClose.whenComplete((closeResult, closeThrowable) -> {
            if (closeThrowable != null) {
                IrisLogging.reportError("Failed while closing an existing studio project before opening \"" + dimm + "\".", closeThrowable);
                J.s(() -> sender.sendMessage("Failed to close the existing studio project: " + closeThrowable.getMessage()));
                return;
            }

            if (closeResult != null && closeResult.failureCause() != null) {
                Throwable failure = closeResult.failureCause();
                IrisLogging.reportError("Failed while closing an existing studio project before opening \"" + dimm + "\".", failure);
                J.s(() -> sender.sendMessage("Failed to close the existing studio project: " + failure.getMessage()));
                return;
            }

            IrisProject project = new IrisProject(new File(getWorkspaceFolder(), dimm));
            activeProject = project;
            try {
                project.open(sender, seed, onDone).whenComplete((result, throwable) -> {
                    if (throwable == null) {
                        return;
                    }

                    if (activeProject == project && !project.isOpen()) {
                        activeProject = null;
                    }
                });
            } catch (IrisException e) {
                if (activeProject == project) {
                    activeProject = null;
                }
                J.s(() -> sender.sendMessage("Failed to open studio world: " + e.getMessage()));
            }
        });
    }

    public void openVSCode(VolmitSender sender, String dim) {
        new IrisProject(new File(getWorkspaceFolder(), dim)).openVSCode(sender);
    }

    public File getWorkspaceFolder(String... sub) {
        return art.arcane.iris.platform.bukkit.BukkitPlatform.volmitPlugin().getDataFolderList(WORKSPACE_NAME, sub);
    }

    public File getWorkspaceFile(String... sub) {
        return art.arcane.iris.platform.bukkit.BukkitPlatform.volmitPlugin().getDataFileList(WORKSPACE_NAME, sub);
    }

    public CompletableFuture<art.arcane.iris.core.runtime.StudioOpenCoordinator.StudioCloseResult> close() {
        if (activeClose != null && !activeClose.isDone()) {
            return activeClose;
        }

        if (activeProject == null) {
            return CompletableFuture.completedFuture(new art.arcane.iris.core.runtime.StudioOpenCoordinator.StudioCloseResult(null, true, true, false, null));
        }

        IrisLogging.debug("Closing Active Project");
        IrisProject project = activeProject;
        activeProject = null;
        activeClose = project.close();
        activeClose.whenComplete((result, throwable) -> activeClose = null);
        return activeClose;
    }

    private void destroyStudioWorld(World world, PlatformChunkGenerator generator) {
        try {
            IrisToolbelt.evacuate(world);
        } catch (Throwable e) {
            IrisLogging.reportError("Failed to evacuate studio world \"" + world.getName() + "\" during shutdown cleanup.", e);
        }

        if (generator != null) {
            try {
                generator.close();
            } catch (Throwable e) {
                IrisLogging.reportError("Failed to close studio generator for \"" + world.getName() + "\" during shutdown cleanup.", e);
            }
        }

        try {
            WorldLifecycleService.get().unload(world, false);
        } catch (Throwable e) {
            IrisLogging.reportError("Failed to unload studio world \"" + world.getName() + "\" during shutdown cleanup.", e);
        }

        deleteTransientStudioFolders(IrisWorldStorage.logicalName(world));
    }

    private void deleteTransientStudioFolders(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }

        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            File folder = IrisWorldStorage.dimensionRoot(familyWorldName);
            if (!folder.exists()) {
                continue;
            }

            IO.delete(folder);
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
            IrisServices.get(art.arcane.iris.core.runtime.WorldDeletionQueue.class).queueForStartupDeletion(List.copyOf(normalizedNames));
        } catch (IOException e) {
            IrisLogging.reportError("Failed to queue studio world deletion on startup.", e);
        }
    }

    public File compilePackage(VolmitSender sender, String d, boolean obfuscate, boolean minify) {
        return new IrisProject(new File(getWorkspaceFolder(), d)).compilePackage(sender, obfuscate, minify);
    }

    public void createFrom(String existingPack, String newName) {
        File importPack = getWorkspaceFolder(existingPack);
        File newPack = getWorkspaceFolder(newName);

        if (importPack.listFiles().length == 0) {
            IrisLogging.warn("Couldn't find the pack to create a new dimension from.");
            return;
        }

        try {
            IrisProjectCopier.copyProject(importPack, newPack, existingPack, newName);
        } catch (JSONException | IOException e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
        }

        try {
            IrisProject p = new IrisProject(getWorkspaceFolder(newName));
            JSONObject ws = p.createCodeWorkspaceConfig();
            IO.writeAll(getWorkspaceFile(newName, newName + ".code-workspace"), ws.toString(0));
        } catch (JSONException | IOException e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
        }
    }

    public void create(VolmitSender sender, String s, String downloadable) {
        boolean shouldDelete = false;
        File importPack = getWorkspaceFolder(downloadable);
        File[] packFiles = importPack.listFiles();

        if (packFiles == null || packFiles.length == 0) {
            downloadSearch(sender, downloadable, false);
            packFiles = importPack.listFiles();

            if (packFiles != null && packFiles.length > 0) {
                shouldDelete = true;
            }
        }

        if (packFiles == null || packFiles.length == 0) {
            sender.sendMessage("Couldn't find the pack to create a new dimension from.");
            return;
        }

        File importDimensionFile = new File(importPack, "dimensions/" + downloadable + ".json");

        if (!importDimensionFile.exists()) {
            sender.sendMessage("Missing Imported Dimension File");
            return;
        }

        sender.sendMessage("Importing " + downloadable + " into new Project " + s);
        createFrom(downloadable, s);
        if (shouldDelete) {
            importPack.delete();
        }
        open(sender, s);
    }

    public void create(VolmitSender sender, String s) {
        create(sender, s, "example");
    }

    public IrisProject getActiveProject() {
        return activeProject;
    }

    public void updateWorkspace() {
        if (isProjectOpen()) {
            activeProject.updateWorkspace();
        }
    }
}

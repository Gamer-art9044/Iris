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

package art.arcane.iris.core;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.core.nms.datapack.IDataFixer;
import art.arcane.iris.core.pack.DefaultPackBootstrapProvisioner;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.misc.ServerProperties;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.volmlib.util.localization.MessageArgument;
public class ServerConfigurator {
    public static void configure() {
        IrisSettings.IrisSettingsAutoconfiguration s = IrisSettings.get().getAutoConfiguration();
        if (s.isConfigureSpigotTimeoutTime()) {
            J.attempt(ServerConfigurator::increaseKeepAliveSpigot);
        }

        if (s.isConfigurePaperWatchdogDelay()) {
            J.attempt(ServerConfigurator::increasePaperWatchdog);
        }

        if (DefaultPackBootstrapProvisioner.wasProvisionedThisStartup()) {
            IrisLogging.info("Paper loaded the Iris datapack during bootstrap; skipping the legacy startup install.");
        } else {
            installDataPacks(true);
        }
    }

    private static void increaseKeepAliveSpigot() throws IOException, InvalidConfigurationException {
        File spigotConfig = new File("spigot.yml");
        FileConfiguration f = new YamlConfiguration();
        f.load(spigotConfig);
        long tt = f.getLong("settings.timeout-time");

        long spigotTimeout = TimeUnit.MINUTES.toSeconds(5);

        if (tt < spigotTimeout) {
            IrisLogging.warn("Updating spigot.yml timeout-time: " + tt + " -> " + spigotTimeout + " (5 minutes)");
            IrisLogging.warn("You can disable this change (autoconfigureServer) in Iris settings, then change back the value.");
            f.set("settings.timeout-time", spigotTimeout);
            f.save(spigotConfig);
        }
    }

    private static void increasePaperWatchdog() throws IOException, InvalidConfigurationException {
        File spigotConfig = new File("config/paper-global.yml");
        FileConfiguration f = new YamlConfiguration();
        f.load(spigotConfig);
        long tt = f.getLong("watchdog.early-warning-delay");

        long watchdog = TimeUnit.MINUTES.toMillis(3);
        if (tt < watchdog) {
            IrisLogging.warn("Updating paper.yml watchdog early-warning-delay: " + tt + " -> " + watchdog + " (3 minutes)");
            IrisLogging.warn("You can disable this change (autoconfigureServer) in Iris settings, then change back the value.");
            f.set("watchdog.early-warning-delay", watchdog);
            f.save(spigotConfig);
        }
    }

    public static KList<File> getDatapacksFolder() {
        return new KList<File>().qadd(new File(IrisWorldStorage.levelRoot(), "datapacks"));
    }

    public static KList<File> getIrisDatapackRoots() {
        KList<File> roots = new KList<>();
        for (File datapacksFolder : getDatapacksFolder()) {
            roots.add(new File(datapacksFolder, "iris"));
        }
        return roots;
    }

    public static boolean installDataPacks(boolean fullInstall) {
        IDataFixer fixer = DataVersion.getDefault();
        if (fixer == null) {
            DataVersion fallback = DataVersion.getLatest();
            IrisLogging.warn("Primary datapack fixer was null, forcing latest fixer: " + fallback.getVersion());
            fixer = fallback.get();
        }
        return installDataPacks(fixer, fullInstall);
    }

    public static boolean installDataPacks(IDataFixer fixer, boolean fullInstall) {
        if (fixer == null) {
            IrisLogging.error("Unable to install datapacks, fixer is null!");
            return false;
        }
        if (fullInstall) {
            IrisLogging.info("Checking Data Packs...");
        } else {
            IrisLogging.debug("Checking Data Packs...");
        }
        KList<File> datapacksFolders = getDatapacksFolder();
        DatapackIngestService.reapplyFromStaging(datapacksFolders);
        List<File> packRoots;
        try (Stream<IrisData> stream = allPacks()) {
            packRoots = stream
                    .map(IrisData::getDataFolder)
                    .map(File::getAbsoluteFile)
                    .distinct()
                    .toList();
        }

        try {
            IrisDatapackCompiler.compile(
                    packRoots,
                    getIrisDatapackRoots(),
                    fixer,
                    BukkitPlatform.dataPackFormat(),
                    IrisSettings.get().getGeneral().adjustVanillaHeight
            );
        } catch (IOException e) {
            IrisLogging.reportError("Unable to compile Iris datapacks", e);
            return false;
        }
        if (fullInstall) {
            IrisLogging.info("Data Packs Setup!");
        } else {
            IrisLogging.debug("Data Packs Setup!");
        }

        return fullInstall && verifyDataPacksPost(IrisSettings.get().getAutoConfiguration().isAutoRestartOnCustomBiomeInstall());
    }

    public static boolean installDataPacksIfChanged(boolean fullInstall) {
        File packsDir = IrisPlatforms.get().dataFolder("packs");
        String current = computePackFingerprint(packsDir);
        File cacheFile = new File(IrisPlatforms.get().dataFolder("cache"), "datapack-fingerprint");
        String cached = "";
        if (cacheFile.exists()) {
            try {
                cached = Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                cached = "";
            }
        }
        if (!current.isEmpty() && current.equals(cached)) {
            IrisLogging.debug("Data packs unchanged, skipping install.");
            return false;
        }
        boolean result = installDataPacks(fullInstall);
        try {
            cacheFile.getParentFile().mkdirs();
            Files.writeString(cacheFile.toPath(), current, StandardCharsets.UTF_8);
        } catch (IOException e) {
            IrisLogging.warn("Failed to write datapack fingerprint cache: " + e.getMessage());
        }
        return result;
    }

    public static String computePackFingerprint(File packsDir) {
        if (packsDir == null || !packsDir.isDirectory()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> entries = new ArrayList<>();
            collectFingerprintEntries(packsDir, packsDir.getAbsolutePath(), entries);
            Collections.sort(entries);
            for (String entry : entries) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void collectFingerprintEntries(File dir, String rootPath, List<String> entries) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (file.getName().startsWith(".")) {
                    continue;
                }
                collectFingerprintEntries(file, rootPath, entries);
            } else {
                String relative = file.getAbsolutePath().substring(rootPath.length());
                entries.add(relative + "|" + file.length() + "|" + file.lastModified());
            }
        }
    }

    public static File resolveDatapacksFolder(File worldFolder) {
        File rootFolder = resolveWorldRootFolder(worldFolder);
        return new File(rootFolder, "datapacks");
    }

    static File resolveWorldRootFolder(File worldFolder) {
        if (worldFolder == null) {
            return IrisWorldStorage.levelRoot();
        }
        return IrisWorldStorage.levelRoot(worldFolder);
    }

    private static boolean verifyDataPacksPost(boolean allowRestarting) {
        try (Stream<IrisData> stream = allPacks()) {
            boolean bad = stream
                    .map(data -> {
                        IrisLogging.debug("Checking Pack: " + data.getDataFolder().getPath());
                        var loader = data.getDimensionLoader();
                        return loader.loadAll(loader.getPossibleKeys())
                                .stream()
                                .filter(Objects::nonNull)
                                .map(ServerConfigurator::verifyDataPackInstalled)
                                .toList()
                                .contains(false);
                    })
                    .toList()
                    .contains(true);
            if (!bad) return false;
        }


        if (allowRestarting) {
            restart();
        } else if (INMS.get().supportsDataPacks()) {
            IrisLogging.error("============================================================================");
            IrisLogging.error(C.ITALIC + "You need to restart your server to properly generate custom biomes.");
            IrisLogging.error(C.ITALIC + "By continuing, Iris will use backup biomes in place of the custom biomes.");
            IrisLogging.error("----------------------------------------------------------------------------");
            IrisLogging.error(C.UNDERLINE + "IT IS HIGHLY RECOMMENDED YOU RESTART THE SERVER BEFORE GENERATING!");
            IrisLogging.error("============================================================================");

            for (Player i : Bukkit.getOnlinePlayers()) {
                if (i.isOp() || i.hasPermission("iris.all")) {
                    VolmitSender sender = new VolmitSender(i, BukkitPlatform.volmitPlugin().getTag("WARNING"));
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.SERVER_CONFIGURATOR_THERE_ARE_SOME_IRIS_PACKS_THAT_HAVE_CUSTOM_BIOMES_THEM));
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.SERVER_CONFIGURATOR_YOU_NEED_RESTART_YOUR_SERVER_USE_THESE_PACKS));
                }
            }

            J.sleep(3000);
        }
        return true;
    }

    public static void restart() {
        J.s(() -> {
            IrisLogging.warn("New data pack entries have been installed in Iris! Restarting server!");
            IrisLogging.warn("This will only happen when your pack changes (updates/first time setup)");
            IrisLogging.warn("(You can disable this auto restart in iris settings)");
            J.s(() -> {
                IrisLogging.warn("Looks like the restart command didn't work. Stopping the server instead!");
                Bukkit.shutdown();
            }, 100);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
        });
    }

    public static boolean verifyDataPackInstalled(IrisDimension dimension) {
        KSet<String> keys = new KSet<>();
        boolean warn = false;

        for (IrisBiome i : dimension.getAllBiomes(dimension::getLoader)) {
            if (i.isCustom()) {
                for (IrisBiomeCustom j : i.getCustomDerivitives()) {
                    keys.add(dimension.getLoadKey() + ":" + j.getId());
                }
            }
        }
        String key = getWorld(dimension.getLoader());
        if (key == null) key = dimension.getLoadKey();
        else key += "/" + dimension.getLoadKey();

        if (!INMS.get().supportsDataPacks()) {
            if (!keys.isEmpty()) {
                IrisLogging.warn("===================================================================================");
                IrisLogging.warn("Pack " + key + " has " + keys.size() + " custom biome(s). ");
                IrisLogging.warn("Your server version does not yet support datapacks for iris.");
                IrisLogging.warn("The world will generate these biomes as backup biomes.");
                IrisLogging.warn("====================================================================================");
            }

            return true;
        }

        for (String i : keys) {
            Object o = INMS.get().getCustomBiomeBaseFor(i);

            if (o == null) {
                IrisLogging.warn("The Biome " + i + " is not registered on the server.");
                warn = true;
            }
        }

        if (INMS.get().missingDimensionTypes(dimension.getDimensionTypeKey())) {
            IrisLogging.warn("The Dimension Type for " + dimension.getLoadFile() + " is not registered on the server.");
            warn = true;
        }

        if (warn) {
            IrisLogging.error("The Pack " + key + " is INCAPABLE of generating custom biomes");
            IrisLogging.error("If not done automatically, restart your server before generating with this pack!");
        }

        return !warn;
    }

    public static Stream<IrisData> allPacks() {
        File[] packs = IrisPlatforms.get().dataFolder("packs").listFiles(File::isDirectory);
        Stream<File> locals = packs == null ? Stream.empty() : Arrays.stream(packs);
        return Stream.concat(locals
                .filter( base -> {
                    var content = new File(base, "dimensions").listFiles();
                    return content != null && content.length > 0;
                })
                .map(IrisData::get), IrisWorlds.get().getPacks());
    }

    @Nullable
    public static String getWorld(@NonNull IrisData data) {
        Path packPath = data.getDataFolder().toPath().toAbsolutePath().normalize();
        Path irisPath = packPath.getParent();
        if (irisPath == null || !"pack".equals(packPath.getFileName().toString()) || !"iris".equals(irisPath.getFileName().toString())) {
            return null;
        }

        Path dimensionPath = irisPath.getParent();
        if (dimensionPath == null) {
            return null;
        }
        File dimensionRoot = dimensionPath.toFile();
        NamespacedKey key = IrisWorldStorage.keyFromDimensionRoot(IrisWorldStorage.levelRoot(), dimensionRoot).orElse(null);
        return key == null ? null : key.toString();
    }

}

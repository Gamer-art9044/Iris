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
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.core.nms.datapack.IDataFixer;
import art.arcane.iris.core.pack.AtomicDirectoryPublisher;
import art.arcane.iris.core.pack.DefaultPackBootstrapProvisioner;
import art.arcane.iris.core.pack.PackDirectoryResolver;
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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.volmlib.util.localization.MessageArgument;
public class ServerConfigurator {
    private static final Object DATAPACK_INSTALL_LOCK = new Object();
    private static final String CODE_WORKSPACE_SUFFIX = ".code-workspace";

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
            DatapackInstallResult result = installDataPacks(true);
            if (result.restartRequired() && IrisSettings.get().getAutoConfiguration().isAutoRestartOnCustomBiomeInstall()) {
                restart();
            }
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

    public static DatapackInstallResult installDataPacks(boolean fullInstall) {
        return installDataPacks(resolveDataFixer(), fullInstall);
    }

    public static DatapackInstallResult installDataPacks(IDataFixer fixer, boolean fullInstall) {
        synchronized (DATAPACK_INSTALL_LOCK) {
            return installDataPacksLocked(fixer, fullInstall);
        }
    }

    private static DatapackInstallResult installDataPacksLocked(IDataFixer fixer, boolean fullInstall) {
        if (fixer == null) {
            IrisLogging.error("Unable to install datapacks, fixer is null!");
            return DatapackInstallResult.failedResult();
        }
        if (fullInstall) {
            IrisLogging.info("Checking Data Packs...");
        } else {
            IrisLogging.debug("Checking Data Packs...");
        }
        KList<File> datapacksFolders = getDatapacksFolder();
        if (!DatapackIngestService.reapplyFromStaging(datapacksFolders)) {
            IrisLogging.error("Unable to compile Iris datapacks while external datapack recovery is incomplete.");
            return DatapackInstallResult.failedResult();
        }
        List<File> packRoots;
        try (Stream<IrisData> stream = allPacks()) {
            packRoots = stream
                    .map(IrisData::getDataFolder)
                    .map(File::getAbsoluteFile)
                    .distinct()
                    .toList();
        }

        KList<File> liveRoots = getIrisDatapackRoots();
        KList<File> stagedRoots = new KList<>();
        List<Path> stagedPaths = new ArrayList<>(liveRoots.size());
        List<AtomicDirectoryPublisher.Publication> publications = new ArrayList<>(liveRoots.size());
        try {
            for (File liveRoot : liveRoots) {
                Path target = liveRoot.toPath().toAbsolutePath().normalize();
                Path parent = target.getParent();
                if (parent == null) {
                    throw new IOException("Iris datapack root has no parent: " + target);
                }
                Files.createDirectories(parent);
                Path staged = parent.resolve(".iris-compile-" + UUID.randomUUID());
                Files.createDirectories(staged);
                stagedPaths.add(staged);
                stagedRoots.add(staged.toFile());
            }
            IrisDatapackCompiler.compile(
                    packRoots,
                    stagedRoots,
                    fixer,
                    BukkitPlatform.dataPackFormat(),
                    IrisSettings.get().getGeneral().adjustVanillaHeight
            );
            for (int i = 0; i < liveRoots.size(); i++) {
                publications.add(AtomicDirectoryPublisher.publish(
                        stagedRoots.get(i).toPath(),
                        liveRoots.get(i).toPath()
                ));
            }
            for (AtomicDirectoryPublisher.Publication publication : publications) {
                publication.commit();
                try {
                    publication.cleanupBackup();
                } catch (IOException cleanupFailure) {
                    IrisLogging.warn("Iris datapack was committed but its backup could not be removed: "
                            + cleanupFailure.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            closePublications(publications, e);
            IrisLogging.reportError("Unable to compile Iris datapacks", e);
            return DatapackInstallResult.failedResult();
        } finally {
            for (Path stagedPath : stagedPaths) {
                try {
                    AtomicDirectoryPublisher.deleteTree(stagedPath);
                } catch (IOException cleanupFailure) {
                    IrisLogging.warn("Failed to clean Iris datapack compilation stage " + stagedPath + ": "
                            + cleanupFailure.getMessage());
                }
            }
        }
        if (fullInstall) {
            IrisLogging.info("Data Packs Setup!");
        } else {
            IrisLogging.debug("Data Packs Setup!");
        }

        boolean restartRequired = fullInstall && verifyDataPacksPost();
        return restartRequired
                ? DatapackInstallResult.restartRequiredResult()
                : DatapackInstallResult.readyResult();
    }

    private static IDataFixer resolveDataFixer() {
        IDataFixer fixer = DataVersion.getDefault();
        if (fixer != null) {
            return fixer;
        }
        DataVersion fallback = DataVersion.getLatest();
        IrisLogging.warn("Primary datapack fixer was null, forcing latest fixer: " + fallback.getVersion());
        return fallback.get();
    }

    private static void closePublications(List<AtomicDirectoryPublisher.Publication> publications, Throwable failure) {
        for (int i = publications.size() - 1; i >= 0; i--) {
            try {
                publications.get(i).close();
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    public static DatapackInstallResult installDataPacksIfChanged(boolean fullInstall) {
        synchronized (DATAPACK_INSTALL_LOCK) {
            File packsDir = IrisPlatforms.get().dataFolder("packs");
            File cacheFile = new File(IrisPlatforms.get().dataFolder("cache"), "datapack-fingerprint");
            FingerprintCache cached = readFingerprintCache(cacheFile.toPath());
            PackFingerprint fingerprint;
            try {
                fingerprint = resolvePackFingerprint(packsDir, cached.metadata(), cached.content());
            } catch (RuntimeException exception) {
                IrisLogging.reportError("Unable to fingerprint Iris packs safely", exception);
                return DatapackInstallResult.failedResult();
            }
            String current = fingerprint.content();
            if (!current.isEmpty() && current.equals(cached.content())) {
                if (!fingerprint.metadata().equals(cached.metadata())) {
                    writeFingerprintCache(cacheFile.toPath(), fingerprint);
                }
                IrisLogging.debug("Data packs unchanged, skipping install.");
                return DatapackInstallResult.unchangedResult();
            }
            DatapackInstallResult result = installDataPacksLocked(resolveDataFixer(), fullInstall);
            if (result.succeeded()) {
                writeFingerprintCache(cacheFile.toPath(), fingerprint);
            }
            return result;
        }
    }

    static PackFingerprint resolvePackFingerprint(File packsDir, String cachedMetadata, String cachedContent) {
        String metadata = computePackMetadataDigest(packsDir);
        if (!metadata.isEmpty()
                && metadata.equals(cachedMetadata)
                && cachedContent != null
                && !cachedContent.isEmpty()) {
            return new PackFingerprint(metadata, cachedContent);
        }
        return new PackFingerprint(metadata, computePackFingerprint(packsDir));
    }

    public static String computePackMetadataDigest(File packsDir) {
        Path root = resolveFingerprintRoot(packsDir);
        if (root == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<FingerprintEntry> entries = collectFingerprintEntries(root.toRealPath());
            entries.sort(Comparator.comparing(FingerprintEntry::relativePath));
            for (FingerprintEntry entry : entries) {
                BasicFileAttributes attributes = Files.readAttributes(
                        entry.source(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                byte[] relativePath = entry.relativePath().getBytes(StandardCharsets.UTF_8);
                updateDigestInt(digest, relativePath.length);
                digest.update(relativePath);
                updateDigestLong(digest, attributes.size());
                updateDigestLong(digest, attributes.lastModifiedTime().toMillis());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to fingerprint Iris packs at " + root, exception);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String computePackFingerprint(File packsDir) {
        Path root = resolveFingerprintRoot(packsDir);
        if (root == null) {
            return "";
        }
        try {
            Path resolvedRoot = root.toRealPath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<FingerprintEntry> entries = collectFingerprintEntries(resolvedRoot);
            entries.sort(Comparator.comparing(FingerprintEntry::relativePath));
            byte[] buffer = new byte[8192];
            for (FingerprintEntry entry : entries) {
                byte[] relativePath = entry.relativePath().getBytes(StandardCharsets.UTF_8);
                updateDigestInt(digest, relativePath.length);
                digest.update(relativePath);
                updateDigestLong(digest, Files.size(entry.source()));
                try (InputStream input = Files.newInputStream(entry.source())) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to fingerprint Iris packs at " + root, exception);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Path resolveFingerprintRoot(File packsDir) {
        if (packsDir == null) {
            return null;
        }
        Path root = packsDir.toPath().toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isDirectory(root)) {
            if (Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("Iris packs root target is missing or unsafe: " + root);
            }
            return null;
        }
        return root;
    }

    private static FingerprintCache readFingerprintCache(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) {
            return new FingerprintCache("", "");
        }
        try {
            List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
            String content = lines.isEmpty() ? "" : lines.getFirst().trim();
            String metadata = lines.size() > 1 ? lines.get(1).trim() : "";
            return new FingerprintCache(content, metadata);
        } catch (IOException e) {
            return new FingerprintCache("", "");
        }
    }

    private static void writeFingerprintCache(Path cacheFile, PackFingerprint fingerprint) {
        try {
            writeFingerprintAtomic(cacheFile, fingerprint.content() + "\n" + fingerprint.metadata());
        } catch (IOException e) {
            IrisLogging.warn("Failed to write datapack fingerprint cache: " + e.getMessage());
        }
    }

    private static void writeFingerprintAtomic(Path target, String fingerprint) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("Datapack fingerprint target has no parent: " + absoluteTarget);
        }
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".datapack-fingerprint-", ".tmp");
        try {
            Files.writeString(staged, fingerprint, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(staged, absoluteTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static List<FingerprintEntry> collectFingerprintEntries(Path root) throws IOException {
        List<FingerprintEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                String childName = child.getFileName().toString();
                if (PackDirectoryResolver.isHiddenName(childName) || isGeneratedPackFile(childName)) {
                    continue;
                }
                if (Files.isSymbolicLink(child)) {
                    if (!Files.isDirectory(child)) {
                        throw new IOException("Iris pack fingerprint rejected symbolic link: " + child);
                    }
                    PackDirectoryResolver.requireSafePackTree(child.toFile());
                    collectFingerprintTree(child.toRealPath(), childName, entries);
                } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    collectFingerprintTree(child, childName, entries);
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(new FingerprintEntry(child, childName));
                } else {
                    throw new IOException("Iris pack fingerprint rejected unsupported entry: " + child);
                }
            }
        }
        return entries;
    }

    private static void collectFingerprintTree(
            Path treeRoot,
            String logicalRoot,
            List<FingerprintEntry> entries
    ) throws IOException {
        Files.walkFileTree(treeRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(treeRoot)
                        && PackDirectoryResolver.isHiddenName(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String fileName = file.getFileName().toString();
                if (PackDirectoryResolver.isHiddenName(fileName) || isGeneratedPackFile(fileName)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw new IOException("Iris pack fingerprint rejected symbolic link: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw new IOException("Iris pack fingerprint rejected unsupported entry: " + file);
                }
                String relative = treeRoot.relativize(file).toString().replace(File.separatorChar, '/');
                entries.add(new FingerprintEntry(file, logicalRoot + "/" + relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw new IOException("Unable to inspect Iris pack entry: " + file, failure);
            }
        });
    }

    private static boolean isGeneratedPackFile(String name) {
        return name != null && name.endsWith(CODE_WORKSPACE_SUFFIX);
    }

    private record FingerprintEntry(Path source, String relativePath) {
    }

    record PackFingerprint(String metadata, String content) {
    }

    private record FingerprintCache(String content, String metadata) {
    }

    private static void updateDigestInt(MessageDigest digest, int value) {
        for (int shift = Integer.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static void updateDigestLong(MessageDigest digest, long value) {
        for (int shift = Long.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            digest.update((byte) (value >>> shift));
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

    private static boolean verifyDataPacksPost() {
        try (Stream<IrisData> stream = allPacks()) {
            boolean bad = stream
                    .map(data -> {
                        IrisLogging.debug("Checking Pack: " + data.getDataFolder().getPath());
                        ResourceLoader<IrisDimension> loader = data.getDimensionLoader();
                        return loader.loadAll(loader.getPossibleKeys())
                                .stream()
                                .filter(Objects::nonNull)
                                .map(ServerConfigurator::verifyDataPackInstalled)
                                .toList()
                                .contains(false);
                    })
                    .toList()
                    .contains(true);
            if (!bad) {
                return false;
            }
        }


        if (INMS.get().supportsDataPacks()) {
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

        }
        return true;
    }

    public static void restart() {
        restart("New data pack entries have been installed in Iris.");
    }

    public static void restart(String reason) {
        LifecycleOperationCoordinator.get().quiesceForRestart(() -> J.s(() -> {
            IrisLogging.warn(reason + " Restarting server to restore a safe lifecycle boundary.");
            J.s(() -> {
                IrisLogging.warn("Looks like the restart command didn't work. Stopping the server instead!");
                Bukkit.shutdown();
            }, 100);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
        }));
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
        Stream<File> locals = PackDirectoryResolver.listVisiblePackDirectories(
                IrisPlatforms.get().dataFolder("packs")
        ).stream();
        return Stream.concat(locals
                .filter(base -> {
                    File[] content = new File(base, "dimensions").listFiles();
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

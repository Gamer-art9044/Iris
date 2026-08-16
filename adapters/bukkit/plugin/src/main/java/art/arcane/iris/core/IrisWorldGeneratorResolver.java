/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

import art.arcane.iris.Iris;
import art.arcane.iris.core.lifecycle.WorldLifecycleStaging;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.BrokenPackException;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.pack.PackDirectoryResolver;
import art.arcane.iris.core.pack.PackValidationCache;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import art.arcane.iris.core.pack.PackValidator;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitPlugin;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Pack validation, dimension lookup, and the world generator / biome provider resolution that the
 * Bukkit plugin entry points delegate to.
 */
public final class IrisWorldGeneratorResolver {
    private static final int VALIDATION_STABILITY_ATTEMPTS = 2;
    private static final Object SNAPSHOT_VALIDATION_LOCK = new Object();

    private final VolmitPlugin plugin;

    public IrisWorldGeneratorResolver(VolmitPlugin plugin) {
        this.plugin = plugin;
    }

    public void validateAllPacks() {
        File packsRoot = plugin.getDataFolder("packs");
        List<File> packDirs = PackDirectoryResolver.listVisiblePackDirectories(packsRoot);
        PackValidationRegistry.clear();
        List<String> packNames = packDirs.stream().map(File::getName).sorted().toList();
        Path cacheFile = IrisPlatforms.get().dataFile("cache", "pack-validation.json").toPath();
        ServerConfigurator.PackContentSnapshot contentSnapshot =
                new ServerConfigurator.PackContentSnapshot("", Map.of());
        String contextFingerprint = "";
        Optional<List<PackValidationResult>> cached = Optional.empty();
        try {
            contentSnapshot = ServerConfigurator.computePackContentSnapshot(packsRoot);
            contextFingerprint = PackValidationCache.contextFingerprint();
            cached = PackValidationCache.load(
                    cacheFile,
                    contentSnapshot.content(),
                    contextFingerprint,
                    packNames);
        } catch (RuntimeException exception) {
            Iris.reportError("Could not evaluate the persisted pack-validation cache", exception);
        }

        List<PackValidationResult> results;
        if (cached.isPresent()) {
            results = cached.get();
            Iris.info("Reused persisted validation for " + results.size()
                    + " unchanged Iris pack(s); full pack parsing was skipped.");
        } else {
            FreshValidation validation = validateStablePacks(packsRoot, packDirs, contentSnapshot);
            packDirs = validation.packDirs();
            contentSnapshot = validation.contentSnapshot();
            results = validation.results();
            if (validation.stable()) {
                try {
                    PackValidationCache.save(
                            cacheFile,
                            contentSnapshot.content(),
                            contextFingerprint,
                            results);
                } catch (IOException exception) {
                    Iris.reportError("Could not persist Iris pack-validation results", exception);
                }
            }
        }

        Map<String, String> packFingerprints = contentSnapshot.packContents();
        for (PackValidationResult result : results) {
            PackValidationRegistry.publish(result);
            String packFingerprint = packFingerprints.get(result.getPackName());
            File packDirectory = PackDirectoryResolver.resolveExisting(packsRoot, result.getPackName());
            if (packDirectory != null && packFingerprint != null && !packFingerprint.isBlank()) {
                PackValidationRegistry.publish(packDirectory.toPath(), result, packFingerprint);
            }
            if (!result.isLoadable()) {
                Iris.error("Pack '" + result.getPackName()
                        + "' FAILED validation - world and Studio creation with this pack will be refused. Reasons:");
                for (String reason : result.getBlockingErrors()) {
                    Iris.error("  - " + reason);
                }
            } else if (!result.getWarnings().isEmpty()) {
                Iris.info("Pack '" + result.getPackName() + "' validated ("
                        + result.getWarnings().size() + " warning(s)).");
                for (String warning : result.getWarnings()) {
                    Iris.warn("  [" + result.getPackName() + "] " + warning);
                }
            } else if (cached.isEmpty()) {
                Iris.success("Pack '" + result.getPackName() + "' validated.");
            }
        }
        IrisStartupValidation.markPacksReady();
    }

    private static FreshValidation validateStablePacks(
            File packsRoot,
            List<File> initialPackDirs,
            ServerConfigurator.PackContentSnapshot initialSnapshot
    ) {
        List<File> packDirs = initialPackDirs;
        ServerConfigurator.PackContentSnapshot before = initialSnapshot;
        for (int attempt = 0; attempt < VALIDATION_STABILITY_ATTEMPTS; attempt++) {
            List<String> packNames = packDirs.stream().map(File::getName).sorted().toList();
            List<PackValidationResult> results = validatePacks(packDirs);
            ServerConfigurator.PackContentSnapshot after;
            try {
                after = ServerConfigurator.computePackContentSnapshot(packsRoot);
            } catch (RuntimeException exception) {
                Iris.reportError("Could not verify Iris pack bytes after validation", exception);
                after = new ServerConfigurator.PackContentSnapshot("", Map.of());
            }
            List<File> afterPackDirs = PackDirectoryResolver.listVisiblePackDirectories(packsRoot);
            List<String> afterPackNames = afterPackDirs.stream().map(File::getName).sorted().toList();
            if (!before.content().isBlank()
                    && before.content().equals(after.content())
                    && packNames.equals(afterPackNames)) {
                return new FreshValidation(afterPackDirs, after, results, true);
            }
            packDirs = afterPackDirs;
            before = after;
        }
        Iris.error("Iris pack files kept changing during validation; validation was refused until writes stop.");
        List<PackValidationResult> failures = new ArrayList<>(packDirs.size());
        for (File packDir : packDirs) {
            failures.add(new PackValidationResult(
                    packDir.getName(),
                    List.of("Pack files changed while validation was in progress; retry after writes stop."),
                    List.of(),
                    System.currentTimeMillis()));
        }
        return new FreshValidation(packDirs, before, failures, false);
    }

    private static List<PackValidationResult> validatePacks(List<File> packDirs) {
        List<PackValidationResult> results = new ArrayList<>(packDirs.size());
        for (File packDir : packDirs) {
            try {
                results.add(PackValidator.validate(packDir));
            } catch (Throwable exception) {
                Iris.reportError("Pack validation failed for '" + packDir.getName() + "'", exception);
                String detail = exception.getMessage();
                if (detail == null || detail.isBlank()) {
                    detail = exception.getClass().getSimpleName();
                }
                results.add(new PackValidationResult(
                        packDir.getName(),
                        List.of("Pack validation failed with " + exception.getClass().getSimpleName()
                                + ": " + detail),
                        List.of(),
                        System.currentTimeMillis()));
            }
        }
        return results;
    }

    static PackValidationResult requireSnapshotLoadable(File packRoot) {
        Path normalizedRoot = packRoot.toPath().toAbsolutePath().normalize();
        PackValidationResult result = PackValidationRegistry.get(normalizedRoot);
        if (result == null) {
            synchronized (SNAPSHOT_VALIDATION_LOCK) {
                result = PackValidationRegistry.get(normalizedRoot);
                if (result == null) {
                    PackValidationRegistry.ValidationTicket ticket =
                            PackValidationRegistry.tryBeginValidation(normalizedRoot);
                    if (ticket != null) {
                        try {
                            result = PackValidator.validate(normalizedRoot.toFile());
                        } catch (Throwable exception) {
                            Iris.reportError("Snapshot pack validation failed for '" + normalizedRoot + "'", exception);
                            String detail = exception.getMessage();
                            if (detail == null || detail.isBlank()) {
                                detail = exception.getClass().getSimpleName();
                            }
                            result = new PackValidationResult(
                                    normalizedRoot.getFileName().toString(),
                                    List.of("Pack validation failed with " + exception.getClass().getSimpleName()
                                            + ": " + detail),
                                    List.of(),
                                    System.currentTimeMillis());
                        }
                        PackValidationRegistry.publishIfCurrent(ticket, result);
                    }
                }
            }
        }
        return PackValidationRegistry.requireLoadable(normalizedRoot);
    }

    @Nullable
    public static IrisDimension loadDimension(@NonNull String worldName, @NonNull String id) {
        File levelRoot = IrisWorldStorage.levelRoot();
        NamespacedKey worldKey = configuredWorldKey(worldName, levelRoot.getName());
        String configuredWorldName = IrisWorldStorage.configuredWorldName(worldKey, levelRoot.getName());
        File pack = IrisWorldStorage.frozenDimensionRoot(
                        Bukkit.getWorldContainer(),
                        levelRoot,
                        configuredWorldName,
                        worldKey
                )
                .map(IrisWorldStorage::requireFrozenPackRoot)
                .orElse(null);
        IrisDimension dimension = pack == null ? null : IrisData.get(pack).getDimensionLoader().load(id);
        if (dimension == null) dimension = IrisData.loadAnyDimension(id, null);
        if (dimension == null) {
            File packsRoot = IrisPlatforms.get().packsFolderNoCreate();
            if (PackDownloader.isPackPresent(packsRoot, id)) {
                Iris.error("Pack '" + id + "' exists at " + new File(packsRoot, id).getPath()
                        + " but its dimension failed to load; not redownloading. Fix or delete the pack folder.");
                return null;
            }
            Iris.warn("Unable to find dimension type " + id + ". Install its pack with "
                    + PackDownloader.downloadCommandFor(id) + " and restart the server.");
        }

        return dimension;
    }

    static NamespacedKey configuredWorldKey(String worldName, String levelName) {
        return IrisWorldStorage.keyFromConfiguredWorldName(worldName, levelName);
    }

    /**
     * Resolves the biome provider for a world, falling back to the supplied Bukkit default when
     * Iris has nothing staged.
     */
    @Nullable
    public BiomeProvider resolveDefaultBiomeProvider(String worldName, @Nullable String id, Supplier<BiomeProvider> fallback) {
        BiomeProvider stagedBiomeProvider = WorldLifecycleStaging.consumeBiomeProvider(worldName);
        if (stagedBiomeProvider != null) {
            Iris.debug("Using staged runtime biome provider for " + worldName);
            return stagedBiomeProvider;
        }
        Iris.debug("Biome Provider Called for " + worldName + " using ID: " + id);
        return fallback.get();
    }

    public ChunkGenerator resolveDefaultWorldGenerator(String worldName, String id) {
        IrisStartupValidation.requireWorldCreationReady();
        ChunkGenerator stagedGenerator = WorldLifecycleStaging.consumeGenerator(worldName);
        if (stagedGenerator != null) {
            Iris.debug("Using staged runtime generator for " + worldName);
            return stagedGenerator;
        }
        Iris.debug("Default World Generator Called for " + worldName + " using ID: " + id);
        if (id == null || id.isEmpty()) id = IrisSettings.get().getGenerator().getDefaultWorldType();
        Iris.debug("Generator ID: " + id + " requested by bukkit/plugin");

        try {
            return resolveFrozenWorldGenerator(worldName, id);
        } catch (RuntimeException failure) {
            Iris.reportError("Refusing to load configured Iris world '" + worldName
                    + "' because its frozen world-local pack snapshot could not be used.", failure);
            Bukkit.shutdown();
            throw failure;
        }
    }

    private ChunkGenerator resolveFrozenWorldGenerator(String worldName, String id) {
        File levelRoot = IrisWorldStorage.levelRoot();
        NamespacedKey worldKey = configuredWorldKey(worldName, levelRoot.getName());
        File dimensionRoot = IrisWorldStorage.requireFrozenDimensionRoot(
                Bukkit.getWorldContainer(),
                levelRoot,
                worldName,
                worldKey
        );
        File expectedDimensionRoot = WorldCreatorCompat.persistentDimensionRoot(worldKey);
        if (!dimensionRoot.toPath().toAbsolutePath().normalize()
                .equals(expectedDimensionRoot.toPath().toAbsolutePath().normalize())) {
            throw new IllegalStateException("Frozen Iris world storage does not match the current platform layout for "
                    + worldKey + ".");
        }
        File snapshotRoot = IrisWorldStorage.requireFrozenPackRoot(dimensionRoot);
        try {
            requireSnapshotLoadable(snapshotRoot);
        } catch (BrokenPackException exception) {
            Iris.error("Refusing to create world '" + worldName + "' using broken snapshot at '"
                    + snapshotRoot + "':");
            for (String reason : exception.getReasons()) {
                Iris.error("  - " + reason);
            }
            throw exception;
        }

        IrisDimension dimension = IrisData.get(snapshotRoot).getDimensionLoader().load(id, false);
        if (dimension == null) {
            throw new IllegalStateException("Frozen Iris pack snapshot at " + snapshotRoot
                    + " does not contain dimension " + id + ".");
        }

        Iris.debug("Assuming IrisDimension: " + dimension.getName());

        IrisWorld world = IrisWorld.builder()
                .platformIdentity(worldKey.toString())
                .name(worldName)
                .seed(1337)
                .worldFolder(dimensionRoot)
                .minHeight(dimension.getMinHeight())
                .maxHeight(dimension.getMaxHeight())
                .build();

        Iris.debug("Generator Config: " + world);

        return new BukkitChunkGenerator(world, false, snapshotRoot, dimension.getLoadKey());
    }

    private record FreshValidation(
            List<File> packDirs,
            ServerConfigurator.PackContentSnapshot contentSnapshot,
            List<PackValidationResult> results,
            boolean stable
    ) {
    }
}

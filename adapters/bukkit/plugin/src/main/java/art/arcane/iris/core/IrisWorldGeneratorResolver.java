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
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitPlugin;
import art.arcane.iris.util.common.plugin.VolmitSender;
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
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Pack validation, dimension lookup, and the world generator / biome provider resolution that the
 * Bukkit plugin entry points delegate to.
 */
public final class IrisWorldGeneratorResolver {
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
        String contentFingerprint = "";
        String contextFingerprint = "";
        Optional<List<PackValidationResult>> cached = Optional.empty();
        try {
            contentFingerprint = PackValidationCache.contentFingerprint(packsRoot);
            contextFingerprint = PackValidationCache.contextFingerprint();
            cached = PackValidationCache.load(
                    cacheFile,
                    contentFingerprint,
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
            results = new ArrayList<>(packDirs.size());
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
            try {
                PackValidationCache.save(cacheFile, contentFingerprint, contextFingerprint, results);
            } catch (IOException exception) {
                Iris.reportError("Could not persist Iris pack-validation results", exception);
            }
        }

        for (PackValidationResult result : results) {
            PackValidationRegistry.publish(result);
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

    static PackValidationResult requireSnapshotLoadable(File packRoot) {
        Path normalizedRoot = packRoot.toPath().toAbsolutePath().normalize();
        PackValidationResult result = PackValidationRegistry.get(normalizedRoot);
        if (result == null) {
            synchronized (SNAPSHOT_VALIDATION_LOCK) {
                result = PackValidationRegistry.get(normalizedRoot);
                if (result == null) {
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
                    PackValidationRegistry.publish(normalizedRoot, result);
                }
            }
        }
        return PackValidationRegistry.requireLoadable(normalizedRoot);
    }

    @Nullable
    public static IrisDimension loadDimension(@NonNull String worldName, @NonNull String id) {
        File pack = IrisWorldStorage.packRoot(IrisWorldStorage.keyFromName(worldName));
        IrisDimension dimension = pack.isDirectory() ? IrisData.get(pack).getDimensionLoader().load(id) : null;
        if (dimension == null) dimension = IrisData.loadAnyDimension(id, null);
        if (dimension == null) {
            File packsRoot = IrisPlatforms.get().dataFolderNoCreate(StudioSVC.WORKSPACE_NAME);
            if (PackDownloader.isPackPresent(packsRoot, id)) {
                Iris.error("Pack '" + id + "' exists at " + new File(packsRoot, id).getPath()
                        + " but its dimension failed to load; not redownloading. Fix or delete the pack folder.");
                return null;
            }
            Iris.warn("Unable to find dimension type " + id + " Looking for online packs...");
            Iris.service(StudioSVC.class).downloadSearch(new VolmitSender(Bukkit.getConsoleSender()), id, false);
            dimension = IrisData.loadAnyDimension(id, null);

            if (dimension != null) {
                Iris.info("Resolved missing dimension, proceeding.");
            }
        }

        return dimension;
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

        IrisDimension dim = loadDimension(worldName, id);
        if (dim == null) {
            throw new RuntimeException("Can't find dimension " + id + "!");
        }
        NamespacedKey worldKey = IrisWorldStorage.keyFromName(worldName);
        File snapshotRoot = IrisWorldStorage.packRoot(worldKey);
        File dimensionPackRoot = dim.getLoader().getDataFolder();
        String packName = dimensionPackRoot.getName();
        try {
            if (snapshotRoot.toPath().toAbsolutePath().normalize()
                    .equals(dimensionPackRoot.toPath().toAbsolutePath().normalize())) {
                requireSnapshotLoadable(snapshotRoot);
            } else {
                PackValidationRegistry.requireLoadable(packName);
            }
        } catch (BrokenPackException exception) {
            Iris.error("Refusing to create world '" + worldName + "' using broken pack '" + packName + "':");
            for (String reason : exception.getReasons()) {
                Iris.error("  - " + reason);
            }
            throw exception;
        }

        Iris.debug("Assuming IrisDimension: " + dim.getName());

        IrisWorld w = IrisWorld.builder()
                .platformIdentity(worldKey.toString())
                .name(worldName)
                .seed(1337)
                .worldFolder(IrisWorldStorage.dimensionRoot(worldKey))
                .minHeight(dim.getMinHeight())
                .maxHeight(dim.getMaxHeight())
                .build();

        Iris.debug("Generator Config: " + w.toString());

        File ff = new File(w.worldFolder(), "iris/pack");
        IrisDimension installedDimension = ff.isDirectory()
                ? IrisData.get(ff).getDimensionLoader().load(dim.getLoadKey(), false)
                : null;
        if (installedDimension == null) {
            dim = Iris.service(StudioSVC.class).replaceIntoWorld(Iris.getSender(), dim, w.worldFolder());
            if (dim == null) {
                throw new IllegalStateException("Failed to install dimension pack for " + id);
            }
        } else {
            dim = installedDimension;
        }
        requireSnapshotLoadable(ff);

        return new BukkitChunkGenerator(w, false, ff, dim.getLoadKey());
    }
}

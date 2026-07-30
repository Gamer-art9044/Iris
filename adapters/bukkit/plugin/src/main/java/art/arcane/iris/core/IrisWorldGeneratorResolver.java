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
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import art.arcane.iris.core.pack.PackValidator;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitPlugin;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.io.IO;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Supplier;

/**
 * Pack validation, dimension lookup, and the world generator / biome provider resolution that the
 * Bukkit plugin entry points delegate to.
 */
public final class IrisWorldGeneratorResolver {
    private final VolmitPlugin plugin;

    public IrisWorldGeneratorResolver(VolmitPlugin plugin) {
        this.plugin = plugin;
    }

    public void validateAllPacks() {
        File packsRoot = plugin.getDataFolder("packs");
        File[] packDirs = packsRoot.listFiles(File::isDirectory);
        if (packDirs == null || packDirs.length == 0) {
            return;
        }
        PackValidationRegistry.clear();
        for (File packDir : packDirs) {
            try {
                PackValidationResult result = PackValidator.validate(packDir);
                PackValidationRegistry.publish(result);
                if (!result.isLoadable()) {
                    Iris.error("Pack '" + result.getPackName() + "' FAILED validation - world/studio creation will be refused. Reasons:");
                    for (String reason : result.getBlockingErrors()) {
                        Iris.error("  - " + reason);
                    }
                } else if (!result.getWarnings().isEmpty()) {
                    Iris.info("Pack '" + result.getPackName() + "' validated ("
                            + result.getWarnings().size() + " warning(s)).");
                    for (String warning : result.getWarnings()) {
                        Iris.warn("  [" + result.getPackName() + "] " + warning);
                    }
                } else {
                    Iris.success("Pack '" + result.getPackName() + "' validated.");
                }
            } catch (Throwable e) {
                Iris.reportError("Pack validation failed for '" + packDir.getName() + "'", e);
            }
        }
    }

    @Nullable
    public static IrisDimension loadDimension(@NonNull String worldName, @NonNull String id) {
        File pack = IrisWorldStorage.packRoot(IrisWorldStorage.keyFromName(worldName));
        IrisDimension dimension = pack.isDirectory() ? IrisData.get(pack).getDimensionLoader().load(id) : null;
        if (dimension == null) dimension = IrisData.loadAnyDimension(id, null);
        if (dimension == null) {
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
        ChunkGenerator stagedGenerator = WorldLifecycleStaging.consumeGenerator(worldName);
        if (stagedGenerator != null) {
            Iris.debug("Using staged runtime generator for " + worldName);
            return stagedGenerator;
        }
        Iris.debug("Default World Generator Called for " + worldName + " using ID: " + id);
        if (id == null || id.isEmpty()) id = IrisSettings.get().getGenerator().getDefaultWorldType();
        Iris.debug("Generator ID: " + id + " requested by bukkit/plugin");

        PackValidationResult validation = PackValidationRegistry.get(id);
        if (validation != null && !validation.isLoadable()) {
            Iris.error("Refusing to create world '" + worldName + "' using broken pack '" + id + "':");
            for (String reason : validation.getBlockingErrors()) {
                Iris.error("  - " + reason);
            }
            throw new BrokenPackException(id, validation.getBlockingErrors());
        }

        IrisDimension dim = loadDimension(worldName, id);
        if (dim == null) {
            throw new RuntimeException("Can't find dimension " + id + "!");
        }

        Iris.debug("Assuming IrisDimension: " + dim.getName());
        NamespacedKey worldKey = IrisWorldStorage.keyFromName(worldName);

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
        File[] files = ff.listFiles();
        if (files == null || files.length == 0)
            IO.delete(ff);

        if (!ff.exists()) {
            ff.mkdirs();
            dim = Iris.service(StudioSVC.class).installIntoWorld(Iris.getSender(), dim, w.worldFolder());
            if (dim == null) {
                throw new IllegalStateException("Failed to install dimension pack for " + id);
            }
        }

        return new BukkitChunkGenerator(w, false, ff, dim.getLoadKey());
    }
}

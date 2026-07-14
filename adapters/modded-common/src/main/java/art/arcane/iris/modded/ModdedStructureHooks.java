/*
 * Iris is a World Generator for Minecraft Servers
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

package art.arcane.iris.modded;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformStructureHooks;
import art.arcane.iris.spi.PlatformWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ModdedStructureHooks implements PlatformStructureHooks {
    private final Supplier<MinecraftServer> server;

    public ModdedStructureHooks(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public List<String> structureKeys() {
        return registryKeys(Registries.STRUCTURE);
    }

    @Override
    public List<String> structureSetKeys() {
        return registryKeys(Registries.STRUCTURE_SET);
    }

    @Override
    public List<String> structureBiomeKeys(String structureKey) {
        List<String> keys = new ArrayList<>();
        try {
            MinecraftServer instance = server.get();
            if (instance == null) {
                return keys;
            }
            Identifier identifier = Identifier.tryParse(structureKey);
            if (identifier == null) {
                return keys;
            }
            Registry<Structure> registry = instance.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Structure structure = registry.getValue(identifier);
            if (structure == null) {
                return keys;
            }
            for (Holder<Biome> holder : structure.biomes()) {
                Optional<ResourceKey<Biome>> key = holder.unwrapKey();
                if (key.isPresent()) {
                    keys.add(key.get().identifier().toString());
                }
            }
        } catch (Throwable error) {
            IrisLogging.reportError(error);
        }
        return keys;
    }

    @Override
    public List<String> objectFeatureKeys() {
        List<String> keys = new ArrayList<>();
        try {
            MinecraftServer instance = server.get();
            if (instance == null) {
                return keys;
            }
            Registry<ConfiguredFeature<?, ?>> registry = instance.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
            for (Identifier identifier : registry.keySet()) {
                ConfiguredFeature<?, ?> configuredFeature = registry.getValue(identifier);
                if (configuredFeature == null) {
                    continue;
                }
                String group = classifyFeature(configuredFeature.feature());
                if (group != null) {
                    keys.add(group + "|" + identifier);
                }
            }
        } catch (Throwable error) {
            IrisLogging.reportError(error);
        }
        return keys;
    }

    @Override
    public List<String> reachableStructureKeys(PlatformWorld world) {
        List<String> keys = new ArrayList<>();
        try {
            ServerLevel level = level(world);
            if (level == null) {
                return keys;
            }
            BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
            Set<String> possibleBiomes = possibleBiomeKeys(source);
            if (possibleBiomes.isEmpty()) {
                return keys;
            }
            Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            for (Map.Entry<ResourceKey<Structure>, Structure> entry : registry.entrySet()) {
                if (hasPossibleBiome(entry.getValue(), possibleBiomes)) {
                    keys.add(entry.getKey().identifier().toString());
                }
            }
        } catch (Throwable error) {
            IrisLogging.reportError(error);
        }
        return keys;
    }

    @Override
    public List<String> possibleBiomeKeys(PlatformWorld world) {
        List<String> keys = new ArrayList<>();
        try {
            ServerLevel level = level(world);
            if (level == null) {
                return keys;
            }
            keys.addAll(possibleBiomeKeys(level.getChunkSource().getGenerator().getBiomeSource()));
        } catch (Throwable error) {
            IrisLogging.reportError(error);
        }
        return keys;
    }

    @Override
    public boolean placeFeature(PlatformWorld world, int x, int y, int z, String featureKey, long seed) {
        try {
            ServerLevel level = level(world);
            Identifier identifier = Identifier.tryParse(featureKey);
            if (level == null || identifier == null) {
                return false;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            Registry<ConfiguredFeature<?, ?>> registry = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
            ConfiguredFeature<?, ?> configuredFeature = registry.getValue(identifier);
            if (configuredFeature == null) {
                return false;
            }
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(seed));
            return configuredFeature.place(level, generator, random, new BlockPos(x, y, z));
        } catch (Throwable error) {
            IrisLogging.reportError(error);
            return false;
        }
    }

    @Override
    public int[] placeStructure(PlatformWorld world, int chunkX, int chunkZ, String structureKey, long seed, int maxSpan) {
        try {
            ServerLevel level = level(world);
            Identifier identifier = Identifier.tryParse(structureKey);
            if (level == null || identifier == null) {
                return null;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Structure structure = registry.getValue(identifier);
            if (structure == null) {
                return null;
            }
            Holder<Structure> holder = registry.wrapAsHolder(structure);
            RandomState randomState = level.getChunkSource().randomState();
            StructureTemplateManager templateManager = level.getStructureManager();
            StructureManager structureManager = level.structureManager();
            BiomeSource biomeSource = generator.getBiomeSource();
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            StructureStart start = structure.generate(
                    holder,
                    level.dimension(),
                    level.registryAccess(),
                    generator,
                    biomeSource,
                    randomState,
                    templateManager,
                    seed,
                    chunkPos,
                    0,
                    level,
                    biome -> true);
            if (start == null || !start.isValid()) {
                return null;
            }
            BoundingBox box = start.getBoundingBox();
            if (!isWithinSpan(box, maxSpan)) {
                return null;
            }
            placeChunks(level, structureManager, generator, start, box, seed);
            return bounds(box);
        } catch (Throwable error) {
            IrisLogging.reportError(error);
            return null;
        }
    }

    @Override
    public boolean supportsStructurePlacement() {
        return true;
    }

    static String classifyFeature(Feature<?> feature) {
        if (feature instanceof TreeFeature) {
            return "trees";
        }
        if (feature instanceof FallenTreeFeature) {
            return "fallen_trees";
        }
        if (feature instanceof AbstractHugeMushroomFeature) {
            return "mushrooms";
        }
        return null;
    }

    static boolean isWithinSpan(BoundingBox box, int maxSpan) {
        return maxSpan <= 0
                || box.getXSpan() <= maxSpan && box.getYSpan() <= maxSpan && box.getZSpan() <= maxSpan;
    }

    static int[] bounds(BoundingBox box) {
        return new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()};
    }

    private static Set<String> possibleBiomeKeys(BiomeSource source) {
        Set<String> keys = new LinkedHashSet<>();
        if (source == null) {
            return keys;
        }
        for (Holder<Biome> holder : source.possibleBiomes()) {
            Optional<ResourceKey<Biome>> key = holder.unwrapKey();
            if (key.isPresent()) {
                keys.add(key.get().identifier().toString());
            }
        }
        return keys;
    }

    private static boolean hasPossibleBiome(Structure structure, Set<String> possibleBiomeKeys) {
        for (Holder<Biome> holder : structure.biomes()) {
            Optional<ResourceKey<Biome>> key = holder.unwrapKey();
            if (key.isPresent() && possibleBiomeKeys.contains(key.get().identifier().toString())) {
                return true;
            }
        }
        return false;
    }

    private static ServerLevel level(PlatformWorld world) {
        return world instanceof ModdedPlatformWorld moddedWorld ? moddedWorld.level() : null;
    }

    private static void placeChunks(ServerLevel level, StructureManager structureManager, ChunkGenerator generator,
                                    StructureStart start, BoundingBox box, long seed) {
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(seed));
        int minChunkX = box.minX() >> 4;
        int maxChunkX = box.maxX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int maxChunkZ = box.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                BoundingBox chunkBox = new BoundingBox(
                        chunkPos.getMinBlockX(), box.minY(), chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX(), box.maxY(), chunkPos.getMaxBlockZ());
                start.placeInChunk(level, structureManager, generator, random, chunkBox, chunkPos);
            }
        }
    }

    private <T> List<String> registryKeys(ResourceKey<Registry<T>> registryKey) {
        List<String> keys = new ArrayList<>();
        try {
            MinecraftServer instance = server.get();
            if (instance == null) {
                return keys;
            }
            Registry<T> registry = instance.registryAccess().lookupOrThrow(registryKey);
            for (Identifier identifier : registry.keySet()) {
                keys.add(identifier.toString());
            }
        } catch (Throwable error) {
            IrisLogging.reportError(error);
        }
        return keys;
    }
}

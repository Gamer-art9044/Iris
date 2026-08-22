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

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Answer to a generator-plugin discovery probe.
 * <p>
 * Multiverse-Core calls {@code getDefaultWorldGenerator} with an empty dimension id and the name of
 * an already loaded world purely to find out whether a plugin is a generator plugin, then throws the
 * returned instance away. A non-null answer keeps Iris in {@code /mv generators} and in Multiverse's
 * generator tab-completion without Multiverse logging a warning block on every boot.
 * <p>
 * Nothing here can build terrain: every generation entry point refuses, so an instance that escapes
 * the probe fails loudly instead of silently producing vanilla chunks.
 */
final class IrisProbeChunkGenerator extends ChunkGenerator {
    private final String worldName;

    IrisProbeChunkGenerator(String worldName) {
        this.worldName = Objects.requireNonNull(worldName, "worldName");
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {
        throw refusal();
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {
        throw refusal();
    }

    @Override
    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {
        throw refusal();
    }

    @Override
    public void generateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {
        throw refusal();
    }

    @Override
    public ChunkData generateChunkData(@NotNull World world, @NotNull Random random, int x, int z, @NotNull BiomeGrid biome) {
        throw refusal();
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        throw refusal();
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull HeightMap heightMap) {
        throw refusal();
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        throw refusal();
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        throw refusal();
    }

    private IllegalStateException refusal() {
        return new IllegalStateException("Iris generator-discovery probe for '" + worldName
                + "' was asked to generate terrain. Iris worlds are created with /iris create.");
    }
}

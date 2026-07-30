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

package art.arcane.iris.spi;

import java.util.List;

/**
 * Neutral access to the host platform's structure, structure-set and configured-feature registries plus placement entry points.
 */
public interface PlatformStructureHooks {
    List<String> structureKeys();

    default List<String> jigsawStructureKeys() {
        return List.of();
    }

    default List<String> templatePoolKeys() {
        return List.of();
    }

    List<String> structureSetKeys();

    List<String> structureBiomeKeys(String structureKey);

    List<String> objectFeatureKeys();

    List<String> reachableStructureKeys(PlatformWorld world);

    List<String> possibleBiomeKeys(PlatformWorld world);

    boolean placeFeature(PlatformWorld world, int x, int y, int z, String featureKey, long seed);

    int[] placeStructure(PlatformWorld world, int chunkX, int chunkZ, String structureKey, long seed, int maxSpan);

    boolean supportsStructurePlacement();
}

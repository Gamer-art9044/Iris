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

package art.arcane.iris.core.structure.studio;

import art.arcane.iris.core.structure.authoring.StructureKey;
import art.arcane.iris.core.structure.authoring.StructureResourceBundle;
import art.arcane.iris.engine.object.ObjectPlaceMode;

import java.util.Objects;

public record SimpleStructureStudioPublishConfig(
        StructureKey structureKey,
        String resourceKey,
        int maxDepth,
        int maxSizeChunks,
        ObjectPlaceMode placeMode
) {
    public SimpleStructureStudioPublishConfig {
        Objects.requireNonNull(structureKey, "structureKey");
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(placeMode, "placeMode");
        if (resourceKey.isBlank()) {
            throw new IllegalArgumentException("resourceKey cannot be blank");
        }
        StructureResourceBundle.validateRelativePath("structures/" + resourceKey + ".json");
        if (!structureKey.path().equals(resourceKey)) {
            throw new IllegalArgumentException("structureKey path must match resourceKey: "
                    + structureKey.path() + " != " + resourceKey);
        }
        if (maxDepth < 1 || maxDepth > 30) {
            throw new IllegalArgumentException("maxDepth must be between 1 and 30: " + maxDepth);
        }
        if (maxSizeChunks < 1 || maxSizeChunks > 32) {
            throw new IllegalArgumentException("maxSizeChunks must be between 1 and 32: " + maxSizeChunks);
        }
    }

    public static SimpleStructureStudioPublishConfig defaults(
            StructureKey structureKey,
            String resourceKey
    ) {
        return new SimpleStructureStudioPublishConfig(
                structureKey,
                resourceKey,
                7,
                8,
                ObjectPlaceMode.STRUCTURE_PIECE
        );
    }
}

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

import java.util.Objects;

public record SimpleStructureStudioVariantKey(int cellX, int cellZ, String variantId) {
    public SimpleStructureStudioVariantKey {
        if (cellX < 0 || cellZ < 0) {
            throw new IllegalArgumentException("Variant cell coordinates cannot be negative: " + cellX + ", " + cellZ);
        }
        Objects.requireNonNull(variantId, "variantId");
        if (variantId.isBlank()) {
            throw new IllegalArgumentException("Variant id cannot be blank");
        }
    }

    public static SimpleStructureStudioVariantKey of(
            SimpleStructureStudioCell cell,
            SimpleStructureStudioVariant variant
    ) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(variant, "variant");
        return new SimpleStructureStudioVariantKey(cell.x(), cell.z(), variant.id());
    }
}

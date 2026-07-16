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

import art.arcane.iris.core.structure.authoring.StructureResourceBundle;

import java.util.Objects;
import java.util.regex.Pattern;

public record SimpleStructureStudioVariant(String id, int weight) {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9._-]+(?:/[a-z0-9._-]+)*");

    public SimpleStructureStudioVariant {
        Objects.requireNonNull(id, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Variant id must be a portable lowercase resource path: " + id);
        }
        StructureResourceBundle.validateRelativePath(id);
        if (weight <= 0) {
            throw new IllegalArgumentException("Variant weight must be greater than zero: " + weight);
        }
    }

    public SimpleStructureStudioVariant withWeight(int newWeight) {
        return new SimpleStructureStudioVariant(id, newWeight);
    }
}

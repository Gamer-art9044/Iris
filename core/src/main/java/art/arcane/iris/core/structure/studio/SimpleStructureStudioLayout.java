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

public record SimpleStructureStudioLayout(
        int gridWidth,
        int gridDepth,
        int cellWidth,
        int cellDepth,
        int captureHeight
) {
    public SimpleStructureStudioLayout {
        requirePositive("gridWidth", gridWidth);
        requirePositive("gridDepth", gridDepth);
        requirePositive("cellWidth", cellWidth);
        requirePositive("cellDepth", cellDepth);
        requirePositive("captureHeight", captureHeight);
        multiply("grid cell count", gridWidth, gridDepth);
        multiply("studio width", gridWidth, cellWidth);
        multiply("studio depth", gridDepth, cellDepth);
    }

    public int cellCount() {
        return gridWidth * gridDepth;
    }

    public int studioWidth() {
        return gridWidth * cellWidth;
    }

    public int studioDepth() {
        return gridDepth * cellDepth;
    }

    public boolean contains(int x, int z) {
        return x >= 0 && x < gridWidth && z >= 0 && z < gridDepth;
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
    }

    private static void multiply(String name, int first, int second) {
        try {
            Math.multiplyExact(first, second);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " exceeds the supported integer range", e);
        }
    }
}

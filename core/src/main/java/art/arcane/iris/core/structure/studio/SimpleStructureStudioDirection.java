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

public enum SimpleStructureStudioDirection {
    NORTH(1, 0, -1),
    EAST(2, 1, 0),
    SOUTH(4, 0, 1),
    WEST(8, -1, 0);

    public static final int ALL_MASK = 15;

    private static final SimpleStructureStudioDirection[] ORDERED = values();

    private final int mask;
    private final int offsetX;
    private final int offsetZ;

    SimpleStructureStudioDirection(int mask, int offsetX, int offsetZ) {
        this.mask = mask;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    public int mask() {
        return mask;
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetZ() {
        return offsetZ;
    }

    public SimpleStructureStudioDirection rotateClockwise(int quarterTurns) {
        int rotatedIndex = Math.floorMod((long) ordinal() + quarterTurns, ORDERED.length);
        return ORDERED[rotatedIndex];
    }

    public static int rotateMask(int connectorMask, int quarterTurns) {
        if ((connectorMask & ~ALL_MASK) != 0) {
            throw new IllegalArgumentException("Connector mask uses unsupported direction bits: " + connectorMask);
        }
        int normalizedTurns = Math.floorMod(quarterTurns, ORDERED.length);
        if (normalizedTurns == 0 || connectorMask == 0) {
            return connectorMask;
        }
        int rotatedMask = 0;
        for (SimpleStructureStudioDirection direction : ORDERED) {
            if ((connectorMask & direction.mask) != 0) {
                rotatedMask |= direction.rotateClockwise(normalizedTurns).mask;
            }
        }
        return rotatedMask;
    }
}

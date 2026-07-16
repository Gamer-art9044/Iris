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

public enum SimpleStructureStudioTopology {
    EMPTY(0),
    END(SimpleStructureStudioDirection.NORTH.mask()),
    STRAIGHT(SimpleStructureStudioDirection.NORTH.mask() | SimpleStructureStudioDirection.SOUTH.mask()),
    CORNER(SimpleStructureStudioDirection.NORTH.mask() | SimpleStructureStudioDirection.EAST.mask()),
    T(SimpleStructureStudioDirection.NORTH.mask()
            | SimpleStructureStudioDirection.EAST.mask()
            | SimpleStructureStudioDirection.WEST.mask()),
    CROSS(SimpleStructureStudioDirection.ALL_MASK),
    START(SimpleStructureStudioDirection.NORTH.mask()),
    TERMINAL(SimpleStructureStudioDirection.NORTH.mask());

    private final int baseConnectorMask;

    SimpleStructureStudioTopology(int baseConnectorMask) {
        this.baseConnectorMask = baseConnectorMask;
    }

    public int baseConnectorMask() {
        return baseConnectorMask;
    }

    public int connectorMask(int quarterTurns) {
        return SimpleStructureStudioDirection.rotateMask(baseConnectorMask, quarterTurns);
    }

    public int connectorCount() {
        return Integer.bitCount(baseConnectorMask);
    }

    public boolean connects(SimpleStructureStudioDirection direction, int quarterTurns) {
        return (connectorMask(quarterTurns) & direction.mask()) != 0;
    }
}

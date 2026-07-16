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

package art.arcane.iris.modded.structure;

import net.minecraft.core.Direction;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ModdedJigsawStructureCaptureTest {
    @Test
    public void namespacedSourcePathsRemainDistinctAndPortable() {
        String nested = ModdedJigsawStructureCapture.pieceName("village", "mod:a/b");
        String underscored = ModdedJigsawStructureCapture.pieceName("village", "mod_a:b");

        assertEquals("village/piece/mod/a/b", nested);
        assertEquals("village/piece/mod_a/b", underscored);
        assertNotEquals(nested, underscored);
        assertEquals("village/pool/mod/a/b", ModdedJigsawStructureCapture.poolName("village", "mod:a/b"));
        assertEquals(
                "village/piece/generated/legacy/mod/a/b",
                ModdedJigsawStructureCapture.legacyPieceName("village", "mod:a/b")
        );
    }

    @Test
    public void rootJsonRetainsGraphLimitsAndSourceIdentity() {
        Map<String, Object> root = ModdedJigsawStructureCapture.structureJson(
                "minecraft:village_plains",
                "village/pool/minecraft/village/plains/town_centers",
                6,
                81
        );

        assertEquals("minecraft:village_plains", root.get("vanillaSource"));
        assertEquals(6, root.get("maxDepth"));
        assertEquals(6, root.get("maxSizeChunks"));
        assertEquals("STRUCTURE_PIECE", root.get("placeMode"));
    }

    @Test
    public void connectorDirectionsUseIrisAxisNames() {
        assertEquals("UP_POSITIVE_Y", ModdedJigsawStructureCapture.directionName(Direction.UP));
        assertEquals("DOWN_NEGATIVE_Y", ModdedJigsawStructureCapture.directionName(Direction.DOWN));
        assertEquals("NORTH_NEGATIVE_Z", ModdedJigsawStructureCapture.directionName(Direction.NORTH));
        assertEquals("SOUTH_POSITIVE_Z", ModdedJigsawStructureCapture.directionName(Direction.SOUTH));
        assertEquals("EAST_POSITIVE_X", ModdedJigsawStructureCapture.directionName(Direction.EAST));
        assertEquals("WEST_NEGATIVE_X", ModdedJigsawStructureCapture.directionName(Direction.WEST));
    }
}

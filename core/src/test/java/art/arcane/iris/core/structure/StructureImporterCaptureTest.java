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

package art.arcane.iris.core.structure;

import art.arcane.iris.core.structure.authoring.StructureCapability;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StructureImporterCaptureTest {
    @Test
    public void captureReturnsAnInMemorySnapshotWithDistinctBlockMarkerLoss() {
        Structure structure = mock(Structure.class);
        Palette palette = mock(Palette.class);
        BlockState marker = mock(BlockState.class);
        BlockData markerData = mock(BlockData.class);
        Location location = mock(Location.class);
        when(structure.getSize()).thenReturn(new BlockVector(2, 3, 4));
        when(structure.getPalettes()).thenReturn(List.of(palette));
        when(structure.getPaletteCount()).thenReturn(2);
        when(structure.getEntityCount()).thenReturn(1);
        when(palette.getBlocks()).thenReturn(List.of(marker));
        when(marker.getLocation()).thenReturn(location);
        when(marker.getBlockData()).thenReturn(markerData);
        when(markerData.getMaterial()).thenReturn(Material.STRUCTURE_BLOCK);

        StructureImporter.CapturedStructure captured = StructureImporter.captureStructure(structure);

        assertEquals(2, captured.width());
        assertEquals(3, captured.height());
        assertEquals(4, captured.depth());
        assertEquals(1, captured.structureMarkers());
        assertEquals(0, captured.blocks());
        assertTrue(captured.capabilities().contains(StructureCapability.BLOCKS));
        assertFalse(captured.capabilities().contains(StructureCapability.CONNECTORS));
        assertTrue(captured.losses().stream().anyMatch(loss ->
                loss.capability() == StructureCapability.BLOCKS
                        && loss.code().equals("structure_markers_resolved")));
        assertFalse(captured.losses().stream().anyMatch(loss -> loss.code().equals("connectors_not_imported")));
    }
}

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

import com.google.gson.Gson;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SimpleStructureStudioModelTest {
    @Test
    public void topologyMasksRotateAcrossCardinalDirections() {
        assertEquals(0, SimpleStructureStudioTopology.EMPTY.baseConnectorMask());
        assertEquals(1, SimpleStructureStudioTopology.END.baseConnectorMask());
        assertEquals(5, SimpleStructureStudioTopology.STRAIGHT.baseConnectorMask());
        assertEquals(3, SimpleStructureStudioTopology.CORNER.baseConnectorMask());
        assertEquals(11, SimpleStructureStudioTopology.T.baseConnectorMask());
        assertEquals(15, SimpleStructureStudioTopology.CROSS.baseConnectorMask());

        assertEquals(6, SimpleStructureStudioTopology.CORNER.connectorMask(1));
        assertEquals(12, SimpleStructureStudioTopology.CORNER.connectorMask(2));
        assertEquals(9, SimpleStructureStudioTopology.CORNER.connectorMask(3));
        assertTrue(SimpleStructureStudioTopology.CORNER.connects(SimpleStructureStudioDirection.EAST, 1));
        assertTrue(SimpleStructureStudioTopology.CORNER.connects(SimpleStructureStudioDirection.SOUTH, 1));
        assertFalse(SimpleStructureStudioTopology.CORNER.connects(SimpleStructureStudioDirection.NORTH, 1));
    }

    @Test
    public void cellVariantsAreWeightedSelectableAndImmutable() {
        SimpleStructureStudioCell cell = SimpleStructureStudioCell
                .create(1, 2, SimpleStructureStudioTopology.CORNER)
                .addVariant(new SimpleStructureStudioVariant("stone", 2))
                .addVariant(new SimpleStructureStudioVariant("mossy", 5));

        assertEquals("stone", cell.activeVariant().orElseThrow().id());
        SimpleStructureStudioCell selected = cell.selectVariant("mossy");
        assertEquals("mossy", selected.activeVariant().orElseThrow().id());
        assertEquals("stone", selected.cycleVariant(1).activeVariant().orElseThrow().id());
        assertEquals(7, selected.setVariantWeight("mossy", 7).variants().get(1).weight());
        assertThrows(UnsupportedOperationException.class, () -> selected.variants().add(
                new SimpleStructureStudioVariant("extra", 1)
        ));
        assertThrows(IllegalArgumentException.class, () -> cell.addVariant(
                new SimpleStructureStudioVariant("stone", 1)
        ));
    }

    @Test
    public void draftIsCanonicalAndJsonRoundTrips() {
        SimpleStructureStudioLayout layout = new SimpleStructureStudioLayout(4, 3, 9, 11, 24);
        SimpleStructureStudioCell later = SimpleStructureStudioCell.create(
                3,
                2,
                SimpleStructureStudioTopology.TERMINAL
        );
        SimpleStructureStudioCell earlier = SimpleStructureStudioCell.create(
                0,
                0,
                SimpleStructureStudioTopology.START
        );
        SimpleStructureStudioDraft draft = new SimpleStructureStudioDraft(
                layout,
                9921L,
                List.of(later, earlier)
        );

        assertEquals(earlier, draft.cells().get(0));
        assertEquals(later, draft.cells().get(1));
        assertEquals(36, layout.studioWidth());
        assertEquals(33, layout.studioDepth());
        assertThrows(
                IllegalStateException.class,
                () -> draft.withLayout(new SimpleStructureStudioLayout(5, 3, 9, 11, 24))
        );

        Gson gson = new Gson();
        String json = gson.toJson(draft);
        SimpleStructureStudioDraft restored = gson.fromJson(json, SimpleStructureStudioDraft.class);
        assertEquals(draft, restored);
    }

    @Test
    public void draftRejectsInvalidGeometryAndConnectorHeights() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleStructureStudioLayout(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SimpleStructureStudioVariant("../piece", 1));
        assertThrows(IllegalArgumentException.class, () -> new SimpleStructureStudioVariant("piece.", 1));
        assertThrows(IllegalArgumentException.class, () -> new SimpleStructureStudioVariant("con", 1));
        assertThrows(IllegalArgumentException.class, () -> new SimpleStructureStudioVariant("nul", 1));
        assertThrows(IllegalArgumentException.class, () -> new SimpleStructureStudioVariant("com1", 1));
        SimpleStructureStudioLayout layout = new SimpleStructureStudioLayout(2, 2, 8, 8, 4);
        SimpleStructureStudioCell tooTall = SimpleStructureStudioCell
                .create(0, 0, SimpleStructureStudioTopology.END)
                .withConnector("iris:path", 4);

        assertThrows(
                IllegalArgumentException.class,
                () -> new SimpleStructureStudioDraft(layout, 0L, List.of(tooTall))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimpleStructureStudioDraft(
                        layout,
                        0L,
                        List.of(
                                SimpleStructureStudioCell.create(0, 0, SimpleStructureStudioTopology.END),
                                SimpleStructureStudioCell.create(0, 0, SimpleStructureStudioTopology.CORNER)
                        )
                )
        );
    }
}

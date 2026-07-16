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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SimpleStructureStudioSessionTest {
    @Test
    public void sessionTracksDirtySavedUndoAndRedoStates() {
        SimpleStructureStudioDraft initial = emptyDraft();
        SimpleStructureStudioSession session = SimpleStructureStudioSession.open(initial, 8);

        assertFalse(session.isDirty());
        assertTrue(session.setTopology(1, 1, SimpleStructureStudioTopology.CORNER));
        assertTrue(session.isDirty());
        assertEquals(3, session.draft().cellOrEmpty(1, 1).connectorMask());

        session.markSaved();
        assertFalse(session.isDirty());
        assertTrue(session.rotateClockwise(1, 1));
        assertEquals(6, session.draft().cellOrEmpty(1, 1).connectorMask());
        assertTrue(session.isDirty());

        assertTrue(session.undo());
        assertFalse(session.isDirty());
        assertEquals(3, session.draft().cellOrEmpty(1, 1).connectorMask());
        assertTrue(session.redo());
        assertTrue(session.isDirty());
        assertEquals(6, session.draft().cellOrEmpty(1, 1).connectorMask());
    }

    @Test
    public void resizeIsAllowedOnlyBeforeContentExists() {
        SimpleStructureStudioSession session = SimpleStructureStudioSession.open(emptyDraft(), 8);
        SimpleStructureStudioLayout resized = new SimpleStructureStudioLayout(6, 5, 12, 14, 28);

        assertTrue(session.resize(resized));
        assertEquals(resized, session.draft().layout());
        assertTrue(session.setTopology(0, 0, SimpleStructureStudioTopology.START));
        assertThrows(
                IllegalStateException.class,
                () -> session.resize(new SimpleStructureStudioLayout(7, 5, 12, 14, 28))
        );

        assertTrue(session.clearCell(0, 0));
        assertTrue(session.resize(new SimpleStructureStudioLayout(7, 5, 12, 14, 28)));
    }

    @Test
    public void variantsConnectorsAndRotationPoliciesUseSharedActions() {
        SimpleStructureStudioSession session = SimpleStructureStudioSession.open(emptyDraft(), 16);
        session.setTopology(2, 1, SimpleStructureStudioTopology.END);
        session.setConnector(2, 1, "iris:hall", 6);
        session.addVariant(2, 1, new SimpleStructureStudioVariant("plain", 1));
        session.addVariant(2, 1, new SimpleStructureStudioVariant("ruined", 3));
        session.selectVariant(2, 1, "ruined");
        session.setVariantWeight(2, 1, "ruined", 8);

        SimpleStructureStudioCell configured = session.draft().cellOrEmpty(2, 1);
        assertEquals("iris:hall", configured.connectorChannel());
        assertEquals(6, configured.connectorHeight());
        assertEquals("ruined", configured.activeVariant().orElseThrow().id());
        assertEquals(8, configured.activeVariant().orElseThrow().weight());

        assertTrue(session.setRotationPolicy(2, 1, SimpleStructureStudioRotationPolicy.HALF_TURNS));
        assertTrue(session.rotateClockwise(2, 1));
        assertEquals(2, session.draft().cellOrEmpty(2, 1).quarterTurns());
        assertTrue(session.setRotationPolicy(2, 1, SimpleStructureStudioRotationPolicy.FIXED));
        assertEquals(0, session.draft().cellOrEmpty(2, 1).quarterTurns());
        assertFalse(session.rotateClockwise(2, 1));
    }

    @Test
    public void previewSeedSequenceAndHistoryBoundAreDeterministic() {
        SimpleStructureStudioSession first = SimpleStructureStudioSession.open(emptyDraft(), 3);
        SimpleStructureStudioSession second = SimpleStructureStudioSession.open(emptyDraft(), 3);

        assertEquals(first.advancePreviewSeed(), second.advancePreviewSeed());
        assertEquals(first.advancePreviewSeed(), second.advancePreviewSeed());

        first.setPreviewSeed(1L);
        first.setPreviewSeed(2L);
        first.setPreviewSeed(3L);
        first.setPreviewSeed(4L);
        assertEquals(3, first.undoDepth());
        assertTrue(first.undo());
        assertEquals(3L, first.draft().previewSeed());
        assertTrue(first.undo());
        assertEquals(2L, first.draft().previewSeed());
        assertTrue(first.undo());
        assertEquals(1L, first.draft().previewSeed());
        assertFalse(first.undo());

        assertTrue(first.redo());
        assertTrue(first.setPreviewSeed(99L));
        assertFalse(first.canRedo());
    }

    @Test
    public void newDraftRemainsDirtyUntilSaved() {
        SimpleStructureStudioSession session = SimpleStructureStudioSession.createNew(emptyDraft(), 8);

        assertTrue(session.isDirty());
        session.markSaved();
        assertFalse(session.isDirty());
        assertThrows(IllegalStateException.class, () -> session.addVariant(
                0,
                0,
                new SimpleStructureStudioVariant("missing", 1)
        ));
    }

    private SimpleStructureStudioDraft emptyDraft() {
        return SimpleStructureStudioDraft.empty(
                new SimpleStructureStudioLayout(4, 4, 10, 10, 20),
                12345L
        );
    }
}

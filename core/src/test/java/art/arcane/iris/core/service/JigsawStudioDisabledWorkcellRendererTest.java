package art.arcane.iris.core.service;

import art.arcane.iris.core.runtime.jigsaw.JigsawPlanarArchetype;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioCellDimensions;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioLayout;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioVariantCatalog;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioWorkcellSpec;
import org.bukkit.entity.BlockDisplay;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class JigsawStudioDisabledWorkcellRendererTest {
    @Test
    public void disabledWorkcellProducesOneFullVolumeDescriptor() {
        JigsawStudioLayout layout = layoutWithDisabled(JigsawPlanarArchetype.TEE);

        Map<String, JigsawStudioDisabledWorkcellRenderer.Descriptor> descriptors =
                JigsawStudioDisabledWorkcellRenderer.descriptors(layout);

        assertEquals(1, descriptors.size());
        JigsawStudioDisabledWorkcellRenderer.Descriptor descriptor =
                descriptors.get(JigsawPlanarArchetype.TEE.stableId());
        assertEquals(11, descriptor.width());
        assertEquals(5, descriptor.height());
        assertEquals(7, descriptor.depth());
        assertEquals(JigsawStudioLayout.FLOOR_Y + 1, descriptor.originY());
    }

    @Test
    public void enabledWorkcellsNeverProduceRedGlassDescriptors() {
        JigsawStudioLayout layout = layoutWithDisabled(null);

        Map<String, JigsawStudioDisabledWorkcellRenderer.Descriptor> descriptors =
                JigsawStudioDisabledWorkcellRenderer.descriptors(layout);

        assertTrue(descriptors.isEmpty());
        assertFalse(layout.bays().isEmpty());
    }

    @Test
    public void chunkUnloadDetachesOnlyDisplaysWhoseOriginsBelongToThatChunk() {
        BlockDisplay target = mock(BlockDisplay.class);
        BlockDisplay retained = mock(BlockDisplay.class);
        JigsawStudioDisabledWorkcellRenderer.Descriptor targetDescriptor =
                new JigsawStudioDisabledWorkcellRenderer.Descriptor("workcell/tee", 31, 65, -1, 3, 3, 3);
        JigsawStudioDisabledWorkcellRenderer.Descriptor retainedDescriptor =
                new JigsawStudioDisabledWorkcellRenderer.Descriptor("workcell/cross", 32, 65, -1, 3, 3, 3);
        Map<String, BlockDisplay> entities = new HashMap<>(Map.of(
                targetDescriptor.workcellId(), target,
                retainedDescriptor.workcellId(), retained));
        Map<String, JigsawStudioDisabledWorkcellRenderer.Descriptor> rendered = new HashMap<>(Map.of(
                targetDescriptor.workcellId(), targetDescriptor,
                retainedDescriptor.workcellId(), retainedDescriptor));

        List<BlockDisplay> removals = JigsawStudioDisabledWorkcellRenderer.detachChunkDisplays(
                entities, rendered, 1, -1);

        assertEquals(List.of(target), removals);
        assertFalse(entities.containsKey(targetDescriptor.workcellId()));
        assertFalse(rendered.containsKey(targetDescriptor.workcellId()));
        assertEquals(retained, entities.get(retainedDescriptor.workcellId()));
        assertEquals(retainedDescriptor, rendered.get(retainedDescriptor.workcellId()));
        verifyNoInteractions(target, retained);
    }

    private static JigsawStudioLayout layoutWithDisabled(JigsawPlanarArchetype disabled) {
        List<JigsawStudioWorkcellSpec> specs = new ArrayList<>();
        for (JigsawPlanarArchetype archetype : JigsawPlanarArchetype.values()) {
            JigsawStudioCellDimensions dimensions = archetype == JigsawPlanarArchetype.TEE
                    ? new JigsawStudioCellDimensions(11, 5, 7)
                    : new JigsawStudioCellDimensions(3, 3, 3);
            specs.add(new JigsawStudioWorkcellSpec(archetype, "", dimensions, archetype != disabled));
        }
        return JigsawStudioLayout.createPlanar(
                new JigsawStudioCellDimensions(3, 3, 3),
                specs,
                JigsawStudioVariantCatalog.empty());
    }
}

package art.arcane.iris.nativegen;

import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeStructurePostProcessorVegetationTest {
    @Test
    public void surfaceStructuresClearTreeColumnsAutomatically() {
        assertTrue(NativeStructurePostProcessor.shouldClearVegetationColumn(100, 100, false));
        assertTrue(NativeStructurePostProcessor.shouldClearVegetationColumn(116, 100, false));
    }

    @Test
    public void buriedStructuresPreserveUnrelatedSurfaceForest() {
        assertFalse(NativeStructurePostProcessor.shouldClearVegetationColumn(99, 100, false));
        assertFalse(NativeStructurePostProcessor.shouldClearVegetationColumn(20, 100, false));
    }

    @Test
    public void explicitVegetationOptionForcesUnusualPlacementCleanup() {
        assertTrue(NativeStructurePostProcessor.shouldClearVegetationColumn(20, 100, true));
    }

    @Test
    public void surfaceStructuresPreserveVegetationUnlessConfigured() {
        assertFalse(NativeStructurePostProcessor.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.SURFACE_STRUCTURES, false));
        assertTrue(NativeStructurePostProcessor.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.SURFACE_STRUCTURES, true));
    }

    @Test
    public void undergroundStructuresPreserveSurfaceVegetationUnlessConfigured() {
        assertFalse(NativeStructurePostProcessor.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.UNDERGROUND_STRUCTURES, false));
        assertTrue(NativeStructurePostProcessor.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.UNDERGROUND_STRUCTURES, true));
    }

    @Test
    public void allUndergroundGenerationStepsShareOneClassification() {
        assertTrue(NativeStructurePostProcessor.isUndergroundStep(
                GenerationStep.Decoration.UNDERGROUND_STRUCTURES));
        assertTrue(NativeStructurePostProcessor.isUndergroundStep(
                GenerationStep.Decoration.UNDERGROUND_DECORATION));
        assertTrue(NativeStructurePostProcessor.isUndergroundStep(
                GenerationStep.Decoration.STRONGHOLDS));
        assertFalse(NativeStructurePostProcessor.isUndergroundStep(
                GenerationStep.Decoration.SURFACE_STRUCTURES));
    }

    @Test
    public void undergroundStructuresUseTheLowestTerrainColumn() {
        BoundingBox bounds = new BoundingBox(0, 60, 0, 1, 80, 0);
        int offset = NativeStructurePostProcessor.resolveBuriedOffset(
                bounds, 0, -64, 320, (x, z) -> x == 1 ? 76 : 100);
        assertEquals(-5, offset);
    }

    @Test
    public void undergroundBurialClampsToTheWorldFloorInsteadOfFailing() {
        BoundingBox bounds = new BoundingBox(0, 60, 0, 1, 80, 0);
        assertEquals(-2, NativeStructurePostProcessor.resolveBuriedOffset(
                bounds, 0, 58, 320, (x, z) -> x == 1 ? 76 : 100));
    }
}

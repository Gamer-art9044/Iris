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
        assertTrue(NativeStructureVegetationClearer.shouldClearVegetationColumn(100, 100, false));
        assertTrue(NativeStructureVegetationClearer.shouldClearVegetationColumn(116, 100, false));
    }

    @Test
    public void buriedStructuresPreserveUnrelatedSurfaceForest() {
        assertFalse(NativeStructureVegetationClearer.shouldClearVegetationColumn(99, 100, false));
        assertFalse(NativeStructureVegetationClearer.shouldClearVegetationColumn(20, 100, false));
    }

    @Test
    public void explicitVegetationOptionForcesUnusualPlacementCleanup() {
        assertTrue(NativeStructureVegetationClearer.shouldClearVegetationColumn(20, 100, true));
    }

    @Test
    public void surfaceStructuresPreserveVegetationUnlessConfigured() {
        assertFalse(NativeStructureVegetationClearer.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.SURFACE_STRUCTURES, false));
        assertTrue(NativeStructureVegetationClearer.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.SURFACE_STRUCTURES, true));
    }

    @Test
    public void undergroundStructuresPreserveSurfaceVegetationUnlessConfigured() {
        assertFalse(NativeStructureVegetationClearer.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.UNDERGROUND_STRUCTURES, false));
        assertTrue(NativeStructureVegetationClearer.shouldClearEntireVegetationFootprint(
                GenerationStep.Decoration.UNDERGROUND_STRUCTURES, true));
    }

    @Test
    public void allUndergroundGenerationStepsShareOneClassification() {
        assertTrue(NativeStructureVegetationClearer.isUndergroundStep(
                GenerationStep.Decoration.UNDERGROUND_STRUCTURES));
        assertTrue(NativeStructureVegetationClearer.isUndergroundStep(
                GenerationStep.Decoration.UNDERGROUND_DECORATION));
        assertTrue(NativeStructureVegetationClearer.isUndergroundStep(
                GenerationStep.Decoration.STRONGHOLDS));
        assertFalse(NativeStructureVegetationClearer.isUndergroundStep(
                GenerationStep.Decoration.SURFACE_STRUCTURES));
    }

    @Test
    public void undergroundStructuresUseTheLowestTerrainColumn() {
        BoundingBox bounds = new BoundingBox(0, 60, 0, 1, 80, 0);
        int offset = NativeStructureVerticalPlacer.resolveBuriedOffset(
                bounds, 0, -64, 320, (x, z) -> x == 1 ? 76 : 100);
        assertEquals(-5, offset);
    }

    @Test
    public void undergroundBurialClampsToTheWorldFloorInsteadOfFailing() {
        BoundingBox bounds = new BoundingBox(0, 60, 0, 1, 80, 0);
        assertEquals(-2, NativeStructureVerticalPlacer.resolveBuriedOffset(
                bounds, 0, 58, 320, (x, z) -> x == 1 ? 76 : 100));
    }
}

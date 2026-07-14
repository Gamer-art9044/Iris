package art.arcane.iris.nativegen;

import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.Test;

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
}

package art.arcane.iris.engine.framework;

import art.arcane.iris.engine.object.IrisNativeStructure;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import art.arcane.iris.engine.object.StructureDistribution;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NativeStructurePlacementPlannerTest {
    @Test
    public void undergroundPlanIsDeterministicAndUsesConfiguredBand() {
        Engine engine = engine(982374L, -64, 384, 90);
        IrisStructurePlacement placement = nativePlacement()
                .setUnderground(true)
                .setMinHeight(-45)
                .setMaxHeight(-20);

        NativeStructureStartPlan first = NativeStructurePlacementPlanner.planAt(engine, placement, 12, -7);
        NativeStructureStartPlan second = NativeStructurePlacementPlanner.planAt(engine, placement, 12, -7);

        assertNotNull(first);
        assertEquals(first, second);
        assertEquals("minecraft:ancient_city", first.source().getStructure());
        assertEquals(12, first.chunkX());
        assertEquals(-7, first.chunkZ());
        assertTrue(first.baseY() >= -45 && first.baseY() <= -20);
    }

    @Test
    public void surfacePlanUsesIrisTerrainAndHonorsHeightGate() {
        Engine engine = engine(77L, -64, 384, 150);
        IrisStructurePlacement placement = nativePlacement()
                .setMinHeight(80)
                .setMaxHeight(90);

        NativeStructureStartPlan plan = NativeStructurePlacementPlanner.planAt(engine, placement, 0, 0);

        assertNotNull(plan);
        assertEquals(86, plan.baseY());

        placement.setMinHeight(87);
        assertNull(NativeStructurePlacementPlanner.planAt(engine, placement, 0, 0));
    }

    @Test
    public void configuredDecisionOverridesSuppressedSourceWithoutLosingTerrain() {
        IrisStructureTerrain terrain = new IrisStructureTerrain()
                .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                .setHorizontalPadding(24);
        IrisStructurePlacement placement = nativePlacement()
                .setUnderground(true)
                .setTerrain(terrain);
        NativeStructureStartPlan plan = new NativeStructureStartPlan(
                placement, placement.getNativeStructures().getFirst(), 3, 5, -30);

        IrisNativeStructureDecision decision = NativeStructurePlacementPlanner.decisionFor(plan);

        assertEquals(NativeStructureGenerationStatus.GENERATE_NATIVE, decision.status());
        assertEquals(false, decision.clearVegetation());
        assertSame(terrain, decision.terrain());
    }

    @Test
    public void placementMustChooseExactlyOneBackend() {
        assertInvalid(new IrisStructurePlacement());
        IrisStructurePlacement mixed = nativePlacement();
        mixed.getStructures().add("legacy");
        assertInvalid(mixed);
    }

    private IrisStructurePlacement nativePlacement() {
        IrisStructurePlacement placement = new IrisStructurePlacement()
                .setDistribution(StructureDistribution.DENSITY)
                .setDensity(1D);
        placement.getNativeStructures().add(new IrisNativeStructure()
                .setStructure("minecraft:ancient_city")
                .setWeight(1));
        return placement;
    }

    private Engine engine(long seed, int minHeight, int height, int terrainHeight) {
        Engine engine = mock(Engine.class);
        when(engine.getSeedManager()).thenReturn(new SeedManager(seed));
        when(engine.getMinHeight()).thenReturn(minHeight);
        when(engine.getHeight()).thenReturn(height);
        when(engine.getHeight(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(terrainHeight);
        return engine;
    }

    private void assertInvalid(IrisStructurePlacement placement) {
        try {
            NativeStructurePlacementPlanner.validateBackend(placement);
            fail("Expected an invalid structure backend");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("exactly one backend"));
        }
    }
}

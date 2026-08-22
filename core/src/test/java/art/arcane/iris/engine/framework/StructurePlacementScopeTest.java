package art.arcane.iris.engine.framework;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructureAnchorMode;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StructurePlacementScopeTest {
    @Test
    public void caveBiomeContributesOnlyCaveAnchoredPlacements() {
        IrisStructurePlacement surfacePlacement = new IrisStructurePlacement();
        IrisStructurePlacement cavePlacement = new IrisStructurePlacement()
                .setAnchor(IrisStructureAnchorMode.CAVE_FLOOR);
        IrisStructurePlacement invalidCaveScopePlacement = new IrisStructurePlacement()
                .setAnchor(IrisStructureAnchorMode.SURFACE);
        IrisStructurePlacement regionPlacement = new IrisStructurePlacement();
        IrisStructurePlacement dimensionPlacement = new IrisStructurePlacement();

        IrisBiome surfaceBiome = new IrisBiome();
        surfaceBiome.getStructures().add(surfacePlacement);
        IrisBiome caveBiome = new IrisBiome();
        caveBiome.getStructures().add(cavePlacement);
        caveBiome.getStructures().add(invalidCaveScopePlacement);
        IrisRegion region = new IrisRegion();
        region.getStructures().add(regionPlacement);
        IrisDimension dimension = new IrisDimension();
        dimension.getStructures().add(dimensionPlacement);
        dimension.getStructures().add(cavePlacement);

        Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS);
        IrisComplex complex = engine.getComplex();
        when(engine.getDimension()).thenReturn(dimension);
        when(complex.getTrueBiomeStream().get(8, 8)).thenReturn(surfaceBiome);
        when(complex.getCaveBiomeStream().get(8, 8)).thenReturn(caveBiome);
        when(complex.getRegionStream().get(8, 8)).thenReturn(region);

        KList<IrisStructurePlacement> placements = StructurePlacementScope.placementsAt(engine, 0, 0);

        assertEquals(4, placements.size());
        assertTrue(placements.contains(surfacePlacement));
        assertTrue(placements.contains(cavePlacement));
        assertTrue(placements.contains(regionPlacement));
        assertTrue(placements.contains(dimensionPlacement));
        assertFalse(placements.contains(invalidCaveScopePlacement));
    }
}

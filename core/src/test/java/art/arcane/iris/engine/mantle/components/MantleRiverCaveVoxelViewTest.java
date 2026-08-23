package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.river.cave.CavePosition;
import art.arcane.iris.engine.river.cave.CaveVoxel;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.matter.Matter;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MantleRiverCaveVoxelViewTest {
    @Test
    @SuppressWarnings("unchecked")
    public void absentCellsAboveLocalTerrainAreOpenAir() {
        Mantle<Matter> mantle = mock(Mantle.class);
        when(mantle.getLoadedRegions()).thenReturn(new KMap<Long, TectonicPlate<Matter>>());
        MantleRiverCaveVoxelView view = new MantleRiverCaveVoxelView(
                mantle,
                128,
                (x, z) -> x < 0 ? 20 : 60,
                (x, z) -> null
        );
        CavePosition cliffAir = new CavePosition(-1, 21, 0);
        CavePosition terrain = new CavePosition(-1, 20, 0);

        assertEquals(CaveVoxel.CAVE_AIR, view.voxelAt(cliffAir));
        assertTrue(view.isOpenToSurface(cliffAir));
        assertEquals(CaveVoxel.SOLID, view.voxelAt(terrain));
        assertFalse(view.isOpenToSurface(terrain));
    }
}

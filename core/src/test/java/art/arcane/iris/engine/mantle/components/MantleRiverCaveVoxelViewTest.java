package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.river.cave.CavePosition;
import art.arcane.iris.engine.river.cave.CaveVoxel;
import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterSlice;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
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

    @Test
    @SuppressWarnings("unchecked")
    public void publishedRiverActionsDoNotReplaceTheUnderlyingPlanningBaseline() {
        Mantle<Matter> mantle = mock(Mantle.class);
        TectonicPlate<Matter> plate = mock(TectonicPlate.class);
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        Matter matter = mock(Matter.class);
        MatterSlice<RiverCaveHydrology> hydrologySlice = mock(MatterSlice.class);
        KMap<Long, TectonicPlate<Matter>> regions = new KMap<>();
        regions.put(Mantle.key(0, 0), plate);
        CavePosition position = new CavePosition(0, 20, 0);
        when(mantle.getLoadedRegions()).thenReturn(regions);
        when(plate.get(0, 0)).thenReturn(chunk);
        when(chunk.exists(1)).thenReturn(true);
        when(chunk.get(1)).thenReturn(matter);
        when(matter.hasSlice(RiverCaveHydrology.class)).thenReturn(true);
        doReturn(hydrologySlice).when(matter).getSlice(RiverCaveHydrology.class);
        when(hydrologySlice.get(0, 4, 0)).thenReturn(RiverCaveHydrology.of(RiverCaveAction.WET_SOURCE));
        MantleRiverCaveVoxelView view = new MantleRiverCaveVoxelView(
                mantle,
                128,
                (x, z) -> 60,
                (x, z) -> null
        );

        assertEquals(CaveVoxel.SOLID, view.voxelAt(position));
        assertEquals(RiverCaveAction.WET_SOURCE, view.riverActionAt(position));
    }
}

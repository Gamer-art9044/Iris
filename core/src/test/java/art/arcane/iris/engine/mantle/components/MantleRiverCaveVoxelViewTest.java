package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.river.cave.CavePosition;
import art.arcane.iris.engine.river.cave.CaveVoxel;
import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveFluidKind;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterSlice;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.iris.spi.PlatformBlockState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
                (x, z) -> null,
                RiverCaveFluidKind.RIVER,
                (chunkX, chunkZ) -> {
                }
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
    public void carvingInputLoadsOnlyOncePerReadChunk() {
        Mantle<Matter> mantle = mock(Mantle.class);
        when(mantle.getLoadedRegions()).thenReturn(new KMap<Long, TectonicPlate<Matter>>());
        List<String> loaded = new ArrayList<>();
        MantleRiverCaveVoxelView view = new MantleRiverCaveVoxelView(
                mantle,
                128,
                (x, z) -> 60,
                (x, z) -> null,
                RiverCaveFluidKind.RIVER,
                (chunkX, chunkZ) -> loaded.add(chunkX + "," + chunkZ)
        );

        view.voxelAt(new CavePosition(0, 20, 0));
        view.voxelAt(new CavePosition(15, 21, 15));
        view.riverHydrologyAt(new CavePosition(1, 22, 1));
        view.voxelAt(new CavePosition(16, 20, 0));

        assertEquals(List.of("0,0", "1,0"), loaded);
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
                (x, z) -> null,
                RiverCaveFluidKind.RIVER,
                (chunkX, chunkZ) -> {
                }
        );

        assertEquals(CaveVoxel.SOLID, view.voxelAt(position));
        assertEquals(RiverCaveAction.WET_SOURCE, view.riverHydrologyAt(position).action());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void configuredLavaRiverTreatsPublishedLavaAsCompatibleFluid() {
        Mantle<Matter> mantle = mock(Mantle.class);
        TectonicPlate<Matter> plate = mock(TectonicPlate.class);
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        Matter matter = mock(Matter.class);
        MatterSlice<MatterCavern> cavernSlice = mock(MatterSlice.class);
        PlatformBlockState lava = mock(PlatformBlockState.class);
        KMap<Long, TectonicPlate<Matter>> regions = new KMap<>();
        regions.put(Mantle.key(0, 0), plate);
        when(mantle.getLoadedRegions()).thenReturn(regions);
        when(plate.get(0, 0)).thenReturn(chunk);
        when(chunk.exists(1)).thenReturn(true);
        when(chunk.get(1)).thenReturn(matter);
        when(matter.hasSlice(MatterCavern.class)).thenReturn(true);
        doReturn(cavernSlice).when(matter).getSlice(MatterCavern.class);
        when(cavernSlice.get(0, 4, 0)).thenReturn(new MatterCavern(true, "", (byte) 2));
        when(lava.materialKey()).thenReturn("minecraft:lava");
        MantleRiverCaveVoxelView view = new MantleRiverCaveVoxelView(
                mantle,
                128,
                (x, z) -> 60,
                (x, z) -> lava,
                RiverCaveFluidKind.RIVER,
                (chunkX, chunkZ) -> {
                }
        );

        assertEquals(CaveVoxel.COMPATIBLE_FLUID, view.voxelAt(new CavePosition(0, 20, 0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void planningRejectsHydrologyOwnedByTheOtherFluidKind() {
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
        when(hydrologySlice.get(0, 4, 0)).thenReturn(RiverCaveHydrology.of(
                RiverCaveAction.WET_SOURCE,
                RiverCaveFluidKind.DEEP_POOL
        ));
        MantleRiverCaveVoxelView riverView = new MantleRiverCaveVoxelView(
                mantle,
                128,
                (x, z) -> 60,
                (x, z) -> null,
                RiverCaveFluidKind.RIVER,
                (chunkX, chunkZ) -> {
                }
        );
        MantleRiverCaveVoxelView deepPoolView = new MantleRiverCaveVoxelView(
                mantle,
                128,
                (x, z) -> 60,
                (x, z) -> null,
                RiverCaveFluidKind.DEEP_POOL,
                (chunkX, chunkZ) -> {
                }
        );

        assertEquals(CaveVoxel.INCOMPATIBLE_FLUID, riverView.voxelAt(position));
        assertEquals(CaveVoxel.SOLID, deepPoolView.voxelAt(position));
    }
}

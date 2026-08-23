package art.arcane.iris.engine.river.cave;

import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterSlice;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RiverCaveHydrologyStorageTest {
    @Test
    @SuppressWarnings("unchecked")
    public void presentReadDoesNotMaterializeASectionOrSlice() {
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        Matter matter = mock(Matter.class);
        MatterSlice<RiverCaveHydrology> slice = mock(MatterSlice.class);
        RiverCaveHydrology expected = RiverCaveHydrology.of(RiverCaveAction.WET_SOURCE);
        when(chunk.exists(2)).thenReturn(true);
        when(chunk.get(2)).thenReturn(matter);
        when(matter.hasSlice(RiverCaveHydrology.class)).thenReturn(true);
        when(matter.getSlice(RiverCaveHydrology.class)).thenReturn(slice);
        when(slice.get(3, 1, 5)).thenReturn(expected);

        assertSame(expected, RiverCaveHydrologyStorage.getIfPresent(chunk, 3, 33, 5));
        verify(chunk, never()).getOrCreate(2);
        verify(matter, never()).slice(RiverCaveHydrology.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void absentSliceReturnsWithoutCreatingIt() {
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        Matter matter = mock(Matter.class);
        when(chunk.exists(2)).thenReturn(true);
        when(chunk.get(2)).thenReturn(matter);

        assertNull(RiverCaveHydrologyStorage.getIfPresent(chunk, 3, 33, 5));
        verify(matter, never()).slice(RiverCaveHydrology.class);
        verify(matter, never()).getSlice(RiverCaveHydrology.class);
    }
}

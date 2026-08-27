package art.arcane.iris.engine.mantle;

import art.arcane.iris.core.link.Identifier;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.volmlib.util.matter.MatterSlice;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MantleWriterOverlayTest {
    private static final int X = 1;
    private static final int Y = 2;
    private static final int Z = 3;

    private MantleWriter writer;
    private Matter matter;
    private MatterSlice<PlatformBlockState> blockSlice;
    private MatterSlice<Identifier> identifierSlice;
    private MatterSlice<MatterCavern> cavernSlice;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        IrisPlatforms.unbind();
        PlatformBlockState defaultBlock = mock(PlatformBlockState.class);
        PlatformRegistries registries = mock(PlatformRegistries.class);
        IrisPlatform platform = mock(IrisPlatform.class);
        when(registries.block(anyString())).thenReturn(defaultBlock);
        when(platform.registries()).thenReturn(registries);
        IrisPlatforms.bind(platform);

        EngineMantle engineMantle = mock(EngineMantle.class);
        Mantle<Matter> mantle = mock(Mantle.class);
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        matter = mock(Matter.class);
        blockSlice = mock(MatterSlice.class);
        identifierSlice = mock(MatterSlice.class);
        cavernSlice = mock(MatterSlice.class);

        when(mantle.getWorldHeight()).thenReturn(64);
        when(mantle.getChunk(0, 0)).thenReturn(chunk);
        when(chunk.use()).thenReturn(chunk);
        when(chunk.getOrCreate(0)).thenReturn(matter);
        when(matter.hasSlice(PlatformBlockState.class)).thenReturn(true);
        when(matter.hasSlice(Identifier.class)).thenReturn(true);
        when(matter.getSlice(PlatformBlockState.class)).thenReturn(blockSlice);
        when(matter.getSlice(Identifier.class)).thenReturn(identifierSlice);
        doReturn(blockSlice).when(matter).slice(PlatformBlockState.class);
        doReturn(cavernSlice).when(matter).slice(MatterCavern.class);

        writer = new MantleWriter(engineMantle, mantle, 0, 0, 0, false);
    }

    @After
    public void tearDown() {
        IrisPlatforms.unbind();
    }

    @Test
    public void existingCavernStillClearsBlockAndDeferredPlacement() {
        MatterCavern existing = new MatterCavern(true, "", (byte) 3);
        MatterCavern requested = new MatterCavern(true, "", (byte) 3);
        when(cavernSlice.get(X, Y, Z)).thenReturn(existing);

        assertFalse(writer.carveDataIfAbsent(X, Y, Z, requested));

        verify(blockSlice).set(X, Y, Z, null);
        verify(identifierSlice).set(X, Y, Z, null);
        verify(cavernSlice, never()).set(X, Y, Z, requested);
    }

    @Test
    public void absentCavernClearsOverlaysAndWritesMask() {
        MatterCavern requested = new MatterCavern(true, "", (byte) 3);

        assertTrue(writer.carveDataIfAbsent(X, Y, Z, requested));

        verify(blockSlice).set(X, Y, Z, null);
        verify(identifierSlice).set(X, Y, Z, null);
        verify(cavernSlice).set(X, Y, Z, requested);
    }

    @Test
    public void normalBlockReplacementClearsDeferredPlacement() {
        PlatformBlockState replacement = mock(PlatformBlockState.class);

        writer.setData(X, Y, Z, replacement);

        verify(identifierSlice).set(X, Y, Z, null);
        verify(blockSlice).set(X, Y, Z, replacement);
    }
}

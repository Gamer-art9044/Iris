package art.arcane.iris.engine.framework.render;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.runtime.IrisRiverRuntime;
import art.arcane.iris.util.project.stream.ProceduralStream;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IrisRendererRiverTest {
    @Test
    public void everyRiverSectionHasItsDiagnosticColor() {
        Map<RiverSection, Integer> expected = new EnumMap<>(RiverSection.class);
        expected.put(RiverSection.CHANNEL, new Color(48, 112, 190).getRGB());
        expected.put(RiverSection.MOUTH, new Color(54, 164, 205).getRGB());
        expected.put(RiverSection.BANK, new Color(92, 146, 78).getRGB());
        expected.put(RiverSection.DRY_CHANNEL, new Color(171, 128, 68).getRGB());
        expected.put(RiverSection.DRY_BANK, new Color(132, 105, 62).getRGB());
        expected.put(RiverSection.NONE, new Color(28, 31, 38).getRGB());

        assertEquals(RiverSection.values().length, expected.size());
        for (RiverSection section : RiverSection.values()) {
            assertEquals(section.name(), expected.get(section).intValue(), IrisRenderer.riverColor(section));
        }
    }

    @Test
    public void riverRenderTypeSamplesOneWorldFootprintPerRenderedPixel() {
        Engine engine = mock(Engine.class);
        IrisComplex complex = mock(IrisComplex.class);
        IrisRiverRuntime runtime = mock(IrisRiverRuntime.class);
        RiverSample river = mock(RiverSample.class);

        when(engine.getComplex()).thenReturn(complex);
        when(complex.getRiverRuntime()).thenReturn(runtime);
        when(runtime.sampleFootprint(12D, -7D, 20D, 1D)).thenReturn(river);
        when(river.present()).thenReturn(true);
        when(river.section()).thenReturn(RiverSection.CHANNEL);

        BufferedImage image = new IrisRenderer(engine).renderStudio(
                12D,
                -7D,
                8D,
                1,
                RenderType.RIVER,
                () -> false
        );

        assertEquals(new Color(48, 112, 190).getRGB(), image.getRGB(0, 0));
        verify(runtime).sampleFootprint(12D, -7D, 20D, 1D);
    }

    @Test
    public void biomeAtlasCompositesRiverChannelsOverTheFastBaseBiome() {
        Engine engine = mock(Engine.class);
        IrisComplex complex = mock(IrisComplex.class);
        IrisRiverRuntime runtime = mock(IrisRiverRuntime.class);
        RiverSample river = mock(RiverSample.class);
        IrisBiome biome = mock(IrisBiome.class);
        @SuppressWarnings("unchecked")
        ProceduralStream<IrisBiome> base = mock(ProceduralStream.class);
        when(engine.getComplex()).thenReturn(complex);
        when(complex.getBaseBiomeStream()).thenReturn(base);
        when(complex.getRiverRuntime()).thenReturn(runtime);
        when(base.get(0D, 0D)).thenReturn(biome);
        when(biome.getColor(engine, RenderType.BIOME)).thenReturn(Color.GREEN);
        when(runtime.sampleFootprint(0D, 0D, 4D, 4D)).thenReturn(river);
        when(river.present()).thenReturn(true);
        when(river.section()).thenReturn(RiverSection.CHANNEL);

        BufferedImage image = new IrisRenderer(engine).renderStudio(
                0D,
                0D,
                4D,
                1,
                RenderType.BIOME,
                () -> false
        );

        assertEquals(new Color(48, 112, 190).getRGB(), image.getRGB(0, 0));
        verify(base).get(0D, 0D);
        verify(runtime).sampleFootprint(0D, 0D, 4D, 4D);
    }
}

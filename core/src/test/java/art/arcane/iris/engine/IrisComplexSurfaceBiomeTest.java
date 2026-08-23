package art.arcane.iris.engine;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.iris.engine.object.IrisRiverWaterMode;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.iris.util.project.stream.interpolation.Interpolated;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class IrisComplexSurfaceBiomeTest {
    @Test
    public void focusBiomeWithoutOverrideIgnoresOverridesOutsideFocus() {
        IrisBiome focusBiome = new IrisBiome();
        IrisBiome unreachableBiome = new IrisBiome().setRiverOverride(new IrisRiverOverride());

        assertFalse(IrisComplex.biomeRiverOverridesPossible(focusBiome, List.of(unreachableBiome)));
    }

    @Test
    public void focusBiomeOverrideAlwaysEnablesBiomeSampling() {
        IrisBiome focusBiome = new IrisBiome().setRiverOverride(new IrisRiverOverride());

        assertTrue(IrisComplex.biomeRiverOverridesPossible(focusBiome, List.of()));
    }

    @Test
    public void nonFocusBiomeSamplingTracksReachableOverrides() {
        assertFalse(IrisComplex.biomeRiverOverridesPossible(null, List.of(new IrisBiome())));
        assertTrue(IrisComplex.biomeRiverOverridesPossible(
                null,
                List.of(new IrisBiome().setRiverOverride(new IrisRiverOverride()))
        ));
    }

    @Test
    public void maxIncisionCapabilityIgnoresIdentityOverrides() {
        assertFalse(IrisComplex.changesMaxIncision(null));
        assertFalse(IrisComplex.changesMaxIncision(new IrisRiverOverride()));
        assertFalse(IrisComplex.changesMaxIncision(
                new IrisRiverOverride().setMaxIncisionMultiplier(1D)));
        assertTrue(IrisComplex.changesMaxIncision(
                new IrisRiverOverride().setMaxIncisionMultiplier(0.75D)));
    }

    @Test
    public void naturalOceanHeightMaskMatchesResolvedLandAndSeaBiomes() {
        double fluidHeight = 64D;
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        IrisBiome sea = new IrisBiome().setInferredType(InferredType.SEA);
        IrisBiome shore = new IrisBiome().setInferredType(InferredType.SHORE);
        IrisRegion region = mock(IrisRegion.class);
        for (double shoreHeight : new double[]{0D, 3D}) {
            doReturn(shoreHeight).when(region).getShoreHeight(0D, 0D);
            double[] heights = new double[]{
                    fluidHeight - 2D,
                    fluidHeight - 1D,
                    fluidHeight,
                    fluidHeight + shoreHeight,
                    fluidHeight + shoreHeight + 0.000001D
            };
            for (IrisBiome base : List.of(land, sea)) {
                for (double height : heights) {
                    IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                            height,
                            base,
                            region,
                            0D,
                            0D,
                            fluidHeight,
                            constant(land),
                            constant(sea),
                            constant(shore)
                    );
                    Boolean ocean = IrisComplex.createNaturalOceanStream(
                            ProceduralStream.ofDouble((x, z) -> height),
                            constantType(InferredType.LAND),
                            null,
                            fluidHeight,
                            IrisRiverWaterMode.TERRACED
                    ).get(0D, 0D);

                    assertEquals(resolved.getInferredType() == InferredType.SEA, ocean.booleanValue());
                }
            }
        }
    }

    @Test
    public void naturalOceanHeightMaskSamplesOnlyNaturalHeight() {
        AtomicInteger heightSamples = new AtomicInteger();
        ProceduralStream<Double> height = ProceduralStream.ofDouble((x, z) -> {
            heightSamples.incrementAndGet();
            return 62D;
        });

        Boolean ocean = IrisComplex.createNaturalOceanStream(
                height,
                constantType(InferredType.LAND),
                null,
                64D,
                IrisRiverWaterMode.TERRACED
        ).get(8D, -3D);

        assertTrue(ocean);
        assertEquals(1, heightSamples.get());
    }

    @Test
    public void focusNaturalOceanMaskIgnoresHeightForEverySurfaceType() {
        AtomicInteger heightSamples = new AtomicInteger();
        ProceduralStream<Double> height = ProceduralStream.ofDouble((x, z) -> {
            heightSamples.incrementAndGet();
            return -1_000D;
        });

        assertTrue(IrisComplex.createNaturalOceanStream(
                height,
                constantType(InferredType.LAND),
                new IrisBiome().setInferredType(InferredType.SEA),
                64D,
                IrisRiverWaterMode.SEA_LEVEL
        ).get(0D, 0D));
        assertFalse(IrisComplex.createNaturalOceanStream(
                height,
                constantType(InferredType.SEA),
                new IrisBiome().setInferredType(InferredType.LAND),
                64D,
                IrisRiverWaterMode.SEA_LEVEL
        ).get(0D, 0D));
        assertFalse(IrisComplex.createNaturalOceanStream(
                height,
                constantType(InferredType.SEA),
                new IrisBiome().setInferredType(InferredType.SHORE),
                64D,
                IrisRiverWaterMode.SEA_LEVEL
        ).get(0D, 0D));
        assertEquals(0, heightSamples.get());
    }

    @Test
    public void seaLevelOceanMaskUsesContinentalIntentWithoutSamplingHeight() {
        AtomicInteger heightSamples = new AtomicInteger();
        ProceduralStream<Double> height = ProceduralStream.ofDouble((x, z) -> {
            heightSamples.incrementAndGet();
            return -1_000D;
        });

        Boolean ocean = IrisComplex.createNaturalOceanStream(
                height,
                constantType(InferredType.SEA),
                null,
                64D,
                IrisRiverWaterMode.SEA_LEVEL
        ).get(8D, -3D);

        assertTrue(ocean);
        assertEquals(0, heightSamples.get());
    }

    @Test
    public void emptyRiverPoolsKeepWetChannelsAquaticAndDryChannelsLand() {
        assertEquals(InferredType.SEA, IrisComplex.directRiverFallback(sample(RiverRouteState.WET, RiverSection.CHANNEL)));
        assertEquals(InferredType.SEA, IrisComplex.directRiverFallback(sample(RiverRouteState.WET, RiverSection.MOUTH)));
        assertEquals(InferredType.LAND, IrisComplex.directRiverFallback(sample(RiverRouteState.DRY, RiverSection.DRY_BANK)));
        assertNull(IrisComplex.directRiverFallback(sample(RiverRouteState.WET, RiverSection.BANK)));
    }

    @Test
    public void elevatedRiverBankUsesTheLocalWaterHead() {
        IrisBiome base = mock(IrisBiome.class);
        IrisBiome sea = mock(IrisBiome.class);
        IrisRegion region = mock(IrisRegion.class);
        doReturn(false).when(base).isShore();
        doReturn(false).when(base).isAquatic();
        doReturn(3D).when(region).getShoreHeight(12D, 18D);

        IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                68D,
                base,
                region,
                12D,
                18D,
                70D,
                constant(base),
                constant(sea),
                constant(base));

        assertSame(sea, resolved);
    }
    @Test
    public void shorelineHeightSelectsShoreBiome() {
        IrisBiome base = mock(IrisBiome.class);
        IrisBiome shore = mock(IrisBiome.class);
        IrisRegion region = mock(IrisRegion.class);
        doReturn(false).when(base).isShore();
        doReturn(3D).when(region).getShoreHeight(12D, 18D);

        IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                63D,
                base,
                region,
                12D,
                18D,
                63D,
                constant(base),
                constant(base),
                constant(shore));

        assertSame(shore, resolved);
    }

    @Test
    public void raisedAquaticBiomeReturnsToLand() {
        IrisBiome aquatic = mock(IrisBiome.class);
        IrisBiome land = mock(IrisBiome.class);
        IrisRegion region = mock(IrisRegion.class);
        doReturn(false).when(aquatic).isShore();
        doReturn(false).when(aquatic).isLand();
        doReturn(1D).when(region).getShoreHeight(4D, 7D);

        IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                66D,
                aquatic,
                region,
                4D,
                7D,
                63D,
                constant(land),
                constant(aquatic),
                constant(aquatic));

        assertSame(land, resolved);
    }

    private static ProceduralStream<IrisBiome> constant(IrisBiome biome) {
        @SuppressWarnings("unchecked")
        ProceduralStream<IrisBiome> stream = mock(ProceduralStream.class);
        doReturn(biome).when(stream).get(anyDouble(), anyDouble());
        return stream;
    }

    private static ProceduralStream<InferredType> constantType(InferredType type) {
        return ProceduralStream.of(
                (x, z) -> type,
                Interpolated.of(value -> 0D, value -> type)
        );
    }

    private static RiverSample sample(RiverRouteState state, RiverSection section) {
        return new RiverSample(true, state, section, 0D, 0.5D, 1D, 1, 1, 10D, 5D, 3D, false, null);
    }
}

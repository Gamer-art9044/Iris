package art.arcane.iris.core.service.terrain;

import art.arcane.iris.api.terrain.IrisSurfaceKind;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.river.RiverEdgeId;
import art.arcane.iris.engine.river.RiverNodeId;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.runtime.IrisRiverSurfaceSample;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisSurfaceClassifierTest {
    private static final int FLUID = 127;

    @Test
    public void columnAtOrBelowWorldMinimumIsVoid() {
        assertEquals(IrisSurfaceKind.VOID, IrisSurfaceClassifier.classify(0, FLUID, InferredType.LAND));
        assertEquals(IrisSurfaceKind.VOID, IrisSurfaceClassifier.classify(-8, FLUID, InferredType.SEA));
    }

    @Test
    public void oceanIsExactlyTheEngineUnderwaterPredicate() {
        assertEquals(IrisSurfaceKind.OCEAN, IrisSurfaceClassifier.classify(FLUID, FLUID, InferredType.LAND));
        assertEquals(IrisSurfaceKind.OCEAN, IrisSurfaceClassifier.classify(FLUID - 1, FLUID, InferredType.LAND));
        assertEquals(IrisSurfaceKind.LAND, IrisSurfaceClassifier.classify(FLUID + 1, FLUID, InferredType.LAND));
    }

    @Test
    public void shoreOnlyAppliesAboveTheFluidLine() {
        assertEquals(IrisSurfaceKind.SHORE, IrisSurfaceClassifier.classify(FLUID + 1, FLUID, InferredType.SHORE));
        assertEquals(IrisSurfaceKind.OCEAN, IrisSurfaceClassifier.classify(FLUID, FLUID, InferredType.SHORE));
    }

    @Test
    public void caveAndAbsentTypesFallBackToLandAboveWater() {
        assertEquals(IrisSurfaceKind.LAND, IrisSurfaceClassifier.classify(FLUID + 10, FLUID, InferredType.CAVE));
        assertEquals(IrisSurfaceKind.LAND, IrisSurfaceClassifier.classify(FLUID + 10, FLUID, InferredType.SEA));
        assertEquals(IrisSurfaceKind.LAND, IrisSurfaceClassifier.classify(FLUID + 10, FLUID, null));
    }

    @Test
    public void biomeIsOnlyRequiredWhenTheAnswerCanDependOnIt() {
        assertFalse(IrisSurfaceClassifier.requiresSurfaceBiome(0, FLUID));
        assertFalse(IrisSurfaceClassifier.requiresSurfaceBiome(FLUID, FLUID));
        assertTrue(IrisSurfaceClassifier.requiresSurfaceBiome(FLUID + 1, FLUID));
    }

    @Test
    public void whenBiomeIsNotRequiredEveryInferredTypeYieldsTheSameKind() {
        for (int surface = -4; surface <= FLUID; surface++) {
            if (IrisSurfaceClassifier.requiresSurfaceBiome(surface, FLUID)) {
                continue;
            }

            IrisSurfaceKind expected = IrisSurfaceClassifier.classify(surface, FLUID, null);
            for (InferredType inferredType : InferredType.values()) {
                assertEquals("surface=" + surface + " type=" + inferredType,
                        expected, IrisSurfaceClassifier.classify(surface, FLUID, inferredType));
            }
        }
    }

    @Test
    public void activeRiverGeometryOverridesGenericOceanAndLandKinds() {
        assertEquals(IrisSurfaceKind.RIVER, IrisSurfaceClassifier.classify(
                60,
                63,
                InferredType.SEA,
                river(RiverRouteState.WET, RiverSection.CHANNEL)
        ));
        assertEquals(IrisSurfaceKind.RIVER, IrisSurfaceClassifier.classify(
                60,
                63,
                InferredType.SEA,
                river(RiverRouteState.WET, RiverSection.MOUTH)
        ));
        assertEquals(IrisSurfaceKind.RIVER_SHORE, IrisSurfaceClassifier.classify(
                64,
                63,
                InferredType.SHORE,
                river(RiverRouteState.WET, RiverSection.BANK)
        ));
        assertEquals(IrisSurfaceKind.DRY_CHANNEL, IrisSurfaceClassifier.classify(
                60,
                60,
                InferredType.LAND,
                river(RiverRouteState.DRY, RiverSection.DRY_CHANNEL)
        ));
        assertEquals(IrisSurfaceKind.LAND, IrisSurfaceClassifier.classify(
                60,
                60,
                InferredType.LAND,
                river(RiverRouteState.DRY, RiverSection.DRY_BANK)
        ));
    }

    @Test
    public void voidClassificationWinsOverRiverGeometry() {
        for (RiverSection section : RiverSection.values()) {
            if (section == RiverSection.NONE) {
                continue;
            }
            RiverRouteState state = section == RiverSection.DRY_CHANNEL || section == RiverSection.DRY_BANK
                    ? RiverRouteState.DRY
                    : RiverRouteState.WET;
            assertEquals(IrisSurfaceKind.VOID, IrisSurfaceClassifier.classify(
                    0,
                    63,
                    InferredType.LAND,
                    river(state, section)
            ));
        }
    }

    @Test
    public void suppressedRoutesDoNotCreatePublicRiverSurfaceKinds() {
        assertEquals(IrisSurfaceKind.LAND, IrisSurfaceClassifier.classify(
                64,
                63,
                InferredType.LAND,
                river(RiverRouteState.SUPPRESSED, RiverSection.CHANNEL)
        ));
    }

    private static IrisRiverSurfaceSample river(RiverRouteState state, RiverSection section) {
        RiverSample sample = new RiverSample(
                true,
                state,
                section,
                0D,
                0.5D,
                1D,
                1,
                1,
                8D,
                4D,
                3D,
                false,
                RiverEdgeId.of(new RiverNodeId(0, 0), new RiverNodeId(1, 0))
        );
        double waterSurface = state == RiverRouteState.WET ? 63D : 60D;
        return new IrisRiverSurfaceSample(sample, 70D, 60D, waterSurface, false, state == RiverRouteState.WET);
    }
}

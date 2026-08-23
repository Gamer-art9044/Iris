package art.arcane.iris.engine.actuator;

import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.runtime.IrisRiverSurfaceSample;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisDecorantActuatorRiverTest {
    @Test
    public void dryRiverSurfaceDoesNotRunShorelineDecoration() {
        IrisRiverSurfaceSample dry = new IrisRiverSurfaceSample(
                river(RiverRouteState.DRY, RiverSection.DRY_CHANNEL),
                70D,
                65D,
                65D,
                false,
                false
        );
        IrisRiverSurfaceSample wet = new IrisRiverSurfaceSample(
                river(RiverRouteState.WET, RiverSection.CHANNEL),
                70D,
                65D,
                65D,
                false,
                true
        );

        assertFalse(IrisDecorantActuator.shouldDecorateShoreline(dry, 65));
        assertTrue(IrisDecorantActuator.shouldDecorateShoreline(wet, 65));
        assertTrue(IrisDecorantActuator.shouldDecorateShoreline(IrisRiverSurfaceSample.none(65D, 65D), 65));
    }

    private static RiverSample river(RiverRouteState state, RiverSection section) {
        return new RiverSample(true, state, section, 0D, 0.5D, 1D, 1, 1, 10D, 5D, 3D, false, null);
    }
}

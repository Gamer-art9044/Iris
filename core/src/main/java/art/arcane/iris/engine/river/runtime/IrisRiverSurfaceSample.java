package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.engine.river.RiverSample;

import java.util.Objects;

public record IrisRiverSurfaceSample(
        RiverSample river,
        double naturalHeight,
        double terrainHeight,
        double waterSurfaceY,
        boolean subterranean,
        boolean surfaceFluid
) {
    public IrisRiverSurfaceSample {
        Objects.requireNonNull(river);
        if (!Double.isFinite(naturalHeight) || !Double.isFinite(terrainHeight)
                || !Double.isFinite(waterSurfaceY)) {
            throw new IllegalArgumentException("River surface values must be finite");
        }
    }

    public static IrisRiverSurfaceSample none(double naturalHeight, double naturalWaterSurfaceY) {
        return new IrisRiverSurfaceSample(
                RiverSample.none(),
                naturalHeight,
                naturalHeight,
                naturalWaterSurfaceY,
                false,
                Math.round(naturalHeight) < Math.round(naturalWaterSurfaceY)
        );
    }
}

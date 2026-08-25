package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.util.project.stream.ProceduralStream;

import java.util.Objects;

public record IrisRiverRuntimeContext(
        long seed,
        IrisRiverNetwork configuration,
        IrisData data,
        int riverFluidHeight,
        int dimensionFluidHeight,
        boolean boreMantleActive,
        boolean caveHydrologyActive,
        boolean blockingRoutingPossible,
        boolean variableMaxIncisionPossible,
        boolean biomeRiverOverridesPossible,
        RiverHeightBoundsSampler naturalHeightBounds,
        ProceduralStream<Double> naturalHeight,
        ProceduralStream<Double> naturalSlope,
        ProceduralStream<Boolean> naturalOcean,
        ProceduralStream<IrisBiome> naturalBiome,
        ProceduralStream<IrisRegion> region
) {
    public IrisRiverRuntimeContext {
        Objects.requireNonNull(configuration);
        Objects.requireNonNull(data);
        Objects.requireNonNull(naturalHeightBounds);
        Objects.requireNonNull(naturalHeight);
        Objects.requireNonNull(naturalSlope);
        Objects.requireNonNull(naturalOcean);
        Objects.requireNonNull(naturalBiome);
        Objects.requireNonNull(region);
    }
}

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Selects the contained cave-water behavior available to river entry events.")
public enum IrisRiverCaveMode {
    @Desc("Keep the surface reservoir sealed from caves.")
    SEALED,

    @Desc("Flood only an existing cave component whose complete fluid-reachable boundary is proven closed.")
    FLOOD_CLOSED_COMPONENT,

    @Desc("Generate a bounded grotto with a guaranteed solid shell.")
    GENERATE_GROTTO,

    @Desc("Use a proven closed cave component when available, otherwise generate a bounded grotto.")
    GROTTO_OR_CLOSED_COMPONENT,

    @Desc("Generate a controlled falling column into a proven contained pool.")
    WATERFALL_POOL
}

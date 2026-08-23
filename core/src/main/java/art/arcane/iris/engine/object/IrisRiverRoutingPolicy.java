package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Controls how river routing treats a region or biome.")
public enum IrisRiverRoutingPolicy {
    @Desc("Allow normal river routing through this area.")
    ALLOW,

    @Desc("Increase the routing cost while still permitting established river trunks.")
    AVOID,

    @Desc("Forbid river reaches from crossing this area.")
    BLOCK
}

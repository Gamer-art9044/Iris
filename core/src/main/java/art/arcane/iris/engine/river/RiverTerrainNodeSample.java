package art.arcane.iris.engine.river;

public record RiverTerrainNodeSample(
        double naturalHeight,
        boolean ocean,
        boolean riverAllowed,
        double routingCost
) {
}

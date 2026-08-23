package art.arcane.iris.engine.river;

public record RiverSample(
        boolean present,
        RiverRouteState state,
        RiverSection section,
        double distance,
        double alongReach,
        double carveWeight,
        int flow,
        int order,
        double width,
        double bankWidth,
        double depth,
        boolean terminal,
        RiverEdgeId reachId
) {
    private static final RiverSample NONE = new RiverSample(
            false,
            RiverRouteState.SUPPRESSED,
            RiverSection.NONE,
            Double.POSITIVE_INFINITY,
            0.0,
            0.0,
            0,
            0,
            0.0,
            0.0,
            0.0,
            false,
            null
    );

    public static RiverSample none() {
        return NONE;
    }
}

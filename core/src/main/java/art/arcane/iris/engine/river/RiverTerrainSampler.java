package art.arcane.iris.engine.river;

public interface RiverTerrainSampler {
    double naturalHeight(int blockX, int blockZ);

    boolean isOcean(int blockX, int blockZ);

    default RiverTerrainNodeSample sampleNode(int blockX, int blockZ) {
        return new RiverTerrainNodeSample(
                naturalHeight(blockX, blockZ),
                isOcean(blockX, blockZ),
                allowsRiver(blockX, blockZ),
                routingCost(blockX, blockZ)
        );
    }

    default RiverTerrainSourceSample sampleSource(int blockX, int blockZ) {
        return new RiverTerrainSourceSample(
                sourceChanceMultiplier(blockX, blockZ),
                allowsRiver(blockX, blockZ),
                isOcean(blockX, blockZ)
        );
    }

    default double routingCost(int blockX, int blockZ) {
        return 0.0;
    }

    default double sourceChanceMultiplier(int blockX, int blockZ) {
        return 1.0;
    }

    default double maximumSourceChanceMultiplier() {
        return Double.POSITIVE_INFINITY;
    }

    default double reachChanceMultiplier(int blockX, int blockZ) {
        return 1.0;
    }

    default boolean allowsRiver(int blockX, int blockZ) {
        return true;
    }

    default boolean allowsReach(RiverRoutingContext context) {
        return true;
    }

    default double reachRoutingCost(RiverRoutingContext context) {
        return 0.0;
    }

    default double meanderNoise(RiverMeanderContext context) {
        return Double.NaN;
    }

    default double flowNoise(double x, double z) {
        return Double.NaN;
    }

    default double channelWidth(RiverRoutingContext context, double fallback) {
        return fallback;
    }

    default double channelWidth(
            RiverRoutingContext context,
            double x,
            double z,
            double fallback
    ) {
        return channelWidth(context, fallback);
    }

    default double bankWidth(RiverRoutingContext context, double fallback) {
        return fallback;
    }

    default double depth(RiverRoutingContext context, double fallback) {
        return fallback;
    }

    default RiverTerminalPolicy terminalPolicy(int blockX, int blockZ) {
        return RiverTerminalPolicy.INHERIT;
    }
}

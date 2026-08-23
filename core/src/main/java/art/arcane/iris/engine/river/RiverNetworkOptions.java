package art.arcane.iris.engine.river;

public record RiverNetworkOptions(
        long seed,
        int cellSize,
        int tileCells,
        double siteJitter,
        int maxRouteReaches,
        int minimumSourcesPerTile,
        int downstreamCandidateLimit,
        int routingBasinCells,
        double routingPlateauHeight,
        double hydraulicBaseHeight,
        boolean requireOcean,
        double sourceChance,
        double reachChance,
        double dryChannelChance,
        double terrainHeightWeight,
        double routingNoiseWeight,
        double flowAlignmentWeight,
        double oceanAttraction,
        double channelWidth,
        double bankWidth,
        double depth,
        double maxChannelWidth,
        double maxBankWidth,
        double maxDepth,
        double orderWidthFactor,
        double orderDepthFactor,
        double maximumReachRadius,
        double meanderStrength,
        int meanderSubdivisions
) {
    public RiverNetworkOptions {
        requireRange(cellSize, 8, 4096, "cellSize");
        requireRange(tileCells, 1, 64, "tileCells");
        requireRange(maxRouteReaches, 1, 256, "maxRouteReaches");
        requireRange(minimumSourcesPerTile, 0, tileCells * tileCells, "minimumSourcesPerTile");
        requireRange(downstreamCandidateLimit, 1, 8, "downstreamCandidateLimit");
        requireRange(routingBasinCells, 8, 256, "routingBasinCells");
        requirePositive(routingPlateauHeight, "routingPlateauHeight");
        requireFinite(hydraulicBaseHeight, "hydraulicBaseHeight");
        requireRange(meanderSubdivisions, 1, 64, "meanderSubdivisions");
        requireProbability(siteJitter, "siteJitter");
        requireProbability(sourceChance, "sourceChance");
        requireProbability(reachChance, "reachChance");
        requireProbability(dryChannelChance, "dryChannelChance");
        requireFiniteNonNegative(terrainHeightWeight, "terrainHeightWeight");
        requireFiniteNonNegative(routingNoiseWeight, "routingNoiseWeight");
        requireFiniteNonNegative(flowAlignmentWeight, "flowAlignmentWeight");
        requireFiniteNonNegative(oceanAttraction, "oceanAttraction");
        requirePositive(channelWidth, "channelWidth");
        requireFiniteNonNegative(bankWidth, "bankWidth");
        requirePositive(depth, "depth");
        requirePositive(maxChannelWidth, "maxChannelWidth");
        requireFiniteNonNegative(maxBankWidth, "maxBankWidth");
        requirePositive(maxDepth, "maxDepth");
        requireFiniteNonNegative(orderWidthFactor, "orderWidthFactor");
        requireFiniteNonNegative(orderDepthFactor, "orderDepthFactor");
        requireFiniteNonNegative(maximumReachRadius, "maximumReachRadius");
        requireFiniteNonNegative(meanderStrength, "meanderStrength");
        RiverTopologyComplexity.requireSafe(
                cellSize,
                tileCells,
                siteJitter,
                maxRouteReaches,
                maximumReachRadius,
                meanderStrength,
                meanderSubdivisions
        );
    }

    public static Builder builder(long seed) {
        return new Builder(seed);
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and between 0 and 1");
        }
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    public static final class Builder {
        private final long seed;
        private int cellSize;
        private int tileCells;
        private double siteJitter;
        private int maxRouteReaches;
        private int minimumSourcesPerTile;
        private int downstreamCandidateLimit;
        private int routingBasinCells;
        private double routingPlateauHeight;
        private double hydraulicBaseHeight;
        private boolean requireOcean;
        private double sourceChance;
        private double reachChance;
        private double dryChannelChance;
        private double terrainHeightWeight;
        private double routingNoiseWeight;
        private double flowAlignmentWeight;
        private double oceanAttraction;
        private double channelWidth;
        private double bankWidth;
        private double depth;
        private double maxChannelWidth;
        private double maxBankWidth;
        private double maxDepth;
        private double orderWidthFactor;
        private double orderDepthFactor;
        private double maximumReachRadius;
        private double meanderStrength;
        private int meanderSubdivisions;

        private Builder(long seed) {
            this.seed = seed;
            cellSize = 512;
            tileCells = 4;
            siteJitter = 0.35;
            maxRouteReaches = 16;
            minimumSourcesPerTile = 0;
            downstreamCandidateLimit = 4;
            routingBasinCells = 64;
            routingPlateauHeight = 8.0;
            hydraulicBaseHeight = 64D;
            requireOcean = false;
            sourceChance = 0.12;
            reachChance = 0.98;
            dryChannelChance = 0.35;
            terrainHeightWeight = 1.0;
            routingNoiseWeight = 24.0;
            flowAlignmentWeight = 0D;
            oceanAttraction = 64.0;
            channelWidth = 10.0;
            bankWidth = 8.0;
            depth = 4.0;
            maxChannelWidth = 10D;
            maxBankWidth = 8D;
            maxDepth = 10D;
            orderWidthFactor = 0.35;
            orderDepthFactor = 0.2;
            maximumReachRadius = Double.NaN;
            meanderStrength = 40.0;
            meanderSubdivisions = 8;
        }

        public Builder cellSize(int value) {
            cellSize = value;
            return this;
        }

        public Builder tileCells(int value) {
            tileCells = value;
            return this;
        }

        public Builder siteJitter(double value) {
            siteJitter = value;
            return this;
        }

        public Builder maxRouteReaches(int value) {
            maxRouteReaches = value;
            return this;
        }

        public Builder minimumSourcesPerTile(int value) {
            minimumSourcesPerTile = value;
            return this;
        }

        public Builder downstreamCandidateLimit(int value) {
            downstreamCandidateLimit = value;
            return this;
        }

        public Builder routingBasinCells(int value) {
            routingBasinCells = value;
            return this;
        }

        public Builder routingPlateauHeight(double value) {
            routingPlateauHeight = value;
            return this;
        }

        public Builder hydraulicBaseHeight(double value) {
            hydraulicBaseHeight = value;
            return this;
        }

        public Builder requireOcean(boolean value) {
            requireOcean = value;
            return this;
        }

        public Builder sourceChance(double value) {
            sourceChance = value;
            return this;
        }

        public Builder reachChance(double value) {
            reachChance = value;
            return this;
        }

        public Builder dryChannelChance(double value) {
            dryChannelChance = value;
            return this;
        }

        public Builder terrainHeightWeight(double value) {
            terrainHeightWeight = value;
            return this;
        }

        public Builder routingNoiseWeight(double value) {
            routingNoiseWeight = value;
            return this;
        }

        public Builder flowAlignmentWeight(double value) {
            flowAlignmentWeight = value;
            return this;
        }

        public Builder oceanAttraction(double value) {
            oceanAttraction = value;
            return this;
        }

        public Builder channelWidth(double value) {
            channelWidth = value;
            return this;
        }

        public Builder bankWidth(double value) {
            bankWidth = value;
            return this;
        }

        public Builder depth(double value) {
            depth = value;
            return this;
        }

        public Builder maxChannelWidth(double value) {
            maxChannelWidth = value;
            return this;
        }

        public Builder maxBankWidth(double value) {
            maxBankWidth = value;
            return this;
        }

        public Builder maxDepth(double value) {
            maxDepth = value;
            return this;
        }

        public Builder orderWidthFactor(double value) {
            orderWidthFactor = value;
            return this;
        }

        public Builder orderDepthFactor(double value) {
            orderDepthFactor = value;
            return this;
        }

        public Builder maximumReachRadius(double value) {
            maximumReachRadius = value;
            return this;
        }

        public Builder meanderStrength(double value) {
            meanderStrength = value;
            return this;
        }

        public Builder meanderSubdivisions(int value) {
            meanderSubdivisions = value;
            return this;
        }

        public RiverNetworkOptions build() {
            double resolvedMaximumReachRadius = Double.isFinite(maximumReachRadius)
                    ? maximumReachRadius
                    : defaultMaximumReachRadius();
            return new RiverNetworkOptions(
                    seed,
                    cellSize,
                    tileCells,
                    siteJitter,
                    maxRouteReaches,
                    minimumSourcesPerTile,
                    downstreamCandidateLimit,
                    routingBasinCells,
                    routingPlateauHeight,
                    hydraulicBaseHeight,
                    requireOcean,
                    sourceChance,
                    reachChance,
                    dryChannelChance,
                    terrainHeightWeight,
                    routingNoiseWeight,
                    flowAlignmentWeight,
                    oceanAttraction,
                    channelWidth,
                    bankWidth,
                    depth,
                    maxChannelWidth,
                    maxBankWidth,
                    maxDepth,
                    orderWidthFactor,
                    orderDepthFactor,
                    resolvedMaximumReachRadius,
                    meanderStrength,
                    meanderSubdivisions
            );
        }

        private double defaultMaximumReachRadius() {
            return maxChannelWidth * 0.5D + maxBankWidth;
        }
    }
}

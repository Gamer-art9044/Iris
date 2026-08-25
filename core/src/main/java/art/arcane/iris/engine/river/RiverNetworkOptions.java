package art.arcane.iris.engine.river;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record RiverNetworkOptions(
        long seed,
        int cellSize,
        int tileCells,
        double siteJitter,
        int maxRouteReaches,
        int minimumSourcesPerTile,
        int downstreamCandidateLimit,
        int routingBasinCells,
        int routingDeviationScaleCells,
        double routingDeviationStrengthCells,
        double routingPlateauHeight,
        double hydraulicBaseHeight,
        boolean requireOcean,
        double sourceChance,
        double reachChance,
        double dryChannelChance,
        double terrainHeightWeight,
        double routingNoiseWeight,
        double flowAlignmentWeight,
        double confluenceWeight,
        double oceanAttraction,
        double channelWidth,
        double bankWidth,
        double depth,
        double channelRadiusBonus,
        double maxChannelWidth,
        double maxBankWidth,
        double maxDepth,
        double orderWidthFactor,
        double orderDepthFactor,
        double maximumReachRadius,
        List<RiverWorm> worms
) {
    public RiverNetworkOptions {
        requireRange(cellSize, 8, 4096, "cellSize");
        requireRange(tileCells, 1, 64, "tileCells");
        requireRange(maxRouteReaches, 1, 256, "maxRouteReaches");
        requireRange(minimumSourcesPerTile, 0, tileCells * tileCells, "minimumSourcesPerTile");
        requireRange(downstreamCandidateLimit, 1, 8, "downstreamCandidateLimit");
        requireRange(routingBasinCells, 8, 256, "routingBasinCells");
        requireRange(routingDeviationScaleCells, 8, 256, "routingDeviationScaleCells");
        requireRange(routingDeviationStrengthCells, 0D, 32D, "routingDeviationStrengthCells");
        requirePositive(routingPlateauHeight, "routingPlateauHeight");
        requireFinite(hydraulicBaseHeight, "hydraulicBaseHeight");
        requireProbability(siteJitter, "siteJitter");
        requireProbability(sourceChance, "sourceChance");
        requireProbability(reachChance, "reachChance");
        requireProbability(dryChannelChance, "dryChannelChance");
        requireFiniteNonNegative(terrainHeightWeight, "terrainHeightWeight");
        requireFiniteNonNegative(routingNoiseWeight, "routingNoiseWeight");
        requireFiniteNonNegative(flowAlignmentWeight, "flowAlignmentWeight");
        requireFiniteNonNegative(confluenceWeight, "confluenceWeight");
        requireFiniteNonNegative(oceanAttraction, "oceanAttraction");
        requirePositive(channelWidth, "channelWidth");
        requireFiniteNonNegative(bankWidth, "bankWidth");
        requirePositive(depth, "depth");
        requireFiniteNonNegative(channelRadiusBonus, "channelRadiusBonus");
        requirePositive(maxChannelWidth, "maxChannelWidth");
        requireFiniteNonNegative(maxBankWidth, "maxBankWidth");
        requirePositive(maxDepth, "maxDepth");
        requireFiniteNonNegative(orderWidthFactor, "orderWidthFactor");
        requireFiniteNonNegative(orderDepthFactor, "orderDepthFactor");
        requireFiniteNonNegative(maximumReachRadius, "maximumReachRadius");
        if (worms == null || worms.isEmpty()) {
            throw new IllegalArgumentException("worms must contain at least one profile");
        }
        if (worms.size() > 16) {
            throw new IllegalArgumentException("worms must contain at most 16 root profiles");
        }
        Set<String> ids = new HashSet<String>();
        Set<Long> seeds = new HashSet<Long>();
        int wormCount = validateWormTree(worms, 1, ids, seeds);
        if (wormCount > 128) {
            throw new IllegalArgumentException("worm hierarchy must contain at most 128 profiles");
        }
        worms = List.copyOf(worms);
        RiverTopologyComplexity.requireSafe(
                cellSize,
                tileCells,
                siteJitter,
                maxRouteReaches,
                maximumReachRadius,
                maximumWormOffset(worms),
                maximumWormSegments(worms)
        );
    }

    public static Builder builder(long seed) {
        return new Builder(seed);
    }

    public double maximumWormOffset() {
        return maximumWormOffset(worms);
    }

    public int maximumWormSegments() {
        return maximumWormSegments(worms);
    }

    private static double maximumWormOffset(List<RiverWorm> worms) {
        double maximum = 0D;
        for (RiverWorm worm : worms) {
            if (worm == null) {
                throw new IllegalArgumentException("worms must not contain null profiles");
            }
            maximum = StrictMath.max(maximum, worm.maxOffset());
            maximum = StrictMath.max(maximum, maximumWormOffset(worm.children()));
        }
        return maximum;
    }

    private static int maximumWormSegments(List<RiverWorm> worms) {
        int maximum = 1;
        for (RiverWorm worm : worms) {
            if (worm == null) {
                throw new IllegalArgumentException("worms must not contain null profiles");
            }
            maximum = StrictMath.max(maximum, worm.segments());
            maximum = StrictMath.max(maximum, maximumWormSegments(worm.children()));
        }
        return maximum;
    }

    private static int validateWormTree(
            List<RiverWorm> worms,
            int depth,
            Set<String> ids,
            Set<Long> seeds
    ) {
        if (depth > 4) {
            throw new IllegalArgumentException("worm hierarchy must be at most 4 profiles deep");
        }
        int count = 0;
        for (RiverWorm worm : worms) {
            if (worm == null) {
                throw new IllegalArgumentException("worm hierarchy must not contain null profiles");
            }
            if (!ids.add(worm.id())) {
                throw new IllegalArgumentException("worm ids must be unique: " + worm.id());
            }
            if (!seeds.add(worm.seed())) {
                throw new IllegalArgumentException("worm seeds must be unique: " + worm.seed());
            }
            count++;
            if (!worm.children().isEmpty()) {
                count += validateWormTree(worm.children(), depth + 1, ids, seeds);
            }
        }
        return count;
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireRange(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
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
        private int routingDeviationScaleCells;
        private double routingDeviationStrengthCells;
        private double routingPlateauHeight;
        private double hydraulicBaseHeight;
        private boolean requireOcean;
        private double sourceChance;
        private double reachChance;
        private double dryChannelChance;
        private double terrainHeightWeight;
        private double routingNoiseWeight;
        private double flowAlignmentWeight;
        private double confluenceWeight;
        private double oceanAttraction;
        private double channelWidth;
        private double bankWidth;
        private double depth;
        private double channelRadiusBonus;
        private double maxChannelWidth;
        private double maxBankWidth;
        private double maxDepth;
        private double orderWidthFactor;
        private double orderDepthFactor;
        private double maximumReachRadius;
        private List<RiverWorm> worms;

        private Builder(long seed) {
            this.seed = seed;
            cellSize = 512;
            tileCells = 4;
            siteJitter = 0.35;
            maxRouteReaches = 16;
            minimumSourcesPerTile = 0;
            downstreamCandidateLimit = 4;
            routingBasinCells = 64;
            routingDeviationScaleCells = 24;
            routingDeviationStrengthCells = 0D;
            routingPlateauHeight = 8.0;
            hydraulicBaseHeight = 64D;
            requireOcean = false;
            sourceChance = 0.12;
            reachChance = 0.98;
            dryChannelChance = 0.35;
            terrainHeightWeight = 1.0;
            routingNoiseWeight = 24.0;
            flowAlignmentWeight = 0D;
            confluenceWeight = 0D;
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
            worms = List.of(new RiverWorm(
                    "default",
                    1L,
                    1D,
                    1024D,
                    256D,
                    0.5D,
                    0.15D,
                    40D,
                    8,
                    1D,
                    1D,
                    1D,
                    512D,
                    128D,
                    0.3D,
                    0D,
                    0D,
                    0D,
                    0D,
                    4,
                    0.35D,
                    1D,
                    0D,
                    0D,
                    List.of()
            ));
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

        public Builder routingDeviationScaleCells(int value) {
            routingDeviationScaleCells = value;
            return this;
        }

        public Builder routingDeviationStrengthCells(double value) {
            routingDeviationStrengthCells = value;
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

        public Builder confluenceWeight(double value) {
            confluenceWeight = value;
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

        public Builder channelRadiusBonus(double value) {
            channelRadiusBonus = value;
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

        public Builder worms(List<RiverWorm> value) {
            worms = value;
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
                    routingDeviationScaleCells,
                    routingDeviationStrengthCells,
                    routingPlateauHeight,
                    hydraulicBaseHeight,
                    requireOcean,
                    sourceChance,
                    reachChance,
                    dryChannelChance,
                    terrainHeightWeight,
                    routingNoiseWeight,
                    flowAlignmentWeight,
                    confluenceWeight,
                    oceanAttraction,
                    channelWidth,
                    bankWidth,
                    depth,
                    channelRadiusBonus,
                    maxChannelWidth,
                    maxBankWidth,
                    maxDepth,
                    orderWidthFactor,
                    orderDepthFactor,
                    resolvedMaximumReachRadius,
                    worms
            );
        }

        private double defaultMaximumReachRadius() {
            return maxChannelWidth * 0.5D + maxBankWidth;
        }
    }
}

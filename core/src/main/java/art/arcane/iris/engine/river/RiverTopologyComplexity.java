package art.arcane.iris.engine.river;

import java.util.ArrayList;
import java.util.List;

public final class RiverTopologyComplexity {
    public static final long MAXIMUM_SOURCE_WINDOW_CELLS = 65_536L;
    public static final long MAXIMUM_ROUTE_SCAN_STEPS = 65_536L;
    public static final long MAXIMUM_BUCKET_WRITES_PER_REACH = 1_048_576L;
    public static final long MAXIMUM_TUNNEL_SAMPLE_COLUMNS = 65_536L;
    private static final int SPATIAL_BUCKET_SIZE = 64;

    private RiverTopologyComplexity() {
    }

    public static Estimate estimate(
            int cellSize,
            int tileCells,
            double siteJitter,
            int maxRouteReaches,
            double maximumReachRadius,
            double maximumWormOffset,
            int maximumWormSegments
    ) {
        double maximumEdgeAxisDelta = cellSize * (1D + siteJitter);
        long geometryPaddingCells = 1L + ceilToLong(
                (maximumReachRadius + maximumWormOffset) / cellSize
        );
        long targetWindowAxis = saturatedAdd(tileCells, saturatedMultiply(2L, geometryPaddingCells));
        long sourceWindowAxis = saturatedAdd(
                targetWindowAxis,
                saturatedMultiply(2L, maxRouteReaches)
        );
        long sourceWindowCells = saturatedMultiply(sourceWindowAxis, sourceWindowAxis);
        long maximumRouteScanSteps = saturatedMultiply(sourceWindowCells, maxRouteReaches);
        double maximumSegmentSpan = maximumEdgeAxisDelta
                + maximumWormOffset * 2D
                + maximumReachRadius * 2D;
        long maximumSegmentBucketAxis = saturatedAdd(
                ceilToLong(maximumSegmentSpan / SPATIAL_BUCKET_SIZE),
                1L
        );
        long maximumSegmentBucketCount = saturatedMultiply(
                maximumSegmentBucketAxis,
                maximumSegmentBucketAxis
        );
        long maximumBucketWritesPerReach = saturatedMultiply(
                maximumSegmentBucketCount,
                maximumWormSegments
        );
        return new Estimate(
                geometryPaddingCells,
                sourceWindowAxis,
                sourceWindowCells,
                maximumRouteScanSteps,
                maximumSegmentBucketAxis,
                maximumBucketWritesPerReach
        );
    }

    public static void requireSafe(
            int cellSize,
            int tileCells,
            double siteJitter,
            int maxRouteReaches,
            double maximumReachRadius,
            double maximumWormOffset,
            int maximumWormSegments
    ) {
        Estimate estimate = estimate(
                cellSize,
                tileCells,
                siteJitter,
                maxRouteReaches,
                maximumReachRadius,
                maximumWormOffset,
                maximumWormSegments
        );
        List<String> violations = estimate.violations();
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", violations));
        }
    }

    public static int tunnelHalo(
            double maximumChannelWidth,
            double maximumTunnelWidthMultiplier,
            double tunnelMouthBlend
    ) {
        if (!Double.isFinite(maximumChannelWidth) || maximumChannelWidth <= 0D
                || !Double.isFinite(maximumTunnelWidthMultiplier) || maximumTunnelWidthMultiplier < 1D
                || !Double.isFinite(tunnelMouthBlend) || tunnelMouthBlend < 0D) {
            throw new IllegalArgumentException("River tunnel dimensions must be finite and valid");
        }
        return Math.max(
                1,
                (int) StrictMath.ceil(
                        maximumChannelWidth * 0.5D * maximumTunnelWidthMultiplier + tunnelMouthBlend
                ) + 1
        );
    }

    public static long tunnelSampleColumns(
            double maximumChannelWidth,
            double maximumTunnelWidthMultiplier,
            double tunnelMouthBlend
    ) {
        long axis = 16L + 2L * tunnelHalo(
                maximumChannelWidth,
                maximumTunnelWidthMultiplier,
                tunnelMouthBlend
        );
        return saturatedMultiply(axis, axis);
    }

    public static String tunnelPlanViolation(
            double maximumChannelWidth,
            double maximumTunnelWidthMultiplier,
            double tunnelMouthBlend
    ) {
        long columns = tunnelSampleColumns(
                maximumChannelWidth,
                maximumTunnelWidthMultiplier,
                tunnelMouthBlend
        );
        if (columns <= MAXIMUM_TUNNEL_SAMPLE_COLUMNS) {
            return null;
        }
        return "River tunnel planning may sample " + columns
                + " columns per generated chunk, above the safe limit of " + MAXIMUM_TUNNEL_SAMPLE_COLUMNS
                + "; reduce maxChannelWidth, tunnelWidthMultiplier.max, or tunnelMouthBlend.";
    }

    public static void requireSafeTunnelPlan(
            double maximumChannelWidth,
            double maximumTunnelWidthMultiplier,
            double tunnelMouthBlend
    ) {
        String violation = tunnelPlanViolation(
                maximumChannelWidth,
                maximumTunnelWidthMultiplier,
                tunnelMouthBlend
        );
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
    }

    private static long ceilToLong(double value) {
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (value <= 0D) {
            return 0L;
        }
        return (long) StrictMath.ceil(value);
    }

    private static long saturatedAdd(long first, long second) {
        if (first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private static long saturatedMultiply(long first, long second) {
        if (first == 0L || second == 0L) {
            return 0L;
        }
        if (first > Long.MAX_VALUE / second) {
            return Long.MAX_VALUE;
        }
        return first * second;
    }

    public record Estimate(
            long geometryPaddingCells,
            long sourceWindowAxis,
            long sourceWindowCells,
            long maximumRouteScanSteps,
            long maximumSegmentBucketAxis,
            long maximumBucketWritesPerReach
    ) {
        public boolean safe() {
            return violations().isEmpty();
        }

        public List<String> violations() {
            ArrayList<String> violations = new ArrayList<>(3);
            if (sourceWindowCells > MAXIMUM_SOURCE_WINDOW_CELLS) {
                violations.add("River topology source window requires " + sourceWindowCells
                        + " cells (" + sourceWindowAxis + " per axis), above the safe limit of "
                        + MAXIMUM_SOURCE_WINDOW_CELLS
                        + "; increase cellSize or reduce tileCells, maxRouteReaches, channel width, or bank width.");
            }
            if (maximumRouteScanSteps > MAXIMUM_ROUTE_SCAN_STEPS) {
                violations.add("River topology route scan permits " + maximumRouteScanSteps
                        + " source-to-reach steps, above the safe limit of " + MAXIMUM_ROUTE_SCAN_STEPS
                        + "; reduce maxRouteReaches, tileCells, channel width, or bank width.");
            }
            if (maximumBucketWritesPerReach > MAXIMUM_BUCKET_WRITES_PER_REACH) {
                violations.add("River topology spatial index may require " + maximumBucketWritesPerReach
                        + " bucket writes for one reach (" + maximumSegmentBucketAxis
                        + " buckets per segment axis), above the safe limit of "
                        + MAXIMUM_BUCKET_WRITES_PER_REACH
                        + "; reduce channel width, bank width, orderWidthFactor, worm maxOffset, or worm segments.");
            }
            return List.copyOf(violations);
        }
    }
}

package art.arcane.iris.probe;

import art.arcane.iris.engine.river.RiverBodyProfile;
import art.arcane.iris.engine.river.RiverEdgeId;
import art.arcane.iris.engine.river.RiverNode;
import art.arcane.iris.engine.river.RiverNodeId;
import art.arcane.iris.engine.river.RiverPolyline;
import art.arcane.iris.engine.river.RiverReach;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverTile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class RiverTileProbe {
    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASURED_ROUNDS = 25;
    private static final int SAMPLES_PER_ROUND = 8_192;

    private RiverTileProbe() {
    }

    public static void main(String[] args) {
        RiverTile tile = createTile();
        long expectedSignature = sampleRound(tile);
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            requireSignature(expectedSignature, sampleRound(tile));
        }

        long[] timings = new long[MEASURED_ROUNDS];
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long start = System.nanoTime();
            long signature = sampleRound(tile);
            timings[round] = System.nanoTime() - start;
            requireSignature(expectedSignature, signature);
            System.out.printf(Locale.ROOT,
                    "IRIS_RIVER_TILE_SAMPLE round=%d nanos=%d signature=%016x%n",
                    round,
                    timings[round],
                    signature);
        }
        long[] sorted = timings.clone();
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT,
                "IRIS_RIVER_TILE_RESULT version=1 rounds=%d samples_per_round=%d median_nanos=%d p95_nanos=%d signature=%016x%n",
                MEASURED_ROUNDS,
                SAMPLES_PER_ROUND,
                sorted[MEASURED_ROUNDS / 2],
                sorted[(int) StrictMath.ceil(MEASURED_ROUNDS * 0.95D) - 1],
                expectedSignature);
    }

    private static RiverTile createTile() {
        List<RiverReach> reaches = new ArrayList<>(64);
        for (int reachIndex = 0; reachIndex < 64; reachIndex++) {
            RiverNode from = node(0L, reachIndex, 0D, reachIndex * 32D);
            RiverNode to = node(1L, reachIndex, 2_048D, reachIndex * 32D);
            RiverBodyProfile profile = profile(reachIndex);
            double[] x = new double[33];
            double[] z = new double[33];
            for (int point = 0; point < x.length; point++) {
                x[point] = point * 64D;
                z[point] = reachIndex * 32D
                        + StrictMath.sin(point * 0.625D + reachIndex * 0.25D) * 12D;
            }
            reaches.add(new RiverReach(
                    RiverEdgeId.of(from.id(), to.id()),
                    from,
                    to,
                    RiverRouteState.WET,
                    1 + reachIndex % 4,
                    1 + reachIndex % 3,
                    profile.maximumWidth(),
                    profile.maximumBankWidth(),
                    profile.maximumDepth(),
                    profile,
                    false,
                    false,
                    new RiverPolyline(x, z)
            ));
        }
        return new RiverTile(0, 0, 0, 0, 2_048, 2_048, reaches);
    }

    private static RiverBodyProfile profile(int reachIndex) {
        double[] positions = new double[17];
        double[] widths = new double[17];
        double[] bankWidths = new double[17];
        double[] depths = new double[17];
        double[] roofScales = new double[17];
        for (int index = 0; index < positions.length; index++) {
            double position = index / 16D;
            double body = StrictMath.sin(StrictMath.PI * position);
            positions[index] = position;
            widths[index] = 8D + reachIndex % 5 + body * 6D;
            bankWidths[index] = 4D + reachIndex % 3 + body * 4D;
            depths[index] = 3D + reachIndex % 2 + body * 2D;
            roofScales[index] = 1D - body * 0.4D;
        }
        return new RiverBodyProfile(positions, widths, bankWidths, depths, roofScales);
    }

    private static RiverNode node(long cellX, long cellZ, double x, double z) {
        return new RiverNode(
                new RiverNodeId(cellX, cellZ),
                x,
                z,
                64D,
                64D,
                64D,
                64D,
                false,
                true
        );
    }

    private static long sampleRound(RiverTile tile) {
        long signature = 0xCBF29CE484222325L;
        for (int sampleIndex = 0; sampleIndex < SAMPLES_PER_ROUND; sampleIndex++) {
            double x = Math.floorMod(sampleIndex * 1_229, 2_048) + 0.375D;
            double z = Math.floorMod(sampleIndex * 811, 2_048) + 0.625D;
            double additionalRadius = 16D + sampleIndex % 17;
            RiverSample sample = tile.sampleExpanded(x, z, additionalRadius);
            signature = mix(signature, sample.present() ? 1L : 0L);
            if (!sample.present()) {
                continue;
            }
            signature = mix(signature, sample.reachId().stableId());
            signature = mix(signature, Double.doubleToLongBits(sample.distance()));
            signature = mix(signature, Double.doubleToLongBits(sample.alongReach()));
            signature = mix(signature, Double.doubleToLongBits(sample.carveWeight()));
            signature = mix(signature, Double.doubleToLongBits(sample.width()));
            signature = mix(signature, Double.doubleToLongBits(sample.bankWidth()));
            signature = mix(signature, Double.doubleToLongBits(sample.depth()));
            signature = mix(signature, sample.section().ordinal());
        }
        return signature;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001B3L;
    }

    private static void requireSignature(long expected, long actual) {
        if (actual != expected) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "River tile output changed: expected %016x but got %016x",
                    expected,
                    actual
            ));
        }
    }
}

package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.engine.river.RiverPolyline;

import java.util.Objects;

final class RiverPolylineProbe {
    private RiverPolylineProbe() {
    }

    static boolean all(RiverPolyline polyline, int maximumSamples, CellPredicate predicate) {
        Objects.requireNonNull(polyline);
        Objects.requireNonNull(predicate);
        if (maximumSamples < 2) {
            throw new IllegalArgumentException("River polyline probing requires at least two samples");
        }
        double totalLength = polyline.length();
        int desiredSamples = totalLength >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) StrictMath.ceil(totalLength) + 1;
        int sampleCount = StrictMath.max(2, StrictMath.min(maximumSamples, desiredSamples));
        int segment = 0;
        for (int sample = 0; sample < sampleCount; sample++) {
            double alongReach = (double) sample / (sampleCount - 1);
            double targetDistance = totalLength * alongReach;
            while (segment < polyline.size() - 2
                    && targetDistance > polyline.cumulativeLength(segment + 1)) {
                segment++;
            }
            double segmentStart = polyline.cumulativeLength(segment);
            double segmentEnd = polyline.cumulativeLength(segment + 1);
            double segmentLength = segmentEnd - segmentStart;
            double factor = segmentLength <= 0D ? 0D : (targetDistance - segmentStart) / segmentLength;
            double x = polyline.x(segment)
                    + (polyline.x(segment + 1) - polyline.x(segment)) * factor;
            double z = polyline.z(segment)
                    + (polyline.z(segment + 1) - polyline.z(segment)) * factor;
            if (!predicate.test(clampRound(x), clampRound(z), alongReach)) {
                return false;
            }
        }
        return true;
    }

    private static int clampRound(double value) {
        return (int) StrictMath.max(
                Integer.MIN_VALUE,
                StrictMath.min(Integer.MAX_VALUE, StrictMath.round(value))
        );
    }

    @FunctionalInterface
    interface CellPredicate {
        boolean test(int blockX, int blockZ, double alongReach);
    }
}

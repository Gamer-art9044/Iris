package art.arcane.iris.engine.river;

import java.util.Arrays;

public final class RiverWidthProfile {
    private final double[] positions;
    private final double[] widths;
    private final double maximum;

    public RiverWidthProfile(double[] positions, double[] widths) {
        if (positions == null || widths == null || positions.length < 2 || positions.length != widths.length) {
            throw new IllegalArgumentException("River width profiles require matching position and width samples");
        }
        this.positions = positions.clone();
        this.widths = widths.clone();
        double resolvedMaximum = 0D;
        for (int index = 0; index < this.positions.length; index++) {
            double position = this.positions[index];
            double width = this.widths[index];
            if (!Double.isFinite(position) || position < 0D || position > 1D
                    || (index > 0 && position <= this.positions[index - 1])) {
                throw new IllegalArgumentException("River width profile positions must increase from zero to one");
            }
            if (!Double.isFinite(width) || width <= 0D) {
                throw new IllegalArgumentException("River width profile widths must be finite and positive");
            }
            resolvedMaximum = Math.max(resolvedMaximum, width);
        }
        if (this.positions[0] != 0D || this.positions[this.positions.length - 1] != 1D) {
            throw new IllegalArgumentException("River width profile positions must include zero and one");
        }
        maximum = resolvedMaximum;
    }

    public static RiverWidthProfile constant(double width) {
        return new RiverWidthProfile(new double[]{0D, 1D}, new double[]{width, width});
    }

    public double sample(double alongReach) {
        double position = Math.max(0D, Math.min(1D, alongReach));
        int index = Arrays.binarySearch(positions, position);
        if (index >= 0) {
            return widths[index];
        }
        int upper = -index - 1;
        if (upper <= 0) {
            return widths[0];
        }
        if (upper >= positions.length) {
            return widths[widths.length - 1];
        }
        int lower = upper - 1;
        double range = positions[upper] - positions[lower];
        double interpolation = range <= 0D ? 0D : (position - positions[lower]) / range;
        return widths[lower] + (widths[upper] - widths[lower]) * interpolation;
    }

    public double maximum() {
        return maximum;
    }

    public int size() {
        return widths.length;
    }

    public double position(int index) {
        return positions[index];
    }

    public double width(int index) {
        return widths[index];
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof RiverWidthProfile profile)) {
            return false;
        }
        return Arrays.equals(positions, profile.positions) && Arrays.equals(widths, profile.widths);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(positions) + Arrays.hashCode(widths);
    }
}

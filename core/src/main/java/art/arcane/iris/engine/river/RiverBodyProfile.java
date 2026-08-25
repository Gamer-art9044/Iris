package art.arcane.iris.engine.river;

import java.util.Arrays;

public final class RiverBodyProfile {
    private final double[] positions;
    private final double[] widths;
    private final double[] bankWidths;
    private final double[] depths;
    private final double[] roofScales;
    private final double maximumWidth;
    private final double maximumBankWidth;
    private final double maximumDepth;

    public RiverBodyProfile(
            double[] positions,
            double[] widths,
            double[] bankWidths,
            double[] depths,
            double[] roofScales
    ) {
        if (positions == null || widths == null || bankWidths == null || depths == null || roofScales == null
                || positions.length < 2
                || positions.length != widths.length
                || positions.length != bankWidths.length
                || positions.length != depths.length
                || positions.length != roofScales.length) {
            throw new IllegalArgumentException("River body profiles require matching dimension samples");
        }
        this.positions = positions.clone();
        this.widths = widths.clone();
        this.bankWidths = bankWidths.clone();
        this.depths = depths.clone();
        this.roofScales = roofScales.clone();
        double resolvedMaximumWidth = 0D;
        double resolvedMaximumBankWidth = 0D;
        double resolvedMaximumDepth = 0D;
        for (int index = 0; index < this.positions.length; index++) {
            double position = this.positions[index];
            if (!Double.isFinite(position) || position < 0D || position > 1D
                    || index > 0 && position <= this.positions[index - 1]) {
                throw new IllegalArgumentException("River body profile positions must increase from zero to one");
            }
            requirePositive(this.widths[index], "width");
            requireNonNegative(this.bankWidths[index], "bank width");
            requirePositive(this.depths[index], "depth");
            requireUnitScale(this.roofScales[index], "roof scale");
            resolvedMaximumWidth = StrictMath.max(resolvedMaximumWidth, this.widths[index]);
            resolvedMaximumBankWidth = StrictMath.max(resolvedMaximumBankWidth, this.bankWidths[index]);
            resolvedMaximumDepth = StrictMath.max(resolvedMaximumDepth, this.depths[index]);
        }
        if (this.positions[0] != 0D || this.positions[this.positions.length - 1] != 1D) {
            throw new IllegalArgumentException("River body profile positions must include zero and one");
        }
        maximumWidth = resolvedMaximumWidth;
        maximumBankWidth = resolvedMaximumBankWidth;
        maximumDepth = resolvedMaximumDepth;
    }

    public static RiverBodyProfile constant(double width, double bankWidth, double depth) {
        return new RiverBodyProfile(
                new double[]{0D, 1D},
                new double[]{width, width},
                new double[]{bankWidth, bankWidth},
                new double[]{depth, depth},
                new double[]{1D, 1D}
        );
    }

    public double width(double alongReach) {
        return sample(widths, alongReach);
    }

    public double bankWidth(double alongReach) {
        return sample(bankWidths, alongReach);
    }

    public double depth(double alongReach) {
        return sample(depths, alongReach);
    }

    public double roofScale(double alongReach) {
        return sample(roofScales, alongReach);
    }

    public double maximumWidth() {
        return maximumWidth;
    }

    public double maximumBankWidth() {
        return maximumBankWidth;
    }

    public double maximumDepth() {
        return maximumDepth;
    }

    public int size() {
        return positions.length;
    }

    public double position(int index) {
        return positions[index];
    }

    public double widthAtIndex(int index) {
        return widths[index];
    }

    public double bankWidthAtIndex(int index) {
        return bankWidths[index];
    }

    public double depthAtIndex(int index) {
        return depths[index];
    }

    public double roofScaleAtIndex(int index) {
        return roofScales[index];
    }

    public int intervalIndex(double alongReach) {
        double position = StrictMath.max(0D, StrictMath.min(1D, alongReach));
        int index = Arrays.binarySearch(positions, position);
        if (index >= 0) {
            return StrictMath.min(index, positions.length - 2);
        }
        return StrictMath.max(0, StrictMath.min(-index - 2, positions.length - 2));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof RiverBodyProfile profile)) {
            return false;
        }
        return Arrays.equals(positions, profile.positions)
                && Arrays.equals(widths, profile.widths)
                && Arrays.equals(bankWidths, profile.bankWidths)
                && Arrays.equals(depths, profile.depths)
                && Arrays.equals(roofScales, profile.roofScales);
    }

    @Override
    public int hashCode() {
        int hash = Arrays.hashCode(positions);
        hash = 31 * hash + Arrays.hashCode(widths);
        hash = 31 * hash + Arrays.hashCode(bankWidths);
        hash = 31 * hash + Arrays.hashCode(depths);
        return 31 * hash + Arrays.hashCode(roofScales);
    }

    private double sample(double[] values, double alongReach) {
        double position = StrictMath.max(0D, StrictMath.min(1D, alongReach));
        int index = Arrays.binarySearch(positions, position);
        if (index >= 0) {
            return values[index];
        }
        int upper = -index - 1;
        if (upper <= 0) {
            return values[0];
        }
        if (upper >= positions.length) {
            return values[values.length - 1];
        }
        int lower = upper - 1;
        double range = positions[upper] - positions[lower];
        double interpolation = range <= 0D ? 0D : (position - positions[lower]) / range;
        return values[lower] + (values[upper] - values[lower]) * interpolation;
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0D) {
            throw new IllegalArgumentException("River body profile " + name + " must be finite and positive");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0D) {
            throw new IllegalArgumentException("River body profile " + name + " must be finite and non-negative");
        }
    }

    private static void requireUnitScale(double value, String name) {
        if (!Double.isFinite(value) || value <= 0D || value > 1D) {
            throw new IllegalArgumentException("River body profile " + name + " must be greater than zero and at most one");
        }
    }
}

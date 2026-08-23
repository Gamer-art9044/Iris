package art.arcane.iris.engine.river;

public final class RiverPolyline {
    private final double[] x;
    private final double[] z;
    private final double[] cumulativeLength;
    private final double length;

    public RiverPolyline(double[] x, double[] z) {
        if (x.length != z.length || x.length < 2) {
            throw new IllegalArgumentException("River polyline requires matching coordinate arrays and at least two points");
        }
        this.x = x.clone();
        this.z = z.clone();
        cumulativeLength = new double[x.length];
        double measuredLength = 0.0;
        for (int i = 0; i < x.length; i++) {
            if (!Double.isFinite(x[i]) || !Double.isFinite(z[i])) {
                throw new IllegalArgumentException("River polyline coordinates must be finite");
            }
            if (i > 0) {
                measuredLength += StrictMath.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
                cumulativeLength[i] = measuredLength;
            }
        }
        length = measuredLength;
    }

    public int size() {
        return x.length;
    }

    public double x(int index) {
        return x[index];
    }

    public double z(int index) {
        return z[index];
    }

    public double cumulativeLength(int index) {
        return cumulativeLength[index];
    }

    public double length() {
        return length;
    }
}

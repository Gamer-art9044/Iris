package art.arcane.iris.util.simd;

public interface SimdKernels {
    String describe();

    void roundToInt(double[] source, int[] target, int length);

    double sum(double[] values, int length);

    double max(double[] values, int length);
}

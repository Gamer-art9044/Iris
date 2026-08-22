package art.arcane.iris.util.simd;

import art.arcane.iris.util.project.noise.FastNoiseDouble;
import art.arcane.iris.util.project.noise.FastNoiseDouble.FractalType;
import art.arcane.iris.util.project.noise.FastNoiseDouble.NoiseType;
import art.arcane.volmlib.util.math.RNG;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NoiseKernels2DParityTest {
    private static FastNoiseDouble oracle(long seed, int octaves) {
        FastNoiseDouble n = new FastNoiseDouble(seed);
        n.setNoiseType(NoiseType.SimplexFractal);
        n.setFractalType(FractalType.FBM);
        n.setFractalOctaves(octaves);
        return n;
    }

    private static void assertKernelMatchesOracle(NoiseKernels2D kernel) {
        RNG rng = new RNG(99L);
        for (int octaves = 1; octaves <= 8; octaves++) {
            FastNoiseDouble n = oracle(1337L + octaves, octaves);
            int length = 256;
            double[] xs = new double[length];
            double[] zs = new double[length];
            double[] expected = new double[length];
            double[] out = new double[length];
            for (int k = 0; k < length; k++) {
                double x = (rng.nextDouble() - 0.5D) * 1_000_000D;
                double z = (rng.nextDouble() - 0.5D) * 1_000_000D;
                xs[k] = x;
                zs[k] = z;
                expected[k] = n.GetSimplexFractal(x, z);
            }
            kernel.simplexFractalFBM(1337L + octaves, octaves, n.getFrequency(), 2.0D, 0.5D, n.getFractalBounding(), xs, zs, out, length);
            for (int k = 0; k < length; k++) {
                assertEquals("octaves=" + octaves + " idx=" + k, expected[k], out[k], 0D);
            }
        }
    }

    @Test
    public void scalarKernelIsBitExactToOracle() {
        assertKernelMatchesOracle(new ScalarNoiseKernels2D());
    }

    @Test
    public void vectorKernelSingleOctaveIsBitExactToOracle() {
        org.junit.Assume.assumeTrue(VectorNoiseKernels2D.lanesAligned());
        FastNoiseDouble n = oracle(202020L, 1);
        RNG rng = new RNG(7L);
        int length = 333;
        double[] xs = new double[length];
        double[] zs = new double[length];
        double[] expected = new double[length];
        double[] out = new double[length];
        for (int k = 0; k < length; k++) {
            double x = (rng.nextDouble() - 0.5D) * 800_000D;
            double z = (rng.nextDouble() - 0.5D) * 800_000D;
            xs[k] = x;
            zs[k] = z;
            expected[k] = n.GetSimplexFractal(x, z);
        }
        new VectorNoiseKernels2D().simplexFractalFBM(202020L, 1, n.getFrequency(), 2.0D, 0.5D, n.getFractalBounding(), xs, zs, out, length);
        for (int k = 0; k < length; k++) {
            assertEquals("idx=" + k, expected[k], out[k], 0D);
        }
    }

    @Test
    public void vectorKernelAllOctavesAreBitExactToOracle() {
        org.junit.Assume.assumeTrue(VectorNoiseKernels2D.lanesAligned());
        assertKernelMatchesOracle(new VectorNoiseKernels2D());
    }

    @Test
    public void simdSupportReturnsBitExactNoiseKernel() {
        NoiseKernels2D kernel = SimdSupport.noiseKernels2D();
        org.junit.Assert.assertNotNull(kernel);
        assertKernelMatchesOracle(kernel);
    }

    @Test
    public void simdSupportNeverSelectsVectorBelowProfitableLanes() {
        if (VectorNoiseKernels2D.profitable()) {
            org.junit.Assert.assertTrue(SimdSupport.createVectorNoiseKernels2D() instanceof VectorNoiseKernels2D);
        } else {
            org.junit.Assert.assertTrue(SimdSupport.noiseKernels2D() instanceof ScalarNoiseKernels2D);
        }
    }

    @Test
    public void vectorMatchesScalarAcrossEdgeCases() {
        org.junit.Assume.assumeTrue(VectorNoiseKernels2D.lanesAligned());
        NoiseKernels2D scalar = new ScalarNoiseKernels2D();
        NoiseKernels2D vector = new VectorNoiseKernels2D();
        RNG rng = new RNG(31337L);
        int[] lengths = {1, 3, 7, 8, 9, 255, 256, 257};
        double[] edgeCoords = {-4.0D, -3.0D, -1.0D, -0.0D, 0.0D, 1.0D, 4.0D, 1_000_000.0D, -1_000_000.0D};
        for (int octaves = 1; octaves <= 8; octaves++) {
            for (int length : lengths) {
                double[] xs = new double[length];
                double[] zs = new double[length];
                double[] a = new double[length];
                double[] b = new double[length];
                for (int k = 0; k < length; k++) {
                    boolean edge = (k % 3) == 0;
                    xs[k] = edge ? edgeCoords[k % edgeCoords.length] : (rng.nextDouble() - 0.5D) * 2_000_000D;
                    zs[k] = edge ? edgeCoords[(k + 1) % edgeCoords.length] : (rng.nextDouble() - 0.5D) * 2_000_000D;
                }
                scalar.simplexFractalFBM(77L, octaves, 0.01D, 2.0D, 0.5D, 0.6667D, xs, zs, a, length);
                vector.simplexFractalFBM(77L, octaves, 0.01D, 2.0D, 0.5D, 0.6667D, xs, zs, b, length);
                for (int k = 0; k < length; k++) {
                    assertEquals("oct=" + octaves + " len=" + length + " idx=" + k, a[k], b[k], 0D);
                }
            }
        }
    }
}

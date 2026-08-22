package art.arcane.iris.engine;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.iris.engine.object.IrisInterpolator;
import art.arcane.iris.util.project.interpolation.NoiseBounds;
import art.arcane.iris.util.project.interpolation.NoiseBoundsProvider;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

public class IrisComplexGridBoundsCacheTest {
    @Test
    public void gridBoundsCacheIsIsolatedPerComplex() throws Exception {
        IrisComplex first = createComplex();
        IrisComplex second = createComplex();
        Method cornerBounds = cornerBoundsMethod();

        long firstPacked = invokeCornerBounds(first, cornerBounds, new CountingInterpolator(1.25D, 2.5D), 64, -32);
        long secondPacked = invokeCornerBounds(second, cornerBounds, new CountingInterpolator(10.25D, 20.5D), 64, -32);

        assertNotEquals(firstPacked, secondPacked);
        assertEquals(1.25F, unpackLow(firstPacked), 0D);
        assertEquals(2.5F, unpackHigh(firstPacked), 0D);
        assertEquals(10.25F, unpackLow(secondPacked), 0D);
        assertEquals(20.5F, unpackHigh(secondPacked), 0D);
    }

    @Test
    public void gridBoundsCacheReusesCornersWithinSameComplex() throws Exception {
        IrisComplex complex = createComplex();
        Method cornerBounds = cornerBoundsMethod();
        CountingInterpolator interpolator = new CountingInterpolator(3.5D, 7.25D);

        long firstPacked = invokeCornerBounds(complex, cornerBounds, interpolator, 128, 96);
        long secondPacked = invokeCornerBounds(complex, cornerBounds, interpolator, 128, 96);

        assertEquals(firstPacked, secondPacked);
        assertEquals(1, interpolator.getInvocations());
    }

    @Test
    public void alignedGridSampleUsesOnlyTheContributingCorner() throws Exception {
        IrisComplex complex = createComplex();
        Method gridSampleBounds = gridSampleBoundsMethod();
        CountingInterpolator interpolator = new CountingInterpolator(3.5D, 7.25D);

        NoiseBounds bounds = invokeGridSampleBounds(complex, gridSampleBounds, interpolator, 64D, -32D);

        assertEquals(3.5F, bounds.min(), 0D);
        assertEquals(7.25F, bounds.max(), 0D);
        assertEquals(1, interpolator.getInvocations());
    }

    private IrisComplex createComplex() throws Exception {
        IrisComplex complex = mock(IrisComplex.class, CALLS_REAL_METHODS);

        Field generatorBounds = IrisComplex.class.getDeclaredField("generatorBounds");
        generatorBounds.setAccessible(true);
        generatorBounds.set(complex, new HashMap<>());

        Class<?> cacheClass = Class.forName("art.arcane.iris.engine.IrisComplex$GridBoundsCache");
        Constructor<?> cacheConstructor = cacheClass.getDeclaredConstructor();
        cacheConstructor.setAccessible(true);
        ThreadLocal<Object> cache = ThreadLocal.withInitial(() -> newCache(cacheConstructor));
        Field gridBoundsCache = IrisComplex.class.getDeclaredField("gridBoundsCache");
        gridBoundsCache.setAccessible(true);
        gridBoundsCache.set(complex, cache);
        return complex;
    }

    private Object newCache(Constructor<?> constructor) {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Method cornerBoundsMethod() throws Exception {
        Class<?> cacheClass = Class.forName("art.arcane.iris.engine.IrisComplex$GridBoundsCache");
        Method method = IrisComplex.class.getDeclaredMethod(
                "cornerBounds",
                cacheClass,
                Engine.class,
                IrisInterpolator.class,
                int.class,
                IrisGenerator[].class,
                int.class,
                int.class
        );
        method.setAccessible(true);
        return method;
    }

    private Method gridSampleBoundsMethod() throws Exception {
        Method method = IrisComplex.class.getDeclaredMethod(
                "gridSampleBounds",
                Engine.class,
                IrisInterpolator.class,
                int.class,
                IrisGenerator[].class,
                double.class,
                double.class
        );
        method.setAccessible(true);
        return method;
    }

    private long invokeCornerBounds(IrisComplex complex, Method method, IrisInterpolator interpolator, int x, int z) throws Exception {
        Field gridBoundsCache = IrisComplex.class.getDeclaredField("gridBoundsCache");
        gridBoundsCache.setAccessible(true);
        ThreadLocal<?> cache = (ThreadLocal<?>) gridBoundsCache.get(complex);
        return (long) method.invoke(complex, cache.get(), null, interpolator, 0, new IrisGenerator[0], x, z);
    }

    private NoiseBounds invokeGridSampleBounds(
            IrisComplex complex,
            Method method,
            IrisInterpolator interpolator,
            double x,
            double z
    ) throws Exception {
        return (NoiseBounds) method.invoke(complex, null, interpolator, 0, new IrisGenerator[0], x, z);
    }

    private float unpackLow(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private float unpackHigh(long packed) {
        return Float.intBitsToFloat((int) packed);
    }

    private static final class CountingInterpolator extends IrisInterpolator {
        private final NoiseBounds bounds;
        private final AtomicInteger invocations = new AtomicInteger();

        private CountingInterpolator(double low, double high) {
            bounds = new NoiseBounds(low, high);
        }

        @Override
        public NoiseBounds interpolateBounds(double x, double z, NoiseBoundsProvider provider) {
            invocations.incrementAndGet();
            return bounds;
        }

        private int getInvocations() {
            return invocations.get();
        }
    }
}

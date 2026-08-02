package art.arcane.iris.core.pregenerator;

import art.arcane.volmlib.util.math.Position2;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PregenTaskBoundsOverflowTest {
    @Test
    public void farPositiveCenterKeepsRegionBoundsOrdered() {
        PregenTask task = PregenTask.builder()
                .center(new Position2(Integer.MAX_VALUE - 16, Integer.MAX_VALUE - 16))
                .radiusX(4096)
                .radiusZ(4096)
                .build();

        int[] bounds = task.regionBounds();

        assertTrue("minX must not exceed maxX", bounds[0] <= bounds[2]);
        assertTrue("minZ must not exceed maxZ", bounds[1] <= bounds[3]);
    }

    @Test
    public void farNegativeCenterKeepsRegionBoundsOrdered() {
        PregenTask task = PregenTask.builder()
                .center(new Position2(Integer.MIN_VALUE + 16, Integer.MIN_VALUE + 16))
                .radiusX(4096)
                .radiusZ(4096)
                .build();

        int[] bounds = task.regionBounds();

        assertTrue("minX must not exceed maxX", bounds[0] <= bounds[2]);
        assertTrue("minZ must not exceed maxZ", bounds[1] <= bounds[3]);
    }

    @Test
    public void hugeRadiusAroundOriginIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PregenTask.builder()
                        .center(new Position2(0, 0))
                        .radiusX(Integer.MAX_VALUE)
                        .radiusZ(Integer.MAX_VALUE)
                        .build());

        assertTrue(failure.getMessage().contains("radius 2147483647x2147483647"));
    }

    @Test
    public void worldLimitRadiusIsAccepted() {
        PregenTask task = PregenTask.builder()
                .center(new Position2(0, 0))
                .radiusX(30_000_000)
                .radiusZ(30_000_000)
                .build();

        int[] bounds = task.regionBounds();

        assertEquals(-58594, bounds[0]);
        assertEquals(58594, bounds[2]);
    }

    @Test
    public void ordinaryBoundsAreUnchanged() {
        PregenTask task = PregenTask.builder()
                .center(new Position2(0, 0))
                .radiusX(1024)
                .radiusZ(512)
                .build();

        assertArrayEqualsMessage(new int[]{-2, -1, 2, 1}, task.regionBounds());
    }

    @Test
    public void clampSaturatesInsteadOfWrapping() {
        assertEquals(Integer.MAX_VALUE, PregenTask.clampBlock((long) Integer.MAX_VALUE + 1L));
        assertEquals(Integer.MIN_VALUE, PregenTask.clampBlock((long) Integer.MIN_VALUE - 1L));
        assertEquals(0, PregenTask.clampBlock(0L));
        assertEquals(-7, PregenTask.clampBlock(-7L));
    }

    private static void assertArrayEqualsMessage(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals("bounds[" + index + "]", expected[index], actual[index]);
        }
    }
}

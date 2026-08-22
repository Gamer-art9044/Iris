package art.arcane.iris.util.project.noise;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CloverNoiseParityTest {
    private static final long[] SEEDS = {0L, -1L, 1337L, Long.MIN_VALUE, Long.MAX_VALUE};
    private static final double[][] POINTS = {
            {0.125D, -0.75D, 1.5D},
            {-1000.25D, 64.5D, 2048.75D},
            {29_999_984D, -64D, -29_999_984D},
            {Math.PI, Math.E, -Math.sqrt(2D)}
    };
    private static final long[][] EXPECTED_3D = {
            {4606295205971726443L, 4596584066878230492L, 4601072533138395306L, 4595465536009596573L},
            {4594559156169257303L, 4602986449390023959L, 4602157576770610768L, 4604264428473675137L},
            {4594287239476896260L, 4606819753250159891L, 4603131747488558437L, 4605050910998320058L},
            {4594574716919961912L, 4582369728393548057L, 4600712766107896080L, 4594781081233153808L},
            {4603622723932858649L, 4604886251804482757L, 4600180200303866897L, 4597047378643079682L}
    };
    private static final long[][] EXPECTED_2D = {
            {4600886466954977930L, 4603042301510796801L, 4605282822283219348L, 4603381024907987886L},
            {4604231235993958026L, 4603294413466771076L, 4606358936184576936L, 4605313173145937690L},
            {4602575096448892092L, 4600034591678710230L, 4603842817538381592L, 4604447254328254894L},
            {4602176302263458666L, 4592022086679349802L, 4605779333798454855L, 4603816542564903403L},
            {4603283241394868042L, 4601437833095430179L, 4606037056899058631L, 4604909992999588843L}
    };

    @Test
    public void optimizedVectorMathPreservesExactNoiseBits() {
        for (int seedIndex = 0; seedIndex < SEEDS.length; seedIndex++) {
            CloverNoise noise = new CloverNoise(SEEDS[seedIndex]);
            for (int pointIndex = 0; pointIndex < POINTS.length; pointIndex++) {
                double[] point = POINTS[pointIndex];
                assertEquals(EXPECTED_3D[seedIndex][pointIndex], Double.doubleToRawLongBits(
                        noise.noise(point[0], point[1], point[2])));
                assertEquals(EXPECTED_2D[seedIndex][pointIndex], Double.doubleToRawLongBits(
                        noise.noise(point[0], point[2])));
            }
        }
    }
}

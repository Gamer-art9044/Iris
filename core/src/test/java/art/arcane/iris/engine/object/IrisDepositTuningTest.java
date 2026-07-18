package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.spi.PlatformBlockState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisDepositTuningTest {
    @Test
    public void depositSizesScaleAndRemainWithinSchemaLimit() {
        assertEquals(8, IrisDepositGenerator.scaledDepositSize(4, 2D));
        assertEquals(16, IrisDepositGenerator.scaledDepositSize(8, 2D));
        assertEquals(0, IrisDepositGenerator.scaledDepositSize(0, 2D));
        assertEquals(8192, IrisDepositGenerator.scaledDepositSize(8192, 2D));
    }

    @Test
    public void biomeOreTuningDefaultsPreserveExistingGeneration() {
        IrisBiome biome = new IrisBiome();

        assertEquals(1D, biome.getOreDepositFrequencyMultiplier(), 0D);
        assertEquals(1D, biome.getOreDepositSizeMultiplier(), 0D);
    }

    @Test
    public void clumpCachesAreScopedByWorldSeedAndSize() {
        IrisDepositGenerator.ClumpCacheKey firstWorld =
                new IrisDepositGenerator.ClumpCacheKey(41L, 4, 8);
        IrisDepositGenerator.ClumpCacheKey secondWorld =
                new IrisDepositGenerator.ClumpCacheKey(42L, 4, 8);
        IrisDepositGenerator.ClumpCacheKey largerVein =
                new IrisDepositGenerator.ClumpCacheKey(41L, 8, 16);

        assertNotEquals(firstWorld, secondWorld);
        assertNotEquals(firstWorld, largerVein);
        assertEquals(firstWorld, new IrisDepositGenerator.ClumpCacheKey(41L, 4, 8));
    }

    @Test
    public void onlyOreDepositPalettesReceiveBiomeTuning() {
        IrisData data = mock(IrisData.class);
        IrisDepositGenerator oreGenerator = generatorWithState(data, true);
        IrisDepositGenerator stoneGenerator = generatorWithState(data, false);

        assertTrue(oreGenerator.isOre(data));
        assertFalse(stoneGenerator.isOre(data));
    }

    private IrisDepositGenerator generatorWithState(IrisData data, boolean ore) {
        IrisBlockData block = mock(IrisBlockData.class);
        PlatformBlockState state = mock(PlatformBlockState.class);
        when(block.getBlockData(data)).thenReturn(state);
        when(state.isOre()).thenReturn(ore);
        IrisDepositGenerator generator = new IrisDepositGenerator();
        generator.getPalette().add(block);
        return generator;
    }
}

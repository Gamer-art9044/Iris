package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CustomBiomeSourceStructureContractTest {
    @Test
    public void nativeStructuresUseTerrainSafeDerivativeAtEveryBiomeBoundary() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));

        assertTrue(source.contains("resolveBiomeHolder(registry, i.getStructureDerivativeKey())"));
        assertTrue(source.contains("resolveBiomeHolder(biomeRegistry, irisBiome.getStructureDerivativeKey())"));
        assertTrue(source.contains("resolution.irisBiome.getStructureDerivativeKey()"));
        assertFalse(source.contains("resolution.irisBiome.getVanillaDerivative()"));
    }

    @Test
    public void biomeRuntimeReadsAreGenerationLeased() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));

        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_possible_biomes\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_spawn_biome\")"));
        assertFalse(source.contains("Iris spawn biome lookup was rejected during an engine transition"));
        assertFalse(source.contains("Iris spawn biome lookup has no active engine runtime"));
        assertTrue(source.contains("vanillaSpawnBiomes.get(biome.value())"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_structure_biome\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_biomes_within\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_visible_biome\")"));
        assertTrue(source.contains("if (engine.isClosed())"));
        assertTrue(source.contains("engine.isClosing() || e.isExpectedTeardown()"));
        assertTrue(source.contains("catch (GenerationSessionException e)"));
        assertTrue(source.contains("e.isExpectedTeardown()"));
    }

    @Test
    public void strongholdRingSearchSamplesOneQuartColumnPerChunk() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));

        assertTrue(source.contains("private static final int STRONGHOLD_RING_SEARCH_Y = 0"));
        assertTrue(source.contains("private static final int STRONGHOLD_RING_SEARCH_RADIUS = 112"));
        assertTrue(source.contains("private static final int STRONGHOLD_RING_SEARCH_QUART_STEP = 4"));
        assertTrue(source.contains("x, y, z, searchRadius, quartStep, allowed, random, false, sampler"));
        assertTrue(source.contains(
                "return super.findBiomeHorizontal(x, y, z, searchRadius, allowed, random, sampler)"));
    }
}

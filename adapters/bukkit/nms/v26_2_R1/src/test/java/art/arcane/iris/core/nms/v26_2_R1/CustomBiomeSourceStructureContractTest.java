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
        assertTrue(source.contains(
                "return super.findBiomeHorizontal(x, y, z, searchRadius, allowed, random, sampler)"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_structure_ring_biome\")"));
        assertTrue(source.contains("findNaturalSurfaceBiomeHorizontal("));
        assertTrue(source.contains("radius += quartStep"));
        assertTrue(source.contains("offsetZ += quartStep"));
        assertTrue(source.contains("offsetX += quartStep"));
        assertTrue(source.contains("random.nextInt(matches + 1) == 0"));
    }

    @Test
    public void onlyConcentricRingSuitabilityUsesTheNaturalTerrainStream() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int realResolutionStart = source.indexOf("private Holder<Biome> resolveSurfaceStructureBiomeHolder(");
        int naturalResolutionStart = source.indexOf(
                "private Holder<Biome> resolveNaturalSurfaceStructureBiomeHolder(");
        int resolutionEnd = source.indexOf("public Holder<Biome> getVisibleNoiseBiome(", naturalResolutionStart);

        assertTrue(realResolutionStart >= 0);
        assertTrue(naturalResolutionStart > realResolutionStart);
        assertTrue(resolutionEnd > naturalResolutionStart);
        String realResolution = source.substring(realResolutionStart, naturalResolutionStart);
        String naturalResolution = source.substring(naturalResolutionStart, resolutionEnd);

        assertTrue(realResolution.contains("engine.getComplex().getTrueBiomeStream().get(blockX, blockZ)"));
        assertFalse(realResolution.contains("getNaturalTrueBiomeStream()"));
        assertTrue(naturalResolution.contains(
                "engine.getComplex().getNaturalTrueBiomeStream().get(blockX, blockZ)"));
        assertFalse(naturalResolution.contains("getTrueBiomeStream()"));
        assertFalse(source.contains("studioBootstrapSurfaceStructureBiomeCache"));
    }

    @Test
    public void visibleBiomeBatchReusesTheCallersGenerationLease() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int lookupStart = source.indexOf("public Holder<Biome> getVisibleNoiseBiome(");
        int lookupEnd = source.indexOf("private GenerationSessionLease tryAcquireGenerationLease(", lookupStart);

        assertTrue(lookupStart >= 0);
        assertTrue(lookupEnd > lookupStart);
        String lookup = source.substring(lookupStart, lookupEnd);

        assertTrue(lookup.contains("tryAcquireGenerationLease(\"bukkit_visible_biome\")"));
        assertTrue(lookup.contains("prepareVisibleBiomeBatch()"));
        assertTrue(lookup.contains("getVisibleNoiseBiomeWithActiveGenerationLease(x, y, z, sampler)"));
        assertTrue(lookup.indexOf("prepareVisibleBiomeBatch()")
                < lookup.indexOf("getVisibleNoiseBiomeWithActiveGenerationLease(x, y, z, sampler)"));
        assertFalse(source.contains("studioBootstrapNoiseBiomeCache"));
    }
}

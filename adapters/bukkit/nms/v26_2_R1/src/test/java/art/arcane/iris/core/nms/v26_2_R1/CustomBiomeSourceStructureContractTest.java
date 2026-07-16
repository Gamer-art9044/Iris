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
}

package art.arcane.iris.modded;

import art.arcane.iris.spi.PlatformBiome;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ModdedBiomeWriterCacheTest {
    @Test
    public void unavailableServerFallsBackToBiomeIdZero() {
        ModdedBiomeWriter writer = new ModdedBiomeWriter(() -> null);

        assertEquals(0, writer.biomeIdFor("minecraft:plains"));
        assertEquals(0, writer.biomeIdFor("minecraft:plains"));
    }

    @Test
    public void unavailableServerYieldsAnEmptyMutableBiomeList() {
        ModdedBiomeWriter writer = new ModdedBiomeWriter(() -> null);

        List<PlatformBiome> first = writer.allBiomes();
        List<PlatformBiome> second = writer.allBiomes();

        assertTrue(first.isEmpty());
        assertNotSame("callers must never share the writer cache instance", first, second);
        first.add(null);
        assertTrue(second.isEmpty());
    }
}

package art.arcane.iris.core;

import org.bukkit.NamespacedKey;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WorldCreatorCompatTest {
    @Test
    public void keyedPathPreservesWorldKey() {
        NamespacedKey key = new NamespacedKey("iris", "compat_world");

        assertEquals(key, WorldCreatorCompat.ofKey(key).key());
    }

    @Test
    public void fallbackNameDerivesLogicalNameFromKey() {
        assertEquals("compat_world", WorldCreatorCompat.fallbackName(new NamespacedKey("iris", "compat_world"), "world"));
        assertEquals("world", WorldCreatorCompat.fallbackName(NamespacedKey.minecraft("overworld"), "world"));
        assertEquals("world_nether", WorldCreatorCompat.fallbackName(NamespacedKey.minecraft("the_nether"), "world"));
        assertEquals("world_the_end", WorldCreatorCompat.fallbackName(NamespacedKey.minecraft("the_end"), "world"));
    }

    @Test
    public void persistentFallbackUsesExactConfiguredStartupName() {
        assertEquals(
                "world_iris_compat_world",
                WorldCreatorCompat.fallbackPersistentName(new NamespacedKey("iris", "compat_world"), "world")
        );
    }

    @Test
    public void fallbackKeyRoundTripsCreatorName() {
        assertEquals(new NamespacedKey("iris", "compat_world"), WorldCreatorCompat.fallbackKey("compat_world", "world"));
        assertEquals(NamespacedKey.minecraft("overworld"), WorldCreatorCompat.fallbackKey("world", "world"));
        assertEquals(NamespacedKey.minecraft("the_nether"), WorldCreatorCompat.fallbackKey("world_nether", "world"));
        assertEquals(
                new NamespacedKey("iris", "compat_world"),
                WorldCreatorCompat.fallbackKey("world_iris_compat_world", "world")
        );
    }

    @Test
    public void fallbackMappingIsStableAcrossRoundTrips() {
        NamespacedKey key = new NamespacedKey("iris", "iris_world");

        String name = WorldCreatorCompat.fallbackName(key, "world");

        assertEquals(key, WorldCreatorCompat.fallbackKey(name, "world"));
        assertEquals(name, WorldCreatorCompat.fallbackName(key, "world"));
    }
}

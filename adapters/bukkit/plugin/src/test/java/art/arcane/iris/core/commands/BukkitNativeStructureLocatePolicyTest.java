package art.arcane.iris.core.commands;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BukkitNativeStructureLocatePolicyTest {
    @Test
    public void nativeMonumentLocateIsUnavailable() {
        assertTrue(BukkitNativeStructureLocatePolicy.isUnavailable("minecraft:monument"));
        assertTrue(BukkitNativeStructureLocatePolicy.isUnavailable(" MINECRAFT:MONUMENT "));
    }

    @Test
    public void unrelatedAndNonCanonicalKeysRemainAvailable() {
        assertFalse(BukkitNativeStructureLocatePolicy.isUnavailable(null));
        assertFalse(BukkitNativeStructureLocatePolicy.isUnavailable(""));
        assertFalse(BukkitNativeStructureLocatePolicy.isUnavailable("minecraft:stronghold"));
        assertFalse(BukkitNativeStructureLocatePolicy.isUnavailable("minecraft:ocean_monument"));
        assertFalse(BukkitNativeStructureLocatePolicy.isUnavailable("minecraft:ocean_monuments"));
    }

    @Test
    public void rejectionExplainsSafetyAndGenerationBehavior() {
        String message = BukkitNativeStructureLocatePolicy.unavailableMessage().toLowerCase();

        assertTrue(message.contains("unavailable"));
        assertTrue(message.contains("stall the server thread"));
        assertTrue(message.contains("generation is unaffected"));
    }
}

package art.arcane.iris.modded.api;

import net.minecraft.resources.Identifier;
import org.junit.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModdedCustomContentRegistryTest {
    @Test
    public void publishesProvidersOnlyAfterCompleteDiscoveryAndCanRollBack() {
        String modId = "iris_discovery_success";
        TestProvider provider = new TestProvider(modId, null);
        boolean previousDiscoveryComplete = ModdedCustomContentRegistry.discoveryComplete();

        ModdedCustomContentRegistry.Discovery discovery =
                ModdedCustomContentRegistry.discover(List.of(provider));
        try {
            assertTrue(ModdedCustomContentRegistry.discoveryComplete());
            assertTrue(ModdedCustomContentRegistry.hasProvider(modId));
        } finally {
            discovery.rollback();
        }

        assertEquals(previousDiscoveryComplete, ModdedCustomContentRegistry.discoveryComplete());
        assertFalse(ModdedCustomContentRegistry.hasProvider(modId));
    }

    @Test
    public void failedDiscoveryPublishesNothingAndPreservesTheCause() {
        String firstModId = "iris_discovery_staged";
        String failingModId = "iris_discovery_failure";
        RuntimeException original = new RuntimeException("provider init failed");
        boolean previousDiscoveryComplete = ModdedCustomContentRegistry.discoveryComplete();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ModdedCustomContentRegistry.discover(List.of(
                        new TestProvider(firstModId, null),
                        new TestProvider(failingModId, original))));

        assertSame(original, thrown);
        assertEquals(previousDiscoveryComplete, ModdedCustomContentRegistry.discoveryComplete());
        assertFalse(ModdedCustomContentRegistry.hasProvider(firstModId));
        assertFalse(ModdedCustomContentRegistry.hasProvider(failingModId));
    }

    private static final class TestProvider implements ModdedDataProvider {
        private final String modId;
        private final RuntimeException failure;

        private TestProvider(String modId, RuntimeException failure) {
            this.modId = modId;
            this.failure = failure;
        }

        @Override
        public String modId() {
            return modId;
        }

        @Override
        public Collection<Identifier> getTypes(ModdedDataType type) {
            return List.of();
        }

        @Override
        public boolean isValidProvider(Identifier id, ModdedDataType type) {
            return false;
        }

        @Override
        public void init() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}

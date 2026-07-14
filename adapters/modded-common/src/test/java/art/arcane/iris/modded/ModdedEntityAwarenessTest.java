package art.arcane.iris.modded;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedEntityAwarenessTest {
    @Test
    public void unawareConfigurationAddsThePartialAiTag() {
        Set<String> tags = new HashSet<>();

        ModdedEntityAwareness.configureTags(tags, false);

        assertFalse(ModdedEntityAwareness.isAware(tags));
    }

    @Test
    public void awareConfigurationRemovesThePartialAiTag() {
        Set<String> tags = new HashSet<>();
        ModdedEntityAwareness.configureTags(tags, false);

        ModdedEntityAwareness.configureTags(tags, true);

        assertTrue(ModdedEntityAwareness.isAware(tags));
    }

    @Test
    public void mobsAreAwareWithoutAnIrisTag() {
        assertTrue(ModdedEntityAwareness.isAware(Set.of()));
    }
}

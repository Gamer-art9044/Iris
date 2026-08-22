package art.arcane.iris.modded;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedEntityPersistenceTest {
    @Test
    public void generatedNonPersistentEntityIsExcludedFromVanillaSaves() {
        Set<String> tags = new HashSet<>();

        ModdedEntityPersistence.configureTags(tags, false);

        assertFalse(ModdedEntityPersistence.shouldSave(tags, true));
    }

    @Test
    public void positivePersistenceRemovesTheSaveExclusion() {
        Set<String> tags = new HashSet<>();
        ModdedEntityPersistence.configureTags(tags, false);

        ModdedEntityPersistence.configureTags(tags, true);

        assertTrue(ModdedEntityPersistence.shouldSave(tags, true));
    }

    @Test
    public void interceptionNeverOverridesVanillaSaveRejection() {
        Set<String> tags = new HashSet<>();

        ModdedEntityPersistence.configureTags(tags, true);

        assertFalse(ModdedEntityPersistence.shouldSave(tags, false));
    }
}

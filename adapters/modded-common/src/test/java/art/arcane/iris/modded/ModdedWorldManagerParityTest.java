package art.arcane.iris.modded;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedWorldManagerParityTest {
    @Test
    public void normalWorldAlwaysAllowsEntitySpawning() {
        assertTrue(ModdedWorldManager.entitySpawningEnabled(false, false));
        assertTrue(ModdedWorldManager.entitySpawningEnabled(false, true));
    }

    @Test
    public void studioWorldRequiresItsEntitySpawningSetting() {
        assertFalse(ModdedWorldManager.entitySpawningEnabled(true, false));
        assertTrue(ModdedWorldManager.entitySpawningEnabled(true, true));
    }
}

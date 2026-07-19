package art.arcane.iris.core;

import org.bukkit.NamespacedKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class IrisWorldStorageTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void derivesPaperDimensionRootFromNamespacedKey() throws Exception {
        File levelRoot = temporaryFolder.newFolder("world");
        NamespacedKey key = new NamespacedKey("iris", "runtime/studio");

        File dimensionRoot = IrisWorldStorage.dimensionRoot(levelRoot, key);

        assertEquals(new File(levelRoot, "dimensions/iris/runtime/studio").getAbsoluteFile(), dimensionRoot);
        assertEquals(key, IrisWorldStorage.keyFromDimensionRoot(levelRoot, dimensionRoot).orElseThrow());
    }

    @Test
    public void separatesLevelRootFromDimensionRoot() throws Exception {
        File levelRoot = temporaryFolder.newFolder("world");
        File dimensionRoot = new File(levelRoot, "dimensions/minecraft/overworld");

        assertEquals(levelRoot.getAbsoluteFile(), IrisWorldStorage.levelRoot(dimensionRoot));
    }

    @Test
    public void mapsIrisWorldNamesToOwnedPaperKeys() {
        assertEquals(NamespacedKey.minecraft("overworld"), IrisWorldStorage.keyFromName("world", "world"));
        assertEquals(NamespacedKey.minecraft("the_nether"), IrisWorldStorage.keyFromName("world_nether", "world"));
        assertEquals(NamespacedKey.minecraft("the_end"), IrisWorldStorage.keyFromName("world_the_end", "world"));
        assertEquals(new NamespacedKey("iris", "iris_world"), IrisWorldStorage.keyFromName("Iris World", "world"));
    }

    @Test
    public void mapsOwnedPaperKeysBackToLogicalWorldNames() {
        assertEquals("world", IrisWorldStorage.logicalName(NamespacedKey.minecraft("overworld"), "world"));
        assertEquals("world_nether", IrisWorldStorage.logicalName(NamespacedKey.minecraft("the_nether"), "world"));
        assertEquals("world_the_end", IrisWorldStorage.logicalName(NamespacedKey.minecraft("the_end"), "world"));
        assertEquals("iris_world", IrisWorldStorage.logicalName(new NamespacedKey("iris", "iris_world"), "world"));
    }

    @Test
    public void rejectsKeysThatEscapeNamespaceStorage() throws Exception {
        File levelRoot = temporaryFolder.newFolder("world");
        NamespacedKey key = NamespacedKey.minecraft("../outside");

        assertThrows(IllegalArgumentException.class, () -> IrisWorldStorage.dimensionRoot(levelRoot, key));
    }
}

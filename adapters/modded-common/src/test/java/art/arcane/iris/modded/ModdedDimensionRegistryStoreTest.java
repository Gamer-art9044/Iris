package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ModdedDimensionRegistryStoreTest {
    @Test
    public void registryRoundTripsPersistentDimensions() throws IOException {
        Path root = Files.createTempDirectory("iris-dimension-registry");
        Path file = root.resolve("iris-dimensions.json");
        try {
            List<ModdedDimensionRegistryStore.PersistentDimension> expected = List.of(
                    new ModdedDimensionRegistryStore.PersistentDimension(
                            "iris:first", "overworld", "overworld", 42L),
                    new ModdedDimensionRegistryStore.PersistentDimension(
                            "iris:second", "other", "surface", -9L));

            ModdedDimensionRegistryStore.write(file, expected);

            assertEquals(expected, ModdedDimensionRegistryStore.load(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void malformedEntryDoesNotDiscardHealthyEntries() throws IOException {
        Path root = Files.createTempDirectory("iris-dimension-registry-partial");
        Path file = root.resolve("iris-dimensions.json");
        try {
            Files.writeString(file, """
                    {
                      "dimensions": [
                        {"id":"iris:good","pack":"overworld","dimension":"overworld","seed":7},
                        {"id":"iris:broken","pack":"overworld"}
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            assertEquals(List.of(new ModdedDimensionRegistryStore.PersistentDimension(
                    "iris:good", "overworld", "overworld", 7L)),
                    ModdedDimensionRegistryStore.load(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void truncatedRegistryNeverBecomesAnEmptySuccessfulLoad() throws IOException {
        Path root = Files.createTempDirectory("iris-dimension-registry-truncated");
        Path file = root.resolve("iris-dimensions.json");
        try {
            Files.writeString(file, "{\"dimensions\":[", StandardCharsets.UTF_8);

            assertThrows(IllegalStateException.class,
                    () -> ModdedDimensionRegistryStore.load(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void missingDimensionsArrayNeverBecomesAnEmptySuccessfulLoad() throws IOException {
        Path root = Files.createTempDirectory("iris-dimension-registry-missing-root");
        Path file = root.resolve("iris-dimensions.json");
        try {
            Files.writeString(file, "{}", StandardCharsets.UTF_8);

            assertThrows(IllegalStateException.class,
                    () -> ModdedDimensionRegistryStore.load(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(root);
        }
    }
}

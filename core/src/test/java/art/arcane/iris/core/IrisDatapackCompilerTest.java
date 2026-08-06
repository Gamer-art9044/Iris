package art.arcane.iris.core;

import art.arcane.iris.core.nms.datapack.v1217.DataFixerV1217;
import art.arcane.volmlib.util.collection.KList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisDatapackCompilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void compilesInstalledAndWorldLocalPacksIntoOneDatapack() throws Exception {
        Path dataDirectory = temporaryFolder.newFolder("data").toPath();
        Path serverRoot = temporaryFolder.newFolder("server").toPath();
        createPack(dataDirectory.resolve("packs/alpha"), "alpha", "alpha_custom");
        createPack(dataDirectory.resolve("packs/beta"), "beta", "beta_custom");
        createPack(dataDirectory.resolve("packs/.iris-import-stale"), "hidden", "hidden_custom");
        createPack(serverRoot.resolve("dimensions/example/world/iris/pack"), "world_local", "world_custom");
        createPack(serverRoot.resolve("dimensions/example/.iris-delete-stale/iris/pack"), "deleted", "deleted_custom");

        List<File> packRoots = IrisDatapackCompiler.collectPackRoots(dataDirectory, serverRoot);
        Path datapackRoot = temporaryFolder.newFolder("datapack").toPath();
        Path stale = datapackRoot.resolve("data/iris/dimension_type/removed.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale", StandardCharsets.UTF_8);
        IrisDatapackCompiler.CompilationResult result = IrisDatapackCompiler.compile(
                packRoots,
                new KList<File>().qadd(datapackRoot.toFile()),
                new DataFixerV1217(),
                107,
                false
        );

        assertEquals(3, result.packCount());
        assertEquals(3, result.dimensionCount());
        assertEquals(3, result.biomeCount());
        assertTrue(Files.isRegularFile(datapackRoot.resolve("pack.mcmeta")));
        assertTrue(Files.isRegularFile(datapackRoot.resolve("data/iris/dimension_type/alpha.json")));
        assertTrue(Files.isRegularFile(datapackRoot.resolve("data/iris/dimension_type/beta.json")));
        assertTrue(Files.isRegularFile(datapackRoot.resolve("data/iris/dimension_type/world_local.json")));
        assertTrue(Files.isRegularFile(datapackRoot.resolve("data/alpha/worldgen/biome/alpha_custom.json")));
        assertTrue(Files.isRegularFile(datapackRoot.resolve("data/beta/worldgen/biome/beta_custom.json")));
        assertTrue(Files.isRegularFile(datapackRoot.resolve("data/world_local/worldgen/biome/world_custom.json")));
        assertFalse(Files.exists(datapackRoot.resolve("data/iris/dimension_type/hidden.json")));
        assertFalse(Files.exists(datapackRoot.resolve("data/iris/dimension_type/deleted.json")));
        assertFalse(Files.exists(stale));
    }

    @Test
    public void compilesExternalPackFixtureWhenConfigured() throws Exception {
        String configuredPack = System.getenv("IRIS_TEST_PACK");
        Assume.assumeTrue(configuredPack != null && !configuredPack.isBlank());
        Path packRoot = Path.of(configuredPack).toAbsolutePath().normalize();
        Assume.assumeTrue(Files.isDirectory(packRoot.resolve("dimensions")));
        Path datapackRoot = temporaryFolder.newFolder("external-datapack").toPath();

        IrisDatapackCompiler.CompilationResult result = IrisDatapackCompiler.compile(
                List.of(packRoot.toFile()),
                new KList<File>().qadd(datapackRoot.toFile()),
                new DataFixerV1217(),
                107,
                false
        );

        assertTrue(result.packCount() > 0);
        assertTrue(result.dimensionCount() > 0);
        assertTrue(result.biomeCount() > 0);
        assertTrue(Files.isRegularFile(datapackRoot.resolve("pack.mcmeta")));
    }

    @Test
    public void collectsInstalledPacksThroughSymbolicLinkWorkspace() throws Exception {
        Path dataDirectory = temporaryFolder.newFolder("linked-data").toPath();
        Path serverRoot = temporaryFolder.newFolder("linked-server").toPath();
        Path sharedPacks = temporaryFolder.newFolder("linked-shared-packs").toPath();
        Path overworld = sharedPacks.resolve("overworld");
        createPack(overworld, "overworld", "linked_custom");
        try {
            Files.createSymbolicLink(dataDirectory.resolve("packs"), sharedPacks);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }

        assertEquals(List.of(dataDirectory.resolve("packs/overworld").toFile()),
                IrisDatapackCompiler.collectPackRoots(dataDirectory, serverRoot));
    }

    @Test
    public void compilingNoPacksPublishesCleanEmptyDatapack() throws Exception {
        Path datapackRoot = temporaryFolder.newFolder("empty-datapack").toPath();
        Path stale = datapackRoot.resolve("data/iris/dimension_type/removed.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale", StandardCharsets.UTF_8);

        IrisDatapackCompiler.CompilationResult result = IrisDatapackCompiler.compile(
                List.of(),
                new KList<File>().qadd(datapackRoot.toFile()),
                new DataFixerV1217(),
                107,
                false
        );

        assertEquals(0, result.packCount());
        assertEquals(0, result.dimensionCount());
        assertEquals(0, result.biomeCount());
        assertTrue(Files.isRegularFile(datapackRoot.resolve("pack.mcmeta")));
        assertFalse(Files.exists(stale));
    }

    private static void createPack(Path root, String dimensionKey, String biomeId) throws Exception {
        Files.createDirectories(root.resolve("dimensions"));
        Files.createDirectories(root.resolve("biomes"));
        Files.writeString(
                root.resolve("dimensions").resolve(dimensionKey + ".json"),
                """
                        {
                          "name": "Test Dimension",
                          "environment": "NORMAL",
                          "logicalHeight": 256,
                          "dimensionHeight": {
                            "min": -64,
                            "max": 320
                          }
                        }
                        """,
                StandardCharsets.UTF_8
        );
        Files.writeString(
                root.resolve("biomes/test.json"),
                """
                        {
                          "name": "Test Biome",
                          "derivative": "minecraft:plains",
                          "vanillaDerivative": "minecraft:plains",
                          "customDerivitives": [
                            {
                              "id": "%s"
                            }
                          ]
                        }
                        """.formatted(biomeId),
                StandardCharsets.UTF_8
        );
    }
}

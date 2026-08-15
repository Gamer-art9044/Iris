package art.arcane.iris.core;

import art.arcane.iris.core.pack.BrokenPackException;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisWorldGeneratorResolverTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void clearValidationRegistry() {
        PackValidationRegistry.clear();
    }

    @Test
    public void snapshotValidationIsLazyAndExactRootScoped() throws Exception {
        File packRoot = temporaryFolder.newFolder("world", "iris", "pack");
        writeValidPack(packRoot.toPath());
        PackValidationResult unrelatedNamedFailure = new PackValidationResult(
                "pack", List.of("unrelated basename failure"), List.of(), 1L);
        PackValidationRegistry.publish(unrelatedNamedFailure);

        PackValidationResult result = IrisWorldGeneratorResolver.requireSnapshotLoadable(packRoot);

        assertTrue(result.isLoadable());
        assertEquals(result, PackValidationRegistry.get(packRoot.toPath()));
        assertEquals(unrelatedNamedFailure, PackValidationRegistry.get("pack"));
    }

    @Test
    public void invalidatedSnapshotIsValidatedAgainBeforeAuthorization() throws Exception {
        File packRoot = temporaryFolder.newFolder("replace", "iris", "pack");
        writeValidPack(packRoot.toPath());
        assertTrue(IrisWorldGeneratorResolver.requireSnapshotLoadable(packRoot).isLoadable());

        Files.writeString(
                packRoot.toPath().resolve("dimensions/main.json"),
                "{",
                StandardCharsets.UTF_8);
        PackValidationRegistry.remove(packRoot.toPath());

        assertThrows(BrokenPackException.class,
                () -> IrisWorldGeneratorResolver.requireSnapshotLoadable(packRoot));
        PackValidationResult invalid = PackValidationRegistry.get(packRoot.toPath());
        assertNotNull(invalid);
        assertFalse(invalid.getBlockingErrors().toString(), invalid.isLoadable());
    }

    @Test
    public void paperStartupAliasResolvesToCanonicalRuntimeKey() {
        assertEquals(
                new NamespacedKey("iris", "moon"),
                IrisWorldGeneratorResolver.configuredWorldKey("world_iris_moon", "world")
        );
        assertEquals(
                new NamespacedKey("iris", "moon"),
                IrisWorldGeneratorResolver.configuredWorldKey("moon", "world")
        );
    }

    private static void writeValidPack(Path packRoot) throws Exception {
        Files.createDirectories(packRoot.resolve("dimensions"));
        Files.createDirectories(packRoot.resolve("regions"));
        Files.createDirectories(packRoot.resolve("biomes"));
        Files.writeString(
                packRoot.resolve("dimensions/main.json"),
                "{\"regions\":[\"region\"]}",
                StandardCharsets.UTF_8);
        Files.writeString(
                packRoot.resolve("regions/region.json"),
                "{\"landBiomes\":[\"biome\"]}",
                StandardCharsets.UTF_8);
        Files.writeString(
                packRoot.resolve("biomes/biome.json"),
                "{\"name\":\"Biome\"}",
                StandardCharsets.UTF_8);
    }
}

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
    public void startupValidationPublishesFingerprintBoundExactRootResults() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/IrisWorldGeneratorResolver.java"));
        int validateAll = source.indexOf("public void validateAllPacks()");
        int snapshot = source.indexOf("ServerConfigurator.computePackContentSnapshot(packsRoot)", validateAll);
        int perPackFingerprint = source.indexOf("contentSnapshot.packContents()", snapshot);
        int exactRootPublish = source.indexOf(
                "PackValidationRegistry.publish(packDirectory.toPath(), result, packFingerprint)",
                perPackFingerprint);

        assertTrue(validateAll >= 0);
        assertTrue(snapshot > validateAll);
        assertTrue(perPackFingerprint > snapshot);
        assertTrue(exactRootPublish > perPackFingerprint);
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

    @Test
    public void configuredWorldResolutionUsesOnlyFrozenWorldLocalSnapshot() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/IrisWorldGeneratorResolver.java"));
        int resolverStart = source.indexOf("private ChunkGenerator resolveFrozenWorldGenerator(");
        int resolverEnd = source.indexOf("private record FreshValidation", resolverStart);
        String resolver = source.substring(resolverStart, resolverEnd);

        int dimensionRoot = resolver.indexOf("IrisWorldStorage.requireFrozenDimensionRoot(");
        int currentPlatformRoot = resolver.indexOf("WorldCreatorCompat.persistentDimensionRoot(worldKey)");
        int layoutRefusal = resolver.indexOf(
                "Frozen Iris world storage does not match the current platform layout",
                currentPlatformRoot
        );
        int snapshotRoot = resolver.indexOf("IrisWorldStorage.requireFrozenPackRoot(dimensionRoot)");
        int validation = resolver.indexOf("requireSnapshotLoadable(snapshotRoot)");
        int exactLoad = resolver.indexOf(
                "IrisData.get(snapshotRoot).getDimensionLoader().load(id, false)"
        );
        int canonicalIdentity = resolver.indexOf(".platformIdentity(worldKey.toString())");
        int resolvedStorage = resolver.indexOf(".worldFolder(dimensionRoot)");

        assertTrue(dimensionRoot >= 0);
        assertTrue(currentPlatformRoot > dimensionRoot);
        assertTrue(layoutRefusal > currentPlatformRoot);
        assertTrue(snapshotRoot > layoutRefusal);
        assertTrue(validation > snapshotRoot);
        assertTrue(exactLoad > validation);
        assertTrue(canonicalIdentity > exactLoad);
        assertTrue(resolvedStorage > canonicalIdentity);
        assertFalse(resolver.contains("loadDimension("));
        assertFalse(resolver.contains("loadAnyDimension("));
        assertFalse(resolver.contains("replaceIntoWorld("));
        assertFalse(resolver.contains("installIntoWorld("));
    }

    @Test
    public void configuredWorldSnapshotFailureStopsStartupAndRethrows() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/IrisWorldGeneratorResolver.java"));
        int resolverStart = source.indexOf("public ChunkGenerator resolveDefaultWorldGenerator(");
        int resolverEnd = source.indexOf("private ChunkGenerator resolveFrozenWorldGenerator(", resolverStart);
        String resolver = source.substring(resolverStart, resolverEnd);

        int failureCapture = resolver.indexOf("catch (RuntimeException failure)");
        int report = resolver.indexOf("Iris.reportError(", failureCapture);
        int shutdown = resolver.indexOf("Bukkit.shutdown()", report);
        int rethrow = resolver.indexOf("throw failure", shutdown);

        assertTrue(failureCapture >= 0);
        assertTrue(report > failureCapture);
        assertTrue(shutdown > report);
        assertTrue(rethrow > shutdown);
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

package art.arcane.iris.core.tools;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisPersistentWorldCreationContractTest {
    @Test
    public void productionCreateFreezesIntoCurrentPlatformStorageBeforeWorldCreation() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/tools/IrisCreator.java")).replace("\r\n", "\n");
        int createStart = source.indexOf("private World createReserved(");
        int createEnd = source.indexOf("static Player createTeleportTarget(", createStart);
        String create = source.substring(createStart, createEnd);

        int dimensionRoot = create.indexOf("WorldCreatorCompat.persistentDimensionRoot(worldKey)");
        int levelRoot = create.indexOf("WorldCreatorCompat.persistentLevelRoot(worldKey)");
        int existingStorage = create.indexOf("Files.exists(storageRoot.toPath())");
        int freezePack = create.indexOf(".installIntoWorld(sender, resolvedDimension, dimensionRoot)");
        int persistentCreator = create.indexOf(".persistent(!studio && !benchmark)");
        int bukkitCreate = create.indexOf("INMS.get().createWorldAsync(wc, request)");
        int rollbackStorage = create.indexOf(
                "rollbackWorldCreation(worldKey, world, stagedGenerator, storageRoot, bukkitRegistered, failure)"
        );

        assertTrue(dimensionRoot >= 0);
        assertTrue(levelRoot > dimensionRoot);
        assertTrue(existingStorage > levelRoot);
        assertTrue(freezePack > existingStorage);
        assertTrue(persistentCreator > freezePack);
        assertTrue(bukkitCreate > persistentCreator);
        assertTrue(rollbackStorage > bukkitCreate);
        assertFalse(create.contains("copySeed"));
        assertFalse(create.contains("level.dat"));
    }

    @Test
    public void persistentCreatorPreservesConfiguredNameAndCanonicalIdentity() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/tools/IrisWorldCreator.java")).replace("\r\n", "\n");
        int createStart = source.indexOf("public WorldCreator create()");
        int createEnd = source.indexOf("private World.Environment findEnvironment()", createStart);
        String create = source.substring(createStart, createEnd);

        int persistentCreator = create.indexOf("WorldCreatorCompat.ofPersistentKey(worldKey)");
        int persistentStorage = create.indexOf("WorldCreatorCompat.persistentDimensionRoot(worldKey)");
        int canonicalIdentity = create.indexOf(".platformIdentity(worldKey.toString())");
        int configuredName = create.indexOf(".name(creator.name())");
        int exactPack = create.indexOf("new File(w.worldFolder(), \"iris/pack\")");

        assertTrue(persistentCreator >= 0);
        assertTrue(persistentStorage > persistentCreator);
        assertTrue(canonicalIdentity > persistentStorage);
        assertTrue(configuredName > canonicalIdentity);
        assertTrue(exactPack > configuredName);
    }

    @Test
    public void publicBukkitBackendRebuildKeepsExactFallbackWorldName() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/lifecycle/WorldLifecycleRequest.java"
        )).replace("\r\n", "\n");
        int rebuildStart = source.indexOf("public WorldCreator toWorldCreator()");
        int rebuildEnd = source.indexOf("return creator;", rebuildStart);
        String rebuild = source.substring(rebuildStart, rebuildEnd);

        assertTrue(rebuild.contains("WorldCreatorCompat.ofKey(worldKey, worldName)"));
        assertFalse(rebuild.contains("WorldCreatorCompat.ofKey(worldKey)"));
    }
}

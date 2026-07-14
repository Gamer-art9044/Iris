package art.arcane.iris.modded.command;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisModdedStructureCommandTest {
    @Test
    public void gotoStructureSupportsIrisAndNativeRegistryTargets() throws IOException {
        String source = source("IrisModdedCommands.java");

        assertTrue(source.contains("IrisStructureLocator.isPlaced(engine, key)"));
        assertTrue(source.contains("registry.get(identifier)"));
        assertTrue(source.contains("getPlacementsForStructure(holder)"));
        assertTrue(source.contains("generator.findNearestMapStructure("));
        assertTrue(source.contains("NATIVE_STRUCTURE_LOCATE_RADIUS = 100"));
        assertTrue(source.contains("HolderSet.direct(target.holder())"));
        assertTrue(source.contains("boolean teleported = player.teleportTo("));
        assertTrue(source.contains("combineStructureKeys(irisKeys, nativeKeys)"));
        assertTrue(source.contains("irisGenerator.isNativeStructureReachable(holder)"));
        assertTrue(source.contains("LocateStatus.SEARCH_LIMIT_REACHED"));
        assertTrue(source.contains("the density search safety limit was reached"));
        assertTrue(source.contains("int targetX = result.originX()"));
        assertTrue(source.contains("int targetY = result.baseY() + 2"));
        assertTrue(source.contains("int targetZ = result.originZ()"));
        assertFalse(source.contains("at[0] + 8"));
        assertFalse(source.contains("at[2] + 8"));
    }

    @Test
    public void generatorLocatePrefersIrisPlacementsAndRejectsDormantNativeStarts() throws IOException {
        String source = moddedSource("IrisModdedChunkGenerator.java");

        assertTrue(source.contains("public Pair<BlockPos, Holder<Structure>> findNearestMapStructure("));
        assertTrue(source.contains("findNearestIrisStructure("));
        assertTrue(source.contains("filterReachableNativeStructures("));
        assertTrue(source.contains("IrisStructureLocator.suppressesVanilla(current, key)"));
        assertTrue(source.contains("structureBiomeSource.isStructureReachable(holder)"));
        assertTrue(source.contains("LocateStatus.SEARCH_LIMIT_REACHED"));
        assertTrue(source.contains("new BlockPos(result.originX(), result.baseY(), result.originZ())"));
    }

    @Test
    public void structureVerificationNoLongerClaimsNativeGenerationIsMissing() throws IOException {
        String source = source("ModdedStructureCommands.java");

        assertTrue(source.contains("verifyTree(\"verify\")"));
        assertTrue(source.contains("verifyTree(\"locateall\")"));
        assertTrue(source.contains("IrisModdedCommands.verifyStructures("));
        assertFalse(source.contains("Vanilla structure locate is meaningless here"));
    }

    private String source(String fileName) throws IOException {
        Path source = commonSourceRoot().resolve("art/arcane/iris/modded/command/")
                .resolve(fileName)
                .normalize();
        return Files.readString(source);
    }

    private String moddedSource(String fileName) throws IOException {
        Path source = commonSourceRoot().resolve("art/arcane/iris/modded/")
                .resolve(fileName)
                .normalize();
        return Files.readString(source);
    }

    private Path commonSourceRoot() {
        String configuredRoot = System.getProperty("iris.moddedCommonSources");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Path.of(configuredRoot);
        }
        return Path.of(System.getProperty("user.dir"))
                .resolve("../modded-common/src/main/java")
                .normalize();
    }
}

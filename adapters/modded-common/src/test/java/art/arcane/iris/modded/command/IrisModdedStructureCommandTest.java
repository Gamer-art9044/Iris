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
        assertFalse(source.contains("NativeStructureLocateCapability"));
        assertTrue(source.contains("boolean teleported = player.teleportTo("));
        assertTrue(source.contains("combineStructureKeys(irisKeys, nativeKeys)"));
        assertTrue(source.contains("irisGenerator.isNativeStructureReachable(holder)"));
        assertTrue(source.contains("LocateStatus.SEARCH_LIMIT_REACHED"));
        assertTrue(source.contains("IRIS_MODDED_COMMANDS_UNABLE_LOCATE_IRIS_PLACED_STRUCTURE_DENSITY_SEARCH_SAFETY_LIMIT_WAS"));
        assertTrue(source.contains("int targetX = result.originX()"));
        assertTrue(source.contains("int targetY = result.baseY() + 2"));
        assertTrue(source.contains("int targetZ = result.originZ()"));
        assertFalse(source.contains("at[0] + 8"));
        assertFalse(source.contains("at[2] + 8"));
    }

    @Test
    public void generatorLocateUsesIrisOnlyForExplicitReplacement() throws IOException {
        String source = moddedSource("IrisModdedChunkGenerator.java");
        int methodStart = source.indexOf("private Pair<BlockPos, Holder<Structure>> findNearestIrisStructure(");
        int methodEnd = source.indexOf("private HolderSet<Structure> filterReachableNativeStructures(", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int unexploredGuard = method.indexOf("if (findUnexplored)");
        int registryLookup = method.indexOf("level.registryAccess().lookupOrThrow(Registries.STRUCTURE)");
        int policyResolution = method.indexOf("NativeStructureGenerationPolicy.resolve(current,");
        int replacementCheck = method.indexOf(
                "decision.status() != NativeStructureGenerationStatus.REPLACED_BY_IRIS", policyResolution);
        int irisLocate = method.indexOf("IrisStructureLocator.locate(", replacementCheck);

        assertTrue(unexploredGuard >= 0);
        assertTrue(registryLookup > unexploredGuard);
        assertTrue(policyResolution > registryLookup);
        assertTrue(replacementCheck > policyResolution);
        assertTrue(irisLocate > replacementCheck);
        assertTrue(method.contains("LocateStatus.SEARCH_LIMIT_REACHED"));
        assertTrue(method.contains("new BlockPos(result.originX(), result.baseY(), result.originZ())"));
        assertFalse(method.contains("NativeStructureLocateCapability"));
        assertTrue(source.contains("structureBiomeSource.isStructureReachable(holder)"));
        assertFalse(source.contains("isPaperUnavailable"));
    }

    @Test
    public void commandResolvesNativePolicyBeforeAnyVanillaAliasLookup() throws IOException {
        String source = source("IrisModdedCommands.java");
        int methodStart = source.indexOf("private static int gotoStructure(");
        int methodEnd = source.indexOf("private static void locateIrisStructure(", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int nativeResolution = method.indexOf("resolveNativeStructure(source, level, engine, key)");
        int genericIrisLookup = method.indexOf("IrisStructureLocator.isPlaced(engine, key)", nativeResolution);
        int policyResolution = method.indexOf("NativeStructureGenerationPolicy.resolve(engine, target.key(), false)", genericIrisLookup);
        int replacementCheck = method.indexOf(
                "decision.status() == NativeStructureGenerationStatus.REPLACED_BY_IRIS", policyResolution);
        int replacementLocate = method.indexOf("locateIrisStructure(source, level, engine, player, target.key())",
                replacementCheck);

        assertTrue(nativeResolution >= 0);
        assertTrue(genericIrisLookup > nativeResolution);
        assertTrue(policyResolution > genericIrisLookup);
        assertTrue(replacementCheck > policyResolution);
        assertTrue(replacementLocate > replacementCheck);
    }

    @Test
    public void verifyResolvesRegisteredNativeBeforeGenericIrisAliases() throws IOException {
        String source = source("IrisModdedCommands.java");
        int methodStart = source.indexOf("private static int verifyStructure(");
        int methodEnd = source.indexOf("private static Optional<NativeStructureTarget> resolveNativeStructure(",
                methodStart);
        String method = source.substring(methodStart, methodEnd);
        int nativeResolution = method.indexOf("resolveNativeStructure(source, level, engine, key)");
        int genericIrisLookup = method.indexOf("IrisStructureLocator.isPlaced(engine, key)", nativeResolution);

        assertTrue(nativeResolution >= 0);
        assertTrue(genericIrisLookup > nativeResolution);
        assertTrue(method.contains("NativeStructureAvailability.IRIS_SUPPRESSED"));
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

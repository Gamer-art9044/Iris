package art.arcane.iris.modded.command;

import art.arcane.iris.nativegen.NativeStructureLocateResults;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IrisModdedStructureCommandTest {
    @Test
    public void mixedUnexploredLocateReferencesOnlyTheSelectedProvider() {
        BlockPos origin = BlockPos.ZERO;
        Pair<BlockPos, String> irisNear = Pair.of(new BlockPos(4, 70, 0), "iris");
        Pair<BlockPos, String> nativeFar = Pair.of(new BlockPos(8, 70, 0), "native");
        AtomicInteger irisReferences = new AtomicInteger();
        AtomicInteger nativeReferences = new AtomicInteger();

        Pair<BlockPos, String> irisSelected = NativeStructureLocateResults.selectAndReference(
                origin,
                irisNear, () -> irisReferences.incrementAndGet(),
                nativeFar, () -> nativeReferences.incrementAndGet());

        assertSame(irisNear, irisSelected);
        assertEquals(1, irisReferences.get());
        assertEquals(0, nativeReferences.get());

        Pair<BlockPos, String> nativeNear = Pair.of(new BlockPos(2, 70, 0), "native");
        Pair<BlockPos, String> nativeSelected = NativeStructureLocateResults.selectAndReference(
                origin,
                irisNear, () -> irisReferences.incrementAndGet(),
                nativeNear, () -> nativeReferences.incrementAndGet());

        assertSame(nativeNear, nativeSelected);
        assertEquals(1, irisReferences.get());
        assertEquals(1, nativeReferences.get());
    }

    @Test
    public void gotoStructureSupportsIrisAndNativeRegistryTargets() throws IOException {
        String source = source("ModdedLocateCommands.java");
        String suggestions = source("ModdedCommandSuggestions.java");

        assertTrue(source.contains("IrisStructureLocator.isPlaced(engine, key)"));
        assertTrue(source.contains("registry.get(identifier)"));
        assertTrue(source.contains("getPlacementsForStructure(holder)"));
        assertTrue(source.contains("generator.findNearestMapStructure("));
        assertTrue(source.contains("NATIVE_STRUCTURE_LOCATE_RADIUS = 100"));
        assertTrue(source.contains("HolderSet.direct(target.holder())"));
        assertFalse(source.contains("NativeStructureLocateCapability"));
        assertTrue(source.contains("boolean teleported = player.teleportTo("));
        assertTrue(suggestions.contains("combineStructureKeys(irisKeys, nativeKeys)"));
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
    public void generatorLocateUsesEveryIrisPlacedNativeStructure() throws IOException {
        String source = moddedSource("ModdedNativeStructureStage.java");
        int methodStart = source.indexOf("Pair<BlockPos, Holder<Structure>> findNearestIrisStructure(");
        int methodEnd = source.indexOf("HolderSet<Structure> filterReachableNativeStructures(", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int registryLookup = method.indexOf("level.registryAccess().lookupOrThrow(Registries.STRUCTURE)");
        int placedCheck = method.indexOf(
                "if (!IrisStructureLocator.hasNativePlacement(current, structureId))");
        int irisLocate = method.indexOf("NativeStructureLocatePersistence.search(", placedCheck);
        int nearestSelection = method.indexOf(
                "NativeStructureLocateResults.nearest(pos, predicted, nativeLocated)", irisLocate);
        int selectedVerification = method.indexOf("bestSearch.search().verify(bestResult)", nearestSelection);
        int selectedReference = method.indexOf(
                "selectedSearch.search().reference(selectedStart)", selectedVerification);

        assertTrue(registryLookup >= 0);
        assertTrue(placedCheck > registryLookup);
        assertTrue(irisLocate > placedCheck);
        assertTrue(nearestSelection > irisLocate);
        assertTrue(selectedVerification > nearestSelection);
        assertTrue(selectedReference > selectedVerification);
        assertTrue(method.contains("NativeStructureLocatePersistence.probe("));
        assertTrue(method.contains("findUnexplored"));
        assertTrue(method.contains("LocateStatus.SEARCH_LIMIT_REACHED"));
        assertTrue(method.contains("verified.ownership().locatorY()"));
        assertTrue(method.contains("selectedSearch.search().reference(selectedStart)"));
        assertTrue(method.contains("NativeStructureLocateResults.selectAndReference("));
        assertFalse(method.contains("NativeStructureLocateCapability"));
        assertTrue(source.contains("structureBiomeSource.isStructureReachable(holder)"));
        assertFalse(source.contains("isPaperUnavailable"));
        String generator = moddedSource("IrisModdedChunkGenerator.java");
        assertTrue(generator.contains("NativeStructureVanillaLocator.predict("));
        assertFalse(generator.contains("super.findNearestMapStructure(level, reachable"));
    }

    @Test
    public void commandResolvesNativePolicyBeforeAnyVanillaAliasLookup() throws IOException {
        String source = source("ModdedLocateCommands.java");
        int methodStart = source.indexOf("static int gotoStructure(");
        int methodEnd = source.indexOf("private static void locateIrisStructure(", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int nativeResolution = method.indexOf("resolveNativeStructure(source, level, engine, key)");
        int genericIrisLookup = method.indexOf("IrisStructureLocator.isPlaced(engine, key)", nativeResolution);
        int policyResolution = method.indexOf("NativeStructureGenerationPolicy.resolve(engine, target.key(), false)", genericIrisLookup);
        int replacementCheck = method.indexOf(
                "decision.status() == NativeStructureGenerationStatus.REPLACED_BY_IRIS", policyResolution);
        int replacementLocate = method.indexOf("runNativeStructureLocate(source, level, player, target)",
                replacementCheck);

        assertTrue(nativeResolution >= 0);
        assertTrue(genericIrisLookup > nativeResolution);
        assertTrue(policyResolution > genericIrisLookup);
        assertTrue(replacementCheck > policyResolution);
        assertTrue(replacementLocate > replacementCheck);
        assertTrue(method.contains("!IrisStructureLocator.hasNativePlacement(engine, target.key())"));
        assertTrue(method.contains("&& target.availability() != NativeStructureAvailability.AVAILABLE"));
    }

    @Test
    public void verifyResolvesRegisteredNativeBeforeGenericIrisAliases() throws IOException {
        String source = source("ModdedLocateCommands.java");
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

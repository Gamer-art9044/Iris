package art.arcane.iris.core.commands;

import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisStructureLocateCommandContractTest {
    @Test
    public void findReportsDensityLimitAndUsesExactLocatedOrigin() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandFindSource")));

        assertTrue(source.contains("LocateStatus.SEARCH_LIMIT_REACHED"));
        assertTrue(source.contains("the density search safety limit was reached"));
        assertTrue(source.contains("result.originX(), result.baseY(), result.originZ()"));
        assertFalse(source.contains("at[0] + 8"));
        assertFalse(source.contains("at[2] + 8"));
    }

    @Test
    public void findRoutesRegisteredReplacementThroughPersistedNativeLocate() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandFindSource")));
        int methodStart = source.indexOf("public void structure(");
        int methodEnd = source.indexOf("private static Structure resolveNativeStructure", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int nativeResolution = method.indexOf("resolveNativeStructure(structureKey)");
        int policyResolution = method.indexOf("NativeStructureGenerationPolicy.resolve(", nativeResolution);
        int replacementCheck = method.indexOf(
                "decision.status() == NativeStructureGenerationStatus.REPLACED_BY_IRIS", policyResolution);
        int genericIrisLookup = method.indexOf(
                "nativeStructure == null && IrisStructureLocator.isPlaced(e, structureKey)", replacementCheck);
        int replacementLocate = method.indexOf("final boolean replacementLocate = irisReplacement", genericIrisLookup);
        int nativeLocate = method.indexOf("targetWorld.locateNearestStructure(", genericIrisLookup);
        assertTrue(nativeResolution >= 0);
        assertTrue(policyResolution > nativeResolution);
        assertTrue(replacementCheck > policyResolution);
        assertTrue(genericIrisLookup > replacementCheck);
        assertTrue(replacementLocate > genericIrisLookup);
        assertTrue(nativeLocate > policyResolution);
        assertTrue(method.contains("irisReplacement && !IrisStructureLocator.hasNativePlacement"));
        assertTrue(method.contains("!replacementLocate && !explicitNativePlacement"));
        assertTrue(method.contains("&& !StructureReachability.isReachable"));
        assertTrue(method.contains("decision.status() != NativeStructureGenerationStatus.REPLACED_BY_IRIS"));
        assertFalse(method.contains("NativeStructureLocateCapability"));
    }

    @Test
    public void findNativePolicyMessagesMatchModdedDiagnostics() {
        assertEquals(
                "Native structure minecraft:village_plains is disabled by this dimension's importedStructures settings.",
                NativeStructureGenerationPolicy.generationStatusMessage(
                        "minecraft:village_plains", NativeStructureGenerationStatus.DISABLED_BY_PACK));
        assertEquals(
                "Native structure minecraft:ancient_city is replaced by an Iris placement in this pack and locates through that explicit replacement.",
                NativeStructureGenerationPolicy.generationStatusMessage(
                        "minecraft:ancient_city", NativeStructureGenerationStatus.REPLACED_BY_IRIS));
        assertEquals(
                "Native structure minecraft:stronghold generates natively.",
                NativeStructureGenerationPolicy.generationStatusMessage(
                        "minecraft:stronghold", NativeStructureGenerationStatus.GENERATE_NATIVE));
    }

    @Test
    public void structureVerifyReportsDensityLimitAndUsesExactLocatedOrigin() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandStructureSource")));

        assertTrue(source.contains("[iris-search-limit]"));
        assertTrue(source.contains("density searches safety-limited"));
        assertTrue(source.contains("result.originX() + \",\" + result.baseY() + \",\" + result.originZ()"));
        assertFalse(source.contains("at[0] + 8"));
        assertFalse(source.contains("at[2] + 8"));
    }

    @Test
    public void structureVerifyPartitionsPolicyBeforeNativeReachability() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandStructureSource")));
        int methodStart = source.indexOf("private void runVerification(");
        int methodEnd = source.indexOf("private void sendVerificationMessages(", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int policyResolution = method.indexOf("NativeStructureGenerationPolicy.resolve(engine, keyName, false)");
        int nativeRequirement = method.indexOf(
                "requiresNativeReachability |= !IrisStructureLocator.isPlaced", policyResolution);
        int reachabilityGuard = method.indexOf("if (requiresNativeReachability)", nativeRequirement);
        int reachabilityLookup = method.indexOf("StructureReachability.reachableKeys(engine)", reachabilityGuard);

        assertTrue(policyResolution >= 0);
        assertTrue(nativeRequirement > policyResolution);
        assertTrue(reachabilityGuard > nativeRequirement);
        assertTrue(reachabilityLookup > reachabilityGuard);
    }
}

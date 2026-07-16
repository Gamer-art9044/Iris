package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.nativegen.NativeStructureLocateResults;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class IrisChunkGeneratorMonumentLocateContractTest {
    @Test
    public void nativeLocateAllowsExplicitReplacementBeforeNativeCapabilityGate() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int findStart = source.indexOf("findNearestMapStructure(ServerLevel level");
        assertTrue(findStart >= 0);
        int irisHelperStart = source.indexOf("private Pair<BlockPos, Holder<Structure>> findNearestIrisStructure(", findStart);
        int filterStart = source.indexOf("private HolderSet<Structure> filterReachableStructures", irisHelperStart);
        assertTrue(irisHelperStart > findStart);
        assertTrue(filterStart > irisHelperStart);
        String outerMethod = source.substring(findStart, irisHelperStart);
        String irisHelper = source.substring(irisHelperStart, filterStart);
        int policyResolution = irisHelper.indexOf("NativeStructureGenerationPolicy.resolve(engine,");
        int unexploredGuard = irisHelper.indexOf("if (findUnexplored)");
        int registryLookup = irisHelper.indexOf("level.registryAccess().lookupOrThrow(Registries.STRUCTURE)");
        int replacementCheck = irisHelper.indexOf(
                "decision.status() != NativeStructureGenerationStatus.REPLACED_BY_IRIS");
        int irisLocate = irisHelper.indexOf("IrisStructureLocator.locate(", replacementCheck);
        int searchLimit = irisHelper.indexOf("LocateStatus.SEARCH_LIMIT_REACHED", irisLocate);
        int limitSkip = irisHelper.indexOf("continue;", searchLimit);
        int nativeFilter = outerMethod.indexOf("filterReachableStructures(level, holders)");
        int delegateLocate = outerMethod.indexOf("delegate.findNearestMapStructure(level, reachable");
        int nearestSelection = outerMethod.indexOf(
                "NativeStructureLocateResults.nearest(pos, irisPlaced, nativeLocated)");
        int reachabilityStart = source.indexOf("private Set<String> reachableStructureKeys", filterStart);
        assertTrue(reachabilityStart > filterStart);
        String filterMethod = source.substring(filterStart, reachabilityStart);
        int monumentReject = filterMethod.indexOf("if (NativeStructureLocateCapability.isPaperUnavailable(key)");
        int rejectContinue = filterMethod.indexOf("continue;", monumentReject);
        int emptyNativePartition = filterMethod.indexOf("if (candidates.isEmpty())", rejectContinue);
        int reachabilityLookup = filterMethod.indexOf("reachableStructureKeys(level)", emptyNativePartition);

        assertTrue(policyResolution >= 0);
        assertTrue(unexploredGuard >= 0);
        assertTrue(registryLookup > unexploredGuard);
        assertTrue(policyResolution > registryLookup);
        assertTrue(replacementCheck > policyResolution);
        assertTrue(irisLocate > replacementCheck);
        assertTrue(searchLimit > irisLocate);
        assertTrue(limitSkip > searchLimit);
        assertTrue(nativeFilter >= 0);
        assertTrue(delegateLocate > nativeFilter);
        assertTrue(nearestSelection > delegateLocate);
        assertTrue(monumentReject >= 0);
        assertTrue(rejectContinue > monumentReject);
        assertTrue(emptyNativePartition > rejectContinue);
        assertTrue(reachabilityLookup > emptyNativePartition);
        assertFalse(irisHelper.contains("NativeStructureLocateCapability.isPaperUnavailable(structureId)"));
        assertTrue(irisHelper.contains("new BlockPos(result.originX(), result.baseY(), result.originZ())"));
    }

    @Test
    public void mixedLocateSelectsNearestProviderAndPrefersNativeOnTie() {
        BlockPos origin = BlockPos.ZERO;
        Pair<BlockPos, Holder<Structure>> irisNear = Pair.of(new BlockPos(4, 70, 0), null);
        Pair<BlockPos, Holder<Structure>> nativeFar = Pair.of(new BlockPos(8, 70, 0), null);
        Pair<BlockPos, Holder<Structure>> nativeNear = Pair.of(new BlockPos(2, 70, 0), null);
        Pair<BlockPos, Holder<Structure>> nativeTie = Pair.of(new BlockPos(0, 70, 4), null);

        assertSame(irisNear, NativeStructureLocateResults.nearest(origin, irisNear, nativeFar));
        assertSame(nativeNear, NativeStructureLocateResults.nearest(origin, irisNear, nativeNear));
        assertSame(nativeTie, NativeStructureLocateResults.nearest(origin, irisNear, nativeTie));
    }

    @Test
    public void stiltSupportUsesPlacedSolidOccupancyWithoutSnapshotDifferenceRequirement() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nativeStructurePostProcessorSource")));
        int placement = source.indexOf("start.placeInChunk(world, structureManager, generator");
        int stiltPlacement = source.indexOf("placeStilts(world, area, structureId, start", placement);
        int occupancyCheck = source.indexOf("if (state.isSolid())", stiltPlacement);
        int terrainFloor = source.indexOf("y > terrainY", stiltPlacement);

        assertTrue(placement >= 0);
        assertTrue(stiltPlacement > placement);
        assertTrue(occupancyCheck > stiltPlacement);
        assertTrue(terrainFloor > stiltPlacement);
        assertFalse(source.contains("state.equals("));
        assertFalse(source.contains("snapshot.states"));
    }

    @Test
    public void verticalPlacementMovesPiecesMonumentChildrenJigsawJunctionsAndCachedBoundsTogether() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nativeStructurePostProcessorSource")));
        int placementStart = source.indexOf("public static int applyVerticalPlacement");
        int shiftStart = source.indexOf("public static int applyVerticalShift", placementStart);
        int alignmentStart = source.indexOf("static int alignOceanMonumentToSeaLevel", shiftStart);
        int moveStart = source.indexOf("private static void moveStructureStart", alignmentStart);
        int movePieceStart = source.indexOf("private static void moveStructurePiece", moveStart);
        int monumentChildrenStart = source.indexOf("static List<StructurePiece> monumentChildPieces", movePieceStart);

        assertTrue(placementStart >= 0);
        assertTrue(shiftStart > placementStart);
        assertTrue(alignmentStart > shiftStart);
        assertTrue(moveStart > alignmentStart);
        assertTrue(movePieceStart > moveStart);
        assertTrue(monumentChildrenStart > movePieceStart);
        String placementMethod = source.substring(placementStart, shiftStart);
        String shiftMethod = source.substring(shiftStart, alignmentStart);
        String moveMethod = source.substring(moveStart, movePieceStart);
        String movePieceMethod = source.substring(movePieceStart, monumentChildrenStart);

        assertTrue(placementMethod.contains("return alignOceanMonumentToSeaLevel("));
        assertTrue(placementMethod.contains("return applyVerticalShift("));
        assertTrue(shiftMethod.contains("StructureVerticalBounds.clampOffset"));
        assertTrue(shiftMethod.contains("moveStructureStart(start, bounds, offsetY)"));
        assertTrue(moveMethod.contains("moveStructurePiece(piece, offsetY)"));
        assertTrue(moveMethod.contains("cachedBounds.move(0, offsetY, 0)"));
        assertTrue(movePieceMethod.contains("piece.move(0, offsetY, 0)"));
        assertTrue(movePieceMethod.contains("child.move(0, offsetY, 0)"));
        assertTrue(movePieceMethod.contains("junction.getSourceGroundY() + offsetY"));
    }
}

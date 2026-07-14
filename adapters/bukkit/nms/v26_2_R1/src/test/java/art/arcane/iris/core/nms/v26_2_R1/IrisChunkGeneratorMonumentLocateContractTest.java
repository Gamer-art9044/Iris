package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class IrisChunkGeneratorMonumentLocateContractTest {
    @Test
    public void irisPlacementRunsBeforeExactNativeMonumentIsRemovedFromDelegateLookup() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int findStart = source.indexOf("findNearestMapStructure(ServerLevel level");
        assertTrue(findStart >= 0);
        int filterStart = source.indexOf("private HolderSet<Structure> filterReachableStructures", findStart);
        assertTrue(filterStart > findStart);
        String findMethod = source.substring(findStart, filterStart);
        int irisLocate = findMethod.indexOf("IrisStructureLocator.locate(");
        int searchLimit = findMethod.indexOf("LocateStatus.SEARCH_LIMIT_REACHED", irisLocate);
        int limitSkip = findMethod.indexOf("continue;", searchLimit);
        int nativeFilter = findMethod.indexOf("filterReachableStructures(level, holders)");
        int delegateLocate = findMethod.indexOf("delegate.findNearestMapStructure(level, reachable");
        int reachabilityStart = source.indexOf("private Set<String> reachableStructureKeys", filterStart);
        assertTrue(reachabilityStart > filterStart);
        String filterMethod = source.substring(filterStart, reachabilityStart);
        int monumentReject = filterMethod.indexOf("if (NATIVE_MONUMENT_KEY.equals(key)");
        int rejectContinue = filterMethod.indexOf("continue;", monumentReject);

        assertTrue(irisLocate >= 0);
        assertTrue(searchLimit > irisLocate);
        assertTrue(limitSkip > searchLimit);
        assertTrue(nativeFilter > limitSkip);
        assertTrue(delegateLocate > nativeFilter);
        assertTrue(monumentReject >= 0);
        assertTrue(rejectContinue > monumentReject);
        assertTrue(findMethod.contains("new BlockPos(result.originX(), result.baseY(), result.originZ())"));
    }

    @Test
    public void stiltSupportUsesPlacedSolidOccupancyWithoutSnapshotDifferenceRequirement() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nativeStructurePostProcessorSource")));
        int placement = source.indexOf("start.placeInChunk(world, structureManager, generator");
        int stiltPlacement = source.indexOf("placeStilts(world, area, structureId, start", placement);
        int occupancyCheck = source.indexOf("if (state.isSolid())", stiltPlacement);

        assertTrue(placement >= 0);
        assertTrue(stiltPlacement > placement);
        assertTrue(occupancyCheck > stiltPlacement);
        assertFalse(source.contains("state.equals("));
        assertFalse(source.contains("snapshot.states"));
    }

    @Test
    public void verticalShiftMovesPiecesJigsawJunctionsAndCachedBoundsTogether() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nativeStructurePostProcessorSource")));
        int method = source.indexOf("public static int applyVerticalShift");
        int clamp = source.indexOf("StructureVerticalBounds.clampOffset", method);
        int pieceMove = source.indexOf("piece.move(0, offsetY, 0)", clamp);
        int junctionMove = source.indexOf("junction.getSourceGroundY() + offsetY", pieceMove);
        int boundsMove = source.indexOf("bounds.move(0, offsetY, 0)", junctionMove);

        assertTrue(method >= 0);
        assertTrue(clamp > method);
        assertTrue(pieceMove > clamp);
        assertTrue(junctionMove > pieceMove);
        assertTrue(boundsMove > junctionMove);
    }
}

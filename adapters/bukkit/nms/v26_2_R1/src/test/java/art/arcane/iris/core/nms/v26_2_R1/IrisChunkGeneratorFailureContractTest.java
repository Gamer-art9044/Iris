package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisChunkGeneratorFailureContractTest {
    @Test
    public void structureLocateDoesNotCatchAndFallThroughToAnotherImplementation() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int locateStart = source.indexOf("public @Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure");
        int locateEnd = source.indexOf("private HolderSet<Structure> filterReachableStructures", locateStart);
        String locate = source.substring(locateStart, locateEnd);
        int reachabilityStart = source.indexOf("private Set<String> reachableStructureKeys");
        int reachabilityEnd = source.indexOf("protected MapCodec", reachabilityStart);
        String reachability = source.substring(reachabilityStart, reachabilityEnd);

        assertTrue(locate.contains("reached its safety limit"));
        assertTrue(locate.contains("unregistered structure holder"));
        assertFalse(locate.contains("catch (Throwable"));
        assertFalse(locate.contains("IrisLogging.reportError"));
        assertFalse(reachability.contains("catch (Throwable"));
        assertFalse(reachability.contains("reachable = Set.of()"));
    }

    @Test
    public void structureGenerationAbortsInsteadOfLoggingAndContinuing() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int adjustmentStart = source.indexOf("private void adjustGeneratedStructures");
        int adjustmentEnd = source.indexOf("public ChunkGeneratorStructureState createState", adjustmentStart);
        String adjustment = source.substring(adjustmentStart, adjustmentEnd);
        int placementStart = source.indexOf("private void placeVanillaStructures");
        int placementEnd = source.indexOf("private void placeVanillaStructure(", placementStart + 1);
        String placement = source.substring(placementStart, placementEnd);

        assertTrue(adjustment.contains("NativeStructureGenerationException.failure("));
        assertTrue(adjustment.contains("\"vertical adjustment\""));
        assertFalse(adjustment.contains("IrisLogging.reportError"));
        assertTrue(placement.contains("\"resolution\""));
        assertTrue(placement.contains("\"terrain integration\""));
        assertTrue(placement.contains("\"vegetation cleanup\""));
        assertTrue(placement.contains("\"placement\""));
        assertTrue(placement.contains("because structure generation is disabled outside the pack"));
        assertTrue(placement.contains("prepareSurfaceStructures"));
        assertTrue(placement.contains("clearIntersectingVegetation"));
        assertTrue(placement.indexOf("prepareSurfaceStructures")
                < placement.indexOf("clearIntersectingVegetation"));
        assertTrue(placement.indexOf("clearIntersectingVegetation")
                < placement.indexOf("for (NativePlacementGroup group"));
        assertFalse(placement.contains("IrisLogging.reportError"));
    }

    @Test
    public void heightmapRuntimeReadsAreGenerationLeased() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));

        assertTrue(source.contains("engine.acquireGenerationLease(\"bukkit_nms_heightmaps\")"));
        assertTrue(source.contains("engine.acquireGenerationLease(\"bukkit_nms_base_height\")"));
        assertTrue(source.contains("engine.acquireGenerationLease(\"bukkit_nms_base_column\")"));
        assertTrue(source.contains("catch (GenerationSessionException e)"));
    }
}

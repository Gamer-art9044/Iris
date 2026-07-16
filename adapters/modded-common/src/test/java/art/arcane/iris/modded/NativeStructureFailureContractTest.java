package art.arcane.iris.modded;

import art.arcane.iris.nativegen.NativeStructureGenerationException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativeStructureFailureContractTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void structureLocateDoesNotCatchAndFallThroughToAnotherImplementation() throws IOException {
        Path sourcePath = Path.of(System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedChunkGenerator.java");
        String source = Files.readString(sourcePath);
        int locateStart = source.indexOf("public Pair<BlockPos, Holder<Structure>> findNearestMapStructure");
        int locateEnd = source.indexOf("public boolean isNativeStructureReachable", locateStart);
        String locate = source.substring(locateStart, locateEnd);
        int filterStart = source.indexOf("private HolderSet<Structure> filterReachableNativeStructures");
        int filterEnd = source.indexOf("private ServerLevel boundLevel", filterStart);
        String filter = source.substring(filterStart, filterEnd);

        assertTrue(locate.contains("Engine current = engine();"));
        assertFalse(locate.contains("catch (Throwable"));
        assertFalse(locate.contains("return null;\n        } catch"));
        assertTrue(filter.contains("unregistered structure holder"));
        assertFalse(filter.contains("catch (Throwable"));
        assertFalse(filter.contains("failed closed"));
    }

    @Test
    public void globalStructureDisableRefusesGeneratorBinding() {
        IrisModdedChunkGenerator.requireGlobalStructureGeneration(true, "overworld:overworld");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> IrisModdedChunkGenerator.requireGlobalStructureGeneration(
                        false, "overworld:overworld"));

        assertTrue(error.getMessage().contains("overworld:overworld"));
        assertTrue(error.getMessage().contains("generate-structures=false"));
        assertTrue(error.getMessage().contains("importedStructures.disabled"));
    }

    @Test
    public void structureTerrainPreparationPrecedesVegetationAndPlacement() throws IOException {
        Path sourcePath = Path.of(System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedChunkGenerator.java");
        String source = Files.readString(sourcePath);
        int placementStart = source.indexOf("private void placeVanillaStructures");
        int placementEnd = source.indexOf("private static String nativeStructureBatchContext", placementStart);
        String placement = source.substring(placementStart, placementEnd);

        assertTrue(placement.contains("\"terrain integration\""));
        assertTrue(placement.contains("prepareSurfaceStructures"));
        assertTrue(placement.contains("clearIntersectingVegetation"));
        assertTrue(placement.indexOf("prepareSurfaceStructures")
                < placement.indexOf("clearIntersectingVegetation"));
        assertTrue(placement.indexOf("clearIntersectingVegetation")
                < placement.indexOf("for (NativePlacementGroup group"));
    }

    @Test
    public void structureFailurePreservesPhaseIdentityChunkAndCause() {
        IllegalArgumentException cause = new IllegalArgumentException("broken placement");
        NativeStructureGenerationException error = NativeStructureGenerationException.failure(
                "placement", "minecraft:monument", 12, -8, cause);

        assertSame(cause, error.getCause());
        assertTrue(error.getMessage().contains("placement"));
        assertTrue(error.getMessage().contains("minecraft:monument"));
        assertTrue(error.getMessage().contains("12,-8"));
        assertTrue(error.getMessage().contains("aborted"));
    }
}

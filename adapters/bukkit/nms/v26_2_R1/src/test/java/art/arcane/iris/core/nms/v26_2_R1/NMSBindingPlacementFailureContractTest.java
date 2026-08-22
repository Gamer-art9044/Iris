package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NMSBindingPlacementFailureContractTest {
    @Test
    public void structurePlacementPropagatesRuntimeFailuresWithContext() throws IOException {
        Path chunkGeneratorSource = Path.of(System.getProperty("iris.nmsChunkGeneratorSource"));
        String source = Files.readString(chunkGeneratorSource.resolveSibling("NMSBinding.java")).replace("\r\n", "\n");
        int methodStart = source.indexOf("public int[] placeStructure(");
        int methodEnd = source.indexOf("\n    @Override", methodStart + 1);
        String method = source.substring(methodStart, methodEnd);
        int catchStart = method.indexOf("catch (RuntimeException e)");

        assertTrue(catchStart >= 0);
        String failurePath = method.substring(catchStart);
        assertTrue(failurePath.contains("throw NativeStructureGenerationException.failure("));
        assertTrue(failurePath.contains("\"capture placement\", structureKey, chunkX, chunkZ, e"));
        assertFalse(failurePath.contains("return null;"));
        assertFalse(failurePath.contains("catch (Throwable"));
    }
}

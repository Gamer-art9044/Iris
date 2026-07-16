package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedStructurePlacementFailureContractTest {
    @Test
    public void structurePlacementPropagatesRuntimeFailuresWithContext() throws IOException {
        Path sourcePath = Path.of(System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/ModdedStructureHooks.java");
        String source = Files.readString(sourcePath);
        int methodStart = source.indexOf("public int[] placeStructure(");
        int methodEnd = source.indexOf("\n    @Override", methodStart + 1);
        String method = source.substring(methodStart, methodEnd);
        int catchStart = method.indexOf("catch (RuntimeException error)");

        assertTrue(catchStart >= 0);
        String failurePath = method.substring(catchStart);
        assertTrue(failurePath.contains("throw NativeStructureGenerationException.failure("));
        assertTrue(failurePath.contains("\"capture placement\", structureKey, chunkX, chunkZ, error"));
        assertFalse(failurePath.contains("return null;"));
        assertFalse(failurePath.contains("catch (Throwable"));
    }
}

package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NMSBindingStructureFailureContractTest {
    @Test
    public void structureRegistryHooksThrowInsteadOfReturningEmptyOnFailure() throws IOException {
        Path chunkGeneratorSource = Path.of(System.getProperty("iris.nmsChunkGeneratorSource"));
        String source = Files.readString(chunkGeneratorSource.resolveSibling("NMSBinding.java")).replace("\r\n", "\n");
        List<String> methodNames = List.of(
                "getStructureKeys",
                "getStructureSetKeys",
                "getReachableStructureKeys",
                "getStructureBiomeKeys",
                "getPossibleBiomeKeys");

        for (String methodName : methodNames) {
            String method = methodSource(source, methodName);
            assertTrue(methodName + " must preserve failure context", method.contains("throw new IllegalStateException("));
            assertTrue(methodName + " must retain its root cause", method.contains(", e);"));
            assertFalse(methodName + " must not log and return an empty registry result",
                    method.contains("IrisLogging.reportError"));
            assertFalse(methodName + " must not catch fatal JVM errors", method.contains("catch (Throwable"));
        }
    }

    private static String methodSource(String source, String methodName) {
        int start = source.indexOf("public KList<String> " + methodName + "(");
        int end = source.indexOf("\n    @Override", start + 1);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Unable to find NMS binding method " + methodName);
        }
        return source.substring(start, end);
    }
}

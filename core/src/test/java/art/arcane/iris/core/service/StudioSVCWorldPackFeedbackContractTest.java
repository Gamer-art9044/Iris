package art.arcane.iris.core.service;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class StudioSVCWorldPackFeedbackContractTest {
    @Test
    public void persistentPackCopyUsesLocalizedStyledOperatorFeedback() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/service/StudioSVC.java"
        )).replace("\r\n", "\n");

        assertEquals(1, occurrences(source, "STUDIO_S_V_C_PACK_COPY_REQUIRES_ASYNC_THREAD"));
        assertEquals(2, occurrences(source, "STUDIO_S_V_C_PACK_INSTALL_FAILED"));
        assertFalse(source.contains("sender.sendMessage(\"Iris refused to copy a pack"));
        assertFalse(source.contains("sender.sendMessage(\"Failed to install studio pack"));
    }

    private static int occurrences(String value, String match) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(match, offset)) >= 0) {
            count++;
            offset += match.length();
        }
        return count;
    }
}

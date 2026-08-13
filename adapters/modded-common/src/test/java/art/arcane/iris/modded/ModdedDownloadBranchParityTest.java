package art.arcane.iris.modded;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedDownloadBranchParityTest {
    private static final List<String> DOWNLOAD_SOURCES = List.of(
            "art/arcane/iris/modded/command/ModdedWorldCommands.java",
            "art/arcane/iris/modded/command/ModdedStudioCommands.java",
            "art/arcane/iris/modded/command/ModdedCommandTree.java");

    @Test
    public void implicitAndExplicitDownloadsShareTheCoreDefaultBranch() throws Exception {
        for (String source : DOWNLOAD_SOURCES) {
            Path path = Path.of(System.getProperty("iris.moddedCommonSources"), source);
            String text = Files.readString(path);
            assertFalse(source + " must not hardcode a \"master\" download branch",
                    text.contains("\"master\""));
            assertFalse(source + " must not hardcode a \"stable\" download branch",
                    text.contains("\"stable\""));
            assertTrue(source + " must use PackDownloader.DEFAULT_BRANCH",
                    text.contains("PackDownloader.DEFAULT_BRANCH"));
        }
    }
}

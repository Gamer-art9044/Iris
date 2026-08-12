package art.arcane.iris.core.service;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioSVCPackDownloadContractTest {
    @Test
    public void downloadsRequireManualRestartWithoutMutatingLiveDatapacks() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/service/StudioSVC.java"));
        int mutationStart = source.indexOf("private void runPackMutation(");
        int mutationEnd = source.indexOf("private boolean finishStandalonePackMutation(", mutationStart);
        int finishEnd = source.indexOf("private void runOffPrimaryThread(", mutationEnd);
        String downloadMutation = source.substring(mutationStart, finishEnd);

        assertTrue(downloadMutation.contains("Restart the server before using the downloaded Iris pack."));
        assertFalse(downloadMutation.contains("ServerConfigurator.restart()"));
        assertFalse(downloadMutation.contains("installDataPacksIfChanged"));
    }
}

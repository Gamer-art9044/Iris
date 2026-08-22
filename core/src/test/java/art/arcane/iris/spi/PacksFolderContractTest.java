package art.arcane.iris.spi;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PacksFolderContractTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void defaultPacksFolderIsDataFolderPacks() {
        File data = temporaryFolder.getRoot();
        IrisPlatform platform = mock(IrisPlatform.class, CALLS_REAL_METHODS);
        when(platform.dataFolder()).thenReturn(data);

        assertEquals(new File(data, "packs"), platform.packsFolder());
        assertEquals(new File(data, "packs" + File.separator + "overworld"), platform.packsFolderNoCreate("overworld"));
    }

    /**
     * Source guard: nothing outside the SPI defaults and platform overrides may spell the packs
     * root by hand. New code goes through packsFolder()/packsFolderNoCreate().
     */
    @Test
    public void noCallSiteResolvesThePacksRootByHand() throws Exception {
        List<Path> roots = List.of(
                Path.of("src/main/java"),
                Path.of("../adapters/bukkit/plugin/src/main/java"));
        for (Path root : roots) {
            try (var stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file);
                    assertFalse(file + " must use packsFolder() instead of dataFolder(\"packs\")",
                            text.contains("dataFolder(\"packs\")"));
                    assertFalse(file + " must use packsFolderNoCreate() instead of the WORKSPACE_NAME indirection",
                            text.contains("dataFolderNoCreate(StudioSVC.WORKSPACE_NAME"));
                }
            }
        }
    }
}

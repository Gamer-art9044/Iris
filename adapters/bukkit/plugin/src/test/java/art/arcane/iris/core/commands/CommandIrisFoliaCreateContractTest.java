package art.arcane.iris.core.commands;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandIrisFoliaCreateContractTest {
    @Test
    public void ordinaryFoliaCreateUsesTheSharedRuntimeCreationPath() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.commandIrisSource")));
        String create = source.substring(
                source.indexOf("    public void create("),
                source.indexOf("    @Director(", source.indexOf("    public void create("))
        );

        assertTrue(create.contains("IrisToolbelt.createWorld()"));
        assertTrue(create.contains(".studio(false)"));
        assertTrue(create.contains(".create();"));
        assertFalse(create.contains("J.isFolia()"));
        assertFalse(create.contains("ServerConfigurator.restart("));
    }

    @Test
    public void obsoleteFoliaStagingSurfaceIsRemoved() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.commandIrisSource")));

        assertFalse(source.contains("stageFoliaWorldCreation"));
        assertFalse(source.contains("writeCurrentPaperWorldData"));
        assertFalse(source.contains("COMMAND_IRIS_RUNTIME_WORLD_CREATION_IS_DISABLED_ON_FOLIA"));
    }
}

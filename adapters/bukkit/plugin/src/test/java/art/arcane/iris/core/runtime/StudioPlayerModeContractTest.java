package art.arcane.iris.core.runtime;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioPlayerModeContractTest {
    @Test
    public void studioEntryKeepsPlayersEligibleForNaturalSpawning() throws IOException {
        String plugin = Files.readString(Path.of("src/main/java/art/arcane/iris/Iris.java"));
        String commands = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/commands/CommandStudio.java"));

        assertFalse(plugin.contains("GameMode.SPECTATOR"));
        assertFalse(commands.contains("GameMode.SPECTATOR"));
        assertTrue(plugin.contains("GameMode.CREATIVE"));
        assertTrue(commands.contains("GameMode.CREATIVE"));
    }
}

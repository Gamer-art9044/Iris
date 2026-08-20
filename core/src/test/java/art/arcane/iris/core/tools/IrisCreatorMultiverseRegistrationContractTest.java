package art.arcane.iris.core.tools;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisCreatorMultiverseRegistrationContractTest {
    @Test
    public void bukkitAndMultiverseRegistrationShareOneConfiguredName() throws IOException {
        String source = compact(Files.readString(Path.of(System.getProperty("iris.irisCreatorSource"))));

        assertTrue("bukkit.yml must be registered under the shared configured name",
                source.contains("BukkitWorldConfiguration.register(BUKKIT_YML,registrationName,dimension,seed)"));
        assertTrue("Multiverse must be registered under the same configured name",
                source.contains(".updateWorld(createdWorld,registrationName,dimension)"));
        assertFalse("Multiverse must never be registered under the transient created-world name",
                source.contains(".updateWorld(createdWorld,dimension)"));
    }

    @Test
    public void multiverseRollbackUsesTheSameConfiguredName() throws IOException {
        String source = compact(Files.readString(Path.of(System.getProperty("iris.irisCreatorSource"))));

        assertTrue("rollback must unregister the name creation registered",
                source.contains(".removeFromConfig(IrisWorldStorage.configuredWorldName(worldKey,"
                        + "IrisWorldStorage.levelRoot().getName()))"));
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "");
    }
}

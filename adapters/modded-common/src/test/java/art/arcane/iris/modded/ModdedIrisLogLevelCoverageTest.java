package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * The level router is a switch statement, which the compiler does not check for exhaustiveness. A level added
 * to {@link art.arcane.iris.spi.LogLevel} with no arm here would compile and then drop every message at that
 * level without a trace, so the fallback arm is what keeps a new level visible on the modded loaders.
 */
public class ModdedIrisLogLevelCoverageTest {
    @Test
    public void everyLogLevelReachesTheLoggerIncludingOnesAddedLater() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/ModdedIrisLog.java"));
        int router = source.indexOf("public static void log(LogLevel level, String message)");
        assertTrue("log(LogLevel, String) not found", router >= 0);

        String body = source.substring(router, source.indexOf("public static void debug(", router));
        assertTrue(body, body.contains("default -> info(message);"));
    }
}

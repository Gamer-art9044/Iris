package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void slf4jStyleArgumentsAndTrailingThrowableArePreserved() {
        RuntimeException failure = new RuntimeException("broken");

        ModdedIrisLog.RenderedLog rendered = ModdedIrisLog.render("chunk {},{} failed", 4, 9, failure);

        assertEquals("chunk 4,9 failed", rendered.message());
        assertEquals(failure, rendered.error());
    }

    @Test
    public void formattedDebugThrowableUsesTheVisibleDebugRouteWhenEnabled() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/ModdedIrisLog.java"));
        int start = source.indexOf("public static void debug(String format, Object... arguments)");
        int end = source.indexOf("public static void info(String message)", start);
        assertTrue("formatted debug overload not found", start >= 0);
        assertTrue("formatted debug overload boundary not found", end > start);
        String body = source.substring(start, end);

        assertTrue(body, body.contains("if (!debugEnabled())"));
        assertTrue(body, body.contains("LOGGER.info(\"[Iris/DEBUG] \" + clean(rendered.message()), rendered.error())"));
    }

    @Test
    public void moddedProductionUsesTheIrisLogFrontDoor() throws IOException {
        Path root = Path.of(System.getProperty("iris.moddedCommonSources"));
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> bypasses = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("ModdedIrisLog.java"))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path);
                            return source.contains("LoggerFactory.getLogger")
                                    || source.contains("private static final Logger LOGGER");
                        } catch (IOException unreadable) {
                            throw new IllegalStateException(unreadable);
                        }
                    })
                    .toList();
            assertTrue(bypasses.toString(), bypasses.isEmpty());
        }
    }
}

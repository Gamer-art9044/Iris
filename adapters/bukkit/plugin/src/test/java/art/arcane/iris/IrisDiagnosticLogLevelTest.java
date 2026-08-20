package art.arcane.iris;

import art.arcane.iris.spi.LogLevel;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Core states a severity on every message it logs and the modded adapters honour it. On Bukkit the same
 * message used to become a coloured console line, which the server logs at INFO, so no core warning ever
 * appeared in a WARN-level scan of logs/latest.log - including the orphaned-world-storage reports the
 * bootstrap replays there specifically for operators to find.
 */
public class IrisDiagnosticLogLevelTest {
    @Test
    public void coreDiagnosticsKeepTheirSeverity() {
        assertEquals(Level.WARNING, Iris.diagnosticLevel(LogLevel.WARN));
        assertEquals(Level.SEVERE, Iris.diagnosticLevel(LogLevel.ERROR));
    }

    @Test
    public void informationalAndDebugMessagesStayOnTheConsolePath() {
        assertNull(Iris.diagnosticLevel(LogLevel.INFO));
        assertNull(Iris.diagnosticLevel(LogLevel.DEBUG));
    }

    /**
     * Console sender output reaches the terminal but not the instance's logs/latest.log, which is the only
     * log most operators read after the fact. A handful of lifecycle lines go to the plugin logger instead.
     */
    @Test
    public void lifecycleNoticesReachThePluginLoggerAtInfo() {
        assertEquals(Level.INFO, Iris.diagnosticLevel(LogLevel.NOTICE));
    }

    /**
     * A warning raised by the adapter is the same kind of thing as a warning raised by core. Routing one
     * through the plugin logger and the other through the console sender makes the level depend on which
     * side of the SPI the call happened to be written on.
     */
    @Test
    public void adapterSideWarningsCarryTheSameSeverityAsCoreWarnings() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/Iris.java"));

        String warn = method(source, "public static void warn(String format, Object... objs)");
        assertTrue(warn, warn.contains("diagnostic(Level.WARNING"));
        assertFalse(warn, warn.contains("msg("));

        String error = method(source, "public static void error(String format, Object... objs)");
        assertTrue(error, error.contains("diagnostic(Level.SEVERE"));
        assertFalse(error, error.contains("msg("));
    }

    @Test
    public void theCoreLogBridgeRoutesDiagnosticsToThePluginLogger() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/Iris.java"));
        String bridge = method(source, "private static void bridgeLog(LogLevel level, String message)");

        assertTrue(bridge, bridge.contains("diagnosticLevel(target)"));
        assertTrue(bridge, bridge.contains("diagnostic(diagnostic, message)"));
        assertTrue(source.contains("plugin.getLogger().log(level, line)"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found: " + signature, start >= 0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unterminated method: " + signature);
    }
}

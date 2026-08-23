package art.arcane.iris.core;

import art.arcane.iris.spi.IrisLogging;
import org.bukkit.Bukkit;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

public class ServerConfiguratorStartupRestartTest {
    @Test
    public void startupBoundaryRestartsImmediatelyAndStopsIfRestartReturns() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<IrisLogging> logging = mockStatic(IrisLogging.class)) {
            ServerConfigurator.restartAtStartupBoundary(" updated external datapacks ");

            bukkit.verify(Bukkit::restart, times(1));
            bukkit.verify(Bukkit::shutdown, times(1));
            logging.verify(() -> IrisLogging.warn(
                    "updated external datapacks Restarting server before default worlds are loaded."));
            logging.verify(() -> IrisLogging.error(
                    "The immediate Iris startup restart returned unexpectedly; stopping the server instead."));
        }
    }

    @Test
    public void startupBoundaryStopsWhenImmediateRestartThrows() {
        IllegalStateException failure = new IllegalStateException("restart unavailable");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<IrisLogging> logging = mockStatic(IrisLogging.class)) {
            bukkit.when(Bukkit::restart).thenThrow(failure);

            ServerConfigurator.restartAtStartupBoundary(null);

            bukkit.verify(Bukkit::restart, times(1));
            bukkit.verify(Bukkit::shutdown, times(1));
            logging.verify(() -> IrisLogging.reportError(
                    "Unable to restart the server at the Iris startup boundary.", failure));
        }
    }

    @Test
    public void startupRestartPathsPromoteTheValidationStateAndBypassTickQueues() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/ServerConfigurator.java"));
        String configure = section(
                source,
                "public static void configure()",
                "public static boolean isLoadedDatapackRuntimeReady");
        String startupRestart = section(
                source,
                "public static void restartAtStartupBoundary",
                "public static boolean verifyDataPackInstalled");

        int restartResult = configure.indexOf("if (result.restartRequired())");
        int validationRestart = configure.indexOf("requireDatapackRestart();", restartResult);
        assertTrue(restartResult >= 0);
        assertTrue(validationRestart > restartResult);
        assertTrue(startupRestart.indexOf("Bukkit.restart();")
                < startupRestart.indexOf("Bukkit.shutdown();"));
        assertFalse(startupRestart.contains("J.s("));
        assertFalse(startupRestart.contains("dispatchCommand"));
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }
}

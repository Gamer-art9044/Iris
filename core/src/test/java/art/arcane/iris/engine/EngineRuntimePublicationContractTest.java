package art.arcane.iris.engine;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class EngineRuntimePublicationContractTest {
    @Test
    public void worldManagerStartsOnlyAfterTheRuntimeSessionIsReady() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/engine/EngineRuntimeBuilder.java")).replace("\r\n", "\n");
        int publishStart = source.indexOf("void publishRuntime(");
        int publishEnd = source.indexOf("private void scheduleRuntimeTasks", publishStart);
        String publish = source.substring(publishStart, publishEnd);

        assertBefore(publish, "engine.runtime = next", "next.worldManager().start()");
        assertBefore(publish, "activateNextSession()", "next.worldManager().start()");
        assertBefore(publish, "engine.lifecycleState = LifecycleState.RUNNING", "next.worldManager().start()");
        assertBefore(publish, "engine.getClosing().set(false)", "next.worldManager().start()");
        assertBefore(publish, "openBackgroundTaskAdmission()", "next.worldManager().start()");
    }

    @Test
    public void failedManagerStartClosesAdmissionBeforeRuntimeCleanup() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/engine/EngineRuntimeBuilder.java")).replace("\r\n", "\n");
        int publishStart = source.indexOf("void publishRuntime(");
        int publishEnd = source.indexOf("private void scheduleRuntimeTasks", publishStart);
        String publish = source.substring(publishStart, publishEnd);

        assertBefore(publish, "engine.getClosing().set(true)", "closeRuntime(next, e)");
        assertBefore(publish, "closeBackgroundTaskAdmission()", "closeRuntime(next, e)");
        assertBefore(publish, "sealAndAwait(", "closeRuntime(next, e)");
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("Missing source contract token: " + first, firstIndex >= 0);
        assertTrue("Missing source contract token: " + second, secondIndex >= 0);
        assertTrue(first + " must occur before " + second, firstIndex < secondIndex);
    }
}

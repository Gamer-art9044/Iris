package art.arcane.iris.core.datapack;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class DatapackIngestRuntimeInvalidationTest {
    @Test
    public void everyPublicMutationInvalidatesLoadedRuntimeBeforeLocking() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/datapack/DatapackIngestService.java"));

        assertInvalidatesBeforeLock(source, "public static Report ingest(");
        assertInvalidatesBeforeLock(source, "public static ReapplyOutcome reapplyFromStaging(");
        assertInvalidatesBeforeLock(source, "public static boolean remove(");
    }

    @Test
    public void noChangeChecksRestoreWhileChangedIngestRemainsRestartBound() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/datapack/DatapackIngestService.java"));
        int ingest = source.indexOf("public static Report ingest(");
        int unlock = source.indexOf("TRANSACTION_LOCK.unlock();", ingest);
        int restore = source.indexOf(
                "ServerConfigurator.restoreLoadedDatapackRuntimeIfUnchanged(invalidation);",
                unlock);
        int changed = source.indexOf("else if (report.changed())", restore);
        int restart = source.indexOf("ServerConfigurator.restart();", changed);
        int restartRequired = source.indexOf("ServerConfigurator.requireDatapackRestart();", restart);

        assertTrue(ingest >= 0);
        assertTrue(unlock > ingest);
        assertTrue(restore > unlock);
        assertTrue(changed > restore);
        assertTrue(restart > changed);
        assertTrue(restartRequired > restart);
    }

    private void assertInvalidatesBeforeLock(String source, String signature) {
        int method = source.indexOf(signature);
        int invalidation = source.indexOf("ServerConfigurator.invalidateLoadedDatapackRuntime();", method);
        int lock = source.indexOf("TRANSACTION_LOCK.lock();", method);

        assertTrue("Missing datapack mutation entry point " + signature, method >= 0);
        assertTrue("Loaded runtime must be invalidated before " + signature, invalidation > method);
        assertTrue("Loaded runtime invalidation must precede the transaction lock", invalidation < lock);
    }
}

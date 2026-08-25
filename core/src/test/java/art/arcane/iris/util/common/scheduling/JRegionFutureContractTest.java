package art.arcane.iris.util.common.scheduling;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class JRegionFutureContractTest {
    @Test
    public void regionFutureSettlesEverySchedulerPath() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/util/common/scheduling/J.java"))
                .replace("\r\n", "\n");
        int start = source.indexOf("public static CompletableFuture<Void> runRegionFuture(");
        int end = source.indexOf("public static boolean runGlobal(", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("settle(future, runnable)"));
        assertTrue(method.contains("future.completeExceptionally("));
        assertTrue(method.contains("return sfut(runnable);"));
    }
}

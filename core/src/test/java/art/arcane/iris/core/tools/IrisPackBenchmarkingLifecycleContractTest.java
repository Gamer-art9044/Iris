package art.arcane.iris.core.tools;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class IrisPackBenchmarkingLifecycleContractTest {
    @Test
    public void benchmarkAppliesProfileBeforeCreatingItsWorld() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.packBenchmarkingSource")));
        String runBenchmark = method(source, "private void runBenchmark()");

        assertBefore(runBenchmark, "IrisToolbelt.applyPregenPerformanceProfile()", "createBenchmark()");
    }

    @Test
    public void liveEngineProfileUsesTheBoundPlatformGenerator() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.irisToolbeltSource")));
        String applyProfile = method(source, "public static void applyPregenPerformanceProfile(Engine engine)");

        assertBefore(applyProfile, "PregenPerformanceProfile.applyToGenerator(generator)", "PregenPerformanceProfile.apply(engine)");
    }

    @Test
    public void bukkitComplexHotloadUsesExclusiveGenerationControl() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.bukkitChunkGeneratorSource")));
        String hotload = method(source, "public CompletableFuture<Void> hotloadComplexAsync(long acquisitionTimeout, TimeUnit unit)");

        assertTrue(hotload.contains("withExclusiveControlFuture(activeEngine::hotloadComplex, acquisitionTimeout, unit)"));
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0);
        assertTrue(secondIndex > firstIndex);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0);
        int openingBrace = source.indexOf('{', start);
        assertTrue(openingBrace >= 0);
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new AssertionError("Method body did not close: " + signature);
    }
}

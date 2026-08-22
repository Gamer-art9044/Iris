package art.arcane.iris.core.pregenerator.methods;

import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.pregenerator.cache.PregenCache;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.math.Position2;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CachedPregenMethodCompletionTest {
    private File directory;
    private PregenCache cache;
    private CapturingMethod underlying;
    private PregenTask task;
    private CachedPregenMethod method;
    private RecordingListener listener;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory("iris-pregen-cache-test").toFile();
        cache = PregenCache.create(directory);
        underlying = new CapturingMethod();
        task = PregenTask.builder()
                .center(new Position2(0, 0))
                .radiusX(256)
                .radiusZ(256)
                .build();
        method = new CachedPregenMethod(underlying, cache, task);
        listener = new RecordingListener();
    }

    @Test
    public void generateChunkDoesNotCacheOnSubmitOnlyOnCompletion() {
        method.generateChunk(3, 4, listener);

        assertEquals(1, underlying.generateChunkCalls.get());
        assertFalse(cache.isChunkCached(3, 4));

        underlying.capturedListener.get().onChunkGenerated(3, 4, false);

        assertTrue(cache.isChunkCached(3, 4));
        assertEquals(1, listener.generated.get());
    }

    @Test
    public void generateChunkFailureIsNotCached() {
        method.generateChunk(6, 9, listener);
        underlying.capturedListener.get().onChunkFailed(6, 9);

        assertFalse(cache.isChunkCached(6, 9));
        assertEquals(0, listener.generated.get());
        assertEquals(1, listener.failed.get());
    }

    @Test
    public void generateChunkSkipsUnderlyingMethodWhenCached() {
        method.generateChunk(1, 2, listener);
        underlying.capturedListener.get().onChunkGenerated(1, 2, false);
        assertEquals(1, underlying.generateChunkCalls.get());

        method.generateChunk(1, 2, listener);

        assertEquals(1, underlying.generateChunkCalls.get());
        assertEquals(2, listener.generated.get());
        assertEquals(1, listener.generatedCached.get());
    }

    @Test
    public void generateRegionReplayRespectsTaskBounds() {
        cache.cacheRegion(0, 0);
        List<long[]> expected = new ArrayList<>();
        task.iterateChunks(0, 0, (x, z) -> expected.add(new long[]{x, z}));
        assertTrue(expected.size() < 1024);

        method.generateRegion(0, 0, listener);

        assertEquals(0, underlying.generateRegionCalls.get());
        assertEquals(expected.size(), listener.generated.get());
        assertEquals(expected.size(), listener.generatedCached.get());
    }

    private static final class CapturingMethod implements PregeneratorMethod {
        private final AtomicInteger generateChunkCalls = new AtomicInteger();
        private final AtomicInteger generateRegionCalls = new AtomicInteger();
        private final AtomicReference<PregenListener> capturedListener = new AtomicReference<>();

        @Override
        public void init() {
        }

        @Override
        public void close() {
        }

        @Override
        public void save() {
        }

        @Override
        public boolean supportsRegions(int x, int z, PregenListener listener) {
            return false;
        }

        @Override
        public String getMethod(int x, int z) {
            return "capture";
        }

        @Override
        public void generateRegion(int x, int z, PregenListener listener) {
            generateRegionCalls.incrementAndGet();
        }

        @Override
        public void generateChunk(int x, int z, PregenListener listener) {
            generateChunkCalls.incrementAndGet();
            capturedListener.set(listener);
        }

        @Override
        public Mantle getMantle() {
            return null;
        }
    }

    private static final class RecordingListener implements PregenListener {
        private final AtomicInteger generated = new AtomicInteger();
        private final AtomicInteger generatedCached = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        @Override
        public void onTick(double chunksPerSecond, double chunksPerMinute, double regionsPerMinute, double percent, long generated, long totalChunks, long chunksRemaining, long eta, long elapsed, String method, boolean cached) {
        }

        @Override
        public void onChunkGenerating(int x, int z) {
        }

        @Override
        public void onChunkGenerated(int x, int z, boolean cached) {
            generated.incrementAndGet();
            if (cached) {
                generatedCached.incrementAndGet();
            }
        }

        @Override
        public void onChunkFailed(int x, int z) {
            failed.incrementAndGet();
        }

        @Override
        public void onRegionGenerated(int x, int z) {
        }

        @Override
        public void onRegionGenerating(int x, int z) {
        }

        @Override
        public void onChunkCleaned(int x, int z) {
        }

        @Override
        public void onRegionSkipped(int x, int z) {
        }

        @Override
        public void onNetworkStarted(int x, int z) {
        }

        @Override
        public void onNetworkFailed(int x, int z) {
        }

        @Override
        public void onNetworkReclaim(int revert) {
        }

        @Override
        public void onNetworkGeneratedChunk(int x, int z) {
        }

        @Override
        public void onNetworkDownloaded(int x, int z) {
        }

        @Override
        public void onClose() {
        }

        @Override
        public void onSaving() {
        }

        @Override
        public void onChunkExistsInRegionGen(int x, int z) {
        }
    }
}

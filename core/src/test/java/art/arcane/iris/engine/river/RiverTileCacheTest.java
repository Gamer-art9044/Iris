package art.arcane.iris.engine.river;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RiverTileCacheTest {
    @Test
    public void concurrentRequestsBuildOneTileOnce() throws Exception {
        int requestCount = 16;
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch builderEntered = new CountDownLatch(1);
        CountDownLatch releaseBuilder = new CountDownLatch(1);
        RiverTileCache cache = new RiverTileCache(8, (tileX, tileZ) -> {
            builds.incrementAndGet();
            builderEntered.countDown();
            assertTrue(releaseBuilder.await(5, TimeUnit.SECONDS));
            return emptyTile(tileX, tileZ);
        });
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            @SuppressWarnings("unchecked")
            Future<RiverTile>[] futures = new Future[requestCount];
            for (int request = 0; request < requestCount; request++) {
                futures[request] = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return cache.get(-7, -11);
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(builderEntered.await(5, TimeUnit.SECONDS));
            releaseBuilder.countDown();

            RiverTile first = futures[0].get(5, TimeUnit.SECONDS);
            for (Future<RiverTile> future : futures) {
                assertSame(first, future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(-7, first.tileX());
            assertEquals(-11, first.tileZ());
            assertEquals(1, builds.get());
            assertEquals(1, cache.completedSize());
        } finally {
            releaseBuilder.countDown();
            executor.shutdownNow();
            cache.close();
        }
    }

    @Test
    public void differentKeysBuildConcurrentlyOutsideCacheLock() throws Exception {
        CountDownLatch buildersEntered = new CountDownLatch(2);
        CountDownLatch releaseBuilders = new CountDownLatch(1);
        RiverTileCache cache = new RiverTileCache(4, (tileX, tileZ) -> {
            buildersEntered.countDown();
            assertTrue(releaseBuilders.await(5, TimeUnit.SECONDS));
            return emptyTile(tileX, tileZ);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RiverTile> first = executor.submit(() -> cache.get(1, 1));
            Future<RiverTile> second = executor.submit(() -> cache.get(2, 2));
            assertTrue(buildersEntered.await(5, TimeUnit.SECONDS));
            releaseBuilders.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS).tileX());
            assertEquals(2, second.get(5, TimeUnit.SECONDS).tileX());
        } finally {
            releaseBuilders.countDown();
            executor.shutdownNow();
            cache.close();
        }
    }

    @Test
    public void completedEntriesUseBoundedAccessOrderEviction() {
        AtomicInteger builds = new AtomicInteger();
        RiverTileCache cache = new RiverTileCache(2, (tileX, tileZ) -> {
            builds.incrementAndGet();
            return emptyTile(tileX, tileZ);
        });
        try {
            RiverTile zero = cache.get(0, 0);
            RiverTile one = cache.get(1, 0);
            assertSame(zero, cache.get(0, 0));
            cache.get(2, 0);
            assertEquals(2, cache.completedSize());
            assertSame(zero, cache.get(0, 0));

            RiverTile rebuiltOne = cache.get(1, 0);
            assertFalse(one == rebuiltOne);
            assertEquals(4, builds.get());
            assertEquals(2, cache.completedSize());
        } finally {
            cache.close();
        }
    }

    @Test
    public void failedBuildsAreRemovedAndRetryable() {
        AtomicInteger attempts = new AtomicInteger();
        RiverTileCache cache = new RiverTileCache(2, (tileX, tileZ) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalArgumentException("expected failure");
            }
            return emptyTile(tileX, tileZ);
        });
        try {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.get(-3, 4)
            );
            assertEquals("expected failure", failure.getMessage());
            assertEquals(0, cache.completedSize());

            RiverTile retried = cache.get(-3, 4);
            assertEquals(-3, retried.tileX());
            assertEquals(2, attempts.get());
            assertEquals(1, cache.completedSize());
        } finally {
            cache.close();
        }
    }

    @Test
    public void mismatchedBuilderResultIsRemovedAndRetryable() {
        AtomicInteger attempts = new AtomicInteger();
        RiverTileCache cache = new RiverTileCache(2, (tileX, tileZ) -> attempts.incrementAndGet() == 1
                ? emptyTile(tileX + 1, tileZ)
                : emptyTile(tileX, tileZ));
        try {
            assertThrows(IllegalStateException.class, () -> cache.get(5, 6));
            RiverTile retried = cache.get(5, 6);
            assertEquals(5, retried.tileX());
            assertEquals(2, attempts.get());
        } finally {
            cache.close();
        }
    }

    @Test
    public void clearAndCloseReleaseEntriesAndEnforceLifecycle() {
        AtomicInteger builds = new AtomicInteger();
        RiverTileCache cache = new RiverTileCache(4, (tileX, tileZ) -> {
            builds.incrementAndGet();
            return emptyTile(tileX, tileZ);
        });
        cache.get(0, 0);
        cache.get(1, 0);
        assertEquals(2, cache.completedSize());

        cache.clear();
        assertEquals(0, cache.completedSize());
        cache.get(0, 0);
        assertEquals(3, builds.get());

        cache.close();
        cache.close();
        assertTrue(cache.isClosed());
        assertEquals(0, cache.completedSize());
        assertThrows(IllegalStateException.class, () -> cache.get(0, 0));
        assertThrows(IllegalStateException.class, cache::clear);
    }

    @Test
    public void closeInvalidatesInflightBuildWithoutRetainingItsResult() throws Exception {
        CountDownLatch builderEntered = new CountDownLatch(1);
        CountDownLatch releaseBuilder = new CountDownLatch(1);
        RiverTileCache cache = new RiverTileCache(2, (tileX, tileZ) -> {
            builderEntered.countDown();
            assertTrue(releaseBuilder.await(5, TimeUnit.SECONDS));
            return emptyTile(tileX, tileZ);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RiverTile> result = executor.submit(() -> cache.get(9, -9));
            assertTrue(builderEntered.await(5, TimeUnit.SECONDS));
            cache.close();
            releaseBuilder.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(0, cache.completedSize());
        } finally {
            releaseBuilder.countDown();
            executor.shutdownNow();
            cache.close();
        }
    }

    @Test
    public void clearInvalidatesInflightBuildAndFreshRequestPublishesNewEntry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstBuilderEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstBuilder = new CountDownLatch(1);
        RiverTileCache cache = new RiverTileCache(2, (tileX, tileZ) -> {
            if (attempts.incrementAndGet() == 1) {
                firstBuilderEntered.countDown();
                assertTrue(releaseFirstBuilder.await(5, TimeUnit.SECONDS));
            }
            return emptyTile(tileX, tileZ);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RiverTile> invalidated = executor.submit(() -> cache.get(4, -2));
            assertTrue(firstBuilderEntered.await(5, TimeUnit.SECONDS));
            cache.clear();

            RiverTile replacement = cache.get(4, -2);
            releaseFirstBuilder.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> invalidated.get(5, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(4, replacement.tileX());
            assertEquals(-2, replacement.tileZ());
            assertEquals(2, attempts.get());
            assertEquals(1, cache.completedSize());
            assertSame(replacement, cache.get(4, -2));
        } finally {
            releaseFirstBuilder.countDown();
            executor.shutdownNow();
            cache.close();
        }
    }

    @Test
    public void tileProvidesConstantTimeReachIdentityLookup() {
        RiverReach reach = reach();
        RiverTile tile = new RiverTile(0, 0, 0, 0, 64, 64, List.of(reach));

        assertSame(reach, tile.reach(reach.id()));
        assertNull(tile.reach(RiverEdgeId.of(new RiverNodeId(2L, 0L), new RiverNodeId(3L, 0L))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RiverTile(0, 0, 0, 0, 64, 64, List.of(reach, reach))
        );
    }

    private static RiverTile emptyTile(int tileX, int tileZ) {
        int minimumX = tileX * 64;
        int minimumZ = tileZ * 64;
        return new RiverTile(tileX, tileZ, minimumX, minimumZ, minimumX + 64, minimumZ + 64, List.of());
    }

    private static RiverReach reach() {
        RiverNode from = new RiverNode(
                new RiverNodeId(0L, 0L),
                8.0,
                8.0,
                20.0,
                20.0,
                20.0,
                20.0,
                false,
                true
        );
        RiverNode to = new RiverNode(
                new RiverNodeId(1L, 0L),
                56.0,
                8.0,
                10.0,
                10.0,
                10.0,
                10.0,
                false,
                true
        );
        return new RiverReach(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                RiverRouteState.WET,
                1,
                1,
                8.0,
                RiverWidthProfile.constant(8.0),
                4.0,
                3.0,
                false,
                false,
                new RiverPolyline(new double[]{8.0, 56.0}, new double[]{8.0, 8.0})
        );
    }
}

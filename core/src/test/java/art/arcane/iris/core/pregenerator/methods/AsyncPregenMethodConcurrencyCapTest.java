package art.arcane.iris.core.pregenerator.methods;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class AsyncPregenMethodConcurrencyCapTest {
    @Test
    public void paperLikeRecommendedCapTracksWorkerThreads() {
        assertEquals(16, AsyncPregenMethod.computePaperLikeRecommendedCap(1));
        assertEquals(32, AsyncPregenMethod.computePaperLikeRecommendedCap(4));
        assertEquals(96, AsyncPregenMethod.computePaperLikeRecommendedCap(12));
        assertEquals(128, AsyncPregenMethod.computePaperLikeRecommendedCap(80));
        assertEquals(128, AsyncPregenMethod.computePaperLikeRecommendedCap(128));
    }

    @Test
    public void foliaRecommendedCapTracksWorkerThreads() {
        assertEquals(64, AsyncPregenMethod.computeFoliaRecommendedCap(1));
        assertEquals(96, AsyncPregenMethod.computeFoliaRecommendedCap(12));
        assertEquals(160, AsyncPregenMethod.computeFoliaRecommendedCap(20));
        assertEquals(192, AsyncPregenMethod.computeFoliaRecommendedCap(80));
    }

    @Test
    public void paperLikeConcurrencyUsesProvisionedWorkerPoolCapacity() {
        assertEquals(32, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(4, 16, 32));
        assertEquals(16, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(4, 16, 16));
        assertEquals(24, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(-1, 16, 24));
        assertEquals(16, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(-1, 16, 8));
        assertEquals(128, AsyncPregenMethod.computePaperLikeRecommendedCap(
                AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(4, 16, 16)));
    }

    @Test
    public void foliaConcurrencyStillUsesBroaderRuntimeCapacity() {
        assertEquals(32, AsyncPregenMethod.resolveFoliaConcurrencyWorkerThreads(4, 16, 32));
        assertEquals(16, AsyncPregenMethod.resolveFoliaConcurrencyWorkerThreads(-1, 16, 12));
    }

    @Test
    public void strictSerialOverridesRecommendedConcurrencyCap() {
        assertEquals(1, AsyncPregenMethod.selectConcurrencyCap(128, true));
        assertEquals(128, AsyncPregenMethod.selectConcurrencyCap(128, false));
        assertEquals(1, AsyncPregenMethod.selectConcurrencyCap(0, false));
    }

    @Test
    public void slowRequestObservationDoesNotCompleteOrReplacePendingFuture() {
        CompletableFuture<String> request = new CompletableFuture<>();
        AtomicInteger slowRequests = new AtomicInteger();

        CompletableFuture<String> observed = AsyncPregenMethod.observeSlowRequest(
                request,
                Runnable::run,
                slowRequests::incrementAndGet);

        assertSame(request, observed);
        assertFalse(request.isDone());
        assertEquals(1, slowRequests.get());
        request.complete("generated");
        assertEquals("generated", observed.join());
    }

    @Test
    public void slowRequestObservationIgnoresCompletedFuture() {
        CompletableFuture<String> request = CompletableFuture.completedFuture("generated");
        AtomicInteger slowRequests = new AtomicInteger();

        AsyncPregenMethod.observeSlowRequest(request, Runnable::run, slowRequests::incrementAndGet);

        assertEquals(0, slowRequests.get());
        assertEquals("generated", request.join());
    }
}

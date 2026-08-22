package art.arcane.iris.core.nms;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerShutdownBoundaryTest {
    @Test
    public void await_returnsImmediatelyWhenBoundaryIsAlreadyReached() {
        assertTrue(ServerShutdownBoundary.await(
                () -> true,
                Thread.currentThread(),
                0L,
                TimeUnit.MILLISECONDS
        ));
    }

    @Test
    public void await_doesNotJoinTheCallingServerThread() {
        assertFalse(ServerShutdownBoundary.await(
                () -> false,
                Thread.currentThread(),
                5L,
                TimeUnit.SECONDS
        ));
    }

    @Test
    public void await_blocksUntilAuthoritativeBoundaryIsReached() throws Exception {
        CountDownLatch serverStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);
        AtomicBoolean boundaryReached = new AtomicBoolean(false);
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        Thread serverThread = new Thread(() -> {
            serverStarted.countDown();
            await(releaseServer);
            boundaryReached.set(true);
        }, "server-boundary-test");
        Thread waiterThread = new Thread(() -> {
            waiterStarted.countDown();
            result.set(ServerShutdownBoundary.await(
                    boundaryReached::get,
                    serverThread,
                    5L,
                    TimeUnit.SECONDS
            ));
            waiterFinished.countDown();
        }, "server-boundary-waiter-test");

        serverThread.start();
        assertTrue(serverStarted.await(1L, TimeUnit.SECONDS));
        waiterThread.start();
        assertTrue(waiterStarted.await(1L, TimeUnit.SECONDS));
        assertFalse(waiterFinished.await(0L, TimeUnit.MILLISECONDS));

        releaseServer.countDown();

        assertTrue(waiterFinished.await(2L, TimeUnit.SECONDS));
        assertTrue(result.get());
        serverThread.join();
        waiterThread.join();
    }

    @Test
    public void await_returnsFalseWhenBoundaryDoesNotArriveBeforeTimeout() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        Thread serverThread = new Thread(() -> await(releaseServer), "server-boundary-timeout-test");
        serverThread.start();

        try {
            assertFalse(ServerShutdownBoundary.await(
                    () -> false,
                    serverThread,
                    0L,
                    TimeUnit.MILLISECONDS
            ));
        } finally {
            releaseServer.countDown();
            serverThread.join();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

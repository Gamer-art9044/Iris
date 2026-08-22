package art.arcane.iris.engine.framework;

import art.arcane.iris.util.project.context.IrisContext;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EngineLifecycleTasksTest {
    @Test
    public void activeLifecycleTaskHoldsGenerationDrainUntilCompletion() throws Exception {
        GenerationSessionManager manager = new GenerationSessionManager();
        Engine engine = mock(Engine.class);
        when(engine.acquireGenerationLease(anyString()))
                .thenAnswer(invocation -> manager.acquire(invocation.getArgument(0)));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> taskFailure = new AtomicReference<>();
        AtomicReference<Engine> contextEngine = new AtomicReference<>();
        Thread task = new Thread(() -> {
            try {
                EngineLifecycleTasks.run(engine, "world_manager_test", () -> {
                    contextEngine.set(IrisContext.get().getEngine());
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable exception) {
                taskFailure.set(exception);
            }
        });
        task.start();

        assertTrue(entered.await(1L, TimeUnit.SECONDS));
        CountDownLatch drained = new CountDownLatch(1);
        Thread sealer = new Thread(() -> {
            try {
                manager.sealAndAwait("hotload", 1_000L);
                drained.countDown();
            } catch (GenerationSessionException exception) {
                taskFailure.set(exception);
            }
        });
        sealer.start();

        waitForSeal(manager);
        assertFalse(drained.await(50L, TimeUnit.MILLISECONDS));
        release.countDown();
        assertTrue(drained.await(1L, TimeUnit.SECONDS));
        task.join(1_000L);
        sealer.join(1_000L);
        assertSame(engine, contextEngine.get());
        assertTrue(taskFailure.get() == null);
    }

    @Test
    public void sealedLifecycleRejectsQueuedManagerTask() throws Exception {
        GenerationSessionManager manager = new GenerationSessionManager();
        manager.sealAndAwait("hotload", 1_000L);
        Engine engine = mock(Engine.class);
        when(engine.acquireGenerationLease(anyString()))
                .thenAnswer(invocation -> manager.acquire(invocation.getArgument(0)));
        when(engine.isClosing()).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean();

        boolean accepted = EngineLifecycleTasks.run(engine, "queued_world_manager_test", () -> ran.set(true));

        assertFalse(accepted);
        assertFalse(ran.get());
    }

    private void waitForSeal(GenerationSessionManager manager) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (System.nanoTime() < deadline) {
            try (GenerationSessionLease ignored = manager.acquire("seal_probe")) {
                Thread.onSpinWait();
            } catch (GenerationSessionException expected) {
                return;
            }
        }
        throw new AssertionError("Generation session did not seal within one second.");
    }
}

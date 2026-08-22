package art.arcane.iris.engine.platform;

import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.platform.studio.StudioGenerator;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class BukkitChunkGeneratorGenerationStageGateTest {
    @Test
    public void activeStageDelaysExclusiveControl() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(2, closing::get);
        BukkitChunkGenerator.GenerationStagePermit stage = gate.acquireStage("active");
        CountDownLatch exclusiveEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> exclusive = executor.submit(() -> {
                try {
                    gate.acquireExclusive();
                    exclusiveEntered.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    if (exclusiveEntered.getCount() == 0L) {
                        gate.releaseExclusive();
                    }
                }
            });

            awaitQueueLength(gate, 1);
            assertEquals(1L, exclusiveEntered.getCount());

            stage.close();
            exclusive.get(2, TimeUnit.SECONDS);

            assertEquals(0L, exclusiveEntered.getCount());
            assertEquals(2, gate.availablePermits());
        } finally {
            stage.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void exclusiveWaiterRunsBeforeLaterGenerationStage() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(2, closing::get);
        BukkitChunkGenerator.GenerationStagePermit activeStage = gate.acquireStage("active");
        CountDownLatch exclusiveEntered = new CountDownLatch(1);
        CountDownLatch releaseExclusive = new CountDownLatch(1);
        CountDownLatch laterStageAttempting = new CountDownLatch(1);
        CountDownLatch laterStageEntered = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> exclusive = executor.submit(() -> {
                try {
                    gate.acquireExclusive();
                    order.add("exclusive");
                    exclusiveEntered.countDown();
                    assertTrue(releaseExclusive.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    if (exclusiveEntered.getCount() == 0L) {
                        gate.releaseExclusive();
                    }
                }
            });
            awaitQueueLength(gate, 1);

            Future<?> laterStage = executor.submit(() -> {
                laterStageAttempting.countDown();
                try (BukkitChunkGenerator.GenerationStagePermit ignored = gate.acquireStage("later")) {
                    order.add("stage");
                    laterStageEntered.countDown();
                }
            });
            assertTrue(laterStageAttempting.await(2, TimeUnit.SECONDS));
            awaitQueueLength(gate, 2);

            activeStage.close();
            assertTrue(exclusiveEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1L, laterStageEntered.getCount());

            releaseExclusive.countDown();
            exclusive.get(2, TimeUnit.SECONDS);
            laterStage.get(2, TimeUnit.SECONDS);

            assertEquals(List.of("exclusive", "stage"), order);
            assertEquals(2, gate.availablePermits());
        } finally {
            releaseExclusive.countDown();
            activeStage.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void generationStageCanBeReleasedTwiceFromAnotherThread() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(1, closing::get);
        BukkitChunkGenerator.GenerationStagePermit stage = gate.acquireStage("async");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> release = executor.submit(() -> {
                stage.close();
                stage.close();
            });
            release.get(2, TimeUnit.SECONDS);

            assertEquals(1, gate.availablePermits());
            gate.acquireExclusive();
            assertEquals(0, gate.availablePermits());
            gate.releaseExclusive();
            assertEquals(1, gate.availablePermits());
        } finally {
            stage.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void exclusiveGenerationStageDowngradesWithoutReleasingItsRetainedPermit() {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(3, closing::get);
        BukkitChunkGenerator.GenerationStageExclusivePermit exclusive =
                gate.acquireExclusiveStage("prepare");

        assertEquals(0, gate.availablePermits());
        BukkitChunkGenerator.GenerationStagePermit stage = exclusive.downgradeToStage();

        assertEquals(2, gate.availablePermits());
        exclusive.close();
        assertEquals(2, gate.availablePermits());
        stage.close();
        assertEquals(3, gate.availablePermits());
    }

    @Test
    public void preparedGenerationRetriesChangedGeneratorWhileHoldingExclusiveAdmission() {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(2, closing::get);
        PreparingStudioGenerator first = new PreparingStudioGenerator(gate, false);
        PreparingStudioGenerator second = new PreparingStudioGenerator(gate, false);
        AtomicInteger resolutions = new AtomicInteger();
        Supplier<StudioGenerator> resolver = () -> resolutions.getAndIncrement() == 0 ? first : second;
        Engine engine = mock(Engine.class);

        BukkitChunkGenerator.GenerationStagePermit stage =
                BukkitChunkGenerator.acquirePreparedGenerationStage(
                        gate,
                        "prepared",
                        resolver,
                        engine,
                        4,
                        7);

        assertEquals(0, first.preparations());
        assertEquals(1, second.preparations());
        assertEquals(0, second.observedPermits());
        assertTrue(resolutions.get() >= 4);
        assertEquals(1, gate.availablePermits());
        verifyNoInteractions(engine);
        stage.close();
        assertEquals(2, gate.availablePermits());
    }

    @Test
    public void failedPreparationReleasesEveryExclusivePermit() {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(2, closing::get);
        PreparingStudioGenerator generator = new PreparingStudioGenerator(gate, true);
        Engine engine = mock(Engine.class);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> BukkitChunkGenerator.acquirePreparedGenerationStage(
                        gate,
                        "prepared-failure",
                        () -> generator,
                        engine,
                        2,
                        3));

        assertTrue(failure.getMessage().contains("could not prepare"));
        assertTrue(failure.getCause() instanceof WrongEngineBroException);
        assertEquals(1, generator.preparations());
        assertEquals(0, generator.observedPermits());
        assertEquals(2, gate.availablePermits());
        verifyNoInteractions(engine);
    }

    @Test
    public void defaultStudioGeneratorUsesOneOrdinaryGenerationPermit() {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(2, closing::get);
        StudioGenerator generator = new DefaultStudioGenerator();
        Engine engine = mock(Engine.class);

        BukkitChunkGenerator.GenerationStagePermit stage =
                BukkitChunkGenerator.acquirePreparedGenerationStage(
                        gate,
                        "ordinary",
                        () -> generator,
                        engine,
                        0,
                        0);

        assertEquals(1, gate.availablePermits());
        verifyNoInteractions(engine);
        stage.close();
        assertEquals(2, gate.availablePermits());
    }

    @Test
    public void exclusiveControlSuccessReleasesGateBeforeSynchronousCompletionDependent() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(1, closing::get);
        CompletableFuture<Void> outward = new CompletableFuture<>();
        CountDownLatch dependentEntered = new CountDownLatch(1);
        outward.thenRun(() -> {
            try (BukkitChunkGenerator.GenerationStagePermit ignored = gate.acquireStage("success-dependent")) {
                dependentEntered.countDown();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> operation = executor.submit(() ->
                    BukkitChunkGenerator.completeExclusiveControlFuture(gate, () -> {
                    }, outward));
            operation.get(2, TimeUnit.SECONDS);

            assertTrue(outward.isDone());
            assertEquals(0L, dependentEntered.getCount());
            assertEquals(1, gate.availablePermits());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void exclusiveControlFailureReleasesGateBeforeSynchronousCompletionDependent() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(1, closing::get);
        CompletableFuture<Void> outward = new CompletableFuture<>();
        IllegalStateException expected = new IllegalStateException("exclusive failure");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch dependentEntered = new CountDownLatch(1);
        outward.whenComplete((ignored, failure) -> {
            try (BukkitChunkGenerator.GenerationStagePermit stage = gate.acquireStage("failure-dependent")) {
                observed.set(failure);
                dependentEntered.countDown();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> operation = executor.submit(() ->
                    BukkitChunkGenerator.completeExclusiveControlFuture(gate, () -> {
                        throw expected;
                    }, outward));
            operation.get(2, TimeUnit.SECONDS);

            CompletionException completion = assertThrows(CompletionException.class, outward::join);
            assertSame(expected, completion.getCause());
            assertSame(expected, observed.get());
            assertEquals(0L, dependentEntered.getCount());
            assertEquals(1, gate.availablePermits());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void timedExclusiveControlDoesNotReleaseAnUnacquiredPermit() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(1, closing::get);
        BukkitChunkGenerator.GenerationStagePermit stage = gate.acquireStage("timeout-holder");
        CompletableFuture<Void> outward = new CompletableFuture<>();
        AtomicBoolean operationRan = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> operation = executor.submit(() ->
                    BukkitChunkGenerator.completeExclusiveControlFuture(
                            gate,
                            () -> operationRan.set(true),
                            outward,
                            50L,
                            TimeUnit.MILLISECONDS));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> outward.get(2L, TimeUnit.SECONDS));
            operation.get(2L, TimeUnit.SECONDS);

            assertTrue(failure.getCause() instanceof TimeoutException);
            assertFalse(operationRan.get());
            assertEquals(0, gate.availablePermits());
        } finally {
            stage.close();
            assertEquals(1, gate.availablePermits());
            executor.shutdownNow();
        }
    }

    @Test
    public void cancelledExclusiveControlStopsWaitingWithoutReleasingAStagePermit() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(1, closing::get);
        BukkitChunkGenerator.GenerationStagePermit stage = gate.acquireStage("cancellation-holder");
        CompletableFuture<Void> outward = new CompletableFuture<>();
        AtomicBoolean operationRan = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> operation = executor.submit(() ->
                    BukkitChunkGenerator.completeExclusiveControlFuture(
                            gate,
                            () -> operationRan.set(true),
                            outward,
                            30L,
                            TimeUnit.SECONDS));
            awaitQueueLength(gate, 1);

            assertTrue(outward.cancel(true));
            operation.get(2L, TimeUnit.SECONDS);

            assertTrue(outward.isCancelled());
            assertFalse(operationRan.get());
            assertEquals(0, gate.availablePermits());
        } finally {
            stage.close();
            assertEquals(1, gate.availablePermits());
            executor.shutdownNow();
        }
    }

    @Test
    public void queuedStageRemainsAdmittedWhileShutdownIsOnlyQuiesced() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(1, closing::get);
        gate.acquireExclusive();
        boolean exclusiveHeld = true;
        BukkitChunkGenerator.GenerationStagePermit admitted = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<BukkitChunkGenerator.GenerationStagePermit> stage =
                    executor.submit(() -> gate.acquireStage("paper-queued-before-shutdown-boundary"));
            awaitQueueLength(gate, 1);

            assertFalse(closing.get());
            gate.releaseExclusive();
            exclusiveHeld = false;

            admitted = stage.get(2, TimeUnit.SECONDS);
            assertEquals(0, gate.availablePermits());
            admitted.close();
            assertEquals(1, gate.availablePermits());
        } finally {
            if (admitted != null) {
                admitted.close();
            }
            if (exclusiveHeld) {
                gate.releaseExclusive();
            }
            executor.shutdownNow();
        }
    }

    @Test
    public void queuedStageIsRejectedAfterCloseBegins() throws Exception {
        AtomicBoolean closing = new AtomicBoolean(false);
        BukkitChunkGenerator.GenerationStageGate gate =
                new BukkitChunkGenerator.GenerationStageGate(1, closing::get);
        gate.acquireExclusive();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<BukkitChunkGenerator.GenerationStagePermit> stage =
                    executor.submit(() -> gate.acquireStage("queued"));
            awaitQueueLength(gate, 1);

            closing.set(true);
            gate.releaseExclusive();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> stage.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains("rejected while the generator is closing"));
            assertEquals(1, gate.availablePermits());
        } finally {
            if (gate.availablePermits() == 0) {
                gate.releaseExclusive();
            }
            executor.shutdownNow();
        }
    }

    @Test
    public void generationStageGateRequiresAtLeastOnePermit() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new BukkitChunkGenerator.GenerationStageGate(0, () -> false));

        assertTrue(failure.getMessage().contains("must be positive"));
    }

    private static void awaitQueueLength(
            BukkitChunkGenerator.GenerationStageGate gate,
            int expected
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (gate.queueLength() < expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue("Expected at least " + expected + " queued gate threads", gate.queueLength() >= expected);
    }

    private static final class DefaultStudioGenerator implements StudioGenerator {
        @Override
        public void generateChunk(Engine engine, TerrainChunk tc, int x, int z) {
        }
    }

    private static final class PreparingStudioGenerator implements StudioGenerator {
        private final BukkitChunkGenerator.GenerationStageGate gate;
        private final boolean fail;
        private final AtomicInteger preparations;
        private int observedPermits;

        private PreparingStudioGenerator(
                BukkitChunkGenerator.GenerationStageGate gate,
                boolean fail
        ) {
            this.gate = gate;
            this.fail = fail;
            this.preparations = new AtomicInteger();
            this.observedPermits = -1;
        }

        @Override
        public boolean requiresPreSessionPreparation() {
            return true;
        }

        @Override
        public void prepareChunkBeforeSession(Engine engine, int x, int z) throws WrongEngineBroException {
            preparations.incrementAndGet();
            observedPermits = gate.availablePermits();
            if (fail) {
                throw new WrongEngineBroException("prepared failure");
            }
        }

        @Override
        public void generateChunk(Engine engine, TerrainChunk tc, int x, int z) {
        }

        private int preparations() {
            return preparations.get();
        }

        private int observedPermits() {
            return observedPermits;
        }
    }
}

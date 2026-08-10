package art.arcane.iris.core.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StudioOpenCoordinatorCloseSequenceTest {
    @Test
    public void unloadCompletesBeforeGeneratorCloseAndFolderDeletion() {
        ArrayList<String> phases = new ArrayList<>();

        StudioOpenCoordinator.sequenceStudioClose(
                () -> phase(phases, "evacuate"),
                () -> phase(phases, "unload"),
                () -> phase(phases, "close-generator"),
                () -> phase(phases, "delete-folders")
        ).join();

        assertEquals(List.of("evacuate", "unload", "close-generator", "delete-folders"), phases);
    }

    @Test
    public void unloadFailurePreventsGeneratorCloseAndFolderDeletion() {
        ArrayList<String> phases = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("unload rejected");

        try {
            StudioOpenCoordinator.sequenceStudioClose(
                    () -> phase(phases, "evacuate"),
                    () -> {
                        phases.add("unload");
                        return CompletableFuture.failedFuture(failure);
                    },
                    () -> phase(phases, "close-generator"),
                    () -> phase(phases, "delete-folders")
            ).join();
            fail("Expected unload failure");
        } catch (CompletionException exception) {
            assertSame(failure, exception.getCause());
        }

        assertEquals(List.of("evacuate", "unload"), phases);
    }

    @Test
    public void generatorCloseFailurePreventsFolderDeletion() {
        ArrayList<String> phases = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("close rejected");

        try {
            StudioOpenCoordinator.sequenceStudioClose(
                    () -> phase(phases, "evacuate"),
                    () -> phase(phases, "unload"),
                    () -> {
                        phases.add("close-generator");
                        return CompletableFuture.failedFuture(failure);
                    },
                    () -> phase(phases, "delete-folders")
            ).join();
            fail("Expected generator close failure");
        } catch (CompletionException exception) {
            assertSame(failure, exception.getCause());
        }

        assertEquals(List.of("evacuate", "unload", "close-generator"), phases);
    }

    @Test
    public void terminalTimeoutPreventsLateUnloadFromClosingOrDeleting() {
        ArrayList<String> phases = new ArrayList<>();
        AtomicBoolean terminalTimeout = new AtomicBoolean(false);
        CompletableFuture<Void> unload = new CompletableFuture<>();

        CompletableFuture<Void> close = StudioOpenCoordinator.sequenceStudioClose(
                () -> phase(phases, "evacuate"),
                () -> {
                    phases.add("unload");
                    return unload;
                },
                () -> phase(phases, "close-generator"),
                () -> phase(phases, "delete-folders"),
                terminalTimeout::get);

        terminalTimeout.set(true);
        unload.complete(null);
        try {
            close.join();
            fail("Expected terminal timeout");
        } catch (CompletionException exception) {
            assertEquals("Studio close stopped after its terminal timeout.", exception.getCause().getMessage());
        }
        assertEquals(List.of("evacuate", "unload"), phases);
    }

    @Test
    public void pendingEntryLoadDefersCleanupUntilSettlement() {
        CompletableFuture<Void> entryLoad = new CompletableFuture<>();
        AtomicInteger cleanups = new AtomicInteger(0);

        CompletableFuture<Void> deferred = StudioOpenCoordinator.deferCleanupUntilEntrySettlement(
                entryLoad,
                () -> {
                    cleanups.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });

        assertEquals(0, cleanups.get());
        assertFalse(deferred.isDone());
        entryLoad.complete(null);
        deferred.join();
        assertEquals(1, cleanups.get());
        entryLoad.complete(null);
        assertEquals(1, cleanups.get());
    }

    @Test
    public void onlyAnUnsettledEntryLoadRequiresDeferredCleanup() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        CompletableFuture<Void> failed = CompletableFuture.failedFuture(
                new IllegalStateException("entry failed"));

        assertTrue(StudioOpenCoordinator.requiresDeferredEntryCleanup(pending));
        assertFalse(StudioOpenCoordinator.requiresDeferredEntryCleanup(
                CompletableFuture.completedFuture(null)));
        assertFalse(StudioOpenCoordinator.requiresDeferredEntryCleanup(failed));
        assertFalse(StudioOpenCoordinator.requiresDeferredEntryCleanup(null));
    }

    @Test
    public void retryRemainsFencedUntilDeferredCleanupCompletes() {
        StudioOpenCoordinator.EntryLoadRegistry registry =
                new StudioOpenCoordinator.EntryLoadRegistry();
        CompletableFuture<Void> entryLoad = new CompletableFuture<>();
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        registry.register("iris-entry-timeout", entryLoad);
        CompletableFuture<Void> deferred = StudioOpenCoordinator.deferCleanupUntilEntrySettlement(
                entryLoad,
                () -> cleanup);
        registry.releaseAfterSuccessfulCompletion(
                "iris-entry-timeout",
                entryLoad,
                deferred);

        entryLoad.complete(null);
        assertFalse(deferred.isDone());
        assertTrue(registry.isFenced("iris-entry-timeout"));
        try {
            registry.rejectNewOpen();
            fail("Expected the retry to be rejected");
        } catch (IllegalStateException exception) {
            assertTrue(exception.getMessage().contains("iris-entry-timeout"));
        }

        cleanup.complete(null);
        deferred.join();
        assertFalse(registry.isFenced("iris-entry-timeout"));
        registry.rejectNewOpen();
    }

    @Test
    public void exceptionalEntrySettlementStillTriggersCleanupOnce() {
        CompletableFuture<Void> entryLoad = new CompletableFuture<>();
        AtomicInteger cleanups = new AtomicInteger(0);

        CompletableFuture<Void> deferred = StudioOpenCoordinator.deferCleanupUntilEntrySettlement(
                entryLoad,
                () -> {
                    cleanups.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });

        entryLoad.completeExceptionally(new IllegalStateException("entry failed"));
        deferred.join();
        assertEquals(1, cleanups.get());
    }

    @Test
    public void nonSettlingEntryLoadNeverStartsCleanup() {
        CompletableFuture<Void> entryLoad = new CompletableFuture<>();
        AtomicInteger cleanups = new AtomicInteger(0);

        CompletableFuture<Void> deferred = StudioOpenCoordinator.deferCleanupUntilEntrySettlement(
                entryLoad,
                () -> {
                    cleanups.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });

        assertFalse(deferred.isDone());
        assertEquals(0, cleanups.get());
    }

    @Test
    public void deferredCleanupFailureIsPreserved() {
        StudioOpenCoordinator.EntryLoadRegistry registry =
                new StudioOpenCoordinator.EntryLoadRegistry();
        CompletableFuture<Void> entryLoad = new CompletableFuture<>();
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");
        registry.register("iris-cleanup-failure", entryLoad);
        CompletableFuture<Void> deferred = StudioOpenCoordinator.deferCleanupUntilEntrySettlement(
                entryLoad,
                () -> CompletableFuture.failedFuture(cleanupFailure));
        registry.releaseAfterSuccessfulCompletion(
                "iris-cleanup-failure",
                entryLoad,
                deferred);

        entryLoad.complete(null);
        try {
            deferred.join();
            fail("Expected deferred cleanup failure");
        } catch (CompletionException exception) {
            assertSame(cleanupFailure, exception.getCause());
        }
        assertTrue(registry.isFenced("iris-cleanup-failure"));
    }

    private static CompletableFuture<Void> phase(List<String> phases, String phase) {
        phases.add(phase);
        return CompletableFuture.completedFuture(null);
    }
}

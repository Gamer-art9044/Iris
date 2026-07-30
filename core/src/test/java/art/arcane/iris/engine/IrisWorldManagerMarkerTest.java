package art.arcane.iris.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisWorldManagerMarkerTest {
    @Test
    public void worldBlockHeightIsTranslatedToMantleHeight() {
        assertEquals(64, WorldBlockDropRouter.toMantleY(0, -64));
        assertEquals(319, WorldBlockDropRouter.toMantleY(255, -64));
        assertEquals(42, WorldBlockDropRouter.toMantleY(42, 0));
    }

    @Test
    public void mantleHeightIsTranslatedToWorldBlockHeight() {
        assertEquals(0, WorldBlockDropRouter.toWorldY(64, -64));
        assertEquals(255, WorldBlockDropRouter.toWorldY(319, -64));
        assertEquals(42, WorldBlockDropRouter.toWorldY(42, 0));
    }

    @Test
    public void completedEntityTasksAreAccepted() {
        assertTrue(WorldEntitySpawner.awaitEntityTasks(new CountDownLatch(0), 0, TimeUnit.MILLISECONDS));
    }

    @Test
    public void incompleteEntityTasksAreRejected() {
        assertFalse(WorldEntitySpawner.awaitEntityTasks(new CountDownLatch(1), 0, TimeUnit.MILLISECONDS));
    }

    @Test
    public void interruptedEntityWaitPreservesInterruptStatus() throws Exception {
        AtomicBoolean preserved = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            boolean completed = WorldEntitySpawner.awaitEntityTasks(new CountDownLatch(1), 1, TimeUnit.SECONDS);
            preserved.set(!completed && Thread.currentThread().isInterrupted());
        });

        thread.start();
        thread.join();

        assertTrue(preserved.get());
    }

    @Test
    public void deferredDropsUseTheRouteAndFallbackOnlyWhenDeclined() {
        List<String> routed = new ArrayList<>();
        List<String> fallback = new ArrayList<>();

        WorldBlockDropRouter.routeDrops(
                List.of("routed", "fallback"),
                drop -> {
                    if (drop.equals("routed")) {
                        routed.add((String) drop);
                        return true;
                    }
                    return false;
                },
                fallback::add
        );

        assertEquals(List.of("routed"), routed);
        assertEquals(List.of("fallback"), fallback);
    }
}

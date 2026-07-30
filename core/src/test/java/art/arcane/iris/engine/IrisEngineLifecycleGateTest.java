package art.arcane.iris.engine;

import org.junit.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisEngineLifecycleGateTest {
    @Test
    public void incompleteBackgroundDrainBlocksResourceRelease() {
        EngineBackgroundTasks.BackgroundTaskDrain drain = new EngineBackgroundTasks.BackgroundTaskDrain(
                new TimeoutException("still running"), false);

        assertFalse(drain.allowsResourceRelease());
    }

    @Test
    public void completedFailedTaskAllowsSafeResourceRelease() {
        EngineBackgroundTasks.BackgroundTaskDrain drain = new EngineBackgroundTasks.BackgroundTaskDrain(
                new IllegalStateException("completed exceptionally"), true);

        assertTrue(drain.allowsResourceRelease());
    }
}

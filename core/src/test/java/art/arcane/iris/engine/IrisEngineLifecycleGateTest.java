package art.arcane.iris.engine;

import org.junit.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisEngineLifecycleGateTest {
    @Test
    public void incompleteBackgroundDrainBlocksResourceRelease() {
        IrisEngine.BackgroundTaskDrain drain = new IrisEngine.BackgroundTaskDrain(
                new TimeoutException("still running"), false);

        assertFalse(drain.allowsResourceRelease());
    }

    @Test
    public void completedFailedTaskAllowsSafeResourceRelease() {
        IrisEngine.BackgroundTaskDrain drain = new IrisEngine.BackgroundTaskDrain(
                new IllegalStateException("completed exceptionally"), true);

        assertTrue(drain.allowsResourceRelease());
    }
}

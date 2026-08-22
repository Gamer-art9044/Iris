package art.arcane.iris.engine.framework;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnginePlayerTest {
    @Test
    public void samplesImmediatelyThenWaitsForTimeAndMovement() {
        assertTrue(EnginePlayer.needsSample(false, 0L, 0D));
        assertFalse(EnginePlayer.needsSample(true, 56L, 81D));
        assertFalse(EnginePlayer.needsSample(true, 55L, 82D));
        assertTrue(EnginePlayer.needsSample(true, 56L, 82D));
    }
}

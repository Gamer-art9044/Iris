package art.arcane.iris.core.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TreeFellingRunnerPacingTest {
    @Test
    public void memberTasksRunInlineOnlyOnNonFoliaMainThread() {
        assertTrue(TreeFellingRunner.shouldRunInline(false, true));
        assertFalse(TreeFellingRunner.shouldRunInline(false, false));
        assertFalse(TreeFellingRunner.shouldRunInline(true, true));
        assertFalse(TreeFellingRunner.shouldRunInline(true, false));
    }
}

package art.arcane.iris.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IrisWorldManagerMarkerTest {
    @Test
    public void worldBlockHeightIsTranslatedToMantleHeight() {
        assertEquals(64, IrisWorldManager.toMantleY(0, -64));
        assertEquals(319, IrisWorldManager.toMantleY(255, -64));
        assertEquals(42, IrisWorldManager.toMantleY(42, 0));
    }
}

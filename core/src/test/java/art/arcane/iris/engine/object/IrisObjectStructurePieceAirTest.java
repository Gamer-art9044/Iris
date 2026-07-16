package art.arcane.iris.engine.object;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisObjectStructurePieceAirTest {
    @Test
    public void explicitAirPlacesOnlyForRawStructurePieces() {
        assertTrue(IrisObject.shouldPlaceObjectBlock(true, true, false));
        assertFalse(IrisObject.shouldPlaceObjectBlock(false, true, false));
    }

    @Test
    public void ordinaryObjectAirRemainsNondestructive() {
        assertFalse(IrisObject.shouldPlaceObjectBlock(false, true, false));
        assertTrue(IrisObject.shouldPlaceObjectBlock(false, false, false));
    }

    @Test
    public void vineReplacementRemainsRejectedForEveryPlacementMode() {
        assertFalse(IrisObject.shouldPlaceObjectBlock(false, false, true));
        assertFalse(IrisObject.shouldPlaceObjectBlock(true, false, true));
        assertFalse(IrisObject.shouldPlaceObjectBlock(true, true, true));
    }
}

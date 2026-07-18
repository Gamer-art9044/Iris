package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.object.IrisStructureCarveShape;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisStructureComponentOverboreTest {
    @Test
    public void exactStructureFootprintAlwaysCarves() {
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(
                IrisStructureCarveShape.ERODED, 0D, 0D, 1D));
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(
                IrisStructureCarveShape.ROUNDED, 0D, 0D, 1D));
    }

    @Test
    public void boxModeKeepsStraightCandidateVolume() {
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(
                IrisStructureCarveShape.BOX, 100D, 0D, 1D));
    }

    @Test
    public void roundedModeStopsAtConfiguredReach() {
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(
                IrisStructureCarveShape.ROUNDED, 1D, 0D, 1D));
        assertFalse(IrisStructureComponent.shouldCarveOverboreCell(
                IrisStructureCarveShape.ROUNDED, 1.000001D, 1D, 0D));
    }

    @Test
    public void zeroErosionStrengthMatchesRoundedMode() {
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(
                IrisStructureCarveShape.ERODED, 1D, 0D, 0D));
        assertFalse(IrisStructureComponent.shouldCarveOverboreCell(
                IrisStructureCarveShape.ERODED, 1.000001D, 1D, 0D));
    }

    @Test
    public void zeroConfiguredCeilingHasNoErodedExtension() {
        assertEquals(0D, IrisStructureComponent.erodedUpReach(null, 0.16D, 1D, 0, 0, 0), 0D);
    }

    @Test
    public void zeroErosionStrengthKeepsTheRoundedCeiling() {
        assertEquals(10D, IrisStructureComponent.erodedUpReach(null, 0.16D, 0D, 0, 0, 10), 0D);
    }

    @Test
    public void erosionStrengthAndNoiseAreClampedDeterministically() {
        assertEquals(0.2D, IrisStructureComponent.overboreBoundaryLimit(-10D, 0.8D), 1.0E-12D);
        assertEquals(1D, IrisStructureComponent.overboreBoundaryLimit(10D, 0.8D), 0D);
        assertEquals(0.5D, IrisStructureComponent.overboreBoundaryLimit(0.5D, 1D), 0D);
        assertEquals(1D, IrisStructureComponent.overboreBoundaryLimit(0D, -1D), 0D);
        assertEquals(0D, IrisStructureComponent.overboreBoundaryLimit(0D, 10D), 0D);
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(
                null, 0.25D, 0.5D, 1D));
        assertFalse(IrisStructureComponent.shouldCarveOverboreCell(
                null, 0.250001D, 0.5D, 1D));
    }

    @Test
    public void ordinaryMarkersAreSeparatedFromStructureMarkers() {
        assertTrue(IrisStructureComponent.isOrdinaryObjectMarker("trees/oak@42"));
        assertFalse(IrisStructureComponent.isOrdinaryObjectMarker(
                "@iris-structure:v1:dHJlZXMvb2Fr:42:bWluZWNyYWZ0Om1hbnNpb24"));
        assertFalse(IrisStructureComponent.isOrdinaryObjectMarker("not-a-marker"));
    }

    @Test
    public void collidingObjectSearchCoversTheWholeMarkerOwnedObject() {
        IrisStructureComponent.ObjectMarkerBounds bounds = IrisStructureComponent.markerSearchBounds(
                20, 64, -8, 21, 66, -7, 7, 12, 384);

        assertEquals(14, bounds.minX());
        assertEquals(53, bounds.minY());
        assertEquals(-14, bounds.minZ());
        assertEquals(27, bounds.maxX());
        assertEquals(77, bounds.maxY());
        assertEquals(-1, bounds.maxZ());
    }

    @Test
    public void collidingObjectSearchClampsToTheWorldHeight() {
        IrisStructureComponent.ObjectMarkerBounds bounds = IrisStructureComponent.markerSearchBounds(
                0, 2, 0, 0, 379, 0, 1, 12, 384);

        assertEquals(0, bounds.minY());
        assertEquals(383, bounds.maxY());
    }
}

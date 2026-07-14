package art.arcane.iris.engine.mantle.components;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisStructureComponentOverboreTest {
    @Test
    public void exactPieceInteriorAlwaysCarves() {
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(0.0, 0.0));
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(0.0, 1.0));
    }

    @Test
    public void lowNoiseRemovesTheFormerFlatShell() {
        double oneBlockAtRadiusSix = 1.0 / 6.0;
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(
                oneBlockAtRadiusSix * oneBlockAtRadiusSix, 0.0));
        assertFalse(IrisStructureComponent.shouldCarveOverboreCell(0.25 * 0.25, 0.0));
    }

    @Test
    public void maximumNoiseStopsAtConfiguredReach() {
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(1.0, 1.0));
        assertFalse(IrisStructureComponent.shouldCarveOverboreCell(1.000001, 1.0));
    }

    @Test
    public void diagonalCornersUseEuclideanFalloff() {
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(1.0, 1.0));
        assertFalse(IrisStructureComponent.shouldCarveOverboreCell(0.8 * 0.8 + 0.8 * 0.8, 1.0));
    }

    @Test
    public void boundaryNoiseIsClampedAndDeterministic() {
        assertEquals(0.2, IrisStructureComponent.overboreBoundaryLimit(-10.0), 0.0);
        assertEquals(1.0, IrisStructureComponent.overboreBoundaryLimit(10.0), 0.0);
        assertTrue(IrisStructureComponent.shouldCarveOverboreCell(0.36, 0.5));
        assertFalse(IrisStructureComponent.shouldCarveOverboreCell(0.360001, 0.5));
    }

    @Test
    public void candidateExtensionsDoNotScanImpossibleOuterShell() {
        assertEquals(6, IrisStructureComponent.overboreSideExtension(6));
        assertEquals(18, IrisStructureComponent.overboreUpExtension(10.0));

        long oldVolume = (41L + 18L) * (31L + 27L) * (35L + 18L);
        long newVolume = (41L + 12L) * (31L + 18L) * (35L + 12L);
        assertTrue(newVolume < oldVolume);
    }

    @Test
    public void ordinaryMarkersAreSeparatedFromStructureMarkers() {
        assertTrue(IrisStructureComponent.isOrdinaryObjectMarker("trees/oak@42"));
        assertFalse(IrisStructureComponent.isOrdinaryObjectMarker(
                "@iris-structure:v1:dHJlZXMvb2Fr:42:bWluZWNyYWZ0Om1hbnNpb24"));
        assertFalse(IrisStructureComponent.isOrdinaryObjectMarker("not-a-marker"));
    }
}

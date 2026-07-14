/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine.framework;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.engine.object.StructureDistribution;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IrisStructureLocatorContractTest {
    @Test
    public void placedKeysIsEmptyForNullEngine() {
        assertTrue(IrisStructureLocator.placedKeys(null).isEmpty());
    }

    @Test
    public void isPlacedIsFalseForNullEngine() {
        assertFalse(IrisStructureLocator.isPlaced(null, "minecraft:ancient_city"));
    }

    @Test
    public void isPlacedIsFalseForNullOrEmptyKey() {
        Engine engine = mock(Engine.class);
        assertFalse(IrisStructureLocator.isPlaced(engine, null));
        assertFalse(IrisStructureLocator.isPlaced(engine, ""));
    }

    @Test
    public void suppressesVanillaIsFalseForNullEngine() {
        assertFalse(IrisStructureLocator.suppressesVanilla(null, "minecraft:ancient_city"));
    }

    @Test
    public void suppressesVanillaIsFalseForNullOrEmptyKey() {
        Engine engine = mock(Engine.class);
        assertFalse(IrisStructureLocator.suppressesVanilla(engine, null));
        assertFalse(IrisStructureLocator.suppressesVanilla(engine, ""));
    }

    @Test
    public void startsInChunkIsFalseForNullEngine() {
        assertFalse(IrisStructureLocator.startsInChunk(null, "minecraft:ancient_city", 0, 0));
    }

    @Test
    public void locateReturnsNotFoundForNullEngine() {
        assertEquals(IrisStructureLocator.LocateStatus.NOT_FOUND,
                IrisStructureLocator.locate(null, "minecraft:village_taiga", 0, 0, 100).status());
    }

    @Test
    public void locateReturnsNotFoundForNullOrEmptyKey() {
        Engine engine = mock(Engine.class);
        assertEquals(IrisStructureLocator.LocateStatus.NOT_FOUND,
                IrisStructureLocator.locate(engine, null, 0, 0, 100).status());
        assertEquals(IrisStructureLocator.LocateStatus.NOT_FOUND,
                IrisStructureLocator.locate(engine, "", 0, 0, 100).status());
    }

    @Test
    public void zeroDensityLocateReturnsWithoutScanningPlacementGraphs() {
        Engine engine = densityEngine(0.0, false, -64, 384, -2032, 2032);

        IrisStructureLocator.LocateResult result =
                IrisStructureLocator.locate(engine, "test:density", 0, 0, 100);

        assertEquals(IrisStructureLocator.LocateStatus.NOT_FOUND, result.status());
        verify(engine, never()).getComplex();
    }

    @Test
    public void impossibleDensityHeightBandReturnsWithoutScanningPlacementGraphs() {
        Engine engine = densityEngine(1.0, true, -64, 384, 500, 600);

        IrisStructureLocator.LocateResult result =
                IrisStructureLocator.locate(engine, "test:density", 0, 0, 100);

        assertEquals(IrisStructureLocator.LocateStatus.NOT_FOUND, result.status());
        verify(engine, never()).getComplex();
    }

    @Test
    public void densityLocateReportsSafetyLimitAfterFixedCandidateBudget() {
        Engine engine = densityEngine(1.0, false, -64, 384, -2032, 2032);

        IrisStructureLocator.LocateResult result =
                IrisStructureLocator.locate(engine, "test:density", 0, 0, 2048);

        assertEquals(IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED, result.status());
        assertFalse(result.found());
        verify(engine, times(4_096)).getComplex();
    }

    @Test
    public void searchableDensityRequiresPositiveProbabilityAndWorldHeightOverlap() {
        Engine engine = mock(Engine.class);
        when(engine.getMinHeight()).thenReturn(-64);
        when(engine.getHeight()).thenReturn(384);
        IrisStructurePlacement placement = new IrisStructurePlacement();
        placement.setDistribution(StructureDistribution.DENSITY);
        placement.setMinHeight(-32);
        placement.setMaxHeight(128);

        placement.setDensity(Double.NaN);
        assertFalse(IrisStructureLocator.isSearchableDensityPlacement(engine, placement));
        placement.setDensity(0.0);
        assertFalse(IrisStructureLocator.isSearchableDensityPlacement(engine, placement));
        placement.setDensity(0.01);
        assertTrue(IrisStructureLocator.isSearchableDensityPlacement(engine, placement));
        placement.setMinHeight(400);
        placement.setMaxHeight(500);
        assertFalse(IrisStructureLocator.isSearchableDensityPlacement(engine, placement));
    }

    @Test
    public void invalidateIsNullSafe() {
        IrisStructureLocator.invalidate(null);
    }

    @Test
    public void placementIndexIsScopedAndInvalidatedPerEngine() {
        IrisData sharedData = mock(IrisData.class);
        Engine firstEngine = emptyEngine(sharedData);
        Engine secondEngine = emptyEngine(sharedData);
        Set<String> firstIndex = IrisStructureLocator.placedKeys(firstEngine);
        Set<String> secondIndex = IrisStructureLocator.placedKeys(secondEngine);
        assertNotSame(firstIndex, secondIndex);

        IrisStructureLocator.invalidate(firstEngine);
        assertNotSame(firstIndex, IrisStructureLocator.placedKeys(firstEngine));
    }

    @Test
    public void assembledBoundsMustFitVerticallyAndHorizontally() {
        KList<PlacedStructurePiece> fitting = new KList<>();
        fitting.add(piece(-4, 1, -4, 4, 20, 4));
        assertTrue(IrisStructureLocator.fitsWorldBounds(fitting, 0, 20, 0, 0, 4));

        KList<PlacedStructurePiece> belowWorld = new KList<>();
        belowWorld.add(piece(-4, -1, -4, 4, 20, 4));
        assertFalse(IrisStructureLocator.fitsWorldBounds(belowWorld, 0, 20, 0, 0, 4));

        KList<PlacedStructurePiece> outsideRadius = new KList<>();
        outsideRadius.add(piece(-4, 1, -4, 5, 20, 4));
        assertFalse(IrisStructureLocator.fitsWorldBounds(outsideRadius, 0, 20, 0, 0, 4));
    }

    @Test
    public void emptyAssemblyDoesNotFit() {
        assertFalse(IrisStructureLocator.fitsWorldBounds(new KList<>(), 0, 20, 0, 0, 4));
    }

    @Test
    public void selectedStructureKeyDoesNotMatchUnselectedPlacementMember() {
        IrisStructurePlacement placement = new IrisStructurePlacement();
        placement.getStructures().add("test:first");
        placement.getStructures().add("test:second");
        RNG selectsLast = new RNG(1L) {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };
        String selectedKey = IrisStructureLocator.selectStructureKey(placement, selectsLast);
        assertEquals("test:second", selectedKey);

        IrisStructure selected = new IrisStructure();
        selected.setLoadKey("test:second");
        selected.setVanillaSource("minecraft:second");
        IrisStructureLocator.ResolvedPlacement resolved = new IrisStructureLocator.ResolvedPlacement(
                placement, selectedKey, selected, new KList<>(), selectsLast, 0, 64, 0, false);
        assertFalse(IrisStructureLocator.matchesResolved(resolved, "test:first"));
        assertTrue(IrisStructureLocator.matchesResolved(resolved, "test:second"));
        assertTrue(IrisStructureLocator.matchesResolved(resolved, "minecraft:second"));
    }

    @Test
    public void undergroundAssemblyIsShiftedToFitOrRejectedWhole() {
        IrisStructurePlacement placement = new IrisStructurePlacement();
        placement.setUnderground(true);
        placement.setMinHeight(-10);
        placement.setMaxHeight(10);

        KList<PlacedStructurePiece> movable = new KList<>();
        movable.add(piece(-4, -10, -4, 4, 5, 4));
        assertEquals(Integer.valueOf(10), IrisStructureLocator.resolveVerticalShift(movable, placement, -10, 0, 319));

        KList<PlacedStructurePiece> tooTall = new KList<>();
        tooTall.add(piece(-4, -100, -4, 4, 400, 4));
        assertNull(IrisStructureLocator.resolveVerticalShift(tooTall, placement, 0, 0, 319));

        placement.setUnderground(false);
        KList<PlacedStructurePiece> clippedSurface = new KList<>();
        clippedSurface.add(piece(-4, 300, -4, 4, 330, 4));
        assertNull(IrisStructureLocator.resolveVerticalShift(clippedSurface, placement, 300, -64, 319));
    }

    @Test
    public void exactYOnlyCoversPlacementPathsWithResolvedAbsoluteAnchors() {
        IrisStructurePlacement placement = new IrisStructurePlacement();
        IrisStructure structure = new IrisStructure();
        structure.setPlaceMode(ObjectPlaceMode.CENTER_HEIGHT);
        KList<PlacedStructurePiece> onePiece = new KList<>();
        onePiece.add(piece(-4, 1, -4, 4, 20, 4));
        assertFalse(IrisStructureLocator.hasExactY(placement, structure, onePiece));

        structure.setPlaceMode(ObjectPlaceMode.STRUCTURE_PIECE);
        assertTrue(IrisStructureLocator.hasExactY(placement, structure, onePiece));

        structure.setPlaceMode(ObjectPlaceMode.CENTER_HEIGHT);
        placement.setUnderground(true);
        assertTrue(IrisStructureLocator.hasExactY(placement, structure, onePiece));

        placement.setUnderground(false);
        KList<PlacedStructurePiece> multiplePieces = new KList<>(onePiece);
        multiplePieces.add(piece(5, 1, -4, 12, 20, 4));
        assertTrue(IrisStructureLocator.hasExactY(placement, structure, multiplePieces));
    }

    @Test
    public void randomSpreadCellRingStopUsesGeometricLowerBound() {
        assertEquals(0L, IrisStructureLocator.cellRingDistanceLowerBound(0, 32, 31, 8, 0, 0));
        assertEquals(1L, IrisStructureLocator.cellRingDistanceLowerBound(1, 32, 31, 8, 0, 0));
        assertEquals(33L, IrisStructureLocator.cellRingDistanceLowerBound(2, 32, 31, 8, 0, 0));
        assertEquals(1L, IrisStructureLocator.cellRingDistanceLowerBound(1, 32, 0, 0, 0, 0));
    }

    private PlacedStructurePiece piece(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new PlacedStructurePiece(null, null, 0, 0, 0, null, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private Engine emptyEngine(IrisData data) {
        Engine engine = mock(Engine.class);
        IrisDimension dimension = mock(IrisDimension.class);
        when(engine.getData()).thenReturn(data);
        when(engine.getDimension()).thenReturn(dimension);
        when(dimension.getStructures()).thenReturn(new KList<>());
        when(dimension.getAllRegions(engine)).thenReturn(new KList<>());
        when(dimension.getReachableBiomes(engine)).thenReturn(new KList<>());
        return engine;
    }

    private Engine densityEngine(double density, boolean underground, int worldMin, int worldHeight,
                                 int placementMin, int placementMax) {
        IrisData data = mock(IrisData.class);
        IrisStructure structure = new IrisStructure();
        structure.setLoadKey("test:density");
        when(data.load(IrisStructure.class, "test:density", false)).thenReturn(structure);

        IrisStructurePlacement placement = new IrisStructurePlacement();
        placement.getStructures().add("test:density");
        placement.setDistribution(StructureDistribution.DENSITY);
        placement.setDensity(density);
        placement.setUnderground(underground);
        placement.setMinHeight(placementMin);
        placement.setMaxHeight(placementMax);

        Engine engine = mock(Engine.class);
        IrisDimension dimension = mock(IrisDimension.class);
        KList<IrisStructurePlacement> placements = new KList<>();
        placements.add(placement);
        when(engine.getData()).thenReturn(data);
        when(engine.getDimension()).thenReturn(dimension);
        when(engine.getSeedManager()).thenReturn(new SeedManager(77L));
        when(engine.getMinHeight()).thenReturn(worldMin);
        when(engine.getHeight()).thenReturn(worldHeight);
        when(dimension.getStructures()).thenReturn(placements);
        when(dimension.getAllRegions(engine)).thenReturn(new KList<>());
        when(dimension.getReachableBiomes(engine)).thenReturn(new KList<>());
        return engine;
    }
}

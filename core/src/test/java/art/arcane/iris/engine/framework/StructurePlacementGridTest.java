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

import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.StructureDistribution;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StructurePlacementGridTest {
    @Test
    public void randomSpreadStartIsDeterministic() {
        boolean first = StructurePlacementGrid.randomSpreadStart(5, 7, 32, 8, 165745296, 12345L);
        boolean second = StructurePlacementGrid.randomSpreadStart(5, 7, 32, 8, 165745296, 12345L);
        assertEquals(first, second);
    }

    @Test
    public void randomSpreadStartYieldsExactlyOneStartPerGridCell() {
        int spacing = 32;
        int separation = 8;
        int salt = 165745296;
        long seed = 99L;
        int count = 0;
        for (int cx = 0; cx < spacing; cx++) {
            for (int cz = 0; cz < spacing; cz++) {
                if (StructurePlacementGrid.randomSpreadStart(cx, cz, spacing, separation, salt, seed)) {
                    count++;
                }
            }
        }
        assertEquals(1, count);
    }

    @Test
    public void randomSpreadStartVariesAcrossCells() {
        int spacing = 32;
        int separation = 8;
        int salt = 165745296;
        long seed = 99L;
        int starts = 0;
        for (int cellX = 0; cellX < 4; cellX++) {
            for (int cellZ = 0; cellZ < 4; cellZ++) {
                for (int x = 0; x < spacing; x++) {
                    for (int z = 0; z < spacing; z++) {
                        int cx = cellX * spacing + x;
                        int cz = cellZ * spacing + z;
                        if (StructurePlacementGrid.randomSpreadStart(cx, cz, spacing, separation, salt, seed)) {
                            starts++;
                        }
                    }
                }
            }
        }
        assertEquals(16, starts);
    }

    @Test
    public void mixIsDeterministicAndSensitiveToInputs() {
        assertEquals(StructurePlacementGrid.mix(1L, 2, 3, 4), StructurePlacementGrid.mix(1L, 2, 3, 4));
        assertNotEquals(StructurePlacementGrid.mix(1L, 2, 3, 4), StructurePlacementGrid.mix(1L, 2, 3, 5));
        assertNotEquals(StructurePlacementGrid.mix(1L, 2, 3, 4), StructurePlacementGrid.mix(2L, 2, 3, 4));
    }

    @Test
    public void differentSaltsShiftStartPositions() {
        int spacing = 32;
        int separation = 8;
        long seed = 7L;
        int matches = 0;
        int total = 0;
        for (int cx = 0; cx < spacing; cx++) {
            for (int cz = 0; cz < spacing; cz++) {
                boolean a = StructurePlacementGrid.randomSpreadStart(cx, cz, spacing, separation, 10387312, seed);
                boolean b = StructurePlacementGrid.randomSpreadStart(cx, cz, spacing, separation, 165745296, seed);
                if (a) {
                    total++;
                }
                if (a && b) {
                    matches++;
                }
            }
        }
        assertEquals(1, total);
        assertTrue(matches <= total);
    }

    @Test
    public void densityStartIsDeterministicAndOrdinalScoped() {
        IrisStructurePlacement placement = placement(StructureDistribution.DENSITY);
        placement.setDensity(0.35);
        boolean differsByOrdinal = false;
        for (int cx = -32; cx <= 32; cx++) {
            for (int cz = -32; cz <= 32; cz++) {
                boolean first = StructurePlacementGrid.startsInChunk(placement, cx, cz, 912345L, 0);
                boolean repeated = StructurePlacementGrid.startsInChunk(placement, cx, cz, 912345L, 0);
                boolean secondPlacement = StructurePlacementGrid.startsInChunk(placement, cx, cz, 912345L, 1);
                assertEquals(first, repeated);
                differsByOrdinal |= first != secondPlacement;
            }
        }
        assertTrue(differsByOrdinal);
    }

    @Test
    public void densityBoundariesNeverAndAlwaysStart() {
        IrisStructurePlacement placement = placement(StructureDistribution.DENSITY);
        placement.setDensity(0.0);
        assertFalse(StructurePlacementGrid.startsInChunk(placement, 4, -9, 77L, 0));
        placement.setDensity(1.0);
        assertTrue(StructurePlacementGrid.startsInChunk(placement, 4, -9, 77L, 0));
    }

    @Test
    public void concentricRingCountIsFiniteTotalPlacementCount() {
        IrisStructurePlacement placement = placement(StructureDistribution.CONCENTRIC_RINGS);
        placement.setRingCount(10);
        placement.setRingSpread(3);
        placement.setRingDistance(12);
        long seed = 484848L;
        int lastRing = Math.ceilDiv(placement.getRingCount(), placement.getRingSpread());
        int scanRadius = (lastRing + 2) * placement.getRingDistance();
        int starts = 0;
        for (int cx = -scanRadius; cx <= scanRadius; cx++) {
            for (int cz = -scanRadius; cz <= scanRadius; cz++) {
                if (StructurePlacementGrid.startsInChunk(placement, cx, cz, seed, 0)) {
                    starts++;
                }
            }
        }
        assertEquals(placement.getRingCount(), starts);
        assertNull(StructurePlacementGrid.concentricRingChunk(placement, placement.getRingCount(), seed));
    }

    @Test
    public void placementRngIsStableAndOrdinalScoped() {
        IrisStructurePlacement placement = placement(StructureDistribution.RANDOM_SPREAD);
        long first = StructurePlacementGrid.placementRng(placement, 7, -11, 123L, 0).getSeed();
        long repeated = StructurePlacementGrid.placementRng(placement, 7, -11, 123L, 0).getSeed();
        long secondPlacement = StructurePlacementGrid.placementRng(placement, 7, -11, 123L, 1).getSeed();
        assertEquals(first, repeated);
        assertNotEquals(first, secondPlacement);
    }

    private IrisStructurePlacement placement(StructureDistribution distribution) {
        IrisStructurePlacement placement = new IrisStructurePlacement();
        placement.setDistribution(distribution);
        placement.getStructures().add("test:structure");
        placement.setSalt(31415926);
        return placement;
    }
}

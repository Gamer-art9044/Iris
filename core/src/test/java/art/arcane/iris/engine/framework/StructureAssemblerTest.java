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
import art.arcane.iris.engine.object.IrisDirection;
import art.arcane.iris.engine.object.IrisJigsawConnector;
import art.arcane.iris.engine.object.IrisJigsawPiece;
import art.arcane.iris.engine.object.IrisJigsawPieceEntry;
import art.arcane.iris.engine.object.IrisJigsawPool;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.JigsawJoint;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StructureAssemblerTest {
    @BeforeClass
    public static void bindPlatform() {
        IrisPlatforms.unbind();
        PlatformBlockState block = mock(PlatformBlockState.class);
        PlatformRegistries registries = mock(PlatformRegistries.class);
        when(registries.block(anyString())).thenReturn(block);
        IrisPlatform platform = mock(IrisPlatform.class);
        when(platform.registries()).thenReturn(registries);
        IrisPlatforms.bind(platform);
    }

    @AfterClass
    public static void unbindPlatform() {
        IrisPlatforms.unbind();
    }

    @Test
    public void attachedNonRotatablePieceKeepsItsAuthoredFacing() {
        Fixture fixture = new Fixture();
        IrisJigsawConnector startConnector = connector(IrisDirection.EAST_POSITIVE_X, "target", "start", "door");
        IrisJigsawPiece startPiece = piece("start-object", false, startConnector);
        IrisJigsawConnector candidateConnector = connector(IrisDirection.NORTH_NEGATIVE_Z, "", "door", "");
        IrisJigsawPiece candidatePiece = piece("candidate-object", false, candidateConnector);
        fixture.pool("start", pool("", "start-piece"));
        fixture.pool("target", pool("", "candidate-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.piece("candidate-piece", candidatePiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));
        fixture.object("candidate-object", new IrisObject(1, 1, 1));

        KList<PlacedStructurePiece> fixed = fixture.assembler(structure("start", 1), 0, 0, 0).assemble(new RNG(11L));

        assertNull(fixed);

        candidatePiece.setRotatable(true);
        KList<PlacedStructurePiece> rotatable = fixture.assembler(structure("start", 1), 0, 0, 0).assemble(new RNG(11L));

        assertEquals(2, rotatable.size());
        assertSame(candidatePiece, rotatable.get(1).getPiece());
    }

    @Test
    public void fallbackCandidatesAreTriedAfterPrimaryCandidatesFail() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false,
                connector(IrisDirection.EAST_POSITIVE_X, "branch", "start", "door"));
        IrisJigsawPiece incompatiblePiece = piece("incompatible-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "wrong", ""));
        IrisJigsawPiece fallbackPiece = piece("fallback-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "door", ""));
        fixture.pool("start", pool("", "start-piece"));
        fixture.pool("branch", pool("fallback", "incompatible-piece"));
        fixture.pool("fallback", pool("", "fallback-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.piece("incompatible-piece", incompatiblePiece);
        fixture.piece("fallback-piece", fallbackPiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));
        fixture.object("incompatible-object", new IrisObject(1, 1, 1));
        fixture.object("fallback-object", new IrisObject(1, 1, 1));

        KList<PlacedStructurePiece> placed = fixture.assembler(structure("start", 2), 0, 0, 0).assemble(new RNG(29L));

        assertEquals(2, placed.size());
        assertSame(fallbackPiece, placed.get(1).getPiece());
    }

    @Test
    public void alignedRuntimeConnectorsPreserveTopOrientation() {
        Fixture fixture = new Fixture();
        IrisJigsawConnector source = connector(IrisDirection.EAST_POSITIVE_X, "target", "start", "door")
                .setJoint(JigsawJoint.ALIGNED)
                .setTop(IrisDirection.UP_POSITIVE_Y);
        IrisJigsawConnector target = connector(IrisDirection.WEST_NEGATIVE_X, "target", "door", "unused")
                .setTop(IrisDirection.DOWN_NEGATIVE_Y);
        IrisJigsawPiece startPiece = piece("start-object", false, source);
        IrisJigsawPiece targetPiece = piece("target-object", false, target);
        fixture.pool("start", pool("", "start-piece"));
        fixture.pool("target", pool("", "target-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.piece("target-piece", targetPiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));
        fixture.object("target-object", new IrisObject(1, 1, 1));

        assertNull(fixture.assembler(structure("start", 1), 0, 0, 0).assemble(new RNG(13L)));

        target.setTop(IrisDirection.UP_POSITIVE_Y);
        KList<PlacedStructurePiece> placed = fixture.assembler(structure("start", 1), 0, 0, 0)
                .assemble(new RNG(13L));
        assertEquals(2, placed.size());
    }

    @Test
    public void fallbackAtMaximumDepthDoesNotExpandAgain() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false,
                connector(IrisDirection.EAST_POSITIVE_X, "branch", "start", "door"));
        IrisJigsawPiece branchPiece = piece("branch-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "door", ""),
                connector(IrisDirection.EAST_POSITIVE_X, "terminal", "branch", "door"));
        IrisJigsawPiece primaryTerminal = piece("primary-terminal-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "door", ""));
        IrisJigsawPiece fallbackTerminal = piece("fallback-terminal-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "door", ""),
                connector(IrisDirection.EAST_POSITIVE_X, "terminal", "terminal", "door"));
        fixture.pool("start", pool("", "start-piece"));
        fixture.pool("branch", pool("", "branch-piece"));
        fixture.pool("terminal", pool("terminal-fallback", "primary-terminal-piece"));
        fixture.pool("terminal-fallback", pool("", "fallback-terminal-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.piece("branch-piece", branchPiece);
        fixture.piece("primary-terminal-piece", primaryTerminal);
        fixture.piece("fallback-terminal-piece", fallbackTerminal);
        fixture.object("start-object", new IrisObject(1, 1, 1));
        fixture.object("branch-object", new IrisObject(1, 1, 1));
        fixture.object("primary-terminal-object", new IrisObject(1, 1, 1));
        fixture.object("fallback-terminal-object", new IrisObject(1, 1, 1));
        IrisStructure structure = structure("start", 1);
        structure.setMaxSizeChunks(1);

        KList<PlacedStructurePiece> placed = fixture.assembler(structure, 0, 0, 0).assemble(new RNG(41L));

        assertEquals(3, placed.size());
        assertSame(branchPiece, placed.get(1).getPiece());
        assertSame(fallbackTerminal, placed.get(2).getPiece());
    }

    @Test
    public void fallbackOfFallbackIsNotSelected() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false,
                connector(IrisDirection.EAST_POSITIVE_X, "branch", "start", "door"));
        IrisJigsawPiece incompatiblePiece = piece("incompatible-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "wrong", ""));
        fixture.pool("start", pool("", "start-piece"));
        fixture.pool("branch", pool("terminators", "incompatible-piece"));
        fixture.pool("terminators", pool("empty", "incompatible-piece"));
        fixture.pool("empty", pool(""));
        fixture.piece("start-piece", startPiece);
        fixture.piece("incompatible-piece", incompatiblePiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));
        fixture.object("incompatible-object", new IrisObject(1, 1, 1));

        assertNull(fixture.assembler(structure("start", 2), 0, 0, 0).assemble(new RNG(31L)));
    }

    @Test
    public void evenSizedBoundsContainExactlyTheObjectFootprint() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false);
        fixture.pool("start", pool("", "start-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.object("start-object", new IrisObject(4, 2, 6));

        PlacedStructurePiece placed = fixture.assembler(structure("start", 1), 10, 20, 30)
                .assemble(new RNG(53L)).getFirst();

        assertBounds(placed, 8, 19, 27, 11, 20, 32);
    }

    @Test
    public void rotatedEvenSizedBoundsPreserveAsymmetricCentering() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", true);
        fixture.pool("start", pool("", "start-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.object("start-object", new IrisObject(4, 2, 6));

        StructureAssembler assembler = fixture.assembler(structure("start", 1), 10, 20, 30);
        assertBounds(assembler.assemble(rotationIndex(1)).getFirst(), 7, 19, 29, 12, 20, 32);
        assertBounds(assembler.assemble(rotationIndex(2)).getFirst(), 9, 19, 28, 12, 20, 33);
        assertBounds(assembler.assemble(rotationIndex(3)).getFirst(), 8, 19, 28, 13, 20, 31);
    }

    @Test
    public void collisionUsesHalfOpenEdgesForInclusivePieceBounds() {
        PlacedStructurePiece origin = bounds(0, 0, 0, 0, 0, 0);
        PlacedStructurePiece overlap = bounds(0, 0, 0, 0, 0, 0);
        PlacedStructurePiece adjacent = bounds(1, 0, 0, 1, 0, 0);

        assertTrue(origin.intersects(overlap));
        assertFalse(origin.intersects(adjacent));
        assertFalse(adjacent.intersects(origin));
    }

    @Test
    public void maximumWeightsCannotOverflowRuntimeSelection() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece first = piece("first-object", false);
        IrisJigsawPiece second = piece("second-object", false);
        IrisJigsawPool start = new IrisJigsawPool();
        start.getPieces().add(new IrisJigsawPieceEntry("first-piece", Integer.MAX_VALUE));
        start.getPieces().add(new IrisJigsawPieceEntry("second-piece", Integer.MAX_VALUE));
        fixture.pool("start", start);
        fixture.piece("first-piece", first);
        fixture.piece("second-piece", second);
        fixture.object("first-object", new IrisObject(1, 1, 1));
        fixture.object("second-object", new IrisObject(1, 1, 1));

        for (long seed = 0; seed < 32; seed++) {
            KList<PlacedStructurePiece> placed = fixture.assembler(structure("start", 1), 0, 0, 0)
                    .assemble(new RNG(seed));
            assertEquals(1, placed.size());
        }
    }

    @Test
    public void malformedStartConnectorDataFailsLoudly() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false);
        startPiece.setConnectors(null);
        fixture.pool("start", pool("", "start-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));

        assertThrows(IllegalStateException.class,
                () -> fixture.assembler(structure("start", 1), 0, 0, 0).assemble(new RNG(3L)));
    }

    @Test
    public void corruptWeightedPeerFailsBeforeCandidateSelection() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false,
                connector(IrisDirection.EAST_POSITIVE_X, "branch", "start", "door"));
        IrisJigsawPiece validPiece = piece("valid-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "door", ""));
        IrisJigsawPiece fallbackPiece = piece("fallback-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "door", ""));
        fixture.pool("start", pool("", "start-piece"));
        fixture.pool("branch", pool("fallback", "valid-piece", "missing-piece"));
        fixture.pool("fallback", pool("", "fallback-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.piece("valid-piece", validPiece);
        fixture.piece("fallback-piece", fallbackPiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));
        fixture.object("valid-object", new IrisObject(1, 1, 1));
        fixture.object("fallback-object", new IrisObject(1, 1, 1));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.assembler(structure("start", 2), 0, 0, 0).assemble(new RNG(17L)));

        assertTrue(failure.getMessage().contains("branch"));
        assertTrue(failure.getMessage().contains("missing-piece"));
    }

    @Test
    public void corruptUnselectedStartEntryFailsBeforeCandidateSelection() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece validPiece = piece("valid-object", false);
        fixture.pool("start", pool("", "valid-piece", "missing-piece"));
        fixture.piece("valid-piece", validPiece);
        fixture.object("valid-object", new IrisObject(1, 1, 1));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.assembler(structure("start", 1), 0, 0, 0).assemble(new RNG(18L)));

        assertTrue(failure.getMessage().contains("start"));
        assertTrue(failure.getMessage().contains("missing-piece"));
    }

    @Test
    public void malformedNestedConnectorDoesNotFallThroughToAuthoredFallback() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false,
                connector(IrisDirection.EAST_POSITIVE_X, "branch", "start", "door"));
        IrisJigsawPiece malformedPiece = piece("malformed-object", false);
        malformedPiece.getConnectors().add((IrisJigsawConnector) null);
        IrisJigsawPiece fallbackPiece = piece("fallback-object", false,
                connector(IrisDirection.WEST_NEGATIVE_X, "", "door", ""));
        fixture.pool("start", pool("", "start-piece"));
        fixture.pool("branch", pool("fallback", "malformed-piece"));
        fixture.pool("fallback", pool("", "fallback-piece"));
        fixture.piece("start-piece", startPiece);
        fixture.piece("malformed-piece", malformedPiece);
        fixture.piece("fallback-piece", fallbackPiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));
        fixture.object("malformed-object", new IrisObject(1, 1, 1));
        fixture.object("fallback-object", new IrisObject(1, 1, 1));

        assertThrows(IllegalStateException.class,
                () -> fixture.assembler(structure("start", 2), 0, 0, 0).assemble(new RNG(19L)));
    }

    @Test
    public void emptyPoolChoiceTerminatesTheBranchWithoutPlacingAir() {
        Fixture fixture = new Fixture();
        IrisJigsawPiece startPiece = piece("start-object", false,
                connector(IrisDirection.EAST_POSITIVE_X, "terminal", "start", "door"));
        fixture.pool("start", pool("", "start-piece"));
        IrisJigsawPool terminal = new IrisJigsawPool();
        terminal.getPieces().add(new IrisJigsawPieceEntry().setEmpty(true).setWeight(1));
        fixture.pool("terminal", terminal);
        fixture.piece("start-piece", startPiece);
        fixture.object("start-object", new IrisObject(1, 1, 1));

        KList<PlacedStructurePiece> placed = fixture.assembler(structure("start", 2), 0, 0, 0)
                .assemble(new RNG(7L));

        assertEquals(1, placed.size());
        assertSame(startPiece, placed.getFirst().getPiece());
    }

    @Test
    public void emptyStartChoiceProducesAnIntentionalEmptyAssembly() {
        Fixture fixture = new Fixture();
        IrisJigsawPool start = new IrisJigsawPool();
        start.getPieces().add(new IrisJigsawPieceEntry().setEmpty(true).setWeight(1));
        fixture.pool("start", start);

        KList<PlacedStructurePiece> placed = fixture.assembler(structure("start", 1), 0, 0, 0)
                .assemble(new RNG(9L));

        assertTrue(placed.isEmpty());
    }

    @Test
    public void assemblerRejectsLimitsOutsideTheDeclaredContract() {
        Fixture fixture = new Fixture();
        IrisStructure deep = structure("start", 31);
        IrisStructure wide = structure("start", 1).setMaxSizeChunks(33);
        IrisStructure overflowing = structure("start", 1).setMaxSizeChunks(Integer.MAX_VALUE);

        assertThrows(IllegalStateException.class,
                () -> fixture.assembler(deep, 0, 0, 0).assemble(new RNG(1L)));
        assertThrows(IllegalStateException.class,
                () -> fixture.assembler(wide, 0, 0, 0).assemble(new RNG(1L)));
        assertThrows(IllegalStateException.class,
                () -> fixture.assembler(overflowing, 0, 0, 0).assemble(new RNG(1L)));
    }

    private static IrisJigsawConnector connector(IrisDirection direction, String pool, String name, String targetName) {
        return new IrisJigsawConnector()
                .setPosition(new IrisPosition())
                .setDirection(direction)
                .setPool(pool == null || pool.isBlank() ? "terminal" : pool)
                .setName(name)
                .setTargetName(targetName)
                .setJoint(JigsawJoint.ROLLABLE);
    }

    private static IrisJigsawPiece piece(String object, boolean rotatable, IrisJigsawConnector... connectors) {
        IrisJigsawPiece piece = new IrisJigsawPiece().setObject(object).setRotatable(rotatable);
        piece.getConnectors().add(connectors);
        return piece;
    }

    private static IrisJigsawPool pool(String fallback, String... pieces) {
        IrisJigsawPool pool = new IrisJigsawPool().setFallback(fallback);
        for (String piece : pieces) {
            pool.getPieces().add(new IrisJigsawPieceEntry().setPiece(piece).setWeight(1));
        }
        return pool;
    }

    private static IrisStructure structure(String startPool, int maxDepth) {
        return new IrisStructure().setStartPool(startPool).setMaxDepth(maxDepth).setMaxSizeChunks(8);
    }

    private static RNG rotationIndex(int index) {
        return new RNG(67L + index) {
            @Override
            public int i(int upperBound) {
                return upperBound == 4 ? index : 0;
            }
        };
    }

    private static PlacedStructurePiece bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new PlacedStructurePiece(null, null, 0, 0, 0, null, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void assertBounds(PlacedStructurePiece piece, int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ) {
        assertEquals(minX, piece.getMinX());
        assertEquals(minY, piece.getMinY());
        assertEquals(minZ, piece.getMinZ());
        assertEquals(maxX, piece.getMaxX());
        assertEquals(maxY, piece.getMaxY());
        assertEquals(maxZ, piece.getMaxZ());
    }

    private static final class Fixture {
        private final IrisData data = mock(IrisData.class);

        private Fixture() {
            when(data.load(IrisJigsawPool.class, "terminal", false)).thenReturn(new IrisJigsawPool());
        }

        private void pool(String key, IrisJigsawPool pool) {
            when(data.load(IrisJigsawPool.class, key, false)).thenReturn(pool);
        }

        private void piece(String key, IrisJigsawPiece piece) {
            when(data.load(IrisJigsawPiece.class, key, false)).thenReturn(piece);
        }

        private void object(String key, IrisObject object) {
            when(data.load(IrisObject.class, key, false)).thenReturn(object);
        }

        private StructureAssembler assembler(IrisStructure structure, int x, int y, int z) {
            return StructureAssembler.forData(data, structure, new IrisPosition(x, y, z));
        }
    }
}

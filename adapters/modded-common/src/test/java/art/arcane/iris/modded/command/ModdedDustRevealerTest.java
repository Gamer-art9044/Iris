package art.arcane.iris.modded.command;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModdedDustRevealerTest {
    @Test
    public void placementClassificationCoversObjectDecorationAndTerrain() {
        assertTrue(ModdedDustRevealer.placementLine(3, 10, 13, "tree")
                .contains("object/stilt 'tree'"));
        assertTrue(ModdedDustRevealer.placementLine(3, 10, 13, null)
                .contains("decoration/object/stilt"));
        assertTrue(ModdedDustRevealer.placementLine(0, 10, 10, "ore")
                .contains("buried object 'ore'"));
        assertTrue(ModdedDustRevealer.placementLine(-4, 10, 6, null)
                .contains("depth 4"));
    }

    @Test
    public void revealTraversalUsesDiagonalAdjacencyButNotDisconnectedBlocks() {
        Set<BlockPos> object = Set.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 1, 1),
                new BlockPos(3, 3, 3));
        List<BlockPos> hits = ModdedDustRevealer.collect(
                new BlockPos(0, 0, 0),
                "object",
                -64,
                -64,
                320,
                new AtomicBoolean(),
                (int x, int relativeY, int z) ->
                        object.contains(new BlockPos(x, relativeY - 64, z))
                                ? "object"
                                : null);

        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)), hits);
    }

    @Test
    public void cancelledRevealDoesNoTraversal() {
        List<BlockPos> hits = ModdedDustRevealer.collect(
                new BlockPos(0, 0, 0),
                "object",
                -64,
                -64,
                320,
                new AtomicBoolean(true),
                (int x, int relativeY, int z) -> "object");

        assertTrue(hits.isEmpty());
    }
}

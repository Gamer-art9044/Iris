package art.arcane.iris.nativegen;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NativeStructurePostProcessorMonumentTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void vanillaSeaLevelKeepsTheVanillaMonumentHeight() {
        StructureStart start = monumentStart(1337L);

        int offset = NativeStructurePostProcessor.applyVerticalPlacement(
                start, "minecraft:monument", 0, 63, -64, 320, false, (x, z) -> 0);

        assertEquals(0, offset);
        assertEquals(39, start.getBoundingBox().minY());
        assertEquals(61, start.getBoundingBox().maxY());
    }

    @Test
    public void shiftedSeaLevelMovesTheShellAndEveryRoomTogether() {
        StructureStart start = monumentStart(1337L);
        OceanMonumentPieces.MonumentBuilding building = monumentBuilding(start);
        List<StructurePiece> children = NativeStructurePostProcessor.monumentChildPieces(building);
        int[] childMinY = minimumYs(children);
        BoundingBox cachedBounds = start.getBoundingBox();

        int offset = NativeStructurePostProcessor.applyVerticalPlacement(
                start, "minecraft:monument", 0, 50, -256, 512, false, (x, z) -> 0);

        assertEquals(-13, offset);
        assertSame(cachedBounds, start.getBoundingBox());
        assertEquals(26, building.getBoundingBox().minY());
        assertEquals(48, building.getBoundingBox().maxY());
        assertEquals(26, start.getBoundingBox().minY());
        assertEquals(48, start.getBoundingBox().maxY());
        assertTrue(children.size() > 20);
        for (int i = 0; i < children.size(); i++) {
            assertEquals(childMinY[i] - 13, children.get(i).getBoundingBox().minY());
        }

        int repeatedOffset = NativeStructurePostProcessor.applyVerticalPlacement(
                start, "minecraft:monument", 0, 50, -256, 512, false, (x, z) -> 0);
        assertEquals(0, repeatedOffset);
    }

    @Test
    public void configuredOffsetIsRelativeToTheActualSeaLevel() {
        StructureStart start = monumentStart(1337L);

        int offset = NativeStructurePostProcessor.applyVerticalPlacement(
                start, "minecraft:monument", 3, 50, -256, 512, false, (x, z) -> 0);

        assertEquals(-10, offset);
        assertEquals(29, start.getBoundingBox().minY());
        assertEquals(51, start.getBoundingBox().maxY());
    }

    @Test
    public void vanillaReloadRegenerationIsRealignedBeforePlacement() {
        long seed = 1337L;
        ChunkPos chunkPos = new ChunkPos(0, 0);
        StructureStart initial = monumentStart(seed);
        NativeStructurePostProcessor.applyVerticalPlacement(
                initial, "minecraft:monument", 0, 50, -256, 512, false, (x, z) -> 0);

        PiecesContainer regenerated = OceanMonumentStructure.regeneratePiecesAfterLoad(
                chunkPos, seed, new PiecesContainer(initial.getPieces()));
        StructureStart reloaded = new StructureStart(
                monumentStructure(), chunkPos, 0, regenerated);

        assertEquals(39, reloaded.getBoundingBox().minY());
        int offset = NativeStructurePostProcessor.applyVerticalPlacement(
                reloaded, "minecraft:monument", 0, 50, -256, 512, false, (x, z) -> 0);
        assertEquals(-13, offset);
        assertEquals(26, reloaded.getBoundingBox().minY());
        assertEquals(48, reloaded.getBoundingBox().maxY());
    }

    @Test
    public void impossibleSeaLevelAlignmentFailsInsteadOfClippingTheMonument() {
        StructureStart start = monumentStart(1337L);
        try {
            NativeStructurePostProcessor.applyVerticalPlacement(
                    start, "minecraft:monument", 0, -50, -64, 320, false, (x, z) -> 0);
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("cannot align"));
            return;
        }
        throw new AssertionError("Expected an out-of-bounds monument alignment to fail");
    }

    private static StructureStart monumentStart(long seed) {
        ChunkPos chunkPos = new ChunkPos(0, 0);
        OceanMonumentPieces.MonumentBuilding building = new OceanMonumentPieces.MonumentBuilding(
                RandomSource.create(seed), -29, -29, Direction.NORTH);
        return new StructureStart(
                monumentStructure(), chunkPos, 0, new PiecesContainer(List.of(building)));
    }

    private static OceanMonumentStructure monumentStructure() {
        return new OceanMonumentStructure(new Structure.StructureSettings(HolderSet.empty()));
    }

    private static OceanMonumentPieces.MonumentBuilding monumentBuilding(StructureStart start) {
        return (OceanMonumentPieces.MonumentBuilding) start.getPieces().get(0);
    }

    private static int[] minimumYs(List<StructurePiece> pieces) {
        int[] ys = new int[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            ys[i] = pieces.get(i).getBoundingBox().minY();
        }
        return ys;
    }
}

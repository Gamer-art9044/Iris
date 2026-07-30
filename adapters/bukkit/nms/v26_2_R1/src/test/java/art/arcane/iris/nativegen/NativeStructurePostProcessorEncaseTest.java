package art.arcane.iris.nativegen;

import art.arcane.iris.engine.object.IrisMaterialPalette;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import art.arcane.iris.engine.object.IrisStructureYBand;
import art.arcane.volmlib.util.math.RNG;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidStructure;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NativeStructurePostProcessorEncaseTest {
    private static final long TEST_SEED = 1234L;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void encaseFillsAirAndLiquidWithoutOverwritingExistingTerrain() {
        StructureStart start = start(TerrainAdjustment.BURY, 64);
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        BoundingBox area = new BoundingBox(
                bounds.minX() - 1, bounds.minY() - 1, bounds.minZ() - 1,
                bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        put(blocks, bounds.minX(), bounds.minY(), bounds.minZ(), Blocks.DIRT.defaultBlockState());
        put(blocks, bounds.maxX(), bounds.minY(), bounds.maxZ(), Blocks.WATER.defaultBlockState());

        NativeStructurePostProcessor.integrateTerrain(
                world(blocks), area, "minecraft:stronghold", start,
                new IrisStructureTerrain()
                        .setMode(IrisStructureTerrainMode.ENCASE)
                        .setHorizontalPadding(1)
                        .setCeilingPadding(1)
                        .setFloorPadding(1),
                null);

        assertEquals(Blocks.DIRT.defaultBlockState(),
                state(blocks, bounds.minX(), bounds.minY(), bounds.minZ()));
        assertEquals(Blocks.STONE.defaultBlockState(),
                state(blocks, bounds.maxX(), bounds.minY(), bounds.maxZ()));
        assertEquals(Blocks.STONE.defaultBlockState(),
                state(blocks, bounds.minX() - 1, bounds.minY() - 1, bounds.minZ() - 1));
        assertEquals(Blocks.STONE.defaultBlockState(),
                state(blocks, bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1));
    }

    @Test
    public void defaultEncasePaletteSplitsStoneAboveZeroAndDeepslateBelow() {
        StructureStart start = start(TerrainAdjustment.BURY, -1);
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        Map<BlockPos, BlockState> blocks = new HashMap<>();

        NativeStructurePostProcessor.integrateTerrain(
                world(blocks), bounds, "minecraft:stronghold", start,
                new IrisStructureTerrain().setMode(IrisStructureTerrainMode.ENCASE),
                null);

        assertEquals(-1, bounds.minY());
        assertEquals(Blocks.DEEPSLATE.defaultBlockState(),
                state(blocks, bounds.minX(), -1, bounds.minZ()));
        assertEquals(Blocks.STONE.defaultBlockState(),
                state(blocks, bounds.minX(), 0, bounds.minZ()));
    }

    @Test
    public void configuredEncasePaletteReplacesTheDefaultMaterial() {
        StructureStart start = start(TerrainAdjustment.BURY, 64);
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        IrisMaterialPalette palette = new IrisMaterialPalette().qclear().qadd("minecraft:tuff");

        NativeStructurePostProcessor.integrateTerrain(
                world(blocks), bounds, "minecraft:stronghold", start,
                new IrisStructureTerrain()
                        .setMode(IrisStructureTerrainMode.ENCASE)
                        .setEncasePalette(palette),
                NativeStructurePostProcessorEncaseTest::tuffBlock);

        assertEquals(Blocks.TUFF.defaultBlockState(),
                state(blocks, bounds.minX(), bounds.minY(), bounds.minZ()));
    }

    @Test
    public void encaseFillRunsBeforeNativePlacement() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int prepareTerrain = source.indexOf("NativeStructurePostProcessor.prepareTerrain(");
        int placementLoop = source.indexOf("for (NativePlacementGroup group : placementGroups)", prepareTerrain);
        int placement = source.indexOf("placeVanillaStructure(world, structureManager, random", placementLoop);

        assertTrue(prepareTerrain >= 0);
        assertTrue(placementLoop > prepareTerrain);
        assertTrue(placement > placementLoop);
    }

    @Test
    public void buryAndEncapsulateAdaptationsAutoDefaultToEncase() {
        for (TerrainAdjustment adjustment : List.of(TerrainAdjustment.BURY, TerrainAdjustment.ENCAPSULATE)) {
            IrisStructureTerrain resolved = NativeStructurePostProcessor.resolveNativeTerrain(
                    start(adjustment, 64), null);

            assertEquals(IrisStructureTerrainMode.ENCASE, resolved.resolvedMode());
            assertEquals(3, resolved.getHorizontalPadding());
            assertEquals(3, resolved.getCeilingPadding());
            assertEquals(3, resolved.getFloorPadding());
            assertNull(resolved.getEncasePalette());
        }
    }

    @Test
    public void otherAdaptationsNeverAutoDefaultToEncase() {
        for (TerrainAdjustment adjustment : List.of(
                TerrainAdjustment.NONE, TerrainAdjustment.BEARD_THIN, TerrainAdjustment.BEARD_BOX)) {
            assertNull(NativeStructurePostProcessor.resolveNativeTerrain(
                    start(adjustment, 64), null));
        }
    }

    @Test
    public void explicitTerrainConfigurationWinsOverAutoEncase() {
        IrisStructureTerrain configured = new IrisStructureTerrain()
                .setMode(IrisStructureTerrainMode.SOURCE);

        assertSame(configured, NativeStructurePostProcessor.resolveNativeTerrain(
                start(TerrainAdjustment.BURY, 64), configured));
    }

    @Test
    public void encaseReservesTheNeighborChunkEnvelope() {
        StructureStart generated = start(TerrainAdjustment.BURY, 64);
        BoundingBox content = generated.getBoundingBox();

        StructureStart wrapped = NativeStructureReferenceEnvelope.wrap(
                generated,
                generated.getStructure(),
                0,
                null,
                new IrisStructureTerrain()
                        .setMode(IrisStructureTerrainMode.ENCASE)
                        .setHorizontalPadding(4));

        assertEquals(content.minX() - 4, wrapped.getBoundingBox().minX());
        assertEquals(content.maxX() + 4, wrapped.getBoundingBox().maxX());
        assertEquals(content.minZ() - 4, wrapped.getBoundingBox().minZ());
        assertEquals(content.maxZ() + 4, wrapped.getBoundingBox().maxZ());
        assertEquals(2, wrapped.getPieces().stream()
                .filter(NativeStructureReferenceEnvelope::isMarker)
                .count());
    }

    @Test
    public void yBandRelocatesTheStartMidpointDeterministically() {
        IrisStructureYBand band = new IrisStructureYBand().setMin(-120).setMax(-20);
        StructureStart first = start(TerrainAdjustment.BURY, 64);
        StructureStart second = start(TerrainAdjustment.BURY, 64);

        NativeStructurePostProcessor.applyVerticalShift(
                first, -64, -256, 320, true, false, band, (x, z) -> 40);
        NativeStructurePostProcessor.applyVerticalShift(
                second, -64, -256, 320, true, false, band, (x, z) -> 40);

        BoundingBox bounds = first.getBoundingBox();
        assertEquals(bounds.minY(), second.getBoundingBox().minY());
        assertTrue(bounds.minY() >= -120);
        assertTrue(bounds.maxY() <= -20);

        int repeated = NativeStructurePostProcessor.applyVerticalShift(
                first, -64, -256, 320, true, false, band, (x, z) -> 40);
        assertEquals(0, repeated);
    }

    @Test
    public void yBandClampsWhenTheBandCannotContainTheStructure() {
        IrisStructureYBand band = new IrisStructureYBand().setMin(-50).setMax(-45);
        StructureStart start = start(TerrainAdjustment.BURY, 64);

        NativeStructurePostProcessor.applyVerticalShift(
                start, 0, -256, 320, true, false, band, (x, z) -> 40);

        BoundingBox bounds = start.getBoundingBox();
        int midpoint = bounds.minY() + (bounds.maxY() - bounds.minY()) / 2;
        assertTrue(midpoint >= -50);
        assertTrue(midpoint <= -45);
    }

    @Test
    public void yBandReplacesBothTheBlindShiftAndBurial() {
        IrisStructureYBand band = new IrisStructureYBand().setMin(-64).setMax(-64);
        StructureStart start = start(TerrainAdjustment.BURY, 64);
        BoundingBox bounds = start.getBoundingBox();
        int height = bounds.maxY() - bounds.minY();

        NativeStructurePostProcessor.applyVerticalShift(
                start, -200, -256, 320, true, false, band, (x, z) -> 40);

        assertEquals(-64 - height / 2, start.getBoundingBox().minY());
    }

    @Test
    public void preserveSourceYWinsOverTheConfiguredBand() {
        IrisStructureYBand band = new IrisStructureYBand().setMin(-120).setMax(-20);
        StructureStart start = start(TerrainAdjustment.BURY, 64);
        int minY = start.getBoundingBox().minY();

        int offset = NativeStructurePostProcessor.applyVerticalShift(
                start, 0, -256, 320, true, true, band, (x, z) -> 40);

        assertEquals(0, offset);
        assertEquals(minY, start.getBoundingBox().minY());
    }

    private static BlockState tuffBlock(IrisMaterialPalette palette, RNG rng, int x, int y, int z) {
        assertNotNull(palette);
        assertNotNull(rng);
        return Blocks.TUFF.defaultBlockState();
    }

    private static StructureStart start(TerrainAdjustment adjustment, int minY) {
        Structure structure = new DesertPyramidStructure(new Structure.StructureSettings(
                HolderSet.empty(), Map.of(), GenerationStep.Decoration.STRONGHOLDS, adjustment));
        StructurePiece piece = new DesertPyramidPiece(RandomSource.create(7L), 0, 0);
        piece.move(0, minY - piece.getBoundingBox().minY(), 0);
        return new StructureStart(
                structure, new ChunkPos(0, 0), 0, new PiecesContainer(List.of(piece)));
    }

    private static WorldGenLevel world(Map<BlockPos, BlockState> blocks) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            String methodName = method.getName();
            if (methodName.equals("getBlockState")) {
                BlockPos position = (BlockPos) arguments[0];
                return state(blocks, position.getX(), position.getY(), position.getZ());
            }
            if (methodName.equals("setBlock")) {
                BlockPos position = (BlockPos) arguments[0];
                BlockState blockState = (BlockState) arguments[1];
                put(blocks, position.getX(), position.getY(), position.getZ(), blockState);
                return true;
            }
            if (methodName.equals("getSeed")) {
                return TEST_SEED;
            }
            if (methodName.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (methodName.equals("equals")) {
                return proxy == arguments[0];
            }
            if (methodName.equals("toString")) {
                return "encase-test-world";
            }
            throw new UnsupportedOperationException(method.toString());
        };
        return (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, handler);
    }

    private static void put(Map<BlockPos, BlockState> blocks,
                           int x, int y, int z, BlockState state) {
        blocks.put(new BlockPos(x, y, z), state);
    }

    private static BlockState state(Map<BlockPos, BlockState> blocks, int x, int y, int z) {
        return blocks.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
    }
}

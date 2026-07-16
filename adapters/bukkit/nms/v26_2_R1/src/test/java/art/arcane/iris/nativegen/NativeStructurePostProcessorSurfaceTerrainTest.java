package art.arcane.iris.nativegen;

import com.mojang.datafixers.util.Either;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeStructurePostProcessorSurfaceTerrainTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void onlySurfaceBeardThinStructuresPrepareTerrain() {
        assertTrue(NativeStructurePostProcessor.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.BEARD_THIN, GenerationStep.Decoration.SURFACE_STRUCTURES));
        assertFalse(NativeStructurePostProcessor.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.BURY, GenerationStep.Decoration.SURFACE_STRUCTURES));
        assertFalse(NativeStructurePostProcessor.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.BEARD_BOX, GenerationStep.Decoration.SURFACE_STRUCTURES));
        assertFalse(NativeStructurePostProcessor.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.ENCAPSULATE, GenerationStep.Decoration.SURFACE_STRUCTURES));
    }

    @Test
    public void undergroundAdjustmentsNeverPrepareSurfaceTerrain() {
        for (TerrainAdjustment adjustment : List.of(
                TerrainAdjustment.BEARD_THIN,
                TerrainAdjustment.BURY,
                TerrainAdjustment.BEARD_BOX,
                TerrainAdjustment.ENCAPSULATE)) {
            assertFalse(NativeStructurePostProcessor.shouldPrepareSurfaceTerrain(
                    adjustment, GenerationStep.Decoration.UNDERGROUND_STRUCTURES));
        }
    }

    @Test
    public void surfaceAnchorIsFlushInsideAndUnchangedAtRadius() {
        NativeStructurePostProcessor.SurfaceAnchor anchor = anchor(80, 2);

        assertEquals(80, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(anchor), 2, 2, 64));
        assertEquals(64, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(anchor), 16, 2, 64));
        assertEquals(64, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(anchor), 17, 2, 64));
    }

    @Test
    public void surfaceAnchorRaisesAndLowersThroughTheTaper() {
        NativeStructurePostProcessor.SurfaceAnchor raised = anchor(80, 2);
        NativeStructurePostProcessor.SurfaceAnchor lowered = anchor(64, 2);

        assertEquals(68, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(raised), 10, 2, 64));
        assertEquals(76, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(lowered), 10, 2, 80));
    }

    @Test
    public void containingRigidFloorsHaveDeterministicPriority() {
        NativeStructurePostProcessor.SurfaceAnchor rigid = anchor(70, 2);
        NativeStructurePostProcessor.SurfaceAnchor junction = anchor(90, 1);

        assertEquals(70, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(rigid, junction), 2, 2, 64));
        assertEquals(70, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(junction, rigid), 2, 2, 64));

        NativeStructurePostProcessor.SurfaceAnchor weakTie = anchor(48, 1);
        NativeStructurePostProcessor.SurfaceAnchor strongTie = anchor(80, 2);
        assertEquals(80, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(weakTie, strongTie), 2, 2, 64));
        assertEquals(80, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(strongTie, weakTie), 2, 2, 64));
    }

    @Test
    public void containingFootprintOverridesAnAdjacentPiecesFalloff() {
        NativeStructurePostProcessor.SurfaceAnchor local =
                new NativeStructurePostProcessor.SurfaceAnchor(0, 4, 0, 4, 65, 2);
        NativeStructurePostProcessor.SurfaceAnchor adjacent =
                new NativeStructurePostProcessor.SurfaceAnchor(5, 9, 0, 4, 80, 2);

        assertEquals(77, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(adjacent), 4, 2, 64));
        assertEquals(65, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(local, adjacent), 4, 2, 64));
        assertEquals(65, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(adjacent, local), 4, 2, 64));
    }

    @Test
    public void opposingFalloffsBlendWithoutAnAbruptMidpointSeam() {
        NativeStructurePostProcessor.SurfaceAnchor high =
                new NativeStructurePostProcessor.SurfaceAnchor(0, 0, 0, 0, 80, 2);
        NativeStructurePostProcessor.SurfaceAnchor low =
                new NativeStructurePostProcessor.SurfaceAnchor(12, 12, 0, 0, 48, 2);
        int previous = NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(high, low), 0, 0, 64);

        for (int x = 1; x <= 12; x++) {
            int forward = NativeStructurePostProcessor.resolveSurfaceTarget(
                    List.of(high, low), x, 0, 64);
            int reversed = NativeStructurePostProcessor.resolveSurfaceTarget(
                    List.of(low, high), x, 0, 64);
            assertEquals(forward, reversed);
            assertTrue(Math.abs(forward - previous) <= 4);
            previous = forward;
        }
        assertEquals(64, NativeStructurePostProcessor.resolveSurfaceTarget(
                List.of(high, low), 6, 0, 64));
    }

    @Test
    public void surfaceColumnsMutateTerrainAndRemoveUnsupportedDecoration() {
        Map<BlockPos, BlockState> lowered = new HashMap<>();
        put(lowered, 0, 61, 0, Blocks.STONE.defaultBlockState());
        put(lowered, 0, 62, 0, Blocks.DIRT.defaultBlockState());
        put(lowered, 0, 63, 0, Blocks.DIRT.defaultBlockState());
        put(lowered, 0, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        put(lowered, 0, 65, 0, Blocks.DANDELION.defaultBlockState());

        NativeStructurePostProcessor.applySurfaceColumn(
                world(lowered), new BlockPos.MutableBlockPos(),
                0, 0, 64, 62, -64, 319);

        assertEquals(Blocks.STONE.defaultBlockState(), state(lowered, 0, 61, 0));
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), state(lowered, 0, 62, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), state(lowered, 0, 63, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), state(lowered, 0, 64, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), state(lowered, 0, 65, 0));

        Map<BlockPos, BlockState> raised = new HashMap<>();
        put(raised, 0, 63, 0, Blocks.DIRT.defaultBlockState());
        put(raised, 0, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        put(raised, 0, 65, 0, Blocks.DANDELION.defaultBlockState());

        NativeStructurePostProcessor.applySurfaceColumn(
                world(raised), new BlockPos.MutableBlockPos(),
                0, 0, 64, 68, -64, 319);

        assertEquals(Blocks.DIRT.defaultBlockState(), state(raised, 0, 65, 0));
        assertEquals(Blocks.DIRT.defaultBlockState(), state(raised, 0, 66, 0));
        assertEquals(Blocks.DIRT.defaultBlockState(), state(raised, 0, 67, 0));
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), state(raised, 0, 68, 0));
    }

    @Test
    public void raisedSurfaceTerrainDoesNotSliceTreeBlocks() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        put(blocks, 0, 63, 0, Blocks.DIRT.defaultBlockState());
        put(blocks, 0, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        put(blocks, 0, 66, 0, log);
        put(blocks, 0, 68, 0, leaves);

        NativeStructurePostProcessor.applySurfaceColumn(
                world(blocks), new BlockPos.MutableBlockPos(),
                0, 0, 64, 68, -64, 319);

        assertEquals(log, state(blocks, 0, 66, 0));
        assertEquals(leaves, state(blocks, 0, 68, 0));
    }

    @Test
    public void loweredFluidColumnsRemainFluidFilled() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        put(blocks, 0, 62, 0, Blocks.DIRT.defaultBlockState());
        put(blocks, 0, 63, 0, Blocks.DIRT.defaultBlockState());
        put(blocks, 0, 64, 0, Blocks.GRAVEL.defaultBlockState());
        put(blocks, 0, 65, 0, Blocks.WATER.defaultBlockState());

        NativeStructurePostProcessor.applySurfaceColumn(
                world(blocks), new BlockPos.MutableBlockPos(),
                0, 0, 64, 62, -64, 319);

        assertEquals(Blocks.WATER.defaultBlockState(), state(blocks, 0, 63, 0));
        assertEquals(Blocks.WATER.defaultBlockState(), state(blocks, 0, 64, 0));
        assertEquals(Blocks.WATER.defaultBlockState(), state(blocks, 0, 65, 0));
        assertEquals(Blocks.GRAVEL.defaultBlockState(), state(blocks, 0, 62, 0));
    }

    @Test
    public void singlePoolTemplateFieldMatchesTheRuntimeContract() {
        Field field = NativeStructurePostProcessor.resolveSinglePoolTemplateField();

        assertEquals(SinglePoolElement.class, field.getDeclaringClass());
        assertEquals(Either.class, field.getType());
        assertTrue(Modifier.isProtected(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
        assertFalse(Modifier.isStatic(field.getModifiers()));
        assertTrue(field.trySetAccessible());
    }

    @Test
    public void runtimeTemplatesAndLegacyAirUseTheExactContract() {
        StructureTemplate runtimeTemplate = new StructureTemplate();

        assertEquals(runtimeTemplate, NativeStructurePostProcessor.resolveTemplateReference(
                Either.right(runtimeTemplate), null));
        assertFalse(NativeStructurePostProcessor.shouldClearLegacyAir(79, 80, false));
        assertTrue(NativeStructurePostProcessor.shouldClearLegacyAir(80, 80, false));
        assertTrue(NativeStructurePostProcessor.shouldClearLegacyAir(96, 80, false));
        assertFalse(NativeStructurePostProcessor.shouldClearLegacyAir(96, 80, true));
    }

    @Test
    public void rotatedTemplateAirClearsOnlyInsideTheChunkAndAboveTheFloor() throws Exception {
        StructureTemplate.StructureBlockInfo clear = new StructureTemplate.StructureBlockInfo(
                new BlockPos(1, 0, 0), Blocks.AIR.defaultBlockState(), null);
        StructureTemplate.StructureBlockInfo belowFloor = new StructureTemplate.StructureBlockInfo(
                new BlockPos(0, -1, 0), Blocks.AIR.defaultBlockState(), null);
        StructureTemplate.StructureBlockInfo outsideChunk = new StructureTemplate.StructureBlockInfo(
                new BlockPos(3, 0, 0), Blocks.AIR.defaultBlockState(), null);
        StructureTemplate template = template(List.of(clear, belowFloor, outsideChunk));
        BlockPos origin = new BlockPos(10, 80, 10);
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(Rotation.CLOCKWISE_90);
        BlockPos clearPosition = origin.offset(StructureTemplate.calculateRelativePosition(settings, clear.pos()));
        BlockPos belowFloorPosition = origin.offset(
                StructureTemplate.calculateRelativePosition(settings, belowFloor.pos()));
        BlockPos outsidePosition = origin.offset(
                StructureTemplate.calculateRelativePosition(settings, outsideChunk.pos()));
        BoundingBox area = new BoundingBox(
                Math.min(clearPosition.getX(), belowFloorPosition.getX()),
                Math.min(clearPosition.getY(), belowFloorPosition.getY()),
                Math.min(clearPosition.getZ(), belowFloorPosition.getZ()),
                Math.max(clearPosition.getX(), belowFloorPosition.getX()),
                Math.max(clearPosition.getY(), belowFloorPosition.getY()),
                Math.max(clearPosition.getZ(), belowFloorPosition.getZ()));
        settings.setBoundingBox(area);
        assertFalse(area.isInside(outsidePosition));
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(clearPosition, Blocks.DIRT.defaultBlockState());
        blocks.put(belowFloorPosition, Blocks.DIRT.defaultBlockState());
        blocks.put(outsidePosition, Blocks.DIRT.defaultBlockState());

        NativeStructurePostProcessor.clearTemplateAir(
                world(blocks), template, origin, 80, settings);

        assertEquals(Blocks.AIR.defaultBlockState(), blocks.get(clearPosition));
        assertEquals(Blocks.DIRT.defaultBlockState(), blocks.get(belowFloorPosition));
        assertEquals(Blocks.DIRT.defaultBlockState(), blocks.get(outsidePosition));
    }

    @Test
    public void unrelatedPieceBoundsAreRejectedBeforeTemplateScanning() {
        BoundingBox area = new BoundingBox(0, -64, 0, 15, 319, 15);

        assertTrue(NativeStructurePostProcessor.intersects(
                new BoundingBox(15, 60, 15, 30, 90, 30), area));
        assertFalse(NativeStructurePostProcessor.intersects(
                new BoundingBox(16, 60, 16, 30, 90, 30), area));
        assertFalse(NativeStructurePostProcessor.intersects(
                new BoundingBox(0, 320, 0, 15, 350, 15), area));
    }

    @Test
    public void templateAirDoesNotEraseTreesInsideVillagePieces() throws Exception {
        BlockPos origin = new BlockPos(0, 80, 0);
        StructureTemplate template = template(List.of(
                new StructureTemplate.StructureBlockInfo(BlockPos.ZERO, Blocks.AIR.defaultBlockState(), null)));
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(Rotation.NONE);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        blocks.put(origin, log);

        NativeStructurePostProcessor.clearTemplateAir(world(blocks), template, origin, 80, settings);

        assertEquals(log, blocks.get(origin));
    }

    private static NativeStructurePostProcessor.SurfaceAnchor anchor(int meetY, int strength) {
        return new NativeStructurePostProcessor.SurfaceAnchor(0, 4, 0, 4, meetY, strength);
    }

    private static StructureTemplate template(
            List<StructureTemplate.StructureBlockInfo> blocks) throws Exception {
        Constructor<StructureTemplate.Palette> constructor =
                StructureTemplate.Palette.class.getDeclaredConstructor(List.class);
        assertTrue(constructor.trySetAccessible());
        StructureTemplate.Palette palette = constructor.newInstance(blocks);
        StructureTemplate template = new StructureTemplate();
        template.palettes.add(palette);
        return template;
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
            if (methodName.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (methodName.equals("equals")) {
                return proxy == arguments[0];
            }
            if (methodName.equals("toString")) {
                return "surface-test-world";
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

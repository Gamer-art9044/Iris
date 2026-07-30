package art.arcane.iris.nativegen;

import art.arcane.iris.engine.mantle.components.StructureCarvingFootprint;
import art.arcane.iris.engine.object.IrisStructureCarveShape;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import com.mojang.datafixers.util.Either;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NativeStructurePostProcessorSurfaceTerrainTest {
    private static final int SLAB_DEPTH = 128;
    private static final int SLAB_MIN_Y = 64;
    private static final int SLAB_PADDING = 14;
    private static final int SLAB_WIDTH = 16;
    private static final long TEST_SEED = 8675309L;

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void onlySurfaceBeardThinStructuresPrepareTerrain() {
        assertTrue(NativeStructureSurfaceFitter.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.BEARD_THIN, GenerationStep.Decoration.SURFACE_STRUCTURES));
        assertFalse(NativeStructureSurfaceFitter.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.BURY, GenerationStep.Decoration.SURFACE_STRUCTURES));
        assertFalse(NativeStructureSurfaceFitter.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.BEARD_BOX, GenerationStep.Decoration.SURFACE_STRUCTURES));
        assertFalse(NativeStructureSurfaceFitter.shouldPrepareSurfaceTerrain(
                TerrainAdjustment.ENCAPSULATE, GenerationStep.Decoration.SURFACE_STRUCTURES));
    }

    @Test
    public void undergroundAdjustmentsNeverPrepareSurfaceTerrain() {
        for (TerrainAdjustment adjustment : List.of(
                TerrainAdjustment.BEARD_THIN,
                TerrainAdjustment.BURY,
                TerrainAdjustment.BEARD_BOX,
                TerrainAdjustment.ENCAPSULATE)) {
            assertFalse(NativeStructureSurfaceFitter.shouldPrepareSurfaceTerrain(
                    adjustment, GenerationStep.Decoration.UNDERGROUND_STRUCTURES));
        }
    }

    @Test
    public void surfaceAnchorIsFlushInsideAndUnchangedAtRadius() {
        NativeStructureSurfaceFitter.SurfaceAnchor anchor = anchor(80, 2);

        assertEquals(80, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(anchor), 2, 2, 64));
        assertEquals(64, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(anchor), 16, 2, 64));
        assertEquals(64, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(anchor), 17, 2, 64));
    }

    @Test
    public void surfaceAnchorRaisesAndLowersThroughTheTaper() {
        NativeStructureSurfaceFitter.SurfaceAnchor raised = anchor(80, 2);
        NativeStructureSurfaceFitter.SurfaceAnchor lowered = anchor(64, 2);

        assertEquals(68, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(raised), 10, 2, 64));
        assertEquals(76, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(lowered), 10, 2, 80));
    }

    @Test
    public void containingRigidFloorsHaveDeterministicPriority() {
        NativeStructureSurfaceFitter.SurfaceAnchor rigid = anchor(70, 2);
        NativeStructureSurfaceFitter.SurfaceAnchor junction = anchor(90, 1);

        assertEquals(70, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(rigid, junction), 2, 2, 64));
        assertEquals(70, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(junction, rigid), 2, 2, 64));

        NativeStructureSurfaceFitter.SurfaceAnchor weakTie = anchor(48, 1);
        NativeStructureSurfaceFitter.SurfaceAnchor strongTie = anchor(80, 2);
        assertEquals(80, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(weakTie, strongTie), 2, 2, 64));
        assertEquals(80, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(strongTie, weakTie), 2, 2, 64));
    }

    @Test
    public void containingFootprintOverridesAnAdjacentPiecesFalloff() {
        NativeStructureSurfaceFitter.SurfaceAnchor local =
                new NativeStructureSurfaceFitter.SurfaceAnchor(0, 4, 0, 4, 65, 2);
        NativeStructureSurfaceFitter.SurfaceAnchor adjacent =
                new NativeStructureSurfaceFitter.SurfaceAnchor(5, 9, 0, 4, 80, 2);

        assertEquals(77, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(adjacent), 4, 2, 64));
        assertEquals(65, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(local, adjacent), 4, 2, 64));
        assertEquals(65, NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(adjacent, local), 4, 2, 64));
    }

    @Test
    public void opposingFalloffsBlendWithoutAnAbruptMidpointSeam() {
        NativeStructureSurfaceFitter.SurfaceAnchor high =
                new NativeStructureSurfaceFitter.SurfaceAnchor(0, 0, 0, 0, 80, 2);
        NativeStructureSurfaceFitter.SurfaceAnchor low =
                new NativeStructureSurfaceFitter.SurfaceAnchor(12, 12, 0, 0, 48, 2);
        int previous = NativeStructureSurfaceFitter.resolveSurfaceTarget(
                List.of(high, low), 0, 0, 64);

        for (int x = 1; x <= 12; x++) {
            int forward = NativeStructureSurfaceFitter.resolveSurfaceTarget(
                    List.of(high, low), x, 0, 64);
            int reversed = NativeStructureSurfaceFitter.resolveSurfaceTarget(
                    List.of(low, high), x, 0, 64);
            assertEquals(forward, reversed);
            assertTrue(Math.abs(forward - previous) <= 4);
            previous = forward;
        }
        assertEquals(64, NativeStructureSurfaceFitter.resolveSurfaceTarget(
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

        NativeStructureSurfaceFitter.applySurfaceColumn(
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

        NativeStructureSurfaceFitter.applySurfaceColumn(
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

        NativeStructureSurfaceFitter.applySurfaceColumn(
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

        NativeStructureSurfaceFitter.applySurfaceColumn(
                world(blocks), new BlockPos.MutableBlockPos(),
                0, 0, 64, 62, -64, 319);

        assertEquals(Blocks.WATER.defaultBlockState(), state(blocks, 0, 63, 0));
        assertEquals(Blocks.WATER.defaultBlockState(), state(blocks, 0, 64, 0));
        assertEquals(Blocks.WATER.defaultBlockState(), state(blocks, 0, 65, 0));
        assertEquals(Blocks.GRAVEL.defaultBlockState(), state(blocks, 0, 62, 0));
    }

    @Test
    public void preserveSourceYSkipsBurialAndKeepsTheVanillaStartY() {
        StructureStart start = desertStart();
        int minY = start.getBoundingBox().minY();

        int offset = NativeStructureVerticalPlacer.applyVerticalShift(
                start, 0, -64, 320, true, true, null, (x, z) -> 40);

        assertEquals(0, offset);
        assertEquals(minY, start.getBoundingBox().minY());
    }

    @Test
    public void preserveSourceYStillAppliesAnExplicitShift() {
        StructureStart start = desertStart();
        int minY = start.getBoundingBox().minY();

        int offset = NativeStructureVerticalPlacer.applyVerticalShift(
                start, -8, -64, 320, true, true, null, (x, z) -> 40);

        assertEquals(-8, offset);
        assertEquals(minY - 8, start.getBoundingBox().minY());
    }

    @Test
    public void burialStillSinksTheStructureBelowTheLowestTerrainColumn() {
        StructureStart start = desertStart();
        int minY = start.getBoundingBox().minY();
        int maxY = start.getBoundingBox().maxY();
        int expected = 40 - 1 - maxY;

        int offset = NativeStructureVerticalPlacer.applyVerticalShift(
                start, 0, -64, 320, true, false, null, (x, z) -> 40);

        assertEquals(expected, offset);
        assertEquals(minY + expected, start.getBoundingBox().minY());
    }

    @Test
    public void unfittableBurialClampsToTheWorldFloorInsteadOfAborting() {
        StructureStart start = desertStart();
        int minY = start.getBoundingBox().minY();
        int worldMinY = minY - 4;

        int offset = NativeStructureVerticalPlacer.applyVerticalShift(
                start, 0, worldMinY, 320, true, false, null, (x, z) -> worldMinY);

        assertEquals(-4, offset);
        assertEquals(worldMinY, start.getBoundingBox().minY());
    }

    @Test
    public void nativeVacuumClearsEveryPieceEnvelopeBeforePlacement() {
        StructureStart start = desertStart();
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        put(blocks, bounds.minX(), bounds.minY(), bounds.minZ(), Blocks.STONE.defaultBlockState());

        NativeStructureTerrainIntegrator.integrateTerrain(
                world(blocks), bounds, "minecraft:desert_pyramid", start,
                new IrisStructureTerrain().setMode(IrisStructureTerrainMode.VACUUM), null);

        assertEquals(Blocks.AIR.defaultBlockState(),
                state(blocks, bounds.minX(), bounds.minY(), bounds.minZ()));
    }

    @Test
    public void nativeForceCarveHonorsConfiguredPadding() {
        StructureStart start = desertStart();
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        BoundingBox area = new BoundingBox(
                bounds.minX() - 1, bounds.minY(), bounds.minZ() - 1,
                bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        put(blocks, bounds.maxX() + 1, bounds.minY(), bounds.maxZ(),
                Blocks.STONE.defaultBlockState());
        put(blocks, bounds.maxX(), bounds.maxY() + 1, bounds.maxZ(),
                Blocks.STONE.defaultBlockState());

        NativeStructureTerrainIntegrator.integrateTerrain(
                world(blocks), area, "minecraft:desert_pyramid", start,
                new IrisStructureTerrain()
                        .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                        .setHorizontalPadding(1)
                        .setCeilingPadding(1), null);

        assertEquals(Blocks.AIR.defaultBlockState(),
                state(blocks, bounds.maxX() + 1, bounds.minY(), bounds.maxZ()));
        assertEquals(Blocks.AIR.defaultBlockState(),
                state(blocks, bounds.maxX(), bounds.maxY() + 1, bounds.maxZ()));
    }

    @Test
    public void nativeForceCarveUsesPieceUnionInsteadOfCombinedBounds() {
        Structure structure = new DesertPyramidStructure(
                new Structure.StructureSettings(HolderSet.empty()));
        DesertPyramidPiece first = new DesertPyramidPiece(RandomSource.create(7L), 0, 0);
        DesertPyramidPiece second = new DesertPyramidPiece(RandomSource.create(8L), 0, 0);
        second.move(64, 0, 0);
        StructureStart start = new StructureStart(
                structure, new ChunkPos(0, 0), 0,
                new PiecesContainer(List.of(first, second)));
        BoundingBox firstBounds = first.getBoundingBox();
        BoundingBox secondBounds = second.getBoundingBox();
        int gapX = (firstBounds.maxX() + secondBounds.minX()) / 2;
        int y = firstBounds.minY();
        int z = firstBounds.minZ();
        BoundingBox area = new BoundingBox(
                firstBounds.minX(), y, z,
                secondBounds.maxX(), y, z);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        put(blocks, firstBounds.minX(), y, z, Blocks.STONE.defaultBlockState());
        put(blocks, gapX, y, z, Blocks.STONE.defaultBlockState());

        NativeStructureTerrainIntegrator.integrateTerrain(
                world(blocks), area, "minecraft:ancient_city", start,
                new IrisStructureTerrain().setMode(IrisStructureTerrainMode.FORCE_CARVE), null);

        assertEquals(Blocks.AIR.defaultBlockState(),
                state(blocks, firstBounds.minX(), y, z));
        assertEquals(Blocks.STONE.defaultBlockState(),
                state(blocks, gapX, y, z));
    }

    @Test
    public void templateBackedColumnsUseTheTemplateAirComplement() throws Exception {
        StructureTemplate template = template(List.of(
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(0, 0, 0), Blocks.AIR.defaultBlockState(), null),
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(0, 1, 0), Blocks.AIR.defaultBlockState(), null),
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(0, 2, 0), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), null),
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(1, 0, 0), Blocks.STRUCTURE_VOID.defaultBlockState(), null),
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(1, 1, 0), Blocks.DEEPSLATE.defaultBlockState(), null),
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(1, 2, 0), Blocks.DEEPSLATE.defaultBlockState(), null)));
        Map<Long, int[]> columns = new HashMap<>();

        assertTrue(NativeStructureTerrainIntegrator.emitTemplateColumns(
                List.of(template), new BlockPos(0, 0, 0), Rotation.NONE,
                new BoundingBox(0, 0, 0, 1, 2, 0),
                (x, z, minY, maxY) -> columns.put((long) x << 32 | z & 0xffffffffL,
                        new int[]{minY, maxY})));

        assertEquals(2, columns.size());
        assertArrayEquals(new int[]{2, 2}, columns.get(0L));
        assertArrayEquals(new int[]{1, 2}, columns.get(1L << 32));
    }

    @Test
    public void fullyVoidTemplateColumnsAreNotCarveSources() throws Exception {
        StructureTemplate template = template(List.of(
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(0, 0, 0), Blocks.AIR.defaultBlockState(), null),
                new StructureTemplate.StructureBlockInfo(
                        new BlockPos(0, 1, 0), Blocks.STRUCTURE_VOID.defaultBlockState(), null)));
        Map<Long, int[]> columns = new HashMap<>();

        assertTrue(NativeStructureTerrainIntegrator.emitTemplateColumns(
                List.of(template), new BlockPos(0, 0, 0), Rotation.NONE,
                new BoundingBox(0, 0, 0, 0, 1, 0),
                (x, z, minY, maxY) -> columns.put((long) x << 32 | z & 0xffffffffL,
                        new int[]{minY, maxY})));

        assertTrue(columns.isEmpty());
    }

    @Test
    public void nonTemplatePiecesFallBackToTheirBoundingBoxColumns() {
        StructureStart start = desertStart();
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();

        StructureCarvingFootprint footprint = NativeStructureTerrainIntegrator.carveFootprint(
                start, 4, NativeStructurePostProcessorSurfaceTerrainTest::forbiddenTemplateManager);

        assertEquals(bounds.minX() - 4, footprint.minX());
        assertEquals(bounds.maxX() + 4, footprint.maxX());
        assertEquals(bounds.minZ() - 4, footprint.minZ());
        assertEquals(bounds.maxZ() + 4, footprint.maxZ());
        assertEquals(0L, footprint.distanceSquaredAt(bounds.minX(), bounds.minZ()));
        assertEquals(32L, footprint.distanceSquaredAt(bounds.minX() - 4, bounds.minZ() - 4));
        assertEquals(bounds.minY(), footprint.sourceMinYAt(bounds.minX(), bounds.minZ()));
        assertEquals(bounds.maxY(), footprint.sourceMaxYAt(bounds.minX(), bounds.minZ()));
    }

    @Test
    public void carveFootprintIsComputedOncePerStartAndPadding() {
        StructureStart start = desertStart();

        StructureCarvingFootprint first = NativeStructureTerrainIntegrator.carveFootprint(
                start, 6, NativeStructurePostProcessorSurfaceTerrainTest::forbiddenTemplateManager);
        StructureCarvingFootprint repeated = NativeStructureTerrainIntegrator.carveFootprint(
                start, 6, NativeStructurePostProcessorSurfaceTerrainTest::forbiddenTemplateManager);
        StructureCarvingFootprint widened = NativeStructureTerrainIntegrator.carveFootprint(
                start, 7, NativeStructurePostProcessorSurfaceTerrainTest::forbiddenTemplateManager);
        StructureCarvingFootprint other = NativeStructureTerrainIntegrator.carveFootprint(
                desertStart(), 6, NativeStructurePostProcessorSurfaceTerrainTest::forbiddenTemplateManager);

        assertSame(first, repeated);
        assertNotSame(first, widened);
        assertNotSame(first, other);
    }

    @Test
    public void organicCarveNeverCutsBelowTheColumnSupportingFloor() {
        StructureStart start = desertStart();
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        NativeStructureTerrainIntegrator.OrganicCarve carve = organicCarve(start, 6);
        BoundingBox area = new BoundingBox(
                bounds.minX() - 6, bounds.minY() - 4, bounds.minZ() - 6,
                bounds.maxX() + 6, bounds.maxY() + 12, bounds.maxZ() + 6);
        Map<BlockPos, BlockState> blocks = fill(area);

        NativeStructureTerrainIntegrator.carveOrganicColumns(world(blocks), area, carve);

        int centerX = bounds.minX() + bounds.getXSpan() / 2;
        int centerZ = bounds.minZ() + bounds.getZSpan() / 2;
        assertEquals(Blocks.AIR.defaultBlockState(), state(blocks, centerX, bounds.minY(), centerZ));
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                for (int y = area.minY(); y < bounds.minY(); y++) {
                    assertEquals(Blocks.STONE.defaultBlockState(), state(blocks, x, y, z));
                }
            }
        }
    }

    @Test
    public void lobedCarveStaysInsideTheUniformCarveAndRemovesLess() {
        StructureStart start = desertStart();
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        BoundingBox area = new BoundingBox(
                bounds.minX() - 10, bounds.minY(), bounds.minZ() - 10,
                bounds.maxX() + 10, bounds.maxY() + 12, bounds.maxZ() + 10);
        Map<BlockPos, BlockState> uniformBlocks = fill(area);
        Map<BlockPos, BlockState> lobedBlocks = fill(area);

        NativeStructureTerrainIntegrator.carveOrganicColumns(
                world(uniformBlocks), area, organicCarve(start, 10, 0D));
        NativeStructureTerrainIntegrator.carveOrganicColumns(
                world(lobedBlocks), area, organicCarve(start, 10, 0.85D));

        int uniform = 0;
        int lobed = 0;
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                for (int y = area.minY(); y <= area.maxY(); y++) {
                    boolean uniformAir = state(uniformBlocks, x, y, z).isAir();
                    boolean lobedAir = state(lobedBlocks, x, y, z).isAir();
                    assertTrue("lobe carved outside the uniform padding at "
                            + x + "," + y + "," + z, uniformAir || !lobedAir);
                    uniform += uniformAir ? 1 : 0;
                    lobed += lobedAir ? 1 : 0;
                }
            }
        }

        assertTrue(lobed > 0);
        assertTrue("uniform " + uniform + " lobed " + lobed, lobed < uniform);
    }

    @Test
    public void lobedCarveDepthWandersAlongAStraightFootprintEdge() {
        int[] uniform = slabEdgeCarveDepths(0D);
        int[] lobed = slabEdgeCarveDepths(0.85D);

        assertTrue("uniform depths wander " + span(uniform), span(uniform) <= 2);
        assertTrue("lobed depths wander " + span(lobed), span(lobed) >= 6);
        for (int depth : lobed) {
            assertTrue(depth >= 0 && depth <= SLAB_PADDING);
        }
    }

    @Test
    public void organicCarveIsIdenticalAcrossNeighboringChunkContexts() {
        StructureStart start = desertStart();
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        BoundingBox wide = new BoundingBox(
                bounds.minX() - 6, bounds.minY(), bounds.minZ() - 6,
                bounds.maxX() + 6, bounds.maxY() + 12, bounds.maxZ() + 6);
        BoundingBox narrow = new BoundingBox(
                bounds.maxX() - 3, bounds.minY(), bounds.maxZ() - 3,
                bounds.maxX() + 6, bounds.maxY() + 12, bounds.maxZ() + 6);
        Map<BlockPos, BlockState> wideBlocks = fill(wide);
        Map<BlockPos, BlockState> narrowBlocks = fill(narrow);

        // Each chunk context rebuilds its own noise channels from the shared start identity.
        NativeStructureTerrainIntegrator.carveOrganicColumns(
                world(wideBlocks), wide, organicCarve(start, 6));
        NativeStructureTerrainIntegrator.carveOrganicColumns(
                world(narrowBlocks), narrow, organicCarve(start, 6));

        int carved = 0;
        for (int x = narrow.minX(); x <= narrow.maxX(); x++) {
            for (int z = narrow.minZ(); z <= narrow.maxZ(); z++) {
                for (int y = narrow.minY(); y <= narrow.maxY(); y++) {
                    BlockState expected = state(wideBlocks, x, y, z);
                    assertEquals(expected, state(narrowBlocks, x, y, z));
                    if (expected.isAir()) {
                        carved++;
                    }
                }
            }
        }
        assertTrue(carved > 0);
    }

    @Test
    public void erodedForceCarveShrinkwrapsWithoutUnderminingTheFloor() {
        StructureStart start = desertStart();
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        BoundingBox area = new BoundingBox(
                bounds.minX() - 6, bounds.minY() - 2, bounds.minZ() - 6,
                bounds.maxX() + 6, bounds.maxY() + 12, bounds.maxZ() + 6);
        Map<BlockPos, BlockState> blocks = fill(area);
        int centerX = bounds.minX() + bounds.getXSpan() / 2;
        int centerZ = bounds.minZ() + bounds.getZSpan() / 2;

        NativeStructureTerrainIntegrator.integrateTerrain(
                world(blocks), area, "minecraft:ancient_city", start,
                new IrisStructureTerrain()
                        .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                        .setShape(IrisStructureCarveShape.ERODED)
                        .setHorizontalPadding(6)
                        .setCeilingPadding(8)
                        .setFloorPadding(0)
                        .setErosionStrength(1D)
                        .setErosionFrequency(0.05D), null);

        assertEquals(Blocks.AIR.defaultBlockState(), state(blocks, centerX, bounds.minY(), centerZ));
        assertEquals(Blocks.STONE.defaultBlockState(),
                state(blocks, centerX, bounds.minY() - 1, centerZ));
        assertEquals(Blocks.STONE.defaultBlockState(),
                state(blocks, area.minX(), area.maxY(), area.minZ()));
    }

    @Test
    public void sparseStiltGridIsDeterministicAndPreflightsGround() {
        assertTrue(NativeStructureFoundationBuilder.isStiltColumn(0, 0, 4));
        assertTrue(NativeStructureFoundationBuilder.isStiltColumn(-4, 8, 4));
        assertFalse(NativeStructureFoundationBuilder.isStiltColumn(1, 0, 4));
        assertTrue(NativeStructureFoundationBuilder.isStiltColumn(1, 1, 1));

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        put(blocks, 0, 7, 0, Blocks.DEEPSLATE.defaultBlockState());
        put(blocks, 0, 8, 0, Blocks.SCULK_VEIN.defaultBlockState());
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

        assertEquals(7, NativeStructureFoundationBuilder.findStiltAnchorY(
                world(blocks), 0, 0, 10, 2, -64, -64, position));
        assertEquals(Integer.MIN_VALUE, NativeStructureFoundationBuilder.findStiltAnchorY(
                world(blocks), 0, 0, 10, 1, -64, -64, position));
        assertEquals(Integer.MIN_VALUE, NativeStructureFoundationBuilder.findStiltAnchorY(
                world(new HashMap<>()), 0, 0, 10, 64, -64, -64, position));
    }

    @Test
    public void nativeTerrainEnvelopePersistsHorizontalReferenceCoverage() {
        StructureStart generated = desertStart();
        BoundingBox content = generated.getBoundingBox();

        StructureStart wrapped = NativeStructureReferenceEnvelope.wrap(
                generated,
                generated.getStructure(),
                0,
                null,
                new IrisStructureTerrain()
                        .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                        .setHorizontalPadding(24));

        assertEquals(content, NativeStructureReferenceEnvelope.contentBounds(wrapped));
        assertEquals(content.minX() - 24, wrapped.getBoundingBox().minX());
        assertEquals(content.minZ() - 24, wrapped.getBoundingBox().minZ());
        assertEquals(content.maxX() + 24, wrapped.getBoundingBox().maxX());
        assertEquals(content.maxZ() + 24, wrapped.getBoundingBox().maxZ());
        assertEquals(2, wrapped.getPieces().stream()
                .filter(NativeStructureReferenceEnvelope::isMarker)
                .count());
    }

    @Test
    public void nativeTerrainEnvelopeClipsToMinecraftReferenceCoverage() {
        StructureStart generated = desertStart();

        StructureStart wrapped = NativeStructureReferenceEnvelope.wrap(
                generated,
                generated.getStructure(),
                0,
                null,
                new IrisStructureTerrain()
                        .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                        .setHorizontalPadding(128));

        assertEquals(-128, wrapped.getBoundingBox().minX());
        assertEquals(-128, wrapped.getBoundingBox().minZ());
        assertEquals(143, wrapped.getBoundingBox().maxX());
        assertEquals(143, wrapped.getBoundingBox().maxZ());
    }

    @Test
    public void singlePoolTemplateFieldMatchesTheRuntimeContract() {
        Field field = NativeStructureReflection.resolveSinglePoolTemplateField();

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

        assertEquals(runtimeTemplate, NativeStructureReflection.resolveTemplateReference(
                Either.right(runtimeTemplate), null));
        assertFalse(NativeStructureTerrainIntegrator.shouldClearLegacyAir(79, 80, false));
        assertTrue(NativeStructureTerrainIntegrator.shouldClearLegacyAir(80, 80, false));
        assertTrue(NativeStructureTerrainIntegrator.shouldClearLegacyAir(96, 80, false));
        assertFalse(NativeStructureTerrainIntegrator.shouldClearLegacyAir(96, 80, true));
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

        NativeStructureTerrainIntegrator.clearTemplateAir(
                world(blocks), template, origin, 80, settings);

        assertEquals(Blocks.AIR.defaultBlockState(), blocks.get(clearPosition));
        assertEquals(Blocks.DIRT.defaultBlockState(), blocks.get(belowFloorPosition));
        assertEquals(Blocks.DIRT.defaultBlockState(), blocks.get(outsidePosition));
    }

    @Test
    public void unrelatedPieceBoundsAreRejectedBeforeTemplateScanning() {
        BoundingBox area = new BoundingBox(0, -64, 0, 15, 319, 15);

        assertTrue(NativeStructureTerrainIntegrator.intersects(
                new BoundingBox(15, 60, 15, 30, 90, 30), area));
        assertFalse(NativeStructureTerrainIntegrator.intersects(
                new BoundingBox(16, 60, 16, 30, 90, 30), area));
        assertFalse(NativeStructureTerrainIntegrator.intersects(
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

        NativeStructureTerrainIntegrator.clearTemplateAir(world(blocks), template, origin, 80, settings);

        assertEquals(log, blocks.get(origin));
    }

    private static NativeStructureSurfaceFitter.SurfaceAnchor anchor(int meetY, int strength) {
        return new NativeStructureSurfaceFitter.SurfaceAnchor(0, 4, 0, 4, meetY, strength);
    }

    private static NativeStructureTerrainIntegrator.OrganicCarve organicCarve(
            StructureStart start, int horizontalPadding) {
        return organicCarve(start, horizontalPadding, 0.85D);
    }

    private static NativeStructureTerrainIntegrator.OrganicCarve organicCarve(
            StructureStart start, int horizontalPadding, double lobeStrength) {
        IrisStructureTerrain terrain = new IrisStructureTerrain()
                .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                .setShape(IrisStructureCarveShape.ERODED)
                .setHorizontalPadding(horizontalPadding)
                .setCeilingPadding(8)
                .setFloorPadding(0)
                .setErosionStrength(1D)
                .setErosionFrequency(0.05D)
                .setLobeStrength(lobeStrength);
        return NativeStructureTerrainIntegrator.organicCarve(
                NativeStructureTerrainIntegrator.carveFootprint(start, horizontalPadding,
                        NativeStructurePostProcessorSurfaceTerrainTest::forbiddenTemplateManager),
                terrain, IrisStructureCarveShape.ERODED, TEST_SEED);
    }

    /**
     * Outward carve depth for every column along the straight +X edge of a slab footprint, measured
     * inside the source vertical span so the boundary is decided purely by the horizontal threshold.
     * The slab spans several lobe wavelengths so a lobed boundary is distinguishable from a uniform one.
     */
    private static int[] slabEdgeCarveDepths(double lobeStrength) {
        StructureCarvingFootprint footprint = StructureCarvingFootprint.fromColumns(sink -> {
            for (int x = 0; x < SLAB_WIDTH; x++) {
                for (int z = 0; z < SLAB_DEPTH; z++) {
                    sink.column(x, z, SLAB_MIN_Y, SLAB_MIN_Y + 8);
                }
            }
            return true;
        }, SLAB_PADDING, 1_000_000);
        IrisStructureTerrain terrain = new IrisStructureTerrain()
                .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                .setShape(IrisStructureCarveShape.ERODED)
                .setHorizontalPadding(SLAB_PADDING)
                .setCeilingPadding(8)
                .setFloorPadding(0)
                .setErosionStrength(1D)
                .setErosionFrequency(0.05D)
                .setLobeStrength(lobeStrength);
        int transectY = SLAB_MIN_Y + 4;
        BoundingBox area = new BoundingBox(
                SLAB_WIDTH, transectY, 0,
                SLAB_WIDTH - 1 + SLAB_PADDING, transectY, SLAB_DEPTH - 1);
        Map<BlockPos, BlockState> blocks = fill(area);

        NativeStructureTerrainIntegrator.carveOrganicColumns(world(blocks), area,
                NativeStructureTerrainIntegrator.organicCarve(
                        footprint, terrain, IrisStructureCarveShape.ERODED, TEST_SEED));

        int[] depths = new int[SLAB_DEPTH];
        for (int z = 0; z < SLAB_DEPTH; z++) {
            int depth = 0;
            while (depth < SLAB_PADDING
                    && state(blocks, SLAB_WIDTH + depth, transectY, z).isAir()) {
                depth++;
            }
            depths[z] = depth;
        }
        return depths;
    }

    private static int span(int[] values) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int value : values) {
            lowest = Math.min(lowest, value);
            highest = Math.max(highest, value);
        }
        return highest - lowest;
    }

    private static StructureTemplateManager forbiddenTemplateManager() {
        throw new AssertionError("Bounding-box carve columns must not resolve templates");
    }

    private static Map<BlockPos, BlockState> fill(BoundingBox area) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                for (int y = area.minY(); y <= area.maxY(); y++) {
                    put(blocks, x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        return blocks;
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

    private static StructureStart desertStart() {
        Structure structure = new DesertPyramidStructure(
                new Structure.StructureSettings(HolderSet.empty()));
        DesertPyramidPiece piece = new DesertPyramidPiece(RandomSource.create(7L), 0, 0);
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

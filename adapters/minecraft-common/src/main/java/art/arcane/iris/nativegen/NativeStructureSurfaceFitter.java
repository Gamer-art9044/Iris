package art.arcane.iris.nativegen;

import art.arcane.iris.engine.object.IrisObjectVacuum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;

public final class NativeStructureSurfaceFitter {
    private static final double SURFACE_TERRAIN_FALLOFF = 2.0;
    private static final long SURFACE_TERRAIN_INFLUENCE_SCALE = 1_000_000L;
    private static final int SURFACE_TERRAIN_RADIUS = 12;

    private NativeStructureSurfaceFitter() {
    }

    public static void prepareSurfaceStructures(WorldGenLevel world, BoundingBox area,
                                                List<StructureStart> starts,
                                                IntBinaryOperator surfaceHeight) {
        if (starts == null || starts.isEmpty()) {
            return;
        }
        Objects.requireNonNull(surfaceHeight, "Surface structure terrain fitting requires an Iris height resolver");
        List<SurfaceAnchor> anchors = collectSurfaceAnchors(starts);
        if (!anchors.isEmpty()) {
            fitSurfaceTerrain(world, area, anchors, surfaceHeight);
        }
        Supplier<StructureTemplateManager> templates = () -> world.getLevel().getStructureManager();
        for (StructureStart start : starts) {
            if (requiresSurfaceTerrain(start)) {
                NativeStructureTerrainIntegrator.clearLegacyTemplateAir(world, area, start, templates);
            }
        }
    }

    static boolean shouldPrepareSurfaceTerrain(TerrainAdjustment adjustment,
                                               GenerationStep.Decoration step) {
        return adjustment == TerrainAdjustment.BEARD_THIN
                && step == GenerationStep.Decoration.SURFACE_STRUCTURES;
    }

    static int resolveSurfaceTarget(List<SurfaceAnchor> anchors, int worldX, int worldZ,
                                    int originalY) {
        int localTargetY = originalY;
        SurfaceAnchor selectedLocal = null;
        long totalInfluence = 0L;
        long weightedMeetY = 0L;
        long maximumInfluence = 0L;
        for (SurfaceAnchor anchor : anchors) {
            int outX = IrisObjectVacuum.outset(worldX, anchor.minX(), anchor.maxX());
            int outZ = IrisObjectVacuum.outset(worldZ, anchor.minZ(), anchor.maxZ());
            long distanceSquared = (long) outX * outX + (long) outZ * outZ;
            if (distanceSquared > (long) SURFACE_TERRAIN_RADIUS * SURFACE_TERRAIN_RADIUS) {
                continue;
            }
            boolean containsColumn = outX == 0 && outZ == 0;
            if (containsColumn) {
                if (precedes(anchor, selectedLocal)) {
                    localTargetY = anchor.meetY();
                    selectedLocal = anchor;
                }
                continue;
            }
            double factor = IrisObjectVacuum.columnInfluence(
                    worldX, worldZ,
                    anchor.minX(), anchor.maxX(), anchor.minZ(), anchor.maxZ(),
                    SURFACE_TERRAIN_RADIUS, SURFACE_TERRAIN_FALLOFF);
            long influence = Math.round(factor * SURFACE_TERRAIN_INFLUENCE_SCALE);
            if (influence <= 0L) {
                continue;
            }
            long weightedInfluence = influence * Math.max(1, anchor.strength());
            totalInfluence += weightedInfluence;
            weightedMeetY += weightedInfluence * anchor.meetY();
            maximumInfluence = Math.max(maximumInfluence, influence);
        }
        if (selectedLocal != null) {
            return localTargetY;
        }
        if (totalInfluence == 0L) {
            return originalY;
        }
        double blendedMeetY = weightedMeetY / (double) totalInfluence;
        double factor = maximumInfluence / (double) SURFACE_TERRAIN_INFLUENCE_SCALE;
        return (int) Math.round(originalY + ((blendedMeetY - originalY) * factor));
    }

    private static boolean precedes(SurfaceAnchor candidate, SurfaceAnchor selected) {
        if (selected == null) {
            return true;
        }
        if (candidate.strength() != selected.strength()) {
            return candidate.strength() > selected.strength();
        }
        if (candidate.meetY() != selected.meetY()) {
            return candidate.meetY() < selected.meetY();
        }
        if (candidate.minX() != selected.minX()) {
            return candidate.minX() < selected.minX();
        }
        if (candidate.minZ() != selected.minZ()) {
            return candidate.minZ() < selected.minZ();
        }
        if (candidate.maxX() != selected.maxX()) {
            return candidate.maxX() < selected.maxX();
        }
        if (candidate.maxZ() != selected.maxZ()) {
            return candidate.maxZ() < selected.maxZ();
        }
        return false;
    }

    private static List<SurfaceAnchor> collectSurfaceAnchors(List<StructureStart> starts) {
        List<SurfaceAnchor> anchors = new ArrayList<>();
        for (StructureStart start : starts) {
            if (!requiresSurfaceTerrain(start)) {
                continue;
            }
            for (StructurePiece piece : start.getPieces()) {
                if (NativeStructureReferenceEnvelope.isMarker(piece)) {
                    continue;
                }
                if (piece instanceof PoolElementStructurePiece poolPiece) {
                    if (poolPiece.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                        BoundingBox bounds = poolPiece.getBoundingBox();
                        anchors.add(new SurfaceAnchor(
                                bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(),
                                bounds.minY() + poolPiece.getGroundLevelDelta() - 1, 2));
                    }
                    for (JigsawJunction junction : poolPiece.getJunctions()) {
                        anchors.add(new SurfaceAnchor(
                                junction.getSourceX(), junction.getSourceX(),
                                junction.getSourceZ(), junction.getSourceZ(),
                                junction.getSourceGroundY() - 1, 1));
                    }
                    continue;
                }
                BoundingBox bounds = piece.getBoundingBox();
                anchors.add(new SurfaceAnchor(
                        bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(),
                        bounds.minY() - 1, 2));
            }
        }
        return List.copyOf(anchors);
    }

    static boolean requiresSurfaceTerrain(StructureStart start) {
        return start != null
                && start.isValid()
                && shouldPrepareSurfaceTerrain(
                        start.getStructure().terrainAdaptation(), start.getStructure().step());
    }

    private static void fitSurfaceTerrain(WorldGenLevel world, BoundingBox area,
                                          List<SurfaceAnchor> anchors,
                                          IntBinaryOperator surfaceHeight) {
        int width = area.getXSpan();
        int depth = area.getZSpan();
        int[] originalHeights = new int[width * depth];
        int[] targetHeights = new int[width * depth];
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int column = (z - area.minZ()) * width + x - area.minX();
                int originalY = Math.max(area.minY(), Math.min(
                        area.maxY(), surfaceHeight.applyAsInt(x, z)));
                originalHeights[column] = originalY;
                targetHeights[column] = Math.max(area.minY(), Math.min(
                        area.maxY(), resolveSurfaceTarget(anchors, x, z, originalY)));
            }
        }
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int column = (z - area.minZ()) * width + x - area.minX();
                applySurfaceColumn(world, position, x, z,
                        originalHeights[column], targetHeights[column], area.minY(), area.maxY());
            }
        }
    }

    static void applySurfaceColumn(WorldGenLevel world, BlockPos.MutableBlockPos position,
                                   int x, int z, int originalY, int targetY,
                                   int worldMinY, int worldMaxY) {
        if (targetY == originalY) {
            return;
        }
        SurfaceMaterials materials = resolveSurfaceMaterials(world, position, x, z, originalY, worldMinY);
        if (targetY < originalY) {
            BlockState clearedState = clearSurfaceDecorationAndResolveFill(
                    world, position, x, z, originalY, worldMaxY);
            for (int y = originalY; y > targetY; y--) {
                world.setBlock(position.set(x, y, z), clearedState, 2);
            }
            world.setBlock(position.set(x, targetY, z), materials.surface(), 2);
            return;
        }
        for (int y = originalY + 1; y < targetY; y++) {
            position.set(x, y, z);
            if (!NativeStructureVegetationClearer.isTreeBlock(world.getBlockState(position))) {
                world.setBlock(position, materials.subsurface(), 2);
            }
        }
        position.set(x, targetY, z);
        if (!NativeStructureVegetationClearer.isTreeBlock(world.getBlockState(position))) {
            world.setBlock(position, materials.surface(), 2);
        }
    }

    private static BlockState clearSurfaceDecorationAndResolveFill(
            WorldGenLevel world, BlockPos.MutableBlockPos position,
            int x, int z, int originalY, int worldMaxY) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = originalY + 1; y <= worldMaxY; y++) {
            BlockState state = world.getBlockState(position.set(x, y, z));
            if (state.isAir()) {
                return air;
            }
            if (!state.getFluidState().isEmpty()) {
                BlockState fluid = state.getFluidState().createLegacyBlock();
                if (state != fluid) {
                    world.setBlock(position, fluid, 2);
                }
                return fluid;
            }
            if (state.isSolid() || NativeStructureVegetationClearer.isTreeBlock(state)) {
                return air;
            }
            world.setBlock(position, air, 2);
        }
        return air;
    }

    private static SurfaceMaterials resolveSurfaceMaterials(WorldGenLevel world,
                                                            BlockPos.MutableBlockPos position,
                                                            int x, int z, int originalY,
                                                            int worldMinY) {
        BlockState surface = world.getBlockState(position.set(x, originalY, z));
        BlockState subsurface = null;
        for (int y = originalY - 1; y >= worldMinY; y--) {
            BlockState candidate = world.getBlockState(position.set(x, y, z));
            if (isTerrainBlock(candidate)) {
                subsurface = candidate;
                break;
            }
        }
        if (subsurface == null) {
            subsurface = isTerrainBlock(surface) ? surface : Blocks.STONE.defaultBlockState();
        }
        if (!isTerrainBlock(surface)) {
            surface = subsurface;
        }
        return new SurfaceMaterials(surface, subsurface);
    }

    private static boolean isTerrainBlock(BlockState state) {
        return state.isSolid() && !NativeStructureVegetationClearer.isTreeBlock(state);
    }

    record SurfaceAnchor(int minX, int maxX, int minZ, int maxZ, int meetY, int strength) {
    }

    private record SurfaceMaterials(BlockState surface, BlockState subsurface) {
    }
}

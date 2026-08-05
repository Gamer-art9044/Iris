package art.arcane.iris.nativegen;

import art.arcane.iris.engine.object.IrisObjectVacuum;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;

public final class NativeStructureSurfaceFitter {
    private static final double SURFACE_TERRAIN_FALLOFF = 2.0;
    private static final long SURFACE_TERRAIN_INFLUENCE_SCALE = 1_000_000L;
    private static final int SURFACE_TERRAIN_RADIUS = 12;

    private NativeStructureSurfaceFitter() {
    }

    public static void prepareSurfaceStructures(WorldGenLevel world, BoundingBox area,
                                                List<NativeStructureTerrainIntegrator.TerrainTarget> targets,
                                                IntBinaryOperator surfaceHeight) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        Objects.requireNonNull(surfaceHeight, "Surface structure terrain fitting requires an Iris height resolver");
        List<SurfaceAnchor> anchors = collectSurfaceAnchors(targets);
        if (!anchors.isEmpty()) {
            fitSurfaceTerrain(world, area, anchors, surfaceHeight);
        }
    }

    static boolean shouldPrepareSurfaceTerrain(TerrainAdjustment adjustment,
                                               GenerationStep.Decoration step) {
        return adjustment == TerrainAdjustment.BEARD_THIN
                || adjustment == TerrainAdjustment.BEARD_BOX;
    }

    static int resolveSurfaceTarget(List<SurfaceAnchor> anchors, int worldX, int worldZ,
                                    int originalY) {
        return resolveSurface(anchors, worldX, worldZ, originalY).targetY();
    }

    private static SurfaceResolution resolveSurface(List<SurfaceAnchor> anchors,
                                                    int worldX, int worldZ, int originalY) {
        int localTargetY = originalY;
        SurfaceAnchor selectedLocal = null;
        long totalInfluence = 0L;
        long weightedMeetY = 0L;
        long maximumInfluence = 0L;
        for (SurfaceAnchor anchor : anchors) {
            int outX = IrisObjectVacuum.outset(worldX, anchor.minX(), anchor.maxX());
            int outZ = IrisObjectVacuum.outset(worldZ, anchor.minZ(), anchor.maxZ());
            boolean containsColumn = outX == 0 && outZ == 0;
            if (containsColumn && anchor.strength() > 1 && originalY < anchor.meetY()) {
                if (precedes(anchor, selectedLocal)) {
                    localTargetY = anchor.meetY();
                    selectedLocal = anchor;
                }
                continue;
            }
            int verticalDistance = anchor.verticalDistance(originalY);
            long horizontalDistanceSquared = (long) outX * outX + (long) outZ * outZ;
            long distanceSquared = (long) outX * outX + (long) outZ * outZ
                    + (long) verticalDistance * verticalDistance;
            double factor = 0D;
            long radiusSquared = (long) SURFACE_TERRAIN_RADIUS * SURFACE_TERRAIN_RADIUS;
            if (distanceSquared <= radiusSquared) {
                double distance = Math.sqrt(distanceSquared);
                factor = Math.pow(
                        1D - distance / SURFACE_TERRAIN_RADIUS, SURFACE_TERRAIN_FALLOFF);
            }
            if (anchor.strength() > 1 && originalY < anchor.meetY()
                    && verticalDistance > SURFACE_TERRAIN_RADIUS
                    && horizontalDistanceSquared <= radiusSquared) {
                double horizontalDistance = Math.sqrt(horizontalDistanceSquared);
                double horizontalFactor = Math.pow(
                        1D - horizontalDistance / SURFACE_TERRAIN_RADIUS,
                        SURFACE_TERRAIN_FALLOFF);
                double rescueProgress = Math.min(1D,
                        (verticalDistance - SURFACE_TERRAIN_RADIUS)
                                / (double) SURFACE_TERRAIN_RADIUS);
                double rescueWeight = rescueProgress * rescueProgress
                        * (3D - 2D * rescueProgress);
                factor = Math.max(factor, horizontalFactor * rescueWeight);
            }
            if (factor <= 0D) {
                continue;
            }
            if (containsColumn) {
                if (precedes(anchor, selectedLocal)) {
                    localTargetY = anchor.meetY();
                    selectedLocal = anchor;
                }
                continue;
            }
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
            return new SurfaceResolution(localTargetY, selectedLocal.strength() > 1);
        }
        if (totalInfluence == 0L) {
            return new SurfaceResolution(originalY, false);
        }
        double blendedMeetY = weightedMeetY / (double) totalInfluence;
        double factor = maximumInfluence / (double) SURFACE_TERRAIN_INFLUENCE_SCALE;
        return new SurfaceResolution(
                (int) Math.round(originalY + ((blendedMeetY - originalY) * factor)), false);
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

    private static List<SurfaceAnchor> collectSurfaceAnchors(
            List<NativeStructureTerrainIntegrator.TerrainTarget> targets) {
        List<SurfaceAnchor> anchors = new ArrayList<>();
        for (NativeStructureTerrainIntegrator.TerrainTarget target : targets) {
            if (!requiresSurfaceTerrain(target)) {
                continue;
            }
            StructureStart start = target.start();
            TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof PoolElementStructurePiece poolPiece) {
                    if (poolPiece.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                        BoundingBox bounds = poolPiece.getBoundingBox();
                        anchors.add(surfaceAnchor(
                                bounds, bounds.minY() + poolPiece.getGroundLevelDelta(),
                                2, adjustment));
                    }
                    for (JigsawJunction junction : poolPiece.getJunctions()) {
                        anchors.add(new SurfaceAnchor(
                                junction.getSourceX(), junction.getSourceX(),
                                junction.getSourceZ(), junction.getSourceZ(),
                                junction.getSourceGroundY() - 1, 1,
                                junction.getSourceGroundY(), junction.getSourceGroundY()));
                    }
                    continue;
                }
                BoundingBox bounds = piece.getBoundingBox();
                anchors.add(surfaceAnchor(bounds, bounds.minY(), 2, adjustment));
            }
        }
        return List.copyOf(anchors);
    }

    static SurfaceAnchor surfaceAnchor(BoundingBox bounds, int groundY, int strength,
                                       TerrainAdjustment adjustment) {
        int meetY = groundY - 1;
        if (adjustment == TerrainAdjustment.BEARD_BOX) {
            return new SurfaceAnchor(
                    bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(),
                    meetY, strength, groundY, bounds.maxY());
        }
        return new SurfaceAnchor(bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(),
                meetY, strength, groundY, groundY);
    }

    static boolean requiresSurfaceTerrain(StructureStart start) {
        return requiresSurfaceTerrain(new NativeStructureTerrainIntegrator.TerrainTarget(
                null, start, new IrisStructureTerrain().setMode(IrisStructureTerrainMode.SOURCE)));
    }

    static boolean requiresSurfaceTerrain(NativeStructureTerrainIntegrator.TerrainTarget target) {
        if (target == null || target.terrain() == null
                || target.terrain().resolvedMode() != IrisStructureTerrainMode.SOURCE) {
            return false;
        }
        StructureStart start = target.start();
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
        boolean[] rigidBaseSupport = new boolean[width * depth];
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int column = (z - area.minZ()) * width + x - area.minX();
                int originalY = Math.max(area.minY(), Math.min(
                        area.maxY(), surfaceHeight.applyAsInt(x, z)));
                SurfaceResolution resolution = resolveSurface(anchors, x, z, originalY);
                originalHeights[column] = originalY;
                targetHeights[column] = Math.max(area.minY(), Math.min(
                        area.maxY(), resolution.targetY()));
                rigidBaseSupport[column] = resolution.rigidBaseSupport();
            }
        }
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int column = (z - area.minZ()) * width + x - area.minX();
                applySurfaceColumn(world, position, x, z,
                        originalHeights[column], targetHeights[column], area.minY(), area.maxY(),
                        rigidBaseSupport[column]);
            }
        }
    }

    static void applySurfaceColumn(WorldGenLevel world, BlockPos.MutableBlockPos position,
                                   int x, int z, int originalY, int targetY,
                                   int worldMinY, int worldMaxY) {
        applySurfaceColumn(world, position, x, z, originalY, targetY,
                worldMinY, worldMaxY, false);
    }

    static void applySurfaceColumn(WorldGenLevel world, BlockPos.MutableBlockPos position,
                                   int x, int z, int originalY, int targetY,
                                   int worldMinY, int worldMaxY,
                                   boolean requireRigidBaseSupport) {
        if (targetY == originalY) {
            if (requireRigidBaseSupport) {
                ensureRigidBaseTerrain(world, position, x, z, targetY, worldMinY);
            }
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
            if (requireRigidBaseSupport) {
                ensureRigidBaseTerrain(world, position, x, z, targetY, worldMinY, materials);
            }
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
        if (requireRigidBaseSupport) {
            ensureRigidBaseTerrain(world, position, x, z, targetY, worldMinY, materials);
        }
    }

    private static void ensureRigidBaseTerrain(WorldGenLevel world,
                                               BlockPos.MutableBlockPos position,
                                               int x, int z, int targetY, int worldMinY) {
        int supportY = targetY - 1;
        boolean targetIsTerrain = isTerrainBlock(world.getBlockState(position.set(x, targetY, z)));
        boolean supportIsTerrain = supportY < worldMinY
                || isTerrainBlock(world.getBlockState(position.set(x, supportY, z)));
        if (targetIsTerrain && supportIsTerrain) {
            return;
        }
        SurfaceMaterials materials = resolveSurfaceMaterials(
                world, position, x, z, targetY, worldMinY);
        ensureRigidBaseTerrain(world, position, x, z, targetY, worldMinY, materials);
    }

    private static void ensureRigidBaseTerrain(WorldGenLevel world,
                                               BlockPos.MutableBlockPos position,
                                               int x, int z, int targetY, int worldMinY,
                                               SurfaceMaterials materials) {
        int supportY = targetY - 1;
        position.set(x, targetY, z);
        if (!isTerrainBlock(world.getBlockState(position))) {
            world.setBlock(position, materials.surface(), 2);
        }
        if (supportY < worldMinY) {
            return;
        }
        position.set(x, supportY, z);
        if (!isTerrainBlock(world.getBlockState(position))) {
            world.setBlock(position, materials.subsurface(), 2);
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

    record SurfaceAnchor(int minX, int maxX, int minZ, int maxZ, int meetY, int strength,
                         int minInfluenceY, int maxInfluenceY) {
        SurfaceAnchor(int minX, int maxX, int minZ, int maxZ, int meetY, int strength) {
            this(minX, maxX, minZ, maxZ, meetY, strength, meetY + 1, meetY + 1);
        }

        int verticalDistance(int y) {
            return IrisObjectVacuum.outset(y, minInfluenceY, maxInfluenceY);
        }
    }

    private record SurfaceMaterials(BlockState surface, BlockState subsurface) {
    }

    private record SurfaceResolution(int targetY, boolean rigidBaseSupport) {
    }
}

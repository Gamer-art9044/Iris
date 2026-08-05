package art.arcane.iris.nativegen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class NativeStructureSurfaceSupportBuilder {
    private static final int MAX_BATCH_CELLS = 524_288;
    private static final int MAX_BRIDGE_SPAN = 2;
    private static final OccupancyCell BLOCKER = new OccupancyCell(null, true);

    private NativeStructureSurfaceSupportBuilder() {
    }

    static Set<Long> bridgeRigidPieceSupport(
            WorldGenLevel world, BoundingBox area,
            List<NativeStructureTerrainIntegrator.TerrainTarget> targets,
            Supplier<StructureTemplateManager> templates) {
        if (targets == null || targets.isEmpty()) {
            return Set.of();
        }
        Map<Long, BlockState> planned;
        try {
            BatchCellBudget budget = new BatchCellBudget();
            List<SupportRequest> requests = new ArrayList<>();
            Set<StructureStart> seenStarts = Collections.newSetFromMap(
                    new IdentityHashMap<>());
            for (NativeStructureTerrainIntegrator.TerrainTarget target : targets) {
                if (target == null
                        || !NativeStructureSurfaceFitter.requiresSurfaceTerrain(target)
                        || !seenStarts.add(target.start())) {
                    continue;
                }
                collectTargetRequests(
                        world, area, target, templates, budget, requests);
            }
            planned = planWrites(world, area, requests, budget);
        } catch (BatchBudgetExceeded ignored) {
            return Set.of();
        }
        if (planned.isEmpty()) {
            return Set.of();
        }
        List<Map.Entry<Long, BlockState>> ordered = new ArrayList<>(planned.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        Set<Long> written = new HashSet<>();
        for (Map.Entry<Long, BlockState> entry : ordered) {
            BlockPos position = BlockPos.of(entry.getKey());
            BlockState existing = world.getBlockState(position);
            if (!existing.isSolid()
                    && !NativeStructureVegetationClearer.isTreeBlock(existing)
                    && existing.getFluidState().isEmpty()
                    && world.setBlock(position, entry.getValue(), 2)) {
                written.add(entry.getKey());
            }
        }
        return Set.copyOf(written);
    }

    private static void collectTargetRequests(
            WorldGenLevel world, BoundingBox area,
            NativeStructureTerrainIntegrator.TerrainTarget target,
            Supplier<StructureTemplateManager> templates,
            BatchCellBudget budget, List<SupportRequest> requests) {
        if (!NativeStructureSurfaceFitter.requiresSurfaceTerrain(target)) {
            return;
        }
        List<RigidAnchor> anchors = rigidAnchors(target.start());
        if (anchors.size() < 2) {
            return;
        }
        BlockPos referencePos = referencePosition(target.start());
        for (RigidAnchor upper : anchors) {
            Map<Long, Integer> lowerMeets = candidateLowerMeets(
                    area, upper, anchors, budget);
            if (lowerMeets.isEmpty()) {
                continue;
            }
            Map<Long, OccupancyCell> occupancy = effectiveOccupancy(
                    world, area, upper.piece(), lowerMeets,
                    referencePos, templates, budget);
            Map<Long, LowestCell> lowest = lowestCells(occupancy);
            for (Map.Entry<Long, LowestCell> entry : lowest.entrySet()) {
                LowestCell cell = entry.getValue();
                Integer lowerMeet = lowerMeets.get(entry.getKey());
                if (lowerMeet == null || cell.occupancy().blocker()
                        || !isSolidBase(cell.occupancy().state())
                        || cell.y() - lowerMeet < 2
                        || cell.y() - lowerMeet > MAX_BRIDGE_SPAN) {
                    continue;
                }
                budget.consume(1);
                requests.add(new SupportRequest(
                        cell.x(), cell.z(), lowerMeet, cell.y()));
            }
        }
    }

    private static List<RigidAnchor> rigidAnchors(StructureStart start) {
        List<RigidAnchor> anchors = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece poolPiece)
                    || poolPiece.getElement().getProjection()
                    != StructureTemplatePool.Projection.RIGID) {
                continue;
            }
            BoundingBox bounds = poolPiece.getBoundingBox();
            int meetY = bounds.minY() + poolPiece.getGroundLevelDelta() - 1;
            anchors.add(new RigidAnchor(poolPiece, bounds, meetY));
        }
        return List.copyOf(anchors);
    }

    private static Map<Long, Integer> candidateLowerMeets(
            BoundingBox area, RigidAnchor upper, List<RigidAnchor> anchors,
            BatchCellBudget budget) {
        Map<Long, Integer> lowerMeets = new HashMap<>();
        for (RigidAnchor lower : anchors) {
            budget.consume(1);
            int span = upper.meetY() - lower.meetY();
            if (lower.piece() == upper.piece() || span < 1
                    || span > MAX_BRIDGE_SPAN
                    || lower.meetY() < area.minY() || lower.meetY() > area.maxY()) {
                continue;
            }
            int minimumX = Math.max(area.minX(),
                    Math.max(upper.bounds().minX(), lower.bounds().minX()));
            int maximumX = Math.min(area.maxX(),
                    Math.min(upper.bounds().maxX(), lower.bounds().maxX()));
            int minimumZ = Math.max(area.minZ(),
                    Math.max(upper.bounds().minZ(), lower.bounds().minZ()));
            int maximumZ = Math.min(area.maxZ(),
                    Math.min(upper.bounds().maxZ(), lower.bounds().maxZ()));
            if (minimumX > maximumX || minimumZ > maximumZ) {
                continue;
            }
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    budget.consume(1);
                    lowerMeets.merge(columnKey(x, z), lower.meetY(), Math::max);
                }
            }
        }
        return lowerMeets;
    }

    private static Map<Long, OccupancyCell> effectiveOccupancy(
            WorldGenLevel world, BoundingBox area,
            PoolElementStructurePiece piece, Map<Long, Integer> lowerMeets,
            BlockPos referencePos, Supplier<StructureTemplateManager> templates,
            BatchCellBudget budget) {
        List<SinglePoolElement> leaves = new ArrayList<>();
        if (!flattenSingles(piece.getElement(), leaves)) {
            return Map.of();
        }
        List<LeafPlacement> placements = new ArrayList<>(leaves.size());
        Map<Long, OccupancyCell> occupancy = new HashMap<>();
        for (SinglePoolElement leaf : leaves) {
            StructurePlaceSettings settings =
                    NativeStructureReflection.resolvePlacementSettings(leaf, piece, area);
            StructureTemplate template = NativeStructureReflection.resolveTemplate(leaf, templates);
            List<StructureTemplate.StructureBlockInfo> rawBlocks =
                    NativeStructureReflection.resolveTemplateBlocks(
                            template, settings, piece.getPosition());
            budget.consume(rawBlocks.size());
            placements.add(new LeafPlacement(settings, rawBlocks, template));
            for (StructureTemplate.StructureBlockInfo raw : rawBlocks) {
                BlockPos position = piece.getPosition().offset(
                        StructureTemplate.calculateRelativePosition(settings, raw.pos()));
                if (retain(position, area, lowerMeets)) {
                    occupancy.put(position.asLong(), BLOCKER);
                }
            }
        }
        for (LeafPlacement placement : placements) {
            List<StructureTemplate.StructureBlockInfo> processed =
                    NativeStructureReflection.processTemplateBlocks(
                            world, piece.getPosition(), referencePos,
                            placement.settings(), placement.rawBlocks(),
                            placement.template());
            budget.consume(processed.size());
            for (StructureTemplate.StructureBlockInfo block : processed) {
                BlockPos position = block.pos();
                if (!retain(position, area, lowerMeets)) {
                    continue;
                }
                BlockState state = block.state()
                        .mirror(placement.settings().getMirror())
                        .rotate(placement.settings().getRotation());
                occupancy.put(position.asLong(), new OccupancyCell(state, false));
            }
        }
        return occupancy;
    }

    private static boolean flattenSingles(
            StructurePoolElement element, List<SinglePoolElement> leaves) {
        if (element instanceof ListPoolElement listElement) {
            for (StructurePoolElement child : listElement.getElements()) {
                if (!flattenSingles(child, leaves)) {
                    return false;
                }
            }
            return true;
        }
        if (element == EmptyPoolElement.INSTANCE) {
            return true;
        }
        if (element instanceof SinglePoolElement singleElement) {
            leaves.add(singleElement);
            return true;
        }
        return false;
    }

    private static boolean retain(
            BlockPos position, BoundingBox area, Map<Long, Integer> lowerMeets) {
        if (!area.isInside(position)) {
            return false;
        }
        Integer lowerMeet = lowerMeets.get(columnKey(position.getX(), position.getZ()));
        return lowerMeet != null && position.getY() <= lowerMeet + MAX_BRIDGE_SPAN;
    }

    private static Map<Long, LowestCell> lowestCells(
            Map<Long, OccupancyCell> occupancy) {
        Map<Long, LowestCell> lowest = new HashMap<>();
        for (Map.Entry<Long, OccupancyCell> entry : occupancy.entrySet()) {
            BlockPos position = BlockPos.of(entry.getKey());
            long column = columnKey(position.getX(), position.getZ());
            LowestCell current = lowest.get(column);
            if (current == null || position.getY() < current.y()) {
                lowest.put(column, new LowestCell(
                        position.getX(), position.getY(), position.getZ(),
                        entry.getValue()));
            }
        }
        return lowest;
    }

    private static Map<Long, BlockState> planWrites(
            WorldGenLevel world, BoundingBox area,
            List<SupportRequest> requests, BatchCellBudget budget) {
        List<SupportRequest> eligible = new ArrayList<>();
        Set<MaterialKey> materialKeys = new HashSet<>();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (SupportRequest request : requests) {
            BlockState lowerTerrain = world.getBlockState(position.set(
                    request.x(), request.lowerMeetY(), request.z()));
            if (!isTerrainSupport(lowerTerrain)
                    || !gapIsClear(world, area, position, request, budget)) {
                continue;
            }
            eligible.add(request);
            materialKeys.add(new MaterialKey(
                    request.x(), request.z(), request.lowerMeetY()));
        }
        Map<MaterialKey, BlockState> materials = new HashMap<>();
        for (MaterialKey key : materialKeys) {
            materials.put(key, resolveSupportState(
                    world, position, key.x(), key.z(), key.lowerMeetY(), budget));
        }
        Map<Long, BlockState> planned = new HashMap<>();
        Set<Long> conflicts = new HashSet<>();
        eligible.sort(Comparator.comparingInt(SupportRequest::x)
                .thenComparingInt(SupportRequest::z)
                .thenComparingInt(SupportRequest::lowerMeetY)
                .thenComparingInt(SupportRequest::baseY));
        for (SupportRequest request : eligible) {
            MaterialKey materialKey = new MaterialKey(
                    request.x(), request.z(), request.lowerMeetY());
            BlockState material = materials.get(materialKey);
            for (int y = request.lowerMeetY() + 1; y < request.baseY(); y++) {
                budget.consume(1);
                long positionKey = BlockPos.asLong(request.x(), y, request.z());
                if (conflicts.contains(positionKey)) {
                    continue;
                }
                BlockState existing = planned.putIfAbsent(positionKey, material);
                if (existing != null && !existing.equals(material)) {
                    planned.remove(positionKey);
                    conflicts.add(positionKey);
                }
            }
        }
        return planned;
    }

    private static boolean gapIsClear(
            WorldGenLevel world, BoundingBox area, BlockPos.MutableBlockPos position,
            SupportRequest request, BatchCellBudget budget) {
        for (int y = request.lowerMeetY() + 1; y < request.baseY(); y++) {
            budget.consume(1);
            if (!area.isInside(position.set(request.x(), y, request.z()))) {
                return false;
            }
            BlockState state = world.getBlockState(position);
            if (state.isSolid() || NativeStructureVegetationClearer.isTreeBlock(state)
                    || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static BlockState resolveSupportState(
            WorldGenLevel world, BlockPos.MutableBlockPos position,
            int x, int z, int lowerMeetY, BatchCellBudget budget) {
        budget.consume(1);
        BlockState below = world.getBlockState(position.set(x, lowerMeetY - 1, z));
        if (isTerrainSupport(below)) {
            return below;
        }
        return world.getBlockState(position.set(x, lowerMeetY, z));
    }

    private static boolean isSolidBase(BlockState state) {
        return state != null && state.isSolid()
                && !state.is(Blocks.STRUCTURE_VOID)
                && !state.is(Blocks.JIGSAW)
                && state.getFluidState().isEmpty();
    }

    private static boolean isTerrainSupport(BlockState state) {
        return state.isSolid()
                && !NativeStructureVegetationClearer.isTreeBlock(state)
                && state.getFluidState().isEmpty();
    }

    private static BlockPos referencePosition(StructureStart start) {
        BoundingBox bounds = start.getPieces().getFirst().getBoundingBox();
        BlockPos center = bounds.getCenter();
        return new BlockPos(center.getX(), bounds.minY(), center.getZ());
    }

    private static long columnKey(int x, int z) {
        return (long) x << 32 ^ z & 0xffffffffL;
    }

    private record RigidAnchor(
            PoolElementStructurePiece piece, BoundingBox bounds, int meetY) {
    }

    private record LeafPlacement(
            StructurePlaceSettings settings,
            List<StructureTemplate.StructureBlockInfo> rawBlocks,
            StructureTemplate template) {
    }

    private record OccupancyCell(BlockState state, boolean blocker) {
    }

    private record LowestCell(
            int x, int y, int z, OccupancyCell occupancy) {
    }

    private record SupportRequest(int x, int z, int lowerMeetY, int baseY) {
    }

    private record MaterialKey(int x, int z, int lowerMeetY) {
    }

    private static final class BatchCellBudget {
        private int consumed;

        private void consume(int amount) {
            if (amount < 0 || consumed > MAX_BATCH_CELLS - amount) {
                throw BatchBudgetExceeded.INSTANCE;
            }
            consumed += amount;
        }
    }

    private static final class BatchBudgetExceeded extends RuntimeException {
        private static final BatchBudgetExceeded INSTANCE = new BatchBudgetExceeded();

        private BatchBudgetExceeded() {
            super(null, null, false, false);
        }
    }
}

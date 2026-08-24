package art.arcane.iris.engine.river.cave;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;

public final class RiverCaveContainmentPlanner {
    private static final List<CavePosition> DIRECTIONS = List.of(
            new CavePosition(1, 0, 0),
            new CavePosition(-1, 0, 0),
            new CavePosition(0, 1, 0),
            new CavePosition(0, -1, 0),
            new CavePosition(0, 0, 1),
            new CavePosition(0, 0, -1)
    );
    private static final Comparator<RiverCaveSource> SOURCE_PRIORITY = Comparator
            .comparingInt(RiverCaveSource::waterHeadY)
            .reversed()
            .thenComparingLong(RiverCaveSource::sourceId)
            .thenComparingInt(source -> source.entry().x())
            .thenComparingInt(source -> source.entry().y())
            .thenComparingInt(source -> source.entry().z())
            .thenComparingInt(source -> source.target().x())
            .thenComparingInt(source -> source.target().y())
            .thenComparingInt(source -> source.target().z())
            .thenComparing(RiverCaveSource::mode);

    public RiverCavePlan plan(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings
    ) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(source);
        Objects.requireNonNull(settings);

        RiverCaveRejection sourceRejection = validateSource(source);
        if (sourceRejection != RiverCaveRejection.NONE) {
            return rejected(source, sourceRejection);
        }

        PathResult throat = buildThroat(view, source, settings);
        if (throat.rejection() != RiverCaveRejection.NONE) {
            return rejected(source, throat.rejection());
        }

        return switch (source.mode()) {
            case CLOSED_COMPONENT -> planClosedComponent(view, source, settings, throat.positions());
            case GENERATED_GROTTO -> planGeneratedGrotto(view, source, settings, throat.positions());
            case GROTTO_OR_CLOSED_COMPONENT -> planGrottoOrClosedComponent(
                    view,
                    source,
                    settings,
                    throat.positions()
            );
            case WATERFALL_POOL -> planWaterfallPool(
                    view,
                    source,
                    settings,
                    throat.positions()
            );
        };
    }

    public RiverCavePlanningResult planAll(
            CaveVoxelView view,
            Collection<RiverCaveSource> sources,
            RiverCavePlannerSettings settings
    ) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(sources);
        Objects.requireNonNull(settings);

        List<RiverCaveSource> orderedSources = new ArrayList<>(sources);
        orderedSources.sort(SOURCE_PRIORITY);
        List<RiverCavePlan> plans = new ArrayList<>(orderedSources.size());
        Map<CavePosition, RiverCaveAction> combinedActions = new LinkedHashMap<>();
        Map<CavePosition, RiverCaveSource> claimedBy = new HashMap<>();
        Map<CavePosition, CaveVoxelPrecondition> combinedPreconditions = new LinkedHashMap<>();

        for (RiverCaveSource source : orderedSources) {
            RiverCavePlan candidate = plan(view, source, settings);
            if (!candidate.accepted()) {
                plans.add(candidate);
                continue;
            }
            OptionalLong winnerSourceId = findWinningSourceId(
                    candidate.actions().keySet(),
                    claimedBy
            );
            if (winnerSourceId.isPresent()) {
                plans.add(rejectedOverlap(source, winnerSourceId.getAsLong()));
            } else {
                plans.add(candidate);
                combinedActions.putAll(candidate.actions());
                combinedPreconditions.putAll(candidate.baselinePreconditions());
            }
            for (CavePosition position : candidate.actions().keySet()) {
                claimedBy.putIfAbsent(position, source);
            }
        }

        return new RiverCavePlanningResult(plans, combinedActions, combinedPreconditions);
    }

    private RiverCavePlan planGrottoOrClosedComponent(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> throat
    ) {
        CaveVoxel targetVoxel = voxelAt(view, source.target());
        if (isFluidReachable(targetVoxel, settings)) {
            return planClosedComponent(view, source, settings, throat);
        }
        if (targetVoxel == CaveVoxel.LAVA) {
            return rejected(source, RiverCaveRejection.LAVA_CONTACT);
        }
        if (targetVoxel == CaveVoxel.INCOMPATIBLE_FLUID) {
            return rejected(source, RiverCaveRejection.INCOMPATIBLE_FLUID);
        }
        return planGeneratedGrotto(view, source, settings, throat);
    }

    private RiverCavePlan planWaterfallPool(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> throat
    ) {
        if (!view.isOpenToSurface(source.target())) {
            return planGrottoOrClosedComponent(view, source, settings, throat);
        }
        RiverCaveRejection dryThroatRejection = validateDryThroatContacts(view, source, throat);
        if (dryThroatRejection != RiverCaveRejection.NONE) {
            return rejected(source, dryThroatRejection);
        }
        RiverCaveRejection shaftRejection = validateWaterfallShaft(view, source, settings, throat);
        if (shaftRejection != RiverCaveRejection.NONE) {
            return rejected(source, shaftRejection);
        }
        CaveVoxel targetVoxel = voxelAt(view, source.target());
        if (!isFluidReachable(targetVoxel, settings)) {
            return rejected(source, rejectionForTarget(targetVoxel, settings));
        }

        Map<CavePosition, RiverCaveAction> actions = new HashMap<>();
        addThroatActions(actions, throat, source);
        addSealGuards(view, source, actions);
        return accepted(view, source, actions);
    }

    private RiverCavePlan planClosedComponent(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> throat
    ) {
        RiverCaveRejection dryThroatRejection = validateDryThroatContacts(view, source, throat);
        if (dryThroatRejection != RiverCaveRejection.NONE) {
            return rejected(source, dryThroatRejection);
        }
        RiverCaveRejection waterfallRejection = validateWaterfallShaft(view, source, settings, throat);
        if (waterfallRejection != RiverCaveRejection.NONE) {
            return rejected(source, waterfallRejection);
        }

        CaveVoxel targetVoxel = voxelAt(view, source.target());
        if (!isFluidReachable(targetVoxel, settings)) {
            return rejected(source, rejectionForTarget(targetVoxel, settings));
        }

        ComponentResult component = resolveClosedComponent(view, source, settings, throat);
        if (component.rejection() != RiverCaveRejection.NONE) {
            return rejected(source, component.rejection());
        }

        Map<CavePosition, RiverCaveAction> actions = new HashMap<>();
        addThroatActions(actions, throat, source);
        for (CavePosition position : component.positions()) {
            actions.put(position, RiverCaveAction.WET_SOURCE);
        }
        addSealGuards(view, source, actions);
        return accepted(view, source, actions);
    }

    private RiverCaveRejection validateDryThroatContacts(
            CaveVoxelView view,
            RiverCaveSource source,
            List<CavePosition> throat
    ) {
        Set<CavePosition> throatPositions = Set.copyOf(throat);
        for (CavePosition position : throat) {
            if (position.y() <= source.waterHeadY()) {
                continue;
            }
            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                if (throatPositions.contains(neighbor) || isInletOpening(source, neighbor)) {
                    continue;
                }
                if (!view.isInWorld(neighbor)) {
                    return RiverCaveRejection.WORLD_BOUNDARY;
                }
                CaveVoxel voxel = voxelAt(view, neighbor);
                if (voxel == CaveVoxel.LAVA) {
                    return RiverCaveRejection.LAVA_CONTACT;
                }
                if (voxel == CaveVoxel.COMPATIBLE_FLUID) {
                    return RiverCaveRejection.EXISTING_FLUID;
                }
                if (voxel == CaveVoxel.INCOMPATIBLE_FLUID) {
                    return RiverCaveRejection.INCOMPATIBLE_FLUID;
                }
            }
        }
        return RiverCaveRejection.NONE;
    }

    private RiverCaveRejection validateWaterfallShaft(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> throat
    ) {
        if (source.mode() != RiverCaveMode.WATERFALL_POOL) {
            return RiverCaveRejection.NONE;
        }

        Set<CavePosition> throatPositions = Set.copyOf(throat);
        for (CavePosition position : throat) {
            if (position.y() <= source.waterHeadY()) {
                continue;
            }
            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                if (throatPositions.contains(neighbor) || isInletOpening(source, neighbor)) {
                    continue;
                }
                if (!view.isInWorld(neighbor)) {
                    return RiverCaveRejection.WORLD_BOUNDARY;
                }
                RiverCaveRejection boundsRejection = validateBounds(source, settings, neighbor);
                if (boundsRejection != RiverCaveRejection.NONE) {
                    return boundsRejection;
                }
                CaveVoxel voxel = voxelAt(view, neighbor);
                RiverCaveRejection hazard = rejectionForHazard(voxel, settings);
                if (hazard != RiverCaveRejection.NONE) {
                    return hazard;
                }
                if (voxel != CaveVoxel.SOLID) {
                    return RiverCaveRejection.WATERFALL_SHAFT_OPEN;
                }
            }
        }
        return RiverCaveRejection.NONE;
    }

    private RiverCavePlan planGeneratedGrotto(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> throat
    ) {
        GrottoResult grotto = buildGrotto(source, settings);
        if (grotto.rejection() != RiverCaveRejection.NONE) {
            return rejected(source, grotto.rejection());
        }
        Set<CavePosition> chamber = grotto.positions();

        Set<CavePosition> carve = new HashSet<>(chamber.size() + throat.size());
        carve.addAll(chamber);
        carve.addAll(throat);
        RiverCaveRejection carveRejection = validateGeneratedCarve(view, source, settings, carve);
        if (carveRejection != RiverCaveRejection.NONE) {
            return rejected(source, carveRejection);
        }

        BoundaryResult boundary = validateGeneratedBoundary(view, source, settings, carve);
        if (boundary.rejection() != RiverCaveRejection.NONE) {
            return rejected(source, boundary.rejection());
        }

        Map<CavePosition, RiverCaveAction> actions = new HashMap<>();
        addChamberActions(actions, chamber, source.waterHeadY());
        addThroatActions(actions, throat, source);
        for (CavePosition position : boundary.sealGuards()) {
            actions.put(position, RiverCaveAction.SEAL_GUARD);
        }
        return accepted(view, source, actions);
    }

    private ComponentResult resolveClosedComponent(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> throat
    ) {
        Queue<CavePosition> queue = new ArrayDeque<>();
        Set<CavePosition> queued = new HashSet<>();
        Set<CavePosition> resolved = new HashSet<>();

        queue.add(source.target());
        queued.add(source.target());
        RiverCaveRejection seedRejection = addThroatContacts(view, source, settings, throat, queue, queued);
        if (seedRejection != RiverCaveRejection.NONE) {
            return ComponentResult.rejected(seedRejection);
        }

        while (!queue.isEmpty()) {
            CavePosition position = queue.remove();
            RiverCaveRejection positionRejection = validateReachablePosition(view, source, settings, position);
            if (positionRejection != RiverCaveRejection.NONE) {
                return ComponentResult.rejected(positionRejection);
            }
            if (!resolved.add(position)) {
                continue;
            }
            if (resolved.size() > settings.maxFloodVolume()) {
                return ComponentResult.rejected(RiverCaveRejection.VOLUME_LIMIT);
            }

            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                RiverCaveRejection neighborRejection = inspectReachableNeighbor(
                        view,
                        source,
                        settings,
                        neighbor,
                        queue,
                        queued
                );
                if (neighborRejection != RiverCaveRejection.NONE) {
                    return ComponentResult.rejected(neighborRejection);
                }
            }
        }

        return ComponentResult.accepted(resolved);
    }

    private RiverCaveRejection addThroatContacts(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> throat,
            Queue<CavePosition> queue,
            Set<CavePosition> queued
    ) {
        for (CavePosition position : throat) {
            if (position.y() > source.waterHeadY()) {
                continue;
            }
            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                RiverCaveRejection rejection = inspectReachableNeighbor(
                        view,
                        source,
                        settings,
                        neighbor,
                        queue,
                        queued
                );
                if (rejection != RiverCaveRejection.NONE) {
                    return rejection;
                }
            }
        }
        return RiverCaveRejection.NONE;
    }

    private RiverCaveRejection inspectReachableNeighbor(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            CavePosition position,
            Queue<CavePosition> queue,
            Set<CavePosition> queued
    ) {
        if (position.y() > source.waterHeadY()) {
            return inspectAboveHeadNeighbor(view, source, position);
        }
        if (!view.isInWorld(position)) {
            return RiverCaveRejection.WORLD_BOUNDARY;
        }

        CaveVoxel voxel = voxelAt(view, position);
        RiverCaveRejection hazard = rejectionForHazard(voxel, settings);
        if (hazard != RiverCaveRejection.NONE) {
            return hazard;
        }
        if (!isFluidReachable(voxel, settings)) {
            return RiverCaveRejection.NONE;
        }

        RiverCaveRejection boundsRejection = validateBounds(source, settings, position);
        if (boundsRejection != RiverCaveRejection.NONE) {
            return boundsRejection;
        }
        if (queued.add(position)) {
            queue.add(position);
        }
        return RiverCaveRejection.NONE;
    }

    private RiverCaveRejection inspectAboveHeadNeighbor(
            CaveVoxelView view,
            RiverCaveSource source,
            CavePosition position
    ) {
        if (isInletOpening(source, position) || !view.isInWorld(position)) {
            return RiverCaveRejection.NONE;
        }
        CaveVoxel voxel = voxelAt(view, position);
        return switch (voxel) {
            case LAVA -> RiverCaveRejection.LAVA_CONTACT;
            case COMPATIBLE_FLUID -> RiverCaveRejection.EXISTING_FLUID;
            case INCOMPATIBLE_FLUID -> RiverCaveRejection.INCOMPATIBLE_FLUID;
            default -> RiverCaveRejection.NONE;
        };
    }

    private RiverCaveRejection validateReachablePosition(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            CavePosition position
    ) {
        if (!view.isInWorld(position)) {
            return RiverCaveRejection.WORLD_BOUNDARY;
        }
        RiverCaveRejection boundsRejection = validateBounds(source, settings, position);
        if (boundsRejection != RiverCaveRejection.NONE) {
            return boundsRejection;
        }
        if (view.isOpenToSurface(position)) {
            return RiverCaveRejection.OPEN_SURFACE;
        }
        CaveVoxel voxel = voxelAt(view, position);
        RiverCaveRejection hazard = rejectionForHazard(voxel, settings);
        if (hazard != RiverCaveRejection.NONE) {
            return hazard;
        }
        return isFluidReachable(voxel, settings)
                ? RiverCaveRejection.NONE
                : RiverCaveRejection.NO_CAVE_TARGET;
    }

    private PathResult buildThroat(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings
    ) {
        CavePosition entry = source.entry();
        CavePosition target = source.target();
        int deltaX = target.x() - entry.x();
        int deltaY = target.y() - entry.y();
        int deltaZ = target.z() - entry.z();
        int movesX = Math.abs(deltaX);
        int movesY = Math.abs(deltaY);
        int movesZ = Math.abs(deltaZ);
        int length = movesX + movesY + movesZ;
        if (length > settings.maxThroatLength()) {
            return PathResult.rejected(RiverCaveRejection.THROAT_LIMIT);
        }

        int stepX = Integer.signum(deltaX);
        int stepY = Integer.signum(deltaY);
        int stepZ = Integer.signum(deltaZ);
        int usedX = 0;
        int usedY = 0;
        int usedZ = 0;
        CavePosition current = entry;
        List<CavePosition> positions = new ArrayList<>(length + 1);

        while (true) {
            RiverCaveRejection positionRejection = validateThroatPosition(view, source, settings, current);
            if (positionRejection != RiverCaveRejection.NONE) {
                return PathResult.rejected(positionRejection);
            }
            positions.add(current);
            if (current.equals(target)) {
                return expandThroat(view, source, settings, positions);
            }

            int axis = selectNextAxis(source.sourceId(), movesX, movesY, movesZ, usedX, usedY, usedZ);
            if (axis == 0) {
                current = current.offset(stepX, 0, 0);
                usedX++;
            } else if (axis == 1) {
                current = current.offset(0, stepY, 0);
                usedY++;
            } else {
                current = current.offset(0, 0, stepZ);
                usedZ++;
            }
        }
    }

    private PathResult expandThroat(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            List<CavePosition> centerline
    ) {
        int radius = settings.throatRadius();
        int extent = radius - 1;
        int radiusSquared = radius * radius;
        Set<CavePosition> expanded = new LinkedHashSet<>();
        for (CavePosition center : centerline) {
            for (int dx = -extent; dx <= extent; dx++) {
                for (int dy = -extent; dy <= extent; dy++) {
                    for (int dz = -extent; dz <= extent; dz++) {
                        if ((dx * dx) + (dy * dy) + (dz * dz) >= radiusSquared) {
                            continue;
                        }
                        CavePosition position = center.offset(dx, dy, dz);
                        if (position.y() > source.entry().y()) {
                            continue;
                        }
                        RiverCaveRejection rejection = validateThroatPosition(view, source, settings, position);
                        if (rejection != RiverCaveRejection.NONE) {
                            return PathResult.rejected(rejection);
                        }
                        expanded.add(position);
                        if (expanded.size() > settings.maxFloodVolume()) {
                            return PathResult.rejected(RiverCaveRejection.VOLUME_LIMIT);
                        }
                    }
                }
            }
        }
        return PathResult.accepted(List.copyOf(expanded));
    }

    private int selectNextAxis(
            long sourceId,
            int movesX,
            int movesY,
            int movesZ,
            int usedX,
            int usedY,
            int usedZ
    ) {
        double scoreX = nextAxisScore(movesX, usedX);
        double scoreY = nextAxisScore(movesY, usedY);
        double scoreZ = nextAxisScore(movesZ, usedZ);
        double minimum = Math.min(scoreX, Math.min(scoreY, scoreZ));
        int tieOffset = Math.floorMod(sourceId, 3);
        for (int offset = 0; offset < 3; offset++) {
            int axis = (tieOffset + offset) % 3;
            double score = axis == 0 ? scoreX : axis == 1 ? scoreY : scoreZ;
            if (score == minimum) {
                return axis;
            }
        }
        throw new IllegalStateException("No remaining throat axis");
    }

    private double nextAxisScore(int moves, int used) {
        if (used >= moves) {
            return Double.POSITIVE_INFINITY;
        }
        return ((2D * used) + 1D) / moves;
    }

    private RiverCaveRejection validateThroatPosition(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            CavePosition position
    ) {
        if (!view.isInWorld(position)) {
            return RiverCaveRejection.WORLD_BOUNDARY;
        }
        RiverCaveRejection boundsRejection = validateBounds(source, settings, position);
        if (boundsRejection != RiverCaveRejection.NONE) {
            return boundsRejection;
        }
        return rejectionForHazard(voxelAt(view, position), settings);
    }

    private GrottoResult buildGrotto(RiverCaveSource source, RiverCavePlannerSettings settings) {
        int horizontalRadius = settings.grottoHorizontalRadius();
        int verticalRadius = settings.grottoVerticalRadius();
        Set<CavePosition> candidates = new HashSet<>();

        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    if (settings.grottoShape().contains(source, settings, dx, dy, dz)) {
                        candidates.add(source.target().offset(dx, dy, dz));
                        if (candidates.size() > settings.maxFloodVolume()) {
                            return GrottoResult.rejected(RiverCaveRejection.VOLUME_LIMIT);
                        }
                    }
                }
            }
        }
        candidates.add(source.target());
        for (int offset = 1; offset <= settings.dryHeadroom(); offset++) {
            CavePosition headroom = new CavePosition(
                    source.target().x(), source.waterHeadY() + offset, source.target().z());
            if (Math.abs(headroom.y() - source.target().y()) > verticalRadius) {
                return GrottoResult.rejected(RiverCaveRejection.DRY_HEADROOM_LIMIT);
            }
            candidates.add(headroom);
            if (candidates.size() > settings.maxFloodVolume()) {
                return GrottoResult.rejected(RiverCaveRejection.VOLUME_LIMIT);
            }
        }
        return GrottoResult.accepted(connectedGrotto(source.target(), candidates));
    }

    private Set<CavePosition> connectedGrotto(CavePosition target, Set<CavePosition> candidates) {
        Queue<CavePosition> queue = new ArrayDeque<>();
        Set<CavePosition> connected = new HashSet<>();
        queue.add(target);
        connected.add(target);
        while (!queue.isEmpty()) {
            CavePosition position = queue.remove();
            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                if (candidates.contains(neighbor) && connected.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return connected;
    }

    private RiverCaveRejection validateGeneratedCarve(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            Set<CavePosition> carve
    ) {
        for (CavePosition position : carve) {
            if (!view.isInWorld(position)) {
                return RiverCaveRejection.WORLD_BOUNDARY;
            }
            RiverCaveRejection boundsRejection = validateBounds(source, settings, position);
            if (boundsRejection != RiverCaveRejection.NONE) {
                return boundsRejection;
            }
            CaveVoxel voxel = voxelAt(view, position);
            if (isGeneratedInletCarve(view, source, settings, position, voxel)) {
                continue;
            }
            RiverCaveRejection hazard = rejectionForHazard(voxel, settings);
            if (hazard != RiverCaveRejection.NONE) {
                return hazard;
            }
            if (voxel != CaveVoxel.SOLID) {
                return RiverCaveRejection.GROTTO_INTERSECTION;
            }
        }
        return RiverCaveRejection.NONE;
    }

    private BoundaryResult validateGeneratedBoundary(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            Set<CavePosition> carve
    ) {
        Set<CavePosition> guards = new HashSet<>();
        for (CavePosition position : carve) {
            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                if (carve.contains(neighbor)) {
                    continue;
                }
                if (isInletOpening(source, neighbor)
                        || isGeneratedInletOpening(view, source, settings, neighbor)) {
                    continue;
                }
                if (!view.isInWorld(neighbor)) {
                    return BoundaryResult.rejected(RiverCaveRejection.WORLD_BOUNDARY);
                }
                RiverCaveRejection boundsRejection = validateBounds(source, settings, neighbor);
                if (boundsRejection != RiverCaveRejection.NONE) {
                    return BoundaryResult.rejected(boundsRejection);
                }
                CaveVoxel voxel = voxelAt(view, neighbor);
                RiverCaveRejection hazard = rejectionForHazard(voxel, settings);
                if (hazard != RiverCaveRejection.NONE) {
                    return BoundaryResult.rejected(hazard);
                }
                if (voxel != CaveVoxel.SOLID) {
                    return BoundaryResult.rejected(RiverCaveRejection.GROTTO_SHELL_OPEN);
                }
                guards.add(neighbor);
            }
        }
        return BoundaryResult.accepted(guards);
    }

    private void addChamberActions(
            Map<CavePosition, RiverCaveAction> actions,
            Collection<CavePosition> positions,
            int waterHeadY
    ) {
        for (CavePosition position : positions) {
            RiverCaveAction action = position.y() <= waterHeadY
                    ? RiverCaveAction.WET_SOURCE
                    : RiverCaveAction.DRY_AIR;
            actions.put(position, action);
        }
    }

    private void addThroatActions(
            Map<CavePosition, RiverCaveAction> actions,
            Collection<CavePosition> throat,
            RiverCaveSource source
    ) {
        for (CavePosition position : throat) {
            RiverCaveAction action;
            if (position.y() <= source.waterHeadY()) {
                action = RiverCaveAction.WET_SOURCE;
            } else if (source.mode() == RiverCaveMode.WATERFALL_POOL
                    || source.mode() == RiverCaveMode.GENERATED_GROTTO) {
                action = RiverCaveAction.FALLING_WATER;
            } else {
                action = RiverCaveAction.DRY_AIR;
            }
            actions.put(position, action);
        }
    }

    private void addSealGuards(
            CaveVoxelView view,
            RiverCaveSource source,
            Map<CavePosition, RiverCaveAction> actions
    ) {
        Set<CavePosition> guards = new HashSet<>();
        for (CavePosition position : List.copyOf(actions.keySet())) {
            for (CavePosition direction : DIRECTIONS) {
                CavePosition neighbor = position.offset(direction.x(), direction.y(), direction.z());
                if (actions.containsKey(neighbor)
                        || isInletOpening(source, neighbor)
                        || !view.isInWorld(neighbor)) {
                    continue;
                }
                if (voxelAt(view, neighbor) == CaveVoxel.SOLID) {
                    guards.add(neighbor);
                }
            }
        }
        for (CavePosition guard : guards) {
            actions.put(guard, RiverCaveAction.SEAL_GUARD);
        }
    }

    private boolean isInletOpening(RiverCaveSource source, CavePosition position) {
        return position.equals(source.entry().offset(0, 1, 0));
    }

    private boolean isGeneratedInletCarve(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            CavePosition position,
            CaveVoxel voxel
    ) {
        if (voxel != CaveVoxel.CAVE_AIR && voxel != CaveVoxel.COMPATIBLE_FLUID) {
            return false;
        }
        int extent = Math.max(0, settings.throatRadius() - 1);
        long deltaX = (long) position.x() - source.entry().x();
        long deltaZ = (long) position.z() - source.entry().z();
        return position.y() >= source.entry().y() - extent
                && position.y() <= source.entry().y()
                && deltaX * deltaX + deltaZ * deltaZ <= (long) extent * extent
                && view.isOpenToSurface(position);
    }

    private boolean isGeneratedInletOpening(
            CaveVoxelView view,
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            CavePosition position
    ) {
        int radius = Math.max(1, settings.throatRadius());
        long deltaX = (long) position.x() - source.entry().x();
        long deltaZ = (long) position.z() - source.entry().z();
        return position.y() >= source.entry().y()
                && position.y() <= source.entry().y() + 1
                && deltaX * deltaX + deltaZ * deltaZ < (long) radius * radius
                && view.isOpenToSurface(position);
    }

    private RiverCaveRejection validateSource(RiverCaveSource source) {
        if (source.entry().y() < source.waterHeadY()) {
            return RiverCaveRejection.INVALID_SOURCE;
        }
        if (source.target().y() > source.waterHeadY()) {
            return RiverCaveRejection.INVALID_SOURCE;
        }
        if (source.target().y() > source.entry().y()) {
            return RiverCaveRejection.INVALID_SOURCE;
        }
        return RiverCaveRejection.NONE;
    }

    private RiverCaveRejection validateBounds(
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            CavePosition position
    ) {
        boolean closedComponent = source.mode() == RiverCaveMode.CLOSED_COMPONENT
                || source.mode() == RiverCaveMode.WATERFALL_POOL;
        int horizontalRadius = closedComponent
                ? settings.maxClosedComponentHorizontalRadius()
                : settings.maxHorizontalRadius();
        int maximumDepth = closedComponent
                ? settings.maxClosedComponentDepth()
                : settings.maxDepth();
        long deltaX = (long) position.x() - source.entry().x();
        long deltaZ = (long) position.z() - source.entry().z();
        long radiusSquared = (long) horizontalRadius * horizontalRadius;
        if ((deltaX * deltaX) + (deltaZ * deltaZ) > radiusSquared) {
            return RiverCaveRejection.RADIUS_LIMIT;
        }
        long depth = (long) source.entry().y() - position.y();
        int maximumGeneratedY = source.waterHeadY() + settings.dryHeadroom() + 1;
        boolean allowedGeneratedHeadroom = !closedComponent
                && position.y() <= maximumGeneratedY;
        if ((depth < 0L && !allowedGeneratedHeadroom) || depth > maximumDepth) {
            return RiverCaveRejection.DEPTH_LIMIT;
        }
        return RiverCaveRejection.NONE;
    }

    private RiverCaveRejection rejectionForTarget(
            CaveVoxel voxel,
            RiverCavePlannerSettings settings
    ) {
        RiverCaveRejection hazard = rejectionForHazard(voxel, settings);
        return hazard == RiverCaveRejection.NONE ? RiverCaveRejection.NO_CAVE_TARGET : hazard;
    }

    private RiverCaveRejection rejectionForHazard(
            CaveVoxel voxel,
            RiverCavePlannerSettings settings
    ) {
        return switch (voxel) {
            case LAVA -> RiverCaveRejection.LAVA_CONTACT;
            case INCOMPATIBLE_FLUID -> settings.existingFluidPolicy() == RiverCaveFluidPolicy.REPLACE_CONTAINED
                    ? RiverCaveRejection.NONE
                    : RiverCaveRejection.INCOMPATIBLE_FLUID;
            case COMPATIBLE_FLUID -> settings.existingFluidPolicy() == RiverCaveFluidPolicy.REJECT_EXISTING
                    ? RiverCaveRejection.EXISTING_FLUID
                    : RiverCaveRejection.NONE;
            default -> RiverCaveRejection.NONE;
        };
    }

    private boolean isFluidReachable(CaveVoxel voxel, RiverCavePlannerSettings settings) {
        return voxel == CaveVoxel.CAVE_AIR
                || (voxel == CaveVoxel.COMPATIBLE_FLUID
                && settings.existingFluidPolicy() != RiverCaveFluidPolicy.REJECT_EXISTING)
                || (voxel == CaveVoxel.INCOMPATIBLE_FLUID
                && settings.existingFluidPolicy() == RiverCaveFluidPolicy.REPLACE_CONTAINED);
    }

    private CaveVoxel voxelAt(CaveVoxelView view, CavePosition position) {
        return Objects.requireNonNull(view.voxelAt(position));
    }

    private OptionalLong findWinningSourceId(
            Set<CavePosition> positions,
            Map<CavePosition, RiverCaveSource> claimedBy
    ) {
        RiverCaveSource winner = null;
        for (CavePosition position : positions) {
            RiverCaveSource contender = claimedBy.get(position);
            if (contender == null) {
                continue;
            }
            if (winner == null || SOURCE_PRIORITY.compare(contender, winner) < 0) {
                winner = contender;
            }
        }
        return winner == null ? OptionalLong.empty() : OptionalLong.of(winner.sourceId());
    }

    private RiverCavePlan accepted(
            CaveVoxelView view,
            RiverCaveSource source,
            Map<CavePosition, RiverCaveAction> actions
    ) {
        Map<CavePosition, CaveVoxelPrecondition> preconditions = new HashMap<>(actions.size());
        for (CavePosition position : actions.keySet()) {
            preconditions.put(
                    position,
                    new CaveVoxelPrecondition(voxelAt(view, position), view.isOpenToSurface(position))
            );
        }
        return new RiverCavePlan(
                source,
                RiverCaveRejection.NONE,
                actions,
                preconditions,
                OptionalLong.empty()
        );
    }

    private RiverCavePlan rejected(RiverCaveSource source, RiverCaveRejection rejection) {
        return new RiverCavePlan(
                source,
                rejection,
                Map.of(),
                Map.of(),
                OptionalLong.empty()
        );
    }

    private RiverCavePlan rejectedOverlap(RiverCaveSource source, long winnerSourceId) {
        return new RiverCavePlan(
                source,
                RiverCaveRejection.OVERLAPPING_SOURCE,
                Map.of(),
                Map.of(),
                OptionalLong.of(winnerSourceId)
        );
    }

    private record PathResult(List<CavePosition> positions, RiverCaveRejection rejection) {
        private static PathResult accepted(List<CavePosition> positions) {
            return new PathResult(List.copyOf(positions), RiverCaveRejection.NONE);
        }

        private static PathResult rejected(RiverCaveRejection rejection) {
            return new PathResult(List.of(), rejection);
        }
    }

    private record ComponentResult(Set<CavePosition> positions, RiverCaveRejection rejection) {
        private static ComponentResult accepted(Set<CavePosition> positions) {
            return new ComponentResult(Set.copyOf(positions), RiverCaveRejection.NONE);
        }

        private static ComponentResult rejected(RiverCaveRejection rejection) {
            return new ComponentResult(Set.of(), rejection);
        }
    }

    private record BoundaryResult(Set<CavePosition> sealGuards, RiverCaveRejection rejection) {
        private static BoundaryResult accepted(Set<CavePosition> sealGuards) {
            return new BoundaryResult(Set.copyOf(sealGuards), RiverCaveRejection.NONE);
        }

        private static BoundaryResult rejected(RiverCaveRejection rejection) {
            return new BoundaryResult(Set.of(), rejection);
        }
    }

    private record GrottoResult(Set<CavePosition> positions, RiverCaveRejection rejection) {
        private static GrottoResult accepted(Set<CavePosition> positions) {
            return new GrottoResult(Set.copyOf(positions), RiverCaveRejection.NONE);
        }

        private static GrottoResult rejected(RiverCaveRejection rejection) {
            return new GrottoResult(Set.of(), rejection);
        }
    }
}

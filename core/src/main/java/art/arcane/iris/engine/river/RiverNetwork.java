package art.arcane.iris.engine.river;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RiverNetwork {
    private static final long NODE_X_SALT = 0x6A09E667F3BCC909L;
    private static final long NODE_Z_SALT = 0xBB67AE8584CAA73BL;
    private static final long NODE_RANK_SALT = 0x3C6EF372FE94F82BL;
    private static final long BASIN_X_SALT = 0xCBBB9D5DC1059ED8L;
    private static final long BASIN_Z_SALT = 0x629A292A367CD507L;
    private static final long DIAGONAL_SALT = 0xA54FF53A5F1D36F1L;
    private static final long SOURCE_SALT = 0x510E527FADE682D1L;
    private static final long SOURCE_FLOOR_SALT = 0xD6E8FEB86659FD93L;
    private static final long REACH_SALT = 0x9B05688C2B3E6C1FL;
    private static final long DRY_SALT = 0x1F83D9ABFB41BD6BL;
    private static final long MEANDER_SALT = 0x5BE0CD19137E2179L;

    private final RiverNetworkOptions options;

    public RiverNetwork(RiverNetworkOptions options) {
        this.options = Objects.requireNonNull(options);
    }

    public RiverNetworkOptions options() {
        return options;
    }

    public RiverNode nodeAtCell(long cellX, long cellZ, RiverTerrainSampler terrain) {
        return createNode(new RiverNodeId(cellX, cellZ), Objects.requireNonNull(terrain));
    }

    public RiverNode nodeAtWorld(int blockX, int blockZ, RiverTerrainSampler terrain) {
        long cellX = Math.floorDiv(blockX, options.cellSize());
        long cellZ = Math.floorDiv(blockZ, options.cellSize());
        return nodeAtCell(cellX, cellZ, terrain);
    }

    public int tileXForBlock(int blockX) {
        return Math.floorDiv(blockX, options.cellSize() * options.tileCells());
    }

    public int tileZForBlock(int blockZ) {
        return Math.floorDiv(blockZ, options.cellSize() * options.tileCells());
    }

    public RiverTile buildTileForBlock(int blockX, int blockZ, RiverTerrainSampler terrain) {
        return buildTile(tileXForBlock(blockX), tileZForBlock(blockZ), terrain);
    }

    public RiverSample sample(int blockX, int blockZ, RiverTerrainSampler terrain) {
        return buildTileForBlock(blockX, blockZ, terrain).sample(blockX, blockZ);
    }

    public List<RiverNodeId> neighbors(RiverNodeId id) {
        Objects.requireNonNull(id);
        ArrayList<RiverNodeId> neighbors = new ArrayList<>(8);
        addUnique(neighbors, new RiverNodeId(id.cellX() - 1L, id.cellZ()));
        addUnique(neighbors, new RiverNodeId(id.cellX() + 1L, id.cellZ()));
        addUnique(neighbors, new RiverNodeId(id.cellX(), id.cellZ() - 1L));
        addUnique(neighbors, new RiverNodeId(id.cellX(), id.cellZ() + 1L));
        for (long squareX = id.cellX() - 1L; squareX <= id.cellX(); squareX++) {
            for (long squareZ = id.cellZ() - 1L; squareZ <= id.cellZ(); squareZ++) {
                RiverNodeId diagonal = diagonalNeighbor(id, squareX, squareZ);
                if (diagonal != null) {
                    addUnique(neighbors, diagonal);
                }
            }
        }
        neighbors.sort(Comparator.naturalOrder());
        return List.copyOf(neighbors);
    }

    public RiverNode downstream(RiverNodeId id, RiverTerrainSampler terrain) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(terrain);
        NodeResolver resolver = new NodeResolver(terrain);
        RiverNode node = resolver.resolve(id);
        List<RiverNode> candidates = resolver.downstreamCandidates(node);
        for (RiverNode candidate : candidates) {
            RiverRoutingContext context = resolver.routingContext(node, candidate);
            if (!resolver.reachFeasible(context)) {
                continue;
            }
            return resolver.continuationPermitted(context) ? candidate : null;
        }
        return null;
    }

    public List<RiverNode> downstreamCandidates(RiverNodeId id, RiverTerrainSampler terrain) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(terrain);
        NodeResolver resolver = new NodeResolver(terrain);
        return resolver.downstreamCandidates(resolver.resolve(id));
    }

    public RiverRoute trace(RiverNodeId source, RiverTerrainSampler terrain) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(terrain);
        return trace(source, new NodeResolver(terrain));
    }

    public RiverTile buildTile(int tileX, int tileZ, RiverTerrainSampler terrain) {
        Objects.requireNonNull(terrain);
        long tileWorldSize = (long) options.cellSize() * options.tileCells();
        long minimumX = (long) tileX * tileWorldSize;
        long minimumZ = (long) tileZ * tileWorldSize;
        long maximumX = minimumX + tileWorldSize;
        long maximumZ = minimumZ + tileWorldSize;
        requireWorldBounds(minimumX, minimumZ, maximumX, maximumZ);

        int geometryPadding = geometryPaddingCells();
        long targetMinimumCellX = (long) tileX * options.tileCells() - geometryPadding;
        long targetMinimumCellZ = (long) tileZ * options.tileCells() - geometryPadding;
        long targetMaximumCellX = (long) (tileX + 1) * options.tileCells() - 1L + geometryPadding;
        long targetMaximumCellZ = (long) (tileZ + 1) * options.tileCells() - 1L + geometryPadding;
        long sourceMinimumCellX = targetMinimumCellX - options.maxRouteReaches();
        long sourceMinimumCellZ = targetMinimumCellZ - options.maxRouteReaches();
        long sourceMaximumCellX = targetMaximumCellX + options.maxRouteReaches();
        long sourceMaximumCellZ = targetMaximumCellZ + options.maxRouteReaches();

        NodeResolver resolver = new NodeResolver(terrain);
        int sourceWidth = Math.toIntExact(sourceMaximumCellX - sourceMinimumCellX + 1L);
        int sourceDepth = Math.toIntExact(sourceMaximumCellZ - sourceMinimumCellZ + 1L);
        int sourceCount = Math.multiplyExact(sourceWidth, sourceDepth);
        ArrayList<RiverRoute> routes = new ArrayList<>(sourceCount);
        for (long cellX = sourceMinimumCellX; cellX <= sourceMaximumCellX; cellX++) {
            for (long cellZ = sourceMinimumCellZ; cellZ <= sourceMaximumCellZ; cellZ++) {
                routes.add(trace(new RiverNodeId(cellX, cellZ), resolver));
            }
        }
        LinkedHashMap<RiverEdgeId, ReachAccumulator> accumulators = new LinkedHashMap<>();
        for (RiverRoute route : routes) {
            accumulate(route, resolver, accumulators);
        }

        ArrayList<RiverReach> reaches = new ArrayList<>(accumulators.size());
        for (ReachAccumulator accumulator : accumulators.values()) {
            if (!potentiallyIntersects(
                    accumulator.from,
                    accumulator.to,
                    minimumX,
                    minimumZ,
                    maximumX,
                    maximumZ
            )) {
                continue;
            }
            RiverReach reach = accumulator.build();
            if (intersects(reach, minimumX, minimumZ, maximumX, maximumZ)) {
                reaches.add(reach);
            }
        }
        reaches.sort(Comparator.comparing(RiverReach::id));
        return new RiverTile(
                tileX,
                tileZ,
                (int) minimumX,
                (int) minimumZ,
                (int) maximumX,
                (int) maximumZ,
                reaches
        );
    }

    public static long mix(long value) {
        long mixed = value;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }

    private RiverNode createNode(RiverNodeId id, RiverTerrainSampler terrain) {
        NodePosition position = nodePosition(id);
        double x = position.x();
        double z = position.z();
        int blockX = position.blockX();
        int blockZ = position.blockZ();
        RiverTerrainNodeSample terrainSample = terrain.sampleNode(blockX, blockZ);
        double naturalHeight = finiteOrZero(terrainSample.naturalHeight());
        boolean ocean = terrainSample.ocean();
        boolean riverAllowed = terrainSample.riverAllowed();
        double routingNoise = centered(hash(id, NODE_RANK_SALT));
        double routingScore = naturalHeight * options.terrainHeightWeight()
                + routingNoise * options.routingNoiseWeight()
                + finiteOrZero(terrainSample.routingCost());
        double drainageDistance = drainageDistance(id);
        double hydraulicHeight = ocean
                ? options.hydraulicBaseHeight()
                : options.hydraulicBaseHeight()
                        + StrictMath.floor(drainageDistance / options.routingPlateauHeight());
        double rank = ocean ? -Double.MAX_VALUE : drainageDistance;
        return new RiverNode(
                id,
                x,
                z,
                naturalHeight,
                hydraulicHeight,
                rank,
                finiteOrZero(routingScore),
                ocean,
                riverAllowed
        );
    }

    private double drainageDistance(RiverNodeId id) {
        int basinCells = options.routingBasinCells();
        long basinX = Math.floorDiv(id.cellX(), basinCells);
        long basinZ = Math.floorDiv(id.cellZ(), basinCells);
        double nodeX = id.cellX() + 0.5D;
        double nodeZ = id.cellZ() + 0.5D;
        double jitterRadius = basinCells * 0.45D;
        double nearestDistance = Double.MAX_VALUE;
        for (long candidateX = basinX - 1L; candidateX <= basinX + 1L; candidateX++) {
            for (long candidateZ = basinZ - 1L; candidateZ <= basinZ + 1L; candidateZ++) {
                double siteX = (candidateX + 0.5D) * basinCells
                        + centered(hash(candidateX, candidateZ, BASIN_X_SALT)) * jitterRadius;
                double siteZ = (candidateZ + 0.5D) * basinCells
                        + centered(hash(candidateX, candidateZ, BASIN_Z_SALT)) * jitterRadius;
                nearestDistance = StrictMath.min(
                        nearestDistance,
                        StrictMath.hypot(nodeX - siteX, nodeZ - siteZ)
                );
            }
        }
        return nearestDistance;
    }

    private NodePosition nodePosition(RiverNodeId id) {
        double centerX = ((double) id.cellX() + 0.5D) * options.cellSize();
        double centerZ = ((double) id.cellZ() + 0.5D) * options.cellSize();
        double jitterRadius = options.siteJitter() * options.cellSize() * 0.5D;
        double x = centerX + centered(hash(id, NODE_X_SALT)) * jitterRadius;
        double z = centerZ + centered(hash(id, NODE_Z_SALT)) * jitterRadius;
        return new NodePosition(
                x,
                z,
                clampToInt(StrictMath.round(x)),
                clampToInt(StrictMath.round(z))
        );
    }

    private List<RiverNode> computeDownstreamCandidates(RiverNode node, NodeResolver resolver) {
        if (node.ocean() || !node.riverAllowed()) {
            return List.of();
        }
        ArrayList<RankedCandidate> ranked = new ArrayList<>(8);
        for (RiverNodeId neighborId : neighbors(node.id())) {
            RiverNode neighbor = resolver.resolve(neighborId);
            if (!neighbor.riverAllowed()) {
                continue;
            }
            if (compareRank(neighbor, node) >= 0) {
                continue;
            }
            RiverRoutingContext context = resolver.routingContext(node, neighbor);
            double routingCost = finiteNonNegative(resolver.terrain.reachRoutingCost(context));
            double oceanAttraction = neighbor.ocean() ? options.oceanAttraction() : 0.0;
            double flowAlignmentCost = flowAlignmentCost(node, neighbor, resolver);
            ranked.add(new RankedCandidate(
                    neighbor,
                    neighbor.routingScore() + routingCost + flowAlignmentCost - oceanAttraction
            ));
        }
        ranked.sort((first, second) -> {
            int costComparison = Double.compare(first.cost(), second.cost());
            return costComparison != 0 ? costComparison : compareRank(first.node(), second.node());
        });
        ArrayList<RiverNode> candidates = new ArrayList<>(ranked.size());
        for (RankedCandidate candidate : ranked) {
            candidates.add(candidate.node());
        }
        return List.copyOf(candidates);
    }

    private RiverRoute trace(RiverNodeId sourceId, NodeResolver resolver) {
        if (!resolver.sourcePermitted(sourceId)) {
            return new RiverRoute(sourceId, RiverRouteState.SUPPRESSED, List.of(), false, false);
        }
        RiverNode source = resolver.resolve(sourceId);

        ArrayList<RiverEdgeId> edges = new ArrayList<>(options.maxRouteReaches());
        RiverNode current = source;
        boolean reachedOcean = false;
        for (int reachIndex = 0; reachIndex < options.maxRouteReaches(); reachIndex++) {
            RiverNode next = null;
            int examined = 0;
            for (RiverNode candidate : resolver.downstreamCandidates(current)) {
                if (examined >= options.downstreamCandidateLimit()) {
                    break;
                }
                examined++;
                RiverRoutingContext context = resolver.routingContext(current, candidate);
                if (resolver.reachFeasible(context)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            RiverEdgeId edgeId = RiverEdgeId.of(current.id(), next.id());
            if (!resolver.continuationPermitted(resolver.routingContext(current, next))) {
                break;
            }
            edges.add(edgeId);
            current = next;
            if (current.ocean()) {
                reachedOcean = true;
                break;
            }
        }

        if (reachedOcean) {
            return new RiverRoute(sourceId, RiverRouteState.WET, edges, true, false);
        }
        if (!edges.isEmpty()) {
            RiverTerminalPolicy terminalPolicy = resolver.terminalPolicy(current);
            if (terminalPolicy == RiverTerminalPolicy.WET
                    || (terminalPolicy == RiverTerminalPolicy.INHERIT && !options.requireOcean())) {
                return new RiverRoute(sourceId, RiverRouteState.WET, edges, false, true);
            }
            if ((terminalPolicy == RiverTerminalPolicy.DRY
                    || terminalPolicy == RiverTerminalPolicy.INHERIT)
                    && resolver.dryPermitted(sourceId)) {
                return new RiverRoute(sourceId, RiverRouteState.DRY, edges, false, true);
            }
        }
        return new RiverRoute(sourceId, RiverRouteState.SUPPRESSED, List.of(), false, false);
    }

    private void accumulate(
            RiverRoute route,
            NodeResolver resolver,
            Map<RiverEdgeId, ReachAccumulator> accumulators
    ) {
        if (route.state() == RiverRouteState.SUPPRESSED) {
            return;
        }
        for (int edgeIndex = 0; edgeIndex < route.edges().size(); edgeIndex++) {
            RiverEdgeId edgeId = route.edges().get(edgeIndex);
            ReachAccumulator accumulator = accumulators.get(edgeId);
            if (accumulator == null) {
                RiverNode first = resolver.resolve(edgeId.first());
                RiverNode second = resolver.resolve(edgeId.second());
                RiverNode from = compareRank(first, second) > 0 ? first : second;
                RiverNode to = from == first ? second : first;
                accumulator = new ReachAccumulator(
                        edgeId,
                        from,
                        to,
                        resolver.routingContext(from, to),
                        resolver.terrain
                );
                accumulators.put(edgeId, accumulator);
            }
            boolean terminal = route.terminal() && edgeIndex == route.edges().size() - 1;
            accumulator.add(route.state(), terminal);
        }
    }

    private RiverNodeId diagonalNeighbor(RiverNodeId id, long squareX, long squareZ) {
        boolean ascending = (hash(squareX, squareZ, DIAGONAL_SALT) & 1L) == 0L;
        RiverNodeId first = ascending
                ? new RiverNodeId(squareX, squareZ)
                : new RiverNodeId(squareX, squareZ + 1L);
        RiverNodeId second = ascending
                ? new RiverNodeId(squareX + 1L, squareZ + 1L)
                : new RiverNodeId(squareX + 1L, squareZ);
        if (id.equals(first)) {
            return second;
        }
        return id.equals(second) ? first : null;
    }

    private RiverPolyline createPolyline(
            RiverEdgeId id,
            RiverNode from,
            RiverNode to,
            NodeResolver resolver
    ) {
        int pointCount = options.meanderSubdivisions() + 1;
        double[] x = new double[pointCount];
        double[] z = new double[pointCount];
        double deltaX = to.x() - from.x();
        double deltaZ = to.z() - from.z();
        double length = StrictMath.hypot(deltaX, deltaZ);
        double normalX = length == 0.0 ? 0.0 : -deltaZ / length;
        double normalZ = length == 0.0 ? 0.0 : deltaX / length;
        double maximumOffset = StrictMath.min(options.meanderStrength(), length * 0.35);
        double directionX = length == 0D ? 1D : deltaX / length;
        double directionZ = length == 0D ? 0D : deltaZ / length;
        FlowTangent fromTangent = flowTangent(from, directionX, directionZ, resolver);
        FlowTangent toTangent = flowTangent(to, directionX, directionZ, resolver);
        for (int point = 0; point < pointCount; point++) {
            double t = (double) point / (pointCount - 1);
            double tSquared = t * t;
            double tCubed = tSquared * t;
            double fromWeight = 2D * tCubed - 3D * tSquared + 1D;
            double fromTangentWeight = tCubed - 2D * tSquared + t;
            double toWeight = -2D * tCubed + 3D * tSquared;
            double toTangentWeight = tCubed - tSquared;
            double curvedX = fromWeight * from.x()
                    + fromTangentWeight * fromTangent.x() * maximumOffset
                    + toWeight * to.x()
                    + toTangentWeight * toTangent.x() * maximumOffset;
            double curvedZ = fromWeight * from.z()
                    + fromTangentWeight * fromTangent.z() * maximumOffset
                    + toWeight * to.z()
                    + toTangentWeight * toTangent.z() * maximumOffset;
            double straightX = from.x() + deltaX * t;
            double straightZ = from.z() + deltaZ * t;
            RiverMeanderContext context = new RiverMeanderContext(id, t, straightX, straightZ);
            double configuredNoise = resolver.terrain.meanderNoise(context);
            double noise = Double.isFinite(configuredNoise)
                    ? StrictMath.max(-1.0, StrictMath.min(1.0, configuredNoise))
                    : smoothEdgeNoise(id, t * 3.0);
            double envelope = 16D * tSquared * (1D - t) * (1D - t);
            double offset = maximumOffset * 0.5D * envelope * noise;
            x[point] = curvedX + normalX * offset;
            z[point] = curvedZ + normalZ * offset;
        }
        x[0] = from.x();
        z[0] = from.z();
        x[pointCount - 1] = to.x();
        z[pointCount - 1] = to.z();
        return new RiverPolyline(x, z);
    }

    private FlowTangent flowTangent(
            RiverNode node,
            double fallbackX,
            double fallbackZ,
            NodeResolver resolver
    ) {
        FlowTangent preferred = resolver.flowTangent(node);
        double tangentX = preferred.x();
        double tangentZ = preferred.z();
        if (tangentX == 0D && tangentZ == 0D) {
            return new FlowTangent(fallbackX, fallbackZ);
        }
        double alignment = tangentX * fallbackX + tangentZ * fallbackZ;
        if (alignment < 0D) {
            tangentX = -tangentX;
            tangentZ = -tangentZ;
            alignment = -alignment;
        }
        if (alignment < 0.15D) {
            return new FlowTangent(fallbackX, fallbackZ);
        }
        return new FlowTangent(tangentX, tangentZ);
    }

    private FlowTangent resolveFlowTangent(RiverNode node, RiverTerrainSampler terrain) {
        double spacing = StrictMath.max(1D, options.cellSize() * 0.5D);
        double left = terrain.flowNoise(node.x() - spacing, node.z());
        double right = terrain.flowNoise(node.x() + spacing, node.z());
        double top = terrain.flowNoise(node.x(), node.z() - spacing);
        double bottom = terrain.flowNoise(node.x(), node.z() + spacing);
        if (!Double.isFinite(left) || !Double.isFinite(right)
                || !Double.isFinite(top) || !Double.isFinite(bottom)) {
            return new FlowTangent(0D, 0D);
        }
        double tangentX = -(bottom - top);
        double tangentZ = right - left;
        double tangentLength = StrictMath.hypot(tangentX, tangentZ);
        if (tangentLength <= 0.0000001D) {
            return new FlowTangent(0D, 0D);
        }
        return new FlowTangent(tangentX / tangentLength, tangentZ / tangentLength);
    }

    private double flowAlignmentCost(RiverNode from, RiverNode to, NodeResolver resolver) {
        if (options.flowAlignmentWeight() <= 0D) {
            return 0D;
        }
        FlowTangent tangent = resolver.flowTangent(from);
        if (tangent.x() == 0D && tangent.z() == 0D) {
            return 0D;
        }
        double deltaX = to.x() - from.x();
        double deltaZ = to.z() - from.z();
        double length = StrictMath.hypot(deltaX, deltaZ);
        if (length <= 0.0000001D) {
            return options.flowAlignmentWeight();
        }
        double alignment = StrictMath.abs(
                tangent.x() * deltaX / length + tangent.z() * deltaZ / length
        );
        return options.flowAlignmentWeight() * (1D - StrictMath.min(1D, alignment));
    }

    private double smoothEdgeNoise(RiverEdgeId id, double position) {
        int lower = (int) StrictMath.floor(position);
        int upper = lower + 1;
        double fraction = position - lower;
        double fade = fraction * fraction * (3.0 - 2.0 * fraction);
        double a = centered(mix(options.seed() ^ id.stableId() ^ MEANDER_SALT ^ lower * 0x9E3779B97F4A7C15L));
        double b = centered(mix(options.seed() ^ id.stableId() ^ MEANDER_SALT ^ upper * 0x9E3779B97F4A7C15L));
        return a + (b - a) * fade;
    }

    private boolean intersects(
            RiverReach reach,
            long minimumX,
            long minimumZ,
            long maximumX,
            long maximumZ
    ) {
        double radius = reach.width() * 0.5 + reach.bankWidth();
        RiverPolyline polyline = reach.polyline();
        for (int point = 0; point < polyline.size() - 1; point++) {
            double segmentMinimumX = StrictMath.min(polyline.x(point), polyline.x(point + 1)) - radius;
            double segmentMaximumX = StrictMath.max(polyline.x(point), polyline.x(point + 1)) + radius;
            double segmentMinimumZ = StrictMath.min(polyline.z(point), polyline.z(point + 1)) - radius;
            double segmentMaximumZ = StrictMath.max(polyline.z(point), polyline.z(point + 1)) + radius;
            if (segmentMaximumX >= minimumX && segmentMinimumX < maximumX
                    && segmentMaximumZ >= minimumZ && segmentMinimumZ < maximumZ) {
                return true;
            }
        }
        return false;
    }

    private boolean potentiallyIntersects(
            RiverNode from,
            RiverNode to,
            long minimumX,
            long minimumZ,
            long maximumX,
            long maximumZ
    ) {
        double length = StrictMath.hypot(to.x() - from.x(), to.z() - from.z());
        double maximumMeander = StrictMath.min(options.meanderStrength(), length * 0.35D);
        double padding = options.maximumReachRadius() + maximumMeander;
        double reachMinimumX = StrictMath.min(from.x(), to.x()) - padding;
        double reachMaximumX = StrictMath.max(from.x(), to.x()) + padding;
        double reachMinimumZ = StrictMath.min(from.z(), to.z()) - padding;
        double reachMaximumZ = StrictMath.max(from.z(), to.z()) + padding;
        return reachMaximumX >= minimumX && reachMinimumX < maximumX
                && reachMaximumZ >= minimumZ && reachMinimumZ < maximumZ;
    }

    private int geometryPaddingCells() {
        double maximumEdgeAxisDelta = options.cellSize() * (1D + options.siteJitter());
        double maximumEdgeLength = StrictMath.sqrt(2D) * maximumEdgeAxisDelta;
        double maximumMeander = StrictMath.min(options.meanderStrength(), maximumEdgeLength * 0.35D);
        double displacement = options.maximumReachRadius() + maximumMeander;
        return 1 + (int) StrictMath.ceil(displacement / options.cellSize());
    }

    private int compareRank(RiverNode first, RiverNode second) {
        if (first.ocean() != second.ocean()) {
            return first.ocean() ? -1 : 1;
        }
        int rankComparison = Double.compare(first.rank(), second.rank());
        if (rankComparison != 0) {
            return rankComparison;
        }
        int hydraulicComparison = Double.compare(first.hydraulicHeight(), second.hydraulicHeight());
        return hydraulicComparison != 0 ? hydraulicComparison : first.id().compareTo(second.id());
    }

    private long hash(RiverNodeId id, long salt) {
        return mix(options.seed() ^ id.stableId() ^ salt);
    }

    private long hash(RiverEdgeId id, long salt) {
        return mix(options.seed() ^ id.stableId() ^ salt);
    }

    private long hash(long x, long z, long salt) {
        return mix(options.seed() ^ salt ^ mix(x * 0x9E3779B97F4A7C15L) ^ Long.rotateLeft(mix(z), 27));
    }

    private static void addUnique(List<RiverNodeId> values, RiverNodeId candidate) {
        if (!values.contains(candidate)) {
            values.add(candidate);
        }
    }

    private static boolean gate(long hash, double chance) {
        if (chance <= 0.0) {
            return false;
        }
        if (chance >= 1.0) {
            return true;
        }
        return unit(hash) < chance;
    }

    private static double centered(long hash) {
        return unit(hash) * 2.0 - 1.0;
    }

    private static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static int clampToInt(long value) {
        return (int) StrictMath.max(Integer.MIN_VALUE, StrictMath.min(Integer.MAX_VALUE, value));
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    private static double effectiveChance(double baseChance, double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 0.0;
        }
        return StrictMath.min(1.0, baseChance * multiplier);
    }

    private static void requireWorldBounds(long minimumX, long minimumZ, long maximumX, long maximumZ) {
        if (minimumX < Integer.MIN_VALUE || minimumZ < Integer.MIN_VALUE
                || maximumX > Integer.MAX_VALUE || maximumZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("River tile exceeds integer world coordinates");
        }
    }

    private final class NodeResolver {
        private final RiverTerrainSampler terrain;
        private final Map<RiverNodeId, RiverNode> nodes;
        private final Map<RiverNodeId, List<RiverNode>> downstreamCandidates;
        private final Map<RiverNodeId, Boolean> sourceGates;
        private final Map<SourceTileId, List<RiverNodeId>> minimumSources;
        private final Map<RiverEdgeId, Boolean> reachFeasibilities;
        private final Map<RiverEdgeId, Boolean> continuationGates;
        private final Map<RiverNodeId, Boolean> dryGates;
        private final Map<RiverNodeId, RiverTerminalPolicy> terminalPolicies;
        private final Map<RiverEdgeId, RiverRoutingContext> routingContexts;
        private final Map<RiverNodeId, FlowTangent> flowTangents;

        private NodeResolver(RiverTerrainSampler terrain) {
            this.terrain = terrain;
            nodes = new HashMap<>();
            downstreamCandidates = new HashMap<>();
            sourceGates = new HashMap<>();
            minimumSources = new HashMap<>();
            reachFeasibilities = new HashMap<>();
            continuationGates = new HashMap<>();
            dryGates = new HashMap<>();
            terminalPolicies = new HashMap<>();
            routingContexts = new HashMap<>();
            flowTangents = new HashMap<>();
        }

        private RiverNode resolve(RiverNodeId id) {
            return nodes.computeIfAbsent(id, key -> createNode(key, terrain));
        }

        private List<RiverNode> downstreamCandidates(RiverNode node) {
            return downstreamCandidates.computeIfAbsent(
                    node.id(),
                    ignored -> computeDownstreamCandidates(node, this));
        }

        private boolean sourcePermitted(RiverNodeId sourceId) {
            return sourceGates.computeIfAbsent(sourceId, this::computeSourcePermitted);
        }

        private boolean computeSourcePermitted(RiverNodeId sourceId) {
            if (options.sourceChance() <= 0D) {
                return false;
            }
            boolean minimumSelected = minimumSources(sourceId).contains(sourceId);
            if (minimumSelected) {
                return true;
            }
            long sourceHash = hash(sourceId, SOURCE_SALT);
            double maximumMultiplier = terrain.maximumSourceChanceMultiplier();
            if (!minimumSelected
                    && Double.isFinite(maximumMultiplier)
                    && !gate(sourceHash, effectiveChance(options.sourceChance(), maximumMultiplier))) {
                return false;
            }
            NodePosition position = nodePosition(sourceId);
            RiverTerrainSourceSample sourceSample = terrain.sampleSource(position.blockX(), position.blockZ());
            double chance = effectiveChance(
                    options.sourceChance(),
                    sourceSample.chanceMultiplier()
            );
            boolean selected = gate(sourceHash, chance);
            boolean permitted = selected
                    && sourceSample.riverAllowed()
                    && !sourceSample.ocean();
            return permitted;
        }

        private List<RiverNodeId> minimumSources(RiverNodeId sourceId) {
            if (options.minimumSourcesPerTile() <= 0 || options.sourceChance() <= 0D) {
                return List.of();
            }
            SourceTileId tileId = new SourceTileId(
                    Math.floorDiv(sourceId.cellX(), options.tileCells()),
                    Math.floorDiv(sourceId.cellZ(), options.tileCells())
            );
            return minimumSources.computeIfAbsent(tileId, this::computeMinimumSources);
        }

        private List<RiverNodeId> computeMinimumSources(SourceTileId tileId) {
            long minimumCellX = tileId.tileX() * options.tileCells();
            long minimumCellZ = tileId.tileZ() * options.tileCells();
            int candidateCount = options.tileCells() * options.tileCells();
            int targetCount = Math.min(options.minimumSourcesPerTile(), candidateCount);
            if (targetCount == candidateCount) {
                ArrayList<RiverNodeId> selected = new ArrayList<>(candidateCount);
                for (long cellX = minimumCellX; cellX < minimumCellX + options.tileCells(); cellX++) {
                    for (long cellZ = minimumCellZ; cellZ < minimumCellZ + options.tileCells(); cellZ++) {
                        RiverNodeId candidateId = new RiverNodeId(cellX, cellZ);
                        if (sourceFloorEligible(candidateId)) {
                            selected.add(candidateId);
                        }
                    }
                }
                return List.copyOf(selected);
            }
            ArrayList<WeightedSource> candidates = new ArrayList<>(candidateCount);
            for (long cellX = minimumCellX; cellX < minimumCellX + options.tileCells(); cellX++) {
                for (long cellZ = minimumCellZ; cellZ < minimumCellZ + options.tileCells(); cellZ++) {
                    RiverNodeId candidateId = new RiverNodeId(cellX, cellZ);
                    candidates.add(new WeightedSource(
                            candidateId,
                            -drainageDistance(candidateId)
                                    + unit(hash(candidateId, SOURCE_FLOOR_SALT)) * 0.25D
                    ));
                }
            }
            candidates.sort(Comparator.comparingDouble(WeightedSource::priority)
                    .thenComparing(WeightedSource::id));
            ArrayList<RiverNodeId> selected = new ArrayList<>(targetCount);
            for (WeightedSource candidate : candidates) {
                if (!sourceFloorEligible(candidate.id())) {
                    continue;
                }
                selected.add(candidate.id());
                if (selected.size() >= targetCount) {
                    break;
                }
            }
            return List.copyOf(selected);
        }

        private boolean sourceFloorEligible(RiverNodeId candidateId) {
            NodePosition position = nodePosition(candidateId);
            RiverTerrainSourceSample sourceSample = terrain.sampleSource(position.blockX(), position.blockZ());
            return sourceSample.riverAllowed()
                    && !sourceSample.ocean()
                    && Double.isFinite(sourceSample.chanceMultiplier())
                    && sourceSample.chanceMultiplier() > 0D;
        }

        private boolean reachFeasible(RiverRoutingContext context) {
            return reachFeasibilities.computeIfAbsent(
                    context.edgeId(),
                    ignored -> terrain.allowsReach(context));
        }

        private boolean continuationPermitted(RiverRoutingContext context) {
            return continuationGates.computeIfAbsent(context.edgeId(), ignored -> {
                double chance = effectiveChance(
                        options.reachChance(),
                        terrain.reachChanceMultiplier(context.midpointX(), context.midpointZ())
                );
                return gate(hash(context.edgeId(), REACH_SALT), chance);
            });
        }

        private boolean dryPermitted(RiverNodeId sourceId) {
            return dryGates.computeIfAbsent(
                    sourceId,
                    ignored -> gate(hash(sourceId, DRY_SALT), options.dryChannelChance()));
        }

        private RiverTerminalPolicy terminalPolicy(RiverNode terminal) {
            return terminalPolicies.computeIfAbsent(terminal.id(), ignored -> {
                int terminalX = clampToInt(StrictMath.round(terminal.x()));
                int terminalZ = clampToInt(StrictMath.round(terminal.z()));
                RiverTerminalPolicy sampled = terrain.terminalPolicy(terminalX, terminalZ);
                return sampled == null ? RiverTerminalPolicy.INHERIT : sampled;
            });
        }

        private RiverRoutingContext routingContext(RiverNode from, RiverNode to) {
            RiverEdgeId edgeId = RiverEdgeId.of(from.id(), to.id());
            return routingContexts.computeIfAbsent(edgeId, ignored -> RiverRoutingContext.lazy(
                    edgeId,
                    from,
                    to,
                    () -> createPolyline(edgeId, from, to, this)));
        }

        private FlowTangent flowTangent(RiverNode node) {
            return flowTangents.computeIfAbsent(
                    node.id(),
                    ignored -> resolveFlowTangent(node, terrain));
        }
    }

    private record RankedCandidate(RiverNode node, double cost) {
    }

    private record NodePosition(double x, double z, int blockX, int blockZ) {
    }

    private record FlowTangent(double x, double z) {
    }

    private record SourceTileId(long tileX, long tileZ) {
    }

    private record WeightedSource(RiverNodeId id, double priority) {
    }

    private final class ReachAccumulator {
        private final RiverEdgeId id;
        private final RiverNode from;
        private final RiverNode to;
        private final RiverRoutingContext context;
        private final RiverTerrainSampler terrain;
        private int wetFlow;
        private int dryFlow;
        private int terminalWetFlow;
        private int terminalDryFlow;

        private ReachAccumulator(
                RiverEdgeId id,
                RiverNode from,
                RiverNode to,
                RiverRoutingContext context,
                RiverTerrainSampler terrain
        ) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.context = context;
            this.terrain = terrain;
        }

        private void add(RiverRouteState state, boolean terminal) {
            if (state == RiverRouteState.WET) {
                wetFlow++;
                if (terminal) {
                    terminalWetFlow++;
                }
            } else if (state == RiverRouteState.DRY) {
                dryFlow++;
                if (terminal) {
                    terminalDryFlow++;
                }
            }
        }

        private RiverReach build() {
            int flow = wetFlow + dryFlow;
            int order = 1 + (31 - Integer.numberOfLeadingZeros(flow));
            double baseWidth = positiveOrFallback(
                    terrain.channelWidth(context, options.channelWidth()),
                    options.channelWidth()
            );
            double bankWidth = nonNegativeOrFallback(
                    terrain.bankWidth(context, options.bankWidth()),
                    options.bankWidth()
            );
            double baseDepth = positiveOrFallback(
                    terrain.depth(context, options.depth()),
                    options.depth()
            );
            double width = StrictMath.min(
                    options.maxChannelWidth(),
                    baseWidth * (1.0 + options.orderWidthFactor() * (order - 1))
            );
            bankWidth = StrictMath.min(options.maxBankWidth(), bankWidth);
            double depth = StrictMath.min(
                    options.maxDepth(),
                    baseDepth * (1.0 + options.orderDepthFactor() * (order - 1))
            );
            RiverRouteState state = wetFlow > 0 ? RiverRouteState.WET : RiverRouteState.DRY;
            return new RiverReach(
                    id,
                    from,
                    to,
                    state,
                    flow,
                    order,
                    width,
                    bankWidth,
                    depth,
                    state == RiverRouteState.WET && to.ocean(),
                    state == RiverRouteState.WET
                            ? terminalWetFlow == wetFlow
                            : terminalDryFlow == dryFlow,
                    context.polyline()
            );
        }
    }

    private static double positiveOrFallback(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrFallback(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }
}

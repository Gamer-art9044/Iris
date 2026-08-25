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
    private static final long BASIN_DEVIATION_X_SALT = 0xA4093822299F31D0L;
    private static final long BASIN_DEVIATION_Z_SALT = 0x082EFA98EC4E6C89L;
    private static final long DIAGONAL_SALT = 0xA54FF53A5F1D36F1L;
    private static final long SOURCE_SALT = 0x510E527FADE682D1L;
    private static final long SOURCE_FLOOR_SALT = 0xD6E8FEB86659FD93L;
    private static final long REACH_SALT = 0x9B05688C2B3E6C1FL;
    private static final long DRY_SALT = 0x1F83D9ABFB41BD6BL;
    private static final long WORM_FAMILY_SALT = 0x5BE0CD19137E2179L;
    private static final long WORM_CHILD_GATE_SALT = 0x452821E638D01377L;
    private static final long WORM_CHILD_SELECTION_SALT = 0xBE5466CF34E90C6CL;
    private static final long WORM_PRIMARY_SALT = 0x243F6A8885A308D3L;
    private static final long WORM_DETAIL_SALT = 0x13198A2E03707344L;
    private static final long BODY_WIDTH_PRIMARY_SALT = 0xA4093822299F31D0L;
    private static final long BODY_WIDTH_DETAIL_SALT = 0x082EFA98EC4E6C89L;
    private static final long BODY_BANK_PRIMARY_SALT = 0x452821E638D01377L;
    private static final long BODY_BANK_DETAIL_SALT = 0xBE5466CF34E90C6CL;
    private static final long BODY_DEPTH_PRIMARY_SALT = 0xC0AC29B7C97C50DDL;
    private static final long BODY_DEPTH_DETAIL_SALT = 0x3F84D5B5B5470917L;
    private static final long BODY_ROOF_PRIMARY_SALT = 0xD1310BA698DFB5ACL;
    private static final long BODY_ROOF_DETAIL_SALT = 0x2FFD72DBD01ADFB7L;
    private static final long CONFLUENCE_SALT = 0x9E3779B97F4A7C15L;
    private static final long BRANCH_SLOT_SALT = 0x94D049BB133111EBL;
    private static final long BRANCH_GATE_SALT = 0x2545F4914F6CDD1DL;
    private static final int MINIMUM_BODY_PROFILE_SAMPLES = 12;
    private static final int MAXIMUM_BODY_PROFILE_SAMPLES = 512;
    private static final double PERLIN_NORMALIZATION = 1.4142135623730951D;

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
        double drainageDistance = drainageBasin(id).distance();
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
        return drainageBasin(id).distance();
    }

    private DrainageBasin drainageBasin(RiverNodeId id) {
        int basinCells = options.routingBasinCells();
        double nodeX = id.cellX() + 0.5D;
        double nodeZ = id.cellZ() + 0.5D;
        double deviationStrength = options.routingDeviationStrengthCells();
        if (deviationStrength > 0D) {
            int deviationScale = options.routingDeviationScaleCells();
            double originalX = nodeX;
            double originalZ = nodeZ;
            nodeX += smoothCellNoise(originalX, originalZ, deviationScale, BASIN_DEVIATION_X_SALT)
                    * deviationStrength;
            nodeZ += smoothCellNoise(originalX, originalZ, deviationScale, BASIN_DEVIATION_Z_SALT)
                    * deviationStrength;
        }
        long basinX = (long) StrictMath.floor(nodeX / basinCells);
        long basinZ = (long) StrictMath.floor(nodeZ / basinCells);
        double jitterRadius = basinCells * 0.45D;
        double nearestDistance = Double.MAX_VALUE;
        DrainageBasinId nearestId = null;
        for (long candidateX = basinX - 1L; candidateX <= basinX + 1L; candidateX++) {
            for (long candidateZ = basinZ - 1L; candidateZ <= basinZ + 1L; candidateZ++) {
                double siteX = (candidateX + 0.5D) * basinCells
                        + centered(hash(candidateX, candidateZ, BASIN_X_SALT)) * jitterRadius;
                double siteZ = (candidateZ + 0.5D) * basinCells
                        + centered(hash(candidateX, candidateZ, BASIN_Z_SALT)) * jitterRadius;
                double distance = StrictMath.hypot(nodeX - siteX, nodeZ - siteZ);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestId = new DrainageBasinId(candidateX, candidateZ);
                }
            }
        }
        return new DrainageBasin(nearestId, nearestDistance);
    }

    private double smoothCellNoise(double x, double z, int scale, long salt) {
        double scaledX = x / scale;
        double scaledZ = z / scale;
        long minimumX = (long) StrictMath.floor(scaledX);
        long minimumZ = (long) StrictMath.floor(scaledZ);
        double fractionX = scaledX - minimumX;
        double fractionZ = scaledZ - minimumZ;
        double fadeX = fractionX * fractionX * (3D - 2D * fractionX);
        double fadeZ = fractionZ * fractionZ * (3D - 2D * fractionZ);
        double northwest = centered(hash(minimumX, minimumZ, salt));
        double northeast = centered(hash(minimumX + 1L, minimumZ, salt));
        double southwest = centered(hash(minimumX, minimumZ + 1L, salt));
        double southeast = centered(hash(minimumX + 1L, minimumZ + 1L, salt));
        double north = northwest + (northeast - northwest) * fadeX;
        double south = southwest + (southeast - southwest) * fadeX;
        return north + (south - north) * fadeZ;
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
            RiverWorm worm = resolver.worm(node, neighbor);
            if (!branchPermitted(node, neighbor, resolver, worm)) {
                continue;
            }
            RiverRoutingContext context = resolver.routingContext(node, neighbor);
            double routingCost = finiteNonNegative(resolver.terrain.reachRoutingCost(context));
            double oceanAttraction = neighbor.ocean() ? options.oceanAttraction() : 0.0;
            double flowAlignmentCost = flowAlignmentCost(node, neighbor, resolver);
            double confluenceAttraction = unit(hash(neighbor.id(), CONFLUENCE_SALT))
                    * options.confluenceWeight()
                    * worm.confluenceMultiplier();
            ranked.add(new RankedCandidate(
                    neighbor,
                    neighbor.routingScore() + routingCost + flowAlignmentCost
                            - oceanAttraction - confluenceAttraction
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

    private boolean branchPermitted(
            RiverNode child,
            RiverNode parent,
            NodeResolver resolver,
            RiverWorm worm
    ) {
        RiverEdgeId childEdge = RiverEdgeId.of(child.id(), parent.id());
        int childSlot = resolver.branchSlot(parent, child);
        if (childSlot < worm.branchCap()) {
            return true;
        }
        double survivalChance = 1D;
        for (int overflow = worm.branchCap(); overflow <= childSlot; overflow++) {
            survivalChance *= worm.branchDecay();
        }
        return gate(hash(childEdge, BRANCH_GATE_SALT), survivalChance);
    }

    private RiverRoute trace(RiverNodeId sourceId, NodeResolver resolver) {
        if (!resolver.sourcePermitted(sourceId)) {
            return new RiverRoute(sourceId, RiverRouteState.SUPPRESSED, List.of(), false, false);
        }
        RiverNode source = resolver.resolve(sourceId);

        ArrayList<RiverEdgeId> edges = new ArrayList<>(options.maxRouteReaches());
        RiverNode current = source;
        boolean reachedOcean = false;
        boolean exhaustedHorizon = true;
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
                exhaustedHorizon = false;
                break;
            }
            RiverEdgeId edgeId = RiverEdgeId.of(current.id(), next.id());
            if (!resolver.continuationPermitted(resolver.routingContext(current, next))) {
                exhaustedHorizon = false;
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
        if (exhaustedHorizon && !options.requireOcean()) {
            return new RiverRoute(sourceId, RiverRouteState.WET, edges, false, false);
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
                        resolver.worm(from, to),
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
            RiverNode from,
            RiverNode to,
            RiverWorm worm
    ) {
        int pointCount = worm.segments() + 1;
        double[] rawX = new double[pointCount];
        double[] rawZ = new double[pointCount];
        double deltaX = to.x() - from.x();
        double deltaZ = to.z() - from.z();
        double length = StrictMath.hypot(deltaX, deltaZ);
        rawX[0] = from.x();
        rawZ[0] = from.z();
        if (length <= 0D) {
            return new RiverPolyline(rawX, rawZ);
        }
        double baseHeading = StrictMath.atan2(deltaZ, deltaX);
        double stepLength = length / worm.segments();
        for (int point = 1; point < pointCount; point++) {
            double x = rawX[point - 1];
            double z = rawZ[point - 1];
            double primary = perlin(x, z, worm.wavelength(), worm.seed() ^ WORM_PRIMARY_SALT);
            double detail = perlin(x, z, worm.detailWavelength(), worm.seed() ^ WORM_DETAIL_SALT);
            double heading = baseHeading + StrictMath.PI * (
                    primary * worm.tortuosity()
                            + detail * worm.detailTortuosity()
            );
            rawX[point] = x + StrictMath.cos(heading) * stepLength;
            rawZ[point] = z + StrictMath.sin(heading) * stepLength;
        }
        double[] x = new double[pointCount];
        double[] z = new double[pointCount];
        double rawDeltaX = rawX[pointCount - 1] - from.x();
        double rawDeltaZ = rawZ[pointCount - 1] - from.z();
        double maximumDisplacement = 0D;
        for (int point = 0; point < pointCount; point++) {
            double t = (double) point / (pointCount - 1);
            double tSquared = t * t;
            double envelope = 16D * tSquared * (1D - t) * (1D - t);
            double straightX = from.x() + deltaX * t;
            double straightZ = from.z() + deltaZ * t;
            double rawBridgeX = rawX[point] - (from.x() + rawDeltaX * t);
            double rawBridgeZ = rawZ[point] - (from.z() + rawDeltaZ * t);
            x[point] = straightX + rawBridgeX * envelope;
            z[point] = straightZ + rawBridgeZ * envelope;
            maximumDisplacement = StrictMath.max(
                    maximumDisplacement,
                    StrictMath.hypot(x[point] - straightX, z[point] - straightZ)
            );
        }
        double maximumOffset = StrictMath.min(worm.maxOffset(), length * 0.35D);
        if (maximumDisplacement > maximumOffset && maximumDisplacement > 0D) {
            double scale = maximumOffset / maximumDisplacement;
            for (int point = 1; point < pointCount - 1; point++) {
                double t = (double) point / (pointCount - 1);
                double straightX = from.x() + deltaX * t;
                double straightZ = from.z() + deltaZ * t;
                x[point] = straightX + (x[point] - straightX) * scale;
                z[point] = straightZ + (z[point] - straightZ) * scale;
            }
        }
        x[0] = from.x();
        z[0] = from.z();
        x[pointCount - 1] = to.x();
        z[pointCount - 1] = to.z();
        return new RiverPolyline(x, z);
    }

    private RiverWorm rootWormFor(RiverNodeId rootId) {
        return selectWeighted(options.worms(), hash(rootId, WORM_FAMILY_SALT));
    }

    private RiverWorm childWormFor(
            RiverWorm parent,
            RiverNodeId parentId,
            RiverNodeId childId,
            int branchSlot
    ) {
        if (parent.children().isEmpty()) {
            return parent;
        }
        double chance = StrictMath.min(
                1D,
                parent.childChance()
                        + StrictMath.min(7, branchSlot) * parent.branchChildChance()
        );
        RiverEdgeId edgeId = RiverEdgeId.of(childId, parentId);
        if (!gate(hash(edgeId, WORM_CHILD_GATE_SALT), chance)) {
            return parent;
        }
        return selectWeighted(parent.children(), hash(edgeId, WORM_CHILD_SELECTION_SALT));
    }

    private RiverWorm selectWeighted(List<RiverWorm> worms, long selectionHash) {
        double totalWeight = 0D;
        for (RiverWorm worm : worms) {
            totalWeight += worm.weight();
        }
        double selection = unit(selectionHash) * totalWeight;
        double cumulative = 0D;
        for (RiverWorm worm : worms) {
            cumulative += worm.weight();
            if (selection < cumulative) {
                return worm;
            }
        }
        return worms.get(worms.size() - 1);
    }

    private double perlin(double x, double z, double wavelength, long salt) {
        double scaledX = x / wavelength;
        double scaledZ = z / wavelength;
        long minimumX = (long) StrictMath.floor(scaledX);
        long minimumZ = (long) StrictMath.floor(scaledZ);
        double fractionX = scaledX - minimumX;
        double fractionZ = scaledZ - minimumZ;
        double fadeX = perlinFade(fractionX);
        double fadeZ = perlinFade(fractionZ);
        double northwest = perlinGradient(minimumX, minimumZ, fractionX, fractionZ, salt);
        double northeast = perlinGradient(minimumX + 1L, minimumZ, fractionX - 1D, fractionZ, salt);
        double southwest = perlinGradient(minimumX, minimumZ + 1L, fractionX, fractionZ - 1D, salt);
        double southeast = perlinGradient(
                minimumX + 1L,
                minimumZ + 1L,
                fractionX - 1D,
                fractionZ - 1D,
                salt
        );
        double north = northwest + (northeast - northwest) * fadeX;
        double south = southwest + (southeast - southwest) * fadeX;
        return StrictMath.max(
                -1D,
                StrictMath.min(1D, (north + (south - north) * fadeZ) * PERLIN_NORMALIZATION)
        );
    }

    private double perlinGradient(long latticeX, long latticeZ, double x, double z, long salt) {
        return switch ((int) (hash(latticeX, latticeZ, salt) & 7L)) {
            case 0 -> x;
            case 1 -> -x;
            case 2 -> z;
            case 3 -> -z;
            case 4 -> (x + z) / PERLIN_NORMALIZATION;
            case 5 -> (-x + z) / PERLIN_NORMALIZATION;
            case 6 -> (x - z) / PERLIN_NORMALIZATION;
            default -> (-x - z) / PERLIN_NORMALIZATION;
        };
    }

    private double perlinFade(double value) {
        double squared = value * value;
        double cubed = squared * value;
        return cubed * (value * (value * 6D - 15D) + 10D);
    }

    private double bodyMultiplier(
            ReachPosition position,
            RiverWorm worm,
            long primarySalt,
            long detailSalt,
            double variation
    ) {
        if (variation <= 0D) {
            return 1D;
        }
        return StrictMath.max(
                0.125D,
                1D + bodyField(position, worm, primarySalt, detailSalt) * variation
        );
    }

    private double roofScale(ReachPosition position, RiverWorm worm) {
        if (worm.roofVariation() <= 0D) {
            return 1D;
        }
        double normalized = bodyField(
                position,
                worm,
                BODY_ROOF_PRIMARY_SALT,
                BODY_ROOF_DETAIL_SALT
        ) * 0.5D + 0.5D;
        return StrictMath.max(0.125D, 1D - normalized * worm.roofVariation());
    }

    private double bodyField(
            ReachPosition position,
            RiverWorm worm,
            long primarySalt,
            long detailSalt
    ) {
        double primary = perlin(
                position.x(),
                position.z(),
                worm.bodyWavelength(),
                worm.seed() ^ primarySalt
        );
        double detail = perlin(
                position.x(),
                position.z(),
                worm.bodyDetailWavelength(),
                worm.seed() ^ detailSalt
        );
        double detailInfluence = worm.bodyDetailInfluence();
        return primary * (1D - detailInfluence) + detail * detailInfluence;
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
        double maximumWormOffset = StrictMath.min(options.maximumWormOffset(), length * 0.35D);
        double padding = options.maximumReachRadius() + maximumWormOffset;
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
        double maximumWormOffset = StrictMath.min(options.maximumWormOffset(), maximumEdgeLength * 0.35D);
        double displacement = options.maximumReachRadius() + maximumWormOffset;
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
        private final Map<RiverEdgeId, Integer> branchSlots;
        private final Map<RiverNodeId, Double> styleDistances;
        private final Map<RiverNodeId, StyleParent> styleParents;
        private final Map<RiverNodeId, RiverWorm> styleWorms;
        private final Map<RiverEdgeId, Integer> styleBranchSlots;
        private final Map<RiverNodeId, Boolean> resolvedBranchParents;
        private final Map<RiverNodeId, Boolean> resolvedStyleBranchParents;

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
            branchSlots = new HashMap<>();
            styleDistances = new HashMap<>();
            styleParents = new HashMap<>();
            styleWorms = new HashMap<>();
            styleBranchSlots = new HashMap<>();
            resolvedBranchParents = new HashMap<>();
            resolvedStyleBranchParents = new HashMap<>();
        }

        private RiverNode resolve(RiverNodeId id) {
            return nodes.computeIfAbsent(id, key -> createNode(key, terrain));
        }

        private List<RiverNode> downstreamCandidates(RiverNode node) {
            return downstreamCandidates.computeIfAbsent(
                    node.id(),
                    ignored -> computeDownstreamCandidates(node, this));
        }

        private int branchSlot(RiverNode parent, RiverNode child) {
            if (!resolvedBranchParents.containsKey(parent.id())) {
                ArrayList<RiverNode> upstream = new ArrayList<>(8);
                for (RiverNodeId siblingId : neighbors(parent.id())) {
                    RiverNode sibling = resolve(siblingId);
                    if (sibling.riverAllowed() && compareRank(sibling, parent) > 0) {
                        upstream.add(sibling);
                    }
                }
                upstream.sort((first, second) -> {
                    long firstPriority = hash(RiverEdgeId.of(first.id(), parent.id()), BRANCH_SLOT_SALT);
                    long secondPriority = hash(RiverEdgeId.of(second.id(), parent.id()), BRANCH_SLOT_SALT);
                    int priorityComparison = Long.compareUnsigned(firstPriority, secondPriority);
                    return priorityComparison != 0
                            ? priorityComparison
                            : first.id().compareTo(second.id());
                });
                for (int slot = 0; slot < upstream.size(); slot++) {
                    RiverNode sibling = upstream.get(slot);
                    branchSlots.put(RiverEdgeId.of(sibling.id(), parent.id()), slot);
                }
                resolvedBranchParents.put(parent.id(), true);
            }
            return branchSlots.getOrDefault(RiverEdgeId.of(child.id(), parent.id()), Integer.MAX_VALUE);
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
            RiverWorm worm = worm(from, to);
            return routingContexts.computeIfAbsent(edgeId, ignored -> RiverRoutingContext.lazy(
                    edgeId,
                    from,
                    to,
                    () -> createPolyline(from, to, worm)));
        }

        private RiverWorm worm(RiverNode first, RiverNode second) {
            RiverNode child = compareRank(first, second) > 0 ? first : second;
            return styleWorm(child.id());
        }

        private RiverWorm styleWorm(RiverNodeId id) {
            RiverWorm cached = styleWorms.get(id);
            if (cached != null) {
                return cached;
            }
            StyleParent parent = styleParent(id);
            RiverWorm selected;
            if (parent.id() == null) {
                selected = rootWormFor(id);
            } else {
                RiverWorm parentWorm = styleWorm(parent.id());
                selected = childWormFor(
                        parentWorm,
                        parent.id(),
                        id,
                        styleBranchSlot(parent.id(), id)
                );
            }
            styleWorms.put(id, selected);
            return selected;
        }

        private StyleParent styleParent(RiverNodeId id) {
            return styleParents.computeIfAbsent(id, nodeId -> {
                double nodeDistance = styleDistance(nodeId);
                RiverNodeId selected = null;
                double selectedDistance = Double.POSITIVE_INFINITY;
                for (RiverNodeId candidate : neighbors(nodeId)) {
                    double candidateDistance = styleDistance(candidate);
                    if (candidateDistance >= nodeDistance - 0.000000001D) {
                        continue;
                    }
                    if (candidateDistance < selectedDistance
                            || candidateDistance == selectedDistance
                            && (selected == null || candidate.compareTo(selected) < 0)) {
                        selected = candidate;
                        selectedDistance = candidateDistance;
                    }
                }
                return new StyleParent(selected);
            });
        }

        private double styleDistance(RiverNodeId id) {
            return styleDistances.computeIfAbsent(id, RiverNetwork.this::drainageDistance);
        }

        private int styleBranchSlot(RiverNodeId parentId, RiverNodeId childId) {
            if (!resolvedStyleBranchParents.containsKey(parentId)) {
                ArrayList<RiverNodeId> children = new ArrayList<>(8);
                for (RiverNodeId candidate : neighbors(parentId)) {
                    StyleParent candidateParent = styleParent(candidate);
                    if (parentId.equals(candidateParent.id())) {
                        children.add(candidate);
                    }
                }
                children.sort((first, second) -> {
                    long firstPriority = hash(RiverEdgeId.of(first, parentId), BRANCH_SLOT_SALT);
                    long secondPriority = hash(RiverEdgeId.of(second, parentId), BRANCH_SLOT_SALT);
                    int priorityComparison = Long.compareUnsigned(firstPriority, secondPriority);
                    return priorityComparison != 0 ? priorityComparison : first.compareTo(second);
                });
                for (int slot = 0; slot < children.size(); slot++) {
                    RiverNodeId child = children.get(slot);
                    styleBranchSlots.put(RiverEdgeId.of(child, parentId), slot);
                }
                resolvedStyleBranchParents.put(parentId, true);
            }
            return styleBranchSlots.getOrDefault(RiverEdgeId.of(childId, parentId), Integer.MAX_VALUE);
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

    private record DrainageBasinId(long x, long z) {
    }

    private record DrainageBasin(DrainageBasinId id, double distance) {
    }

    private record StyleParent(RiverNodeId id) {
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
        private final RiverWorm worm;
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
                RiverWorm worm,
                RiverTerrainSampler terrain
        ) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.context = context;
            this.worm = worm;
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
            RiverBodyProfile bodyProfile = bodyProfile(order, worm);
            RiverRouteState state = wetFlow > 0 ? RiverRouteState.WET : RiverRouteState.DRY;
            return new RiverReach(
                    id,
                    from,
                    to,
                    state,
                    flow,
                    order,
                    bodyProfile.maximumWidth(),
                    bodyProfile.maximumBankWidth(),
                    bodyProfile.maximumDepth(),
                    bodyProfile,
                    state == RiverRouteState.WET && to.ocean(),
                    state == RiverRouteState.WET
                            ? terminalWetFlow == wetFlow
                            : terminalDryFlow == dryFlow,
                    context.polyline()
            );
        }

        private RiverBodyProfile bodyProfile(int order, RiverWorm worm) {
            RiverPolyline polyline = context.polyline();
            double minimumWavelength = StrictMath.min(worm.bodyWavelength(), worm.bodyDetailWavelength());
            int resolvedSamples = 1 + (int) StrictMath.ceil(polyline.length() * 2D / minimumWavelength);
            int sampleCount = StrictMath.max(
                    MINIMUM_BODY_PROFILE_SAMPLES,
                    StrictMath.min(MAXIMUM_BODY_PROFILE_SAMPLES, resolvedSamples)
            );
            double[] positions = new double[sampleCount];
            double[] widths = new double[sampleCount];
            double[] bankWidths = new double[sampleCount];
            double[] depths = new double[sampleCount];
            double[] roofScales = new double[sampleCount];
            double widthOrderScale = 1D + options.orderWidthFactor() * (order - 1);
            double depthOrderScale = 1D + options.orderDepthFactor() * (order - 1);
            for (int index = 0; index < sampleCount; index++) {
                double alongReach = (double) index / (sampleCount - 1);
                ReachPosition position = positionAt(polyline, alongReach);
                double baseWidth = positiveOrFallback(
                        terrain.channelWidth(
                                context,
                                position.x(),
                                position.z(),
                                options.channelWidth()
                        ),
                        options.channelWidth()
                );
                positions[index] = alongReach;
                widths[index] = StrictMath.min(
                        options.maxChannelWidth(),
                        StrictMath.max(
                                1D,
                                baseWidth
                                        * widthOrderScale
                                        * worm.widthMultiplier()
                                        * bodyMultiplier(
                                                position,
                                                worm,
                                                BODY_WIDTH_PRIMARY_SALT,
                                                BODY_WIDTH_DETAIL_SALT,
                                                worm.widthVariation()
                                        )
                                        + options.channelRadiusBonus() * 2D
                        )
                );
                double baseBankWidth = nonNegativeOrFallback(
                        terrain.bankWidth(context, position.x(), position.z(), options.bankWidth()),
                        options.bankWidth()
                );
                bankWidths[index] = StrictMath.min(
                        options.maxBankWidth(),
                        baseBankWidth
                                * worm.bankMultiplier()
                                * bodyMultiplier(
                                        position,
                                        worm,
                                        BODY_BANK_PRIMARY_SALT,
                                        BODY_BANK_DETAIL_SALT,
                                        worm.bankVariation()
                                )
                );
                double baseDepth = positiveOrFallback(
                        terrain.depth(context, position.x(), position.z(), options.depth()),
                        options.depth()
                );
                depths[index] = StrictMath.min(
                        options.maxDepth(),
                        StrictMath.max(
                                1D,
                                baseDepth
                                        * depthOrderScale
                                        * worm.depthMultiplier()
                                        * bodyMultiplier(
                                                position,
                                                worm,
                                                BODY_DEPTH_PRIMARY_SALT,
                                                BODY_DEPTH_DETAIL_SALT,
                                                worm.depthVariation()
                                        )
                        )
                );
                roofScales[index] = roofScale(position, worm);
            }
            return new RiverBodyProfile(positions, widths, bankWidths, depths, roofScales);
        }
    }

    private static ReachPosition positionAt(RiverPolyline polyline, double alongReach) {
        double targetDistance = Math.max(0D, Math.min(1D, alongReach)) * polyline.length();
        for (int point = 0; point < polyline.size() - 1; point++) {
            double segmentStart = polyline.cumulativeLength(point);
            double segmentEnd = polyline.cumulativeLength(point + 1);
            if (targetDistance > segmentEnd && point < polyline.size() - 2) {
                continue;
            }
            double segmentLength = segmentEnd - segmentStart;
            double interpolation = segmentLength <= 0D
                    ? 0D
                    : (targetDistance - segmentStart) / segmentLength;
            return new ReachPosition(
                    polyline.x(point) + (polyline.x(point + 1) - polyline.x(point)) * interpolation,
                    polyline.z(point) + (polyline.z(point + 1) - polyline.z(point)) * interpolation
            );
        }
        int last = polyline.size() - 1;
        return new ReachPosition(polyline.x(last), polyline.z(last));
    }

    private static double positiveOrFallback(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrFallback(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private record ReachPosition(double x, double z) {
    }
}

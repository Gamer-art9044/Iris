package art.arcane.iris.engine.river;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RiverTile {
    private static final int BUCKET_SIZE = 64;

    private final int tileX;
    private final int tileZ;
    private final int minimumX;
    private final int minimumZ;
    private final int maximumX;
    private final int maximumZ;
    private final List<RiverReach> reaches;
    private final Map<RiverEdgeId, RiverReach> reachesById;
    private final Map<Long, List<RiverReach>> spatialIndex;

    public RiverTile(
            int tileX,
            int tileZ,
            int minimumX,
            int minimumZ,
            int maximumX,
            int maximumZ,
            List<RiverReach> reaches
    ) {
        if (minimumX >= maximumX || minimumZ >= maximumZ) {
            throw new IllegalArgumentException("River tile bounds must have positive area");
        }
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.minimumX = minimumX;
        this.minimumZ = minimumZ;
        this.maximumX = maximumX;
        this.maximumZ = maximumZ;
        this.reaches = List.copyOf(reaches);
        reachesById = indexById(this.reaches);
        spatialIndex = createSpatialIndex(this.reaches);
    }

    public int tileX() {
        return tileX;
    }

    public int tileZ() {
        return tileZ;
    }

    public int minimumX() {
        return minimumX;
    }

    public int minimumZ() {
        return minimumZ;
    }

    public int maximumX() {
        return maximumX;
    }

    public int maximumZ() {
        return maximumZ;
    }

    public List<RiverReach> reaches() {
        return reaches;
    }

    public RiverReach reach(RiverEdgeId id) {
        return reachesById.get(Objects.requireNonNull(id));
    }

    public List<RiverAnchor> candidateAnchors(double spacing, long salt) {
        return candidateAnchors(minimumX, minimumZ, maximumX, maximumZ, spacing, salt);
    }

    public List<RiverAnchor> candidateAnchors(
            double queryMinimumX,
            double queryMinimumZ,
            double queryMaximumX,
            double queryMaximumZ,
            double spacing,
            long salt
    ) {
        if (!Double.isFinite(spacing) || spacing <= 0.0) {
            throw new IllegalArgumentException("River anchor spacing must be finite and positive");
        }
        if (!Double.isFinite(queryMinimumX) || !Double.isFinite(queryMinimumZ)
                || !Double.isFinite(queryMaximumX) || !Double.isFinite(queryMaximumZ)
                || queryMinimumX >= queryMaximumX || queryMinimumZ >= queryMaximumZ) {
            throw new IllegalArgumentException("River anchor query bounds must be finite and have positive area");
        }
        ArrayList<RiverAnchor> anchors = new ArrayList<>();
        for (RiverReach reach : indexedReaches(queryMinimumX, queryMinimumZ, queryMaximumX, queryMaximumZ)) {
            addAnchors(
                    reach,
                    spacing,
                    salt,
                    queryMinimumX,
                    queryMinimumZ,
                    queryMaximumX,
                    queryMaximumZ,
                    anchors
            );
        }
        return List.copyOf(anchors);
    }

    public int sampleCandidateCount(double x, double z) {
        return indexedReaches(x, z).size();
    }

    public RiverSample sample(double x, double z) {
        return sampleExpanded(x, z, 0D);
    }

    public RiverSample sampleExpanded(double x, double z, double additionalRadius) {
        if (!Double.isFinite(additionalRadius) || additionalRadius < 0D) {
            throw new IllegalArgumentException("Additional river sample radius must be finite and non-negative");
        }
        RiverReach nearestReach = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        double nearestAlongReach = 0.0;
        List<RiverReach> candidates = additionalRadius == 0D
                ? indexedReaches(x, z)
                : indexedReaches(
                        x - additionalRadius,
                        z - additionalRadius,
                        x + additionalRadius,
                        z + additionalRadius
                );
        for (RiverReach reach : candidates) {
            ClosestPoint closest = closestCoveringPoint(reach, x, z, additionalRadius);
            if (closest == null) {
                continue;
            }
            if (closest.distanceSquared() < nearestDistanceSquared
                    || (closest.distanceSquared() == nearestDistanceSquared
                    && nearestReach != null
                    && reach.id().compareTo(nearestReach.id()) < 0)) {
                nearestReach = reach;
                nearestDistanceSquared = closest.distanceSquared();
                nearestAlongReach = closest.alongReach();
            }
        }
        if (nearestReach == null) {
            return RiverSample.none();
        }

        return createSample(nearestReach, nearestDistanceSquared, nearestAlongReach);
    }

    public RiverSample sampleFootprint(
            double queryMinimumX,
            double queryMinimumZ,
            double queryMaximumX,
            double queryMaximumZ
    ) {
        if (!Double.isFinite(queryMinimumX) || !Double.isFinite(queryMinimumZ)
                || !Double.isFinite(queryMaximumX) || !Double.isFinite(queryMaximumZ)
                || queryMinimumX > queryMaximumX || queryMinimumZ > queryMaximumZ) {
            throw new IllegalArgumentException("River footprint bounds must be finite and ordered");
        }
        RiverReach nearestReach = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        double nearestAlongReach = 0.0;
        for (RiverReach reach : indexedReachesInclusive(
                queryMinimumX,
                queryMinimumZ,
                queryMaximumX,
                queryMaximumZ
        )) {
            ClosestPoint closest = closestCoveringPoint(
                    reach,
                    queryMinimumX,
                    queryMinimumZ,
                    queryMaximumX,
                    queryMaximumZ
            );
            if (closest == null) {
                continue;
            }
            if (closest.distanceSquared() < nearestDistanceSquared
                    || (closest.distanceSquared() == nearestDistanceSquared
                    && nearestReach != null
                    && reach.id().compareTo(nearestReach.id()) < 0)) {
                nearestReach = reach;
                nearestDistanceSquared = closest.distanceSquared();
                nearestAlongReach = closest.alongReach();
            }
        }
        if (nearestReach == null) {
            return RiverSample.none();
        }

        return createSample(nearestReach, nearestDistanceSquared, nearestAlongReach);
    }

    private static RiverSample createSample(
            RiverReach nearestReach,
            double nearestDistanceSquared,
            double nearestAlongReach
    ) {

        double distance = StrictMath.sqrt(nearestDistanceSquared);
        double localWidth = nearestReach.widthAt(nearestAlongReach);
        double localBankWidth = nearestReach.bankWidthAt(nearestAlongReach);
        double localDepth = nearestReach.depthAt(nearestAlongReach);
        double channelRadius = localWidth * 0.5;
        RiverSection section = section(nearestReach, distance, channelRadius);
        double carveWeight = carveWeight(distance, channelRadius, localBankWidth);
        return new RiverSample(
                true,
                nearestReach.state(),
                section,
                distance,
                nearestAlongReach,
                carveWeight,
                nearestReach.flow(),
                nearestReach.order(),
                localWidth,
                localBankWidth,
                localDepth,
                nearestReach.terminal(),
                nearestReach.id()
        );
    }

    private static RiverSection section(RiverReach reach, double distance, double channelRadius) {
        if (distance <= channelRadius) {
            if (reach.state() == RiverRouteState.DRY) {
                return RiverSection.DRY_CHANNEL;
            }
            return reach.mouth() ? RiverSection.MOUTH : RiverSection.CHANNEL;
        }
        return reach.state() == RiverRouteState.DRY ? RiverSection.DRY_BANK : RiverSection.BANK;
    }

    private void addAnchors(
            RiverReach reach,
            double spacing,
            long salt,
            double queryMinimumX,
            double queryMinimumZ,
            double queryMaximumX,
            double queryMaximumZ,
            List<RiverAnchor> anchors
    ) {
        double length = reach.polyline().length();
        double firstDistance = unit(RiverNetwork.mix(reach.id().stableId() ^ salt)) * spacing;
        int index = 0;
        for (double distance = firstDistance; distance < length; distance += spacing) {
            Position position = positionAt(reach.polyline(), distance);
            if (position.x() >= minimumX && position.x() < maximumX
                    && position.z() >= minimumZ && position.z() < maximumZ
                    && position.x() >= queryMinimumX && position.x() < queryMaximumX
                    && position.z() >= queryMinimumZ && position.z() < queryMaximumZ) {
                long stableId = RiverNetwork.mix(
                        reach.id().stableId() ^ salt ^ (long) index * 0x9E3779B97F4A7C15L
                );
                anchors.add(new RiverAnchor(
                        reach.id(),
                        index,
                        stableId,
                        spacing,
                        salt,
                        position.x(),
                        position.z(),
                        position.alongReach(),
                        reach.state(),
                        reach.flow(),
                        reach.order()
                ));
            }
            index++;
        }
    }

    private static Position positionAt(RiverPolyline polyline, double targetDistance) {
        double traversed = 0.0;
        for (int point = 0; point < polyline.size() - 1; point++) {
            double startX = polyline.x(point);
            double startZ = polyline.z(point);
            double deltaX = polyline.x(point + 1) - startX;
            double deltaZ = polyline.z(point + 1) - startZ;
            double segmentLength = StrictMath.hypot(deltaX, deltaZ);
            if (targetDistance <= traversed + segmentLength || point == polyline.size() - 2) {
                double t = segmentLength == 0.0 ? 0.0 : (targetDistance - traversed) / segmentLength;
                t = StrictMath.max(0.0, StrictMath.min(1.0, t));
                double alongReach = polyline.length() == 0.0 ? 0.0 : targetDistance / polyline.length();
                return new Position(startX + deltaX * t, startZ + deltaZ * t, alongReach);
            }
            traversed += segmentLength;
        }
        return new Position(
                polyline.x(polyline.size() - 1),
                polyline.z(polyline.size() - 1),
                1.0
        );
    }

    private static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static double carveWeight(double distance, double channelRadius, double bankWidth) {
        if (distance <= channelRadius || bankWidth == 0.0) {
            return 1.0;
        }
        double t = StrictMath.min(1.0, (distance - channelRadius) / bankWidth);
        double smooth = t * t * (3.0 - 2.0 * t);
        return 1.0 - smooth;
    }

    private static ClosestPoint closestCoveringPoint(
            RiverReach reach,
            double x,
            double z,
            double additionalRadius
    ) {
        RiverPolyline polyline = reach.polyline();
        RiverBodyProfile bodyProfile = reach.bodyProfile();
        double polylineLength = polyline.length();
        if (polylineLength == 0D) {
            double distanceSquared = squared(x - polyline.x(0)) + squared(z - polyline.z(0));
            double radius = reach.widthAt(0D) * 0.5D + reach.bankWidthAt(0D) + additionalRadius;
            return distanceSquared <= radius * radius ? new ClosestPoint(distanceSquared, 0D) : null;
        }
        double nearest = Double.POSITIVE_INFINITY;
        double nearestAlong = 0.0;
        int pointLimit = polyline.size() - 1;
        int profileLimit = bodyProfile.size() - 1;
        for (int point = 0; point < pointLimit; point++) {
            double segmentStartAlong = polyline.cumulativeLength(point) / polylineLength;
            double segmentEndAlong = polyline.cumulativeLength(point + 1) / polylineLength;
            double segmentAlongSpan = segmentEndAlong - segmentStartAlong;
            if (segmentAlongSpan == 0D) {
                continue;
            }
            double startX = polyline.x(point);
            double startZ = polyline.z(point);
            double deltaX = polyline.x(point + 1) - startX;
            double deltaZ = polyline.z(point + 1) - startZ;
            int firstProfileIndex = bodyProfile.intervalIndex(segmentStartAlong);
            for (int profileIndex = firstProfileIndex;
                 profileIndex < profileLimit
                         && bodyProfile.position(profileIndex) <= segmentEndAlong;
                 profileIndex++) {
                double profileStart = bodyProfile.position(profileIndex);
                double profileEnd = bodyProfile.position(profileIndex + 1);
                double overlapStart = StrictMath.max(segmentStartAlong, profileStart);
                double overlapEnd = StrictMath.min(segmentEndAlong, profileEnd);
                if (overlapStart > overlapEnd) {
                    continue;
                }
                double intervalStart = (overlapStart - segmentStartAlong) / segmentAlongSpan;
                double intervalEnd = (overlapEnd - segmentStartAlong) / segmentAlongSpan;
                double profileSpan = profileEnd - profileStart;
                double profileWidth = bodyProfile.widthAtIndex(profileIndex);
                double profileBankWidth = bodyProfile.bankWidthAtIndex(profileIndex);
                double widthSlope = (bodyProfile.widthAtIndex(profileIndex + 1)
                        - profileWidth) / profileSpan;
                double bankSlope = (bodyProfile.bankWidthAtIndex(profileIndex + 1)
                        - profileBankWidth) / profileSpan;
                double radiusBase = (profileWidth
                        + widthSlope * (segmentStartAlong - profileStart)) * 0.5D
                        + profileBankWidth
                        + bankSlope * (segmentStartAlong - profileStart)
                        + additionalRadius;
                double radiusSlope = (widthSlope * 0.5D + bankSlope) * segmentAlongSpan;
                ClosestPoint candidate = coveringPoint(
                        intervalStart,
                        intervalEnd,
                        deltaX,
                        startX - x,
                        deltaZ,
                        startZ - z,
                        radiusSlope,
                        radiusBase,
                        segmentStartAlong,
                        segmentAlongSpan
                );
                if (candidate != null && candidate.distanceSquared() < nearest) {
                    nearest = candidate.distanceSquared();
                    nearestAlong = candidate.alongReach();
                }
            }
        }
        return Double.isFinite(nearest) ? new ClosestPoint(nearest, nearestAlong) : null;
    }

    private static ClosestPoint closestCoveringPoint(
            RiverReach reach,
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        RiverPolyline polyline = reach.polyline();
        RiverBodyProfile bodyProfile = reach.bodyProfile();
        double polylineLength = polyline.length();
        if (polylineLength == 0D) {
            double distanceSquared = pointRectangleDistanceSquared(
                    polyline.x(0),
                    polyline.z(0),
                    minimumX,
                    minimumZ,
                    maximumX,
                    maximumZ
            );
            double radius = reach.widthAt(0D) * 0.5D + reach.bankWidthAt(0D);
            return distanceSquared <= radius * radius ? new ClosestPoint(distanceSquared, 0D) : null;
        }
        double nearest = Double.POSITIVE_INFINITY;
        double nearestAlong = 0.0;
        int pointLimit = polyline.size() - 1;
        int profileLimit = bodyProfile.size() - 1;
        for (int point = 0; point < pointLimit; point++) {
            double segmentStartAlong = polyline.cumulativeLength(point) / polylineLength;
            double segmentEndAlong = polyline.cumulativeLength(point + 1) / polylineLength;
            double segmentAlongSpan = segmentEndAlong - segmentStartAlong;
            if (segmentAlongSpan == 0D) {
                continue;
            }
            double startX = polyline.x(point);
            double startZ = polyline.z(point);
            double deltaX = polyline.x(point + 1) - startX;
            double deltaZ = polyline.z(point + 1) - startZ;
            int firstProfileIndex = bodyProfile.intervalIndex(segmentStartAlong);
            for (int profileIndex = firstProfileIndex;
                 profileIndex < profileLimit
                         && bodyProfile.position(profileIndex) <= segmentEndAlong;
                 profileIndex++) {
                double profileStart = bodyProfile.position(profileIndex);
                double profileEnd = bodyProfile.position(profileIndex + 1);
                double overlapStart = StrictMath.max(segmentStartAlong, profileStart);
                double overlapEnd = StrictMath.min(segmentEndAlong, profileEnd);
                if (overlapStart > overlapEnd) {
                    continue;
                }
                double intervalStart = (overlapStart - segmentStartAlong) / segmentAlongSpan;
                double intervalEnd = (overlapEnd - segmentStartAlong) / segmentAlongSpan;
                double profileSpan = profileEnd - profileStart;
                double profileWidth = bodyProfile.widthAtIndex(profileIndex);
                double profileBankWidth = bodyProfile.bankWidthAtIndex(profileIndex);
                double widthSlope = (bodyProfile.widthAtIndex(profileIndex + 1)
                        - profileWidth) / profileSpan;
                double bankSlope = (bodyProfile.bankWidthAtIndex(profileIndex + 1)
                        - profileBankWidth) / profileSpan;
                double radiusBase = (profileWidth
                        + widthSlope * (segmentStartAlong - profileStart)) * 0.5D
                        + profileBankWidth
                        + bankSlope * (segmentStartAlong - profileStart);
                double radiusSlope = (widthSlope * 0.5D + bankSlope) * segmentAlongSpan;
                double cursor = intervalStart;
                do {
                    double next = intervalEnd;
                    next = nextCrossing(startX, deltaX, minimumX, cursor, next);
                    next = nextCrossing(startX, deltaX, maximumX, cursor, next);
                    next = nextCrossing(startZ, deltaZ, minimumZ, cursor, next);
                    next = nextCrossing(startZ, deltaZ, maximumZ, cursor, next);
                    double middle = (cursor + next) * 0.5D;
                    double middleX = startX + deltaX * middle;
                    double middleZ = startZ + deltaZ * middle;
                    double distanceSlopeX = middleX < minimumX || middleX > maximumX ? deltaX : 0D;
                    double distanceBaseX = middleX < minimumX
                            ? startX - minimumX
                            : middleX > maximumX ? startX - maximumX : 0D;
                    double distanceSlopeZ = middleZ < minimumZ || middleZ > maximumZ ? deltaZ : 0D;
                    double distanceBaseZ = middleZ < minimumZ
                            ? startZ - minimumZ
                            : middleZ > maximumZ ? startZ - maximumZ : 0D;
                    ClosestPoint candidate = coveringPoint(
                            cursor,
                            next,
                            distanceSlopeX,
                            distanceBaseX,
                            distanceSlopeZ,
                            distanceBaseZ,
                            radiusSlope,
                            radiusBase,
                            segmentStartAlong,
                            segmentAlongSpan
                    );
                    if (candidate != null && candidate.distanceSquared() < nearest) {
                        nearest = candidate.distanceSquared();
                        nearestAlong = candidate.alongReach();
                    }
                    cursor = next;
                } while (cursor < intervalEnd);
            }
        }
        return Double.isFinite(nearest) ? new ClosestPoint(nearest, nearestAlong) : null;
    }

    private static ClosestPoint coveringPoint(
            double intervalStart,
            double intervalEnd,
            double distanceSlopeX,
            double distanceBaseX,
            double distanceSlopeZ,
            double distanceBaseZ,
            double radiusSlope,
            double radiusBase,
            double segmentStartAlong,
            double segmentAlongSpan
    ) {
        double distanceQuadratic = squared(distanceSlopeX) + squared(distanceSlopeZ);
        double distanceLinear = 2D * (distanceSlopeX * distanceBaseX + distanceSlopeZ * distanceBaseZ);
        double distanceConstant = squared(distanceBaseX) + squared(distanceBaseZ);
        double coverageQuadratic = distanceQuadratic - squared(radiusSlope);
        double coverageLinear = distanceLinear - 2D * radiusSlope * radiusBase;
        double coverageConstant = distanceConstant - squared(radiusBase);
        double distancePosition = distanceQuadratic == 0D
                ? intervalStart
                : clamp(-distanceLinear / (2D * distanceQuadratic), intervalStart, intervalEnd);
        if (quadraticValue(coverageQuadratic, coverageLinear, coverageConstant, distancePosition) <= 0D) {
            return new ClosestPoint(
                    StrictMath.max(0D, quadraticValue(
                            distanceQuadratic,
                            distanceLinear,
                            distanceConstant,
                            distancePosition
                    )),
                    segmentStartAlong + segmentAlongSpan * distancePosition
            );
        }
        double coveragePosition = intervalStart;
        double minimumCoverage = quadraticValue(
                coverageQuadratic,
                coverageLinear,
                coverageConstant,
                coveragePosition
        );
        double endCoverage = quadraticValue(coverageQuadratic, coverageLinear, coverageConstant, intervalEnd);
        if (endCoverage < minimumCoverage) {
            minimumCoverage = endCoverage;
            coveragePosition = intervalEnd;
        }
        if (coverageQuadratic > 0D) {
            double vertex = clamp(-coverageLinear / (2D * coverageQuadratic), intervalStart, intervalEnd);
            double vertexCoverage = quadraticValue(coverageQuadratic, coverageLinear, coverageConstant, vertex);
            if (vertexCoverage < minimumCoverage) {
                minimumCoverage = vertexCoverage;
                coveragePosition = vertex;
            }
        }
        if (minimumCoverage > 0D) {
            return null;
        }
        double uncovered = distancePosition;
        double covered = coveragePosition;
        for (int iteration = 0; iteration < 40; iteration++) {
            double middle = (uncovered + covered) * 0.5D;
            if (quadraticValue(coverageQuadratic, coverageLinear, coverageConstant, middle) <= 0D) {
                covered = middle;
            } else {
                uncovered = middle;
            }
        }
        return new ClosestPoint(
                StrictMath.max(0D, quadraticValue(
                        distanceQuadratic,
                        distanceLinear,
                        distanceConstant,
                        covered
                )),
                segmentStartAlong + segmentAlongSpan * covered
        );
    }

    private static double nextCrossing(
            double start,
            double delta,
            double boundary,
            double cursor,
            double currentNext
    ) {
        if (delta == 0D) {
            return currentNext;
        }
        double crossing = (boundary - start) / delta;
        return crossing > cursor && crossing < currentNext ? crossing : currentNext;
    }

    private static double quadraticValue(double quadratic, double linear, double constant, double value) {
        return (quadratic * value + linear) * value + constant;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return StrictMath.max(minimum, StrictMath.min(maximum, value));
    }

    private static double pointRectangleDistanceSquared(
            double x,
            double z,
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        double deltaX = x < minimumX ? minimumX - x : StrictMath.max(0.0, x - maximumX);
        double deltaZ = z < minimumZ ? minimumZ - z : StrictMath.max(0.0, z - maximumZ);
        return squared(deltaX) + squared(deltaZ);
    }

    private static double squared(double value) {
        return value * value;
    }

    private static Map<Long, List<RiverReach>> createSpatialIndex(List<RiverReach> reaches) {
        HashMap<Long, Set<RiverReach>> mutable = new HashMap<>();
        for (RiverReach reach : reaches) {
            double radius = reach.width() * 0.5 + reach.bankWidth();
            RiverPolyline polyline = reach.polyline();
            for (int point = 0; point < polyline.size() - 1; point++) {
                int minimumBucketX = bucket(StrictMath.min(polyline.x(point), polyline.x(point + 1)) - radius);
                int maximumBucketX = bucket(StrictMath.max(polyline.x(point), polyline.x(point + 1)) + radius);
                int minimumBucketZ = bucket(StrictMath.min(polyline.z(point), polyline.z(point + 1)) - radius);
                int maximumBucketZ = bucket(StrictMath.max(polyline.z(point), polyline.z(point + 1)) + radius);
                for (int bucketX = minimumBucketX; bucketX <= maximumBucketX; bucketX++) {
                    for (int bucketZ = minimumBucketZ; bucketZ <= maximumBucketZ; bucketZ++) {
                        mutable.computeIfAbsent(bucketKey(bucketX, bucketZ), ignored -> new LinkedHashSet<>()).add(reach);
                    }
                }
            }
        }
        HashMap<Long, List<RiverReach>> immutable = new HashMap<>(mutable.size());
        for (Map.Entry<Long, Set<RiverReach>> entry : mutable.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    private static Map<RiverEdgeId, RiverReach> indexById(List<RiverReach> reaches) {
        HashMap<RiverEdgeId, RiverReach> indexed = new HashMap<>(reaches.size());
        for (RiverReach reach : reaches) {
            RiverReach previous = indexed.put(reach.id(), reach);
            if (previous != null) {
                throw new IllegalArgumentException("River tile cannot contain duplicate reach IDs");
            }
        }
        return Map.copyOf(indexed);
    }

    private List<RiverReach> indexedReaches(double x, double z) {
        return spatialIndex.getOrDefault(bucketKey(bucket(x), bucket(z)), List.of());
    }

    private List<RiverReach> indexedReaches(
            double queryMinimumX,
            double queryMinimumZ,
            double queryMaximumX,
            double queryMaximumZ
    ) {
        LinkedHashSet<RiverReach> indexed = new LinkedHashSet<>();
        int minimumBucketX = bucket(queryMinimumX);
        int maximumBucketX = bucket(StrictMath.nextDown(queryMaximumX));
        int minimumBucketZ = bucket(queryMinimumZ);
        int maximumBucketZ = bucket(StrictMath.nextDown(queryMaximumZ));
        if (minimumBucketX == maximumBucketX && minimumBucketZ == maximumBucketZ) {
            return spatialIndex.getOrDefault(
                    bucketKey(minimumBucketX, minimumBucketZ),
                    List.of()
            );
        }
        for (int bucketX = minimumBucketX; bucketX <= maximumBucketX; bucketX++) {
            for (int bucketZ = minimumBucketZ; bucketZ <= maximumBucketZ; bucketZ++) {
                indexed.addAll(spatialIndex.getOrDefault(bucketKey(bucketX, bucketZ), List.of()));
            }
        }
        return List.copyOf(indexed);
    }

    private List<RiverReach> indexedReachesInclusive(
            double queryMinimumX,
            double queryMinimumZ,
            double queryMaximumX,
            double queryMaximumZ
    ) {
        int minimumBucketX = bucket(queryMinimumX);
        int maximumBucketX = bucket(queryMaximumX);
        int minimumBucketZ = bucket(queryMinimumZ);
        int maximumBucketZ = bucket(queryMaximumZ);
        if (minimumBucketX == maximumBucketX && minimumBucketZ == maximumBucketZ) {
            return indexedReaches(queryMinimumX, queryMinimumZ);
        }
        LinkedHashSet<RiverReach> indexed = new LinkedHashSet<>();
        for (int bucketX = minimumBucketX; bucketX <= maximumBucketX; bucketX++) {
            for (int bucketZ = minimumBucketZ; bucketZ <= maximumBucketZ; bucketZ++) {
                indexed.addAll(spatialIndex.getOrDefault(bucketKey(bucketX, bucketZ), List.of()));
            }
        }
        return List.copyOf(indexed);
    }

    private static int bucket(double coordinate) {
        return (int) StrictMath.floor(coordinate / BUCKET_SIZE);
    }

    private static long bucketKey(int bucketX, int bucketZ) {
        return ((long) bucketX << 32) ^ (bucketZ & 0xFFFFFFFFL);
    }

    private record Position(double x, double z, double alongReach) {
    }

    private record ClosestPoint(double distanceSquared, double alongReach) {
    }

}

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
        RiverReach nearestReach = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        double nearestAlongReach = 0.0;
        for (RiverReach reach : indexedReaches(x, z)) {
            ClosestPoint closest = closestPoint(reach.polyline(), x, z);
            double outerRadius = reach.width() * 0.5 + reach.bankWidth();
            if (closest.distanceSquared() > outerRadius * outerRadius) {
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
            ClosestPoint closest = closestPoint(
                    reach.polyline(),
                    queryMinimumX,
                    queryMinimumZ,
                    queryMaximumX,
                    queryMaximumZ
            );
            double outerRadius = reach.width() * 0.5 + reach.bankWidth();
            if (closest.distanceSquared() > outerRadius * outerRadius) {
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
        double channelRadius = nearestReach.width() * 0.5;
        RiverSection section = section(nearestReach, distance, channelRadius);
        double carveWeight = carveWeight(distance, channelRadius, nearestReach.bankWidth());
        return new RiverSample(
                true,
                nearestReach.state(),
                section,
                distance,
                nearestAlongReach,
                carveWeight,
                nearestReach.flow(),
                nearestReach.order(),
                nearestReach.width(),
                nearestReach.bankWidth(),
                nearestReach.depth(),
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

    private static ClosestPoint closestPoint(RiverPolyline polyline, double x, double z) {
        double nearest = Double.POSITIVE_INFINITY;
        double nearestAlong = 0.0;
        for (int point = 0; point < polyline.size() - 1; point++) {
            SegmentPoint segmentPoint = segmentPoint(
                    polyline.x(point),
                    polyline.z(point),
                    polyline.x(point + 1),
                    polyline.z(point + 1),
                    x,
                    z
            );
            if (segmentPoint.distanceSquared() < nearest) {
                nearest = segmentPoint.distanceSquared();
                double segmentLength = polyline.cumulativeLength(point + 1) - polyline.cumulativeLength(point);
                double alongLength = polyline.cumulativeLength(point) + segmentLength * segmentPoint.t();
                nearestAlong = polyline.length() == 0.0 ? 0.0 : alongLength / polyline.length();
            }
        }
        return new ClosestPoint(nearest, nearestAlong);
    }

    private static ClosestPoint closestPoint(
            RiverPolyline polyline,
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        double nearest = Double.POSITIVE_INFINITY;
        double nearestAlong = 0.0;
        for (int point = 0; point < polyline.size() - 1; point++) {
            SegmentPoint segmentPoint = segmentRectanglePoint(
                    polyline.x(point),
                    polyline.z(point),
                    polyline.x(point + 1),
                    polyline.z(point + 1),
                    minimumX,
                    minimumZ,
                    maximumX,
                    maximumZ
            );
            if (segmentPoint.distanceSquared() < nearest) {
                nearest = segmentPoint.distanceSquared();
                double segmentLength = polyline.cumulativeLength(point + 1) - polyline.cumulativeLength(point);
                double alongLength = polyline.cumulativeLength(point) + segmentLength * segmentPoint.t();
                nearestAlong = polyline.length() == 0.0 ? 0.0 : alongLength / polyline.length();
            }
        }
        return new ClosestPoint(nearest, nearestAlong);
    }

    private static SegmentPoint segmentPoint(
            double startX,
            double startZ,
            double endX,
            double endZ,
            double x,
            double z
    ) {
        double deltaX = endX - startX;
        double deltaZ = endZ - startZ;
        double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (lengthSquared == 0.0) {
            return new SegmentPoint(squared(x - startX) + squared(z - startZ), 0.0);
        }
        double projection = ((x - startX) * deltaX + (z - startZ) * deltaZ) / lengthSquared;
        double t = StrictMath.max(0.0, StrictMath.min(1.0, projection));
        double nearestX = startX + deltaX * t;
        double nearestZ = startZ + deltaZ * t;
        return new SegmentPoint(squared(x - nearestX) + squared(z - nearestZ), t);
    }

    private static SegmentPoint segmentRectanglePoint(
            double startX,
            double startZ,
            double endX,
            double endZ,
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        double intersectionPosition = segmentRectangleIntersectionPosition(
                startX,
                startZ,
                endX,
                endZ,
                minimumX,
                minimumZ,
                maximumX,
                maximumZ
        );
        if (!Double.isNaN(intersectionPosition)) {
            return new SegmentPoint(0.0, intersectionPosition);
        }

        double nearestDistanceSquared = pointRectangleDistanceSquared(
                startX,
                startZ,
                minimumX,
                minimumZ,
                maximumX,
                maximumZ
        );
        double nearestPosition = 0.0;
        double endDistanceSquared = pointRectangleDistanceSquared(
                endX,
                endZ,
                minimumX,
                minimumZ,
                maximumX,
                maximumZ
        );
        if (endDistanceSquared < nearestDistanceSquared) {
            nearestDistanceSquared = endDistanceSquared;
            nearestPosition = 1.0;
        }

        SegmentPoint corner = segmentPoint(startX, startZ, endX, endZ, minimumX, minimumZ);
        if (corner.distanceSquared() < nearestDistanceSquared) {
            nearestDistanceSquared = corner.distanceSquared();
            nearestPosition = corner.t();
        }
        corner = segmentPoint(startX, startZ, endX, endZ, minimumX, maximumZ);
        if (corner.distanceSquared() < nearestDistanceSquared) {
            nearestDistanceSquared = corner.distanceSquared();
            nearestPosition = corner.t();
        }
        corner = segmentPoint(startX, startZ, endX, endZ, maximumX, minimumZ);
        if (corner.distanceSquared() < nearestDistanceSquared) {
            nearestDistanceSquared = corner.distanceSquared();
            nearestPosition = corner.t();
        }
        corner = segmentPoint(startX, startZ, endX, endZ, maximumX, maximumZ);
        if (corner.distanceSquared() < nearestDistanceSquared) {
            nearestDistanceSquared = corner.distanceSquared();
            nearestPosition = corner.t();
        }
        return new SegmentPoint(nearestDistanceSquared, nearestPosition);
    }

    private static double segmentRectangleIntersectionPosition(
            double startX,
            double startZ,
            double endX,
            double endZ,
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        double minimumPosition = 0.0;
        double maximumPosition = 1.0;
        double deltaX = endX - startX;
        if (deltaX == 0.0) {
            if (startX < minimumX || startX > maximumX) {
                return Double.NaN;
            }
        } else {
            double first = (minimumX - startX) / deltaX;
            double second = (maximumX - startX) / deltaX;
            minimumPosition = StrictMath.max(minimumPosition, StrictMath.min(first, second));
            maximumPosition = StrictMath.min(maximumPosition, StrictMath.max(first, second));
            if (minimumPosition > maximumPosition) {
                return Double.NaN;
            }
        }

        double deltaZ = endZ - startZ;
        if (deltaZ == 0.0) {
            if (startZ < minimumZ || startZ > maximumZ) {
                return Double.NaN;
            }
        } else {
            double first = (minimumZ - startZ) / deltaZ;
            double second = (maximumZ - startZ) / deltaZ;
            minimumPosition = StrictMath.max(minimumPosition, StrictMath.min(first, second));
            maximumPosition = StrictMath.min(maximumPosition, StrictMath.max(first, second));
            if (minimumPosition > maximumPosition) {
                return Double.NaN;
            }
        }
        return minimumPosition;
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
        List<RiverReach> indexed = spatialIndex.get(bucketKey(bucket(x), bucket(z)));
        return indexed == null ? List.of() : indexed;
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
        for (int bucketX = minimumBucketX; bucketX <= maximumBucketX; bucketX++) {
            for (int bucketZ = minimumBucketZ; bucketZ <= maximumBucketZ; bucketZ++) {
                List<RiverReach> bucketReaches = spatialIndex.get(bucketKey(bucketX, bucketZ));
                if (bucketReaches != null) {
                    indexed.addAll(bucketReaches);
                }
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
        if (spatialIndex.isEmpty()) {
            return List.of();
        }
        if (minimumBucketX == maximumBucketX && minimumBucketZ == maximumBucketZ) {
            List<RiverReach> bucketReaches = spatialIndex.get(bucketKey(minimumBucketX, minimumBucketZ));
            return bucketReaches == null ? List.of() : bucketReaches;
        }
        long bucketWidth = (long) maximumBucketX - minimumBucketX + 1L;
        long bucketDepth = (long) maximumBucketZ - minimumBucketZ + 1L;
        if (bucketWidth > spatialIndex.size() / bucketDepth
                || bucketWidth * bucketDepth >= spatialIndex.size()) {
            return reaches;
        }
        LinkedHashSet<RiverReach> indexed = new LinkedHashSet<>();
        for (int bucketX = minimumBucketX; bucketX <= maximumBucketX; bucketX++) {
            for (int bucketZ = minimumBucketZ; bucketZ <= maximumBucketZ; bucketZ++) {
                List<RiverReach> bucketReaches = spatialIndex.get(bucketKey(bucketX, bucketZ));
                if (bucketReaches != null) {
                    indexed.addAll(bucketReaches);
                }
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

    private record SegmentPoint(double distanceSquared, double t) {
    }
}

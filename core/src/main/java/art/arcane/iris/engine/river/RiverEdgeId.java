package art.arcane.iris.engine.river;

import java.util.Objects;

public record RiverEdgeId(RiverNodeId first, RiverNodeId second) implements Comparable<RiverEdgeId> {
    public RiverEdgeId {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (first.compareTo(second) >= 0) {
            throw new IllegalArgumentException("River edge endpoints must be distinct and canonical");
        }
    }

    public static RiverEdgeId of(RiverNodeId first, RiverNodeId second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (first.equals(second)) {
            throw new IllegalArgumentException("River edge endpoints must be distinct");
        }
        return first.compareTo(second) < 0
                ? new RiverEdgeId(first, second)
                : new RiverEdgeId(second, first);
    }

    public long stableId() {
        return RiverNetwork.mix(first.stableId() ^ Long.rotateLeft(second.stableId(), 29));
    }

    @Override
    public int compareTo(RiverEdgeId other) {
        int firstComparison = first.compareTo(other.first);
        return firstComparison != 0 ? firstComparison : second.compareTo(other.second);
    }
}

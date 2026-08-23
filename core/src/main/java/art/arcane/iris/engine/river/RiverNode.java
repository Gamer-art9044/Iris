package art.arcane.iris.engine.river;

import java.util.Objects;

public record RiverNode(
        RiverNodeId id,
        double x,
        double z,
        double naturalHeight,
        double hydraulicHeight,
        double rank,
        double routingScore,
        boolean ocean,
        boolean riverAllowed
) {
    public RiverNode {
        Objects.requireNonNull(id);
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(naturalHeight)
                || !Double.isFinite(hydraulicHeight)
                || !Double.isFinite(rank) || !Double.isFinite(routingScore)) {
            throw new IllegalArgumentException("River node coordinates, height, and rank must be finite");
        }
    }
}

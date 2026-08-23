package art.arcane.iris.engine.river;

import java.util.Objects;

public record RiverAnchor(
        RiverEdgeId reachId,
        int index,
        long stableId,
        double samplingSpacing,
        long samplingSalt,
        double x,
        double z,
        double alongReach,
        RiverRouteState state,
        int flow,
        int order
) {
    public RiverAnchor {
        Objects.requireNonNull(reachId);
        Objects.requireNonNull(state);
        if (index < 0 || !Double.isFinite(samplingSpacing) || samplingSpacing <= 0D
                || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(alongReach) || alongReach < 0.0 || alongReach > 1.0) {
            throw new IllegalArgumentException("River anchor index and coordinates must be valid");
        }
    }
}

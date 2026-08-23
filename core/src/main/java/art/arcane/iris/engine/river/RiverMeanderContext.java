package art.arcane.iris.engine.river;

import java.util.Objects;

public record RiverMeanderContext(
        RiverEdgeId reachId,
        double normalizedPosition,
        double x,
        double z
) {
    public RiverMeanderContext {
        Objects.requireNonNull(reachId);
        if (!Double.isFinite(normalizedPosition) || normalizedPosition < 0.0 || normalizedPosition > 1.0
                || !Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("River meander context must be finite and normalized");
        }
    }
}

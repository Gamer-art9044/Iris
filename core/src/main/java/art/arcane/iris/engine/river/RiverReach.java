package art.arcane.iris.engine.river;

import java.util.Objects;

public record RiverReach(
        RiverEdgeId id,
        RiverNode from,
        RiverNode to,
        RiverRouteState state,
        int flow,
        int order,
        double width,
        double bankWidth,
        double depth,
        boolean mouth,
        boolean terminal,
        RiverPolyline polyline
) {
    public RiverReach {
        Objects.requireNonNull(id);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(state);
        Objects.requireNonNull(polyline);
        if (state == RiverRouteState.SUPPRESSED) {
            throw new IllegalArgumentException("Suppressed routes cannot produce reaches");
        }
        if (flow < 1 || order < 1) {
            throw new IllegalArgumentException("River reach flow and order must be positive");
        }
        if (!Double.isFinite(width) || width <= 0.0 || !Double.isFinite(bankWidth) || bankWidth < 0.0
                || !Double.isFinite(depth) || depth <= 0.0) {
            throw new IllegalArgumentException("River reach dimensions must be finite and valid");
        }
    }
}

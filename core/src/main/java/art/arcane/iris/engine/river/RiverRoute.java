package art.arcane.iris.engine.river;

import java.util.List;
import java.util.Objects;

public record RiverRoute(
        RiverNodeId source,
        RiverRouteState state,
        List<RiverEdgeId> edges,
        boolean oceanConnected,
        boolean terminal
) {
    public RiverRoute {
        Objects.requireNonNull(source);
        Objects.requireNonNull(state);
        edges = List.copyOf(edges);
    }
}

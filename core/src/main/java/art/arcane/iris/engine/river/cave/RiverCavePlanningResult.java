package art.arcane.iris.engine.river.cave;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RiverCavePlanningResult(
        List<RiverCavePlan> plans,
        Map<CavePosition, RiverCaveAction> actions,
        Map<CavePosition, CaveVoxelPrecondition> baselinePreconditions
) {
    public RiverCavePlanningResult {
        plans = List.copyOf(Objects.requireNonNull(plans));
        actions = Map.copyOf(Objects.requireNonNull(actions));
        baselinePreconditions = Map.copyOf(Objects.requireNonNull(baselinePreconditions));
    }
}

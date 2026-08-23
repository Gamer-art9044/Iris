package art.arcane.iris.engine.river.cave;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

public record RiverCavePlan(
        RiverCaveSource source,
        RiverCaveRejection rejection,
        Map<CavePosition, RiverCaveAction> actions,
        Map<CavePosition, CaveVoxelPrecondition> baselinePreconditions,
        OptionalLong arbitrationWinnerSourceId
) {
    public RiverCavePlan {
        Objects.requireNonNull(source);
        Objects.requireNonNull(rejection);
        actions = Map.copyOf(Objects.requireNonNull(actions));
        baselinePreconditions = Map.copyOf(Objects.requireNonNull(baselinePreconditions));
        Objects.requireNonNull(arbitrationWinnerSourceId);
        if (rejection != RiverCaveRejection.NONE && (!actions.isEmpty() || !baselinePreconditions.isEmpty())) {
            throw new IllegalArgumentException("Rejected cave plans cannot contain mutations or preconditions");
        }
        if (rejection != RiverCaveRejection.OVERLAPPING_SOURCE && arbitrationWinnerSourceId.isPresent()) {
            throw new IllegalArgumentException("Only overlap rejections can name an arbitration winner");
        }
    }

    public boolean accepted() {
        return rejection == RiverCaveRejection.NONE;
    }
}

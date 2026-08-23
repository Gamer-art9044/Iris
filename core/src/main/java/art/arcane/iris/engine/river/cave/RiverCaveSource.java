package art.arcane.iris.engine.river.cave;

import java.util.Objects;

public record RiverCaveSource(
        long sourceId,
        CavePosition entry,
        CavePosition target,
        int waterHeadY,
        RiverCaveMode mode
) {
    public RiverCaveSource {
        Objects.requireNonNull(entry);
        Objects.requireNonNull(target);
        Objects.requireNonNull(mode);
    }
}

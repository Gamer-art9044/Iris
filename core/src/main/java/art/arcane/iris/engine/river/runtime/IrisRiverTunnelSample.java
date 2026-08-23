package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.engine.river.RiverSample;

import java.util.Objects;

public record IrisRiverTunnelSample(
        RiverSample river,
        int bedY,
        int waterHeadY,
        int ceilingY
) {
    public IrisRiverTunnelSample {
        Objects.requireNonNull(river);
        if (!river.present()) {
            throw new IllegalArgumentException("A river tunnel sample requires a present river");
        }
        if (bedY >= waterHeadY || ceilingY < waterHeadY) {
            throw new IllegalArgumentException("River tunnel vertical bounds are invalid");
        }
    }
}

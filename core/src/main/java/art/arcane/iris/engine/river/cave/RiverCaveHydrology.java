package art.arcane.iris.engine.river.cave;

import art.arcane.volmlib.util.matter.MatterCavern;

import java.util.Objects;
import java.util.Optional;

public final class RiverCaveHydrology {
    private static final byte LIQUID_FLUID = 1;
    private static final byte LIQUID_FORCED_AIR = 3;

    private final RiverCaveAction action;
    private final String floodedBiomeKey;
    private final RiverCaveFluidKind fluidKind;
    private final MatterCavern cavern;

    public RiverCaveHydrology(
            RiverCaveAction action,
            String floodedBiomeKey,
            RiverCaveFluidKind fluidKind
    ) {
        this.action = Objects.requireNonNull(action);
        this.floodedBiomeKey = floodedBiomeKey == null ? "" : floodedBiomeKey.trim();
        this.fluidKind = Objects.requireNonNull(fluidKind);
        this.cavern = switch (action) {
            case WET_SOURCE, FALLING_FLUID -> new MatterCavern(true, this.floodedBiomeKey, LIQUID_FLUID);
            case DRY_AIR -> new MatterCavern(true, this.floodedBiomeKey, LIQUID_FORCED_AIR);
            case SEAL_GUARD -> null;
        };
    }

    public static RiverCaveHydrology of(RiverCaveAction action) {
        return new RiverCaveHydrology(action, "", RiverCaveFluidKind.RIVER);
    }

    public static RiverCaveHydrology of(RiverCaveAction action, RiverCaveFluidKind fluidKind) {
        return new RiverCaveHydrology(action, "", fluidKind);
    }

    public Optional<String> floodedBiome() {
        return floodedBiomeKey.isEmpty() ? Optional.empty() : Optional.of(floodedBiomeKey);
    }

    public boolean carves() {
        return action != RiverCaveAction.SEAL_GUARD;
    }

    public boolean isWet() {
        return action == RiverCaveAction.WET_SOURCE || action == RiverCaveAction.FALLING_FLUID;
    }

    public boolean isFalling() {
        return action == RiverCaveAction.FALLING_FLUID;
    }

    public boolean protectsPlacement() {
        return action == RiverCaveAction.SEAL_GUARD || isWet();
    }

    public MatterCavern asCavern() {
        return cavern;
    }

    public RiverCaveAction action() {
        return action;
    }

    public String floodedBiomeKey() {
        return floodedBiomeKey;
    }

    public RiverCaveFluidKind fluidKind() {
        return fluidKind;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof RiverCaveHydrology hydrology)) {
            return false;
        }
        return action == hydrology.action
                && floodedBiomeKey.equals(hydrology.floodedBiomeKey)
                && fluidKind == hydrology.fluidKind;
    }

    @Override
    public int hashCode() {
        return (31 * ((31 * action.hashCode()) + floodedBiomeKey.hashCode())) + fluidKind.hashCode();
    }

    @Override
    public String toString() {
        return "RiverCaveHydrology[action=" + action + ", floodedBiomeKey=" + floodedBiomeKey
                + ", fluidKind=" + fluidKind + "]";
    }
}

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Selects the fallback when a requested river cave connection cannot be proven safe.")
public enum IrisRiverCaveFallback {
    @Desc("Keep the rejected connection sealed.")
    SEALED,

    @Desc("Try a bounded generated grotto instead of the rejected existing cave.")
    GENERATE_GROTTO
}

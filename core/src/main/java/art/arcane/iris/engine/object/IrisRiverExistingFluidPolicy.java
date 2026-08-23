package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Controls how river cave hydrology treats fluid already present in a candidate cave body.")
public enum IrisRiverExistingFluidPolicy {
    @Desc("Reject a candidate containing any existing fluid.")
    REJECT,

    @Desc("Accept only fluid compatible with the river fluid palette.")
    ALLOW_SAME,

    @Desc("Replace contained existing fluid with the river fluid palette.")
    REPLACE
}

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Selects how a river determines its surface fluid height.")
public enum IrisRiverWaterMode {
    @Desc("Use the river water configuration's fixed fluid height for every wet reach.")
    FIXED,

    @Desc("Use flat pools connected by controlled vertical drops.")
    TERRACED
}

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Selects how a river determines its surface fluid height.")
public enum IrisRiverWaterMode {
    @Desc("Use the dimension fluid height for every wet river reach.")
    SEA_LEVEL,

    @Desc("Use flat pools connected by controlled vertical drops.")
    TERRACED
}

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Controls waterlogging behavior for a configured native jigsaw.")
public enum IrisJigsawLiquidSettings {
    SOURCE,
    IGNORE_WATERLOGGING,
    APPLY_WATERLOGGING
}

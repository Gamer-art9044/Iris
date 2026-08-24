package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("How a color absent from a color-map legend is handled")
public enum IrisImageMapUnknownColor {
    ERROR,
    FALLBACK,
    IGNORE
}

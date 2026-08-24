package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("How coordinates outside an image are resolved")
public enum IrisImageMapOutOfBounds {
    FALLBACK,
    CLAMP,
    REPEAT,
    MIRROR,
    ERROR
}

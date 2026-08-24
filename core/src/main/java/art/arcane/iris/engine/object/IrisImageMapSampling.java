package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("The filter used between source pixels")
public enum IrisImageMapSampling {
    NEAREST,
    BILINEAR,
    BICUBIC
}

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("The generation input controlled by an image map")
public enum IrisImageMapApplication {
    TERRAIN_HEIGHT,
    BIOME,
    REGION,
    SURFACE_BLOCK,
    MASK,
    CUSTOM
}

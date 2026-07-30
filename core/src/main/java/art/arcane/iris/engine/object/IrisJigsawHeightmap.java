package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Controls whether a configured native jigsaw projects its start onto a Minecraft heightmap.")
public enum IrisJigsawHeightmap {
    SOURCE,
    NONE,
    WORLD_SURFACE_WG,
    WORLD_SURFACE,
    OCEAN_FLOOR_WG,
    OCEAN_FLOOR,
    MOTION_BLOCKING,
    MOTION_BLOCKING_NO_LEAVES
}

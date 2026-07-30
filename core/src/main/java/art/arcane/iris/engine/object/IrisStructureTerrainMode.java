package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Controls how terrain is integrated with a structure.")
public enum IrisStructureTerrainMode {
    SOURCE,
    PRESERVE,
    BORE,
    FORCE_CARVE,
    SURFACE_FIT,
    REQUIRE_SUPPORT,
    VACUUM,

    @Desc("Fills the padded piece volume with solid blocks before any piece is placed so shells, walls, and floors land in solid ground instead of pre-carved air. Only air and liquid cells are filled; existing terrain and structures are never overwritten. Native pieces then carve their own interiors.")
    ENCASE
}

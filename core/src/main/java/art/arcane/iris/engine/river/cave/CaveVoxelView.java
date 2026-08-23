package art.arcane.iris.engine.river.cave;

public interface CaveVoxelView {
    boolean isInWorld(CavePosition position);

    CaveVoxel voxelAt(CavePosition position);

    boolean isOpenToSurface(CavePosition position);
}

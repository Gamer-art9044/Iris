package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.river.cave.CavePosition;
import art.arcane.iris.engine.river.cave.CaveVoxel;
import art.arcane.iris.engine.river.cave.CaveVoxelView;
import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.object.IrisProceduralBlocks;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.function.Function2;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Objects;

final class MantleRiverCaveVoxelView implements MantleRiverHydrologyComponent.TunnelVoxelView {
    private static final int CLOSED_COLUMN = Integer.MAX_VALUE;
    private static final int CACHE_MISS = Integer.MIN_VALUE;

    private final Mantle<Matter> mantle;
    private final int worldHeight;
    private final Function2<Integer, Integer, Integer> surfaceHeight;
    private final Function2<Integer, Integer, PlatformBlockState> compatibleFluid;
    private final Long2IntOpenHashMap openFloorCache;
    private final Long2IntOpenHashMap surfaceHeightCache;

    MantleRiverCaveVoxelView(
            Mantle<Matter> mantle,
            int worldHeight,
            Function2<Integer, Integer, Integer> surfaceHeight,
            Function2<Integer, Integer, PlatformBlockState> compatibleFluid
    ) {
        this.mantle = Objects.requireNonNull(mantle);
        this.worldHeight = worldHeight;
        this.surfaceHeight = Objects.requireNonNull(surfaceHeight);
        this.compatibleFluid = Objects.requireNonNull(compatibleFluid);
        openFloorCache = new Long2IntOpenHashMap();
        openFloorCache.defaultReturnValue(CACHE_MISS);
        surfaceHeightCache = new Long2IntOpenHashMap();
        surfaceHeightCache.defaultReturnValue(CACHE_MISS);
    }

    @Override
    public boolean isInWorld(CavePosition position) {
        return position.y() > 0 && position.y() < worldHeight - 1;
    }

    @Override
    public CaveVoxel voxelAt(CavePosition position) {
        RiverCaveHydrology hydrology = dataIfPresent(position, RiverCaveHydrology.class);
        if (hydrology != null && hydrology.carves()) {
            return hydrology.isWet() ? CaveVoxel.COMPATIBLE_FLUID : CaveVoxel.CAVE_AIR;
        }
        MatterCavern cavern = dataIfPresent(position, MatterCavern.class);
        if (cavern != null) {
            if (cavern.isLava()) {
                return CaveVoxel.LAVA;
            }
            if (cavern.getLiquid() == 1) {
                return CaveVoxel.COMPATIBLE_FLUID;
            }
            return CaveVoxel.CAVE_AIR;
        }
        PlatformBlockState block = dataIfPresent(position, PlatformBlockState.class);
        if (block == null) {
            return position.y() > surfaceY(position.x(), position.z())
                    ? CaveVoxel.CAVE_AIR
                    : CaveVoxel.SOLID;
        }
        if (!block.isFluid()) {
            return CaveVoxel.SOLID;
        }
        if (IrisProceduralBlocks.materialKey(block).endsWith(":lava")) {
            return CaveVoxel.LAVA;
        }
        PlatformBlockState expected = compatibleFluid.apply(position.x(), position.z());
        return expected != null
                && IrisProceduralBlocks.materialKey(expected).equals(IrisProceduralBlocks.materialKey(block))
                ? CaveVoxel.COMPATIBLE_FLUID
                : CaveVoxel.INCOMPATIBLE_FLUID;
    }

    @Override
    public boolean isOpenToSurface(CavePosition position) {
        if (!isInWorld(position) || voxelAt(position) == CaveVoxel.SOLID) {
            return false;
        }
        if (position.y() > surfaceY(position.x(), position.z())) {
            return true;
        }
        long key = Cache.key(position.x(), position.z());
        int openFloor = openFloorCache.get(key);
        if (openFloor == CACHE_MISS) {
            openFloor = resolveOpenFloor(position.x(), position.z());
            openFloorCache.put(key, openFloor);
        }
        return openFloor != CLOSED_COLUMN && position.y() >= openFloor;
    }

    @Override
    public RiverCaveAction riverActionAt(CavePosition position) {
        RiverCaveHydrology hydrology = dataIfPresent(position, RiverCaveHydrology.class);
        return hydrology == null ? null : hydrology.action();
    }

    private int resolveOpenFloor(int x, int z) {
        int top = surfaceY(x, z);
        CavePosition surface = new CavePosition(x, top, z);
        if (voxelAt(surface) == CaveVoxel.SOLID) {
            return CLOSED_COLUMN;
        }
        int y = top;
        while (y > 0 && voxelAt(new CavePosition(x, y - 1, z)) != CaveVoxel.SOLID) {
            y--;
        }
        return y;
    }

    private int surfaceY(int x, int z) {
        long key = Cache.key(x, z);
        int cached = surfaceHeightCache.get(key);
        if (cached != CACHE_MISS) {
            return cached;
        }
        int resolved = Math.max(1, Math.min(worldHeight - 2, surfaceHeight.apply(x, z)));
        surfaceHeightCache.put(key, resolved);
        return resolved;
    }

    private <T> T dataIfPresent(CavePosition position, Class<T> type) {
        int chunkX = position.x() >> 4;
        int chunkZ = position.z() >> 4;
        TectonicPlate<Matter> plate = mantle.getLoadedRegions().get(Mantle.key(chunkX >> 5, chunkZ >> 5));
        if (plate == null || plate.isClosed()) {
            return null;
        }
        MantleChunk<Matter> chunk = plate.get(chunkX & 31, chunkZ & 31);
        int section = position.y() >> 4;
        if (chunk == null || !chunk.exists(section)) {
            return null;
        }
        Matter matter = chunk.get(section);
        if (matter == null || !matter.hasSlice(type)) {
            return null;
        }
        return matter.<T>getSlice(type).get(
                position.x() & 15,
                position.y() & 15,
                position.z() & 15
        );
    }
}

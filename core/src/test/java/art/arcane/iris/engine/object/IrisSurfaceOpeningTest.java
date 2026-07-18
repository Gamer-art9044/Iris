package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.math.IrisBlockVector;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisSurfaceOpeningTest {
    @Test
    public void carvedSurfaceCellRejectsPlacement() {
        SurfacePlacer placer = new SurfacePlacer(80);
        placer.carve(1, 80, 1);

        assertTrue(IrisSurfaceOpening.isOpen(placer, null, 0, 0, null, null, 0, 0, 0,
                List.of(new IrisBlockVector(0, 0, 0))));
        assertEquals(9, placer.heightQueries());
    }

    @Test
    public void sealedShallowCaveDoesNotRejectPlacement() {
        SurfacePlacer placer = new SurfacePlacer(80);
        placer.carve(0, 79, 0);
        placer.carve(0, 78, 0);
        placer.carve(0, 77, 0);

        assertFalse(IrisSurfaceOpening.isOpen(placer, null, 0, 0, null, null, 0, 0, 0,
                List.of(new IrisBlockVector(0, 0, 0))));
    }

    @Test
    public void everySupportColumnUsesItsOwnSurfaceHeight() {
        SurfacePlacer placer = new SurfacePlacer(80);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                placer.height(dx, dz, 80 + dx + dz);
            }
        }
        placer.carve(1, 82, 1);

        assertTrue(IrisSurfaceOpening.isOpen(placer, null, 0, 0, null, null, 0, 0, 0,
                List.of(new IrisBlockVector(0, 0, 0))));
    }

    @Test
    public void openingOutsideSupportStencilDoesNotRejectPlacement() {
        SurfacePlacer placer = new SurfacePlacer(80);
        placer.carve(2, 80, 0);

        assertFalse(IrisSurfaceOpening.isOpen(placer, null, 0, 0, null, null, 0, 0, 0,
                List.of(new IrisBlockVector(0, 0, 0))));
    }

    @Test
    public void offsetSupportFootprintMovesSupportStencil() {
        SurfacePlacer placer = new SurfacePlacer(80);
        placer.carve(5, 80, -3);

        assertTrue(IrisSurfaceOpening.isOpen(placer, null, 0, 0, null, null, 0, 0, 0,
                List.of(new IrisBlockVector(5, 0, -3))));
    }

    @Test
    public void placementTranslationAndRotationMatchObjectBlocks() {
        SurfacePlacer placer = new SurfacePlacer(80);
        IrisObjectTranslate translate = new IrisObjectTranslate().setX(5).setZ(-3);
        IrisObjectRotation rotation = IrisObjectRotation.of(0, 90, 0);
        placer.carve(-3, 80, -7);

        assertTrue(IrisSurfaceOpening.isOpen(placer, null, 0, 0, translate, rotation, 0, 0, 0,
                List.of(new IrisBlockVector(2, 0, 0))));
    }

    @Test
    public void emptyFootprintFallsBackToPlacementOrigin() {
        SurfacePlacer placer = new SurfacePlacer(80);
        IrisObjectTranslate translate = new IrisObjectTranslate().setX(4).setZ(-2);
        placer.carve(4, 80, -2);

        assertTrue(IrisSurfaceOpening.isOpen(placer, null, 0, 0, translate, null, 0, 0, 0, List.of()));
    }

    @Test
    public void objectCachesItsLowestNonFoliageSupportBlocks() {
        PlatformBlockState solid = mock(PlatformBlockState.class);
        PlatformBlockState foliage = mock(PlatformBlockState.class);
        when(solid.isSolid()).thenReturn(true);
        when(foliage.isSolid()).thenReturn(true);
        when(foliage.isFoliage()).thenReturn(true);

        IrisObject object = new IrisObject(9, 9, 9);
        object.setUnsigned(7, 1, 4, solid);
        object.setUnsigned(4, 0, 4, foliage);
        object.setUnsigned(4, 5, 4, solid);

        List<IrisBlockVector> initial = object.getSurfaceSupportOffsets();
        assertEquals(1, initial.size());
        assertEquals(3, initial.getFirst().getBlockX());
        assertEquals(-3, initial.getFirst().getBlockY());

        object.setUnsigned(2, 0, 4, solid);

        List<IrisBlockVector> updated = object.getSurfaceSupportOffsets();
        assertEquals(1, updated.size());
        assertEquals(-2, updated.getFirst().getBlockX());
        assertEquals(-4, updated.getFirst().getBlockY());
    }

    private static final class SurfacePlacer implements IObjectPlacer {
        private final int defaultHeight;
        private final Map<String, Integer> heights = new HashMap<>();
        private final Set<String> carved = new HashSet<>();
        private int heightQueries;

        private SurfacePlacer(int defaultHeight) {
            this.defaultHeight = defaultHeight;
        }

        private void height(int x, int z, int y) {
            heights.put(columnKey(x, z), y);
        }

        private void carve(int x, int y, int z) {
            carved.add(blockKey(x, y, z));
        }

        private int heightQueries() {
            return heightQueries;
        }

        @Override
        public int getHighest(int x, int z, IrisData data) {
            return getHighest(x, z, data, true);
        }

        @Override
        public int getHighest(int x, int z, IrisData data, boolean ignoreFluid) {
            heightQueries++;
            return heights.getOrDefault(columnKey(x, z), defaultHeight);
        }

        @Override
        public void set(int x, int y, int z, PlatformBlockState state) {
        }

        @Override
        public PlatformBlockState get(int x, int y, int z) {
            return null;
        }

        @Override
        public boolean isPreventingDecay() {
            return false;
        }

        @Override
        public boolean isCarved(int x, int y, int z) {
            return carved.contains(blockKey(x, y, z));
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isUnderwater(int x, int z) {
            return false;
        }

        @Override
        public int getFluidHeight() {
            return 0;
        }

        @Override
        public boolean isDebugSmartBore() {
            return false;
        }

        @Override
        public void setTile(int x, int y, int z, TileData tile) {
        }

        @Override
        public <T> void setData(int x, int y, int z, T data) {
        }

        @Override
        public <T> T getData(int x, int y, int z, Class<T> type) {
            return null;
        }

        @Override
        public Engine getEngine() {
            return null;
        }

        private static String columnKey(int x, int z) {
            return x + ":" + z;
        }

        private static String blockKey(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}

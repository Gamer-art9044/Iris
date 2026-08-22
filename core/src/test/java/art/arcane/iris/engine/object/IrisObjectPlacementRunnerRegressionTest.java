package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.PlacedObject;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.volmlib.util.math.RNG;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisObjectPlacementRunnerRegressionTest {
    private static final int ANCHOR_Y = 80;

    private IrisData data;
    private Engine engine;
    private PlatformBlockState solid;
    private PlatformBlockState schematicAir;

    @Before
    public void bindPlatform() {
        IrisPlatforms.unbind();
        PlatformRegistries registries = mock(PlatformRegistries.class);
        Map<String, PlatformBlockState> registryStates = new HashMap<>();
        when(registries.block(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return registryStates.computeIfAbsent(key, value -> state(value.toLowerCase(), !value.toLowerCase().contains("air")));
        });
        IrisPlatform platform = mock(IrisPlatform.class);
        when(platform.registries()).thenReturn(registries);
        IrisPlatforms.bind(platform);

        engine = mock(Engine.class);
        when(engine.getHeight()).thenReturn(256);
        data = mock(IrisData.class);
        when(data.getEngine()).thenReturn(engine);
        solid = state("minecraft:stone", true);
        schematicAir = state("minecraft:air", false);
    }

    @After
    public void unbindPlatform() {
        IrisPlatforms.unbind();
    }

    @Test
    public void placementFailurePropagatesToTheCaller() {
        RecordingPlacer placer = new RecordingPlacer(null);
        placer.failAfterWrites(1);
        IrisObject object = lineObject(3);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> object.place(0, ANCHOR_Y, 0, placer, placement(), new RNG(2L), data));

        assertTrue(error.getMessage().contains("write failed"));
        assertEquals(1, placer.writes().size());
    }

    @Test
    public void boreUsesRotatedTranslationAndRandomizedAnchor() {
        RecordingPlacer placer = new RecordingPlacer(null);
        IrisObjectPlacement placement = placement();
        placement.setBore(true);
        placement.setRotation(IrisObjectRotation.of(0, 90, 0));
        placement.setTranslate(new IrisObjectTranslate().setX(4).setYRandom(3));

        int resultY = lineObject(3).place(0, ANCHOR_Y, 0, placer, placement, new RNG(2L), data);

        List<BlockWrite> airWrites = placer.writesOf(IrisObject.States.AIR);
        assertFalse(airWrites.isEmpty());
        assertTrue(airWrites.stream().allMatch(write -> write.y() >= resultY && write.y() <= resultY));
        assertTrue(airWrites.stream().allMatch(write -> write.x() == 0));
        assertTrue(airWrites.stream().allMatch(write -> write.z() <= -3 && write.z() >= -5));
    }

    @Test
    public void collisionCheckUsesTheTransformedBounds() {
        PlacedObject forbidden = new PlacedObject(null, object("forbidden"), 1, 0, 0);
        when(engine.getObjectPlacement(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            return x == 0 && y == ANCHOR_Y && z == -4 ? forbidden : null;
        });
        RecordingPlacer placer = new RecordingPlacer(engine);
        IrisObjectPlacement placement = placement();
        placement.setRotation(IrisObjectRotation.of(0, 90, 0));
        placement.setTranslate(new IrisObjectTranslate().setX(4));
        placement.getForbiddenCollisions().add("forbidden");

        int result = lineObject(3).place(0, ANCHOR_Y, 0, placer, placement, new RNG(2L), data);

        assertEquals(-1, result);
        assertTrue(placer.writes().isEmpty());
    }

    @Test
    public void arbitraryRotationSamplesEveryTransformedFootprintColumn() {
        RecordingPlacer placer = new RecordingPlacer(null);
        IrisObjectPlacement placement = placement();
        placement.setMode(ObjectPlaceMode.MAX_HEIGHT);
        placement.setRotation(IrisObjectRotation.of(0, 45, 0));

        boxObject(5, 1, 3).place(0, -1, 0, placer, placement, new RNG(2L), data);

        assertTrue(placer.sampledColumns().contains("-2:-2"));
        assertTrue(placer.sampledColumns().contains("2:2"));
        assertTrue(placer.sampledColumns().size() >= 25);
    }

    @Test
    public void snowUsesOnlyBlocksThatWereWritten() {
        RecordingPlacer placer = new RecordingPlacer(null);
        IrisObject object = new IrisObject(1, 3, 1);
        object.setUnsigned(0, 0, 0, solid);
        object.setUnsigned(0, 2, 0, schematicAir);
        IrisObjectPlacement placement = placement();
        placement.setSnow(0.1);

        object.place(0, ANCHOR_Y, 0, placer, placement, new RNG(2L), data);

        List<BlockWrite> snowWrites = placer.writesOf(IrisObject.States.SNOW_LAYERS[0]);
        assertEquals(1, snowWrites.size());
        assertEquals(ANCHOR_Y, snowWrites.get(0).y());
    }

    @Test
    public void debugPlacementDoesNotMutateSmartBoreCache() {
        IrisObject object = new IrisObject(1, 1, 1);
        object.setUnsigned(0, 0, 0, IrisObject.States.VAIR);
        object.setSmartBored(true);
        IrisObjectPlacement placement = placement();
        placement.setSmartBore(true);
        RecordingPlacer debug = new RecordingPlacer(null);
        debug.setDebugSmartBore(true);
        RecordingPlacer normal = new RecordingPlacer(null);

        object.place(0, ANCHOR_Y, 0, debug, placement, new RNG(2L), data);
        object.place(0, ANCHOR_Y, 0, normal, placement, new RNG(2L), data);

        PlatformBlockState cachedState = object.getBlocks().get(object.getSigned(0, 0, 0));
        assertSame(IrisObject.States.VAIR, cachedState);
        if (IrisObject.States.VAIR != IrisObject.States.VAIR_DEBUG) {
            assertTrue(cachedState != IrisObject.States.VAIR_DEBUG);
        }
    }

    @Test
    public void placementDataIsRequiredAtTheBoundary() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> lineObject(1).place(0, ANCHOR_Y, 0, new RecordingPlacer(null), placement(), new RNG(2L), null));

        assertEquals("Object placement data is required.", error.getMessage());
    }

    private IrisObjectPlacement placement() {
        IrisObjectPlacement placement = new IrisObjectPlacement();
        placement.setMode(ObjectPlaceMode.CENTER_HEIGHT);
        placement.setRequireSurfaceSupport(false);
        return placement;
    }

    private IrisObject lineObject(int width) {
        IrisObject object = new IrisObject(width, 1, 1);
        for (int x = 0; x < width; x++) {
            object.setUnsigned(x, 0, 0, solid);
        }
        return object;
    }

    private IrisObject boxObject(int width, int height, int depth) {
        IrisObject object = new IrisObject(width, height, depth);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    object.setUnsigned(x, y, z, solid);
                }
            }
        }
        return object;
    }

    private IrisObject object(String key) {
        IrisObject object = lineObject(1);
        object.setLoadKey(key);
        return object;
    }

    private static PlatformBlockState state(String key, boolean solid) {
        PlatformBlockState state = mock(PlatformBlockState.class);
        when(state.key()).thenReturn(key);
        when(state.materialKey()).thenReturn(key);
        when(state.isSolid()).thenReturn(solid);
        when(state.isOccluding()).thenReturn(solid);
        return state;
    }

    private static final class RecordingPlacer implements IObjectPlacer {
        private final List<BlockWrite> writes = new ArrayList<>();
        private final Map<String, PlatformBlockState> world = new HashMap<>();
        private final List<String> sampledColumns = new ArrayList<>();
        private final Engine engine;
        private int failAfterWrites = Integer.MAX_VALUE;
        private boolean debugSmartBore;

        private RecordingPlacer(Engine engine) {
            this.engine = engine;
        }

        private void failAfterWrites(int writes) {
            failAfterWrites = writes;
        }

        private void setDebugSmartBore(boolean debugSmartBore) {
            this.debugSmartBore = debugSmartBore;
        }

        private List<BlockWrite> writes() {
            return writes;
        }

        private List<BlockWrite> writesOf(PlatformBlockState state) {
            return writes.stream().filter(write -> write.state() == state).toList();
        }

        private List<String> sampledColumns() {
            return sampledColumns;
        }

        @Override
        public int getHighest(int x, int z, IrisData data) {
            sampledColumns.add(x + ":" + z);
            return ANCHOR_Y;
        }

        @Override
        public int getHighest(int x, int z, IrisData data, boolean ignoreFluid) {
            sampledColumns.add(x + ":" + z);
            return ANCHOR_Y;
        }

        @Override
        public void set(int x, int y, int z, PlatformBlockState state) {
            if (writes.size() >= failAfterWrites) {
                throw new IllegalStateException("write failed");
            }
            writes.add(new BlockWrite(x, y, z, state));
            world.put(x + ":" + y + ":" + z, state);
        }

        @Override
        public PlatformBlockState get(int x, int y, int z) {
            return world.get(x + ":" + y + ":" + z);
        }

        @Override
        public boolean isPreventingDecay() {
            return false;
        }

        @Override
        public boolean isCarved(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            PlatformBlockState state = get(x, y, z);
            return state != null && state.isSolid();
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
            return debugSmartBore;
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
            return engine;
        }
    }

    private record BlockWrite(int x, int y, int z, PlatformBlockState state) {
    }
}

package art.arcane.iris.modded.service;

import art.arcane.iris.util.project.matter.TileWrapper;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.mantle.runtime.MantleDataAdapter;
import art.arcane.volmlib.util.mantle.runtime.MantleHooks;
import art.arcane.volmlib.util.matter.Matter;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModdedChunkUpdateServiceTest {
    @Test
    public void scansWhenPlayersArePresent() {
        assertTrue(ModdedChunkUpdateService.hasUpdateTargets(true, false));
    }

    @Test
    public void scansHeadlessForceLoadedChunks() {
        assertTrue(ModdedChunkUpdateService.hasUpdateTargets(false, true));
    }

    @Test
    public void skipsLevelsWithoutPlayersOrForcedChunks() {
        assertFalse(ModdedChunkUpdateService.hasUpdateTargets(false, false));
    }

    @Test
    public void deferredSliceIsDeletedAfterMaterialization() {
        RecordingMantleChunk chunk = new RecordingMantleChunk();

        ModdedChunkUpdateService.materializeDeferredSlice(
                chunk,
                TileWrapper.class,
                () -> chunk.record("materialize")
        );

        assertEquals(List.of("materialize", "delete:" + TileWrapper.class.getName()),
                chunk.operations());
    }

    @Test
    public void failedMaterializationRetainsDeferredSlice() {
        RecordingMantleChunk chunk = new RecordingMantleChunk();

        assertThrows(IllegalStateException.class, () ->
                ModdedChunkUpdateService.materializeDeferredSlice(
                        chunk,
                        TileWrapper.class,
                        () -> {
                            chunk.record("materialize");
                            throw new IllegalStateException("materialization failure");
                        }
                ));

        assertEquals(List.of("materialize"), chunk.operations());
    }

    private static final class RecordingMantleChunk extends MantleChunk<Matter> {
        private final ArrayList<String> operations = new ArrayList<>();

        private RecordingMantleChunk() {
            super(1, 0, 0, emptyAdapter(), MantleHooks.NONE);
        }

        @Override
        public void deleteSlices(Class<?> type) {
            operations.add("delete:" + type.getName());
        }

        private void record(String operation) {
            operations.add(operation);
        }

        private List<String> operations() {
            return List.copyOf(operations);
        }
    }

    @SuppressWarnings("unchecked")
    private static MantleDataAdapter<Matter> emptyAdapter() {
        return (MantleDataAdapter<Matter>) Proxy.newProxyInstance(
                MantleDataAdapter.class.getClassLoader(),
                new Class<?>[]{MantleDataAdapter.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}

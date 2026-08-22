package art.arcane.iris.engine.framework;

import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NativeStructureVolumeMemoTest {
    @Test
    public void repeatedQueriesInsideOneChunkAskThePlatformOnce() {
        RecordingHooks hooks = new RecordingHooks(volume(0, 64, 0, 15, 78, 15));
        NativeStructureVolumeMemo memo = new NativeStructureVolumeMemo();

        KList<NativeStructureVolume> first = memo.volumes(null, hooks, 2, 2, 6, 6);
        KList<NativeStructureVolume> second = memo.volumes(null, hooks, 8, 8, 12, 12);

        assertEquals(1, hooks.queries().size());
        assertEquals(first, second);
    }

    @Test
    public void chunkQueriesCoverTheWholeChunkColumn() {
        RecordingHooks hooks = new RecordingHooks();
        NativeStructureVolumeMemo memo = new NativeStructureVolumeMemo();

        memo.volumes(null, hooks, 20, 36, 21, 37);

        assertEquals(1, hooks.queries().size());
        assertEquals(List.of(16, 32, 31, 47), hooks.queries().getFirst());
    }

    @Test
    public void rectsSpanningChunksUnionWithoutDuplicates() {
        NativeStructureVolume shared = volume(10, 64, 10, 40, 78, 40);
        RecordingHooks hooks = new RecordingHooks(shared);
        NativeStructureVolumeMemo memo = new NativeStructureVolumeMemo();

        KList<NativeStructureVolume> volumes = memo.volumes(null, hooks, 12, 12, 20, 20);

        assertEquals(4, hooks.queries().size());
        assertEquals(1, volumes.size());
        assertEquals(shared, volumes.getFirst());
    }

    @Test
    public void volumesOutsideTheRectAreFiltered() {
        RecordingHooks hooks = new RecordingHooks(volume(0, 64, 0, 3, 78, 3));
        NativeStructureVolumeMemo memo = new NativeStructureVolumeMemo();

        assertTrue(memo.volumes(null, hooks, 5, 5, 9, 9).isEmpty());
    }

    @Test
    public void clearingTheMemoReQueriesThePlatform() {
        RecordingHooks hooks = new RecordingHooks(volume(0, 64, 0, 15, 78, 15));
        NativeStructureVolumeMemo memo = new NativeStructureVolumeMemo();

        memo.volumes(null, hooks, 2, 2, 6, 6);
        memo.clear();
        memo.volumes(null, hooks, 2, 2, 6, 6);

        assertEquals(2, hooks.queries().size());
    }

    @Test
    public void missingHooksResolveNoVolumes() {
        NativeStructureVolumeMemo memo = new NativeStructureVolumeMemo();

        assertTrue(memo.volumes(null, null, 0, 0, 15, 15).isEmpty());
    }

    private static NativeStructureVolume volume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new NativeStructureVolume("minecraft:village_swamp", minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static final class RecordingHooks implements EnginePlatformHooks {
        private final KList<List<Integer>> queries = new KList<>();
        private final List<NativeStructureVolume> answer = new ArrayList<>();

        private RecordingHooks(NativeStructureVolume... volumes) {
            for (NativeStructureVolume volume : volumes) {
                answer.add(volume);
            }
        }

        private KList<List<Integer>> queries() {
            return queries;
        }

        @Override
        public KList<NativeStructureVolume> nativeStructureVolumes(Engine engine, int minX, int minZ, int maxX, int maxZ) {
            queries.add(List.of(minX, minZ, maxX, maxZ));
            KList<NativeStructureVolume> volumes = new KList<>();
            for (NativeStructureVolume volume : answer) {
                if (volume.intersectsRect(minX, minZ, maxX, maxZ)) {
                    volumes.add(volume);
                }
            }
            return volumes;
        }
    }
}

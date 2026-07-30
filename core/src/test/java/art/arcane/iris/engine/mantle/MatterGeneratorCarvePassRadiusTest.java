package art.arcane.iris.engine.mantle;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MatterGeneratorCarvePassRadiusTest {
    @Test
    @SuppressWarnings("unchecked")
    public void carvePassCoversEveryChunkTheObjectPassWritesObjectsInto() {
        IrisDimension dimension = mock(IrisDimension.class);
        when(dimension.isUseMantle()).thenReturn(true);
        Mantle<Matter> mantle = mock(Mantle.class);
        MantleChunk<Matter> chunk = mock(MantleChunk.class);
        when(mantle.getChunk(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.use()).thenReturn(chunk);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return null;
        }).when(chunk).raiseFlagSuspend(any(), any(Runnable.class));

        Engine engine = mock(Engine.class);
        when(engine.getDimension()).thenReturn(dimension);

        RecordingComponent carving = new RecordingComponent(ReservedFlag.CARVED, 0, 1);
        RecordingComponent objects = new RecordingComponent(ReservedFlag.OBJECT, 1, 40);

        TestMatterGenerator generator = new TestMatterGenerator(engine, mantle, List.of(
                new MantlePass(List.of(carving), Math.ceilDiv(1 + 40, 16), 40),
                new MantlePass(List.of(objects), Math.ceilDiv(40, 16), 0)
        ));
        generator.generateMatter(0, 0, false, mock(ChunkContext.class));

        assertTrue(objects.visited.size() > 9);
        assertEquals(objects.visited, carving.visited);
    }

    private static final class RecordingComponent implements MantleComponent {
        private final MantleFlag flag;
        private final int priority;
        private final int radius;
        private final Set<String> visited = new LinkedHashSet<>();

        private RecordingComponent(MantleFlag flag, int priority, int radius) {
            this.flag = flag;
            this.priority = priority;
            this.radius = radius;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public int getRadius() {
            return radius;
        }

        @Override
        public EngineMantle getEngineMantle() {
            return null;
        }

        @Override
        public MantleFlag getFlag() {
            return flag;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        @Override
        public void hotload() {
        }

        @Override
        public void generateLayer(MantleWriter writer, int x, int z, ChunkContext context) {
            visited.add(x + "," + z);
        }
    }

    private static final class TestMatterGenerator implements MatterGenerator {
        private final Engine engine;
        private final Mantle<Matter> mantle;
        private final List<MantlePass> components;

        private TestMatterGenerator(Engine engine, Mantle<Matter> mantle, List<MantlePass> components) {
            this.engine = engine;
            this.mantle = mantle;
            this.components = components;
        }

        @Override
        public Engine getEngine() {
            return engine;
        }

        @Override
        public Mantle<Matter> getMantle() {
            return mantle;
        }

        @Override
        public int getRadius() {
            return components.getFirst().passChunkRadius();
        }

        @Override
        public int getRealRadius() {
            return components.getLast().passChunkRadius();
        }

        @Override
        public List<MantlePass> getComponents() {
            return components;
        }
    }
}

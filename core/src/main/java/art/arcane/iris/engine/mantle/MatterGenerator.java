package art.arcane.iris.engine.mantle;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public interface MatterGenerator {
    MultiBurst DISPATCHER = MultiBurst.burst;
    ConcurrentHashMap<MatterTaskKey, CompletableFuture<Void>> IN_FLIGHT_COMPONENTS = new ConcurrentHashMap<>();

    Engine getEngine();

    Mantle<Matter> getMantle();

    int getRadius();

    int getRealRadius();

    List<MantlePass> getComponents();

    @ChunkCoordinates
    default void generateMatter(int x, int z, boolean multicore, ChunkContext context) {
        if (!getEngine().getDimension().isUseMantle()) {
            return;
        }

        int writeRadius = getRadius();
        LongOpenHashSet partialChunks = new LongOpenHashSet();

        try (MantleWriter writer = new MantleWriter(getEngine().getMantle(), getMantle(), x, z, writeRadius, multicore)) {
            for (MantlePass pass : getComponents()) {
                int passRadius = pass.passChunkRadius();
                List<MantleComponent> passComponents = pass.components();
                MantleComponent[] enabledComponents = new MantleComponent[passComponents.size()];
                int[] componentPassRadii = new int[passComponents.size()];
                int enabledComponentCount = 0;
                for (MantleComponent component : passComponents) {
                    if (component.isEnabled()) {
                        // A component must cover its own reach plus every later pass' reach, or a
                        // later pass reads this component's data from chunks it never wrote.
                        int componentReach = component.getRadius() + pass.downstreamBlockRadius();
                        componentPassRadii[enabledComponentCount] = componentReach > 0 ? Math.ceilDiv(componentReach, 16) : 0;
                        enabledComponents[enabledComponentCount++] = component;
                    }
                }

                if (enabledComponentCount == 0) {
                    continue;
                }

                boolean inlineComponents = multicore && DISPATCHER.ownsCurrentThread();
                List<CompletableFuture<Void>> launchedTasks = multicore && !inlineComponents ? new ArrayList<>() : null;
                MantleComponent[] eligibleComponents = new MantleComponent[enabledComponentCount];

                for (int i = -passRadius; i <= passRadius; i++) {
                    int absI = Math.abs(i);
                    for (int j = -passRadius; j <= passRadius; j++) {
                        int absJ = Math.abs(j);
                        int passX = x + i;
                        int passZ = z + j;
                        long passKey = chunkKey(passX, passZ);
                        boolean partial = false;
                        boolean anyComponentInRadius = false;

                        for (int componentIndex = 0; componentIndex < enabledComponentCount; componentIndex++) {
                            int componentPassRadius = componentPassRadii[componentIndex];
                            if (absI > componentPassRadius || absJ > componentPassRadius) {
                                partial = true;
                            } else {
                                anyComponentInRadius = true;
                            }
                        }

                        if (!anyComponentInRadius) {
                            partialChunks.add(passKey);
                            continue;
                        }

                        if (partial) {
                            partialChunks.add(passKey);
                        }

                        MantleChunk<Matter> chunk = writer.acquireChunk(passX, passZ);
                        if (chunk.isFlagged(MantleFlag.PLANNED)) {
                            continue;
                        }

                        int eligibleComponentCount = 0;
                        for (int componentIndex = 0; componentIndex < enabledComponentCount; componentIndex++) {
                            MantleComponent component = enabledComponents[componentIndex];
                            int componentPassRadius = componentPassRadii[componentIndex];
                            if (absI > componentPassRadius || absJ > componentPassRadius) {
                                continue;
                            }

                            if (chunk.isFlagged(component.getFlag())) {
                                continue;
                            }

                            MantleFlag[] prerequisites = component.getPrerequisiteFlags();
                            if (prerequisites.length > 0) {
                                boolean prerequisitesMet = true;
                                for (MantleFlag prereq : prerequisites) {
                                    if (!chunk.isFlagged(prereq)) {
                                        prerequisitesMet = false;
                                        break;
                                    }
                                }
                                if (!prerequisitesMet) {
                                    partialChunks.add(passKey);
                                    continue;
                                }
                            }

                            eligibleComponents[eligibleComponentCount++] = component;
                        }

                        if (eligibleComponentCount == 0) {
                            continue;
                        }

                        int finalPassX = passX;
                        int finalPassZ = passZ;
                        if (multicore) {
                            if (inlineComponents) {
                                for (int componentIndex = 0; componentIndex < eligibleComponentCount; componentIndex++) {
                                    MantleComponent component = eligibleComponents[componentIndex];
                                    runComponentInline(chunk, component, writer, finalPassX, finalPassZ, context);
                                }
                            } else {
                                for (int componentIndex = 0; componentIndex < eligibleComponentCount; componentIndex++) {
                                    MantleComponent component = eligibleComponents[componentIndex];
                                    launchedTasks.add(runComponentAsync(chunk, component, writer, finalPassX, finalPassZ, context));
                                }
                            }
                        } else {
                            for (int componentIndex = 0; componentIndex < eligibleComponentCount; componentIndex++) {
                                MantleComponent component = eligibleComponents[componentIndex];
                                runComponentInline(chunk, component, writer, finalPassX, finalPassZ, context);
                            }
                        }
                    }
                }

                if (launchedTasks != null) {
                    for (CompletableFuture<Void> launchedTask : launchedTasks) {
                        launchedTask.join();
                    }
                }
            }

            for (int i = -getRealRadius(); i <= getRealRadius(); i++) {
                for (int j = -getRealRadius(); j <= getRealRadius(); j++) {
                    int realX = x + i;
                    int realZ = z + j;
                    long realKey = chunkKey(realX, realZ);
                    if (partialChunks.contains(realKey)) {
                        continue;
                    }
                    writer.acquireChunk(realX, realZ).flag(MantleFlag.PLANNED, true);
                }
            }
        }
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private CompletableFuture<Void> runComponentAsync(
            MantleChunk<Matter> chunk,
            MantleComponent component,
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            ChunkContext context
    ) {
        MantleFlag flag = component.getFlag();
        if (chunk.isFlagged(flag)) {
            return CompletableFuture.completedFuture(null);
        }

        MatterTaskKey key = new MatterTaskKey(getMantle(), chunkX, chunkZ, flag.ordinal());
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> existing = IN_FLIGHT_COMPONENTS.putIfAbsent(key, future);
        if (existing != null) {
            return existing;
        }

        try {
            if (DISPATCHER.ownsCurrentThread()) {
                completeComponentTask(future, key, chunk, component, writer, chunkX, chunkZ, context);
            } else {
                CompletableFuture.runAsync(() -> completeComponentTask(future, key, chunk, component, writer, chunkX, chunkZ, context), DISPATCHER);
            }
        } catch (Throwable throwable) {
            IN_FLIGHT_COMPONENTS.remove(key, future);
            future.completeExceptionally(throwable);
            throw throwable;
        }

        return future;
    }

    private void completeComponentTask(
            CompletableFuture<Void> future,
            MatterTaskKey key,
            MantleChunk<Matter> chunk,
            MantleComponent component,
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            ChunkContext context
    ) {
        try {
            runComponentInline(chunk, component, writer, chunkX, chunkZ, context);
            future.complete(null);
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
            throw throwable;
        } finally {
            IN_FLIGHT_COMPONENTS.remove(key, future);
        }
    }

    private void runComponentInline(
            MantleChunk<Matter> chunk,
            MantleComponent component,
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            ChunkContext context
    ) {
        chunk.raiseFlagSuspend(component.getFlag(), () -> component.generateLayer(writer, chunkX, chunkZ, context));
    }

    final class MatterTaskKey {
        private final Mantle<Matter> mantle;
        private final int chunkX;
        private final int chunkZ;
        private final int flagOrdinal;

        MatterTaskKey(Mantle<Matter> mantle, int chunkX, int chunkZ, int flagOrdinal) {
            this.mantle = mantle;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.flagOrdinal = flagOrdinal;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }

            if (!(object instanceof MatterTaskKey other)) {
                return false;
            }

            return mantle == other.mantle
                    && chunkX == other.chunkX
                    && chunkZ == other.chunkZ
                    && flagOrdinal == other.flagOrdinal;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(mantle);
            result = 31 * result + chunkX;
            result = 31 * result + chunkZ;
            result = 31 * result + flagOrdinal;
            return result;
        }
    }
}

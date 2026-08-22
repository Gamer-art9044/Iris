package art.arcane.iris.engine.mantle;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.spi.IrisLogging;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface MatterGenerator {
    MultiBurst DISPATCHER = MultiBurst.burst;
    ConcurrentHashMap<MatterTaskKey, CompletableFuture<Void>> IN_FLIGHT_COMPONENTS = new ConcurrentHashMap<>();
    long COMPONENT_TASK_POLL_MS = 1000L;
    long COMPONENT_TASK_TIMEOUT_MS = Long.getLong("iris.mantle.componentTimeout", 120000L);

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
            // Every task launched below writes through this writer. They must all be finished before
            // close() releases the cached chunks, even when a pass throws, or detached pool threads
            // write into released chunks.
            List<MatterComponentTask> outstandingTasks = null;

            try {
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

                    // A dispatcher thread runs its components inline: dispatching from inside the pool
                    // only trades a worker for a blocked worker.
                    boolean asyncComponents = multicore && !DISPATCHER.ownsCurrentThread();
                    if (asyncComponents && outstandingTasks == null) {
                        outstandingTasks = new ArrayList<>();
                    }
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
                            if (chunk == null) {
                                throw new IllegalStateException("Mantle pass chunk " + passX + "," + passZ
                                        + " is outside the writer prepared at " + x + "," + z + " with radius " + writeRadius);
                            }

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
                            if (asyncComponents) {
                                for (int componentIndex = 0; componentIndex < eligibleComponentCount; componentIndex++) {
                                    MantleComponent component = eligibleComponents[componentIndex];
                                    outstandingTasks.add(runComponentAsync(chunk, component, writer, finalPassX, finalPassZ, context));
                                }
                            } else {
                                for (int componentIndex = 0; componentIndex < eligibleComponentCount; componentIndex++) {
                                    MantleComponent component = eligibleComponents[componentIndex];
                                    runComponentInline(chunk, component, writer, finalPassX, finalPassZ, context);
                                }
                            }
                        }
                    }

                    if (asyncComponents) {
                        awaitComponentTasks(outstandingTasks);
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
            } finally {
                abandonComponentTasks(outstandingTasks);
            }
        }
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private MatterComponentTask runComponentAsync(
            MantleChunk<Matter> chunk,
            MantleComponent component,
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            ChunkContext context
    ) {
        MantleFlag flag = component.getFlag();
        MatterTaskKey key = new MatterTaskKey(getMantle(), chunkX, chunkZ, flag);
        if (chunk.isFlagged(flag)) {
            return new MatterComponentTask(key, CompletableFuture.completedFuture(null), null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> existing = IN_FLIGHT_COMPONENTS.putIfAbsent(key, future);
        if (existing != null) {
            return new MatterComponentTask(key, existing, null);
        }

        try {
            if (DISPATCHER.ownsCurrentThread()) {
                completeComponentTask(future, key, chunk, component, writer, chunkX, chunkZ, context);
                return new MatterComponentTask(key, future, null);
            }

            Future<?> submission = DISPATCHER.submit(() -> completeComponentTask(future, key, chunk, component, writer, chunkX, chunkZ, context));
            return new MatterComponentTask(key, future, submission);
        } catch (Throwable throwable) {
            IN_FLIGHT_COMPONENTS.remove(key, future);
            future.completeExceptionally(throwable);
            throw throwable;
        }
    }

    /**
     * Pass barrier. Waits for every launched task, even if one of them failed, so that no task is
     * still writing through the writer once this returns. The first failure is rethrown.
     */
    private static void awaitComponentTasks(List<MatterComponentTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }

        Throwable failure = null;
        for (MatterComponentTask task : tasks) {
            try {
                awaitComponentTask(task);
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                } else if (failure != throwable) {
                    failure.addSuppressed(throwable);
                }
            }
        }
        tasks.clear();

        if (failure != null) {
            throw failure instanceof RuntimeException runtime ? runtime : new CompletionException(failure);
        }
    }

    /**
     * Drains tasks that never reached their pass barrier because the pass threw. The generation
     * attempt is already failing, so the wait itself is the point and failures are dropped rather
     * than masking the original throwable.
     */
    private static void abandonComponentTasks(List<MatterComponentTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        try {
            awaitComponentTasks(tasks);
        } catch (Throwable ignored) {
        }
    }

    private static void awaitComponentTask(MatterComponentTask task) {
        CompletableFuture<Void> future = task.future();
        long start = System.currentTimeMillis();
        boolean interrupted = false;

        try {
            while (true) {
                try {
                    future.get(COMPONENT_TASK_POLL_MS, TimeUnit.MILLISECONDS);
                    return;
                } catch (InterruptedException interruption) {
                    // The writer cannot close while this task still writes through it, so the wait is
                    // uninterruptible and the flag is restored on the way out.
                    interrupted = true;
                } catch (TimeoutException timeout) {
                    Future<?> submission = task.submission();
                    boolean dropped = submission != null && submission.isDone();
                    if (future.isDone()) {
                        continue;
                    }

                    long waited = System.currentTimeMillis() - start;
                    if (!dropped && waited < COMPONENT_TASK_TIMEOUT_MS) {
                        continue;
                    }

                    // The task can no longer complete (the dispatcher dropped it) or is wedged. Drop
                    // the dedup entry so later generations do not inherit a future that never
                    // completes, and release anything else already waiting on it.
                    IllegalStateException failure = new IllegalStateException("Mantle component " + task.key()
                            + (dropped ? " was dropped by the dispatcher" : " did not complete in " + waited + "ms"));
                    IN_FLIGHT_COMPONENTS.remove(task.key(), future);
                    future.completeExceptionally(failure);
                    IrisLogging.error(failure.getMessage());
                    throw new CompletionException(failure);
                } catch (ExecutionException executionFailure) {
                    Throwable cause = executionFailure.getCause();
                    throw new CompletionException(cause != null ? cause : executionFailure);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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

    /**
     * A launched component task. {@code submission} is the dispatcher handle when this generation
     * owns the task, and null when the future belongs to another generation or already completed.
     */
    record MatterComponentTask(MatterTaskKey key, CompletableFuture<Void> future, Future<?> submission) {
    }

    final class MatterTaskKey {
        private final Mantle<Matter> mantle;
        private final int chunkX;
        private final int chunkZ;
        private final MantleFlag flag;

        MatterTaskKey(Mantle<Matter> mantle, int chunkX, int chunkZ, MantleFlag flag) {
            this.mantle = mantle;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.flag = flag;
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
                    && flag.ordinal() == other.flag.ordinal();
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(mantle);
            result = 31 * result + chunkX;
            result = 31 * result + chunkZ;
            result = 31 * result + flag.ordinal();
            return result;
        }

        @Override
        public String toString() {
            return flag.name() + " at " + chunkX + "," + chunkZ;
        }
    }
}

/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.scheduling.J;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static art.arcane.iris.engine.EngineShutdownSequence.appendFailure;
import static art.arcane.iris.engine.EngineShutdownSequence.propagate;

/**
 * Admission gate and drain tracker for the asynchronous work an {@link IrisEngine} schedules.
 * Admission is closed before any lifecycle transition so a draining engine can never accrue new
 * background work, and every tracked task is awaited (or cancelled) before resources are released.
 */
final class EngineBackgroundTasks {
    private static final long BACKGROUND_TASK_TIMEOUT_MILLIS = 15000L;

    private final Object backgroundTaskLock = new Object();
    private final List<TrackedBackgroundTask> backgroundTasks = new ArrayList<>();
    private boolean backgroundTaskAdmission;

    boolean scheduleTrackedTask(Runnable task) {
        synchronized (backgroundTaskLock) {
            // A finished task is not outstanding work, however it finished. Retaining failed
            // entries made the NEXT transition's drain re-report a long-settled failure and
            // blocked close() from ever marking the engine closed.
            backgroundTasks.removeIf(tracked -> tracked.completion.isDone());
            if (!backgroundTaskAdmission) {
                return false;
            }
            TrackedBackgroundTask tracked = new TrackedBackgroundTask();
            Future<Void> future = J.a(() -> {
                tracked.started.set(true);
                try {
                    task.run();
                    tracked.completion.complete(null);
                    return null;
                } catch (Throwable exception) {
                    tracked.completion.completeExceptionally(exception);
                    // J.a(Callable) routes through a future nobody reads; report here or the
                    // failure is invisible.
                    IrisLogging.reportError(exception);
                    IrisLogging.error("Iris background task failed.");
                    throw propagate(exception);
                }
            });
            if (future == null) {
                throw new IllegalStateException("Iris background task scheduler returned no task handle.");
            }
            tracked.future = future;
            backgroundTasks.add(tracked);
            return true;
        }
    }

    BackgroundTaskDrain drainBackgroundTasks(String reason) {
        List<TrackedBackgroundTask> tasks;
        synchronized (backgroundTaskLock) {
            tasks = List.copyOf(backgroundTasks);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BACKGROUND_TASK_TIMEOUT_MILLIS);
        Throwable failure = null;
        // Settled-set snapshot taken ONCE at drain entry: a per-iteration isDone() sample
        // would also suppress tasks that failed while the drain was blocked on an earlier
        // task, letting a real in-flight failure pass the transition unreported.
        Set<TrackedBackgroundTask> settledAtEntry = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TrackedBackgroundTask task : tasks) {
            if (task.completion.isDone()) {
                settledAtEntry.add(task);
            }
        }
        for (TrackedBackgroundTask task : tasks) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                cancelBackgroundTask(task, reason);
                failure = appendFailure(failure, new TimeoutException("Timed out waiting for Iris background tasks during " + reason + "."));
                continue;
            }
            boolean alreadyDone = settledAtEntry.contains(task);
            try {
                task.completion.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelBackgroundTask(task, reason);
                failure = appendFailure(failure, e);
            } catch (ExecutionException | TimeoutException e) {
                cancelBackgroundTask(task, reason);
                // A task that had settled before this drain began was already reported when it
                // failed; only genuinely in-flight failures may poison this transition.
                if (!alreadyDone) {
                    failure = appendFailure(failure, e);
                } else {
                    IrisLogging.debug("Ignoring pre-settled background task failure during " + reason + ".");
                }
            }
        }
        boolean complete;
        synchronized (backgroundTaskLock) {
            backgroundTasks.removeIf(tracked -> tracked.completion.isDone());
            complete = backgroundTasks.isEmpty();
        }
        return new BackgroundTaskDrain(failure, complete);
    }

    private void cancelBackgroundTask(TrackedBackgroundTask task, String reason) {
        Future<?> future = task.future;
        if (future != null && future.cancel(true) && !task.started.get()) {
            task.completion.completeExceptionally(
                    new IllegalStateException("Iris background task was cancelled before starting during " + reason + "."));
        }
    }

    void openBackgroundTaskAdmission() {
        synchronized (backgroundTaskLock) {
            backgroundTaskAdmission = true;
        }
    }

    void closeBackgroundTaskAdmission() {
        synchronized (backgroundTaskLock) {
            backgroundTaskAdmission = false;
        }
    }

    void cancelBackgroundTasks(String reason) {
        List<TrackedBackgroundTask> tasks;
        synchronized (backgroundTaskLock) {
            tasks = List.copyOf(backgroundTasks);
        }
        for (TrackedBackgroundTask task : tasks) {
            cancelBackgroundTask(task, reason);
        }
    }

    private static final class TrackedBackgroundTask {
        private final AtomicBoolean started = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile Future<?> future;
    }

    record BackgroundTaskDrain(Throwable failure, boolean complete) {
        boolean allowsResourceRelease() {
            return complete;
        }

        void requireComplete(String reason) {
            if (failure != null) {
                throw new IllegalStateException("Iris background tasks failed to drain during " + reason + ".", failure);
            }
            if (!complete) {
                throw new IllegalStateException("Iris background tasks remain active during " + reason + ".");
            }
        }
    }
}

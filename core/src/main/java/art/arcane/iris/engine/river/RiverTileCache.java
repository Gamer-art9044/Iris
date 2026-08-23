package art.arcane.iris.engine.river;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class RiverTileCache implements AutoCloseable {
    private final Object lock;
    private final int maxCompletedEntries;
    private final Map<TileKey, Entry> entries;
    private final LinkedHashMap<TileKey, Entry> completedEntries;
    private TileBuilder builder;
    private boolean closed;

    public RiverTileCache(int maxCompletedEntries, TileBuilder builder) {
        if (maxCompletedEntries < 1) {
            throw new IllegalArgumentException("River tile cache capacity must be positive");
        }
        this.maxCompletedEntries = maxCompletedEntries;
        this.builder = Objects.requireNonNull(builder);
        lock = new Object();
        entries = new HashMap<>(maxCompletedEntries);
        completedEntries = new LinkedHashMap<>(maxCompletedEntries, 0.75f, true);
    }

    public RiverTile get(int tileX, int tileZ) {
        TileKey key = new TileKey(tileX, tileZ);
        Entry entry;
        TileBuilder activeBuilder;
        boolean build;
        synchronized (lock) {
            requireOpen();
            entry = entries.get(key);
            if (entry == null) {
                entry = new Entry();
                entries.put(key, entry);
                activeBuilder = builder;
                build = true;
            } else {
                if (entry.completed) {
                    completedEntries.get(key);
                }
                activeBuilder = null;
                build = false;
            }
        }

        if (build) {
            build(key, entry, activeBuilder);
        }
        return await(entry.future, key);
    }

    public int completedSize() {
        synchronized (lock) {
            return completedEntries.size();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public void clear() {
        List<CompletableFuture<RiverTile>> invalidated;
        synchronized (lock) {
            requireOpen();
            invalidated = clearLocked();
        }
        invalidate(invalidated, "River tile cache was cleared");
    }

    @Override
    public void close() {
        List<CompletableFuture<RiverTile>> invalidated;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            builder = null;
            invalidated = clearLocked();
        }
        invalidate(invalidated, "River tile cache was closed");
    }

    private void build(TileKey key, Entry entry, TileBuilder activeBuilder) {
        try {
            RiverTile tile = Objects.requireNonNull(
                    activeBuilder.build(key.tileX(), key.tileZ()),
                    "River tile builder returned null"
            );
            if (tile.tileX() != key.tileX() || tile.tileZ() != key.tileZ()) {
                throw new IllegalStateException(
                        "River tile builder returned " + tile.tileX() + "," + tile.tileZ()
                                + " for " + key.tileX() + "," + key.tileZ()
                );
            }
            publishCompleted(key, entry, tile);
        } catch (Throwable failure) {
            removeFailed(key, entry);
            entry.future.completeExceptionally(failure);
        }
    }

    private void publishCompleted(TileKey key, Entry entry, RiverTile tile) {
        synchronized (lock) {
            if (closed || entries.get(key) != entry) {
                entry.future.completeExceptionally(new IllegalStateException(
                        closed ? "River tile cache was closed" : "River tile cache entry was cleared"
                ));
                return;
            }
            entry.completed = true;
            completedEntries.put(key, entry);
            while (completedEntries.size() > maxCompletedEntries) {
                Map.Entry<TileKey, Entry> eldest = completedEntries.entrySet().iterator().next();
                completedEntries.remove(eldest.getKey());
                entries.remove(eldest.getKey(), eldest.getValue());
            }
            entry.future.complete(tile);
        }
    }

    private void removeFailed(TileKey key, Entry entry) {
        synchronized (lock) {
            entries.remove(key, entry);
            completedEntries.remove(key, entry);
        }
    }

    private List<CompletableFuture<RiverTile>> clearLocked() {
        ArrayList<CompletableFuture<RiverTile>> invalidated = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            if (!entry.future.isDone()) {
                invalidated.add(entry.future);
            }
        }
        entries.clear();
        completedEntries.clear();
        return invalidated;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("River tile cache is closed");
        }
    }

    private static RiverTile await(CompletableFuture<RiverTile> future, TileKey key) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Failed to build river tile " + key.tileX() + "," + key.tileZ(),
                    cause
            );
        }
    }

    private static void invalidate(List<CompletableFuture<RiverTile>> futures, String message) {
        for (CompletableFuture<RiverTile> future : futures) {
            future.completeExceptionally(new IllegalStateException(message));
        }
    }

    @FunctionalInterface
    public interface TileBuilder {
        RiverTile build(int tileX, int tileZ) throws Exception;
    }

    private record TileKey(int tileX, int tileZ) {
    }

    private static final class Entry {
        private final CompletableFuture<RiverTile> future;
        private boolean completed;

        private Entry() {
            future = new CompletableFuture<>();
        }
    }
}

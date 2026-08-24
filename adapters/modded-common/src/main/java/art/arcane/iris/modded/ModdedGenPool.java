/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

package art.arcane.iris.modded;


import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the Iris generation pool and the single decision of whether this loader already runs chunk
 * generation on its own worker threads.
 *
 * <p>The parallel-chunk-system probe is a two-stage check: {@link Class#forName} presence gates it
 * (never a version string), then the mod's own configuration is read reflectively to confirm the
 * feature is actually enabled. Any reflection failure resolves to "not parallel", which keeps
 * generation on the Iris pool - the safe side, since an extra hop only costs throughput while a
 * missing hop serializes generation on the loader's chunk threads.
 */
public final class ModdedGenPool {
    private static final long SHUTDOWN_DRAIN_MILLIS = 2_000L;
    private static final String[] C2ME_MARKERS = {
            "com.ishland.c2me.base.ModProperties",
            "com.ishland.c2me.base.common.config.C2MEConfig",
            "com.ishland.c2me.opts.chunkio.ModProperties"
    };
    private static final String C2ME_CONFIG = "com.ishland.c2me.base.common.config.C2MEConfig";
    private static final String[] C2ME_SECTIONS = {"asyncScheduling", "asyncSchedulingConfig", "threadedWorldGen"};
    private static final String[] C2ME_FLAGS = {"enabled", "isEnabled", "shouldEnable"};
    private static final String MOONRISE_MARKER = "ca.spottedleaf.moonrise.common.util.MoonriseCommon";
    private static final String[] MOONRISE_WORKER_COUNTS = {"getWorkerThreads", "workerThreads"};
    private static final String[] MOONRISE_POOLS = {"WORKER_POOL", "workerPool"};
    private static final String[] MOONRISE_POOL_COUNTS = {"getCoreThreads", "getThreadCount", "getThreads", "coreThreads", "threadCount"};

    private static final AtomicInteger GEN_THREAD_SEQ = new AtomicInteger();
    private static final ChunkSystem CHUNK_SYSTEM = detectChunkSystem();
    private static final AtomicReference<ExecutorService> GEN_POOL = new AtomicReference<>(createGenPool());

    private ModdedGenPool() {
    }

    /**
     * True when the loader's chunk system already generates off the server thread, so Iris must not
     * add its own pool hop.
     */
    public static boolean parallelChunkSystem() {
        return CHUNK_SYSTEM.parallel();
    }

    /**
     * Terse description of the detected chunk system, for one-line diagnostics.
     */
    public static String describeChunkSystem() {
        return CHUNK_SYSTEM.description();
    }

    static ExecutorService pool() {
        ExecutorService pool = GEN_POOL.get();
        if (pool != null && !pool.isShutdown()) {
            return pool;
        }
        start();
        ExecutorService restarted = GEN_POOL.get();
        if (restarted == null) {
            throw new RejectedExecutionException("Iris gen pool is shut down");
        }
        return restarted;
    }

    static void start() {
        while (true) {
            ExecutorService current = GEN_POOL.get();
            if (current != null && !current.isShutdown()) {
                return;
            }
            ExecutorService created = createGenPool();
            if (GEN_POOL.compareAndSet(current, created)) {
                return;
            }
            created.shutdownNow();
        }
    }

    static void shutdown() {
        ExecutorService pool = GEN_POOL.getAndSet(null);
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (pool.awaitTermination(SHUTDOWN_DRAIN_MILLIS, TimeUnit.MILLISECONDS)) {
                return;
            }
            ModdedIrisLog.debug("Iris gen pool did not drain in {}ms, forcing shutdown", SHUTDOWN_DRAIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
    }

    private static ChunkSystem detectChunkSystem() {
        ChunkSystem detected = probeC2ME();
        if (detected == null) {
            detected = probeMoonrise();
        }
        if (detected == null) {
            detected = new ChunkSystem(false, "vanilla");
        }
        ModdedIrisLog.info("Iris chunk system: {} (parallel={}, generation on {})",
                detected.description(),
                detected.parallel() ? "yes" : "no",
                detected.parallel() ? "loader threads" : "Iris gen pool");
        return detected;
    }

    private static ChunkSystem probeC2ME() {
        if (!anyPresent(C2ME_MARKERS)) {
            return null;
        }
        Class<?> config = loadOrNull(C2ME_CONFIG);
        if (config == null) {
            return new ChunkSystem(false, "c2me present, config class missing");
        }
        Boolean enabled = readSectionFlag(config, C2ME_SECTIONS, C2ME_FLAGS);
        if (enabled == null) {
            return new ChunkSystem(false, "c2me present, async scheduling unreadable");
        }
        return new ChunkSystem(enabled, enabled ? "c2me async scheduling on" : "c2me async scheduling off");
    }

    private static ChunkSystem probeMoonrise() {
        Class<?> marker = loadOrNull(MOONRISE_MARKER);
        if (marker == null) {
            return null;
        }
        Integer workers = readMoonriseWorkers(marker);
        if (workers == null) {
            return new ChunkSystem(false, "moonrise present, worker pool unreadable");
        }
        if (workers <= 0) {
            return new ChunkSystem(false, "moonrise worker pool empty");
        }
        return new ChunkSystem(true, "moonrise workers=" + workers);
    }

    /**
     * Reads {@code <configClass>.<section>.<flag>} where the section may itself be the boolean.
     * Returns null when nothing along the path is readable.
     */
    private static Boolean readSectionFlag(Class<?> configClass, String[] sections, String[] flags) {
        for (String section : sections) {
            Field field = staticFieldOrNull(configClass, section);
            if (field == null) {
                continue;
            }
            Object value;
            try {
                value = field.get(null);
            } catch (Throwable e) {
                ModdedIrisLog.debug("Iris chunk system probe could not read {}.{}: {}", configClass.getName(), section, e.toString());
                continue;
            }
            if (value instanceof Boolean flag) {
                return flag;
            }
            if (value == null) {
                continue;
            }
            Boolean nested = readBooleanMember(value, flags);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Boolean readBooleanMember(Object owner, String[] names) {
        for (String name : names) {
            for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                Field field = declaredFieldOrNull(type, name);
                if (field != null && (field.getType() == boolean.class || field.getType() == Boolean.class)) {
                    try {
                        if (field.get(owner) instanceof Boolean flag) {
                            return flag;
                        }
                    } catch (Throwable e) {
                        ModdedIrisLog.debug("Iris chunk system probe could not read {}.{}: {}", type.getName(), name, e.toString());
                    }
                }
                Method method = declaredMethodOrNull(type, name);
                if (method != null && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                    try {
                        if (method.invoke(owner) instanceof Boolean flag) {
                            return flag;
                        }
                    } catch (Throwable e) {
                        ModdedIrisLog.debug("Iris chunk system probe could not call {}.{}(): {}", type.getName(), name, e.toString());
                    }
                }
            }
        }
        return null;
    }

    private static Integer readMoonriseWorkers(Class<?> marker) {
        for (String name : MOONRISE_WORKER_COUNTS) {
            Integer direct = readIntMember(marker, null, name);
            if (direct != null) {
                return direct;
            }
            Field field = staticFieldOrNull(marker, name);
            if (field != null) {
                try {
                    Object value = field.get(null);
                    if (value instanceof Number number) {
                        return number.intValue();
                    }
                } catch (Throwable e) {
                    ModdedIrisLog.debug("Iris chunk system probe could not read {}.{}: {}", marker.getName(), name, e.toString());
                }
            }
        }
        for (String poolName : MOONRISE_POOLS) {
            Field field = staticFieldOrNull(marker, poolName);
            if (field == null) {
                continue;
            }
            Object pool;
            try {
                pool = field.get(null);
            } catch (Throwable e) {
                ModdedIrisLog.debug("Iris chunk system probe could not read {}.{}: {}", marker.getName(), poolName, e.toString());
                continue;
            }
            if (pool == null) {
                continue;
            }
            for (String countName : MOONRISE_POOL_COUNTS) {
                Integer count = readIntMember(pool.getClass(), pool, countName);
                if (count != null) {
                    return count;
                }
            }
        }
        return null;
    }

    private static Integer readIntMember(Class<?> type, Object owner, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Method method = declaredMethodOrNull(current, name);
            if (method == null) {
                continue;
            }
            try {
                if (method.invoke(owner) instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable e) {
                ModdedIrisLog.debug("Iris chunk system probe could not call {}.{}(): {}", current.getName(), name, e.toString());
            }
        }
        return null;
    }

    private static Field staticFieldOrNull(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Field field = declaredFieldOrNull(current, name);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    private static Field declaredFieldOrNull(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        } catch (Throwable e) {
            ModdedIrisLog.debug("Iris chunk system probe could not access field {}.{}: {}", type.getName(), name, e.toString());
            return null;
        }
    }

    private static Method declaredMethodOrNull(Class<?> type, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Throwable e) {
            ModdedIrisLog.debug("Iris chunk system probe could not access method {}.{}(): {}", type.getName(), name, e.toString());
            return null;
        }
    }

    private static boolean anyPresent(String[] markers) {
        for (String marker : markers) {
            if (loadOrNull(marker) != null) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> loadOrNull(String name) {
        try {
            return Class.forName(name, false, ModdedGenPool.class.getClassLoader());
        } catch (Throwable e) {
            ModdedIrisLog.debug("Iris chunk system probe: {} absent ({})", name, e.getClass().getSimpleName());
            return null;
        }
    }

    private static ExecutorService createGenPool() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "Iris ModGen-" + GEN_THREAD_SEQ.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private record ChunkSystem(boolean parallel, String description) {
    }
}

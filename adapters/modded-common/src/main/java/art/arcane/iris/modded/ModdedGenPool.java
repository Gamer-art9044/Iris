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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class ModdedGenPool {
    private static final AtomicInteger GEN_THREAD_SEQ = new AtomicInteger();
    private static final boolean PARALLEL_CHUNK_SYSTEM = detectParallelChunkSystem();
    private static volatile ExecutorService genPool = createGenPool();

    private ModdedGenPool() {
    }

    static boolean parallelChunkSystem() {
        return PARALLEL_CHUNK_SYSTEM;
    }

    static ExecutorService pool() {
        return genPool;
    }

    static void start() {
        ExecutorService pool = genPool;
        if (pool == null || pool.isShutdown()) {
            genPool = createGenPool();
        }
    }

    static void shutdown() {
        ExecutorService pool = genPool;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static boolean detectParallelChunkSystem() {
        String[] markers = {
                "com.ishland.c2me.base.ModProperties",
                "com.ishland.c2me.base.common.config.C2MEConfig",
                "com.ishland.c2me.opts.chunkio.ModProperties",
                "ca.spottedleaf.moonrise.common.util.MoonriseCommon"
        };
        for (String marker : markers) {
            try {
                Class.forName(marker, false, IrisModdedChunkGenerator.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
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
}

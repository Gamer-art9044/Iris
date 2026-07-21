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

package art.arcane.iris.core.pregenerator;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MantleHeapPressure {
    private static final double HIGH_WATER = 0.92D;
    private static final double LOW_WATER = 0.82D;
    private static final double PANIC_WATER = 0.96D;
    private static final long PANIC_GC_INTERVAL_MS = 30_000L;
    private static final AtomicBoolean engaged = new AtomicBoolean(false);
    private static final AtomicLong lastPanicGcAt = new AtomicLong(0L);

    private MantleHeapPressure() {
    }

    public static double usedFraction() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax();
        if (max <= 0L) {
            Runtime runtime = Runtime.getRuntime();
            long runtimeMax = runtime.maxMemory();
            if (runtimeMax <= 0L) {
                return 0.0D;
            }
            long used = runtime.totalMemory() - runtime.freeMemory();
            return (double) used / (double) runtimeMax;
        }
        return (double) heap.getUsed() / (double) max;
    }

    public static boolean overHighWater() {
        double fraction = usedFraction();
        if (engaged.get()) {
            if (fraction <= LOW_WATER) {
                engaged.set(false);
                return false;
            }
            return true;
        }
        if (fraction >= HIGH_WATER) {
            engaged.set(true);
            return true;
        }
        return false;
    }

    public static double reclaimUrgency(double fraction) {
        if (!Double.isFinite(fraction) || fraction <= LOW_WATER) {
            return 0D;
        }
        if (fraction >= HIGH_WATER) {
            return 1D;
        }
        return (fraction - LOW_WATER) / (HIGH_WATER - LOW_WATER);
    }

    public static boolean overPanicWater() {
        return usedFraction() >= PANIC_WATER;
    }

    public static void requestPanicReclaim() {
        long now = System.currentTimeMillis();
        long last = lastPanicGcAt.get();
        if (now - last < PANIC_GC_INTERVAL_MS) {
            return;
        }
        if (lastPanicGcAt.compareAndSet(last, now)) {
            System.gc();
        }
    }
}

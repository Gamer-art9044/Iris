package art.arcane.iris.core.tools;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.engine.mantle.MantleSliceRetention;
import art.arcane.iris.spi.IrisLogging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorldMaintenance {
    private static final Map<String, AtomicInteger> worldMaintenanceDepth = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> worldMaintenanceMantleBypassDepth = new ConcurrentHashMap<>();

    private WorldMaintenance() {
    }

    public static void beginWorldMaintenance(String worldName, String reason) {
        beginWorldMaintenance(worldName, reason, false);
    }

    public static void beginWorldMaintenance(String worldName, String reason, boolean bypassMantleStages) {
        if (worldName == null) {
            return;
        }

        int depth = worldMaintenanceDepth.computeIfAbsent(worldName, k -> new AtomicInteger()).incrementAndGet();
        if (bypassMantleStages) {
            worldMaintenanceMantleBypassDepth.computeIfAbsent(worldName, k -> new AtomicInteger()).incrementAndGet();
        }
        if (IrisSettings.get().getGeneral().isDebug()) {
            IrisLogging.info("World maintenance enter: " + worldName + " reason=" + reason + " depth=" + depth + " bypassMantle=" + bypassMantleStages);
        } else {
            IrisLogging.debug("World maintenance enter: " + worldName + " reason=" + reason + " depth=" + depth + " bypassMantle=" + bypassMantleStages);
        }
    }

    public static void endWorldMaintenance(String worldName, String reason) {
        endWorldMaintenance(worldName, reason, false);
    }

    public static void endWorldMaintenance(String worldName, String reason, boolean bypassMantleStages) {
        if (worldName == null) {
            return;
        }

        AtomicInteger depthCounter = worldMaintenanceDepth.get(worldName);
        if (depthCounter == null) {
            return;
        }

        int depth = depthCounter.decrementAndGet();
        if (depth <= 0) {
            worldMaintenanceDepth.remove(worldName, depthCounter);
            depth = 0;
        }

        // Only a bypass-begin's paired end releases bypass credit: a plain end overlapping a
        // bypassing operation stole its credit and re-enabled mantle stages under it.
        int bypassDepth = 0;
        if (bypassMantleStages) {
            AtomicInteger bypassCounter = worldMaintenanceMantleBypassDepth.get(worldName);
            if (bypassCounter != null) {
                bypassDepth = bypassCounter.decrementAndGet();
                if (bypassDepth <= 0) {
                    worldMaintenanceMantleBypassDepth.remove(worldName, bypassCounter);
                    bypassDepth = 0;
                }
            }
        }

        if (IrisSettings.get().getGeneral().isDebug()) {
            IrisLogging.info("World maintenance exit: " + worldName + " reason=" + reason + " depth=" + depth + " bypassMantleDepth=" + bypassDepth);
        } else {
            IrisLogging.debug("World maintenance exit: " + worldName + " reason=" + reason + " depth=" + depth + " bypassMantleDepth=" + bypassDepth);
        }
    }

    public static boolean isWorldMaintenanceActive(String worldName) {
        if (worldName == null) {
            return false;
        }

        AtomicInteger counter = worldMaintenanceDepth.get(worldName);
        return counter != null && counter.get() > 0;
    }

    public static boolean isWorldMaintenanceBypassingMantleStages(String worldName) {
        if (worldName == null) {
            return false;
        }

        AtomicInteger counter = worldMaintenanceMantleBypassDepth.get(worldName);
        return counter != null && counter.get() > 0;
    }

    public static void retainMantleDataForSlice(String className) {
        MantleSliceRetention.retain(className);
    }

    public static boolean isRetainingMantleDataForSlice(String className) {
        return MantleSliceRetention.isRetained(className);
    }
}

package art.arcane.iris.core.service;

import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.framework.MeteredCache;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.project.stream.utility.CachedDoubleStream2D;
import art.arcane.iris.util.project.stream.utility.CachedStream2D;
import art.arcane.iris.util.project.stream.utility.CachedStream3D;
import art.arcane.volmlib.util.format.Form;

import java.util.List;

final class IrisEngineStatus {
    private IrisEngineStatus() {
    }

    static void send(VolmitSender sender, Snapshot snapshot) {
        CacheSummary caches = summarizeCaches();
        MaintenanceMetrics metrics = snapshot.metrics();

        sender.sendMessage(C.DARK_PURPLE + "-------------------------");
        sender.sendMessage(C.DARK_PURPLE + "Status:");
        sender.sendMessage(C.DARK_PURPLE + "- Service: " + C.LIGHT_PURPLE + (snapshot.serviceRunning() ? "Running" : "Stopped"));
        sender.sendMessage(C.DARK_PURPLE + "- Metrics: " + C.LIGHT_PURPLE + (snapshot.metricsRunning() ? "Running" : "Stopped"));
        sender.sendMessage(C.DARK_PURPLE + "- Maintenance Period: " + C.LIGHT_PURPLE + Form.duration(snapshot.maintenancePeriodMillis()));
        sender.sendMessage(C.DARK_PURPLE + "- Worker Parallelism: " + C.LIGHT_PURPLE + snapshot.workerParallelism());
        sender.sendMessage(C.DARK_PURPLE + "- Active World Tasks: " + C.LIGHT_PURPLE + metrics.activeTasks());
        sender.sendMessage(C.DARK_PURPLE + "Tectonic Plates:");
        sender.sendMessage(C.DARK_PURPLE + "- Configured Retention: " + C.LIGHT_PURPLE + Form.duration(snapshot.retentionMillis()));
        sender.sendMessage(C.DARK_PURPLE + "- Heap Usage: " + C.LIGHT_PURPLE + Form.pc(snapshot.heapUsage()));
        sender.sendMessage(C.DARK_PURPLE + "- Resident: " + C.LIGHT_PURPLE + metrics.residentTectonicPlates());
        sender.sendMessage(C.DARK_PURPLE + "- Queued: " + C.LIGHT_PURPLE + metrics.queuedTectonicPlates());
        sender.sendMessage(C.DARK_PURPLE + "- Average Idle Duration: " + C.LIGHT_PURPLE + Form.duration(metrics.averageIdleDuration(), 2));
        sender.sendMessage(C.DARK_PURPLE + "- Max Idle Duration: " + C.LIGHT_PURPLE + Form.duration(metrics.maxIdleDuration(), 2));
        sender.sendMessage(C.DARK_PURPLE + "- Min Idle Duration: " + C.LIGHT_PURPLE + Form.duration(metrics.minIdleDuration(), 2));
        sender.sendMessage(C.DARK_PURPLE + "Caches:");
        sender.sendMessage(C.DARK_PURPLE + "- Resource: " + C.LIGHT_PURPLE + caches.sizes()[0] + " (" + caches.counts()[0] + ")");
        sender.sendMessage(C.DARK_PURPLE + "- 2D Stream: " + C.LIGHT_PURPLE + caches.sizes()[1] + " (" + caches.counts()[1] + ")");
        sender.sendMessage(C.DARK_PURPLE + "- 3D Stream: " + C.LIGHT_PURPLE + caches.sizes()[2] + " (" + caches.counts()[2] + ")");
        sender.sendMessage(C.DARK_PURPLE + "- Other: " + C.LIGHT_PURPLE + caches.sizes()[3] + " (" + caches.counts()[3] + ")");
        sender.sendMessage(C.DARK_PURPLE + "Other:");
        sender.sendMessage(C.DARK_PURPLE + "- Iris Worlds: " + C.LIGHT_PURPLE + metrics.worlds());
        sender.sendMessage(C.DARK_PURPLE + "- Loaded Chunks: " + C.LIGHT_PURPLE + metrics.loadedChunks());
        sender.sendMessage(C.DARK_PURPLE + "-------------------------");
    }

    private static CacheSummary summarizeCaches() {
        long[] sizes = new long[4];
        long[] counts = new long[4];
        PreservationSVC preservation = IrisServices.get(PreservationSVC.class);
        List<MeteredCache> caches = preservation == null ? List.of() : preservation.getCaches();

        for (MeteredCache cache : caches) {
            int type = switch (cache) {
                case ResourceLoader<?> ignored -> 0;
                case CachedStream2D<?> ignored -> 1;
                case CachedDoubleStream2D ignored -> 1;
                case CachedStream3D<?> ignored -> 2;
                default -> 3;
            };
            sizes[type] += cache.getSize();
            counts[type]++;
        }
        return new CacheSummary(sizes, counts);
    }

    record Snapshot(boolean serviceRunning,
                    boolean metricsRunning,
                    long maintenancePeriodMillis,
                    int workerParallelism,
                    long retentionMillis,
                    double heapUsage,
                    MaintenanceMetrics metrics) {
    }

    record MaintenanceMetrics(long residentTectonicPlates,
                              long queuedTectonicPlates,
                              long loadedChunks,
                              int worlds,
                              int activeTasks,
                              double averageIdleDuration,
                              double maxIdleDuration,
                              double minIdleDuration) {
        static final MaintenanceMetrics EMPTY = new MaintenanceMetrics(0L, 0L, 0L, 0, 0, 0D, 0D, 0D);
    }

    private record CacheSummary(long[] sizes, long[] counts) {
    }
}

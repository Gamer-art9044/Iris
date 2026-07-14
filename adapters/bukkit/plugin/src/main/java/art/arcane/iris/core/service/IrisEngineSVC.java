package art.arcane.iris.core.service;

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import com.google.common.util.concurrent.AtomicDouble;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.runtime.GoldenHashScanner;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.scheduling.Looper;
import art.arcane.iris.util.project.stream.utility.CachedDoubleStream2D;
import art.arcane.iris.util.project.stream.utility.CachedStream2D;
import art.arcane.iris.util.project.stream.utility.CachedStream3D;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.pregenerator.MantleHeapPressure;
import lombok.Synchronized;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class IrisEngineSVC implements IrisService {
    private static final int TRIM_PERIOD = 2_000;
    private final AtomicInteger tectonicLimit = new AtomicInteger(30);
    private final AtomicInteger tectonicPlates = new AtomicInteger();
    private final AtomicInteger queuedTectonicPlates = new AtomicInteger();
    private final AtomicInteger trimmerAlive = new AtomicInteger();
    private final AtomicInteger unloaderAlive = new AtomicInteger();
    private final AtomicInteger totalWorlds = new AtomicInteger();
    private final AtomicDouble maxIdleDuration = new AtomicDouble();
    private final AtomicDouble minIdleDuration = new AtomicDouble();
    private final AtomicLong loadedChunks = new AtomicLong();
    private final KMap<World, Registered> worlds = new KMap<>();
    private ScheduledExecutorService service;
    private Looper updateTicker;

    @Override
    public void onEnable() {
        var settings = IrisSettings.get().getPerformance();
        var engine = settings.getEngineSVC();
        service = Executors.newScheduledThreadPool(0,
                (engine.isUseVirtualThreads()
                        ? Thread.ofVirtual()
                        : Thread.ofPlatform().priority(engine.getPriority()))
                        .name("Iris EngineSVC-", 0)
                        .factory());
        tectonicLimit.set(settings.getTectonicPlateSize());
        Bukkit.getWorlds().forEach(this::add);
        setup();
    }

    @Override
    public void onDisable() {
        for (World world : worlds.keySet()) {
            PlatformChunkGenerator gen = IrisToolbelt.access(world);
            if (gen == null) continue;
            try {
                gen.close();
            } catch (Throwable t) {
                IrisLogging.reportError(t);
            }
        }
        if (service != null) {
            service.shutdown();
        }
        if (updateTicker != null) {
            updateTicker.interrupt();
        }
        worlds.keySet().forEach(this::remove);
        worlds.clear();
    }

    public int getQueuedTectonicPlateCount() {
        return queuedTectonicPlates.get();
    }

    public double getAverageIdleDuration() {
        double min = minIdleDuration.get();
        double max = maxIdleDuration.get();
        if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0D || max < 0D) {
            return 0D;
        }

        if (max < min) {
            return max;
        }

        return (min + max) / 2D;
    }

    public double getBiomeCacheUsageRatio() {
        PreservationSVC preservation = IrisServices.get(PreservationSVC.class);
        if (preservation == null) {
            return 0D;
        }

        double total = 0D;
        int count = 0;
        for (var cache : preservation.getCaches()) {
            if (!(cache instanceof CachedStream2D<?>) && !(cache instanceof CachedDoubleStream2D)) {
                continue;
            }

            double usage = cache.getUsage();
            if (!Double.isFinite(usage)) {
                continue;
            }

            total += Math.max(0D, Math.min(1D, usage));
            count++;
        }

        if (count <= 0) {
            return 0D;
        }

        return total / count;
    }

    public void engineStatus(VolmitSender sender) {
        long[] sizes = new long[4];
        long[] count = new long[4];

        for (var cache : IrisServices.get(PreservationSVC.class).getCaches()) {
            var type = switch (cache) {
                case ResourceLoader<?> ignored -> 0;
                case CachedStream2D<?> ignored -> 1;
                case CachedDoubleStream2D ignored -> 1;
                case CachedStream3D<?> ignored -> 2;
                default -> 3;
            };

            sizes[type] += cache.getSize();
            count[type]++;
        }

        sender.sendMessage(C.DARK_PURPLE + "-------------------------");
        sender.sendMessage(C.DARK_PURPLE + "Status:");
        sender.sendMessage(C.DARK_PURPLE + "- Service: " + C.LIGHT_PURPLE + (service.isShutdown() ? "Shutdown" : "Running"));
        sender.sendMessage(C.DARK_PURPLE + "- Updater: " + C.LIGHT_PURPLE + (updateTicker.isAlive() ? "Running" : "Stopped"));
        sender.sendMessage(C.DARK_PURPLE + "- Period: " + C.LIGHT_PURPLE + Form.duration(TRIM_PERIOD));
        sender.sendMessage(C.DARK_PURPLE + "- Trimmers: " + C.LIGHT_PURPLE + trimmerAlive.get());
        sender.sendMessage(C.DARK_PURPLE + "- Unloaders: " + C.LIGHT_PURPLE + unloaderAlive.get());
        sender.sendMessage(C.DARK_PURPLE + "Tectonic Plates:");
        sender.sendMessage(C.DARK_PURPLE + "- Limit: " + C.LIGHT_PURPLE + tectonicLimit.get());
        sender.sendMessage(C.DARK_PURPLE + "- Total: " + C.LIGHT_PURPLE + tectonicPlates.get());
        sender.sendMessage(C.DARK_PURPLE + "- Queued: " + C.LIGHT_PURPLE + queuedTectonicPlates.get());
        sender.sendMessage(C.DARK_PURPLE + "- Max Idle Duration: " + C.LIGHT_PURPLE + Form.duration(maxIdleDuration.get(), 2));
        sender.sendMessage(C.DARK_PURPLE + "- Min Idle Duration: " + C.LIGHT_PURPLE + Form.duration(minIdleDuration.get(), 2));
        sender.sendMessage(C.DARK_PURPLE + "Caches:");
        sender.sendMessage(C.DARK_PURPLE + "- Resource: " + C.LIGHT_PURPLE + sizes[0] + " (" + count[0] + ")");
        sender.sendMessage(C.DARK_PURPLE + "- 2D Stream: " + C.LIGHT_PURPLE + sizes[1] + " (" + count[1] + ")");
        sender.sendMessage(C.DARK_PURPLE + "- 3D Stream: " + C.LIGHT_PURPLE + sizes[2] + " (" + count[2] + ")");
        sender.sendMessage(C.DARK_PURPLE + "- Other: " + C.LIGHT_PURPLE + sizes[3] + " (" + count[3] + ")");
        sender.sendMessage(C.DARK_PURPLE + "Other:");
        sender.sendMessage(C.DARK_PURPLE + "- Iris Worlds: " + C.LIGHT_PURPLE + totalWorlds.get());
        sender.sendMessage(C.DARK_PURPLE + "- Loaded Chunks: " + C.LIGHT_PURPLE + loadedChunks.get());
        sender.sendMessage(C.DARK_PURPLE + "-------------------------");
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        remove(event.getWorld());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        add(event.getWorld());
    }

    private void remove(World world) {
        var entry = worlds.remove(world);
        if (entry == null) return;
        entry.close();
    }

    private void add(World world) {
        if (world == null || worlds.containsKey(world)) return;
        PlatformChunkGenerator access = IrisToolbelt.access(world);
        if (access == null) return;
        worlds.put(world, new Registered(world.getName(), access));
    }

    private synchronized void setup() {
        if (updateTicker != null && updateTicker.isAlive())
            return;

        updateTicker = new Looper() {
            @Override
            protected long loop() {
                try {
                    for (World world : Bukkit.getWorlds()) {
                        add(world);
                    }

                    int queuedPlates = 0;
                    int totalPlates = 0;
                    long chunks = 0;
                    int unloaders = 0;
                    int trimmers = 0;
                    int iris = 0;

                    double maxDuration = Long.MIN_VALUE;
                    double minDuration = Long.MAX_VALUE;
                    for (var entry : worlds.entrySet()) {
                        var registered = entry.getValue();
                        if (registered.closed) continue;

                        iris++;
                        if (registered.unloaderAlive()) unloaders++;
                        if (registered.trimmerAlive()) trimmers++;

                        var engine = registered.getEngine();
                        if (engine == null) continue;

                        queuedPlates += engine.getMantle().getUnloadRegionCount();
                        totalPlates += engine.getMantle().getLoadedRegionCount();
                        chunks += entry.getKey().getLoadedChunks().length;

                        double duration = engine.getMantle().getAdjustedIdleDuration();
                        if (duration > maxDuration) maxDuration = duration;
                        if (duration < minDuration) minDuration = duration;
                    }

                    trimmerAlive.set(trimmers);
                    unloaderAlive.set(unloaders);
                    tectonicPlates.set(totalPlates);
                    queuedTectonicPlates.set(queuedPlates);
                    maxIdleDuration.set(maxDuration);
                    minIdleDuration.set(minDuration);
                    loadedChunks.set(chunks);
                    totalWorlds.set(iris);

                    worlds.values().forEach(Registered::update);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                return 1000;
            }
        };
        updateTicker.start();
    }

    private static boolean isMantleClosed(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("mantle is closed")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    static boolean shouldSkipMantleReductionForMaintenance(boolean maintenanceActive, boolean pregeneratorTargetsWorld) {
        return maintenanceActive && !pregeneratorTargetsWorld;
    }

    private final class Registered {
        private final String name;
        private final PlatformChunkGenerator access;
        private final int offset = RNG.r.nextInt(TRIM_PERIOD);
        private transient ScheduledFuture<?> trimmer;
        private transient ScheduledFuture<?> unloader;
        private transient boolean closed;

        private Registered(String name, PlatformChunkGenerator access) {
            this.name = name;
            this.access = access;
            update();
        }

        private boolean unloaderAlive() {
            return unloader != null && !unloader.isDone() && !unloader.isCancelled();
        }

        private boolean trimmerAlive() {
            return trimmer != null && !trimmer.isDone() && !trimmer.isCancelled();
        }

        @Synchronized
        private void update() {
            if (closed || service == null || service.isShutdown())
                return;

            if (trimmer == null || trimmer.isDone() || trimmer.isCancelled()) {
                trimmer = service.scheduleAtFixedRate(() -> {
                    Engine engine = getEngine();
                    if (engine == null
                            || engine.isClosed()
                            || engine.getMantle().getMantle().isClosed()
                            || !shouldReduce(engine))
                        return;
                    World engineWorld = BukkitWorldBinding.world(engine.getWorld());
                    if (shouldSkipForMaintenance(engineWorld)) {
                        return;
                    }
                    if (GoldenHashScanner.isScanActive()) {
                        return;
                    }

                    try {
                        if (pregenTargets(engine) && MantleHeapPressure.overHighWater()) {
                            engine.getMantle().trim(0L, 0);
                        } else {
                            engine.getMantle().trim(activeIdleDuration(engine), activeTectonicLimit(engine));
                        }
                    } catch (Throwable e) {
                        if (isMantleClosed(e)) {
                            close();
                            return;
                        }
                        IrisLogging.reportError(e);
                        IrisLogging.error("EngineSVC: Failed to trim for " + name);
                        e.printStackTrace();
                    }
                }, offset, TRIM_PERIOD, TimeUnit.MILLISECONDS);
            }

            if (unloader == null || unloader.isDone() || unloader.isCancelled()) {
                unloader = service.scheduleAtFixedRate(() -> {
                    Engine engine = getEngine();
                    if (engine == null
                            || engine.isClosed()
                            || engine.getMantle().getMantle().isClosed()
                            || !shouldReduce(engine))
                        return;
                    World engineWorld = BukkitWorldBinding.world(engine.getWorld());
                    if (shouldSkipForMaintenance(engineWorld)) {
                        return;
                    }

                    if (GoldenHashScanner.isScanActive()) {
                        return;
                    }

                    try {
                        long unloadStart = System.currentTimeMillis();
                        boolean heapPressure = pregenTargets(engine) && MantleHeapPressure.overHighWater();
                        int unloadLimit = (heapPressure || IrisSettings.get().getPerformance().getEngineSVC().forceMulticoreWrite) ? 0 : activeTectonicLimit(engine);
                        int count = engine.getMantle().unloadTectonicPlate(unloadLimit);
                        if (heapPressure && MantleHeapPressure.overPanicWater()) {
                            MantleHeapPressure.requestPanicReclaim();
                        }
                        if (count > 0) {
                            IrisLogging.debug(C.GOLD + "Unloaded " + C.YELLOW + count + " TectonicPlates in " + C.RED + Form.duration(System.currentTimeMillis() - unloadStart, 2));
                        }
                    } catch (Throwable e) {
                        if (isMantleClosed(e)) {
                            close();
                            return;
                        }
                        IrisLogging.reportError(e);
                        IrisLogging.error("EngineSVC: Failed to unload for " + name);
                        e.printStackTrace();
                    }
                }, offset + TRIM_PERIOD / 2, TRIM_PERIOD, TimeUnit.MILLISECONDS);
            }
        }

        private int tectonicLimit() {
            return tectonicLimit.get() / Math.max(worlds.size(), 1);
        }

        private boolean pregenTargets(Engine engine) {
            if (engine == null || engine.getWorld() == null) {
                return false;
            }

            PregeneratorJob pregeneratorJob = PregeneratorJob.getInstance();
            return pregeneratorJob != null && pregeneratorJob.targetsWorldIdentity(engine.getWorld().identity());
        }

        private int activeTectonicLimit(Engine engine) {
            int limit = tectonicLimit();
            if (!pregenTargets(engine)) {
                return limit;
            }

            return Math.max(limit, IrisSettings.get().getPregen().getEffectiveResidentTectonicPlates(engine.getHeight()));
        }

        private long activeIdleDuration(Engine engine) {
            return TimeUnit.SECONDS.toMillis(IrisSettings.get().getPerformance().getMantleKeepAlive());
        }

        @Synchronized
        private void close() {
            if (closed) return;
            closed = true;

            if (trimmer != null) {
                trimmer.cancel(false);
                trimmer = null;
            }

            if (unloader != null) {
                unloader.cancel(false);
                unloader = null;
            }
        }

        @Nullable
        private Engine getEngine() {
            if (closed) return null;
            return access.getEngine();
        }

        private boolean shouldReduce(Engine engine) {
            if (!engine.isStudio() || IrisSettings.get().getPerformance().isTrimMantleInStudio()) {
                return true;
            }

            return pregenTargets(engine);
        }

        private boolean shouldSkipForMaintenance(@Nullable World world) {
            if (world == null) {
                return false;
            }

            boolean maintenanceActive = IrisToolbelt.isWorldMaintenanceActive(world);
            if (!maintenanceActive) {
                return false;
            }

            PregeneratorJob pregeneratorJob = PregeneratorJob.getInstance();
            boolean pregeneratorTargetsWorld = pregeneratorJob != null && pregeneratorJob.targetsWorldIdentity(WorldIdentity.serialize(world));
            return shouldSkipMantleReductionForMaintenance(maintenanceActive, pregeneratorTargetsWorld);
        }
    }
}

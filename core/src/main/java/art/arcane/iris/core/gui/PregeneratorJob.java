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

package art.arcane.iris.core.gui;

import art.arcane.iris.core.localization.DesktopUiMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.protocol.IrisMessage;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.protocol.IrisProtocolServer;
import art.arcane.iris.core.pregenerator.IrisPregenerator;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.format.MemoryMonitor;
import art.arcane.volmlib.util.function.Consumer2;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;

import java.awt.Color;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class PregeneratorJob implements PregenListener, PregenRenderSource {
    private static final long WORLD_SHUTDOWN_TIMEOUT_MILLIS = 15_000L;
    private static final Color COLOR_EXISTS = parseColor("#4d7d5b");
    private static final Color COLOR_BLACK = parseColor("#4d7d5b");
    private static final Color COLOR_MANTLE = parseColor("#3c2773");
    private static final Color COLOR_GENERATING = parseColor("#66967f");
    private static final Color COLOR_NETWORK = parseColor("#a863c2");
    private static final Color COLOR_NETWORK_GENERATING = parseColor("#836b8c");
    private static final Color COLOR_GENERATED = parseColor("#65c295");
    private static final Color COLOR_CLEANED = parseColor("#34eb93");
    private static final AtomicReference<PregeneratorJob> instance = new AtomicReference<>();
    private final MemoryMonitor monitor;
    private final PregenTask task;
    private final boolean saving;
    private final KList<Consumer<Double>> onProgress = new KList<>();
    private final KList<Runnable> whenDone = new KList<>();
    private final IrisPregenerator pregenerator;
    private final Position2 min;
    private final Position2 max;
    private final ChronoLatch cl = new ChronoLatch(TimeUnit.MINUTES.toMillis(1));
    private final Engine engine;
    private final ExecutorService service;
    private final Thread worker;
    private PregenRenderer renderer;
    private Consumer2<Position2, Color> drawFunction;
    private int rgc = 0;
    private String[] info;
    private volatile double lastChunksPerSecond = 0D;
    private volatile long lastChunksRemaining = 0L;
    private volatile long lastGenerated = 0L;
    private volatile long lastTotalChunks = 0L;
    private volatile long lastEta = 0L;
    private volatile long lastElapsed = 0L;
    private volatile String lastMethod = IrisLanguage.plain(DesktopUiMessages.PREGEN_METHOD_PENDING);

    public PregeneratorJob(PregenTask task, PregeneratorMethod method, Engine engine) {
        instance.updateAndGet(old -> {
            if (old != null) {
                old.pregenerator.close();
                old.close();
            }
            return this;
        });
        this.engine = engine;
        monitor = new MemoryMonitor(50);
        saving = false;
        info = new String[]{IrisLanguage.plain(DesktopUiMessages.PREGEN_INITIALIZING)};
        this.task = task;
        this.pregenerator = new IrisPregenerator(task, method, this);
        max = new Position2(Integer.MIN_VALUE, Integer.MIN_VALUE);
        min = new Position2(Integer.MAX_VALUE, Integer.MAX_VALUE);
        service = Executors.newVirtualThreadPerTaskExecutor();

        if (IrisSettings.get().getGui().isUseServerLaunchedGuis() && task.isGui()) {
            open();
        }

        worker = new Thread(() -> {
            J.sleep(1000);
            computeBounds();
            this.pregenerator.start();
        }, "Iris Pregenerator");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.setDaemon(true);
        worker.setUncaughtExceptionHandler((thread, ex) -> IrisLogging.reportError(ex));
        worker.start();
    }

    private void computeBounds() {
        task.iterateAllChunks((xx, zz) -> {
            min.setX(Math.min(xx, min.getX()));
            min.setZ(Math.min(zz, min.getZ()));
            max.setX(Math.max(xx, max.getX()));
            max.setZ(Math.max(zz, max.getZ()));
        });
    }

    public static boolean shutdownInstance() {
        PregeneratorJob inst = instance.get();
        if (inst == null) {
            return false;
        }

        J.a(inst.pregenerator::close);
        return true;
    }

    public static boolean shutdownInstanceForWorld(String worldIdentity) {
        PregeneratorJob inst = instance.get();
        if (inst == null || !inst.targetsWorldIdentity(worldIdentity)) {
            return false;
        }

        return shutdownAndWait(inst, WORLD_SHUTDOWN_TIMEOUT_MILLIS);
    }

    public static boolean shutdownAndWait(long timeoutMs) {
        PregeneratorJob inst = instance.get();
        if (inst == null) {
            return false;
        }

        return shutdownAndWait(inst, timeoutMs);
    }

    private static boolean shutdownAndWait(PregeneratorJob inst, long timeoutMs) {
        inst.pregenerator.close();
        inst.worker.interrupt();
        try {
            inst.worker.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping the Iris pregenerator.", e);
        }
        if (inst.worker.isAlive()) {
            throw new IllegalStateException("Timed out while stopping the Iris pregenerator after "
                    + Math.max(1L, timeoutMs) + "ms.");
        }
        instance.compareAndSet(inst, null);
        return true;
    }

    public static PregeneratorJob getInstance() {
        return instance.get();
    }

    public static boolean pauseResume() {
        PregeneratorJob inst = instance.get();
        if (inst == null) {
            return false;
        }

        if (isPaused()) {
            inst.pregenerator.resume();
        } else {
            inst.pregenerator.pause();
        }
        return true;
    }

    public static boolean isPaused() {
        PregeneratorJob inst = instance.get();
        if (inst == null) {
            return true;
        }

        return inst.paused();
    }

    public static double chunksPerSecond() {
        PregeneratorJob inst = instance.get();
        return inst == null ? 0D : Math.max(0D, inst.lastChunksPerSecond);
    }

    public static long chunksRemaining() {
        PregeneratorJob inst = instance.get();
        return inst == null ? -1L : Math.max(0L, inst.lastChunksRemaining);
    }

    public record PregenProgress(double percent, long generated, long totalChunks, double chunksPerSecond, long chunksRemaining, long eta, long elapsed, String method, boolean paused, long failed, String worldName, String worldIdentity) {
    }

    public static PregenProgress progressSnapshot() {
        PregeneratorJob inst = instance.get();
        if (inst == null) {
            return null;
        }

        double percent = inst.lastTotalChunks <= 0 ? 0D : ((double) inst.lastGenerated / (double) inst.lastTotalChunks) * 100D;
        return new PregenProgress(
                percent,
                inst.lastGenerated,
                inst.lastTotalChunks,
                Math.max(0D, inst.lastChunksPerSecond),
                Math.max(0L, inst.lastChunksRemaining),
                inst.lastEta,
                inst.lastElapsed,
                inst.lastMethod,
                inst.paused(),
                inst.pregenerator.getFailedChunks(),
                inst.worldName(),
                inst.worldIdentity());
    }

    public String worldName() {
        if (engine == null || engine.getWorld() == null) {
            return null;
        }

        return engine.getWorld().name();
    }

    public String worldIdentity() {
        if (engine == null || engine.getWorld() == null) {
            return null;
        }
        return engine.getWorld().identity();
    }

    public boolean targetsWorldIdentity(String worldIdentity) {
        if (worldIdentity == null || engine == null || engine.getWorld() == null) {
            return false;
        }

        return worldIdentity.equals(engine.getWorld().identity());
    }

    private static Color parseColor(String c) {
        String v = (c.startsWith("#") ? c : "#" + c).trim();
        try {
            return Color.decode(v);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            IrisLogging.error("Error Parsing 'color', (" + c + ")");
        }

        return Color.RED;
    }

    public Mantle getMantle() {
        return pregenerator.getMantle();
    }

    public PregeneratorJob onProgress(Consumer<Double> c) {
        onProgress.add(c);
        return this;
    }

    public PregeneratorJob whenDone(Runnable r) {
        whenDone.add(r);
        return this;
    }

    public void drawRegion(int x, int z, Color color) {
        J.a(() -> task.iterateChunks(x, z, (xx, zz) -> {
            draw(xx, zz, color);
            J.sleep(3);
        }));
    }

    public void draw(int x, int z, Color color) {
        try {
            if (renderer != null && drawFunction != null && renderer.isVisibleFrame()) {
                drawFunction.accept(new Position2(x, z), color);
            }
        } catch (Throwable ignored) {
            IrisLogging.error("Failed to draw pregen");
        }
    }

    public void stop() {
        J.a(() -> {
            pregenerator.close();
            close();
            instance.compareAndSet(this, null);
        });
    }

    public void close() {
        J.a(() -> {
            try {
                monitor.close();
                if (renderer == null) {
                    return;
                }
                J.sleep(3000);
                renderer.close();
            } catch (Throwable ignored) {
                IrisLogging.error("Error closing pregen gui");
            }
        });
    }

    public void open() {
        J.a(() -> {
            try {
                renderer = PregenRenderer.open(IrisLanguage.plain(DesktopUiMessages.PREGEN_TITLE), this, PregeneratorJob::pauseResume);
                drawFunction = renderer.drawFunction();
            } catch (Throwable ignored) {
                IrisLogging.error("Error opening pregen gui");
            }
        });
    }

    @Override
    public void onTick(double chunksPerSecond, double chunksPerMinute, double regionsPerMinute, double percent, long generated, long totalChunks, long chunksRemaining, long eta, long elapsed, String method, boolean cached) {
        lastChunksPerSecond = chunksPerSecond;
        lastChunksRemaining = chunksRemaining;
        lastGenerated = generated;
        lastTotalChunks = totalChunks;
        lastEta = eta;
        lastElapsed = elapsed;
        lastMethod = method;

        info = new String[]{
                IrisLanguage.plain(
                        paused() ? DesktopUiMessages.PREGEN_PROGRESS_PAUSED
                                : saving ? DesktopUiMessages.PREGEN_PROGRESS_SAVING : DesktopUiMessages.PREGEN_PROGRESS_GENERATING,
                        MessageArgument.trusted("generated", Form.f(generated)),
                        MessageArgument.trusted("total", Form.f(totalChunks)),
                        MessageArgument.trusted("percent", Form.pc(percent, 0))
                ),
                IrisLanguage.plain(
                        cached ? DesktopUiMessages.PREGEN_SPEED_CACHED : DesktopUiMessages.PREGEN_SPEED,
                        MessageArgument.trusted("chunksPerSecond", Form.f(chunksPerSecond, 0)),
                        MessageArgument.trusted("regionsPerMinute", Form.f(regionsPerMinute, 1)),
                        MessageArgument.trusted("chunksPerMinute", Form.f(chunksPerMinute, 0))
                ),
                IrisLanguage.plain(
                        DesktopUiMessages.PREGEN_TIME,
                        MessageArgument.trusted("remaining", Form.duration(eta, 2)),
                        MessageArgument.trusted("elapsed", Form.duration(elapsed, 2))
                ),
                IrisLanguage.plain(DesktopUiMessages.PREGEN_METHOD, MessageArgument.untrusted("method", String.valueOf(method))),
                IrisLanguage.plain(
                        DesktopUiMessages.PREGEN_MEMORY,
                        MessageArgument.trusted("used", Form.memSize(monitor.getUsedBytes(), 2)),
                        MessageArgument.trusted("usage", Form.pc(monitor.getUsagePercent(), 0)),
                        MessageArgument.trusted("pressure", Form.memSize(monitor.getPressure(), 0))
                )
        };

        for (Consumer<Double> i : onProgress) {
            i.accept(percent);
        }
    }

    @Override
    public void onChunkGenerating(int x, int z) {
        draw(x, z, COLOR_GENERATING);
    }

    @Override
    public void onChunkGenerated(int x, int z, boolean cached) {
        if (renderer == null || !renderer.isVisibleFrame()) return;
        service.submit(() -> {
            if (engine != null) {
                draw(x, z, engine.draw((x << 4) + 8, (z << 4) + 8));
                return;
            }

            draw(x, z, COLOR_GENERATED);
        });
    }

    @Override
    public void onRegionGenerated(int x, int z) {
        shouldGc();
        rgc++;
        broadcastRegionDelta(x, z, IrisMessage.PregenRegionDelta.STATE_DONE);
    }

    private void shouldGc() {
        if (cl.flip() && rgc > 16) {
            System.gc();
        }
    }

    private void broadcastRegionDelta(int regionX, int regionZ, int state) {
        IrisProtocolServer protocolServer = IrisServices.getOrNull(IrisProtocolServer.class);
        if (protocolServer == null) {
            return;
        }
        protocolServer.broadcastPregenRegionDelta(pregenerator.getJobId(), regionX, regionZ, state);
    }

    @Override
    public void onRegionGenerating(int x, int z) {
        broadcastRegionDelta(x, z, IrisMessage.PregenRegionDelta.STATE_GENERATING);
    }

    @Override
    public void onChunkCleaned(int x, int z) {
        //draw(x, z, COLOR_CLEANED);
    }

    @Override
    public void onRegionSkipped(int x, int z) {

    }

    @Override
    public void onNetworkStarted(int x, int z) {
        drawRegion(x, z, COLOR_NETWORK);
    }

    @Override
    public void onNetworkFailed(int x, int z) {

    }

    @Override
    public void onNetworkReclaim(int revert) {

    }

    @Override
    public void onNetworkGeneratedChunk(int x, int z) {
        draw(x, z, COLOR_NETWORK_GENERATING);
    }

    @Override
    public void onNetworkDownloaded(int x, int z) {
        drawRegion(x, z, COLOR_NETWORK);
    }

    @Override
    public void onClose() {
        close();
        instance.compareAndSet(this, null);
        whenDone.forEach(Runnable::run);
        service.shutdownNow();
    }

    @Override
    public void onSaving() {

    }

    @Override
    public void onChunkExistsInRegionGen(int x, int z) {
        if (engine != null) {
            draw(x, z, engine.draw((x << 4) + 8, (z << 4) + 8));
            return;
        }

        draw(x, z, COLOR_EXISTS);
    }

    @Override
    public Position2 max() {
        return max;
    }

    @Override
    public Position2 min() {
        return min;
    }

    @Override
    public boolean paused() {
        return pregenerator.paused();
    }

    @Override
    public String[] progress() {
        return info;
    }
}

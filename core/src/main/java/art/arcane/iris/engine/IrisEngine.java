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

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineEffects;
import art.arcane.iris.engine.framework.EngineEffectsProvider;
import art.arcane.iris.engine.framework.EngineMetrics;
import art.arcane.iris.engine.framework.EngineMode;
import art.arcane.iris.engine.framework.EnginePlatformHooks;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.framework.EngineWorldManager;
import art.arcane.iris.engine.framework.EngineWorldManagerProvider;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.framework.GenerationSessionLease;
import art.arcane.iris.engine.framework.GenerationSessionManager;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.framework.SeedManager;
import art.arcane.iris.engine.framework.StructureReachability;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomePaletteLayer;
import art.arcane.iris.engine.object.IrisDecorator;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionMode;
import art.arcane.iris.engine.object.IrisDimensionModeType;
import art.arcane.iris.engine.object.IrisEngineData;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.spi.IrisLogging;
import com.google.common.util.concurrent.AtomicDouble;
import com.google.gson.Gson;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.protocol.IrisMessage;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.protocol.IrisProtocolServer;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.nms.container.BlockPos;
import art.arcane.iris.core.nms.container.Pair;
import art.arcane.iris.core.structure.StructureIndexService;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.atomics.AtomicRollingSequence;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.documentation.BlockCoordinates;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.MatterStructurePOI;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class IrisEngine implements Engine {
    private final AtomicInteger bud;
    private final AtomicInteger buds;
    private final AtomicInteger generated;
    private final AtomicInteger generatedLast;
    private final AtomicDouble perSecond;
    private final AtomicLong lastGPS;
    private final EngineTarget target;
    private final EngineMantle mantle;
    private final ChronoLatch perSecondLatch;
    private final ChronoLatch perSecondBudLatch;
    private final EngineMetrics metrics;
    private final boolean studio;
    private final AtomicRollingSequence wallClock;
    private final int art;
    private final AtomicCache<IrisEngineData> engineData = new AtomicCache<>();
    private final AtomicBoolean cleaning;
    private final ChronoLatch cleanLatch;
    private final SeedManager seedManager;
    private final GenerationSessionManager generationSessions;
    private final EnginePlatformHooks platformHooks;
    private final AtomicBoolean closing;
    private CompletableFuture<Long> hash32;
    private EngineMode mode;
    private EngineEffects effects;
    private EngineWorldManager worldManager;
    private volatile int parallelism;
    private boolean failing;
    private boolean closed;
    private int cacheId;
    private double maxBiomeObjectDensity;
    private double maxBiomeLayerDensity;
    private double maxBiomeDecoratorDensity;
    private IrisComplex complex;
    private UpperDimensionContext upperContext;
    private final AtomicBoolean modeFallbackLogged;
    private final AtomicBoolean prefetchSaveStarted;

    public IrisEngine(EngineTarget target, boolean studio) {
        this.studio = studio;
        this.target = target;
        this.platformHooks = IrisServices.get(EnginePlatformHooks.class);
        getEngineData();
        verifySeed();
        this.seedManager = new SeedManager(target.getWorld().getRawWorldSeed());
        this.generationSessions = new GenerationSessionManager();
        this.closing = new AtomicBoolean(false);
        bud = new AtomicInteger(0);
        buds = new AtomicInteger(0);
        metrics = new EngineMetrics(32);
        cleanLatch = new ChronoLatch(10000);
        generatedLast = new AtomicInteger(0);
        perSecond = new AtomicDouble(0);
        perSecondLatch = new ChronoLatch(1000, false);
        perSecondBudLatch = new ChronoLatch(1000, false);
        wallClock = new AtomicRollingSequence(32);
        lastGPS = new AtomicLong(M.ms());
        generated = new AtomicInteger(0);
        long _t0 = M.ms();
        mantle = new IrisEngineMantle(this);
        IrisLogging.debug("[IrisEngine timing] new IrisEngineMantle=" + (M.ms() - _t0) + "ms");
        cleaning = new AtomicBoolean(false);
        modeFallbackLogged = new AtomicBoolean(false);
        prefetchSaveStarted = new AtomicBoolean(false);
        if (studio) {
            _t0 = M.ms();
            getData().dump();
            getData().clearLists();
            getTarget().setDimension(getData().getDimensionLoader().load(getDimension().getLoadKey()));
            IrisLogging.debug("[IrisEngine timing] dump+clearLists+reload=" + (M.ms() - _t0) + "ms");
        }
        getData().setEngine(this);
        _t0 = M.ms();
        getData().loadPrefetch(this);
        IrisLogging.debug("[IrisEngine timing] loadPrefetch=" + (M.ms() - _t0) + "ms");
        try {
            StructureIndexService.writeOnce(getData());
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
        IrisLogging.info("Engine init: " + target.getWorld().name() + "/" + target.getDimension().getLoadKey() + " seed=" + getSeedManager().getSeed());
        failing = false;
        closed = false;
        art = J.ar(this::tickRandomPlayer, 0);
        _t0 = M.ms();
        setupEngine();
        IrisLogging.debug("[IrisEngine timing] setupEngine total=" + (M.ms() - _t0) + "ms");
        _t0 = M.ms();
        GenerationCacheWarmer.warm(this);
        IrisLogging.debug("[IrisEngine timing] cache warm total=" + (M.ms() - _t0) + "ms");
        IrisLogging.debug("Engine Initialized " + getCacheID());
    }

    private void verifySeed() {
        if (getEngineData().getSeed() != null && getEngineData().getSeed() != target.getWorld().getRawWorldSeed()) {
            target.getWorld().setRawWorldSeed(getEngineData().getSeed());
        }
    }

    private void tickRandomPlayer() {
        recycle();
        if (perSecondBudLatch.flip()) {
            buds.set(bud.get());
            bud.set(0);
        }

        if (effects != null) {
            effects.tickRandomPlayer();
        }
    }

    private void prehotload() {
        closing.set(true);
        try {
            generationSessions.sealAndAwait("hotload", 15000L);
        } catch (GenerationSessionException e) {
            throw new IllegalStateException(e);
        }

        EngineWorldManager currentWorldManager = worldManager;
        worldManager = null;
        if (currentWorldManager != null) {
            currentWorldManager.close();
        }

        IrisComplex currentComplex = complex;
        complex = null;
        if (currentComplex != null) {
            currentComplex.close();
        }

        EngineEffects currentEffects = effects;
        effects = null;
        if (currentEffects != null) {
            currentEffects.close();
        }

        EngineMode currentMode = mode;
        mode = null;
        if (currentMode != null) {
            currentMode.close();
        }

        if (getWorld().hasPlatformWorld()) {
            J.a(() -> platformHooks.refreshWorkspace(this));
        }
    }

    private void setupEngine() {
        try {
            IrisStructureLocator.invalidate(this);
            StructureReachability.invalidate(this);
            generationSessions.activateNextSession();
            closing.set(false);
            IrisLogging.debug("Setup Engine " + getCacheID());
            cacheId = RNG.r.nextInt();
            long t0 = M.ms();
            complex = ensureComplex();
            IrisLogging.debug("[IrisEngine timing] ensureComplex=" + (M.ms() - t0) + "ms");
            t0 = M.ms();
            upperContext = buildUpperContext();
            IrisLogging.debug("[IrisEngine timing] buildUpperContext=" + (M.ms() - t0) + "ms");
            t0 = M.ms();
            effects = IrisServices.get(EngineEffectsProvider.class).create(this);
            IrisLogging.debug("[IrisEngine timing] EngineEffects=" + (M.ms() - t0) + "ms");
            hash32 = new CompletableFuture<>();
            t0 = M.ms();
            mantle.hotload();
            IrisLogging.debug("[IrisEngine timing] mantle.hotload=" + (M.ms() - t0) + "ms");
            t0 = M.ms();
            setupMode();
            IrisLogging.debug("[IrisEngine timing] setupMode=" + (M.ms() - t0) + "ms");
            t0 = M.ms();
            EngineWorldManager manager = IrisServices.get(EngineWorldManagerProvider.class).create(this);
            worldManager = manager;
            IrisLogging.debug("[IrisEngine timing] IrisWorldManager=" + (M.ms() - t0) + "ms");
            J.a(this::computeBiomeMaxes);
            J.a(() -> {
                File[] roots = getData().getLoaders()
                        .values()
                        .stream()
                        .map(ResourceLoader::getFolderName)
                        .map(n -> new File(getData().getDataFolder(), n))
                        .filter(File::exists)
                        .filter(File::isDirectory)
                        .toArray(File[]::new);
                hash32.complete(IO.hashRecursiveMeta(roots));
            });
            J.a(() -> platformHooks.refreshDatapackWorkspace(this));
        } catch (Throwable e) {
            IrisLogging.error("FAILED TO SETUP ENGINE!");
            IrisLogging.reportError(e);
            e.printStackTrace();
        }

        IrisLogging.debug("Engine Setup Complete " + getCacheID());
    }

    private UpperDimensionContext buildUpperContext() {
        IrisDimension dim = getDimension();
        if (!dim.hasUpperDimension()) {
            return null;
        }
        String upperKey = dim.getUpperDimension();
        IrisDimension upperDim = upperKey.equals(dim.getLoadKey())
                ? dim
                : IrisData.loadAnyDimension(upperKey, getData());
        if (upperDim != null) {
            UpperDimensionContext ctx = UpperDimensionContext.create(this, upperDim);
            IrisLogging.info("Upper dimension enabled: " + upperKey
                    + (ctx.isSelfReferencing() ? " (self-referencing)" : " (cross-referencing)"));
            return ctx;
        }
        IrisLogging.warn("Upper dimension '" + upperKey + "' could not be resolved, skipping upper terrain.");
        return null;
    }

    private void setupMode() {
        EngineMode currentMode = mode;
        if (currentMode != null) {
            currentMode.close();
        }

        mode = null;
        mode = ensureMode();
    }

    private EngineMode ensureMode() {
        EngineMode currentMode = mode;
        if (currentMode != null) {
            return currentMode;
        }

        synchronized (this) {
            currentMode = mode;
            if (currentMode != null) {
                return currentMode;
            }

            try {
                IrisComplex readyComplex = ensureComplex();
                if (readyComplex == null) {
                    throw new IllegalStateException("Iris complex is unavailable");
                }

                IrisDimensionMode configuredMode = getDimension().getMode();
                if (configuredMode == null) {
                    configuredMode = new IrisDimensionMode();
                    getDimension().setMode(configuredMode);
                }

                currentMode = configuredMode.create(this);
                if (currentMode == null) {
                    throw new IllegalStateException("Dimension mode factory returned null");
                }
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                if (modeFallbackLogged.compareAndSet(false, true)) {
                    IrisLogging.warn("Failed to initialize configured dimension mode for " + getDimension().getLoadKey() + ", falling back to OVERWORLD mode.");
                }
                currentMode = IrisDimensionModeType.OVERWORLD.create(this);
            }

            mode = currentMode;
            return currentMode;
        }
    }

    private IrisComplex ensureComplex() {
        IrisComplex currentComplex = complex;
        if (currentComplex != null) {
            return currentComplex;
        }

        if (closed) {
            return null;
        }

        synchronized (this) {
            currentComplex = complex;
            if (currentComplex != null) {
                return currentComplex;
            }

            currentComplex = new IrisComplex(this);
            complex = currentComplex;
            return currentComplex;
        }
    }

    @Override
    public void generateMatter(int x, int z, boolean multicore, ChunkContext context) {
        getMantle().generateMatter(x, z, multicore, context);
    }

    @Override
    public Set<String> getObjectsAt(int x, int z) {
        return getMantle().getObjectComponent().guess(x, z);
    }

    @Override
    public Set<Pair<String, BlockPos>> getPOIsAt(int chunkX, int chunkY) {
        Set<Pair<String, BlockPos>> pois = new HashSet<>();
        getMantle().getMantle().iterateChunk(chunkX, chunkY, MatterStructurePOI.class, (x, y, z, d) -> pois.add(new Pair<>(d.getType(), new BlockPos(x, y, z))));
        return pois;
    }

    private void warmupChunk(int x, int z) {
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int xx = x + (i << 4);
                int zz = z + (z << 4);
                getComplex().getTrueBiomeStream().get(xx, zz);
                getComplex().getHeightStream().get(xx, zz);
            }
        }
    }

    @Override
    public void hotload() {
        hotloadSilently();
        platformHooks.fireHotloadEvent(this);
    }

    public void hotloadComplex() {
        complex.close();
        complex = new IrisComplex(this);
        upperContext = buildUpperContext();
    }

    public void hotloadSilently() {
        try {
            getData().dump();
            getData().clearLists();
            IrisDimension replacement = getData().getDimensionLoader().load(getDimension().getLoadKey());
            if (replacement == null) {
                throw new IllegalStateException("Studio hotload could not reload Iris dimension '" + getDimension().getLoadKey() + "'");
            }
            platformHooks.validateDimensionHotload(this, replacement);
            getTarget().setDimension(replacement);
            prehotload();
            setupEngine();
            if (getWorld().hasPlatformWorld()) {
                J.a(() -> platformHooks.reloadDatapacks(this));
            }
            broadcastStudioHotload(false, "");
        } catch (RuntimeException | Error e) {
            broadcastStudioHotload(true, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private void broadcastStudioHotload(boolean failed, String message) {
        IrisProtocolServer protocolServer = IrisServices.getOrNull(IrisProtocolServer.class);
        if (protocolServer == null) {
            return;
        }
        IrisDimension dimension = getDimension();
        String packKey = dimension == null ? "" : dimension.getLoadKey();
        protocolServer.broadcastStudioHotload(packKey, 0, failed, message);
        protocolServer.broadcastToast(
                failed ? IrisMessage.Toast.KIND_ERROR : IrisMessage.Toast.KIND_SUCCESS,
                "Studio Hotload",
                failed ? packKey + " failed" : packKey);
    }

    @Override
    public IrisEngineData getEngineData() {
        return engineData.aquire(() -> {
            //TODO: Method this file
            File f = new File(getWorld().worldFolder(), "iris/engine-data/" + getDimension().getLoadKey() + ".json");
            IrisEngineData data = null;

            if (f.exists()) {
                try {
                    data = new Gson().fromJson(IO.readAll(f), IrisEngineData.class);
                    if (data == null) {
                        IrisLogging.error("Failed to read Engine Data! Corrupted File? recreating...");
                    }
                } catch (IOException e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                }
            }

            if (data == null) {
                data = new IrisEngineData();
                data.getStatistics().setVersion(IrisPlatforms.get().irisVersionNumber());
                data.getStatistics().setMCVersion(IrisPlatforms.get().minecraftVersionNumber());
                data.getStatistics().setUpgradedVersion(IrisPlatforms.get().irisVersionNumber());
                if (data.getStatistics().getVersion() == -1 || data.getStatistics().getMCVersion() == -1 ) {
                    IrisLogging.error("Failed to setup Engine Data!");
                }

                if (f.getParentFile().exists() || f.getParentFile().mkdirs()) {
                    try {
                        IO.writeAll(f, new Gson().toJson(data));
                    } catch (IOException e) {
                        IrisLogging.reportError(e);
                        e.printStackTrace();
                    }
                } else {
                    IrisLogging.error("Failed to setup Engine Data!");
                }
            }

            return data;
        });
    }

    @Override
    public int getGenerated() {
        return generated.get();
    }

    @Override
    public double getGeneratedPerSecond() {
        if (perSecondLatch.flip()) {
            double g = generated.get() - generatedLast.get();
            generatedLast.set(generated.get());

            if (g == 0) {
                return 0;
            }

            long dur = M.ms() - lastGPS.get();
            lastGPS.set(M.ms());
            perSecond.set(g / ((double) (dur) / 1000D));
        }

        return perSecond.get();
    }

    @Override
    public boolean isStudio() {
        return studio;
    }

    private void computeBiomeMaxes() {
        for (IrisBiome i : getDimension().getReachableBiomes(this)) {
            double density = 0;

            for (IrisObjectPlacement j : i.getObjects()) {
                density += j.getDensity() * j.getChance();
            }

            maxBiomeObjectDensity = Math.max(maxBiomeObjectDensity, density);
            density = 0;

            for (IrisDecorator j : i.getDecorators()) {
                density += Math.max(j.getStackMax(), 1) * j.getChance();
            }

            maxBiomeDecoratorDensity = Math.max(maxBiomeDecoratorDensity, density);
            density = 0;

            for (IrisBiomePaletteLayer j : i.getLayers()) {
                density++;
            }

            maxBiomeLayerDensity = Math.max(maxBiomeLayerDensity, density);
        }
    }

    @Override
    public int getBlockUpdatesPerSecond() {
        return buds.get();
    }

    public void printMetrics(VolmitSender sender) {
        KMap<String, Double> totals = new KMap<>();
        KMap<String, Double> weights = new KMap<>();
        double masterWallClock = wallClock.getAverage();
        KMap<String, Double> timings = getMetrics().pull();
        double totalWeight = 0;
        double wallClock = getMetrics().getTotal().getAverage();

        for (double j : timings.values()) {
            totalWeight += j;
        }

        for (String j : timings.k()) {
            weights.put(getName() + "." + j, (wallClock / totalWeight) * timings.get(j));
        }

        totals.put(getName(), wallClock);

        double mtotals = 0;

        for (double i : totals.values()) {
            mtotals += i;
        }

        for (String i : totals.k()) {
            totals.put(i, (masterWallClock / mtotals) * totals.get(i));
        }

        double v = 0;

        for (double i : weights.values()) {
            v += i;
        }

        for (String i : weights.k()) {
            weights.put(i, weights.get(i) / v);
        }

        sender.sendMessage("Total: " + C.BOLD + C.WHITE + Form.duration(masterWallClock, 0));

        for (String i : totals.k()) {
            sender.sendMessage("  Engine " + C.UNDERLINE + C.GREEN + i + C.RESET + ": " + C.BOLD + C.WHITE + Form.duration(totals.get(i), 0));
        }

        sender.sendMessage("Details: ");

        for (String i : weights.sortKNumber().reverse()) {
            String befb = C.UNDERLINE + "" + C.GREEN + "" + i.split("\\Q[\\E")[0] + C.RESET + C.GRAY + "[";
            String num = C.GOLD + i.split("\\Q[\\E")[1].split("]")[0] + C.RESET + C.GRAY + "].";
            String afb = C.ITALIC + "" + C.AQUA + i.split("\\Q]\\E")[1].substring(1) + C.RESET + C.GRAY;

            sender.sendMessage("  " + befb + num + afb + ": " + C.BOLD + C.WHITE + Form.pc(weights.get(i), 0));
        }
    }

    @Override
    public void close() {
        platformHooks.shutdownPregenerator(this);
        closing.set(true);
        closed = true;
        J.car(art);
        try {
            generationSessions.sealAndAwait("close", 15000L, true);
        } catch (GenerationSessionException e) {
            throw new IllegalStateException(e);
        }
        EngineWorldManager currentWorldManager = getWorldManager();
        if (currentWorldManager != null) {
            currentWorldManager.close();
        }
        savePrefetchOnce();
        getTarget().close();
        saveEngineData();
        getMantle().close();
        IrisComplex currentComplex = complex;
        if (currentComplex != null) {
            currentComplex.close();
        }
        complex = null;
        EngineMode currentMode = mode;
        if (currentMode != null) {
            currentMode.close();
        }
        mode = null;
        effects = null;
        worldManager = null;
        getData().dump();
        getData().clearLists();
        IrisServices.get(PreservationRegistry.class).dereference();
        IrisLogging.debug("Engine Fully Shutdown!");
    }

    private boolean isPregeneratorActiveForThisWorld() {
        return platformHooks.isPregeneratorActive(this);
    }

    private void savePrefetchOnce() {
        if (prefetchSaveStarted.compareAndSet(false, true)) {
            getData().savePrefetch(this);
        }
    }

    @Override
    public IrisComplex getComplex() {
        return ensureComplex();
    }

    @Override
    public EngineMode getMode() {
        return ensureMode();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isClosing() {
        return closing.get();
    }

    @Override
    public void recycle() {
        if (!cleanLatch.flip()) {
            return;
        }

        if (cleaning.get()) {
            cleanLatch.flipDown();
            return;
        }

        cleaning.set(true);

        J.a(() -> {
            try {
                getData().getObjectLoader().clean();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                IrisLogging.error("Cleanup failed! Enable debug to see stacktrace.");
            }

            cleaning.lazySet(false);
        });
    }

    @BlockCoordinates
    @Override
    public void generate(int x, int z, Hunk<PlatformBlockState> vblocks, Hunk<PlatformBiome> vbiomes, boolean multicore) throws WrongEngineBroException {
        if (closing.get() || closed) {
            throw new GenerationSessionException("Generation session is closed for world \"" + getWorld().name() + "\".", true);
        }

        try (GenerationSessionLease lease = acquireGenerationLease("chunk_generate");
             IrisContext.Scope generationScope = IrisContext.open(this, lease.sessionId(), null)) {
            getEngineData().getStatistics().generatedChunk();
            PrecisionStopwatch p = PrecisionStopwatch.start();
            Hunk<PlatformBlockState> blocks = vblocks.listen((xx, y, zz, t) -> catchBlockUpdates(x + xx, y, z + zz, t));

            if (getDimension().isDebugChunkCrossSections() && ((x >> 4) % getDimension().getDebugCrossSectionsMod() == 0 || (z >> 4) % getDimension().getDebugCrossSectionsMod() == 0)) {
                PlatformBlockState crossSection = B.getState("CRYING_OBSIDIAN");
                for (int i = 0; i < 16; i++) {
                    for (int j = 0; j < 16; j++) {
                        blocks.set(i, 0, j, crossSection);
                    }
                }
            } else {
                EngineMode activeMode = ensureMode();
                activeMode.generate(x, z, blocks, vbiomes, multicore, lease.sessionId());
            }

            boolean skipRealFlag = platformHooks.shouldBypassMantleStages(this);
            if (!skipRealFlag) {
                getMantle().getMantle().flag(x >> 4, z >> 4, MantleFlag.REAL, true);
            }
            getMetrics().getTotal().put(p.getMilliseconds());
            generated.incrementAndGet();

            if (generated.get() == 661 && !isPregeneratorActiveForThisWorld()) {
                J.a(this::savePrefetchOnce);
            }
        } catch (GenerationSessionException e) {
            throw e;
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            fail("Failed to generate " + x + ", " + z, e);
        }
    }

    @Override
    public GenerationSessionManager getGenerationSessions() {
        return generationSessions;
    }

    @Override
    public void saveEngineData() {
        //TODO: Method this file
        File f = new File(getWorld().worldFolder(), "iris/engine-data/" + getDimension().getLoadKey() + ".json");
        f.getParentFile().mkdirs();
        try {
            IO.writeAll(f, new Gson().toJson(getEngineData()));
            IrisLogging.debug("Saved Engine Data");
        } catch (IOException e) {
            IrisLogging.error("Failed to save Engine Data");
            IrisLogging.reportError(e);
            e.printStackTrace();
        }
    }

    @Override
    public void blockUpdatedMetric() {
        bud.incrementAndGet();
    }

    @Override
    public IrisBiome getFocus() {
        if (getDimension().getFocus() == null || getDimension().getFocus().trim().isEmpty()) {
            return null;
        }

        return getData().getBiomeLoader().load(getDimension().getFocus());
    }

    @Override
    public IrisRegion getFocusRegion() {
        if (getDimension().getFocusRegion() == null || getDimension().getFocusRegion().trim().isEmpty()) {
            return null;
        }

        return getData().getRegionLoader().load(getDimension().getFocusRegion());
    }

    @Override
    public void fail(String error, Throwable e) {
        failing = true;
        IrisLogging.error(error);
        IrisLogging.reportError(e);
        e.printStackTrace();
    }

    @Override
    public boolean hasFailed() {
        return failing;
    }

    @Override
    public int getCacheID() {
        return cacheId;
    }

    private boolean EngineSafe() {
        // Todo: this has potential if done right
        int EngineMCVersion = getEngineData().getStatistics().getMCVersion();
        int EngineIrisVersion = getEngineData().getStatistics().getVersion();
        int MinecraftVersion = IrisPlatforms.get().minecraftVersionNumber();
        int IrisVersion = IrisPlatforms.get().irisVersionNumber();
        if (EngineIrisVersion != IrisVersion) {
            return false;
        }
        if (EngineMCVersion != MinecraftVersion) {
            return false;
        }
        return true;
    }

}

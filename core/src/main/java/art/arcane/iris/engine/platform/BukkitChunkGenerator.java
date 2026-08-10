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

package art.arcane.iris.engine.platform;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioActivation;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioSession;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.framework.GenerationSessionLease;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.object.IrisDimensionContractException;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionRuntimeContract;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.object.StudioMode;
import art.arcane.iris.engine.platform.studio.StudioGenerator;
import art.arcane.iris.engine.platform.studio.generators.JigsawStudioGenerator;
import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.M;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.iris.util.project.hunk.view.ChunkDataHunkHolder;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.io.ReactiveFolder;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.Looper;
import io.papermc.lib.PaperLib;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.PrintWriter;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@EqualsAndHashCode(callSuper = true)
@Data
public class BukkitChunkGenerator extends ChunkGenerator implements PlatformChunkGenerator, Listener {
    private static final int LOAD_LOCKS = Runtime.getRuntime().availableProcessors() * 4;
    private static final long HOTLOAD_LOOP_DELAY_MS = 250L;
    private static final long HOTLOAD_MAINTENANCE_DELAY_MS = 4000L;
    private final GenerationStageGate loadLock;
    private final IrisWorld world;
    private final File dataLocation;
    private final String dimensionKey;
    private final ReactiveFolder folder;
    private final ReentrantLock lock = new ReentrantLock();
    private final KList<BlockPopulator> populators;
    private final ChronoLatch hotloadChecker;
    private final AtomicBoolean setup;
    private final boolean studio;
    private final AtomicBoolean studioEntryBootstrapActive;
    private final AtomicInteger a = new AtomicInteger(0);
    private volatile long lastChunkGenTime = 0L;
    private final CompletableFuture<Integer> spawnChunks = new CompletableFuture<>();
    private final AtomicCache<EngineTarget> targetCache = new AtomicCache<>();
    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();
    private volatile Engine engine;
    private volatile Looper hotloader;
    private volatile StudioMode lastMode;
    private volatile UUID lastJigsawStudioRequestId;
    private volatile boolean jigsawStudioActive;
    private volatile DummyBiomeProvider dummyBiomeProvider;
    private volatile Throwable initializationFailure;
    private volatile IrisDimension validatedDimension;
    private volatile IrisDimensionRuntimeContract validatedWorldContract;
    private volatile boolean closing;
    @Setter
    private volatile StudioGenerator studioGenerator;

    public BukkitChunkGenerator(IrisWorld world, boolean studio, File dataLocation, String dimensionKey) {
        setup = new AtomicBoolean(false);
        studioGenerator = null;
        dummyBiomeProvider = new DummyBiomeProvider();
        populators = new KList<>();
        loadLock = new GenerationStageGate(LOAD_LOCKS, () -> closing);
        this.world = world;
        this.hotloadChecker = new ChronoLatch(1000, false);
        this.studio = studio;
        this.studioEntryBootstrapActive = new AtomicBoolean(studio);
        this.dataLocation = dataLocation;
        this.dimensionKey = dimensionKey;
        this.folder = new ReactiveFolder(
                dataLocation,
                (_a, _b, _c) -> hotload(),
                new KList<>(".iob", ".json"),
                new KList<>(".iris"),
                new KList<>()
        );
        this.initializationFailure = null;
        this.jigsawStudioActive = false;
        this.validatedDimension = null;
        this.validatedWorldContract = null;
        this.closing = false;
        Bukkit.getServer().getPluginManager().registerEvents(this, BukkitPlatform.plugin());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent event) {
        if (!Objects.equals(world.identity(), WorldIdentity.key(event.getWorld()).toString())) return;
        BukkitPlatform.volmitPlugin().unregisterListener(this);
        world.setRawWorldSeed(event.getWorld().getSeed());
        if (initialize(event.getWorld())) return;

        IrisLogging.warn("Failed to get Engine for " + event.getWorld().getName() + " re-trying...");
        J.s(() -> {
            if (!initialize(event.getWorld())) {
                IrisLogging.error("Failed to get Engine for " + event.getWorld().getName() + "!");
            }
        }, 10);
    }

    private boolean initialize(World world) {
        try {
            Engine engine = getEngine(world);
            if (engine == null) {
                return false;
            }
            INMS.get().inject(world.getSeed(), engine, world);
            IrisLogging.debug("Injected Iris Biome Source into " + world.getName());
            if (!studio) {
                J.s(() -> updateSpawnLocation(world), 1);
            }
        } catch (Throwable e) {
            initializationFailure = e;
            spawnChunks.completeExceptionally(e);
            IrisLogging.reportError(e);
            IrisLogging.error("Failed to initialize Iris generator for " + world.getName());
            e.printStackTrace();
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to initialize Iris for world '" + world.getName() + "'", e);
        }
        spawnChunks.complete(INMS.get().getSpawnChunkCount(world));
        BukkitPlatform.volmitPlugin().unregisterListener(this);
        IrisWorlds.get().put(WorldIdentity.serialize(world), dimensionKey);
        return true;
    }

    @Nullable
    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return getInitialSpawnLocation(world);
    }

    public Location getInitialSpawnLocation(World world) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int y = Math.max(minY, Math.min(maxY, 96));
        return new Location(world, 0.5D, y, 0.5D);
    }

    private void updateSpawnLocation(World world) {
        Location initialSpawn = getInitialSpawnLocation(world);
        int chunkX = initialSpawn.getBlockX() >> 4;
        int chunkZ = initialSpawn.getBlockZ() >> 4;
        CompletableFuture<Chunk> chunkFuture = requestChunkAsync(world, chunkX, chunkZ, true);
        if (chunkFuture == null) {
            return;
        }

        chunkFuture.thenAccept(chunk ->
                J.runRegion(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> applySpawnLocation(chunk.getWorld(), initialSpawn)));
    }

    private void applySpawnLocation(World world, Location initialSpawn) {
        Location currentSpawn = world.getSpawnLocation();
        if (currentSpawn == null) {
            return;
        }

        if (!studio && (currentSpawn.getBlockX() != initialSpawn.getBlockX() || currentSpawn.getBlockZ() != initialSpawn.getBlockZ())) {
            return;
        }

        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int y = Math.max(minY, Math.min(maxY, world.getHighestBlockYAt(initialSpawn)));
        world.setSpawnLocation(new Location(world, initialSpawn.getX(), y, initialSpawn.getZ(), initialSpawn.getYaw(), initialSpawn.getPitch()));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Chunk> requestChunkAsync(World world, int chunkX, int chunkZ, boolean generate) {
        try {
            Object result = World.class
                    .getMethod("getChunkAtAsync", int.class, int.class, boolean.class)
                    .invoke(world, chunkX, chunkZ, generate);
            if (result instanceof CompletableFuture<?>) {
                return (CompletableFuture<Chunk>) result;
            }
            if (PaperLib.isPaper()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Paper World#getChunkAtAsync returned a non-future result."));
            }
        } catch (Throwable e) {
            if (PaperLib.isPaper()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Paper World#getChunkAtAsync is unavailable.", e));
            }
        }

        return PaperLib.getChunkAtAsync(world, chunkX, chunkZ, generate);
    }

    private void setupEngine() {
        lastMode = StudioMode.NORMAL;
        lastJigsawStudioRequestId = null;
        EngineTarget engineTarget = getTarget();
        String packKey = engineTarget.getDimension().getLoadKey();
        JigsawStudioActivation.Request request = studio
                ? JigsawStudioActivation.getGeneratorRequest(packKey)
                : null;
        JigsawStudioSession session = request == null
                ? null
                : JigsawStudioActivation.getGeneratorSession(packKey);
        jigsawStudioActive = request != null
                && session != null
                && session.sessionId().equals(request.requestId());
        IrisEngine createdEngine = new IrisEngine(
                engineTarget,
                selectInitializationMode(studio, jigsawStudioActive));
        createdEngine.setNativeStructureVolumeQueriesEnabled(shouldGenerateNativeStructures(
                jigsawStudioActive,
                studioEntryBootstrapActive.get(),
                initializationFailure != null));
        engine = createdEngine;
        populators.clear();
        targetCache.reset();
    }

    static IrisEngine.InitializationMode selectInitializationMode(boolean studio, boolean jigsawStudioActive) {
        if (!studio) {
            return IrisEngine.InitializationMode.RUNTIME;
        }
        return jigsawStudioActive
                ? IrisEngine.InitializationMode.JIGSAW_STUDIO
                : IrisEngine.InitializationMode.STUDIO;
    }

    @NotNull
    @Override
    public EngineTarget getTarget() {
        if (engine != null) return engine.getTarget();

        return targetCache.aquire(() -> {
            IrisData data = IrisData.openRuntime(dataLocation);
            data.dump();
            data.clearLists();
            IrisDimension dimension = data.getDimensionLoader().load(dimensionKey);

            if (dimension == null) {
                IrisLogging.error("Oh No! There's no pack in " + data.getDataFolder().getPath() + " or... there's no dimension for the key " + dimensionKey);
                IrisDimension test = IrisData.loadAnyDimension(dimensionKey, null);

                if (test != null) {
                    IrisLogging.warn("Looks like " + dimensionKey + " exists in " + test.getLoadFile().getPath() + " ");
                    test = IrisServices.get(StudioSVC.class).installInto(BukkitPlatform.console(), dimensionKey, dataLocation);
                    IrisLogging.warn("Attempted to install into " + data.getDataFolder().getPath());

                    if (test != null) {
                        IrisLogging.msg(C.IRIS + "Woo! Patched the Engine!");
                        dimension = test;
                    } else {
                        IrisLogging.error("Failed to patch dimension!");
                        throw new RuntimeException("Missing Dimension: " + dimensionKey);
                    }
                } else {
                    IrisLogging.error("Nope, you don't have an installation containing " + dimensionKey + " try downloading it?");
                    throw new RuntimeException("Missing Dimension: " + dimensionKey);
                }
            }

            return new EngineTarget(world, dimension, data);
        });
    }

    private Engine getEngine(WorldInfo world) {
        throwIfInitializationFailed();
        validateAndBindWorld(world);
        if (setup.get()) {
            return getEngine();
        }

        lock.lock();

        try {
            if (setup.get()) {
                return getEngine();
            }


            getWorld().setRawWorldSeed(world.getSeed());
            setupEngine();
            setup.set(true);
            this.hotloader = shouldRunStudioHotload(studio, closing, jigsawStudioActive) ? new Looper() {
                @Override
                protected long loop() {
                    if (shouldThrottleHotload()) {
                        return HOTLOAD_MAINTENANCE_DELAY_MS;
                    }

                    if (hotloadChecker.flip()) {
                        folder.check();
                    }

                    return HOTLOAD_LOOP_DELAY_MS;
                }
            } : null;

            if (hotloader != null) {
                hotloader.setPriority(Thread.MIN_PRIORITY);
                hotloader.start();
                hotloader.setName(getTarget().getWorld().name() + " Hotloader");
            }

            return engine;
        } finally {
            lock.unlock();
        }
    }

    private void throwIfInitializationFailed() {
        Throwable failure = initializationFailure;
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Iris initialization previously failed for world '" + world.name() + "'", failure);
    }

    private void validateAndBindWorld(WorldInfo worldInfo) {
        IrisDimension dimension = getTarget().getDimension();
        IrisDimensionRuntimeContract activeContract = validatedWorldContract;
        if (activeContract == null || validatedDimension != dimension) {
            IrisDimensionRuntimeContract expected = IrisDimensionRuntimeContract.expected(dimension, "iris");
            if (activeContract != null) {
                expected.requireExact("Bukkit world '" + worldInfo.getName() + "'", activeContract);
            }
            int runtimeHeight = worldInfo.getMaxHeight() - worldInfo.getMinHeight();
            expected.requireHeight("Bukkit world '" + worldInfo.getName() + "'", worldInfo.getMinHeight(), runtimeHeight);
            BukkitWorldBinding.bind(world, worldInfo);
            validatedWorldContract = expected;
            validatedDimension = dimension;
            return;
        }
        if (!world.hasPlatformWorld() && worldInfo instanceof World) {
            BukkitWorldBinding.bind(world, worldInfo);
        }
    }

    @Override
    public void close() {
        closeAsync();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        closing = true;
        CompletableFuture<Void> future = new CompletableFuture<>();
        while (!closeFuture.compareAndSet(null, future)) {
            CompletableFuture<Void> existing = closeFuture.get();
            if (existing != null) {
                return existing;
            }
        }

        CompletableFuture<Void> operation;
        try {
            operation = withExclusiveControlFuture(() -> {
                Looper activeHotloader = hotloader;
                hotloader = null;
                if (isStudio() && activeHotloader != null) {
                    activeHotloader.interrupt();
                    try {
                        activeHotloader.join(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        IrisLogging.reportError(e);
                    }
                }

                Engine currentEngine = engine;
                if (currentEngine != null && !currentEngine.isClosed()) {
                    currentEngine.close();
                }
                folder.clear();
                populators.clear();
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
            closeFuture.compareAndSet(future, null);
            return future;
        }

        operation.whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(throwable);
                closeFuture.compareAndSet(future, null);
            }
        });
        return future;
    }

    @Override
    public boolean isStudio() {
        return studio;
    }

    public void endStudioEntryBootstrap() {
        Engine activeEngine = engine;
        if (activeEngine instanceof IrisEngine irisEngine) {
            irisEngine.setNativeStructureVolumeQueriesEnabled(shouldGenerateNativeStructures(
                    jigsawStudioActive,
                    false,
                    initializationFailure != null));
        }
        studioEntryBootstrapActive.set(false);
    }

    public boolean isStudioEntryBootstrapActive() {
        return studioEntryBootstrapActive.get();
    }

    public boolean isJigsawStudioActive() {
        return jigsawStudioActive;
    }

    @Override
    public void hotload() {
        if (!shouldRunStudioHotload(isStudio(), closing, jigsawStudioActive)) {
            return;
        }

        withExclusiveControl(() -> getEngine().hotload());
    }

    static boolean shouldRunStudioHotload(boolean studio, boolean closing, boolean jigsawStudioActive) {
        return studio && !closing && !jigsawStudioActive;
    }

    public GenerationStagePermit acquireGenerationStage(String operation) {
        return loadLock.acquireStage(operation);
    }

    public GenerationStagePermit acquireNoiseGenerationStage(
            Engine expectedEngine,
            int chunkX,
            int chunkZ,
            String operation
    ) {
        Engine activeEngine = Objects.requireNonNull(expectedEngine, "Noise generation engine");
        return acquirePreparedGenerationStage(
                loadLock,
                operation,
                () -> resolveStudioGeneratorForNoise(activeEngine),
                activeEngine,
                chunkX,
                chunkZ);
    }

    static GenerationStagePermit acquirePreparedGenerationStage(
            GenerationStageGate gate,
            String operation,
            Supplier<StudioGenerator> resolver,
            Engine engine,
            int chunkX,
            int chunkZ
    ) {
        GenerationStageGate activeGate = Objects.requireNonNull(gate, "Generation stage gate");
        Supplier<StudioGenerator> activeResolver = Objects.requireNonNull(resolver, "Studio generator resolver");
        Engine activeEngine = Objects.requireNonNull(engine, "Prepared generation engine");
        while (true) {
            GenerationStagePermit stage = activeGate.acquireStage(operation);
            StudioGenerator selected;
            boolean retained = false;
            try {
                selected = activeResolver.get();
                if (selected == null || !selected.requiresPreSessionPreparation()) {
                    if (activeResolver.get() == selected) {
                        retained = true;
                        return stage;
                    }
                    continue;
                }
            } finally {
                if (!retained) {
                    stage.close();
                }
            }

            GenerationStageExclusivePermit exclusive = activeGate.acquireExclusiveStage(operation);
            try {
                StudioGenerator active = activeResolver.get();
                if (active != selected) {
                    continue;
                }
                active.prepareChunkBeforeSession(activeEngine, chunkX, chunkZ);
                return exclusive.downgradeToStage();
            } catch (WrongEngineBroException e) {
                throw new IllegalStateException("Iris generation stage " + operation
                        + " could not prepare its Studio generator.", e);
            } finally {
                exclusive.close();
            }
        }
    }

    private StudioGenerator resolveStudioGeneratorForNoise(Engine expectedEngine) {
        if (engine != expectedEngine) {
            throw new IllegalStateException("Iris noise generation belongs to a replaced engine runtime.");
        }
        computeStudioGenerator();
        if (engine != expectedEngine) {
            throw new IllegalStateException("Iris noise generation changed engine runtime during Studio resolution.");
        }
        return studioGenerator;
    }

    public void withExclusiveControl(Runnable r) {
        J.a(() -> {
            boolean acquired = false;
            try {
                loadLock.acquireExclusive();
                acquired = true;
                r.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IrisLogging.reportError(e);
                e.printStackTrace();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                e.printStackTrace();
            } finally {
                if (acquired) {
                    loadLock.releaseExclusive();
                }
            }
        });
    }

    public CompletableFuture<Void> withExclusiveControlFuture(Runnable r) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        J.a(() -> completeExclusiveControlFuture(loadLock, r, future));
        return future;
    }

    static void completeExclusiveControlFuture(
            GenerationStageGate gate,
            Runnable operation,
            CompletableFuture<Void> future
    ) {
        GenerationStageGate activeGate = Objects.requireNonNull(gate, "Exclusive control gate");
        Runnable activeOperation = Objects.requireNonNull(operation, "Exclusive control operation");
        CompletableFuture<Void> outward = Objects.requireNonNull(future, "Exclusive control future");
        boolean acquired = false;
        Throwable failure = null;
        try {
            activeGate.acquireExclusive();
            acquired = true;
            activeOperation.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = e;
        } catch (Throwable e) {
            failure = e;
        } finally {
            if (acquired) {
                activeGate.releaseExclusive();
            }
        }

        if (failure == null) {
            outward.complete(null);
        } else {
            outward.completeExceptionally(failure);
        }
    }

    public void touch(World world) {
        getEngine(world);
    }

    @Override
    public void generateNoise(@NotNull WorldInfo world, @NotNull Random random, int x, int z, @NotNull ChunkGenerator.ChunkData d) {
        if (closing) {
            throw new IllegalStateException("Iris chunk generation was rejected while the generator is closing.");
        }
        throwIfInitializationFailed();

        try {
            Engine engine = getEngine(world);
            lastChunkGenTime = System.currentTimeMillis();
            computeStudioGenerator();
            TerrainChunk tc = TerrainChunk.create(d);
            if (studioGenerator != null) {
                studioGenerator.generateChunk(engine, tc, x, z);
            } else {
                ChunkDataHunkHolder blocks = new ChunkDataHunkHolder(d);
                Hunk<PlatformBiome> biomes = Hunk.viewBiomes(tc);
                boolean useMulticore = studio && !J.isFolia();
                try (GenerationSessionLease lease = engine.acquireGenerationLease("bukkit_terrain_stage");
                     IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
                    engine.generate(x << 4, z << 4, blocks, biomes, useMulticore);
                    blocks.apply();
                }
            }

            IrisLogging.debug("Generated " + x + " " + z);
        } catch (IrisDimensionContractException e) {
            throw e;
        } catch (GenerationSessionException e) {
            if (closing || isExpectedTeardown(engine, e)) {
                throw new IllegalStateException("Iris chunk generation was rejected during an engine transition.", e);
            }

            IrisLogging.error("======================================");
            e.printStackTrace();
            reportErrorChunk(x, z, e);
            IrisLogging.error("======================================");

            throw new IllegalStateException("Iris chunk generation could not acquire its engine runtime.", e);
        } catch (Throwable e) {
            IrisLogging.error("======================================");
            e.printStackTrace();
            reportErrorChunk(x, z, e);
            IrisLogging.error("======================================");

            throw new IllegalStateException("Iris failed to generate chunk " + x + "," + z + ".", e);
        }
    }

    private boolean isExpectedTeardown(Engine currentEngine, Throwable throwable) {
        if (throwable instanceof GenerationSessionException generationSessionException && generationSessionException.isExpectedTeardown()) {
            return true;
        }

        if (currentEngine != null && currentEngine.isClosing()) {
            return true;
        }

        return isMaintenanceActive();
    }

    private boolean isMaintenanceActive() {
        World realWorld = BukkitWorldBinding.world(this.world);
        return realWorld != null && IrisToolbelt.isWorldMaintenanceActive(realWorld);
    }

    private static void reportErrorChunk(int x, int z, Throwable e) {
        if (IrisSettings.get().getGeneral().isDebug()) {
            File f = IrisPlatforms.get().dataFile("debug", "chunk-errors", "chunk." + x + "." + z + ".txt");

            if (!f.exists()) {
                J.attempt(() -> {
                    PrintWriter pw = new PrintWriter(f);
                    pw.println("Thread: " + Thread.currentThread().getName());
                    pw.println("First: " + new Date(M.ms()));
                    e.printStackTrace(pw);
                    pw.close();
                });
            }

            IrisLogging.debug("Chunk " + x + "," + z + " Exception Logged: " + e.getClass().getSimpleName() + ": " + C.RESET + "" + C.LIGHT_PURPLE + e.getMessage());
        }
    }

    private boolean shouldThrottleHotload() {
        if (isMaintenanceActive()) {
            return true;
        }

        if (System.currentTimeMillis() - lastChunkGenTime < 2000L) {
            return true;
        }

        World realWorld = BukkitWorldBinding.world(this.world);
        PregeneratorJob pregeneratorJob = PregeneratorJob.getInstance();
        return realWorld != null && pregeneratorJob != null && pregeneratorJob.targetsWorldIdentity(WorldIdentity.serialize(realWorld));
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull HeightMap heightMap) {
        Engine currentEngine = getEngine(worldInfo);

        boolean ignoreFluid = switch (heightMap) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> true;
            default -> false;
        };

        try (GenerationSessionLease lease = currentEngine.acquireGenerationLease("bukkit_base_height");
             IrisContext.Scope ignored = IrisContext.open(currentEngine, lease.sessionId(), null)) {
            return currentEngine.getMinHeight() + currentEngine.getHeight(x, z, ignoreFluid) + 1;
        } catch (GenerationSessionException e) {
            throw new IllegalStateException("Iris base height query was rejected for world '"
                    + worldInfo.getName() + "'.", e);
        }
    }

    private void computeStudioGenerator() {
        String packKey = getEngine().getDimension().getLoadKey();
        JigsawStudioActivation.Request jigsawRequest = studio
                ? JigsawStudioActivation.getGeneratorRequest(packKey)
                : null;
        if (jigsawRequest != null) {
            JigsawStudioSession session = JigsawStudioActivation.getGeneratorSession(packKey);
            if (session != null && session.sessionId().equals(jigsawRequest.requestId())) {
                if (!jigsawRequest.requestId().equals(lastJigsawStudioRequestId)) {
                    setStudioGenerator(new JigsawStudioGenerator(getEngine(), jigsawRequest, session));
                    lastJigsawStudioRequestId = jigsawRequest.requestId();
                    lastMode = null;
                }
                return;
            }
        }
        if (lastJigsawStudioRequestId != null) {
            lastJigsawStudioRequestId = null;
            lastMode = null;
        }
        StudioMode desired = getEngine().getDimension().getStudioMode();
        if (studio && art.arcane.iris.core.runtime.ObjectStudioActivation.isActive(getEngine().getDimension().getLoadKey())) {
            desired = StudioMode.OBJECT_BUFFET;
        }
        if (!desired.equals(lastMode)) {
            lastMode = desired;
            desired.inject(this);
        }
    }

    @NotNull
    @Override
    public List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return populators;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return shouldGenerateNativeStructures(
                jigsawStudioActive,
                studioEntryBootstrapActive.get(),
                initializationFailure != null);
    }

    static boolean shouldGenerateNativeStructures(
            boolean jigsawStudioActive,
            boolean studioEntryBootstrapActive,
            boolean initializationFailed
    ) {
        return !jigsawStudioActive && !studioEntryBootstrapActive && !initializationFailed;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {

    }

    @Nullable
    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return dummyBiomeProvider;
    }

    static final class GenerationStageGate {
        private final int permitCount;
        private final @Nullable Semaphore permits;
        private final BooleanSupplier closing;

        GenerationStageGate(int permitCount, BooleanSupplier closing) {
            if (permitCount <= 0) {
                throw new IllegalArgumentException("Generation stage permit count must be positive.");
            }
            this.permitCount = permitCount;
            this.permits = new Semaphore(permitCount, true);
            this.closing = Objects.requireNonNull(closing, "Generation stage close state");
        }

        GenerationStagePermit acquireStage(String operation) {
            String activeOperation = Objects.requireNonNull(operation, "Generation stage operation");
            if (closing.getAsBoolean()) {
                throw rejected(activeOperation);
            }
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Iris generation stage " + activeOperation
                        + " was interrupted while waiting for engine access.", e);
            }
            if (closing.getAsBoolean()) {
                permits.release();
                throw rejected(activeOperation);
            }
            return new GenerationStagePermit(permits);
        }

        GenerationStageExclusivePermit acquireExclusiveStage(String operation) {
            String activeOperation = Objects.requireNonNull(operation, "Generation stage operation");
            if (closing.getAsBoolean()) {
                throw rejected(activeOperation);
            }
            try {
                permits.acquire(permitCount);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Iris generation stage " + activeOperation
                        + " was interrupted while waiting for exclusive engine access.", e);
            }
            if (closing.getAsBoolean()) {
                permits.release(permitCount);
                throw rejected(activeOperation);
            }
            return new GenerationStageExclusivePermit(permits, permitCount);
        }

        void acquireExclusive() throws InterruptedException {
            permits.acquire(permitCount);
        }

        void releaseExclusive() {
            permits.release(permitCount);
        }

        int queueLength() {
            return permits.getQueueLength();
        }

        int availablePermits() {
            return permits.availablePermits();
        }

        private IllegalStateException rejected(String operation) {
            return new IllegalStateException("Iris generation stage " + operation
                    + " was rejected while the generator is closing.");
        }
    }

    static final class GenerationStageExclusivePermit implements AutoCloseable {
        private final Semaphore permits;
        private final int permitCount;
        private final AtomicBoolean released;

        private GenerationStageExclusivePermit(Semaphore permits, int permitCount) {
            this.permits = permits;
            this.permitCount = permitCount;
            this.released = new AtomicBoolean(false);
        }

        GenerationStagePermit downgradeToStage() {
            if (!released.compareAndSet(false, true)) {
                throw new IllegalStateException("Exclusive Iris generation stage was already released.");
            }
            GenerationStagePermit stage = new GenerationStagePermit(permits);
            if (permitCount > 1) {
                permits.release(permitCount - 1);
            }
            return stage;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release(permitCount);
            }
        }
    }

    public static final class GenerationStagePermit implements AutoCloseable {
        private static final GenerationStagePermit NOOP = new GenerationStagePermit(null);

        private final Semaphore permits;
        private final AtomicBoolean released;

        private GenerationStagePermit(@Nullable Semaphore permits) {
            this.permits = permits;
            this.released = new AtomicBoolean(permits == null);
        }

        public static GenerationStagePermit noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (permits != null && released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}

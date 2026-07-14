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
import org.bukkit.Material;
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
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.object.IrisDimensionContractException;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionRuntimeContract;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.object.StudioMode;
import art.arcane.iris.engine.platform.studio.StudioGenerator;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@EqualsAndHashCode(callSuper = true)
@Data
public class BukkitChunkGenerator extends ChunkGenerator implements PlatformChunkGenerator, Listener {
    private static final int LOAD_LOCKS = Runtime.getRuntime().availableProcessors() * 4;
    private static final long HOTLOAD_LOOP_DELAY_MS = 250L;
    private static final long HOTLOAD_MAINTENANCE_DELAY_MS = 4000L;
    private final Semaphore loadLock;
    private final IrisWorld world;
    private final File dataLocation;
    private final String dimensionKey;
    private final ReactiveFolder folder;
    private final ReentrantLock lock = new ReentrantLock();
    private final KList<BlockPopulator> populators;
    private final ChronoLatch hotloadChecker;
    private final AtomicBoolean setup;
    private final boolean studio;
    private final AtomicInteger a = new AtomicInteger(0);
    private volatile long lastChunkGenTime = 0L;
    private final CompletableFuture<Integer> spawnChunks = new CompletableFuture<>();
    private final AtomicCache<EngineTarget> targetCache = new AtomicCache<>();
    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();
    private volatile Engine engine;
    private volatile Looper hotloader;
    private volatile StudioMode lastMode;
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
        loadLock = new Semaphore(LOAD_LOCKS);
        this.world = world;
        this.hotloadChecker = new ChronoLatch(1000, false);
        this.studio = studio;
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
        engine = new IrisEngine(getTarget(), studio);
        populators.clear();
        targetCache.reset();
    }

    @NotNull
    @Override
    public EngineTarget getTarget() {
        if (engine != null) return engine.getTarget();

        return targetCache.aquire(() -> {
            IrisData data = IrisData.get(dataLocation);
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
            this.hotloader = studio ? new Looper() {
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

            if (studio) {
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
        CompletableFuture<Void> existing = closeFuture.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        closing = true;
        CompletableFuture<Void> future = withExclusiveControlFuture(() -> {
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
        if (!closeFuture.compareAndSet(existing, future)) {
            CompletableFuture<Void> winningFuture = closeFuture.get();
            return winningFuture == null ? future : winningFuture;
        }

        future.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                closeFuture.compareAndSet(future, null);
            }
        });
        return future;
    }

    @Override
    public boolean isStudio() {
        return studio;
    }

    @Override
    public void hotload() {
        if (!isStudio() || closing) {
            return;
        }

        withExclusiveControl(() -> getEngine().hotload());
    }

    public void withExclusiveControl(Runnable r) {
        J.a(() -> {
            boolean acquired = false;
            try {
                loadLock.acquire(LOAD_LOCKS);
                acquired = true;
                r.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IrisLogging.reportError(e);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            } finally {
                if (acquired) {
                    loadLock.release(LOAD_LOCKS);
                }
            }
        });
    }

    public CompletableFuture<Void> withExclusiveControlFuture(Runnable r) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        J.a(() -> {
            boolean acquired = false;
            try {
                loadLock.acquire(LOAD_LOCKS);
                acquired = true;
                r.run();
                future.complete(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            } finally {
                if (acquired) {
                    loadLock.release(LOAD_LOCKS);
                }
            }
        });
        return future;
    }

    public void touch(World world) {
        getEngine(world);
    }

    @Override
    public void generateNoise(@NotNull WorldInfo world, @NotNull Random random, int x, int z, @NotNull ChunkGenerator.ChunkData d) {
        if (closing) {
            return;
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
                engine.generate(x << 4, z << 4, blocks, biomes, useMulticore);
                blocks.apply();
            }

            IrisLogging.debug("Generated " + x + " " + z);
        } catch (IrisDimensionContractException e) {
            throw e;
        } catch (GenerationSessionException e) {
            if (closing || isExpectedTeardown(engine, e)) {
                return;
            }

            IrisLogging.error("======================================");
            e.printStackTrace();
            reportErrorChunk(x, z, e);
            IrisLogging.error("======================================");

            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    d.setBlock(i, 0, j, Material.RED_GLAZED_TERRACOTTA.createBlockData());
                }
            }
        } catch (Throwable e) {
            IrisLogging.error("======================================");
            e.printStackTrace();
            reportErrorChunk(x, z, e);
            IrisLogging.error("======================================");

            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    d.setBlock(i, 0, j, Material.RED_GLAZED_TERRACOTTA.createBlockData());
                }
            }
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

        return currentEngine.getMinHeight() + currentEngine.getHeight(x, z, ignoreFluid) + 1;
    }

    private void computeStudioGenerator() {
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
        if (initializationFailure != null) {
            return false;
        }
        try {
            Engine e = this.engine;
            if (e == null) {
                return true;
            }
            return e.getDimension().getImportedStructures().active();
        } catch (Throwable t) {
            IrisLogging.reportError("Iris could not resolve native structure generation policy for "
                    + world.name() + ".", t);
            return false;
        }
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
}

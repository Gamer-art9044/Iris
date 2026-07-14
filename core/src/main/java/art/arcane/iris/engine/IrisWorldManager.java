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

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedWorldManager;
import art.arcane.iris.engine.platform.EngineBukkitOps;
import art.arcane.iris.engine.object.IRare;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBlockDrops;
import art.arcane.iris.engine.object.IrisEntitySpawn;
import art.arcane.iris.engine.object.IrisMarker;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisSpawner;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.math.PowerOfTwoCoordinates;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterMarker;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.plugin.Chunks;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.Looper;
import art.arcane.iris.util.common.scheduling.jobs.QueueJob;
import io.papermc.lib.PaperLib;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@EqualsAndHashCode(callSuper = true)
@Data
public class IrisWorldManager extends EngineAssignedWorldManager {
    private static final int MAX_FORCED_CHUNK_UPDATES = 128;

    private final Looper looper;
    private final int id;
    private final KList<Runnable> updateQueue = new KList<>();
    private final ChronoLatch cl;
    private final ChronoLatch clw;
    private final ChronoLatch cln;
    private final ChronoLatch chunkUpdater;
    private final ChronoLatch chunkDiscovery;
    private final KMap<Long, Future<?>> cleanup = new KMap<>();
    private final ScheduledExecutorService cleanupService;
    private final Set<Long> mantleWarmupQueue = ConcurrentHashMap.newKeySet();
    private final Set<Long> markerFlagQueue = ConcurrentHashMap.newKeySet();
    private final Set<Long> discoveredFlagQueue = ConcurrentHashMap.newKeySet();
    private final Set<Long> markerScanQueue = ConcurrentHashMap.newKeySet();
    private final Set<Long> chunkUpdateQueue = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean chunkUpdateScanScheduled = new AtomicBoolean();
    private final AtomicBoolean chunkDiscoveryScanScheduled = new AtomicBoolean();
    private int entityCount = 0;
    private int actuallySpawned = 0;
    private int cooldown = 0;
    private int forcedChunkUpdateCursor = 0;
    private volatile boolean playersPresent = false;
    private KSet<Position2> injectBiomes = new KSet<>();
    private volatile int loadedChunkCount = 0;

    public IrisWorldManager() {
        super(null);
        cl = null;
        cln = null;
        clw = null;
        looper = null;
        chunkUpdater = null;
        chunkDiscovery = null;
        cleanupService = null;
        id = -1;
    }

    public IrisWorldManager(Engine engine) {
        super(engine);
        chunkUpdater = new ChronoLatch(3000);
        chunkDiscovery = new ChronoLatch(5000);
        cln = new ChronoLatch(60000);
        cl = new ChronoLatch(3000);
        clw = new ChronoLatch(1000, true);
        cleanupService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Iris Mantle Cleanup " + getTarget().getWorld().name());
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        id = engine.getCacheID();
        looper = new Looper() {
            @Override
            protected long loop() {
                if (getEngine().isClosed() || getEngine().getCacheID() != id) {
                    interrupt();
                }

                if (!getEngine().getWorld().hasPlatformWorld() && clw.flip()) {
                    J.runGlobal(() -> BukkitWorldBinding.tryBind(getEngine().getWorld()));
                }

                if (getEngine().getWorld().hasPlatformWorld()) {
                    if (chunkUpdater.flip()) {
                        updateChunks();
                    }

                    if (!playersPresent) {
                        return 5000;
                    }

                    if (chunkDiscovery.flip()) {
                        discoverChunks();
                    }

                    if (cln.flip()) {
                        engine.getEngineData().cleanup(getEngine());
                    }

                    if (!IrisSettings.get().getWorld().isMarkerEntitySpawningSystem() && !IrisSettings.get().getWorld().isAmbientEntitySpawningSystem()) {
                        return 3000;
                    }

                    onAsyncTick();
                }

                return IrisSettings.get().getWorld().getAsyncTickIntervalMS();
            }
        };
        looper.setPriority(Thread.MIN_PRIORITY);
        looper.setName("Iris World Manager " + getTarget().getWorld().name());
    }

    public void startManager() {
        if (!looper.isAlive()) {
            looper.start();
        }
    }

    private void discoverChunks() {
        World world = BukkitWorldBinding.world(getEngine().getWorld());
        if (world == null) {
            return;
        }

        if (isPregenActiveForThisWorld()) {
            return;
        }

        if (!chunkDiscoveryScanScheduled.compareAndSet(false, true)) {
            return;
        }

        boolean scheduled = J.runGlobal(() -> {
            try {
                if (getEngine().isClosed() || !world.equals(BukkitWorldBinding.world(getEngine().getWorld()))) {
                    return;
                }

                for (Player player : world.getPlayers()) {
                    if (player == null) {
                        continue;
                    }

                    J.runEntity(player, () -> {
                        if (!player.isOnline() || !world.equals(player.getWorld())) {
                            return;
                        }

                        int centerX = PowerOfTwoCoordinates.blockToChunkFloor(player.getLocation().getBlockX());
                        int centerZ = PowerOfTwoCoordinates.blockToChunkFloor(player.getLocation().getBlockZ());
                        int radius = 1;
                        for (int x = -radius; x <= radius; x++) {
                            for (int z = -radius; z <= radius; z++) {
                                int chunkX = centerX + x;
                                int chunkZ = centerZ + z;
                                raiseDiscoveredChunkFlag(world, chunkX, chunkZ);
                            }
                        }
                    });
                }
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            } finally {
                chunkDiscoveryScanScheduled.set(false);
            }
        });
        if (!scheduled) {
            chunkDiscoveryScanScheduled.set(false);
        }
    }

    private void raiseDiscoveredChunkFlag(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return;
        }

        if (!J.isFolia()) {
            getMantle().getChunk(chunkX, chunkZ).flag(MantleFlag.DISCOVERED, true);
            return;
        }

        long key = Cache.key(chunkX, chunkZ);
        if (!discoveredFlagQueue.add(key)) {
            return;
        }

        J.a(() -> {
            try {
                Mantle<Matter> mantle = getMantle();
                if (!mantle.hasFlag(chunkX, chunkZ, MantleFlag.DISCOVERED)) {
                    mantle.flag(chunkX, chunkZ, MantleFlag.DISCOVERED, true);
                }
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            } finally {
                discoveredFlagQueue.remove(key);
            }
        });
    }

    private void updateChunks() {
        World world = BukkitWorldBinding.world(getEngine().getWorld());
        if (world == null) {
            return;
        }

        if (isPregenActiveForThisWorld()) {
            return;
        }

        if (!chunkUpdateScanScheduled.compareAndSet(false, true)) {
            return;
        }

        boolean scheduled = J.runGlobal(() -> updateChunksOnGlobal(world));
        if (!scheduled) {
            chunkUpdateScanScheduled.set(false);
        }
    }

    private void updateChunksOnGlobal(World world) {
        try {
            if (getEngine().isClosed() || !world.equals(BukkitWorldBinding.world(getEngine().getWorld()))) {
                return;
            }

            List<Player> players = new ArrayList<>(world.getPlayers());
            playersPresent = !players.isEmpty();
            loadedChunkCount = world.getLoadedChunks().length;
            for (Player player : players) {
                if (player == null) {
                    continue;
                }

                J.runEntity(player, () -> schedulePlayerChunkUpdates(world, player));
            }

            scheduleForcedChunkUpdates(world);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        } finally {
            chunkUpdateScanScheduled.set(false);
        }
    }

    private void schedulePlayerChunkUpdates(World world, Player player) {
        if (!player.isOnline() || !world.equals(player.getWorld())) {
            return;
        }

        int centerX = PowerOfTwoCoordinates.blockToChunkFloor(player.getLocation().getBlockX());
        int centerZ = PowerOfTwoCoordinates.blockToChunkFloor(player.getLocation().getBlockZ());
        int radius = 1;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                scheduleChunkUpdate(world, centerX + x, centerZ + z);
            }
        }
    }

    private void scheduleForcedChunkUpdates(World world) {
        List<Position2> forcedChunks = new ArrayList<>();
        for (Chunk chunk : world.getForceLoadedChunks()) {
            forcedChunks.add(new Position2(chunk.getX(), chunk.getZ()));
        }
        forcedChunks.sort(Comparator.comparingInt(Position2::getX).thenComparingInt(Position2::getZ));

        int forcedChunkCount = forcedChunks.size();
        if (forcedChunkCount == 0) {
            forcedChunkUpdateCursor = 0;
            return;
        }

        int updateCount = Math.min(forcedChunkCount, MAX_FORCED_CHUNK_UPDATES);
        int start = Math.floorMod(forcedChunkUpdateCursor, forcedChunkCount);
        for (int i = 0; i < updateCount; i++) {
            Position2 chunk = forcedChunks.get((start + i) % forcedChunkCount);
            scheduleChunkUpdate(world, chunk.getX(), chunk.getZ());
        }
        forcedChunkUpdateCursor = (start + updateCount) % forcedChunkCount;
    }

    private void scheduleChunkUpdate(World world, int chunkX, int chunkZ) {
        long key = Cache.key(chunkX, chunkZ);
        if (!chunkUpdateQueue.add(key)) {
            return;
        }

        try {
            boolean scheduled = J.runRegion(world, chunkX, chunkZ, () -> {
                try {
                    updateChunkRegion(world, chunkX, chunkZ);
                } finally {
                    chunkUpdateQueue.remove(key);
                }
            });
            if (!scheduled) {
                chunkUpdateQueue.remove(key);
            }
        } catch (Throwable e) {
            chunkUpdateQueue.remove(key);
            IrisLogging.reportError(e);
        }
    }

    private void updateChunkRegion(World world, int chunkX, int chunkZ) {
        if (world == null || !world.isChunkLoaded(chunkX, chunkZ) || !Chunks.isSafe(world, chunkX, chunkZ)) {
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);

        if (IrisSettings.get().getWorld().isPostLoadBlockUpdates()) {
            if (!getMantle().isChunkLoaded(chunkX, chunkZ)) {
                warmupMantleChunkAsync(chunkX, chunkZ);
                return;
            }
            EngineBukkitOps.updateChunk(getEngine(), chunk);
        }

        if (!isEntitySpawningEnabledForCurrentWorld()) {
            return;
        }

        if (!IrisSettings.get().getWorld().isMarkerEntitySpawningSystem()) {
            return;
        }

        if (!J.isFolia() && !getMantle().isChunkLoaded(chunkX, chunkZ)) {
            warmupMantleChunkAsync(chunkX, chunkZ);
            return;
        }

        raiseInitialSpawnMarkerFlag(world, chunkX, chunkZ, () -> {
            int delay = RNG.r.i(5, 200);
            J.runRegion(world, chunkX, chunkZ, () -> {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return;
                }
                spawnIn(world.getChunkAt(chunkX, chunkZ), true);
            }, delay);

            Chunk markerChunk = world.getChunkAt(chunkX, chunkZ);
            forEachMarkerSpawner(markerChunk, (block, spawners) -> {
                IrisSpawner s = new KList<>(spawners).getRandom();
                if (s == null) {
                    return;
                }
                spawn(block, s, true);
            });
        });
    }

    private void raiseInitialSpawnMarkerFlag(World world, int chunkX, int chunkZ, Runnable onFirstRaise) {
        if (world == null || onFirstRaise == null) {
            return;
        }

        if (!J.isFolia()) {
            getMantle().raiseFlag(chunkX, chunkZ, MantleFlag.INITIAL_SPAWNED_MARKER, onFirstRaise);
            return;
        }

        long key = Cache.key(chunkX, chunkZ);
        if (!markerFlagQueue.add(key)) {
            return;
        }

        J.a(() -> {
            boolean raised = false;
            try {
                Mantle<Matter> mantle = getMantle();
                if (!mantle.hasFlag(chunkX, chunkZ, MantleFlag.INITIAL_SPAWNED_MARKER)) {
                    mantle.flag(chunkX, chunkZ, MantleFlag.INITIAL_SPAWNED_MARKER, true);
                    raised = true;
                }
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            } finally {
                markerFlagQueue.remove(key);
            }

            if (!raised) {
                return;
            }

            J.runRegion(world, chunkX, chunkZ, () -> {
                if (!world.isChunkLoaded(chunkX, chunkZ) || !Chunks.isSafe(world, chunkX, chunkZ)) {
                    return;
                }
                onFirstRaise.run();
            });
        });
    }

    private void warmupMantleChunkAsync(int chunkX, int chunkZ) {
        long key = Cache.key(chunkX, chunkZ);
        if (!mantleWarmupQueue.add(key)) {
            return;
        }

        J.a(() -> {
            try {
                getMantle().getChunk(chunkX, chunkZ);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            } finally {
                mantleWarmupQueue.remove(key);
            }
        });
    }

    private boolean onAsyncTick() {
        if (getEngine().isClosed()) {
            return false;
        }

        if (isPregenActiveForThisWorld()) {
            J.sleep(500);
            return false;
        }

        actuallySpawned = 0;

        if (!getEngine().getWorld().hasPlatformWorld()) {
            IrisLogging.debug("Can't spawn. No real world");
            J.sleep(5000);
            return false;
        }

        if (cl.flip()) {
            try {
                World realWorld = BukkitWorldBinding.world(getEngine().getWorld());
                if (realWorld == null) {
                    entityCount = 0;
                } else if (J.isFolia()) {
                    entityCount = getFoliaLivingEntityCount(realWorld);
                } else {
                    CompletableFuture<Integer> future = new CompletableFuture<>();
                    boolean scheduled = J.runGlobal(() -> {
                        try {
                            int count = 0;
                            for (Entity entity : realWorld.getEntities()) {
                                if (entity instanceof LivingEntity && !entity.isDead()) {
                                    count++;
                                }
                            }
                            future.complete(count);
                        } catch (Throwable ex) {
                            future.completeExceptionally(ex);
                        }
                    });
                    entityCount = scheduled ? future.get(2, TimeUnit.SECONDS) : 0;
                }
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                close();
            }
        }

        double epx = getEntitySaturation();
        if (epx > IrisSettings.get().getWorld().getTargetSpawnEntitiesPerChunk()) {
            IrisLogging.debug("Can't spawn. The entity per chunk ratio is at " + Form.pc(epx, 2) + " > 100% (total entities " + entityCount + ")");
            J.sleep(5000);
            return false;
        }

        int spawnBuffer = RNG.r.i(2, 12);
        World world = BukkitWorldBinding.world(getEngine().getWorld());
        if (world == null) {
            return false;
        }

        Position2[] cc = getLoadedChunkPositionsSnapshot(world);
        while (spawnBuffer-- > 0) {
            if (cc.length == 0) {
                IrisLogging.debug("Can't spawn. No chunks!");
                return false;
            }

            Position2 c = cc[RNG.r.nextInt(cc.length)];
            spawnChunkSafely(world, c.getX(), c.getZ(), false);
        }

        return actuallySpawned > 0;
    }

    private boolean isPregenActiveForThisWorld() {
        World world = BukkitWorldBinding.world(getEngine().getWorld());
        if (world == null) {
            return false;
        }

        if (IrisToolbelt.isWorldMaintenanceActive(world)) {
            return true;
        }

        PregeneratorJob job = PregeneratorJob.getInstance();
        if (job == null) {
            return false;
        }

        return job.targetsWorldIdentity(WorldIdentity.serialize(world));
    }

    private Position2[] getLoadedChunkPositionsSnapshot(World world) {
        if (world == null) {
            return new Position2[0];
        }

        CompletableFuture<Position2[]> future = new CompletableFuture<>();
        boolean scheduled = J.runGlobal(() -> {
            try {
                Chunk[] chunks = world.getLoadedChunks();
                Position2[] positions = new Position2[chunks.length];
                for (int i = 0; i < chunks.length; i++) {
                    positions[i] = new Position2(chunks[i].getX(), chunks[i].getZ());
                }
                loadedChunkCount = positions.length;
                future.complete(positions);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        if (!scheduled) {
            return new Position2[0];
        }

        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            return new Position2[0];
        }
    }

    private int getFoliaLivingEntityCount(World world) {
        CompletableFuture<List<Player>> playerFuture = new CompletableFuture<>();
        boolean scheduled = J.runGlobal(() -> {
            try {
                playerFuture.complete(new ArrayList<>(world.getPlayers()));
            } catch (Throwable e) {
                playerFuture.completeExceptionally(e);
            }
        });
        if (!scheduled) {
            return 0;
        }

        List<Player> players;
        try {
            players = playerFuture.get(2, TimeUnit.SECONDS);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            return 0;
        }

        Map<String, Entity> candidates = new ConcurrentHashMap<>();

        CountDownLatch latch = new CountDownLatch(players.size());
        for (Player player : players) {
            if (player == null) {
                latch.countDown();
                continue;
            }

            if (!J.runEntity(player, () -> {
                try {
                    if (!player.isOnline() || !world.equals(player.getWorld())) {
                        return;
                    }
                    candidates.put(player.getUniqueId().toString(), player);
                    for (Entity nearby : player.getNearbyEntities(64, 64, 64)) {
                        if (nearby != null) {
                            candidates.put(nearby.getUniqueId().toString(), nearby);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            })) {
                latch.countDown();
            }
        }

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        AtomicInteger count = new AtomicInteger();
        CountDownLatch entityLatch = new CountDownLatch(candidates.size());
        for (Entity entity : candidates.values()) {
            if (!J.runEntity(entity, () -> {
                try {
                    if (entity instanceof LivingEntity && world.equals(entity.getWorld()) && !entity.isDead()) {
                        count.incrementAndGet();
                    }
                } finally {
                    entityLatch.countDown();
                }
            })) {
                entityLatch.countDown();
            }
        }

        try {
            entityLatch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return count.get();
    }

    private void spawnChunkSafely(World world, int chunkX, int chunkZ, boolean initial) {
        if (world == null) {
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        J.runRegion(world, chunkX, chunkZ, () -> {
            try {
                if (!world.isChunkLoaded(chunkX, chunkZ) || !Chunks.isSafe(world, chunkX, chunkZ)) {
                    return;
                }

                spawnIn(world.getChunkAt(chunkX, chunkZ), initial);
            } finally {
                future.complete(null);
            }
        });

        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    private void spawnIn(Chunk c, boolean initial) {
        if (getEngine().isClosed()) {
            return;
        }

        if (!isEntitySpawningEnabledForCurrentWorld()) {
            return;
        }

        IrisComplex complex = getEngine().getComplex();
        if (complex == null) {
            return;
        }

        if (IrisSettings.get().getWorld().isMarkerEntitySpawningSystem()) {
            forEachMarkerSpawner(c, (block, spawners) -> {
                IrisSpawner s = new KList<>(spawners).getRandom();
                if (s == null) {
                    return;
                }

                spawn(block, s, false);
                J.runRegion(c.getWorld(), c.getX(), c.getZ(), () -> raiseInitialSpawnMarkerFlag(c.getWorld(), c.getX(), c.getZ(),
                        () -> spawn(block, s, true)));
            });
        }

        if (!IrisSettings.get().getWorld().isAmbientEntitySpawningSystem()) {
            return;
        }

        //@builder
        Predicate<IrisSpawner> filter = i -> i.canSpawn(getEngine(), c.getX(), c.getZ());
        ChunkCounter counter = new ChunkCounter(c.getEntities());

        IrisBiome biome = EngineBukkitOps.getSurfaceBiome(getEngine(), c);
        IrisEntitySpawn v = spawnRandomly(Stream.concat(getData().getSpawnerLoader()
                                .loadAll(getDimension().getEntitySpawners())
                                .shuffleCopy(RNG.r)
                                .stream()
                                .filter(filter)
                                .filter((i) -> i.isValid(biome)),
                        Stream.concat(getData()
                                        .getSpawnerLoader()
                                        .loadAll(getEngine().getRegion(PowerOfTwoCoordinates.chunkToBlock(c.getX()), PowerOfTwoCoordinates.chunkToBlock(c.getZ())).getEntitySpawners())
                                        .shuffleCopy(RNG.r)
                                        .stream()
                                        .filter(filter),
                                getData().getSpawnerLoader()
                                        .loadAll(getEngine().getSurfaceBiome(PowerOfTwoCoordinates.chunkToBlock(c.getX()), PowerOfTwoCoordinates.chunkToBlock(c.getZ())).getEntitySpawners())
                                        .shuffleCopy(RNG.r)
                                        .stream()
                                        .filter(filter)))
                .filter(counter)
                .flatMap((i) -> stream(i, initial))
                .collect(Collectors.toList()))
                .getRandom();
        //@done
        if (v == null || v.getReferenceSpawner() == null)
            return;

        try {
            spawn(c, v);
        } catch (Throwable e) {
            J.runRegion(c.getWorld(), c.getX(), c.getZ(), () -> spawn(c, v));
        }
    }

    private void spawn(Chunk c, IrisEntitySpawn i) {
        IrisSpawner ref = i.getReferenceSpawner();
        int s = i.spawn(getEngine(), c, RNG.r);
        actuallySpawned += s;
        if (s > 0) {
            ref.spawn(getEngine(), c.getX(), c.getZ());
        }
    }

    private void spawn(IrisPosition pos, IrisEntitySpawn i) {
        IrisSpawner ref = i.getReferenceSpawner();
        if (!ref.canSpawn(getEngine(), PowerOfTwoCoordinates.blockToChunkFloor(pos.getX()), PowerOfTwoCoordinates.blockToChunkFloor(pos.getZ())))
            return;

        int s = i.spawn(getEngine(), pos, RNG.r);
        actuallySpawned += s;
        if (s > 0) {
            ref.spawn(getEngine(), PowerOfTwoCoordinates.blockToChunkFloor(pos.getX()), PowerOfTwoCoordinates.blockToChunkFloor(pos.getZ()));
        }
    }

    private Stream<IrisEntitySpawn> stream(IrisSpawner s, boolean initial) {
        for (IrisEntitySpawn i : initial ? s.getInitialSpawns() : s.getSpawns()) {
            i.setReferenceSpawner(s);
            i.setReferenceMarker(s.getReferenceMarker());
        }

        return (initial ? s.getInitialSpawns() : s.getSpawns()).stream();
    }

    private boolean isEntitySpawningEnabledForCurrentWorld() {
        if (!getEngine().isStudio()) {
            return true;
        }

        return IrisSettings.get().getStudio().isEnableEntitySpawning();
    }

    private KList<IrisEntitySpawn> spawnRandomly(List<IrisEntitySpawn> types) {
        KList<IrisEntitySpawn> rarityTypes = new KList<>();
        int totalRarity = 0;

        for (IrisEntitySpawn i : types) {
            totalRarity += IRare.get(i);
        }

        for (IrisEntitySpawn i : types) {
            rarityTypes.addMultiple(i, totalRarity / IRare.get(i));
        }

        return rarityTypes;
    }

    @Override
    public void onTick() {

    }

    @Override
    public void onSave() {
        getEngine().getMantle().save();
    }

    public void requestBiomeInject(Position2 p) {
        injectBiomes.add(p);
    }

    @Override
    public void onChunkLoad(Chunk e, boolean generated) {
        if (getEngine().isClosed()) {
            return;
        }

        int cX = e.getX(), cZ = e.getZ();
        Long key = Cache.key(cX, cZ);
        cleanup.put(key, cleanupService.schedule(() -> {
            cleanup.remove(key);
            getEngine().cleanupMantleChunk(cX, cZ);
        }, Math.max(IrisSettings.get().getPerformance().mantleCleanupDelay * 50L, 0), TimeUnit.MILLISECONDS));
    }

    @Override
    public void onChunkUnload(Chunk e) {
        final var future = cleanup.remove(Cache.key(e.getX(), e.getZ()));
        if (future != null) {
            future.cancel(false);
        }
    }

    private void spawn(IrisPosition block, IrisSpawner spawner, boolean initial) {
        if (getEngine().isClosed()) {
            return;
        }

        if (spawner == null) {
            return;
        }

        KList<IrisEntitySpawn> s = initial ? spawner.getInitialSpawns() : spawner.getSpawns();
        if (s.isEmpty()) {
            return;
        }

        IrisEntitySpawn ss = spawnRandomly(s).getRandom();
        ss.setReferenceSpawner(spawner);
        ss.setReferenceMarker(spawner.getReferenceMarker());
        spawn(block, ss);
    }

    public Mantle<Matter> getMantle() {
        return getEngine().getMantle().getMantle();
    }

    @Override
    public void teleportAsync(PlayerTeleportEvent e) {
        e.setCancelled(true);
        warmupAreaAsync(e.getPlayer(), e.getTo(), () -> J.runEntity(e.getPlayer(), () -> {
            ignoreTP.set(true);
            e.getPlayer().teleport(e.getTo(), e.getCause());
            ignoreTP.set(false);
        }));
    }

    private void warmupAreaAsync(Player player, Location to, Runnable r) {
        J.a(() -> {
            int viewDistance = 2;
            KList<Future<Chunk>> futures = new KList<>();
            for (int i = -viewDistance; i <= viewDistance; i++) {
                for (int j = -viewDistance; j <= viewDistance; j++) {
                    int finalJ = j;
                    int finalI = i;

                    if (to.getWorld().isChunkLoaded((to.getBlockX() >> 4) + i, (to.getBlockZ() >> 4) + j)) {
                        futures.add(CompletableFuture.completedFuture(null));
                        continue;
                    }

                    futures.add(MultiBurst.burst.completeValue(()
                            -> PaperLib.getChunkAtAsync(to.getWorld(),
                            (to.getBlockX() >> 4) + finalI,
                            (to.getBlockZ() >> 4) + finalJ,
                            true, false).get()));
                }
            }

            new QueueJob<Future<Chunk>>() {
                @Override
                public void execute(Future<Chunk> chunkFuture) {
                    try {
                        chunkFuture.get();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        IrisLogging.debug("Chunk warmup interrupted while loading async teleport chunk.");
                    } catch (ExecutionException ex) {
                        IrisLogging.reportError(ex);
                    }
                }

                @Override
                public String getName() {
                    return "Loading Chunks";
                }
            }.queue(futures).execute(new VolmitSender(player), true, r);
        });
    }

    public Map<IrisPosition, KSet<IrisSpawner>> getSpawnersFromMarkers(Chunk c) {
        Map<IrisPosition, KSet<IrisSpawner>> p = new KMap<>();
        Set<IrisPosition> b = new KSet<>();

        if (J.isFolia()) {
            if (!getMantle().isChunkLoaded(c.getX(), c.getZ())) {
                warmupMantleChunkAsync(c.getX(), c.getZ());
            }
            return p;
        }

        getMantle().iterateChunk(c.getX(), c.getZ(), MatterMarker.class, (x, y, z, t) -> {
            if (t.getTag().equals("cave_floor") || t.getTag().equals("cave_ceiling")) {
                return;
            }

            IrisMarker mark = getData().getMarkerLoader().load(t.getTag());
            IrisPosition pos = new IrisPosition((c.getX() << 4) + x, y, (c.getZ() << 4) + z);

            if (mark.isEmptyAbove()) {
                boolean remove = c.getBlock(x, y + 1, z).getBlockData().getMaterial().isSolid()
                        || c.getBlock(x, y + 2, z).getBlockData().getMaterial().isSolid();

                if (remove) {
                    b.add(pos);
                    return;
                }
            }

            for (String i : mark.getSpawners()) {
                IrisSpawner m = getData().getSpawnerLoader().load(i);
                if (m == null) {
                    IrisLogging.error("Cannot load spawner: " + i + " for marker on " + getName());
                    continue;
                }
                m.setReferenceMarker(mark);

                // This is so fucking incorrect its a joke
                //noinspection ConstantConditions
                if (m != null) {
                    p.computeIfAbsent(pos, (k) -> new KSet<>()).add(m);
                }
            }
        });

        for (IrisPosition i : b) {
            getEngine().getMantle().getMantle().remove(i.getX(), i.getY(), i.getZ(), MatterMarker.class);
        }

        return p;
    }

    private void forEachMarkerSpawner(Chunk c, BiConsumer<IrisPosition, KSet<IrisSpawner>> consumer) {
        if (c == null || consumer == null) {
            return;
        }

        if (!J.isFolia()) {
            int minY = getEngine().getWorld().minHeight();
            getSpawnersFromMarkers(c).forEach((relative, spawners) -> {
                if (spawners.isEmpty()) {
                    return;
                }

                consumer.accept(new IrisPosition(relative.getX(), relative.getY() + minY, relative.getZ()), spawners);
            });
            return;
        }

        int chunkX = c.getX();
        int chunkZ = c.getZ();
        World world = c.getWorld();
        long key = Cache.key(chunkX, chunkZ);
        if (!markerScanQueue.add(key)) {
            return;
        }

        J.a(() -> {
            try {
                Map<IrisPosition, MarkerSpawnData> markerData = collectMarkerSpawnData(chunkX, chunkZ);
                if (markerData.isEmpty()) {
                    return;
                }

                J.runRegion(world, chunkX, chunkZ, () -> {
                    if (!world.isChunkLoaded(chunkX, chunkZ) || !Chunks.isSafe(world, chunkX, chunkZ)) {
                        return;
                    }

                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    int minY = getEngine().getWorld().minHeight();
                    markerData.forEach((relative, data) -> {
                        if (data.spawners.isEmpty()) {
                            return;
                        }

                        if (isMarkerObstructed(chunk, relative, data.requiresEmptyAbove)) {
                            removeMarkerAsync(relative);
                            return;
                        }

                        consumer.accept(new IrisPosition(relative.getX(), relative.getY() + minY, relative.getZ()), data.spawners);
                    });
                });
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            } finally {
                markerScanQueue.remove(key);
            }
        });
    }

    private Map<IrisPosition, MarkerSpawnData> collectMarkerSpawnData(int chunkX, int chunkZ) {
        Map<IrisPosition, MarkerSpawnData> markerData = new KMap<>();
        getMantle().iterateChunk(chunkX, chunkZ, MatterMarker.class, (x, y, z, t) -> {
            if (t.getTag().equals("cave_floor") || t.getTag().equals("cave_ceiling")) {
                return;
            }

            IrisMarker mark = getData().getMarkerLoader().load(t.getTag());
            if (mark == null) {
                return;
            }

            IrisPosition position = new IrisPosition((chunkX << 4) + x, y, (chunkZ << 4) + z);
            MarkerSpawnData data = markerData.computeIfAbsent(position, k -> new MarkerSpawnData());
            data.requiresEmptyAbove = data.requiresEmptyAbove || mark.isEmptyAbove();

            for (String i : mark.getSpawners()) {
                IrisSpawner spawner = getData().getSpawnerLoader().load(i);
                if (spawner == null) {
                    IrisLogging.error("Cannot load spawner: " + i + " for marker on " + getName());
                    continue;
                }
                spawner.setReferenceMarker(mark);
                data.spawners.add(spawner);
            }
        });

        return markerData;
    }

    private boolean isMarkerObstructed(Chunk chunk, IrisPosition relative, boolean requiresEmptyAbove) {
        if (!requiresEmptyAbove) {
            return false;
        }

        int minY = getEngine().getWorld().minHeight();
        int markerY = relative.getY() + minY;
        if (markerY + 2 >= chunk.getWorld().getMaxHeight()) {
            return true;
        }

        int localX = relative.getX() & 15;
        int localZ = relative.getZ() & 15;
        return chunk.getBlock(localX, markerY + 1, localZ).getBlockData().getMaterial().isSolid()
                || chunk.getBlock(localX, markerY + 2, localZ).getBlockData().getMaterial().isSolid();
    }

    private void removeMarkerAsync(IrisPosition marker) {
        J.a(() -> {
            try {
                getMantle().remove(marker.getX(), marker.getY(), marker.getZ(), MatterMarker.class);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        });
    }

    private static final class MarkerSpawnData {
        private final KSet<IrisSpawner> spawners = new KSet<>();
        private boolean requiresEmptyAbove;
    }

    @Override
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.getBlock().getWorld().equals(BukkitWorldBinding.world(getTarget().getWorld()))) {
            int blockX = e.getBlock().getX();
            int mantleY = toMantleY(e.getBlock().getY(), getEngine().getWorld().minHeight());
            int blockZ = e.getBlock().getZ();
            J.a(() -> {
                MatterMarker marker = getMantle().get(blockX, mantleY, blockZ, MatterMarker.class);

                if (marker != null) {
                    if (marker.getTag().equals("cave_floor") || marker.getTag().equals("cave_ceiling")) {
                        return;
                    }

                    IrisMarker mark = getData().getMarkerLoader().load(marker.getTag());

                    if (mark == null || mark.isRemoveOnChange()) {
                        getMantle().remove(blockX, mantleY, blockZ, MatterMarker.class);
                    }
                }
            });

            KList<ItemStack> d = new KList<>();
            IrisBiome b = EngineBukkitOps.getBiome(getEngine(), e.getBlock().getLocation().clone().subtract(0, getEngine().getWorld().minHeight(), 0));
            List<IrisBlockDrops> dropProviders = filterDrops(b.getBlockDrops(), e, getData());

            if (dropProviders.stream().noneMatch(IrisBlockDrops::isSkipParents)) {
                IrisRegion r = EngineBukkitOps.getRegion(getEngine(), e.getBlock().getLocation());
                dropProviders.addAll(filterDrops(r.getBlockDrops(), e, getData()));
                dropProviders.addAll(filterDrops(getEngine().getDimension().getBlockDrops(), e, getData()));
            }

            dropProviders.forEach(provider -> provider.fillDrops(false, d));

            if (dropProviders.stream().anyMatch(IrisBlockDrops::isReplaceVanillaDrops)) {
                e.setDropItems(false);
            }

            if (d.isNotEmpty()) {
                World w = e.getBlock().getWorld();
                Location dropLocation = e.getBlock().getLocation().clone().add(.5, .5, .5);
                Runnable dropTask = () -> d.forEach(item -> w.dropItemNaturally(dropLocation, item));
                if (!J.runAt(dropLocation, dropTask)) {
                    if (!J.isFolia()) {
                        J.s(dropTask);
                    }
                }
            }
        }
    }

    static int toMantleY(int worldY, int minHeight) {
        return worldY - minHeight;
    }

    private List<IrisBlockDrops> filterDrops(KList<IrisBlockDrops> drops, BlockBreakEvent e, IrisData data) {
        return new KList<>(drops.stream().filter(d -> d.shouldDropFor(e.getBlock().getBlockData(), data)).toList());
    }

    @Override
    public void onBlockPlace(BlockPlaceEvent e) {

    }

    @Override
    public void close() {
        super.close();
        looper.interrupt();
        if (cleanupService != null) {
            cleanupService.shutdownNow();
        }
    }

    @Override
    public int getChunkCount() {
        return loadedChunkCount;
    }

    @Override
    public double getEntitySaturation() {
        if (!getEngine().getWorld().hasPlatformWorld()) {
            return 1;
        }

        return (double) entityCount / (loadedChunkCount + 1) * 1.28;
    }

    @Data
    private static class ChunkCounter implements Predicate<IrisSpawner> {
        private final Entity[] entities;
        private transient int index = 0;
        private transient int count = 0;

        @Override
        public boolean test(IrisSpawner spawner) {
            int max = spawner.getMaxEntitiesPerChunk();
            if (max <= count)
                return false;

            while (index < entities.length) {
                if (entities[index++] instanceof LivingEntity) {
                    if (++count >= max)
                        return false;
                }
            }

            return true;
        }
    }
}

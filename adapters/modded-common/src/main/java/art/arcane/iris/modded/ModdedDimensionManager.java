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

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class ModdedDimensionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, Handle> HANDLES = new ConcurrentHashMap<>();
    private static final TicketType TELEPORT_WARM_TICKET = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final long TELEPORT_WARM_TIMEOUT_SECONDS = 30L;
    private static volatile ModdedServerAccess access;

    private ModdedDimensionManager() {
    }

    public static synchronized ModdedServerAccess bindAccess(ModdedServerAccess serverAccess) {
        ModdedServerAccess previous = access;
        access = serverAccess;
        return previous;
    }

    public static synchronized void restoreAccess(ModdedServerAccess serverAccess) {
        access = serverAccess;
    }

    public static void clear() {
        HANDLES.clear();
    }

    public static Handle handle(String dimensionId) {
        return HANDLES.get(dimensionId);
    }

    public static List<Handle> handles() {
        return new ArrayList<>(HANDLES.values());
    }

    public static ServerLevel level(MinecraftServer server, String dimensionId) {
        Handle handle = HANDLES.get(dimensionId);
        if (handle != null && handle.level().getServer() == server) {
            return handle.level();
        }
        ResourceKey<Level> key = levelKey(dimensionId);
        // Server thread only (create/remove hold LOCK, teleport and the primary-world router tick, command
        // handlers). Off-thread callers must use ModdedServerLevels.level instead of the live map.
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().equals(key)) {
                return level;
            }
        }
        return null;
    }

    public static Engine engine(MinecraftServer server, String dimensionId) {
        ServerLevel level = level(server, dimensionId);
        if (level == null) {
            return null;
        }
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator)) {
            return null;
        }
        return generator.commandEngine();
    }

    public static Handle create(MinecraftServer server, String dimensionId, String pack, String packDimensionKey, long seed) {
        ModdedStartup.requirePackForWorldCreation(pack);
        ModdedServerAccess serverAccess = requireAccess();
        synchronized (LOCK) {
            ResourceKey<Level> key = levelKey(dimensionId);
            Handle existing = HANDLES.get(dimensionId);
            if (existing != null && serverAccess.hasLevel(server, key)) {
                existing.generator().repointAndBind(existing.level(), pack, packDimensionKey, seed);
                Handle refreshed = new Handle(dimensionId, pack, packDimensionKey, seed, existing.level(), existing.generator());
                HANDLES.put(dimensionId, refreshed);
                return refreshed;
            }
            if (serverAccess.hasLevel(server, key)) {
                ServerLevel present = level(server, dimensionId);
                if (present == null || !(present.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator)) {
                    throw new IllegalStateException("Iris cannot inject dimension '" + dimensionId + "': a non-Iris level with that id is already loaded");
                }
                LOGGER.warn("Iris dimension '{}' is already present in the running server; reusing it", dimensionId);
                generator.repointAndBind(present, pack, packDimensionKey, seed);
                Handle handle = new Handle(dimensionId, pack, packDimensionKey, seed, present, generator);
                HANDLES.put(dimensionId, handle);
                return handle;
            }

            try {
                Handle handle = inject(server, serverAccess, dimensionId, key, pack, packDimensionKey, seed);
                HANDLES.put(dimensionId, handle);
                LOGGER.info("Iris injected runtime dimension '{}' (pack={} dim={} seed={})", dimensionId, pack, packDimensionKey, seed);
                return handle;
            } catch (Throwable e) {
                LOGGER.error("Iris failed to inject runtime dimension '{}' (pack={} dim={} seed={})", dimensionId, pack, packDimensionKey, seed, e);
                throw new IllegalStateException("Iris runtime dimension injection failed for " + dimensionId, e);
            }
        }
    }

    public static Handle createPersistent(MinecraftServer server, String dimensionId, String pack, String packDimensionKey, long seed) {
        ModdedDimensionRegistryStore.PersistentDimension previous =
                ModdedDimensionRegistryStore.get(server, dimensionId);
        ModdedDimensionRegistryStore.put(server, new ModdedDimensionRegistryStore.PersistentDimension(dimensionId, pack, packDimensionKey, seed));
        try {
            return create(server, dimensionId, pack, packDimensionKey, seed);
        } catch (Throwable e) {
            try {
                if (previous == null) {
                    ModdedDimensionRegistryStore.remove(server, dimensionId);
                } else {
                    ModdedDimensionRegistryStore.put(server, previous);
                }
            } catch (Throwable rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("Iris runtime dimension injection failed for " + dimensionId, e);
        }
    }

    public static boolean removePersistent(MinecraftServer server, String dimensionId, boolean wipeStorage) {
        ModdedDimensionRegistryStore.PersistentDimension previous =
                ModdedDimensionRegistryStore.get(server, dimensionId);
        ModdedDimensionRegistryStore.remove(server, dimensionId);
        try {
            return remove(server, dimensionId, wipeStorage);
        } catch (Throwable e) {
            if (previous != null) {
                try {
                    ModdedDimensionRegistryStore.put(server, previous);
                } catch (Throwable rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw e;
        }
    }

    public static boolean remove(MinecraftServer server, String dimensionId, boolean wipeStorage) {
        ModdedServerAccess serverAccess = requireAccess();
        synchronized (LOCK) {
            ResourceKey<Level> key = levelKey(dimensionId);
            ServerLevel level = level(server, dimensionId);
            if (level == null) {
                HANDLES.remove(dimensionId);
                if (wipeStorage) {
                    ModdedDimensionStorage.wipe(server, key);
                }
                return false;
            }
            IrisModdedChunkGenerator generator = level.getChunkSource().getGenerator()
                    instanceof IrisModdedChunkGenerator irisGenerator
                    ? irisGenerator
                    : null;
            boolean generatorUnbound = false;
            try {
                evacuate(server, level);
                if (generator != null) {
                    generator.unbindEngine(level);
                    generatorUnbound = true;
                }
                ModdedWorldEngines.evictOrThrow(level);
                level.save(null, true, false);
                serverAccess.removeLevel(server, key);
                level.close();
                HANDLES.remove(dimensionId);
                if (wipeStorage) {
                    ModdedDimensionStorage.wipe(server, key);
                }
                LOGGER.info("Iris removed runtime dimension '{}'", dimensionId);
                return true;
            } catch (Throwable e) {
                rollbackRemoval(server, serverAccess, key, level, generator, generatorUnbound, e);
                LOGGER.error("Iris failed to remove runtime dimension '{}'", dimensionId, e);
                throw new IllegalStateException("Iris runtime dimension removal failed for " + dimensionId, e);
            }
        }
    }

    private static void rollbackRemoval(MinecraftServer server, ModdedServerAccess serverAccess,
                                        ResourceKey<Level> key, ServerLevel level,
                                        IrisModdedChunkGenerator generator, boolean generatorUnbound,
                                        Throwable failure) {
        try {
            if (!generatorUnbound || generator == null || !serverAccess.hasLevel(server, key)) {
                return;
            }
            generator.bindLevel(level);
        } catch (Throwable rollbackFailure) {
            if (rollbackFailure != failure) {
                failure.addSuppressed(rollbackFailure);
            }
            LOGGER.error("Iris failed to restore the engine for retained runtime dimension '{}'",
                    key.identifier(), rollbackFailure);
        }
    }

    public static boolean teleport(ServerPlayer player, MinecraftServer server, String dimensionId, double x, double y, double z) {
        ServerLevel level = level(server, dimensionId);
        if (level == null) {
            return false;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        ChunkPos chunkPos = new ChunkPos(blockX >> 4, blockZ >> 4);
        if (level.getChunkSource().hasChunk(chunkPos.x(), chunkPos.z())) {
            completeTeleport(player, level, x, y, z, blockX, blockZ);
            return true;
        }
        UUID playerId = player.getUUID();
        CompletableFuture
                .supplyAsync(() -> level.getChunkSource().addTicketAndLoadWithRadius(TELEPORT_WARM_TICKET, chunkPos, 1), server)
                .thenCompose((CompletableFuture<?> inner) -> inner)
                // The ticket has no timeout of its own: bound the wait so the release below always runs.
                .orTimeout(TELEPORT_WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((Object result, Throwable error) -> server.execute(() -> {
                    level.getChunkSource().removeTicketWithRadius(TELEPORT_WARM_TICKET, chunkPos, 1);
                    if (error != null) {
                        LOGGER.warn("Iris chunk warm for teleport into '{}' at {},{} failed: {}", dimensionId, chunkPos.x(), chunkPos.z(), error.toString());
                    }
                    ServerPlayer target = server.getPlayerList().getPlayer(playerId);
                    if (target == null) {
                        return;
                    }
                    completeTeleport(target, level, x, y, z, blockX, blockZ);
                }));
        return true;
    }

    private static void completeTeleport(ServerPlayer player, ServerLevel level, double x, double y, double z, int blockX, int blockZ) {
        double targetY = y;
        if (y == Double.MIN_VALUE) {
            targetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        }
        player.teleportTo(level, x, targetY, z, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
    }

    private static Holder<DimensionType> resolveDimensionType(RegistryAccess registryAccess, String pack, String packDimensionKey) {
        Registry<DimensionType> registry = registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE);
        IrisDimension dimension = loadPackDimension(pack, packDimensionKey);
        String typeRef = ModdedWorldgenIds.dimensionTypeRef(pack, packDimensionKey);
        ResourceKey<DimensionType> typeKey = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(typeRef));
        ModdedRuntimeRegistry.ensureCustomBiomes(registryAccess, dimension, pack);
        ModdedRuntimeRegistry.ensureDimensionType(registry, typeKey, typeRef);
        return ModdedForcedDatapack.requireRegisteredDimensionType(
                typeRef, registry.get(typeKey), pack, packDimensionKey);
    }

    private static IrisDimension loadPackDimension(String pack, String packDimensionKey) {
        try {
            File packFolder = ModdedWorldEngines.packFolder(pack);
            if (!packFolder.isDirectory()) {
                throw new IllegalStateException("Iris pack directory is missing: " + packFolder.getAbsolutePath());
            }
            IrisDimension dimension = IrisData.get(packFolder).getDimensionLoader().load(packDimensionKey);
            if (dimension == null) {
                throw new IllegalStateException("Iris pack '" + pack
                        + "' does not contain dimension '" + packDimensionKey + "'");
            }
            return dimension;
        } catch (Throwable e) {
            LOGGER.error("Iris could not load pack '{}' dimension '{}' for dimension type resolution",
                    pack, packDimensionKey, e);
            if (e instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("Iris could not load pack '" + pack + "' dimension '"
                    + packDimensionKey + "' for dimension type resolution", e);
        }
    }

    private static Handle inject(MinecraftServer server, ModdedServerAccess serverAccess, String dimensionId, ResourceKey<Level> key, String pack, String packDimensionKey, long seed) {
        RegistryAccess registryAccess = server.registryAccess();
        Holder<DimensionType> dimensionType = resolveDimensionType(registryAccess, pack, packDimensionKey);
        Holder<Biome> plains = registryAccess.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        FixedBiomeSource biomeSource = new FixedBiomeSource(plains);
        String generatorRef = pack.equals(packDimensionKey) ? pack : pack + ":" + packDimensionKey;
        IrisModdedChunkGenerator generator = new IrisModdedChunkGenerator(biomeSource, generatorRef);
        generator.repoint(pack, packDimensionKey, seed);
        LevelStem stem = new LevelStem(dimensionType, generator);

        WorldData worldData = server.getWorldData();
        ServerLevelData overworldData = worldData.overworldData();
        DerivedLevelData derivedLevelData = new DerivedLevelData(worldData, overworldData);

        Executor executor = serverAccess.levelExecutor(server);
        LevelStorageSource.LevelStorageAccess storage = serverAccess.levelStorage(server);
        long obfuscatedSeed = BiomeManager.obfuscateSeed(seed);

        ServerLevel level = new ServerLevel(
                server,
                executor,
                storage,
                derivedLevelData,
                key,
                stem,
                false,
                obfuscatedSeed,
                List.of(),
                false);

        try {
            generator.bindLevel(level);
            Handle handle = new Handle(dimensionId, pack, packDimensionKey, seed, level, generator);
            ServerLevel previous = serverAccess.putLevelIfAbsent(server, key, level);
            if (previous != null) {
                throw new IllegalStateException("Iris cannot inject dimension '" + dimensionId
                        + "': the level was registered concurrently");
            }
            server.getPlayerList().addWorldborderListener(level);
            return handle;
        } catch (Throwable error) {
            rollbackInjection(server, serverAccess, key, level, generator, error);
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (error instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("Iris runtime dimension injection failed for " + dimensionId, error);
        }
    }

    private static void rollbackInjection(MinecraftServer server, ModdedServerAccess serverAccess,
                                          ResourceKey<Level> key, ServerLevel level,
                                          IrisModdedChunkGenerator generator, Throwable failure) {
        try {
            if (serverAccess.hasLevel(server, key)) {
                ServerLevel removed = serverAccess.removeLevel(server, key);
                if (removed != null && removed != level) {
                    serverAccess.putLevel(server, key, removed);
                    throw new IllegalStateException("Iris injection rollback encountered another registered level for "
                            + key.identifier());
                }
            }
        } catch (Throwable cleanupError) {
            failure.addSuppressed(cleanupError);
            LOGGER.error("Iris failed to remove a partially injected level for {}", key.identifier(), cleanupError);
        }
        try {
            generator.unbindEngine(level);
        } catch (Throwable cleanupError) {
            failure.addSuppressed(cleanupError);
            LOGGER.error("Iris failed to close a partially bound engine for {}", key.identifier(), cleanupError);
        }
        try {
            level.close();
        } catch (Throwable cleanupError) {
            failure.addSuppressed(cleanupError);
            LOGGER.error("Iris failed to close a partially injected level for {}", key.identifier(), cleanupError);
        }
    }

    public static int evacuate(MinecraftServer server, ServerLevel from) {
        ServerLevel fallback = server.overworld();
        if (fallback == from) {
            return 0;
        }
        BlockPos spawn = fallback.getRespawnData().pos();
        int spawnY = fallback.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
        List<ServerPlayer> players = new ArrayList<>(from.players());
        for (ServerPlayer player : players) {
            player.teleportTo(fallback, spawn.getX() + 0.5D, spawnY, spawn.getZ() + 0.5D, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        }
        return players.size();
    }

    private static ResourceKey<Level> levelKey(String dimensionId) {
        Identifier identifier = Identifier.parse(dimensionId);
        return ResourceKey.create(Registries.DIMENSION, identifier);
    }

    private static ModdedServerAccess requireAccess() {
        ModdedServerAccess bound = access;
        if (bound == null) {
            throw new IllegalStateException("Iris modded server access is not bound; the loader bootstrap must bind ModdedServerAccess before runtime dimension injection");
        }
        return bound;
    }

    public record Handle(String dimensionId, String pack, String packDimensionKey, long seed, ServerLevel level, IrisModdedChunkGenerator generator) {
    }
}

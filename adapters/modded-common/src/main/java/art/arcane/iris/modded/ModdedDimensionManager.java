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

public final class ModdedDimensionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, Handle> HANDLES = new ConcurrentHashMap<>();
    private static final TicketType TELEPORT_WARM_TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
    private static volatile ModdedServerAccess access;

    private ModdedDimensionManager() {
    }

    public static void bindAccess(ModdedServerAccess serverAccess) {
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
        ModdedServerAccess serverAccess = requireAccess();
        synchronized (LOCK) {
            ResourceKey<Level> key = levelKey(dimensionId);
            Handle existing = HANDLES.get(dimensionId);
            if (existing != null && serverAccess.hasLevel(server, key)) {
                existing.generator().repoint(pack, packDimensionKey, seed);
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
                generator.repoint(pack, packDimensionKey, seed);
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
        Handle handle = create(server, dimensionId, pack, packDimensionKey, seed);
        ModdedDimensionRegistryStore.put(server, new ModdedDimensionRegistryStore.PersistentDimension(dimensionId, pack, packDimensionKey, seed));
        return handle;
    }

    public static boolean removePersistent(MinecraftServer server, String dimensionId, boolean wipeStorage) {
        boolean removed = remove(server, dimensionId, wipeStorage);
        ModdedDimensionRegistryStore.remove(server, dimensionId);
        return removed;
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
            try {
                evacuate(server, level);
                if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator) {
                    generator.unbindEngine();
                }
                ModdedWorldEngines.evict(level);
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
                LOGGER.error("Iris failed to remove runtime dimension '{}'", dimensionId, e);
                throw new IllegalStateException("Iris runtime dimension removal failed for " + dimensionId, e);
            }
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
        if (dimension == null) {
            throw new IllegalStateException("Iris cannot resolve dimension type for missing pack dimension '"
                    + packDimensionKey + "' in pack '" + pack + "'");
        }
        String typeRef = ModdedForcedDatapack.dimensionTypeRef(dimension);
        ResourceKey<DimensionType> typeKey = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(typeRef));
        Holder.Reference<DimensionType> packType = ModdedForcedDatapack.requireRegisteredDimensionType(
                typeRef, registry.get(typeKey), pack, packDimensionKey);
        return packType;
    }

    private static IrisDimension loadPackDimension(String pack, String packDimensionKey) {
        try {
            File packFolder = ModdedWorldEngines.packFolder(pack);
            if (!packFolder.isDirectory()) {
                return null;
            }
            return IrisData.get(packFolder).getDimensionLoader().load(packDimensionKey);
        } catch (Throwable e) {
            LOGGER.warn("Iris could not load pack '{}' dimension '{}' for dimension type resolution: {}", pack, packDimensionKey, e.toString());
            return null;
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

        serverAccess.putLevel(server, key, level);
        generator.bindLevel(level);
        server.getPlayerList().addWorldborderListener(level);
        return new Handle(dimensionId, pack, packDimensionKey, seed, level, generator);
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

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
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionRuntimeContract;
import art.arcane.iris.engine.object.IrisWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModdedWorldEngines {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final ConcurrentHashMap<ServerLevel, Engine> ENGINES = new ConcurrentHashMap<>();

    private ModdedWorldEngines() {
    }

    public static Engine get(ServerLevel level, String pack, String dimensionKey, long seedOverride) {
        Engine existing = ENGINES.get(level);
        if (existing != null) {
            return existing;
        }
        return ENGINES.computeIfAbsent(level, (ServerLevel l) -> create(l, pack, dimensionKey, seedOverride));
    }

    public static Collection<Engine> activeEngines() {
        return new ArrayList<>(ENGINES.values());
    }

    public static void evict(ServerLevel level) {
        Engine removed = ENGINES.remove(level);
        if (removed == null) {
            return;
        }
        try {
            if (!removed.isClosed()) {
                removed.close();
            }
            LOGGER.info("Iris engine evicted for {}", level.dimension().identifier());
        } catch (Throwable e) {
            LOGGER.error("Iris engine evict close failed for {}", level.dimension().identifier(), e);
        }
    }

    private static Engine create(ServerLevel level, String pack, String dimensionKey, long seedOverride) {
        ModdedEngineBootstrap.bind();
        File packDir = resolvePack(pack, dimensionKey);
        IrisData data = IrisData.get(packDir);
        IrisDimension dimension = data.getDimensionLoader().load(dimensionKey);
        if (dimension == null) {
            LOGGER.error("Iris pack '{}' at {} does not contain dimension '{}' (expected dimensions/{}.json). Install a matching Iris pack and restart.",
                    pack, packDir.getAbsolutePath(), dimensionKey, dimensionKey);
            throw new IllegalStateException("Iris dimension '" + dimensionKey + "' missing from pack " + packDir.getAbsolutePath());
        }

        long seed = seedOverride == Long.MIN_VALUE ? level.getSeed() : seedOverride;
        validateDimensionContract(dimension, level);
        File worldFolder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).toFile();
        IrisWorld world = IrisWorld.builder()
                .platformIdentity(level.dimension().identifier().toString())
                .name(level.dimension().identifier().toString().replace(':', '_'))
                .seed(seed)
                .worldFolder(worldFolder)
                .minHeight(dimension.getMinHeight())
                .maxHeight(dimension.getMaxHeight())
                .platformWorld(new ModdedPlatformWorld(level))
                .build();
        Engine engine = new IrisEngine(new EngineTarget(world, dimension, data), false);

        LOGGER.info("Iris engine up for {}: pack={} dim={} seed={} height={}..{}",
                level.dimension().identifier(), packDir.getAbsolutePath(), dimension.getLoadKey(), seed, dimension.getMinHeight(), dimension.getMaxHeight());
        return engine;
    }

    private static void validateDimensionContract(IrisDimension dimension, ServerLevel level) {
        DimensionType actualType = level.dimensionType();
        String actualTypeKey = level.dimensionTypeRegistration().unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("<unregistered>");
        IrisDimensionRuntimeContract expected = IrisDimensionRuntimeContract.expected(dimension, "irisworldgen");
        IrisDimensionRuntimeContract actual = new IrisDimensionRuntimeContract(
                actualTypeKey,
                actualType.minY(),
                actualType.height(),
                actualType.logicalHeight());
        String runtimeName = "Modded level '" + level.dimension().identifier() + "'";
        expected.requireExact(runtimeName, actual);
        expected.requireHeight(runtimeName, level.getMinY(), level.getHeight());
    }

    public static File packFolder(String pack) {
        return ModdedEngineBootstrap.loader().configDir()
                .resolve("irisworldgen")
                .resolve("packs")
                .resolve(pack)
                .toFile();
    }

    static File resolvePack(String pack, String dimensionKey) {
        File packDir = packFolder(pack);
        if (packDir.isDirectory()) {
            return packDir;
        }

        String parity = System.getProperty("iris.parity");
        if (parity != null) {
            String parityPath = parity;
            int lastColon = parity.lastIndexOf(':');
            if (lastColon > 0) {
                try {
                    Integer.parseInt(parity.substring(lastColon + 1));
                    parityPath = parity.substring(0, lastColon);
                } catch (NumberFormatException ignored) {
                }
            }
            File parityPack = new File(parityPath);
            if (parityPack.isDirectory()) {
                LOGGER.warn("Iris pack missing at {}; falling back to parity pack {}", packDir.getAbsolutePath(), parityPack.getAbsolutePath());
                return parityPack;
            }
        }

        LOGGER.error("===============================================================");
        LOGGER.error("Iris pack '{}' is not installed.", pack);
        LOGGER.error("Expected a pack folder at: {}", packDir.getAbsolutePath());
        LOGGER.error("Install an Iris pack there (the folder must contain dimensions/{}.json) and restart the server.", dimensionKey);
        LOGGER.error("===============================================================");
        throw new IllegalStateException("Iris pack not installed: " + packDir.getAbsolutePath());
    }

    public static void shutdown() {
        for (Map.Entry<ServerLevel, Engine> entry : ENGINES.entrySet()) {
            Engine engine = entry.getValue();
            try {
                if (!engine.isClosed()) {
                    engine.close();
                }
                LOGGER.info("Iris engine closed for {}", entry.getKey().dimension().identifier());
            } catch (Throwable e) {
                LOGGER.error("Iris engine close failed for {}", entry.getKey().dimension().identifier(), e);
            }
        }
        ENGINES.clear();
    }
}

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

import art.arcane.iris.core.protocol.EngineResolver;
import art.arcane.iris.core.protocol.IrisProtocolServer;
import art.arcane.iris.core.protocol.IrisSession;
import art.arcane.iris.core.protocol.IrisSessionRegistry;
import art.arcane.iris.core.protocol.IrisVisionRequestService;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.protocol.IrisProtocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ModdedProtocolHandler {
    private static final long SERVER_CAPABILITIES = IrisProtocol.CAPABILITY_PREGEN | IrisProtocol.CAPABILITY_VISION | IrisProtocol.CAPABILITY_CURSOR;
    private static final int DIMENSION_SYNC_INTERVAL_TICKS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    private static final ConcurrentHashMap<String, Engine> SESSION_ENGINES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SESSION_LEVELS = new ConcurrentHashMap<>();

    private static volatile ModdedProtocolChannel channel;
    private static volatile IrisSessionRegistry registry;
    private static volatile IrisProtocolServer protocolServer;
    private static volatile ModdedProtocolTransport transport;
    private static int dimensionSyncTicks;

    private ModdedProtocolHandler() {
    }

    public static void bindChannel(ModdedProtocolChannel boundChannel) {
        channel = Objects.requireNonNull(boundChannel, "protocol channel");
    }

    public static void start(MinecraftServer server) {
        ModdedProtocolChannel boundChannel = channel;
        if (server == null || boundChannel == null) {
            return;
        }
        SESSION_ENGINES.clear();
        SESSION_LEVELS.clear();
        dimensionSyncTicks = 0;
        IrisSessionRegistry sessionRegistry = new IrisSessionRegistry();
        ModdedProtocolTransport serverTransport = new ModdedProtocolTransport(server, boundChannel);
        IrisProtocolServer protocol = new IrisProtocolServer(sessionRegistry, SERVER_CAPABILITIES, brand(), true);
        EngineResolver engineResolver = (String sessionId) -> {
            Engine engine = SESSION_ENGINES.get(sessionId);
            return engine == null || engine.isClosed() ? null : engine;
        };
        protocol.setEngineResolver(engineResolver);
        protocol.setVisionTileHandler(IrisVisionRequestService.create(engineResolver, sessionRegistry));
        registry = sessionRegistry;
        transport = serverTransport;
        protocolServer = protocol;
        IrisServices.register(IrisProtocolServer.class, protocol);
        if (server.getPlayerList() == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sessionRegistry.register(new IrisSession(player.getUUID().toString(), serverTransport));
        }
    }

    public static void stop() {
        IrisServices.remove(IrisProtocolServer.class);
        IrisSessionRegistry current = registry;
        if (current != null) {
            for (IrisSession session : current.all()) {
                current.unregister(session.id());
            }
        }
        SESSION_ENGINES.clear();
        SESSION_LEVELS.clear();
        dimensionSyncTicks = 0;
        registry = null;
        protocolServer = null;
        transport = null;
    }

    public static void onPlayerJoin(ServerPlayer player) {
        IrisSessionRegistry current = registry;
        ModdedProtocolTransport currentTransport = transport;
        if (player == null || current == null || currentTransport == null) {
            return;
        }
        current.register(new IrisSession(player.getUUID().toString(), currentTransport));
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        IrisSessionRegistry current = registry;
        if (player == null || current == null) {
            return;
        }
        String sessionId = player.getUUID().toString();
        current.unregister(sessionId);
        SESSION_ENGINES.remove(sessionId);
        SESSION_LEVELS.remove(sessionId);
    }

    public static void onInbound(ServerPlayer player, byte[] frame) {
        IrisProtocolServer current = protocolServer;
        if (player == null || frame == null || current == null) {
            return;
        }
        String sessionId = player.getUUID().toString();
        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler == null) {
            current.onClientFrame(sessionId, frame);
            return;
        }
        scheduler.global(() -> current.onClientFrame(sessionId, frame));
    }

    public static void tickDimensionSync(MinecraftServer server) {
        IrisSessionRegistry current = registry;
        IrisProtocolServer protocol = protocolServer;
        if (server == null || current == null || protocol == null) {
            return;
        }
        dimensionSyncTicks++;
        if (dimensionSyncTicks < DIMENSION_SYNC_INTERVAL_TICKS) {
            return;
        }
        dimensionSyncTicks = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String sessionId = player.getUUID().toString();
            IrisSession session = current.get(sessionId);
            if (session == null || !session.isReady()) {
                continue;
            }
            ServerLevel level = player.level();
            String levelId = level.dimension().identifier().toString();
            Engine cached = SESSION_ENGINES.get(sessionId);
            if (levelId.equals(SESSION_LEVELS.get(sessionId)) && (cached == null || !cached.isClosed())) {
                continue;
            }
            if (syncDimension(protocol, sessionId, level, levelId)) {
                SESSION_LEVELS.put(sessionId, levelId);
            }
        }
    }

    private static boolean syncDimension(IrisProtocolServer protocol, String sessionId, ServerLevel level, String levelId) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        Engine engine = generator instanceof IrisModdedChunkGenerator irisGenerator ? resolveEngine(level, irisGenerator) : null;
        if (generator instanceof IrisModdedChunkGenerator && engine == null) {
            return false;
        }
        long seed = level.getSeed();
        if (engine != null) {
            SESSION_ENGINES.put(sessionId, engine);
            protocol.sendDimensionStatus(sessionId, engine.getDimension().getLoadKey(), engine.getData().getDataFolder().getName(),
                    seed, engine.getMinHeight(), engine.getMaxHeight(), true);
            return true;
        }
        SESSION_ENGINES.remove(sessionId);
        protocol.sendDimensionStatus(sessionId, levelId, "", seed, level.getMinY(), level.getMinY() + level.getHeight(), false);
        return true;
    }

    private static Engine resolveEngine(ServerLevel level, IrisModdedChunkGenerator generator) {
        try {
            return generator.commandEngine();
        } catch (Throwable failure) {
            LOGGER.error("Iris dimension status engine lookup failed for {}", level.dimension().identifier(), failure);
            return null;
        }
    }

    private static String brand() {
        return "Iris " + ModdedEngineBootstrap.loader().modVersion() + " (" + ModdedEngineBootstrap.loader().platformName() + ")";
    }
}

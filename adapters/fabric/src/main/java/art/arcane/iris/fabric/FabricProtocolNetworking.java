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

package art.arcane.iris.fabric;

import art.arcane.iris.modded.ModdedIrisPayload;
import art.arcane.iris.modded.ModdedProtocolChannel;
import art.arcane.iris.modded.ModdedProtocolHandler;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class FabricProtocolNetworking {
    private FabricProtocolNetworking() {
    }

    public static void install() {
        PayloadTypeRegistry.clientboundPlay().register(ModdedIrisPayload.TYPE, ModdedIrisPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModdedIrisPayload.TYPE, ModdedIrisPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ModdedIrisPayload.TYPE,
                (payload, context) -> ModdedProtocolHandler.onInbound(context.player(), payload.data()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ModdedProtocolHandler.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ModdedProtocolHandler.onPlayerDisconnect(handler.player));
        ModdedProtocolHandler.bindChannel(new FabricProtocolChannel());
    }

    private static final class FabricProtocolChannel implements ModdedProtocolChannel {
        @Override
        public boolean canReceive(ServerPlayer player) {
            return ServerPlayNetworking.canSend(player, ModdedIrisPayload.TYPE);
        }

        @Override
        public void send(ServerPlayer player, ModdedIrisPayload payload) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}

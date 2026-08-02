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

package art.arcane.iris.forge;

import art.arcane.iris.modded.ModdedIrisPayload;
import art.arcane.iris.modded.ModdedProtocolChannel;
import art.arcane.iris.modded.ModdedProtocolHandler;
import art.arcane.iris.spi.protocol.IrisProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraftforge.network.PacketDistributor;

public final class ForgeProtocolNetworking {
    private static volatile Channel<CustomPacketPayload> channel;

    private ForgeProtocolNetworking() {
    }

    public static void register() {
        Channel<CustomPacketPayload> boundChannel = ChannelBuilder.named(Identifier.parse(IrisProtocol.CHANNEL))
                .optional()
                .payloadChannel()
                .protocol(NetworkProtocol.PLAY)
                .bidirectional()
                .add(ModdedIrisPayload.TYPE, ModdedIrisPayload.STREAM_CODEC, ForgeProtocolNetworking::onPayload)
                .build();
        channel = boundChannel;
        ModdedProtocolHandler.bindChannel(new ForgeProtocolChannel(boundChannel));
    }

    public static Channel<CustomPacketPayload> channel() {
        return channel;
    }

    private static void onPayload(ModdedIrisPayload payload, CustomPayloadEvent.Context context) {
        context.setPacketHandled(true);
        if (context.isClientSide()) {
            ForgeClientProtocol.handleInbound(context, payload.data());
            return;
        }
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        ModdedProtocolHandler.onInbound(player, payload.data());
    }

    private static final class ForgeProtocolChannel implements ModdedProtocolChannel {
        private final Channel<CustomPacketPayload> channel;

        private ForgeProtocolChannel(Channel<CustomPacketPayload> channel) {
            this.channel = channel;
        }

        @Override
        public boolean canReceive(ServerPlayer player) {
            // Forge tracks the channels the remote side announced during handshake. Without this the
            // server pushes Iris payloads at vanilla clients, which drop them as unknown custom
            // payloads. Mirrors the NeoForge ICommonPacketListener.hasChannel check.
            return channel.isRemotePresent(player.connection.getConnection());
        }

        @Override
        public void send(ServerPlayer player, ModdedIrisPayload payload) {
            channel.send(payload, PacketDistributor.PLAYER.with(player));
        }
    }
}

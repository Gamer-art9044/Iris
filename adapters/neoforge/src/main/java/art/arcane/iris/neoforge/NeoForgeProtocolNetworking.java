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

package art.arcane.iris.neoforge;

import art.arcane.iris.modded.ModdedIrisPayload;
import art.arcane.iris.modded.ModdedProtocolChannel;
import art.arcane.iris.modded.ModdedProtocolHandler;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class NeoForgeProtocolNetworking {
    private NeoForgeProtocolNetworking() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> onRegisterPayloads(event));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ModdedProtocolHandler.onPlayerJoin(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ModdedProtocolHandler.onPlayerDisconnect(player);
            }
        });
        ModdedProtocolHandler.bindChannel(new NeoForgeProtocolChannel());
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            IrisNeoForgeClient.registerBidirectional(registrar);
        } else {
            registrar.playBidirectional(ModdedIrisPayload.TYPE, ModdedIrisPayload.STREAM_CODEC,
                    NeoForgeProtocolNetworking::onInbound);
        }
    }

    static void onInbound(ModdedIrisPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ModdedProtocolHandler.onInbound(player, payload.data());
        }
    }

    private static final class NeoForgeProtocolChannel implements ModdedProtocolChannel {
        @Override
        public boolean canReceive(ServerPlayer player) {
            return ((ICommonPacketListener) player.connection).hasChannel(ModdedIrisPayload.TYPE);
        }

        @Override
        public void send(ServerPlayer player, ModdedIrisPayload payload) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}

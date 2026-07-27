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

import art.arcane.iris.client.IrisClient;
import art.arcane.iris.client.IrisClientHud;
import art.arcane.iris.client.IrisClientKeybinds;
import art.arcane.iris.modded.ModdedIrisPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class IrisNeoForgeClient {
    private IrisNeoForgeClient() {
    }

    public static void registerBidirectional(PayloadRegistrar registrar) {
        registrar.playBidirectional(ModdedIrisPayload.TYPE, ModdedIrisPayload.STREAM_CODEC,
                NeoForgeProtocolNetworking::onInbound,
                (payload, context) -> context.enqueueWork(() -> IrisClient.onInbound(payload.data())));
    }

    public static void init(IEventBus modBus) {
        IrisClient.bindSender(frame -> ClientPacketDistributor.sendToServer(new ModdedIrisPayload(frame)));
        modBus.addListener((RegisterGuiLayersEvent event) ->
                event.registerAboveAll(IrisClient.HUD_ELEMENT_ID, (graphics, delta) -> IrisClientHud.render(graphics)));
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(IrisClientKeybinds.TOGGLE_HUD);
            event.register(IrisClientKeybinds.OPEN_MAP);
            event.register(IrisClientKeybinds.TOGGLE_WHAT);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> IrisClient.onWorldJoin());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> IrisClient.onDisconnect());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            IrisClient.tick();
            IrisClientKeybinds.pollToggle();
        });
    }
}

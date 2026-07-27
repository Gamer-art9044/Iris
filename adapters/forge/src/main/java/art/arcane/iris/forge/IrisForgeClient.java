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

import art.arcane.iris.client.IrisClient;
import art.arcane.iris.client.IrisClientHud;
import art.arcane.iris.client.IrisClientKeybinds;
import art.arcane.iris.modded.ModdedIrisPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.PacketDistributor;

public final class IrisForgeClient {
    private IrisForgeClient() {
    }

    public static void init() {
        IrisClient.bindSender(IrisForgeClient::sendToServer);
        AddGuiOverlayLayersEvent.BUS.addListener((AddGuiOverlayLayersEvent event) ->
                event.getLayeredDraw().add(IrisClient.HUD_ELEMENT_ID, (graphics, delta) -> IrisClientHud.render(graphics)));
        RegisterKeyMappingsEvent.BUS.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(IrisClientKeybinds.TOGGLE_HUD);
            event.register(IrisClientKeybinds.OPEN_MAP);
            event.register(IrisClientKeybinds.TOGGLE_WHAT);
        });
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> IrisClient.onWorldJoin());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> IrisClient.onDisconnect());
        InputEvent.Key.BUS.addListener((InputEvent.Key event) -> IrisClientKeybinds.pollToggle());
        TickEvent.ClientTickEvent.Post.BUS.addListener(
                (TickEvent.ClientTickEvent.Post event) -> IrisClient.tick());
    }

    private static void sendToServer(byte[] frame) {
        Channel<CustomPacketPayload> channel = ForgeProtocolNetworking.channel();
        if (channel == null) {
            return;
        }
        channel.send(new ModdedIrisPayload(frame), PacketDistributor.SERVER.noArg());
    }
}

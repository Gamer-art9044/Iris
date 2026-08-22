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

import art.arcane.iris.client.IrisClient;
import art.arcane.iris.client.IrisClientHud;
import art.arcane.iris.client.IrisClientKeybinds;
import art.arcane.iris.modded.ModdedIrisPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

public final class IrisFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ModdedIrisPayload.TYPE,
                (payload, context) -> IrisClient.onInbound(payload.data()));
        IrisClient.bindSender(frame -> {
            if (ClientPlayNetworking.canSend(ModdedIrisPayload.TYPE)) {
                ClientPlayNetworking.send(new ModdedIrisPayload(frame));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> IrisClient.onWorldJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> IrisClient.onDisconnect());
        KeyMappingHelper.registerKeyMapping(IrisClientKeybinds.TOGGLE_HUD);
        KeyMappingHelper.registerKeyMapping(IrisClientKeybinds.OPEN_MAP);
        KeyMappingHelper.registerKeyMapping(IrisClientKeybinds.TOGGLE_WHAT);
        HudElementRegistry.addLast(IrisClient.HUD_ELEMENT_ID, (graphics, delta) -> IrisClientHud.render(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            IrisClient.tick();
            IrisClientHud.tick();
            IrisClientKeybinds.pollToggle();
        });
    }
}

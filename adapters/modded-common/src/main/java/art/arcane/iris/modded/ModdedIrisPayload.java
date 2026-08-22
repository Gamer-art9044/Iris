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

import art.arcane.iris.spi.protocol.IrisProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ModdedIrisPayload(byte[] data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ModdedIrisPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.parse(IrisProtocol.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModdedIrisPayload> STREAM_CODEC = CustomPacketPayload.codec(ModdedIrisPayload::write, ModdedIrisPayload::read);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static ModdedIrisPayload read(RegistryFriendlyByteBuf buffer) {
        return new ModdedIrisPayload(buffer.readByteArray(IrisProtocol.MAX_FRAME_BYTES));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeByteArray(data);
    }
}

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

package art.arcane.iris.modded.api;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public record ModdedBlockData(BlockState state, boolean deferredPlacement) {
    public ModdedBlockData {
        Objects.requireNonNull(state);
    }

    public static ModdedBlockData direct(BlockState state) {
        return new ModdedBlockData(state, false);
    }

    public static ModdedBlockData deferred(BlockState state) {
        return new ModdedBlockData(state, true);
    }
}

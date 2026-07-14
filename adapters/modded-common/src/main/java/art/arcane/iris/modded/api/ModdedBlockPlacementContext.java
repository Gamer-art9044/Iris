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

import art.arcane.iris.engine.framework.Engine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;

public record ModdedBlockPlacementContext(
        Engine engine,
        ServerLevel level,
        BlockPos position,
        Identifier blockId,
        Map<String, String> state,
        BlockState blockState) {
    public ModdedBlockPlacementContext {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(level);
        Objects.requireNonNull(position);
        Objects.requireNonNull(blockId);
        state = Map.copyOf(state);
        Objects.requireNonNull(blockState);
    }
}

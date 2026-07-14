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

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.Map;

public interface ModdedDataProvider {
    String modId();

    default boolean isReady() {
        return true;
    }

    Collection<Identifier> getTypes(ModdedDataType type);

    boolean isValidProvider(Identifier id, ModdedDataType type);

    default ModdedBlockData getBlockData(Identifier blockId, Map<String, String> state) {
        return null;
    }

    default void processBlockPlacement(ModdedBlockPlacementContext context) {
    }

    default Entity spawnMob(ServerLevel level, double x, double y, double z, Identifier entityId) {
        return null;
    }

    default void init() {
    }
}

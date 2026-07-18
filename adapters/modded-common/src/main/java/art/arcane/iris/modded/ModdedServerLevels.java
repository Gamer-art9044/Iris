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

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class ModdedServerLevels implements ModdedServerAccess {
    private final Consumer<MinecraftServer> levelCacheInvalidator;

    public ModdedServerLevels(Consumer<MinecraftServer> levelCacheInvalidator) {
        this.levelCacheInvalidator = Objects.requireNonNull(levelCacheInvalidator);
    }

    @Override
    public Executor levelExecutor(MinecraftServer server) {
        return server.executor;
    }

    @Override
    public LevelStorageSource.LevelStorageAccess levelStorage(MinecraftServer server) {
        return server.storageSource;
    }

    @Override
    public ServerLevel putLevel(MinecraftServer server, ResourceKey<Level> key, ServerLevel level) {
        ServerLevel previous = server.levels.put(key, level);
        if (previous != level) {
            levelCacheInvalidator.accept(server);
        }
        return previous;
    }

    @Override
    public ServerLevel putLevelIfAbsent(MinecraftServer server, ResourceKey<Level> key, ServerLevel level) {
        ServerLevel previous = server.levels.putIfAbsent(key, level);
        if (previous == null) {
            levelCacheInvalidator.accept(server);
        }
        return previous;
    }

    @Override
    public ServerLevel removeLevel(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel removed = server.levels.remove(key);
        if (removed != null) {
            levelCacheInvalidator.accept(server);
        }
        return removed;
    }

    @Override
    public boolean hasLevel(MinecraftServer server, ResourceKey<Level> key) {
        return server.levels.containsKey(key);
    }
}

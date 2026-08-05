/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.link;

import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;
import org.mvplugins.multiverse.core.world.reasons.RemoveFailureReason;

import java.lang.reflect.Field;

public class MultiverseCoreLink {
    public boolean removeFromConfig(World world) {
        return removeFromConfig(world.getName());
    }

    public boolean removeFromConfig(String world) {
        if (!isActive()) {
            return false;
        }
        WorldManager manager = worldManager();
        MultiverseWorld multiverseWorld = manager.getWorld(world).getOrElse((MultiverseWorld) null);
        if (multiverseWorld == null) {
            return false;
        }
        Attempt<String, RemoveFailureReason> removal = manager.removeWorld(RemoveWorldOptions.world(multiverseWorld));
        if (removal.isFailure()) {
            throw new IllegalStateException("Multiverse refused to remove world \"" + world + "\": "
                    + removal.getFailureMessage());
        }
        manager.saveWorldsConfig().get();
        return true;
    }

    @SneakyThrows
    public void updateWorld(World bukkitWorld, String pack) {
        if (!isActive()) {
            return;
        }
        String generator = "Iris:" + pack;
        WorldManager manager = worldManager();
        MultiverseWorld multiverseWorld = manager.getWorld(bukkitWorld).getOrElse(() -> {
            ImportWorldOptions options = ImportWorldOptions.worldName(bukkitWorld.getName())
                    .generator(generator)
                    .environment(bukkitWorld.getEnvironment())
                    .useSpawnAdjust(false);
            return manager.importWorld(options).get();
        });

        multiverseWorld.setAutoLoad(false);
        if (!generator.equals(multiverseWorld.getGenerator())) {
            Field field = MultiverseWorld.class.getDeclaredField("worldConfig");
            field.setAccessible(true);

            Object config = field.get(multiverseWorld);
            config.getClass()
                    .getDeclaredMethod("setGenerator", String.class)
                    .invoke(config, generator);
        }

        manager.saveWorldsConfig().get();
    }

    private WorldManager worldManager() {
        MultiverseCoreApi api = MultiverseCoreApi.get();
        return api.getWorldManager();
    }

    private boolean isActive() {
        return Bukkit.getPluginManager().isPluginEnabled("Multiverse-Core");
    }
}

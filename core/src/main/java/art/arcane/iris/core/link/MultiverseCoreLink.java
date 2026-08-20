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

import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.spi.IrisLogging;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;
import org.mvplugins.multiverse.core.world.reasons.RemoveFailureReason;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Multiverse indexes its world store by NamespacedKey and by the Bukkit world name it saw when the
 * world was imported, and records that name as {@code legacy-world-name}. Iris creates and registers
 * its worlds under the Paper startup name ({@code <level>_<namespace>_<key>}), so the name Multiverse
 * sees at import is already the name it will see again after every restart, and it is the name every
 * Iris unregistration uses.
 */
public class MultiverseCoreLink {
    /**
     * Best-effort scrub for worlds Iris never registered (studio worlds). Multiverse not knowing the
     * world is the normal case here, so a miss is not worth a warning.
     */
    public boolean removeIfPresent(World world) {
        String worldName = Objects.requireNonNull(world, "world").getName();
        if (!isActive()) {
            return false;
        }
        WorldManager manager = worldManager();
        MultiverseWorld multiverseWorld = resolve(manager, worldName);
        if (multiverseWorld == null) {
            return false;
        }
        return remove(manager, multiverseWorld, worldName);
    }

    /**
     * Authoritative unregistration for a world Iris registered. A miss leaves a ghost entry behind in
     * Multiverse's worlds.yml, so it is reported.
     */
    public boolean removeFromConfig(String configuredWorldName) {
        String worldName = requireWorldName(configuredWorldName);
        if (!isActive()) {
            IrisLogging.debug("Multiverse is not enabled; skipped unregistering \"" + worldName + "\".");
            return false;
        }
        WorldManager manager = worldManager();
        MultiverseWorld multiverseWorld = resolve(manager, worldName);
        if (multiverseWorld == null) {
            IrisLogging.warn("Multiverse has no world registered as %s; its worlds.yml entry was left as-is.",
                    worldName);
            return false;
        }
        return remove(manager, multiverseWorld, worldName);
    }

    @SneakyThrows
    public void updateWorld(World bukkitWorld, String configuredWorldName, String pack) {
        World world = Objects.requireNonNull(bukkitWorld, "bukkitWorld");
        String worldName = requireWorldName(configuredWorldName);
        if (!isActive()) {
            return;
        }
        if (!worldName.equals(world.getName())) {
            // Multiverse records the live name as legacy-world-name. A live name that is not the
            // startup name makes it re-import the world next boot and collide with its own config key.
            IrisLogging.warn("World %s is live as %s; Multiverse will record the live name.",
                    worldName, world.getName());
        }
        String generator = "Iris:" + pack;
        WorldManager manager = worldManager();
        MultiverseWorld multiverseWorld = manager.getWorld(world)
                .orElse(() -> manager.getWorld(worldName))
                .getOrElse(() -> {
                    // Import through the live world so Multiverse binds its own config key to the key
                    // Paper gave the world and records the startup name the world was created under.
                    ImportWorldOptions options = ImportWorldOptions.worldName(world.getName())
                            .generator(generator)
                            .environment(world.getEnvironment())
                            .useSpawnAdjust(false);
                    return manager.importWorld(options).get();
                });

        multiverseWorld.setAutoLoad(false);
        if (!generator.equals(multiverseWorld.getGenerator())) {
            setWorldConfigString(multiverseWorld, "setGenerator", generator);
        }

        manager.saveWorldsConfig().get();
    }

    private boolean remove(WorldManager manager, MultiverseWorld multiverseWorld, String worldName) {
        Attempt<String, RemoveFailureReason> removal = manager.removeWorld(RemoveWorldOptions.world(multiverseWorld));
        if (removal.isFailure()) {
            throw new IllegalStateException("Multiverse refused to remove world \"" + worldName + "\": "
                    + removal.getFailureMessage());
        }
        manager.saveWorldsConfig().get();
        return true;
    }

    private MultiverseWorld resolve(WorldManager manager, String worldName) {
        for (String candidate : lookupNames(worldName, IrisWorldStorage.levelRoot().getName())) {
            MultiverseWorld multiverseWorld = manager.getWorld(candidate).getOrElse((MultiverseWorld) null);
            if (multiverseWorld != null) {
                return multiverseWorld;
            }
        }
        return null;
    }

    /**
     * worlds.yml files written by Iris builds that created keyed worlds under {@code <namespace>_<key>}
     * still carry that name, so a startup name must also resolve through the Multiverse config key it
     * encodes or those entries can never be removed.
     */
    static List<String> lookupNames(String worldName, String levelName) {
        List<String> candidates = new ArrayList<>(2);
        candidates.add(worldName);
        try {
            NamespacedKey key = IrisWorldStorage.managedKeyFromName(worldName, levelName);
            if (IrisWorldStorage.configuredWorldName(key, levelName).equals(worldName)) {
                candidates.add(key.toString());
            }
        } catch (IllegalArgumentException ignored) {
            // Not an Iris startup name; the plain name is the only identity Multiverse can have.
        }
        return List.copyOf(candidates);
    }

    private static void setWorldConfigString(MultiverseWorld world, String setter, String value) throws Exception {
        Field field = MultiverseWorld.class.getDeclaredField("worldConfig");
        field.setAccessible(true);

        Object config = field.get(world);
        Method method = config.getClass().getDeclaredMethod(setter, String.class);
        method.setAccessible(true);
        method.invoke(config, value);
    }

    private static String requireWorldName(String worldName) {
        String name = Objects.requireNonNull(worldName, "worldName").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("World name cannot be empty.");
        }
        return name;
    }

    private WorldManager worldManager() {
        MultiverseCoreApi api = MultiverseCoreApi.get();
        return api.getWorldManager();
    }

    private boolean isActive() {
        return Bukkit.getPluginManager().isPluginEnabled("Multiverse-Core");
    }
}

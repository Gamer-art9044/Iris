package art.arcane.iris.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldCreator;

import java.io.File;

/**
 * WorldCreator.ofKey and WorldCreator#key are Paper-API-only. Once a call throws
 * NoSuchMethodError (plain Spigot/CraftBukkit) this flips and every later call goes
 * straight to the fallback. The fallback derives names/keys through IrisWorldStorage's
 * current configured-name mapping so persistent Spigot worlds round-trip without changing
 * their startup directory.
 */
public final class WorldCreatorCompat {
    private static volatile boolean keyedCreatorsUnavailable;

    private WorldCreatorCompat() {
    }

    public static WorldCreator ofKey(NamespacedKey worldKey) {
        return ofKey(worldKey, IrisWorldStorage.logicalName(worldKey));
    }

    public static WorldCreator ofKey(NamespacedKey worldKey, String fallbackWorldName) {
        WorldCreator keyedCreator = keyedCreator(worldKey);
        if (keyedCreator != null) {
            return keyedCreator;
        }
        return new WorldCreator(fallbackWorldName);
    }

    public static WorldCreator ofPersistentKey(NamespacedKey worldKey) {
        WorldCreator keyedCreator = keyedCreator(worldKey);
        if (keyedCreator != null) {
            return keyedCreator;
        }
        return new WorldCreator(fallbackPersistentName(worldKey, IrisWorldStorage.levelRoot().getName()));
    }

    public static File persistentDimensionRoot(NamespacedKey worldKey) {
        if (keyedCreator(worldKey) != null) {
            return IrisWorldStorage.requireSafePersistentDimensionRoot(worldKey);
        }
        return IrisWorldStorage.configuredDimensionRoot(
                Bukkit.getWorldContainer(),
                IrisWorldStorage.levelRoot(),
                worldKey
        );
    }

    public static File persistentLevelRoot(NamespacedKey worldKey) {
        if (keyedCreator(worldKey) != null) {
            return persistentDimensionRoot(worldKey);
        }
        return IrisWorldStorage.configuredLevelRoot(
                Bukkit.getWorldContainer(),
                IrisWorldStorage.levelRoot(),
                worldKey
        );
    }

    public static NamespacedKey keyOf(WorldCreator creator) {
        if (!keyedCreatorsUnavailable) {
            try {
                return creator.key();
            } catch (NoSuchMethodError e) {
                keyedCreatorsUnavailable = true;
            }
        }
        return IrisWorldStorage.keyFromConfiguredWorldName(
                creator.name(),
                IrisWorldStorage.levelRoot().getName()
        );
    }

    static String fallbackName(NamespacedKey worldKey, String levelName) {
        return IrisWorldStorage.logicalName(worldKey, levelName);
    }

    static String fallbackPersistentName(NamespacedKey worldKey, String levelName) {
        return IrisWorldStorage.configuredWorldName(worldKey, levelName);
    }

    static NamespacedKey fallbackKey(String creatorName, String levelName) {
        return IrisWorldStorage.keyFromConfiguredWorldName(creatorName, levelName);
    }

    private static WorldCreator keyedCreator(NamespacedKey worldKey) {
        if (keyedCreatorsUnavailable) {
            return null;
        }
        try {
            return WorldCreator.ofKey(worldKey);
        } catch (NoSuchMethodError e) {
            keyedCreatorsUnavailable = true;
            return null;
        }
    }
}

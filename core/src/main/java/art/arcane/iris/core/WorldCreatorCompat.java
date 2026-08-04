package art.arcane.iris.core;

import org.bukkit.NamespacedKey;
import org.bukkit.WorldCreator;

/**
 * WorldCreator.ofKey and WorldCreator#key are Paper-API-only. Once a call throws
 * NoSuchMethodError (plain Spigot/CraftBukkit) this flips and every later call goes
 * straight to the fallback. The fallback derives names/keys through IrisWorldStorage's
 * logical mapping so keyFromName(creator.name()) round-trips on Spigot.
 */
public final class WorldCreatorCompat {
    private static volatile boolean keyedCreatorsUnavailable;

    private WorldCreatorCompat() {
    }

    public static WorldCreator ofKey(NamespacedKey worldKey) {
        if (!keyedCreatorsUnavailable) {
            try {
                return WorldCreator.ofKey(worldKey);
            } catch (NoSuchMethodError e) {
                keyedCreatorsUnavailable = true;
            }
        }
        return new WorldCreator(IrisWorldStorage.logicalName(worldKey));
    }

    public static NamespacedKey keyOf(WorldCreator creator) {
        if (!keyedCreatorsUnavailable) {
            try {
                return creator.key();
            } catch (NoSuchMethodError e) {
                keyedCreatorsUnavailable = true;
            }
        }
        return IrisWorldStorage.keyFromName(creator.name());
    }

    static String fallbackName(NamespacedKey worldKey, String levelName) {
        return IrisWorldStorage.logicalName(worldKey, levelName);
    }

    static NamespacedKey fallbackKey(String creatorName, String levelName) {
        return IrisWorldStorage.keyFromName(creatorName, levelName);
    }
}

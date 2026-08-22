package art.arcane.iris.modded;

import art.arcane.iris.spi.IrisLogging;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.Set;

public final class ModdedEntityPersistence {
    private static final String NON_PERSISTENT_TAG = "iris_non_persistent";

    private ModdedEntityPersistence() {
    }

    public static void configure(Entity entity, boolean persistent) {
        if (persistent) {
            entity.removeTag(NON_PERSISTENT_TAG);
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired();
            }
            return;
        }
        if (!entity.entityTags().contains(NON_PERSISTENT_TAG) && !entity.addTag(NON_PERSISTENT_TAG)) {
            IrisLogging.warn("Iris could not mark generated entity '" + entity.getStringUUID() + "' as non-persistent");
        }
    }

    public static boolean shouldSave(Entity entity, boolean vanillaResult) {
        return shouldSave(entity.entityTags(), vanillaResult);
    }

    static boolean shouldSave(Set<String> tags, boolean vanillaResult) {
        return vanillaResult && !tags.contains(NON_PERSISTENT_TAG);
    }

    static void configureTags(Set<String> tags, boolean persistent) {
        if (persistent) {
            tags.remove(NON_PERSISTENT_TAG);
        } else {
            tags.add(NON_PERSISTENT_TAG);
        }
    }
}

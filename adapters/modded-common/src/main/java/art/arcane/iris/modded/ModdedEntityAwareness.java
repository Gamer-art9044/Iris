package art.arcane.iris.modded;

import art.arcane.iris.spi.IrisLogging;
import net.minecraft.world.entity.Mob;

import java.util.Set;

public final class ModdedEntityAwareness {
    private static final String UNAWARE_TAG = "iris_unaware";

    private ModdedEntityAwareness() {
    }

    public static void configure(Mob mob, boolean aware) {
        if (aware) {
            mob.removeTag(UNAWARE_TAG);
            return;
        }
        if (!mob.entityTags().contains(UNAWARE_TAG) && !mob.addTag(UNAWARE_TAG)) {
            IrisLogging.warn("Iris could not mark generated mob '" + mob.getStringUUID() + "' as unaware");
        }
    }

    public static boolean isAware(Mob mob) {
        return isAware(mob.entityTags());
    }

    static boolean isAware(Set<String> tags) {
        return !tags.contains(UNAWARE_TAG);
    }

    static void configureTags(Set<String> tags, boolean aware) {
        if (aware) {
            tags.remove(UNAWARE_TAG);
        } else {
            tags.add(UNAWARE_TAG);
        }
    }
}

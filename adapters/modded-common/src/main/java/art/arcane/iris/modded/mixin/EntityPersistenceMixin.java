package art.arcane.iris.modded.mixin;

import art.arcane.iris.modded.ModdedEntityPersistence;
import art.arcane.iris.modded.ModdedMixinFlags;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityPersistenceMixin {
    @Inject(method = "shouldBeSaved", at = @At("RETURN"), cancellable = true)
    private void iris$applyGeneratedPersistence(CallbackInfoReturnable<Boolean> info) {
        ModdedMixinFlags.markEntityPersistence();
        Entity entity = (Entity) (Object) this;
        info.setReturnValue(ModdedEntityPersistence.shouldSave(entity, info.getReturnValue()));
    }
}

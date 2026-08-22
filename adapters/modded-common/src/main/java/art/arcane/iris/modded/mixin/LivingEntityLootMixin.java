package art.arcane.iris.modded.mixin;

import art.arcane.iris.modded.ModdedDeathLoot;
import art.arcane.iris.modded.ModdedMixinFlags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityLootMixin {
    @Inject(method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V", at = @At("HEAD"), cancellable = true)
    private void iris$replaceBaseLoot(ServerLevel level, DamageSource damageSource, boolean playerKilled, CallbackInfo info) {
        ModdedMixinFlags.markLivingEntityLoot();
        if (ModdedDeathLoot.replaceBaseLoot((LivingEntity) (Object) this)) {
            info.cancel();
        }
    }
}

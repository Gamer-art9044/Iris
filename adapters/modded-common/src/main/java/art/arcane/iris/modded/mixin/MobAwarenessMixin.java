package art.arcane.iris.modded.mixin;

import art.arcane.iris.modded.ModdedEntityAwareness;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobAwarenessMixin {
    @Inject(
            method = "serverAiStep",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/Mob;noActionTime:I",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void iris$tickUnawareMob(CallbackInfo info) {
        Mob mob = (Mob) (Object) this;
        if (ModdedEntityAwareness.isAware(mob)) {
            return;
        }
        for (WrappedGoal wrappedGoal : mob.getGoalSelector().getAvailableGoals()) {
            if (wrappedGoal.getGoal() instanceof FloatGoal floatGoal) {
                if (floatGoal.canUse()) {
                    floatGoal.tick();
                }
                mob.getJumpControl().tick();
                break;
            }
        }
        info.cancel();
    }
}

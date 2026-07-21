package art.arcane.iris.fabric.mixin;

import art.arcane.iris.modded.ModdedBlockBreakHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public final class BlockItemMixin {
    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void iris$clearTreeProvenance(
            BlockPlaceContext context,
            BlockState state,
            CallbackInfoReturnable<Boolean> info
    ) {
        if (info.getReturnValue() && context.getLevel() instanceof ServerLevel level) {
            ModdedBlockBreakHandler.clearPlacedProvenance(level, context.getClickedPos());
        }
    }
}

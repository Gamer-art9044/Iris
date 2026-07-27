package art.arcane.iris.client.mixin;

import art.arcane.iris.modded.ModdedWorldgenIds;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(WorldCreationUiState.WorldTypeEntry.class)
public class IrisWorldTypeEntryMixin {
    @Shadow
    @Final
    private Holder<WorldPreset> preset;

    @Inject(method = "describePreset", at = @At("HEAD"), cancellable = true)
    private void iris$describePreset(CallbackInfoReturnable<Component> info) {
        Optional<ResourceKey<WorldPreset>> key = preset == null
                ? Optional.empty()
                : preset.unwrapKey();
        if (key.isEmpty() || !"irisworldgen".equals(key.get().identifier().getNamespace())) {
            return;
        }
        String label = ModdedWorldgenIds.displayName(key.get().identifier().getPath());
        if (label != null) {
            info.setReturnValue(Component.literal(label));
        }
    }
}

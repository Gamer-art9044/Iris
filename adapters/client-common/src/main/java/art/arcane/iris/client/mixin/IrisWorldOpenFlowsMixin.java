package art.arcane.iris.client.mixin;

import art.arcane.iris.modded.IrisModdedChunkGenerator;
import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(WorldOpenFlows.class)
public abstract class IrisWorldOpenFlowsMixin {
    @Invoker("openWorldLoadBundledResourcePack")
    protected abstract void iris$openWorldLoadBundledResourcePack(
            LevelStorageSource.LevelStorageAccess worldAccess,
            WorldStem worldStem,
            PackRepository packRepository,
            Runnable onCancel);

    @Inject(method = "confirmWorldCreation", at = @At("HEAD"), cancellable = true)
    private static void iris$confirmWorldCreation(
            Minecraft minecraft,
            CreateWorldScreen parent,
            Lifecycle lifecycle,
            Runnable task,
            boolean skipWarning,
            CallbackInfo info) {
        if (skipWarning || lifecycle == Lifecycle.stable() || !iris$selectedPresetIsIris(parent)) {
            return;
        }
        task.run();
        info.cancel();
    }

    @Inject(method = "openWorldCheckWorldStemCompatibility", at = @At("HEAD"), cancellable = true)
    private void iris$openWorldCheckWorldStemCompatibility(
            LevelStorageSource.LevelStorageAccess worldAccess,
            WorldStem worldStem,
            PackRepository packRepository,
            Runnable onCancel,
            CallbackInfo info) {
        if (!iris$containsIrisGenerator(worldStem)) {
            return;
        }
        iris$openWorldLoadBundledResourcePack(worldAccess, worldStem, packRepository, onCancel);
        info.cancel();
    }

    private static boolean iris$selectedPresetIsIris(CreateWorldScreen parent) {
        WorldCreationUiState.WorldTypeEntry worldType = parent.getUiState().getWorldType();
        Holder<WorldPreset> preset = worldType.preset();
        if (preset == null) {
            return false;
        }
        Optional<ResourceKey<WorldPreset>> key = preset.unwrapKey();
        return key.isPresent() && "irisworldgen".equals(key.get().identifier().getNamespace());
    }

    private static boolean iris$containsIrisGenerator(WorldStem worldStem) {
        Registry<LevelStem> dimensions = worldStem.registries()
                .compositeAccess()
                .lookupOrThrow(Registries.LEVEL_STEM);
        for (LevelStem dimension : dimensions) {
            if (dimension.generator() instanceof IrisModdedChunkGenerator) {
                return true;
            }
        }
        return false;
    }
}

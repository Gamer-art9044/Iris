/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.fabric.mixin;

import art.arcane.iris.fabric.FabricForcedDatapackSources;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

@Mixin(PackRepository.class)
public class PackRepositoryMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void iris$addForcedDatapackSource(RepositorySource[] sources, CallbackInfo info) {
        for (RepositorySource source : sources) {
            if (source instanceof ServerPacksSource) {
                FabricForcedDatapackSources.attach((PackRepository) (Object) this);
                return;
            }
        }
        // Client resource-pack repositories legitimately have no ServerPacksSource; a missing server-data
        // repository is reported once at boot by ModdedForcedDatapack.verifyInjected().
        LoggerFactory.getLogger("Iris").debug(
                "Iris forced datapack source not attached: no ServerPacksSource among {} source(s) {}",
                sources.length, Arrays.toString(sources));
    }
}

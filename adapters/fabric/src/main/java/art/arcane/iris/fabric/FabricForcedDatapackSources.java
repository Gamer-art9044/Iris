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

package art.arcane.iris.fabric;

import art.arcane.iris.modded.ModdedForcedDatapack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class FabricForcedDatapackSources {
    private FabricForcedDatapackSources() {
    }

    public static void attach(PackRepository repository) {
        PackRepository activeRepository = Objects.requireNonNull(repository,
                "Iris cannot attach the forced startup datapack source to a null repository");
        Set<RepositorySource> merged = new LinkedHashSet<>(activeRepository.sources);
        merged.add(ModdedForcedDatapack.repositorySource());
        activeRepository.sources = merged;
    }
}

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

package art.arcane.iris.modded;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.util.common.data.DataProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ModdedRuntimeRegistry {
    private ModdedRuntimeRegistry() {
    }

    static void ensureDimensionType(Registry<DimensionType> registry,
                                    ResourceKey<DimensionType> typeKey, String typeRef) {
        if (registry.get(typeKey).isPresent()) {
            return;
        }
        throw new IllegalStateException("Iris dimension type '" + typeRef
                + "' is not synchronized. Restart after installing the pack before creating its world.");
    }

    static void ensureCustomBiomes(RegistryAccess registryAccess, IrisDimension dimension, String pack) {
        File packFolder = ModdedWorldEngines.packFolder(pack);
        if (!packFolder.isDirectory()) {
            return;
        }
        Registry<Biome> registry = registryAccess.lookupOrThrow(Registries.BIOME);
        IrisData data = IrisData.get(packFolder);
        DataProvider provider = () -> data;
        Set<String> seen = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (IrisBiome irisBiome : dimension.getAllBiomes(provider)) {
            if (!irisBiome.isCustom()) {
                continue;
            }
            for (IrisBiomeCustom customBiome : irisBiome.getCustomDerivitives()) {
                String biomeId = customBiome.getId();
                if (!seen.add(biomeId)) {
                    continue;
                }
                String biomeRef = ModdedWorldgenIds.biomeRef(pack, dimension.getLoadKey(), biomeId);
                ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, Identifier.parse(biomeRef));
                if (registry.get(biomeKey).isEmpty()) {
                    missing.add(biomeRef);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Iris pack '" + pack + "' has " + missing.size()
                    + " unsynchronized custom biome(s). Restart before creating its world. First missing entry: "
                    + missing.getFirst());
        }
    }
}

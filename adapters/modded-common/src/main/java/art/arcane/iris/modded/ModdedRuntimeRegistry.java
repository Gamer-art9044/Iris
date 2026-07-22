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
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.core.nms.datapack.IDataFixer;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.util.common.data.DataProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class ModdedRuntimeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Object LOCK = new Object();

    private ModdedRuntimeRegistry() {
    }

    static void ensureDimensionType(RegistryAccess registryAccess, Registry<DimensionType> registry,
                                    ResourceKey<DimensionType> typeKey, String typeRef, IrisDimension dimension) {
        if (registry.get(typeKey).isPresent()) {
            return;
        }
        IDataFixer fixer = DataVersion.getLatest().get();
        String json = dimension.getDimensionType().toJson(fixer);
        DimensionType type = decode(registryAccess, DimensionType.DIRECT_CODEC, json, typeRef);
        registerIntoFrozen(registry, typeKey, type, typeRef);
        LOGGER.info("Iris registered runtime dimension type '{}'", typeRef);
    }

    static void ensureCustomBiomes(RegistryAccess registryAccess, IrisDimension dimension, String pack) {
        File packFolder = ModdedWorldEngines.packFolder(pack);
        if (!packFolder.isDirectory()) {
            return;
        }
        Registry<Biome> registry = registryAccess.lookupOrThrow(Registries.BIOME);
        IrisData data = IrisData.get(packFolder);
        DataProvider provider = () -> data;
        IDataFixer fixer = DataVersion.getLatest().get();
        String namespace = dimension.getLoadKey().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        int registered = 0;
        for (IrisBiome irisBiome : dimension.getAllBiomes(provider)) {
            if (!irisBiome.isCustom()) {
                continue;
            }
            for (IrisBiomeCustom customBiome : irisBiome.getCustomDerivitives()) {
                String biomeId = customBiome.getId();
                if (!seen.add(biomeId)) {
                    continue;
                }
                String biomeRef = namespace + ":" + biomeId;
                ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, Identifier.parse(biomeRef));
                if (registry.get(biomeKey).isPresent()) {
                    continue;
                }
                String json = customBiome.generateJson(fixer);
                Biome biome = decode(registryAccess, Biome.DIRECT_CODEC, json, biomeRef);
                registerIntoFrozen(registry, biomeKey, biome, biomeRef);
                registered++;
            }
        }
        if (registered > 0) {
            LOGGER.info("Iris registered {} runtime biome(s) for pack '{}'", registered, pack);
        }
    }

    private static <T> T decode(RegistryAccess registryAccess, Codec<T> codec, String json, String ref) {
        JsonElement element = JsonParser.parseString(json);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess);
        return codec.parse(ops, element).getOrThrow((String message) ->
                new IllegalStateException("Iris could not decode runtime registry entry '" + ref + "': " + message));
    }

    private static <T> Holder.Reference<T> registerIntoFrozen(Registry<T> registry, ResourceKey<T> key, T value, String ref) {
        if (!(registry instanceof MappedRegistry<T> mapped)) {
            throw new IllegalStateException("Iris cannot register '" + ref + "' at runtime: "
                    + registry.getClass().getName() + " is not a MappedRegistry");
        }
        synchronized (LOCK) {
            Optional<Holder.Reference<T>> raced = registry.get(key);
            if (raced.isPresent()) {
                return raced.get();
            }
            boolean wasFrozen = mapped.frozen;
            mapped.frozen = false;
            try {
                return mapped.register(key, value, RegistrationInfo.BUILT_IN);
            } finally {
                if (wasFrozen) {
                    mapped.freeze();
                }
            }
        }
    }
}

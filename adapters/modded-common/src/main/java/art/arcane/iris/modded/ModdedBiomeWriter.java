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

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBiomeWriter;
import art.arcane.iris.util.project.context.IrisContext;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ModdedBiomeWriter implements PlatformBiomeWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final String VANILLA_FALLBACK_KEY = "minecraft:plains";

    private final Supplier<MinecraftServer> server;

    public ModdedBiomeWriter(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public int biomeIdFor(String key) {
        Registry<Biome> registry = biomeRegistry();
        if (registry == null) {
            return 0;
        }
        int direct = idForKey(registry, scopedBiomeKey(key));
        if (direct >= 0) {
            return direct;
        }
        int derivative = idForDerivative(registry, key);
        if (derivative >= 0) {
            return derivative;
        }
        return fallbackId(registry);
    }

    private String scopedBiomeKey(String key) {
        IrisContext context = IrisContext.get();
        if (context == null || key == null) {
            return key;
        }
        Engine engine = context.getEngine();
        String prefix = engine.getDimension().getLoadKey() + ":";
        if (!key.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return key;
        }
        return ModdedWorldgenIds.biomeRef(engine, key.substring(prefix.length()));
    }

    @Override
    public List<PlatformBiome> allBiomes() {
        Registry<Biome> registry = biomeRegistry();
        List<PlatformBiome> biomes = new ArrayList<>();
        if (registry == null) {
            return biomes;
        }
        for (Identifier identifier : registry.keySet()) {
            Biome biome = registry.getValue(identifier);
            if (biome != null) {
                biomes.add(ModdedBiome.of(biome, identifier.toString()));
            }
        }
        return biomes;
    }

    private int idForKey(Registry<Biome> registry, String key) {
        Identifier identifier = Identifier.tryParse(key);
        if (identifier == null) {
            return -1;
        }
        Biome biome = registry.getValue(identifier);
        return biome == null ? -1 : registry.getId(biome);
    }

    private int idForDerivative(Registry<Biome> registry, String key) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon >= key.length() - 1) {
            return -1;
        }
        String dimensionLoadKey = key.substring(0, colon);
        String customBiomeId = key.substring(colon + 1);
        IrisBiome owner = findCustomBiomeOwner(dimensionLoadKey, customBiomeId);
        if (owner == null) {
            return -1;
        }
        String derivativeKey = owner.getVanillaDerivativeKey();
        if (derivativeKey == null) {
            return -1;
        }
        return idForKey(registry, derivativeKey);
    }

    private IrisBiome findCustomBiomeOwner(String dimensionLoadKey, String customBiomeId) {
        for (Engine engine : ModdedWorldEngines.activeEngines()) {
            if (engine == null || engine.isClosed()) {
                continue;
            }
            if (!dimensionLoadKey.equalsIgnoreCase(engine.getDimension().getLoadKey())) {
                continue;
            }
            for (IrisBiome biome : engine.getDimension().getAllBiomes(engine)) {
                if (!biome.isCustom()) {
                    continue;
                }
                for (IrisBiomeCustom custom : biome.getCustomDerivitives()) {
                    if (customBiomeId.equals(custom.getId())) {
                        return biome;
                    }
                }
            }
        }
        return null;
    }

    private int fallbackId(Registry<Biome> registry) {
        int plains = idForKey(registry, VANILLA_FALLBACK_KEY);
        return plains >= 0 ? plains : 0;
    }

    private Registry<Biome> biomeRegistry() {
        MinecraftServer instance = server.get();
        if (instance == null) {
            return null;
        }
        return instance.registryAccess().lookupOrThrow(Registries.BIOME);
    }
}

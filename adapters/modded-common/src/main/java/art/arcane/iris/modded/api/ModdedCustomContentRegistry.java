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

package art.arcane.iris.modded.api;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.modded.ModdedBlockResolution;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ModdedCustomContentRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final List<ModdedDataProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final Map<String, BlockState> CUSTOM_BLOCKS = new ConcurrentHashMap<>();
    private static volatile boolean scanned = false;

    private ModdedCustomContentRegistry() {
    }

    public static void registerCustomBlockData(String namespace, String key, String state) {
        if (namespace == null || key == null || state == null) {
            return;
        }
        Identifier identifier = Identifier.tryParse(namespace + ":" + key);
        if (identifier == null) {
            LOGGER.warn("Iris custom block data registration rejected invalid id {}:{}", namespace, key);
            return;
        }
        BlockState parsed;
        try {
            parsed = ModdedBlockResolution.strictParse(state).handle();
        } catch (Throwable error) {
            LOGGER.error("Iris custom block data '{}:{}' has unparseable state '{}'", namespace, key, state, error);
            return;
        }
        CUSTOM_BLOCKS.put(identifier.toString(), parsed);
        LOGGER.info("Iris registered custom block data {}:{} -> {}", namespace, key, state);
    }

    public static void register(ModdedDataProvider provider) {
        if (provider == null) {
            return;
        }
        for (ModdedDataProvider existing : PROVIDERS) {
            if (existing.modId().equals(provider.modId())) {
                LOGGER.warn("Iris custom content provider for '{}' already registered; ignoring duplicate", provider.modId());
                return;
            }
        }
        PROVIDERS.add(provider);
        try {
            provider.init();
        } catch (Throwable error) {
            LOGGER.error("Iris custom content provider '{}' failed to initialize", provider.modId(), error);
        }
        LOGGER.info("Iris registered custom content provider '{}'", provider.modId());
    }

    public static void discover() {
        if (scanned) {
            return;
        }
        scanned = true;
        ServiceLoader<ModdedDataProvider> loader = ServiceLoader.load(ModdedDataProvider.class, ModdedCustomContentRegistry.class.getClassLoader());
        for (ModdedDataProvider provider : loader) {
            register(provider);
        }
    }

    public static boolean hasProviders() {
        return !PROVIDERS.isEmpty() || !CUSTOM_BLOCKS.isEmpty();
    }

    public static ModdedBlockData resolveBlock(String key) {
        if (key == null || (PROVIDERS.isEmpty() && CUSTOM_BLOCKS.isEmpty())) {
            return null;
        }
        Identifier base = parseIdentifier(key);
        if (base == null) {
            return null;
        }
        BlockState custom = CUSTOM_BLOCKS.get(base.toString());
        if (custom != null) {
            return ModdedBlockData.direct(custom);
        }
        if (PROVIDERS.isEmpty()) {
            return null;
        }
        Map<String, String> state = parseState(key);
        for (ModdedDataProvider provider : PROVIDERS) {
            if (!provider.isReady() || !provider.isValidProvider(base, ModdedDataType.BLOCK)) {
                continue;
            }
            try {
                ModdedBlockData resolved = provider.getBlockData(base, state);
                if (resolved != null) {
                    return resolved;
                }
            } catch (Throwable error) {
                LOGGER.error("Iris custom content provider '{}' failed resolving block {}", provider.modId(), key, error);
            }
        }
        return null;
    }

    public static void processBlockPlacement(Engine engine, ServerLevel level, BlockPos position, String key) {
        Identifier base = parseIdentifier(key);
        if (base == null) {
            LOGGER.warn("Iris deferred custom block placement rejected invalid id {}", key);
            return;
        }
        Map<String, String> state = parseState(key);
        for (ModdedDataProvider provider : PROVIDERS) {
            if (!provider.isReady() || !provider.isValidProvider(base, ModdedDataType.BLOCK)) {
                continue;
            }
            try {
                provider.processBlockPlacement(new ModdedBlockPlacementContext(
                        engine, level, position.immutable(), base, state, level.getBlockState(position)));
            } catch (Throwable error) {
                LOGGER.error("Iris custom content provider '{}' failed post-placement for {} at {}", provider.modId(), key, position, error);
            }
            return;
        }
        LOGGER.warn("Iris deferred custom block placement has no provider for {}", key);
    }

    public static Entity spawnMob(ServerLevel level, double x, double y, double z, String key) {
        if (PROVIDERS.isEmpty() || level == null || key == null) {
            return null;
        }
        Identifier base = parseIdentifier(key);
        if (base == null) {
            return null;
        }
        for (ModdedDataProvider provider : PROVIDERS) {
            if (!provider.isReady() || !provider.isValidProvider(base, ModdedDataType.ENTITY)) {
                continue;
            }
            try {
                Entity entity = provider.spawnMob(level, x, y, z, base);
                if (entity != null) {
                    return entity;
                }
            } catch (Throwable error) {
                LOGGER.error("Iris custom content provider '{}' failed spawning mob {}", provider.modId(), key, error);
            }
        }
        return null;
    }

    private static Identifier parseIdentifier(String key) {
        String trimmed = key.trim();
        int bracket = trimmed.indexOf('[');
        String head = bracket < 0 ? trimmed : trimmed.substring(0, bracket);
        return Identifier.tryParse(head);
    }

    private static Map<String, String> parseState(String key) {
        Map<String, String> state = new LinkedHashMap<>();
        int open = key.indexOf('[');
        int close = key.indexOf(']');
        if (open < 0 || close < open) {
            return state;
        }
        String body = key.substring(open + 1, close);
        if (body.isEmpty()) {
            return state;
        }
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            state.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return state;
    }
}

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

package art.arcane.iris.modded.command;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.modded.ModdedBlockState;
import art.arcane.volmlib.util.localization.MessageArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class ModdedDustRevealer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final int MAX_HITS = 2048;
    private static final DustParticleOptions REVEAL_DUST = new DustParticleOptions(0xFFD24A, 1.2F);

    private ModdedDustRevealer() {
    }

    public static void reveal(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Engine engine = IrisModdedCommands.engineFor(level);
        if (engine == null) {
            player.sendSystemMessage(Component.literal(IrisLanguage.plain(RuntimeUiMessages.DUST_IRIS_WORLD_REQUIRED)));
            return;
        }
        describe(player, level, engine, pos);

        int relativeY = pos.getY() - engine.getMinHeight();
        String key = safe(() -> engine.getObjectPlacementKey(pos.getX(), relativeY, pos.getZ()));
        if (key == null) {
            return;
        }
        player.sendSystemMessage(Component.literal(IrisLanguage.plain(
                RuntimeUiMessages.DUST_FOUND_OBJECT,
                MessageArgument.untrusted("object", key)
        )));
        MinecraftServer server = level.getServer();
        BlockPos origin = pos.immutable();
        Thread thread = new Thread(() -> {
            List<BlockPos> hits = collect(engine, level, origin, key);
            server.execute(() -> {
                for (BlockPos hit : hits) {
                    level.sendParticles(player, REVEAL_DUST, true, true,
                            hit.getX() + 0.5D, hit.getY() + 0.5D, hit.getZ() + 0.5D,
                            3, 0.25D, 0.25D, 0.25D, 0.0D);
                }
                player.sendSystemMessage(Component.literal(IrisLanguage.plain(
                        hits.size() >= MAX_HITS ? RuntimeUiMessages.DUST_REVEALED_CAPPED : RuntimeUiMessages.DUST_REVEALED,
                        MessageArgument.trusted("count", hits.size()),
                        MessageArgument.untrusted("object", key)
                )));
            });
        }, "Iris Dust Revealer");
        thread.setDaemon(true);
        thread.start();
    }

    private static List<BlockPos> collect(Engine engine, ServerLevel level, BlockPos origin, String key) {
        List<BlockPos> hits = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(origin);
        visited.add(origin);
        int minY = level.getMinY();
        int maxY = minY + level.getHeight();
        try {
            while (!frontier.isEmpty() && hits.size() < MAX_HITS) {
                BlockPos current = frontier.poll();
                hits.add(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            BlockPos next = current.offset(dx, dy, dz);
                            if (next.getY() < minY || next.getY() >= maxY || visited.contains(next)) {
                                continue;
                            }
                            visited.add(next);
                            String nextKey = engine.getObjectPlacementKey(next.getX(), next.getY() - engine.getMinHeight(), next.getZ());
                            if (key.equals(nextKey)) {
                                frontier.add(next);
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            LOGGER.error("Iris dust reveal BFS failed for {}", key, e);
        }
        return hits;
    }

    private static void describe(ServerPlayer player, ServerLevel level, Engine engine, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int minHeight = engine.getMinHeight();
        int relativeY = y - minHeight;
        int surfaceRelative = safeInt(() -> engine.getHeight(x, z, true));
        int surfaceY = surfaceRelative + minHeight;
        int offset = y - surfaceY;

        String objectKey = safe(() -> engine.getObjectPlacementKey(x, relativeY, z));
        IrisBiome surfaceBiome = safe(() -> engine.getSurfaceBiome(x, z));
        IrisBiome biomeHere = safe(() -> engine.getBiome(x, relativeY, z));
        IrisBiome caveBiome = safe(() -> engine.getCaveOrMantleBiome(x, relativeY, z));
        IrisRegion region = safe(() -> engine.getRegion(x, z));

        List<String> lines = new ArrayList<>();
        lines.add(IrisLanguage.plain(
                RuntimeUiMessages.DUST_HEADER,
                MessageArgument.trusted("x", x),
                MessageArgument.trusted("y", y),
                MessageArgument.trusted("z", z)
        ));
        lines.add(IrisLanguage.plain(
                RuntimeUiMessages.DUST_BLOCK,
                MessageArgument.untrusted("block", ModdedBlockState.serialize(level.getBlockState(pos)))
        ));
        if (offset > 0) {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_POSITION_ABOVE,
                    MessageArgument.trusted("offset", offset),
                    MessageArgument.trusted("surfaceY", surfaceY)
            ));
        } else if (offset < 0) {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_POSITION_BELOW,
                    MessageArgument.trusted("offset", -offset),
                    MessageArgument.trusted("surfaceY", surfaceY)
            ));
        } else {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_POSITION_AT,
                    MessageArgument.trusted("surfaceY", surfaceY)
            ));
        }
        lines.add(IrisLanguage.plain(
                RuntimeUiMessages.DUST_OBJECT_AT_BLOCK,
                MessageArgument.untrusted(
                        "object",
                        objectKey == null ? IrisLanguage.plain(RuntimeUiMessages.DUST_NONE) : objectKey
                )
        ));
        if (surfaceBiome != null) {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_SURFACE_BIOME,
                    MessageArgument.untrusted("biome", surfaceBiome.getLoadKey())
            ));
        }
        if (biomeHere != null && (surfaceBiome == null || !biomeHere.getLoadKey().equals(surfaceBiome.getLoadKey()))) {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_BIOME_AT_Y,
                    MessageArgument.untrusted("biome", biomeHere.getLoadKey())
            ));
        }
        if (caveBiome != null && (surfaceBiome == null || !caveBiome.getLoadKey().equals(surfaceBiome.getLoadKey()))) {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_CAVE_BIOME,
                    MessageArgument.untrusted("biome", caveBiome.getLoadKey())
            ));
        }
        if (region != null) {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_REGION,
                    MessageArgument.untrusted("region", region.getLoadKey()),
                    MessageArgument.untrusted("name", region.getName())
            ));
        }
        Set<String> objects = safe(() -> engine.getObjectsAt(x >> 4, z >> 4));
        if (objects != null && !objects.isEmpty()) {
            lines.add(IrisLanguage.plain(
                    RuntimeUiMessages.DUST_OBJECTS_IN_CHUNK,
                    MessageArgument.untrusted("objects", objects)
            ));
        }
        for (String line : lines) {
            player.sendSystemMessage(Component.literal(line));
        }
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable e) {
            return null;
        }
    }

    private static int safeInt(Supplier<Integer> supplier) {
        try {
            Integer value = supplier.get();
            return value == null ? 0 : value;
        } catch (Throwable e) {
            return 0;
        }
    }
}

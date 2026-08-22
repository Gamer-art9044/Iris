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

import art.arcane.iris.core.gui.GuiHost;
import art.arcane.iris.core.gui.GuiMarker;
import art.arcane.iris.core.gui.GuiOverlay;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.render.RenderType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class ModdedVisionOverlay implements GuiOverlay {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final Engine engine;
    private final UUID opener;

    public ModdedVisionOverlay(MinecraftServer server, ServerLevel level, Engine engine, UUID opener) {
        this.server = server;
        this.level = level;
        this.engine = engine;
        this.opener = opener;
    }

    @Override
    public List<GuiMarker> players() {
        List<GuiMarker> markers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            Vec3 position = player.position();
            markers.add(GuiMarker.player(player.getScoreboardName(), position.x(), position.z()));
        }
        return markers;
    }

    @Override
    public void requestEntities(Consumer<List<GuiMarker>> sink) {
        server.execute(() -> {
            List<GuiMarker> markers = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer || !(entity instanceof LivingEntity living)) {
                    continue;
                }
                Vec3 position = living.position();
                markers.add(GuiMarker.entity(living.getType().toShortString(), position.x(), position.y(), position.z(),
                        living.getHealth(), living.getMaxHealth()));
            }
            sink.accept(markers);
        });
    }

    @Override
    public void teleport(double worldX, double worldZ) {
        int blockX = (int) worldX;
        int blockZ = (int) worldZ;
        server.execute(() -> {
            ServerPlayer player = opener == null ? null : server.getPlayerList().getPlayer(opener);
            if (player == null) {
                List<ServerPlayer> players = level.players();
                if (players.isEmpty()) {
                    return;
                }
                player = players.get(0);
            }
            int surfaceY = engine.getMinHeight() + engine.getHeight(blockX, blockZ, false) + 2;
            int safeY = Math.max(surfaceY, level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ) + 1);
            player.teleportTo(level, blockX + 0.5D, safeY, blockZ + 0.5D, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
        });
    }

    @Override
    public String openInEditor(double worldX, double worldZ, RenderType type) {
        if (!GuiHost.isAvailable() || !Desktop.isDesktopSupported()) {
            return null;
        }
        IrisComplex complex = engine.getComplex();
        File file = switch (type) {
            case BIOME, LAYER_LOAD, DECORATOR_LOAD, OBJECT_LOAD, HEIGHT ->
                    complex.getTrueBiomeStream().get(worldX, worldZ).openInVSCode();
            case BIOME_LAND -> complex.getLandBiomeStream().get(worldX, worldZ).openInVSCode();
            case BIOME_SEA -> complex.getSeaBiomeStream().get(worldX, worldZ).openInVSCode();
            case REGION -> complex.getRegionStream().get(worldX, worldZ).openInVSCode();
            case CAVE_LAND -> complex.getCaveBiomeStream().get(worldX, worldZ).openInVSCode();
            default -> null;
        };
        return file == null ? null : file.getName();
    }
}

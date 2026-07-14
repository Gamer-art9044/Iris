/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.gui;

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.render.RenderType;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.format.Form;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static art.arcane.iris.util.common.data.registry.Attributes.MAX_HEALTH;

public final class BukkitVisionOverlay implements GuiOverlay {
    private final Engine engine;

    public BukkitVisionOverlay(Engine engine) {
        this.engine = engine;
    }

    @Override
    public List<GuiMarker> players() {
        IrisWorld world = engine.getWorld();
        List<GuiMarker> markers = new ArrayList<>();
        for (Player player : BukkitWorldBinding.players(world)) {
            markers.add(GuiMarker.player(player.getName(), player.getLocation().getX(), player.getLocation().getZ()));
        }
        return markers;
    }

    @Override
    public void requestEntities(Consumer<List<GuiMarker>> sink) {
        J.s(() -> {
            IrisWorld world = engine.getWorld();
            List<GuiMarker> markers = new ArrayList<>();
            for (LivingEntity entity : BukkitWorldBinding.entities(world, LivingEntity.class)) {
                if (entity instanceof Player) {
                    continue;
                }
                String label = Form.capitalizeWords(entity.getType().name().toLowerCase(Locale.ROOT).replaceAll("\\Q_\\E", " "));
                double maxHealth = 0;
                try {
                    maxHealth = entity.getAttribute(MAX_HEALTH).getValue();
                } catch (Throwable ignored) {
                }
                markers.add(GuiMarker.entity(label, entity.getLocation().getX(), entity.getLocation().getY(), entity.getLocation().getZ(),
                        entity.getHealth(), maxHealth));
            }
            sink.accept(markers);
        });
    }

    @Override
    public void teleport(double worldX, double worldZ) {
        IrisWorld world = engine.getWorld();
        if (!world.hasPlatformWorld()) {
            return;
        }
        J.s(() -> {
            List<Player> players = BukkitWorldBinding.players(world);
            if (players.isEmpty()) {
                return;
            }
            Player player = players.get(0);
            int xx = (int) worldX;
            int zz = (int) worldZ;
            int yy = player.getWorld().getHighestBlockYAt(xx, zz) + 1;
            player.teleport(new Location(player.getWorld(), xx, yy, zz));
        });
    }

    @Override
    public String openInEditor(double worldX, double worldZ, RenderType type) {
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

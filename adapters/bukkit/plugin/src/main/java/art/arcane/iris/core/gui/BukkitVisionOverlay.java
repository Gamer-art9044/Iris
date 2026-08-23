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

import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.render.RenderType;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.format.Form;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static art.arcane.iris.util.common.data.registry.Attributes.MAX_HEALTH;

public final class BukkitVisionOverlay implements GuiOverlay {
    private final Engine engine;
    private final AtomicBoolean playerRefreshQueued = new AtomicBoolean();
    private volatile List<GuiMarker> playerMarkers = List.of();

    public BukkitVisionOverlay(Engine engine) {
        this.engine = engine;
    }

    /**
     * Called from the AWT event thread, so it may only hand back the last snapshot
     * built by a server thread.
     */
    @Override
    public List<GuiMarker> players() {
        queuePlayerRefresh();
        return playerMarkers;
    }

    private void queuePlayerRefresh() {
        if (!playerRefreshQueued.compareAndSet(false, true)) {
            return;
        }

        boolean scheduled = J.runGlobal(() -> {
            try {
                List<GuiMarker> markers = new ArrayList<>();
                for (Player player : BukkitWorldBinding.players(engine.getWorld())) {
                    Location at = player.getLocation();
                    markers.add(GuiMarker.player(player.getName(), at.getX(), at.getZ()));
                }
                playerMarkers = List.copyOf(markers);
            } finally {
                playerRefreshQueued.set(false);
            }
        });

        if (!scheduled) {
            playerRefreshQueued.set(false);
        }
    }

    @Override
    public void requestEntities(Consumer<List<GuiMarker>> sink) {
        J.runGlobal(() -> {
            IrisWorld target = engine.getWorld();
            World world = BukkitWorldBinding.world(target);
            if (world == null) {
                sink.accept(List.of());
                return;
            }

            List<LivingEntity> living = new ArrayList<>();
            for (LivingEntity entity : BukkitWorldBinding.entities(target, LivingEntity.class)) {
                if (!(entity instanceof Player)) {
                    living.add(entity);
                }
            }

            if (living.isEmpty()) {
                sink.accept(List.of());
                return;
            }

            List<GuiMarker> collected = Collections.synchronizedList(new ArrayList<>(living.size()));
            AtomicInteger pending = new AtomicInteger(living.size());
            Runnable complete = () -> {
                if (pending.decrementAndGet() == 0) {
                    sink.accept(List.copyOf(collected));
                }
            };

            for (LivingEntity entity : living) {
                Location at = entity.getLocation();
                Runnable read = () -> {
                    try {
                        collected.add(marker(entity, at));
                    } catch (Throwable ignored) {
                    } finally {
                        complete.run();
                    }
                };

                if (!J.runRegion(world, at.getBlockX() >> 4, at.getBlockZ() >> 4, read)) {
                    complete.run();
                }
            }
        });
    }

    private GuiMarker marker(LivingEntity entity, Location at) {
        String label = Form.capitalizeWords(entity.getType().name().toLowerCase(Locale.ROOT).replaceAll("\\Q_\\E", " "));
        double maxHealth = 0;
        try {
            maxHealth = entity.getAttribute(MAX_HEALTH).getValue();
        } catch (Throwable ignored) {
        }
        return GuiMarker.entity(label, at.getX(), at.getY(), at.getZ(), entity.getHealth(), maxHealth);
    }

    @Override
    public void teleport(double worldX, double worldZ) {
        IrisWorld target = engine.getWorld();
        if (!target.hasPlatformWorld()) {
            return;
        }
        J.runGlobal(() -> {
            List<Player> players = BukkitWorldBinding.players(target);
            if (players.isEmpty()) {
                return;
            }
            Player player = players.get(0);
            World world = player.getWorld();
            int xx = (int) worldX;
            int zz = (int) worldZ;
            J.runRegion(world, xx >> 4, zz >> 4, () -> {
                int yy = world.getHighestBlockYAt(xx, zz) + 1;
                Location destination = new Location(world, xx, yy, zz);
                J.runEntity(player, () -> BukkitPlatform.teleportAsync(player, destination));
            });
        });
    }

    @Override
    public String openInEditor(double worldX, double worldZ, RenderType type) {
        IrisComplex complex = engine.getComplex();
        File file = switch (type) {
            case BIOME, LAYER_LOAD, DECORATOR_LOAD, OBJECT_LOAD, HEIGHT, RIVER ->
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

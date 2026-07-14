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

package art.arcane.iris.engine.framework;

import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.events.IrisEngineHotloadEvent;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class EngineAssignedWorldManager extends EngineAssignedComponent implements EngineWorldManager, BukkitEngineWorldManager, Listener {
    private final int taskId;
    protected AtomicBoolean ignoreTP = new AtomicBoolean(false);

    public EngineAssignedWorldManager() {
        super(null, null);
        taskId = -1;
    }

    public EngineAssignedWorldManager(Engine engine) {
        super(engine, "World");
        BukkitPlatform.volmitPlugin().registerListener(this);
        taskId = J.sr(this::onTick, 1);
    }

    @EventHandler
    public void on(IrisEngineHotloadEvent e) {
        for (Player i : BukkitWorldBinding.players(e.getEngine().getWorld())) {
            i.playSound(i.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1.8f);
            VolmitSender s = new VolmitSender(i);
            s.sendTitle(C.IRIS + "Engine " + C.AQUA + "<font:minecraft:uniform>Hotloaded", 70, 60, 410);
        }
    }

    @EventHandler
    public void on(WorldSaveEvent e) {
        if (e.getWorld().equals(BukkitWorldBinding.world(getTarget().getWorld()))) {
            getEngine().save();
        }
    }

    @EventHandler
    public void on(WorldUnloadEvent e) {
        if (e.getWorld().equals(BukkitWorldBinding.world(getTarget().getWorld()))) {
            getEngine().close();
        }
    }

    @EventHandler
    public void on(BlockBreakEvent e) {
        if (e.getPlayer().getWorld().equals(BukkitWorldBinding.world(getTarget().getWorld()))) {
            onBlockBreak(e);
        }
    }

    @EventHandler
    public void on(BlockPlaceEvent e) {
        if (e.getPlayer().getWorld().equals(BukkitWorldBinding.world(getTarget().getWorld()))) {
            onBlockPlace(e);
        }
    }

    @EventHandler
    public void on(ChunkLoadEvent e) {
        if (e.getChunk().getWorld().equals(BukkitWorldBinding.world(getTarget().getWorld()))) {
            onChunkLoad(e.getChunk(), e.isNewChunk());
        }
    }

    @EventHandler
    public void on(ChunkUnloadEvent e) {
        if (e.getChunk().getWorld().equals(BukkitWorldBinding.world(getTarget().getWorld()))) {
            onChunkUnload(e.getChunk());
        }
    }

    @Override
    public void close() {
        super.close();
        BukkitPlatform.volmitPlugin().unregisterListener(this);
        if (taskId != -1) {
            J.csr(taskId);
        }
    }
}

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

package art.arcane.iris.engine;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.iris.util.common.scheduling.jobs.QueueJob;
import art.arcane.volmlib.util.collection.KList;
import io.papermc.lib.PaperLib;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Cancels a teleport, loads the destination chunks off the main thread and then replays the teleport
 * on the thread that owns the player. The replay sets the manager's ignore flag so the reissued
 * teleport is not intercepted again.
 */
final class WorldTeleportWarmup {
    private final IrisWorldManager manager;

    WorldTeleportWarmup(IrisWorldManager manager) {
        this.manager = manager;
    }

    void teleportAsync(PlayerTeleportEvent e) {
        e.setCancelled(true);
        warmupAreaAsync(e.getPlayer(), e.getTo(), () -> J.runEntity(e.getPlayer(), manager.managedTask(
                "bukkit_world_manager_teleport",
                () -> {
            manager.ignoreTeleport().set(true);
            e.getPlayer().teleport(e.getTo(), e.getCause());
            manager.ignoreTeleport().set(false);
        })));
    }

    private void warmupAreaAsync(Player player, Location to, Runnable r) {
        J.a(manager.managedTask("bukkit_world_manager_teleport_warmup", () -> {
            int viewDistance = 2;
            KList<Future<Chunk>> futures = new KList<>();
            for (int i = -viewDistance; i <= viewDistance; i++) {
                for (int j = -viewDistance; j <= viewDistance; j++) {
                    int finalJ = j;
                    int finalI = i;

                    if (to.getWorld().isChunkLoaded((to.getBlockX() >> 4) + i, (to.getBlockZ() >> 4) + j)) {
                        futures.add(CompletableFuture.completedFuture(null));
                        continue;
                    }

                    futures.add(MultiBurst.burst.completeValue(()
                            -> PaperLib.getChunkAtAsync(to.getWorld(),
                            (to.getBlockX() >> 4) + finalI,
                            (to.getBlockZ() >> 4) + finalJ,
                            true, false).get()));
                }
            }

            new QueueJob<Future<Chunk>>() {
                @Override
                public void execute(Future<Chunk> chunkFuture) {
                    try {
                        chunkFuture.get();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        IrisLogging.debug("Chunk warmup interrupted while loading async teleport chunk.");
                    } catch (ExecutionException ex) {
                        IrisLogging.reportError(ex);
                    }
                }

                @Override
                public String getName() {
                    return IrisLanguage.text(RuntimeUiMessages.JOB_LOADING_CHUNKS);
                }
            }.queue(futures).execute(new VolmitSender(player), true, r);
        }));
    }
}

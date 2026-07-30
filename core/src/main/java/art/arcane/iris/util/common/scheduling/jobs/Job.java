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

package art.arcane.iris.util.common.scheduling.jobs;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSlotClaim;
import art.arcane.volmlib.util.hud.HudSlotRequest;
import art.arcane.volmlib.util.hud.HudSurface;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public interface Job {
    String getName();

    void execute();

    void completeWork();

    int getTotalWork();

    default int getWorkRemaining() {
        return getTotalWork() - getWorkCompleted();
    }

    int getWorkCompleted();

    default String getProgressString() {
        return Form.pc(getProgress(), 0);
    }

    default double getProgress() {
        return (double) getWorkCompleted() / (double) getTotalWork();
    }


    default void execute(VolmitSender sender) {
        execute(sender, () -> {
        });
    }


    default void execute(VolmitSender sender, Runnable whenComplete) {
        execute(sender, false, whenComplete);
    }

    default void execute(VolmitSender sender, boolean silentMsg, Runnable whenComplete) {
        PrecisionStopwatch p = PrecisionStopwatch.start();
        CompletableFuture<?> f = J.afut(this::execute);
        HudSlotClaim titleClaim = sender.isPlayer()
                ? BukkitPlatform.hudSlots().open(sender.player(), new HudSlotRequest("iris:job", HudPriority.PROGRESS, 1200L, List.of(HudSurface.TITLE)))
                : null;
        HudSlotClaim barClaim = sender.isPlayer()
                ? BukkitPlatform.hudSlots().open(sender.player(), new HudSlotRequest("iris:job", HudPriority.PROGRESS, 1200L, List.of(HudSurface.ACTION_BAR, HudSurface.BOSS_BAR)))
                : null;
        AtomicLong lastResolveMs = new AtomicLong(0L);
        int c = J.ar(() -> {
            if (sender.isPlayer()) {
                long now = System.currentTimeMillis();
                if (now - lastResolveMs.get() >= 250L) {
                    lastResolveMs.set(now);
                    titleClaim.resolve();
                    barClaim.resolve();
                }
                HudSurface titleSurface = titleClaim.granted();
                HudSurface barSurface = barClaim.granted();
                sender.sendProgress(getProgress(), getName(), titleSurface, barSurface);
                if (barSurface == HudSurface.BOSS_BAR) {
                    BukkitPlatform.hudLanes().show(sender.player(), "iris:job", getName() + " " + getProgressString(), getProgress(), BarColor.BLUE, BarStyle.SOLID, 4000L);
                } else if (barSurface == HudSurface.ACTION_BAR) {
                    BukkitPlatform.hudLanes().hide(sender.player(), "iris:job");
                }
            } else {
                sender.sendMessage(getName() + ": " + getProgressString());
            }
        }, sender.isPlayer() ? 0 : 20);
        f.whenComplete((fs, ff) -> {
            J.car(c);
            if (titleClaim != null) {
                titleClaim.release();
            }
            if (barClaim != null) {
                barClaim.release();
                BukkitPlatform.hudLanes().hide(sender.player(), "iris:job");
            }
            if (!silentMsg) {
                sender.sendMessage(C.AQUA + IrisLanguage.text(
                        RuntimeUiMessages.JOB_COMPLETED,
                        MessageArgument.untrusted("job", getName()),
                        MessageArgument.trusted("duration", Form.duration(p.getMilliseconds(), 1))
                ));
            }
            whenComplete.run();
        });
    }
}

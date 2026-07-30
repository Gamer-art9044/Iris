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

package art.arcane.iris.core.project;

import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeProgressMessages;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSlotClaim;
import art.arcane.volmlib.util.hud.HudSlotRequest;
import art.arcane.volmlib.util.hud.HudSurface;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("ALL")
final class StudioOpenProgressReporter {
    private static final int STUDIO_PROGRESS_BAR_WIDTH = 44;

    private StudioOpenProgressReporter() {
    }

    static void startStudioOpenReporter(VolmitSender sender, AtomicReference<String> stage, AtomicReference<Double> progress, AtomicBoolean complete, AtomicBoolean failed) {
        AtomicLong nextConsoleUpdate = new AtomicLong(0L);
        AtomicLong startMs = new AtomicLong(System.currentTimeMillis());
        AtomicInteger taskId = new AtomicInteger(-1);
        org.bukkit.boss.BossBar bossBar;
        HudSlotClaim loaderClaim;

        if (sender.isPlayer() && sender.player() != null) {
            bossBar = Bukkit.createBossBar(
                    IrisLanguage.text(RuntimeProgressMessages.STUDIO_OPENING),
                    org.bukkit.boss.BarColor.BLUE,
                    org.bukkit.boss.BarStyle.SEGMENTED_20
            );
            bossBar.setProgress(0.0D);
            bossBar.addPlayer(sender.player());
            bossBar.setVisible(true);
            loaderClaim = BukkitPlatform.hudSlots().open(sender.player(), new HudSlotRequest(
                    "iris:studio-open",
                    HudPriority.PROGRESS,
                    1200L,
                    List.of(HudSurface.ACTION_BAR, HudSurface.BOSS_BAR)
            ));
        } else {
            bossBar = null;
            loaderClaim = null;
        }

        int scheduledTaskId = J.ar(() -> {
            double currentProgress = Math.max(0D, Math.min(0.99D, progress.get()));
            String currentStage = describeStage(stage.get());
            int percent = (int) Math.round(currentProgress * 100.0D);
            long elapsed = System.currentTimeMillis() - startMs.get();

            if (complete.get()) {
                J.car(taskId.get());

                if (failed.get()) {
                    if (bossBar != null) {
                        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, currentProgress)));
                        bossBar.setColor(org.bukkit.boss.BarColor.RED);
                        bossBar.setTitle(IrisLanguage.text(
                                RuntimeProgressMessages.STUDIO_FAILED_PROGRESS,
                                MessageArgument.trusted("percent", percent)
                        ));
                        J.a(() -> {
                            bossBar.removeAll();
                            bossBar.setVisible(false);
                            loaderClaim.release();
                            BukkitPlatform.hudLanes().hide(sender.player(), "iris:studio-open");
                        }, 60);
                    }
                    if (sender.isPlayer()) {
                        HudSurface loaderSurface = loaderClaim.resolve();
                        if (loaderSurface == HudSurface.ACTION_BAR) {
                            BukkitPlatform.hudLanes().hide(sender.player(), "iris:studio-open");
                            sender.sendAction(IrisLanguage.text(
                                    RuntimeProgressMessages.STUDIO_ACTION_FAILED,
                                    MessageArgument.trusted("bar", buildStudioProgressBar(currentProgress)),
                                    MessageArgument.trusted("stage", currentStage)
                            ));
                        } else if (loaderSurface == HudSurface.BOSS_BAR) {
                            BukkitPlatform.hudLanes().show(sender.player(), "iris:studio-open", IrisLanguage.text(
                                    RuntimeProgressMessages.STUDIO_ACTION_FAILED,
                                    MessageArgument.trusted("bar", ""),
                                    MessageArgument.trusted("stage", currentStage)
                            ), currentProgress, BarColor.RED, BarStyle.SOLID, 4000L);
                        }
                    } else {
                        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_STUDIO_OPEN_FAILED_2));
                    }
                } else {
                    if (bossBar != null) {
                        bossBar.setProgress(1.0D);
                        bossBar.setColor(org.bukkit.boss.BarColor.GREEN);
                        bossBar.setTitle(IrisLanguage.text(RuntimeProgressMessages.STUDIO_READY_PROGRESS));
                        J.a(() -> {
                            bossBar.removeAll();
                            bossBar.setVisible(false);
                            loaderClaim.release();
                            BukkitPlatform.hudLanes().hide(sender.player(), "iris:studio-open");
                        }, 60);
                    }
                    if (sender.isPlayer()) {
                        HudSurface loaderSurface = loaderClaim.resolve();
                        if (loaderSurface == HudSurface.ACTION_BAR) {
                            BukkitPlatform.hudLanes().hide(sender.player(), "iris:studio-open");
                            sender.sendAction(IrisLanguage.text(
                                    RuntimeProgressMessages.STUDIO_ACTION_READY,
                                    MessageArgument.trusted("bar", buildStudioProgressBar(1.0D)),
                                    MessageArgument.trusted("elapsed", Form.duration(elapsed, 1))
                            ));
                        } else if (loaderSurface == HudSurface.BOSS_BAR) {
                            BukkitPlatform.hudLanes().show(sender.player(), "iris:studio-open", IrisLanguage.text(
                                    RuntimeProgressMessages.STUDIO_ACTION_READY,
                                    MessageArgument.trusted("bar", ""),
                                    MessageArgument.trusted("elapsed", Form.duration(elapsed, 1))
                            ), 1.0D, BarColor.GREEN, BarStyle.SOLID, 4000L);
                        }
                    } else {
                        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_STUDIO_READY, MessageArgument.untrusted("value", String.valueOf(Form.duration(elapsed, 1)))));
                    }
                }
                return;
            }

            if (sender.isPlayer() && sender.player() != null) {
                if (bossBar != null) {
                    bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, currentProgress)));
                    bossBar.setTitle(IrisLanguage.text(
                            RuntimeProgressMessages.STUDIO_OPENING_PROGRESS,
                            MessageArgument.trusted("percent", percent)
                    ));
                }

                HudSurface loaderSurface = loaderClaim.resolve();
                if (loaderSurface == HudSurface.ACTION_BAR) {
                    BukkitPlatform.hudLanes().hide(sender.player(), "iris:studio-open");
                    sender.sendAction(IrisLanguage.text(
                            RuntimeProgressMessages.STUDIO_ACTION_PROGRESS,
                            MessageArgument.trusted("bar", buildStudioProgressBar(currentProgress)),
                            MessageArgument.trusted("percent", percent),
                            MessageArgument.trusted("stage", currentStage),
                            MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
                    ));
                } else if (loaderSurface == HudSurface.BOSS_BAR) {
                    BukkitPlatform.hudLanes().show(sender.player(), "iris:studio-open", IrisLanguage.text(
                            RuntimeProgressMessages.STUDIO_ACTION_PROGRESS,
                            MessageArgument.trusted("bar", ""),
                            MessageArgument.trusted("percent", percent),
                            MessageArgument.trusted("stage", currentStage),
                            MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
                    ), currentProgress, BarColor.GREEN, BarStyle.SOLID, 4000L);
                }
            } else {
                long now = System.currentTimeMillis();
                long nextUpdate = nextConsoleUpdate.get();
                if (now >= nextUpdate) {
                    String bar = buildStudioConsoleBar(currentProgress);
                    sender.sendMessage(IrisLanguage.text(
                            RuntimeProgressMessages.STUDIO_CONSOLE_PROGRESS,
                            MessageArgument.trusted("bar", bar),
                            MessageArgument.trusted("percent", percent),
                            MessageArgument.trusted("stage", currentStage),
                            MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
                    ));
                    nextConsoleUpdate.set(now + 1500L);
                }
            }
        }, 3);

        taskId.set(scheduledTaskId);
        if (complete.get()) {
            J.car(taskId.get());
        }
    }

    private static String buildStudioProgressBar(double progress) {
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * STUDIO_PROGRESS_BAR_WIDTH);
        StringBuilder bar = new StringBuilder(STUDIO_PROGRESS_BAR_WIDTH * 3 + 4);
        bar.append(C.DARK_GRAY).append("[");
        for (int i = 0; i < STUDIO_PROGRESS_BAR_WIDTH; i++) {
            bar.append(i < filled ? C.GREEN : C.DARK_GRAY).append("|");
        }
        bar.append(C.DARK_GRAY).append("]");
        return bar.toString();
    }

    private static String buildStudioConsoleBar(double progress) {
        int width = 20;
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * width);
        StringBuilder bar = new StringBuilder();
        bar.append("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? "#" : "-");
        }
        bar.append("]");
        return bar.toString();
    }

    private static String describeStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_INITIALIZING);
        }
        return switch (stage) {
            case "Queued" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_QUEUED);
            case "resolve_dimension" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_RESOLVE_DIMENSION);
            case "prepare_world_pack" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_PREPARE_WORLD_PACK);
            case "install_datapacks" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_INSTALL_DATAPACKS);
            case "create_world" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_CREATE_WORLD);
            case "apply_world_rules" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_APPLY_WORLD_RULES);
            case "prepare_generator" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_PREPARE_GENERATOR);
            case "request_entry_chunk" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_REQUEST_ENTRY_CHUNK);
            case "resolve_safe_entry" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_RESOLVE_SAFE_ENTRY);
            case "teleport_player" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_TELEPORT_PLAYER);
            case "finalize_open" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_FINALIZE_OPEN);
            case "cleanup" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_CLEANUP);
            default -> Form.capitalizeWords(stage.replace('_', ' '));
        };
    }
}

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

package art.arcane.iris.core.tools;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeProgressMessages;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class WorldCreationProgressReporter {
    private static final int PLAYER_BAR_WIDTH = 44;
    private static final int CONSOLE_BAR_WIDTH = 20;
    private static final long CONSOLE_UPDATE_INTERVAL_MILLIS = 1500L;

    private final VolmitSender sender;
    private final String worldName;
    private final long startedAtMillis;
    private final AtomicReference<String> stage;
    private final AtomicReference<String> detail;
    private final AtomicReference<Double> progress;
    private final AtomicBoolean complete;
    private final AtomicBoolean failed;
    private final AtomicBoolean terminalRendered;
    private final AtomicBoolean playerRenderQueued;
    private final AtomicInteger taskId;
    private final AtomicLong nextConsoleUpdate;
    private volatile BossBar bossBar;
    private volatile boolean hudDisabled;

    private WorldCreationProgressReporter(VolmitSender sender, String worldName) {
        this.sender = sender;
        this.worldName = worldName;
        this.startedAtMillis = System.currentTimeMillis();
        this.stage = new AtomicReference<>("resolve_dimension");
        this.detail = new AtomicReference<>("");
        this.progress = new AtomicReference<>(0.01D);
        this.complete = new AtomicBoolean(false);
        this.failed = new AtomicBoolean(false);
        this.terminalRendered = new AtomicBoolean(false);
        this.playerRenderQueued = new AtomicBoolean(false);
        this.taskId = new AtomicInteger(-1);
        this.nextConsoleUpdate = new AtomicLong(0L);

        this.bossBar = null;
        this.hudDisabled = false;
    }

    static WorldCreationProgressReporter start(VolmitSender sender, String worldName) {
        WorldCreationProgressReporter reporter = new WorldCreationProgressReporter(sender, worldName);
        if (sender.isPlayer() && sender.player() != null) {
            try {
                J.sfut(reporter::initializePlayerHud).get(5L, TimeUnit.SECONDS);
            } catch (Throwable failure) {
                reporter.hudDisabled = true;
                J.runGlobal(reporter::releaseHud);
                IrisLogging.reportError("Failed to initialize world creation progress HUD for \""
                        + worldName + "\".", failure);
            }
        }
        reporter.taskId.set(J.ar(reporter::tick, 3));
        return reporter;
    }

    private void initializePlayerHud() {
        if (hudDisabled) {
            return;
        }
        bossBar = Bukkit.createBossBar(
                IrisLanguage.text(
                        RuntimeProgressMessages.WORLD_CREATE_BOSSBAR_WORKING,
                        MessageArgument.untrusted("world", worldName)
                ),
                BarColor.BLUE,
                BarStyle.SEGMENTED_20
        );
        bossBar.setProgress(0.01D);
        bossBar.addPlayer(sender.player());
        bossBar.setVisible(true);
    }

    void update(double progress, String stage) {
        update(progress, stage, "");
    }

    void update(double progress, String stage, String detail) {
        this.progress.set(clampProgress(progress));
        if (stage != null && !stage.isBlank()) {
            this.stage.set(stage);
        }
        this.detail.set(detail == null ? "" : detail);
    }

    void succeed() {
        progress.set(1.0D);
        stage.set("complete");
        detail.set("");
        complete.set(true);
        requestTerminalRender();
    }

    void fail() {
        failed.set(true);
        complete.set(true);
        requestTerminalRender();
    }

    private void tick() {
        double currentProgress = complete.get() && !failed.get()
                ? 1.0D
                : Math.min(0.99D, clampProgress(progress.get()));
        String currentStage = IrisLanguage.text(stageKey(stage.get()));
        String currentDetail = detail.get();
        int percent = (int) Math.round(currentProgress * 100.0D);
        long elapsed = System.currentTimeMillis() - startedAtMillis;

        if (complete.get()) {
            cancel();
            if (!terminalRendered.compareAndSet(false, true)) {
                return;
            }
            renderTerminal(currentProgress, currentStage, currentDetail, percent, elapsed);
            return;
        }

        if (sender.isPlayer() && sender.player() != null) {
            if (hasPlayerHud()) {
                schedulePlayerRender(() -> renderPlayerProgress(
                        currentProgress,
                        currentStage,
                        currentDetail,
                        percent,
                        elapsed
                ));
            } else {
                schedulePlayerRender(() -> sender.sendAction(IrisLanguage.text(
                        RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_ACTION,
                        MessageArgument.trusted("bar", buildPlayerBar(currentProgress)),
                        MessageArgument.trusted("percent", percent),
                        MessageArgument.trusted("stage", currentStage),
                        MessageArgument.trusted("detail", currentDetail),
                        MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
                )));
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= nextConsoleUpdate.get()) {
            sender.sendMessage(IrisLanguage.text(
                    RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_CONSOLE,
                    MessageArgument.untrusted("world", worldName),
                    MessageArgument.trusted("bar", buildConsoleBar(currentProgress)),
                    MessageArgument.trusted("percent", percent),
                    MessageArgument.trusted("stage", currentStage),
                    MessageArgument.trusted("detail", currentDetail),
                    MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
            ));
            nextConsoleUpdate.set(now + CONSOLE_UPDATE_INTERVAL_MILLIS);
        }
    }

    private void renderPlayerProgress(
            double currentProgress,
            String currentStage,
            String currentDetail,
            int percent,
            long elapsed
    ) {
        bossBar.setProgress(currentProgress);
        bossBar.setTitle(IrisLanguage.text(
                RuntimeProgressMessages.WORLD_CREATE_BOSSBAR_PROGRESS,
                MessageArgument.untrusted("world", worldName),
                MessageArgument.trusted("percent", percent),
                MessageArgument.trusted("stage", currentStage)
        ));

        sender.sendAction(IrisLanguage.text(
                RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_ACTION,
                MessageArgument.trusted("bar", buildPlayerBar(currentProgress)),
                MessageArgument.trusted("percent", percent),
                MessageArgument.trusted("stage", currentStage),
                MessageArgument.trusted("detail", currentDetail),
                MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
        ));
    }

    private void renderTerminal(
            double currentProgress,
            String currentStage,
            String currentDetail,
            int percent,
            long elapsed
    ) {
        if (sender.isPlayer() && sender.player() != null) {
            if (hasPlayerHud()) {
                schedulePlayerTerminalRender(() -> renderPlayerTerminal(
                        currentProgress,
                        currentStage,
                        currentDetail,
                        percent,
                        elapsed
                ));
            } else {
                schedulePlayerTerminalRender(() -> sender.sendAction(IrisLanguage.text(
                        failed.get()
                                ? RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_ACTION_FAILED
                                : RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_ACTION_READY,
                        MessageArgument.trusted("bar", buildPlayerBar(currentProgress)),
                        MessageArgument.trusted("percent", percent),
                        MessageArgument.trusted("stage", currentStage),
                        MessageArgument.trusted("detail", currentDetail),
                        MessageArgument.trusted("elapsed", Form.duration(elapsed, 1))
                )));
            }
            return;
        }

        sender.sendMessage(IrisLanguage.text(
                failed.get()
                        ? RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_CONSOLE_FAILED
                        : RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_CONSOLE_READY,
                MessageArgument.untrusted("world", worldName),
                MessageArgument.trusted("elapsed", Form.duration(elapsed, 1))
        ));
    }

    private void renderPlayerTerminal(
            double currentProgress,
            String currentStage,
            String currentDetail,
            int percent,
            long elapsed
    ) {
        bossBar.setProgress(currentProgress);
        bossBar.setColor(failed.get() ? BarColor.RED : BarColor.GREEN);
        bossBar.setTitle(IrisLanguage.text(
                failed.get()
                        ? RuntimeProgressMessages.WORLD_CREATE_BOSSBAR_FAILED
                        : RuntimeProgressMessages.WORLD_CREATE_BOSSBAR_READY,
                MessageArgument.untrusted("world", worldName),
                MessageArgument.trusted("percent", percent)
        ));

        sender.sendAction(IrisLanguage.text(
                failed.get()
                        ? RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_ACTION_FAILED
                        : RuntimeProgressMessages.WORLD_CREATE_LIFECYCLE_ACTION_READY,
                MessageArgument.trusted("bar", buildPlayerBar(currentProgress)),
                MessageArgument.trusted("percent", percent),
                MessageArgument.trusted("stage", currentStage),
                MessageArgument.trusted("detail", currentDetail),
                MessageArgument.trusted("elapsed", Form.duration(elapsed, 1))
        ));

        Runnable cleanup = () -> {
            bossBar.removeAll();
            bossBar.setVisible(false);
        };
        J.runEntity(sender.player(), cleanup, 60, cleanup);
    }

    private boolean hasPlayerHud() {
        return !hudDisabled
                && sender.isPlayer()
                && sender.player() != null
                && bossBar != null;
    }

    private void schedulePlayerRender(Runnable render) {
        if (!playerRenderQueued.compareAndSet(false, true)) {
            return;
        }
        Runnable guardedRender = () -> {
            try {
                render.run();
            } finally {
                playerRenderQueued.set(false);
            }
        };
        if (J.runEntity(sender.player(), guardedRender)) {
            return;
        }
        playerRenderQueued.set(false);
        hudDisabled = true;
        J.runGlobal(this::releaseHud);
    }

    private void schedulePlayerTerminalRender(Runnable render) {
        if (J.runEntity(sender.player(), render)) {
            return;
        }
        hudDisabled = true;
        J.runGlobal(this::releaseHud);
    }

    private void releaseHud() {
        BossBar activeBossBar = bossBar;
        if (activeBossBar != null) {
            activeBossBar.removeAll();
            activeBossBar.setVisible(false);
        }
    }

    private void cancel() {
        int scheduledTaskId = taskId.getAndSet(-1);
        if (scheduledTaskId >= 0) {
            J.car(scheduledTaskId);
        }
    }

    private void requestTerminalRender() {
        if (!J.runGlobal(this::tick)) {
            cancel();
        }
    }

    static double clampProgress(double progress) {
        return Math.max(0.0D, Math.min(1.0D, progress));
    }

    static String buildPlayerBar(double progress) {
        return buildBar(progress, PLAYER_BAR_WIDTH, true);
    }

    static String buildConsoleBar(double progress) {
        return buildBar(progress, CONSOLE_BAR_WIDTH, false);
    }

    private static String buildBar(double progress, int width, boolean colored) {
        int filled = (int) Math.round(clampProgress(progress) * width);
        StringBuilder bar = new StringBuilder(colored ? width * 3 + 4 : width + 2);
        if (colored) {
            bar.append(C.DARK_GRAY);
        }
        bar.append("[");
        for (int index = 0; index < width; index++) {
            if (colored) {
                bar.append(index < filled ? C.GREEN : C.DARK_GRAY).append("|");
            } else {
                bar.append(index < filled ? "#" : "-");
            }
        }
        if (colored) {
            bar.append(C.DARK_GRAY);
        }
        return bar.append("]").toString();
    }

    static TextKey stageKey(String stage) {
        if (stage == null || stage.isBlank()) {
            return RuntimeProgressMessages.WORLD_CREATE_STAGE_INITIALIZING;
        }
        return switch (stage) {
            case "resolve_dimension" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_RESOLVE_DIMENSION;
            case "validate_pack" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_VALIDATE_PACK;
            case "prepare_world_pack" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_PREPARE_WORLD_PACK;
            case "install_datapacks" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_INSTALL_DATAPACKS;
            case "prepare_generator" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_PREPARE_GENERATOR;
            case "create_world" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_CREATE_WORLD;
            case "register_world" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_REGISTER_WORLD;
            case "teleport_player" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_TELEPORT_PLAYER;
            case "pregenerate" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_PREGENERATE;
            case "finalize" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_FINALIZE;
            case "complete" -> RuntimeProgressMessages.WORLD_CREATE_STAGE_COMPLETE;
            default -> RuntimeProgressMessages.WORLD_CREATE_STAGE_INITIALIZING;
        };
    }
}

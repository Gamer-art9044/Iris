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

package art.arcane.iris.modded.service;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ModdedSettingsHotloadService implements ModdedTickableService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final long POLL_PERIOD_MILLIS = 500L;
    private static final long HOTLOAD_COOLDOWN_MILLIS = 3_000L;
    private static final int MAX_SETTINGS_BYTES = 2 * 1024 * 1024;

    private ConfigHotloadEngine hotloadEngine;
    private long lastPollAtNanos;

    @Override
    public void onEnable() {
        File settingsFile = settingsFile();
        hotloadEngine = new ConfigHotloadEngine(
                this::isSettingsFile,
                () -> List.of(settingsFile),
                this::readSettings,
                this::normalizeSettings
        );
        hotloadEngine.configure(
                POLL_PERIOD_MILLIS,
                HOTLOAD_COOLDOWN_MILLIS,
                List.of(settingsFile),
                List.of()
        );
        lastPollAtNanos = 0L;
    }

    @Override
    public void onDisable() {
        ConfigHotloadEngine active = hotloadEngine;
        hotloadEngine = null;
        if (active != null) {
            active.clear();
        }
    }

    @Override
    public void onServerTick(MinecraftServer server) {
        ConfigHotloadEngine active = hotloadEngine;
        if (active == null) {
            return;
        }

        long now = System.nanoTime();
        if (now - lastPollAtNanos < TimeUnit.MILLISECONDS.toNanos(POLL_PERIOD_MILLIS)) {
            return;
        }
        lastPollAtNanos = now;

        try {
            for (ConfigHotloadEngine.StableContentSnapshot snapshot : active.pollTouchedSnapshots()) {
                if ("missing".equals(snapshot.signature())) {
                    active.processSnapshotChange(snapshot, ignored -> true, null);
                    LOGGER.warn("settings.json was removed; retaining the last valid runtime settings");
                    continue;
                }
                active.processSnapshotChange(snapshot, this::reloadSettings, ignored -> LOGGER.info("Hotloaded settings.json"));
            }
            IrisLanguage.update();
        } catch (RuntimeException failure) {
            LOGGER.error("Iris settings hotload watcher failed", failure);
        }
    }

    private boolean reloadSettings(ConfigHotloadEngine.StableContentSnapshot snapshot) {
        try {
            String content = snapshot.normalizedContent();
            if (content == null) {
                return false;
            }
            return IrisSettings.applyHotloadSnapshot(content, IrisLanguage::reload);
        } catch (RuntimeException failure) {
            LOGGER.error("Iris settings hotload failed; keeping the previous runtime settings", failure);
            return false;
        }
    }

    private boolean isSettingsFile(File file) {
        return file != null && settingsFile().getAbsoluteFile().equals(file.getAbsoluteFile());
    }

    private String readSettings(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] content = input.readNBytes(MAX_SETTINGS_BYTES + 1);
            if (content.length > MAX_SETTINGS_BYTES) {
                throw new IOException("Settings exceed " + MAX_SETTINGS_BYTES + " bytes: " + file);
            }
            return new String(content, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read Iris settings from " + file, failure);
        }
    }

    private String normalizeSettings(String content) {
        return content == null ? null : content.replace("\r\n", "\n").trim();
    }

    private static File settingsFile() {
        return IrisPlatforms.get().dataFile("settings.json");
    }
}

/*
 * Iris is a World Generator for Minecraft Bukkit Servers
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

package art.arcane.iris.core;

import art.arcane.iris.Iris;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Identity and hotload handling for settings.json. Supplies the predicates the
 * {@link ConfigHotloadEngine} is built from and drains the touched-file queue.
 */
public final class SettingsHotloadWatch {
    private static final int MAX_SETTINGS_BYTES = 2 * 1024 * 1024;

    private final File settingsFile;

    public SettingsHotloadWatch(File settingsFile) {
        this.settingsFile = settingsFile;
    }

    public File settingsFile() {
        return settingsFile;
    }

    public void checkConfigHotload(ConfigHotloadEngine engine) {
        if (engine == null) {
            return;
        }

        for (ConfigHotloadEngine.StableContentSnapshot snapshot : engine.pollTouchedSnapshots()) {
            if ("missing".equals(snapshot.signature())) {
                engine.processSnapshotChange(snapshot, ignored -> true, null);
                Iris.warn("settings.json was removed; retaining the last valid runtime settings.");
                continue;
            }
            engine.processSnapshotChange(
                    snapshot,
                    stable -> applySettingsSnapshot(stable.file(), stable.normalizedContent()),
                    ignored -> Iris.info("Hotloaded settings.json ")
            );
        }
        IrisLanguage.update();
    }

    public boolean isSettingsFile(File file) {
        if (file == null || settingsFile == null) {
            return false;
        }
        return settingsFile.getAbsoluteFile().equals(file.getAbsoluteFile());
    }

    public List<File> knownSettingsFiles() {
        if (settingsFile == null) {
            return List.of();
        }
        return List.of(settingsFile);
    }

    public String readSettingsContent(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] content = input.readNBytes(MAX_SETTINGS_BYTES + 1);
            if (content.length > MAX_SETTINGS_BYTES) {
                throw new IOException("Settings exceed " + MAX_SETTINGS_BYTES + " bytes: " + file);
            }
            return new String(content, StandardCharsets.UTF_8);
        } catch (Throwable ex) {
            Iris.warn("Failed to read settings file %s: %s%s",
                    file.getAbsolutePath(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage() == null ? "" : " - " + ex.getMessage());
            Iris.reportError(ex);
            return null;
        }
    }

    public String normalizeSettingsContent(String text) {
        if (text == null) {
            return null;
        }

        return text.replace("\r\n", "\n").trim();
    }

    private boolean applySettingsSnapshot(File file, String content) {
        if (content == null) {
            return false;
        }
        try {
            return IrisSettings.applyHotloadSnapshot(content, IrisLanguage::reload);
        } catch (RuntimeException failure) {
            Iris.warn("Rejected invalid settings hotload from %s: %s",
                    file.getAbsolutePath(),
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            Iris.reportError(failure);
            return false;
        }
    }
}

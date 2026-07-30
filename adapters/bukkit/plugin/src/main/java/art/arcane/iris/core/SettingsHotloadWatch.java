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
import art.arcane.volmlib.util.io.IO;

import java.io.File;
import java.util.List;

/**
 * Identity and hotload handling for settings.json. Supplies the predicates the
 * {@link ConfigHotloadEngine} is built from and drains the touched-file queue.
 */
public final class SettingsHotloadWatch {
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

        for (File file : engine.pollTouchedFiles()) {
            engine.processFileChange(file, ignored -> {
                IrisSettings.invalidate();
                IrisSettings.get();
                IrisLanguage.reload();
                return true;
            }, ignored -> Iris.info("Hotloaded settings.json "));
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

        try {
            return IO.readAll(file);
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
}

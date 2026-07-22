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
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public final class ModdedSettingsHotloadService implements ModdedTickableService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final long POLL_PERIOD_MILLIS = 3_000L;

    private long lastPollAt;
    private long lastModified;

    @Override
    public void onEnable() {
        lastPollAt = 0L;
        lastModified = settingsFile().lastModified();
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onServerTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastPollAt < POLL_PERIOD_MILLIS) {
            return;
        }
        lastPollAt = now;
        long modified = settingsFile().lastModified();
        if (modified == lastModified) {
            IrisLanguage.update();
            return;
        }
        if (IrisSettings.settings != null) {
            IrisSettings.invalidate();
        }
        IrisSettings.get();
        IrisLanguage.reload();
        lastModified = settingsFile().lastModified();
        LOGGER.info("Hotloaded settings.json");
    }

    private static File settingsFile() {
        return IrisPlatforms.get().dataFile("settings.json");
    }
}

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

package art.arcane.iris.modded;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModdedIrisLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static volatile boolean DEBUG_SETTING_WARNING_LOGGED;

    private ModdedIrisLog() {
    }

    public static void log(LogLevel level, String message) {
        LogLevel target = level == null ? LogLevel.INFO : level;
        switch (target) {
            case DEBUG -> debug(message);
            case INFO -> info(message);
            case WARN -> warn(message);
            case ERROR -> error(message);
        }
    }

    public static void debug(String message) {
        if (!debugEnabled()) {
            LOGGER.debug(clean(message));
            return;
        }

        LOGGER.info("[Iris/DEBUG] " + clean(message));
    }

    public static void info(String message) {
        LOGGER.info(clean(message));
    }

    public static void warn(String message) {
        LOGGER.warn(clean(message));
    }

    public static void error(String message) {
        LOGGER.error(clean(message));
    }

    public static void error(String message, Throwable error) {
        if (error == null) {
            error(message);
            return;
        }

        LOGGER.error(clean(message), error);
    }

    public static String clean(String message) {
        return IrisLogging.clean(message);
    }

    private static boolean debugEnabled() {
        try {
            IrisSettings settings = IrisSettings.settings != null ? IrisSettings.settings : IrisSettings.get();
            return settings != null && settings.getGeneral() != null && settings.getGeneral().isDebug();
        } catch (Throwable error) {
            warnDebugSetting(error);
            return false;
        }
    }

    private static void warnDebugSetting(Throwable error) {
        if (DEBUG_SETTING_WARNING_LOGGED) {
            return;
        }

        DEBUG_SETTING_WARNING_LOGGED = true;
        LOGGER.warn("Iris debug logging setting could not be read", error);
    }
}

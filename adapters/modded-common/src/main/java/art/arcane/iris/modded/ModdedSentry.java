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

import art.arcane.iris.BuildConstants;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.json.JSONException;
import com.google.gson.JsonSyntaxException;
import io.sentry.Sentry;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ModdedSentry {
    private static final String DSN = "http://4cdbb9ac953306529947f4ca1e8e6b26@sentry.volmit.com:8080/2";
    private static final long FLUSH_TIMEOUT_MS = 2000L;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private ModdedSentry() {
    }

    public static void start(ModdedLoader loader) {
        IrisSettings.IrisSettingsSentry settings = IrisSettings.get().getSentry();
        if (settings.disableAutoReporting || Sentry.isEnabled() || Boolean.getBoolean("iris.suppressReporting")) {
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        IrisLogging.info("Enabling Sentry for anonymous error reporting. You can disable this in the settings.");
        Sentry.init(options -> {
            options.setDsn(DSN);
            if (settings.debug) {
                options.setDebug(true);
            }
            options.setAttachServerName(false);
            options.setEnableUncaughtExceptionHandler(false);
            options.setRelease(loader.modVersion());
            options.setEnvironment(BuildConstants.ENVIRONMENT);
            options.setBeforeSend((event, hint) -> suppress(event.getThrowable()) ? null : event);
        });
        Sentry.configureScope(scope -> {
            scope.setTag("server", loader.platformName());
            scope.setTag("server.mc", loader.minecraftVersion());
            scope.setTag("iris.loader", loader.platformName());
            scope.setTag("iris.commit", BuildConstants.COMMIT);
        });
        ModdedPlatform.captureSink(Sentry::captureException);
    }

    public static void flush() {
        if (Sentry.isEnabled()) {
            Sentry.flush(FLUSH_TIMEOUT_MS);
            Sentry.close();
        }
        ModdedPlatform.captureSink(null);
        STARTED.set(false);
    }

    private static boolean suppress(Throwable error) {
        return (error instanceof IllegalStateException state && "zip file closed".equals(state.getMessage()))
                || error instanceof JSONException
                || error instanceof JsonSyntaxException;
    }
}

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

package art.arcane.iris.core.gui;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.engine.framework.Engine;

import java.awt.GraphicsEnvironment;

public final class GuiHost {
    private static volatile Provider provider = new Provider() {
    };
    private static volatile boolean desktopSuppressed = false;

    private GuiHost() {
    }

    public static void suppressDesktop(boolean suppress) {
        desktopSuppressed = suppress;
    }

    public static boolean isDesktopSuppressed() {
        return desktopSuppressed;
    }

    public interface Provider {
        default Engine findActiveEngine() {
            return null;
        }

        default void registerHotloadHook(Runnable onHotload) {
        }

        default void unregisterHotloadHook(Runnable onHotload) {
        }

        default GuiOverlay overlayFor(Engine engine) {
            return null;
        }
    }

    public static void set(Provider boundProvider) {
        if (boundProvider != null) {
            provider = boundProvider;
        }
    }

    public static Provider get() {
        return provider;
    }

    public static boolean isAvailable() {
        return !desktopSuppressed && !GraphicsEnvironment.isHeadless();
    }

    /**
     * Outcome of a server triggered desktop gui launch request.
     */
    public enum ServerGuiLaunch {
        /**
         * The gui was asked for and a display environment exists.
         */
        OPEN,
        /**
         * The gui was not asked for, or server launched guis are turned off in settings.
         */
        DISABLED,
        /**
         * The gui was asked for but the jvm is headless or the desktop is suppressed.
         */
        UNAVAILABLE
    }

    /**
     * Decides whether a server triggered job may open a desktop gui. Callers must not attempt an
     * awt launch on anything other than {@link ServerGuiLaunch#OPEN}, since awt throws on a headless jvm.
     */
    public static ServerGuiLaunch serverGuiLaunch(boolean requested) {
        if (!requested || !IrisSettings.get().getGui().isUseServerLaunchedGuis()) {
            return ServerGuiLaunch.DISABLED;
        }

        return isAvailable() ? ServerGuiLaunch.OPEN : ServerGuiLaunch.UNAVAILABLE;
    }
}

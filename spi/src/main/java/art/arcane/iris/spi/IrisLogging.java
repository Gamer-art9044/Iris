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

package art.arcane.iris.spi;

import java.util.IllegalFormatException;
import java.util.regex.Pattern;

/**
 * Logging front door for core: routes through the bound {@link IrisPlatform} when there is one and falls back
 * to {@code System.out}/{@code System.err} when there is not, so code that runs before adapter startup, in
 * tests, or in the standalone probe still logs.
 * <p>
 * Every method is safe from any thread and swallows its own failures - logging never throws. Formatting is
 * lenient: a malformed format string is emitted verbatim rather than raising
 * {@link java.util.IllegalFormatException}, and a null message renders as {@code "null"}.
 * <p>
 * Internal to Iris; not a published integration surface.
 */
public final class IrisLogging {
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)\\u00a7[0-9A-FK-ORX]");
    private static final Pattern MINI_MESSAGE_TAG = Pattern.compile("(?i)</?(?:reset|bold|b|italic|i|underlined?|u|strikethrough|st|obfuscated?|obf|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|gradient|font|hover|click|rainbow)(?::[^>\\n]{0,96})?>|<#[0-9a-f]{6}>");

    private IrisLogging() {
    }

    /**
     * Logs at {@link LogLevel#INFO}, applying {@link #format(String, Object...)} to the arguments.
     */
    public static void info(String format, Object... args) {
        emit(LogLevel.INFO, format(format, args));
    }

    /**
     * Logs a literal message at {@link LogLevel#DEBUG}. Takes no format arguments, so a message containing
     * {@code %} needs no escaping.
     */
    public static void debug(String message) {
        emit(LogLevel.DEBUG, message);
    }

    /**
     * Logs at {@link LogLevel#WARN}, applying {@link #format(String, Object...)} to the arguments.
     */
    public static void warn(String format, Object... args) {
        emit(LogLevel.WARN, format(format, args));
    }

    /**
     * Logs at {@link LogLevel#ERROR}, applying {@link #format(String, Object...)} to the arguments.
     */
    public static void error(String format, Object... args) {
        emit(LogLevel.ERROR, format(format, args));
    }

    /**
     * Emits a player-facing message to the console, preserving colour markup when a platform is bound and
     * stripping it via {@link #clean(String)} when one is not.
     */
    public static void msg(String message) {
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().msg(message);
            return;
        }

        System.out.println("[Iris] " + clean(message));
    }

    /**
     * Logs {@code context} at {@link LogLevel#ERROR} and then reports {@code error}. A null or blank
     * {@code context} gets a generic description; a null {@code error} is replaced with a synthetic
     * {@link IllegalStateException} so the report is never empty.
     */
    public static void reportError(String context, Throwable error) {
        Throwable cause = error == null ? new IllegalStateException("Unknown Iris failure") : error;
        String message = context == null || context.isBlank() ? "Unhandled Iris failure." : context;

        try {
            error(message);
        } catch (Throwable inner) {
            System.err.println("[Iris] " + message);
            inner.printStackTrace(System.err);
        }

        reportError(cause);
        cause.printStackTrace(System.err);
    }

    /**
     * Hands {@code error} to the platform's error reporting, or prints it to {@code System.err} when no
     * platform is bound. A null {@code error} is ignored.
     */
    public static void reportError(Throwable error) {
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().reportError(error);
            return;
        }

        if (error != null) {
            System.err.println("[Iris/ERROR] " + error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            error.printStackTrace(System.err);
        }
    }

    /**
     * {@link String#format(String, Object...)} that cannot throw: a null format renders as {@code "null"},
     * no arguments returns {@code format} unchanged, and a mismatched format string is returned verbatim.
     * Never returns null.
     */
    public static String format(String format, Object... args) {
        if (format == null) {
            return "null";
        }

        if (args == null || args.length == 0) {
            return format;
        }

        try {
            return String.format(format, args);
        } catch (IllegalFormatException ignored) {
            return format;
        }
    }

    /**
     * Strips legacy section-sign colour codes and MiniMessage tags, for sinks that render neither. A null
     * message renders as {@code "null"}. Never returns null.
     */
    public static String clean(String message) {
        if (message == null) {
            return "null";
        }

        String legacyStripped = LEGACY_COLOR.matcher(message).replaceAll("");
        return MINI_MESSAGE_TAG.matcher(legacyStripped).replaceAll("");
    }

    private static void emit(LogLevel level, String message) {
        LogLevel target = level == null ? LogLevel.INFO : level;
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().log(target, message);
            return;
        }

        String output = clean(message);
        if (target == LogLevel.WARN || target == LogLevel.ERROR) {
            System.err.println("[Iris/" + target + "] " + output);
            return;
        }

        System.out.println("[Iris/" + target + "] " + output);
    }
}

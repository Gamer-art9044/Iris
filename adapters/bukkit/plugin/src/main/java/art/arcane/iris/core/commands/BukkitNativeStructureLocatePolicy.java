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

package art.arcane.iris.core.commands;

final class BukkitNativeStructureLocatePolicy {
    private static final String MONUMENT_KEY = "minecraft:monument";
    private static final String UNAVAILABLE_MESSAGE = "Native monument locating is unavailable on Paper, Purpur, and Folia because a cold search can stall the server thread. The monument lookup was skipped; monument generation is unaffected.";

    private BukkitNativeStructureLocatePolicy() {
    }

    static boolean isUnavailable(String structureKey) {
        return structureKey != null && MONUMENT_KEY.equalsIgnoreCase(structureKey.trim());
    }

    static String unavailableMessage() {
        return UNAVAILABLE_MESSAGE;
    }
}

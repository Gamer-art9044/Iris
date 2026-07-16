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

package art.arcane.iris.core.pack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PackValidationRegistry {
    private static final Map<String, PackValidationResult> RESULTS = new ConcurrentHashMap<>();

    private PackValidationRegistry() {
    }

    public static void publish(PackValidationResult result) {
        if (result == null || result.getPackName() == null || result.getPackName().isBlank()) {
            return;
        }
        RESULTS.put(result.getPackName(), result);
    }

    public static PackValidationResult get(String packName) {
        if (packName == null || packName.isBlank()) {
            return null;
        }
        return RESULTS.get(packName);
    }

    public static PackValidationResult requireLoadable(String packName) {
        if (packName == null || packName.isBlank()) {
            throw new IllegalArgumentException("Pack name is required for validation");
        }
        PackValidationResult result = get(packName);
        if (result == null) {
            throw new BrokenPackException(packName, List.of(
                    "Required pack validation has not completed. World creation fails closed until validation succeeds."));
        }
        if (!result.isLoadable()) {
            throw new BrokenPackException(packName, result.getBlockingErrors());
        }
        return result;
    }

    public static boolean isBroken(String packName) {
        PackValidationResult result = get(packName);
        return result != null && !result.isLoadable();
    }

    public static Map<String, PackValidationResult> snapshot() {
        return Collections.unmodifiableMap(RESULTS);
    }

    public static void remove(String packName) {
        if (packName == null || packName.isBlank()) {
            return;
        }
        RESULTS.remove(packName);
    }

    public static void clear() {
        RESULTS.clear();
    }
}

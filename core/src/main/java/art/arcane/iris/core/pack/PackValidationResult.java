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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PackValidationResult {
    private final String packName;
    private final List<String> blockingErrors;
    private final List<String> warnings;
    private final long validatedAtMillis;

    public PackValidationResult(String packName,
                                List<String> blockingErrors,
                                List<String> warnings,
                                long validatedAtMillis) {
        this.packName = packName;
        this.blockingErrors = blockingErrors == null ? new ArrayList<>() : new ArrayList<>(blockingErrors);
        this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
        this.validatedAtMillis = validatedAtMillis;
    }

    public String getPackName() {
        return packName;
    }

    public boolean isLoadable() {
        return blockingErrors.isEmpty();
    }

    public List<String> getBlockingErrors() {
        return Collections.unmodifiableList(blockingErrors);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public long getValidatedAtMillis() {
        return validatedAtMillis;
    }
}

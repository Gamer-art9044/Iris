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

package art.arcane.iris.core.pack;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Validates biome layer stacks. caveCeilingLayers reuses the height generators built from layers,
 * so a biome with more ceiling entries than surface entries has no generator for the extras; the
 * engine skips them, and this validator surfaces the mistake to the author at validate time.
 */
final class PackBiomeLayerValidator {
    /** Both layers and caveCeilingLayers default to a single entry when absent (IrisBiome field initializers). */
    private static final int DEFAULT_LAYER_COUNT = 1;

    private PackBiomeLayerValidator() {
    }

    static List<String> validateCeilingLayerCounts(File biomesFolder) {
        List<String> blockingErrors = new ArrayList<>();
        if (biomesFolder == null || !biomesFolder.isDirectory()) {
            return blockingErrors;
        }

        List<File> biomeFiles = PackValidationIo.listJsonRecursive(biomesFolder);
        biomeFiles.sort(Comparator.comparing(File::getPath));
        for (File biomeFile : biomeFiles) {
            String biomeKey = PackValidationIo.deriveKey(biomesFolder, biomeFile);
            JSONObject biome;
            try {
                biome = new JSONObject(Files.readString(biomeFile.toPath(), StandardCharsets.UTF_8));
            } catch (Throwable e) {
                // Invalid JSON is reported by the graph validators; layer counts have nothing to add.
                continue;
            }

            Integer layers = arrayLength(biome, "layers", biomeKey, blockingErrors);
            Integer ceiling = arrayLength(biome, "caveCeilingLayers", biomeKey, blockingErrors);
            if (layers == null || ceiling == null) {
                continue;
            }

            if (ceiling > layers) {
                blockingErrors.add("Biome '" + biomeKey + "' declares " + ceiling + " caveCeilingLayers but only "
                        + layers + " layers. caveCeilingLayers reuses the layers height generators and must not have more entries.");
            }
        }
        return blockingErrors;
    }

    private static Integer arrayLength(JSONObject biome, String field, String biomeKey, List<String> blockingErrors) {
        if (!biome.has(field) || biome.isNull(field)) {
            return DEFAULT_LAYER_COUNT;
        }

        JSONArray array = biome.optJSONArray(field);
        if (array == null) {
            blockingErrors.add("Biome '" + biomeKey + "' " + field + " must be an array.");
            return null;
        }
        return array.length();
    }
}

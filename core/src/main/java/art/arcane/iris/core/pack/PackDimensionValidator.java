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

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

final class PackDimensionValidator {
    private PackDimensionValidator() {
    }

    static void validateDimensions(File packFolder, File[] dimensionFiles, List<String> blockingErrors, List<String> warnings) {
        File regionsFolder = new File(packFolder, "regions");
        File biomesFolder = new File(packFolder, "biomes");

        for (File dimFile : dimensionFiles) {
            String dimensionKey = PackValidationIo.stripExtension(dimFile.getName());
            JSONObject dimJson;
            try {
                dimJson = new JSONObject(Files.readString(dimFile.toPath(), StandardCharsets.UTF_8));
            } catch (Throwable e) {
                blockingErrors.add("Dimension '" + dimensionKey + "' has invalid JSON: " + e.getMessage());
                continue;
            }

            validateImportedStructurePolicy(dimensionKey, dimJson, blockingErrors);

            JSONArray regionsArray = dimJson.optJSONArray("regions");
            if (regionsArray == null || regionsArray.length() == 0) {
                blockingErrors.add("Dimension '" + dimensionKey + "' declares no regions.");
                continue;
            }

            int resolvedRegions = 0;
            for (int i = 0; i < regionsArray.length(); i++) {
                String regionKey = regionsArray.optString(i, null);
                if (regionKey == null || regionKey.isBlank()) {
                    warnings.add("Dimension '" + dimensionKey + "' has a blank region entry at index " + i + ".");
                    continue;
                }
                File regionFile = new File(regionsFolder, regionKey + ".json");
                if (!regionFile.isFile()) {
                    blockingErrors.add("Dimension '" + dimensionKey + "' references missing region '" + regionKey + "'.");
                    continue;
                }

                JSONObject regionJson;
                try {
                    regionJson = new JSONObject(Files.readString(regionFile.toPath(), StandardCharsets.UTF_8));
                } catch (Throwable e) {
                    blockingErrors.add("Region '" + regionKey + "' has invalid JSON: " + e.getMessage());
                    continue;
                }

                int anyBiome = countBiomeRefs(regionJson, "landBiomes", biomesFolder, regionKey, warnings)
                        + countBiomeRefs(regionJson, "seaBiomes", biomesFolder, regionKey, warnings)
                        + countBiomeRefs(regionJson, "shoreBiomes", biomesFolder, regionKey, warnings)
                        + countBiomeRefs(regionJson, "caveBiomes", biomesFolder, regionKey, warnings);
                if (anyBiome == 0) {
                    blockingErrors.add("Region '" + regionKey + "' has no resolvable biomes.");
                }
                resolvedRegions++;
            }

            if (resolvedRegions == 0) {
                blockingErrors.add("Dimension '" + dimensionKey + "' has no resolvable regions.");
            }
        }
    }

    static void validateImportedStructurePolicy(String dimensionKey, JSONObject dimension,
                                                List<String> blockingErrors) {
        if (!dimension.has("importedStructures")) {
            return;
        }
        if (dimension.isNull("importedStructures")) {
            blockingErrors.add("Dimension '" + dimensionKey + "' importedStructures must be an object.");
            return;
        }
        JSONObject policy = dimension.optJSONObject("importedStructures");
        if (policy == null) {
            blockingErrors.add("Dimension '" + dimensionKey + "' importedStructures must be an object.");
            return;
        }
        if (policy.has("mode")) {
            blockingErrors.add("Dimension '" + dimensionKey
                    + "' importedStructures.mode is not supported. Native structures are enabled by default; list explicit denials in importedStructures.disabled.");
        }
        if (policy.has("enabled")) {
            blockingErrors.add("Dimension '" + dimensionKey
                    + "' importedStructures.enabled is not supported. Native structures are enabled by default; list explicit denials in importedStructures.disabled.");
        }
        validateStructureKeyList(dimensionKey, policy, "disabled", blockingErrors);
        JSONArray adjustments = policy.optJSONArray("adjustments");
        if (adjustments == null) {
            if (policy.has("adjustments")) {
                blockingErrors.add("Dimension '" + dimensionKey
                        + "' importedStructures.adjustments must be an array.");
            }
            return;
        }
        for (int index = 0; index < adjustments.length(); index++) {
            JSONObject adjustment = adjustments.optJSONObject(index);
            if (adjustment == null) {
                blockingErrors.add("Dimension '" + dimensionKey
                        + "' importedStructures.adjustments has a non-object entry at index " + index + ".");
                continue;
            }
            validateStructureKeyList(dimensionKey, adjustment, "match", blockingErrors);
            validateAdjustmentYBand(dimensionKey, adjustment, index, blockingErrors);
            PackStructurePlacementValidator.validateNativeTerrain("Dimension '" + dimensionKey
                    + "' importedStructures.adjustments[" + index + "]", adjustment, blockingErrors);
        }
    }

    private static void validateAdjustmentYBand(String dimensionKey, JSONObject adjustment, int index,
                                                List<String> blockingErrors) {
        if (!adjustment.has("yBand") || adjustment.opt("yBand") == JSONObject.NULL) {
            return;
        }
        String path = "Dimension '" + dimensionKey
                + "' importedStructures.adjustments[" + index + "].yBand";
        JSONObject band = adjustment.optJSONObject("yBand");
        if (band == null) {
            blockingErrors.add(path + " must be an object.");
            return;
        }
        PackJsonFieldChecks.validateOptionalIntegerRange(path, band, "min", -4064, 4064, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, band, "max", -4064, 4064, blockingErrors);
    }

    private static void validateStructureKeyList(String dimensionKey, JSONObject owner, String field,
                                                 List<String> blockingErrors) {
        if (!owner.has(field)) {
            return;
        }
        JSONArray keys = owner.optJSONArray(field);
        if (keys == null) {
            blockingErrors.add("Dimension '" + dimensionKey + "' structure policy field '"
                    + field + "' must be an array.");
            return;
        }
        for (int index = 0; index < keys.length(); index++) {
            Object value = keys.opt(index);
            if (!(value instanceof String key) || key.isBlank()) {
                blockingErrors.add("Dimension '" + dimensionKey + "' structure policy field '"
                        + field + "' has a blank or non-string entry at index " + index + ".");
            }
        }
    }

    private static int countBiomeRefs(JSONObject regionJson, String field, File biomesFolder, String regionKey, List<String> warnings) {
        JSONArray arr = regionJson.optJSONArray(field);
        if (arr == null) {
            return 0;
        }
        int resolved = 0;
        for (int i = 0; i < arr.length(); i++) {
            String biomeKey = arr.optString(i, null);
            if (biomeKey == null || biomeKey.isBlank()) {
                continue;
            }
            File biomeFile = new File(biomesFolder, biomeKey + ".json");
            if (!biomeFile.isFile()) {
                warnings.add("Region '" + regionKey + "' references missing biome '" + biomeKey + "' in " + field + ".");
                continue;
            }
            resolved++;
        }
        return resolved;
    }
}

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

import art.arcane.iris.engine.object.IrisBiomeCustomSpawnType;
import art.arcane.iris.engine.object.IrisLoot;
import art.arcane.iris.engine.object.IrisLootReference;
import art.arcane.iris.engine.object.IrisLootTable;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformEntityType;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class PackValidator {
    private static final String TRASH_ROOT = ".iris-trash";
    private static final String DATAPACK_IMPORTS = "datapack-imports";
    private static final String EXTERNAL_DATAPACKS = "externaldatapacks";
    private static final String INTERNAL_DATAPACKS = "internaldatapacks";
    private static final String DATAPACKS_FOLDER = "datapacks";
    private static final String CACHE_FOLDER = "cache";
    private static final String OBJECTS_FOLDER = "objects";
    private static final String LOOT_FOLDER = "loot";
    private static final String DIMENSIONS_FOLDER = "dimensions";
    private static final String STRUCTURES_FOLDER = "structures";
    private static final String JIGSAW_POOLS_FOLDER = "jigsaw-pools";
    private static final String JIGSAW_PIECES_FOLDER = "jigsaw-pieces";
    private static final List<String> STRUCTURE_HOST_FOLDERS = List.of(DIMENSIONS_FOLDER, "regions", "biomes");
    private static final List<String> REMOVED_WORLDGEN_FIELDS = List.of("fluidBodies");
    private static final List<String> UNSUPPORTED_STRUCTURE_TRANSFORM_FIELDS = List.of("rotation", "translate", "scale");
    private static final Pattern RESOURCE_KEY_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private PackValidator() {
    }

    public static PackValidationResult validate(File packFolder) {
        String packName = packFolder == null ? "<unknown>" : packFolder.getName();
        List<String> blockingErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long validatedAt = System.currentTimeMillis();

        if (packFolder == null || !packFolder.isDirectory()) {
            blockingErrors.add("Pack folder does not exist or is not a directory.");
            return new PackValidationResult(packName, blockingErrors, warnings, validatedAt);
        }

        File dimensionsFolder = new File(packFolder, DIMENSIONS_FOLDER);
        if (!dimensionsFolder.isDirectory()) {
            blockingErrors.add("Missing dimensions/ folder.");
            return new PackValidationResult(packName, blockingErrors, warnings, validatedAt);
        }

        File[] dimensionFiles = dimensionsFolder.listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
        if (dimensionFiles == null || dimensionFiles.length == 0) {
            blockingErrors.add("No dimension JSON files under dimensions/.");
            return new PackValidationResult(packName, blockingErrors, warnings, validatedAt);
        }

        validateDimensions(packFolder, dimensionFiles, blockingErrors, warnings);
        blockingErrors.addAll(validateLootGraph(packFolder));
        blockingErrors.addAll(validateRemovedWorldgenFields(packFolder));
        blockingErrors.addAll(validateObjectSurfaceSupport(packFolder));
        blockingErrors.addAll(validateUnsupportedStructureTransforms(packFolder));
        blockingErrors.addAll(validateStructureGraph(packFolder));
        StructureGraphPackValidator.Validation compiledStructures =
                StructureGraphPackValidator.validate(
                        packFolder.toPath(), collectPlacedStructureKeys(packFolder));
        addDistinct(blockingErrors, compiledStructures.errors());
        addDistinct(warnings, compiledStructures.warnings());
        blockingErrors.addAll(validateNativeStructureReplacements(
                packFolder,
                compiledStructures.replacementOutputStructures(),
                compiledStructures.sampledVerticalEnvelopes()));
        blockingErrors.addAll(validateSpawnerEntityReferences(
                new File(packFolder, "spawners"), new File(packFolder, "entities")));
        blockingErrors.addAll(validateCustomBiomeSpawns(
                new File(packFolder, "biomes"), PackValidator::resolveEntitySpawnCategory));

        runContentKeyValidation(packFolder, warnings);

        return new PackValidationResult(packName, blockingErrors, warnings, validatedAt);
    }

    private static void addDistinct(List<String> destination, List<String> additions) {
        for (String addition : additions) {
            if (!destination.contains(addition)) {
                destination.add(addition);
            }
        }
    }

    static Set<String> collectPlacedStructureKeys(File packFolder) {
        Set<String> structureKeys = new LinkedHashSet<>();
        if (packFolder == null || !packFolder.isDirectory()) {
            return structureKeys;
        }
        for (String folderName : STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            for (File resourceFile : listJsonRecursive(resourceFolder)) {
                JSONObject resource = readJson(resourceFile);
                if (resource == null) {
                    continue;
                }
                JSONArray placements = resource.optJSONArray("structures");
                if (placements == null) {
                    continue;
                }
                for (int placementIndex = 0; placementIndex < placements.length(); placementIndex++) {
                    JSONObject placement = placements.optJSONObject(placementIndex);
                    if (placement == null) {
                        continue;
                    }
                    JSONArray references = placement.optJSONArray("structures");
                    if (references == null) {
                        continue;
                    }
                    for (int referenceIndex = 0; referenceIndex < references.length(); referenceIndex++) {
                        Object rawReference = references.opt(referenceIndex);
                        if (rawReference instanceof String structureKey && !structureKey.isBlank()) {
                            structureKeys.add(structureKey);
                        }
                    }
                }
            }
        }
        return Set.copyOf(structureKeys);
    }

    static List<String> validateUnsupportedStructureTransforms(File packFolder) {
        List<String> blockingErrors = new ArrayList<>();
        if (packFolder == null || !packFolder.isDirectory()) {
            return blockingErrors;
        }

        for (String folderName : STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            List<File> resourceFiles = listJsonRecursive(resourceFolder);
            resourceFiles.sort(Comparator.comparing(File::getPath));
            String resourceType = structureHostType(folderName);
            for (File resourceFile : resourceFiles) {
                JSONObject resource;
                try {
                    resource = new JSONObject(Files.readString(resourceFile.toPath(), StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                    continue;
                }

                JSONArray placements = resource.optJSONArray("structures");
                if (placements == null) {
                    continue;
                }
                String resourceKey = deriveKey(resourceFolder, resourceFile);
                for (int placementIndex = 0; placementIndex < placements.length(); placementIndex++) {
                    JSONObject placement = placements.optJSONObject(placementIndex);
                    if (placement == null) {
                        continue;
                    }
                    for (String field : UNSUPPORTED_STRUCTURE_TRANSFORM_FIELDS) {
                        if (placement.has(field)) {
                            blockingErrors.add(resourceType + " '" + resourceKey + "' structures[" + placementIndex
                                    + "] declares unsupported field '" + field
                                    + "'. Structure placement transforms are not supported; remove the field.");
                        }
                    }
                }
            }
        }
        return blockingErrors;
    }

    static List<String> validateStructureGraph(File packFolder) {
        List<String> blockingErrors = new ArrayList<>();
        if (packFolder == null || !packFolder.isDirectory()) {
            return blockingErrors;
        }

        File structuresFolder = new File(packFolder, STRUCTURES_FOLDER);
        File poolsFolder = new File(packFolder, JIGSAW_POOLS_FOLDER);
        File piecesFolder = new File(packFolder, JIGSAW_PIECES_FOLDER);
        File objectsFolder = new File(packFolder, OBJECTS_FOLDER);
        Set<String> structureKeys = deriveRegistrantKeysExact(structuresFolder);
        Set<String> poolKeys = deriveRegistrantKeysExact(poolsFolder);
        Set<String> pieceKeys = deriveRegistrantKeysExact(piecesFolder);
        Set<String> objectKeys = deriveObjectKeysExact(objectsFolder);

        validateStructurePlacements(packFolder, structureKeys, blockingErrors);
        validateStructureStartPools(structuresFolder, poolKeys, blockingErrors);
        validateJigsawPools(poolsFolder, poolKeys, pieceKeys, blockingErrors);
        validateJigsawPieces(piecesFolder, poolKeys, objectKeys, blockingErrors);
        return blockingErrors;
    }

    static List<String> validateLootGraph(File packFolder) {
        List<String> blockingErrors = new ArrayList<>();
        if (packFolder == null || !packFolder.isDirectory()) {
            return blockingErrors;
        }

        File lootFolder = new File(packFolder, LOOT_FOLDER);
        Set<String> lootKeys = deriveRegistrantKeysExact(lootFolder);
        if (lootFolder.isDirectory()) {
            List<File> lootFiles = listJsonRecursive(lootFolder);
            lootFiles.sort(Comparator.comparing(File::getPath));
            for (File lootFile : lootFiles) {
                String lootKey = deriveKey(lootFolder, lootFile);
                JSONObject table = readGraphJson(lootFile, "Loot table", lootKey, blockingErrors);
                if (table != null) {
                    validateLootTable(lootKey, table, blockingErrors);
                }
            }
        }

        for (String folderName : STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            List<File> resourceFiles = listJsonRecursive(resourceFolder);
            resourceFiles.sort(Comparator.comparing(File::getPath));
            for (File resourceFile : resourceFiles) {
                JSONObject resource = readJson(resourceFile);
                if (resource == null || !resource.has("loot")) {
                    continue;
                }
                String resourceType = structureHostType(folderName);
                String resourceKey = deriveKey(resourceFolder, resourceFile);
                validateLootReference(resourceType, resourceKey, resource.opt("loot"), lootKeys, blockingErrors);
            }
        }
        return blockingErrors;
    }

    static List<String> validateRemovedWorldgenFields(File packFolder) {
        List<String> blockingErrors = new ArrayList<>();
        if (packFolder == null || !packFolder.isDirectory()) {
            return blockingErrors;
        }

        for (String folderName : STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            List<File> resourceFiles = listJsonRecursive(resourceFolder);
            resourceFiles.sort(Comparator.comparing(File::getPath));
            String resourceType = structureHostType(folderName);
            for (File resourceFile : resourceFiles) {
                JSONObject resource = readJson(resourceFile);
                if (resource == null) {
                    continue;
                }
                String resourceKey = deriveKey(resourceFolder, resourceFile);
                for (String field : REMOVED_WORLDGEN_FIELDS) {
                    if (resource.has(field)) {
                        blockingErrors.add(resourceType + " '" + resourceKey + "' declares removed field '"
                                + field + "'. Remove it because fluid-body generation is not supported.");
                    }
                }
            }
        }
        return blockingErrors;
    }

    static List<String> validateObjectSurfaceSupport(File packFolder) {
        List<String> blockingErrors = new ArrayList<>();
        if (packFolder == null || !packFolder.isDirectory()) {
            return blockingErrors;
        }

        for (String folderName : STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            List<File> resourceFiles = listJsonRecursive(resourceFolder);
            resourceFiles.sort(Comparator.comparing(File::getPath));
            String resourceType = structureHostType(folderName);
            for (File resourceFile : resourceFiles) {
                JSONObject resource = readJson(resourceFile);
                if (resource == null) {
                    continue;
                }
                String path = resourceType + " '" + deriveKey(resourceFolder, resourceFile) + "'";
                if ("dimensions".equals(folderName)) {
                    validateOptionalIntegerRange(path, resource, "objectSurfaceSupportBuffer", 0, 16, blockingErrors);
                    validateOptionalBoolean(path, resource, "requireObjectSurfaceSupport", blockingErrors);
                }
                validateObjectPlacementSurfaceSupport(path, resource.optJSONArray("objects"), blockingErrors);
            }
        }
        return blockingErrors;
    }

    private static void validateObjectPlacementSurfaceSupport(String path, JSONArray placements,
                                                              List<String> blockingErrors) {
        if (placements == null) {
            return;
        }
        for (int i = 0; i < placements.length(); i++) {
            JSONObject placement = placements.optJSONObject(i);
            if (placement == null) {
                continue;
            }
            String placementPath = path + ".objects[" + i + "]";
            if (placement.has("surfaceOpeningClearance")) {
                blockingErrors.add(placementPath + " declares removed field 'surfaceOpeningClearance'. "
                        + "Use surfaceSupportBuffer instead.");
            }
            validateOptionalIntegerRange(placementPath, placement, "surfaceSupportBuffer", 0, 16, blockingErrors);
            validateOptionalIntegerRange(placementPath, placement, "surfaceSupportDepth", 1, 16, blockingErrors);
            validateOptionalBoolean(placementPath, placement, "requireSurfaceSupport", blockingErrors);
        }
    }

    private static void validateOptionalBoolean(String path, JSONObject object, String field,
                                                List<String> blockingErrors) {
        if (!object.has(field) || object.opt(field) == JSONObject.NULL) {
            return;
        }
        if (!(object.opt(field) instanceof Boolean)) {
            blockingErrors.add(path + "." + field + " must be a boolean.");
        }
    }

    private static void validateLootTable(String lootKey, JSONObject table, List<String> blockingErrors) {
        String path = "Loot table '" + lootKey + "'";
        Integer rarity = lootInteger(table, "rarity", 1, path, blockingErrors);
        Integer minimumPicked = lootInteger(table, "minPicked", 1, path, blockingErrors);
        Integer maximumPicked = lootInteger(table, "maxPicked", 5, path, blockingErrors);
        Integer maximumTries = lootInteger(table, "maxTries", 10, path, blockingErrors);
        requireMinimum(path + ".rarity", rarity, 1, blockingErrors);
        requireMinimum(path + ".minPicked", minimumPicked, 0, blockingErrors);
        requireMinimum(path + ".maxPicked", maximumPicked, 1, blockingErrors);
        requireMinimum(path + ".maxTries", maximumTries, 1, blockingErrors);
        requireMaximum(path + ".minPicked", minimumPicked, IrisLootTable.MAX_PICKED, blockingErrors);
        requireMaximum(path + ".maxPicked", maximumPicked, IrisLootTable.MAX_PICKED, blockingErrors);
        requireMaximum(path + ".maxTries", maximumTries, IrisLootTable.MAX_TRIES, blockingErrors);
        requireOrdered(path + ".minPicked", minimumPicked, path + ".maxPicked", maximumPicked, blockingErrors);

        JSONArray entries = table.optJSONArray("loot");
        if (entries == null || entries.length() == 0) {
            blockingErrors.add(path + ".loot must be a non-empty array.");
            return;
        }
        for (int entryIndex = 0; entryIndex < entries.length(); entryIndex++) {
            JSONObject entry = entries.optJSONObject(entryIndex);
            String entryPath = path + ".loot[" + entryIndex + "]";
            if (entry == null) {
                blockingErrors.add(entryPath + " must be an object.");
                continue;
            }
            String type = entry.optString("type", "").trim();
            if (type.isEmpty()) {
                blockingErrors.add(entryPath + ".type must not be blank.");
            }
            Integer entryRarity = lootInteger(entry, "rarity", 1, entryPath, blockingErrors);
            Integer minimumAmount = lootInteger(entry, "minAmount", 1, entryPath, blockingErrors);
            Integer maximumAmount = lootInteger(entry, "maxAmount", 1, entryPath, blockingErrors);
            requireMinimum(entryPath + ".rarity", entryRarity, 1, blockingErrors);
            requireMinimum(entryPath + ".minAmount", minimumAmount, 1, blockingErrors);
            requireMinimum(entryPath + ".maxAmount", maximumAmount, 1, blockingErrors);
            requireMaximum(entryPath + ".minAmount", minimumAmount, IrisLoot.MAX_AMOUNT, blockingErrors);
            requireMaximum(entryPath + ".maxAmount", maximumAmount, IrisLoot.MAX_AMOUNT, blockingErrors);
            requireOrdered(entryPath + ".minAmount", minimumAmount,
                    entryPath + ".maxAmount", maximumAmount, blockingErrors);
            validateLootEnchantments(entryPath, entry.opt("enchantments"), blockingErrors);
        }
    }

    private static void validateLootEnchantments(String entryPath, Object rawEnchantments,
                                                  List<String> blockingErrors) {
        if (rawEnchantments == null || rawEnchantments == JSONObject.NULL) {
            return;
        }
        if (!(rawEnchantments instanceof JSONArray enchantments)) {
            blockingErrors.add(entryPath + ".enchantments must be an array.");
            return;
        }
        for (int enchantmentIndex = 0; enchantmentIndex < enchantments.length(); enchantmentIndex++) {
            JSONObject enchantment = enchantments.optJSONObject(enchantmentIndex);
            String enchantmentPath = entryPath + ".enchantments[" + enchantmentIndex + "]";
            if (enchantment == null) {
                blockingErrors.add(enchantmentPath + " must be an object.");
                continue;
            }
            if (enchantment.optString("enchantment", "").isBlank()) {
                blockingErrors.add(enchantmentPath + ".enchantment must not be blank.");
            }
            Integer minimumLevel = lootInteger(enchantment, "minLevel", 1, enchantmentPath, blockingErrors);
            Integer maximumLevel = lootInteger(enchantment, "maxLevel", 1, enchantmentPath, blockingErrors);
            requireMinimum(enchantmentPath + ".minLevel", minimumLevel, 1, blockingErrors);
            requireMinimum(enchantmentPath + ".maxLevel", maximumLevel, 1, blockingErrors);
            requireOrdered(enchantmentPath + ".minLevel", minimumLevel,
                    enchantmentPath + ".maxLevel", maximumLevel, blockingErrors);
            if (enchantment.has("chance")) {
                Object rawChance = enchantment.opt("chance");
                if (!(rawChance instanceof Number number)
                        || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() < 0D
                        || number.doubleValue() > 1D) {
                    blockingErrors.add(enchantmentPath + ".chance must be a finite number from 0 to 1.");
                }
            }
        }
    }

    private static void validateLootReference(String resourceType, String resourceKey, Object rawLoot,
                                              Set<String> lootKeys, List<String> blockingErrors) {
        String path = resourceType + " '" + resourceKey + "'.loot";
        if (!(rawLoot instanceof JSONObject reference)) {
            blockingErrors.add(path + " must be an object.");
            return;
        }
        if (reference.has("mode")) {
            Object rawMode = reference.opt("mode");
            if (!(rawMode instanceof String mode)
                    || !Set.of("ADD", "CLEAR", "REPLACE", "FALLBACK").contains(mode)) {
                blockingErrors.add(path + ".mode must be ADD, CLEAR, REPLACE, or FALLBACK.");
            }
        }
        if (reference.has("multiplier")) {
            Object rawMultiplier = reference.opt("multiplier");
            if (!(rawMultiplier instanceof Number multiplier)
                    || !Double.isFinite(multiplier.doubleValue())
                    || multiplier.doubleValue() < 0D
                    || multiplier.doubleValue() > IrisLootReference.MAX_MULTIPLIER) {
                blockingErrors.add(path + ".multiplier must be a finite number from 0 to "
                        + (int) IrisLootReference.MAX_MULTIPLIER + ".");
            }
        }
        if (!reference.has("tables")) {
            return;
        }
        JSONArray tables = reference.optJSONArray("tables");
        if (tables == null) {
            blockingErrors.add(path + ".tables must be an array.");
            return;
        }
        for (int tableIndex = 0; tableIndex < tables.length(); tableIndex++) {
            Object rawTableKey = tables.opt(tableIndex);
            if (!(rawTableKey instanceof String tableKey) || tableKey.isBlank()) {
                blockingErrors.add(path + ".tables[" + tableIndex + "] must name a loot table.");
            } else if (!lootKeys.contains(tableKey)) {
                blockingErrors.add(path + ".tables[" + tableIndex
                        + "] references missing loot table '" + tableKey + "'.");
            }
        }
    }

    private static Integer lootInteger(JSONObject object, String field, int defaultValue,
                                       String path, List<String> blockingErrors) {
        if (!object.has(field)) {
            return defaultValue;
        }
        Object rawValue = object.opt(field);
        if (!(rawValue instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() < Integer.MIN_VALUE
                || number.longValue() > Integer.MAX_VALUE) {
            blockingErrors.add(path + "." + field + " must be an integer.");
            return null;
        }
        return number.intValue();
    }

    private static void requireMinimum(String fieldPath, Integer value, int minimum,
                                       List<String> blockingErrors) {
        if (value != null && value < minimum) {
            blockingErrors.add(fieldPath + " must be at least " + minimum + ".");
        }
    }

    private static void requireMaximum(String fieldPath, Integer value, int maximum,
                                       List<String> blockingErrors) {
        if (value != null && value > maximum) {
            blockingErrors.add(fieldPath + " must be at most " + maximum + ".");
        }
    }

    private static void requireOrdered(String minimumPath, Integer minimum, String maximumPath,
                                       Integer maximum, List<String> blockingErrors) {
        if (minimum != null && maximum != null && minimum > maximum) {
            blockingErrors.add(minimumPath + " must not exceed " + maximumPath + ".");
        }
    }

    static List<String> validateNativeStructureReplacements(
            File packFolder,
            Set<String> replacementOutputStructures,
            Map<String, List<StructureGraphPackValidator.SampledVerticalEnvelope>> sampledVerticalEnvelopes
    ) {
        List<String> blockingErrors = new ArrayList<>();
        if (packFolder == null || !packFolder.isDirectory()) {
            return blockingErrors;
        }
        Set<String> viableStructures = replacementOutputStructures == null
                ? Set.of() : replacementOutputStructures;
        Map<String, List<StructureGraphPackValidator.SampledVerticalEnvelope>> verticalEnvelopes =
                sampledVerticalEnvelopes == null ? Map.of() : sampledVerticalEnvelopes;
        File structuresFolder = new File(packFolder, STRUCTURES_FOLDER);
        Map<String, JSONObject> structures = new HashMap<>();
        for (File structureFile : listJsonRecursive(structuresFolder)) {
            JSONObject structure = readJson(structureFile);
            if (structure != null) {
                structures.put(deriveKey(structuresFolder, structureFile), structure);
            }
        }

        for (String folderName : STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            List<File> resourceFiles = listJsonRecursive(resourceFolder);
            resourceFiles.sort(Comparator.comparing(File::getPath));
            String resourceType = structureHostType(folderName);
            for (File resourceFile : resourceFiles) {
                JSONObject resource = readJson(resourceFile);
                if (resource == null) {
                    continue;
                }
                JSONArray placements = resource.optJSONArray("structures");
                if (placements == null) {
                    continue;
                }
                String resourceKey = deriveKey(resourceFolder, resourceFile);
                for (int placementIndex = 0; placementIndex < placements.length(); placementIndex++) {
                    JSONObject placement = placements.optJSONObject(placementIndex);
                    if (placement == null || !placement.has("nativeSuppression")) {
                        continue;
                    }
                    Object rawSuppression = placement.opt("nativeSuppression");
                    if (!(rawSuppression instanceof String suppression)) {
                        blockingErrors.add(resourceType + " '" + resourceKey + "' structures["
                                + placementIndex + "].nativeSuppression must be NONE or REPLACE_SOURCE.");
                        continue;
                    }
                    if ("NONE".equals(suppression)) {
                        continue;
                    }
                    if (!"REPLACE_SOURCE".equals(suppression)) {
                        blockingErrors.add(resourceType + " '" + resourceKey + "' structures["
                                + placementIndex + "].nativeSuppression has unsupported value '"
                                + suppression + "'. Use NONE or REPLACE_SOURCE.");
                        continue;
                    }
                    if (!DIMENSIONS_FOLDER.equals(folderName)) {
                        blockingErrors.add(resourceType + " '" + resourceKey + "' structures["
                                + placementIndex + "] requests REPLACE_SOURCE, but native replacement is only"
                                + " valid on dimension-level placements.");
                        continue;
                    }
                    JSONArray references = placement.optJSONArray("structures");
                    JSONArray nativeStructures = placement.optJSONArray("nativeStructures");
                    if (nativeStructures != null && nativeStructures.length() > 0) {
                        continue;
                    }
                    if (references == null || references.length() == 0) {
                        blockingErrors.add("Dimension '" + resourceKey + "' structures[" + placementIndex
                                + "] requests REPLACE_SOURCE without any structure backend.");
                        continue;
                    }
                    for (int referenceIndex = 0; referenceIndex < references.length(); referenceIndex++) {
                        Object rawReference = references.opt(referenceIndex);
                        if (!(rawReference instanceof String structureKey) || structureKey.isBlank()) {
                            blockingErrors.add("Dimension '" + resourceKey + "' structures[" + placementIndex
                                    + "].structures[" + referenceIndex
                                    + "] must name an Iris structure for REPLACE_SOURCE.");
                            continue;
                        }
                        JSONObject structure = structures.get(structureKey);
                        if (structure == null) {
                            blockingErrors.add("Dimension '" + resourceKey + "' structures[" + placementIndex
                                    + "] cannot REPLACE_SOURCE with missing or invalid structure '"
                                    + structureKey + "'.");
                            continue;
                        }
                        String vanillaSource = structure.optString("vanillaSource", "").trim();
                        if (!RESOURCE_KEY_PATTERN.matcher(vanillaSource).matches()) {
                            blockingErrors.add("Dimension '" + resourceKey + "' structures[" + placementIndex
                                    + "] requests REPLACE_SOURCE for structure '" + structureKey
                                    + "', but its vanillaSource is not a valid namespaced registry key.");
                        }
                        if (!viableStructures.contains(structureKey)) {
                            blockingErrors.add("Dimension '" + resourceKey + "' structures[" + placementIndex
                                    + "] requests REPLACE_SOURCE for structure '" + structureKey
                                    + "', but that structure is not runtime-viable. Native generation will not"
                                    + " be used as a fallback.");
                            continue;
                        }
                        validateReplacementVerticalEnvelope(
                                resourceKey,
                                resource,
                                placementIndex,
                                placement,
                                structureKey,
                                structure,
                                verticalEnvelopes.get(structureKey),
                                blockingErrors);
                    }
                }
            }
        }
        return blockingErrors;
    }

    private static void validateReplacementVerticalEnvelope(
            String dimensionKey,
            JSONObject dimension,
            int placementIndex,
            JSONObject placement,
            String structureKey,
            JSONObject structure,
            List<StructureGraphPackValidator.SampledVerticalEnvelope> sampledVerticalEnvelopes,
            List<String> blockingErrors
    ) {
        String context = "Dimension '" + dimensionKey + "' structures[" + placementIndex
                + "] REPLACE_SOURCE structure '" + structureKey + "'";
        if (sampledVerticalEnvelopes == null || sampledVerticalEnvelopes.isEmpty()) {
            blockingErrors.add(context + " has no sampled vertical envelope. Native generation will not"
                    + " be used as a fallback.");
            return;
        }

        DimensionVerticalBounds worldBounds = resolveDimensionVerticalBounds(dimension, context, blockingErrors);
        PlacementVerticalBounds placementBounds = resolvePlacementVerticalBounds(placement, context, blockingErrors);
        ObjectPlaceMode placeMode = resolvePlaceMode(structure, context, blockingErrors);
        if (worldBounds == null || placementBounds == null || placeMode == null) {
            return;
        }

        for (StructureGraphPackValidator.SampledVerticalEnvelope sampled : sampledVerticalEnvelopes) {
            boolean exactY = placementBounds.underground()
                    || sampled.pieceCount() > 1
                    || placeMode == ObjectPlaceMode.STRUCTURE_PIECE
                    || placeMode == ObjectPlaceMode.FLOATING;
            if (!exactY) {
                continue;
            }

            long minimumYOffset = sampled.minimumYOffset();
            long maximumYOffset = sampled.maximumYOffset();
            boolean surfaceAligned = !placementBounds.underground()
                    && sampled.pieceCount() > 1
                    && placeMode != ObjectPlaceMode.STRUCTURE_PIECE
                    && placeMode != ObjectPlaceMode.FLOATING;
            if (surfaceAligned) {
                maximumYOffset -= minimumYOffset;
                minimumYOffset = 0L;
            }

            boolean fitsConfiguredRange;
            if (placementBounds.underground()) {
                long minimumAnchor = Math.max(
                        Math.max(placementBounds.minimumY(), worldBounds.minimumY()),
                        worldBounds.minimumY() - minimumYOffset);
                long maximumAnchor = Math.min(
                        Math.min(placementBounds.maximumY(), worldBounds.maximumY()),
                        worldBounds.maximumY() - maximumYOffset);
                fitsConfiguredRange = minimumAnchor <= maximumAnchor;
            } else {
                long minimumTerrainY = Math.max(placementBounds.minimumY(), worldBounds.minimumY());
                long maximumTerrainY = Math.min(placementBounds.maximumY(), worldBounds.maximumY());
                fitsConfiguredRange = minimumTerrainY <= maximumTerrainY
                        && minimumTerrainY + minimumYOffset >= worldBounds.minimumY()
                        && maximumTerrainY + maximumYOffset <= worldBounds.maximumY();
            }
            if (fitsConfiguredRange) {
                continue;
            }

            String alignment = surfaceAligned ? "surface-aligned " : "";
            blockingErrors.add(context + " sampled seed " + sampled.seed() + " has an " + alignment
                    + "exact-Y piece envelope " + minimumYOffset + ".." + maximumYOffset
                    + " relative to its anchor, which cannot fit placement band "
                    + placementBounds.minimumY() + ".." + placementBounds.maximumY()
                    + " inside writable world " + worldBounds.minimumY() + ".." + worldBounds.maximumY()
                    + ". Native generation will not be used as a fallback.");
            return;
        }
    }

    private static DimensionVerticalBounds resolveDimensionVerticalBounds(
            JSONObject dimension,
            String context,
            List<String> blockingErrors
    ) {
        long dimensionMinimum = -64L;
        long dimensionMaximum = 320L;
        if (dimension.has("dimensionHeight")) {
            JSONObject dimensionHeight = dimension.optJSONObject("dimensionHeight");
            if (dimensionHeight == null) {
                blockingErrors.add(context + " cannot validate its vertical envelope because dimensionHeight"
                        + " must be an object.");
                return null;
            }
            Long configuredMinimum = integralJsonNumber(dimensionHeight, "min", 16L);
            Long configuredMaximum = integralJsonNumber(dimensionHeight, "max", 32L);
            if (configuredMinimum == null || configuredMaximum == null) {
                blockingErrors.add(context + " cannot validate its vertical envelope because dimensionHeight"
                        + " min and max must be finite integer values.");
                return null;
            }
            dimensionMinimum = configuredMinimum;
            dimensionMaximum = configuredMaximum;
        }

        long writableMinimum = dimensionMinimum + 1L;
        long writableMaximum = dimensionMaximum - 1L;
        if (writableMinimum > writableMaximum) {
            blockingErrors.add(context + " cannot validate its vertical envelope because dimensionHeight "
                    + dimensionMinimum + ".." + dimensionMaximum + " has no writable structure range.");
            return null;
        }
        return new DimensionVerticalBounds(writableMinimum, writableMaximum);
    }

    private static PlacementVerticalBounds resolvePlacementVerticalBounds(
            JSONObject placement,
            String context,
            List<String> blockingErrors
    ) {
        boolean underground = false;
        if (placement.has("underground")) {
            Object rawUnderground = placement.opt("underground");
            if (!(rawUnderground instanceof Boolean configuredUnderground)) {
                blockingErrors.add(context + " cannot validate its vertical envelope because underground"
                        + " must be true or false.");
                return null;
            }
            underground = configuredUnderground;
        }
        Long configuredMinimum = integralJsonNumber(placement, "minHeight", -2032L);
        Long configuredMaximum = integralJsonNumber(placement, "maxHeight", 2032L);
        if (configuredMinimum == null || configuredMaximum == null) {
            blockingErrors.add(context + " cannot validate its vertical envelope because minHeight and"
                    + " maxHeight must be finite integer values.");
            return null;
        }
        long minimumY = underground
                ? Math.min(configuredMinimum, configuredMaximum) : configuredMinimum;
        long maximumY = underground
                ? Math.max(configuredMinimum, configuredMaximum) : configuredMaximum;
        return new PlacementVerticalBounds(minimumY, maximumY, underground);
    }

    private static ObjectPlaceMode resolvePlaceMode(
            JSONObject structure,
            String context,
            List<String> blockingErrors
    ) {
        if (!structure.has("placeMode")) {
            return ObjectPlaceMode.STRUCTURE_PIECE;
        }
        Object rawPlaceMode = structure.opt("placeMode");
        if (!(rawPlaceMode instanceof String placeModeName)) {
            blockingErrors.add(context + " cannot validate its vertical envelope because placeMode must name"
                    + " an Iris object place mode.");
            return null;
        }
        try {
            return ObjectPlaceMode.valueOf(placeModeName);
        } catch (IllegalArgumentException exception) {
            blockingErrors.add(context + " cannot validate its vertical envelope because placeMode '"
                    + placeModeName + "' is not supported.");
            return null;
        }
    }

    private static Long integralJsonNumber(JSONObject owner, String field, long defaultValue) {
        if (!owner.has(field)) {
            return defaultValue;
        }
        Object rawValue = owner.opt(field);
        if (!(rawValue instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return (long) value;
    }

    private static void validateStructurePlacements(File packFolder,
                                                    Set<String> structureKeys,
                                                    List<String> blockingErrors) {
        Set<String> registeredStructures = registeredStructureKeys();
        Set<String> registeredJigsaws = registeredJigsawKeys();
        Set<String> registeredPools = registeredTemplatePoolKeys();
        for (String folderName : STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            List<File> resourceFiles = listJsonRecursive(resourceFolder);
            resourceFiles.sort(Comparator.comparing(File::getPath));
            String resourceType = structureHostType(folderName);
            for (File resourceFile : resourceFiles) {
                JSONObject resource = readJson(resourceFile);
                if (resource == null) {
                    continue;
                }
                JSONArray placements = resource.optJSONArray("structures");
                if (placements == null) {
                    continue;
                }
                String resourceKey = deriveKey(resourceFolder, resourceFile);
                for (int placementIndex = 0; placementIndex < placements.length(); placementIndex++) {
                    JSONObject placement = placements.optJSONObject(placementIndex);
                    if (placement == null) {
                        continue;
                    }
                    JSONArray references = placement.optJSONArray("structures");
                    JSONArray nativeStructures = placement.optJSONArray("nativeStructures");
                    boolean hasIrisStructures = references != null && references.length() > 0;
                    boolean hasNativeStructures = nativeStructures != null && nativeStructures.length() > 0;
                    String placementPath = resourceType + " '" + resourceKey + "' structures["
                            + placementIndex + "]";
                    if (hasIrisStructures == hasNativeStructures) {
                        blockingErrors.add(placementPath
                                + " must declare exactly one non-empty backend: structures or nativeStructures.");
                        continue;
                    }
                    if (hasNativeStructures) {
                        validateNativeStructures(
                                placementPath, placement, nativeStructures,
                                registeredStructures, registeredJigsaws,
                                registeredPools, blockingErrors);
                        continue;
                    }
                    for (int referenceIndex = 0; referenceIndex < references.length(); referenceIndex++) {
                        Object rawReference = references.opt(referenceIndex);
                        if (!(rawReference instanceof String structureKey) || structureKey.isBlank()) {
                            continue;
                        }
                        if (!structureKeys.contains(structureKey)) {
                            blockingErrors.add(resourceType + " '" + resourceKey + "' structures["
                                    + placementIndex + "].structures[" + referenceIndex
                                    + "] references missing structure '" + structureKey + "'.");
                        }
                    }
                }
            }
        }
    }

    private static Set<String> registeredJigsawKeys() {
        try {
            List<String> registered = IrisPlatforms.get().structureHooks().jigsawStructureKeys();
            if (registered == null || registered.isEmpty()) {
                return Set.of();
            }
            Set<String> keys = new HashSet<>();
            for (String key : registered) {
                if (key != null && !key.isBlank()) {
                    keys.add(key.toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(keys);
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    private static Set<String> registeredStructureKeys() {
        try {
            List<String> registered = IrisPlatforms.get().structureHooks().structureKeys();
            if (registered == null || registered.isEmpty()) {
                return Set.of();
            }
            Set<String> keys = new HashSet<>();
            for (String key : registered) {
                if (key != null && !key.isBlank()) {
                    keys.add(key.toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(keys);
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    private static Set<String> registeredTemplatePoolKeys() {
        try {
            List<String> registered = IrisPlatforms.get().structureHooks().templatePoolKeys();
            if (registered == null || registered.isEmpty()) {
                return Set.of();
            }
            Set<String> keys = new HashSet<>();
            for (String key : registered) {
                if (key != null && !key.isBlank()) {
                    keys.add(key.toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(keys);
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    private static void validateNativeStructures(String placementPath, JSONObject placement,
                                                 JSONArray nativeStructures,
                                                 Set<String> registeredStructures,
                                                 Set<String> registeredJigsaws,
                                                 Set<String> registeredPools,
                                                 List<String> blockingErrors) {
        for (int sourceIndex = 0; sourceIndex < nativeStructures.length(); sourceIndex++) {
            String sourcePath = placementPath + ".nativeStructures[" + sourceIndex + "]";
            JSONObject source = nativeStructures.optJSONObject(sourceIndex);
            if (source == null) {
                blockingErrors.add(sourcePath + " must be an object.");
                continue;
            }
            String structureKey = source.optString("structure", "").trim();
            if (!RESOURCE_KEY_PATTERN.matcher(structureKey).matches()) {
                blockingErrors.add(sourcePath + ".structure must be a namespaced registry key.");
            } else if (!registeredStructures.isEmpty()
                    && !registeredStructures.contains(structureKey.toLowerCase(Locale.ROOT))) {
                blockingErrors.add(sourcePath + ".structure '" + structureKey
                        + "' is not a registered structure.");
            }
            Integer weight = lootInteger(source, "weight", 1, sourcePath, blockingErrors);
            requireMinimum(sourcePath + ".weight", weight, 1, blockingErrors);
            JSONObject jigsaw = source.optJSONObject("jigsaw");
            if (source.has("jigsaw") && source.opt("jigsaw") != JSONObject.NULL && jigsaw == null) {
                blockingErrors.add(sourcePath + ".jigsaw must be an object.");
            } else if (jigsaw != null) {
                if (!registeredJigsaws.isEmpty()
                        && !registeredJigsaws.contains(structureKey.toLowerCase(Locale.ROOT))) {
                    blockingErrors.add(sourcePath
                            + ".jigsaw requires a registered jigsaw structure.");
                }
                validateJigsawAssembly(
                        sourcePath + ".jigsaw", jigsaw, registeredPools, blockingErrors);
            }
        }
        validateNativeTerrain(placementPath, placement, blockingErrors);
    }

    private static void validateJigsawAssembly(String path, JSONObject assembly,
                                               Set<String> registeredPools,
                                               List<String> blockingErrors) {
        validateOptionalResourceKey(path, assembly, "startPool", false, blockingErrors);
        String startPool = assembly.optString("startPool", "").trim();
        if (!startPool.isEmpty() && !registeredPools.isEmpty()
                && !registeredPools.contains(startPool.toLowerCase(Locale.ROOT))) {
            blockingErrors.add(path + ".startPool '" + startPool
                    + "' is not a registered template pool.");
        }
        validateOptionalResourceKey(path, assembly, "startJigsawName", true, blockingErrors);
        validateOptionalIntegerRange(path, assembly, "maxDepth", 0, 20, blockingErrors);
        validateOptionalIntegerRange(path, assembly, "maxDistanceHorizontal", 1, 128, blockingErrors);
        validateOptionalIntegerRange(path, assembly, "maxDistanceVertical", 1, 4064, blockingErrors);
        validateOptionalIntegerRange(
                path, assembly, "dimensionPaddingBottom", 0, Integer.MAX_VALUE, blockingErrors);
        validateOptionalIntegerRange(
                path, assembly, "dimensionPaddingTop", 0, Integer.MAX_VALUE, blockingErrors);
        if (assembly.has("useExpansionHack")
                && !(assembly.opt("useExpansionHack") instanceof Boolean)) {
            blockingErrors.add(path + ".useExpansionHack must be a boolean.");
        }
        validateOptionalEnum(path, assembly, "projectStartToHeightmap",
                Set.of("SOURCE", "NONE", "WORLD_SURFACE_WG", "WORLD_SURFACE",
                        "OCEAN_FLOOR_WG", "OCEAN_FLOOR", "MOTION_BLOCKING",
                        "MOTION_BLOCKING_NO_LEAVES"), blockingErrors);
        validateOptionalEnum(path, assembly, "liquidSettings",
                Set.of("SOURCE", "IGNORE_WATERLOGGING", "APPLY_WATERLOGGING"), blockingErrors);
    }

    private static void validateNativeTerrain(String path, JSONObject placement,
                                              List<String> blockingErrors) {
        JSONObject terrain = placement.optJSONObject("terrain");
        if (placement.has("terrain") && placement.opt("terrain") != JSONObject.NULL && terrain == null) {
            blockingErrors.add(path + ".terrain must be an object.");
            return;
        }
        if (terrain == null) {
            return;
        }
        validateOptionalEnum(path + ".terrain", terrain, "mode",
                Set.of("SOURCE", "PRESERVE", "BORE", "FORCE_CARVE", "VACUUM", "ENCASE"), blockingErrors);
        validateOptionalIntegerRange(path + ".terrain", terrain,
                "horizontalPadding", 0, 128, blockingErrors);
        validateOptionalIntegerRange(path + ".terrain", terrain,
                "ceilingPadding", 0, 128, blockingErrors);
        validateOptionalIntegerRange(path + ".terrain", terrain,
                "floorPadding", 0, 64, blockingErrors);
        validateOptionalDoubleRange(path + ".terrain", terrain,
                "erosionStrength", 0D, 1D, blockingErrors);
        validateOptionalDoubleRange(path + ".terrain", terrain,
                "erosionFrequency", 0.001D, 1D, blockingErrors);
        validateOptionalDoubleRange(path + ".terrain", terrain,
                "lobeFrequency", 0D, 1D, blockingErrors);
        validateOptionalDoubleRange(path + ".terrain", terrain,
                "lobeStrength", 0D, 1D, blockingErrors);
        if (terrain.has("encasePalette") && terrain.opt("encasePalette") != JSONObject.NULL
                && terrain.optJSONObject("encasePalette") == null) {
            blockingErrors.add(path + ".terrain.encasePalette must be an object.");
        }
    }

    private static void validateOptionalResourceKey(String path, JSONObject object, String field,
                                                    boolean allowNone, List<String> blockingErrors) {
        if (!object.has(field)) {
            return;
        }
        Object rawValue = object.opt(field);
        if (!(rawValue instanceof String value)) {
            blockingErrors.add(path + "." + field + " must be a string.");
            return;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || allowNone && "NONE".equalsIgnoreCase(normalized)) {
            return;
        }
        if (!RESOURCE_KEY_PATTERN.matcher(normalized).matches()) {
            blockingErrors.add(path + "." + field + " must be a namespaced registry key.");
        }
    }

    private static void validateOptionalIntegerRange(String path, JSONObject object, String field,
                                                     int minimum, int maximum,
                                                     List<String> blockingErrors) {
        if (!object.has(field) || object.opt(field) == JSONObject.NULL) {
            return;
        }
        Integer value = lootInteger(object, field, minimum, path, blockingErrors);
        requireMinimum(path + "." + field, value, minimum, blockingErrors);
        requireMaximum(path + "." + field, value, maximum, blockingErrors);
    }

    private static void validateOptionalDoubleRange(String path, JSONObject object, String field,
                                                    double minimum, double maximum,
                                                    List<String> blockingErrors) {
        if (!object.has(field) || object.opt(field) == JSONObject.NULL) {
            return;
        }
        String fieldPath = path + "." + field;
        Object rawValue = object.opt(field);
        if (!(rawValue instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            blockingErrors.add(fieldPath + " must be a number.");
            return;
        }
        double value = number.doubleValue();
        if (value < minimum) {
            blockingErrors.add(fieldPath + " must be at least " + minimum + ".");
        }
        if (value > maximum) {
            blockingErrors.add(fieldPath + " must be at most " + maximum + ".");
        }
    }

    private static void validateOptionalEnum(String path, JSONObject object, String field,
                                             Set<String> values, List<String> blockingErrors) {
        if (!object.has(field)) {
            return;
        }
        Object rawValue = object.opt(field);
        if (!(rawValue instanceof String value) || !values.contains(value)) {
            blockingErrors.add(path + "." + field + " must be one of " + values + ".");
        }
    }

    private static void validateStructureStartPools(File structuresFolder,
                                                     Set<String> poolKeys,
                                                     List<String> blockingErrors) {
        if (!structuresFolder.isDirectory()) {
            return;
        }
        List<File> structureFiles = listJsonRecursive(structuresFolder);
        structureFiles.sort(Comparator.comparing(File::getPath));
        for (File structureFile : structureFiles) {
            String structureKey = deriveKey(structuresFolder, structureFile);
            JSONObject structure = readGraphJson(structureFile, "Structure", structureKey, blockingErrors);
            if (structure == null || isLegacyStructureIndex(structureKey, structure)) {
                continue;
            }
            String startPool = structure.optString("startPool", "").trim();
            if (startPool.isEmpty()) {
                blockingErrors.add("Structure '" + structureKey + "' does not declare a startPool.");
            } else if (!poolKeys.contains(startPool)) {
                blockingErrors.add("Structure '" + structureKey + "' references missing start pool '"
                        + startPool + "'.");
            }
        }
    }

    private static void validateJigsawPools(File poolsFolder,
                                            Set<String> poolKeys,
                                            Set<String> pieceKeys,
                                            List<String> blockingErrors) {
        if (!poolsFolder.isDirectory()) {
            return;
        }
        List<File> poolFiles = listJsonRecursive(poolsFolder);
        poolFiles.sort(Comparator.comparing(File::getPath));
        for (File poolFile : poolFiles) {
            String poolKey = deriveKey(poolsFolder, poolFile);
            JSONObject pool = readGraphJson(poolFile, "Jigsaw pool", poolKey, blockingErrors);
            if (pool == null) {
                continue;
            }
            JSONArray entries = pool.optJSONArray("pieces");
            if (entries != null) {
                for (int entryIndex = 0; entryIndex < entries.length(); entryIndex++) {
                    JSONObject entry = entries.optJSONObject(entryIndex);
                    if (entry == null) {
                        continue;
                    }
                    String pieceKey = entry.optString("piece", "").trim();
                    if (!pieceKey.isEmpty() && !pieceKeys.contains(pieceKey)) {
                        blockingErrors.add("Jigsaw pool '" + poolKey + "' pieces[" + entryIndex
                                + "] references missing piece '" + pieceKey + "'.");
                    }
                }
            }
            String fallback = pool.optString("fallback", "").trim();
            if (!fallback.isEmpty() && !poolKeys.contains(fallback)) {
                blockingErrors.add("Jigsaw pool '" + poolKey + "' references missing fallback pool '"
                        + fallback + "'.");
            }
        }
    }

    private static void validateJigsawPieces(File piecesFolder,
                                             Set<String> poolKeys,
                                             Set<String> objectKeys,
                                             List<String> blockingErrors) {
        if (!piecesFolder.isDirectory()) {
            return;
        }
        List<File> pieceFiles = listJsonRecursive(piecesFolder);
        pieceFiles.sort(Comparator.comparing(File::getPath));
        for (File pieceFile : pieceFiles) {
            String pieceKey = deriveKey(piecesFolder, pieceFile);
            JSONObject piece = readGraphJson(pieceFile, "Jigsaw piece", pieceKey, blockingErrors);
            if (piece == null) {
                continue;
            }
            String objectKey = piece.optString("object", "").trim();
            if (objectKey.isEmpty()) {
                blockingErrors.add("Jigsaw piece '" + pieceKey + "' does not declare an object.");
            } else if (!objectKeys.contains(objectKey)) {
                blockingErrors.add("Jigsaw piece '" + pieceKey + "' references missing object '"
                        + objectKey + "'.");
            }
            JSONArray connectors = piece.optJSONArray("connectors");
            if (connectors == null) {
                continue;
            }
            for (int connectorIndex = 0; connectorIndex < connectors.length(); connectorIndex++) {
                JSONObject connector = connectors.optJSONObject(connectorIndex);
                if (connector == null) {
                    continue;
                }
                String poolKey = connector.optString("pool", "").trim();
                if (!poolKey.isEmpty() && !poolKeys.contains(poolKey)) {
                    blockingErrors.add("Jigsaw piece '" + pieceKey + "' connectors[" + connectorIndex
                            + "] references missing pool '" + poolKey + "'.");
                }
            }
        }
    }

    private static JSONObject readGraphJson(File file,
                                            String resourceType,
                                            String resourceKey,
                                            List<String> blockingErrors) {
        try {
            return new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            String reason = e.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = e.getClass().getSimpleName();
            }
            blockingErrors.add(resourceType + " '" + resourceKey + "' has invalid JSON: " + reason);
            return null;
        }
    }

    private static JSONObject readJson(File file) {
        try {
            return new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLegacyStructureIndex(String structureKey, JSONObject structure) {
        return "structure-index".equals(structureKey)
                && structure.has("counts")
                && structure.has("structureSets")
                && structure.has("iris");
    }

    private static String structureHostType(String folderName) {
        return switch (folderName) {
            case "dimensions" -> "Dimension";
            case "regions" -> "Region";
            case "biomes" -> "Biome";
            default -> "Resource";
        };
    }

    static List<String> validateSpawnerEntityReferences(File spawnersFolder, File entitiesFolder) {
        List<String> blockingErrors = new ArrayList<>();
        if (spawnersFolder == null || !spawnersFolder.isDirectory()) {
            return blockingErrors;
        }

        Path entityRoot = entitiesFolder.toPath().toAbsolutePath().normalize();
        Set<Path> validEntityFiles = new HashSet<>();
        Map<Path, String> invalidEntityFiles = new HashMap<>();
        List<File> spawnerFiles = listJsonRecursive(spawnersFolder);
        spawnerFiles.sort(Comparator.comparing(File::getPath));
        for (File spawnerFile : spawnerFiles) {
            String spawnerKey = deriveKey(spawnersFolder, spawnerFile);
            JSONObject spawner;
            try {
                spawner = new JSONObject(Files.readString(spawnerFile.toPath(), StandardCharsets.UTF_8));
            } catch (Throwable e) {
                blockingErrors.add("Spawner '" + spawnerKey + "' has invalid JSON: " + e.getMessage());
                continue;
            }

            validateSpawnerSpawnEntries(spawnerKey, spawner, "spawns", entityRoot,
                    validEntityFiles, invalidEntityFiles, blockingErrors);
            validateSpawnerSpawnEntries(spawnerKey, spawner, "initialSpawns", entityRoot,
                    validEntityFiles, invalidEntityFiles, blockingErrors);
        }
        return blockingErrors;
    }

    private static void validateSpawnerSpawnEntries(String spawnerKey,
                                                    JSONObject spawner,
                                                    String field,
                                                    Path entityRoot,
                                                    Set<Path> validEntityFiles,
                                                    Map<Path, String> invalidEntityFiles,
                                                    List<String> blockingErrors) {
        if (!spawner.has(field)) {
            return;
        }
        JSONArray entries = spawner.optJSONArray(field);
        if (entries == null) {
            blockingErrors.add("Spawner '" + spawnerKey + "' " + field + " must be an array.");
            return;
        }

        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field
                        + " has a non-object entry at index " + index + ".");
                continue;
            }
            if (!entry.has("entity") || entry.isNull("entity")) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field
                        + " has an entry without an entity reference at index " + index + ".");
                continue;
            }

            Object rawEntity = entry.get("entity");
            if (!(rawEntity instanceof String entityKey)) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field
                        + " entity reference at index " + index + " must be a string.");
                continue;
            }
            if (entityKey.isBlank()) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field
                        + " has a blank entity reference at index " + index + ".");
                continue;
            }

            Path entityFile;
            try {
                if (entityKey.indexOf('\\') >= 0) {
                    throw new IllegalArgumentException("backslash path separators are not portable");
                }
                entityFile = entityRoot.resolve(entityKey + ".json").normalize();
            } catch (RuntimeException e) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field + " entry at index " + index
                        + " has invalid entity reference '" + entityKey + "': " + e.getMessage());
                continue;
            }
            if (!entityFile.startsWith(entityRoot)) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field + " entry at index " + index
                        + " has unsafe entity reference '" + entityKey + "'.");
                continue;
            }
            if (!Files.isRegularFile(entityFile)) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field + " entry at index " + index
                        + " references missing entity '" + entityKey + "'.");
                continue;
            }

            String invalidJson = invalidEntityFiles.get(entityFile);
            if (invalidJson != null) {
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field + " entry at index " + index
                        + " references malformed entity '" + entityKey + "': " + invalidJson);
                continue;
            }
            if (validEntityFiles.contains(entityFile)) {
                continue;
            }

            try {
                new JSONObject(Files.readString(entityFile, StandardCharsets.UTF_8));
                validEntityFiles.add(entityFile);
            } catch (Throwable e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                invalidEntityFiles.put(entityFile, message);
                blockingErrors.add("Spawner '" + spawnerKey + "' " + field + " entry at index " + index
                        + " references malformed entity '" + entityKey + "': " + message);
            }
        }
    }

    static List<String> validateCustomBiomeSpawns(File biomesFolder, Function<String, SpawnCategoryResolution> categoryResolver) {
        List<String> blockingErrors = new ArrayList<>();
        if (biomesFolder == null || !biomesFolder.isDirectory()) {
            return blockingErrors;
        }

        List<File> biomeFiles = listJsonRecursive(biomesFolder);
        biomeFiles.sort(Comparator.comparing(File::getPath));
        for (File biomeFile : biomeFiles) {
            String biomeKey = deriveKey(biomesFolder, biomeFile);
            JSONObject biome;
            try {
                biome = new JSONObject(Files.readString(biomeFile.toPath(), StandardCharsets.UTF_8));
            } catch (Throwable e) {
                blockingErrors.add("Biome '" + biomeKey + "' has invalid JSON: " + e.getMessage());
                continue;
            }

            JSONArray derivatives = biome.optJSONArray("customDerivitives");
            if (derivatives == null) {
                if (biome.has("customDerivitives") && !biome.isNull("customDerivitives")) {
                    blockingErrors.add("Biome '" + biomeKey + "' customDerivitives must be an array.");
                }
                continue;
            }
            for (int derivativeIndex = 0; derivativeIndex < derivatives.length(); derivativeIndex++) {
                JSONObject derivative = derivatives.optJSONObject(derivativeIndex);
                if (derivative == null) {
                    blockingErrors.add("Biome '" + biomeKey + "' has a non-object custom derivative at index " + derivativeIndex + ".");
                    continue;
                }
                validateCustomBiomeDerivativeTags(biomeKey, derivative, derivativeIndex, blockingErrors);
                validateCustomBiomeDerivativeSpawns(
                        biomeKey, derivative, derivativeIndex, categoryResolver, blockingErrors);
            }
        }
        return blockingErrors;
    }

    private static void validateCustomBiomeDerivativeTags(String biomeKey,
                                                           JSONObject derivative,
                                                           int derivativeIndex,
                                                           List<String> blockingErrors) {
        if (!derivative.has("tags") || derivative.isNull("tags")) {
            return;
        }
        String derivativeId = derivative.optString("id", "#" + derivativeIndex);
        JSONArray tags = derivative.optJSONArray("tags");
        if (tags == null) {
            blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                    + "' tags must be an array.");
            return;
        }
        for (int tagIndex = 0; tagIndex < tags.length(); tagIndex++) {
            Object rawTag = tags.opt(tagIndex);
            if (!(rawTag instanceof String tag)) {
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' has a non-string tag at index " + tagIndex + ".");
                continue;
            }
            String normalized = tag.trim().toLowerCase(Locale.ROOT);
            if (normalized.indexOf(':') < 0) {
                normalized = "minecraft:" + normalized;
            }
            if (!isSafeResourceKey(normalized)) {
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' has invalid tag '" + tag + "'.");
            }
        }
    }

    private static boolean isSafeResourceKey(String key) {
        if (!RESOURCE_KEY_PATTERN.matcher(key).matches()) {
            return false;
        }
        int separator = key.indexOf(':');
        String[] segments = key.substring(separator + 1).split("/");
        for (String segment : segments) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static void validateCustomBiomeDerivativeSpawns(String biomeKey,
                                                             JSONObject derivative,
                                                             int derivativeIndex,
                                                             Function<String, SpawnCategoryResolution> categoryResolver,
                                                             List<String> blockingErrors) {
        JSONArray spawns = derivative.optJSONArray("spawns");
        if (spawns == null) {
            if (derivative.has("spawns") && !derivative.isNull("spawns")) {
                String derivativeId = derivative.optString("id", "#" + derivativeIndex);
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' spawns must be an array.");
            }
            return;
        }
        String derivativeId = derivative.optString("id", "#" + derivativeIndex);
        for (int spawnIndex = 0; spawnIndex < spawns.length(); spawnIndex++) {
            JSONObject spawn = spawns.optJSONObject(spawnIndex);
            if (spawn == null) {
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' has a non-object spawn at index " + spawnIndex + ".");
                continue;
            }

            String type = spawn.optString("type", "").trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' has a spawn without an entity type at index " + spawnIndex + ".");
                continue;
            }
            String typeKey = type.indexOf(':') >= 0 ? type : "minecraft:" + type;
            SpawnCategoryResolution resolution;
            try {
                resolution = categoryResolver == null ? null : categoryResolver.apply(typeKey);
            } catch (Throwable e) {
                IrisLogging.reportError("PackValidator failed to resolve spawn category for '" + typeKey + "'", e);
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' spawn category lookup failed for '" + typeKey + "': " + e.getMessage());
                continue;
            }
            if (resolution != null && !resolution.entityKnown()) {
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' spawn references unknown entity type '" + typeKey + "'.");
                continue;
            }
            String expectedGroup = resolution == null ? null : resolution.category();
            String group = spawn.optString("group", "").trim();
            if (group.isEmpty()) {
                if (expectedGroup != null && !expectedGroup.isBlank()
                        && !IrisBiomeCustomSpawnType.MISC.name().equalsIgnoreCase(expectedGroup)) {
                    blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                            + "' spawn '" + typeKey + "' must declare group '"
                            + expectedGroup.toUpperCase(Locale.ROOT) + "'.");
                }
                continue;
            }

            IrisBiomeCustomSpawnType configuredGroup;
            try {
                configuredGroup = IrisBiomeCustomSpawnType.valueOf(group);
            } catch (IllegalArgumentException e) {
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' spawn '" + typeKey + "' declares unknown group '" + group + "'.");
                continue;
            }

            if (expectedGroup != null && !expectedGroup.isBlank()
                    && !configuredGroup.name().equalsIgnoreCase(expectedGroup)) {
                blockingErrors.add("Biome '" + biomeKey + "' custom derivative '" + derivativeId
                        + "' spawn '" + typeKey + "' declares group '" + configuredGroup.name()
                        + "' but the live entity registry requires '" + expectedGroup.toUpperCase(Locale.ROOT) + "'.");
            }
        }
    }

    private static SpawnCategoryResolution resolveEntitySpawnCategory(String typeKey) {
        if (!IrisPlatforms.isBound()) {
            return null;
        }
        PlatformRegistries registries = IrisPlatforms.get().registries();
        if (registries == null) {
            return null;
        }
        PlatformEntityType entityType = registries.entity(typeKey);
        return entityType == null
                ? SpawnCategoryResolution.unknown()
                : SpawnCategoryResolution.known(entityType.spawnCategory());
    }

    private static void runContentKeyValidation(File packFolder, List<String> warnings) {
        try {
            if (!IrisPlatforms.isBound()) {
                return;
            }
            PlatformRegistries registries = IrisPlatforms.get().registries();
            if (registries == null) {
                return;
            }
            List<String> blockKeys = registries.blockKeys();
            List<String> itemKeys = registries.itemKeys();
            List<String> entityKeys = registries.entityKeys();
            if (blockKeys == null || blockKeys.isEmpty() || itemKeys == null || itemKeys.isEmpty() || entityKeys == null || entityKeys.isEmpty()) {
                return;
            }

            ReferencedContentKeys referenced = collectReferencedContentKeys(packFolder);
            List<ContentKeyValidator.ContentKeyError> errors = ContentKeyValidator.validate(
                    registries, referenced.blocks(), referenced.items(), referenced.entities());
            for (ContentKeyValidator.ContentKeyError error : errors) {
                warnings.add(error.message());
            }
        } catch (Throwable e) {
            IrisLogging.reportError("PackValidator content-key validation failed for pack '" + packFolder.getName() + "'", e);
        }
    }

    private static ReferencedContentKeys collectReferencedContentKeys(File packFolder) {
        Set<String> blocks = new HashSet<>();
        Set<String> items = new HashSet<>();
        Set<String> entities = new HashSet<>();
        Set<String> customBlocks = deriveRegistrantKeys(new File(packFolder, "blocks"));

        try (Stream<Path> stream = Files.walk(packFolder.toPath())) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(PackValidator::isScannableJsonPath)
                    .toList();
            for (Path path : files) {
                String relative = packFolder.toPath().relativize(path).toString().replace(File.separatorChar, '/');
                boolean inLoot = relative.startsWith("loot/");
                boolean inEntities = relative.startsWith("entities/");
                JSONObject json;
                try {
                    json = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                    continue;
                }
                collectFromNode(json, blocks, inLoot ? items : null, inEntities ? entities : null, customBlocks);
            }
        } catch (Throwable e) {
            IrisLogging.reportError("PackValidator failed to walk pack for content-key extraction", e);
        }

        return new ReferencedContentKeys(blocks, items, entities);
    }

    private static void collectFromNode(Object node, Set<String> blocks, Set<String> items, Set<String> entities, Set<String> customBlocks) {
        if (node instanceof JSONObject obj) {
            for (String key : obj.keySet()) {
                Object value = obj.get(key);
                if (value instanceof String str) {
                    if ("block".equals(key)) {
                        addBlockRef(str, blocks, customBlocks);
                    } else if (items != null && "type".equals(key)) {
                        addSimpleRef(str, items);
                    } else if (entities != null && "type".equals(key)) {
                        addSimpleRef(str, entities);
                    }
                } else {
                    collectFromNode(value, blocks, items, entities, customBlocks);
                }
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                collectFromNode(arr.get(i), blocks, items, entities, customBlocks);
            }
        }
    }

    private static void addBlockRef(String raw, Set<String> blocks, Set<String> customBlocks) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            value = value.substring(0, bracket).trim();
        }
        if (value.isEmpty() || customBlocks.contains(value)) {
            return;
        }
        blocks.add(value);
    }

    private static void addSimpleRef(String raw, Set<String> target) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.isEmpty()) {
            target.add(value);
        }
    }

    private static Set<String> deriveRegistrantKeys(File folder) {
        Set<String> keys = new HashSet<>();
        if (!folder.isDirectory()) {
            return keys;
        }
        for (File file : listJsonRecursive(folder)) {
            String key = deriveKey(folder, file);
            if (key != null && !key.isBlank()) {
                keys.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    private static Set<String> deriveRegistrantKeysExact(File folder) {
        Set<String> keys = new HashSet<>();
        if (!folder.isDirectory()) {
            return keys;
        }
        for (File file : listJsonRecursive(folder)) {
            String key = deriveKey(folder, file);
            if (key != null && !key.isBlank()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static Set<String> deriveObjectKeysExact(File folder) {
        Set<String> keys = new HashSet<>();
        if (!folder.isDirectory()) {
            return keys;
        }
        try (Stream<Path> stream = Files.walk(folder.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".iob"))
                    .forEach(path -> {
                        Path relative = folder.toPath().relativize(path);
                        String key = relative.toString().replace(File.separatorChar, '/');
                        keys.add(key.substring(0, key.length() - ".iob".length()));
                    });
        } catch (IOException ignored) {
        }
        return keys;
    }

    private record ReferencedContentKeys(Set<String> blocks, Set<String> items, Set<String> entities) {
    }

    private record DimensionVerticalBounds(long minimumY, long maximumY) {
    }

    private record PlacementVerticalBounds(long minimumY, long maximumY, boolean underground) {
    }

    record SpawnCategoryResolution(boolean entityKnown, String category) {
        static SpawnCategoryResolution unknown() {
            return new SpawnCategoryResolution(false, null);
        }

        static SpawnCategoryResolution known(String category) {
            return new SpawnCategoryResolution(true, category);
        }
    }

    private static void validateDimensions(File packFolder, File[] dimensionFiles, List<String> blockingErrors, List<String> warnings) {
        File regionsFolder = new File(packFolder, "regions");
        File biomesFolder = new File(packFolder, "biomes");

        for (File dimFile : dimensionFiles) {
            String dimensionKey = stripExtension(dimFile.getName());
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
            validateNativeTerrain("Dimension '" + dimensionKey
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
        validateOptionalIntegerRange(path, band, "min", -4064, 4064, blockingErrors);
        validateOptionalIntegerRange(path, band, "max", -4064, 4064, blockingErrors);
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

    private static boolean isScannableJsonPath(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".json")) {
            return false;
        }
        String str = path.toString().replace(File.separatorChar, '/');
        if (str.contains("/" + TRASH_ROOT + "/")) {
            return false;
        }
        if (str.contains("/" + DATAPACK_IMPORTS + "/")) {
            return false;
        }
        if (str.contains("/" + EXTERNAL_DATAPACKS + "/")) {
            return false;
        }
        if (str.contains("/" + INTERNAL_DATAPACKS + "/")) {
            return false;
        }
        if (str.contains("/" + DATAPACKS_FOLDER + "/")) {
            return false;
        }
        if (str.contains("/" + CACHE_FOLDER + "/")) {
            return false;
        }
        if (str.contains("/" + OBJECTS_FOLDER + "/")) {
            return false;
        }
        if (str.contains("/.iris/")) {
            return false;
        }
        return true;
    }

    private static List<File> listJsonRecursive(File root) {
        List<File> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> out.add(p.toFile()));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String deriveKey(File resourceFolder, File resourceFile) {
        Path relative = resourceFolder.toPath().relativize(resourceFile.toPath());
        String str = relative.toString().replace(File.separatorChar, '/');
        if (!str.endsWith(".json")) {
            return null;
        }
        return str.substring(0, str.length() - ".json".length());
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    public static Set<String> listReferencedKeysFromCorpus(String corpus) {
        Set<String> keys = new HashSet<>();
        if (corpus == null) {
            return keys;
        }
        int i = 0;
        while (i < corpus.length()) {
            int start = corpus.indexOf('"', i);
            if (start < 0) {
                break;
            }
            int end = corpus.indexOf('"', start + 1);
            if (end < 0) {
                break;
            }
            keys.add(corpus.substring(start + 1, end));
            i = end + 1;
        }
        return keys;
    }
}

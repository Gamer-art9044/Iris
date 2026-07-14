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
    private static final String DIMENSIONS_FOLDER = "dimensions";
    private static final String STRUCTURES_FOLDER = "structures";
    private static final String JIGSAW_POOLS_FOLDER = "jigsaw-pools";
    private static final String JIGSAW_PIECES_FOLDER = "jigsaw-pieces";
    private static final List<String> STRUCTURE_HOST_FOLDERS = List.of(DIMENSIONS_FOLDER, "regions", "biomes");
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
        blockingErrors.addAll(validateUnsupportedStructureTransforms(packFolder));
        blockingErrors.addAll(validateStructureGraph(packFolder));
        blockingErrors.addAll(validateSpawnerEntityReferences(
                new File(packFolder, "spawners"), new File(packFolder, "entities")));
        blockingErrors.addAll(validateCustomBiomeSpawns(
                new File(packFolder, "biomes"), PackValidator::resolveEntitySpawnCategory));

        runContentKeyValidation(packFolder, warnings);

        return new PackValidationResult(packName, blockingErrors, warnings, validatedAt);
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

    private static void validateStructurePlacements(File packFolder,
                                                    Set<String> structureKeys,
                                                    List<String> blockingErrors) {
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
                    if (references == null) {
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

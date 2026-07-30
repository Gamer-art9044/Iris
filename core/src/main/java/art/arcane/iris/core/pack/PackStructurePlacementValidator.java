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

import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PackStructurePlacementValidator {
    private PackStructurePlacementValidator() {
    }

    static void validateStructurePlacements(File packFolder,
                                            Set<String> structureKeys,
                                            List<String> blockingErrors) {
        Set<String> registeredStructures = registeredStructureKeys();
        Set<String> registeredJigsaws = registeredJigsawKeys();
        Set<String> registeredPools = registeredTemplatePoolKeys();
        for (String folderName : PackValidator.STRUCTURE_HOST_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }
            List<File> resourceFiles = PackValidationIo.listJsonRecursive(resourceFolder);
            resourceFiles.sort(Comparator.comparing(File::getPath));
            String resourceType = structureHostType(folderName);
            for (File resourceFile : resourceFiles) {
                JSONObject resource = PackValidationIo.readJson(resourceFile);
                if (resource == null) {
                    continue;
                }
                JSONArray placements = resource.optJSONArray("structures");
                if (placements == null) {
                    continue;
                }
                String resourceKey = PackValidationIo.deriveKey(resourceFolder, resourceFile);
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
            if (!PackValidator.RESOURCE_KEY_PATTERN.matcher(structureKey).matches()) {
                blockingErrors.add(sourcePath + ".structure must be a namespaced registry key.");
            } else if (!registeredStructures.isEmpty()
                    && !registeredStructures.contains(structureKey.toLowerCase(Locale.ROOT))) {
                blockingErrors.add(sourcePath + ".structure '" + structureKey
                        + "' is not a registered structure.");
            }
            Integer weight = PackLootValidator.lootInteger(source, "weight", 1, sourcePath, blockingErrors);
            PackLootValidator.requireMinimum(sourcePath + ".weight", weight, 1, blockingErrors);
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
        PackJsonFieldChecks.validateOptionalResourceKey(path, assembly, "startPool", false, blockingErrors);
        String startPool = assembly.optString("startPool", "").trim();
        if (!startPool.isEmpty() && !registeredPools.isEmpty()
                && !registeredPools.contains(startPool.toLowerCase(Locale.ROOT))) {
            blockingErrors.add(path + ".startPool '" + startPool
                    + "' is not a registered template pool.");
        }
        PackJsonFieldChecks.validateOptionalResourceKey(path, assembly, "startJigsawName", true, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, assembly, "maxDepth", 0, 20, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, assembly, "maxDistanceHorizontal", 1, 128, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, assembly, "maxDistanceVertical", 1, 4064, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(
                path, assembly, "dimensionPaddingBottom", 0, Integer.MAX_VALUE, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(
                path, assembly, "dimensionPaddingTop", 0, Integer.MAX_VALUE, blockingErrors);
        if (assembly.has("useExpansionHack")
                && !(assembly.opt("useExpansionHack") instanceof Boolean)) {
            blockingErrors.add(path + ".useExpansionHack must be a boolean.");
        }
        PackJsonFieldChecks.validateOptionalEnum(path, assembly, "projectStartToHeightmap",
                Set.of("SOURCE", "NONE", "WORLD_SURFACE_WG", "WORLD_SURFACE",
                        "OCEAN_FLOOR_WG", "OCEAN_FLOOR", "MOTION_BLOCKING",
                        "MOTION_BLOCKING_NO_LEAVES"), blockingErrors);
        PackJsonFieldChecks.validateOptionalEnum(path, assembly, "liquidSettings",
                Set.of("SOURCE", "IGNORE_WATERLOGGING", "APPLY_WATERLOGGING"), blockingErrors);
    }

    static void validateNativeTerrain(String path, JSONObject placement,
                                      List<String> blockingErrors) {
        JSONObject terrain = placement.optJSONObject("terrain");
        if (placement.has("terrain") && placement.opt("terrain") != JSONObject.NULL && terrain == null) {
            blockingErrors.add(path + ".terrain must be an object.");
            return;
        }
        if (terrain == null) {
            return;
        }
        PackJsonFieldChecks.validateOptionalEnum(path + ".terrain", terrain, "mode",
                Set.of("SOURCE", "PRESERVE", "BORE", "FORCE_CARVE", "VACUUM", "ENCASE"), blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path + ".terrain", terrain,
                "horizontalPadding", 0, 128, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path + ".terrain", terrain,
                "ceilingPadding", 0, 128, blockingErrors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path + ".terrain", terrain,
                "floorPadding", 0, 64, blockingErrors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path + ".terrain", terrain,
                "erosionStrength", 0D, 1D, blockingErrors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path + ".terrain", terrain,
                "erosionFrequency", 0.001D, 1D, blockingErrors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path + ".terrain", terrain,
                "lobeFrequency", 0D, 1D, blockingErrors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path + ".terrain", terrain,
                "lobeStrength", 0D, 1D, blockingErrors);
        if (terrain.has("encasePalette") && terrain.opt("encasePalette") != JSONObject.NULL
                && terrain.optJSONObject("encasePalette") == null) {
            blockingErrors.add(path + ".terrain.encasePalette must be an object.");
        }
    }

    static void validateStructureStartPools(File structuresFolder,
                                            Set<String> poolKeys,
                                            List<String> blockingErrors) {
        if (!structuresFolder.isDirectory()) {
            return;
        }
        List<File> structureFiles = PackValidationIo.listJsonRecursive(structuresFolder);
        structureFiles.sort(Comparator.comparing(File::getPath));
        for (File structureFile : structureFiles) {
            String structureKey = PackValidationIo.deriveKey(structuresFolder, structureFile);
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

    static void validateJigsawPools(File poolsFolder,
                                    Set<String> poolKeys,
                                    Set<String> pieceKeys,
                                    List<String> blockingErrors) {
        if (!poolsFolder.isDirectory()) {
            return;
        }
        List<File> poolFiles = PackValidationIo.listJsonRecursive(poolsFolder);
        poolFiles.sort(Comparator.comparing(File::getPath));
        for (File poolFile : poolFiles) {
            String poolKey = PackValidationIo.deriveKey(poolsFolder, poolFile);
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

    static void validateJigsawPieces(File piecesFolder,
                                     Set<String> poolKeys,
                                     Set<String> objectKeys,
                                     List<String> blockingErrors) {
        if (!piecesFolder.isDirectory()) {
            return;
        }
        List<File> pieceFiles = PackValidationIo.listJsonRecursive(piecesFolder);
        pieceFiles.sort(Comparator.comparing(File::getPath));
        for (File pieceFile : pieceFiles) {
            String pieceKey = PackValidationIo.deriveKey(piecesFolder, pieceFile);
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

    static JSONObject readGraphJson(File file,
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

    private static boolean isLegacyStructureIndex(String structureKey, JSONObject structure) {
        return "structure-index".equals(structureKey)
                && structure.has("counts")
                && structure.has("structureSets")
                && structure.has("iris");
    }

    static String structureHostType(String folderName) {
        return switch (folderName) {
            case "dimensions" -> "Dimension";
            case "regions" -> "Region";
            case "biomes" -> "Biome";
            default -> "Resource";
        };
    }
}

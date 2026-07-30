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

package art.arcane.iris.core.pack;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ContentKeyValidator {
    private static final int MAX_SUGGESTION_SCANS = 4096;
    private static final String DEFAULT_NAMESPACE = "minecraft";

    private ContentKeyValidator() {
    }

    public enum ContentRegistry {
        BLOCK,
        ITEM,
        ENTITY;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record ContentKeyError(String key, ContentRegistry registry, boolean namespaceLoaded, String suggestion) {
        public String message() {
            StringBuilder sb = new StringBuilder(64);
            sb.append("Unknown ").append(registry.label()).append(" key '").append(key).append('\'');
            sb.append(" (missing from the ").append(registry.label()).append(" registry");
            if (!namespaceLoaded) {
                sb.append("; namespace '").append(namespaceOf(key)).append("' is not loaded on this server");
            }
            sb.append(')');
            if (suggestion != null) {
                sb.append(" - did you mean '").append(suggestion).append("'?");
            }
            return sb.toString();
        }
    }

    public static List<ContentKeyError> validate(PlatformRegistries registries,
                                                 Collection<String> referencedBlocks,
                                                 Collection<String> referencedItems,
                                                 Collection<String> referencedEntities) {
        if (registries == null) {
            return List.of();
        }

        List<String> blockKeys = normalizeKeyList(registries.blockKeys());
        List<String> itemKeys = normalizeKeyList(registries.itemKeys());
        List<String> entityKeys = normalizeKeyList(registries.entityKeys());

        Set<String> knownBlocks = new HashSet<>(blockKeys);
        Set<String> knownItems = new HashSet<>(itemKeys);
        Set<String> knownEntities = new HashSet<>(entityKeys);

        Set<String> loadedNamespaces = new HashSet<>();
        addNamespaces(blockKeys, loadedNamespaces);
        addNamespaces(itemKeys, loadedNamespaces);
        addNamespaces(entityKeys, loadedNamespaces);

        Map<String, ContentKeyError> errors = new LinkedHashMap<>();
        validateCategory(referencedBlocks, ContentRegistry.BLOCK, knownBlocks, blockKeys, loadedNamespaces, errors);
        validateCategory(referencedItems, ContentRegistry.ITEM, knownItems, itemKeys, loadedNamespaces, errors);
        validateCategory(referencedEntities, ContentRegistry.ENTITY, knownEntities, entityKeys, loadedNamespaces, errors);
        return List.copyOf(errors.values());
    }

    static String namespaceOf(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? DEFAULT_NAMESPACE : key.substring(0, colon);
    }

    private static void validateCategory(Collection<String> referenced,
                                         ContentRegistry registry,
                                         Set<String> known,
                                         List<String> candidatePool,
                                         Set<String> loadedNamespaces,
                                         Map<String, ContentKeyError> errors) {
        if (referenced == null || referenced.isEmpty()) {
            return;
        }
        for (String raw : referenced) {
            String normalized = normalizeKey(raw);
            if (normalized == null || known.contains(normalized)) {
                continue;
            }
            String dedupKey = registry.name() + '|' + normalized;
            if (errors.containsKey(dedupKey)) {
                continue;
            }
            boolean namespaceLoaded = loadedNamespaces.contains(namespaceOf(normalized));
            String suggestion = nearestKey(normalized, candidatePool);
            errors.put(dedupKey, new ContentKeyError(normalized, registry, namespaceLoaded, suggestion));
        }
    }

    private static List<String> normalizeKeyList(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            String value = key.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                out.add(value.indexOf(':') < 0 ? DEFAULT_NAMESPACE + ':' + value : value);
            }
        }
        return out;
    }

    private static void addNamespaces(List<String> keys, Set<String> namespaces) {
        for (String key : keys) {
            namespaces.add(namespaceOf(key));
        }
    }

    private static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            value = value.substring(0, bracket).trim();
        }
        if (value.isEmpty()) {
            return null;
        }
        return value.indexOf(':') < 0 ? DEFAULT_NAMESPACE + ':' + value : value;
    }

    private static String nearestKey(String key, List<String> candidatePool) {
        String path = pathOf(key);
        int maxDistance = Math.max(2, path.length() / 3);
        int bestDistance = Integer.MAX_VALUE;
        String best = null;
        int scans = 0;

        for (String candidate : candidatePool) {
            String candidatePath = pathOf(candidate);
            if (Math.abs(candidatePath.length() - path.length()) > maxDistance) {
                continue;
            }
            if (scans++ >= MAX_SUGGESTION_SCANS) {
                break;
            }
            int distance = boundedLevenshtein(path, candidatePath, maxDistance);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                if (distance <= 1) {
                    break;
                }
            }
        }

        return bestDistance <= maxDistance ? best : null;
    }

    private static String pathOf(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(colon + 1);
    }

    private static int boundedLevenshtein(String a, String b, int max) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > max) {
            return max + 1;
        }
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
                if (curr[j] < rowMin) {
                    rowMin = curr[j];
                }
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lb];
    }

    static void runContentKeyValidation(File packFolder, List<String> warnings) {
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
                    .filter(PackValidationIo::isScannableJsonPath)
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
        for (File file : PackValidationIo.listJsonRecursive(folder)) {
            String key = PackValidationIo.deriveKey(folder, file);
            if (key != null && !key.isBlank()) {
                keys.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    static Set<String> deriveRegistrantKeysExact(File folder) {
        Set<String> keys = new HashSet<>();
        if (!folder.isDirectory()) {
            return keys;
        }
        for (File file : PackValidationIo.listJsonRecursive(folder)) {
            String key = PackValidationIo.deriveKey(folder, file);
            if (key != null && !key.isBlank()) {
                keys.add(key);
            }
        }
        return keys;
    }

    static Set<String> deriveObjectKeysExact(File folder) {
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
}

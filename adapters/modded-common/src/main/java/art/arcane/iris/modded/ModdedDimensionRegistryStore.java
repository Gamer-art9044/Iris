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

package art.arcane.iris.modded;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModdedDimensionRegistryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final String FILE_NAME = "iris-dimensions.json";

    private ModdedDimensionRegistryStore() {
    }

    public static List<PersistentDimension> load(MinecraftServer server) {
        return load(storeFile(server));
    }

    static List<PersistentDimension> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray entries = root.optJSONArray("dimensions");
            if (entries == null) {
                throw new IllegalArgumentException("registry root has no dimensions array");
            }
            Map<String, PersistentDimension> deduplicated = new LinkedHashMap<>();
            for (int index = 0; index < entries.length(); index++) {
                try {
                    JSONObject entry = entries.getJSONObject(index);
                    String id = required(entry, "id", index, file);
                    String pack = required(entry, "pack", index, file);
                    String dimension = required(entry, "dimension", index, file);
                    if (!entry.has("seed")) {
                        throw new IllegalArgumentException("missing seed");
                    }
                    PersistentDimension previous = deduplicated.putIfAbsent(
                            id, new PersistentDimension(id, pack, dimension, entry.getLong("seed")));
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate id '" + id + "'");
                    }
                } catch (RuntimeException invalidEntry) {
                    LOGGER.error("Iris persistent dimension registry entry {} in {} is invalid; skipping only that entry",
                            index, file, invalidEntry);
                }
            }
            return new ArrayList<>(deduplicated.values());
        } catch (RuntimeException | IOException e) {
            throw new IllegalStateException("Iris persistent dimension registry at " + file
                    + " could not be read; refusing to discard persistent worlds", e);
        }
    }

    public static PersistentDimension get(MinecraftServer server, String id) {
        return index(load(server)).get(id);
    }

    public static synchronized void put(MinecraftServer server, PersistentDimension dimension) {
        Map<String, PersistentDimension> current = index(load(server));
        current.put(dimension.id(), dimension);
        write(server, new ArrayList<>(current.values()));
    }

    public static synchronized void remove(MinecraftServer server, String id) {
        Map<String, PersistentDimension> current = index(load(server));
        if (current.remove(id) != null) {
            write(server, new ArrayList<>(current.values()));
        }
    }

    private static Map<String, PersistentDimension> index(List<PersistentDimension> dimensions) {
        Map<String, PersistentDimension> map = new LinkedHashMap<>();
        for (PersistentDimension dimension : dimensions) {
            map.put(dimension.id(), dimension);
        }
        return map;
    }

    private static String required(JSONObject entry, String key, int index, Path file) {
        String value = entry.optString(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("entry " + index + " in " + file + " has no " + key);
        }
        return value;
    }

    private static void write(MinecraftServer server, List<PersistentDimension> dimensions) {
        write(storeFile(server), dimensions);
    }

    static void write(Path file, List<PersistentDimension> dimensions) {
        JSONArray entries = new JSONArray();
        for (PersistentDimension dimension : dimensions) {
            JSONObject entry = new JSONObject();
            entry.put("id", dimension.id());
            entry.put("pack", dimension.pack());
            entry.put("dimension", dimension.dimension());
            entry.put("seed", dimension.seed());
            entries.put(entry);
        }
        JSONObject root = new JSONObject();
        root.put("dimensions", entries);
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temp, root.toString(2), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveReplacing(temp, file);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Iris failed to write persistent dimension registry at " + file, e);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path storeFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("iris").resolve(FILE_NAME);
    }

    public record PersistentDimension(String id, String pack, String dimension, long seed) {
    }
}

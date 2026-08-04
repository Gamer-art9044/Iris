package art.arcane.iris.core;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.generator.WorldInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

public final class IrisWorldStorage {
    private static final String IRIS_NAMESPACE = "iris";
    private static final String DEFAULT_LEVEL_NAME = "world";

    /**
     * Server#getLevelDirectory is Paper-API-only. Once a call throws NoSuchMethodError (plain
     * Spigot/CraftBukkit) this flips and every later call goes straight to the fallback.
     */
    private static volatile boolean levelDirectoryUnavailable;
    private static volatile String cachedFallbackLevelName;

    private IrisWorldStorage() {
    }

    public static File levelRoot() {
        if (!levelDirectoryUnavailable) {
            try {
                return Bukkit.getServer().getLevelDirectory().toAbsolutePath().normalize().toFile();
            } catch (NoSuchMethodError e) {
                levelDirectoryUnavailable = true;
            }
        }
        return new File(Bukkit.getWorldContainer(), fallbackLevelName()).getAbsoluteFile();
    }

    private static String fallbackLevelName() {
        String cached = cachedFallbackLevelName;
        if (cached == null) {
            cached = levelNameFromProperties(new File("server.properties"));
            cachedFallbackLevelName = cached;
        }
        return cached;
    }

    static String levelNameFromProperties(File serverProperties) {
        Properties properties = new Properties();
        if (Objects.requireNonNull(serverProperties, "serverProperties").isFile()) {
            try (InputStream in = new FileInputStream(serverProperties)) {
                properties.load(in);
            } catch (IOException ignored) {
                // Unreadable server.properties: fall through to the default level name.
            }
        }
        return levelNameFromProperties(properties);
    }

    static String levelNameFromProperties(Properties properties) {
        String levelName = Objects.requireNonNull(properties, "properties").getProperty("level-name", DEFAULT_LEVEL_NAME).trim();
        return levelName.isEmpty() ? DEFAULT_LEVEL_NAME : levelName;
    }

    public static File levelRoot(File dimensionRoot) {
        Path current = Objects.requireNonNull(dimensionRoot, "dimensionRoot").toPath().toAbsolutePath().normalize();
        while (current != null) {
            Path fileName = current.getFileName();
            if (fileName != null && "dimensions".equals(fileName.toString())) {
                Path parent = current.getParent();
                if (parent != null) {
                    return parent.toFile();
                }
                break;
            }
            current = current.getParent();
        }
        return dimensionRoot.getAbsoluteFile();
    }

    public static NamespacedKey keyFromName(String worldName) {
        return keyFromName(worldName, levelRoot().getName());
    }

    static NamespacedKey keyFromName(String worldName, String levelName) {
        String name = Objects.requireNonNull(worldName, "worldName").trim();
        String mainLevelName = Objects.requireNonNull(levelName, "levelName").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("World name cannot be empty.");
        }
        if (name.equals(mainLevelName)) {
            return NamespacedKey.minecraft("overworld");
        }
        if (name.equals(mainLevelName + "_nether")) {
            return NamespacedKey.minecraft("the_nether");
        }
        if (name.equals(mainLevelName + "_the_end")) {
            return NamespacedKey.minecraft("the_end");
        }

        String key = name.toLowerCase(Locale.ENGLISH).replace(' ', '_');
        return new NamespacedKey(IRIS_NAMESPACE, key);
    }

    public static String logicalName(WorldInfo world) {
        return logicalName(WorldIdentity.key(world));
    }

    public static String logicalName(NamespacedKey key) {
        return logicalName(key, levelRoot().getName());
    }

    static String logicalName(NamespacedKey key, String levelName) {
        NamespacedKey worldKey = Objects.requireNonNull(key, "key");
        String mainLevelName = Objects.requireNonNull(levelName, "levelName").trim();
        if (NamespacedKey.minecraft("overworld").equals(worldKey)) {
            return mainLevelName;
        }
        if (NamespacedKey.minecraft("the_nether").equals(worldKey)) {
            return mainLevelName + "_nether";
        }
        if (NamespacedKey.minecraft("the_end").equals(worldKey)) {
            return mainLevelName + "_the_end";
        }
        return worldKey.getKey();
    }

    public static File dimensionRoot(String worldName) {
        return dimensionRoot(keyFromName(worldName));
    }

    public static File dimensionRoot(WorldInfo world) {
        NamespacedKey key = WorldIdentity.key(world);
        Optional<World> loadedWorld = WorldIdentity.resolve(key);
        if (loadedWorld.isPresent()) {
            return loadedWorld.get().getWorldFolder().getAbsoluteFile();
        }
        return dimensionRoot(key);
    }

    public static File dimensionRoot(NamespacedKey key) {
        return dimensionRoot(levelRoot(), key);
    }

    public static File dimensionRoot(File levelRoot, NamespacedKey key) {
        Path dimensionsRoot = Objects.requireNonNull(levelRoot, "levelRoot")
                .toPath()
                .toAbsolutePath()
                .normalize()
                .resolve("dimensions");
        NamespacedKey worldKey = Objects.requireNonNull(key, "key");
        Path namespaceRoot = dimensionsRoot.resolve(worldKey.getNamespace()).normalize();
        Path dimensionRoot = namespaceRoot.resolve(worldKey.getKey()).normalize();
        if (!dimensionRoot.startsWith(namespaceRoot)) {
            throw new IllegalArgumentException("World key escapes its namespace storage root: " + worldKey);
        }
        return dimensionRoot.toFile();
    }

    public static Optional<NamespacedKey> keyFromDimensionRoot(File levelRoot, File dimensionRoot) {
        Path dimensionsPath = Objects.requireNonNull(levelRoot, "levelRoot")
                .toPath()
                .toAbsolutePath()
                .normalize()
                .resolve("dimensions");
        Path worldPath = Objects.requireNonNull(dimensionRoot, "dimensionRoot")
                .toPath()
                .toAbsolutePath()
                .normalize();
        if (!worldPath.startsWith(dimensionsPath)) {
            return Optional.empty();
        }

        Path relative = dimensionsPath.relativize(worldPath);
        if (relative.getNameCount() < 2) {
            return Optional.empty();
        }

        String namespace = relative.getName(0).toString();
        StringBuilder key = new StringBuilder();
        for (int i = 1; i < relative.getNameCount(); i++) {
            if (!key.isEmpty()) {
                key.append('/');
            }
            key.append(relative.getName(i));
        }

        return Optional.ofNullable(NamespacedKey.fromString(namespace + ":" + key));
    }

    public static File packRoot(NamespacedKey key) {
        return new File(dimensionRoot(key), "iris/pack");
    }
}

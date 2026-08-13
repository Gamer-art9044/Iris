package art.arcane.iris.core;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.util.common.misc.ServerProperties;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.io.IO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.stream.Stream;

public class IrisWorlds {
    private static final AtomicCache<IrisWorlds> cache = new AtomicCache<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = TypeToken.getParameterized(KMap.class, String.class, String.class).getType();
    private final KMap<String, String> worlds;
    private volatile boolean dirty = false;

    private IrisWorlds(KMap<String, String> worlds) {
        this.worlds = new KMap<>();
        worlds.forEach((identity, type) -> this.worlds.put(WorldIdentity.parse(identity).toString(), type));
        readBukkitWorlds().forEach((name, type) -> put0(IrisWorldStorage.keyFromName(name).toString(), type));
        save();
    }

    public static IrisWorlds get() {
        return cache.aquire(() -> {
            File file = IrisPlatforms.get().dataFile("worlds.json");
            if (!file.exists()) {
                return new IrisWorlds(new KMap<>());
            }

            try {
                String json = IO.readAll(file);
                KMap<String, String> worlds = GSON.fromJson(json, TYPE);
                return new IrisWorlds(Objects.requireNonNullElseGet(worlds, KMap::new));
            } catch (Throwable e) {
                IrisLogging.error("Failed to load worlds.json!");
                e.printStackTrace();
                IrisLogging.reportError(e);
            }

            return new IrisWorlds(new KMap<>());
        });
    }

    public synchronized void put(String identity, String type) {
        String canonicalIdentity = WorldIdentity.parse(identity).toString();
        String requiredType = Objects.requireNonNull(type, "type");
        String previous = worlds.put(canonicalIdentity, requiredType);
        if (requiredType.equals(previous)) {
            return;
        }
        dirty = true;
        try {
            saveOrThrow();
        } catch (IOException e) {
            if (previous == null) {
                worlds.remove(canonicalIdentity);
            } else {
                worlds.put(canonicalIdentity, previous);
            }
            dirty = true;
            throw new UncheckedIOException("Failed to persist Iris world registry entry for " + canonicalIdentity, e);
        }
    }

    public synchronized boolean remove(String identity) {
        String canonicalIdentity = WorldIdentity.parse(identity).toString();
        String previous = worlds.remove(canonicalIdentity);
        if (previous == null) {
            return false;
        }
        dirty = true;
        try {
            saveOrThrow();
            return true;
        } catch (IOException e) {
            worlds.put(canonicalIdentity, previous);
            dirty = true;
            throw new UncheckedIOException("Failed to remove Iris world registry entry for " + canonicalIdentity, e);
        }
    }

    private void put0(String identity, String type) {
        String canonicalIdentity = WorldIdentity.parse(identity).toString();
        String old = worlds.put(canonicalIdentity, type);
        if (!type.equals(old))
            dirty = true;
    }

    public synchronized KMap<String, String> getWorlds() {
        clean();
        KMap<String, String> result = new KMap<>();
        readBukkitWorlds().forEach((name, type) -> result.put(IrisWorldStorage.keyFromName(name).toString(), type));
        return result.put(worlds);
    }

    public Stream<IrisData> getPacks() {
        return getDimensions()
                .map(IrisDimension::getLoader)
                .filter(Objects::nonNull);
    }

    public Stream<IrisDimension> getDimensions() {
        return getWorlds()
                .entrySet()
                .stream()
                .map(entry -> loadDimension(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull);
    }

    public synchronized void clean() {
        boolean removed = worlds.entrySet().removeIf(entry -> {
            try {
                File packRoot = IrisWorldStorage.packRoot(WorldIdentity.parse(entry.getKey()));
                return !new File(packRoot, "dimensions/" + entry.getValue() + ".json").exists();
            } catch (IllegalArgumentException e) {
                return true;
            }
        });
        dirty = dirty || removed;
    }

    public synchronized void save() {
        try {
            saveOrThrow();
        } catch (IOException e) {
            IrisLogging.error("Failed to save worlds.json!");
            IrisLogging.reportError(e);
        }
    }

    private void saveOrThrow() throws IOException {
        clean();
        if (!dirty) {
            return;
        }

        Path target = IrisPlatforms.get().dataFile("worlds.json").toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("worlds.json target has no parent: " + target);
        }
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".iris-worlds-", ".json");
        try {
            Files.writeString(staged, GSON.toJson(worlds, TYPE), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static Long readBukkitWorldSeed(String world) {
        YamlConfiguration bukkit = YamlConfiguration.loadConfiguration(ServerProperties.BUKKIT_YML);
        ConfigurationSection worlds = bukkit.getConfigurationSection("worlds");
        if (worlds == null || !worlds.contains(world + ".seed")) {
            return null;
        }

        return worlds.getLong(world + ".seed");
    }

    public static KMap<String, String> readBukkitWorlds() {
        YamlConfiguration bukkit = YamlConfiguration.loadConfiguration(ServerProperties.BUKKIT_YML);
        ConfigurationSection worlds = bukkit.getConfigurationSection("worlds");
        if (worlds == null) return new KMap<>();

        KMap<String, String> result = new KMap<>();
        for (String world : worlds.getKeys(false)) {
            String gen = worlds.getString(world + ".generator");
            if (gen == null) continue;

            String loadKey;
            if (gen.equalsIgnoreCase("iris")) {
                loadKey = IrisSettings.get().getGenerator().getDefaultWorldType();
            } else if (gen.startsWith("Iris:")) {
                loadKey = gen.substring(5);
            } else continue;

            result.put(world, loadKey);
        }

        return result;
    }

    private static IrisDimension loadDimension(String worldIdentity, String id) {
        File pack = IrisWorldStorage.packRoot(WorldIdentity.parse(worldIdentity));
        IrisDimension dimension = pack.isDirectory() ? IrisData.get(pack).getDimensionLoader().load(id) : null;
        if (dimension == null) {
            dimension = IrisData.loadAnyDimension(id, null);
        }
        if (dimension == null) {
            File packsRoot = IrisPlatforms.get().packsFolderNoCreate();
            if (PackDownloader.isPackPresent(packsRoot, id)) {
                IrisLogging.error("Pack '" + id + "' exists at " + new File(packsRoot, id).getPath()
                        + " but its dimension failed to load; not redownloading. Fix or delete the pack folder.");
                return null;
            }
            IrisLogging.warn("Unable to find dimension type " + id + ". Install it with "
                    + PackDownloader.downloadCommandFor(id) + " and restart the server.");
        }
        return dimension;
    }
}

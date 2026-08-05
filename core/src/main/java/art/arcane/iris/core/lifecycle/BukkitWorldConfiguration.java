package art.arcane.iris.core.lifecycle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.ArrayList;
import java.util.function.Predicate;

public final class BukkitWorldConfiguration {
    private static final Object MUTATION_LOCK = new Object();

    private BukkitWorldConfiguration() {
    }

    public static Registration register(File configurationFile, String worldName, String dimension, Long seed) throws IOException {
        Objects.requireNonNull(configurationFile, "configurationFile");
        String requiredWorldName = requireWorldName(worldName);
        String requiredDimension = requireName(dimension, "Dimension");
        synchronized (MUTATION_LOCK) {
            YamlConfiguration configuration = load(configurationFile);
            ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
            if (worlds == null) {
                worlds = configuration.createSection("worlds");
            }

            ConfigurationSection existing = worlds.getConfigurationSection(requiredWorldName);
            String generator = "Iris:" + requiredDimension;
            if (existing != null) {
                String existingGenerator = existing.getString("generator");
                Long existingSeed = existing.contains("seed") ? existing.getLong("seed") : null;
                if (!generator.equals(existingGenerator) || !Objects.equals(seed, existingSeed)) {
                    throw new IOException("bukkit.yml already contains a different definition for world \""
                            + requiredWorldName + "\".");
                }
                return Registration.UNCHANGED;
            }

            ConfigurationSection created = worlds.createSection(requiredWorldName);
            created.set("generator", generator);
            if (seed != null) {
                created.set("seed", seed);
            }
            saveAtomic(configurationFile.toPath(), configuration);
            return Registration.CREATED;
        }
    }

    public static boolean remove(File configurationFile, String worldName) throws IOException {
        Objects.requireNonNull(configurationFile, "configurationFile");
        String requiredWorldName = requireWorldName(worldName);
        synchronized (MUTATION_LOCK) {
            YamlConfiguration configuration = load(configurationFile);
            ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
            if (worlds == null || worlds.get(requiredWorldName) == null) {
                return false;
            }

            worlds.set(requiredWorldName, null);
            if (worlds.getKeys(false).isEmpty()) {
                configuration.set("worlds", null);
            }
            saveAtomic(configurationFile.toPath(), configuration);
            return true;
        }
    }

    public static boolean removeIfMatching(
            File configurationFile,
            String worldName,
            String dimension,
            Long seed
    ) throws IOException {
        Objects.requireNonNull(configurationFile, "configurationFile");
        String requiredWorldName = requireWorldName(worldName);
        String requiredDimension = requireName(dimension, "Dimension");
        synchronized (MUTATION_LOCK) {
            YamlConfiguration configuration = load(configurationFile);
            ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
            if (worlds == null) {
                return false;
            }

            ConfigurationSection existing = worlds.getConfigurationSection(requiredWorldName);
            if (existing == null) {
                return false;
            }
            String expectedGenerator = "Iris:" + requiredDimension;
            String actualGenerator = existing.getString("generator");
            Long actualSeed = existing.contains("seed") ? existing.getLong("seed") : null;
            if (!expectedGenerator.equals(actualGenerator) || !Objects.equals(seed, actualSeed)) {
                return false;
            }

            worlds.set(requiredWorldName, null);
            if (worlds.getKeys(false).isEmpty()) {
                configuration.set("worlds", null);
            }
            saveAtomic(configurationFile.toPath(), configuration);
            return true;
        }
    }

    public static int removeMatching(File configurationFile, Predicate<String> matcher) throws IOException {
        Objects.requireNonNull(configurationFile, "configurationFile");
        Predicate<String> requiredMatcher = Objects.requireNonNull(matcher, "matcher");
        synchronized (MUTATION_LOCK) {
            YamlConfiguration configuration = load(configurationFile);
            ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
            if (worlds == null) {
                return 0;
            }

            int removed = 0;
            for (String worldName : new ArrayList<>(worlds.getKeys(false))) {
                if (!requiredMatcher.test(worldName)) {
                    continue;
                }
                worlds.set(worldName, null);
                removed++;
            }
            if (removed == 0) {
                return 0;
            }
            if (worlds.getKeys(false).isEmpty()) {
                configuration.set("worlds", null);
            }
            saveAtomic(configurationFile.toPath(), configuration);
            return removed;
        }
    }

    static void saveAtomic(Path target, YamlConfiguration configuration) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("bukkit.yml target has no parent: " + absoluteTarget);
        }
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".bukkit-worlds-", ".yml");
        try {
            configuration.save(staged.toFile());
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(staged, absoluteTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static YamlConfiguration load(File configurationFile) throws IOException {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(configurationFile);
            return configuration;
        } catch (InvalidConfigurationException exception) {
            throw new IOException("bukkit.yml is invalid and was not changed.", exception);
        }
    }

    private static String requireWorldName(String value) {
        String worldName = requireName(value, "World name");
        if (!worldName.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("World name must contain only lowercase letters, numbers, underscores, or hyphens.");
        }
        return worldName;
    }

    private static String requireName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be empty.");
        }
        return value.trim();
    }

    public enum Registration {
        CREATED,
        UNCHANGED
    }
}

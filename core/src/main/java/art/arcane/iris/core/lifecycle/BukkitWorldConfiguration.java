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
import java.util.ArrayList;
import java.util.Objects;
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

    public static WorldGeneratorSnapshot snapshot(File configurationFile, String worldName) throws IOException {
        Objects.requireNonNull(configurationFile, "configurationFile");
        String requiredWorldName = requireWorldName(worldName);
        synchronized (MUTATION_LOCK) {
            return snapshot(load(configurationFile), requiredWorldName);
        }
    }

    public static GeneratorReplacement replaceIfMatching(
            File configurationFile,
            String worldName,
            WorldGeneratorSnapshot expected,
            String dimension,
            Long seed
    ) throws IOException {
        Objects.requireNonNull(configurationFile, "configurationFile");
        String requiredWorldName = requireWorldName(worldName);
        WorldGeneratorSnapshot requiredExpected = Objects.requireNonNull(expected, "expected");
        String requiredDimension = requireName(dimension, "Dimension");
        WorldGeneratorSnapshot replacement = WorldGeneratorSnapshot.configured(requiredDimension, seed);
        synchronized (MUTATION_LOCK) {
            YamlConfiguration configuration = load(configurationFile);
            WorldGeneratorSnapshot current = snapshot(configuration, requiredWorldName);
            if (!current.matchesGeneratorAndSeed(requiredExpected)) {
                return new GeneratorReplacement(false, current, replacement);
            }
            apply(configuration, requiredWorldName, replacement);
            saveAtomic(configurationFile.toPath(), configuration);
            return new GeneratorReplacement(true, current, replacement);
        }
    }

    public static boolean restoreIfMatching(
            File configurationFile,
            String worldName,
            WorldGeneratorSnapshot expectedCurrent,
            WorldGeneratorSnapshot restoration
    ) throws IOException {
        Objects.requireNonNull(configurationFile, "configurationFile");
        String requiredWorldName = requireWorldName(worldName);
        WorldGeneratorSnapshot requiredExpected = Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        WorldGeneratorSnapshot requiredRestoration = Objects.requireNonNull(restoration, "restoration");
        synchronized (MUTATION_LOCK) {
            YamlConfiguration configuration = load(configurationFile);
            WorldGeneratorSnapshot current = snapshot(configuration, requiredWorldName);
            if (!current.matchesGeneratorAndSeed(requiredExpected)) {
                return false;
            }
            apply(configuration, requiredWorldName, requiredRestoration);
            saveAtomic(configurationFile.toPath(), configuration);
            return true;
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

    private static WorldGeneratorSnapshot snapshot(
            YamlConfiguration configuration,
            String worldName
    ) throws IOException {
        Object rawWorlds = configuration.get("worlds");
        ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
        if (rawWorlds != null && worlds == null) {
            throw new IOException("bukkit.yml worlds entry is not a section and was not changed.");
        }
        if (worlds == null) {
            return WorldGeneratorSnapshot.absent();
        }

        Object rawWorld = worlds.get(worldName);
        ConfigurationSection world = worlds.getConfigurationSection(worldName);
        if (rawWorld != null && world == null) {
            throw new IOException("bukkit.yml world entry \"" + worldName + "\" is not a section and was not changed.");
        }
        if (world == null) {
            return WorldGeneratorSnapshot.absentWorld(true);
        }

        boolean generatorPresent = world.getKeys(false).contains("generator");
        String generator = null;
        if (generatorPresent) {
            Object rawGenerator = world.get("generator");
            if (!(rawGenerator instanceof String generatorValue)) {
                throw new IOException("bukkit.yml generator for world \"" + worldName
                        + "\" is not a string and was not changed.");
            }
            generator = generatorValue;
        }

        boolean seedPresent = world.getKeys(false).contains("seed");
        Long seed = null;
        if (seedPresent) {
            Object rawSeed = world.get("seed");
            if (!(rawSeed instanceof Byte
                    || rawSeed instanceof Short
                    || rawSeed instanceof Integer
                    || rawSeed instanceof Long)) {
                throw new IOException("bukkit.yml seed for world \"" + worldName
                        + "\" is not an integer and was not changed.");
            }
            seed = ((Number) rawSeed).longValue();
        }

        return new WorldGeneratorSnapshot(
                true,
                true,
                generatorPresent,
                generator,
                seedPresent,
                seed
        );
    }

    private static void apply(
            YamlConfiguration configuration,
            String worldName,
            WorldGeneratorSnapshot snapshot
    ) throws IOException {
        ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
        if (worlds == null) {
            Object rawWorlds = configuration.get("worlds");
            if (rawWorlds != null) {
                throw new IOException("bukkit.yml worlds entry is not a section and was not changed.");
            }
            worlds = configuration.createSection("worlds");
        }

        ConfigurationSection world = worlds.getConfigurationSection(worldName);
        if (world == null) {
            Object rawWorld = worlds.get(worldName);
            if (rawWorld != null) {
                throw new IOException("bukkit.yml world entry \"" + worldName
                        + "\" is not a section and was not changed.");
            }
            world = worlds.createSection(worldName);
        }

        world.set("generator", snapshot.generatorPresent() ? snapshot.generator() : null);
        world.set("seed", snapshot.seedPresent() ? snapshot.seed() : null);
        if (!snapshot.worldSectionPresent() && world.getKeys(false).isEmpty()) {
            worlds.set(worldName, null);
        }
        if (!snapshot.worldsSectionPresent() && worlds.getKeys(false).isEmpty()) {
            configuration.set("worlds", null);
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

    public record WorldGeneratorSnapshot(
            boolean worldsSectionPresent,
            boolean worldSectionPresent,
            boolean generatorPresent,
            String generator,
            boolean seedPresent,
            Long seed
    ) {
        public WorldGeneratorSnapshot {
            if (worldSectionPresent && !worldsSectionPresent) {
                throw new IllegalArgumentException("A world section requires a worlds section.");
            }
            if (!worldSectionPresent && (generatorPresent || seedPresent)) {
                throw new IllegalArgumentException("Generator and seed values require a world section.");
            }
            if (generatorPresent != (generator != null)) {
                throw new IllegalArgumentException("Generator presence and value must agree.");
            }
            if (seedPresent != (seed != null)) {
                throw new IllegalArgumentException("Seed presence and value must agree.");
            }
        }

        private static WorldGeneratorSnapshot absent() {
            return new WorldGeneratorSnapshot(false, false, false, null, false, null);
        }

        private static WorldGeneratorSnapshot absentWorld(boolean worldsSectionPresent) {
            return new WorldGeneratorSnapshot(worldsSectionPresent, false, false, null, false, null);
        }

        private static WorldGeneratorSnapshot configured(String dimension, Long seed) {
            return new WorldGeneratorSnapshot(true, true, true, "Iris:" + dimension, seed != null, seed);
        }

        public boolean matchesGeneratorAndSeed(WorldGeneratorSnapshot other) {
            return generatorPresent == other.generatorPresent
                    && Objects.equals(generator, other.generator)
                    && seedPresent == other.seedPresent
                    && Objects.equals(seed, other.seed);
        }
    }

    public record GeneratorReplacement(
            boolean applied,
            WorldGeneratorSnapshot observed,
            WorldGeneratorSnapshot replacement
    ) {
        public GeneratorReplacement {
            Objects.requireNonNull(observed, "observed");
            Objects.requireNonNull(replacement, "replacement");
        }
    }
}

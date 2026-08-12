package art.arcane.iris.core.lifecycle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

public class BukkitWorldConfigurationTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void registersAndRemovesWorldAtomically() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");

        assertEquals(BukkitWorldConfiguration.Registration.CREATED,
                BukkitWorldConfiguration.register(configuration, "probe", "overworld", 1337L));
        assertEquals(BukkitWorldConfiguration.Registration.UNCHANGED,
                BukkitWorldConfiguration.register(configuration, "probe", "overworld", 1337L));

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(configuration);
        assertEquals("Iris:overworld", loaded.getString("worlds.probe.generator"));
        assertEquals(1337L, loaded.getLong("worlds.probe.seed"));
        assertTrue(BukkitWorldConfiguration.remove(configuration, "probe"));
        assertFalse(BukkitWorldConfiguration.remove(configuration, "probe"));
        assertNull(YamlConfiguration.loadConfiguration(configuration).getConfigurationSection("worlds"));
    }

    @Test
    public void conflictingRegistrationLeavesOriginalUntouched() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        BukkitWorldConfiguration.register(configuration, "probe", "overworld", 1337L);

        IOException failure = assertThrows(IOException.class,
                () -> BukkitWorldConfiguration.register(configuration, "probe", "theend", 42L));

        assertTrue(failure.getMessage().contains("different definition"));
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(configuration);
        assertEquals("Iris:overworld", loaded.getString("worlds.probe.generator"));
        assertEquals(1337L, loaded.getLong("worlds.probe.seed"));
    }

    @Test
    public void malformedConfigurationIsPreserved() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        String malformed = "worlds: [unterminated";
        Files.writeString(configuration.toPath(), malformed);

        IOException failure = assertThrows(IOException.class,
                () -> BukkitWorldConfiguration.register(configuration, "probe", "overworld", 1337L));

        assertTrue(failure.getMessage().contains("invalid"));
        assertEquals(malformed, Files.readString(configuration.toPath()));
    }

    @Test
    public void unsafeWorldNameIsRejected() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");

        assertThrows(IllegalArgumentException.class,
                () -> BukkitWorldConfiguration.register(configuration, "nested.world", "overworld", 1337L));
        assertEquals(0L, configuration.length());
    }

    @Test
    public void matchingRemovalIsCommittedOnce() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        BukkitWorldConfiguration.register(configuration, "iris-one", "overworld", null);
        BukkitWorldConfiguration.register(configuration, "iris-two", "overworld", null);
        BukkitWorldConfiguration.register(configuration, "persistent", "overworld", null);

        assertEquals(2, BukkitWorldConfiguration.removeMatching(
                configuration,
                worldName -> worldName.startsWith("iris-")));

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(configuration);
        assertNull(loaded.get("worlds.iris-one"));
        assertNull(loaded.get("worlds.iris-two"));
        assertEquals("Iris:overworld", loaded.getString("worlds.persistent.generator"));
    }

    @Test
    public void conditionalRemovalPreservesChangedRegistration() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        BukkitWorldConfiguration.register(configuration, "probe", "overworld", 1337L);

        assertFalse(BukkitWorldConfiguration.removeIfMatching(
                configuration,
                "probe",
                "overworld",
                42L));
        assertFalse(BukkitWorldConfiguration.removeIfMatching(
                configuration,
                "probe",
                "theend",
                1337L));

        YamlConfiguration preserved = YamlConfiguration.loadConfiguration(configuration);
        assertEquals("Iris:overworld", preserved.getString("worlds.probe.generator"));
        assertEquals(1337L, preserved.getLong("worlds.probe.seed"));
        assertTrue(BukkitWorldConfiguration.removeIfMatching(
                configuration,
                "probe",
                "overworld",
                1337L));
        assertNull(YamlConfiguration.loadConfiguration(configuration).get("worlds.probe"));
    }

    @Test
    public void replacementChangesOnlyGeneratorAndSeed() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        YamlConfiguration initial = new YamlConfiguration();
        initial.set("settings.allow-end", true);
        initial.set("worlds.world_nether.generator", "VanillaNether");
        initial.set("worlds.world_nether.seed", 7L);
        initial.set("worlds.world_nether.environment", "NETHER");
        initial.set("worlds.world_nether.keep-spawn-loaded", false);
        initial.save(configuration);

        BukkitWorldConfiguration.WorldGeneratorSnapshot original =
                BukkitWorldConfiguration.snapshot(configuration, "world_nether");
        BukkitWorldConfiguration.GeneratorReplacement replacement =
                BukkitWorldConfiguration.replaceIfMatching(
                        configuration,
                        "world_nether",
                        original,
                        "underworld",
                        1337L
                );

        assertTrue(replacement.applied());
        assertEquals(original, replacement.observed());
        assertEquals("VanillaNether", original.generator());
        assertEquals(Long.valueOf(7L), original.seed());
        assertEquals("Iris:underworld", replacement.replacement().generator());
        assertEquals(Long.valueOf(1337L), replacement.replacement().seed());
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(configuration);
        assertEquals("Iris:underworld", loaded.getString("worlds.world_nether.generator"));
        assertEquals(1337L, loaded.getLong("worlds.world_nether.seed"));
        assertEquals("NETHER", loaded.getString("worlds.world_nether.environment"));
        assertFalse(loaded.getBoolean("worlds.world_nether.keep-spawn-loaded"));
        assertTrue(loaded.getBoolean("settings.allow-end"));
    }

    @Test
    public void staleReplacementSnapshotDoesNotWrite() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        BukkitWorldConfiguration.register(configuration, "probe", "overworld", 1337L);
        BukkitWorldConfiguration.WorldGeneratorSnapshot original =
                BukkitWorldConfiguration.snapshot(configuration, "probe");
        BukkitWorldConfiguration.GeneratorReplacement first =
                BukkitWorldConfiguration.replaceIfMatching(
                        configuration,
                        "probe",
                        original,
                        "underworld",
                        42L
                );
        assertTrue(first.applied());
        String beforeStaleAttempt = Files.readString(configuration.toPath());

        BukkitWorldConfiguration.GeneratorReplacement stale =
                BukkitWorldConfiguration.replaceIfMatching(
                        configuration,
                        "probe",
                        original,
                        "theend",
                        99L
                );

        assertFalse(stale.applied());
        assertEquals(first.replacement(), stale.observed());
        assertEquals(beforeStaleAttempt, Files.readString(configuration.toPath()));
    }

    @Test
    public void restorationPreservesConcurrentUnrelatedFields() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        YamlConfiguration initial = new YamlConfiguration();
        initial.set("worlds.world_nether.generator", "VanillaNether");
        initial.set("worlds.world_nether.seed", 7L);
        initial.set("worlds.world_nether.environment", "NETHER");
        initial.save(configuration);
        BukkitWorldConfiguration.WorldGeneratorSnapshot original =
                BukkitWorldConfiguration.snapshot(configuration, "world_nether");
        BukkitWorldConfiguration.GeneratorReplacement replacement =
                BukkitWorldConfiguration.replaceIfMatching(
                        configuration,
                        "world_nether",
                        original,
                        "underworld",
                        1337L
                );

        YamlConfiguration concurrent = YamlConfiguration.loadConfiguration(configuration);
        concurrent.set("worlds.world_nether.environment", "CUSTOM");
        concurrent.set("worlds.world_nether.extra", "preserve");
        concurrent.save(configuration);

        assertTrue(BukkitWorldConfiguration.restoreIfMatching(
                configuration,
                "world_nether",
                replacement.replacement(),
                original
        ));
        YamlConfiguration restored = YamlConfiguration.loadConfiguration(configuration);
        assertEquals("VanillaNether", restored.getString("worlds.world_nether.generator"));
        assertEquals(7L, restored.getLong("worlds.world_nether.seed"));
        assertEquals("CUSTOM", restored.getString("worlds.world_nether.environment"));
        assertEquals("preserve", restored.getString("worlds.world_nether.extra"));
    }

    @Test
    public void staleRestorationDoesNotOverwriteChangedGenerator() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        BukkitWorldConfiguration.register(configuration, "probe", "overworld", 1337L);
        BukkitWorldConfiguration.WorldGeneratorSnapshot original =
                BukkitWorldConfiguration.snapshot(configuration, "probe");
        BukkitWorldConfiguration.GeneratorReplacement replacement =
                BukkitWorldConfiguration.replaceIfMatching(
                        configuration,
                        "probe",
                        original,
                        "underworld",
                        42L
                );
        YamlConfiguration concurrent = YamlConfiguration.loadConfiguration(configuration);
        concurrent.set("worlds.probe.generator", "ExternalGenerator");
        concurrent.save(configuration);
        String beforeRestore = Files.readString(configuration.toPath());

        assertFalse(BukkitWorldConfiguration.restoreIfMatching(
                configuration,
                "probe",
                replacement.replacement(),
                original
        ));
        assertEquals(beforeRestore, Files.readString(configuration.toPath()));
    }

    @Test
    public void restorationRemovesSectionsCreatedByReplacement() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        YamlConfiguration initial = new YamlConfiguration();
        initial.set("settings.allow-end", true);
        initial.save(configuration);
        BukkitWorldConfiguration.WorldGeneratorSnapshot original =
                BukkitWorldConfiguration.snapshot(configuration, "world_nether");
        BukkitWorldConfiguration.GeneratorReplacement replacement =
                BukkitWorldConfiguration.replaceIfMatching(
                        configuration,
                        "world_nether",
                        original,
                        "underworld",
                        null
                );

        assertTrue(BukkitWorldConfiguration.restoreIfMatching(
                configuration,
                "world_nether",
                replacement.replacement(),
                original
        ));
        YamlConfiguration restored = YamlConfiguration.loadConfiguration(configuration);
        assertNull(restored.getConfigurationSection("worlds"));
        assertTrue(restored.getBoolean("settings.allow-end"));
    }

    @Test
    public void restorationRetainsConcurrentFieldsInNewWorldSection() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        BukkitWorldConfiguration.WorldGeneratorSnapshot original =
                BukkitWorldConfiguration.snapshot(configuration, "world_nether");
        BukkitWorldConfiguration.GeneratorReplacement replacement =
                BukkitWorldConfiguration.replaceIfMatching(
                        configuration,
                        "world_nether",
                        original,
                        "underworld",
                        1337L
                );
        YamlConfiguration concurrent = YamlConfiguration.loadConfiguration(configuration);
        concurrent.set("worlds.world_nether.environment", "NETHER");
        concurrent.save(configuration);

        assertTrue(BukkitWorldConfiguration.restoreIfMatching(
                configuration,
                "world_nether",
                replacement.replacement(),
                original
        ));
        YamlConfiguration restored = YamlConfiguration.loadConfiguration(configuration);
        assertNull(restored.get("worlds.world_nether.generator"));
        assertNull(restored.get("worlds.world_nether.seed"));
        assertEquals("NETHER", restored.getString("worlds.world_nether.environment"));
    }

    @Test
    public void snapshotRefusesMalformedGeneratorWithoutChangingFile() throws Exception {
        File configuration = temporaryFolder.newFile("bukkit.yml");
        String malformed = "worlds:\n  probe:\n    generator:\n      nested: value\n    seed: 7\n";
        Files.writeString(configuration.toPath(), malformed);

        IOException failure = assertThrows(IOException.class,
                () -> BukkitWorldConfiguration.snapshot(configuration, "probe"));

        assertTrue(failure.getMessage().contains("generator"));
        assertEquals(malformed, Files.readString(configuration.toPath()));
    }

    @Test
    public void replacementRejectsSymbolicConfigurationWithoutChangingLinkOrTarget() throws Exception {
        Path target = temporaryFolder.newFile("shared-bukkit.yml").toPath();
        String original = "settings:\n  allow-end: true\n";
        Files.writeString(target, original);
        Path link = temporaryFolder.getRoot().toPath().resolve("bukkit.yml");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException failure) {
            assumeNoException(failure);
        }
        BukkitWorldConfiguration.WorldGeneratorSnapshot expected =
                new BukkitWorldConfiguration.WorldGeneratorSnapshot(false, false, false, null, false, null);

        assertThrows(IOException.class, () -> BukkitWorldConfiguration.replaceIfMatching(
                link.toFile(),
                "world_nether",
                expected,
                "underworld",
                1337L
        ));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals(original, Files.readString(target));
    }

    @Test
    public void snapshotRejectsSpecialConfigurationWithoutOpeningIt() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("unix"));
        Path socket = temporaryFolder.getRoot().toPath().resolve("bukkit.yml");
        try (ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            try {
                channel.bind(UnixDomainSocketAddress.of(socket));
            } catch (IOException | UnsupportedOperationException failure) {
                assumeNoException(failure);
            }

            assertThrows(IOException.class, () -> BukkitWorldConfiguration.snapshot(
                    socket.toFile(),
                    "world_nether"
            ));
        }
    }
}

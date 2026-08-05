package art.arcane.iris.core.lifecycle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
}

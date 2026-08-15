package art.arcane.iris.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class IrisWorldsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void registryFileBelongsToSelectedLevelRoot() throws Exception {
        Path levelRoot = temporaryFolder.newFolder("world").toPath();

        assertEquals(
                levelRoot.toAbsolutePath().normalize().resolve("iris/worlds.json"),
                IrisWorlds.registryFile(levelRoot));
    }

    @Test
    public void bukkitWorldFilteringUsesExactStorageInSelectedRoot() throws Exception {
        Path selectedRoot = temporaryFolder.newFolder("world").toPath();
        Path otherRoot = temporaryFolder.newFolder("archive").toPath();
        Files.createDirectories(selectedRoot.resolve("dimensions/minecraft/overworld"));
        Files.createDirectories(selectedRoot.resolve("dimensions/iris/moon"));
        Files.createDirectories(otherRoot.resolve("dimensions/iris/foreign"));
        Files.createDirectories(selectedRoot.resolve("dimensions/iris"));
        Files.writeString(selectedRoot.resolve("dimensions/iris/not_a_directory"), "not storage");

        Map<String, String> configuredWorlds = new LinkedHashMap<>();
        configuredWorlds.put("world", "overworld");
        configuredWorlds.put("world_nether", "underworld");
        configuredWorlds.put("world_iris_moon", "overworld");
        configuredWorlds.put("moon", "overworld");
        configuredWorlds.put("world_iris_foreign", "overworld");
        configuredWorlds.put("archive_iris_foreign", "overworld");
        configuredWorlds.put("foreign", "overworld");
        configuredWorlds.put("world_iris_not_a_directory", "overworld");
        configuredWorlds.put("world_iris_missing", "overworld");

        Map<String, String> selected = IrisWorlds.filterBukkitWorldsByStorage(selectedRoot, configuredWorlds);
        Map<String, String> other = IrisWorlds.filterBukkitWorldsByStorage(otherRoot, configuredWorlds);

        assertEquals(Set.of("world", "world_iris_moon"), selected.keySet());
        assertEquals(Set.of("archive_iris_foreign"), other.keySet());
    }
}

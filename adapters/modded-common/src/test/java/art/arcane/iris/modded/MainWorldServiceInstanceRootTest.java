package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MainWorldServiceInstanceRootTest {
    @Test
    public void universeOptionIsReadInBothArgumentForms() {
        assertEquals("worlds", MainWorldService.commandLineOption(
                List.of("nogui", "--universe", "worlds"), "universe"));
        assertEquals("worlds", MainWorldService.commandLineOption(
                List.of("--universe=worlds", "nogui"), "universe"));
        assertEquals("survival", MainWorldService.commandLineOption(
                List.of("--universe", "worlds", "--world", "survival"), "world"));
    }

    @Test
    public void missingOrValuelessOptionsResolveToNull() {
        assertNull(MainWorldService.commandLineOption(List.of("nogui"), "universe"));
        assertNull(MainWorldService.commandLineOption(List.of(), "world"));
        assertNull(MainWorldService.commandLineOption(List.of("nogui", "--world"), "world"));
    }

    @Test
    public void universeRootDefaultsToTheInstanceRootAndHonorsTheOption() throws IOException {
        Path instanceRoot = Files.createTempDirectory("iris-instance");
        Path elsewhere = Files.createTempDirectory("iris-elsewhere");

        assertEquals(instanceRoot, MainWorldService.universeRoot(instanceRoot, null));
        assertEquals(instanceRoot, MainWorldService.universeRoot(instanceRoot, ""));
        assertEquals(instanceRoot.resolve("worlds"), MainWorldService.universeRoot(instanceRoot, "worlds"));
        assertEquals(elsewhere, MainWorldService.universeRoot(instanceRoot, elsewhere.toString()));
    }

    @Test
    public void worldRootResolvesInsideTheUniverse() throws IOException {
        Path universe = Files.createTempDirectory("iris-universe");
        Path world = Files.createDirectory(universe.resolve("survival"));

        assertEquals(world.toAbsolutePath().normalize(),
                MainWorldService.resolveWorldRoot(universe, "survival"));
    }

    @Test
    public void worldRootRefusesToEscapeTheUniverse() throws IOException {
        Path universe = Files.createTempDirectory("iris-universe");
        Files.createDirectory(universe.resolve("survival"));

        IOException escaped = assertThrows(IOException.class,
                () -> MainWorldService.resolveWorldRoot(universe, "../survival"));
        IOException empty = assertThrows(IOException.class,
                () -> MainWorldService.resolveWorldRoot(universe, "."));

        assertTrue(escaped.getMessage().contains("Unsafe world name"));
        assertTrue(empty.getMessage().contains("Unsafe world name"));
    }

    @Test
    public void worldRootReportsMissingDirectoriesAsTheRecoverableCase() throws IOException {
        Path universe = Files.createTempDirectory("iris-universe");

        MainWorldService.MissingWorldRootException missingWorld = assertThrows(
                MainWorldService.MissingWorldRootException.class,
                () -> MainWorldService.resolveWorldRoot(universe, "survival"));
        MainWorldService.MissingWorldRootException missingUniverse = assertThrows(
                MainWorldService.MissingWorldRootException.class,
                () -> MainWorldService.resolveWorldRoot(universe.resolve("absent"), "survival"));

        assertTrue(missingWorld.getMessage().contains("world directory does not exist"));
        assertEquals(universe.resolve("survival").toAbsolutePath().normalize(), missingWorld.path());
        assertTrue(missingUniverse.getMessage().contains("universe directory does not exist"));
        assertEquals(universe.resolve("absent"), missingUniverse.path());
    }

    @Test
    public void unsafeWorldNameIsNotTreatedAsAMissingWorldRoot() throws IOException {
        Path universe = Files.createTempDirectory("iris-universe");

        IOException escaped = assertThrows(IOException.class,
                () -> MainWorldService.resolveWorldRoot(universe, "../survival"));

        assertFalse(escaped instanceof MainWorldService.MissingWorldRootException);
    }
}

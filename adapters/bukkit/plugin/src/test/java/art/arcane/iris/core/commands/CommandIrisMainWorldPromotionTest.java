package art.arcane.iris.core.commands;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CommandIrisMainWorldPromotionTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void existingTopLevelWorldIsRefusedWithoutMerging() throws IOException {
        PromotionPaths paths = createPromotionPaths("existing-target");
        Files.createDirectories(paths.target());
        Files.writeString(paths.target().resolve("sentinel.txt"), "keep");

        assertThrows(FileAlreadyExistsException.class, () -> CommandIris.publishMainWorldFiles(
                paths.current(),
                paths.sourceDimension(),
                paths.target()
        ));

        assertEquals("keep", Files.readString(paths.target().resolve("sentinel.txt")));
        assertFalse(Files.exists(paths.target().resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertFalse(hasPromotionStage(paths.root(), paths.target().getFileName().toString()));
    }

    @Test
    public void uncommittedPromotionRollsBackThePublishedWorld() throws IOException {
        PromotionPaths paths = createPromotionPaths("rollback-target");

        try (CommandIris.MainWorldPublication publication = CommandIris.publishMainWorldFiles(
                paths.current(),
                paths.sourceDimension(),
                paths.target()
        )) {
            assertTrue(Files.isRegularFile(paths.target().resolve("data/map.dat")));
            assertTrue(Files.isRegularFile(paths.target().resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        }

        assertFalse(Files.exists(paths.target()));
        assertFalse(hasPromotionStage(paths.root(), paths.target().getFileName().toString()));
    }

    @Test
    public void committedPromotionKeepsTheCompleteStagedWorld() throws IOException {
        PromotionPaths paths = createPromotionPaths("committed-target");

        try (CommandIris.MainWorldPublication publication = CommandIris.publishMainWorldFiles(
                paths.current(),
                paths.sourceDimension(),
                paths.target()
        )) {
            publication.commit();
        }

        assertEquals("map", Files.readString(paths.target().resolve("data/map.dat")));
        assertEquals("region", Files.readString(paths.target().resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertFalse(hasPromotionStage(paths.root(), paths.target().getFileName().toString()));
    }

    private PromotionPaths createPromotionPaths(String targetName) throws IOException {
        Path root = temporaryFolder.newFolder(targetName + "-root").toPath();
        Path current = root.resolve("world");
        Path sourceDimension = current.resolve("dimensions/iris/" + targetName);
        Path target = root.resolve(targetName);
        Files.createDirectories(current.resolve("data"));
        Files.writeString(current.resolve("data/map.dat"), "map");
        Files.createDirectories(sourceDimension.resolve("region"));
        Files.writeString(sourceDimension.resolve("region/r.0.0.mca"), "region");
        return new PromotionPaths(root, current, sourceDimension, target);
    }

    private boolean hasPromotionStage(Path root, String targetName) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.anyMatch(path -> path.getFileName().toString().startsWith("." + targetName + ".promoting-"));
        }
    }

    private record PromotionPaths(Path root, Path current, Path sourceDimension, Path target) {
    }
}

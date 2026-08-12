package art.arcane.iris.core.service;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.AtomicDirectoryPublisher;
import art.arcane.iris.core.pack.BrokenPackException;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import org.junit.Assume;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StudioSVCWorldPackPublishTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void clearValidationRegistry() {
        PackValidationRegistry.clear();
    }

    @Test
    public void copiesToStageAndPublishesTheCompletePack() throws IOException {
        Path root = temporaryFolder.newFolder("world").toPath();
        Path source = root.resolve("source");
        Path stage = root.resolve("iris/.pack.installing-test");
        Path target = root.resolve("iris/pack");
        Files.createDirectories(source.resolve("dimensions"));
        Files.writeString(source.resolve("dimensions/example.json"), "{}");
        Files.createDirectories(stage);

        StudioSVC.copyPackTree(source, stage);
        assertFalse(Files.exists(target));
        StudioSVC.publishNewDirectory(stage, target);

        assertFalse(Files.exists(stage));
        assertTrue(Files.isRegularFile(target.resolve("dimensions/example.json")));
    }

    @Test
    public void existingPartialTargetIsNeverReplacedOrMerged() throws IOException {
        Path root = temporaryFolder.newFolder("existing-world").toPath();
        Path stage = root.resolve("iris/.pack.installing-test");
        Path target = root.resolve("iris/pack");
        Files.createDirectories(stage);
        Files.writeString(stage.resolve("new.txt"), "new");
        Files.createDirectories(target);
        Files.writeString(target.resolve("sentinel.txt"), "keep");

        assertThrows(FileAlreadyExistsException.class, () -> StudioSVC.publishNewDirectory(stage, target));

        assertEquals("keep", Files.readString(target.resolve("sentinel.txt")));
        assertFalse(Files.exists(target.resolve("new.txt")));
        assertTrue(Files.exists(stage.resolve("new.txt")));
    }

    @Test
    public void symbolicLinksInSourceAreRejectedBeforePublish() throws IOException {
        Path root = temporaryFolder.newFolder("linked-source").toPath();
        Path source = root.resolve("source");
        Path stage = root.resolve("stage");
        Path outside = root.resolve("outside.txt");
        Files.createDirectories(source);
        Files.createDirectories(stage);
        Files.writeString(outside, "outside");
        try {
            Files.createSymbolicLink(source.resolve("link.txt"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assume.assumeNoException(e);
        }

        assertThrows(IOException.class, () -> StudioSVC.copyPackTree(source, stage));
        assertFalse(Files.exists(stage.resolve("link.txt")));
    }

    @Test
    public void rootPackSymlinkResolvesWhileNestedLinksRemainRejected() throws IOException {
        Path root = temporaryFolder.newFolder("linked-pack-root").toPath();
        Path source = root.resolve("source");
        Path linkedSource = root.resolve("linked_source");
        Path stage = root.resolve("stage");
        Files.createDirectories(source.resolve("dimensions"));
        Files.writeString(source.resolve("dimensions/example.json"), "{}");
        Files.createDirectories(stage);
        try {
            Files.createSymbolicLink(linkedSource, source);
        } catch (IOException | UnsupportedOperationException e) {
            Assume.assumeNoException(e);
        }

        Path resolved = StudioSVC.resolveSafePackSource(linkedSource.toFile());
        StudioSVC.copyPackTree(resolved, stage);

        assertEquals(source.toRealPath(), resolved);
        assertTrue(Files.isRegularFile(stage.resolve("dimensions/example.json")));
    }

    @Test
    public void rejectedPublicationEvictsCreatedLoaderBeforeDiskRollback() throws IOException {
        Path root = temporaryFolder.newFolder("cache-rollback").toPath();
        Path target = root.resolve("pack");
        Path stage = root.resolve("stage");
        Files.createDirectories(target);
        Files.writeString(target.resolve("sentinel.txt"), "previous");
        Files.createDirectories(stage);
        Files.writeString(stage.resolve("rejected.txt"), "rejected");
        AtomicDirectoryPublisher.Publication publication = AtomicDirectoryPublisher.publish(stage, target);
        IrisData createdData = IrisData.get(target.toFile());

        assertSame(createdData, IrisData.getLoaded(target.toFile()).orElse(null));
        StudioSVC.rollbackFailedPublication(createdData, publication, new IOException("validation failed"));

        assertTrue(IrisData.getLoaded(target.toFile()).isEmpty());
        assertEquals("previous", Files.readString(target.resolve("sentinel.txt")));
        assertFalse(Files.exists(target.resolve("rejected.txt")));
    }

    @Test
    public void finalPublishedSnapshotReplacesStalePathValidation() throws Exception {
        Path packRoot = temporaryFolder.newFolder("published-snapshot", "iris", "pack").toPath();
        writeValidPack(packRoot);
        PackValidationResult staleFailure = new PackValidationResult(
                "pack", List.of("stale failure"), List.of(), 1L);
        PackValidationRegistry.publish(packRoot, staleFailure);

        PackValidationResult validated = StudioSVC.validatePublishedPack(packRoot);

        assertTrue(validated.isLoadable());
        assertSame(validated, PackValidationRegistry.requireLoadable(packRoot));

        Files.writeString(packRoot.resolve("dimensions/main.json"), "{");
        assertThrows(BrokenPackException.class, () -> StudioSVC.validatePublishedPack(packRoot));
        assertTrue(PackValidationRegistry.isBroken(packRoot));
    }

    @Test
    public void createdProjectRollbackEvictsOnlyItsCachedLoaderBeforeDeletion() throws IOException {
        Path root = temporaryFolder.newFolder("project-cache-rollback").toPath();
        Path project = root.resolve("created_project");
        Path sibling = root.resolve("existing_project");
        Files.createDirectories(project.resolve("dimensions"));
        Files.writeString(project.resolve("dimensions/created_project.json"), "{}");
        Files.createDirectories(sibling.resolve("dimensions"));
        Files.writeString(sibling.resolve("dimensions/existing_project.json"), "{}");
        IrisData createdData = IrisData.get(project.toFile());
        IrisData siblingData = IrisData.get(sibling.toFile());

        try {
            assertSame(createdData, IrisData.getLoaded(project.toFile()).orElse(null));
            assertNull(StudioSVC.rollbackCreatedProjectFiles(project.toFile()));

            assertTrue(IrisData.getLoaded(project.toFile()).isEmpty());
            assertFalse(Files.exists(project));
            assertSame(siblingData, IrisData.getLoaded(sibling.toFile()).orElse(null));
            assertTrue(Files.isDirectory(sibling));
        } finally {
            IrisData.getLoaded(project.toFile()).ifPresent(IrisData::close);
            IrisData.getLoaded(sibling.toFile()).ifPresent(IrisData::close);
        }
    }

    @Test
    public void studioTransitionsWaitForTheInFlightOpenBeforeReplacement() {
        StudioSVC.StudioTransitionQueue transitions = new StudioSVC.StudioTransitionQueue();
        CompletableFuture<String> firstGate = new CompletableFuture<>();
        CompletableFuture<String> secondGate = new CompletableFuture<>();
        List<String> events = new ArrayList<>();

        CompletableFuture<String> first = transitions.submit(() -> {
            events.add("first-start");
            return firstGate;
        });
        CompletableFuture<String> second = transitions.submit(() -> {
            events.add("second-start");
            return secondGate;
        });

        assertEquals(List.of("first-start"), events);
        assertFalse(first.isDone());
        assertFalse(second.isDone());

        firstGate.complete("first");
        assertEquals(List.of("first-start", "second-start"), events);
        assertEquals("first", first.join());
        assertFalse(second.isDone());

        secondGate.complete("second");
        assertEquals("second", second.join());
    }

    private static void writeValidPack(Path packRoot) throws Exception {
        Files.createDirectories(packRoot.resolve("dimensions"));
        Files.createDirectories(packRoot.resolve("regions"));
        Files.createDirectories(packRoot.resolve("biomes"));
        Files.writeString(packRoot.resolve("dimensions/main.json"), "{\"regions\":[\"region\"]}");
        Files.writeString(packRoot.resolve("regions/region.json"), "{\"landBiomes\":[\"biome\"]}");
        Files.writeString(packRoot.resolve("biomes/biome.json"), "{\"name\":\"Biome\"}");
    }
}

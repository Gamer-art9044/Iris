/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.core.structure.authoring;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StructureTransactionWriterTest {
    private static final StructureKey TARGET_KEY = StructureKey.parse("iris_test:temple");
    private static final StructureKey SOURCE_KEY = StructureKey.parse("minecraft:trial_chambers");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void dryRunPerformsPreflightWithoutWriting() throws IOException {
        Path root = temporaryFolder.newFolder("dry-run").toPath();
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureWriteResult result = writer.preview(bundle("object-v1", "structure-v1"), StructureWriteMode.ADD_ONLY);

        assertEquals(StructureWriteResult.Status.DRY_RUN, result.status());
        assertEquals(StructureWriteResult.Action.ADD, result.action());
        assertTrue(result.successful());
        assertFalse(result.committed());
        assertFalse(Files.exists(root.resolve("objects/temple.iob")));
        assertFalse(Files.exists(writer.ownershipManifestPath(TARGET_KEY)));
        assertFalse(Files.exists(root.resolve(".iris")));
    }

    @Test
    public void addOnlyReportsExistingResourcesWithoutChangingThem() throws IOException {
        Path root = temporaryFolder.newFolder("add-only").toPath();
        Path existing = root.resolve("objects/temple.iob");
        Files.createDirectories(existing.getParent());
        byte[] userContent = "user-content".getBytes(StandardCharsets.UTF_8);
        Files.write(existing, userContent);
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureWriteResult result = writer.write(bundle("object-v1", "structure-v1"), StructureWriteMode.ADD_ONLY);

        assertEquals(StructureWriteResult.Status.ADD_ONLY_CONFLICT, result.status());
        assertEquals(1, result.conflicts().size());
        assertEquals(StructureWriteResult.ConflictReason.RESOURCE_EXISTS, result.conflicts().get(0).reason());
        assertArrayEquals(userContent, Files.readAllBytes(existing));
        assertFalse(Files.exists(writer.ownershipManifestPath(TARGET_KEY)));
    }

    @Test
    public void ownershipManifestProtectsHandEditedResources() throws IOException {
        Path root = temporaryFolder.newFolder("ownership").toPath();
        StructureTransactionWriter writer = new StructureTransactionWriter(root);
        StructureWriteResult initial = writer.write(bundle("object-v1", "structure-v1"), StructureWriteMode.ADD_ONLY);
        Path object = root.resolve("objects/temple.iob");
        byte[] handEdit = "hand-edit".getBytes(StandardCharsets.UTF_8);
        assertEquals(failureMessage(initial), StructureWriteResult.Status.ADDED, initial.status());
        Files.write(object, handEdit);

        StructureWriteResult result = writer.write(bundle("object-v2", "structure-v2"), StructureWriteMode.OVERWRITE);

        assertEquals(failureMessage(initial), StructureWriteResult.Status.ADDED, initial.status());
        assertEquals(StructureWriteResult.Status.OWNERSHIP_CONFLICT, result.status());
        assertTrue(result.conflicts().stream().anyMatch(conflict ->
                conflict.reason() == StructureWriteResult.ConflictReason.MODIFIED_RESOURCE
                        && conflict.relativePath().equals("objects/temple.iob")));
        assertArrayEquals(handEdit, Files.readAllBytes(object));
        StructureOwnershipManifest manifest = StructureOwnershipManifest.fromJson(
                Files.readAllBytes(writer.ownershipManifestPath(TARGET_KEY))
        );
        assertEquals(StructureHash.sha256("object-v1".getBytes(StandardCharsets.UTF_8)),
                manifest.resourceHashes().get("objects/temple.iob"));
    }

    @Test
    public void overwriteReplacesOnlyOwnedUnmodifiedResources() throws IOException {
        Path root = temporaryFolder.newFolder("overwrite").toPath();
        StructureTransactionWriter writer = new StructureTransactionWriter(root);
        StructureWriteResult initial = writer.write(bundle("object-v1", "structure-v1"), StructureWriteMode.ADD_ONLY);

        StructureWriteResult result = writer.write(bundle("object-v2", "structure-v2"), StructureWriteMode.OVERWRITE);

        assertEquals(failureMessage(initial), StructureWriteResult.Status.ADDED, initial.status());
        assertEquals(StructureWriteResult.Status.OVERWRITTEN, result.status());
        assertEquals(StructureWriteResult.Action.OVERWRITE, result.action());
        assertArrayEquals("object-v2".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(root.resolve("objects/temple.iob")));
        assertArrayEquals("structure-v2".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(root.resolve("structures/temple.json")));
    }

    @Test
    public void failedMultiFileInstallRollsBackEveryResourceAndManifest() throws IOException {
        Path root = temporaryFolder.newFolder("rollback").toPath();
        StructureTransactionWriter initialWriter = new StructureTransactionWriter(root);
        StructureWriteResult initial = initialWriter.write(
                bundle("object-v1", "structure-v1"),
                StructureWriteMode.ADD_ONLY
        );
        Path object = root.resolve("objects/temple.iob");
        Path structure = root.resolve("structures/temple.json");
        Path manifest = initialWriter.ownershipManifestPath(TARGET_KEY);
        assertEquals(failureMessage(initial), StructureWriteResult.Status.ADDED, initial.status());
        byte[] originalObject = Files.readAllBytes(object);
        byte[] originalStructure = Files.readAllBytes(structure);
        byte[] originalManifest = Files.readAllBytes(manifest);
        FailOnceMoveOperations operations = new FailOnceMoveOperations("staged/structures/temple.json");
        StructureTransactionWriter failingWriter = new StructureTransactionWriter(root, operations);

        StructureWriteResult result = failingWriter.write(
                bundle("object-v2", "structure-v2"),
                StructureWriteMode.OVERWRITE
        );

        assertEquals(StructureWriteResult.Status.ADDED, initial.status());
        assertEquals(StructureWriteResult.Status.ROLLED_BACK, result.status());
        assertTrue(result.failure().isPresent());
        assertArrayEquals(originalObject, Files.readAllBytes(object));
        assertArrayEquals(originalStructure, Files.readAllBytes(structure));
        assertArrayEquals(originalManifest, Files.readAllBytes(manifest));
        Path staging = root.resolve(".iris/structure-staging");
        if (Files.exists(staging)) {
            try (Stream<Path> paths = Files.list(staging)) {
                assertEquals(0, paths.count());
            }
        }
    }

    @Test
    public void preparedTransactionRecoveryRestoresBackupsAndRemovesNewTargets() throws IOException {
        Path root = temporaryFolder.newFolder("prepared-recovery").toPath();
        Path originalTarget = root.resolve("objects/temple.iob");
        Path newTarget = root.resolve("structures/temple.json");
        Files.createDirectories(originalTarget.getParent());
        Files.createDirectories(newTarget.getParent());
        Files.write(originalTarget, "partially-installed".getBytes(StandardCharsets.UTF_8));
        Files.write(newTarget, "new-target".getBytes(StandardCharsets.UTF_8));
        byte[] partialReplacement = "partially-installed".getBytes(StandardCharsets.UTF_8);
        byte[] newReplacement = "new-target".getBytes(StandardCharsets.UTF_8);
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        Path backup = transactionRoot.resolve("backup/objects/temple.iob");
        Files.createDirectories(backup.getParent());
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(backup, original);
        writeJournal(transactionRoot, StructureTransactionJournal.prepared(transactionId, List.of(
                new StructureTransactionJournal.Target(
                        "objects/temple.iob",
                        true,
                        StructureHash.sha256(original),
                        StructureHash.sha256(partialReplacement)
                ),
                new StructureTransactionJournal.Target(
                        "structures/temple.json",
                        false,
                        "",
                        StructureHash.sha256(newReplacement)
                )
        )));
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureRecoveryResult result = writer.recoverIncompleteTransactions();

        assertTrue(result.successful());
        assertEquals(1, result.restoredPreparedTransactions());
        assertEquals(1, result.recoveredTransactions());
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(originalTarget));
        assertFalse(Files.exists(newTarget));
        assertFalse(Files.exists(transactionRoot));
    }

    @Test
    public void committedTransactionRecoveryKeepsInstalledTargetsAndCleansRecoveryData() throws IOException {
        Path root = temporaryFolder.newFolder("committed-recovery").toPath();
        Path target = root.resolve("objects/temple.iob");
        Files.createDirectories(target.getParent());
        Files.write(target, "committed".getBytes(StandardCharsets.UTF_8));
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        Path backup = transactionRoot.resolve("backup/objects/temple.iob");
        Files.createDirectories(backup.getParent());
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(backup, original);
        writeJournal(transactionRoot, StructureTransactionJournal.prepared(transactionId, List.of(
                new StructureTransactionJournal.Target(
                        "objects/temple.iob",
                        true,
                        StructureHash.sha256(original),
                        StructureHash.sha256("committed".getBytes(StandardCharsets.UTF_8))
                )
        )).committed());
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureRecoveryResult result = writer.recoverIncompleteTransactions();

        assertTrue(result.successful());
        assertEquals(1, result.cleanedCommittedTransactions());
        assertEquals(1, result.recoveredTransactions());
        assertArrayEquals("committed".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertFalse(Files.exists(transactionRoot));
    }

    @Test
    public void nextOnlyPreparedJournalIsRecoveredAfterANonAtomicMoveInterruption() throws IOException {
        Path root = temporaryFolder.newFolder("next-journal-recovery").toPath();
        Path target = root.resolve("objects/temple.iob");
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(target.getParent());
        Files.write(target, replacement);
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        Path backup = transactionRoot.resolve("backup/objects/temple.iob");
        Files.createDirectories(backup.getParent());
        Files.write(backup, original);
        StructureTransactionJournal journal = StructureTransactionJournal.prepared(transactionId, List.of(
                new StructureTransactionJournal.Target(
                        "objects/temple.iob",
                        true,
                        StructureHash.sha256(original),
                        StructureHash.sha256(replacement)
                )
        ));
        Files.write(transactionRoot.resolve(StructureTransactionJournal.NEXT_FILE_NAME), journal.toJson());
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureRecoveryResult result = writer.recoverIncompleteTransactions();

        assertTrue(result.successful());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertFalse(Files.exists(transactionRoot));
    }

    @Test
    public void committedRecoveryRetainsBackupsWhenInstalledTargetsDoNotMatch() throws IOException {
        Path root = temporaryFolder.newFolder("committed-mismatch").toPath();
        Path target = root.resolve("objects/temple.iob");
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        byte[] unexpected = "unexpected".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(target.getParent());
        Files.write(target, unexpected);
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        Path backup = transactionRoot.resolve("backup/objects/temple.iob");
        Files.createDirectories(backup.getParent());
        Files.write(backup, original);
        StructureTransactionJournal journal = StructureTransactionJournal.prepared(transactionId, List.of(
                new StructureTransactionJournal.Target(
                        "objects/temple.iob",
                        true,
                        StructureHash.sha256(original),
                        StructureHash.sha256("committed".getBytes(StandardCharsets.UTF_8))
                )
        )).committed();
        Files.write(transactionRoot.resolve(StructureTransactionJournal.FILE_NAME), journal.toJson());
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureRecoveryResult result = writer.recoverIncompleteTransactions();

        assertFalse(result.successful());
        assertArrayEquals(unexpected, Files.readAllBytes(target));
        assertTrue(Files.exists(backup));
        assertTrue(Files.exists(transactionRoot));
    }

    @Test
    public void invalidRecoveryJournalBlocksWritesAndRetainsRecoveryData() throws IOException {
        Path root = temporaryFolder.newFolder("invalid-recovery").toPath();
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        Path journalPath = transactionRoot.resolve(StructureTransactionJournal.FILE_NAME);
        Files.createDirectories(transactionRoot);
        Files.write(journalPath, "not-json".getBytes(StandardCharsets.UTF_8));
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureWriteResult result = writer.write(bundle("object-v1", "structure-v1"),
                StructureWriteMode.ADD_ONLY);

        assertEquals(StructureWriteResult.Status.FAILED, result.status());
        assertTrue(result.failure().isPresent());
        assertTrue(Files.exists(journalPath));
        assertFalse(Files.exists(root.resolve("objects/temple.iob")));
    }

    @Test
    public void writeRecoversPreparedTransactionBeforePreflight() throws IOException {
        Path root = temporaryFolder.newFolder("write-recovery").toPath();
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        byte[] interruptedReplacement = "interrupted".getBytes(StandardCharsets.UTF_8);
        writeJournal(transactionRoot, StructureTransactionJournal.prepared(transactionId, List.of(
                new StructureTransactionJournal.Target(
                        "objects/temple.iob",
                        false,
                        "",
                        StructureHash.sha256(interruptedReplacement)
                )
        )));
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureWriteResult result = writer.write(bundle("object-v1", "structure-v1"),
                StructureWriteMode.ADD_ONLY);

        assertEquals(failureMessage(result), StructureWriteResult.Status.ADDED, result.status());
        assertArrayEquals("object-v1".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(root.resolve("objects/temple.iob")));
        assertFalse(Files.exists(transactionRoot));
    }

    @Test
    public void preparedRecoveryRetainsUnexpectedReplacement() throws IOException {
        Path root = temporaryFolder.newFolder("replacement-conflict").toPath();
        Path target = root.resolve("objects/temple.iob");
        Files.createDirectories(target.getParent());
        byte[] userEdit = "user-edit".getBytes(StandardCharsets.UTF_8);
        Files.write(target, userEdit);
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        writeJournal(transactionRoot, StructureTransactionJournal.prepared(transactionId, List.of(
                new StructureTransactionJournal.Target(
                        "objects/temple.iob",
                        false,
                        "",
                        StructureHash.sha256("interrupted".getBytes(StandardCharsets.UTF_8))
                )
        )));
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureRecoveryResult result = writer.recoverIncompleteTransactions();

        assertFalse(result.successful());
        assertEquals(1, result.failures().size());
        assertArrayEquals(userEdit, Files.readAllBytes(target));
        assertTrue(Files.exists(transactionRoot));
    }

    @Test
    public void concurrentTargetCreationIsNotDeletedByRollback() throws IOException {
        Path root = temporaryFolder.newFolder("concurrent-target").toPath();
        byte[] userContent = "concurrent-user-file".getBytes(StandardCharsets.UTF_8);
        FailOnceMoveOperations operations = new FailOnceMoveOperations(
                "staged/objects/temple.iob",
                userContent
        );
        StructureTransactionWriter writer = new StructureTransactionWriter(root, operations);

        StructureWriteResult result = writer.write(bundle("object-v1", "structure-v1"),
                StructureWriteMode.ADD_ONLY);

        assertEquals(StructureWriteResult.Status.ROLLED_BACK, result.status());
        assertArrayEquals(userContent, Files.readAllBytes(root.resolve("objects/temple.iob")));
    }

    @Test
    public void preparedRecoveryAcceptsAlreadyRestoredOriginalWithRemainingBackup() throws IOException {
        Path root = temporaryFolder.newFolder("repeated-recovery").toPath();
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Path target = root.resolve("objects/temple.iob");
        Files.createDirectories(target.getParent());
        Files.write(target, original);
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = transactionRoot(root, transactionId);
        Path backup = transactionRoot.resolve("backup/objects/temple.iob");
        Files.createDirectories(backup.getParent());
        Files.write(backup, original);
        writeJournal(transactionRoot, StructureTransactionJournal.prepared(transactionId, List.of(
                new StructureTransactionJournal.Target(
                        "objects/temple.iob",
                        true,
                        StructureHash.sha256(original),
                        StructureHash.sha256("replacement".getBytes(StandardCharsets.UTF_8))
                )
        )));
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureRecoveryResult result = writer.recoverIncompleteTransactions();

        assertTrue(result.successful());
        assertEquals(1, result.restoredPreparedTransactions());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertFalse(Files.exists(transactionRoot));
    }

    @Test
    public void symbolicLinkAncestorsCannotEscapeThePackRoot() throws IOException {
        Path root = temporaryFolder.newFolder("symlink-pack").toPath();
        Path outside = temporaryFolder.newFolder("symlink-outside").toPath();
        try {
            Files.createSymbolicLink(root.resolve("objects"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assume.assumeNoException(e);
        }
        StructureTransactionWriter writer = new StructureTransactionWriter(root);

        StructureWriteResult result = writer.write(bundle("object-v1", "structure-v1"),
                StructureWriteMode.ADD_ONLY);

        assertEquals(StructureWriteResult.Status.FAILED, result.status());
        assertFalse(Files.exists(outside.resolve("temple.iob")));
    }

    private StructureResourceBundle bundle(String objectContent, String structureContent) {
        return StructureResourceBundle.builder(TARGET_KEY)
                .source(StructureSource.of(StructureSource.Kind.VANILLA, SOURCE_KEY))
                .backend(StructureBackend.IRIS_ASSEMBLY)
                .capability(StructureCapability.BLOCKS)
                .capability(StructureCapability.CONNECTORS)
                .loss(StructureLoss.warning(
                        StructureCapability.PROCESSORS,
                        "processors_omitted",
                        "The source processor list was not represented"
                ))
                .resource("objects/temple.iob", objectContent.getBytes(StandardCharsets.UTF_8))
                .textResource("structures/temple.json", structureContent)
                .build();
    }

    private Path transactionRoot(Path root, UUID transactionId) {
        return root.resolve(".iris/structure-staging").resolve(transactionId.toString());
    }

    private String failureMessage(StructureWriteResult result) {
        return result.failure().map(Throwable::toString).orElse("");
    }

    private void writeJournal(
            Path transactionRoot,
            StructureTransactionJournal journal
    ) throws IOException {
        Files.createDirectories(transactionRoot);
        Files.write(transactionRoot.resolve(StructureTransactionJournal.FILE_NAME), journal.toJson());
    }

    private static final class FailOnceMoveOperations implements StructureFileOperations {
        private final NioStructureFileOperations delegate;
        private final String failingSuffix;
        private final byte[] competingContent;
        private boolean failed;

        private FailOnceMoveOperations(String failingSuffix) {
            this(failingSuffix, null);
        }

        private FailOnceMoveOperations(String failingSuffix, byte[] competingContent) {
            delegate = new NioStructureFileOperations();
            this.failingSuffix = failingSuffix;
            this.competingContent = competingContent == null ? null : competingContent.clone();
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public boolean isRegularFile(Path path) {
            return delegate.isRegularFile(path);
        }

        @Override
        public byte[] readAllBytes(Path path) throws IOException {
            return delegate.readAllBytes(path);
        }

        @Override
        public String sha256(Path path) throws IOException {
            return delegate.sha256(path);
        }

        @Override
        public void createDirectories(Path path) throws IOException {
            delegate.createDirectories(path);
        }

        @Override
        public void writeNew(Path path, byte[] content) throws IOException {
            delegate.writeNew(path, content);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            String portableSource = source.toString().replace(File.separatorChar, '/');
            if (!failed && portableSource.endsWith(failingSuffix)) {
                failed = true;
                throw new IOException("Injected install failure for " + failingSuffix);
            }
            delegate.move(source, target);
        }

        @Override
        public void moveNew(Path source, Path target) throws IOException {
            String portableSource = source.toString().replace(File.separatorChar, '/');
            if (!failed && competingContent != null && portableSource.endsWith(failingSuffix)) {
                failed = true;
                delegate.createDirectories(target.getParent());
                delegate.writeNew(target, competingContent);
                delegate.moveNew(source, target);
                return;
            }
            StructureFileOperations.super.moveNew(source, target);
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            delegate.deleteIfExists(path);
        }

        @Override
        public void deleteTree(Path root) throws IOException {
            delegate.deleteTree(root);
        }
    }
}

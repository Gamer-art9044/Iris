package art.arcane.iris.core.lifecycle;

import art.arcane.iris.core.SnapshotDirectoryTreeDeleter;
import art.arcane.iris.core.ExactWorldSlotPathPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class WorldReplacementFilesystem {
    private static final Pattern STAGE_NAME = Pattern.compile(
            "^\\.iris-replace-[a-z0-9_-]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.stage$");
    private static final Pattern BACKUP_NAME = Pattern.compile(
            "^\\.iris-replace-[a-z0-9_-]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.backup$");

    private WorldReplacementFilesystem() {
    }

    public static ReplacementPaths paths(ExactWorldSlotPathPolicy.Target target, UUID id) {
        ExactWorldSlotPathPolicy.Target requiredTarget = Objects.requireNonNull(target, "target");
        UUID requiredId = Objects.requireNonNull(id, "id");
        String artifactBase = ".iris-replace-" + requiredTarget.worldKey().getKey() + "-" + requiredId;
        return new ReplacementPaths(
                requiredTarget.worldDirectory(),
                requiredTarget.namespaceRoot().resolve(artifactBase + ".stage"),
                requiredTarget.namespaceRoot().resolve(artifactBase + ".backup")
        );
    }

    public static void publish(
            ReplacementPaths paths,
            boolean originalTargetPresent,
            String expectedPackFingerprint
    ) throws IOException {
        ReplacementPaths requiredPaths = Objects.requireNonNull(paths, "paths");
        String expectedFingerprint = requireFingerprint(expectedPackFingerprint);
        State state = inspect(requiredPaths);
        if (state.stagePresent()) {
            requireSafeTree(requiredPaths.stage(), "replacement stage");
            requireFingerprint(requiredPaths.stage().resolve("iris/pack"), expectedFingerprint);
            if (state.targetPresent()) {
                if (state.backupPresent()) {
                    throw new IOException("Replacement target, stage, and backup are all present.");
                }
                if (!originalTargetPresent) {
                    throw new IOException("Replacement target appeared after an absent target was staged.");
                }
                move(requiredPaths.target(), requiredPaths.backup());
                state = inspect(requiredPaths);
            }
            if (state.targetPresent()) {
                throw new IOException("Replacement target is still present after backup publication.");
            }
            if (originalTargetPresent != state.backupPresent()) {
                throw new IOException("Replacement backup state does not match the original target state.");
            }
            requireSafeTree(requiredPaths.stage(), "replacement stage");
            requireFingerprint(requiredPaths.stage().resolve("iris/pack"), expectedFingerprint);
            move(requiredPaths.stage(), requiredPaths.target());
            state = inspect(requiredPaths);
        }

        if (!state.targetPresent() || state.stagePresent()) {
            throw new IOException("Replacement publication did not produce one exact target directory.");
        }
        if (originalTargetPresent != state.backupPresent()) {
            throw new IOException("Replacement publication lost or unexpectedly created its backup.");
        }
        requireSafeTree(requiredPaths.target(), "replacement target");
        requireFingerprint(requiredPaths.target().resolve("iris/pack"), expectedFingerprint);
    }

    public static void rollback(ReplacementPaths paths, boolean originalTargetPresent) throws IOException {
        prepareRollback(paths, originalTargetPresent);
        discardStage(paths);
    }

    public static void prepareRollback(ReplacementPaths paths, boolean originalTargetPresent) throws IOException {
        ReplacementPaths requiredPaths = Objects.requireNonNull(paths, "paths");
        State state = inspect(requiredPaths);
        if (originalTargetPresent) {
            if (state.backupPresent()) {
                if (state.targetPresent()) {
                    if (state.stagePresent()) {
                        throw new IOException("Rollback cannot quarantine two replacement directories.");
                    }
                    move(requiredPaths.target(), requiredPaths.stage());
                }
                move(requiredPaths.backup(), requiredPaths.target());
                state = inspect(requiredPaths);
            }
            if (!state.targetPresent() || !state.stagePresent() || state.backupPresent()) {
                throw new IOException("Rollback could not restore the original world target.");
            }
        } else {
            if (state.backupPresent()) {
                throw new IOException("An originally absent world acquired an unexpected backup.");
            }
            if (state.targetPresent()) {
                if (state.stagePresent()) {
                    throw new IOException("Rollback cannot quarantine two replacement directories.");
                }
                move(requiredPaths.target(), requiredPaths.stage());
                state = inspect(requiredPaths);
            }
            if (state.targetPresent()) {
                throw new IOException("Rollback could not remove the replacement target.");
            }
            if (!state.stagePresent()) {
                throw new IOException("Rollback lost the quarantined replacement target.");
            }
        }
    }

    public static void finishPreparedRollback(ReplacementPaths paths, boolean originalTargetPresent)
            throws IOException {
        ReplacementPaths requiredPaths = Objects.requireNonNull(paths, "paths");
        State state = inspect(requiredPaths);
        if (state.backupPresent()) {
            throw new IOException("Rollback cleanup cannot continue while a retained backup is still published.");
        }
        if (originalTargetPresent != state.targetPresent()) {
            throw new IOException("Rollback cleanup target state does not match the retained world state.");
        }
        discardStage(requiredPaths);
    }

    public static void discardStage(ReplacementPaths paths) throws IOException {
        ReplacementPaths requiredPaths = Objects.requireNonNull(paths, "paths");
        State state = inspect(requiredPaths);
        if (state.backupPresent()) {
            throw new IOException("Cannot discard a replacement stage after a backup was published.");
        }
        if (state.stagePresent()) {
            SnapshotDirectoryTreeDeleter.delete(requiredPaths.stage());
        }
    }

    public static void cleanupBackup(ReplacementPaths paths) throws IOException {
        ReplacementPaths requiredPaths = Objects.requireNonNull(paths, "paths");
        State state = inspect(requiredPaths);
        if (!state.targetPresent() || state.stagePresent()) {
            throw new IOException("Cannot clean a replacement backup before publication is complete.");
        }
        if (state.backupPresent()) {
            SnapshotDirectoryTreeDeleter.delete(requiredPaths.backup());
        }
    }

    public static void validateCommittedTarget(ReplacementPaths paths, String expectedPackFingerprint)
            throws IOException {
        ReplacementPaths requiredPaths = Objects.requireNonNull(paths, "paths");
        String expectedFingerprint = requireFingerprint(expectedPackFingerprint);
        State state = inspect(requiredPaths);
        if (!state.targetPresent() || state.stagePresent()) {
            throw new IOException("Committed replacement storage does not contain one exact target directory.");
        }
        requireSafeTree(requiredPaths.target(), "replacement target");
        requireFingerprint(requiredPaths.target().resolve("iris/pack"), expectedFingerprint);
    }

    public static String fingerprintPack(Path packRoot) throws IOException {
        Path root = Objects.requireNonNull(packRoot, "packRoot").toAbsolutePath().normalize();
        requireDirectory(root, "pack root");
        MessageDigest digest = sha256();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream
                    .filter(path -> !path.equals(root))
                    .filter(path -> !containsMetadataSegment(root.relativize(path)))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        for (Path file : files) {
            BasicFileAttributes attributes = requireSafeEntry(file);
            Path relative = root.relativize(file);
            update(digest, relative.toString().replace(file.getFileSystem().getSeparator(), "/"));
            digest.update((byte) (attributes.isDirectory() ? 1 : 0));
            if (!attributes.isRegularFile()) {
                continue;
            }
            update(digest, Long.toString(attributes.size()));
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static State inspect(ReplacementPaths paths) throws IOException {
        return new State(
                directoryPresent(paths.target(), "replacement target"),
                directoryPresent(paths.stage(), "replacement stage"),
                directoryPresent(paths.backup(), "replacement backup")
        );
    }

    private static boolean directoryPresent(Path path, String label) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        requireDirectory(path, label);
        return true;
    }

    private static void requireFingerprint(Path packRoot, String expected) throws IOException {
        String actual = fingerprintPack(packRoot);
        if (!actual.equals(expected)) {
            throw new IOException("Staged world pack fingerprint changed before publication.");
        }
    }

    private static String requireFingerprint(String value) {
        String fingerprint = Objects.requireNonNull(value, "expectedPackFingerprint");
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Pack fingerprint must be lowercase SHA-256.");
        }
        return fingerprint;
    }

    private static boolean containsMetadataSegment(Path relative) {
        for (Path component : relative) {
            if (".iris".equals(component.toString())) {
                return true;
            }
        }
        return false;
    }

    private static BasicFileAttributes requireSafeEntry(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink()) {
            throw new IOException("Replacement storage contains a symbolic link: " + path);
        }
        if (!attributes.isDirectory() && !attributes.isRegularFile()) {
            throw new IOException("Replacement storage contains an unsafe entry: " + path);
        }
        return attributes;
    }

    private static void requireDirectory(Path path, String label) throws IOException {
        BasicFileAttributes attributes = requireSafeEntry(path);
        if (!attributes.isDirectory()) {
            throw new IOException("The " + label + " is not a directory: " + path);
        }
    }

    private static void requireSafeTree(Path root, String label) throws IOException {
        requireDirectory(root, label);
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(root)) {
            entries = stream.sorted().toList();
        }
        for (Path entry : entries) {
            requireSafeEntry(entry);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
        try (FileChannel channel = FileChannel.open(source.getParent(), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public record ReplacementPaths(Path target, Path stage, Path backup) {
        public ReplacementPaths {
            target = normalize(target, "target");
            stage = normalize(stage, "stage");
            backup = normalize(backup, "backup");
            Path parent = target.getParent();
            if (parent == null || !parent.equals(stage.getParent()) || !parent.equals(backup.getParent())) {
                throw new IllegalArgumentException("Replacement paths must have one exact parent.");
            }
            if (target.equals(stage) || target.equals(backup) || stage.equals(backup)) {
                throw new IllegalArgumentException("Replacement paths must be distinct.");
            }
            if (!STAGE_NAME.matcher(stage.getFileName().toString()).matches()
                    || !BACKUP_NAME.matcher(backup.getFileName().toString()).matches()) {
                throw new IllegalArgumentException("Replacement artifact names are invalid.");
            }
            String stageStem = stage.getFileName().toString().replaceFirst("\\.stage$", "");
            String backupStem = backup.getFileName().toString().replaceFirst("\\.backup$", "");
            if (!stageStem.equals(backupStem)) {
                throw new IllegalArgumentException("Replacement stage and backup do not belong to one transaction.");
            }
        }

        private static Path normalize(Path path, String label) {
            Path required = Objects.requireNonNull(path, label).toAbsolutePath();
            for (Path component : required) {
                if ("..".equals(component.toString())) {
                    throw new IllegalArgumentException("Replacement " + label + " contains path traversal.");
                }
            }
            return required.normalize();
        }
    }

    private record State(boolean targetPresent, boolean stagePresent, boolean backupPresent) {
    }
}

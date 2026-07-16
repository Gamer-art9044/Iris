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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public final class StructureTransactionWriter {
    private static final String STAGING_RELATIVE_PATH = ".iris/structure-staging";
    private static final String PROCESS_LOCK_RELATIVE_PATH = ".iris/structure-authoring.lock";
    private static final ConcurrentMap<Path, ReentrantLock> ROOT_LOCKS = new ConcurrentHashMap<>();

    private final Path packRoot;
    private final StructureFileOperations files;
    private final ReentrantLock rootLock;

    public StructureTransactionWriter(Path packRoot) {
        this(packRoot, new NioStructureFileOperations());
    }

    StructureTransactionWriter(Path packRoot, StructureFileOperations files) {
        this.packRoot = canonicalPackRoot(Objects.requireNonNull(packRoot, "packRoot"));
        this.files = Objects.requireNonNull(files, "files");
        rootLock = ROOT_LOCKS.computeIfAbsent(this.packRoot, ignored -> new ReentrantLock());
    }

    public Path packRoot() {
        return packRoot;
    }

    public Path ownershipManifestPath(StructureKey key) {
        Objects.requireNonNull(key, "key");
        return resolveTarget(StructureOwnershipManifest.relativePath(key));
    }

    public StructureRecoveryResult recoverIncompleteTransactions() {
        rootLock.lock();
        try (ProcessLock ignored = acquireProcessLock()) {
            return recoverIncompleteTransactionsLocked();
        } catch (IOException | RuntimeException e) {
            return new StructureRecoveryResult(
                    0,
                    0,
                    0,
                    List.of(new StructureRecoveryResult.Failure(packRoot, e))
            );
        } finally {
            rootLock.unlock();
        }
    }

    public StructureWriteResult write(StructureResourceBundle bundle, StructureWriteMode mode) {
        return write(bundle, new StructureWriteOptions(mode, false));
    }

    public StructureWriteResult preview(StructureResourceBundle bundle, StructureWriteMode mode) {
        return write(bundle, StructureWriteOptions.preview(mode));
    }

    public StructureWriteResult write(StructureResourceBundle bundle, StructureWriteOptions options) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(options, "options");
        rootLock.lock();
        try {
            if (options.dryRun()) {
                return writeLocked(bundle, options);
            }
            try (ProcessLock ignored = acquireProcessLock()) {
                return writeLocked(bundle, options);
            }
        } catch (IOException | RuntimeException e) {
            return failedResult(bundle, e);
        } finally {
            rootLock.unlock();
        }
    }

    private StructureWriteResult writeLocked(StructureResourceBundle bundle, StructureWriteOptions options)
            throws IOException {
        if (!options.dryRun()) {
            StructureRecoveryResult recovery = recoverIncompleteTransactionsLocked();
            if (!recovery.successful()) {
                return failedResult(bundle, recoveryFailure(recovery));
            }
        }
        WritePlan plan = buildPlan(bundle, options.mode());
        if (!plan.conflicts().isEmpty()) {
            StructureWriteResult.Status status = options.mode() == StructureWriteMode.ADD_ONLY
                    ? StructureWriteResult.Status.ADD_ONLY_CONFLICT
                    : StructureWriteResult.Status.OWNERSHIP_CONFLICT;
            return result(status, plan, Optional.empty());
        }
        if (options.dryRun()) {
            return result(StructureWriteResult.Status.DRY_RUN, plan, Optional.empty());
        }
        if (plan.action() == StructureWriteResult.Action.NONE) {
            return result(StructureWriteResult.Status.UNCHANGED, plan, Optional.empty());
        }
        return commit(plan);
    }

    private StructureRecoveryResult recoverIncompleteTransactionsLocked() {
        Path stagingRoot = stagingRoot();
        ArrayList<StructureRecoveryResult.Failure> failures = new ArrayList<>();
        int restoredPrepared = 0;
        int cleanedCommitted = 0;
        int cleanedOrphans = 0;
        if (!files.exists(stagingRoot)) {
            return new StructureRecoveryResult(0, 0, 0, List.of());
        }
        if (!files.isDirectory(stagingRoot)) {
            IOException failure = new IOException("Structure staging root is not a directory: " + stagingRoot);
            return new StructureRecoveryResult(
                    0,
                    0,
                    0,
                    List.of(new StructureRecoveryResult.Failure(stagingRoot, failure))
            );
        }

        List<Path> transactionRoots;
        try {
            transactionRoots = files.list(stagingRoot);
        } catch (IOException | RuntimeException e) {
            return new StructureRecoveryResult(
                    0,
                    0,
                    0,
                    List.of(new StructureRecoveryResult.Failure(stagingRoot, e))
            );
        }

        for (Path transactionRoot : transactionRoots) {
            try {
                RecoveryOutcome outcome = recoverTransaction(transactionRoot);
                switch (outcome) {
                    case RESTORED_PREPARED -> restoredPrepared++;
                    case CLEANED_COMMITTED -> cleanedCommitted++;
                    case CLEANED_ORPHAN -> cleanedOrphans++;
                }
            } catch (IOException | RuntimeException e) {
                failures.add(new StructureRecoveryResult.Failure(transactionRoot, e));
            }
        }
        return new StructureRecoveryResult(
                restoredPrepared,
                cleanedCommitted,
                cleanedOrphans,
                failures
        );
    }

    private RecoveryOutcome recoverTransaction(Path transactionRoot) throws IOException {
        Path normalizedRoot = transactionRoot.toAbsolutePath().normalize();
        Path stagingRoot = stagingRoot();
        if (!Objects.equals(normalizedRoot.getParent(), stagingRoot)) {
            throw new IOException("Structure transaction is not a direct child of " + stagingRoot);
        }
        rejectSymbolicLinks(stagingRoot, normalizedRoot);
        if (!files.isDirectory(normalizedRoot)) {
            files.deleteTree(normalizedRoot);
            files.forceDirectory(stagingRoot);
            return RecoveryOutcome.CLEANED_ORPHAN;
        }

        Path journalPath = recoveryJournalPath(normalizedRoot);
        if (journalPath == null) {
            if (hasRecoveryData(normalizedRoot.resolve("backup"))) {
                throw new IOException("Structure transaction has backups but no recovery journal: "
                        + normalizedRoot);
            }
            cleanupTransaction(normalizedRoot);
            return RecoveryOutcome.CLEANED_ORPHAN;
        }
        if (!files.isRegularFile(journalPath)) {
            throw new IOException("Structure transaction journal is not a regular file: " + journalPath);
        }

        StructureTransactionJournal journal;
        try {
            journal = StructureTransactionJournal.fromJson(files.readAllBytes(journalPath));
        } catch (RuntimeException e) {
            throw new IOException("Invalid structure transaction journal at " + journalPath, e);
        }
        String directoryName = Objects.requireNonNull(normalizedRoot.getFileName(), "transaction directory name")
                .toString();
        if (!journal.transactionId().toString().equals(directoryName)) {
            throw new IOException("Structure transaction journal id " + journal.transactionId()
                    + " does not match directory " + directoryName);
        }

        return switch (journal.phase()) {
            case PREPARED -> {
                restorePreparedTransaction(normalizedRoot, journal);
                cleanupTransaction(normalizedRoot);
                yield RecoveryOutcome.RESTORED_PREPARED;
            }
            case COMMITTED -> {
                verifyCommittedTransaction(journal);
                cleanupTransaction(normalizedRoot);
                yield RecoveryOutcome.CLEANED_COMMITTED;
            }
        };
    }

    private Path recoveryJournalPath(Path transactionRoot) throws IOException {
        Path journalPath = transactionRoot.resolve(StructureTransactionJournal.FILE_NAME);
        if (files.exists(journalPath)) {
            return journalPath;
        }
        Path nextJournalPath = transactionRoot.resolve(StructureTransactionJournal.NEXT_FILE_NAME);
        if (!files.exists(nextJournalPath)) {
            return null;
        }
        if (!files.isRegularFile(nextJournalPath)) {
            throw new IOException("Structure transaction next journal is not a regular file: "
                    + nextJournalPath);
        }
        return nextJournalPath;
    }

    private void verifyCommittedTransaction(StructureTransactionJournal journal) throws IOException {
        for (StructureTransactionJournal.Target state : journal.targets()) {
            Path target = resolveTarget(state.relativePath());
            if (state.replacementHash().isEmpty()) {
                if (files.exists(target)) {
                    throw new IOException("Committed structure transaction retained a removed target: "
                            + state.relativePath());
                }
                continue;
            }
            if (!files.isRegularFile(target)) {
                throw new IOException("Committed structure transaction target is missing or is not a regular file: "
                        + state.relativePath());
            }
            String actualHash = files.sha256(target);
            if (!state.replacementHash().equals(actualHash)) {
                throw new IOException("Committed structure transaction target hash mismatch for "
                        + state.relativePath());
            }
        }
    }

    private void restorePreparedTransaction(
            Path transactionRoot,
            StructureTransactionJournal journal
    ) throws IOException {
        Throwable recoveryFailure = null;
        ArrayList<StructureTransactionJournal.Target> targets = new ArrayList<>(journal.targets());
        Collections.reverse(targets);
        for (StructureTransactionJournal.Target state : targets) {
            try {
                restorePreparedTarget(transactionRoot, state);
            } catch (IOException | RuntimeException e) {
                recoveryFailure = appendFailure(recoveryFailure, e);
            }
        }
        if (recoveryFailure != null) {
            throw new IOException("Unable to restore prepared structure transaction " + transactionRoot,
                    recoveryFailure);
        }
    }

    private void restorePreparedTarget(
            Path transactionRoot,
            StructureTransactionJournal.Target state
    ) throws IOException {
        Path target = resolveTarget(state.relativePath());
        Path backupRoot = transactionRoot.resolve("backup").normalize();
        Path backup = resolveTransactionPath(backupRoot, state.relativePath());
        Path targetParent = Objects.requireNonNull(target.getParent(), "recovery target parent");
        if (!state.hadOriginal()) {
            removeReplacementIfPresent(target, state);
            return;
        }
        if (!files.exists(backup)) {
            verifyOriginalTarget(target, state);
            files.forceFile(target);
            files.forceDirectory(targetParent);
            return;
        }
        if (!files.isRegularFile(backup)) {
            throw new IOException("Structure transaction backup is not a regular file: " + backup);
        }
        verifyOriginalContent(backup, state);
        if (files.isRegularFile(target) && state.originalHash().equals(files.sha256(target))) {
            files.deleteIfExists(backup);
            files.forceFile(target);
            files.forceDirectory(targetParent);
            files.forceDirectory(Objects.requireNonNull(backup.getParent(), "recovery backup parent"));
            return;
        }
        removeReplacementIfPresent(target, state);
        files.createDirectories(targetParent);
        files.moveNew(backup, target);
        files.forceFile(target);
        files.forceDirectory(targetParent);
        files.forceDirectory(Objects.requireNonNull(backup.getParent(), "recovery backup parent"));
    }

    private boolean hasRecoveryData(Path backupRoot) throws IOException {
        if (!files.exists(backupRoot)) {
            return false;
        }
        if (!files.isDirectory(backupRoot)) {
            return true;
        }
        for (Path child : files.list(backupRoot)) {
            if (!files.isDirectory(child) || hasRecoveryData(child)) {
                return true;
            }
        }
        return false;
    }

    private IOException recoveryFailure(StructureRecoveryResult recovery) {
        IOException failure = new IOException("Unable to recover " + recovery.failures().size()
                + " incomplete structure transaction(s) under " + stagingRoot());
        for (StructureRecoveryResult.Failure recoveryFailure : recovery.failures()) {
            failure.addSuppressed(recoveryFailure.cause());
        }
        return failure;
    }

    private WritePlan buildPlan(StructureResourceBundle bundle, StructureWriteMode mode) throws IOException {
        StructureOwnershipManifest nextManifest = StructureOwnershipManifest.from(bundle);
        String manifestRelativePath = nextManifest.relativePath();
        Path manifestPath = resolveTarget(manifestRelativePath);
        ArrayList<StructureWriteResult.Conflict> conflicts = new ArrayList<>();
        TreeMap<String, String> previousResourceHashes = new TreeMap<>();

        if (mode == StructureWriteMode.ADD_ONLY) {
            if (files.exists(manifestPath)) {
                conflicts.add(StructureWriteResult.Conflict.at(
                        manifestRelativePath,
                        StructureWriteResult.ConflictReason.MANIFEST_EXISTS
                ));
            }
            findAddOnlyConflicts(bundle, conflicts);
            return createPlan(
                    bundle,
                    nextManifest,
                    previousResourceHashes,
                    StructureWriteResult.Action.ADD,
                    conflicts
            );
        }

        if (!files.exists(manifestPath)) {
            findUnownedResources(bundle, previousResourceHashes, conflicts);
            return createPlan(
                    bundle,
                    nextManifest,
                    previousResourceHashes,
                    StructureWriteResult.Action.ADD,
                    conflicts
            );
        }

        if (!files.isRegularFile(manifestPath)) {
            conflicts.add(StructureWriteResult.Conflict.invalidManifest(
                    manifestRelativePath,
                    "Ownership manifest is not a regular file"
            ));
            return createPlan(
                    bundle,
                    nextManifest,
                    previousResourceHashes,
                    StructureWriteResult.Action.OVERWRITE,
                    conflicts
            );
        }

        StructureOwnershipManifest previousManifest;
        try {
            previousManifest = StructureOwnershipManifest.fromJson(files.readAllBytes(manifestPath));
        } catch (RuntimeException e) {
            conflicts.add(StructureWriteResult.Conflict.invalidManifest(manifestRelativePath, e.toString()));
            return createPlan(
                    bundle,
                    nextManifest,
                    previousResourceHashes,
                    StructureWriteResult.Action.OVERWRITE,
                    conflicts
            );
        }

        if (!previousManifest.structure().equals(bundle.key())) {
            conflicts.add(StructureWriteResult.Conflict.invalidManifest(
                    manifestRelativePath,
                    "Ownership manifest belongs to " + previousManifest.structure()
            ));
            return createPlan(
                    bundle,
                    nextManifest,
                    previousResourceHashes,
                    StructureWriteResult.Action.OVERWRITE,
                    conflicts
            );
        }

        previousResourceHashes.putAll(previousManifest.resourceHashes());
        verifyOwnedResources(previousResourceHashes, conflicts);
        findUnownedResources(bundle, previousResourceHashes, conflicts);
        StructureWriteResult.Action action = nextManifest.equals(previousManifest) && conflicts.isEmpty()
                ? StructureWriteResult.Action.NONE
                : StructureWriteResult.Action.OVERWRITE;
        return createPlan(bundle, nextManifest, previousResourceHashes, action, conflicts);
    }

    private void findAddOnlyConflicts(
            StructureResourceBundle bundle,
            List<StructureWriteResult.Conflict> conflicts
    ) {
        for (String relativePath : bundle.resources().keySet()) {
            if (files.exists(resolveTarget(relativePath))) {
                conflicts.add(StructureWriteResult.Conflict.at(
                        relativePath,
                        StructureWriteResult.ConflictReason.RESOURCE_EXISTS
                ));
            }
        }
    }

    private void findUnownedResources(
            StructureResourceBundle bundle,
            Map<String, String> previousResourceHashes,
            List<StructureWriteResult.Conflict> conflicts
    ) {
        for (String relativePath : bundle.resources().keySet()) {
            if (!previousResourceHashes.containsKey(relativePath) && files.exists(resolveTarget(relativePath))) {
                conflicts.add(StructureWriteResult.Conflict.at(
                        relativePath,
                        StructureWriteResult.ConflictReason.UNOWNED_RESOURCE
                ));
            }
        }
    }

    private void verifyOwnedResources(
            Map<String, String> previousResourceHashes,
            List<StructureWriteResult.Conflict> conflicts
    ) throws IOException {
        for (Map.Entry<String, String> entry : previousResourceHashes.entrySet()) {
            String relativePath = entry.getKey();
            Path target = resolveTarget(relativePath);
            if (!files.exists(target)) {
                conflicts.add(StructureWriteResult.Conflict.at(
                        relativePath,
                        StructureWriteResult.ConflictReason.MISSING_OWNED_RESOURCE
                ));
                continue;
            }
            if (!files.isRegularFile(target)) {
                conflicts.add(StructureWriteResult.Conflict.at(
                        relativePath,
                        StructureWriteResult.ConflictReason.NON_FILE_RESOURCE
                ));
                continue;
            }
            String actualHash = files.sha256(target);
            if (!entry.getValue().equals(actualHash)) {
                conflicts.add(StructureWriteResult.Conflict.modified(relativePath, entry.getValue(), actualHash));
            }
        }
    }

    private WritePlan createPlan(
            StructureResourceBundle bundle,
            StructureOwnershipManifest nextManifest,
            Map<String, String> previousResourceHashes,
            StructureWriteResult.Action action,
            List<StructureWriteResult.Conflict> conflicts
    ) {
        TreeSet<String> affectedResources = new TreeSet<>(previousResourceHashes.keySet());
        affectedResources.addAll(bundle.resources().keySet());
        affectedResources.add(nextManifest.relativePath());
        ArrayList<StructureWriteResult.Conflict> orderedConflicts = new ArrayList<>(conflicts);
        orderedConflicts.sort((left, right) -> left.relativePath().compareTo(right.relativePath()));
        return new WritePlan(
                bundle,
                nextManifest,
                nextManifest.toJson(),
                action,
                List.copyOf(orderedConflicts),
                List.copyOf(affectedResources)
        );
    }

    private StructureWriteResult commit(WritePlan plan) {
        UUID transactionId = UUID.randomUUID();
        Path transactionRoot = stagingRoot().resolve(transactionId.toString()).normalize();
        Path stagedRoot = transactionRoot.resolve("staged");
        Path backupRoot = transactionRoot.resolve("backup");
        Path stagedManifest = stagedRoot.resolve("ownership-manifest.json");
        LinkedHashMap<Path, Path> backups = new LinkedHashMap<>();
        ArrayList<InstalledTarget> installedTargets = new ArrayList<>();
        StructureTransactionJournal journal;

        try {
            files.createDirectories(stagedRoot);
            files.createDirectories(backupRoot);
            stageResources(plan, stagedRoot, stagedManifest);
            journal = createJournal(plan, transactionId);
            writeJournal(transactionRoot, journal);
            files.forceDirectory(transactionRoot);
            files.forceDirectory(stagingRoot());
            verifyTargetSnapshot(journal);
            backupTargets(journal, backupRoot, backups);
            installResources(plan, stagedRoot, stagedManifest, installedTargets);
        } catch (IOException | RuntimeException commitFailure) {
            return rollbackResult(plan, transactionRoot, backups, installedTargets, commitFailure);
        }

        boolean committedJournalWritten = false;
        try {
            writeJournal(transactionRoot, journal.committed());
            committedJournalWritten = true;
            files.forceDirectory(transactionRoot);
        } catch (IOException | RuntimeException commitPhaseFailure) {
            if (committedJournalWritten || isCommittedJournal(transactionRoot, commitPhaseFailure)) {
                return result(
                        StructureWriteResult.Status.COMMITTED_CLEANUP_REQUIRED,
                        plan,
                        Optional.of(commitPhaseFailure)
                );
            }
            return rollbackResult(plan, transactionRoot, backups, installedTargets, commitPhaseFailure);
        }

        try {
            cleanupTransaction(transactionRoot);
        } catch (IOException | RuntimeException cleanupFailure) {
            return result(
                    StructureWriteResult.Status.COMMITTED_CLEANUP_REQUIRED,
                    plan,
                    Optional.of(cleanupFailure)
            );
        }

        StructureWriteResult.Status status = plan.action() == StructureWriteResult.Action.ADD
                ? StructureWriteResult.Status.ADDED
                : StructureWriteResult.Status.OVERWRITTEN;
        return result(status, plan, Optional.empty());
    }

    private void stageResources(WritePlan plan, Path stagedRoot, Path stagedManifest) throws IOException {
        for (StructureResourceBundle.Resource resource : plan.bundle().resources().values()) {
            Path staged = stagedRoot.resolve(resource.relativePath()).normalize();
            files.createDirectories(Objects.requireNonNull(staged.getParent(), "staged parent"));
            files.writeNew(staged, resource.contentForWrite());
            files.forceFile(staged);
        }
        files.createDirectories(Objects.requireNonNull(stagedManifest.getParent(), "manifest parent"));
        files.writeNew(stagedManifest, plan.nextManifestContent());
        files.forceFile(stagedManifest);
    }

    private StructureTransactionJournal createJournal(WritePlan plan, UUID transactionId) throws IOException {
        ArrayList<StructureTransactionJournal.Target> targets = new ArrayList<>(plan.affectedResources().size());
        for (String relativePath : plan.affectedResources()) {
            Path target = resolveTarget(relativePath);
            boolean hadOriginal = files.exists(target);
            targets.add(new StructureTransactionJournal.Target(
                    relativePath,
                    hadOriginal,
                    hadOriginal ? files.sha256(target) : "",
                    replacementHash(plan, relativePath)
            ));
        }
        return StructureTransactionJournal.prepared(transactionId, targets);
    }

    private void writeJournal(
            Path transactionRoot,
            StructureTransactionJournal journal
    ) throws IOException {
        Path journalPath = transactionRoot.resolve(StructureTransactionJournal.FILE_NAME);
        Path nextJournalPath = transactionRoot.resolve(StructureTransactionJournal.NEXT_FILE_NAME);
        files.deleteIfExists(nextJournalPath);
        files.writeNew(nextJournalPath, journal.toJson());
        files.forceFile(nextJournalPath);
        files.move(nextJournalPath, journalPath);
    }

    private boolean isCommittedJournal(Path transactionRoot, Throwable commitPhaseFailure) {
        Path journalPath = transactionRoot.resolve(StructureTransactionJournal.FILE_NAME);
        if (!files.isRegularFile(journalPath)) {
            return false;
        }
        try {
            return StructureTransactionJournal.fromJson(files.readAllBytes(journalPath)).phase()
                    == StructureTransactionJournal.Phase.COMMITTED;
        } catch (IOException | RuntimeException e) {
            commitPhaseFailure.addSuppressed(e);
            return false;
        }
    }

    private void verifyTargetSnapshot(StructureTransactionJournal journal) throws IOException {
        for (StructureTransactionJournal.Target state : journal.targets()) {
            Path target = resolveTarget(state.relativePath());
            boolean exists = files.exists(target);
            if (exists != state.hadOriginal()) {
                throw new IOException("Structure transaction target changed during preparation: "
                        + state.relativePath());
            }
            if (state.hadOriginal()) {
                verifyOriginalTarget(target, state);
            }
        }
    }

    private void backupTargets(
            StructureTransactionJournal journal,
            Path backupRoot,
            Map<Path, Path> backups
    ) throws IOException {
        for (StructureTransactionJournal.Target state : journal.targets()) {
            if (!state.hadOriginal()) {
                continue;
            }
            Path target = resolveTarget(state.relativePath());
            Path backup = resolveTransactionPath(backupRoot, state.relativePath());
            files.createDirectories(Objects.requireNonNull(backup.getParent(), "backup parent"));
            backups.put(target, backup);
            files.moveNew(target, backup);
            verifyOriginalContent(backup, state);
            files.forceDirectory(Objects.requireNonNull(target.getParent(), "backup target parent"));
            files.forceDirectory(Objects.requireNonNull(backup.getParent(), "backup parent"));
        }
    }

    private void installResources(
            WritePlan plan,
            Path stagedRoot,
            Path stagedManifest,
            List<InstalledTarget> installedTargets
    ) throws IOException {
        for (StructureResourceBundle.Resource resource : plan.bundle().resources().values()) {
            Path source = stagedRoot.resolve(resource.relativePath()).normalize();
            Path target = resolveTarget(resource.relativePath());
            files.createDirectories(Objects.requireNonNull(target.getParent(), "target parent"));
            files.moveNew(source, target);
            installedTargets.add(new InstalledTarget(target, resource.contentHash()));
            files.forceFile(target);
            files.forceDirectory(Objects.requireNonNull(target.getParent(), "target parent"));
        }
        Path manifestTarget = resolveTarget(plan.nextManifest().relativePath());
        files.createDirectories(Objects.requireNonNull(manifestTarget.getParent(), "manifest target parent"));
        files.moveNew(stagedManifest, manifestTarget);
        installedTargets.add(new InstalledTarget(
                manifestTarget,
                StructureHash.sha256(plan.nextManifestContent())
        ));
        files.forceFile(manifestTarget);
        files.forceDirectory(Objects.requireNonNull(manifestTarget.getParent(), "manifest target parent"));
    }

    private StructureWriteResult rollbackResult(
            WritePlan plan,
            Path transactionRoot,
            Map<Path, Path> backups,
            List<InstalledTarget> installedTargets,
            Throwable commitFailure
    ) {
        Optional<Throwable> rollbackFailure = rollback(backups, installedTargets);
        if (rollbackFailure.isPresent()) {
            IOException retainedRecovery = new IOException(
                    "Transaction recovery data retained at " + transactionRoot,
                    rollbackFailure.get()
            );
            commitFailure.addSuppressed(retainedRecovery);
            return result(StructureWriteResult.Status.FAILED, plan, Optional.of(commitFailure));
        }
        cleanupAfterFailure(transactionRoot, commitFailure);
        return result(StructureWriteResult.Status.ROLLED_BACK, plan, Optional.of(commitFailure));
    }

    private Optional<Throwable> rollback(Map<Path, Path> backups, List<InstalledTarget> installedTargets) {
        Throwable rollbackFailure = null;
        ArrayList<InstalledTarget> reversedTargets = new ArrayList<>(installedTargets);
        Collections.reverse(reversedTargets);
        for (InstalledTarget installedTarget : reversedTargets) {
            try {
                removeInstalledTarget(installedTarget);
            } catch (IOException | RuntimeException e) {
                rollbackFailure = appendFailure(rollbackFailure, e);
            }
        }

        ArrayList<Map.Entry<Path, Path>> reversedBackups = new ArrayList<>(backups.entrySet());
        Collections.reverse(reversedBackups);
        for (Map.Entry<Path, Path> entry : reversedBackups) {
            Path target = entry.getKey();
            Path backup = entry.getValue();
            if (!files.exists(backup)) {
                continue;
            }
            try {
                files.createDirectories(Objects.requireNonNull(target.getParent(), "rollback target parent"));
                files.moveNew(backup, target);
                files.forceFile(target);
                files.forceDirectory(Objects.requireNonNull(target.getParent(), "rollback target parent"));
                files.forceDirectory(Objects.requireNonNull(backup.getParent(), "rollback backup parent"));
            } catch (IOException | RuntimeException e) {
                rollbackFailure = appendFailure(rollbackFailure, e);
            }
        }
        return Optional.ofNullable(rollbackFailure);
    }

    private void cleanupAfterFailure(Path transactionRoot, Throwable failure) {
        try {
            cleanupTransaction(transactionRoot);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void cleanupTransaction(Path transactionRoot) throws IOException {
        files.deleteTree(transactionRoot.resolve("staged"));
        files.deleteTree(transactionRoot.resolve("backup"));
        files.deleteIfExists(transactionRoot.resolve(StructureTransactionJournal.NEXT_FILE_NAME));
        forceExistingDirectory(transactionRoot);
        files.deleteIfExists(transactionRoot.resolve(StructureTransactionJournal.FILE_NAME));
        files.deleteTree(transactionRoot);
        Path stagingRoot = stagingRoot();
        if (files.exists(stagingRoot)) {
            files.forceDirectory(stagingRoot);
        }
    }

    private Throwable appendFailure(Throwable existing, Throwable next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    private void verifyOriginalTarget(
            Path target,
            StructureTransactionJournal.Target state
    ) throws IOException {
        if (!files.isRegularFile(target)) {
            throw new IOException("Structure transaction original target is missing or is not a regular file for "
                    + state.relativePath());
        }
        verifyOriginalContent(target, state);
    }

    private void verifyOriginalContent(
            Path path,
            StructureTransactionJournal.Target state
    ) throws IOException {
        String actualHash = files.sha256(path);
        if (!state.originalHash().equals(actualHash)) {
            throw new IOException("Structure transaction original content hash mismatch for "
                    + state.relativePath());
        }
    }

    private void removeReplacementIfPresent(
            Path target,
            StructureTransactionJournal.Target state
    ) throws IOException {
        if (!files.exists(target)) {
            return;
        }
        if (!files.isRegularFile(target)) {
            throw new IOException("Structure transaction replacement is not a regular file for "
                    + state.relativePath());
        }
        if (state.replacementHash().isEmpty()) {
            throw new IOException("Unexpected replacement appeared while recovering " + state.relativePath());
        }
        String actualHash = files.sha256(target);
        if (!state.replacementHash().equals(actualHash)) {
            throw new IOException("Structure transaction replacement content changed after the interrupted write for "
                    + state.relativePath());
        }
        files.deleteIfExists(target);
        forceExistingDirectory(Objects.requireNonNull(target.getParent(), "replacement target parent"));
    }

    private String replacementHash(WritePlan plan, String relativePath) {
        StructureResourceBundle.Resource resource = plan.bundle().resources().get(relativePath);
        if (resource != null) {
            return resource.contentHash();
        }
        if (plan.nextManifest().relativePath().equals(relativePath)) {
            return StructureHash.sha256(plan.nextManifestContent());
        }
        return "";
    }

    private void forceExistingDirectory(Path path) throws IOException {
        if (files.isDirectory(path)) {
            files.forceDirectory(path);
        }
    }

    private void removeInstalledTarget(InstalledTarget installedTarget) throws IOException {
        Path target = installedTarget.path();
        if (!files.exists(target)) {
            return;
        }
        if (!files.isRegularFile(target) || !installedTarget.contentHash().equals(files.sha256(target))) {
            throw new IOException("Installed structure target changed before rollback: " + target);
        }
        files.deleteIfExists(target);
        forceExistingDirectory(Objects.requireNonNull(target.getParent(), "rollback target parent"));
    }

    private StructureWriteResult result(
            StructureWriteResult.Status status,
            WritePlan plan,
            Optional<Throwable> failure
    ) {
        return new StructureWriteResult(
                status,
                plan.action(),
                plan.conflicts(),
                plan.affectedResources(),
                plan.nextManifest().relativePath(),
                failure
        );
    }

    private StructureWriteResult failedResult(StructureResourceBundle bundle, Throwable failure) {
        StructureOwnershipManifest manifest = StructureOwnershipManifest.from(bundle);
        TreeSet<String> affectedResources = new TreeSet<>(bundle.resources().keySet());
        affectedResources.add(manifest.relativePath());
        return new StructureWriteResult(
                StructureWriteResult.Status.FAILED,
                StructureWriteResult.Action.NONE,
                List.of(),
                List.copyOf(affectedResources),
                manifest.relativePath(),
                Optional.of(failure)
        );
    }

    private Path resolveTarget(String relativePath) {
        return resolveWithin(packRoot, relativePath, "Resource path escapes the pack root: ");
    }

    private Path resolveTransactionPath(Path root, String relativePath) {
        return resolveWithin(root, relativePath, "Transaction path escapes its root: ");
    }

    private Path stagingRoot() {
        return resolveWithin(packRoot, STAGING_RELATIVE_PATH, "Structure staging path escapes the pack root: ");
    }

    private ProcessLock acquireProcessLock() throws IOException {
        Path lockPath = resolveWithin(
                packRoot,
                PROCESS_LOCK_RELATIVE_PATH,
                "Structure authoring lock path escapes the pack root: "
        );
        Files.createDirectories(Objects.requireNonNull(lockPath.getParent(), "structure authoring lock parent"));
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );
        try {
            return new ProcessLock(channel, channel.lock());
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Unable to acquire the structure authoring lock for " + packRoot, e);
        }
    }

    private Path resolveWithin(Path root, String relativePath, String errorPrefix) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException(errorPrefix + relativePath);
        }
        rejectSymbolicLinks(root, target);
        return target;
    }

    private void rejectSymbolicLinks(Path root, Path target) {
        Path current = root;
        Path relative = root.relativize(target);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Structure authoring path contains a symbolic link: " + current);
            }
        }
    }

    private static Path canonicalPackRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve the structure pack root " + normalized, e);
        }
    }

    private enum RecoveryOutcome {
        RESTORED_PREPARED,
        CLEANED_COMMITTED,
        CLEANED_ORPHAN
    }

    private record InstalledTarget(Path path, String contentHash) {
        private InstalledTarget {
            Objects.requireNonNull(path, "path");
            if (!StructureHash.isSha256(contentHash)) {
                throw new IllegalArgumentException("Installed target content hash is invalid");
            }
        }
    }

    private record ProcessLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        private ProcessLock {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(lock, "lock");
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record WritePlan(
            StructureResourceBundle bundle,
            StructureOwnershipManifest nextManifest,
            byte[] nextManifestContent,
            StructureWriteResult.Action action,
            List<StructureWriteResult.Conflict> conflicts,
            List<String> affectedResources
    ) {
        private WritePlan {
            nextManifestContent = nextManifestContent.clone();
        }

        @Override
        public byte[] nextManifestContent() {
            return nextManifestContent.clone();
        }
    }
}

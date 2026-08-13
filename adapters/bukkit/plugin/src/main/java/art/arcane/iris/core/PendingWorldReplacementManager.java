package art.arcane.iris.core;

import art.arcane.iris.Iris;
import art.arcane.iris.core.ExactWorldSlotPathPolicy.SlotKind;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration.GeneratorReplacement;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration.WorldGeneratorSnapshot;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.lifecycle.WorldReplacementBootstrap;
import art.arcane.iris.core.lifecycle.WorldReplacementBootstrapMarker;
import art.arcane.iris.core.lifecycle.WorldReplacementFilesystem;
import art.arcane.iris.core.lifecycle.WorldReplacementFilesystem.ReplacementPaths;
import art.arcane.iris.core.lifecycle.WorldReplacementJournal;
import art.arcane.iris.core.lifecycle.WorldReplacementJournal.Phase;
import art.arcane.iris.core.lifecycle.WorldReplacementJournal.Transaction;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisEnvironment;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.misc.ServerProperties;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PendingWorldReplacementManager implements Listener {
    private final Iris plugin;
    private final Set<UUID> cleanupInFlight = new HashSet<>();

    public PendingWorldReplacementManager(Iris plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public NamespacedKey resolveRequestedWorldKey(String requestedName) {
        String requested = Objects.requireNonNull(requestedName, "requestedName").trim();
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("World name cannot be empty.");
        }
        if (requested.contains("/") || requested.contains("\\") || requested.contains("..")) {
            throw new IllegalArgumentException("World name must be a safe single path segment.");
        }
        NamespacedKey worldKey = requested.contains(":")
                ? NamespacedKey.fromString(requested.toLowerCase(Locale.ENGLISH))
                : IrisWorldStorage.keyFromName(requested);
        if (worldKey == null) {
            throw new IllegalArgumentException("World identifier is invalid: " + requestedName);
        }
        ExactWorldSlotPathPolicy.resolve(IrisWorldStorage.levelRoot().toPath(), toWorldSlotKey(worldKey));
        return worldKey;
    }

    public synchronized StagedReplacement stageReplacement(
            VolmitSender sender,
            NamespacedKey worldKey,
            IrisDimension dimension,
            long seed
    ) throws IOException {
        VolmitSender requiredSender = Objects.requireNonNull(sender, "sender");
        NamespacedKey requiredWorldKey = Objects.requireNonNull(worldKey, "worldKey");
        WorldSlotKey requiredWorldSlotKey = toWorldSlotKey(requiredWorldKey);
        IrisDimension requiredDimension = Objects.requireNonNull(dimension, "dimension");
        IrisStartupValidation.requireWorldCreationReady();
        if (!WorldReplacementBootstrapMarker.wasBootstrappedThisProcess()) {
            throw new IOException("Exact world replacement requires a full Paper-family startup bootstrap.");
        }
        PackValidationRegistry.requireLoadable(requiredDimension.getLoader().getDataFolder().getName());
        LifecycleOperationCoordinator coordinator = LifecycleOperationCoordinator.get();
        try (LifecycleOperationCoordinator.Lease ignored = coordinator.acquire(
                LifecycleOperationCoordinator.Domain.WORLD_MUTATION,
                LifecycleOperationCoordinator.OperationKind.WORLD_REPLACE,
                requiredWorldKey.toString()
        )) {
            if (findTransaction(requiredWorldSlotKey) != null) {
                throw new IOException("A replacement is already pending for " + requiredWorldKey + ".");
            }
            ExactWorldSlotPathPolicy.Target target = prepareTarget(requiredWorldSlotKey);
            requireCompatibleEnvironment(target.slotKind(), requiredDimension.getEnvironment());
            long effectiveSeed = resolveEffectiveSeed(target.slotKind(), seed);
            String worldName = WorldReplacementJournal.logicalWorldName(target.levelRoot(), requiredWorldSlotKey);
            DatapackInstallResult datapacks = ServerConfigurator.installDataPacksIfChanged(true);
            if (!datapacks.succeeded()) {
                throw new IOException("Iris could not compile the dimension datapacks.");
            }

            UUID transactionId = UUID.randomUUID();
            ReplacementPaths paths = WorldReplacementFilesystem.paths(target, transactionId);
            WorldReplacementFilesystem.requireExistingTarget(paths);
            boolean targetPresent = true;
            WorldGeneratorSnapshot originalConfiguration = BukkitWorldConfiguration.snapshot(
                    ServerProperties.BUKKIT_YML,
                    worldName
            );
            Transaction transaction = null;
            boolean journalWritten = false;
            boolean configurationApplied = false;
            try {
                Files.createDirectory(paths.stage());
                IrisDimension installed = Iris.service(StudioSVC.class).installIntoWorld(
                        requiredSender,
                        requiredDimension,
                        paths.stage().toFile()
                );
                if (installed == null) {
                    throw new IOException("Iris could not stage the dimension pack.");
                }
                requireCompatibleEnvironment(target.slotKind(), installed.getEnvironment());
                File stagedPack = paths.stage().resolve("iris/pack").toFile();
                IrisWorldGeneratorResolver.requireSnapshotLoadable(stagedPack);
                String packFingerprint = WorldReplacementFilesystem.fingerprintPack(stagedPack.toPath());
                transaction = new Transaction(
                        transactionId,
                        requiredWorldSlotKey,
                        worldName,
                        target.levelRoot(),
                        installed.getLoadKey(),
                        effectiveSeed,
                        packFingerprint,
                        originalConfiguration,
                        targetPresent,
                        Phase.PREPARED
                );
                writeTransaction(transaction);
                journalWritten = true;
                GeneratorReplacement replacement = BukkitWorldConfiguration.replaceIfMatching(
                        ServerProperties.BUKKIT_YML,
                        worldName,
                        originalConfiguration,
                        installed.getLoadKey(),
                        effectiveSeed
                );
                if (!replacement.applied()) {
                    throw new IOException("bukkit.yml changed while the replacement was being staged.");
                }
                configurationApplied = true;
                transaction = transaction.withPhase(Phase.ARMED);
                writeTransaction(transaction);
                return new StagedReplacement(
                        requiredWorldKey,
                        worldName,
                        installed.getLoadKey(),
                        effectiveSeed,
                        targetPresent,
                        datapacks.restartRequired()
                );
            } catch (Throwable failure) {
                if (configurationApplied && transaction != null) {
                    try {
                        WorldGeneratorSnapshot replacement = WorldReplacementBootstrap.replacementSnapshot(transaction);
                        if (!BukkitWorldConfiguration.restoreIfMatching(
                                ServerProperties.BUKKIT_YML,
                                worldName,
                                replacement,
                                originalConfiguration
                        )) {
                            failure.addSuppressed(new IOException(
                                    "bukkit.yml changed before the failed replacement could be restored."));
                        }
                    } catch (Throwable restoreFailure) {
                        failure.addSuppressed(restoreFailure);
                    }
                }
                if (!configurationApplied || configurationMatches(originalConfiguration, worldName)) {
                    try {
                        WorldReplacementFilesystem.discardStage(paths);
                        if (journalWritten) {
                            deleteJournal(transactionId);
                        }
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                if (failure instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                throw new IOException("Failed to stage replacement for " + requiredWorldKey + ".", failure);
            }
        }
    }

    public synchronized void processPendingStartupReplacements() {
        ArrayList<String> failures = new ArrayList<>();
        ArrayList<String> restartBoundaries = new ArrayList<>();
        List<Transaction> transactions;
        try {
            transactions = loadTransactions();
        } catch (Throwable failure) {
            Iris.reportError("Failed to read pending Iris world replacements.", failure);
            IrisStartupValidation.markPacksInvalid(List.of(
                    "Pending Iris world replacement journal validation failed: " + detail(failure)));
            return;
        }
        for (Transaction transaction : transactions) {
            try {
                inspectStartupTransaction(transaction);
            } catch (RestartBoundaryRequired boundary) {
                String message = "Pending replacement for " + transaction.worldKey()
                        + " still requires a complete server restart: " + detail(boundary);
                restartBoundaries.add(message);
                Iris.warn(message);
            } catch (Throwable failure) {
                String message = "Pending replacement for " + transaction.worldKey()
                        + " failed safely: " + detail(failure);
                failures.add(message);
                Iris.reportError(message, failure);
            }
        }
        if (!failures.isEmpty()) {
            IrisStartupValidation.markPacksInvalid(failures);
        }
        if (!restartBoundaries.isEmpty()) {
            IrisStartupValidation.requireRestart(restartBoundaries.getFirst());
        }
    }

    public synchronized void verifyLoadedPublishedWorlds() {
        try {
            for (Transaction transaction : loadTransactions()) {
                if (transaction.phase() == Phase.CLEANUP_PENDING) {
                    scheduleCommittedCleanup(transaction);
                } else if (transaction.phase() == Phase.PUBLISHED) {
                    WorldIdentity.resolve(toNamespacedKey(transaction.worldKey()))
                            .ifPresent(world -> verifyPublishedWorld(world, transaction));
                }
            }
        } catch (Throwable failure) {
            Iris.reportError("Failed to inspect published Iris world replacements.", failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        // One tick to let the load settle, then verify off-main: the body takes the manager
        // monitor (held across pack staging by async threads) and SHA-hashes the whole pack
        // tree — blocking the main thread on either froze the server.
        J.s(() -> J.a(() -> verifyPublishedWorldIfPending(world)), 1);
    }

    private synchronized void verifyPublishedWorldIfPending(World world) {
        try {
            Transaction transaction = findTransaction(WorldIdentity.key(world));
            if (transaction != null && transaction.phase() == Phase.PUBLISHED) {
                verifyPublishedWorld(world, transaction);
            } else if (transaction != null && transaction.phase() == Phase.CLEANUP_PENDING) {
                scheduleCommittedCleanup(transaction);
            }
        } catch (Throwable failure) {
            Iris.reportError("Failed to verify a published Iris world replacement.", failure);
        }
    }

    private void verifyPublishedWorld(World world, Transaction transaction) {
        try {
            if (!transaction.worldKey().equals(toWorldSlotKey(WorldIdentity.key(world)))) {
                throw new IOException("Loaded world identity does not match the replacement journal.");
            }
            if (!IrisToolbelt.isIrisWorld(world)) {
                throw new IOException("The replaced world did not load with an Iris generator.");
            }
            if (world.getSeed() != transaction.seed()) {
                throw new IOException("The replaced world loaded with an unexpected seed.");
            }
            World.Environment expectedEnvironment = expectedEnvironment(transaction.worldKey());
            if (expectedEnvironment != null && world.getEnvironment() != expectedEnvironment) {
                throw new IOException("The replaced world loaded with an unexpected environment.");
            }
            PlatformChunkGenerator generator = IrisToolbelt.access(world);
            if (generator == null || !transaction.dimension().equals(
                    generator.getTarget().getDimension().getLoadKey())) {
                throw new IOException("The replaced world loaded an unexpected Iris dimension.");
            }
            ExactWorldSlotPathPolicy.Target target = resolveTransactionTarget(transaction);
            requireCompatibleEnvironment(
                    target.slotKind(),
                    generator.getTarget().getDimension().getEnvironment()
            );
            ReplacementPaths paths = WorldReplacementFilesystem.paths(target, transaction.id());
            String fingerprint = WorldReplacementFilesystem.fingerprintPack(
                    paths.target().resolve("iris/pack"));
            if (!transaction.packFingerprint().equals(fingerprint)) {
                throw new IOException("The replacement pack changed before runtime verification.");
            }
            WorldGeneratorSnapshot configured = BukkitWorldConfiguration.snapshot(
                    ServerProperties.BUKKIT_YML,
                    transaction.worldName()
            );
            if (!configured.matchesGeneratorAndSeed(WorldReplacementBootstrap.replacementSnapshot(transaction))) {
                throw new IOException("bukkit.yml changed before the replacement could be committed.");
            }
        } catch (Throwable failure) {
            initiateRollback(transaction, failure);
            return;
        }
        Transaction committed = transaction.withPhase(Phase.CLEANUP_PENDING);
        try {
            writeTransaction(committed);
            Iris.success("Committed Iris world replacement for " + transaction.worldKey() + ".");
            scheduleCommittedCleanup(committed);
        } catch (Throwable failure) {
            Iris.reportError("The replacement for " + transaction.worldKey()
                    + " was verified, but its cleanup journal could not be advanced."
                    + " The retained backup was not removed.", failure);
        }
    }

    private void initiateRollback(Transaction transaction, Throwable failure) {
        Iris.reportError("Iris world replacement verification failed for " + transaction.worldKey()
                + "; the retained world will be restored on restart.", failure);
        try {
            Transaction rollback = transaction.withPhase(Phase.ROLLBACK_PENDING);
            writeTransaction(rollback);
        } catch (Throwable rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            IrisStartupValidation.markPacksInvalid(List.of(
                    "Iris could not arm rollback for " + transaction.worldKey() + ": " + detail(rollbackFailure)));
            Iris.reportError("Failed to arm Iris world replacement rollback for "
                    + transaction.worldKey() + ". Stop the server and preserve the replacement artifacts.", rollbackFailure);
        } finally {
            ServerConfigurator.restart("An Iris world replacement failed verification and requires a cold restart.");
        }
    }

    private void inspectStartupTransaction(Transaction transaction) throws IOException {
        ExactWorldSlotPathPolicy.Target target = resolveTransactionTarget(transaction);
        ReplacementPaths paths = WorldReplacementFilesystem.paths(target, transaction.id());
        WorldGeneratorSnapshot current = BukkitWorldConfiguration.snapshot(
                ServerProperties.BUKKIT_YML,
                transaction.worldName()
        );
        WorldGeneratorSnapshot replacement = WorldReplacementBootstrap.replacementSnapshot(transaction);
        if (transaction.phase() == Phase.CLEANUP_PENDING) {
            if (!current.matchesGeneratorAndSeed(replacement)) {
                throw new IOException("A verified replacement no longer matches bukkit.yml.");
            }
            WorldReplacementFilesystem.validateCommittedTarget(paths, transaction.packFingerprint());
            return;
        }
        if (transaction.phase() == Phase.ROLLBACK_PENDING
                || transaction.phase() == Phase.ROLLBACK_CLEANUP
                || transaction.phase() == Phase.ARMED) {
            throw new RestartBoundaryRequired("The world-storage transaction has not reached its cold bootstrap.");
        }
        if (transaction.phase() == Phase.PREPARED) {
            if (current.matchesGeneratorAndSeed(transaction.originalConfiguration())) {
                WorldReplacementFilesystem.discardStage(paths);
                deleteJournal(transaction.id());
                Iris.warn("Cancelled incomplete Iris world replacement for " + transaction.worldKey() + ".");
                return;
            } else if (current.matchesGeneratorAndSeed(replacement)) {
                writeTransaction(transaction.withPhase(Phase.ARMED));
                throw new RestartBoundaryRequired("The replacement was armed before its journal phase was durable.");
            } else {
                throw new IOException("bukkit.yml does not match the prepared replacement or its original state.");
            }
        }
        if (transaction.phase() == Phase.PUBLISHED) {
            if (!current.matchesGeneratorAndSeed(replacement)) {
                throw new IOException("bukkit.yml changed after the replacement was published.");
            }
            WorldReplacementFilesystem.validatePublishedTarget(
                    paths,
                    transaction.originalTargetPresent(),
                    transaction.packFingerprint()
            );
        }
    }

    private synchronized void scheduleCommittedCleanup(Transaction transaction) {
        if (!cleanupInFlight.add(transaction.id())) {
            return;
        }
        try {
            J.a(() -> cleanupCommittedReplacement(transaction));
        } catch (Throwable failure) {
            cleanupInFlight.remove(transaction.id());
            Iris.reportError("Could not schedule retained-backup cleanup for " + transaction.worldKey()
                    + "; cleanup will retry without rolling back the verified world.", failure);
        }
    }

    private void cleanupCommittedReplacement(Transaction transaction) {
        try {
            synchronized (this) {
                Transaction current = findTransaction(transaction.worldKey());
                if (current == null
                        || !current.id().equals(transaction.id())
                        || current.phase() != Phase.CLEANUP_PENDING) {
                    return;
                }
                WorldGeneratorSnapshot configured = BukkitWorldConfiguration.snapshot(
                        ServerProperties.BUKKIT_YML,
                        current.worldName()
                );
                if (!configured.matchesGeneratorAndSeed(WorldReplacementBootstrap.replacementSnapshot(current))) {
                    throw new IOException("bukkit.yml changed before the retained backup could be removed.");
                }
                ExactWorldSlotPathPolicy.Target target = resolveTransactionTarget(current);
                ReplacementPaths paths = WorldReplacementFilesystem.paths(target, current.id());
                WorldReplacementFilesystem.validateCommittedTarget(paths, current.packFingerprint());
                WorldReplacementFilesystem.cleanupBackup(paths);
                deleteJournal(current.id());
                Iris.success("Removed the retained backup for " + current.worldKey() + ".");
            }
        } catch (Throwable failure) {
            Iris.reportError("Could not clean the retained backup for " + transaction.worldKey()
                    + "; cleanup will retry without rolling back the verified world.", failure);
        } finally {
            synchronized (this) {
                cleanupInFlight.remove(transaction.id());
            }
        }
    }

    private ExactWorldSlotPathPolicy.Target prepareTarget(WorldSlotKey worldKey) throws IOException {
        Path levelRoot = IrisWorldStorage.levelRoot().toPath();
        ExactWorldSlotPathPolicy.Target target = ExactWorldSlotPathPolicy.resolve(levelRoot, worldKey);
        Path dimensions = target.levelRoot().resolve("dimensions");
        createDirectoryIfMissing(dimensions);
        createDirectoryIfMissing(target.namespaceRoot());
        return ExactWorldSlotPathPolicy.resolve(levelRoot, worldKey);
    }

    private static void createDirectoryIfMissing(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("World storage parent is unsafe: " + directory);
            }
            return;
        }
        Files.createDirectory(directory);
    }

    private Transaction findTransaction(NamespacedKey worldKey) throws IOException {
        return findTransaction(toWorldSlotKey(worldKey));
    }

    private Transaction findTransaction(WorldSlotKey worldKey) throws IOException {
        for (Transaction transaction : loadTransactions()) {
            if (transaction.worldKey().equals(worldKey)) {
                return transaction;
            }
        }
        return null;
    }

    private List<Transaction> loadTransactions() throws IOException {
        Path levelRoot = IrisWorldStorage.levelRoot().toPath();
        List<Transaction> transactions = WorldReplacementJournal.load(dataDirectory(), levelRoot);
        List<Transaction> applicable = new ArrayList<>(transactions.size());
        for (Transaction transaction : transactions) {
            // A journal staged against another level root is not this server's transaction;
            // skip it (the bootstrap already told the operator how to resolve it).
            if (WorldReplacementJournal.appliesTo(transaction, levelRoot)) {
                applicable.add(transaction);
            } else {
                Iris.warn("Ignoring pending world replacement " + transaction.id() + " for "
                        + transaction.worldKey() + "; it was staged against level root "
                        + transaction.levelRoot() + ".");
            }
        }
        return applicable;
    }

    private void writeTransaction(Transaction transaction) throws IOException {
        WorldReplacementJournal.write(dataDirectory(), transaction);
    }

    private void deleteJournal(UUID id) throws IOException {
        WorldReplacementJournal.delete(dataDirectory(), id);
    }

    private Path dataDirectory() {
        return plugin.getDataFolder().toPath().toAbsolutePath().normalize();
    }

    private ExactWorldSlotPathPolicy.Target resolveTransactionTarget(Transaction transaction) throws IOException {
        return WorldReplacementJournal.resolveTarget(transaction, IrisWorldStorage.levelRoot().toPath());
    }

    private static boolean configurationMatches(WorldGeneratorSnapshot expected, String worldName) {
        try {
            return BukkitWorldConfiguration.snapshot(ServerProperties.BUKKIT_YML, worldName)
                    .matchesGeneratorAndSeed(expected);
        } catch (IOException failure) {
            return false;
        }
    }

    private static void requireCompatibleEnvironment(SlotKind slotKind, IrisEnvironment environment) {
        IrisEnvironment expected = switch (slotKind) {
            case VANILLA_OVERWORLD -> IrisEnvironment.NORMAL;
            case VANILLA_NETHER -> IrisEnvironment.NETHER;
            case VANILLA_END -> IrisEnvironment.THE_END;
            case IRIS_MANAGED -> null;
        };
        if (expected != null && environment != expected) {
            throw new IllegalArgumentException("The " + slotKind.name().toLowerCase(Locale.ENGLISH)
                    + " slot requires a pack environment of " + expected.name() + ".");
        }
    }

    /**
     * Captures the primary level context on the main thread at startup. resolveEffectiveSeed
     * reads this snapshot so staging never blocks on a main-thread hop while holding the
     * manager monitor — that inversion froze the server for the full 30s hop timeout whenever
     * another world loaded during staging. The context is stable for the process lifetime:
     * slot replacements only commit across a restart.
     */
    public void captureVanillaLevelContext() {
        try {
            vanillaLevelContext = new VanillaLevelContext(
                    WorldIdentity.resolve(NamespacedKey.minecraft("overworld"))
                            .orElseThrow(() -> new IllegalStateException("The configured primary world is not loaded."))
                            .getSeed(),
                    Iris.instance.getServer().getAllowNether(),
                    Iris.instance.getServer().getAllowEnd()
            );
        } catch (Throwable failure) {
            Iris.debug("Could not capture the primary level context yet: " + detail(failure));
        }
    }

    private static long resolveEffectiveSeed(SlotKind slotKind, long requestedSeed) throws IOException {
        if (slotKind == SlotKind.IRIS_MANAGED) {
            return requestedSeed;
        }
        VanillaLevelContext context = vanillaLevelContext;
        if (context == null) {
            context = resolveVanillaLevelContext();
            vanillaLevelContext = context;
        }
        if (slotKind == SlotKind.VANILLA_NETHER && !context.allowNether()) {
            throw new IOException("allow-nether must be true before the vanilla Nether can be replaced.");
        }
        if (slotKind == SlotKind.VANILLA_END && !context.allowEnd()) {
            throw new IOException("Bukkit allow-end must be true before the vanilla End can be replaced.");
        }
        return context.seed();
    }

    private static VanillaLevelContext resolveVanillaLevelContext() throws IOException {
        CompletableFuture<VanillaLevelContext> contextFuture = J.sfut(() -> new VanillaLevelContext(
                WorldIdentity.resolve(NamespacedKey.minecraft("overworld"))
                        .orElseThrow(() -> new IllegalStateException("The configured primary world is not loaded."))
                        .getSeed(),
                Iris.instance.getServer().getAllowNether(),
                Iris.instance.getServer().getAllowEnd()
        ));
        if (contextFuture == null) {
            throw new IOException("Could not schedule primary level-seed resolution.");
        }
        try {
            return contextFuture.get(30L, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Primary level-seed resolution was interrupted.", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Could not resolve the authoritative primary level seed.", failure);
        }
    }

    private static World.Environment expectedEnvironment(WorldSlotKey worldKey) {
        if (WorldSlotKey.minecraft("overworld").equals(worldKey)) {
            return World.Environment.NORMAL;
        }
        if (WorldSlotKey.minecraft("the_nether").equals(worldKey)) {
            return World.Environment.NETHER;
        }
        if (WorldSlotKey.minecraft("the_end").equals(worldKey)) {
            return World.Environment.THE_END;
        }
        return null;
    }

    private static WorldSlotKey toWorldSlotKey(NamespacedKey worldKey) {
        NamespacedKey requiredWorldKey = Objects.requireNonNull(worldKey, "worldKey");
        return new WorldSlotKey(requiredWorldKey.getNamespace(), requiredWorldKey.getKey());
    }

    private static NamespacedKey toNamespacedKey(WorldSlotKey worldKey) {
        WorldSlotKey requiredWorldKey = Objects.requireNonNull(worldKey, "worldKey");
        return new NamespacedKey(requiredWorldKey.namespace(), requiredWorldKey.key());
    }

    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    public record StagedReplacement(
            NamespacedKey worldKey,
            String worldName,
            String dimension,
            long seed,
            boolean replacedExistingTarget,
            boolean datapackRestartRequired
    ) {
        public StagedReplacement {
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    private static volatile VanillaLevelContext vanillaLevelContext;

    private record VanillaLevelContext(long seed, boolean allowNether, boolean allowEnd) {
    }

    private static final class RestartBoundaryRequired extends IOException {
        private RestartBoundaryRequired(String message) {
            super(message);
        }
    }
}

package art.arcane.iris.core;

import art.arcane.iris.Iris;
import art.arcane.iris.core.ExactWorldSlotPathPolicy.SlotKind;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration.GeneratorReplacement;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration.WorldGeneratorSnapshot;
import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.lifecycle.WorldReplacementFilesystem;
import art.arcane.iris.core.lifecycle.WorldReplacementFilesystem.ReplacementPaths;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PendingWorldReplacementManager implements Listener {
    private static final String JOURNAL_DIRECTORY = "pending-world-replacements";
    private static final String JOURNAL_SUFFIX = ".properties";

    private final Iris plugin;

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
        ExactWorldSlotPathPolicy.resolve(IrisWorldStorage.levelRoot().toPath(), worldKey);
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
        IrisDimension requiredDimension = Objects.requireNonNull(dimension, "dimension");
        IrisStartupValidation.requireWorldCreationReady();
        PackValidationRegistry.requireLoadable(requiredDimension.getLoader().getDataFolder().getName());
        ExactWorldSlotPathPolicy.Target resolvedTarget = ExactWorldSlotPathPolicy.resolve(
                IrisWorldStorage.levelRoot().toPath(),
                requiredWorldKey
        );
        requireCompatibleEnvironment(resolvedTarget.slotKind(), requiredDimension.getEnvironment());
        long effectiveSeed = resolveEffectiveSeed(resolvedTarget.slotKind(), seed);
        String worldName = IrisWorldStorage.logicalName(requiredWorldKey);
        LifecycleOperationCoordinator coordinator = LifecycleOperationCoordinator.get();
        try (LifecycleOperationCoordinator.Lease ignored = coordinator.acquire(
                LifecycleOperationCoordinator.Domain.WORLD_MUTATION,
                LifecycleOperationCoordinator.OperationKind.WORLD_REPLACE,
                requiredWorldKey.toString()
        )) {
            if (findTransaction(requiredWorldKey) != null) {
                throw new IOException("A replacement is already pending for " + requiredWorldKey + ".");
            }
            ExactWorldSlotPathPolicy.Target target = prepareTarget(requiredWorldKey);
            DatapackInstallResult datapacks = ServerConfigurator.installDataPacksIfChanged(true);
            if (!datapacks.succeeded()) {
                throw new IOException("Iris could not compile the dimension datapacks.");
            }

            UUID transactionId = UUID.randomUUID();
            ReplacementPaths paths = replacementPaths(target, transactionId);
            boolean targetPresent = Files.exists(paths.target(), LinkOption.NOFOLLOW_LINKS);
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
                File stagedPack = paths.stage().resolve("iris/pack").toFile();
                IrisWorldGeneratorResolver.requireSnapshotLoadable(stagedPack);
                String packFingerprint = WorldReplacementFilesystem.fingerprintPack(stagedPack.toPath());
                transaction = new Transaction(
                        transactionId,
                        requiredWorldKey,
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
                        WorldGeneratorSnapshot replacement = replacementSnapshot(transaction);
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
                processStartupTransaction(transaction);
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
    }

    public synchronized void verifyLoadedPublishedWorlds() {
        try {
            for (Transaction transaction : loadTransactions()) {
                if (transaction.phase() != Phase.PUBLISHED) {
                    continue;
                }
                WorldIdentity.resolve(transaction.worldKey()).ifPresent(world -> verifyPublishedWorld(world, transaction));
            }
        } catch (Throwable failure) {
            Iris.reportError("Failed to inspect published Iris world replacements.", failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        J.s(() -> verifyPublishedWorldIfPending(world), 1);
    }

    private synchronized void verifyPublishedWorldIfPending(World world) {
        try {
            Transaction transaction = findTransaction(WorldIdentity.key(world));
            if (transaction != null && transaction.phase() == Phase.PUBLISHED) {
                verifyPublishedWorld(world, transaction);
            }
        } catch (Throwable failure) {
            Iris.reportError("Failed to verify a published Iris world replacement.", failure);
        }
    }

    private void verifyPublishedWorld(World world, Transaction transaction) {
        try {
            if (!transaction.worldKey().equals(WorldIdentity.key(world))) {
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
            ExactWorldSlotPathPolicy.Target target = ExactWorldSlotPathPolicy.resolve(
                    IrisWorldStorage.levelRoot().toPath(),
                    transaction.worldKey()
            );
            ReplacementPaths paths = replacementPaths(target, transaction.id());
            String fingerprint = WorldReplacementFilesystem.fingerprintPack(
                    paths.target().resolve("iris/pack"));
            if (!transaction.packFingerprint().equals(fingerprint)) {
                throw new IOException("The replacement pack changed before runtime verification.");
            }
            WorldReplacementFilesystem.cleanupBackup(paths);
            deleteJournal(transaction.id());
            Iris.success("Committed Iris world replacement for " + transaction.worldKey() + ".");
        } catch (Throwable failure) {
            initiateRollback(transaction, failure);
        }
    }

    private void initiateRollback(Transaction transaction, Throwable failure) {
        Iris.reportError("Iris world replacement verification failed for " + transaction.worldKey()
                + "; the retained world will be restored on restart.", failure);
        try {
            Transaction rollback = transaction.withPhase(Phase.ROLLBACK_PENDING);
            writeTransaction(rollback);
            WorldGeneratorSnapshot replacement = replacementSnapshot(transaction);
            WorldGeneratorSnapshot current = BukkitWorldConfiguration.snapshot(
                    ServerProperties.BUKKIT_YML,
                    transaction.worldName()
            );
            if (current.matchesGeneratorAndSeed(replacement)) {
                if (!BukkitWorldConfiguration.restoreIfMatching(
                        ServerProperties.BUKKIT_YML,
                        transaction.worldName(),
                        replacement,
                        transaction.originalConfiguration()
                )) {
                    throw new IOException("bukkit.yml changed during replacement rollback.");
                }
            } else if (!current.matchesGeneratorAndSeed(transaction.originalConfiguration())) {
                throw new IOException("bukkit.yml no longer matches either side of the replacement.");
            }
            ServerConfigurator.restart("An Iris world replacement failed verification and will be rolled back.");
        } catch (Throwable rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            IrisStartupValidation.markPacksInvalid(List.of(
                    "Iris could not arm rollback for " + transaction.worldKey() + ": " + detail(rollbackFailure)));
            Iris.reportError("Failed to arm Iris world replacement rollback for "
                    + transaction.worldKey() + ". Stop the server and preserve the replacement artifacts.", rollbackFailure);
        }
    }

    private void processStartupTransaction(Transaction transaction) throws IOException {
        ExactWorldSlotPathPolicy.Target target = ExactWorldSlotPathPolicy.resolve(
                IrisWorldStorage.levelRoot().toPath(),
                transaction.worldKey()
        );
        ReplacementPaths paths = replacementPaths(target, transaction.id());
        WorldGeneratorSnapshot current = BukkitWorldConfiguration.snapshot(
                ServerProperties.BUKKIT_YML,
                transaction.worldName()
        );
        WorldGeneratorSnapshot replacement = replacementSnapshot(transaction);
        if (transaction.phase() == Phase.ROLLBACK_PENDING) {
            processRollback(transaction, paths, current, replacement);
            return;
        }
        if (transaction.phase() == Phase.PREPARED) {
            if (current.matchesGeneratorAndSeed(replacement)) {
                transaction = transaction.withPhase(Phase.ARMED);
                writeTransaction(transaction);
            } else if (current.matchesGeneratorAndSeed(transaction.originalConfiguration())) {
                WorldReplacementFilesystem.discardStage(paths);
                deleteJournal(transaction.id());
                Iris.warn("Cancelled incomplete Iris world replacement for " + transaction.worldKey() + ".");
                return;
            } else {
                throw new IOException("bukkit.yml does not match the prepared replacement or its original state.");
            }
        }
        if (transaction.phase() == Phase.ARMED) {
            if (!current.matchesGeneratorAndSeed(replacement)) {
                throw new IOException("bukkit.yml no longer authorizes the armed replacement.");
            }
            WorldReplacementFilesystem.publish(
                    paths,
                    transaction.originalTargetPresent(),
                    transaction.packFingerprint()
            );
            transaction = transaction.withPhase(Phase.PUBLISHED);
            writeTransaction(transaction);
            Iris.success("Published Iris world replacement for " + transaction.worldKey()
                    + "; waiting for runtime verification.");
        }
        if (transaction.phase() == Phase.PUBLISHED) {
            if (!current.matchesGeneratorAndSeed(replacement)) {
                throw new IOException("bukkit.yml changed after the replacement was published.");
            }
            String fingerprint = WorldReplacementFilesystem.fingerprintPack(
                    paths.target().resolve("iris/pack"));
            if (!transaction.packFingerprint().equals(fingerprint)) {
                throw new IOException("Published replacement pack fingerprint does not match its journal.");
            }
        }
    }

    private void processRollback(
            Transaction transaction,
            ReplacementPaths paths,
            WorldGeneratorSnapshot current,
            WorldGeneratorSnapshot replacement
    ) throws IOException {
        if (current.matchesGeneratorAndSeed(replacement)) {
            if (!BukkitWorldConfiguration.restoreIfMatching(
                    ServerProperties.BUKKIT_YML,
                    transaction.worldName(),
                    replacement,
                    transaction.originalConfiguration()
            )) {
                throw new IOException("bukkit.yml changed during startup rollback.");
            }
        } else if (!current.matchesGeneratorAndSeed(transaction.originalConfiguration())) {
            throw new IOException("bukkit.yml conflicts with the pending world rollback.");
        }
        WorldReplacementFilesystem.rollback(paths, transaction.originalTargetPresent());
        deleteJournal(transaction.id());
        Iris.success("Restored the retained world for " + transaction.worldKey() + ".");
    }

    private ExactWorldSlotPathPolicy.Target prepareTarget(NamespacedKey worldKey) throws IOException {
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
        for (Transaction transaction : loadTransactions()) {
            if (transaction.worldKey().equals(worldKey)) {
                return transaction;
            }
        }
        return null;
    }

    private List<Transaction> loadTransactions() throws IOException {
        Path directory = journalDirectory(false);
        if (directory == null) {
            return List.of();
        }
        ArrayList<Transaction> transactions = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + JOURNAL_SUFFIX)) {
            for (Path file : files) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Replacement journal entry is unsafe: " + file);
                }
                transactions.add(readTransaction(file));
            }
        }
        transactions.sort(Comparator.comparing(transaction -> transaction.id().toString()));
        return List.copyOf(transactions);
    }

    private Transaction readTransaction(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        UUID id = UUID.fromString(required(properties, "id"));
        if (!file.getFileName().toString().equals(id + JOURNAL_SUFFIX)) {
            throw new IOException("Replacement journal filename does not match its transaction id.");
        }
        NamespacedKey worldKey = NamespacedKey.fromString(required(properties, "worldKey"));
        if (worldKey == null) {
            throw new IOException("Replacement journal contains an invalid world key.");
        }
        ExactWorldSlotPathPolicy.resolve(IrisWorldStorage.levelRoot().toPath(), worldKey);
        String dimension = required(properties, "dimension");
        if (!safeDimension(dimension)) {
            throw new IOException("Replacement journal contains an invalid dimension key.");
        }
        long seed = parseLong(properties, "seed");
        String packFingerprint = required(properties, "packFingerprint");
        if (!packFingerprint.matches("[0-9a-f]{64}")) {
            throw new IOException("Replacement journal contains an invalid pack fingerprint.");
        }
        WorldGeneratorSnapshot original = readSnapshot(properties, "original.");
        boolean originalTargetPresent = parseBoolean(properties, "originalTargetPresent");
        Phase phase;
        try {
            phase = Phase.valueOf(required(properties, "phase"));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Replacement journal contains an invalid phase.", failure);
        }
        return new Transaction(
                id,
                worldKey,
                dimension,
                seed,
                packFingerprint,
                original,
                originalTargetPresent,
                phase
        );
    }

    private void writeTransaction(Transaction transaction) throws IOException {
        Path directory = Objects.requireNonNull(journalDirectory(true));
        Path target = directory.resolve(transaction.id() + JOURNAL_SUFFIX);
        Properties properties = new Properties();
        properties.setProperty("id", transaction.id().toString());
        properties.setProperty("worldKey", transaction.worldKey().toString());
        properties.setProperty("dimension", transaction.dimension());
        properties.setProperty("seed", Long.toString(transaction.seed()));
        properties.setProperty("packFingerprint", transaction.packFingerprint());
        properties.setProperty("originalTargetPresent", Boolean.toString(transaction.originalTargetPresent()));
        properties.setProperty("phase", transaction.phase().name());
        writeSnapshot(properties, "original.", transaction.originalConfiguration());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, null);
        writeAtomic(target, output.toByteArray());
    }

    private void deleteJournal(UUID id) throws IOException {
        Path directory = journalDirectory(false);
        if (directory == null) {
            return;
        }
        Files.deleteIfExists(directory.resolve(id + JOURNAL_SUFFIX));
        forceDirectory(directory);
    }

    private Path journalDirectory(boolean create) throws IOException {
        Path directory = plugin.getDataFile(JOURNAL_DIRECTORY).toPath().toAbsolutePath().normalize();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) {
                return null;
            }
            Files.createDirectories(directory);
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Replacement journal storage is unsafe: " + directory);
        }
        return directory;
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path parent = Objects.requireNonNull(target.getParent(), "journal parent");
        Path temporary = parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static ReplacementPaths replacementPaths(
            ExactWorldSlotPathPolicy.Target target,
            UUID id
    ) {
        String artifactBase = ".iris-replace-" + target.worldKey().getKey() + "-" + id;
        return new ReplacementPaths(
                target.worldDirectory(),
                target.namespaceRoot().resolve(artifactBase + ".stage"),
                target.namespaceRoot().resolve(artifactBase + ".backup")
        );
    }

    private static WorldGeneratorSnapshot replacementSnapshot(Transaction transaction) {
        return new WorldGeneratorSnapshot(
                true,
                true,
                true,
                "Iris:" + transaction.dimension(),
                true,
                transaction.seed()
        );
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

    private static long resolveEffectiveSeed(SlotKind slotKind, long requestedSeed) throws IOException {
        if (slotKind == SlotKind.IRIS_MANAGED) {
            return requestedSeed;
        }
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
            VanillaLevelContext context = contextFuture.get(30L, TimeUnit.SECONDS);
            if (slotKind == SlotKind.VANILLA_NETHER && !context.allowNether()) {
                throw new IOException("allow-nether must be true before the vanilla Nether can be replaced.");
            }
            if (slotKind == SlotKind.VANILLA_END && !context.allowEnd()) {
                throw new IOException("Bukkit allow-end must be true before the vanilla End can be replaced.");
            }
            return context.seed();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Primary level-seed resolution was interrupted.", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Could not resolve the authoritative primary level seed.", failure);
        }
    }

    private static World.Environment expectedEnvironment(NamespacedKey worldKey) {
        if (NamespacedKey.minecraft("overworld").equals(worldKey)) {
            return World.Environment.NORMAL;
        }
        if (NamespacedKey.minecraft("the_nether").equals(worldKey)) {
            return World.Environment.NETHER;
        }
        if (NamespacedKey.minecraft("the_end").equals(worldKey)) {
            return World.Environment.THE_END;
        }
        return null;
    }

    private static void writeSnapshot(Properties properties, String prefix, WorldGeneratorSnapshot snapshot) {
        properties.setProperty(prefix + "worldsSectionPresent", Boolean.toString(snapshot.worldsSectionPresent()));
        properties.setProperty(prefix + "worldSectionPresent", Boolean.toString(snapshot.worldSectionPresent()));
        properties.setProperty(prefix + "generatorPresent", Boolean.toString(snapshot.generatorPresent()));
        if (snapshot.generatorPresent()) {
            properties.setProperty(prefix + "generator", snapshot.generator());
        }
        properties.setProperty(prefix + "seedPresent", Boolean.toString(snapshot.seedPresent()));
        if (snapshot.seedPresent()) {
            properties.setProperty(prefix + "seed", Long.toString(snapshot.seed()));
        }
    }

    private static WorldGeneratorSnapshot readSnapshot(Properties properties, String prefix) throws IOException {
        boolean worldsPresent = parseBoolean(properties, prefix + "worldsSectionPresent");
        boolean worldPresent = parseBoolean(properties, prefix + "worldSectionPresent");
        boolean generatorPresent = parseBoolean(properties, prefix + "generatorPresent");
        String generator = generatorPresent ? required(properties, prefix + "generator") : null;
        boolean seedPresent = parseBoolean(properties, prefix + "seedPresent");
        Long seed = seedPresent ? parseLong(properties, prefix + "seed") : null;
        try {
            return new WorldGeneratorSnapshot(
                    worldsPresent,
                    worldPresent,
                    generatorPresent,
                    generator,
                    seedPresent,
                    seed
            );
        } catch (IllegalArgumentException failure) {
            throw new IOException("Replacement journal contains an invalid configuration snapshot.", failure);
        }
    }

    private static boolean safeDimension(String value) {
        if (value.isEmpty() || value.length() > 256 || value.startsWith(".") || value.contains("..")) {
            return false;
        }
        String[] segments = value.split("/", -1);
        if (segments.length > 16) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || !segment.matches("[A-Za-z0-9_-]+")) {
                return false;
            }
        }
        return true;
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Replacement journal is missing " + key + ".");
        }
        return value.trim();
    }

    private static boolean parseBoolean(Properties properties, String key) throws IOException {
        String value = required(properties, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IOException("Replacement journal contains an invalid boolean for " + key + ".");
        }
        return Boolean.parseBoolean(value);
    }

    private static long parseLong(Properties properties, String key) throws IOException {
        try {
            return Long.parseLong(required(properties, key));
        } catch (NumberFormatException failure) {
            throw new IOException("Replacement journal contains an invalid integer for " + key + ".", failure);
        }
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

    private record Transaction(
            UUID id,
            NamespacedKey worldKey,
            String dimension,
            long seed,
            String packFingerprint,
            WorldGeneratorSnapshot originalConfiguration,
            boolean originalTargetPresent,
            Phase phase
    ) {
        private Transaction {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(packFingerprint, "packFingerprint");
            Objects.requireNonNull(originalConfiguration, "originalConfiguration");
            Objects.requireNonNull(phase, "phase");
        }

        private String worldName() {
            return IrisWorldStorage.logicalName(worldKey);
        }

        private Transaction withPhase(Phase nextPhase) {
            return new Transaction(
                    id,
                    worldKey,
                    dimension,
                    seed,
                    packFingerprint,
                    originalConfiguration,
                    originalTargetPresent,
                    nextPhase
            );
        }
    }

    private enum Phase {
        PREPARED,
        ARMED,
        PUBLISHED,
        ROLLBACK_PENDING
    }

    private record VanillaLevelContext(long seed, boolean allowNether, boolean allowEnd) {
    }
}

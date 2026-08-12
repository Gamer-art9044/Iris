package art.arcane.iris.core.lifecycle;

import art.arcane.iris.core.ExactWorldSlotPathPolicy;
import art.arcane.iris.core.WorldSlotKey;
import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration.WorldGeneratorSnapshot;
import art.arcane.iris.core.lifecycle.WorldReplacementFilesystem.ReplacementPaths;
import art.arcane.iris.core.lifecycle.WorldReplacementJournal.Phase;
import art.arcane.iris.core.lifecycle.WorldReplacementJournal.Transaction;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorldReplacementBootstrapTest {
    private static final WorldSlotKey WORLD_KEY = WorldSlotKey.minecraft("the_nether");
    private static final long SEED = 4242424242L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path serverRoot;
    private Path dataDirectory;
    private Path levelRoot;
    private Path bukkitConfiguration;
    private ExactWorldSlotPathPolicy.Target target;

    @Before
    public void setUp() throws Exception {
        serverRoot = temporaryFolder.newFolder("server").toPath();
        dataDirectory = Files.createDirectories(serverRoot.resolve("plugins/Iris"));
        levelRoot = Files.createDirectories(serverRoot.resolve("world"));
        bukkitConfiguration = Files.createFile(serverRoot.resolve("bukkit.yml"));
        target = ExactWorldSlotPathPolicy.resolve(levelRoot, WORLD_KEY);
        Files.createDirectories(target.namespaceRoot());
    }

    @Test
    public void publishesArmedReplacementBeforeRegistryCompilation() throws Exception {
        Transaction transaction = stagedTransaction(Phase.ARMED, true, "original");
        configureReplacement(transaction);

        WorldReplacementBootstrap.ReconcileResult result = reconcile();

        assertEquals(1, result.published());
        assertEquals("replacement", replacementContent(target.worldDirectory()));
        assertEquals("original", Files.readString(backup(transaction).resolve("original.txt")));
        assertEquals(Phase.PUBLISHED, loadSingle().phase());
    }

    @Test
    public void publishesArmedReplacementWhenOriginalConfigurationAlreadyMatchesReplacement() throws Exception {
        configureExistingReplacement();
        Transaction transaction = stagedTransaction(Phase.ARMED, true, "original");

        WorldReplacementBootstrap.ReconcileResult result = reconcile();

        assertEquals(1, result.published());
        assertEquals("replacement", replacementContent(target.worldDirectory()));
        assertEquals("original", Files.readString(backup(transaction).resolve("original.txt")));
        assertEquals(Phase.PUBLISHED, loadSingle().phase());
    }

    @Test
    public void retainsPublishedReplacementWhenOriginalConfigurationAlreadyMatchesReplacement() throws Exception {
        configureExistingReplacement();
        Transaction transaction = stagedTransaction(Phase.PUBLISHED, true, "original");
        WorldReplacementFilesystem.publish(paths(transaction), true, transaction.packFingerprint());

        WorldReplacementBootstrap.ReconcileResult result = reconcile();

        assertEquals(1, result.retained());
        assertEquals("replacement", replacementContent(target.worldDirectory()));
        assertEquals("original", Files.readString(backup(transaction).resolve("original.txt")));
        assertEquals(Phase.PUBLISHED, loadSingle().phase());
    }

    @Test
    public void cancelsPreparedReplacementWhenOriginalConfigurationAlreadyMatchesReplacement() throws Exception {
        configureExistingReplacement();
        Transaction transaction = stagedTransaction(Phase.PREPARED, true, "original");

        WorldReplacementBootstrap.ReconcileResult result = reconcile();

        assertEquals(1, result.rolledBack());
        assertEquals("original", Files.readString(target.worldDirectory().resolve("original.txt")));
        assertFalse(Files.exists(paths(transaction).stage()));
        assertTrue(WorldReplacementJournal.load(dataDirectory, levelRoot).isEmpty());
    }

    @Test
    public void resumesPublicationAfterOriginalMoveCrash() throws Exception {
        Transaction transaction = stagedTransaction(Phase.ARMED, true, "original");
        configureReplacement(transaction);
        ReplacementPaths paths = paths(transaction);
        Files.move(paths.target(), paths.backup());

        reconcile();

        assertEquals("replacement", replacementContent(paths.target()));
        assertEquals("original", Files.readString(paths.backup().resolve("original.txt")));
        assertEquals(Phase.PUBLISHED, loadSingle().phase());
    }

    @Test
    public void cancelsPreparedTransactionWhenConfigurationWasNotApplied() throws Exception {
        Transaction transaction = stagedTransaction(Phase.PREPARED, true, "original");

        WorldReplacementBootstrap.ReconcileResult result = reconcile();

        assertEquals(1, result.rolledBack());
        assertEquals("original", Files.readString(target.worldDirectory().resolve("original.txt")));
        assertFalse(Files.exists(paths(transaction).stage()));
        assertTrue(WorldReplacementJournal.load(dataDirectory, levelRoot).isEmpty());
    }

    @Test
    public void restoresPublishedWorldWhenConfigurationWasReverted() throws Exception {
        Transaction transaction = stagedTransaction(Phase.PUBLISHED, true, "original");
        configureReplacement(transaction);
        WorldReplacementFilesystem.publish(paths(transaction), true, transaction.packFingerprint());
        restoreOriginalConfiguration(transaction);

        WorldReplacementBootstrap.ReconcileResult result = reconcile();

        assertEquals(1, result.rolledBack());
        assertEquals("original", Files.readString(target.worldDirectory().resolve("original.txt")));
        assertFalse(Files.exists(paths(transaction).stage()));
        assertFalse(Files.exists(paths(transaction).backup()));
        assertTrue(WorldReplacementJournal.load(dataDirectory, levelRoot).isEmpty());
    }

    @Test
    public void rejectsThirdPartyConfigurationAfterPublicationWithoutMovingStorage() throws Exception {
        Transaction transaction = stagedTransaction(Phase.PUBLISHED, true, "original");
        configureReplacement(transaction);
        WorldReplacementFilesystem.publish(paths(transaction), true, transaction.packFingerprint());
        WorldGeneratorSnapshot replacement = WorldReplacementBootstrap.replacementSnapshot(transaction);
        BukkitWorldConfiguration.replaceIfMatching(
                bukkitConfiguration.toFile(),
                transaction.worldName(),
                replacement,
                "other",
                SEED
        );

        assertThrows(IOException.class, this::reconcile);

        assertEquals("replacement", replacementContent(target.worldDirectory()));
        assertEquals("original", Files.readString(backup(transaction).resolve("original.txt")));
        assertEquals(Phase.PUBLISHED, loadSingle().phase());
    }

    @Test
    public void completesRollbackAcrossPreparedStorageCrashBoundary() throws Exception {
        Transaction transaction = stagedTransaction(Phase.ROLLBACK_PENDING, true, "original");
        configureReplacement(transaction);
        WorldReplacementFilesystem.publish(paths(transaction), true, transaction.packFingerprint());
        WorldReplacementFilesystem.prepareRollback(paths(transaction), true);
        restoreOriginalConfiguration(transaction);
        WorldReplacementJournal.write(dataDirectory, transaction.withPhase(Phase.ROLLBACK_CLEANUP));

        reconcile();

        assertEquals("original", Files.readString(target.worldDirectory().resolve("original.txt")));
        assertFalse(Files.exists(paths(transaction).stage()));
        assertTrue(WorldReplacementJournal.load(dataDirectory, levelRoot).isEmpty());
    }

    @Test
    public void retainsVerifiedTargetWhenBackupWasAlreadyCleaned() throws Exception {
        Transaction transaction = stagedTransaction(Phase.CLEANUP_PENDING, true, "original");
        configureReplacement(transaction);
        WorldReplacementFilesystem.publish(paths(transaction), true, transaction.packFingerprint());
        WorldReplacementFilesystem.cleanupBackup(paths(transaction));

        WorldReplacementBootstrap.ReconcileResult result = reconcile();

        assertEquals(1, result.retained());
        assertEquals("replacement", replacementContent(target.worldDirectory()));
        assertEquals(Phase.CLEANUP_PENDING, loadSingle().phase());
    }

    @Test
    public void rejectsChangedLevelRootBeforeTouchingStagedStorage() throws Exception {
        Transaction transaction = stagedTransaction(Phase.ARMED, true, "original");
        configureReplacement(transaction);
        Path otherLevelRoot = Files.createDirectories(serverRoot.resolve("renamed-world"));

        assertThrows(
                IOException.class,
                () -> WorldReplacementBootstrap.reconcile(
                        dataDirectory,
                        otherLevelRoot,
                        bukkitConfiguration,
                        ignored -> {
                        }
                )
        );

        assertEquals("original", Files.readString(target.worldDirectory().resolve("original.txt")));
        assertTrue(Files.isDirectory(paths(transaction).stage()));
        assertFalse(Files.exists(paths(transaction).backup()));
    }

    @Test
    public void rejectsDuplicateWorldJournalsBeforePublishingEither() throws Exception {
        Transaction first = stagedTransaction(Phase.ARMED, true, "original");
        configureReplacement(first);
        Transaction second = new Transaction(
                UUID.randomUUID(),
                first.worldKey(),
                first.worldName(),
                first.levelRoot(),
                first.dimension(),
                first.seed(),
                first.packFingerprint(),
                first.originalConfiguration(),
                first.originalTargetPresent(),
                first.phase()
        );
        WorldReplacementJournal.write(dataDirectory, second);

        assertThrows(IOException.class, this::reconcile);

        assertTrue(Files.isDirectory(paths(first).stage()));
        assertEquals("original", Files.readString(target.worldDirectory().resolve("original.txt")));
        assertFalse(Files.exists(paths(first).backup()));
    }

    @Test
    public void roundTripsBlankAndWhitespaceOriginalGenerators() throws Exception {
        for (String generator : List.of("", "   ")) {
            WorldGeneratorSnapshot original = new WorldGeneratorSnapshot(
                    true,
                    true,
                    true,
                    generator,
                    false,
                    null
            );
            Transaction transaction = transaction(UUID.randomUUID(), original, Phase.PREPARED, false, "replacement");
            WorldReplacementJournal.write(dataDirectory, transaction);

            Transaction loaded = loadSingle();

            assertEquals(generator, loaded.originalConfiguration().generator());
            WorldReplacementJournal.delete(dataDirectory, transaction.id());
        }
    }

    @Test
    public void rejectsIrisWorldKeysThatCollideWithConfiguredVanillaAliases() {
        WorldGeneratorSnapshot original = new WorldGeneratorSnapshot(false, false, false, null, false, null);
        for (String alias : List.of("world", "world_nether", "world_the_end")) {
            Transaction transaction = new Transaction(
                    UUID.randomUUID(),
                    new WorldSlotKey("iris", alias),
                    alias,
                    levelRoot,
                    "underworld",
                    SEED,
                    "0".repeat(64),
                    original,
                    false,
                    Phase.ARMED
            );

            assertThrows(IOException.class, () -> WorldReplacementJournal.resolveTarget(transaction, levelRoot));
        }
    }

    private WorldReplacementBootstrap.ReconcileResult reconcile() throws Exception {
        return WorldReplacementBootstrap.reconcile(
                dataDirectory,
                levelRoot,
                bukkitConfiguration,
                ignored -> {
                }
        );
    }

    private Transaction stagedTransaction(Phase phase, boolean originalPresent, String originalContent)
            throws Exception {
        WorldGeneratorSnapshot original = BukkitWorldConfiguration.snapshot(
                bukkitConfiguration.toFile(),
                "world_nether"
        );
        return transaction(UUID.randomUUID(), original, phase, originalPresent, originalContent);
    }

    private Transaction transaction(
            UUID id,
            WorldGeneratorSnapshot original,
            Phase phase,
            boolean originalPresent,
            String originalContent
    ) throws Exception {
        ReplacementPaths paths = WorldReplacementFilesystem.paths(target, id);
        if (originalPresent) {
            writeOriginalTarget(paths, originalContent);
        }
        Path dimension = paths.stage().resolve("iris/pack/dimensions/underworld.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(dimension, "replacement");
        String fingerprint = WorldReplacementFilesystem.fingerprintPack(paths.stage().resolve("iris/pack"));
        Transaction transaction = new Transaction(
                id,
                WORLD_KEY,
                "world_nether",
                target.levelRoot(),
                "underworld",
                SEED,
                fingerprint,
                original,
                originalPresent,
                phase
        );
        WorldReplacementJournal.write(dataDirectory, transaction);
        return transaction;
    }

    private void configureReplacement(Transaction transaction) throws Exception {
        BukkitWorldConfiguration.GeneratorReplacement result = BukkitWorldConfiguration.replaceIfMatching(
                bukkitConfiguration.toFile(),
                transaction.worldName(),
                transaction.originalConfiguration(),
                transaction.dimension(),
                transaction.seed()
        );
        assertTrue(result.applied());
    }

    private void configureExistingReplacement() throws Exception {
        BukkitWorldConfiguration.register(
                bukkitConfiguration.toFile(),
                "world_nether",
                "underworld",
                SEED
        );
    }

    private void restoreOriginalConfiguration(Transaction transaction) throws Exception {
        assertTrue(BukkitWorldConfiguration.restoreIfMatching(
                bukkitConfiguration.toFile(),
                transaction.worldName(),
                WorldReplacementBootstrap.replacementSnapshot(transaction),
                transaction.originalConfiguration()
        ));
    }

    private Transaction loadSingle() throws Exception {
        return WorldReplacementJournal.load(dataDirectory, levelRoot).getFirst();
    }

    private ReplacementPaths paths(Transaction transaction) {
        return WorldReplacementFilesystem.paths(target, transaction.id());
    }

    private Path backup(Transaction transaction) {
        return paths(transaction).backup();
    }

    private void writeOriginalTarget(ReplacementPaths paths, String originalContent) throws Exception {
        Files.createDirectories(paths.target().resolve("data/paper"));
        Files.createDirectories(paths.target().resolve("data/minecraft"));
        Files.writeString(paths.target().resolve("original.txt"), originalContent);
        Files.writeString(paths.target().resolve("data/paper/metadata.dat"), "metadata");
        Files.writeString(paths.target().resolve("data/paper/level_overrides.dat"), "overrides");
        Files.writeString(paths.target().resolve("data/minecraft/world_gen_settings.dat"), "generation");
    }

    private String replacementContent(Path worldDirectory) throws Exception {
        return Files.readString(worldDirectory.resolve("iris/pack/dimensions/underworld.json"));
    }
}

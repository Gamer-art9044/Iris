package art.arcane.iris.core.tools;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisCreatorProgressContractTest {
    @Test
    public void persistentCreateReportsTheWholeLifecycleInsteadOfOnlySpawnChunks() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/tools/IrisCreator.java")).replace("\r\n", "\n");

        int startReporter = source.indexOf("WorldCreationProgressReporter.start(sender, name)");
        int resolve = source.indexOf("0.02D, \"resolve_dimension\"", startReporter);
        int validate = source.indexOf("0.06D, \"validate_pack\"", resolve);
        int datapacks = source.indexOf("0.10D, \"install_datapacks\"", validate);
        int pack = source.indexOf("0.26D, \"prepare_world_pack\"", datapacks);
        int generator = source.indexOf("0.36D, \"prepare_generator\"", pack);
        int createWorld = source.indexOf("0.44D, \"create_world\"", generator);
        int register = source.indexOf("0.84D, \"register_world\"", createWorld);
        int teleport = source.indexOf("0.92D, \"teleport_player\"", register);
        int finalize = source.indexOf("0.99D, \"finalize\"", teleport);
        int createReserved = source.indexOf("createReserved(worldKey, resolvedDimension, creationReporter)", validate);
        int succeed = source.indexOf("creationReporter.succeed()", createReserved);

        assertTrue(startReporter >= 0);
        assertTrue(resolve > startReporter);
        assertTrue(validate > resolve);
        assertTrue(datapacks > validate);
        assertTrue(pack > datapacks);
        assertTrue(generator > pack);
        assertTrue(createWorld > generator);
        assertTrue(register > createWorld);
        assertTrue(teleport > register);
        assertTrue(finalize > teleport);
        assertTrue(createReserved > validate);
        assertTrue(succeed > createReserved);
        assertTrue(source.contains("creationReporter.fail()"));
        assertTrue(source.contains("Form.f(generated) + \"/\" + Form.f(required) + \" chunks)\""));
        assertFalse(source.contains("RuntimeProgressMessages.WORLD_CREATE_ACTION"));
        assertFalse(source.contains("RuntimeProgressMessages.WORLD_CREATE_CONSOLE"));
    }

    @Test
    public void persistentCreateReadinessPrecedesSuccessRegistrationAndLeaseRelease() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.irisCreatorSource")))
                .replace("\r\n", "\n");

        int acquireLease = source.indexOf("worldLease = coordinator.acquire(");
        int createReserved = source.indexOf(
                "createReserved(worldKey, resolvedDimension, creationReporter)",
                acquireLease);
        int reportSuccess = source.indexOf("creationReporter.succeed()", createReserved);
        int releaseLease = source.indexOf("worldLease.close()", reportSuccess);
        int createWorld = source.indexOf("INMS.get().createWorldAsync(wc, request)", createReserved);
        int persistentGuard = source.indexOf("if (!studio && !benchmark) {", createWorld);
        int awaitSpawn = source.indexOf("awaitInitialSpawnPreparation(access, name)", persistentGuard);
        int creationDone = source.indexOf("done.set(true)", awaitSpawn);
        int registerWorld = source.indexOf("BukkitWorldConfiguration.register(", creationDone);
        int returnWorld = source.indexOf("return world;", registerWorld);

        assertTrue(acquireLease >= 0);
        assertTrue(createReserved > acquireLease);
        assertTrue(reportSuccess > createReserved);
        assertTrue(releaseLease > reportSuccess);
        assertTrue(createWorld > createReserved);
        assertTrue(persistentGuard > createWorld);
        assertTrue(awaitSpawn > persistentGuard);
        assertTrue(creationDone > awaitSpawn);
        assertTrue(registerWorld > creationDone);
        assertTrue(returnWorld > registerWorld);
    }

    @Test
    public void spawnReadinessFailureReachesWorldCreationRollback() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.irisCreatorSource")))
                .replace("\r\n", "\n");

        int awaitSpawn = source.indexOf("awaitInitialSpawnPreparation(access, name)");
        int createReservedFailure = source.indexOf("} catch (Throwable failure) {", awaitSpawn);
        int rollback = source.indexOf(
                "rollbackWorldCreation(worldKey, world, stagedGenerator, storageRoot, bukkitRegistered, failure)",
                createReservedFailure);
        int rethrow = source.indexOf("throw irisException", rollback);

        assertTrue(awaitSpawn >= 0);
        assertTrue(createReservedFailure > awaitSpawn);
        assertTrue(rollback > createReservedFailure);
        assertTrue(rethrow > rollback);
    }
}

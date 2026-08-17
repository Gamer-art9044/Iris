package art.arcane.iris.core.tools;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisCreatorProgressContractTest {
    @Test
    public void persistentCreateReportsTheWholeLifecycleInsteadOfOnlySpawnChunks() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/tools/IrisCreator.java"));

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
}

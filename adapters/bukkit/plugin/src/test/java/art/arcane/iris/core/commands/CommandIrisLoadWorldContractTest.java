package art.arcane.iris.core.commands;

import art.arcane.iris.core.BukkitWorldReconciler;
import art.arcane.volmlib.util.director.annotations.Param;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandIrisLoadWorldContractTest {
    @Test
    public void commandUsesTypedAsyncReconciliationBeforeReportingSuccess() throws Exception {
        Method reconciliation = BukkitWorldReconciler.class.getDeclaredMethod(
                "loadWorld",
                File.class,
                String.class);
        assertEquals(CompletableFuture.class, reconciliation.getReturnType());
        Method commandMethod = CommandIris.class.getDeclaredMethod("loadWorld", String.class);
        Parameter worldParameter = commandMethod.getParameters()[0];
        assertEquals(CommandIris.ManagedWorldNameHandler.class,
                worldParameter.getAnnotation(Param.class).customHandler());

        String source = Files.readString(Path.of(System.getProperty("iris.commandIrisSource")));
        String command = source.substring(
                source.indexOf("public void loadWorld("),
                source.indexOf("private void reportLoadWorldResult("));
        String reporter = source.substring(
                source.indexOf("private void reportLoadWorldResult("),
                source.indexOf("@Director(description = \"Evacuate an iris world\""));

        assertTrue(command.contains("IrisWorldStorage.managedKeyFromName(world)"));
        assertTrue(command.contains(".loadWorld(BUKKIT_YML, worldKey.toString())"));
        assertTrue(command.contains(".whenComplete((result, failure) ->"));
        assertFalse(command.contains("checkForBukkitWorlds"));
        assertFalse(command.contains("COMMAND_IRIS_LOADED_SUCCESSFULLY"));
        assertTrue(reporter.contains("if (result.succeeded())"));
        assertTrue(reporter.contains("COMMAND_IRIS_LOADED_SUCCESSFULLY"));
    }
}

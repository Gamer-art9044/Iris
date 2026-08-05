package art.arcane.iris.core.commands;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class CommandIrisUnloadContractTest {
    @Test
    public void unloadAwaitsEvacuationAndHasATerminalLifecycleTimeout() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.commandIrisSource")));
        String command = source.substring(
                source.indexOf("public void unloadWorld("),
                source.indexOf("private void reportUnloadResult("));

        assertTrue(command.contains("IrisToolbelt.evacuateAsync(world)"));
        assertTrue(command.indexOf("IrisToolbelt.evacuateAsync(world)")
                < command.indexOf("WorldLifecycleService.get().unloadAsync(world, true)"));
        assertTrue(command.contains("guardUnloadCompletion(sequence, terminalTimeout, world.getName())"));
        assertTrue(command.contains("ServerConfigurator.restart(\"World unload timed out"));
        assertTrue(command.indexOf("ServerConfigurator.restart(\"World unload timed out")
                < command.indexOf("guarded.completeExceptionally(timeout)"));
    }
}

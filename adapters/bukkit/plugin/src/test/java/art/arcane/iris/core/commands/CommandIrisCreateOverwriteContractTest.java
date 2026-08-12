package art.arcane.iris.core.commands;

import art.arcane.volmlib.util.director.annotations.Param;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CommandIrisCreateOverwriteContractTest {
    @Test
    public void createExposesOptInRestartReplacementFlag() throws Exception {
        Method command = CommandIris.class.getDeclaredMethod(
                "create",
                String.class,
                String.class,
                long.class,
                boolean.class,
                boolean.class
        );
        Parameter overwriteParameter = command.getParameters()[4];
        Param overwrite = overwriteParameter.getAnnotation(Param.class);

        assertEquals("overwrite", overwrite.name());
        assertEquals("false", overwrite.defaultValue());
        assertTrue(Arrays.asList(overwrite.aliases()).contains("force"));
        assertEquals(
                "iris.director.commandiris.param.replace_exact_existing_world_slot_next_restart",
                overwrite.descriptionKey()
        );
    }
}

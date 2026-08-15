package art.arcane.iris.core.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandIrisCreateOverwriteContractTest {
    @Test
    public void createOnlyAcceptsNameTypeAndSeed() throws Exception {
        Method command = CommandIris.class.getDeclaredMethod(
                "create",
                String.class,
                String.class,
                long.class
        );

        assertEquals(3, command.getParameterCount());
        assertFalse(Arrays.stream(command.getParameterTypes()).anyMatch(type -> type == boolean.class));
    }

    @Test
    public void replaceOwnsOverrideAndOverwriteAliasesWithoutASeed() throws Exception {
        Method command = CommandIris.class.getDeclaredMethod("replace", String.class, String.class);
        Director director = command.getAnnotation(Director.class);
        Parameter targetParameter = command.getParameters()[0];
        Param target = targetParameter.getAnnotation(Param.class);
        Parameter typeParameter = command.getParameters()[1];
        Param type = typeParameter.getAnnotation(Param.class);

        assertTrue(Arrays.asList(director.aliases()).contains("override"));
        assertTrue(Arrays.asList(director.aliases()).contains("overwrite"));
        assertEquals(
                "iris.director.commandiris.param.replace_exact_existing_world_slot_next_restart",
                director.descriptionKey()
        );
        assertEquals("target", target.name());
        assertEquals(director.descriptionKey(), target.descriptionKey());
        assertEquals("default", type.defaultValue());
        assertEquals(CommandIris.PackDimensionTypeHandler.class, type.customHandler());
        assertFalse(Arrays.stream(command.getParameterTypes()).anyMatch(parameterType -> parameterType == long.class));
    }
}

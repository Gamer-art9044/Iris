package art.arcane.iris.core.commands;

import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.util.common.director.specialhandlers.NullableDimensionHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CommandStudioCreationContractTest {
    @Test
    public void createRunsAsynchronouslyAndTemplateIsOptional() throws NoSuchMethodException {
        Method command = CommandStudio.class.getDeclaredMethod("create", String.class, IrisDimension.class);
        Director director = command.getAnnotation(Director.class);
        Parameter templateParameter = command.getParameters()[1];
        Param template = templateParameter.getAnnotation(Param.class);

        assertFalse(director.sync());
        assertEquals("null", template.defaultValue());
        assertEquals(NullableDimensionHandler.class, template.customHandler());
    }
}

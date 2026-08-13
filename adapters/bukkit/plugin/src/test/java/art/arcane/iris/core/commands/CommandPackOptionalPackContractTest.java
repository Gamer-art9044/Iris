package art.arcane.iris.core.commands;

import art.arcane.volmlib.util.director.compat.DirectorAnnotationCompatibility;
import art.arcane.volmlib.util.director.runtime.DirectorNodeDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandPackOptionalPackContractTest {
    private static DirectorParameterDescriptor packParam(String method) throws Exception {
        DirectorNodeDescriptor node = DirectorAnnotationCompatibility
                .fromMethod(CommandPack.class.getDeclaredMethod(method, String.class))
                .orElseThrow();
        DirectorParameterDescriptor pack = node.getParameters().get(0);
        assertEquals("pack", pack.getName());
        return pack;
    }

    @Test
    public void bareValidateAndStatusAreInvocableWithoutAPackArgument() throws Exception {
        for (String method : List.of("validate", "status")) {
            DirectorParameterDescriptor pack = packParam(method);
            assertFalse(method + " must not require a pack", pack.isRequired());
            assertFalse(method + " needs a non-blank default for Director to treat it as optional",
                    pack.getDefaultValue().isBlank());
        }
    }

    @Test
    public void cleanupAndRestoreStillRequireAPack() throws Exception {
        for (String method : List.of("cleanup", "restore")) {
            DirectorNodeDescriptor node = DirectorAnnotationCompatibility
                    .fromMethod(findMethod(method))
                    .orElseThrow();
            DirectorParameterDescriptor pack = node.getParameters().get(0);
            assertEquals("pack", pack.getName());
            assertTrue(method + " must keep requiring a pack", pack.isRequired());
        }
    }

    @Test
    public void allPacksPredicateAcceptsSentinelBlankAndNull() {
        assertTrue(CommandPack.wantsAllPacks(null));
        assertTrue(CommandPack.wantsAllPacks(""));
        assertTrue(CommandPack.wantsAllPacks(" "));
        assertTrue(CommandPack.wantsAllPacks("*"));
        assertFalse(CommandPack.wantsAllPacks("overworld"));
    }

    private static java.lang.reflect.Method findMethod(String name) {
        for (java.lang.reflect.Method method : CommandPack.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("CommandPack must declare " + name);
    }
}

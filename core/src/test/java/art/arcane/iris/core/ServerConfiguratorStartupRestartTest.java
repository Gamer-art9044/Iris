package art.arcane.iris.core;

import art.arcane.iris.spi.IrisLogging;
import org.bukkit.Bukkit;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

public class ServerConfiguratorStartupRestartTest {
    @Test
    public void startupBoundaryRestartsImmediatelyAndStopsIfRestartReturns() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<IrisLogging> logging = mockStatic(IrisLogging.class)) {
            ServerConfigurator.restartAtStartupBoundary(" updated external datapacks ");

            bukkit.verify(Bukkit::restart, times(1));
            bukkit.verify(Bukkit::shutdown, times(1));
            logging.verify(() -> IrisLogging.warn(
                    "updated external datapacks Restarting server before default worlds are loaded."));
            logging.verify(() -> IrisLogging.error(
                    "The immediate Iris startup restart returned unexpectedly; stopping the server instead."));
        }
    }

    @Test
    public void startupBoundaryStopsWhenImmediateRestartThrows() {
        IllegalStateException failure = new IllegalStateException("restart unavailable");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<IrisLogging> logging = mockStatic(IrisLogging.class)) {
            bukkit.when(Bukkit::restart).thenThrow(failure);

            ServerConfigurator.restartAtStartupBoundary(null);

            bukkit.verify(Bukkit::restart, times(1));
            bukkit.verify(Bukkit::shutdown, times(1));
            logging.verify(() -> IrisLogging.reportError(
                    "Unable to restart the server at the Iris startup boundary.", failure));
        }
    }

    @Test
    public void immediateRestartCapabilityIsOptional() throws ReflectiveOperationException {
        RestartCapableApi.restarted = false;

        assertTrue(ServerConfigurator.invokeImmediateRestartIfSupported(RestartCapableApi.class));
        assertTrue(RestartCapableApi.restarted);
        assertFalse(ServerConfigurator.invokeImmediateRestartIfSupported(ShutdownOnlyApi.class));
    }

    @Test
    public void productionBytecodeDoesNotLinkBukkitRestartDirectly() throws Exception {
        AtomicBoolean directRestartInvocation = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(ServerConfigurator.class.getName());
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String methodName,
                            String methodDescriptor,
                            boolean isInterface
                    ) {
                        if ("org/bukkit/Bukkit".equals(owner) && "restart".equals(methodName)) {
                            directRestartInvocation.set(true);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertFalse(directRestartInvocation.get());
    }

    @Test
    public void startupRestartPathsPromoteTheValidationStateAndBypassTickQueues() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/ServerConfigurator.java"));
        String configure = section(
                source,
                "public static void configure()",
                "public static boolean isLoadedDatapackRuntimeReady");
        String startupRestart = section(
                source,
                "public static void restartAtStartupBoundary",
                "public static boolean verifyDataPackInstalled");

        int restartResult = configure.indexOf("if (result.restartRequired())");
        int validationRestart = configure.indexOf("requireDatapackRestart();", restartResult);
        assertTrue(restartResult >= 0);
        assertTrue(validationRestart > restartResult);
        assertTrue(startupRestart.indexOf("invokeImmediateRestartIfSupported(Bukkit.class)")
                < startupRestart.indexOf("Bukkit.shutdown();"));
        assertFalse(startupRestart.contains("Bukkit.restart"));
        assertFalse(startupRestart.contains("J.s("));
        assertFalse(startupRestart.contains("dispatchCommand"));
    }

    public static final class RestartCapableApi {
        private static boolean restarted;

        public static void restart() {
            restarted = true;
        }
    }

    public static final class ShutdownOnlyApi {
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }
}

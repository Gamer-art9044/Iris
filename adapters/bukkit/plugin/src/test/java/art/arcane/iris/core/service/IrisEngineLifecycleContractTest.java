package art.arcane.iris.core.service;

import art.arcane.iris.engine.framework.EngineAssignedWorldManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IrisEngineLifecycleContractTest {
    @Test
    public void engineServiceExclusivelyOwnsWorldUnload() throws NoSuchMethodException {
        assertMonitorUnloadHandler(IrisEngineSVC.class.getMethod("onWorldUnload", WorldUnloadEvent.class));
        for (Method method : EngineAssignedWorldManager.class.getDeclaredMethods()) {
            boolean handlesWorldUnload = method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == WorldUnloadEvent.class
                    && method.isAnnotationPresent(EventHandler.class);
            assertFalse("EngineAssignedWorldManager must defer world unload ownership to IrisEngineSVC.", handlesWorldUnload);
        }
    }

    @Test
    public void registrationIdentityConflictsByWorldIdentityOrFolder() {
        Path sharedFolder = Path.of("build", "worlds", "shared");
        IrisEngineSVC.RegistrationIdentity first =
                new IrisEngineSVC.RegistrationIdentity("minecraft:first", sharedFolder);
        IrisEngineSVC.RegistrationIdentity sameIdentity =
                new IrisEngineSVC.RegistrationIdentity("minecraft:first", Path.of("build", "worlds", "other"));
        IrisEngineSVC.RegistrationIdentity sameFolder =
                new IrisEngineSVC.RegistrationIdentity("minecraft:other", sharedFolder);
        IrisEngineSVC.RegistrationIdentity distinct =
                new IrisEngineSVC.RegistrationIdentity("minecraft:distinct", Path.of("build", "worlds", "distinct"));

        assertTrue(first.conflictsWith(sameIdentity));
        assertTrue(first.conflictsWith(sameFolder));
        assertFalse(first.conflictsWith(distinct));
    }

    @Test
    public void registrationRetryWaitsAsynchronouslyForClose() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.engineSvcSource")));
        String add = method(source, "private void add(World world)");
        String remove = method(source, "private void remove(World world, CompletionStage<Boolean> unloadBoundary)");
        String completeClose = method(source, "private void completeClose(");
        String retry = method(source, "private void retryRegistrationAfterClose(");
        String invokeClose = method(source, "private CompletableFuture<Void> invokeGeneratorClose()");

        assertBefore(add, "findClosingGenerator(registrationIdentity)", "new Registered(");
        assertBefore(remove, "registered.close()", "reserveClose(registered)");
        assertBefore(remove, "reserveClose(registered)", "deferCloseUntilWorldUnload(");
        assertBefore(completeClose, "if (failure == null)", "closingGenerators.remove(closing)");
        assertBefore(completeClose, "closingGenerators.remove(closing)", "closing.completion().complete(null)");
        assertBefore(completeClose, "} else {", "closing.completion().completeExceptionally(failure)");
        assertTrue(retry.contains("completion.whenComplete("));
        assertTrue(retry.contains("J.s(() -> add(world), 1);"));
        assertBefore(retry, "if (failure != null)", "J.s(() -> add(world), 1);");
        assertFalse(retry.contains("completion.get("));
        assertFalse(retry.contains("completion.join("));
        assertTrue(invokeClose.contains("CompletableFuture.failedFuture("));
        assertTrue(invokeClose.contains("future.whenComplete("));
    }

    @Test
    public void worldUnloadDefersGeneratorCloseUntilTheRawBoundaryCompletesTrue() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.engineSvcSource")));
        String handler = method(source, "public void onWorldUnload(WorldUnloadEvent event)");
        String remove = method(source, "private void remove(World world, CompletionStage<Boolean> unloadBoundary)");
        String defer = method(source, "private void deferCloseUntilWorldUnload(");

        assertBefore(handler, "WorldUnloadBoundaryRegistry.claim(", "remove(world, unloadBoundary)");
        assertBefore(remove, "registered.close()", "deferCloseUntilWorldUnload(");
        assertFalse(remove.contains("startClose(registered, closing)"));
        assertTrue(defer.contains("J.sfut(() -> startClose(registered, closing), 1)"));
        assertTrue(defer.contains("unloadBoundary.whenComplete("));
        String managedBoundary = defer.substring(defer.indexOf("unloadBoundary.whenComplete("));
        assertBefore(managedBoundary, "failure == null && Boolean.TRUE.equals(unloaded)",
                "startClose(registered, closing)");
        assertBefore(managedBoundary, "startClose(registered, closing)", "abandonClose(world, closing)");
    }

    private static void assertMonitorUnloadHandler(Method method) {
        EventHandler eventHandler = method.getAnnotation(EventHandler.class);
        assertNotNull(eventHandler);
        assertEquals(EventPriority.MONITOR, eventHandler.priority());
        assertTrue(eventHandler.ignoreCancelled());
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("Missing source contract token: " + first, firstIndex >= 0);
        assertTrue("Missing source contract token: " + second, secondIndex >= 0);
        assertTrue(first + " must occur before " + second, firstIndex < secondIndex);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Missing source contract signature: " + signature, start >= 0);
        int openBrace = source.indexOf('{', start);
        assertTrue("Missing source contract method body: " + signature, openBrace >= 0);
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new IllegalArgumentException("Unclosed source contract method: " + signature);
    }
}

package art.arcane.iris.core.service;

import art.arcane.iris.core.lifecycle.LifecycleOperationCoordinator;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.PackDownloadMessages;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioSVCPackDownloadContractTest {
    @Test
    public void downloadsUseReporterCompletionWithoutMutatingLiveDatapacks() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/service/StudioSVC.java"));

        assertTrue(source.contains("reporter.succeed(result);"));
        assertFalse(method(source, "public void downloadBuiltIn(VolmitSender sender, String key)")
                .contains("ServerConfigurator.restart()"));
        assertFalse(method(source, "public void downloadUrl(VolmitSender sender, String url)")
                .contains("installDataPacksIfChanged"));
    }

    @Test
    public void downloadLeaseIsAcquiredBeforeUnconditionalIoDispatch() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/service/StudioSVC.java"));
        int mutationStart = source.indexOf("private void runPackMutation(");
        int mutationEnd = source.indexOf("private void executePackMutation(", mutationStart);
        String runPackMutation = source.substring(mutationStart, mutationEnd);
        int leaseAcquisition = runPackMutation.indexOf("LifecycleOperationCoordinator.get().acquire(");
        int executionTracking = runPackMutation.indexOf("new PackDownloadExecution(");
        int ioDispatch = runPackMutation.indexOf("MultiBurst.ioBurst.submit(");

        assertTrue(leaseAcquisition >= 0);
        assertTrue(executionTracking > leaseAcquisition);
        assertTrue(ioDispatch > leaseAcquisition);
        assertTrue(runPackMutation.contains("execution.bind(future);"));
        assertTrue(runPackMutation.contains("execution.cancel();"));
        assertTrue(runPackMutation.contains("finally"));
        assertTrue(runPackMutation.contains("reporter.start();"));
        assertTrue(runPackMutation.contains("reporter.executionComplete();"));
        assertFalse(runPackMutation.contains("runOffPrimaryThread"));
    }

    @Test
    public void acceptedDownloadsRouteFeedbackProgressAndTerminalStatesThroughReporter() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/service/StudioSVC.java"));
        String builtIn = method(source, "public void downloadBuiltIn(VolmitSender sender, String key)");
        String remote = method(source, "public void downloadUrl(VolmitSender sender, String url)");
        String execute = method(source, "private void executePackMutation(");

        assertTrue(builtIn.contains("PackDownloadProgressReporter reporter"));
        assertTrue(remote.contains("PackDownloadProgressReporter reporter"));
        assertTrue(remote.contains("reporter::detail"));
        assertTrue(remote.contains("\"remote-zip\", reporter"));
        assertTrue(remote.contains("cancellation,"));
        assertTrue(remote.contains("reporter"));
        assertTrue(execute.contains("reporter.cancel();"));
        assertTrue(execute.contains("reporter.fail(e);"));
    }

    @Test
    public void shutdownClosesAdmissionAndDrainsTrackedDownloadBeforeServiceTeardown() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/service/StudioSVC.java"));
        String onDisable = method(source, "public void onDisable()");
        String quiesce = method(source, "public void quiesceDownloadsForShutdown()");

        assertTrue(onDisable.contains("quiesceDownloadsForShutdown();"));
        assertTrue(quiesce.contains("downloadAdmissionOpen = false;"));
        assertTrue(quiesce.contains("execution.cancel();"));
        assertTrue(quiesce.contains("execution.await("));
        assertTrue(quiesce.contains("while (!execution.isComplete())"));
    }

    @Test
    public void stackedDownloadUsesLocalizedBusyMessage() {
        LifecycleOperationCoordinator.ActiveOperation download = new LifecycleOperationCoordinator.ActiveOperation(
                1L,
                LifecycleOperationCoordinator.Domain.PACK_MUTATION,
                LifecycleOperationCoordinator.OperationKind.PACK_DOWNLOAD,
                "overworld"
        );
        LifecycleOperationCoordinator.ActiveOperation worldCreation = new LifecycleOperationCoordinator.ActiveOperation(
                2L,
                LifecycleOperationCoordinator.Domain.WORLD_MUTATION,
                LifecycleOperationCoordinator.OperationKind.WORLD_CREATE,
                "iris_world"
        );

        assertEquals(
                IrisLanguage.plain(PackDownloadMessages.IN_PROGRESS),
                StudioSVC.packMutationBusyMessage(download)
        );
        assertEquals(
                "Iris pack changes are busy with world_create for 'iris_world'. Try again when it completes.",
                StudioSVC.packMutationBusyMessage(worldCreation)
        );
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

package art.arcane.iris.core.runtime;

import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.tools.IrisCreator;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioOpenCoordinatorOpenKindTest {
    @Test
    public void standardStudioOwnsWorkspaceAndEntryTeleport() {
        StudioOpenCoordinator.StudioOpenKind kind = StudioOpenCoordinator.StudioOpenKind.STANDARD;

        assertTrue(kind.openWorkspace());
        assertTrue(kind.teleportThroughStandardEntry());
        assertTrue(kind.prepareGeneratorState());
        assertEquals(
                IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY,
                kind.datapackPreparation());
    }

    @Test
    public void bothStudioKindsReuseOnlyAnUnchangedLoadedRuntime() {
        for (StudioOpenCoordinator.StudioOpenKind kind : StudioOpenCoordinator.StudioOpenKind.values()) {
            assertEquals(
                    IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY,
                    kind.datapackPreparation());
        }
    }

    @Test
    public void jigsawStudioLeavesWorkspaceClosedAndUsesItsDestinationTeleport() {
        StudioOpenCoordinator.StudioOpenKind kind = StudioOpenCoordinator.StudioOpenKind.JIGSAW;

        assertFalse(kind.openWorkspace());
        assertFalse(kind.teleportThroughStandardEntry());
        assertFalse(kind.prepareGeneratorState());
        assertEquals(
                IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY,
                kind.datapackPreparation());
    }

    @Test
    public void studioRequestRetainsExplicitOpenKind() {
        IrisProject project = new IrisProject(new File("overworld"));
        StudioOpenCoordinator.StudioOpenRequest request =
                StudioOpenCoordinator.StudioOpenRequest.studioProject(
                        project,
                        null,
                        1337L,
                        StudioOpenCoordinator.StudioOpenKind.JIGSAW,
                        null,
                        null);

        assertEquals(StudioOpenCoordinator.StudioOpenKind.JIGSAW, request.openKind());
    }

    @Test
    public void studioTimingSeparatesOrderedLifecyclePhases() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java"));
        int previous = -1;
        for (String phase : List.of(
                "resolve_dimension_and_cleanup",
                "create_world_total",
                "apply_world_rules",
                "prepare_generator",
                "resolve_entry_anchor",
                "load_entry_chunk",
                "resolve_safe_entry",
                "teleport_standard_entry",
                "finalize_open")) {
            int current = source.indexOf("\"" + phase + "\"", previous + 1);
            assertTrue("Missing or out-of-order Studio timing phase " + phase, current > previous);
            previous = current;
        }
        assertTrue(source.contains("long openStart = System.nanoTime();"));
    }

    @Test
    public void structureStateActivatesAfterStandardTeleportBeforeFinalization() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java"));
        int entryReady = source.indexOf(
                "entryLoadFuture.get(STUDIO_ENTRY_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)");
        int teleport = source.indexOf(
                "WorldRuntimeControlService.get().teleport(player, safeEntry)", entryReady);
        int completionCall = source.indexOf("endStudioEntryBootstrap(world, provider)", teleport);
        int finalizeOpen = source.indexOf(
                "updateStage(request, \"finalize_open\", 1.00D)", completionCall);
        int futureComplete = source.indexOf(
                "future.complete(new StudioOpenResult(world, safeEntry))", finalizeOpen);
        int methodStart = source.indexOf(
                "private void endStudioEntryBootstrap(World world, PlatformChunkGenerator provider)");
        int methodEnd = source.indexOf("private void abandonStudioEntryBootstrap", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int scheduled = method.indexOf("J.sfut(() ->");
        int claim = method.indexOf("activationClaim.compareAndSet(true, false)");
        int activation = method.indexOf("INMS.get().completeStudioStructureBootstrap(world)");
        int gateRelease = method.indexOf("bukkitGenerator.endStudioEntryBootstrap()");

        assertTrue(entryReady >= 0);
        assertTrue(teleport > entryReady);
        assertTrue(completionCall > teleport);
        assertTrue(finalizeOpen > completionCall);
        assertTrue(futureComplete > finalizeOpen);
        assertTrue(scheduled >= 0);
        assertTrue(claim > scheduled);
        assertTrue(activation > claim);
        assertTrue(gateRelease > activation);
        assertTrue(source.contains("abandonStudioEntryBootstrap(world, e);"));
    }

    @Test
    public void failedOpenMarshalsRetainedStateAbandonmentToTheServerScheduler() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java"));
        int methodStart = source.indexOf(
                "private void abandonStudioEntryBootstrap(World world, Throwable failure)");
        int methodEnd = source.indexOf("private void deferFailedOpenCleanup", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(method.contains("if (J.isPrimaryThread())"));
        assertTrue(method.contains("CompletableFuture<Void> abandonment = J.sfut("));
        assertTrue(method.contains("abandonment.get(STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS"));
    }

    @Test
    public void openFinalizerReturnsToTheServerThreadBeforeCompletion() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java"));
        int finalizerCall = source.indexOf("runOpenFinalizer(request.onDone(), world);");
        int futureCompletion = source.indexOf(
                "future.complete(new StudioOpenResult(world, safeEntry))", finalizerCall);
        int methodStart = source.indexOf(
                "private void runOpenFinalizer(Consumer<World> finalizer, World world)");
        int methodEnd = source.indexOf("private long elapsedMillis", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(finalizerCall >= 0);
        assertTrue(futureCompletion > finalizerCall);
        assertTrue(method.contains("if (J.isPrimaryThread())"));
        assertTrue(method.contains("J.sfut(() -> finalizer.accept(world))"));
        assertTrue(method.contains(
                "completion.get(STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)"));
    }
}

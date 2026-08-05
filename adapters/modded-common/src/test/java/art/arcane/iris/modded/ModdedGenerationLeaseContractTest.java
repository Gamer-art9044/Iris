package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedGenerationLeaseContractTest {
    private static final String SOURCE_ROOT_PROPERTY = "iris.moddedCommonSources";

    @Test
    public void biomeRuntimeReadsAreGenerationLeased() throws IOException {
        String source = source("art/arcane/iris/modded/IrisModdedBiomeSource.java");

        assertTrue(source.contains("tryAcquireGenerationLease(engine, \"modded_structure_biome\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(engine, \"modded_visible_biome\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(engine, \"modded_biomes_within\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(engine, \"modded_structure_reachability\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(engine, \"modded_structure_state_biome\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(engine, \"modded_structure_biome_keys\")"));
        assertTrue(source.contains("engine == null || engine.isClosed()"));
        assertTrue(source.contains("catch (GenerationSessionException e)"));
        assertTrue(source.contains("e.isExpectedTeardown()"));
        assertTrue(source.contains("Iris structure biome key lookup was rejected during an engine transition"));
        assertTrue(source.contains("source.clearCache()"));
    }

    @Test
    public void terrainAndHeightQueriesShareGenerationSessions() throws IOException {
        String source = source("art/arcane/iris/modded/IrisModdedChunkGenerator.java");

        assertTrue(source.contains("generationEngine.acquireGenerationLease(\"modded_chunk_pipeline\")"));
        assertTrue(source.contains("current.acquireGenerationLease(\"modded_base_height\")"));
        assertTrue(source.contains("current.acquireGenerationLease(\"modded_base_column\")"));
        assertTrue(source.contains("current.acquireGenerationLease(\"modded_configured_biome_keys\")"));
        assertTrue(source.contains("generationEngine.isClosing() || e.isExpectedTeardown()"));
        String terrain = method(source, "private ChunkAccess generateTerrain(");
        int sessionFailure = terrain.indexOf("catch (GenerationSessionException e)");
        int generalFailure = terrain.indexOf("catch (Throwable e)", sessionFailure);
        String sessionHandler = terrain.substring(sessionFailure, generalFailure);
        assertTrue(sessionHandler.contains("throw new IllegalStateException"));
        assertFalse(sessionHandler.contains("return chunk;"));

        String references = method(source, "public void createReferences(");
        assertTrue(references.contains("requireGenerationLease(current, \"modded_create_references\")"));
        assertTrue(references.contains("IrisContext.open(current, lease.sessionId(), null)"));
        assertTrue(references.contains("NativeStructureReferenceRepair.createReferences("));
        assertFalse(references.contains("super.createReferences(level, structureManager, chunk);"));
    }

    @Test
    public void preservationDereferencesSharedPackData() throws IOException {
        String source = source("art/arcane/iris/modded/service/ModdedPreservationService.java");
        int dereference = source.indexOf("public void dereference()");
        int irisData = source.indexOf("IrisData.dereference();", dereference);
        int trackedResources = source.indexOf("threads.removeIf", dereference);

        assertTrue(dereference >= 0);
        assertTrue(irisData > dereference);
        assertTrue(trackedResources > irisData);
    }

    @Test
    public void hotloadShutdownOnlyTargetsItsOwnWorld() throws IOException {
        String source = source("art/arcane/iris/modded/service/ModdedStudioHotloadService.java");
        int shutdown = source.indexOf("public void shutdownPregenerator(Engine engine)");
        int nextMethod = source.indexOf("private boolean throttled", shutdown);
        String method = source.substring(shutdown, nextMethod);

        assertTrue(method.contains("ModdedPregenJob.shutdownForWorld(world.identity());"));
        assertFalse(method.contains("PregeneratorJob.shutdownInstance();"));
    }

    @Test
    public void maintenanceOwnsOneGenerationSessionUntilWorkCompletes() throws IOException {
        String source = source("art/arcane/iris/modded/service/ModdedEngineMaintenanceService.java");
        String maintain = method(source, "private void maintain(Engine engine, long scheduledAt)");

        assertTrue(maintain.contains("engine.acquireGenerationLease(\"modded_engine_maintenance\")"));
        assertTrue(maintain.contains("IrisContext.open(engine, lease.sessionId(), null)"));
        assertTrue(maintain.contains("exception.isExpectedTeardown()"));
        assertTrue(source.contains("active.awaitTermination(INTERRUPT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)"));
    }

    @Test
    public void blockingPregenShutdownDefersItsFinalSaveToTheServerThread() throws IOException {
        String jobSource = source("art/arcane/iris/modded/command/ModdedPregenJob.java");
        String shutdown = method(jobSource, "private static boolean shutdownAndSave(");
        assertTrue(shutdown.contains("deferFinalSaveToServerThread()"));
        assertTrue(shutdown.contains("completeDeferredFinalSave()"));
        assertTrue(shutdown.contains("cancelDeferredFinalSave()"));

        String methodSource = source("art/arcane/iris/modded/command/ModdedPregenMethod.java");
        String close = method(methodSource, "public void close()");
        assertTrue(close.contains("deferFinalSaveIfRequested()"));

        String await = method(methodSource, "private void awaitFinalSave(");
        assertTrue(await.contains("FINAL_SAVE_POLL_MILLIS"));
        assertTrue(await.contains("deferFinalSaveIfRequested()"));

        String complete = method(methodSource, "void completeDeferredFinalSave()");
        int cancel = complete.indexOf("cancelQueuedFinalSave();");
        int directSave = complete.indexOf("saveLevelOnServerThread();");
        assertTrue(cancel >= 0);
        assertTrue(directSave > cancel);

        String queuedSave = method(methodSource, "private void executeFinalSave(");
        assertTrue(queuedSave.contains("if (!request.claim())"));
        String cancelRequest = method(methodSource, "private void cancelQueuedFinalSave()");
        assertTrue(cancelRequest.contains("request.cancel();"));
        String pending = method(methodSource, "boolean hasPendingFinalSave()");
        assertTrue(pending.contains("queuedFinalSave.get() != null"));
    }

    private static String source(String relativePath) throws IOException {
        String root = System.getProperty(SOURCE_ROOT_PROPERTY);
        assertTrue("Missing system property " + SOURCE_ROOT_PROPERTY, root != null && !root.isBlank());
        return Files.readString(Path.of(root, relativePath));
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

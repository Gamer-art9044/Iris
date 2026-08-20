package art.arcane.iris.core.service;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisApiWiringContractTest {
    @Test
    public void theTerrainServiceRegistersOnBothRegistriesAndReleasesBoth() throws IOException {
        String source = source("iris.terrainSvcSource");
        String onEnable = method(source, "public void onEnable()");
        String onDisable = method(source, "public void onDisable()");

        assertTrue(onEnable.contains("Bukkit.getServicesManager().register("));
        assertTrue(onEnable.contains("IrisTerrainService.class"));
        assertTrue(onEnable.contains("IrisServices.register(IrisTerrainService.class, this)"));
        assertTrue(onDisable.contains("Bukkit.getServicesManager().unregister(IrisTerrainService.class, this)"));
        assertTrue(onDisable.contains("IrisServices.remove(IrisTerrainService.class)"));
        assertBefore(onDisable, "serviceEnabled.set(false)", "Bukkit.getServicesManager().unregister(");
    }

    @Test
    public void theTerrainServiceNeverForcesEngineInitialisationOrLoadsTheMantle() throws IOException {
        String source = source("iris.terrainSvcSource");

        assertFalse("isIrisWorld must not touch the generator", source.contains("IrisToolbelt"));
        assertFalse("isIrisWorld must not touch the generator", source.contains(".touch("));
        assertFalse("terrain queries must not load mantle chunks", source.contains("getMantle()"));
        assertFalse("terrain queries must not load mantle chunks", source.contains("getObjectsAt("));
        assertFalse("terrain queries must not load mantle chunks", source.contains("getPOIsAt("));
        assertFalse("terrain queries must not load mantle chunks", source.contains("getCaveOrMantleBiome("));
        assertFalse("terrain queries must never block", source.contains(".join()"));
        assertFalse("terrain queries must never block", source.contains("synchronized"));
        assertFalse("terrain queries must never block", source.contains("J.sfut("));
    }

    @Test
    public void sampleColumnsBoundsTheQueryBeforeItWalksAndTreatsAThrowingSinkAsARefusal() throws IOException {
        String sampleColumns = method(source("iris.terrainSvcSource"),
                "public boolean sampleColumns(World world, IrisColumnQuery query, IrisColumnSink sink)");

        assertBefore(sampleColumns, "IrisSampleLimits.withinLimits(", "IrisColumnWalk.walk(");
        assertBefore(sampleColumns, "IrisColumnWalk.walk(", "catch (Throwable error)");
        assertTrue(sampleColumns.contains("reportSinkFault(world, error)"));
        assertTrue(sampleColumns.contains("return false;"));
        assertTrue("the walk must abort when the engine closes underneath it",
                sampleColumns.contains("engine.isClosed()"));
    }

    @Test
    public void thePregeneratorDispatchesPhasesFromItsExistingTickAndNeverBlocksOnTheSink() throws IOException {
        String source = source("iris.pregeneratorJobSource");
        String dispatch = method(source, "private void dispatchApiPhases(List<PregenApiPhase> phases)");

        assertTrue(dispatch.contains("IrisServices.getOrNull(PregenApiSink.class)"));
        assertTrue(dispatch.contains("catch (Throwable error)"));
        assertBefore(dispatch, "sink.pregen(phase, progress)", "catch (Throwable error)");
        assertFalse(dispatch.contains(".join()"));

        assertTrue(method(source, "public void onTick(").contains("dispatchApiPhases(apiPhases.onTick(paused()))"));
        assertTrue(method(source, "public void onSaving()").contains("dispatchApiPhases(apiPhases.onSaving())"));
        assertTrue(method(source, "public void onClose()").contains("dispatchApiPhases(apiPhases.onClose(reachedTotal()))"));
    }

    @Test
    public void worldPhasesAreFiredOutsideTheRegistrationLock() throws IOException {
        String source = source("iris.engineSvcSource");

        String add = method(source, "private void add(World world)");
        assertBefore(add, "catch (RejectedExecutionException exception)", "phases.ready(world)");
        assertBefore(add, "registered = true;", "phases.ready(world)");

        String remove = method(source,
                "private boolean remove(World world, CompletionStage<Boolean> unloadBoundary)");
        assertBefore(remove, "registered = worlds.remove(world)", "phases.closing(world)");
        assertBefore(remove, "phases.closing(world)",
                "deferCloseUntilWorldUnload(world, registered, closing, unloadBoundary)");
    }

    @Test
    public void everyRegisteredWorldIsAnnouncedClosingWhenTheServiceShutsDown() throws IOException {
        String onDisable = method(source("iris.engineSvcSource"), "public void onDisable()");

        assertTrue("service shutdown must announce ENGINE_CLOSING for every registered world",
                onDisable.contains("phases.closing(teardown.world())"));
        assertBefore(onDisable, "worlds.clear()", "phases.closing(teardown.world())");
        assertBefore(onDisable, "phases.closing(teardown.world())", "shutdownAndDrain(activeService)");
        assertBefore(onDisable, "phases.closing(teardown.world())",
                "startClose(teardown.registered(), teardown.closing())");
    }

    @Test
    public void aReplacedEngineIsAnnouncedClosingBeforeTheRetryReRegistersTheWorld() throws IOException {
        String add = method(source("iris.engineSvcSource"), "private void add(World world)");

        assertBefore(add, "catch (RejectedExecutionException exception)", "phases.closing(world)");
        assertBefore(add, "phases.closing(world)", "retryRegistrationAfterClose(world, retryAfter)");
        assertBefore(add, "phases.closing(world)", "startClose(replaced, replacementClose)");
    }

    @Test
    public void aWorldPhaseIsNeverConditionalOnAnotherServicesRegistration() throws IOException {
        String source = source("iris.apiEventSvcSource");
        String fire = method(source, "public static void fireWorldPhase(World world, IrisWorldPhase phase)");

        assertFalse("world lifecycle must not resolve a swappable service to build its payload",
                fire.contains("IrisServices"));
        assertFalse("world lifecycle must not resolve a swappable service to build its payload",
                fire.contains("IrisTerrainService"));
        assertTrue(fire.contains("deliver(new IrisWorldEngineEvent(world, phase, describe(world, phase)))"));

        String describe = method(source, "private static IrisWorldInfo describe(World world, IrisWorldPhase phase)");
        assertTrue(describe.contains("IrisWorldInfoFactory.forWorld(world)"));
        assertTrue("an undescribable world must be reported, not swallowed",
                describe.contains("IrisLogging.reportError("));
        assertTrue("an undescribable world must still deliver the phase", describe.contains("return null;"));
    }

    @Test
    public void aWorldPhaseRaisedFromAServerThreadIsDeliveredBeforeThatThreadMovesOn() throws IOException {
        String deliver = method(source("iris.apiEventSvcSource"), "private static void deliver(Event event)");

        assertBefore(deliver, "Bukkit.isPrimaryThread()", "Bukkit.getPluginManager().callEvent(event)");
        assertBefore(deliver, "Bukkit.getPluginManager().callEvent(event)", "Iris.callEvent(event)");
    }

    @Test
    public void theWorldInfoFactoryNeverForcesEngineInitialisationOrLoadsTheMantle() throws IOException {
        String source = source("iris.worldInfoFactorySource");

        assertFalse("the factory must not touch the generator", source.contains("IrisToolbelt"));
        assertFalse("the factory must not touch the generator", source.contains(".touch("));
        assertFalse("the factory must not load mantle chunks", source.contains("getMantle()"));
        assertFalse("the factory must never block", source.contains(".join()"));
        assertFalse("the factory must never block", source.contains("synchronized"));
        assertTrue("the factory must refuse a closed engine", source.contains("engine.isClosed()"));
    }

    @Test
    public void theHotloadHookStillFiresTheLegacyEventAlongsideTheApiEvent() throws IOException {
        String hook = method(source("iris.bukkitEnginePlatformHooksSource"), "public void fireHotloadEvent(Engine engine)");

        assertBefore(hook, "new IrisEngineHotloadEvent(engine)",
                "IrisApiEventSVC.fireWorldPhase(BukkitWorldBinding.world(engine.getWorld()), IrisWorldPhase.ENGINE_HOTLOADED)");
    }

    @Test
    public void theEventServiceNeverLetsAThirdPartyFailureEscapeALifecyclePath() throws IOException {
        String fire = method(source("iris.apiEventSvcSource"),
                "public static void fireWorldPhase(World world, IrisWorldPhase phase)");

        assertTrue(fire.contains("catch (Throwable error)"));
        assertTrue(fire.contains("IrisLogging.reportError("));
        assertFalse(fire.contains("printStackTrace"));
    }

    private static String source(String property) throws IOException {
        String path = System.getProperty(property);
        assertTrue("missing source property " + property, path != null && !path.isBlank());
        return Files.readString(Path.of(path));
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

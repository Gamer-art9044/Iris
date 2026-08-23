package art.arcane.iris.core.service;

import art.arcane.iris.api.terrain.IrisColumnField;
import art.arcane.iris.api.terrain.IrisColumnQuery;
import art.arcane.iris.api.terrain.IrisRiverState;
import art.arcane.iris.api.terrain.IrisSurfaceKind;
import art.arcane.iris.api.terrain.IrisTerrainService;
import art.arcane.iris.engine.river.RiverEdgeId;
import art.arcane.iris.engine.river.RiverNodeId;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.runtime.IrisRiverSurfaceSample;
import art.arcane.iris.util.common.plugin.IrisService;
import org.bukkit.World;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisTerrainSVCTest {
    private static final IrisColumnQuery SMALL = IrisColumnQuery.rect(
            0, 0, 15, 15, 4, EnumSet.of(IrisColumnField.SURFACE_HEIGHT, IrisColumnField.SURFACE_KIND));

    @Test
    public void theServiceImplementsBothContracts() throws NoSuchMethodException {
        assertTrue(IrisService.class.isAssignableFrom(IrisTerrainSVC.class));
        assertTrue(IrisTerrainService.class.isAssignableFrom(IrisTerrainSVC.class));
        IrisTerrainSVC.class.getDeclaredConstructor();
    }

    @Test
    public void aQueryIsOnlyAnswerableWhileEnabledAndAgainstARealWorld() {
        assertTrue(IrisTerrainSVC.answerable(true, true));
        assertFalse("a disabled service must never resolve a generator",
                IrisTerrainSVC.answerable(false, true));
        assertFalse(IrisTerrainSVC.answerable(true, false));
        assertFalse(IrisTerrainSVC.answerable(false, false));
    }

    @Test
    public void aServiceThatIsNotEnabledAnswersAbsenceInsteadOfThrowing() {
        IrisTerrainSVC service = new IrisTerrainSVC();

        assertFalse(service.isIrisWorld(null));
        assertTrue(service.worldInfo(null).isEmpty());
        assertEquals(OptionalInt.empty(), service.surfaceHeight(null, 0, 0));
        assertEquals(IrisSurfaceKind.UNKNOWN, service.surfaceKind(null, 0, 0));
        assertTrue(service.surfaceBiomeKey(null, 0, 0).isEmpty());
        assertTrue(service.surfaceBiomeName(null, 0, 0).isEmpty());
        assertTrue(service.biomeKey(null, 0, 64, 0).isEmpty());
        assertTrue(service.regionKey(null, 0, 0).isEmpty());
        assertTrue(service.regionName(null, 0, 0).isEmpty());
    }

    @Test
    public void theDisplayNameAccessorsReadNamesRatherThanLoadKeys() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.terrainSvcSource")));

        assertTrue("surfaceBiomeName must read the biome display name",
                source.contains("return name(engine.getSurfaceBiome(blockX, blockZ));"));
        assertTrue("the biome name helper must read getName()",
                source.contains("String name = biome == null ? null : biome.getName();"));
        assertTrue("regionName must read the region display name",
                source.contains("String name = region == null ? null : region.getName();"));
    }

    @Test
    public void theServiceExposesDisplayNamesAlongsideLoadKeys() throws NoSuchMethodException {
        assertEquals(Optional.class,
                IrisTerrainService.class.getMethod("surfaceBiomeName", World.class, int.class, int.class)
                        .getReturnType());
        assertEquals(Optional.class,
                IrisTerrainService.class.getMethod("regionName", World.class, int.class, int.class)
                        .getReturnType());
    }

    @Test
    public void anUnanswerableSampleNeverTouchesTheSink() {
        IrisTerrainSVC service = new IrisTerrainSVC();
        AtomicInteger sinkCalls = new AtomicInteger();

        boolean answered = service.sampleColumns(null, SMALL, sample -> sinkCalls.incrementAndGet());

        assertFalse(answered);
        assertEquals(0, sinkCalls.get());
    }

    @Test
    public void nullArgumentsAreRefusedRatherThanDereferenced() {
        IrisTerrainSVC service = new IrisTerrainSVC();

        assertFalse(service.sampleColumns(null, null, null));
        assertFalse(service.sampleColumns(null, SMALL, null));
    }

    @Test
    public void riverRouteStatesMapToThePublicDiagnosticStates() {
        assertEquals(IrisRiverState.NONE, IrisTerrainSVC.riverState(
                new IrisRiverSurfaceSample(RiverSample.none(), 70D, 70D, 70D, false, false)
        ));
        assertEquals(IrisRiverState.WET, IrisTerrainSVC.riverState(river(RiverRouteState.WET)));
        assertEquals(IrisRiverState.DRY, IrisTerrainSVC.riverState(river(RiverRouteState.DRY)));
        assertEquals(IrisRiverState.NONE, IrisTerrainSVC.riverState(river(RiverRouteState.SUPPRESSED)));
    }

    private static IrisRiverSurfaceSample river(RiverRouteState state) {
        RiverSection section = switch (state) {
            case WET -> RiverSection.CHANNEL;
            case DRY -> RiverSection.DRY_CHANNEL;
            case SUPPRESSED -> RiverSection.NONE;
        };
        RiverSample sample = new RiverSample(
                true,
                state,
                section,
                0D,
                0.5D,
                1D,
                1,
                1,
                8D,
                4D,
                3D,
                false,
                RiverEdgeId.of(new RiverNodeId(0, 0), new RiverNodeId(1, 0))
        );
        return new IrisRiverSurfaceSample(sample, 70D, 60D, 63D, false, state == RiverRouteState.WET);
    }
}

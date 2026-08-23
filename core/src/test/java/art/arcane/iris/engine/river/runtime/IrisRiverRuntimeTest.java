package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisGeneratorStyle;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverCaveMode;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.iris.engine.object.IrisRiverRoutingPolicy;
import art.arcane.iris.engine.object.IrisRiverTerminalMode;
import art.arcane.iris.engine.object.IrisRiverWaterMode;
import art.arcane.iris.engine.object.IrisStyledRange;
import art.arcane.iris.engine.object.NoiseStyle;
import art.arcane.iris.engine.river.RiverEdgeId;
import art.arcane.iris.engine.river.RiverNode;
import art.arcane.iris.engine.river.RiverNodeId;
import art.arcane.iris.engine.river.RiverPolyline;
import art.arcane.iris.engine.river.RiverReach;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverAnchor;
import art.arcane.iris.engine.river.RiverRoutingContext;
import art.arcane.iris.engine.river.RiverTerrainNodeSample;
import art.arcane.iris.engine.river.RiverTerrainSourceSample;
import art.arcane.iris.util.project.interpolation.NoiseBounds;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.iris.util.project.stream.interpolation.Interpolated;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class IrisRiverRuntimeTest {
    @Test
    public void terminalTaperUsesMeasuredReachLength() {
        assertEquals(1D, IrisRiverRuntime.terminalWeight(40, 200D, 0.8D), 0D);
        assertEquals(0.5D, IrisRiverRuntime.terminalWeight(40, 200D, 0.9D), 0.0000001D);
        assertEquals(0.5D, IrisRiverRuntime.terminalWeight(40, 20D, 0.5D), 0.0000001D);
    }

    @Test
    public void footprintSamplingBuildsOnlyItsCenterTile() {
        IrisRiverNetwork configuration = configuration(false);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            runtime.sampleFootprint(-4096D, -4096D, 4096D, 4096D);

            assertEquals(1, runtime.completedTileCount());
        }
    }

    @Test
    public void settingsAtSkipsNaturalBiomeWhenBiomeOverridesAreUnreachable() {
        IrisRiverNetwork configuration = configuration(false);
        IrisRegion region = new IrisRegion().setRiverOverride(
                new IrisRiverOverride().setWidthMultiplier(1.75D)
        );
        IrisBiome biome = new IrisBiome().setInferredType(InferredType.LAND);
        AtomicInteger biomeSamples = new AtomicInteger();

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                constantHeight(80D),
                countedBiome(biome, biomeSamples),
                region,
                false,
                true,
                true,
                false
        )) {
            EffectiveRiverSettings settings = runtime.settingsAt(12D, -7D);

            assertEquals(1.75D, settings.widthMultiplier(), 0D);
            assertEquals(0, biomeSamples.get());
        }
    }

    @Test
    public void settingsAtSamplesNaturalBiomeWhenBiomeOverrideIsReachable() {
        IrisRiverNetwork configuration = configuration(false);
        IrisRegion region = new IrisRegion().setRiverOverride(
                new IrisRiverOverride().setWidthMultiplier(1.75D)
        );
        IrisBiome biome = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setWidthMultiplier(2.5D));
        AtomicInteger biomeSamples = new AtomicInteger();

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                constantHeight(80D),
                countedBiome(biome, biomeSamples),
                region,
                false,
                true,
                true,
                true
        )) {
            EffectiveRiverSettings settings = runtime.settingsAt(12D, -7D);

            assertEquals(2.5D, settings.widthMultiplier(), 0D);
            assertEquals(1, biomeSamples.get());
        }
    }

    @Test
    public void nodeSamplingResolvesInputsOnceAndSkipsZeroWeightSlope() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getTopology().setTerrainHeightWeight(0D);
        configuration.getTopology().setTerrainSlopeWeight(0D);
        IrisRegion region = new IrisRegion().setRiverOverride(
                new IrisRiverOverride().setRoutingCostMultiplier(2D)
        );
        IrisBiome biome = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride()
                        .setRoutingPolicy(IrisRiverRoutingPolicy.BLOCK)
                        .setRoutingCostMultiplier(0D));
        AtomicInteger heightSamples = new AtomicInteger();
        AtomicInteger slopeSamples = new AtomicInteger();
        AtomicInteger oceanSamples = new AtomicInteger();
        AtomicInteger biomeSamples = new AtomicInteger();
        AtomicInteger regionSamples = new AtomicInteger();
        ProceduralStream<Double> height = ProceduralStream.ofDouble((x, z) -> {
            heightSamples.incrementAndGet();
            return 80D;
        });
        ProceduralStream<Double> slope = ProceduralStream.ofDouble((x, z) -> {
            slopeSamples.incrementAndGet();
            return 0.25D;
        });
        ProceduralStream<Boolean> ocean = ProceduralStream.of((x, z) -> {
            oceanSamples.incrementAndGet();
            return false;
        }, Interpolated.BOOLEAN);
        ProceduralStream<IrisBiome> biomes = countedBiome(biome, biomeSamples);
        ProceduralStream<IrisRegion> regions = ProceduralStream.of(
                (x, z) -> {
                    regionSamples.incrementAndGet();
                    return region;
                },
                Interpolated.of(value -> 0D, value -> region)
        );

        try (IrisRiverRuntime runtime = new IrisRiverRuntime(new IrisRiverRuntimeContext(
                4829759234L,
                configuration,
                mock(IrisData.class),
                63,
                false,
                true,
                true,
                true,
                true,
                (x, z) -> new NoiseBounds(0D, 512D),
                height,
                slope,
                ocean,
                biomes,
                regions
        ))) {
            RiverTerrainNodeSample sample = runtime.sampleNode(12, -7);
            RiverTerrainSourceSample sourceSample = runtime.sampleSource(13, -8);

            assertEquals(63D, sample.naturalHeight(), 0D);
            assertFalse(sample.ocean());
            assertFalse(sample.riverAllowed());
            assertEquals(0D, sample.routingCost(), 0D);
            assertFalse(sourceSample.ocean());
            assertFalse(sourceSample.riverAllowed());
            assertEquals(0, heightSamples.get());
            assertEquals(0, slopeSamples.get());
            assertEquals(2, oceanSamples.get());
            assertEquals(2, biomeSamples.get());
            assertEquals(2, regionSamples.get());
        }
    }

    @Test
    public void wetTerminalRiverCarvesDownAndPublishesAFluidHead() {
        IrisRiverNetwork configuration = configuration(false);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            IrisRiverSurfaceSample sample = findRiver(runtime, RiverRouteState.WET);

            assertNotNull(sample);
            assertTrue(sample.river().present());
            assertTrue(sample.surfaceFluid());
            assertTrue(sample.terrainHeight() <= sample.naturalHeight());
            assertTrue(sample.waterSurfaceY() >= sample.terrainHeight());
            assertTrue(runtime.completedTileCount() <= 32);
        }
    }

    @Test
    public void failedOceanRouteCanProduceDryTerrainWithoutSurfaceWater() {
        IrisRiverNetwork configuration = configuration(true);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            IrisRiverSurfaceSample sample = findRiver(runtime, RiverRouteState.DRY);

            assertNotNull(sample);
            assertFalse(sample.surfaceFluid());
            assertTrue(sample.terrainHeight() <= sample.naturalHeight());
            assertTrue(sample.waterSurfaceY() == sample.terrainHeight());
        }
    }

    @Test
    public void localSinkholeOverrideMakesRequiredOceanTerminalWet() {
        IrisRiverNetwork configuration = configuration(true);
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion())) {
            IrisRiverSurfaceSample sample = findRiver(runtime, RiverRouteState.WET);

            assertNotNull(sample);
            assertTrue(sample.surfaceFluid());
        }
    }

    @Test
    public void sinkholeTerminalPublishesAGuaranteedSpecificCaveAnchor() {
        IrisRiverNetwork configuration = configuration(true);
        configuration.getTopology().setMaxRouteReaches(1);
        configuration.getCaves()
                .setMode(IrisRiverCaveMode.GROTTO_OR_CLOSED_COMPONENT)
                .setMaximumPerReach(1);
        configuration.getCaves().getEntry()
                .setChance(0D)
                .setInfluence(0D)
                .setStyle(flat());
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion())) {
            List<RiverAnchor> anchors = runtime.candidateAnchors(-128, -128, 256, 256, 16D, 994L);
            RiverAnchor terminal = null;
            for (RiverAnchor anchor : anchors) {
                if (runtime.isTerminalCaveAnchor(anchor)) {
                    terminal = anchor;
                    break;
                }
            }

            assertNotNull(terminal);
            assertTrue(runtime.acceptsCaveAnchor(terminal));
            IrisRiverSurfaceSample terminalSample = runtime.sample(
                    StrictMath.floor(terminal.x()),
                    StrictMath.floor(terminal.z())
            );
            assertTrue(terminalSample.surfaceFluid());
            assertTrue(Math.round(terminalSample.terrainHeight())
                    < Math.round(terminalSample.waterSurfaceY()));
            for (RiverAnchor anchor : anchors) {
                if (anchor.reachId().equals(terminal.reachId())
                        && !runtime.isTerminalCaveAnchor(anchor)) {
                    assertFalse(runtime.acceptsCaveAnchor(anchor));
                }
            }
            configuration.getCaves().setMaximumPerReach(0);
            assertFalse(runtime.acceptsCaveAnchor(terminal));
        }
    }

    @Test
    public void localSuppressOverrideRemovesDimensionSinkholeTerminalRoute() {
        IrisRiverNetwork configuration = configuration(true);
        configuration.getTerrain().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO);
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SUPPRESS));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion())) {
            assertFalse(hasRiver(runtime));
        }
    }

    @Test
    public void inactiveCaveHydrologySuppressesLocalSinkholeTerminalRoute() {
        IrisRiverNetwork configuration = configuration(true);
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion(), false)) {
            assertFalse(hasRiver(runtime));
        }
    }

    @Test
    public void caveEntryGateHonorsWetStateAndMaximumPerReach() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getCaves().setMode(IrisRiverCaveMode.FLOOD_CLOSED_COMPONENT);
        configuration.getCaves().setMaximumPerReach(1);
        configuration.getCaves().getEntry()
                .setChance(0.35D)
                .setInfluence(0D)
                .setStyle(flat());

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            List<RiverAnchor> anchors = runtime.candidateAnchors(0, 0, 256, 256, 16D, 773L);
            Map<RiverEdgeId, Integer> acceptedPerReach = new HashMap<>();
            boolean acceptedAfterRejectedRawIndex = false;
            for (RiverAnchor anchor : anchors) {
                if (runtime.acceptsCaveAnchor(anchor)) {
                    acceptedPerReach.merge(anchor.reachId(), 1, Integer::sum);
                    acceptedAfterRejectedRawIndex |= anchor.index() >= 1;
                }
            }

            assertFalse(acceptedPerReach.isEmpty());
            for (int accepted : acceptedPerReach.values()) {
                assertEquals(1, accepted);
            }
            assertTrue(acceptedAfterRejectedRawIndex);
        }
    }

    @Test
    public void finalPolylineSupercoverRejectsOneBlockedColumnMissedByWidthSpacing() {
        IrisRiverNetwork configuration = configuration(false);
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        IrisBiome blocked = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setRoutingPolicy(IrisRiverRoutingPolicy.BLOCK));
        ProceduralStream<IrisBiome> biomes = ProceduralStream.of(
                (x, z) -> x == 1D && z == 0D ? blocked : land,
                Interpolated.of(value -> 0D, value -> land)
        );

        try (IrisRiverRuntime runtime = runtime(configuration, constantHeight(80D), biomes)) {
            assertFalse(runtime.allowsReach(straightReach(80D, 79D)));
        }
    }

    @Test
    public void finalPolylineSupercoverRejectsOneUnincisableTerrainSpike() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getTerrain().setMaxIncision(48);
        ProceduralStream<Double> height = ProceduralStream.of(
                (x, z) -> x == 1D && z == 0D ? 200D : 80D,
                Interpolated.DOUBLE
        );

        try (IrisRiverRuntime runtime = runtime(configuration, height, constantLandBiome())) {
            assertFalse(runtime.allowsReach(straightReach(80D, 79D)));
        }
    }

    @Test
    public void longReachFeasibilityHasADeterministicSampleCeiling() {
        IrisRiverNetwork configuration = configuration(false);
        int[] heightSamples = new int[1];
        ProceduralStream<Double> height = ProceduralStream.ofDouble((x, z) -> {
            heightSamples[0]++;
            return 80D;
        });

        try (IrisRiverRuntime runtime = runtime(configuration, height, constantLandBiome())) {
            heightSamples[0] = 0;

            assertTrue(runtime.allowsReach(straightReach(80D, 79D, 4_096D)));
            assertEquals(IrisRiverRuntime.MAXIMUM_REACH_FEASIBILITY_SAMPLES, heightSamples[0]);
        }
    }

    @Test
    public void invariantFeasibilitySettingsAreResolvedOncePerReach() {
        IrisRiverNetwork configuration = configuration(false);
        AtomicInteger regionSamples = new AtomicInteger();
        IrisRegion region = new IrisRegion();
        ProceduralStream<IrisRegion> regions = ProceduralStream.of(
                (x, z) -> {
                    regionSamples.incrementAndGet();
                    return region;
                },
                Interpolated.of(value -> 0D, value -> region)
        );
        ProceduralStream<Double> height = constantHeight(80D);
        ProceduralStream<IrisBiome> biome = constantLandBiome();
        ProceduralStream<Boolean> oceans = ProceduralStream.of(
                (x, z) -> false,
                Interpolated.BOOLEAN
        );
        try (IrisRiverRuntime runtime = new IrisRiverRuntime(new IrisRiverRuntimeContext(
                4829759234L,
                configuration,
                mock(IrisData.class),
                63,
                false,
                true,
                false,
                false,
                false,
                (x, z) -> new NoiseBounds(0D, 512D),
                height,
                ProceduralStream.ofDouble((x, z) -> 0.025D),
                oceans,
                biome,
                regions
        ))) {
            assertTrue(runtime.allowsReach(straightReach(80D, 79D, 4_096D)));
            assertEquals(1, regionSamples.get());
        }
    }

    @Test
    public void feasibilityBoundsMatchFullIncisionAcrossRoundingBoundaries() {
        double[] heads = new double[]{62.5D, Math.nextDown(62.5D), Math.nextUp(62.5D), 63D};
        double[] heights = new double[]{62.49D, 62.5D, 63D, 80D};
        double[] incisions = new double[]{0D, 0.5D, 16D, 512D};
        double[] depths = new double[]{0.25D, 1D, 4D};
        double[] roughnessBounds = new double[]{0D, 0.75D, 2D};
        double[] bedNoiseValues = new double[]{-1D, 0D, 1D};
        for (double head : heads) {
            for (double naturalHeight : heights) {
                for (double maximumIncision : incisions) {
                    for (double depth : depths) {
                        for (double roughnessBound : roughnessBounds) {
                            int decision = IrisRiverRuntime.boundedFeasibility(
                                    naturalHeight,
                                    head,
                                    maximumIncision,
                                    depth,
                                    roughnessBound);
                            if (decision == IrisRiverRuntime.FEASIBILITY_SAMPLE_BED) {
                                continue;
                            }
                            for (double bedNoise : bedNoiseValues) {
                                double bedHeight = head - depth + roughnessBound * bedNoise;
                                double finalHeight = Math.min(
                                        naturalHeight,
                                        Math.max(
                                                bedHeight,
                                                naturalHeight - Math.max(0D, maximumIncision)));
                                boolean expected = Math.round(finalHeight) < Math.round(head);
                                assertEquals(expected, decision == IrisRiverRuntime.FEASIBILITY_ACCEPT);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void mantleBoreSkipsSurfaceFeasibilitySampling() {
        IrisRiverNetwork configuration = configuration(false);
        int[] heightSamples = new int[1];
        ProceduralStream<Double> height = ProceduralStream.ofDouble((x, z) -> {
            heightSamples[0]++;
            return 512D;
        });

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                height,
                constantLandBiome(),
                new IrisRegion(),
                true,
                false,
                false,
                true
        )) {
            heightSamples[0] = 0;

            assertTrue(runtime.allowsReach(straightReach(80D, 79D, 4_096D)));
            assertEquals(0, heightSamples[0]);
        }
    }

    @Test
    public void mantleBoreStillHonorsBlockedRoutingPolicy() {
        IrisRiverNetwork configuration = configuration(false);
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        IrisBiome blocked = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setRoutingPolicy(IrisRiverRoutingPolicy.BLOCK));
        ProceduralStream<IrisBiome> biomes = ProceduralStream.of(
                (x, z) -> x == 1D && z == 0D ? blocked : land,
                Interpolated.of(value -> 0D, value -> land)
        );

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                constantHeight(512D),
                biomes,
                new IrisRegion(),
                true,
                false,
                true,
                true
        )) {
            assertFalse(runtime.allowsReach(straightReach(80D, 79D)));
        }
    }

    @Test
    public void regionalDepthMultiplierCannotCollapseWetChannelBelowOneBlock() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getTerrain()
                .setDepth(range(2D))
                .setBedRoughness(0.65D)
                .setBedRoughnessStyle(flat());
        IrisRegion shallow = new IrisRegion()
                .setRiverOverride(new IrisRiverOverride().setDepthMultiplier(0.1D));

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                constantHeight(80D),
                constantLandBiome(),
                shallow
        )) {
            assertTrue(runtime.allowsReach(straightReach(80D, 79D)));
        }
    }

    @Test
    public void incisionLimitTurnsADeepWetReachIntoAHiddenMantleTunnel() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getTerrain().setMaxIncision(10);
        ProceduralStream<Double> height = constantHeight(100D);

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                height,
                constantLandBiome(),
                new IrisRegion(),
                true,
                false
        )) {
            IrisRiverSurfaceSample sample = null;
            IrisRiverTunnelSample tunnel = null;
            for (int x = -128; x < 192 && tunnel == null; x += 2) {
                for (int z = -128; z < 192; z += 2) {
                    IrisRiverSurfaceSample candidate = runtime.sample(x, z);
                    IrisRiverTunnelSample candidateTunnel = runtime.sampleTunnel(x, z);
                    if (candidate.river().present()
                            && candidate.river().state() == RiverRouteState.WET
                            && candidateTunnel != null) {
                        sample = candidate;
                        tunnel = candidateTunnel;
                        break;
                    }
                }
            }

            assertNotNull(sample);
            assertTrue(sample.subterranean());
            assertFalse(sample.surfaceFluid());
            assertEquals(sample.naturalHeight(), sample.terrainHeight(), 0D);
            assertNotNull(tunnel);
            assertTrue(tunnel.bedY() < tunnel.waterHeadY());
            assertTrue(tunnel.ceilingY() >= tunnel.waterHeadY());
        }
    }

    @Test
    public void terracedWaterUsesFixedInteriorPoolsAndPreservesNodeHeads() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getWater()
                .setMode(IrisRiverWaterMode.TERRACED)
                .setMaximumPoolRise(8)
                .setDropHeight(2)
                .setPoolLength(96);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            assertEquals(71D, runtime.terracedWaterSurface(72D, 64D, 600D, 155D / 600D), 0D);
            assertEquals(69D, runtime.terracedWaterSurface(72D, 64D, 600D, 156D / 600D), 0D);
            assertEquals(69D, runtime.terracedWaterSurface(72D, 64D, 600D, 251D / 600D), 0D);
            assertEquals(67D, runtime.terracedWaterSurface(72D, 64D, 600D, 252D / 600D), 0D);
            assertEquals(65D, runtime.terracedWaterSurface(72D, 64D, 600D, 443D / 600D), 0D);
            assertEquals(63D, runtime.terracedWaterSurface(72D, 64D, 600D, 444D / 600D), 0D);
            assertEquals(63D, runtime.terracedWaterSurface(72D, 64D, 600D, 1D), 0D);
            assertEquals(
                    runtime.terracedWaterSurface(72D, 64D, 600D, 1D),
                    runtime.terracedWaterSurface(64D, 63D, 600D, 0D),
                    0D
            );
            assertEquals(67D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.33D), 0D);
            assertEquals(65D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.34D), 0D);
            assertEquals(65D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.66D), 0D);
            assertEquals(63D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.67D), 0D);
        }
    }

    @Test
    public void terracedRiverMouthCannotRiseAboveNaturalOcean() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getWater()
                .setMode(IrisRiverWaterMode.TERRACED)
                .setMaximumPoolRise(8)
                .setDropHeight(1)
                .setPoolLength(128);
        RiverNode from = new RiverNode(
                new RiverNodeId(0L, 0L),
                -600D,
                0D,
                72D,
                72D,
                2D,
                0D,
                false,
                true
        );
        RiverNode to = new RiverNode(
                new RiverNodeId(1L, 0L),
                0D,
                0D,
                63D,
                63D,
                -Double.MAX_VALUE,
                0D,
                true,
                true
        );
        RiverReach mouth = new RiverReach(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                RiverRouteState.WET,
                1,
                1,
                4D,
                2D,
                4D,
                true,
                false,
                new RiverPolyline(new double[]{-600D, 0D}, new double[]{0D, 0D})
        );

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                constantHeight(80D),
                constantLandBiome(),
                new IrisRegion()
        )) {
            assertEquals(71D, runtime.waterSurface(mouth, 0.1D, false), 0D);
            assertEquals(63D, runtime.waterSurface(mouth, 0.9D, true), 0D);
        }
    }

    private static IrisRiverRuntime runtime(IrisRiverNetwork configuration) {
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        IrisRegion region = new IrisRegion();
        return runtime(configuration, land, region);
    }

    private static IrisRiverRuntime runtime(IrisRiverNetwork configuration, IrisBiome land, IrisRegion region) {
        return runtime(configuration, land, region, true);
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            IrisBiome land,
            IrisRegion region,
            boolean caveHydrologyActive
    ) {
        ProceduralStream<Double> height = ProceduralStream.of(
                (x, z) -> 180D - x * 0.01D - z * 0.015D,
                Interpolated.DOUBLE
        );
        ProceduralStream<IrisBiome> biome = ProceduralStream.of(
                (x, z) -> land,
                Interpolated.of(value -> 0D, value -> land)
        );
        return runtime(configuration, height, biome, region, caveHydrologyActive);
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome
    ) {
        return runtime(configuration, height, biome, new IrisRegion());
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome,
            IrisRegion region
    ) {
        return runtime(configuration, height, biome, region, true);
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome,
            IrisRegion region,
            boolean caveHydrologyActive
    ) {
        return runtime(configuration, height, biome, region, false, caveHydrologyActive);
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome,
            IrisRegion region,
            boolean boreMantleActive,
            boolean caveHydrologyActive
    ) {
        return runtime(
                configuration,
                height,
                biome,
                region,
                boreMantleActive,
                caveHydrologyActive,
                true,
                true
        );
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome,
            IrisRegion region,
            boolean boreMantleActive,
            boolean caveHydrologyActive,
            boolean blockingRoutingPossible,
            boolean biomeRiverOverridesPossible
    ) {
        ProceduralStream<Double> slope = ProceduralStream.ofDouble((x, z) -> 0.025D);
        ProceduralStream<Boolean> oceans = ProceduralStream.of(
                (x, z) -> height.getDouble(x, z) < 62D,
                Interpolated.BOOLEAN
        );
        ProceduralStream<IrisRegion> regions = ProceduralStream.of(
                (x, z) -> region,
                Interpolated.of(value -> 0D, value -> region)
        );
        return new IrisRiverRuntime(new IrisRiverRuntimeContext(
                4829759234L,
                configuration,
                mock(IrisData.class),
                63,
                boreMantleActive,
                caveHydrologyActive,
                blockingRoutingPossible,
                true,
                biomeRiverOverridesPossible,
                (x, z) -> new NoiseBounds(0D, 512D),
                height,
                slope,
                oceans,
                biome,
                regions
        ));
    }

    private static RiverRoutingContext straightReach(double fromHeight, double toHeight) {
        return straightReach(fromHeight, toHeight, 32D);
    }

    private static RiverRoutingContext straightReach(double fromHeight, double toHeight, double length) {
        RiverNode from = new RiverNode(
                new RiverNodeId(0L, 0L),
                0D,
                0D,
                fromHeight,
                fromHeight,
                fromHeight,
                fromHeight,
                false,
                true
        );
        RiverNode to = new RiverNode(
                new RiverNodeId(1L, 0L),
                length,
                0D,
                toHeight,
                toHeight,
                toHeight,
                toHeight,
                false,
                true
        );
        return new RiverRoutingContext(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                new RiverPolyline(new double[]{0D, length}, new double[]{0D, 0D})
        );
    }

    private static ProceduralStream<Double> constantHeight(double height) {
        return ProceduralStream.of((x, z) -> height, Interpolated.DOUBLE);
    }

    private static ProceduralStream<IrisBiome> constantLandBiome() {
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        return ProceduralStream.of(
                (x, z) -> land,
                Interpolated.of(value -> 0D, value -> land)
        );
    }

    private static ProceduralStream<IrisBiome> countedBiome(IrisBiome biome, AtomicInteger samples) {
        return ProceduralStream.of(
                (x, z) -> {
                    samples.incrementAndGet();
                    return biome;
                },
                Interpolated.of(value -> 0D, value -> biome)
        );
    }

    private static IrisRiverNetwork configuration(boolean requireOcean) {
        IrisRiverNetwork configuration = new IrisRiverNetwork().setEnabled(true);
        configuration.getTopology()
                .setCellSize(64)
                .setTileCells(1)
                .setSiteJitter(0D)
                .setMaxRouteReaches(4)
                .setSinkSearchReaches(3)
                .setRequireOcean(requireOcean);
        configuration.getTopology().getSource()
                .setChance(1D)
                .setInfluence(0D)
                .setStyle(flat());
        configuration.getTopology().getContinuation()
                .setChance(1D)
                .setInfluence(0D)
                .setStyle(flat());
        configuration.getTopology().setRoutingStyle(flat());
        configuration.getTerrain()
                .setChannelWidth(range(24D))
                .setBankWidth(range(12D))
                .setDepth(range(5D))
                .setMaxIncision(512)
                .setMeanderStrength(0D)
                .setMeanderStyle(flat())
                .setBedRoughness(0D)
                .setBedRoughnessStyle(flat())
                .setDryContinuationChance(1D);
        configuration.getTerrain().getIncision()
                .setChance(1D)
                .setInfluence(0D)
                .setStyle(flat());
        configuration.getBiomes().setSelectionStyle(flat());
        return configuration;
    }

    private static IrisRiverSurfaceSample findRiver(IrisRiverRuntime runtime, RiverRouteState state) {
        for (int x = -128; x < 192; x += 2) {
            for (int z = -128; z < 192; z += 2) {
                IrisRiverSurfaceSample sample = runtime.sample(x, z);
                if (sample.river().present() && sample.river().state() == state) {
                    return sample;
                }
            }
        }
        return null;
    }

    private static boolean hasRiver(IrisRiverRuntime runtime) {
        for (int x = 0; x < 64; x += 2) {
            for (int z = 0; z < 64; z += 2) {
                if (runtime.sample(x, z).river().present()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IrisStyledRange range(double value) {
        return new IrisStyledRange(value, value, flat());
    }

    private static IrisGeneratorStyle flat() {
        return new IrisGeneratorStyle(NoiseStyle.FLAT);
    }
}

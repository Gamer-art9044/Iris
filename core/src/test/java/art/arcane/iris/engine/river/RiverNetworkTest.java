package art.arcane.iris.engine.river;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RiverNetworkTest {
    @Test
    public void mapsNegativeWorldCoordinatesWithFloorDivision() {
        RiverNetwork network = new RiverNetwork(options(1L).build());
        RiverTerrainSampler terrain = slopedTerrain(false);

        assertEquals(new RiverNodeId(-1L, -1L), network.nodeAtWorld(-1, -1, terrain).id());
        assertEquals(new RiverNodeId(-1L, 0L), network.nodeAtWorld(-64, 0, terrain).id());
        assertEquals(new RiverNodeId(0L, 0L), network.nodeAtWorld(0, 0, terrain).id());
        assertEquals(-1, network.tileXForBlock(-1));
        assertEquals(0, network.tileXForBlock(0));
    }

    @Test
    public void expandedSamplingAddsOnlyTheRequestedLateralRadius() {
        RiverNode from = node(0L, 0L, 0D, 0D);
        RiverNode to = node(1L, 0L, 100D, 0D);
        RiverTile tile = new RiverTile(
                0,
                0,
                0,
                -64,
                128,
                64,
                List.of(reach(from, to, 4D, 1D))
        );

        assertFalse(tile.sample(50D, 5D).present());
        RiverSample expanded = tile.sampleExpanded(50D, 5D, 2D);
        assertTrue(expanded.present());
        assertEquals(RiverSection.BANK, expanded.section());
        assertEquals(5D, expanded.distance(), 0D);
        assertThrows(IllegalArgumentException.class, () -> tile.sampleExpanded(50D, 5D, -1D));
    }

    @Test
    public void graphHasReciprocalCardinalsAndOneStableDiagonalPerSquare() {
        RiverNetwork network = new RiverNetwork(options(2L).build());
        RiverNodeId origin = new RiverNodeId(0L, 0L);
        List<RiverNodeId> neighbors = network.neighbors(origin);

        assertTrue(neighbors.contains(new RiverNodeId(-1L, 0L)));
        assertTrue(neighbors.contains(new RiverNodeId(1L, 0L)));
        assertTrue(neighbors.contains(new RiverNodeId(0L, -1L)));
        assertTrue(neighbors.contains(new RiverNodeId(0L, 1L)));
        assertTrue(neighbors.size() >= 4 && neighbors.size() <= 8);
        for (RiverNodeId neighbor : neighbors) {
            assertTrue(network.neighbors(neighbor).contains(origin));
        }

        boolean ascending = network.neighbors(new RiverNodeId(0L, 0L)).contains(new RiverNodeId(1L, 1L));
        boolean descending = network.neighbors(new RiverNodeId(0L, 1L)).contains(new RiverNodeId(1L, 0L));
        assertNotEquals(ascending, descending);
    }

    @Test
    public void downstreamAlwaysLowersStrictRankAndCannotCycle() {
        RiverNetwork network = new RiverNetwork(options(3L).routingNoiseWeight(20.0).build());
        RiverTerrainSampler terrain = flatTerrain(false);
        RiverNode current = network.nodeAtCell(12L, -7L, terrain);
        Set<RiverNodeId> visited = new HashSet<>();

        for (int step = 0; step < 64; step++) {
            assertTrue(visited.add(current.id()));
            RiverNode next = network.downstream(current.id(), terrain);
            if (next == null) {
                break;
            }
            assertTrue(compareRank(next, current) < 0);
            assertTrue(next.ocean() || next.hydraulicHeight() <= current.hydraulicHeight());
            current = next;
        }
    }

    @Test
    public void routingPlateausCanCrossSmallNaturalRisesWithoutRaisingTheHydraulicHead() {
        RiverNetwork network = new RiverNetwork(options(71L)
                .siteJitter(0D)
                .routingPlateauHeight(16D)
                .routingNoiseWeight(0D)
                .build());
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double naturalHeight(int blockX, int blockZ) {
                if (blockX >= 0 && blockX < 64 && blockZ >= 0 && blockZ < 64) {
                    return 68D;
                }
                return blockX >= 64 ? 70D : 80D;
            }

            @Override
            public double routingCost(int blockX, int blockZ) {
                return blockX >= 64 ? 0D : 10D;
            }
        };

        RiverNode source = network.nodeAtCell(0L, 0L, terrain);
        RiverNode next = network.downstream(source.id(), terrain);

        assertNotNull(next);
        assertTrue(next.naturalHeight() > source.naturalHeight());
        assertTrue(next.hydraulicHeight() <= source.hydraulicHeight());
    }

    @Test
    public void longRoutesConvergeIntoDeterministicBranches() {
        RiverNetwork network = new RiverNetwork(options(72L)
                .tileCells(4)
                .maxRouteReaches(16)
                .routingPlateauHeight(8D)
                .requireOcean(false)
                .build());
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double naturalHeight(int blockX, int blockZ) {
                return 512D - blockX * 0.25D;
            }

            @Override
            public double routingCost(int blockX, int blockZ) {
                return StrictMath.abs(blockZ) * 0.5D;
            }

            @Override
            public double flowNoise(double x, double z) {
                return StrictMath.sin(x / 384D) + StrictMath.cos(z / 512D);
            }
        };

        RiverRoute route = network.trace(new RiverNodeId(0L, 4L), terrain);
        RiverTile tile = network.buildTile(0, 0, terrain);
        HashMap<RiverNodeId, Integer> incoming = new HashMap<>();
        for (RiverReach reach : tile.reaches()) {
            incoming.merge(reach.to().id(), 1, Integer::sum);
        }

        assertEquals(16, route.edges().size());
        assertTrue(incoming.values().stream().anyMatch(count -> count >= 2));
        assertEquals(digest(tile), digest(network.buildTile(0, 0, terrain)));
    }

    @Test
    public void recursiveBranchesApplyTheProfileChildSoftCapAtEveryNode() {
        RiverWorm constrained = wormProfile(
                "constrained",
                720L,
                6,
                1D,
                1D,
                1D,
                4,
                0D,
                1D,
                0D,
                0D,
                List.of()
        );
        RiverNetwork network = new RiverNetwork(options(720L)
                .requireOcean(false)
                .routingBasinCells(16)
                .worms(List.of(constrained))
                .build());
        RiverTerrainSampler terrain = flatTerrain(false);
        HashMap<RiverNodeId, Integer> incoming = new HashMap<>();
        for (long cellX = -24L; cellX <= 24L; cellX++) {
            for (long cellZ = -24L; cellZ <= 24L; cellZ++) {
                RiverNode downstream = network.downstream(new RiverNodeId(cellX, cellZ), terrain);
                if (downstream != null) {
                    incoming.merge(downstream.id(), 1, Integer::sum);
                }
            }
        }

        assertFalse(incoming.isEmpty());
        assertTrue(incoming.values().stream().allMatch(children -> children <= 4));
        assertTrue(incoming.values().stream().anyMatch(children -> children >= 3));
    }

    @Test
    public void candidateRankingAndTracingAreIndependentOfWormGeometry() {
        RiverNetwork straightNetwork = new RiverNetwork(options(75L)
                .requireOcean(false)
                .worms(List.of(worm(1L, 0D, 0D, 0D, 1)))
                .build());
        RiverNetwork windingNetwork = new RiverNetwork(options(75L)
                .requireOcean(false)
                .worms(List.of(worm(2L, 0.8D, 0.2D, 32D, 32)))
                .build());
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double reachRoutingCost(RiverRoutingContext context) {
                return context.midpointX() + context.midpointZ();
            }
        };
        RiverNodeId source = new RiverNodeId(0L, 0L);

        assertFalse(straightNetwork.downstreamCandidates(source, terrain).isEmpty());
        assertEquals(
                straightNetwork.downstreamCandidates(source, terrain),
                windingNetwork.downstreamCandidates(source, terrain)
        );
        assertEquals(straightNetwork.trace(source, terrain), windingNetwork.trace(source, terrain));
    }

    @Test
    public void routingFieldAlignmentSelectsTheMostParallelDownstreamReach() {
        RiverNetwork reference = new RiverNetwork(options(74L)
                .siteJitter(0D)
                .flowAlignmentWeight(0D)
                .build());
        RiverNodeId sourceId = new RiverNodeId(0L, 0L);
        RiverTerrainSampler flat = flatTerrain(false);
        RiverNode source = reference.nodeAtCell(sourceId.cellX(), sourceId.cellZ(), flat);
        List<RiverNode> candidates = reference.downstreamCandidates(sourceId, flat);
        assertTrue(candidates.size() > 1);
        RiverNode target = null;
        double targetGap = 0D;
        for (RiverNode proposed : candidates) {
            double proposedX = proposed.x() - source.x();
            double proposedZ = proposed.z() - source.z();
            double proposedLength = StrictMath.hypot(proposedX, proposedZ);
            double firstAlignment = -1D;
            double secondAlignment = -1D;
            RiverNode first = null;
            for (RiverNode candidate : candidates) {
                double candidateX = candidate.x() - source.x();
                double candidateZ = candidate.z() - source.z();
                double candidateLength = StrictMath.hypot(candidateX, candidateZ);
                double alignment = StrictMath.abs(
                        proposedX * candidateX + proposedZ * candidateZ
                ) / (proposedLength * candidateLength);
                if (alignment > firstAlignment) {
                    secondAlignment = firstAlignment;
                    firstAlignment = alignment;
                    first = candidate;
                } else if (alignment > secondAlignment) {
                    secondAlignment = alignment;
                }
            }
            double gap = firstAlignment - secondAlignment;
            if (gap > targetGap) {
                targetGap = gap;
                target = first;
            }
        }
        assertNotNull(target);
        assertTrue(targetGap > 0.000001D);
        double tangentX = target.x() - source.x();
        double tangentZ = target.z() - source.z();
        RiverTerrainSampler guidedTerrain = new TestTerrain(false) {
            @Override
            public double flowNoise(double x, double z) {
                return x * tangentZ - z * tangentX;
            }
        };
        RiverNetwork guided = new RiverNetwork(options(74L)
                .siteJitter(0D)
                .flowAlignmentWeight(10_000D)
                .build());

        RiverNode downstream = guided.downstream(sourceId, guidedTerrain);

        assertNotNull(downstream);
        assertEquals(target.id(), downstream.id());
    }

    @Test
    public void basinPotentialCarriesLongRoutesAcrossLocalTerrainRises() {
        RiverNetwork network = new RiverNetwork(options(73L)
                .siteJitter(0D)
                .maxRouteReaches(16)
                .routingPlateauHeight(8D)
                .terrainHeightWeight(0D)
                .requireOcean(false)
                .build());
        RiverTerrainSampler flat = flatTerrain(false);
        RiverRoute baseline = network.trace(new RiverNodeId(0L, 0L), flat);
        HashSet<RiverNodeId> raisedNodes = new HashSet<>();
        RiverNodeId baselineNode = new RiverNodeId(0L, 0L);
        for (int edgeIndex = 0; edgeIndex < baseline.edges().size(); edgeIndex++) {
            RiverEdgeId edge = baseline.edges().get(edgeIndex);
            baselineNode = edge.first().equals(baselineNode) ? edge.second() : edge.first();
            if ((edgeIndex & 1) == 0) {
                raisedNodes.add(baselineNode);
            }
        }
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double naturalHeight(int blockX, int blockZ) {
                RiverNodeId id = new RiverNodeId(Math.floorDiv(blockX, 64), Math.floorDiv(blockZ, 64));
                return raisedNodes.contains(id) ? 104D : 64D;
            }
        };

        RiverRoute route = network.trace(new RiverNodeId(0L, 0L), terrain);
        RiverNode current = network.nodeAtCell(0L, 0L, terrain);
        boolean crossedNaturalRise = false;
        for (RiverEdgeId edge : route.edges()) {
            RiverNode next = network.downstream(current.id(), terrain);
            assertNotNull(next);
            assertEquals(edge, RiverEdgeId.of(current.id(), next.id()));
            crossedNaturalRise |= next.naturalHeight() > current.naturalHeight();
            assertTrue(next.rank() < current.rank());
            assertTrue(next.hydraulicHeight() <= current.hydraulicHeight());
            current = next;
        }

        assertEquals(16, route.edges().size());
        assertTrue(crossedNaturalRise);
    }

    @Test
    public void rejectedBestReachReroutesToAnotherStrictlyDownhillCandidate() {
        RiverNetwork network = new RiverNetwork(options(31L).downstreamCandidateLimit(4).build());
        RiverNodeId source = new RiverNodeId(0L, 0L);
        RiverTerrainSampler terrain = slopedTerrain(false);
        List<RiverNode> candidates = network.downstreamCandidates(source, terrain);
        assertTrue(candidates.size() > 1);
        RiverEdgeId rejected = RiverEdgeId.of(source, candidates.getFirst().id());

        RiverTerrainSampler reroutingTerrain = new TestTerrain(false) {
            @Override
            public boolean allowsReach(RiverRoutingContext context) {
                return !context.edgeId().equals(rejected);
            }
        };
        RiverNode rerouted = network.downstream(source, reroutingTerrain);
        assertNotNull(rerouted);
        assertNotEquals(candidates.getFirst().id(), rerouted.id());
        assertTrue(rerouted.rank() < network.nodeAtCell(0L, 0L, reroutingTerrain).rank());
    }

    @Test
    public void failedContinuationStopsAtTheFirstPhysicallyValidReach() {
        RiverNetwork network = new RiverNetwork(options(32L).reachChance(0D).build());
        int[] feasibilityChecks = new int[1];
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public boolean allowsReach(RiverRoutingContext context) {
                feasibilityChecks[0]++;
                return true;
            }
        };

        assertEquals(null, network.downstream(new RiverNodeId(0L, 0L), terrain));
        assertEquals(1, feasibilityChecks[0]);
    }

    @Test
    public void classifiesCompleteOceanRoutesAsWetAndIncompleteRoutesAsDryOrSuppressed() {
        RiverNodeId source = new RiverNodeId(0L, 0L);
        RiverNetwork wetNetwork = new RiverNetwork(options(4L).maxRouteReaches(16).build());
        RiverTerrainSampler oceanNeighbor = new TestTerrain(false) {
            @Override
            public boolean isOcean(int blockX, int blockZ) {
                return Math.floorDiv(blockX, 64) != 0 || Math.floorDiv(blockZ, 64) != 0;
            }
        };
        RiverRoute wet = wetNetwork.trace(source, oceanNeighbor);
        assertEquals(RiverRouteState.WET, wet.state());
        assertFalse(wet.edges().isEmpty());

        RiverNetwork dryNetwork = new RiverNetwork(options(4L).maxRouteReaches(6).build());
        RiverTerrainSampler physicalTerminal = new TestTerrain(false) {
            @Override
            public boolean allowsReach(RiverRoutingContext context) {
                return context.from().id().equals(source);
            }
        };
        RiverRoute dry = dryNetwork.trace(source, physicalTerminal);
        assertEquals(RiverRouteState.DRY, dry.state());
        assertFalse(dry.edges().isEmpty());

        RiverNetwork suppressedNetwork = new RiverNetwork(
                options(4L).maxRouteReaches(6).dryChannelChance(0.0).build()
        );
        RiverRoute suppressed = suppressedNetwork.trace(source, physicalTerminal);
        assertEquals(RiverRouteState.SUPPRESSED, suppressed.state());
        assertTrue(suppressed.edges().isEmpty());
    }

    @Test
    public void oceanOptionalRoutesDoNotTurnTheTraceHorizonIntoATerminal() {
        RiverNetwork network = new RiverNetwork(
                options(41L).maxRouteReaches(3).requireOcean(false).build()
        );
        RiverRoute route = network.trace(new RiverNodeId(0L, 0L), slopedTerrain(false));
        assertEquals(RiverRouteState.WET, route.state());
        assertFalse(route.oceanConnected());
        assertFalse(route.terminal());
        assertFalse(route.edges().isEmpty());
    }

    @Test
    public void sampledTerminalPoliciesOverrideTheNetworkFallback() {
        RiverNodeId source = new RiverNodeId(0L, 0L);
        RiverNetwork requiredOcean = new RiverNetwork(options(42L).maxRouteReaches(3).requireOcean(true).build());
        RiverTerrainSampler wetTerminal = new TestTerrain(false) {
            @Override
            public boolean allowsReach(RiverRoutingContext context) {
                return context.from().id().equals(source);
            }

            @Override
            public RiverTerminalPolicy terminalPolicy(int blockX, int blockZ) {
                return RiverTerminalPolicy.WET;
            }
        };
        RiverTerrainSampler suppressedTerminal = new TestTerrain(false) {
            @Override
            public boolean allowsReach(RiverRoutingContext context) {
                return context.from().id().equals(source);
            }

            @Override
            public RiverTerminalPolicy terminalPolicy(int blockX, int blockZ) {
                return RiverTerminalPolicy.SUPPRESS;
            }
        };

        assertEquals(RiverRouteState.WET, requiredOcean.trace(source, wetTerminal).state());
        assertEquals(RiverRouteState.SUPPRESSED, requiredOcean.trace(source, suppressedTerminal).state());

        RiverNetwork optionalOcean = new RiverNetwork(options(43L).maxRouteReaches(3).requireOcean(false).build());
        RiverTerrainSampler dryTerminal = new TestTerrain(false) {
            @Override
            public boolean allowsReach(RiverRoutingContext context) {
                return context.from().id().equals(source);
            }

            @Override
            public RiverTerminalPolicy terminalPolicy(int blockX, int blockZ) {
                return RiverTerminalPolicy.DRY;
            }
        };
        assertEquals(RiverRouteState.DRY, optionalOcean.trace(source, dryTerminal).state());
    }

    @Test
    public void sourceAndReachPoliciesGateWholeGraphEvents() {
        RiverNetwork sourceNetwork = new RiverNetwork(options(5L).build());
        RiverRoute sourceSuppressed = sourceNetwork.trace(new RiverNodeId(0L, 0L), new TestTerrain(false) {
            @Override
            public double sourceChanceMultiplier(int blockX, int blockZ) {
                return 0.0;
            }
        });
        assertEquals(RiverRouteState.SUPPRESSED, sourceSuppressed.state());
        assertTrue(sourceSuppressed.edges().isEmpty());

        RiverNetwork reachNetwork = new RiverNetwork(options(5L).reachChance(0.0).build());
        RiverRoute reachSuppressed = reachNetwork.trace(new RiverNodeId(0L, 0L), slopedTerrain(false));
        assertEquals(RiverRouteState.SUPPRESSED, reachSuppressed.state());
        assertTrue(reachSuppressed.edges().isEmpty());

        RiverTerrainSampler blocked = new TestTerrain(false) {
            @Override
            public boolean allowsRiver(int blockX, int blockZ) {
                return blockX < 0;
            }
        };
        RiverNode blockedNode = sourceNetwork.nodeAtCell(0L, 0L, blocked);
        assertFalse(blockedNode.riverAllowed());
        assertEquals(null, sourceNetwork.downstream(blockedNode.id(), blocked));
    }

    @Test
    public void impossibleSourceRollsSkipExpensiveTerrainSettings() {
        int[] sampledSettings = new int[1];
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double maximumSourceChanceMultiplier() {
                return 0D;
            }

            @Override
            public double sourceChanceMultiplier(int blockX, int blockZ) {
                sampledSettings[0]++;
                return 1D;
            }

            @Override
            public double naturalHeight(int blockX, int blockZ) {
                throw new AssertionError("A source rejected by its upper bound must not sample terrain");
            }
        };
        RiverNetwork network = new RiverNetwork(options(53L)
                .sourceChance(0.05D)
                .minimumSourcesPerTile(0)
                .build());

        RiverRoute route = network.trace(new RiverNodeId(0L, 0L), terrain);

        assertEquals(RiverRouteState.SUPPRESSED, route.state());
        assertTrue(route.edges().isEmpty());
        assertEquals(0, sampledSettings[0]);
    }

    @Test
    public void minimumSourceFloorKeepsEligibleRoutingTilesActive() {
        RiverNetwork network = new RiverNetwork(options(52L)
                .maxRouteReaches(4)
                .minimumSourcesPerTile(1)
                .sourceChance(0.000001D)
                .requireOcean(false)
                .build());
        RiverTerrainSampler terrain = slopedTerrain(false);

        for (int tileX = -2; tileX <= 2; tileX++) {
            for (int tileZ = -2; tileZ <= 2; tileZ++) {
                assertFalse(network.buildTile(tileX, tileZ, terrain).reaches().isEmpty());
            }
        }

        RiverNetwork disabled = new RiverNetwork(options(52L)
                .maxRouteReaches(4)
                .minimumSourcesPerTile(1)
                .sourceChance(0D)
                .requireOcean(false)
                .build());
        assertTrue(disabled.buildTile(0, 0, terrain).reaches().isEmpty());
    }

    @Test
    public void minimumSourceFloorDoesNotResolveEveryRandomCandidate() {
        int[] sampledSettings = new int[1];
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double maximumSourceChanceMultiplier() {
                return 1D;
            }

            @Override
            public double sourceChanceMultiplier(int blockX, int blockZ) {
                sampledSettings[0]++;
                return 1D;
            }
        };
        RiverNetwork network = new RiverNetwork(options(54L)
                .maxRouteReaches(1)
                .minimumSourcesPerTile(1)
                .sourceChance(0.000000001D)
                .requireOcean(false)
                .build());

        RiverTile tile = network.buildTile(0, 0, terrain);

        assertFalse(tile.reaches().isEmpty());
        assertTrue(sampledSettings[0] <= 25);
    }

    @Test
    public void minimumSourceFloorRejectsNonFiniteLocalChanceMultipliers() {
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double sourceChanceMultiplier(int blockX, int blockZ) {
                return Double.NaN;
            }
        };
        RiverNetwork network = new RiverNetwork(options(55L)
                .maxRouteReaches(4)
                .minimumSourcesPerTile(1)
                .sourceChance(1D)
                .requireOcean(false)
                .build());

        assertTrue(network.buildTile(0, 0, terrain).reaches().isEmpty());
    }

    @Test
    public void tileBuildIsDeterministicUnderParallelEvaluation() throws Exception {
        RiverNetwork network = new RiverNetwork(options(6L).build());
        RiverTerrainSampler terrain = slopedTerrain(false);
        long expected = digest(network.buildTile(-1, 0, terrain));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            ArrayList<Callable<Long>> tasks = new ArrayList<>();
            for (int task = 0; task < 12; task++) {
                tasks.add(() -> digest(network.buildTile(-1, 0, terrain)));
            }
            List<Future<Long>> results = executor.invokeAll(tasks);
            for (Future<Long> result : results) {
                assertEquals(expected, result.get().longValue());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void tileBuildPreservesPinnedOutputDigests() {
        RiverTerrainSampler terrain = slopedTerrain(false);
        List<Long> actual = List.of(
                digest(new RiverNetwork(options(6L).build()).buildTile(-1, 0, terrain)),
                digest(new RiverNetwork(options(6L).build()).buildTile(0, 0, terrain)),
                digest(new RiverNetwork(options(6L).build()).buildTile(1, 1, terrain)),
                digest(new RiverNetwork(options(61L).build()).buildTile(-2, -1, terrain))
        );

        assertEquals(List.of(
                973031325888677478L,
                8932177974453767311L,
                -5189009084208004632L,
                6374264141259432071L
        ), actual);
    }

    @Test
    public void adjacentTilesShareIdenticalPinnedReachGeometry() {
        RiverNetwork network = new RiverNetwork(options(7L).build());
        RiverTerrainSampler terrain = slopedTerrain(false);
        RiverTile first = network.buildTile(0, 0, terrain);
        RiverTile second = network.buildTile(1, 0, terrain);
        HashMap<RiverEdgeId, RiverReach> secondById = new HashMap<>();
        for (RiverReach reach : second.reaches()) {
            secondById.put(reach.id(), reach);
        }
        int sharedCount = 0;
        for (RiverReach candidate : first.reaches()) {
            RiverReach matching = secondById.get(candidate.id());
            if (matching == null) {
                continue;
            }
            sharedCount++;
            assertReachEquals(candidate, matching);
            assertEquals(candidate.from().x(), candidate.polyline().x(0), 0.0);
            assertEquals(candidate.from().z(), candidate.polyline().z(0), 0.0);
            int last = candidate.polyline().size() - 1;
            assertEquals(candidate.to().x(), candidate.polyline().x(last), 0.0);
            assertEquals(candidate.to().z(), candidate.polyline().z(last), 0.0);
        }
        assertTrue(sharedCount > 0);
    }

    @Test
    public void geometryEnvelopeKeepsWideReachesIdenticalAcrossTileBoundaries() {
        RiverNetwork network = new RiverNetwork(options(71L)
                .maxRouteReaches(1)
                .siteJitter(0D)
                .maximumReachRadius(256D)
                .channelWidth(512D)
                .maxChannelWidth(512D)
                .bankWidth(0D)
                .worms(List.of(wormProfile(
                        "straight",
                        1L,
                        1,
                        1D,
                        1D,
                        1D,
                        8,
                        1D,
                        1D,
                        0D,
                        0D,
                        List.of()
                )))
                .build());
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public double sourceChanceMultiplier(int blockX, int blockZ) {
                return blockX == -96 ? 1D : 0D;
            }
        };
        RiverTile west = network.buildTile(0, 0, terrain);
        RiverTile east = network.buildTile(1, 0, terrain);

        boolean present = false;
        for (int z = 0; z < 128; z++) {
            RiverSample westSample = west.sample(128D, z);
            RiverSample eastSample = east.sample(128D, z);
            assertEquals(westSample, eastSample);
            present |= westSample.present();
        }
        assertTrue(present);
    }

    @Test
    public void mergedReachesIncreaseFlowAndOrderAndCanBeSampled() {
        RiverNetwork network = new RiverNetwork(options(8L).sourceChance(1.0).build());
        RiverTile tile = network.buildTile(0, 0, slopedTerrain(false));
        boolean merged = false;
        boolean sampled = false;

        for (RiverReach reach : tile.reaches()) {
            assertEquals(1 + (31 - Integer.numberOfLeadingZeros(reach.flow())), reach.order());
            if (reach.flow() > 1) {
                merged = true;
            }
            int middle = reach.polyline().size() / 2;
            RiverSample sample = tile.sample(reach.polyline().x(middle), reach.polyline().z(middle));
            if (sample.present()) {
                sampled = true;
                assertTrue(sample.carveWeight() > 0.0);
                assertTrue(sample.alongReach() >= 0.0 && sample.alongReach() <= 1.0);
            }
        }

        assertTrue(merged);
        assertTrue(sampled);
        assertThrows(UnsupportedOperationException.class, () -> tile.reaches().add(tile.reaches().getFirst()));
    }

    @Test
    public void candidateAnchorsAreStableUniqueAndOwnedByOneTile() {
        RiverNetwork network = new RiverNetwork(options(9L).build());
        RiverTile tile = network.buildTile(-1, -1, slopedTerrain(false));
        List<RiverAnchor> first = tile.candidateAnchors(24.0, 77L);
        List<RiverAnchor> second = tile.candidateAnchors(24.0, 77L);
        Set<Long> identities = new HashSet<>();

        assertEquals(first, second);
        for (RiverAnchor anchor : first) {
            assertTrue(identities.add(anchor.stableId()));
            assertTrue(anchor.x() >= tile.minimumX() && anchor.x() < tile.maximumX());
            assertTrue(anchor.z() >= tile.minimumZ() && anchor.z() < tile.maximumZ());
            assertTrue(anchor.alongReach() >= 0.0 && anchor.alongReach() <= 1.0);
        }
        assertThrows(UnsupportedOperationException.class, () -> first.add(first.getFirst()));
    }

    @Test
    public void spatialIndexReducesColumnCandidatesAndBoundsAnchorQueries() {
        RiverNetwork network = new RiverNetwork(options(51L).tileCells(8).build());
        RiverTile tile = network.buildTile(0, 0, slopedTerrain(false));
        int minimumCandidates = Integer.MAX_VALUE;
        for (int x = tile.minimumX() + 32; x < tile.maximumX(); x += 64) {
            for (int z = tile.minimumZ() + 32; z < tile.maximumZ(); z += 64) {
                minimumCandidates = StrictMath.min(minimumCandidates, tile.sampleCandidateCount(x, z));
            }
        }
        assertTrue(tile.reaches().size() > 8);
        assertTrue(minimumCandidates < tile.reaches().size() / 2);

        double queryMaximumX = tile.minimumX() + 64.0;
        double queryMaximumZ = tile.minimumZ() + 64.0;
        List<RiverAnchor> anchors = tile.candidateAnchors(
                tile.minimumX(),
                tile.minimumZ(),
                queryMaximumX,
                queryMaximumZ,
                12.0,
                12L
        );
        for (RiverAnchor anchor : anchors) {
            assertTrue(anchor.x() >= tile.minimumX() && anchor.x() < queryMaximumX);
            assertTrue(anchor.z() >= tile.minimumZ() && anchor.z() < queryMaximumZ);
        }
    }

    @Test
    public void sampleReportsNormalizedClosestPositionAndTerminalMetadata() {
        RiverNode from = new RiverNode(
                new RiverNodeId(0L, 0L),
                0.0,
                0.0,
                20.0,
                20.0,
                20.0,
                20.0,
                false,
                true
        );
        RiverNode to = new RiverNode(
                new RiverNodeId(1L, 0L),
                100.0,
                0.0,
                10.0,
                10.0,
                10.0,
                10.0,
                false,
                true
        );
        RiverReach reach = new RiverReach(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                RiverRouteState.DRY,
                1,
                1,
                10.0,
                5.0,
                3.0,
                RiverBodyProfile.constant(10.0, 5.0, 3.0),
                false,
                true,
                new RiverPolyline(new double[]{0.0, 100.0}, new double[]{0.0, 0.0})
        );
        RiverTile tile = new RiverTile(0, 0, 0, -32, 128, 32, List.of(reach));

        RiverSample sample = tile.sample(75.0, 0.0);
        assertTrue(sample.present());
        assertEquals(0.75, sample.alongReach(), 0.0000001);
        assertEquals(RiverSection.DRY_CHANNEL, sample.section());
        assertTrue(sample.terminal());
    }

    @Test
    public void uncoveredNarrowReachDoesNotMaskCoveringWideReach() {
        RiverNode narrowFrom = node(0L, 0L, 0D, 0D);
        RiverNode narrowTo = node(1L, 0L, 100D, 0D);
        RiverNode wideFrom = node(0L, 1L, 0D, 4D);
        RiverNode wideTo = node(1L, 1L, 100D, 4D);
        RiverReach narrow = reach(narrowFrom, narrowTo, 2D, 0D);
        RiverReach wide = reach(wideFrom, wideTo, 8D, 0D);
        RiverTile tile = new RiverTile(0, 0, 0, -16, 128, 32, List.of(narrow, wide));

        RiverSample sample = tile.sample(50D, 1.5D);

        assertTrue(sample.present());
        assertEquals(wide.id(), sample.reachId());
    }

    @Test
    public void footprintSamplingFindsChannelThatPointSamplingMisses() {
        RiverNode from = node(0L, 0L, 0D, 0D);
        RiverNode to = node(1L, 0L, 100D, 0D);
        RiverReach reach = reach(from, to, 2D, 0D);
        RiverTile tile = new RiverTile(0, 0, 0, -16, 128, 32, List.of(reach));

        assertFalse(tile.sample(50D, 2.5D).present());
        RiverSample sample = tile.sampleFootprint(48D, 0.5D, 52D, 4.5D);

        assertTrue(sample.present());
        assertEquals(RiverSection.CHANNEL, sample.section());
        assertEquals(0.5D, sample.distance(), 0D);
    }

    @Test
    public void footprintSamplingFindsBankThatPointSamplingMisses() {
        RiverNode from = node(0L, 0L, 0D, 0D);
        RiverNode to = node(1L, 0L, 100D, 0D);
        RiverReach reach = reach(from, to, 2D, 3D);
        RiverTile tile = new RiverTile(0, 0, 0, -16, 128, 32, List.of(reach));

        assertFalse(tile.sample(50D, 5D).present());
        RiverSample sample = tile.sampleFootprint(48D, 2D, 52D, 8D);

        assertTrue(sample.present());
        assertEquals(RiverSection.BANK, sample.section());
        assertEquals(2D, sample.distance(), 0D);
    }

    @Test
    public void footprintSamplingRejectsBoundingBoxOnlyCornerOverlap() {
        RiverNode from = node(0L, 0L, 0D, 0D);
        RiverNode to = node(1L, 0L, 100D, 0D);
        RiverReach reach = reach(from, to, 1D, 2D);
        RiverTile tile = new RiverTile(0, 0, 0, -16, 128, 32, List.of(reach));

        RiverSample sample = tile.sampleFootprint(101.8D, 1.8D, 104D, 4D);

        assertFalse(sample.present());
    }

    @Test
    public void terrainSamplerDimensionsAreClampedAfterOrderScaling() {
        RiverNetwork network = new RiverNetwork(options(61L)
                .maxChannelWidth(10D)
                .maxBankWidth(3D)
                .maxDepth(6D)
                .worms(List.of(worm(1L, 0D, 0D, 0D, 1)))
                .build());
        RiverTerrainSampler styled = new TestTerrain(false) {
            @Override
            public double channelWidth(RiverRoutingContext context, double fallback) {
                return 20.0;
            }

            @Override
            public double bankWidth(RiverRoutingContext context, double fallback) {
                return 9.0;
            }

            @Override
            public double depth(RiverRoutingContext context, double fallback) {
                return 7.0;
            }
        };
        RiverTile tile = network.buildTile(0, 0, styled);
        RiverReach reach = tile.reaches().getFirst();
        double deltaX = reach.to().x() - reach.from().x();
        double deltaZ = reach.to().z() - reach.from().z();

        assertEquals(10D, reach.width(), 0D);
        assertEquals(3D, reach.bankWidth(), 0D);
        assertEquals(6D, reach.depth(), 0D);
        for (int point = 0; point < reach.polyline().size(); point++) {
            double pointDeltaX = reach.polyline().x(point) - reach.from().x();
            double pointDeltaZ = reach.polyline().z(point) - reach.from().z();
            assertEquals(0.0, pointDeltaX * deltaZ - pointDeltaZ * deltaX, 0.0000001);
        }
    }

    @Test
    public void bodyAnatomyVariesInsideOneReachAndRemainsSpatiallyQueryable() {
        RiverNode from = node(0L, 0L, 0D, 0D);
        RiverNode to = node(1L, 0L, 100D, 0D);
        RiverBodyProfile profile = new RiverBodyProfile(
                new double[]{0D, 0.5D, 1D},
                new double[]{1D, 7D, 1D},
                new double[]{1D, 9D, 1D},
                new double[]{2D, 8D, 2D},
                new double[]{1D, 0.35D, 1D}
        );
        RiverReach reach = new RiverReach(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                RiverRouteState.WET,
                1,
                1,
                7D,
                9D,
                8D,
                profile,
                false,
                false,
                new RiverPolyline(new double[]{0D, 100D}, new double[]{0D, 0D})
        );
        RiverTile tile = new RiverTile(0, 0, 0, -16, 128, 32, List.of(reach));

        RiverSample narrow = tile.sample(10D, 7D);
        RiverSample swollen = tile.sample(50D, 7D);

        assertFalse(narrow.present());
        assertTrue(swollen.present());
        assertEquals(7D, swollen.width(), 0D);
        assertEquals(9D, swollen.bankWidth(), 0D);
        assertEquals(8D, swollen.depth(), 0D);
        assertEquals(0.35D, reach.roofScaleAt(swollen.alongReach()), 0.0000001D);
        assertEquals(RiverSection.BANK, swollen.section());
    }

    @Test
    public void perlinWormBodyAnatomyVariesAllDimensionsDeterministically() {
        RiverWorm anatomy = wormProfile(
                "anatomy",
                917L,
                24,
                1D,
                1D,
                1D,
                64D,
                32D,
                0.875D,
                0.875D,
                0.875D,
                0.875D,
                4,
                0.35D,
                1D,
                0D,
                0D,
                List.of()
        );
        RiverNetwork network = new RiverNetwork(options(917L)
                .requireOcean(false)
                .maxChannelWidth(64D)
                .maxBankWidth(64D)
                .maxDepth(64D)
                .maximumReachRadius(96D)
                .worms(List.of(anatomy))
                .build());
        RiverTerrainSampler terrain = flatTerrain(false);

        RiverTile first = network.buildTile(0, 0, terrain);
        RiverTile second = network.buildTile(0, 0, terrain);

        assertFalse(first.reaches().isEmpty());
        assertEquals(digest(first), digest(second));
        double minimumWidth = Double.POSITIVE_INFINITY;
        double maximumWidth = 0D;
        double minimumBank = Double.POSITIVE_INFINITY;
        double maximumBank = 0D;
        double minimumDepth = Double.POSITIVE_INFINITY;
        double maximumDepth = 0D;
        double minimumRoof = Double.POSITIVE_INFINITY;
        double maximumRoof = 0D;
        for (RiverReach reach : first.reaches()) {
            for (int index = 0; index < reach.bodyProfile().size(); index++) {
                minimumWidth = StrictMath.min(minimumWidth, reach.bodyProfile().widthAtIndex(index));
                maximumWidth = StrictMath.max(maximumWidth, reach.bodyProfile().widthAtIndex(index));
                minimumBank = StrictMath.min(minimumBank, reach.bodyProfile().bankWidthAtIndex(index));
                maximumBank = StrictMath.max(maximumBank, reach.bodyProfile().bankWidthAtIndex(index));
                minimumDepth = StrictMath.min(minimumDepth, reach.bodyProfile().depthAtIndex(index));
                maximumDepth = StrictMath.max(maximumDepth, reach.bodyProfile().depthAtIndex(index));
                minimumRoof = StrictMath.min(minimumRoof, reach.bodyProfile().roofScaleAtIndex(index));
                maximumRoof = StrictMath.max(maximumRoof, reach.bodyProfile().roofScaleAtIndex(index));
            }
        }
        assertTrue(maximumWidth - minimumWidth > 2D);
        assertTrue(maximumBank - minimumBank > 1.5D);
        assertTrue(maximumDepth - minimumDepth > 0.75D);
        assertTrue(maximumRoof - minimumRoof > 0.1D);
    }

    @Test
    public void volatileBodyProfilesResolveSubTenBlockThicknessChanges() {
        RiverWorm volatileBody = new RiverWorm(
                "volatile-body",
                4815L,
                1D,
                1024D,
                128D,
                0.65D,
                0.2D,
                180D,
                48,
                1D,
                1D,
                1D,
                24D,
                8D,
                0.95D,
                0.875D,
                0.75D,
                0.75D,
                0.75D,
                4,
                0.35D,
                1D,
                0D,
                0D,
                List.of()
        );
        RiverNetwork network = new RiverNetwork(options(4815L)
                .cellSize(1700)
                .tileCells(1)
                .siteJitter(0D)
                .requireOcean(false)
                .channelWidth(14D)
                .maxChannelWidth(38D)
                .maximumReachRadius(64D)
                .worms(List.of(volatileBody))
                .build());

        RiverReach reach = network.buildTile(0, 0, flatTerrain(false)).reaches().getFirst();
        RiverBodyProfile profile = reach.bodyProfile();
        double maximumStationSpacing = 0D;
        for (int index = 1; index < profile.size(); index++) {
            maximumStationSpacing = StrictMath.max(
                    maximumStationSpacing,
                    reach.polyline().length() * (profile.position(index) - profile.position(index - 1))
            );
        }
        boolean volatileChangeFound = false;
        for (double distance = 0D; distance + 10D <= reach.polyline().length(); distance += 2D) {
            double start = distance / reach.polyline().length();
            double end = (distance + 10D) / reach.polyline().length();
            if (StrictMath.abs(profile.width(start) - profile.width(end)) >= 2D) {
                volatileChangeFound = true;
                break;
            }
        }

        assertTrue(maximumStationSpacing <= 10D);
        assertTrue(volatileChangeFound);
    }

    @Test
    public void channelRadiusBonusAddsThreeBlocksPerSideAfterShaping() {
        RiverWorm constantBody = wormProfile(
                "radius-bonus",
                6501L,
                8,
                1D,
                1D,
                1D,
                64D,
                16D,
                0D,
                0D,
                0D,
                0D,
                4,
                0.35D,
                1D,
                0D,
                0D,
                List.of()
        );
        RiverNetwork baseline = new RiverNetwork(options(6501L)
                .siteJitter(0D)
                .requireOcean(false)
                .channelWidth(10D)
                .maxChannelWidth(38D)
                .maximumReachRadius(64D)
                .worms(List.of(constantBody))
                .build());
        RiverNetwork expanded = new RiverNetwork(options(6501L)
                .siteJitter(0D)
                .requireOcean(false)
                .channelWidth(10D)
                .channelRadiusBonus(3D)
                .maxChannelWidth(38D)
                .maximumReachRadius(64D)
                .worms(List.of(constantBody))
                .build());

        RiverReach baselineReach = baseline.buildTile(0, 0, flatTerrain(false)).reaches().getFirst();
        RiverReach expandedReach = expanded.buildTile(0, 0, flatTerrain(false)).reaches().getFirst();

        assertEquals(baselineReach.id(), expandedReach.id());
        assertEquals(6D, expandedReach.bodyProfile().width(0.5D)
                - baselineReach.bodyProfile().width(0.5D), 0.0000001D);
    }

    @Test
    public void foldedReachSamplingFindsFartherCoveringWidthEnvelope() {
        RiverNode from = node(0L, 0L, 0D, 0D);
        RiverNode to = node(1L, 0L, 0D, 10D);
        RiverBodyProfile profile = new RiverBodyProfile(
                new double[]{0D, 0.48D, 0.53D, 1D},
                new double[]{1D, 1D, 18D, 18D},
                new double[]{0D, 0D, 0D, 0D},
                new double[]{3D, 3D, 3D, 3D},
                new double[]{1D, 1D, 1D, 1D}
        );
        RiverReach reach = new RiverReach(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                RiverRouteState.WET,
                1,
                1,
                18D,
                0D,
                3D,
                profile,
                false,
                false,
                new RiverPolyline(
                        new double[]{0D, 100D, 100D, 0D},
                        new double[]{0D, 0D, 10D, 10D}
                )
        );
        RiverTile tile = new RiverTile(0, 0, 0, -16, 128, 32, List.of(reach));

        RiverSample point = tile.sample(50D, 2D);
        RiverSample footprint = tile.sampleFootprint(49D, 1.5D, 51D, 2.5D);

        assertTrue(point.present());
        assertTrue(point.alongReach() > 0.53D);
        assertEquals(18D, point.width(), 0.000001D);
        assertTrue(footprint.present());
        assertTrue(footprint.alongReach() > 0.53D);
    }

    @Test
    public void reachFeasibilityReceivesTheFinalPinnedWormPolyline() {
        RiverNetwork network = new RiverNetwork(options(62L)
                .worms(List.of(worm(62L, 0.8D, 0.2D, 20D, 32)))
                .build());
        boolean[] observedWorm = new boolean[1];
        RiverTerrainSampler terrain = new TestTerrain(false) {
            @Override
            public boolean allowsReach(RiverRoutingContext context) {
                RiverPolyline polyline = context.polyline();
                double deltaX = context.to().x() - context.from().x();
                double deltaZ = context.to().z() - context.from().z();
                for (int point = 1; point < polyline.size() - 1; point++) {
                    double pointX = polyline.x(point) - context.from().x();
                    double pointZ = polyline.z(point) - context.from().z();
                    if (StrictMath.abs(pointX * deltaZ - pointZ * deltaX) > 0.000001D) {
                        observedWorm[0] = true;
                        return false;
                    }
                }
                return true;
            }
        };

        RiverNode downstream = network.downstream(new RiverNodeId(0L, 0L), terrain);

        assertTrue(observedWorm[0]);
        assertEquals(null, downstream);
    }

    @Test
    public void wormSeedChangesDeterministicGeometryWithoutBreakingItsEnvelope() {
        double maximumOffset = 20D;
        RiverNetwork firstNetwork = new RiverNetwork(options(63L)
                .siteJitter(0D)
                .requireOcean(false)
                .worms(List.of(worm(101L, 0.8D, 0.2D, maximumOffset, 32)))
                .build());
        RiverNetwork secondNetwork = new RiverNetwork(options(63L)
                .siteJitter(0D)
                .requireOcean(false)
                .worms(List.of(worm(202L, 0.8D, 0.2D, maximumOffset, 32)))
                .build());
        RiverTerrainSampler terrain = new TestTerrain(false);

        RiverReach firstReach = firstNetwork.buildTile(0, 0, terrain).reaches().getFirst();
        RiverReach repeatedReach = firstNetwork.buildTile(0, 0, terrain).reaches().getFirst();
        RiverReach secondReach = secondNetwork.buildTile(0, 0, terrain).reaches().getFirst();

        assertReachEquals(firstReach, repeatedReach);
        assertEquals(firstReach.id(), secondReach.id());
        assertTrue(polylinesDiffer(firstReach.polyline(), secondReach.polyline()));
        assertWormBounds(firstReach, maximumOffset);
        assertWormBounds(secondReach, maximumOffset);
    }

    @Test
    public void weightedWormProfilesSelectDistinctConfigurableVariants() {
        RiverWorm gentle = new RiverWorm(
                "gentle", 301L, 1D, 1024D, 256D, 0.2D, 0.05D, 10D, 8, 0.5D, 0.5D, 0.5D,
                512D, 128D, 0.3D, 0D, 0D, 0D, 0D,
                4, 0.35D, 1D, 0D, 0D, List.of()
        );
        RiverWorm winding = new RiverWorm(
                "winding", 302L, 1D, 512D, 128D, 0.55D, 0.15D, 20D, 16, 1D, 1D, 1D,
                512D, 128D, 0.3D, 0D, 0D, 0D, 0D,
                4, 0.35D, 1D, 0D, 0D, List.of()
        );
        RiverWorm restless = new RiverWorm(
                "restless", 303L, 1D, 192D, 48D, 0.9D, 0.35D, 30D, 32, 2D, 2D, 2D,
                512D, 128D, 0.3D, 0D, 0D, 0D, 0D,
                4, 0.35D, 1D, 0D, 0D, List.of()
        );
        RiverNetwork network = new RiverNetwork(options(64L)
                .siteJitter(0D)
                .routingBasinCells(8)
                .requireOcean(false)
                .maxChannelWidth(32D)
                .maxBankWidth(32D)
                .maxDepth(32D)
                .orderWidthFactor(0D)
                .orderDepthFactor(0D)
                .maximumReachRadius(32D)
                .worms(List.of(gentle, winding, restless))
                .build());
        RiverTerrainSampler terrain = new TestTerrain(false);

        HashMap<RiverEdgeId, RiverReach> uniqueReaches = new HashMap<>();
        for (int tileX = -2; tileX <= 2; tileX++) {
            for (int tileZ = -2; tileZ <= 2; tileZ++) {
                for (RiverReach reach : network.buildTile(tileX, tileZ, terrain).reaches()) {
                    uniqueReaches.putIfAbsent(reach.id(), reach);
                }
            }
        }
        Set<Integer> selectedPointCounts = new HashSet<>();
        HashMap<Integer, Double> maximumDisplacementRatios = new HashMap<>();
        for (RiverReach reach : uniqueReaches.values()) {
            int pointCount = reach.polyline().size();
            selectedPointCounts.add(pointCount);
            RiverWorm selected = switch (pointCount) {
                case 9 -> gentle;
                case 17 -> winding;
                case 33 -> restless;
                default -> throw new AssertionError("Unexpected worm point count " + pointCount);
            };
            assertEquals(8D * selected.widthMultiplier(), reach.width(), 0.0000001D);
            assertEquals(6D * selected.bankMultiplier(), reach.bankWidth(), 0.0000001D);
            assertEquals(3D * selected.depthMultiplier(), reach.depth(), 0.0000001D);
            assertWormBounds(reach, selected.maxOffset());
            maximumDisplacementRatios.merge(
                    pointCount,
                    maximumWormDisplacement(reach) / selected.maxOffset(),
                    StrictMath::max
            );
        }
        assertEquals(Set.of(9, 17, 33), selectedPointCounts);
        assertTrue(maximumDisplacementRatios.toString(), maximumDisplacementRatios.get(9) > 0.03D);
        assertTrue(maximumDisplacementRatios.toString(), maximumDisplacementRatios.get(17) > 0.18D);
        assertTrue(maximumDisplacementRatios.toString(), maximumDisplacementRatios.get(33) > 0.4D);
    }

    @Test
    public void forcedChildHierarchyControlsReachGeometryAndDimensionsDeterministically() {
        RiverWorm child = wormProfile(
                "child",
                402L,
                24,
                2D,
                1.5D,
                0.5D,
                8,
                1D,
                1D,
                0D,
                0D,
                List.of()
        );
        RiverWorm root = wormProfile(
                "root",
                401L,
                8,
                0.5D,
                0.5D,
                0.5D,
                8,
                1D,
                1D,
                1D,
                0D,
                List.of(child)
        );
        RiverNetwork network = new RiverNetwork(options(68L)
                .siteJitter(0D)
                .requireOcean(false)
                .maxChannelWidth(32D)
                .maxBankWidth(32D)
                .maxDepth(32D)
                .orderWidthFactor(0D)
                .orderDepthFactor(0D)
                .maximumReachRadius(32D)
                .worms(List.of(root))
                .build());
        RiverTerrainSampler terrain = new TestTerrain(false);

        RiverTile first = network.buildTile(0, 0, terrain);
        RiverTile second = network.buildTile(0, 0, terrain);

        assertFalse(first.reaches().isEmpty());
        assertEquals(digest(first), digest(second));
        for (RiverReach reach : first.reaches()) {
            assertEquals(child.segments() + 1, reach.polyline().size());
            assertEquals(8D * child.widthMultiplier(), reach.width(), 0.0000001D);
            assertEquals(6D * child.bankMultiplier(), reach.bankWidth(), 0.0000001D);
            assertEquals(3D * child.depthMultiplier(), reach.depth(), 0.0000001D);
            assertWormBounds(reach, child.maxOffset());
        }
    }

    @Test
    public void wormHierarchyRejectsInvalidIdsDepthAndCounts() {
        assertThrows(IllegalArgumentException.class, () -> wormProfile(
                "Invalid",
                1L,
                8,
                1D,
                1D,
                1D,
                512D,
                128D,
                0D,
                0D,
                0D,
                0D,
                4,
                0.35D,
                1D,
                0D,
                0D,
                List.of()
        ));

        RiverWorm duplicateChild = wormProfile(
                "duplicate", 2L, 8, 1D, 1D, 1D, 4, 0.35D, 1D, 0D, 0D, List.of()
        );
        RiverWorm duplicateRoot = wormProfile(
                "duplicate", 3L, 8, 1D, 1D, 1D, 4, 0.35D, 1D, 1D, 0D, List.of(duplicateChild)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> options(10L).worms(List.of(duplicateRoot)).build()
        );

        RiverWorm tooDeep = wormProfile(
                "depth-5", 5L, 8, 1D, 1D, 1D, 4, 0.35D, 1D, 0D, 0D, List.of()
        );
        for (int depth = 4; depth >= 1; depth--) {
            tooDeep = wormProfile(
                    "depth-" + depth,
                    depth,
                    8,
                    1D,
                    1D,
                    1D,
                    4,
                    0.35D,
                    1D,
                    1D,
                    0D,
                    List.of(tooDeep)
            );
        }
        RiverWorm depthRoot = tooDeep;
        assertThrows(
                IllegalArgumentException.class,
                () -> options(10L).worms(List.of(depthRoot)).build()
        );

        ArrayList<RiverWorm> roots = new ArrayList<>();
        for (int root = 0; root < 17; root++) {
            roots.add(wormProfile(
                    "root-" + root, root, 8, 1D, 1D, 1D, 4, 0.35D, 1D, 0D, 0D, List.of()
            ));
        }
        assertThrows(IllegalArgumentException.class, () -> options(10L).worms(roots).build());

        ArrayList<RiverWorm> branches = new ArrayList<>();
        for (int branch = 0; branch < 8; branch++) {
            ArrayList<RiverWorm> leaves = new ArrayList<>();
            for (int leaf = 0; leaf < 16; leaf++) {
                leaves.add(wormProfile(
                        "leaf-" + branch + "-" + leaf,
                        branch * 16L + leaf,
                        8,
                        1D,
                        1D,
                        1D,
                        4,
                        0.35D,
                        1D,
                        0D,
                        0D,
                        List.of()
                ));
            }
            branches.add(wormProfile(
                    "branch-" + branch,
                    branch,
                    8,
                    1D,
                    1D,
                    1D,
                    4,
                    0.35D,
                    1D,
                    1D,
                    0D,
                    leaves
            ));
        }
        RiverWorm oversized = wormProfile(
                "oversized", 900L, 8, 1D, 1D, 1D, 4, 0.35D, 1D, 1D, 0D, branches
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> options(10L).worms(List.of(oversized)).build()
        );
    }

    @Test
    public void wormProfileRejectsInvalidBranchControls() {
        assertThrows(IllegalArgumentException.class, () -> wormProfile(
                "cap", 1L, 8, 1D, 1D, 1D, 0, 0.35D, 1D, 0D, 0D, List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> wormProfile(
                "decay", 1L, 8, 1D, 1D, 1D, 4, 1.1D, 1D, 0D, 0D, List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> wormProfile(
                "confluence", 1L, 8, 1D, 1D, 1D, 4, 0.35D, 8.1D, 0D, 0D, List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> wormProfile(
                "child-chance", 1L, 8, 1D, 1D, 1D, 4, 0.35D, 1D, 1.1D, 0D, List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> wormProfile(
                "sibling-chance", 1L, 8, 1D, 1D, 1D, 4, 0.35D, 1D, 0D, 1.1D, List.of()
        ));
    }

    @Test
    public void drainageDomainWarpChangesRoutesDeterministically() {
        RiverTerrainSampler terrain = flatTerrain(false);
        RiverNetwork straight = new RiverNetwork(options(65L)
                .requireOcean(false)
                .routingDeviationStrengthCells(0D)
                .build());
        RiverNetwork deviated = new RiverNetwork(options(65L)
                .requireOcean(false)
                .routingDeviationScaleCells(8)
                .routingDeviationStrengthCells(4D)
                .build());
        boolean changed = false;
        for (long cellX = -8L; cellX <= 8L; cellX++) {
            for (long cellZ = -8L; cellZ <= 8L; cellZ++) {
                RiverNode straightDownstream = straight.downstream(new RiverNodeId(cellX, cellZ), terrain);
                RiverNode deviatedDownstream = deviated.downstream(new RiverNodeId(cellX, cellZ), terrain);
                if (!Objects.equals(
                        straightDownstream == null ? null : straightDownstream.id(),
                        deviatedDownstream == null ? null : deviatedDownstream.id()
                )) {
                    changed = true;
                }
                RiverNode repeated = deviated.downstream(new RiverNodeId(cellX, cellZ), terrain);
                assertEquals(
                        deviatedDownstream == null ? null : deviatedDownstream.id(),
                        repeated == null ? null : repeated.id()
                );
            }
        }
        assertTrue(changed);
    }

    @Test
    public void confluenceAttractionChangesDownstreamChoicesDeterministically() {
        RiverTerrainSampler terrain = flatTerrain(false);
        RiverNetwork dispersed = new RiverNetwork(options(67L)
                .requireOcean(false)
                .confluenceWeight(0D)
                .build());
        RiverNetwork branching = new RiverNetwork(options(67L)
                .requireOcean(false)
                .confluenceWeight(512D)
                .build());
        boolean changed = false;
        for (long cellX = -8L; cellX <= 8L; cellX++) {
            for (long cellZ = -8L; cellZ <= 8L; cellZ++) {
                RiverNodeId id = new RiverNodeId(cellX, cellZ);
                RiverNode dispersedDownstream = dispersed.downstream(id, terrain);
                RiverNode branchingDownstream = branching.downstream(id, terrain);
                if (!Objects.equals(
                        dispersedDownstream == null ? null : dispersedDownstream.id(),
                        branchingDownstream == null ? null : branchingDownstream.id()
                )) {
                    changed = true;
                }
                RiverNode repeated = branching.downstream(id, terrain);
                assertEquals(
                        branchingDownstream == null ? null : branchingDownstream.id(),
                        repeated == null ? null : repeated.id()
                );
            }
        }
        assertTrue(changed);
    }

    @Test
    public void optionsRejectNonFiniteOrUnsafeValues() {
        assertThrows(IllegalArgumentException.class, () -> options(10L).sourceChance(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> options(10L).cellSize(0).build());
        assertThrows(IllegalArgumentException.class, () -> options(10L).routingBasinCells(7).build());
        assertThrows(IllegalArgumentException.class, () -> options(10L).routingPlateauHeight(0D).build());
        assertThrows(IllegalArgumentException.class, () -> options(10L).hydraulicBaseHeight(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> options(10L).confluenceWeight(-1D).build());
        assertThrows(IllegalArgumentException.class, () -> options(10L).channelWidth(-1.0).build());
        assertThrows(IllegalArgumentException.class, () -> options(10L)
                .tileCells(1)
                .minimumSourcesPerTile(2)
                .build());
    }

    @Test
    public void derivedComplexityAllowsDefaultsAndRejectsExpensiveTopologies() {
        double defaultRadius = 10D * 0.5D + 4D;
        RiverTopologyComplexity.Estimate defaults = RiverTopologyComplexity.estimate(
                768,
                4,
                0.35D,
                4,
                defaultRadius,
                72D,
                8
        );
        assertTrue(defaults.violations().toString(), defaults.safe());
        assertEquals(2L, defaults.geometryPaddingCells());
        assertEquals(16L, defaults.sourceWindowAxis());
        assertEquals(256L, defaults.sourceWindowCells());
        assertEquals(1_024L, defaults.maximumRouteScanSteps());

        double expensiveRadius = defaultRadius;
        RiverTopologyComplexity.Estimate expensive = RiverTopologyComplexity.estimate(
                768,
                4,
                0.35D,
                48,
                expensiveRadius,
                72D,
                8
        );
        assertFalse(expensive.safe());
        assertTrue(expensive.violations().toString(), expensive.maximumRouteScanSteps()
                > RiverTopologyComplexity.MAXIMUM_ROUTE_SCAN_STEPS);

        double pathologicalRadius = 2048D * 0.5D + 2048D;
        RiverTopologyComplexity.Estimate pathological = RiverTopologyComplexity.estimate(
                64,
                64,
                0.49D,
                256,
                pathologicalRadius,
                1024D,
                64
        );
        assertFalse(pathological.safe());
        assertTrue(pathological.sourceWindowCells() > 400_000L);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> options(10L)
                        .cellSize(64)
                        .tileCells(64)
                        .siteJitter(0.49D)
                        .maxRouteReaches(256)
                        .maximumReachRadius(pathologicalRadius)
                        .worms(List.of(worm(1L, 1D, 1D, 1024D, 64)))
                        .build()
        );
        assertTrue(failure.getMessage(), failure.getMessage().contains("source window"));
    }

    private static RiverNetworkOptions.Builder options(long seed) {
        return RiverNetworkOptions.builder(seed)
                .cellSize(64)
                .tileCells(2)
                .siteJitter(0.25)
                .maxRouteReaches(12)
                .sourceChance(1.0)
                .reachChance(1.0)
                .dryChannelChance(1.0)
                .requireOcean(true)
                .terrainHeightWeight(1.0)
                .routingNoiseWeight(0.0)
                .oceanAttraction(256.0)
                .channelWidth(8.0)
                .bankWidth(6.0)
                .depth(3.0)
                .worms(List.of(worm(1L, 0.5D, 0.15D, 12D, 6)));
    }

    private static RiverWorm worm(
            long seed,
            double tortuosity,
            double detailTortuosity,
            double maximumOffset,
            int segments
    ) {
        return new RiverWorm(
                "worm-" + Long.toUnsignedString(seed),
                seed,
                1D,
                1024D,
                256D,
                tortuosity,
                detailTortuosity,
                maximumOffset,
                segments,
                1D,
                1D,
                1D,
                512D,
                128D,
                0.3D,
                0D,
                0D,
                0D,
                0D,
                4,
                0.35D,
                1D,
                0D,
                0D,
                List.of()
        );
    }

    private static RiverWorm wormProfile(
            String id,
            long seed,
            int segments,
            double widthMultiplier,
            double bankMultiplier,
            double depthMultiplier,
            int branchCap,
            double branchDecay,
            double confluenceMultiplier,
            double childChance,
            double branchChildChance,
            List<RiverWorm> children
    ) {
        return wormProfile(
                id,
                seed,
                segments,
                widthMultiplier,
                bankMultiplier,
                depthMultiplier,
                512D,
                128D,
                0D,
                0D,
                0D,
                0D,
                branchCap,
                branchDecay,
                confluenceMultiplier,
                childChance,
                branchChildChance,
                children
        );
    }

    private static RiverWorm wormProfile(
            String id,
            long seed,
            int segments,
            double widthMultiplier,
            double bankMultiplier,
            double depthMultiplier,
            double bodyWavelength,
            double bodyDetailWavelength,
            double widthVariation,
            double bankVariation,
            double depthVariation,
            double roofVariation,
            int branchCap,
            double branchDecay,
            double confluenceMultiplier,
            double childChance,
            double branchChildChance,
            List<RiverWorm> children
    ) {
        return new RiverWorm(
                id,
                seed,
                1D,
                256D,
                64D,
                0.7D,
                0.2D,
                32D,
                segments,
                widthMultiplier,
                bankMultiplier,
                depthMultiplier,
                bodyWavelength,
                bodyDetailWavelength,
                0.3D,
                widthVariation,
                bankVariation,
                depthVariation,
                roofVariation,
                branchCap,
                branchDecay,
                confluenceMultiplier,
                childChance,
                branchChildChance,
                children
        );
    }

    private static RiverNode node(long cellX, long cellZ, double x, double z) {
        return new RiverNode(
                new RiverNodeId(cellX, cellZ),
                x,
                z,
                64D,
                64D,
                64D,
                64D,
                false,
                true
        );
    }

    private static RiverReach reach(RiverNode from, RiverNode to, double width, double bankWidth) {
        return new RiverReach(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                RiverRouteState.WET,
                1,
                1,
                width,
                bankWidth,
                3D,
                RiverBodyProfile.constant(width, bankWidth, 3D),
                false,
                false,
                new RiverPolyline(
                        new double[]{from.x(), to.x()},
                        new double[]{from.z(), to.z()}
                )
        );
    }

    private static RiverTerrainSampler slopedTerrain(boolean ocean) {
        return new TestTerrain(ocean);
    }

    private static RiverTerrainSampler flatTerrain(boolean ocean) {
        return new RiverTerrainSampler() {
            @Override
            public double naturalHeight(int blockX, int blockZ) {
                return 64.0;
            }

            @Override
            public boolean isOcean(int blockX, int blockZ) {
                return ocean && blockX >= 224;
            }
        };
    }

    private static int compareRank(RiverNode first, RiverNode second) {
        if (first.ocean() != second.ocean()) {
            return first.ocean() ? -1 : 1;
        }
        int comparison = Double.compare(first.rank(), second.rank());
        if (comparison != 0) {
            return comparison;
        }
        int hydraulicComparison = Double.compare(first.hydraulicHeight(), second.hydraulicHeight());
        return hydraulicComparison != 0 ? hydraulicComparison : first.id().compareTo(second.id());
    }

    private static void assertWormBounds(RiverReach reach, double maximumOffset) {
        assertTrue(maximumWormDisplacement(reach) <= maximumOffset + 0.0000001D);
        RiverPolyline polyline = reach.polyline();
        assertEquals(reach.from().x(), polyline.x(0), 0D);
        assertEquals(reach.from().z(), polyline.z(0), 0D);
        int last = polyline.size() - 1;
        assertEquals(reach.to().x(), polyline.x(last), 0D);
        assertEquals(reach.to().z(), polyline.z(last), 0D);
    }

    private static double maximumWormDisplacement(RiverReach reach) {
        RiverPolyline polyline = reach.polyline();
        double deltaX = reach.to().x() - reach.from().x();
        double deltaZ = reach.to().z() - reach.from().z();
        double maximumDisplacement = 0D;
        for (int point = 0; point < polyline.size(); point++) {
            double t = (double) point / (polyline.size() - 1);
            double straightX = reach.from().x() + deltaX * t;
            double straightZ = reach.from().z() + deltaZ * t;
            double displacement = StrictMath.hypot(
                    polyline.x(point) - straightX,
                    polyline.z(point) - straightZ
            );
            maximumDisplacement = StrictMath.max(maximumDisplacement, displacement);
        }
        return maximumDisplacement;
    }

    private static boolean polylinesDiffer(RiverPolyline first, RiverPolyline second) {
        if (first.size() != second.size()) {
            return true;
        }
        for (int point = 0; point < first.size(); point++) {
            if (Double.doubleToLongBits(first.x(point)) != Double.doubleToLongBits(second.x(point))
                    || Double.doubleToLongBits(first.z(point)) != Double.doubleToLongBits(second.z(point))) {
                return true;
            }
        }
        return false;
    }

    private static long digest(RiverTile tile) {
        long hash = 0xCBF29CE484222325L;
        for (RiverReach reach : tile.reaches()) {
            hash = RiverNetwork.mix(hash ^ reach.id().stableId());
            hash = digestNode(hash, reach.from());
            hash = digestNode(hash, reach.to());
            hash = RiverNetwork.mix(hash ^ reach.flow());
            hash = RiverNetwork.mix(hash ^ reach.order());
            hash = RiverNetwork.mix(hash ^ reach.state().ordinal());
            hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.width()));
            for (int index = 0; index < reach.bodyProfile().size(); index++) {
                hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.bodyProfile().position(index)));
                hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.bodyProfile().widthAtIndex(index)));
                hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.bodyProfile().bankWidthAtIndex(index)));
                hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.bodyProfile().depthAtIndex(index)));
                hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.bodyProfile().roofScaleAtIndex(index)));
            }
            hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.bankWidth()));
            hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.depth()));
            hash = RiverNetwork.mix(hash ^ (reach.mouth() ? 1L : 0L));
            hash = RiverNetwork.mix(hash ^ (reach.terminal() ? 1L : 0L));
            for (int point = 0; point < reach.polyline().size(); point++) {
                hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.polyline().x(point)));
                hash = RiverNetwork.mix(hash ^ Double.doubleToLongBits(reach.polyline().z(point)));
            }
        }
        return hash;
    }

    private static long digestNode(long hash, RiverNode node) {
        long result = RiverNetwork.mix(hash ^ node.id().stableId());
        result = RiverNetwork.mix(result ^ Double.doubleToLongBits(node.x()));
        result = RiverNetwork.mix(result ^ Double.doubleToLongBits(node.z()));
        result = RiverNetwork.mix(result ^ Double.doubleToLongBits(node.naturalHeight()));
        result = RiverNetwork.mix(result ^ Double.doubleToLongBits(node.hydraulicHeight()));
        result = RiverNetwork.mix(result ^ Double.doubleToLongBits(node.rank()));
        result = RiverNetwork.mix(result ^ Double.doubleToLongBits(node.routingScore()));
        result = RiverNetwork.mix(result ^ (node.ocean() ? 1L : 0L));
        return RiverNetwork.mix(result ^ (node.riverAllowed() ? 1L : 0L));
    }

    private static void assertReachEquals(RiverReach first, RiverReach second) {
        assertEquals(first.id(), second.id());
        assertEquals(first.state(), second.state());
        assertEquals(first.flow(), second.flow());
        assertEquals(first.order(), second.order());
        assertEquals(first.width(), second.width(), 0.0);
        assertEquals(first.bodyProfile(), second.bodyProfile());
        assertEquals(first.bankWidth(), second.bankWidth(), 0.0);
        assertEquals(first.depth(), second.depth(), 0.0);
        assertEquals(first.mouth(), second.mouth());
        assertEquals(first.terminal(), second.terminal());
        assertEquals(first.from(), second.from());
        assertEquals(first.to(), second.to());
        assertEquals(first.polyline().size(), second.polyline().size());
        for (int point = 0; point < first.polyline().size(); point++) {
            assertEquals(first.polyline().x(point), second.polyline().x(point), 0.0);
            assertEquals(first.polyline().z(point), second.polyline().z(point), 0.0);
        }
    }

    private static class TestTerrain implements RiverTerrainSampler {
        private final boolean ocean;

        private TestTerrain(boolean ocean) {
            this.ocean = ocean;
        }

        @Override
        public double naturalHeight(int blockX, int blockZ) {
            return 256.0 - blockX;
        }

        @Override
        public boolean isOcean(int blockX, int blockZ) {
            return ocean && blockX >= 224;
        }
    }
}

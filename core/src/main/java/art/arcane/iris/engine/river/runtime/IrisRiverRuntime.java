package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IRare;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisGeneratorStyle;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverCaveMode;
import art.arcane.iris.engine.object.IrisRiverCaves;
import art.arcane.iris.engine.object.IrisRiverNoiseChance;
import art.arcane.iris.engine.object.IrisRiverRoutingPolicy;
import art.arcane.iris.engine.object.IrisRiverTerminalMode;
import art.arcane.iris.engine.object.IrisRiverTerrain;
import art.arcane.iris.engine.object.IrisRiverTopology;
import art.arcane.iris.engine.object.IrisRiverWater;
import art.arcane.iris.engine.object.IrisRiverWaterMode;
import art.arcane.iris.engine.object.IrisStyledRange;
import art.arcane.iris.engine.object.NoiseStyle;
import art.arcane.iris.engine.river.RiverAnchor;
import art.arcane.iris.engine.river.RiverEdgeId;
import art.arcane.iris.engine.river.RiverMeanderContext;
import art.arcane.iris.engine.river.RiverNetworkOptions;
import art.arcane.iris.engine.river.RiverPolyline;
import art.arcane.iris.engine.river.RiverReach;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverRoutingContext;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.RiverTerrainNodeSample;
import art.arcane.iris.engine.river.RiverTerrainSourceSample;
import art.arcane.iris.engine.river.RiverTerrainSampler;
import art.arcane.iris.engine.river.RiverTerminalPolicy;
import art.arcane.iris.engine.river.RiverTile;
import art.arcane.iris.engine.river.RiverTileCache;
import art.arcane.iris.util.project.interpolation.NoiseBounds;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisRiverRuntime implements AutoCloseable {
    static final int MAXIMUM_REACH_FEASIBILITY_SAMPLES = 65;
    static final int FEASIBILITY_REJECT = 0;
    static final int FEASIBILITY_ACCEPT = 1;
    static final int FEASIBILITY_SAMPLE_BED = 2;
    private static final long SOURCE_NOISE_SALT = 0x243F6A8885A308D3L;
    private static final long CONTINUATION_NOISE_SALT = 0x13198A2E03707344L;
    private static final long INCISION_NOISE_SALT = 0xA4093822299F31D0L;
    private static final long INCISION_GATE_SALT = 0x082EFA98EC4E6C89L;
    private static final long ROUTING_NOISE_SALT = 0x452821E638D01377L;
    private static final long WIDTH_NOISE_SALT = 0xBE5466CF34E90C6CL;
    private static final long BANK_NOISE_SALT = 0xC0AC29B7C97C50DDL;
    private static final long DEPTH_NOISE_SALT = 0x3F84D5B5B5470917L;
    private static final long MEANDER_NOISE_SALT = 0x9216D5D98979FB1BL;
    private static final long BED_NOISE_SALT = 0xD1310BA698DFB5ACL;
    private static final long BIOME_NOISE_SALT = 0x2FFD72DBD01ADFB7L;
    private static final long CAVE_ENTRY_NOISE_SALT = 0xB8E1AFED6A267E96L;
    private static final long CAVE_ENTRY_GATE_SALT = 0xBA7C9045F12C7F99L;
    private static final long FLOODED_CAVE_BIOME_SALT = 0x24A19947B3916CF7L;
    private static final long TERMINAL_CAVE_ANCHOR_SALT = 0x9E3779B97F4A7C15L;
    private static final int TILE_CACHE_SIZE = 32;

    private final long seed;
    private final IrisRiverNetwork configuration;
    private final IrisData data;
    private final int fluidHeight;
    private final boolean boreMantleActive;
    private final boolean caveHydrologyActive;
    private final boolean blockingRoutingPossible;
    private final boolean variableMaxIncisionPossible;
    private final boolean biomeRiverOverridesPossible;
    private final RiverHeightBoundsSampler naturalHeightBounds;
    private final ProceduralStream<Double> naturalHeight;
    private final ProceduralStream<Double> naturalSlope;
    private final ProceduralStream<Boolean> naturalOcean;
    private final ProceduralStream<IrisBiome> naturalBiome;
    private final ProceduralStream<IrisRegion> region;
    private final IrisRiverTerrain terrain;
    private final IrisRiverWater water;
    private final CNG sourceNoise;
    private final CNG continuationNoise;
    private final CNG incisionNoise;
    private final CNG routingNoise;
    private final CNG widthNoise;
    private final CNG bankNoise;
    private final CNG depthNoise;
    private final CNG meanderNoise;
    private final CNG bedNoise;
    private final CNG biomeNoise;
    private final CNG caveEntryNoise;
    private final art.arcane.iris.engine.river.RiverNetwork network;
    private final RuntimeTerrainSampler terrainSampler;
    private final RiverTileCache tileCache;
    private final ConcurrentHashMap<IdentitySettingsKey, EffectiveRiverSettings> settingsCache;
    private final ConcurrentHashMap<BiomePoolKey, List<IrisBiome>> biomePoolCache;

    public IrisRiverRuntime(IrisRiverRuntimeContext context) {
        Objects.requireNonNull(context);
        seed = context.seed();
        configuration = context.configuration();
        data = context.data();
        fluidHeight = context.fluidHeight();
        boreMantleActive = context.boreMantleActive();
        caveHydrologyActive = context.caveHydrologyActive();
        blockingRoutingPossible = context.blockingRoutingPossible();
        variableMaxIncisionPossible = context.variableMaxIncisionPossible();
        biomeRiverOverridesPossible = context.biomeRiverOverridesPossible();
        naturalHeightBounds = context.naturalHeightBounds();
        naturalHeight = context.naturalHeight();
        naturalSlope = context.naturalSlope();
        naturalOcean = context.naturalOcean();
        naturalBiome = context.naturalBiome();
        region = context.region();
        terrain = Objects.requireNonNull(configuration.getTerrain());
        water = Objects.requireNonNull(configuration.getWater());
        IrisRiverTopology topology = Objects.requireNonNull(configuration.getTopology());
        sourceNoise = noise(topology.getSource(), SOURCE_NOISE_SALT);
        continuationNoise = noise(topology.getContinuation(), CONTINUATION_NOISE_SALT);
        incisionNoise = noise(terrain.getIncision(), INCISION_NOISE_SALT);
        routingNoise = noise(topology.getRoutingStyle(), ROUTING_NOISE_SALT);
        widthNoise = noise(terrain.getChannelWidth(), WIDTH_NOISE_SALT);
        bankNoise = noise(terrain.getBankWidth(), BANK_NOISE_SALT);
        depthNoise = noise(terrain.getDepth(), DEPTH_NOISE_SALT);
        meanderNoise = noise(terrain.getMeanderStyle(), MEANDER_NOISE_SALT);
        bedNoise = noise(terrain.getBedRoughnessStyle(), BED_NOISE_SALT);
        biomeNoise = noise(configuration.getBiomes().getSelectionStyle(), BIOME_NOISE_SALT);
        caveEntryNoise = noise(caveSettings().getEntry(), CAVE_ENTRY_NOISE_SALT);
        settingsCache = new ConcurrentHashMap<>();
        biomePoolCache = new ConcurrentHashMap<>();
        RiverNetworkOptions options = options(topology, terrain);
        network = new art.arcane.iris.engine.river.RiverNetwork(options);
        terrainSampler = new RuntimeTerrainSampler(topology);
        tileCache = new RiverTileCache(
                TILE_CACHE_SIZE,
                (tileX, tileZ) -> network.buildTile(tileX, tileZ, terrainSampler)
        );
    }

    public IrisRiverSurfaceSample sample(double x, double z) {
        ResolvedRiverColumn column = resolveColumn(x, z);
        if (column == null) {
            return IrisRiverSurfaceSample.none(naturalHeight.get(x, z), fluidHeight);
        }
        if (column.subterranean()) {
            return new IrisRiverSurfaceSample(
                    column.river(),
                    column.naturalHeight(),
                    column.naturalHeight(),
                    column.waterSurfaceY(),
                    true,
                    false
            );
        }
        double carveWeight = StrictMath.pow(
                clamp01(column.river().carveWeight()),
                Math.max(0.125D, terrain.getBankExponent())
        );
        if (column.river().terminal() && shouldTaperTerminal(column.reach())) {
            carveWeight *= terminalWeight(
                    terrain.getTerminalTaper(),
                    column.reach().polyline().length(),
                    column.river().alongReach()
            );
        }
        double terrainHeight = incisedHeight(
                column.naturalHeight(),
                column.bedHeight(),
                carveWeight,
                column.maximumIncision()
        );
        boolean wet = column.river().state() == RiverRouteState.WET;
        boolean surfaceFluid = wet && Math.round(terrainHeight) < Math.round(column.waterSurfaceY());
        return new IrisRiverSurfaceSample(
                column.river(),
                column.naturalHeight(),
                terrainHeight,
                wet ? column.waterSurfaceY() : terrainHeight,
                false,
                surfaceFluid
        );
    }

    public IrisRiverTunnelSample sampleTunnel(double x, double z) {
        ResolvedRiverColumn column = resolveColumn(x, z);
        if (column == null
                || !column.subterranean()
                || column.river().state() != RiverRouteState.WET
                || column.river().section() != RiverSection.CHANNEL) {
            return null;
        }
        double channelRadius = column.river().width() * 0.5D;
        double normalizedDistance = channelRadius <= 0D
                ? 1D
                : clamp01(column.river().distance() / channelRadius);
        double roofProfile = StrictMath.sqrt(Math.max(0D, 1D - normalizedDistance * normalizedDistance));
        int bedY = (int) Math.round(column.bedHeight());
        int waterHeadY = (int) Math.round(column.waterSurfaceY());
        int ceilingY = waterHeadY + (int) StrictMath.ceil(caveSettings().getDryHeadroom() * roofProfile);
        if (bedY >= waterHeadY) {
            return null;
        }
        return new IrisRiverTunnelSample(column.river(), bedY, waterHeadY, ceilingY);
    }

    private ResolvedRiverColumn resolveColumn(double x, double z) {
        double sampledNaturalHeight = naturalHeight.get(x, z);
        RiverTile tile = tileAt(x, z);
        RiverSample river = tile.sample(x, z);
        if (!river.present()) {
            return null;
        }
        RiverReach reach = tile.reach(river.reachId());
        IrisRegion sampledRegion = region.get(x, z);
        IrisBiome sampledBiome = naturalBiome.get(x, z);
        EffectiveRiverSettings settings = settingsFor(sampledRegion, sampledBiome);
        double waterSurfaceY = river.state() == RiverRouteState.WET
                ? waterSurface(reach, river.alongReach(), isNaturalOcean(sampledBiome))
                : sampledNaturalHeight;
        double bedHeight = river.state() == RiverRouteState.WET
                ? waterSurfaceY - river.depth() + bedRoughness(x, z)
                : sampledNaturalHeight - river.depth() + bedRoughness(x, z);
        double maximumIncision = Math.max(0D, terrain.getMaxIncision() * settings.maxIncisionMultiplier());
        return new ResolvedRiverColumn(
                river,
                reach,
                sampledNaturalHeight,
                waterSurfaceY,
                bedHeight,
                maximumIncision,
                isSubterraneanSegment(reach, river.alongReach())
        );
    }

    private boolean isSubterraneanSegment(RiverReach reach, double alongReach) {
        if (!boreMantleActive || reach.state() != RiverRouteState.WET) {
            return false;
        }
        CenterlinePosition center = centerlinePosition(reach, alongReach);
        IrisRegion sampledRegion = region.get(center.x(), center.z());
        IrisBiome sampledBiome = naturalBiome.get(center.x(), center.z());
        double head = waterSurface(reach, alongReach, isNaturalOcean(sampledBiome));
        double centerNaturalHeight = naturalHeight.get(center.x(), center.z());
        double centerBedHeight = head - reach.depth() + bedRoughness(center.x(), center.z());
        EffectiveRiverSettings settings = settingsFor(sampledRegion, sampledBiome);
        double maximumIncision = Math.max(0D, terrain.getMaxIncision() * settings.maxIncisionMultiplier());
        double cappedSurface = incisedHeight(centerNaturalHeight, centerBedHeight, 1D, maximumIncision);
        return Math.round(cappedSurface) >= Math.round(head);
    }

    private static CenterlinePosition centerlinePosition(RiverReach reach, double alongReach) {
        double targetDistance = clamp01(alongReach) * reach.polyline().length();
        for (int point = 0; point < reach.polyline().size() - 1; point++) {
            double segmentStart = reach.polyline().cumulativeLength(point);
            double segmentEnd = reach.polyline().cumulativeLength(point + 1);
            if (targetDistance > segmentEnd && point < reach.polyline().size() - 2) {
                continue;
            }
            double segmentLength = segmentEnd - segmentStart;
            double t = segmentLength <= 0D ? 0D : (targetDistance - segmentStart) / segmentLength;
            return new CenterlinePosition(
                    reach.polyline().x(point)
                            + (reach.polyline().x(point + 1) - reach.polyline().x(point)) * t,
                    reach.polyline().z(point)
                            + (reach.polyline().z(point + 1) - reach.polyline().z(point)) * t
            );
        }
        int last = reach.polyline().size() - 1;
        return new CenterlinePosition(reach.polyline().x(last), reach.polyline().z(last));
    }

    public RiverTile tileAt(double x, double z) {
        int blockX = clampToInt(StrictMath.floor(x));
        int blockZ = clampToInt(StrictMath.floor(z));
        return tileCache.get(network.tileXForBlock(blockX), network.tileZForBlock(blockZ));
    }

    public RiverSample sampleFootprint(
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        if (!Double.isFinite(minimumX) || !Double.isFinite(minimumZ)
                || !Double.isFinite(maximumX) || !Double.isFinite(maximumZ)
                || minimumX > maximumX || minimumZ > maximumZ) {
            throw new IllegalArgumentException("River footprint bounds must be finite and ordered");
        }
        double centerX = minimumX * 0.5D + maximumX * 0.5D;
        double centerZ = minimumZ * 0.5D + maximumZ * 0.5D;
        return tileAt(centerX, centerZ).sampleFootprint(minimumX, minimumZ, maximumX, maximumZ);
    }

    public List<RiverAnchor> candidateAnchors(
            int minimumX,
            int minimumZ,
            int maximumX,
            int maximumZ,
            double spacing,
            long salt
    ) {
        if (minimumX >= maximumX || minimumZ >= maximumZ) {
            return List.of();
        }
        int minimumTileX = network.tileXForBlock(minimumX);
        int minimumTileZ = network.tileZForBlock(minimumZ);
        int maximumTileX = network.tileXForBlock(maximumX - 1);
        int maximumTileZ = network.tileZForBlock(maximumZ - 1);
        ArrayList<RiverAnchor> anchors = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
            for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
                RiverTile tile = tileCache.get(tileX, tileZ);
                List<RiverAnchor> candidates = tile.candidateAnchors(
                        minimumX,
                        minimumZ,
                        maximumX,
                        maximumZ,
                        spacing,
                        salt
                );
                for (RiverAnchor anchor : candidates) {
                    if (seen.add(anchor.stableId())) {
                        anchors.add(anchor);
                    }
                }
                addTerminalCaveAnchors(
                        tile,
                        minimumX,
                        minimumZ,
                        maximumX,
                        maximumZ,
                        spacing,
                        salt,
                        anchors,
                        seen
                );
            }
        }
        return List.copyOf(anchors);
    }

    public EffectiveRiverSettings settingsAt(double x, double z) {
        IrisRegion sampledRegion = region.get(x, z);
        IrisBiome sampledBiome = biomeRiverOverridesPossible ? naturalBiome.get(x, z) : null;
        return settingsFor(sampledRegion, sampledBiome);
    }

    private EffectiveRiverSettings settingsFor(IrisRegion sampledRegion, IrisBiome sampledBiome) {
        IdentitySettingsKey key = new IdentitySettingsKey(sampledRegion, sampledBiome);
        return settingsCache.computeIfAbsent(
                key,
                ignored -> EffectiveRiverSettings.resolve(configuration, sampledRegion, sampledBiome)
        );
    }

    public IrisRiverCaves caveSettings() {
        IrisRiverCaves caves = configuration.getCaves();
        return caves == null ? new IrisRiverCaves() : caves;
    }

    public double maximumChannelWidth() {
        return terrain.getMaxChannelWidth();
    }

    public boolean acceptsCaveAnchor(RiverAnchor anchor) {
        Objects.requireNonNull(anchor);
        IrisRiverCaves caves = caveSettings();
        if (anchor.state() != RiverRouteState.WET
                || !caveHydrologyActive
                || caves.getMode() == IrisRiverCaveMode.SEALED
                || caves.getMaximumPerReach() <= 0) {
            return false;
        }
        RiverReach reach = tileAt(anchor.x(), anchor.z()).reach(anchor.reachId());
        if (reach == null || reach.state() != RiverRouteState.WET) {
            return false;
        }
        TerminalCaveAnchor terminal = terminalCaveAnchor(reach);
        if (terminal != null) {
            return anchor.stableId() == terminal.stableId()
                    && caves.getMode() != IrisRiverCaveMode.SEALED;
        }
        double firstDistance = unit(art.arcane.iris.engine.river.RiverNetwork.mix(
                reach.id().stableId() ^ anchor.samplingSalt()
        )) * anchor.samplingSpacing();
        double anchorDistance = firstDistance + anchor.index() * anchor.samplingSpacing();
        if (anchorDistance >= reach.polyline().length()) {
            return false;
        }
        int accepted = 0;
        for (int index = 0; index <= anchor.index(); index++) {
            double distance = firstDistance + index * anchor.samplingSpacing();
            TerminalPosition position = positionAt(reach.polyline(), distance);
            long stableId = art.arcane.iris.engine.river.RiverNetwork.mix(
                    reach.id().stableId()
                            ^ anchor.samplingSalt()
                            ^ (long) index * 0x9E3779B97F4A7C15L
            );
            if (!caveEntryEligible(caves, stableId, position.x(), position.z())) {
                continue;
            }
            if (index == anchor.index()) {
                return stableId == anchor.stableId() && accepted < caves.getMaximumPerReach();
            }
            accepted++;
            if (accepted >= caves.getMaximumPerReach()) {
                return false;
            }
        }
        return false;
    }

    public boolean isTerminalCaveAnchor(RiverAnchor anchor) {
        TerminalCaveAnchor terminal = terminalCaveAnchor(anchor);
        return terminal != null && anchor.stableId() == terminal.stableId();
    }

    public String selectFloodedCaveBiome(RiverAnchor anchor) {
        Objects.requireNonNull(anchor);
        List<String> biomes = settingsAt(anchor.x(), anchor.z()).floodedCaveBiomes();
        if (biomes.isEmpty()) {
            return "";
        }
        long hash = art.arcane.iris.engine.river.RiverNetwork.mix(
                seed ^ anchor.stableId() ^ FLOODED_CAVE_BIOME_SALT);
        int index = Math.floorMod(hash, biomes.size());
        String selected = biomes.get(index);
        return selected == null ? "" : selected.trim();
    }

    public IrisBiome selectSurfaceBiome(IrisRiverSurfaceSample sample, double x, double z) {
        RiverSample river = sample.river();
        if (!river.present() || sample.subterranean()) {
            return null;
        }
        EffectiveRiverSettings settings = settingsAt(x, z);
        PoolSelection selection = poolFor(river.section(), settings);
        if (selection.keys().isEmpty()) {
            return null;
        }
        BiomePoolKey key = new BiomePoolKey(selection.keys(), selection.type());
        List<IrisBiome> candidates = biomePoolCache.computeIfAbsent(key, this::loadBiomePool);
        if (candidates.isEmpty()) {
            return null;
        }
        double selector = clamp01(biomeNoise.fitDouble(0D, 1D, x, z));
        IrisBiome selected = IRare.pick(candidates, selector);
        return selected == null ? null : selected.withInferredType(selection.type());
    }

    public int completedTileCount() {
        return tileCache.completedSize();
    }

    boolean allowsReach(RiverRoutingContext context) {
        return terrainSampler.allowsReach(Objects.requireNonNull(context));
    }

    RiverTerrainNodeSample sampleNode(int blockX, int blockZ) {
        return terrainSampler.sampleNode(blockX, blockZ);
    }

    RiverTerrainSourceSample sampleSource(int blockX, int blockZ) {
        return terrainSampler.sampleSource(blockX, blockZ);
    }

    @Override
    public void close() {
        tileCache.close();
        settingsCache.clear();
        biomePoolCache.clear();
    }

    private RiverNetworkOptions options(IrisRiverTopology topology, IrisRiverTerrain riverTerrain) {
        double dryChance = clamp01(riverTerrain.getDryContinuationChance());
        return RiverNetworkOptions.builder(seed)
                .cellSize(topology.getCellSize())
                .tileCells(topology.getTileCells())
                .siteJitter(topology.getSiteJitter())
                .maxRouteReaches(topology.getMaxRouteReaches())
                .minimumSourcesPerTile(topology.getMinimumSourcesPerTile())
                .downstreamCandidateLimit(Math.max(1, Math.min(8, topology.getSinkSearchReaches() + 1)))
                .routingBasinCells(topology.getRoutingBasinCells())
                .routingPlateauHeight(topology.getRoutingPlateauHeight())
                .hydraulicBaseHeight(fluidHeight)
                .requireOcean(topology.isRequireOcean())
                .sourceChance(chance(topology.getSource()))
                .reachChance(chance(topology.getContinuation()))
                .dryChannelChance(dryChance)
                .terrainHeightWeight(topology.getTerrainHeightWeight())
                .routingNoiseWeight(0D)
                .flowAlignmentWeight(topology.getRoutingNoiseWeight())
                .oceanAttraction(topology.getOceanAttraction())
                .channelWidth(mid(riverTerrain.getChannelWidth(), 12D))
                .bankWidth(mid(riverTerrain.getBankWidth(), 8D))
                .depth(mid(riverTerrain.getDepth(), 4D))
                .maxChannelWidth(riverTerrain.getMaxChannelWidth())
                .maxBankWidth(riverTerrain.getMaxBankWidth())
                .maxDepth(riverTerrain.getMaxDepth())
                .orderWidthFactor(riverTerrain.getOrderWidthFactor())
                .orderDepthFactor(riverTerrain.getOrderDepthFactor())
                .maximumReachRadius(maximumReachRadius(topology, riverTerrain))
                .meanderStrength(riverTerrain.getMeanderStrength())
                .meanderSubdivisions(riverTerrain.getMeanderSubdivisions())
                .build();
    }

    private void addTerminalCaveAnchors(
            RiverTile tile,
            int minimumX,
            int minimumZ,
            int maximumX,
            int maximumZ,
            double spacing,
            long salt,
            List<RiverAnchor> anchors,
            Set<Long> seen
    ) {
        for (RiverReach reach : tile.reaches()) {
            TerminalCaveAnchor terminal = terminalCaveAnchor(reach);
            if (terminal == null
                    || terminal.x() < minimumX
                    || terminal.x() >= maximumX
                    || terminal.z() < minimumZ
                    || terminal.z() >= maximumZ
                    || !seen.add(terminal.stableId())) {
                continue;
            }
            anchors.add(new RiverAnchor(
                    reach.id(),
                    0,
                    terminal.stableId(),
                    spacing,
                    salt,
                    terminal.x(),
                    terminal.z(),
                    terminal.alongReach(),
                    reach.state(),
                    reach.flow(),
                    reach.order()
            ));
        }
    }

    private TerminalCaveAnchor terminalCaveAnchor(RiverAnchor anchor) {
        RiverReach reach = tileAt(anchor.x(), anchor.z()).reach(anchor.reachId());
        return reach == null ? null : terminalCaveAnchor(reach);
    }

    private boolean caveEntryEligible(
            IrisRiverCaves caves,
            long stableId,
            double x,
            double z
    ) {
        EffectiveRiverSettings settings = settingsAt(x, z);
        double chance = clamp01(effectiveChance(
                caves.getEntry(),
                caveEntryNoise,
                (int) StrictMath.floor(x),
                (int) StrictMath.floor(z)
        ) * settings.caveEntryMultiplier());
        long hash = art.arcane.iris.engine.river.RiverNetwork.mix(
                seed ^ stableId ^ CAVE_ENTRY_GATE_SALT
        );
        return unit(hash) < chance;
    }

    private TerminalCaveAnchor terminalCaveAnchor(RiverReach reach) {
        if (!caveHydrologyActive || !reach.terminal() || reach.state() != RiverRouteState.WET) {
            return null;
        }
        RiverPolyline polyline = reach.polyline();
        double length = polyline.length();
        double taperDistance = Math.min(length, Math.max(0D, terrain.getTerminalTaper()));
        double targetDistance = taperDistance == 0D ? length : length - (taperDistance * 0.5D);
        TerminalPosition position = positionAt(polyline, targetDistance);
        EffectiveRiverSettings settings = settingsAt(reach.to().x(), reach.to().z());
        if (settings.terminalMode() != IrisRiverTerminalMode.SINKHOLE_GROTTO) {
            return null;
        }
        long stableId = art.arcane.iris.engine.river.RiverNetwork.mix(
                reach.id().stableId() ^ TERMINAL_CAVE_ANCHOR_SALT
        );
        return new TerminalCaveAnchor(stableId, position.x(), position.z(), position.alongReach());
    }

    private boolean shouldTaperTerminal(RiverReach reach) {
        return settingsAt(reach.to().x(), reach.to().z()).terminalMode()
                != IrisRiverTerminalMode.SINKHOLE_GROTTO;
    }

    private TerminalPosition positionAt(RiverPolyline polyline, double targetDistance) {
        double traversed = 0D;
        for (int point = 0; point < polyline.size() - 1; point++) {
            double startX = polyline.x(point);
            double startZ = polyline.z(point);
            double deltaX = polyline.x(point + 1) - startX;
            double deltaZ = polyline.z(point + 1) - startZ;
            double segmentLength = StrictMath.hypot(deltaX, deltaZ);
            if (targetDistance <= traversed + segmentLength || point == polyline.size() - 2) {
                double factor = segmentLength == 0D ? 0D : (targetDistance - traversed) / segmentLength;
                factor = Math.max(0D, Math.min(1D, factor));
                double alongReach = polyline.length() == 0D ? 0D : targetDistance / polyline.length();
                return new TerminalPosition(
                        startX + (deltaX * factor),
                        startZ + (deltaZ * factor),
                        alongReach
                );
            }
            traversed += segmentLength;
        }
        return new TerminalPosition(
                polyline.x(polyline.size() - 1),
                polyline.z(polyline.size() - 1),
                1D
        );
    }

    double waterSurface(RiverReach reach, double alongReach, boolean naturalOcean) {
        if (reach == null || water.getMode() == IrisRiverWaterMode.SEA_LEVEL || naturalOcean) {
            return fluidHeight;
        }
        return terracedWaterSurface(
                reach.from().hydraulicHeight(),
                reach.to().hydraulicHeight(),
                reach.polyline().length(),
                alongReach
        );
    }

    double terracedWaterSurface(
            double fromNaturalHeight,
            double toNaturalHeight,
            double reachLength,
            double alongReach
    ) {
        int dropHeight = Math.max(1, water.getDropHeight());
        int fromHead = nodeWaterHead(fromNaturalHeight, dropHeight);
        int toHead = nodeWaterHead(toNaturalHeight, dropHeight);
        int headDelta = toHead - fromHead;
        int availableDrops = StrictMath.abs(headDelta) / dropHeight;
        if (availableDrops == 0) {
            return fromHead;
        }
        double normalized = clamp01(alongReach);
        double distance = normalized * Math.max(0D, reachLength);
        double configuredPoolLength = Math.max(1D, water.getPoolLength());
        double requiredInteriorLength = configuredPoolLength * Math.max(0, availableDrops - 1);
        double requiredTargetLength = configuredPoolLength * (availableDrops + 1D);
        double dropSpacing;
        double firstDrop;
        if (reachLength >= requiredTargetLength) {
            dropSpacing = configuredPoolLength;
            firstDrop = (reachLength - requiredInteriorLength) * 0.5D;
        } else {
            dropSpacing = reachLength / (availableDrops + 1D);
            firstDrop = dropSpacing;
        }
        int completedDrops;
        if (normalized >= 1D || dropSpacing <= 0D) {
            completedDrops = availableDrops;
        } else if (distance < firstDrop) {
            completedDrops = 0;
        } else {
            completedDrops = 1 + (int) StrictMath.floor((distance - firstDrop) / dropSpacing);
            completedDrops = Math.min(availableDrops, completedDrops);
        }
        int direction = Integer.signum(headDelta);
        return fromHead + direction * completedDrops * dropHeight;
    }

    private double bedRoughness(double x, double z) {
        return bedNoise.fitDouble(
                -terrain.getBedRoughness(),
                terrain.getBedRoughness(),
                x,
                z
        );
    }

    private static double incisedHeight(
            double naturalHeight,
            double bedHeight,
            double carveWeight,
            double maximumIncision
    ) {
        double targetHeight = naturalHeight + (bedHeight - naturalHeight) * carveWeight;
        double guardedTarget = Math.max(targetHeight, naturalHeight - maximumIncision);
        return Math.min(naturalHeight, guardedTarget);
    }

    private int nodeWaterHead(double naturalNodeHeight, int dropHeight) {
        int availableRise = Math.max(0, water.getMaximumPoolRise());
        int maximumHead = fluidHeight + availableRise;
        int naturalHead = (int) StrictMath.floor(naturalNodeHeight - 1D);
        int clamped = Math.max(fluidHeight, Math.min(maximumHead, naturalHead));
        return fluidHeight + Math.floorDiv(clamped - fluidHeight, dropHeight) * dropHeight;
    }

    private static boolean isNaturalOcean(IrisBiome biome) {
        return biome != null && biome.getInferredType() == InferredType.SEA;
    }

    static double terminalWeight(int terminalTaper, double reachLength, double alongReach) {
        double taperFraction = Math.min(
                1D,
                Math.max(0, terminalTaper) / Math.max(0.000001D, reachLength)
        );
        double taperStart = 1D - taperFraction;
        if (alongReach <= taperStart) {
            return 1D;
        }
        return clamp01((1D - alongReach) / Math.max(0.000001D, taperFraction));
    }

    private PoolSelection poolFor(RiverSection section, EffectiveRiverSettings settings) {
        return switch (section) {
            case CHANNEL -> new PoolSelection(settings.channelBiomes(), InferredType.SEA);
            case MOUTH -> new PoolSelection(settings.mouthBiomes(), InferredType.SEA);
            case BANK -> new PoolSelection(settings.bankBiomes(), InferredType.SHORE);
            case DRY_CHANNEL, DRY_BANK -> new PoolSelection(settings.dryBiomes(), InferredType.LAND);
            case NONE -> new PoolSelection(List.of(), InferredType.LAND);
        };
    }

    private List<IrisBiome> loadBiomePool(BiomePoolKey pool) {
        KList<IrisBiome> loaded = data.getBiomeLoader().loadAll(new KList<>(pool.keys()));
        ArrayList<IrisBiome> inferred = new ArrayList<>(loaded.size());
        for (IrisBiome biome : loaded) {
            if (biome != null) {
                inferred.add(biome.withInferredType(pool.type()));
            }
        }
        return List.copyOf(inferred);
    }

    private CNG noise(IrisRiverNoiseChance configured, long salt) {
        IrisGeneratorStyle style = configured == null ? null : configured.getStyle();
        return noise(style, salt);
    }

    private CNG noise(IrisStyledRange configured, long salt) {
        IrisGeneratorStyle style = configured == null ? null : configured.getStyle();
        return noise(style, salt);
    }

    private CNG noise(IrisGeneratorStyle configured, long salt) {
        IrisGeneratorStyle style = configured == null ? new IrisGeneratorStyle(NoiseStyle.FLAT) : configured;
        return style.createNoCache(new RNG(seed ^ salt), data);
    }

    private double effectiveChance(IrisRiverNoiseChance configured, CNG noise, int x, int z) {
        if (configured == null) {
            return 1D;
        }
        double contribution = noise.fitDouble(-configured.getInfluence(), configured.getInfluence(), x, z);
        return clamp01(configured.getChance() + contribution);
    }

    private double chanceMultiplier(IrisRiverNoiseChance configured, CNG noise, int x, int z) {
        double baseChance = chance(configured);
        if (baseChance <= 0D) {
            return 0D;
        }
        return effectiveChance(configured, noise, x, z) / baseChance;
    }

    private static double chance(IrisRiverNoiseChance configured) {
        return configured == null ? 1D : clamp01(configured.getChance());
    }

    private static double mid(IrisStyledRange range, double fallback) {
        if (range == null || !Double.isFinite(range.getMin()) || !Double.isFinite(range.getMax())) {
            return fallback;
        }
        return Math.max(0.000001D, (range.getMin() + range.getMax()) * 0.5D);
    }

    private static double styled(IrisStyledRange range, CNG noise, int x, int z, double fallback) {
        if (range == null || !Double.isFinite(range.getMin()) || !Double.isFinite(range.getMax())) {
            return fallback;
        }
        double minimum = Math.min(range.getMin(), range.getMax());
        double maximum = Math.max(range.getMin(), range.getMax());
        return noise.fitDouble(minimum, maximum, x, z);
    }

    private static double maximumReachRadius(IrisRiverTopology topology, IrisRiverTerrain riverTerrain) {
        return riverTerrain.getMaxChannelWidth() * 0.5D + riverTerrain.getMaxBankWidth();
    }

    private static double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static int clampToInt(double value) {
        return (int) StrictMath.max(Integer.MIN_VALUE, StrictMath.min(Integer.MAX_VALUE, value));
    }

    private static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    private final class RuntimeTerrainSampler implements RiverTerrainSampler {
        private final IrisRiverTopology topology;

        private RuntimeTerrainSampler(IrisRiverTopology topology) {
            this.topology = topology;
        }

        @Override
        public RiverTerrainNodeSample sampleNode(int blockX, int blockZ) {
            boolean naturalHeightRequired = topology.getTerrainHeightWeight() > 0D
                    || water.getMode() != IrisRiverWaterMode.SEA_LEVEL;
            double sampledNaturalHeight = naturalHeightRequired
                    ? naturalHeight.get(blockX, blockZ)
                    : fluidHeight;
            boolean sampledOcean = Boolean.TRUE.equals(naturalOcean.get(blockX, blockZ));
            IrisRegion sampledRegion = region.get(blockX, blockZ);
            IrisBiome sampledBiome = biomeRiverOverridesPossible ? naturalBiome.get(blockX, blockZ) : null;
            EffectiveRiverSettings settings = settingsFor(sampledRegion, sampledBiome);
            return new RiverTerrainNodeSample(
                    sampledNaturalHeight,
                    sampledOcean,
                    settings.routingPolicy() != IrisRiverRoutingPolicy.BLOCK,
                    routingCost(blockX, blockZ, settings)
            );
        }

        @Override
        public RiverTerrainSourceSample sampleSource(int blockX, int blockZ) {
            EffectiveRiverSettings settings = settingsAt(blockX, blockZ);
            double chanceMultiplier = settings.allowSources()
                    ? chanceMultiplier(topology.getSource(), sourceNoise, blockX, blockZ)
                    : 0D;
            return new RiverTerrainSourceSample(
                    chanceMultiplier,
                    settings.routingPolicy() != IrisRiverRoutingPolicy.BLOCK,
                    Boolean.TRUE.equals(naturalOcean.get(blockX, blockZ))
            );
        }

        @Override
        public double naturalHeight(int blockX, int blockZ) {
            return IrisRiverRuntime.this.naturalHeight.get(blockX, blockZ);
        }

        @Override
        public boolean isOcean(int blockX, int blockZ) {
            return Boolean.TRUE.equals(naturalOcean.get(blockX, blockZ));
        }

        @Override
        public double routingCost(int blockX, int blockZ) {
            EffectiveRiverSettings settings = settingsAt(blockX, blockZ);
            return routingCost(blockX, blockZ, settings);
        }

        @Override
        public double sourceChanceMultiplier(int blockX, int blockZ) {
            EffectiveRiverSettings settings = settingsAt(blockX, blockZ);
            if (!settings.allowSources()) {
                return 0D;
            }
            return chanceMultiplier(topology.getSource(), sourceNoise, blockX, blockZ);
        }

        @Override
        public double maximumSourceChanceMultiplier() {
            IrisRiverNoiseChance configured = topology.getSource();
            if (configured == null) {
                return 1D;
            }
            double baseChance = chance(configured);
            if (baseChance <= 0D) {
                return 0D;
            }
            return clamp01(baseChance + StrictMath.abs(configured.getInfluence())) / baseChance;
        }

        @Override
        public double reachChanceMultiplier(int blockX, int blockZ) {
            EffectiveRiverSettings settings = settingsAt(blockX, blockZ);
            return chanceMultiplier(topology.getContinuation(), continuationNoise, blockX, blockZ)
                    * settings.continuationChanceMultiplier();
        }

        @Override
        public boolean allowsRiver(int blockX, int blockZ) {
            return settingsAt(blockX, blockZ).routingPolicy() != IrisRiverRoutingPolicy.BLOCK;
        }

        @Override
        public boolean allowsReach(RiverRoutingContext context) {
            if (!incisionGate(context)) {
                return false;
            }
            if (boreMantleActive) {
                if (!blockingRoutingPossible) {
                    return true;
                }
                return RiverPolylineProbe.all(
                        context.polyline(),
                        MAXIMUM_REACH_FEASIBILITY_SAMPLES,
                        (x, z, alongReach) -> settingsAt(x, z).routingPolicy()
                                != IrisRiverRoutingPolicy.BLOCK
                );
            }
            double depth = depth(context, mid(terrain.getDepth(), 4D));
            return RiverPolylineProbe.all(
                    context.polyline(),
                    MAXIMUM_REACH_FEASIBILITY_SAMPLES,
                    (x, z, alongReach) -> allowsReachSample(context, depth, alongReach, x, z)
            );
        }

        private boolean allowsReachSample(
                RiverRoutingContext context,
                double depth,
                double alongReach,
                int x,
                int z
        ) {
            double maximumIncision = terrain.getMaxIncision();
            if (blockingRoutingPossible || variableMaxIncisionPossible) {
                EffectiveRiverSettings settings = settingsAt(x, z);
                if (settings.routingPolicy() == IrisRiverRoutingPolicy.BLOCK) {
                    return false;
                }
                maximumIncision *= settings.maxIncisionMultiplier();
            }
            double head = configuration.getWater().getMode() == IrisRiverWaterMode.SEA_LEVEL
                    ? fluidHeight
                    : terracedWaterSurface(
                            context.from().hydraulicHeight(),
                            context.to().hydraulicHeight(),
                            context.polyline().length(),
                            alongReach
                    );
            int rangeDecision = boundedFeasibilityRange(
                    naturalHeightBounds.sample(x, z),
                    head,
                    maximumIncision
            );
            if (rangeDecision == FEASIBILITY_ACCEPT) {
                return true;
            }
            if (rangeDecision == FEASIBILITY_REJECT) {
                return false;
            }
            double sampledNaturalHeight = naturalHeight(x, z);
            int boundedDecision = boundedFeasibility(
                    sampledNaturalHeight,
                    head,
                    maximumIncision,
                    depth,
                    terrain.getBedRoughness());
            if (boundedDecision == FEASIBILITY_ACCEPT) {
                return true;
            }
            if (boundedDecision == FEASIBILITY_REJECT) {
                return false;
            }
            maximumIncision = Math.max(0D, maximumIncision);
            double bedHeight = head - depth + bedRoughness(x, z);
            double finalHeight = incisedHeight(sampledNaturalHeight, bedHeight, 1D, maximumIncision);
            return Math.round(finalHeight) < Math.round(head);
        }

        @Override
        public double reachRoutingCost(RiverRoutingContext context) {
            return routingCost(context.midpointX(), context.midpointZ());
        }

        @Override
        public double meanderNoise(RiverMeanderContext context) {
            return meanderNoise.fitDouble(-1D, 1D, context.x(), context.z());
        }

        @Override
        public double flowNoise(double x, double z) {
            return routingNoise.fitDouble(-1D, 1D, x, z);
        }

        @Override
        public double channelWidth(RiverRoutingContext context, double fallback) {
            EffectiveRiverSettings settings = settingsAt(context.midpointX(), context.midpointZ());
            return styled(terrain.getChannelWidth(), widthNoise, context.midpointX(), context.midpointZ(), fallback)
                    * settings.widthMultiplier();
        }

        @Override
        public double bankWidth(RiverRoutingContext context, double fallback) {
            EffectiveRiverSettings settings = settingsAt(context.midpointX(), context.midpointZ());
            return styled(terrain.getBankWidth(), bankNoise, context.midpointX(), context.midpointZ(), fallback)
                    * settings.bankWidthMultiplier();
        }

        @Override
        public double depth(RiverRoutingContext context, double fallback) {
            EffectiveRiverSettings settings = settingsAt(context.midpointX(), context.midpointZ());
            double configuredDepth = styled(
                    terrain.getDepth(),
                    depthNoise,
                    context.midpointX(),
                    context.midpointZ(),
                    fallback
            ) * settings.depthMultiplier();
            return Math.min(
                    terrain.getMaxDepth(),
                    Math.max(1D + terrain.getBedRoughness(), configuredDepth)
            );
        }

        @Override
        public RiverTerminalPolicy terminalPolicy(int blockX, int blockZ) {
            EffectiveRiverSettings settings = settingsAt(blockX, blockZ);
            if (settings.terminalMode() == IrisRiverTerminalMode.SINKHOLE_GROTTO) {
                return caveHydrologyActive ? RiverTerminalPolicy.WET : RiverTerminalPolicy.SUPPRESS;
            }
            if (!topology.isRequireOcean() && !settings.terminalModeOverridden()) {
                return RiverTerminalPolicy.WET;
            }
            return switch (settings.terminalMode()) {
                case SINKHOLE_GROTTO -> caveHydrologyActive
                        ? RiverTerminalPolicy.WET
                        : RiverTerminalPolicy.SUPPRESS;
                case DRY_CHANNEL -> RiverTerminalPolicy.DRY;
                case SUPPRESS -> RiverTerminalPolicy.SUPPRESS;
            };
        }

        private double routingCost(int blockX, int blockZ, EffectiveRiverSettings settings) {
            double noiseCost = routingNoise.fitDouble(0D, topology.getRoutingNoiseWeight(), blockX, blockZ);
            double slopeWeight = topology.getTerrainSlopeWeight();
            double slopeCost = slopeWeight <= 0D
                    ? 0D
                    : Math.max(0D, naturalSlope.get(blockX, blockZ)) * slopeWeight;
            double avoidance = settings.routingPolicy() == IrisRiverRoutingPolicy.AVOID
                    ? topology.getRoutingNoiseWeight() + topology.getOceanAttraction() + 64D
                    : 0D;
            return (noiseCost + slopeCost + avoidance) * settings.routingCostMultiplier();
        }

        private boolean incisionGate(RiverRoutingContext context) {
            IrisRiverNoiseChance configured = terrain.getIncision();
            double chance = effectiveChance(
                    configured,
                    incisionNoise,
                    context.midpointX(),
                    context.midpointZ()
            );
            long hash = art.arcane.iris.engine.river.RiverNetwork.mix(
                    seed ^ context.edgeId().stableId() ^ INCISION_GATE_SALT
            );
            return unit(hash) < chance;
        }

    }

    static int boundedFeasibility(
            double naturalHeight,
            double head,
            double maximumIncision,
            double depth,
            double maximumBedRoughness
    ) {
        long roundedHead = Math.round(head);
        if (Math.round(naturalHeight) < roundedHead) {
            return FEASIBILITY_ACCEPT;
        }
        double clampedIncision = Math.max(0D, maximumIncision);
        if (Math.round(naturalHeight - clampedIncision) >= roundedHead) {
            return FEASIBILITY_REJECT;
        }
        double maximumBedHeight = head - depth + Math.abs(maximumBedRoughness);
        return Math.round(maximumBedHeight) < roundedHead
                ? FEASIBILITY_ACCEPT
                : FEASIBILITY_SAMPLE_BED;
    }

    static int boundedFeasibilityRange(
            NoiseBounds bounds,
            double head,
            double maximumIncision
    ) {
        double minimum = Math.min(bounds.min(), bounds.max());
        double maximum = Math.max(bounds.min(), bounds.max());
        long roundedHead = Math.round(head);
        if (Math.round(maximum) < roundedHead) {
            return FEASIBILITY_ACCEPT;
        }
        if (Math.round(minimum - Math.max(0D, maximumIncision)) >= roundedHead) {
            return FEASIBILITY_REJECT;
        }
        return FEASIBILITY_SAMPLE_BED;
    }

    private record ResolvedRiverColumn(
            RiverSample river,
            RiverReach reach,
            double naturalHeight,
            double waterSurfaceY,
            double bedHeight,
            double maximumIncision,
            boolean subterranean
    ) {
    }

    private record CenterlinePosition(double x, double z) {
    }

    private record PoolSelection(List<String> keys, InferredType type) {
    }

    private record TerminalPosition(double x, double z, double alongReach) {
    }

    private record TerminalCaveAnchor(long stableId, double x, double z, double alongReach) {
    }

    private record BiomePoolKey(List<String> keys, InferredType type) {
        private BiomePoolKey {
            keys = List.copyOf(keys);
            Objects.requireNonNull(type);
        }
    }

    private static final class IdentitySettingsKey {
        private final IrisRegion region;
        private final IrisBiome biome;
        private final int hash;

        private IdentitySettingsKey(IrisRegion region, IrisBiome biome) {
            this.region = region;
            this.biome = biome;
            hash = 31 * System.identityHashCode(region) + System.identityHashCode(biome);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentitySettingsKey key
                    && key.region == region
                    && key.biome == biome;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}

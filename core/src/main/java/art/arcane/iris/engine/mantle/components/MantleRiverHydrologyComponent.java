package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.mantle.ComponentFlag;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.mantle.IrisMantleComponent;
import art.arcane.iris.engine.mantle.MantleComponent;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.IrisRiverCaveFallback;
import art.arcane.iris.engine.object.IrisRiverCaveMode;
import art.arcane.iris.engine.object.IrisRiverCaves;
import art.arcane.iris.engine.object.IrisRiverDeepPools;
import art.arcane.iris.engine.object.IrisRiverExistingFluidPolicy;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.river.RiverAnchor;
import art.arcane.iris.engine.river.RiverNetwork;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.RiverTopologyComplexity;
import art.arcane.iris.engine.river.cave.CavePosition;
import art.arcane.iris.engine.river.cave.CaveVoxel;
import art.arcane.iris.engine.river.cave.CaveVoxelPrecondition;
import art.arcane.iris.engine.river.cave.CaveVoxelView;
import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveContainmentPlanner;
import art.arcane.iris.engine.river.cave.RiverCaveFluidKind;
import art.arcane.iris.engine.river.cave.RiverCaveFluidPolicy;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import art.arcane.iris.engine.river.cave.RiverCaveMode;
import art.arcane.iris.engine.river.cave.RiverCavePlan;
import art.arcane.iris.engine.river.cave.RiverCavePlannerSettings;
import art.arcane.iris.engine.river.cave.RiverCavePlanningResult;
import art.arcane.iris.engine.river.cave.RiverCaveSource;
import art.arcane.iris.engine.river.runtime.IrisRiverRuntime;
import art.arcane.iris.engine.river.runtime.IrisRiverSurfaceSample;
import art.arcane.iris.engine.river.runtime.IrisRiverTunnelSample;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.math.BlockPosition;
import art.arcane.volmlib.util.matter.Matter;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ComponentFlag(ReservedFlag.RIVER_HYDROLOGY)
public final class MantleRiverHydrologyComponent extends IrisMantleComponent {
    private static final long CANDIDATE_SALT = 0x6A09E667F3BCC909L;
    private static final long DEEP_POOL_CANDIDATE_SALT = 0xBB67AE8584CAA73BL;
    private static final long DEEP_POOL_POSITION_SALT = 0x3C6EF372FE94F82BL;
    static final int PRIORITY = 1;
    private static final int[] FALLBACK_X = {0, 1, -1, 0, 0};
    private static final int[] FALLBACK_Z = {0, 0, 0, 1, -1};
    private static final MantleFlag[] PREREQUISITES = {ReservedFlag.CARVED};
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };
    private static final Comparator<Map.Entry<CavePosition, RiverCaveAction>> ACTION_ORDER = Comparator
            .comparingInt((Map.Entry<CavePosition, RiverCaveAction> entry) -> entry.getKey().x())
            .thenComparingInt(entry -> entry.getKey().y())
            .thenComparingInt(entry -> entry.getKey().z());

    private final RiverCaveContainmentPlanner planner;

    public MantleRiverHydrologyComponent(EngineMantle engineMantle) {
        super(engineMantle, ReservedFlag.RIVER_HYDROLOGY, PRIORITY);
        planner = new RiverCaveContainmentPlanner();
    }

    @Override
    public MantleFlag[] getPrerequisiteFlags() {
        return PREREQUISITES;
    }

    @Override
    public boolean isInputGenerationLazy() {
        return true;
    }

    @Override
    public int getInputRadius() {
        if (!getDimension().isCarvingEnabled()
                || getDimension().getRivers() == null
                || !getDimension().getRivers().isEnabled()) {
            return 0;
        }
        IrisRiverRuntime runtime = getComplex().getRiverRuntime();
        if (runtime == null) {
            return 0;
        }
        return inputRadius(runtime.caveSettings(), tunnelHalo(runtime));
    }

    @Override
    public int getInputRadius(
            int targetChunkX,
            int targetChunkZ,
            int invocationChunkRadius,
            ChunkContext context
    ) {
        if (!getDimension().isCarvingEnabled()
                || getDimension().getRivers() == null
                || !getDimension().getRivers().isEnabled()) {
            return 0;
        }
        if (context == null || context.getComplex() == null) {
            return getInputRadius();
        }
        IrisRiverRuntime runtime = context.getComplex().getRiverRuntime();
        if (runtime == null) {
            return getInputRadius();
        }
        IrisRiverCaves caves = runtime.caveSettings();
        int tunnelRadius = tunnelHalo(runtime);
        int radius = hasRiverFootprint(
                runtime,
                targetChunkX,
                targetChunkZ,
                invocationChunkRadius,
                tunnelRadius
        ) ? tunnelRadius : 0;
        if (caves.getMode() != IrisRiverCaveMode.SEALED
                && caves.getMaximumPerReach() > 0) {
            radius = Math.max(radius, hasAcceptedCaveAnchor(
                    runtime,
                    caves,
                    targetChunkX,
                    targetChunkZ,
                    invocationChunkRadius
            ) ? planningHalo(caves) : 0);
        }
        IrisRiverDeepPools deepPools = caves.getDeepPools();
        if (deepPools != null
                && deepPools.isEnabled()
                && deepPools.getMaximumPerReach() > 0) {
            radius = Math.max(radius, hasAcceptedDeepPoolAnchor(
                    runtime,
                    deepPools,
                    targetChunkX,
                    targetChunkZ,
                    invocationChunkRadius
            ) ? deepPoolPlanningHalo(deepPools) : 0);
        }
        return radius;
    }

    private static boolean hasRiverFootprint(
            IrisRiverRuntime runtime,
            int targetChunkX,
            int targetChunkZ,
            int invocationChunkRadius,
            int tunnelRadius
    ) {
        return runtime.hasRiverFootprint(
                ((targetChunkX - invocationChunkRadius) << 4) - tunnelRadius,
                ((targetChunkZ - invocationChunkRadius) << 4) - tunnelRadius,
                ((targetChunkX + invocationChunkRadius + 1) << 4) + tunnelRadius,
                ((targetChunkZ + invocationChunkRadius + 1) << 4) + tunnelRadius
        );
    }

    private static boolean hasAcceptedCaveAnchor(
            IrisRiverRuntime runtime,
            IrisRiverCaves caves,
            int targetChunkX,
            int targetChunkZ,
            int invocationChunkRadius
    ) {
        int candidateHalo = candidateHalo(caves);
        List<RiverAnchor> anchors = runtime.candidateAnchors(
                ((targetChunkX - invocationChunkRadius) << 4) - candidateHalo,
                ((targetChunkZ - invocationChunkRadius) << 4) - candidateHalo,
                ((targetChunkX + invocationChunkRadius + 1) << 4) + candidateHalo,
                ((targetChunkZ + invocationChunkRadius + 1) << 4) + candidateHalo,
                caves.getMinimumSpacing(),
                CANDIDATE_SALT
        );
        for (RiverAnchor anchor : anchors) {
            if (runtime.acceptsCaveAnchor(anchor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAcceptedDeepPoolAnchor(
            IrisRiverRuntime runtime,
            IrisRiverDeepPools deepPools,
            int targetChunkX,
            int targetChunkZ,
            int invocationChunkRadius
    ) {
        int candidateHalo = deepPoolCandidateHalo(deepPools);
        List<RiverAnchor> anchors = runtime.candidateAnchors(
                ((targetChunkX - invocationChunkRadius) << 4) - candidateHalo,
                ((targetChunkZ - invocationChunkRadius) << 4) - candidateHalo,
                ((targetChunkX + invocationChunkRadius + 1) << 4) + candidateHalo,
                ((targetChunkZ + invocationChunkRadius + 1) << 4) + candidateHalo,
                deepPools.getMinimumSpacing(),
                DEEP_POOL_CANDIDATE_SALT
        );
        for (RiverAnchor anchor : anchors) {
            if (runtime.acceptsDeepPoolAnchor(anchor)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void generateLayer(MantleWriter writer, int chunkX, int chunkZ, ChunkContext context) {
        IrisRiverRuntime runtime = context.getComplex().getRiverRuntime();
        if (runtime == null || !getDimension().isCarvingEnabled()) {
            return;
        }
        publishTunnels(writer, context, runtime, chunkX, chunkZ);

        IrisRiverCaves caves = runtime.caveSettings();
        if (caves.getMode() != IrisRiverCaveMode.SEALED && caves.getMaximumPerReach() > 0) {
            publishCaveConnections(writer, context, runtime, caves, chunkX, chunkZ);
        }
        publishDeepPools(writer, context, runtime, caves.getDeepPools(), chunkX, chunkZ);
    }

    private void publishCaveConnections(
            MantleWriter writer,
            ChunkContext context,
            IrisRiverRuntime runtime,
            IrisRiverCaves caves,
            int chunkX,
            int chunkZ
    ) {
        MantleRiverCaveVoxelView view = createView(writer, context, RiverCaveFluidKind.RIVER);
        int candidateHalo = candidateHalo(caves);
        int minimumX = (chunkX << 4) - candidateHalo;
        int minimumZ = (chunkZ << 4) - candidateHalo;
        int maximumX = ((chunkX + 1) << 4) + candidateHalo;
        int maximumZ = ((chunkZ + 1) << 4) + candidateHalo;
        List<RiverAnchor> anchors = runtime.candidateAnchors(
                minimumX,
                minimumZ,
                maximumX,
                maximumZ,
                caves.getMinimumSpacing(),
                CANDIDATE_SALT
        );
        if (anchors.isEmpty()) {
            return;
        }

        RiverCavePlannerSettings settings = plannerSettings(caves, seed(), getData());
        List<RiverCaveSource> sources = new ArrayList<>();
        Map<Long, String> floodedBiomes = new HashMap<>();
        for (RiverAnchor anchor : anchors) {
            if (!runtime.acceptsCaveAnchor(anchor)) {
                continue;
            }
            SourceCandidate candidate = sourceFor(runtime, view, caves, anchor);
            if (candidate == null) {
                continue;
            }
            RiverCaveSource source = candidate.source();
            RiverCavePlan initial = planner.plan(view, source, settings);
            if (!initial.accepted()
                    && caves.getFallback() == IrisRiverCaveFallback.GENERATE_GROTTO) {
                source = fallbackSource(view, caves, settings, candidate, source);
            }
            if (source == null) {
                continue;
            }
            sources.add(source);
            floodedBiomes.put(source.sourceId(), runtime.selectFloodedCaveBiome(anchor));
        }
        if (sources.isEmpty()) {
            return;
        }

        RiverCavePlanningResult result = planner.planAll(view, sources, settings);
        MantleRiverCaveVoxelView revalidationView = createView(
                writer,
                context,
                RiverCaveFluidKind.RIVER
        );
        if (!preconditionsHold(revalidationView, result.baselinePreconditions())) {
            return;
        }
        publishLocal(
                writer,
                chunkX,
                chunkZ,
                result,
                floodedBiomes,
                RiverCaveFluidKind.RIVER
        );
    }

    private void publishDeepPools(
            MantleWriter writer,
            ChunkContext context,
            IrisRiverRuntime runtime,
            IrisRiverDeepPools deepPools,
            int chunkX,
            int chunkZ
    ) {
        if (deepPools == null || !deepPools.isEnabled() || deepPools.getMaximumPerReach() <= 0) {
            return;
        }
        MantleRiverCaveVoxelView view = createView(
                writer,
                context,
                RiverCaveFluidKind.DEEP_POOL
        );
        int candidateHalo = deepPoolCandidateHalo(deepPools);
        int minimumX = (chunkX << 4) - candidateHalo;
        int minimumZ = (chunkZ << 4) - candidateHalo;
        int maximumX = ((chunkX + 1) << 4) + candidateHalo;
        int maximumZ = ((chunkZ + 1) << 4) + candidateHalo;
        List<RiverAnchor> anchors = runtime.candidateAnchors(
                minimumX,
                minimumZ,
                maximumX,
                maximumZ,
                deepPools.getMinimumSpacing(),
                DEEP_POOL_CANDIDATE_SALT
        );
        if (anchors.isEmpty()) {
            return;
        }

        RiverCavePlannerSettings settings = deepPoolPlannerSettings(deepPools, seed(), getData());
        List<RiverCaveSource> sources = new ArrayList<>();
        Map<Long, String> floodedBiomes = new HashMap<>();
        for (RiverAnchor anchor : anchors) {
            if (!runtime.acceptsDeepPoolAnchor(anchor)) {
                continue;
            }
            RiverCaveSource source = deepPoolSourceFor(
                    view,
                    deepPools,
                    anchor,
                    getDimension().getMinHeight(),
                    seed()
            );
            if (source == null) {
                continue;
            }
            sources.add(source);
            floodedBiomes.put(source.sourceId(), runtime.selectFloodedCaveBiome(anchor));
        }
        if (sources.isEmpty()) {
            return;
        }

        RiverCavePlanningResult result = planner.planAll(view, sources, settings);
        MantleRiverCaveVoxelView revalidationView = createView(
                writer,
                context,
                RiverCaveFluidKind.DEEP_POOL
        );
        if (!preconditionsHold(revalidationView, result.baselinePreconditions())) {
            return;
        }
        publishLocal(
                writer,
                chunkX,
                chunkZ,
                result,
                floodedBiomes,
                RiverCaveFluidKind.DEEP_POOL
        );
    }

    static RiverCavePlannerSettings deepPoolPlannerSettings(
            IrisRiverDeepPools deepPools,
            long seed,
            IrisData data
    ) {
        int verticalDepth = deepPools.getVerticalRadius() * 2 - deepPools.getDryHeadroom() + 1;
        int proofRadius = (int) StrictMath.ceil(
                StrictMath.sqrt(2D) * deepPools.getHorizontalRadius()
        ) + 1;
        return new RiverCavePlannerSettings(
                proofRadius,
                verticalDepth,
                deepPools.getMaximumVolume(),
                deepPools.getVerticalRadius(),
                1,
                deepPools.getHorizontalRadius(),
                deepPools.getVerticalRadius(),
                deepPools.getDryHeadroom(),
                RiverCaveFluidPolicy.REJECT_EXISTING,
                new ConfiguredRiverGrottoShape(
                        seed ^ DEEP_POOL_POSITION_SALT,
                        data,
                        deepPools.getShapeStyle(),
                        deepPools.getWarpStyle(),
                        deepPools.getWarpStrength(),
                        deepPools.getShapeVariation()
                ),
                proofRadius,
                verticalDepth
        );
    }

    static RiverCaveSource deepPoolSourceFor(
            CaveVoxelView view,
            IrisRiverDeepPools deepPools,
            RiverAnchor anchor,
            int worldMinimumY,
            long seed
    ) {
        int minimumHead = deepPools.getMinimumFluidY() - worldMinimumY;
        int maximumHead = deepPools.getMaximumFluidY() - worldMinimumY;
        int headRange = maximumHead - minimumHead + 1;
        if (headRange <= 0) {
            return null;
        }
        int searchRadius = deepPools.getSearchRadius();
        int searchWidth = searchRadius * 2 + 1;
        int targetDepth = Math.max(
                1,
                deepPools.getVerticalRadius() - deepPools.getDryHeadroom()
        );
        for (int attempt = 0; attempt < deepPools.getSearchAttempts(); attempt++) {
            long hash = RiverNetwork.mix(
                    seed
                            ^ anchor.stableId()
                            ^ DEEP_POOL_POSITION_SALT
                            ^ (long) attempt * 0x9E3779B97F4A7C15L
            );
            int offsetX = searchRadius == 0
                    ? 0
                    : Math.floorMod((int) hash, searchWidth) - searchRadius;
            int offsetZ = searchRadius == 0
                    ? 0
                    : Math.floorMod((int) (hash >>> 32), searchWidth) - searchRadius;
            if ((long) offsetX * offsetX + (long) offsetZ * offsetZ
                    > (long) searchRadius * searchRadius) {
                continue;
            }
            int x = (int) StrictMath.floor(anchor.x()) + offsetX;
            int z = (int) StrictMath.floor(anchor.z()) + offsetZ;
            int startOffset = (int) StrictMath.floor(unit(hash) * headRange);
            for (int scanned = 0; scanned < headRange; scanned++) {
                int headY = maximumHead - Math.floorMod(startOffset + scanned, headRange);
                CavePosition floor = new CavePosition(x, headY, z);
                CavePosition above = new CavePosition(x, headY + 1, z);
                CavePosition target = new CavePosition(x, headY - targetDepth, z);
                if (!view.isInWorld(target)
                        || !view.isInWorld(above)
                        || view.voxelAt(floor) != CaveVoxel.SOLID
                        || view.voxelAt(above) != CaveVoxel.CAVE_AIR
                        || view.isOpenToSurface(above)) {
                    continue;
                }
                long sourceId = RiverNetwork.mix(
                        anchor.stableId()
                                ^ BlockPosition.toLong(x, headY, z)
                                ^ DEEP_POOL_POSITION_SALT
                );
                return new RiverCaveSource(
                        sourceId,
                        floor,
                        target,
                        headY,
                        RiverCaveMode.DEEP_POOL
                );
            }
        }
        return null;
    }

    private static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    @Override
    protected int computeRadius() {
        return 0;
    }

    public static boolean isEnabledFor(IrisDimension dimension) {
        IrisRiverNetwork rivers = dimension.getRivers();
        if (!dimension.isUseMantle()
                || !dimension.isCarvingEnabled()
                || dimension.getDisabledComponents().contains(ReservedFlag.CARVED)
                || dimension.getDisabledComponents().contains(ReservedFlag.RIVER_HYDROLOGY)
                || rivers == null
                || !rivers.isEnabled()) {
            return false;
        }
        return true;
    }

    public static boolean isCaveConnectionsEnabledFor(IrisDimension dimension) {
        if (!isEnabledFor(dimension)) {
            return false;
        }
        IrisRiverCaves caves = dimension.getRivers().getCaves();
        if (caves == null) {
            return false;
        }
        boolean caveConnections = caves.getMode() != IrisRiverCaveMode.SEALED
                && caves.getMaximumPerReach() > 0;
        IrisRiverDeepPools deepPools = caves.getDeepPools();
        return caveConnections || deepPools != null
                && deepPools.isEnabled()
                && deepPools.getMaximumPerReach() > 0;
    }

    static int planningHalo(IrisRiverCaves caves) {
        return cavePublicationRadius(caves) * 4;
    }

    static int inputRadius(IrisRiverCaves caves, int tunnelRadius) {
        int radius = tunnelRadius;
        if (caves.getMode() != IrisRiverCaveMode.SEALED && caves.getMaximumPerReach() > 0) {
            radius = Math.max(radius, planningHalo(caves));
        }
        IrisRiverDeepPools deepPools = caves.getDeepPools();
        if (deepPools != null && deepPools.isEnabled() && deepPools.getMaximumPerReach() > 0) {
            radius = Math.max(radius, deepPoolPlanningHalo(deepPools));
        }
        return radius;
    }

    static int candidateHalo(IrisRiverCaves caves) {
        return cavePublicationRadius(caves) * 3;
    }

    static int deepPoolPlanningHalo(IrisRiverDeepPools deepPools) {
        return deepPoolPublicationRadius(deepPools) * 4;
    }

    static int deepPoolCandidateHalo(IrisRiverDeepPools deepPools) {
        return deepPoolPublicationRadius(deepPools) * 3;
    }

    static int deepPoolPublicationRadius(IrisRiverDeepPools deepPools) {
        return deepPools.getSearchRadius() + deepPools.getHorizontalRadius() + 1;
    }

    static int cavePublicationRadius(IrisRiverCaves caves) {
        int generatedRadius = generatedGrottoPublicationRadius(caves);
        return switch (caves.getMode()) {
            case SEALED -> 0;
            case GENERATE_GROTTO -> generatedRadius;
            case FLOOD_CLOSED_COMPONENT, GROTTO_OR_CLOSED_COMPONENT, WATERFALL_POOL ->
                    Math.max(closedComponentPublicationRadius(caves), generatedRadius);
        };
    }

    static int closedComponentPublicationRadius(IrisRiverCaves caves) {
        return caves.getMaxFloodRadius() + 1;
    }

    static int generatedGrottoPublicationRadius(IrisRiverCaves caves) {
        int targetOffset = caves.getFallback() == IrisRiverCaveFallback.GENERATE_GROTTO
                ? caves.getThroatRadius() + 2
                : 0;
        long maximumX = (long) targetOffset + caves.getGrottoHorizontalRadius() + 1L;
        long maximumZ = caves.getGrottoHorizontalRadius();
        int grottoRadius = (int) StrictMath.ceil(StrictMath.sqrt(
                maximumX * maximumX + maximumZ * maximumZ
        ));
        int throatRadius = targetOffset + caves.getThroatRadius();
        return Math.max(grottoRadius, throatRadius);
    }

    static int tunnelHalo(IrisRiverRuntime runtime) {
        return RiverTopologyComplexity.tunnelHalo(
                runtime.maximumChannelWidth(),
                runtime.maximumTunnelWidthMultiplier(),
                runtime.tunnelMouthBlend()
        );
    }

    static int waterHeadY(IrisRiverSurfaceSample sample, IrisRiverCaves caves) {
        return (int) Math.round(sample.waterSurfaceY()) + caves.getWaterLevelOffset();
    }

    static boolean owns(int chunkX, int chunkZ, CavePosition position) {
        return (position.x() >> 4) == chunkX && (position.z() >> 4) == chunkZ;
    }

    static RiverCaveFluidPolicy fluidPolicy(IrisRiverExistingFluidPolicy policy) {
        return switch (policy) {
            case REJECT -> RiverCaveFluidPolicy.REJECT_EXISTING;
            case ALLOW_SAME -> RiverCaveFluidPolicy.ALLOW_COMPATIBLE;
            case REPLACE -> RiverCaveFluidPolicy.REPLACE_CONTAINED;
        };
    }

    static boolean preconditionsHold(
            CaveVoxelView view,
            Map<CavePosition, CaveVoxelPrecondition> preconditions
    ) {
        for (Map.Entry<CavePosition, CaveVoxelPrecondition> entry : preconditions.entrySet()) {
            CaveVoxelPrecondition expected = entry.getValue();
            if (view.voxelAt(entry.getKey()) != expected.voxel()
                    || view.isOpenToSurface(entry.getKey()) != expected.openToSurface()) {
                return false;
            }
        }
        return true;
    }

    static TunnelPlan planTunnels(
            CaveVoxelView view,
            int chunkX,
            int chunkZ,
            int halo,
            int dryHeadroom,
            FootprintSampler footprintSampler,
            TunnelSampler tunnelSampler,
            SurfaceSampler surfaceSampler
    ) {
        int minimumX = (chunkX << 4) - halo;
        int minimumZ = (chunkZ << 4) - halo;
        int maximumX = ((chunkX + 1) << 4) + halo;
        int maximumZ = ((chunkZ + 1) << 4) + halo;
        if (!footprintSampler.sample(minimumX, minimumZ, maximumX, maximumZ).present()) {
            return TunnelPlan.empty();
        }
        ArrayList<TunnelColumn> solidColumns = new ArrayList<>();
        for (int x = minimumX; x < maximumX; x++) {
            for (int z = minimumZ; z < maximumZ; z++) {
                IrisRiverTunnelSample sample = tunnelSampler.sample(x, z);
                TunnelColumn column = createTunnelColumn(view, x, z, sample);
                if (column != null) {
                    solidColumns.add(column);
                }
            }
        }
        if (solidColumns.isEmpty()) {
            return TunnelPlan.empty();
        }

        ArrayList<TunnelColumn> containedColumns = new ArrayList<>(solidColumns);
        boolean changed;
        do {
            changed = false;
            Long2ObjectOpenHashMap<TunnelColumn> candidateColumns = indexColumns(containedColumns);
            for (int index = containedColumns.size() - 1; index >= 0; index--) {
                TunnelColumn column = containedColumns.get(index);
                if (!isTunnelColumnContained(
                        view,
                        column,
                        candidateColumns,
                        dryHeadroom,
                        surfaceSampler
                )) {
                    containedColumns.remove(index);
                    changed = true;
                }
            }
        } while (changed && !containedColumns.isEmpty());
        Map<CavePosition, RiverCaveAction> actions = mergeActions(containedColumns);
        LinkedHashMap<CavePosition, CaveVoxelPrecondition> preconditions = new LinkedHashMap<>();
        for (CavePosition position : actions.keySet()) {
            preconditions.put(position, new CaveVoxelPrecondition(
                    view.voxelAt(position),
                    view.isOpenToSurface(position)
            ));
        }
        for (CavePosition position : List.copyOf(actions.keySet())) {
            RiverCaveAction action = actions.get(position);
            for (int[] offset : NEIGHBORS) {
                CavePosition neighbor = offset(position, offset);
                if (actions.containsKey(neighbor) || !view.isInWorld(neighbor)) {
                    continue;
                }
                CaveVoxel neighborVoxel = view.voxelAt(neighbor);
                boolean sealsSolidBoundary = neighborVoxel == CaveVoxel.SOLID;
                boolean sealsCaveWaterline = action == RiverCaveAction.WET_SOURCE
                        && neighborVoxel == CaveVoxel.CAVE_AIR;
                if (!sealsSolidBoundary && !sealsCaveWaterline) {
                    if (action == RiverCaveAction.DRY_AIR
                            && neighborVoxel == CaveVoxel.CAVE_AIR
                            && !view.isOpenToSurface(neighbor)) {
                        preconditions.putIfAbsent(
                                neighbor,
                                new CaveVoxelPrecondition(CaveVoxel.CAVE_AIR, false)
                        );
                    }
                    continue;
                }
                actions.putIfAbsent(neighbor, RiverCaveAction.SEAL_GUARD);
                preconditions.putIfAbsent(neighbor, new CaveVoxelPrecondition(
                        neighborVoxel,
                        view.isOpenToSurface(neighbor)
                ));
            }
        }
        return new TunnelPlan(
                Collections.unmodifiableMap(actions),
                Collections.unmodifiableMap(preconditions)
        );
    }

    private static TunnelColumn createTunnelColumn(
            CaveVoxelView view,
            int x,
            int z,
            IrisRiverTunnelSample sample
    ) {
        if (sample == null) {
            return null;
        }
        int minimumY = sample.bedY() + 1;
        int maximumY = sample.ceilingY();
        for (int y = minimumY; y <= maximumY; y++) {
            CavePosition position = new CavePosition(x, y, z);
            RiverCaveAction action = y <= sample.waterHeadY()
                    ? RiverCaveAction.WET_SOURCE
                    : RiverCaveAction.DRY_AIR;
            if (!view.isInWorld(position)
                    || (!canCarveTunnelVoxel(view, position)
                    && !matchesPublishedAction(view, position, action))) {
                return null;
            }
        }
        return minimumY > maximumY
                ? null
                : new TunnelColumn(x, z, minimumY, sample.waterHeadY(), maximumY);
    }

    private static boolean isTunnelColumnContained(
            CaveVoxelView view,
            TunnelColumn column,
            Long2ObjectOpenHashMap<TunnelColumn> candidateColumns,
            int dryHeadroom,
            SurfaceSampler surfaceSampler
    ) {
        for (int y = column.minimumY(); y <= column.maximumY(); y++) {
            CavePosition position = new CavePosition(column.x(), y, column.z());
            for (int[] offset : NEIGHBORS) {
                CavePosition neighbor = offset(position, offset);
                if (containsAction(candidateColumns, neighbor)) {
                    continue;
                }
                if (!view.isInWorld(neighbor)) {
                    return false;
                }
                CaveVoxel neighborVoxel = view.voxelAt(neighbor);
                if (neighborVoxel == CaveVoxel.SOLID
                        || (neighborVoxel == CaveVoxel.CAVE_AIR
                        && !view.isOpenToSurface(neighbor))
                        || isSurfaceMouth(neighbor, dryHeadroom, surfaceSampler)) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private static boolean canCarveTunnelVoxel(CaveVoxelView view, CavePosition position) {
        CaveVoxel voxel = view.voxelAt(position);
        return voxel == CaveVoxel.SOLID
                || (voxel == CaveVoxel.CAVE_AIR && !view.isOpenToSurface(position));
    }

    private static boolean isSurfaceMouth(
            CavePosition position,
            int dryHeadroom,
            SurfaceSampler surfaceSampler
    ) {
        IrisRiverSurfaceSample sample = surfaceSampler.sample(position.x(), position.z());
        if (!sample.river().present()
                || sample.river().state() != RiverRouteState.WET
                || sample.subterranean()) {
            return false;
        }
        int bedY = (int) Math.round(sample.terrainHeight());
        int headY = (int) Math.round(sample.waterSurfaceY());
        return position.y() > bedY && position.y() <= headY + Math.max(0, dryHeadroom);
    }

    private static Map<CavePosition, RiverCaveAction> mergeActions(List<TunnelColumn> columns) {
        LinkedHashMap<CavePosition, RiverCaveAction> actions = new LinkedHashMap<>();
        for (TunnelColumn column : columns) {
            for (int y = column.minimumY(); y <= column.maximumY(); y++) {
                actions.put(
                        new CavePosition(column.x(), y, column.z()),
                        y <= column.waterHeadY()
                                ? RiverCaveAction.WET_SOURCE
                                : RiverCaveAction.DRY_AIR
                );
            }
        }
        return actions;
    }

    private static Long2ObjectOpenHashMap<TunnelColumn> indexColumns(List<TunnelColumn> columns) {
        Long2ObjectOpenHashMap<TunnelColumn> indexed = new Long2ObjectOpenHashMap<>(columns.size());
        for (TunnelColumn column : columns) {
            indexed.put(Cache.key(column.x(), column.z()), column);
        }
        return indexed;
    }

    private static boolean containsAction(
            Long2ObjectOpenHashMap<TunnelColumn> columns,
            CavePosition position
    ) {
        TunnelColumn column = columns.get(Cache.key(position.x(), position.z()));
        return column != null
                && position.y() >= column.minimumY()
                && position.y() <= column.maximumY();
    }

    private static boolean matchesPublishedAction(
            CaveVoxelView view,
            CavePosition position,
            RiverCaveAction action
    ) {
        if (!(view instanceof TunnelVoxelView tunnelView)) {
            return false;
        }
        RiverCaveHydrology hydrology = tunnelView.riverHydrologyAt(position);
        return hydrology != null
                && hydrology.action() == action
                && hydrology.fluidKind() == RiverCaveFluidKind.RIVER;
    }

    private static CavePosition offset(CavePosition position, int[] offset) {
        return new CavePosition(
                position.x() + offset[0],
                position.y() + offset[1],
                position.z() + offset[2]
        );
    }

    private MantleRiverCaveVoxelView createView(
            MantleWriter writer,
            ChunkContext context,
            RiverCaveFluidKind fluidKind
    ) {
        return new MantleRiverCaveVoxelView(
                writer.getMantle(),
                writer.getMantle().getWorldHeight(),
                (x, z) -> context.getComplex().getRoundedHeighteightStream().get(x, z),
                (x, z) -> context.getComplex().resolveRiverCaveFluid(fluidKind, x, z),
                fluidKind,
                (chunkX, chunkZ) -> generateCarvingInput(writer, context, chunkX, chunkZ)
        );
    }

    private void generateCarvingInput(
            MantleWriter writer,
            ChunkContext context,
            int chunkX,
            int chunkZ
    ) {
        MantleComponent carving = getEngineMantle().getRegisteredComponents().get(ReservedFlag.CARVED);
        if (carving == null || !carving.isEnabled()) {
            throw new IllegalStateException("River hydrology requires the carving component");
        }
        MantleChunk<Matter> chunk = writer.acquireChunk(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("River hydrology read exceeded the prepared mantle radius at "
                    + chunkX + "," + chunkZ);
        }
        chunk.raiseFlagSuspend(ReservedFlag.CARVED, () -> carving.generateLayer(writer, chunkX, chunkZ, context));
    }

    private void publishTunnels(
            MantleWriter writer,
            ChunkContext context,
            IrisRiverRuntime runtime,
            int chunkX,
            int chunkZ
    ) {
        for (int attempt = 0; attempt < 2; attempt++) {
            MantleRiverCaveVoxelView view = createView(
                    writer,
                    context,
                    RiverCaveFluidKind.RIVER
            );
            TunnelPlan plan = planTunnels(
                    view,
                    chunkX,
                    chunkZ,
                    tunnelHalo(runtime),
                    runtime.maximumTunnelHeadroom(),
                    runtime::sampleFootprint,
                    runtime::sampleTunnel,
                    runtime::sample
            );
            MantleRiverCaveVoxelView revalidationView = createView(
                    writer,
                    context,
                    RiverCaveFluidKind.RIVER
            );
            if (preconditionsHold(revalidationView, plan.preconditions())) {
                publishTunnelLocal(writer, chunkX, chunkZ, plan);
                return;
            }
        }
    }

    static RiverCavePlannerSettings plannerSettings(IrisRiverCaves caves, long seed, IrisData data) {
        int horizontalRadius = generatedGrottoPublicationRadius(caves);
        int maximumDepth = caves.getMaxBoreDepth() + caves.getGrottoVerticalRadius() + 1;
        int throatLength = caves.getMaxBoreDepth() + horizontalRadius;
        return new RiverCavePlannerSettings(
                horizontalRadius,
                maximumDepth,
                caves.getMaxFloodVolume(),
                throatLength,
                caves.getThroatRadius(),
                caves.getGrottoHorizontalRadius(),
                caves.getGrottoVerticalRadius(),
                caves.getDryHeadroom(),
                fluidPolicy(caves.getExistingFluidPolicy()),
                new ConfiguredRiverGrottoShape(
                        seed,
                        data,
                        caves.getGrottoShapeStyle(),
                        caves.getGrottoWarpStyle(),
                        caves.getGrottoWarpStrength(),
                        0.2D
                ),
                caves.getMaxFloodRadius(),
                caves.getMaxFloodDepth()
        );
    }

    private SourceCandidate sourceFor(
            IrisRiverRuntime runtime,
            CaveVoxelView view,
            IrisRiverCaves caves,
            RiverAnchor anchor
    ) {
        int x = (int) StrictMath.floor(anchor.x());
        int z = (int) StrictMath.floor(anchor.z());
        IrisRiverSurfaceSample sample = runtime.sample(x, z);
        IrisRiverTunnelSample tunnel = runtime.sampleTunnel(x, z);
        if (!isWetChannelBed(sample) && tunnel == null) {
            return null;
        }
        int bedY = tunnel == null
                ? (int) Math.round(sample.terrainHeight())
                : tunnel.bedY();
        int headY = tunnel == null
                ? waterHeadY(sample, caves)
                : tunnel.waterHeadY() + caves.getWaterLevelOffset();
        int entryY = Math.max(bedY, headY);
        CavePosition entry = new CavePosition(x, entryY, z);
        if (!view.isInWorld(entry)) {
            return null;
        }

        CavePosition existingTarget = findExistingTarget(view, caves, x, z, bedY, headY);
        RiverCaveMode requestedMode = sourceMode(
                caves.getMode(),
                runtime.isTerminalCaveAnchor(anchor)
        );
        CavePosition target;
        RiverCaveMode sourceMode;
        if (requestedMode == RiverCaveMode.GENERATED_GROTTO) {
            target = findGeneratedTarget(view, caves, entry, headY, 0, 0);
            sourceMode = RiverCaveMode.GENERATED_GROTTO;
        } else if (requestedMode == RiverCaveMode.GROTTO_OR_CLOSED_COMPONENT) {
            target = existingTarget;
            sourceMode = RiverCaveMode.CLOSED_COMPONENT;
            if (target == null) {
                target = findGeneratedTarget(view, caves, entry, headY, 0, 0);
                sourceMode = RiverCaveMode.GENERATED_GROTTO;
            }
        } else {
            target = existingTarget;
            sourceMode = requestedMode;
        }
        if (target == null && caves.getFallback() == IrisRiverCaveFallback.GENERATE_GROTTO) {
            target = findGeneratedTarget(view, caves, entry, headY, 0, 0);
            sourceMode = RiverCaveMode.GENERATED_GROTTO;
        }
        if (target == null) {
            return null;
        }
        RiverCaveSource source = new RiverCaveSource(anchor.stableId(), entry, target, headY, sourceMode);
        return new SourceCandidate(entry, headY, source);
    }

    static boolean isWetChannelBed(IrisRiverSurfaceSample sample) {
        return sample.river().present()
                && sample.river().state() == RiverRouteState.WET
                && sample.river().section() == RiverSection.CHANNEL
                && sample.surfaceFluid();
    }

    static CavePosition findExistingTarget(
            CaveVoxelView view,
            IrisRiverCaves caves,
            int x,
            int z,
            int bedY,
            int headY
    ) {
        int maximumY = Math.min(bedY - 1, headY);
        int minimumY = Math.max(1, bedY - caves.getMaxBoreDepth());
        for (int y = maximumY; y >= minimumY; y--) {
            CavePosition position = new CavePosition(x, y, z);
            CaveVoxel voxel = view.voxelAt(position);
            if (voxel != CaveVoxel.SOLID) {
                return position;
            }
        }
        return null;
    }

    static CavePosition findGeneratedTarget(
            CaveVoxelView view,
            IrisRiverCaves caves,
            CavePosition entry,
            int headY,
            int offsetX,
            int offsetZ
    ) {
        int preferredY = Math.min(
                headY + caves.getDryHeadroom() - caves.getGrottoVerticalRadius(),
                entry.y() - caves.getGrottoVerticalRadius() - 1
        );
        int maximumY = Math.min(Math.min(entry.y() - 1, headY), preferredY);
        int minimumY = Math.max(1, entry.y() - caves.getMaxBoreDepth());
        for (int y = maximumY; y >= minimumY; y--) {
            CavePosition target = new CavePosition(entry.x() + offsetX, y, entry.z() + offsetZ);
            if (view.isInWorld(target) && view.voxelAt(target) == CaveVoxel.SOLID) {
                return target;
            }
        }
        return null;
    }

    private RiverCaveSource fallbackSource(
            CaveVoxelView view,
            IrisRiverCaves caves,
            RiverCavePlannerSettings settings,
            SourceCandidate candidate,
            RiverCaveSource rejected
    ) {
        int fallbackDistance = caves.getThroatRadius() + 2;
        for (int index = 0; index < FALLBACK_X.length; index++) {
            int offsetX = FALLBACK_X[index] * fallbackDistance;
            int offsetZ = FALLBACK_Z[index] * fallbackDistance;
            CavePosition target = findGeneratedTarget(
                    view,
                    caves,
                    candidate.entry(),
                    candidate.waterHeadY(),
                    offsetX,
                    offsetZ
            );
            if (target == null || target.equals(rejected.target())) {
                continue;
            }
            RiverCaveSource fallback = new RiverCaveSource(
                    rejected.sourceId(),
                    candidate.entry(),
                    target,
                    candidate.waterHeadY(),
                    RiverCaveMode.GENERATED_GROTTO
            );
            if (planner.plan(view, fallback, settings).accepted()) {
                return fallback;
            }
        }
        return null;
    }

    static RiverCaveMode sourceMode(IrisRiverCaveMode mode, boolean forcedTerminal) {
        if (forcedTerminal) {
            return RiverCaveMode.GENERATED_GROTTO;
        }
        return switch (mode) {
            case FLOOD_CLOSED_COMPONENT -> RiverCaveMode.CLOSED_COMPONENT;
            case GENERATE_GROTTO -> RiverCaveMode.GENERATED_GROTTO;
            case GROTTO_OR_CLOSED_COMPONENT -> RiverCaveMode.GROTTO_OR_CLOSED_COMPONENT;
            case WATERFALL_POOL -> RiverCaveMode.WATERFALL_POOL;
            case SEALED -> throw new IllegalArgumentException("Sealed river caves do not create sources");
        };
    }

    private void publishLocal(
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            RiverCavePlanningResult result,
            Map<Long, String> floodedBiomes,
            RiverCaveFluidKind fluidKind
    ) {
        Map<CavePosition, RiverCaveSource> owners = actionOwners(result);
        ArrayList<Map.Entry<CavePosition, RiverCaveAction>> actions = new ArrayList<>(result.actions().entrySet());
        actions.sort(ACTION_ORDER);
        for (Map.Entry<CavePosition, RiverCaveAction> entry : actions) {
            CavePosition position = entry.getKey();
            if (!owns(chunkX, chunkZ, position)) {
                continue;
            }
            RiverCaveSource source = owners.get(position);
            String biome = source == null ? "" : floodedBiomes.getOrDefault(source.sourceId(), "");
            if (entry.getValue() == RiverCaveAction.SEAL_GUARD) {
                biome = "";
            }
            writer.setData(
                    position.x(),
                    position.y(),
                    position.z(),
                    new RiverCaveHydrology(entry.getValue(), biome, fluidKind)
            );
        }
    }

    private void publishTunnelLocal(
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            TunnelPlan plan
    ) {
        ArrayList<Map.Entry<CavePosition, RiverCaveAction>> actions = new ArrayList<>(plan.actions().entrySet());
        actions.sort(ACTION_ORDER);
        for (Map.Entry<CavePosition, RiverCaveAction> entry : actions) {
            CavePosition position = entry.getKey();
            if (owns(chunkX, chunkZ, position)) {
                writer.setData(
                        position.x(),
                        position.y(),
                        position.z(),
                        RiverCaveHydrology.of(entry.getValue(), RiverCaveFluidKind.RIVER)
                );
            }
        }
    }

    private Map<CavePosition, RiverCaveSource> actionOwners(RiverCavePlanningResult result) {
        Map<CavePosition, RiverCaveSource> owners = new LinkedHashMap<>();
        for (RiverCavePlan plan : result.plans()) {
            if (!plan.accepted()) {
                continue;
            }
            for (CavePosition position : plan.actions().keySet()) {
                owners.put(position, plan.source());
            }
        }
        return owners;
    }

    private record SourceCandidate(
            CavePosition entry,
            int waterHeadY,
            RiverCaveSource source
    ) {
    }

    @FunctionalInterface
    interface FootprintSampler {
        RiverSample sample(double minimumX, double minimumZ, double maximumX, double maximumZ);
    }

    @FunctionalInterface
    interface TunnelSampler {
        IrisRiverTunnelSample sample(int x, int z);
    }

    @FunctionalInterface
    interface SurfaceSampler {
        IrisRiverSurfaceSample sample(int x, int z);
    }

    interface TunnelVoxelView extends CaveVoxelView {
        RiverCaveHydrology riverHydrologyAt(CavePosition position);
    }

    record TunnelPlan(
            Map<CavePosition, RiverCaveAction> actions,
            Map<CavePosition, CaveVoxelPrecondition> preconditions
    ) {
        static TunnelPlan empty() {
            return new TunnelPlan(Map.of(), Map.of());
        }
    }

    private record TunnelColumn(
            int x,
            int z,
            int minimumY,
            int waterHeadY,
            int maximumY
    ) {
    }
}

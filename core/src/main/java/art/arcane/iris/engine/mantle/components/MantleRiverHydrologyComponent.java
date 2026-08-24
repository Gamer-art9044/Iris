package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.mantle.ComponentFlag;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.mantle.IrisMantleComponent;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.IrisRiverCaveFallback;
import art.arcane.iris.engine.object.IrisRiverCaveMode;
import art.arcane.iris.engine.object.IrisRiverCaves;
import art.arcane.iris.engine.object.IrisRiverExistingFluidPolicy;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.river.RiverAnchor;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ComponentFlag(ReservedFlag.RIVER_HYDROLOGY)
public final class MantleRiverHydrologyComponent extends IrisMantleComponent {
    private static final long CANDIDATE_SALT = 0x6A09E667F3BCC909L;
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
    public void generateLayer(MantleWriter writer, int chunkX, int chunkZ, ChunkContext context) {
        IrisRiverRuntime runtime = context.getComplex().getRiverRuntime();
        if (runtime == null || !getDimension().isCarvingEnabled()) {
            return;
        }
        publishTunnels(writer, context, runtime, chunkX, chunkZ);

        IrisRiverCaves caves = runtime.caveSettings();
        if (caves.getMode() == IrisRiverCaveMode.SEALED || caves.getMaximumPerReach() <= 0) {
            return;
        }

        MantleRiverCaveVoxelView view = createView(writer, context);
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
        MantleRiverCaveVoxelView revalidationView = createView(writer, context);
        if (!preconditionsHold(revalidationView, result.baselinePreconditions())) {
            return;
        }
        publishLocal(writer, chunkX, chunkZ, result, floodedBiomes);
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
        return caves != null
                && caves.getMode() != IrisRiverCaveMode.SEALED
                && caves.getMaximumPerReach() > 0;
    }

    static int planningHalo(IrisRiverCaves caves) {
        return cavePublicationRadius(caves) * 4;
    }

    static int inputRadius(IrisRiverCaves caves, int tunnelRadius) {
        if (caves.getMode() == IrisRiverCaveMode.SEALED || caves.getMaximumPerReach() <= 0) {
            return tunnelRadius;
        }
        return Math.max(tunnelRadius, planningHalo(caves));
    }

    static int candidateHalo(IrisRiverCaves caves) {
        return cavePublicationRadius(caves) * 3;
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
            Set<CavePosition> candidateActions = mergeActions(containedColumns).keySet();
            for (int index = containedColumns.size() - 1; index >= 0; index--) {
                TunnelColumn column = containedColumns.get(index);
                if (!isTunnelColumnContained(
                        view,
                        column,
                        candidateActions,
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
        return new TunnelPlan(Map.copyOf(actions), Map.copyOf(preconditions));
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
        LinkedHashMap<CavePosition, RiverCaveAction> actions = new LinkedHashMap<>();
        for (int y = sample.bedY() + 1; y <= sample.ceilingY(); y++) {
            CavePosition position = new CavePosition(x, y, z);
            RiverCaveAction action = y <= sample.waterHeadY()
                    ? RiverCaveAction.WET_SOURCE
                    : RiverCaveAction.DRY_AIR;
            if (!view.isInWorld(position)
                    || (!canCarveTunnelVoxel(view, position)
                    && !matchesPublishedAction(view, position, action))) {
                return null;
            }
            actions.put(position, action);
        }
        return actions.isEmpty() ? null : new TunnelColumn(actions);
    }

    private static boolean isTunnelColumnContained(
            CaveVoxelView view,
            TunnelColumn column,
            Set<CavePosition> candidateActions,
            int dryHeadroom,
            SurfaceSampler surfaceSampler
    ) {
        for (CavePosition position : column.actions().keySet()) {
            for (int[] offset : NEIGHBORS) {
                CavePosition neighbor = offset(position, offset);
                if (candidateActions.contains(neighbor)) {
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
        if (!isWetChannelBed(sample) || sample.subterranean()) {
            return false;
        }
        int bedY = (int) Math.round(sample.terrainHeight());
        int headY = (int) Math.round(sample.waterSurfaceY());
        return position.y() > bedY && position.y() <= headY + Math.max(0, dryHeadroom);
    }

    private static Map<CavePosition, RiverCaveAction> mergeActions(List<TunnelColumn> columns) {
        LinkedHashMap<CavePosition, RiverCaveAction> actions = new LinkedHashMap<>();
        for (TunnelColumn column : columns) {
            actions.putAll(column.actions());
        }
        return actions;
    }

    private static boolean matchesPublishedAction(
            CaveVoxelView view,
            CavePosition position,
            RiverCaveAction action
    ) {
        return view instanceof TunnelVoxelView tunnelView
                && tunnelView.riverActionAt(position) == action;
    }

    private static CavePosition offset(CavePosition position, int[] offset) {
        return new CavePosition(
                position.x() + offset[0],
                position.y() + offset[1],
                position.z() + offset[2]
        );
    }

    private MantleRiverCaveVoxelView createView(MantleWriter writer, ChunkContext context) {
        return new MantleRiverCaveVoxelView(
                writer.getMantle(),
                writer.getMantle().getWorldHeight(),
                (x, z) -> context.getComplex().getRoundedHeighteightStream().get(x, z),
                (x, z) -> context.getComplex().getFluidStream().get(x, z)
        );
    }

    private void publishTunnels(
            MantleWriter writer,
            ChunkContext context,
            IrisRiverRuntime runtime,
            int chunkX,
            int chunkZ
    ) {
        for (int attempt = 0; attempt < 2; attempt++) {
            MantleRiverCaveVoxelView view = createView(writer, context);
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
            MantleRiverCaveVoxelView revalidationView = createView(writer, context);
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
                        caves.getGrottoWarpStrength()
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
            Map<Long, String> floodedBiomes
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
                    new RiverCaveHydrology(entry.getValue(), biome)
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
                writer.setData(position.x(), position.y(), position.z(), RiverCaveHydrology.of(entry.getValue()));
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
        RiverCaveAction riverActionAt(CavePosition position);
    }

    record TunnelPlan(
            Map<CavePosition, RiverCaveAction> actions,
            Map<CavePosition, CaveVoxelPrecondition> preconditions
    ) {
        static TunnelPlan empty() {
            return new TunnelPlan(Map.of(), Map.of());
        }
    }

    private record TunnelColumn(Map<CavePosition, RiverCaveAction> actions) {
    }
}

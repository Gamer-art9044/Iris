package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.IrisStructureStiltSettings;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import art.arcane.iris.nativegen.NativeStructureGenerationException;
import art.arcane.iris.nativegen.NativeStructureLocateResults;
import art.arcane.iris.nativegen.NativeStructurePostProcessor;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.data.IrisCustomData;
import art.arcane.iris.util.common.reflect.WrappedField;
import art.arcane.iris.util.common.reflect.WrappedReturningMethod;
import art.arcane.volmlib.util.math.RNG;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;
import org.bukkit.block.data.BlockData;
import org.spigotmc.SpigotWorldConfig;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class IrisChunkGenerator extends CustomChunkGenerator {
    private static final WrappedField<ChunkGenerator, BiomeSource> BIOME_SOURCE;
    private static final WrappedReturningMethod<Heightmap, Object> SET_HEIGHT;
    private final ChunkGenerator delegate;
    private final Engine engine;
    private final CustomBiomeSource customBiomeSource;
    private final int runtimeMinY;
    private final int runtimeHeight;
    private final ConcurrentHashMap<SpawnTableKey, WeightedList<MobSpawnSettings.SpawnerData>> mergedSpawnTables = new ConcurrentHashMap<>();
    private volatile ReachableStructureCache reachableStructureCache;
    private volatile StructureStepCache structureStepCache;

    public IrisChunkGenerator(ChunkGenerator delegate, long seed, Engine engine, World world) {
        this(delegate, engine, world, new CustomBiomeSource(seed, engine, world));
    }

    private IrisChunkGenerator(ChunkGenerator delegate, Engine engine, World world, CustomBiomeSource customBiomeSource) {
        super(((CraftWorld) world).getHandle(), edit(delegate, customBiomeSource), world.getGenerator());
        this.delegate = delegate;
        this.engine = engine;
        this.customBiomeSource = customBiomeSource;
        ServerLevel level = ((CraftWorld) world).getHandle();
        this.runtimeMinY = level.getMinY();
        this.runtimeHeight = level.getHeight();
    }

    @Override
    public @Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> holders, BlockPos pos, int radius, boolean findUnexplored) {
        Pair<BlockPos, Holder<Structure>> irisPlaced = findNearestIrisStructure(
                level, holders, pos, Math.max(1, radius), findUnexplored);
        HolderSet<Structure> reachable = filterReachableStructures(level, holders);
        Pair<BlockPos, Holder<Structure>> nativeLocated = reachable == null || reachable.size() == 0
                ? null
                : delegate.findNearestMapStructure(level, reachable, pos, radius, findUnexplored);
        return NativeStructureLocateResults.nearest(pos, irisPlaced, nativeLocated);
    }

    private Pair<BlockPos, Holder<Structure>> findNearestIrisStructure(ServerLevel level,
                                                                       HolderSet<Structure> holders,
                                                                       BlockPos pos, int radius,
                                                                       boolean findUnexplored) {
        if (findUnexplored) {
            return null;
        }
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        BlockPos best = null;
        Holder<Structure> bestHolder = null;
        long bestDist = Long.MAX_VALUE;
        for (Holder<Structure> holder : holders) {
            Object id = registry.getKey(holder.value());
            if (id == null) {
                throw new IllegalStateException("Native structure locate received an unregistered structure holder");
            }
            String structureId = id.toString();
            IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(engine,
                    structureId, NativeStructurePostProcessor.isUndergroundStep(holder.value().step()));
            if (decision.status() != NativeStructureGenerationStatus.REPLACED_BY_IRIS) {
                continue;
            }
            IrisStructureLocator.LocateResult result = IrisStructureLocator.locate(
                    engine, structureId, pos.getX(), pos.getZ(), radius);
            if (result.status() == IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED) {
                throw new IllegalStateException("Iris structure locate reached its safety limit for "
                        + structureId + " within " + radius + " chunks");
            }
            if (!result.found()) {
                continue;
            }
            long dx = (long) result.originX() - pos.getX();
            long dz = (long) result.originZ() - pos.getZ();
            long d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = new BlockPos(result.originX(), result.baseY(), result.originZ());
                bestHolder = holder;
            }
        }
        return best == null ? null : Pair.of(best, bestHolder);
    }

    private HolderSet<Structure> filterReachableStructures(ServerLevel level, HolderSet<Structure> holders) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<NativeLocateCandidate> candidates = new ArrayList<>(holders.size());
        for (Holder<Structure> holder : holders) {
            Object id = registry.getKey(holder.value());
            if (id == null) {
                throw new IllegalStateException("Native structure filtering received an unregistered structure holder");
            }
            String key = id.toString();
            IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(engine,
                    key, NativeStructurePostProcessor.isUndergroundStep(holder.value().step()));
            if (!decision.generate()) {
                continue;
            }
            candidates.add(new NativeLocateCandidate(holder, key));
        }
        if (candidates.isEmpty()) {
            return HolderSet.direct(List.of());
        }
        Set<String> reachable = reachableStructureKeys(level);
        List<Holder<Structure>> kept = new ArrayList<>(candidates.size());
        for (NativeLocateCandidate candidate : candidates) {
            if (reachable.contains(candidate.key())) {
                kept.add(candidate.holder());
            }
        }
        if (kept.size() == holders.size()) {
            return holders;
        }
        return HolderSet.direct(kept);
    }

    private Set<String> reachableStructureKeys(ServerLevel level) {
        IrisDimension dimension = engine.getDimension();
        ReachableStructureCache cached = reachableStructureCache;
        if (cached != null && cached.dimension() == dimension) {
            return cached.keys();
        }
        synchronized (this) {
            cached = reachableStructureCache;
            if (cached != null && cached.dimension() == dimension) {
                return cached.keys();
            }
            Set<String> reachable = Set.copyOf(
                    VanillaStructureBiomes.reachableStructureKeys(level, customBiomeSource));
            reachableStructureCache = new ReachableStructureCache(dimension, reachable);
            return reachable;
        }
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return MapCodec.unit(null);
    }

    @Override
    public ChunkGenerator getDelegate() {
        if (delegate instanceof CustomChunkGenerator chunkGenerator)
            return chunkGenerator.getDelegate();
        return delegate;
    }

    @Override
    public int getMinY() {
        return runtimeMinY;
    }

    @Override
    public int getSeaLevel() {
        return runtimeMinY + engine.getDimension().getFluidHeight();
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState, StructureManager structureManager, ChunkAccess access, StructureTemplateManager templateManager, ResourceKey<Level> levelKey) {
        Map<Structure, StructureStart> previousStarts = new HashMap<>(access.getAllStarts());
        super.createStructures(registryAccess, structureState, structureManager, access, templateManager, levelKey);
        adjustGeneratedStructures(registryAccess, access, previousStarts);
    }

    private void adjustGeneratedStructures(RegistryAccess registryAccess, ChunkAccess access, Map<Structure, StructureStart> previousStarts) {
        Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
        ChunkPos chunkPos = access.getPos();
        for (Map.Entry<Structure, StructureStart> entry : access.getAllStarts().entrySet()) {
            Structure structure = entry.getKey();
            StructureStart start = entry.getValue();
            if (!start.isValid() || previousStarts.get(structure) == start) {
                continue;
            }
            Identifier id = registry.getKey(structure);
            String structureId = id == null ? null : id.toString();
            if (structureId == null) {
                throw NativeStructureGenerationException.failure(
                        "resolution", null, chunkPos.x(), chunkPos.z());
            }
            boolean undergroundStep = NativeStructurePostProcessor.isUndergroundStep(structure.step());
            IrisNativeStructureDecision decision;
            try {
                decision = NativeStructureGenerationPolicy.resolve(engine,
                        structureId, undergroundStep);
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "policy resolution", structureId, chunkPos.x(), chunkPos.z(), error);
            }
            if (!decision.generate()) {
                access.setStartForStructure(structure, StructureStart.INVALID_START);
                continue;
            }
            try {
                NativeStructurePostProcessor.applyVerticalPlacement(
                        start,
                        structureId,
                        decision.yShift(),
                        getSeaLevel(),
                        access.getMinY(),
                        access.getMinY() + access.getHeight(),
                        undergroundStep,
                        (x, z) -> engine.getHeight(x, z, true) + engine.getMinHeight());
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "vertical adjustment", structureId, chunkPos.x(), chunkPos.z(), error);
            }
        }
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> holderlookup, RandomState randomstate, long i, SpigotWorldConfig conf) {
        return delegate.createState(holderlookup, randomstate, i, conf);
    }

    @Override
    public void createReferences(WorldGenLevel generatoraccessseed, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        delegate.createReferences(generatoraccessseed, structuremanager, ichunkaccess);
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomstate, Blender blender, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        ichunkaccess.fillBiomesFromNoise(customBiomeSource::getVisibleNoiseBiome, randomstate.sampler());
        return CompletableFuture.completedFuture(ichunkaccess);
    }

    @Override
    public void buildSurface(WorldGenRegion regionlimitedworldaccess, StructureManager structuremanager, RandomState randomstate, ChunkAccess ichunkaccess) {
        delegate.buildSurface(regionlimitedworldaccess, structuremanager, randomstate, ichunkaccess);
    }

    @Override
    public void applyCarvers(WorldGenRegion regionlimitedworldaccess, long seed, RandomState randomstate, BiomeManager biomemanager, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        delegate.applyCarvers(regionlimitedworldaccess, seed, randomstate, biomemanager, structuremanager, ichunkaccess);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomstate, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        return delegate.fillFromNoise(blender, randomstate, structuremanager, ichunkaccess);
    }

    @Override
    public WeightedList<MobSpawnSettings.SpawnerData> getMobsAt(Holder<Biome> holder, StructureManager structuremanager, MobCategory enumcreaturetype, BlockPos blockposition) {
        Holder<Biome> vanillaSpawnBiome = customBiomeSource.getVanillaSpawnBiome(holder);
        if (vanillaSpawnBiome == null) {
            return delegate.getMobsAt(holder, structuremanager, enumcreaturetype, blockposition);
        }

        WeightedList<MobSpawnSettings.SpawnerData> vanillaSpawns = vanillaSpawnBiome.value().getMobSettings().getMobs(enumcreaturetype);
        WeightedList<MobSpawnSettings.SpawnerData> resolvedSpawns = delegate.getMobsAt(
                vanillaSpawnBiome, structuremanager, enumcreaturetype, blockposition);
        if (resolvedSpawns != vanillaSpawns) {
            return resolvedSpawns;
        }

        WeightedList<MobSpawnSettings.SpawnerData> explicitSpawns = holder.value().getMobSettings().getMobs(enumcreaturetype);
        if (explicitSpawns.isEmpty()) {
            return vanillaSpawns;
        }
        if (vanillaSpawns.isEmpty()) {
            return explicitSpawns;
        }

        SpawnTableKey key = new SpawnTableKey(holder.value(), enumcreaturetype);
        return mergedSpawnTables.computeIfAbsent(key, ignored -> mergeSpawnTables(vanillaSpawns, explicitSpawns));
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager) {
        applyBiomeDecoration(generatoraccessseed, ichunkaccess, structuremanager, true);
    }

    @Override
    public void addDebugScreenInfo(List<String> list, RandomState randomstate, BlockPos blockposition) {
        delegate.addDebugScreenInfo(list, randomstate, blockposition);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager, boolean vanilla) {
        addVanillaDecorations(generatoraccessseed, ichunkaccess, structuremanager);
        placeVanillaStructures(generatoraccessseed, ichunkaccess, structuremanager);
        delegate.applyBiomeDecoration(generatoraccessseed, ichunkaccess, structuremanager, false);
    }

    private void placeVanillaStructures(WorldGenLevel world, ChunkAccess chunk, StructureManager structureManager) {
        if (!structureManager.shouldGenerateStructures()) {
            ChunkPos disabledChunk = chunk.getPos();
            throw new IllegalStateException("Iris cannot generate native structures in chunk "
                    + disabledChunk.x() + "," + disabledChunk.z()
                    + " because structure generation is disabled outside the pack; enable native structure generation "
                    + "and deny individual structures through importedStructures.disabled");
        }
        ChunkPos chunkPos = chunk.getPos();
        SectionPos sectionPos = SectionPos.of(chunkPos, world.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<List<Structure>> byStep = structuresByStep(registry);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decoSeed = random.setDecorationSeed(world.getSeed(), origin.getX(), origin.getZ());
        BoundingBox area = writableArea(chunk);
        int steps = GenerationStep.Decoration.values().length;
        List<NativePlacementGroup> placementGroups = new ArrayList<>();
        List<StructureStart> nativeStarts = new ArrayList<>();
        List<NativeStructurePostProcessor.VegetationTarget> vegetationTargets = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            int index = 0;
            for (Structure structure : byStep.get(step)) {
                Object id = registry.getKey(structure);
                String structureId = id == null ? null : id.toString();
                if (structureId == null) {
                    throw NativeStructureGenerationException.failure(
                            "resolution", null, chunkPos.x(), chunkPos.z());
                }
                try {
                    IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(engine,
                            structureId, NativeStructurePostProcessor.isUndergroundStep(structure.step()));
                    if (decision.generate()) {
                        List<StructureStart> starts = structureManager.startsForStructure(sectionPos, structure);
                        if (!starts.isEmpty()) {
                            List<StructureStart> resolvedStarts = List.copyOf(starts);
                            placementGroups.add(new NativePlacementGroup(
                                    structureId, decision, index, step, resolvedStarts));
                            nativeStarts.addAll(resolvedStarts);
                            boolean clearEntireFootprint = NativeStructurePostProcessor
                                    .shouldClearEntireVegetationFootprint(
                                            structure.step(), decision.clearVegetation());
                            for (StructureStart start : resolvedStarts) {
                                vegetationTargets.add(new NativeStructurePostProcessor.VegetationTarget(
                                        start, clearEntireFootprint));
                            }
                        }
                    }
                } catch (Throwable error) {
                    throw NativeStructureGenerationException.failure(
                            "resolution", structureId, chunkPos.x(), chunkPos.z(), error);
                }
                index++;
            }
        }
        try {
            NativeStructurePostProcessor.prepareSurfaceStructures(
                    world, area, nativeStarts,
                    (x, z) -> engine.getHeight(x, z, true) + engine.getMinHeight());
        } catch (Throwable error) {
            throw NativeStructureGenerationException.failure(
                    "terrain integration", nativeStructureBatchContext(placementGroups),
                    chunkPos.x(), chunkPos.z(), error);
        }
        try {
            NativeStructurePostProcessor.clearIntersectingVegetation(
                    world, chunk, area, vegetationTargets);
        } catch (Throwable error) {
            throw NativeStructureGenerationException.failure(
                    "vegetation cleanup", nativeStructureBatchContext(placementGroups),
                    chunkPos.x(), chunkPos.z(), error);
        }
        for (NativePlacementGroup group : placementGroups) {
            random.setFeatureSeed(decoSeed, group.featureIndex(), group.step());
            try {
                for (StructureStart start : group.starts()) {
                    placeVanillaStructure(world, structureManager, random, area, chunkPos,
                            group.structureId(), start, group.decision());
                }
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "placement", group.structureId(), chunkPos.x(), chunkPos.z(), error);
            }
        }
    }

    private static String nativeStructureBatchContext(List<NativePlacementGroup> placementGroups) {
        if (placementGroups.isEmpty()) {
            return "<no resolved native structures>";
        }
        StringBuilder context = new StringBuilder("[");
        for (int i = 0; i < placementGroups.size(); i++) {
            if (i > 0) {
                context.append(", ");
            }
            context.append(placementGroups.get(i).structureId());
        }
        return context.append(']').toString();
    }

    private void placeVanillaStructure(WorldGenLevel world, StructureManager structureManager, WorldgenRandom random,
                                       BoundingBox area, ChunkPos chunkPos, String structureId, StructureStart start,
                                       IrisNativeStructureDecision decision) {
        NativeStructurePostProcessor.place(world, structureManager, this, random, area, chunkPos,
                structureId, start, decision, this::resolveStiltBlock,
                (x, z) -> engine.getHeight(x, z, true) + engine.getMinHeight());
    }

    private List<List<Structure>> structuresByStep(Registry<Structure> registry) {
        StructureStepCache cached = structureStepCache;
        if (cached != null && cached.registry() == registry) {
            return cached.structures();
        }
        synchronized (this) {
            cached = structureStepCache;
            if (cached != null && cached.registry() == registry) {
                return cached.structures();
            }
            int steps = GenerationStep.Decoration.values().length;
            List<List<Structure>> grouped = new ArrayList<>(steps);
            for (int step = 0; step < steps; step++) {
                grouped.add(new ArrayList<>());
            }
            for (Structure structure : registry) {
                grouped.get(structure.step().ordinal()).add(structure);
            }
            for (int step = 0; step < steps; step++) {
                grouped.set(step, List.copyOf(grouped.get(step)));
            }
            List<List<Structure>> resolved = List.copyOf(grouped);
            structureStepCache = new StructureStepCache(registry, resolved);
            return resolved;
        }
    }

    private BlockState resolveStiltBlock(IrisStructureStiltSettings settings, RNG rng, int x, int y, int z) {
        if (settings.getPalette() == null) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        PlatformBlockState platformState = settings.getPalette().get(rng, x, y, z, engine.getData());
        if (platformState == null || !(platformState.nativeHandle() instanceof BlockData blockData)) {
            throw new IllegalStateException("Configured native structure stilt palette did not resolve a Bukkit block at "
                    + x + "," + y + "," + z);
        }
        if (blockData instanceof IrisCustomData customData) {
            blockData = customData.getBase();
        }
        if (blockData instanceof CraftBlockData craftBlockData) {
            return craftBlockData.getState();
        }
        throw new IllegalStateException("Configured native structure stilt palette resolved unsupported Bukkit block data "
                + blockData.getClass().getName() + " at " + x + "," + y + "," + z);
    }

    private BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int i = cp.getMinBlockX();
        int j = cp.getMinBlockZ();
        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight() - 1;
        return new BoundingBox(i, minY, j, i + 15, maxY, j + 15);
    }

    @Override
    public void addVanillaDecorations(WorldGenLevel level, ChunkAccess chunkAccess, StructureManager structureManager) {
        SectionPos sectionPos = SectionPos.of(chunkAccess.getPos(), level.getMinSectionY());
        BlockPos blockPos = sectionPos.origin();

        Heightmap surface = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap ocean = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap motion = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        Heightmap motionNoLeaves = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wX = x + blockPos.getX();
                int wZ = z + blockPos.getZ();

                int terrainTop = engine.getHeight(wX, wZ, false) + engine.getMinHeight() + 1;
                int terrainNoFluid = engine.getHeight(wX, wZ, true) + engine.getMinHeight() + 1;
                SET_HEIGHT.invoke(ocean, x, z, terrainNoFluid);
                SET_HEIGHT.invoke(surface, x, z, terrainTop);
                SET_HEIGHT.invoke(motion, x, z, terrainTop);
                SET_HEIGHT.invoke(motionNoLeaves, x, z, terrainTop);
            }
        }

        Heightmap.primeHeightmaps(chunkAccess, ChunkStatus.FINAL_HEIGHTMAPS);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion regionlimitedworldaccess) {
        delegate.spawnOriginalMobs(regionlimitedworldaccess);
    }

    private static WeightedList<MobSpawnSettings.SpawnerData> mergeSpawnTables(
            WeightedList<MobSpawnSettings.SpawnerData> vanillaSpawns,
            WeightedList<MobSpawnSettings.SpawnerData> explicitSpawns) {
        List<Weighted<MobSpawnSettings.SpawnerData>> entries = new ArrayList<>(
                vanillaSpawns.unwrap().size() + explicitSpawns.unwrap().size());
        Set<EntityType<?>> explicitTypes = new HashSet<>();
        for (Weighted<MobSpawnSettings.SpawnerData> entry : explicitSpawns.unwrap()) {
            explicitTypes.add(entry.value().type());
        }
        for (Weighted<MobSpawnSettings.SpawnerData> entry : vanillaSpawns.unwrap()) {
            if (!explicitTypes.contains(entry.value().type())) {
                entries.add(entry);
            }
        }
        entries.addAll(explicitSpawns.unwrap());
        return WeightedList.of(entries);
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor levelheightaccessor) {
        return delegate.getSpawnHeight(levelheightaccessor);
    }

    @Override
    public int getGenDepth() {
        return runtimeHeight;
    }

    @Override
    public int getBaseHeight(int i, int j, Heightmap.Types heightmap_type, LevelHeightAccessor levelheightaccessor, RandomState randomstate) {
        return levelheightaccessor.getMinY() + engine.getHeight(i, j, !heightmap_type.isOpaque().test(Blocks.WATER.defaultBlockState())) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int i, int j, LevelHeightAccessor levelheightaccessor, RandomState randomstate) {
        int block = engine.getHeight(i, j, true);
        int water = engine.getHeight(i, j, false);
        BlockState[] column = new BlockState[levelheightaccessor.getHeight()];
        for (int k = 0; k < column.length; k++) {
            if (k <= block) column[k] = Blocks.STONE.defaultBlockState();
            else if (k <= water) column[k] = Blocks.WATER.defaultBlockState();
            else column[k] = Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(levelheightaccessor.getMinY(), column);
    }

    @Override
    public Optional<Identifier> getTypeNameForDataFixer() {
        return delegate.getTypeNameForDataFixer();
    }

    @Override
    public void validate() {
        delegate.validate();
    }

    static {
        Field biomeSource = null;
        for (Field field : ChunkGenerator.class.getDeclaredFields()) {
            if (!field.getType().equals(BiomeSource.class))
                continue;
            biomeSource = field;
            break;
        }
        if (biomeSource == null)
            throw new RuntimeException("Could not find biomeSource field in ChunkGenerator!");

        Method setHeight = null;
        for (Method method : Heightmap.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 3 || !Arrays.equals(types, new Class<?>[]{int.class, int.class, int.class})
                    || !method.getReturnType().equals(void.class))
                continue;
            setHeight = method;
            break;
        }
        if (setHeight == null)
            throw new RuntimeException("Could not find setHeight method in Heightmap!");

        BIOME_SOURCE = new WrappedField<>(ChunkGenerator.class, biomeSource.getName());
        SET_HEIGHT = new WrappedReturningMethod<>(Heightmap.class, setHeight.getName(), setHeight.getParameterTypes());
    }

    private static ChunkGenerator edit(ChunkGenerator generator, BiomeSource source) {
        try {
            BIOME_SOURCE.set(generator, source);
            if (generator instanceof CustomChunkGenerator custom)
                BIOME_SOURCE.set(custom.getDelegate(), source);

            return generator;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private record SpawnTableKey(Biome biome, MobCategory category) {
    }

    private record ReachableStructureCache(IrisDimension dimension, Set<String> keys) {
    }

    private record StructureStepCache(Registry<Structure> registry, List<List<Structure>> structures) {
    }

    private record NativePlacementGroup(String structureId, IrisNativeStructureDecision decision,
                                        int featureIndex, int step, List<StructureStart> starts) {
    }

    private record NativeLocateCandidate(Holder<Structure> holder, String key) {
    }
}

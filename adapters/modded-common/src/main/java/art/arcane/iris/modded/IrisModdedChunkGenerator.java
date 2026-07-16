/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.IrisStructureStiltSettings;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import art.arcane.iris.nativegen.NativeStructureGenerationException;
import art.arcane.iris.nativegen.NativeStructureLocateResults;
import art.arcane.iris.nativegen.NativeStructurePostProcessor;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.math.RNG;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;

public final class IrisModdedChunkGenerator extends ChunkGenerator {
    private static final int WORLD_CHECK_SHIFT_RECORD_LIMIT = 4096;
    private static final boolean WORLD_CHECK_ENABLED = Boolean.getBoolean("iris.worldcheck");
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    public static final MapCodec<IrisModdedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec((RecordCodecBuilder.Instance<IrisModdedChunkGenerator> instance) -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter((IrisModdedChunkGenerator generator) -> generator.serializedBiomeSource),
            Codec.STRING.fieldOf("dimension").forGetter((IrisModdedChunkGenerator generator) -> generator.dimensionKey)
    ).apply(instance, IrisModdedChunkGenerator::new));

    private static final AtomicInteger GEN_THREAD_SEQ = new AtomicInteger();
    private static final boolean PARALLEL_CHUNK_SYSTEM = detectParallelChunkSystem();
    private static volatile ExecutorService genPool = createGenPool();

    public static void startGenPool() {
        ExecutorService pool = genPool;
        if (pool == null || pool.isShutdown()) {
            genPool = createGenPool();
        }
    }

    public static void shutdownGenPool() {
        ExecutorService pool = genPool;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static boolean detectParallelChunkSystem() {
        String[] markers = {
                "com.ishland.c2me.base.ModProperties",
                "com.ishland.c2me.base.common.config.C2MEConfig",
                "com.ishland.c2me.opts.chunkio.ModProperties",
                "ca.spottedleaf.moonrise.common.util.MoonriseCommon"
        };
        for (String marker : markers) {
            try {
                Class.forName(marker, false, IrisModdedChunkGenerator.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static ExecutorService createGenPool() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "Iris ModGen-" + GEN_THREAD_SEQ.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private final String dimensionKey;
    private final String defaultPack;
    private final String defaultDimensionKey;
    private final BiomeSource serializedBiomeSource;
    private final IrisModdedBiomeSource structureBiomeSource;
    private final EngineBinding<Engine> engineBinding = new EngineBinding<>(60L, TimeUnit.SECONDS);
    private final ConcurrentHashMap<Biome, Holder<Biome>> vanillaSpawnBiomes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SpawnTableKey, WeightedList<MobSpawnSettings.SpawnerData>> mergedSpawnTables = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NativeStructureStartKey, Integer> worldCheckStructureShifts = new ConcurrentHashMap<>();
    private final AtomicBoolean announced = new AtomicBoolean(false);
    private volatile boolean vanillaSpawnBiomesInitialized;
    private volatile Engine engine;
    private volatile String activePack;
    private volatile String activeDimensionKey;
    private volatile long seedOverride = Long.MIN_VALUE;
    private volatile long lastChunkGenAt = 0L;
    private volatile Set<String> configuredStructureBiomeKeys;
    private volatile ConfiguredPack configuredPack;
    private volatile StructureStepCache structureStepCache;

    public IrisModdedChunkGenerator(BiomeSource biomeSource, String dimensionKey) {
        this(biomeSource, dimensionKey, new IrisModdedBiomeSource(biomeSource));
    }

    private IrisModdedChunkGenerator(BiomeSource serializedBiomeSource, String dimensionKey, IrisModdedBiomeSource structureBiomeSource) {
        super(structureBiomeSource);
        this.dimensionKey = dimensionKey;
        this.serializedBiomeSource = serializedBiomeSource;
        this.structureBiomeSource = structureBiomeSource;
        this.structureBiomeSource.bind(this);
        int colon = dimensionKey.indexOf(':');
        this.defaultPack = colon >= 0 ? dimensionKey.substring(0, colon) : dimensionKey;
        this.defaultDimensionKey = colon >= 0 ? dimensionKey.substring(colon + 1) : dimensionKey;
        this.activePack = defaultPack;
        this.activeDimensionKey = defaultDimensionKey;
    }

    public synchronized void repoint(String pack, String packDimensionKey, long seed) {
        ServerLevel level = boundLevel();
        if (level != null) {
            repointAndBind(level, pack, packDimensionKey, seed);
            return;
        }
        applyUnboundConfiguration(pack, packDimensionKey, seed);
    }

    synchronized void repointAndBind(ServerLevel level, String pack, String packDimensionKey, long seed) {
        if (level.getChunkSource().getGenerator() != this) {
            throw new IllegalArgumentException("ServerLevel does not use Iris generator '" + dimensionKey + "'");
        }
        requireGlobalStructureGeneration(
                level.getServer().getWorldGenSettings().options().generateStructures(), dimensionKey);
        Engine replacement = ModdedWorldEngines.prepareReplacement(level, pack, packDimensionKey, seed);
        try {
            ModdedWorldEngines.installReplacement(level, replacement);
        } catch (Throwable error) {
            try {
                ModdedWorldEngines.closeUnregistered(replacement);
            } catch (Throwable cleanupError) {
                error.addSuppressed(cleanupError);
            }
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (error instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("Iris generator '" + dimensionKey + "' failed to replace its engine", error);
        }
        this.activePack = pack;
        this.activeDimensionKey = packDimensionKey;
        this.seedOverride = seed;
        this.engine = replacement;
        this.configuredStructureBiomeKeys = null;
        this.configuredPack = null;
        this.engineBinding.reset();
        this.engineBinding.complete(replacement);
        this.announced.set(false);
        this.structureBiomeSource.clearCaches();
        this.worldCheckStructureShifts.clear();
        resetVanillaSpawnBiomes();
    }

    public synchronized void unbindEngine() {
        ServerLevel level = boundLevel();
        unbindEngine(level);
    }

    synchronized void unbindEngine(ServerLevel level) {
        if (level != null) {
            ModdedWorldEngines.evictOrThrow(level);
        }
        clearEngineBinding();
    }

    private void clearEngineBinding() {
        this.engine = null;
        this.configuredStructureBiomeKeys = null;
        this.configuredPack = null;
        this.engineBinding.reset();
        this.announced.set(false);
        this.structureBiomeSource.clearCaches();
        this.worldCheckStructureShifts.clear();
        resetVanillaSpawnBiomes();
    }

    private void applyUnboundConfiguration(String pack, String packDimensionKey, long seed) {
        this.activePack = pack;
        this.activeDimensionKey = packDimensionKey;
        this.seedOverride = seed;
        this.engine = null;
        this.configuredStructureBiomeKeys = null;
        this.configuredPack = null;
        this.engineBinding.reset();
        this.announced.set(false);
        this.structureBiomeSource.clearCaches();
        this.worldCheckStructureShifts.clear();
        resetVanillaSpawnBiomes();
    }

    public synchronized void resetToDefault() {
        repoint(defaultPack, defaultDimensionKey, Long.MIN_VALUE);
    }

    public String activePack() {
        return activePack;
    }

    public String activeDimensionKey() {
        return activeDimensionKey;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
        return ChunkGeneratorStructureState.createForNormal(
                randomState, seed, structureBiomeSource.forStructureState(structureSets), structureSets);
    }

    @Override
    public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> holders,
                                                                     BlockPos pos, int radius,
                                                                     boolean findUnexplored) {
        Engine current = engine();
        Pair<BlockPos, Holder<Structure>> irisPlaced = findNearestIrisStructure(
                level, holders, pos, Math.max(1, radius), findUnexplored, current);
        HolderSet<Structure> reachable = filterReachableNativeStructures(level, holders, current);
        Pair<BlockPos, Holder<Structure>> nativeLocated = reachable.size() == 0
                ? null
                : super.findNearestMapStructure(level, reachable, pos, radius, findUnexplored);
        return NativeStructureLocateResults.nearest(pos, irisPlaced, nativeLocated);
    }

    public boolean isNativeStructureReachable(Holder<Structure> structure) {
        return structure != null && structureBiomeSource.isStructureReachable(structure);
    }

    private Pair<BlockPos, Holder<Structure>> findNearestIrisStructure(ServerLevel level,
                                                                       HolderSet<Structure> holders,
                                                                       BlockPos pos, int radius, boolean findUnexplored,
                                                                       Engine current) {
        if (findUnexplored) {
            return null;
        }
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        BlockPos best = null;
        Holder<Structure> bestHolder = null;
        long bestDistance = Long.MAX_VALUE;
        for (Holder<Structure> holder : holders) {
            Identifier id = registry.getKey(holder.value());
            if (id == null) {
                throw new IllegalStateException("Native structure locate received an unregistered structure holder");
            }
            String structureId = id.toString();
            IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(current,
                    structureId, NativeStructurePostProcessor.isUndergroundStep(holder.value().step()));
            if (decision.status() != NativeStructureGenerationStatus.REPLACED_BY_IRIS) {
                continue;
            }
            IrisStructureLocator.LocateResult result = IrisStructureLocator.locate(
                    current, structureId, pos.getX(), pos.getZ(), radius);
            if (result.status() == IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED) {
                throw new IllegalStateException("Iris structure locate reached its safety limit for "
                        + structureId + " within " + radius + " chunks");
            }
            if (!result.found()) {
                continue;
            }
            long dx = (long) result.originX() - pos.getX();
            long dz = (long) result.originZ() - pos.getZ();
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new BlockPos(result.originX(), result.baseY(), result.originZ());
                bestHolder = holder;
            }
        }
        return best == null ? null : Pair.of(best, bestHolder);
    }

    private HolderSet<Structure> filterReachableNativeStructures(ServerLevel level, HolderSet<Structure> holders,
                                                                  Engine current) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> kept = new ArrayList<>(holders.size());
        for (Holder<Structure> holder : holders) {
            Identifier id = registry.getKey(holder.value());
            if (id == null) {
                throw new IllegalStateException("Native structure filtering received an unregistered structure holder");
            }
            String key = id.toString();
            IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(current,
                    key, NativeStructurePostProcessor.isUndergroundStep(holder.value().step()));
            if (!decision.generate() || !structureBiomeSource.isStructureReachable(holder)) {
                continue;
            }
            kept.add(holder);
        }
        return kept.size() == holders.size() ? holders : HolderSet.direct(kept);
    }

    private ServerLevel boundLevel() {
        MinecraftServer server = ModdedEngineBootstrap.currentServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() == this) {
                return level;
            }
        }
        return null;
    }

    private Engine engine() {
        Engine cached = engine;
        if (cached != null) {
            return cached;
        }
        ServerLevel level = boundLevel();
        if (level == null) {
            throw new IllegalStateException("Iris generator '" + dimensionKey + "' has no bound ServerLevel yet");
        }
        return bindEngine(level);
    }

    void bindLevel(ServerLevel level) {
        if (level.getChunkSource().getGenerator() != this) {
            throw new IllegalArgumentException("ServerLevel does not use Iris generator '" + dimensionKey + "'");
        }
        bindEngine(level);
    }

    private Engine bindEngine(ServerLevel level) {
        try {
            requireGlobalStructureGeneration(
                    level.getServer().getWorldGenSettings().options().generateStructures(), dimensionKey);
        } catch (RuntimeException error) {
            engineBinding.fail(error);
            throw error;
        }
        Engine cached = engine;
        if (cached != null && !cached.isClosed() && cached.getComplex() != null) {
            engineBinding.complete(cached);
            return cached;
        }
        synchronized (this) {
            Engine existing = engine;
            if (existing != null && !existing.isClosed() && existing.getComplex() != null) {
                engineBinding.complete(existing);
                return existing;
            }
            try {
                Engine created = ModdedWorldEngines.get(level, activePack, activeDimensionKey, seedOverride);
                if (created.isClosed() || created.getComplex() == null) {
                    throw new IllegalStateException("Iris generator '" + dimensionKey
                            + "' created an engine without a ready biome complex");
                }
                engine = created;
                configuredStructureBiomeKeys = null;
                structureBiomeSource.clearCaches();
                engineBinding.complete(created);
                return created;
            } catch (Throwable error) {
                engineBinding.fail(error);
                if (error instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (error instanceof Error fatalError) {
                    throw fatalError;
                }
                throw new IllegalStateException("Iris generator '" + dimensionKey + "' failed to bind", error);
            }
        }
    }

    static void requireGlobalStructureGeneration(boolean enabled, String dimensionKey) {
        if (enabled) {
            return;
        }
        throw new IllegalStateException("Iris generator '" + dimensionKey
                + "' cannot bind while generate-structures=false; set generate-structures=true, restart the server, "
                + "and deny individual structures through importedStructures.disabled");
    }

    private Engine engineOrNull() {
        Engine cached = engine;
        if (cached != null) {
            return cached;
        }
        try {
            return engine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    Engine structureEngineOrNull() {
        engineBinding.throwIfFailed(dimensionKey);
        Engine cached = engine;
        if (cached != null && !cached.isClosed() && cached.getComplex() != null) {
            return cached;
        }
        ServerLevel level = boundLevel();
        return level == null ? null : bindEngine(level);
    }

    Engine awaitStructureEngine() {
        Engine current = engine;
        if (current != null && !current.isClosed() && current.getComplex() != null) {
            return current;
        }
        Engine bound = engineBinding.await(dimensionKey);
        if (bound.isClosed() || bound.getComplex() == null) {
            throw new IllegalStateException("Iris generator '" + dimensionKey
                    + "' completed bootstrap without a ready biome complex");
        }
        return bound;
    }

    private Engine requireDataQueryEngine(String operation) {
        Engine current = engine;
        if (current != null && !current.isClosed() && current.getComplex() != null) {
            return current;
        }
        ServerLevel level = boundLevel();
        if (level != null) {
            return bindEngine(level);
        }
        MinecraftServer server = ModdedEngineBootstrap.currentServer();
        if (server == null) {
            throw new IllegalStateException("Iris generator '" + dimensionKey + "' cannot answer "
                    + operation + " without an active server");
        }
        if (server.isSameThread()) {
            throw new IllegalStateException("Iris generator '" + dimensionKey + "' cannot answer "
                    + operation + " on the server thread before its ServerLevel is bound");
        }
        return awaitStructureEngine();
    }

    long visibleBiomeSeed() {
        long configuredSeed = seedOverride;
        if (configuredSeed != Long.MIN_VALUE) {
            return configuredSeed;
        }
        ServerLevel level = boundLevel();
        if (level != null) {
            return level.getSeed();
        }
        Engine current = engine;
        return current == null ? 0L : current.getWorld().getRawWorldSeed();
    }

    Set<String> configuredStructureBiomeKeys() {
        Set<String> cached = configuredStructureBiomeKeys;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (configuredStructureBiomeKeys != null) {
                return configuredStructureBiomeKeys;
            }
            Engine current = engine;
            Iterable<IrisBiome> biomes;
            String namespace;
            if (current != null && !current.isClosed()) {
                biomes = current.getAllBiomes();
                namespace = current.getDimension().getLoadKey();
            } else {
                ConfiguredPack configured = configuredPack();
                Set<String> resolved = collectConfiguredBiomeKeys(configured.dimension(), configured.data());
                configuredStructureBiomeKeys = resolved;
                return resolved;
            }
            Set<String> resolved = collectConfiguredBiomeKeys(biomes, namespace);
            configuredStructureBiomeKeys = resolved;
            return resolved;
        }
    }

    private ConfiguredPack configuredPack() {
        ConfiguredPack cached = configuredPack;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (configuredPack != null) {
                return configuredPack;
            }
            PackValidationRegistry.requireLoadable(activePack);
            File packDirectory = ModdedWorldEngines.resolvePack(activePack, activeDimensionKey);
            IrisData data = IrisData.get(packDirectory);
            IrisDimension dimension = data.getDimensionLoader().load(activeDimensionKey);
            if (dimension == null) {
                throw new IllegalStateException("Iris dimension '" + activeDimensionKey
                        + "' missing from pack " + packDirectory.getAbsolutePath());
            }
            ConfiguredPack resolved = new ConfiguredPack(data, dimension, dimensionMetadata(dimension));
            configuredPack = resolved;
            return resolved;
        }
    }

    static DimensionMetadata dimensionMetadata(IrisDimension dimension) {
        int minY = dimension.getMinHeight();
        int maxY = dimension.getMaxHeight();
        if (maxY <= minY) {
            throw new IllegalStateException("Iris dimension '" + dimension.getLoadKey()
                    + "' has invalid height range " + minY + ".." + maxY);
        }
        return new DimensionMetadata(minY, maxY, minY + dimension.getFluidHeight());
    }

    static Set<String> collectConfiguredBiomeKeys(IrisDimension dimension, IrisData data) {
        return collectConfiguredBiomeKeys(dimension.getReachableBiomes(() -> data), dimension.getLoadKey());
    }

    static Set<String> collectConfiguredBiomeKeys(Iterable<IrisBiome> biomes, String dimensionLoadKey) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String namespace = dimensionLoadKey.toLowerCase(Locale.ROOT);
        for (IrisBiome irisBiome : biomes) {
            if (irisBiome == null) {
                continue;
            }
            Identifier derivative = Identifier.tryParse(irisBiome.getVanillaDerivativeKey());
            if (derivative != null) {
                keys.add(derivative.toString().toLowerCase(Locale.ROOT));
            }
            if (!irisBiome.isCustom()) {
                continue;
            }
            for (IrisBiomeCustom customBiome : irisBiome.getCustomDerivitives()) {
                keys.add(namespace + ":" + customBiome.getId().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(keys);
    }

    public String dimensionKey() {
        return dimensionKey;
    }

    public Engine engineIfBound() {
        return engine;
    }

    public Engine commandEngine() {
        return engine();
    }

    public long lastChunkGenAt() {
        return lastChunkGenAt;
    }

    public void onHotload() {
        configuredStructureBiomeKeys = null;
        structureBiomeSource.clearCaches();
        worldCheckStructureShifts.clear();
        resetVanillaSpawnBiomes();
    }

    @Override
    public WeightedList<MobSpawnSettings.SpawnerData> getMobsAt(
            Holder<Biome> biome, StructureManager structureManager, MobCategory category, BlockPos pos) {
        WeightedList<MobSpawnSettings.SpawnerData> explicitSpawns = biome.value().getMobSettings().getMobs(category);
        WeightedList<MobSpawnSettings.SpawnerData> resolvedSpawns = super.getMobsAt(
                biome, structureManager, category, pos);
        if (resolvedSpawns != explicitSpawns) {
            return resolvedSpawns;
        }

        Registry<Biome> registry = structureManager.registryAccess().lookupOrThrow(Registries.BIOME);
        initializeVanillaSpawnBiomes(registry);
        Holder<Biome> vanillaSpawnBiome = vanillaSpawnBiomes.get(biome.value());
        if (vanillaSpawnBiome == null) {
            return explicitSpawns;
        }

        WeightedList<MobSpawnSettings.SpawnerData> vanillaSpawns = vanillaSpawnBiome.value().getMobSettings().getMobs(category);
        if (explicitSpawns.isEmpty()) {
            return vanillaSpawns;
        }
        if (vanillaSpawns.isEmpty()) {
            return explicitSpawns;
        }

        SpawnTableKey key = new SpawnTableKey(biome.value(), category);
        return mergedSpawnTables.computeIfAbsent(key, ignored -> NativeSpawnTableMerger.merge(vanillaSpawns, explicitSpawns));
    }

    private synchronized void initializeVanillaSpawnBiomes(Registry<Biome> registry) {
        if (vanillaSpawnBiomesInitialized) {
            return;
        }
        Engine current = engineOrNull();
        if (current == null) {
            return;
        }

        String namespace = current.getDimension().getLoadKey().toLowerCase(Locale.ROOT);
        for (IrisBiome irisBiome : current.getDimension().getReachableBiomes(current)) {
            if (irisBiome == null || !irisBiome.isCustom()) {
                continue;
            }
            Holder<Biome> vanillaHolder = resolveBiomeHolder(registry, irisBiome.getVanillaDerivativeKey());
            if (vanillaHolder == null) {
                continue;
            }
            for (IrisBiomeCustom customBiome : irisBiome.getCustomDerivitives()) {
                Holder<Biome> customHolder = resolveBiomeHolder(registry, namespace + ":" + customBiome.getId());
                if (customHolder != null) {
                    vanillaSpawnBiomes.putIfAbsent(customHolder.value(), vanillaHolder);
                }
            }
        }
        vanillaSpawnBiomesInitialized = true;
    }

    private Holder<Biome> resolveBiomeHolder(Registry<Biome> registry, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Identifier identifier = Identifier.tryParse(key);
        if (identifier == null) {
            return null;
        }
        Optional<Holder.Reference<Biome>> reference = registry.get(identifier);
        return reference.<Holder<Biome>>map((Holder.Reference<Biome> value) -> value).orElse(null);
    }

    private synchronized void resetVanillaSpawnBiomes() {
        vanillaSpawnBiomes.clear();
        mergedSpawnTables.clear();
        vanillaSpawnBiomesInitialized = false;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender,
                                                       StructureManager structureManager, ChunkAccess chunk) {
        chunk.fillBiomesFromNoise(structureBiomeSource::getVisibleNoiseBiome, randomState.sampler());
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        Engine generationEngine = engine();
        ChunkPos pos = chunk.getPos();
        lastChunkGenAt = System.currentTimeMillis();
        if (announced.compareAndSet(false, true)) {
            LOGGER.info("Iris generating {} through IrisModdedChunkGenerator (dim={} first chunk {},{})",
                    dimensionKey, generationEngine.getDimension().getLoadKey(), pos.x(), pos.z());
        }
        LOGGER.debug("Iris generating chunk {},{}", pos.x(), pos.z());

        int dimMinY = generationEngine.getMinHeight();
        int dimMaxY = generationEngine.getMaxHeight();
        int height = dimMaxY - dimMinY;
        PlatformBlockState air = IrisPlatforms.get().registries().air();

        if (PARALLEL_CHUNK_SYSTEM) {
            return CompletableFuture.completedFuture(
                    generateTerrain(chunk, generationEngine, pos, dimMinY, height, air));
        }
        return CompletableFuture.supplyAsync(
                () -> generateTerrain(chunk, generationEngine, pos, dimMinY, height, air),
                genPool);
    }

    private ChunkAccess generateTerrain(ChunkAccess chunk, Engine generationEngine, ChunkPos pos,
                                        int dimMinY, int height, PlatformBlockState air) {
        ModdedBlockBuffer blocks = new ModdedBlockBuffer(height, air);
        Hunk<PlatformBiome> biomes = Hunk.newArrayHunk(16, height, 16);
        try {
            generationEngine.generate(pos.getMinBlockX(), pos.getMinBlockZ(), blocks, biomes, false);
        } catch (GenerationSessionException e) {
            if (e.isExpectedTeardown()) {
                LOGGER.debug("Iris chunk {},{} skipped: engine sealed for hotload/teardown", pos.x(), pos.z());
                return chunk;
            }
            LOGGER.error("Iris failed to generate chunk {},{}", pos.x(), pos.z(), e);
            throw new IllegalStateException("Iris generation failed for chunk " + pos.x() + "," + pos.z(), e);
        } catch (Throwable e) {
            LOGGER.error("Iris failed to generate chunk {},{}", pos.x(), pos.z(), e);
            throw new IllegalStateException("Iris generation failed for chunk " + pos.x() + "," + pos.z(), e);
        }

        writeBlocks(chunk, blocks, dimMinY, height);
        writeTerrainHeightmaps(chunk, generationEngine, pos, height);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        ModdedWorldManager.enqueueGenerated(generationEngine, pos.x(), pos.z());
        return chunk;
    }

    private void writeTerrainHeightmaps(ChunkAccess chunk, Engine generationEngine, ChunkPos pos, int height) {
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        writeTerrainHeightmap(chunk, Heightmap.Types.WORLD_SURFACE_WG, height,
                (x, z) -> generationEngine.getHeight(baseX + x, baseZ + z, false) + 1);
        writeTerrainHeightmap(chunk, Heightmap.Types.OCEAN_FLOOR_WG, height,
                (x, z) -> generationEngine.getHeight(baseX + x, baseZ + z, true) + 1);
    }

    private void writeTerrainHeightmap(ChunkAccess chunk, Heightmap.Types type, int height,
                                       IntBinaryOperator heightResolver) {
        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(type);
        heightmap.setRawData(chunk, type, ModdedHeightmaps.terrainRawData(height, heightResolver));
    }

    public BiomeResolver regenBiomeResolver() {
        engine();
        return structureBiomeSource::getVisibleNoiseBiome;
    }

    private void writeBlocks(ChunkAccess chunk, ModdedBlockBuffer blocks, int dimMinY, int height) {
        int chunkMinY = chunk.getMinY();
        int chunkMaxY = chunkMinY + chunk.getHeight();
        int from = Math.max(dimMinY, chunkMinY);
        int to = Math.min(dimMinY + height, chunkMaxY);
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        for (int y = from; y < to; ) {
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection section = chunk.getSection(sectionIndex);
            int sectionMinY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            int sectionEnd = Math.min(sectionMinY + 16, to);
            section.acquire();
            try {
                for (int blockY = y; blockY < sectionEnd; blockY++) {
                    int bufferY = blockY - dimMinY;
                    int localY = blockY & 15;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (blocks.isAir(x, bufferY, z)) {
                                continue;
                            }
                            PlatformBlockState state = blocks.getRaw(x, bufferY, z);
                            BlockState blockState = (BlockState) state.nativeHandle();
                            section.setBlockState(x, localY, z, blockState, false);
                            if (blockState.hasBlockEntity()) {
                                createDefaultBlockEntity(chunk, new BlockPos(baseX + x, blockY, baseZ + z), blockState);
                            }
                        }
                    }
                }
            } finally {
                section.release();
            }
            y = sectionEnd;
        }
    }

    static void createDefaultBlockEntity(ChunkAccess chunk, BlockPos position, BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return;
        }
        BlockEntity blockEntity = entityBlock.newBlockEntity(position, state);
        if (blockEntity != null) {
            chunk.setBlockEntity(blockEntity);
        }
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        placeVanillaStructures(level, chunk, structureManager);
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState, StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templateManager, ResourceKey<Level> levelKey) {
        Engine current = engine();
        Map<Structure, StructureStart> previousStarts = new HashMap<>(chunk.getAllStarts());
        super.createStructures(registryAccess, structureState, structureManager, chunk, templateManager, levelKey);
        adjustGeneratedStructures(registryAccess, chunk, previousStarts, current);
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess chunk) {
        super.createReferences(level, structureManager, chunk);
    }

    private void adjustGeneratedStructures(RegistryAccess registryAccess, ChunkAccess chunk,
                                           Map<Structure, StructureStart> previousStarts, Engine current) {
        Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
        ChunkPos chunkPos = chunk.getPos();
        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
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
                decision = NativeStructureGenerationPolicy.resolve(current,
                        structureId, undergroundStep);
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "policy resolution", structureId, chunkPos.x(), chunkPos.z(), error);
            }
            if (!decision.generate()) {
                chunk.setStartForStructure(structure, StructureStart.INVALID_START);
                continue;
            }
            int offsetY;
            try {
                offsetY = NativeStructurePostProcessor.applyVerticalPlacement(
                        start,
                        structureId,
                        decision.yShift(),
                        getSeaLevel(),
                        chunk.getMinY(),
                        chunk.getMinY() + chunk.getHeight(),
                        undergroundStep,
                        (x, z) -> current.getHeight(x, z, true) + current.getMinHeight());
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "vertical adjustment", structureId, chunkPos.x(), chunkPos.z(), error);
            }
            recordWorldCheckStructureShift(structureId, start.getChunkPos(), offsetY);
        }
    }

    private void placeVanillaStructures(WorldGenLevel world, ChunkAccess chunk, StructureManager structureManager) {
        if (!structureManager.shouldGenerateStructures()) {
            ChunkPos disabledChunk = chunk.getPos();
            throw new IllegalStateException("Iris cannot generate native structures in chunk "
                    + disabledChunk.x() + "," + disabledChunk.z()
                    + " because generate-structures=false disables them outside the pack; set generate-structures=true, "
                    + "restart the server, and deny individual structures through importedStructures.disabled");
        }
        ChunkPos chunkPos = chunk.getPos();
        SectionPos sectionPos = SectionPos.of(chunkPos, world.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<List<Structure>> byStep = structuresByStep(registry);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(world.getSeed(), origin.getX(), origin.getZ());
        BoundingBox area = writableArea(chunk);
        int steps = GenerationStep.Decoration.values().length;
        Engine current = engine();
        List<NativePlacementGroup> placementGroups = new ArrayList<>();
        List<StructureStart> nativeStarts = new ArrayList<>();
        List<NativeStructurePostProcessor.VegetationTarget> vegetationTargets = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            int index = 0;
            for (Structure structure : byStep.get(step)) {
                Identifier id = registry.getKey(structure);
                String structureId = id == null ? null : id.toString();
                if (structureId == null) {
                    throw NativeStructureGenerationException.failure(
                            "resolution", null, chunkPos.x(), chunkPos.z());
                }
                try {
                    IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(current,
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
                    (x, z) -> current.getHeight(x, z, true) + current.getMinHeight());
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
            random.setFeatureSeed(decorationSeed, group.featureIndex(), group.step());
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

    private void placeVanillaStructure(WorldGenLevel world, StructureManager structureManager,
                                       WorldgenRandom random, BoundingBox area, ChunkPos chunkPos,
                                       String structureId, StructureStart start,
                                       IrisNativeStructureDecision decision) {
        NativeStructurePostProcessor.place(world, structureManager, this, random, area, chunkPos,
                structureId, start, decision, this::resolveStiltBlock,
                (x, z) -> engine().getHeight(x, z, true) + engine().getMinHeight());
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

    private void recordWorldCheckStructureShift(String structureId, ChunkPos startChunk, int offsetY) {
        if (!WORLD_CHECK_ENABLED || structureId == null) {
            return;
        }
        if (worldCheckStructureShifts.size() >= WORLD_CHECK_SHIFT_RECORD_LIMIT) {
            worldCheckStructureShifts.clear();
        }
        worldCheckStructureShifts.put(new NativeStructureStartKey(structureId, startChunk.pack()), offsetY);
    }

    Integer worldCheckStructureShift(String structureId, ChunkPos startChunk) {
        if (structureId == null || startChunk == null) {
            return null;
        }
        return worldCheckStructureShifts.get(new NativeStructureStartKey(structureId, startChunk.pack()));
    }

    private BlockState resolveStiltBlock(IrisStructureStiltSettings settings, RNG rng,
                                         int x, int y, int z) {
        if (settings.getPalette() == null) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        PlatformBlockState platformState = settings.getPalette().get(rng, x, y, z, engine().getData());
        if (platformState == null || !(platformState.nativeHandle() instanceof BlockState blockState)) {
            throw new IllegalStateException("Configured native structure stilt palette did not resolve a Minecraft block at "
                    + x + "," + y + "," + z);
        }
        return blockState;
    }

    private BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight() - 1;
        return new BoundingBox(minX, minY, minZ, minX + 15, maxY, minZ + 15);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public int getGenDepth() {
        Engine current = engine;
        return current == null || current.isClosed()
                ? configuredPack().metadata().depth()
                : current.getMaxHeight() - current.getMinHeight();
    }

    @Override
    public int getSeaLevel() {
        Engine current = engine;
        return current == null || current.isClosed()
                ? configuredPack().metadata().seaLevel()
                : current.getMinHeight() + current.getDimension().getFluidHeight();
    }

    @Override
    public int getMinY() {
        Engine current = engine;
        return current == null || current.isClosed()
                ? configuredPack().metadata().minY()
                : current.getMinHeight();
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
        return clampSpawnHeight(heightAccessor.getMinY(), heightAccessor.getHeight());
    }

    static int clampSpawnHeight(int minY, int height) {
        int minimum = minY + 1;
        int maximum = minY + height - 2;
        return Math.max(minimum, Math.min(maximum, 96));
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
        Engine current = requireDataQueryEngine("base height");
        boolean ignoreFluid = !type.isOpaque().test(Blocks.WATER.defaultBlockState());
        return heightAccessor.getMinY() + current.getHeight(x, z, ignoreFluid) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        int minY = heightAccessor.getMinY();
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        Engine current = requireDataQueryEngine("base column");
        BlockState airState = Blocks.AIR.defaultBlockState();
        int surface = current.getHeight(x, z, true);
        int fluid = current.getHeight(x, z, false);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        int solidEnd = Math.max(0, Math.min(states.length, surface + 1));
        int fluidEnd = Math.max(solidEnd, Math.max(0, Math.min(states.length, fluid + 1)));
        Arrays.fill(states, 0, solidEnd, stone);
        Arrays.fill(states, solidEnd, fluidEnd, water);
        Arrays.fill(states, fluidEnd, states.length, airState);
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Iris dimension: " + dimensionKey);
    }

    private record SpawnTableKey(Biome biome, MobCategory category) {
    }

    private record StructureStepCache(Registry<Structure> registry, List<List<Structure>> structures) {
    }

    private record NativeStructureStartKey(String structureId, long chunkPosition) {
    }

    private record NativePlacementGroup(String structureId, IrisNativeStructureDecision decision,
                                        int featureIndex, int step, List<StructureStart> starts) {
    }

    record DimensionMetadata(int minY, int maxY, int seaLevel) {
        int depth() {
            return maxY - minY;
        }
    }

    private record ConfiguredPack(IrisData data, IrisDimension dimension, DimensionMetadata metadata) {
    }

    static final class EngineBinding<T> {
        private final long timeout;
        private final TimeUnit timeoutUnit;
        private volatile CompletableFuture<T> future = new CompletableFuture<>();

        EngineBinding(long timeout, TimeUnit timeoutUnit) {
            this.timeout = timeout;
            this.timeoutUnit = timeoutUnit;
        }

        T await(String dimensionKey) {
            try {
                return future.get(timeout, timeoutUnit);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Iris generator '"
                        + dimensionKey + "' to bind", error);
            } catch (ExecutionException error) {
                throw new IllegalStateException("Iris generator '" + dimensionKey + "' failed to bind",
                        error.getCause());
            } catch (TimeoutException error) {
                throw new IllegalStateException("Timed out waiting for Iris generator '"
                        + dimensionKey + "' to bind", error);
            }
        }

        void complete(T value) {
            future.complete(value);
        }

        void fail(Throwable error) {
            future.completeExceptionally(error);
        }

        void throwIfFailed(String dimensionKey) {
            CompletableFuture<T> current = future;
            if (!current.isCompletedExceptionally()) {
                return;
            }
            try {
                current.join();
            } catch (CompletionException error) {
                throw new IllegalStateException("Iris generator '" + dimensionKey + "' failed to bind",
                        error.getCause());
            }
        }

        void reset() {
            future = new CompletableFuture<>();
        }
    }
}

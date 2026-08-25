/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.engine;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.image.IrisImageMapRuntime;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDecorationPart;
import art.arcane.iris.engine.object.IrisDecorator;
import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.iris.engine.object.IrisInterpolator;
import art.arcane.iris.engine.object.IrisMaterialPalette;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverCaves;
import art.arcane.iris.engine.object.IrisRiverDeepPools;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.iris.engine.object.IrisRiverRoutingPolicy;
import art.arcane.iris.engine.object.IrisRiverWater;
import art.arcane.iris.engine.object.IrisShapedGeneratorStyle;
import art.arcane.iris.engine.river.runtime.IrisRiverRuntime;
import art.arcane.iris.engine.river.runtime.IrisRiverRuntimeContext;
import art.arcane.iris.engine.river.runtime.IrisRiverSurfaceSample;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.cave.RiverCaveFluidKind;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.data.DataProvider;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.project.interpolation.NoiseBounds;
import art.arcane.iris.util.project.interpolation.NoiseBoundsProvider;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.iris.util.project.stream.interpolation.Interpolated;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Data
@EqualsAndHashCode(exclude = {"data", "gridBoundsCache", "frozenInterpolators", "frozenGenerators", "riverRuntime", "imageMapRuntime"})
@ToString(exclude = {"data", "gridBoundsCache", "frozenInterpolators", "frozenGenerators", "riverRuntime", "imageMapRuntime"})
public class IrisComplex implements DataProvider {
    private static final NoiseBounds ZERO_NOISE_BOUNDS = new NoiseBounds(0D, 0D);
    private static final AtomicLong lastBoundsFailureLog = new AtomicLong(0L);
    private static final int GRID_BOUNDS_CACHE_SIZE = 8192;
    private static final int HEIGHT_BOUNDS_GRID = 4;
    @Getter(AccessLevel.NONE)
    private final transient ThreadLocal<GridBoundsCache> gridBoundsCache = ThreadLocal.withInitial(GridBoundsCache::new);
    /**
     * Immutable snapshot of {@link #generators} taken once at the end of construction, in the exact
     * iteration order the map produces. The per-column height paths walk these arrays instead of
     * allocating map/set iterators, and the frozen order keeps the floating point accumulation order
     * identical to the map iteration it replaces. Mutating {@link #generators} after construction is
     * not reflected here.
     */
    @Getter(AccessLevel.NONE)
    private final transient IrisInterpolator[] frozenInterpolators;
    @Getter(AccessLevel.NONE)
    private final transient IrisGenerator[][] frozenGenerators;
    private RNG rng;
    private double fluidHeight;
    private IrisData data;
    private Map<IrisInterpolator, Set<IrisGenerator>> generators;
    private ProceduralStream<IrisRegion> regionStream;
    private ProceduralStream<Double> regionStyleStream;
    private ProceduralStream<Double> regionIdentityStream;
    private ProceduralStream<UUID> regionIDStream;
    private ProceduralStream<InferredType> bridgeStream;
    private ProceduralStream<IrisBiome> landBiomeStream;
    private ProceduralStream<IrisBiome> caveBiomeStream;
    private ProceduralStream<IrisBiome> seaBiomeStream;
    private ProceduralStream<IrisBiome> shoreBiomeStream;
    private ProceduralStream<IrisBiome> baseBiomeStream;
    private ProceduralStream<UUID> baseBiomeIDStream;
    private ProceduralStream<IrisBiome> naturalTrueBiomeStream;
    private ProceduralStream<IrisBiome> trueBiomeStream;
    private ProceduralStream<PlatformBiome> trueBiomeDerivativeStream;
    private ProceduralStream<Double> naturalHeightStream;
    private ProceduralStream<Double> heightStream;
    private ProceduralStream<Integer> roundedHeighteightStream;
    private ProceduralStream<Double> maxHeightStream;
    private ProceduralStream<Double> overlayStream;
    private ProceduralStream<Double> heightFluidStream;
    private ProceduralStream<Double> naturalSlopeStream;
    private ProceduralStream<Double> slopeStream;
    private ProceduralStream<IrisRiverSurfaceSample> riverSurfaceStream;
    private ProceduralStream<Double> riverDistanceStream;
    private ProceduralStream<Double> riverFlowStream;
    private ProceduralStream<Double> riverCarveWeightStream;
    private ProceduralStream<Double> riverWaterSurfaceStream;
    private ProceduralStream<Integer> topSurfaceStream;
    private ProceduralStream<IrisDecorator> terrainSurfaceDecoration;
    private ProceduralStream<IrisDecorator> terrainCeilingDecoration;
    private ProceduralStream<IrisDecorator> terrainCaveSurfaceDecoration;
    private ProceduralStream<IrisDecorator> terrainCaveCeilingDecoration;
    private ProceduralStream<IrisDecorator> seaSurfaceDecoration;
    private ProceduralStream<IrisDecorator> seaFloorDecoration;
    private ProceduralStream<IrisDecorator> shoreSurfaceDecoration;
    private ProceduralStream<PlatformBlockState> rockStream;
    private ProceduralStream<PlatformBlockState> fluidStream;
    private ProceduralStream<PlatformBlockState> riverFluidStream;
    private ProceduralStream<PlatformBlockState> riverDeepPoolFluidStream;
    private IrisBiome focusBiome;
    private IrisRegion focusRegion;
    private Map<IrisInterpolator, IdentityHashMap<IrisBiome, GeneratorBounds>> generatorBounds;
    private Set<IrisBiome> generatorBiomes;
    private IrisRiverRuntime riverRuntime;
    private transient IrisImageMapRuntime imageMapRuntime;
    // Copy-on-write: reads happen per column on every burst thread; the synchronizedMap
    // monitor was taken on every HIT. Writes are once per biome and bounded, so a fresh map
    // per insert is cheap. Identity keying is load-bearing (IrisBiome is mutable/value-hashed).
    private volatile IdentityHashMap<IrisBiome, ChildSelectionPlan> childSelectionPlans = new IdentityHashMap<>();
    private final Object childSelectionPlanLock = new Object();

    public IrisComplex(Engine engine) {
        this(engine, false);
    }

    public IrisComplex(Engine engine, boolean simple) {
        int cacheSize = IrisSettings.get().getPerformance().getNoiseCacheSize();
        IrisBiome emptyBiome = new IrisBiome().setInferredType(InferredType.CAVE);
        UUID focusUUID = UUID.nameUUIDFromBytes("focus".getBytes());
        this.rng = new RNG(engine.getSeedManager().getComplex());
        this.data = engine.getData();
        imageMapRuntime = IrisImageMapRuntime.compile(engine);
        double height = engine.getMaxHeight();
        fluidHeight = engine.getDimension().getFluidHeight();
        generators = new HashMap<>();
        generatorBiomes = Collections.newSetFromMap(new IdentityHashMap<>());
        focusBiome = engine.getFocus();
        focusRegion = engine.getFocusRegion();
        Map<InferredType, ProceduralStream<IrisBiome>> inferredStreams = new HashMap<>();

        if (focusBiome != null) {
            focusBiome = focusBiome.withInferredType(InferredType.LAND);
            focusRegion = findRegion(focusBiome, engine);
        }

        //@builder
        if (focusRegion != null) {
            prepareInferredBiomes(focusRegion);
            focusRegion.getNaturalBiomes(this).forEach(this::registerGenerators);
        } else {
            engine.getDimension().getRegions().forEach(regionKey -> {
                IrisRegion region = data.getRegionLoader().load(regionKey);
                if (region == null) {
                    return;
                }
                prepareInferredBiomes(region);
                region.getNaturalBiomes(this).forEach(this::registerGenerators);
            });
            for (IrisRegion region : imageMapRuntime.getMappedRegions()) {
                prepareInferredBiomes(region);
                region.getNaturalBiomes(this).forEach(this::registerGenerators);
            }
            imageMapRuntime.getMappedBiomes().forEach(this::registerGenerators);
        }
        int interpolatorCount = generators.size();
        frozenInterpolators = new IrisInterpolator[interpolatorCount];
        frozenGenerators = new IrisGenerator[interpolatorCount][];
        int frozenIndex = 0;
        for (Map.Entry<IrisInterpolator, Set<IrisGenerator>> entry : generators.entrySet()) {
            frozenInterpolators[frozenIndex] = entry.getKey();
            frozenGenerators[frozenIndex] = entry.getValue().toArray(new IrisGenerator[0]);
            frozenIndex++;
        }
        generatorBounds = buildGeneratorBounds(engine);
        KList<IrisShapedGeneratorStyle> overlayNoise = engine.getDimension().getOverlayNoise();
        overlayStream = overlayNoise.isEmpty()
                ? ProceduralStream.ofDouble((x, z) -> 0.0D)
                : ProceduralStream.ofDouble((x, z) -> {
            double value = 0D;

            for (IrisShapedGeneratorStyle style : overlayNoise) {
                value += style.get(rng, getData(), x, z);
            }

            return value;
        });
        rockStream = engine.getDimension().getRockPalette().getLayerGenerator(rng.nextParallelRNG(45), data).stream()
                .select(engine.getDimension().getRockPalette().getBlockData(data));
        fluidStream = engine.getDimension().getFluidPalette().getLayerGenerator(rng.nextParallelRNG(78), data).stream()
                .select(engine.getDimension().getFluidPalette().getBlockData(data));
        IrisRiverNetwork configuredRivers = engine.getDimension().getRivers();
        IrisRiverWater configuredRiverWater = configuredRivers == null ? null : configuredRivers.getWater();
        riverFluidStream = configuredRivers != null && configuredRivers.isEnabled()
                ? configuredFluidStream(
                        Objects.requireNonNull(configuredRiverWater).getFluidPalette(),
                        rng.nextParallelRNG(79),
                        "River water"
                )
                : fluidStream;
        IrisRiverCaves configuredRiverCaves = configuredRivers == null ? null : configuredRivers.getCaves();
        IrisRiverDeepPools configuredDeepPools = configuredRiverCaves == null
                ? null
                : configuredRiverCaves.getDeepPools();
        riverDeepPoolFluidStream = configuredRivers != null
                && configuredRivers.isEnabled()
                && configuredDeepPools != null
                && configuredDeepPools.isEnabled()
                ? configuredFluidStream(
                        configuredDeepPools.getFluidPalette(),
                        rng.nextParallelRNG(80),
                        "River deep-pool"
                )
                : riverFluidStream;
        regionStyleStream = engine.getDimension().getRegionStyle().create(rng.nextParallelRNG(883), getData()).stream()
                .zoom(engine.getDimension().getRegionZoom());
        regionIdentityStream = regionStyleStream.fit(Integer.MIN_VALUE, Integer.MAX_VALUE);
        ProceduralStream<IrisRegion> proceduralRegionStream = focusRegion != null ?
                ProceduralStream.of((x, z) -> focusRegion,
                        Interpolated.of(a -> 0D, a -> focusRegion))
                : regionStyleStream
                .selectRarity(data.getRegionLoader().loadAll(engine.getDimension().getRegions()))
                .cache2D("regionStream", engine, cacheSize);
        regionStream = focusRegion != null ? proceduralRegionStream : proceduralRegionStream
                .convertAware2D((region, x, z) -> {
                    IrisRegion mapped = imageMapRuntime.sampleRegion(x, z);
                    return mapped == null ? region : mapped;
                })
                .cache2D("imageMappedRegionStream", engine, cacheSize);
        regionIDStream = regionIdentityStream.convertCached((i) -> new UUID(Double.doubleToLongBits(i),
                String.valueOf(i * 38445).hashCode() * 3245556666L));
        caveBiomeStream = regionStream.contextInjecting(engine, (c, x, z) -> c.getRegion().get(x, z))
                .convert((r)
                        -> engine.getDimension().getCaveBiomeStyle().create(rng.nextParallelRNG(InferredType.CAVE.ordinal()), getData()).stream()
                        .zoom(engine.getDimension().getBiomeZoom())
                        .zoom(r.getCaveBiomeZoom())
                        .selectRarity(loadInferredBiomes(r.getCaveBiomes(), InferredType.CAVE))
                        .onNull(emptyBiome)
                ).convertAware2D(ProceduralStream::get).cache2D("caveBiomeStream", engine, cacheSize);
        inferredStreams.put(InferredType.CAVE, caveBiomeStream);
        landBiomeStream = regionStream.contextInjecting(engine, (c, x, z) -> c.getRegion().get(x, z))
                .convert((r)
                        -> engine.getDimension().getLandBiomeStyle().create(rng.nextParallelRNG(InferredType.LAND.ordinal()), getData()).stream()
                        .zoom(engine.getDimension().getBiomeZoom())
                        .zoom(engine.getDimension().getLandZoom())
                        .zoom(r.getLandBiomeZoom())
                        .selectRarity(loadInferredBiomes(r.getLandBiomes(), InferredType.LAND))
                ).convertAware2D(ProceduralStream::get)
                .cache2D("landBiomeStream", engine, cacheSize);
        inferredStreams.put(InferredType.LAND, landBiomeStream);
        seaBiomeStream = regionStream.contextInjecting(engine, (c, x, z) -> c.getRegion().get(x, z))
                .convert((r)
                        -> engine.getDimension().getSeaBiomeStyle().create(rng.nextParallelRNG(InferredType.SEA.ordinal()), getData()).stream()
                        .zoom(engine.getDimension().getBiomeZoom())
                        .zoom(engine.getDimension().getSeaZoom())
                        .zoom(r.getSeaBiomeZoom())
                        .selectRarity(loadInferredBiomes(r.getSeaBiomes(), InferredType.SEA))
                ).convertAware2D(ProceduralStream::get)
                .cache2D("seaBiomeStream", engine, cacheSize);
        inferredStreams.put(InferredType.SEA, seaBiomeStream);
        shoreBiomeStream = regionStream.contextInjecting(engine, (c, x, z) -> c.getRegion().get(x, z))
                .convert((r)
                        -> engine.getDimension().getShoreBiomeStyle().create(rng.nextParallelRNG(InferredType.SHORE.ordinal()), getData()).stream()
                        .zoom(engine.getDimension().getBiomeZoom())
                        .zoom(r.getShoreBiomeZoom())
                        .selectRarity(loadInferredBiomes(r.getShoreBiomes(), InferredType.SHORE))
                ).convertAware2D(ProceduralStream::get).cache2D("shoreBiomeStream", engine, cacheSize);
        inferredStreams.put(InferredType.SHORE, shoreBiomeStream);
        bridgeStream = focusBiome != null ? ProceduralStream.of((x, z) -> focusBiome.getInferredType(),
                Interpolated.of(a -> 0D, a -> focusBiome.getInferredType())) :
                engine.getDimension().getContinentalStyle().create(rng.nextParallelRNG(234234565), getData())
                        .bake().scale(1D / engine.getDimension().getContinentZoom()).bake().stream()
                        .convert((v) -> v >= engine.getDimension().getLandChance() ? InferredType.SEA : InferredType.LAND)
                        .cache2D("bridgeStream", engine, cacheSize);
        ProceduralStream<IrisBiome> proceduralBaseBiomeStream = focusBiome != null ? ProceduralStream.of((x, z) -> focusBiome,
                Interpolated.of(a -> 0D, a -> focusBiome)) :
                bridgeStream.convertAware2D((t, x, z) -> inferredStreams.get(t).get(x, z))
                        .convertAware2D(this::implode)
                        .cache2D("baseBiomeStream", engine, cacheSize);
        baseBiomeStream = focusBiome != null ? proceduralBaseBiomeStream : proceduralBaseBiomeStream
                .convertAware2D((biome, x, z) -> {
                    IrisBiome mapped = imageMapRuntime.sampleBiome(x, z);
                    return mapped == null ? biome : mapped;
                })
                .cache2D("imageMappedBaseBiomeStream", engine, cacheSize);
        naturalHeightStream = ProceduralStream.of((x, z) -> {
            IrisBiome b = focusBiome != null ? focusBiome : baseBiomeStream.get(x, z);
            double proceduralHeight = getHeight(engine, b, x, z, engine.getSeedManager().getHeight());
            return imageMapRuntime.sampleTerrainHeight(x, z, proceduralHeight);
        }, Interpolated.DOUBLE).cache2DDouble("naturalHeightStream", engine, cacheSize);
        naturalSlopeStream = naturalHeightStream.slope(3)
                .cache2DDouble("naturalSlopeStream", engine, cacheSize);
        naturalTrueBiomeStream = focusBiome != null ? ProceduralStream.of((x, y) -> focusBiome, Interpolated.of(a -> 0D,
                        b -> focusBiome))
                .cache2D("naturalTrueBiomeStream-focus", engine, cacheSize) : naturalHeightStream
                .convertAware2D((h, x, z) -> {
                    IrisBiome mapped = imageMapRuntime.sampleBiome(x, z);
                    return mapped == null
                            ? fixBiomeType(h, baseBiomeStream.get(x, z), regionStream.get(x, z), x, z, fluidHeight)
                            : mapped;
                })
                .cache2D("naturalTrueBiomeStream", engine, cacheSize);
        if (engine.getDimension().getRivers() != null && engine.getDimension().getRivers().isEnabled()) {
            ProceduralStream<Boolean> naturalOceanStream = createNaturalOceanStream(
                    bridgeStream,
                    focusBiome
            ).cache2D("naturalOceanStream", engine, cacheSize);
            int riverFluidHeight = engine.getDimension().getRivers().getWater().getFluidHeight()
                    - engine.getDimension().getMinHeight();
            riverRuntime = new IrisRiverRuntime(new IrisRiverRuntimeContext(
                    engine.getSeedManager().getBodies(),
                    engine.getDimension().getRivers(),
                    data,
                    riverFluidHeight,
                    (int) Math.round(fluidHeight),
                    IrisEngineMantle.isRiverHydrologyEnabled(engine.getDimension()),
                    IrisEngineMantle.isRiverCaveHydrologyEnabled(engine.getDimension()),
                    blockingRiverRoutingPossible(engine),
                    variableMaxIncisionPossible(engine),
                    biomeRiverOverridesPossible(focusBiome, generatorBiomes),
                    (blockX, blockZ) -> naturalHeightBounds(engine, overlayNoise, blockX, blockZ),
                    naturalHeightStream,
                    naturalSlopeStream,
                    naturalOceanStream,
                    naturalTrueBiomeStream,
                    regionStream
            ));
            riverSurfaceStream = ProceduralStream.of(
                            (x, z) -> riverRuntime.sample(x, z),
                            Interpolated.of(
                                    IrisRiverSurfaceSample::terrainHeight,
                                    value -> IrisRiverSurfaceSample.none(value, fluidHeight)
                            )
                    )
                    .cache2D("riverSurfaceStream", engine, cacheSize);
        } else {
            riverSurfaceStream = naturalHeightStream.convert(
                            value -> IrisRiverSurfaceSample.none(value, fluidHeight)
                    )
                    .cache2D("riverSurfaceStream-disabled", engine, cacheSize);
        }
        heightStream = riverSurfaceStream.convert(IrisRiverSurfaceSample::terrainHeight)
                .cache2DDouble("heightStream", engine, cacheSize);
        roundedHeighteightStream = heightStream.contextInjecting(engine, (c, x, z) -> c.getHeight().getDouble(x, z))
                .round();
        slopeStream = heightStream.contextInjecting(engine, (c, x, z) -> c.getHeight().getDouble(x, z))
                .slope(3).cache2DDouble("slopeStream", engine, cacheSize);
        trueBiomeStream = focusBiome != null ? ProceduralStream.of((x, y) -> focusBiome, Interpolated.of(a -> 0D,
                        b -> focusBiome))
                .cache2D("trueBiomeStream-focus", engine, cacheSize) : riverSurfaceStream
                .convertAware2D((sample, x, z) -> resolveRiverSurfaceBiome(sample, x, z))
                .cache2D("trueBiomeStream", engine, cacheSize);
        trueBiomeDerivativeStream = trueBiomeStream.contextInjecting(engine, (c, x, z) -> c.getBiome().get(x, z))
                .convert((b) -> IrisPlatforms.get().registries().biome(b.getDerivativeKey())).cache2D("trueBiomeDerivativeStream", engine, cacheSize);
        riverDistanceStream = riverSurfaceStream.convert(sample -> sample.river().present()
                        ? sample.river().distance()
                        : Double.MAX_VALUE)
                .cache2DDouble("riverDistanceStream", engine, cacheSize);
        riverFlowStream = riverSurfaceStream.convert(sample -> (double) sample.river().flow())
                .cache2DDouble("riverFlowStream", engine, cacheSize);
        riverCarveWeightStream = riverSurfaceStream.convert(sample -> sample.river().carveWeight())
                .cache2DDouble("riverCarveWeightStream", engine, cacheSize);
        riverWaterSurfaceStream = riverSurfaceStream.convert(IrisRiverSurfaceSample::waterSurfaceY)
                .cache2DDouble("riverWaterSurfaceStream", engine, cacheSize);
        heightFluidStream = ProceduralStream.ofDouble((x, z) -> Math.max(
                        heightStream.get(x, z),
                        riverWaterSurfaceStream.get(x, z)
                ))
                .cache2DDouble("heightFluidStream", engine, cacheSize);
        maxHeightStream = ProceduralStream.ofDouble((x, z) -> height);
        terrainSurfaceDecoration = trueBiomeStream.contextInjecting(engine, (c, x, z) -> c.getBiome().get(x, z))
                .convertAware2D((b, xx, zz) -> decorateFor(b, xx, zz, IrisDecorationPart.NONE)).cache2D("terrainSurfaceDecoration", engine, cacheSize);
        terrainCeilingDecoration = trueBiomeStream.contextInjecting(engine, (c, x, z) -> c.getBiome().get(x, z))
                .convertAware2D((b, xx, zz) -> decorateFor(b, xx, zz, IrisDecorationPart.CEILING)).cache2D("terrainCeilingDecoration", engine, cacheSize);
        terrainCaveSurfaceDecoration = caveBiomeStream.contextInjecting(engine, (c, x, z) -> c.getCave().get(x, z))
                .convertAware2D((b, xx, zz) -> decorateFor(b, xx, zz, IrisDecorationPart.NONE)).cache2D("terrainCaveSurfaceDecoration", engine, cacheSize);
        terrainCaveCeilingDecoration = caveBiomeStream.contextInjecting(engine, (c, x, z) -> c.getCave().get(x, z))
                .convertAware2D((b, xx, zz) -> decorateFor(b, xx, zz, IrisDecorationPart.CEILING)).cache2D("terrainCaveCeilingDecoration", engine, cacheSize);
        shoreSurfaceDecoration = trueBiomeStream.contextInjecting(engine, (c, x, z) -> c.getBiome().get(x, z))
                .convertAware2D((b, xx, zz) -> decorateFor(b, xx, zz, IrisDecorationPart.SHORE_LINE)).cache2D("shoreSurfaceDecoration", engine, cacheSize);
        seaSurfaceDecoration = trueBiomeStream.contextInjecting(engine, (c, x, z) -> c.getBiome().get(x, z))
                .convertAware2D((b, xx, zz) -> decorateFor(b, xx, zz, IrisDecorationPart.SEA_SURFACE)).cache2D("seaSurfaceDecoration", engine, cacheSize);
        seaFloorDecoration = trueBiomeStream.contextInjecting(engine, (c, x, z) -> c.getBiome().get(x, z))
                .convertAware2D((b, xx, zz) -> decorateFor(b, xx, zz, IrisDecorationPart.SEA_FLOOR)).cache2D("seaFloorDecoration", engine, cacheSize);
        baseBiomeIDStream = trueBiomeStream.contextInjecting(engine, (c, x, z) -> c.getBiome().get(x, z))
                .convertAware2D((b, x, z) -> {
                    UUID d = regionIDStream.get(x, z);
                    return new UUID(b.getLoadKey().hashCode() * 818223L,
                            d.hashCode());
                })
                .cache2D("", engine, cacheSize);
        //@done
    }

    private boolean blockingRiverRoutingPossible(Engine engine) {
        if (blocksRiverRouting(focusRegion == null ? null : focusRegion.getRiverOverride())
                || blocksRiverRouting(focusBiome == null ? null : focusBiome.getRiverOverride())) {
            return true;
        }
        KList<IrisRegion> regions = engine.getDimension().getAllRegions(engine);
        for (IrisRegion loadedRegion : regions) {
            if (loadedRegion != null && blocksRiverRouting(loadedRegion.getRiverOverride())) {
                return true;
            }
        }
        KList<IrisBiome> biomes = engine.getDimension().getReachableBiomes(engine);
        for (IrisBiome biome : biomes) {
            if (biome != null && blocksRiverRouting(biome.getRiverOverride())) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocksRiverRouting(IrisRiverOverride override) {
        return override != null && override.getRoutingPolicy() == IrisRiverRoutingPolicy.BLOCK;
    }

    private boolean variableMaxIncisionPossible(Engine engine) {
        if (changesMaxIncision(focusRegion == null ? null : focusRegion.getRiverOverride())
                || changesMaxIncision(focusBiome == null ? null : focusBiome.getRiverOverride())) {
            return true;
        }
        KList<IrisRegion> regions = engine.getDimension().getAllRegions(engine);
        for (IrisRegion loadedRegion : regions) {
            if (loadedRegion != null && changesMaxIncision(loadedRegion.getRiverOverride())) {
                return true;
            }
        }
        KList<IrisBiome> biomes = engine.getDimension().getReachableBiomes(engine);
        for (IrisBiome biome : biomes) {
            if (biome != null && changesMaxIncision(biome.getRiverOverride())) {
                return true;
            }
        }
        return false;
    }

    static boolean changesMaxIncision(IrisRiverOverride override) {
        if (override == null || override.getMaxIncisionMultiplier() == null) {
            return false;
        }
        double multiplier = override.getMaxIncisionMultiplier();
        return Double.isFinite(multiplier) && Double.compare(Math.max(0D, multiplier), 1D) != 0;
    }

    static boolean biomeRiverOverridesPossible(IrisBiome focusBiome, Iterable<IrisBiome> naturalBiomes) {
        if (focusBiome != null) {
            return focusBiome.getRiverOverride() != null;
        }
        for (IrisBiome biome : naturalBiomes) {
            if (biome != null && biome.getRiverOverride() != null) {
                return true;
            }
        }
        return false;
    }

    static ProceduralStream<Boolean> createNaturalOceanStream(
            ProceduralStream<InferredType> bridgeStream,
            IrisBiome focusBiome
    ) {
        if (focusBiome != null) {
            boolean ocean = focusBiome.getInferredType() == InferredType.SEA;
            return ProceduralStream.of((x, z) -> ocean, Interpolated.BOOLEAN);
        }
        return bridgeStream.convert(type -> type == InferredType.SEA);
    }

    private ProceduralStream<PlatformBlockState> configuredFluidStream(
            IrisMaterialPalette palette,
            RNG fluidRng,
            String configurationName
    ) {
        Objects.requireNonNull(palette, configurationName + " fluidPalette must be configured");
        KList<PlatformBlockState> blocks = palette.getBlockData(data);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException(
                    configurationName + " fluidPalette must resolve at least one fluid block");
        }
        for (PlatformBlockState block : blocks) {
            if (block == null || !block.isFluid()) {
                throw new IllegalArgumentException(
                        configurationName + " fluidPalette may contain only fluid blocks");
            }
        }
        return palette.getLayerGenerator(fluidRng, data).stream().select(blocks);
    }

    public PlatformBlockState resolveRiverCaveFluid(RiverCaveFluidKind fluidKind, double x, double z) {
        return switch (Objects.requireNonNull(fluidKind)) {
            case RIVER -> riverFluidStream.get(x, z);
            case DEEP_POOL -> riverDeepPoolFluidStream.get(x, z);
        };
    }

    public PlatformBlockState resolveSurfaceFluid(double x, double z) {
        IrisRiverSurfaceSample sample = riverSurfaceStream.get(x, z);
        if (sample.river().present() && sample.surfaceFluid()) {
            return riverFluidStream.get(x, z);
        }
        return fluidStream.get(x, z);
    }

    public ProceduralStream<IrisBiome> getBiomeStream(InferredType type) {
        switch (type) {
            case CAVE:
                return caveBiomeStream;
            case LAND:
                return landBiomeStream;
            case SEA:
                return seaBiomeStream;
            case SHORE:
                return shoreBiomeStream;
            default:
                break;
        }

        return null;
    }

    public double sampleProceduralTerrainHeight(Engine engine, double worldX, double worldZ) {
        if (engine == null) {
            throw new IllegalArgumentException("Engine is required to sample procedural terrain height");
        }
        return getHeight(engine, null, worldX, worldZ, engine.getSeedManager().getHeight());
    }

    private IrisRegion findRegion(IrisBiome focus, Engine engine) {
        for (IrisRegion i : engine.getDimension().getAllRegions(engine)) {
            if (i.getAllBiomeIds().contains(focus.getLoadKey())) {
                return i;
            }
        }

        String key = UUID.randomUUID().toString();
        IrisRegion region = new IrisRegion();
        region.getLandBiomes().add(focus.getLoadKey());
        region.getSeaBiomes().add(focus.getLoadKey());
        region.getShoreBiomes().add(focus.getLoadKey());
        region.setLoadKey(key);
        region.setLoader(data);
        region.setLoadFile(new File(data.getDataFolder(), data.getRegionLoader().getFolderName() + "/" + key + ".json"));
        return region;
    }

    private IrisDecorator decorateFor(IrisBiome b, double x, double z, IrisDecorationPart part) {
        RNG rngc = new RNG(Cache.key(((int) x), ((int) z)));

        for (IrisDecorator i : b.getDecorators()) {
            if (!i.getPartOf().equals(part)) {
                continue;
            }

            PlatformBlockState block = i.getBlockData(b, rngc, x, z, data);

            if (block != null) {
                return i;
            }
        }

        return null;
    }

    private IrisBiome resolveRiverSurfaceBiome(IrisRiverSurfaceSample sample, double x, double z) {
        IrisBiome mapped = imageMapRuntime.sampleBiome(x, z);
        if (mapped != null && (riverRuntime == null || !sample.river().present())) {
            return mapped;
        }
        if (riverRuntime != null) {
            if (sample.subterranean()) {
                return fixBiomeType(
                        sample.naturalHeight(),
                        baseBiomeStream.get(x, z),
                        regionStream.get(x, z),
                        x,
                        z,
                        fluidHeight
                );
            }
            IrisBiome riverBiome = riverRuntime.selectSurfaceBiome(sample, x, z);
            if (riverBiome != null) {
                return implode(riverBiome, x, z);
            }
            InferredType directFallback = directRiverFallback(sample.river());
            if (directFallback != null) {
                IrisBiome baseBiome = baseBiomeStream.get(x, z);
                return implode(baseBiome.withInferredType(directFallback), x, z);
            }
            if (sample.river().present()
                    && sample.river().state() == RiverRouteState.WET
                    && sample.river().section() == RiverSection.BANK) {
                return fixBiomeType(
                        sample.terrainHeight(),
                        baseBiomeStream.get(x, z),
                        regionStream.get(x, z),
                        x,
                        z,
                        sample.waterSurfaceY()
                );
            }
        }
        return fixBiomeType(
                sample.terrainHeight(),
                baseBiomeStream.get(x, z),
                regionStream.get(x, z),
                x,
                z,
                fluidHeight
        );
    }

    static InferredType directRiverFallback(RiverSample river) {
        if (!river.present()) {
            return null;
        }
        if (river.state() == RiverRouteState.DRY) {
            return InferredType.LAND;
        }
        return switch (river.section()) {
            case CHANNEL, MOUTH -> InferredType.SEA;
            default -> null;
        };
    }

    private IrisBiome fixBiomeType(Double height, IrisBiome biome, IrisRegion region, Double x, Double z, double fluidHeight) {
        IrisBiome resolved = resolveSurfaceBiome(
                height,
                biome,
                region,
                x,
                z,
                fluidHeight,
                landBiomeStream,
                seaBiomeStream,
                shoreBiomeStream);
        return resolved == biome ? biome : implode(resolved, x, z);
    }

    static IrisBiome resolveSurfaceBiome(
            double height,
            IrisBiome biome,
            IrisRegion region,
            double x,
            double z,
            double fluidHeight,
            ProceduralStream<IrisBiome> landBiomes,
            ProceduralStream<IrisBiome> seaBiomes,
            ProceduralStream<IrisBiome> shoreBiomes
    ) {
        if (biome == null || region == null) {
            return biome;
        }
        double sh = region.getShoreHeight(x, z);

        if (height >= fluidHeight - 1 && height <= fluidHeight + sh && !biome.isShore()) {
            return shoreBiomes.get(x, z);
        }

        if (height > fluidHeight + sh && !biome.isLand()) {
            return landBiomes.get(x, z);
        }

        if (height < fluidHeight && !biome.isAquatic()) {
            return seaBiomes.get(x, z);
        }

        if (height == fluidHeight && !biome.isShore()) {
            return shoreBiomes.get(x, z);
        }

        return biome;
    }

    private double interpolateGenerators(Engine engine, IrisInterpolator interpolator, int interpolatorIndex, IrisGenerator[] generators, double x, double z, long seed) {
        if (generators.length == 0) {
            return 0;
        }

        NoiseBounds sampledBounds = gridSampleBounds(engine, interpolator, interpolatorIndex, generators, x, z);
        double hi = sampledBounds.max();
        double lo = sampledBounds.min();

        double d = 0;

        for (IrisGenerator i : generators) {
            d += M.lerp(lo, hi, i.getHeight(x, z, seed + 239945));
        }

        return d / generators.length;
    }

    private NoiseBounds gridSampleBounds(Engine engine, IrisInterpolator interpolator, int interpolatorIndex, IrisGenerator[] generators, double x, double z) {
        int grid = HEIGHT_BOUNDS_GRID;
        GridBoundsCache cache = gridBoundsCache.get();
        if (grid <= 1) {
            return sampleBoundsRaw(cache, engine, interpolator, generators, x, z);
        }

        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        int mask = grid - 1;
        int gx = xi & ~mask;
        int gz = zi & ~mask;
        double fx = (x - gx) / grid;
        double fz = (z - gz) / grid;

        long b00 = cornerBounds(cache, engine, interpolator, interpolatorIndex, generators, gx, gz);
        if (fx == 0D && fz == 0D) {
            return new NoiseBounds(boundsLow(b00), boundsHigh(b00));
        }

        long b10 = cornerBounds(cache, engine, interpolator, interpolatorIndex, generators, gx + grid, gz);
        long b01 = cornerBounds(cache, engine, interpolator, interpolatorIndex, generators, gx, gz + grid);
        long b11 = cornerBounds(cache, engine, interpolator, interpolatorIndex, generators, gx + grid, gz + grid);

        double lo = biLerp(boundsLow(b00), boundsLow(b10), boundsLow(b01), boundsLow(b11), fx, fz);
        double hi = biLerp(boundsHigh(b00), boundsHigh(b10), boundsHigh(b01), boundsHigh(b11), fx, fz);
        return new NoiseBounds(lo, hi);
    }

    private long cornerBounds(GridBoundsCache cache, Engine engine, IrisInterpolator interpolator, int interpolatorIndex, IrisGenerator[] generators, int gx, int gz) {
        int slot = cache.slot(gx, gz, interpolatorIndex);
        if (cache.valid[slot] && cache.gx[slot] == gx && cache.gz[slot] == gz && cache.idx[slot] == interpolatorIndex) {
            return cache.packed[slot];
        }

        NoiseBounds bounds = sampleBoundsRaw(cache, engine, interpolator, generators, gx, gz);
        long packed = (((long) Float.floatToRawIntBits((float) bounds.min())) << 32) | (Float.floatToRawIntBits((float) bounds.max()) & 0xFFFFFFFFL);
        cache.gx[slot] = gx;
        cache.gz[slot] = gz;
        cache.idx[slot] = interpolatorIndex;
        cache.packed[slot] = packed;
        cache.valid[slot] = true;
        return packed;
    }

    private static double boundsLow(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private static double boundsHigh(long packed) {
        return Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
    }

    private static double biLerp(double v00, double v10, double v01, double v11, double fx, double fz) {
        double a = v00 + ((v10 - v00) * fx);
        double b = v01 + ((v11 - v01) * fx);
        return a + ((b - a) * fz);
    }

    private NoiseBounds sampleBoundsRaw(GridBoundsCache cache, Engine engine, IrisInterpolator interpolator, IrisGenerator[] generators, double x, double z) {
        IdentityHashMap<IrisBiome, GeneratorBounds> cachedBounds = generatorBounds.get(interpolator);
        BoundsSampler sampler = cache.sampler.isInUse() ? new BoundsSampler() : cache.sampler;
        sampler.bind(this, engine, generators, cachedBounds);

        try {
            return interpolator.interpolateBounds(x, z, sampler);
        } finally {
            sampler.release();
        }
    }

    private double getInterpolatedHeight(Engine engine, double x, double z, long seed) {
        double h = 0;

        for (int interpolatorIndex = 0; interpolatorIndex < frozenInterpolators.length; interpolatorIndex++) {
            h += interpolateGenerators(engine, frozenInterpolators[interpolatorIndex], interpolatorIndex, frozenGenerators[interpolatorIndex], x, z, seed);
        }

        return h;
    }

    private NoiseBounds naturalHeightBounds(
            Engine engine,
            KList<IrisShapedGeneratorStyle> overlayNoise,
            double x,
            double z
    ) {
        double minimum = fluidHeight;
        double maximum = fluidHeight;
        for (int interpolatorIndex = 0; interpolatorIndex < frozenInterpolators.length; interpolatorIndex++) {
            NoiseBounds bounds = gridSampleBounds(
                    engine,
                    frozenInterpolators[interpolatorIndex],
                    interpolatorIndex,
                    frozenGenerators[interpolatorIndex],
                    x,
                    z
            );
            minimum += Math.min(bounds.min(), bounds.max());
            maximum += Math.max(bounds.min(), bounds.max());
        }
        for (IrisShapedGeneratorStyle style : overlayNoise) {
            minimum += Math.min(style.getMin(), style.getMax());
            maximum += Math.max(style.getMin(), style.getMax());
        }
        minimum = imageMapRuntime.sampleTerrainHeight(x, z, minimum);
        maximum = imageMapRuntime.sampleTerrainHeight(x, z, maximum);
        return new NoiseBounds(
                Math.max(0D, Math.min(engine.getHeight(), Math.min(minimum, maximum))),
                Math.max(0D, Math.min(engine.getHeight(), Math.max(minimum, maximum)))
        );
    }

    private double getHeight(Engine engine, IrisBiome b, double x, double z, long seed) {
        return Math.max(Math.min(getInterpolatedHeight(engine, x, z, seed) + fluidHeight + overlayStream.get(x, z), engine.getHeight()), 0);
    }

    private void prepareInferredBiomes(IrisRegion region) {
        loadInferredBiomes(region.getLandBiomes(), InferredType.LAND);
        loadInferredBiomes(region.getCaveBiomes(), InferredType.CAVE);
        loadInferredBiomes(region.getSeaBiomes(), InferredType.SEA);
        loadInferredBiomes(region.getShoreBiomes(), InferredType.SHORE);
    }

    private KList<IrisBiome> loadInferredBiomes(KList<String> keys, InferredType type) {
        KList<IrisBiome> inferred = new KList<>();
        for (IrisBiome biome : data.getBiomeLoader().loadAll(keys)) {
            inferred.add(biome.withInferredType(type));
        }
        return inferred;
    }

    private void registerGenerators(IrisBiome biome) {
        generatorBiomes.add(biome);
        biome.getGenerators().forEach(c -> registerGenerator(c.getCachedGenerator(this)));
    }

    private void registerGenerator(IrisGenerator cachedGenerator) {
        generators.computeIfAbsent(cachedGenerator.getInterpolator(), (k) -> new HashSet<>()).add(cachedGenerator);
    }

    private Map<IrisInterpolator, IdentityHashMap<IrisBiome, GeneratorBounds>> buildGeneratorBounds(Engine engine) {
        Map<IrisInterpolator, IdentityHashMap<IrisBiome, GeneratorBounds>> bounds = new HashMap<>();
        KList<IrisBiome> allBiomes = new KList<>(generatorBiomes);

        if (focusBiome != null && !allBiomes.contains(focusBiome)) {
            allBiomes.add(focusBiome);
        }

        for (int i = 0; i < frozenInterpolators.length; i++) {
            IdentityHashMap<IrisBiome, GeneratorBounds> interpolatorBounds = new IdentityHashMap<>(Math.max(allBiomes.size(), 16));
            for (IrisBiome biome : allBiomes) {
                interpolatorBounds.put(biome, computeGeneratorBounds(engine, frozenGenerators[i], biome));
            }
            bounds.put(frozenInterpolators[i], interpolatorBounds);
        }

        return bounds;
    }

    private GeneratorBounds computeGeneratorBounds(Engine engine, IrisGenerator[] generators, IrisBiome biome) {
        double min = 0D;
        double max = 0D;

        for (IrisGenerator gen : generators) {
            String key = gen.getLoadKey();
            if (key == null || key.isBlank()) {
                continue;
            }

            max += biome.getGenLinkMax(key, engine);
            min += biome.getGenLinkMin(key, engine);
        }

        return new GeneratorBounds(min, max);
    }

    private GeneratorBounds resolveGeneratorBounds(
            Engine engine,
            IrisGenerator[] generators,
            IrisBiome biome,
            IdentityHashMap<IrisBiome, GeneratorBounds> cachedBounds,
            IdentityHashMap<IrisBiome, GeneratorBounds> localBounds
    ) {
        GeneratorBounds bounds = cachedBounds == null ? null : cachedBounds.get(biome);
        if (bounds != null) {
            return bounds;
        }

        GeneratorBounds local = localBounds.get(biome);
        if (local != null) {
            return local;
        }

        GeneratorBounds computed = computeGeneratorBounds(engine, generators, biome);
        localBounds.put(biome, computed);
        return computed;
    }

    private IrisBiome implode(IrisBiome b, Double x, Double z) {
        if (b.getChildren().isEmpty()) {
            return b;
        }

        return implode(b, x, z, 3);
    }

    private IrisBiome implode(IrisBiome b, Double x, Double z, int max) {
        if (max < 0) {
            return b;
        }

        if (b.getChildren().isEmpty()) {
            return b;
        }

        CNG childCell = b.getChildrenGenerator(rng, 123, b.getChildShrinkFactor());
        ChildSelectionPlan childSelectionPlan = resolveChildSelectionPlan(b);
        IrisBiome biome = childSelectionPlan.select(childCell, x, z).withInferredType(b.getInferredType());
        return implode(biome, x, z, max - 1);
    }

    private ChildSelectionPlan resolveChildSelectionPlan(IrisBiome biome) {
        ChildSelectionPlan cachedPlan = childSelectionPlans.get(biome);
        if (cachedPlan != null) {
            return cachedPlan;
        }

        synchronized (childSelectionPlanLock) {
            ChildSelectionPlan synchronizedPlan = childSelectionPlans.get(biome);
            if (synchronizedPlan != null) {
                return synchronizedPlan;
            }

            KList<IrisBiome> children = biome.getRealChildren(this);
            KList<IrisBiome> options = new KList<>();
            for (IrisBiome child : children) {
                if (child != null) {
                    options.add(child);
                }
            }
            options.add(biome);

            ChildSelectionPlan createdPlan = ChildSelectionPlan.create(options);
            IdentityHashMap<IrisBiome, ChildSelectionPlan> next = new IdentityHashMap<>(childSelectionPlans);
            next.put(biome, createdPlan);
            childSelectionPlans = next;
            return createdPlan;
        }
    }

    private static class GeneratorBounds {
        private final double min;
        private final double max;
        private final NoiseBounds noiseBounds;

        private GeneratorBounds(double min, double max) {
            this.min = min;
            this.max = max;
            this.noiseBounds = new NoiseBounds(min, max);
        }
    }

    private static final class GridBoundsCache {
        private final int[] gx = new int[GRID_BOUNDS_CACHE_SIZE];
        private final int[] gz = new int[GRID_BOUNDS_CACHE_SIZE];
        private final int[] idx = new int[GRID_BOUNDS_CACHE_SIZE];
        private final long[] packed = new long[GRID_BOUNDS_CACHE_SIZE];
        private final boolean[] valid = new boolean[GRID_BOUNDS_CACHE_SIZE];
        private final BoundsSampler sampler = new BoundsSampler();

        private int slot(int cornerX, int cornerZ, int interpolatorIndex) {
            long h = (cornerX * 0x9E3779B97F4A7C15L) ^ (cornerZ * 0xC2B2AE3D27D4EB4FL) ^ (interpolatorIndex * 0x165667B19E3779F9L);
            h ^= (h >>> 32);
            return (int) (h & (GRID_BOUNDS_CACHE_SIZE - 1));
        }
    }

    /**
     * Reusable per-thread bounds provider. Holds the biome memo and the lazily computed generator
     * bounds for one sampleBoundsRaw pass so the pass allocates nothing; {@link #bind}
     * resets both, giving each pass the same empty-scratch semantics a fresh allocation had.
     * <p>
     * Single threaded and non reentrant by contract, matching the thread local sample caches in
     * IrisInterpolation that this provider is invoked through. If a nested pass ever does appear,
     * {@link #isInUse()} makes the caller fall back to a freshly allocated sampler.
     */
    private static final class BoundsSampler implements NoiseBoundsProvider {
        private final CoordinateBiomeCache sampleCache = new CoordinateBiomeCache(64);
        private final IdentityHashMap<IrisBiome, GeneratorBounds> localBounds = new IdentityHashMap<>(8);
        private IrisComplex complex;
        private Engine engine;
        private IrisGenerator[] generators;
        private IdentityHashMap<IrisBiome, GeneratorBounds> cachedBounds;
        private boolean inUse;

        private boolean isInUse() {
            return inUse;
        }

        private void bind(IrisComplex complex, Engine engine, IrisGenerator[] generators, IdentityHashMap<IrisBiome, GeneratorBounds> cachedBounds) {
            this.complex = complex;
            this.engine = engine;
            this.generators = generators;
            this.cachedBounds = cachedBounds;
            this.inUse = true;
            sampleCache.clear();

            if (!localBounds.isEmpty()) {
                localBounds.clear();
            }
        }

        private void release() {
            complex = null;
            engine = null;
            generators = null;
            cachedBounds = null;
            inUse = false;
        }

        @Override
        public NoiseBounds noise(double xx, double zz) {
            try {
                IrisBiome bx = sampleCache.get(xx, zz);
                if (bx == null) {
                    bx = complex.baseBiomeStream.get(xx, zz);
                    sampleCache.put(xx, zz, bx);
                }

                GeneratorBounds bounds = complex.resolveGeneratorBounds(engine, generators, bx, cachedBounds, localBounds);
                return bounds.noiseBounds;
            } catch (Throwable e) {
                long now = System.currentTimeMillis();
                long last = lastBoundsFailureLog.get();
                // The five second gate keeps this off the hot path; the once key keeps it out of a log
                // scan for the rest of the generation, where the same cause repeats for every column.
                if (now - last >= 5000L && lastBoundsFailureLog.compareAndSet(last, now)
                        && IrisLogging.warnOnce("biome-bounds:" + e.getClass().getName() + ":" + e.getMessage(),
                        "Failed to sample interpolated biome bounds at " + xx + " " + zz + ", flattening height to zero: " + e.getClass().getSimpleName() + ": " + e.getMessage())) {
                    IrisLogging.reportError(e);
                }
            }

            return ZERO_NOISE_BOUNDS;
        }
    }

    /**
     * Open addressed biome memo keyed on the packed coordinate bits, replacing a linear scan that was
     * quadratic in the number of columns a wide starcast touches. Single threaded by contract.
     */
    private static final class CoordinateBiomeCache {
        private long[] xBits;
        private long[] zBits;
        private IrisBiome[] values;
        private byte[] states;
        private int mask;
        private int resizeThreshold;
        private int size;

        private CoordinateBiomeCache(int initialCapacity) {
            int minimumCapacity = Math.max(8, initialCapacity);
            int tableSize = tableSizeFor((minimumCapacity << 1) + minimumCapacity);
            xBits = new long[tableSize];
            zBits = new long[tableSize];
            values = new IrisBiome[tableSize];
            states = new byte[tableSize];
            mask = tableSize - 1;
            resizeThreshold = Math.max(1, (tableSize * 3) >> 2);
            size = 0;
        }

        private void clear() {
            if (size == 0) {
                return;
            }

            Arrays.fill(states, (byte) 0);
            size = 0;
        }

        private IrisBiome get(double x, double z) {
            int slot = findSlot(Double.doubleToLongBits(x), Double.doubleToLongBits(z));
            return states[slot] == 0 ? null : values[slot];
        }

        private void put(double x, double z, IrisBiome biome) {
            long xb = Double.doubleToLongBits(x);
            long zb = Double.doubleToLongBits(z);
            int slot = findSlot(xb, zb);
            boolean occupied = states[slot] != 0;
            xBits[slot] = xb;
            zBits[slot] = zb;
            values[slot] = biome;
            states[slot] = 1;

            if (occupied) {
                return;
            }

            size++;
            if (size >= resizeThreshold) {
                grow();
            }
        }

        private int findSlot(long xb, long zb) {
            int slot = mix(xb, zb) & mask;
            while (states[slot] != 0) {
                if (xBits[slot] == xb && zBits[slot] == zb) {
                    break;
                }
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        private int mix(long xb, long zb) {
            long hash = xb * 0x9E3779B97F4A7C15L;
            hash ^= Long.rotateLeft(zb * 0xC2B2AE3D27D4EB4FL, 32);
            hash ^= (hash >>> 33);
            hash *= 0xff51afd7ed558ccdL;
            hash ^= (hash >>> 33);
            return (int) hash;
        }

        private void grow() {
            long[] previousXBits = xBits;
            long[] previousZBits = zBits;
            IrisBiome[] previousValues = values;
            byte[] previousStates = states;

            int nextLength = previousXBits.length << 1;
            xBits = new long[nextLength];
            zBits = new long[nextLength];
            values = new IrisBiome[nextLength];
            states = new byte[nextLength];
            mask = nextLength - 1;
            resizeThreshold = Math.max(1, (nextLength * 3) >> 2);
            size = 0;

            for (int i = 0; i < previousStates.length; i++) {
                if (previousStates[i] == 0) {
                    continue;
                }

                int slot = findSlot(previousXBits[i], previousZBits[i]);
                xBits[slot] = previousXBits[i];
                zBits[slot] = previousZBits[i];
                values[slot] = previousValues[i];
                states[slot] = 1;
                size++;
            }
        }

        private int tableSizeFor(int value) {
            int n = value - 1;
            n |= n >>> 1;
            n |= n >>> 2;
            n |= n >>> 4;
            n |= n >>> 8;
            n |= n >>> 16;
            int tableSize = n + 1;
            if (tableSize < 8) {
                return 8;
            }
            return tableSize;
        }
    }

    static final class ChildSelectionPlan {
        private final IrisBiome[] mappedBiomes;
        private final int maxIndex;

        private ChildSelectionPlan(IrisBiome[] mappedBiomes) {
            this.mappedBiomes = mappedBiomes;
            this.maxIndex = mappedBiomes.length - 1;
        }

        static ChildSelectionPlan create(KList<IrisBiome> options) {
            if (options.isEmpty()) {
                return new ChildSelectionPlan(new IrisBiome[0]);
            }

            int maxRarity = 1;
            for (IrisBiome biome : options) {
                if (biome != null && biome.getRarity() > maxRarity) {
                    maxRarity = biome.getRarity();
                }
            }

            int rarityMax = maxRarity + 1;
            boolean flip = false;
            KList<IrisBiome> mapped = new KList<>();
            for (IrisBiome biome : options) {
                if (biome == null) {
                    continue;
                }

                int rarity = Math.max(1, biome.getRarity());
                int count = rarityMax - rarity;
                for (int index = 0; index < count; index++) {
                    flip = !flip;
                    if (flip) {
                        mapped.add(biome);
                    } else {
                        mapped.add(0, biome);
                    }
                }
            }

            if (mapped.isEmpty()) {
                IrisBiome[] fallback = new IrisBiome[]{options.get(0)};
                return new ChildSelectionPlan(fallback);
            }

            IrisBiome[] mappedBiomes = mapped.toArray(new IrisBiome[0]);
            return new ChildSelectionPlan(mappedBiomes);
        }

        IrisBiome select(CNG childCell, double x, double z) {
            if (mappedBiomes.length == 0) {
                return null;
            }

            if (mappedBiomes.length == 1) {
                return mappedBiomes[0];
            }

            int selectedIndex = childCell.fit2D(0, maxIndex, x, z);
            if (selectedIndex < 0) {
                return mappedBiomes[0];
            }

            if (selectedIndex > maxIndex) {
                return mappedBiomes[maxIndex];
            }

            return mappedBiomes[selectedIndex];
        }
    }

    public void close() {
        if (riverRuntime != null) {
            riverRuntime.close();
        }
    }
}

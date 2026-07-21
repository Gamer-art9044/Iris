package art.arcane.iris.engine;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.iris.engine.object.IrisInterpolator;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisShapedGeneratorStyle;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.data.DataProvider;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.project.interpolation.IrisInterpolation.NoiseBounds;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.iris.util.project.stream.interpolation.Interpolated;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public class UpperDimensionContext implements DataProvider {
    private static final NoiseBounds ZERO_NOISE_BOUNDS = new NoiseBounds(0D, 0D);
    private final IrisDimension dimension;
    private final IrisData data;
    private final int chunkHeight;
    private final ProceduralStream<Double> heightStream;
    private final ProceduralStream<IrisBiome> biomeStream;
    private final ProceduralStream<IrisRegion> regionStream;
    private final ProceduralStream<PlatformBlockState> rockStream;
    private final boolean selfReferencing;

    private UpperDimensionContext(IrisDimension dimension, IrisData data, int chunkHeight,
                                 ProceduralStream<Double> heightStream,
                                 ProceduralStream<IrisBiome> biomeStream,
                                 ProceduralStream<IrisRegion> regionStream,
                                 ProceduralStream<PlatformBlockState> rockStream,
                                 boolean selfReferencing) {
        this.dimension = dimension;
        this.data = data;
        this.chunkHeight = chunkHeight;
        this.heightStream = heightStream;
        this.biomeStream = biomeStream;
        this.regionStream = regionStream;
        this.rockStream = rockStream;
        this.selfReferencing = selfReferencing;
    }

    public static UpperDimensionContext create(Engine engine, IrisDimension upperDim) {
        boolean selfRef = upperDim.getLoadKey().equals(engine.getDimension().getLoadKey());
        int chunkHeight = engine.getHeight();
        if (selfRef) {
            return createSelfReferencing(engine, chunkHeight);
        }
        return createCrossReferencing(engine, upperDim, chunkHeight);
    }

    private static UpperDimensionContext createSelfReferencing(Engine engine, int chunkHeight) {
        IrisComplex complex = engine.getComplex();
        return new UpperDimensionContext(
                engine.getDimension(),
                engine.getData(),
                chunkHeight,
                complex.getHeightStream(),
                complex.getTrueBiomeStream(),
                complex.getRegionStream(),
                complex.getRockStream(),
                true
        );
    }

    private static UpperDimensionContext createCrossReferencing(Engine engine, IrisDimension upperDim, int chunkHeight) {
        IrisData resolvedData = upperDim.getLoader();
        if (resolvedData == null) {
            resolvedData = engine.getData();
        }
        IrisData upperData = resolvedData;
        long seedOffset = upperDim.getLoadKey().hashCode();
        RNG rng = new RNG(engine.getSeedManager().getComplex() ^ seedOffset);
        double fluidHeight = upperDim.getFluidHeight();
        DataProvider dataProvider = () -> upperData;

        Map<IrisInterpolator, Set<IrisGenerator>> generators = new HashMap<>();
        Set<IrisBiome> allBiomes = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<IrisBiome, IrisComplex.ChildSelectionPlan> childSelectionPlans =
                Collections.synchronizedMap(new IdentityHashMap<>());
        upperDim.getRegions().forEach(regionKey -> {
            IrisRegion region = upperData.getRegionLoader().load(regionKey);
            if (region != null) {
                region.getAllBiomes(dataProvider).forEach(biome -> {
                    allBiomes.add(biome);
                    biome.getGenerators().forEach(link -> {
                        IrisGenerator gen = link.getCachedGenerator(dataProvider);
                        if (gen != null) {
                            generators.computeIfAbsent(gen.getInterpolator(), k -> new HashSet<>()).add(gen);
                        }
                    });
                });
            }
        });

        Map<IrisInterpolator, IdentityHashMap<IrisBiome, NoiseBounds>> generatorBounds = new HashMap<>();
        for (Map.Entry<IrisInterpolator, Set<IrisGenerator>> entry : generators.entrySet()) {
            IdentityHashMap<IrisBiome, NoiseBounds> interpolatorBounds = new IdentityHashMap<>(Math.max(allBiomes.size(), 16));
            for (IrisBiome biome : allBiomes) {
                double min = 0D;
                double max = 0D;
                for (IrisGenerator gen : entry.getValue()) {
                    String key = gen.getLoadKey();
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    max += biome.getGenLinkMax(key, engine);
                    min += biome.getGenLinkMin(key, engine);
                }
                interpolatorBounds.put(biome, new NoiseBounds(min, max));
            }
            generatorBounds.put(entry.getKey(), interpolatorBounds);
        }

        ProceduralStream<Double> regionStyleStream = upperDim.getRegionStyle()
                .create(rng.nextParallelRNG(883), upperData).stream()
                .zoom(upperDim.getRegionZoom());
        ProceduralStream<IrisRegion> regionStream = regionStyleStream
                .selectRarity(upperData.getRegionLoader().loadAll(upperDim.getRegions()));

        ProceduralStream<IrisBiome> landBiomeStream = regionStream
                .convert(r -> upperDim.getLandBiomeStyle()
                        .create(rng.nextParallelRNG(InferredType.LAND.ordinal()), upperData).stream()
                        .zoom(upperDim.getBiomeZoom())
                        .zoom(upperDim.getLandZoom())
                        .zoom(r.getLandBiomeZoom())
                        .selectRarity(loadInferredBiomes(upperData, r.getLandBiomes(), InferredType.LAND)))
                .convertAware2D(ProceduralStream::get);
        ProceduralStream<IrisBiome> seaBiomeStream = regionStream
                .convert(r -> upperDim.getSeaBiomeStyle()
                        .create(rng.nextParallelRNG(InferredType.SEA.ordinal()), upperData).stream()
                        .zoom(upperDim.getBiomeZoom())
                        .zoom(upperDim.getSeaZoom())
                        .zoom(r.getSeaBiomeZoom())
                        .selectRarity(loadInferredBiomes(upperData, r.getSeaBiomes(), InferredType.SEA)))
                .convertAware2D(ProceduralStream::get);
        ProceduralStream<IrisBiome> shoreBiomeStream = regionStream
                .convert(r -> upperDim.getShoreBiomeStyle()
                        .create(rng.nextParallelRNG(InferredType.SHORE.ordinal()), upperData).stream()
                        .zoom(upperDim.getBiomeZoom())
                        .zoom(r.getShoreBiomeZoom())
                        .selectRarity(loadInferredBiomes(upperData, r.getShoreBiomes(), InferredType.SHORE)))
                .convertAware2D(ProceduralStream::get);

        Map<InferredType, ProceduralStream<IrisBiome>> inferredStreams = new HashMap<>();
        inferredStreams.put(InferredType.LAND, landBiomeStream);
        inferredStreams.put(InferredType.SEA, seaBiomeStream);
        inferredStreams.put(InferredType.SHORE, shoreBiomeStream);

        ProceduralStream<InferredType> bridgeStream = upperDim.getContinentalStyle()
                .create(rng.nextParallelRNG(234234565), upperData)
                .bake().scale(1D / upperDim.getContinentZoom()).bake().stream()
                .convert(v -> v >= upperDim.getLandChance() ? InferredType.SEA : InferredType.LAND);

        ProceduralStream<IrisBiome> baseBiomeStream = bridgeStream
                .convertAware2D((t, x, z) -> {
                    ProceduralStream<IrisBiome> stream = inferredStreams.get(t);
                    return stream != null ? stream.get(x, z) : inferredStreams.get(InferredType.LAND).get(x, z);
                })
                .convertAware2D((biome, x, z) -> implode(
                        biome, x, z, rng, dataProvider, childSelectionPlans, 3));

        KList<IrisShapedGeneratorStyle> overlayNoise = upperDim.getOverlayNoise();
        ProceduralStream<Double> overlayStream = overlayNoise.isEmpty()
                ? ProceduralStream.ofDouble((x, z) -> 0.0D)
                : ProceduralStream.ofDouble((x, z) -> {
            double value = 0D;
            for (IrisShapedGeneratorStyle style : overlayNoise) {
                value += style.get(rng, upperData, x, z);
            }
            return value;
        });

        long heightSeed = engine.getSeedManager().getHeight() ^ seedOffset;

        ProceduralStream<Double> heightStream = ProceduralStream.of((x, z) -> {
            IrisBiome b = baseBiomeStream.get(x, z);
            if (b == null) {
                return fluidHeight;
            }
            double interpolatedHeight = 0;
            for (Map.Entry<IrisInterpolator, Set<IrisGenerator>> entry : generators.entrySet()) {
                IrisInterpolator interpolator = entry.getKey();
                Set<IrisGenerator> gens = entry.getValue();
                if (gens.isEmpty()) {
                    continue;
                }
                IdentityHashMap<IrisBiome, NoiseBounds> cachedBounds = generatorBounds.get(interpolator);
                NoiseBounds sampledBounds = interpolator.interpolateBounds(x, z, (xx, zz) -> {
                    try {
                        IrisBiome bx = baseBiomeStream.get(xx, zz);
                        if (bx == null) {
                            return ZERO_NOISE_BOUNDS;
                        }
                        NoiseBounds bounds = cachedBounds != null ? cachedBounds.get(bx) : null;
                        if (bounds != null) {
                            return bounds;
                        }
                        double bMin = 0D;
                        double bMax = 0D;
                        for (IrisGenerator gen : gens) {
                            String key = gen.getLoadKey();
                            if (key == null || key.isBlank()) {
                                continue;
                            }
                            bMax += bx.getGenLinkMax(key, engine);
                            bMin += bx.getGenLinkMin(key, engine);
                        }
                        return new NoiseBounds(bMin, bMax);
                    } catch (Throwable e) {
                        IrisLogging.reportError(e);
                        return ZERO_NOISE_BOUNDS;
                    }
                });
                double hi = sampledBounds.max();
                double lo = sampledBounds.min();
                double d = 0;
                for (IrisGenerator gen : gens) {
                    d += M.lerp(lo, hi, gen.getHeight(x, z, heightSeed + 239945));
                }
                interpolatedHeight += d / gens.size();
            }
            return Math.max(Math.min(interpolatedHeight + fluidHeight + overlayStream.get(x, z), chunkHeight), 0);
        }, Interpolated.DOUBLE);

        ProceduralStream<IrisBiome> finalBiomeStream = heightStream.convertAware2D((height, x, z) -> {
            IrisBiome baseBiome = baseBiomeStream.get(x, z);
            IrisBiome resolved = IrisComplex.resolveSurfaceBiome(
                    height,
                    baseBiome,
                    regionStream.get(x, z),
                    x,
                    z,
                    fluidHeight,
                    landBiomeStream,
                    seaBiomeStream,
                    shoreBiomeStream);
            return resolved == baseBiome
                    ? baseBiome
                    : implode(resolved, x, z, rng, dataProvider, childSelectionPlans, 3);
        });

        ProceduralStream<PlatformBlockState> rockStream = upperDim.getRockPalette()
                .getLayerGenerator(rng.nextParallelRNG(45), upperData).stream()
                .select(upperDim.getRockPalette().getBlockData(upperData));

        return new UpperDimensionContext(
                upperDim,
                upperData,
                chunkHeight,
                heightStream,
                finalBiomeStream,
                regionStream,
                rockStream,
                false
        );
    }

    private static KList<IrisBiome> loadInferredBiomes(IrisData data, KList<String> keys, InferredType type) {
        KList<IrisBiome> inferred = new KList<>();
        for (IrisBiome biome : data.getBiomeLoader().loadAll(keys)) {
            inferred.add(biome.withInferredType(type));
        }
        return inferred;
    }

    private static IrisBiome implode(
            IrisBiome biome,
            double x,
            double z,
            RNG rng,
            DataProvider dataProvider,
            Map<IrisBiome, IrisComplex.ChildSelectionPlan> childSelectionPlans,
            int remainingDepth
    ) {
        if (biome == null || remainingDepth < 0 || biome.getChildren().isEmpty()) {
            return biome;
        }

        IrisComplex.ChildSelectionPlan selectionPlan = childSelectionPlans.get(biome);
        if (selectionPlan == null) {
            synchronized (childSelectionPlans) {
                selectionPlan = childSelectionPlans.get(biome);
                if (selectionPlan == null) {
                    KList<IrisBiome> options = new KList<>();
                    for (IrisBiome child : biome.getRealChildren(dataProvider)) {
                        if (child != null) {
                            options.add(child);
                        }
                    }
                    options.add(biome);
                    selectionPlan = IrisComplex.ChildSelectionPlan.create(options);
                    childSelectionPlans.put(biome, selectionPlan);
                }
            }
        }

        IrisBiome selected = selectionPlan.select(
                biome.getChildrenGenerator(rng, 123, biome.getChildShrinkFactor()), x, z);
        if (selected == null) {
            return biome;
        }
        return implode(
                selected.withInferredType(biome.getInferredType()),
                x,
                z,
                rng,
                dataProvider,
                childSelectionPlans,
                remainingDepth - 1);
    }

    public int getUpperSurfaceY(int x, int z) {
        double rawHeight = heightStream.get((double) x, (double) z);
        return chunkHeight - 1 - (int) Math.round(rawHeight);
    }

    public IrisBiome getUpperBiome(int x, int z) {
        return biomeStream.get((double) x, (double) z);
    }

    public IrisRegion getUpperRegion(int x, int z) {
        return regionStream == null ? null : regionStream.get((double) x, (double) z);
    }

    public PlatformBlockState getRockBlock(int x, int z) {
        return rockStream.get((double) x, (double) z);
    }

    public IrisDimension getDimension() {
        return dimension;
    }

    @Override
    public IrisData getData() {
        return data;
    }

    public boolean isSelfReferencing() {
        return selfReferencing;
    }
}

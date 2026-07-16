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

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.framework.render.RenderType;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.DependsOn;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.iris.engine.object.annotations.Required;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.common.data.DataProvider;
import art.arcane.iris.util.common.data.registry.RegistryUtil;
import art.arcane.volmlib.util.data.VanillaBiomeColors;
import art.arcane.volmlib.util.inventorygui.RandomColor;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.project.context.IrisContext;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import art.arcane.iris.spi.PlatformBlockState;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;

import java.awt.Color;
import java.util.EnumMap;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Represents a biome in iris. Biomes are placed inside of regions and hold objects.\nA biome consists of layers (block palletes), decorations, objects & generators.")
@Data
@EqualsAndHashCode(callSuper = false)
public class IrisBiome extends IrisRegistrant implements IRare {
    private static final int BIOME_GENERATOR_CACHE_SIZE = 8;

    private static final class States {
        private static final PlatformBlockState BARRIER = B.getState("BARRIER");
    }

    private final transient AtomicCache<KMap<String, IrisBiomeGeneratorLink>> genCache = new AtomicCache<>();
    private final transient AtomicCache<KMap<String, Integer>> genCacheMax = new AtomicCache<>();
    private final transient AtomicCache<KMap<String, Integer>> genCacheMin = new AtomicCache<>();
    private final transient AtomicCache<KList<IrisObjectPlacement>> surfaceObjectsCache = new AtomicCache<>();
    private final transient AtomicCache<KList<IrisObjectPlacement>> carveObjectsCache = new AtomicCache<>();
    private final transient AtomicCache<Color> cacheColor = new AtomicCache<>();
    private final transient AtomicCache<Color> cacheColorObjectDensity = new AtomicCache<>();
    private final transient AtomicCache<Color> cacheColorDecoratorLoad = new AtomicCache<>();
    private final transient AtomicCache<Color> cacheColorLayerLoad = new AtomicCache<>();
    private final transient AtomicCache<Color> cacheColorDepositLoad = new AtomicCache<>();
    private final transient AtomicCache<CNG> childrenCell = new AtomicCache<>();
    @Getter(AccessLevel.NONE)
    private final transient ConcurrentLinkedHashMap<Long, CNG> biomeGenerators = new ConcurrentLinkedHashMap.Builder<Long, CNG>()
            .maximumWeightedCapacity(BIOME_GENERATOR_CACHE_SIZE)
            .build();
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient volatile SeededBiomeGenerator recentBiomeGenerator;
    private final transient AtomicCache<Integer> maxHeight = new AtomicCache<>();
    private final transient AtomicCache<Integer> maxWithObjectHeight = new AtomicCache<>();
    private final transient AtomicCache<IrisBiome> realCarveBiome = new AtomicCache<>();
    private final transient AtomicCache<KList<IrisBiome>> realChildren = new AtomicCache<>();
    private final transient AtomicCache<KList<CNG>> layerHeightGenerators = new AtomicCache<>();
    private final transient AtomicCache<KList<CNG>> layerSeaHeightGenerators = new AtomicCache<>();
    private final transient AtomicCache<KList<IrisOreGenerator>> surfaceOreCache = new AtomicCache<>();
    private final transient AtomicCache<KList<IrisOreGenerator>> undergroundOreCache = new AtomicCache<>();
    private final transient AtomicCache<IrisOreGeneratorBounds> surfaceOreBoundsCache = new AtomicCache<>();
    private final transient AtomicCache<IrisOreGeneratorBounds> undergroundOreBoundsCache = new AtomicCache<>();
    private final transient AtomicCache<EnumMap<IrisDecorationPart, IrisDecorator[]>> decoratorBuckets = new AtomicCache<>();
    private final transient AtomicCache<Biome> derivativeResolved = new AtomicCache<>();
    private final transient AtomicCache<Biome> vanillaDerivativeResolved = new AtomicCache<>();
    private final transient AtomicCache<KList<Biome>> biomeScatterResolved = new AtomicCache<>();
    private final transient AtomicCache<KList<Biome>> biomeSkyScatterResolved = new AtomicCache<>();
    private static final IrisDecorator[] EMPTY_BUCKET = new IrisDecorator[0];
    @MinNumber(2)
    @Required
    @Desc("This is the human readable name for this biome. This can and should be different than the file name. This is not used for loading biomes in other objects.")
    private String name = "Subterranean Land";
    @ArrayType(min = 1, type = IrisBiomeCustom.class)
    @Desc("If the biome type custom is defined, specify this")
    private KList<IrisBiomeCustom> customDerivitives;
    @Desc("Spawn Entities in this area over time. Iris will continually replenish these mobs just like vanilla does.")
    @ArrayType(min = 1, type = String.class)
    @RegistryListResource(IrisSpawner.class)
    private KList<String> entitySpawners = new KList<>();
    @ArrayType(min = 1, type = IrisEffect.class)
    @Desc("Effects are ambient effects such as potion effects, random sounds, or even particles around each player. All of these effects are played via packets so two players won't see/hear each others effects.\nDue to performance reasons, effects will play around the player even if where the effect was played is no longer in the biome the player is in.")
    private KList<IrisEffect> effects = new KList<>();
    @DependsOn({"biomeStyle", "biomeZoom", "biomeScatter"})
    @Desc("This changes the dispersion of the biome colors if multiple derivatives are chosen.")
    private IrisGeneratorStyle biomeStyle = NoiseStyle.SIMPLEX.style();
    @ArrayType(min = 1, type = IrisBlockDrops.class)
    @Desc("Define custom block drops for this biome")
    private KList<IrisBlockDrops> blockDrops = new KList<>();
    @Desc("Reference loot tables in this area")
    private IrisLootReference loot = new IrisLootReference();
    @Desc("Layers no longer descend from the surface block, they descend from the max possible height the biome can produce (constant) creating mesa like layers.")
    private boolean lockLayers = false;
    @Desc("The max layers to iterate below the surface for locked layer biomes (mesa).")
    private int lockLayersMax = 7;
    @Desc("Profile-driven 3D cave configuration")
    private IrisCaveProfile caveProfile = new IrisCaveProfile();
    @MinNumber(1)
    @MaxNumber(512)
    @Desc("The rarity of this biome (integer)")
    private int rarity = 1;
    @Desc("A color for visualizing this biome with a color. I.e. #F13AF5. This will show up on the map.")
    private String color = null;
    @Required
    @Desc("The raw derivative of this biome. This is required or the terrain will not properly generate. Use any vanilla biome type. Look in examples/biome-list.txt")
    private String derivative = "minecraft:the_void";
    @Required
    @Desc("Override the derivative used for vanilla structure selection. Iris still enforces the generated terrain role: land-only Minecraft derivatives on sea biomes expose no native structure biome, and land-only derivatives on shore biomes resolve as beach, while exact ocean, river, beach, and shore variants remain eligible. Non-Minecraft namespaces remain authoritative. Not defining this value selects derivative.")
    private String vanillaDerivative = null;
    @ArrayType(min = 1, type = String.class)
    @Desc("You can instead specify multiple biome derivatives to randomly scatter colors in this biome")
    private KList<String> biomeScatter = new KList<>();
    @ArrayType(min = 1, type = String.class)
    @Desc("Since 1.13 supports 3D biomes, you can add different derivative colors for anything above the terrain. (Think swampy tree leaves with a desert looking grass surface)")
    private KList<String> biomeSkyScatter = new KList<>();
    @DependsOn({"children"})
    @Desc("If this biome has children biomes, and the gen layer chooses one of this biomes children, how much smaller will it be (inside of this biome). Higher values means a smaller biome relative to this biome's size. Set higher than 1.0 and below 3.0 for best results.")
    private double childShrinkFactor = 1.5;
    @DependsOn({"children"})
    @Desc("If this biome has children biomes, and the gen layer chooses one of this biomes children, How will it be shaped?")
    private IrisGeneratorStyle childStyle = NoiseStyle.CELLULAR_IRIS_DOUBLE.style();
    @RegistryListResource(IrisBiome.class)
    @ArrayType(min = 1, type = String.class)
    @Desc("List any biome names (file names without.json) here as children. Portions of this biome can sometimes morph into their children. Iris supports cyclic relationships such as A > B > A > B. Iris will stop checking 9 biomes down the tree.")
    private KList<String> children = new KList<>();
    @RegistryListResource(IrisBiome.class)
    @Desc("The carving biome. If specified the biome will be used when under a carving instead of this current biome.")
    private String carvingBiome = "";
    @MinNumber(0)
    @MaxNumber(256)
    @Desc("Minimum depth below terrain surface required before this cave biome can be selected.")
    private int caveMinDepthBelowSurface = 0;
    @Desc("The default slab if iris decides to place a slab in this biome. Default is no slab.")
    private IrisBiomePaletteLayer slab = new IrisBiomePaletteLayer().zero();
    @Desc("The default wall if iris decides to place a wall higher than 2 blocks (steep hills or possibly cliffs)")
    private IrisBiomePaletteLayer wall = new IrisBiomePaletteLayer().zero();
    @Required
    @ArrayType(type = IrisBiomePaletteLayer.class)
    @Desc("This defines the layers of materials in this biome. Each layer has a palette and min/max height and some other properties. Usually a grassy/sandy layer then a dirt layer then a stone layer. Iris will fill in the remaining blocks below your layers with stone.")
    private KList<IrisBiomePaletteLayer> layers = new KList<IrisBiomePaletteLayer>().qadd(new IrisBiomePaletteLayer());
    @ArrayType(type = IrisBiomePaletteLayer.class)
    @Desc("This defines the layers of materials in this biome. Each layer has a palette and min/max height and some other properties. Usually a grassy/sandy layer then a dirt layer then a stone layer. Iris will fill in the remaining blocks below your layers with stone.")
    private KList<IrisBiomePaletteLayer> caveCeilingLayers = new KList<IrisBiomePaletteLayer>().qadd(new IrisBiomePaletteLayer());
    @ArrayType(type = IrisBiomePaletteLayer.class)
    @Desc("This defines the layers of materials in this biome. Each layer has a palette and min/max height and some other properties. Usually a grassy/sandy layer then a dirt layer then a stone layer. Iris will fill in the remaining blocks below your layers with stone.")
    private KList<IrisBiomePaletteLayer> seaLayers = new KList<>();
    @ArrayType(min = 1, type = IrisDecorator.class)
    @Desc("Decorators are used for things like tall grass, bisected flowers, and even kelp or cactus (random heights)")
    private KList<IrisDecorator> decorators = new KList<>();
    @ArrayType(min = 1, type = IrisObjectPlacement.class)
    @Desc("Objects define what schematics (iob files) iris will place in this biome")
    private KList<IrisObjectPlacement> objects = new KList<>();
    @Desc("Procedural objects (such as trees) iris generates from scratch and places in this biome, instead of loading them from iob files")
    private IrisProceduralObjects proceduralObjects = new IrisProceduralObjects();
    @ArrayType(min = 1, type = IrisStructurePlacement.class)
    @Desc("Structures define jigsaw or vanilla/datapack structures iris will place in this biome")
    private KList<IrisStructurePlacement> structures = new KList<>();
    @ArrayType(min = 1, type = IrisFloatingChildBiomes.class)
    @Desc("Floating child biomes that procedurally generate above this biome's terrain. Each entry references a target biome whose layers, decorators, and objects drive the floating island's visual design, while the config here drives size, shape, altitude, rarity, and water level. Multiple entries are supported and selected by rarity per column unless mergeFloatingChildBiomes is enabled.")
    private KList<IrisFloatingChildBiomes> floatingChildBiomes = new KList<>();
    @Desc("When true, every floating child entry is sampled independently and their solid masks are unioned, so multiple floating islands can stack, overlap, and collide instead of the picker choosing only one child per column.")
    private boolean mergeFloatingChildBiomes = false;
    @ArrayType(min = 1, type = IrisBiomeGeneratorLink.class)
    @Desc("Generators for this biome. Multiple generators with different interpolation sizes will mix with other biomes how you would expect. This defines your biome height relative to the fluid height. Use negative for oceans.")
    private KList<IrisBiomeGeneratorLink> generators = new KList<IrisBiomeGeneratorLink>().qadd(new IrisBiomeGeneratorLink());
    @ArrayType(min = 1, type = IrisDepositGenerator.class)
    @Desc("Define biome deposit generators that add onto the existing regional and global deposit generators")
    private KList<IrisDepositGenerator> deposits = new KList<>();
    @ArrayType(min = 1, type = IrisDepositVariant.class)
    @Desc("Deposit ore remap rules scoped to this biome. Each entry declares a vertical band and a source->replacement block id map. Applied before regional and dimension rules; first matching biome rule wins.")
    private KList<IrisDepositVariant> depositVariants = new KList<>();
    private transient InferredType inferredType;
    @Desc("Collection of ores to be generated")
    @ArrayType(type = IrisOreGenerator.class, min = 1)
    private KList<IrisOreGenerator> ores = new KList<>();

    public PlatformBlockState generateOres(int x, int y, int z, RNG rng, IrisData data, boolean surface) {
        KList<IrisOreGenerator> localOres = surface ? getSurfaceOres() : getUndergroundOres();
        return generateOres(localOres, x, y, z, rng, data);
    }

    public PlatformBlockState generateSurfaceOres(int x, int y, int z, RNG rng, IrisData data) {
        return generateOres(getSurfaceOres(), x, y, z, rng, data);
    }

    public PlatformBlockState generateUndergroundOres(int x, int y, int z, RNG rng, IrisData data) {
        return generateOres(getUndergroundOres(), x, y, z, rng, data);
    }

    public boolean hasSurfaceOres() {
        return !getSurfaceOres().isEmpty();
    }

    public boolean hasUndergroundOres() {
        return !getUndergroundOres().isEmpty();
    }

    private PlatformBlockState generateOres(KList<IrisOreGenerator> localOres, int x, int y, int z, RNG rng, IrisData data) {
        if (localOres.isEmpty()) {
            return null;
        }

        int oreCount = localOres.size();
        for (int oreIndex = 0; oreIndex < oreCount; oreIndex++) {
            IrisOreGenerator oreGenerator = localOres.get(oreIndex);
            PlatformBlockState ore = oreGenerator.generate(x, y, z, rng, data);
            if (ore != null) {
                return ore;
            }
        }
        return null;
    }

    public void setOres(KList<IrisOreGenerator> ores) {
        this.ores = ores == null ? new KList<>() : ores;
        surfaceOreCache.reset();
        undergroundOreCache.reset();
        surfaceOreBoundsCache.reset();
        undergroundOreBoundsCache.reset();
    }

    public KList<IrisOreGenerator> getSurfaceOreGenerators() {
        return getOres(true);
    }

    public KList<IrisOreGenerator> getUndergroundOreGenerators() {
        return getOres(false);
    }

    public IrisOreGeneratorBounds getSurfaceOreGeneratorBounds() {
        return surfaceOreBoundsCache.aquire(() -> IrisOreGeneratorBounds.of(getSurfaceOres()));
    }

    public IrisOreGeneratorBounds getUndergroundOreGeneratorBounds() {
        return undergroundOreBoundsCache.aquire(() -> IrisOreGeneratorBounds.of(getUndergroundOres()));
    }

    private KList<IrisOreGenerator> getSurfaceOres() {
        return getOres(true);
    }

    private KList<IrisOreGenerator> getUndergroundOres() {
        return getOres(false);
    }

    private KList<IrisOreGenerator> getOres(boolean surface) {
        AtomicCache<KList<IrisOreGenerator>> oreCache = surface ? surfaceOreCache : undergroundOreCache;
        return oreCache.aquire(() -> {
            KList<IrisOreGenerator> filtered = new KList<>();
            KList<IrisOreGenerator> localOres = ores;
            int oreCount = localOres.size();
            for (int oreIndex = 0; oreIndex < oreCount; oreIndex++) {
                IrisOreGenerator oreGenerator = localOres.get(oreIndex);
                if (oreGenerator.isGenerateSurface() == surface) {
                    filtered.add(oreGenerator);
                }
            }

            return filtered;
        });
    }

    public Biome getDerivative() {
        return derivativeResolved.aquire(() -> resolveBiomeKey(derivative));
    }

    public Biome getVanillaDerivative() {
        Biome resolved = vanillaDerivative == null
                ? null
                : vanillaDerivativeResolved.aquire(() -> resolveBiomeKey(vanillaDerivative));
        return resolved == null ? getDerivative() : resolved;
    }

    public String getVanillaDerivativeKey() {
        String resolved = namespacedBiomeKey(vanillaDerivative);
        return resolved == null ? namespacedBiomeKey(derivative) : resolved;
    }

    public String getStructureDerivativeKey() {
        String key = getVanillaDerivativeKey();
        if (key == null || !key.startsWith("minecraft:")) {
            return key;
        }
        if (isSea() && !isVanillaSeaStructureBiome(key)) {
            return "minecraft:the_void";
        }
        if (isShore() && !isVanillaShoreStructureBiome(key)) {
            return "minecraft:beach";
        }
        return key;
    }

    public String getDerivativeKey() {
        return namespacedBiomeKey(derivative);
    }

    private static String namespacedBiomeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    static boolean isVanillaSeaStructureBiome(String key) {
        return key != null && (key.contains("ocean") || key.endsWith("river"));
    }

    static boolean isVanillaShoreStructureBiome(String key) {
        return key != null && (key.endsWith("beach") || key.endsWith("shore"));
    }

    private KList<Biome> getBiomeScatterResolved() {
        return biomeScatterResolved.aquire(() -> resolveBiomeKeys(biomeScatter));
    }

    private KList<Biome> getBiomeSkyScatterResolved() {
        return biomeSkyScatterResolved.aquire(() -> resolveBiomeKeys(biomeSkyScatter));
    }

    private static KList<Biome> resolveBiomeKeys(KList<String> keys) {
        KList<Biome> resolved = new KList<>();
        for (String key : keys) {
            resolved.add(resolveBiomeKey(key));
        }
        return resolved;
    }

    private static Biome resolveBiomeKey(String key) {
        if (key == null) {
            return null;
        }
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        return namespacedKey == null ? null : RegistryUtil.lookup(Biome.class).get(namespacedKey);
    }

    public boolean isCustom() {
        return getCustomDerivitives() != null && getCustomDerivitives().isNotEmpty();
    }

    public double getGenLinkMax(String loadKey, Engine engine) {
        if (loadKey == null || loadKey.isBlank()) {
            return 0;
        }

        Integer v = genCacheMax.aquire(() ->
        {
            KMap<String, Integer> l = new KMap<>();

            for (IrisBiomeGeneratorLink i : getGenerators()) {
                String generatorKey = i.getGenerator();
                if (generatorKey == null || generatorKey.isBlank()) {
                    continue;
                }

                l.put(generatorKey, i.getMax());

            }

            return l;
        }).get(loadKey);

        return v == null ? 0 : v;
    }

    public double getGenLinkMin(String loadKey, Engine engine) {
        if (loadKey == null || loadKey.isBlank()) {
            return 0;
        }

        Integer v = genCacheMin.aquire(() ->
        {
            KMap<String, Integer> l = new KMap<>();

            for (IrisBiomeGeneratorLink i : getGenerators()) {
                String generatorKey = i.getGenerator();
                if (generatorKey == null || generatorKey.isBlank()) {
                    continue;
                }

                l.put(generatorKey, i.getMin());
            }

            return l;
        }).get(loadKey);

        return v == null ? 0 : v;
    }

    public IrisBiomeGeneratorLink getGenLink(String loadKey) {
        if (loadKey == null || loadKey.isBlank()) {
            return null;
        }

        return genCache.aquire(() ->
        {
            KMap<String, IrisBiomeGeneratorLink> l = new KMap<>();

            for (IrisBiomeGeneratorLink i : getGenerators()) {
                String generatorKey = i.getGenerator();
                if (generatorKey == null || generatorKey.isBlank()) {
                    continue;
                }

                l.put(generatorKey, i);
            }

            return l;
        }).get(loadKey);
    }

    public IrisBiome getRealCarvingBiome(IrisData data) {
        return realCarveBiome.aquire(() ->
        {
            IrisBiome biome = data.getBiomeLoader().load(getCarvingBiome());

            if (biome == null) {
                biome = this;
            }

            return biome;
        });
    }

    public KList<IrisObjectPlacement> getSurfaceObjects() {
        return getSurfaceObjectsCache().aquire(() ->
        {
            KList<IrisObjectPlacement> o = getObjects().copy();

            for (IrisObjectPlacement i : o.copy()) {
                if (!i.getCarvingSupport().supportsSurface()) {
                    o.remove(i);
                }
            }

            return o;
        });
    }

    public KList<IrisObjectPlacement> getCarvingObjects() {
        return getCarveObjectsCache().aquire(() ->
        {
            KList<IrisObjectPlacement> o = getObjects().copy();

            for (IrisObjectPlacement i : o.copy()) {
                if (!i.getCarvingSupport().supportsCarving()) {
                    o.remove(i);
                }
            }

            return o;
        });
    }

    public double getHeight(Engine xg, double x, double z, long seed) {
        double height = 0;

        for (IrisBiomeGeneratorLink i : generators) {
            height += i.getHeight(xg, x, z, seed);
        }

        return Math.max(0, Math.min(height, xg.getHeight()));
    }

    public CNG getBiomeGenerator(RNG random) {
        IrisData loader = getLoader();
        Engine engine = resolveBiomeGeneratorEngine(loader);
        return getBiomeGenerator(random, engine);
    }

    public CNG getBiomeGenerator(RNG random, Engine engine) {
        IrisData loader = getLoader();
        long seed = engine == null ? random.getSeed() : engine.getSeedManager().getBiome();
        SeededBiomeGenerator recent = recentBiomeGenerator;
        if (recent != null && recent.seed == seed) {
            return recent.generator;
        }

        CNG generator = biomeGenerators.computeIfAbsent(seed, ignored -> createBiomeGenerator(seed, loader));
        recentBiomeGenerator = new SeededBiomeGenerator(seed, generator);
        return generator;
    }

    private Engine resolveBiomeGeneratorEngine(IrisData loader) {
        IrisContext context = IrisContext.get();
        if (context != null) {
            Engine contextEngine = context.getEngine();
            if (!contextEngine.isClosed() && (loader == null || contextEngine.getData() == loader)) {
                return contextEngine;
            }
        }

        return loader == null ? null : loader.getEngine();
    }

    private CNG createBiomeGenerator(long seed, IrisData loader) {
        int signature = 213949 + 228888 + getRarity() + getName().length();
        return biomeStyle.createNoCache(new RNG(seed).nextParallelRNG(signature), loader);
    }

    public CNG getChildrenGenerator(RNG random, int sig, double scale) {
        return childrenCell.aquire(() -> getChildStyle().create(random.nextParallelRNG(sig * 2137), getLoader()).bake().scale(scale).bake());
    }

    public KList<PlatformBlockState> generateLayers(IrisDimension dim, double wx, double wz, RNG random, int maxDepth, int height, IrisData rdata, IrisComplex complex) {
        if (isLockLayers()) {
            return generateLockedLayers(wx, wz, random, maxDepth, height, rdata, complex);
        }

        KList<PlatformBlockState> data = new KList<>();

        if (maxDepth <= 0) {
            return data;
        }

        for (int i = 0; i < layers.size(); i++) {
            CNG hgen = getLayerHeightGenerators(random, rdata).get(i);
            double d = hgen.fit(layers.get(i).getMinHeight(), layers.get(i).getMaxHeight(), wx / layers.get(i).getZoom(), wz / layers.get(i).getZoom());

            IrisSlopeClip sc = getLayers().get(i).getSlopeCondition();

            if (!sc.isDefault()) {
                if (!sc.isValid(complex.getSlopeStream().get(wx, wz))) {
                    d = 0;
                }
            }

            if (d <= 0) {
                continue;
            }

            for (int j = 0; j < d; j++) {
                if (data.size() >= maxDepth) {
                    break;
                }

                try {
                    data.add(getLayers().get(i).get(random.nextParallelRNG(i + j), (wx + j) / layers.get(i).getZoom(), j, (wz - j) / layers.get(i).getZoom(), rdata));
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                }
            }

            if (data.size() >= maxDepth) {
                break;
            }

            if (dim.isExplodeBiomePalettes()) {
                for (int j = 0; j < dim.getExplodeBiomePaletteSize(); j++) {
                    data.add(States.BARRIER);

                    if (data.size() >= maxDepth) {
                        break;
                    }
                }
            }
        }

        return data;
    }

    public KList<PlatformBlockState> generateCeilingLayers(IrisDimension dim, double wx, double wz, RNG random, int maxDepth, int height, IrisData rdata, IrisComplex complex) {
        KList<PlatformBlockState> data = new KList<>();

        if (maxDepth <= 0) {
            return data;
        }

        for (int i = 0; i < caveCeilingLayers.size(); i++) {
            CNG hgen = getLayerHeightGenerators(random, rdata).get(i);
            double d = hgen.fit(caveCeilingLayers.get(i).getMinHeight(), caveCeilingLayers.get(i).getMaxHeight(), wx / caveCeilingLayers.get(i).getZoom(), wz / caveCeilingLayers.get(i).getZoom());

            if (d <= 0) {
                continue;
            }

            for (int j = 0; j < d; j++) {
                if (data.size() >= maxDepth) {
                    break;
                }

                try {
                    data.add(getCaveCeilingLayers().get(i).get(random.nextParallelRNG(i + j), (wx + j) / caveCeilingLayers.get(i).getZoom(), j, (wz - j) / caveCeilingLayers.get(i).getZoom(), rdata));
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                }
            }

            if (data.size() >= maxDepth) {
                break;
            }

            if (dim.isExplodeBiomePalettes()) {
                for (int j = 0; j < dim.getExplodeBiomePaletteSize(); j++) {
                    data.add(States.BARRIER);

                    if (data.size() >= maxDepth) {
                        break;
                    }
                }
            }
        }

        return data;
    }

    public KList<PlatformBlockState> generateLockedLayers(double wx, double wz, RNG random, int maxDepthf, int height, IrisData rdata, IrisComplex complex) {
        KList<PlatformBlockState> data = new KList<>();
        KList<PlatformBlockState> real = new KList<>();
        int maxDepth = Math.min(maxDepthf, getLockLayersMax());
        if (maxDepth <= 0) {
            return data;
        }

        for (int i = 0; i < layers.size(); i++) {
            CNG hgen = getLayerHeightGenerators(random, rdata).get(i);
            double d = hgen.fit(layers.get(i).getMinHeight(), layers.get(i).getMaxHeight(), wx / layers.get(i).getZoom(), wz / layers.get(i).getZoom());

            IrisSlopeClip sc = getLayers().get(i).getSlopeCondition();

            if (!sc.isDefault()) {
                if (!sc.isValid(complex.getSlopeStream().get(wx, wz))) {
                    d = 0;
                }
            }

            if (d <= 0) {
                continue;
            }

            for (int j = 0; j < d; j++) {
                try {
                    data.add(getLayers().get(i).get(random.nextParallelRNG(i + j), (wx + j) / layers.get(i).getZoom(), j, (wz - j) / layers.get(i).getZoom(), rdata));
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                }
            }
        }

        if (data.isEmpty()) {
            return real;
        }

        for (int i = 0; i < maxDepth; i++) {
            int offset = (512 - height) - i;
            int index = offset % data.size();
            real.add(data.get(Math.max(index, 0)));
        }

        return real;
    }

    public int getMaxHeight(Engine engine) {
        return maxHeight.aquire(() ->
        {
            int maxHeight = 0;

            for (IrisBiomeGeneratorLink i : getGenerators()) {
                maxHeight += i.getMax();
            }

            return maxHeight;
        });
    }

    public int getMaxWithObjectHeight(IrisData data, Engine engine) {
        return maxWithObjectHeight.aquire(() ->
        {
            int maxHeight = 0;

            for (IrisBiomeGeneratorLink i : getGenerators()) {
                maxHeight += i.getMax();
            }

            int gg = 0;

            for (IrisObjectPlacement i : getObjects()) {
                for (IrisObject j : data.getObjectLoader().loadAll(i.getPlace())) {
                    gg = Math.max(gg, j.getH());
                }
            }

            return maxHeight + gg + 3;
        });
    }

    public KList<PlatformBlockState> generateSeaLayers(double wx, double wz, RNG random, int maxDepth, IrisData rdata) {
        KList<PlatformBlockState> data = new KList<>();

        for (int i = 0; i < seaLayers.size(); i++) {
            CNG hgen = getLayerSeaHeightGenerators(random, rdata).get(i);
            int d = hgen.fit(seaLayers.get(i).getMinHeight(), seaLayers.get(i).getMaxHeight(), wx / seaLayers.get(i).getZoom(), wz / seaLayers.get(i).getZoom());

            if (d < 0) {
                continue;
            }

            for (int j = 0; j < d; j++) {
                if (data.size() >= maxDepth) {
                    break;
                }

                try {
                    data.add(getSeaLayers().get(i).get(random.nextParallelRNG(i + j), (wx + j) / seaLayers.get(i).getZoom(), j, (wz - j) / seaLayers.get(i).getZoom(), rdata));
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                }
            }

            if (data.size() >= maxDepth) {
                break;
            }
        }

        return data;
    }

    public KList<CNG> getLayerHeightGenerators(RNG rng, IrisData rdata) {
        return layerHeightGenerators.aquire(() ->
        {
            KList<CNG> layerHeightGenerators = new KList<>();

            int m = 7235;

            for (IrisBiomePaletteLayer i : getLayers()) {
                layerHeightGenerators.add(i.getHeightGenerator(rng.nextParallelRNG((m++) * m * m * m), rdata));
            }

            return layerHeightGenerators;
        });
    }

    public KList<CNG> getLayerSeaHeightGenerators(RNG rng, IrisData data) {
        return layerSeaHeightGenerators.aquire(() ->
        {
            KList<CNG> layerSeaHeightGenerators = new KList<>();

            int m = 7735;

            for (IrisBiomePaletteLayer i : getSeaLayers()) {
                layerSeaHeightGenerators.add(i.getHeightGenerator(rng.nextParallelRNG((m++) * m * m * m), data));
            }

            return layerSeaHeightGenerators;
        });
    }

    public boolean isLand() {
        if (inferredType == null) {
            return true;
        }

        return inferredType.equals(InferredType.LAND);
    }

    public boolean isSea() {
        if (inferredType == null) {
            return false;
        }
        return inferredType.equals(InferredType.SEA);
    }

    public boolean isAquatic() {
        return isSea();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isShore() {
        if (inferredType == null) {
            return false;
        }
        return inferredType.equals(InferredType.SHORE);
    }

    public Biome getSkyBiome(RNG rng, double x, double y, double z) {
        return getSkyBiome(rng, resolveBiomeGeneratorEngine(getLoader()), x, y, z);
    }

    public Biome getSkyBiome(RNG rng, Engine engine, double x, double y, double z) {
        if (biomeSkyScatter.size() == 1) {
            return getBiomeSkyScatterResolved().get(0);
        }

        if (biomeSkyScatter.isEmpty()) {
            return getGroundBiome(rng, engine, x, y, z);
        }

        return getBiomeSkyScatterResolved().get(getBiomeGenerator(rng, engine).fit(0, biomeSkyScatter.size() - 1, x, y, z));
    }

    public IrisBiomeCustom getCustomBiome(RNG rng, double x, double y, double z) {
        return getCustomBiome(rng, resolveBiomeGeneratorEngine(getLoader()), x, y, z);
    }

    public IrisBiomeCustom getCustomBiome(RNG rng, Engine engine, double x, double y, double z) {
        if (customDerivitives.size() == 1) {
            return customDerivitives.get(0);
        }

        return customDerivitives.get(getBiomeGenerator(rng, engine).fit(0, customDerivitives.size() - 1, x, y, z));
    }

    public KList<IrisBiome> getRealChildren(DataProvider g) {
        return realChildren.aquire(() ->
        {
            KList<IrisBiome> realChildren = new KList<>();

            for (String i : getChildren()) {
                realChildren.add(g.getData().getBiomeLoader().load(i));
            }

            return realChildren;
        });
    }

    public KList<String> getAllChildren(DataProvider g, int limit) {
        KSet<String> m = new KSet<>();
        m.addAll(getChildren());
        limit--;

        if (limit > 0) {
            for (String i : getChildren()) {
                IrisBiome b = g.getData().getBiomeLoader().load(i);
                m.addAll(b.getAllChildren(g, limit));
            }
        }

        return new KList<>(m);
    }

    //TODO: Test
    public Biome getGroundBiome(RNG rng, double x, double y, double z) {
        return getGroundBiome(rng, resolveBiomeGeneratorEngine(getLoader()), x, y, z);
    }

    public Biome getGroundBiome(RNG rng, Engine engine, double x, double y, double z) {
        if (biomeScatter.isEmpty()) {
            return getDerivative();
        }

        if (biomeScatter.size() == 1) {
            return getBiomeScatterResolved().get(0);
        }

        return getBiomeGenerator(rng, engine).fit(getBiomeScatterResolved(), x, y, z);
    }

    public String getSkyBiomeKey(RNG rng, double x, double y, double z) {
        return getSkyBiomeKey(rng, resolveBiomeGeneratorEngine(getLoader()), x, y, z);
    }

    public String getSkyBiomeKey(RNG rng, Engine engine, double x, double y, double z) {
        if (biomeSkyScatter.size() == 1) {
            return namespacedBiomeKey(biomeSkyScatter.get(0));
        }

        if (biomeSkyScatter.isEmpty()) {
            return getGroundBiomeKey(rng, engine, x, y, z);
        }

        return namespacedBiomeKey(biomeSkyScatter.get(getBiomeGenerator(rng, engine).fit(0, biomeSkyScatter.size() - 1, x, y, z)));
    }

    public String getGroundBiomeKey(RNG rng, double x, double y, double z) {
        return getGroundBiomeKey(rng, resolveBiomeGeneratorEngine(getLoader()), x, y, z);
    }

    public String getGroundBiomeKey(RNG rng, Engine engine, double x, double y, double z) {
        if (biomeScatter.isEmpty()) {
            return namespacedBiomeKey(derivative);
        }

        if (biomeScatter.size() == 1) {
            return namespacedBiomeKey(biomeScatter.get(0));
        }

        return namespacedBiomeKey(biomeScatter.get(getBiomeGenerator(rng, engine).fit(0, biomeScatter.size() - 1, x, y, z)));
    }

    public PlatformBlockState getSurfaceBlock(int x, int z, RNG rng, IrisData idm) {
        if (getLayers().isEmpty()) {
            return B.getState("AIR");
        }

        return getLayers().get(0).get(rng, x, 0, z, idm);
    }

    public Color getColor(Engine engine, RenderType type) {
        switch (type) {
            case BIOME, HEIGHT, CAVE_LAND, REGION, BIOME_SEA, BIOME_LAND -> {
                return this.cacheColor.aquire(() -> {
                    if (this.color == null) {
                        RandomColor randomColor = new RandomColor(getName().hashCode());
                        String vanillaKey = this.getVanillaDerivativeKey();
                        RandomColor.Color col = vanillaKey == null ? null : VanillaBiomeColors.getColorType(vanillaKey);
                        if (col == null) {
                            IrisLogging.warn("No vanilla biome found for " + getName());
                            return new Color(randomColor.randomColor());
                        }
                        RandomColor.Luminosity lum = VanillaBiomeColors.getColorLuminosity(vanillaKey);
                        RandomColor.SaturationType sat = VanillaBiomeColors.getColorSaturation(vanillaKey);
                        int newColorI = randomColor.randomColor(col, col == RandomColor.Color.MONOCHROME ? RandomColor.SaturationType.MONOCHROME : sat, lum);

                        return new Color(newColorI);
                    }

                    try {
                        return Color.decode(this.color);
                    } catch (NumberFormatException e) {
                        IrisLogging.warn("Could not parse color \"" + this.color + "\" for biome " + getName());
                        return new Color(new RandomColor(getName().hashCode()).randomColor());
                    }
                });
            }
            case OBJECT_LOAD -> {
                return cacheColorObjectDensity.aquire(() -> {
                    double density = 0;

                    for (IrisObjectPlacement i : getObjects()) {
                        density += i.getDensity() * i.getChance();
                    }

                    return Color.getHSBColor(0.225f, (float) (density / engine.getMaxBiomeObjectDensity()), 1f);
                });
            }
            case DECORATOR_LOAD -> {
                return cacheColorDecoratorLoad.aquire(() -> {
                    double density = 0;

                    for (IrisDecorator i : getDecorators()) {
                        density += i.getChance() * Math.min(1, i.getStackMax()) * 256;
                    }

                    return Color.getHSBColor(0.41f, (float) (density / engine.getMaxBiomeDecoratorDensity()), 1f);
                });
            }
            case LAYER_LOAD -> {
                return cacheColorLayerLoad.aquire(() -> Color.getHSBColor(0.625f, (float) (getLayers().size() / engine.getMaxBiomeLayerDensity()), 1f));
            }
        }

        return Color.black;
    }

    @Override
    public String getFolderName() {
        return "biomes";
    }

    public IrisDecorator[] getDecoratorBucket(IrisDecorationPart part) {
        return decoratorBuckets.aquire(this::buildDecoratorBuckets).getOrDefault(part, EMPTY_BUCKET);
    }

    private EnumMap<IrisDecorationPart, IrisDecorator[]> buildDecoratorBuckets() {
        EnumMap<IrisDecorationPart, KList<IrisDecorator>> staging = new EnumMap<>(IrisDecorationPart.class);
        for (IrisDecorator d : decorators) {
            staging.computeIfAbsent(d.getPartOf(), k -> new KList<>()).add(d);
        }
        EnumMap<IrisDecorationPart, IrisDecorator[]> result = new EnumMap<>(IrisDecorationPart.class);
        for (IrisDecorationPart part : IrisDecorationPart.values()) {
            KList<IrisDecorator> list = staging.get(part);
            result.put(part, list == null ? EMPTY_BUCKET : list.toArray(EMPTY_BUCKET));
        }
        return result;
    }

    public String getTypeName() {
        return "Biome";
    }

    @Override
    public void scanForErrors(JSONObject p, VolmitSender sender) {

    }

    private static final class SeededBiomeGenerator {
        private final long seed;
        private final CNG generator;

        private SeededBiomeGenerator(long seed, CNG generator) {
            this.seed = seed;
            this.generator = generator;
        }
    }
}

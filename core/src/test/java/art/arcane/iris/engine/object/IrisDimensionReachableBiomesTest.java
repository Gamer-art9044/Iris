package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisDimensionReachableBiomesTest {
    @Test
    @SuppressWarnings("unchecked")
    public void includesOnlyBiomesReachableThroughSelectedRegions() {
        IrisDimension dimension = new IrisDimension().setRegions(new KList<>("reachable", "missing"));
        IrisRegion reachable = new IrisRegion()
                .setLandBiomes(new KList<>("parent", "shared"))
                .setSeaBiomes(new KList<>("shared"));
        IrisBiome parent = biome("parent").setChildren(new KList<>("child", "shared")).setCarvingBiome("carve");
        IrisBiome child = biome("child").setChildren(new KList<>("parent"));
        IrisBiome shared = biome("shared");
        IrisBiome carve = biome("carve");
        IrisBiome unused = biome("unused");

        IrisData data = mock(IrisData.class);
        ResourceLoader<IrisRegion> regionLoader = mock(ResourceLoader.class);
        ResourceLoader<IrisBiome> biomeLoader = mock(ResourceLoader.class);
        when(data.getRegionLoader()).thenReturn(regionLoader);
        when(data.getBiomeLoader()).thenReturn(biomeLoader);
        when(regionLoader.load("reachable")).thenReturn(reachable);
        when(biomeLoader.load("parent")).thenReturn(parent);
        when(biomeLoader.load("child")).thenReturn(child);
        when(biomeLoader.load("shared")).thenReturn(shared);
        when(biomeLoader.load("carve")).thenReturn(carve);
        when(biomeLoader.load("unused")).thenReturn(unused);

        KList<IrisBiome> biomes = dimension.getReachableBiomes(() -> data);
        Set<String> keys = biomes.stream().map(IrisBiome::getLoadKey).collect(Collectors.toSet());

        assertEquals(Set.of("parent", "child", "shared", "carve"), keys);
        assertEquals(keys.size(), biomes.size());
    }

    private IrisBiome biome(String loadKey) {
        IrisBiome biome = new IrisBiome();
        biome.setLoadKey(loadKey);
        return biome;
    }
}

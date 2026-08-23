package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.volmlib.util.collection.KList;
import com.google.gson.Gson;
import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisRiverConfigurationTest {
    @Test
    public void defaultsKeepRiverGenerationDisabledAndContained() {
        IrisDimension dimension = new IrisDimension();

        assertNotNull(dimension.getRivers());
        assertFalse(dimension.getRivers().isEnabled());
        assertEquals(IrisRiverWaterMode.SEA_LEVEL, dimension.getRivers().getWater().getMode());
        assertFalse(dimension.getRivers().getTopology().isRequireOcean());
        assertEquals(512, dimension.getRivers().getTopology().getCellSize());
        assertEquals(16, dimension.getRivers().getTopology().getMaxRouteReaches());
        assertEquals(0, dimension.getRivers().getTopology().getMinimumSourcesPerTile());
        assertEquals(64, dimension.getRivers().getTopology().getRoutingBasinCells());
        assertEquals(8D, dimension.getRivers().getTopology().getRoutingPlateauHeight(), 0D);
        assertEquals(0.05D, dimension.getRivers().getTopology().getSource().getChance(), 0D);
        assertEquals(0.035D, dimension.getRivers().getTopology().getSource().getInfluence(), 0D);
        assertEquals(10D, dimension.getRivers().getTerrain().getMaxChannelWidth(), 0D);
        assertEquals(4D, dimension.getRivers().getTerrain().getMaxBankWidth(), 0D);
        assertEquals(10D, dimension.getRivers().getTerrain().getMaxDepth(), 0D);
        assertEquals(IrisRiverCaveMode.SEALED, dimension.getRivers().getCaves().getMode());
        assertEquals(IrisRiverCaveFallback.SEALED, dimension.getRivers().getCaves().getFallback());
        assertEquals(IrisRiverExistingFluidPolicy.REJECT,
                dimension.getRivers().getCaves().getExistingFluidPolicy());
        assertTrue(dimension.getRivers().getBiomes().getAllBiomeIds().isEmpty());
        assertNull(new IrisRegion().getRiverOverride());
        assertNull(new IrisBiome().getRiverOverride());
    }

    @Test
    public void deserializesTypedDimensionRegionAndBiomeSettings() {
        Gson gson = new Gson();
        IrisDimension dimension = gson.fromJson("""
                {
                  "rivers": {
                    "enabled": true,
                    "topology": {
                      "cellSize": 512,
                      "minimumSourcesPerTile": 2,
                      "routingBasinCells": 96,
                      "routingPlateauHeight": 12,
                      "requireOcean": false,
                      "source": {"chance": 0.27, "influence": 0.4}
                    },
                    "terrain": {
                      "maxChannelWidth": 9,
                      "maxBankWidth": 2.5,
                      "maxDepth": 8,
                      "maxIncision": 36,
                      "terminalMode": "SUPPRESS"
                    },
                    "water": {"mode": "TERRACED", "poolLength": 80},
                    "biomes": {
                      "channel": ["river/channel"],
                      "floodedCave": ["river/grotto"]
                    },
                    "caves": {
                      "mode": "FLOOD_CLOSED_COMPONENT",
                      "maxFloodVolume": 2048,
                      "existingFluidPolicy": "ALLOW_SAME"
                    }
                  }
                }
                """, IrisDimension.class);
        IrisRegion region = gson.fromJson("""
                {
                  "riverOverride": {
                    "allowSources": false,
                    "routingPolicy": "AVOID",
                    "widthMultiplier": 0.75,
                    "bankBiomes": ["river/region-bank"]
                  }
                }
                """, IrisRegion.class);
        IrisBiome biome = gson.fromJson("""
                {
                  "riverOverride": {
                    "routingPolicy": "BLOCK",
                    "caveEntryMultiplier": 0.2,
                    "floodedCaveBiomes": []
                  }
                }
                """, IrisBiome.class);

        assertTrue(dimension.getRivers().isEnabled());
        assertEquals(512, dimension.getRivers().getTopology().getCellSize());
        assertEquals(2, dimension.getRivers().getTopology().getMinimumSourcesPerTile());
        assertEquals(96, dimension.getRivers().getTopology().getRoutingBasinCells());
        assertEquals(12D, dimension.getRivers().getTopology().getRoutingPlateauHeight(), 0D);
        assertFalse(dimension.getRivers().getTopology().isRequireOcean());
        assertEquals(0.27D, dimension.getRivers().getTopology().getSource().getChance(), 0D);
        assertEquals(9D, dimension.getRivers().getTerrain().getMaxChannelWidth(), 0D);
        assertEquals(2.5D, dimension.getRivers().getTerrain().getMaxBankWidth(), 0D);
        assertEquals(8D, dimension.getRivers().getTerrain().getMaxDepth(), 0D);
        assertEquals(36, dimension.getRivers().getTerrain().getMaxIncision());
        assertEquals(IrisRiverTerminalMode.SUPPRESS,
                dimension.getRivers().getTerrain().getTerminalMode());
        assertEquals(IrisRiverWaterMode.TERRACED, dimension.getRivers().getWater().getMode());
        assertEquals(Set.of("river/channel", "river/grotto"),
                Set.copyOf(dimension.getRivers().getBiomes().getAllBiomeIds()));
        assertEquals(IrisRiverCaveMode.FLOOD_CLOSED_COMPONENT,
                dimension.getRivers().getCaves().getMode());
        assertEquals(IrisRiverExistingFluidPolicy.ALLOW_SAME,
                dimension.getRivers().getCaves().getExistingFluidPolicy());

        assertEquals(Boolean.FALSE, region.getRiverOverride().getAllowSources());
        assertEquals(IrisRiverRoutingPolicy.AVOID, region.getRiverOverride().getRoutingPolicy());
        assertEquals(Double.valueOf(0.75D), region.getRiverOverride().getWidthMultiplier());
        assertNull(region.getRiverOverride().getChannelBiomes());
        assertEquals(new KList<>("river/region-bank"), region.getRiverOverride().getBankBiomes());

        assertEquals(IrisRiverRoutingPolicy.BLOCK, biome.getRiverOverride().getRoutingPolicy());
        assertNull(biome.getRiverOverride().getChannelBiomes());
        assertNotNull(biome.getRiverOverride().getFloodedCaveBiomes());
        assertTrue(biome.getRiverOverride().getFloodedCaveBiomes().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void resolvesRiverPoolsOnlyWhenRiversAreEnabled() {
        IrisRiverBiomes dimensionBiomes = new IrisRiverBiomes()
                .setChannel(new KList<>("dimension-channel"));
        IrisRiverOverride regionOverride = new IrisRiverOverride()
                .setBankBiomes(new KList<>("region-bank"));
        IrisRiverOverride biomeOverride = new IrisRiverOverride()
                .setMouthBiomes(new KList<>("biome-mouth"))
                .setFloodedCaveBiomes(new KList<>("biome-grotto"));
        IrisDimension dimension = new IrisDimension()
                .setRegions(new KList<>("region"))
                .setRivers(new IrisRiverNetwork().setEnabled(true).setBiomes(dimensionBiomes));
        IrisRegion region = new IrisRegion()
                .setLandBiomes(new KList<>("natural"))
                .setRiverOverride(regionOverride);
        IrisBiome natural = biome("natural").setRiverOverride(biomeOverride);

        IrisData data = mock(IrisData.class);
        ResourceLoader<IrisRegion> regionLoader = mock(ResourceLoader.class);
        ResourceLoader<IrisBiome> biomeLoader = mock(ResourceLoader.class);
        when(data.getRegionLoader()).thenReturn(regionLoader);
        when(data.getBiomeLoader()).thenReturn(biomeLoader);
        when(regionLoader.load("region")).thenReturn(region);
        when(biomeLoader.load("natural")).thenReturn(natural);
        when(biomeLoader.load("dimension-channel")).thenReturn(biome("dimension-channel"));
        when(biomeLoader.load("region-bank")).thenReturn(biome("region-bank"));
        when(biomeLoader.load("biome-mouth")).thenReturn(biome("biome-mouth"));
        when(biomeLoader.load("biome-grotto")).thenReturn(biome("biome-grotto"));

        Set<String> enabledKeys = keys(dimension.getReachableBiomes(() -> data));
        dimension.getRivers().setEnabled(false);
        Set<String> disabledKeys = keys(dimension.getReachableBiomes(() -> data));

        assertEquals(Set.of("natural", "dimension-channel", "region-bank", "biome-mouth", "biome-grotto"),
                enabledKeys);
        assertEquals(Set.of("natural"), disabledKeys);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void separatesNaturalAndRiverOnlyRegionBiomes() {
        IrisRegion region = new IrisRegion()
                .setLandBiomes(new KList<>("natural-parent"))
                .setRiverOverride(new IrisRiverOverride()
                        .setChannelBiomes(new KList<>("river-parent")));
        IrisBiome naturalParent = biome("natural-parent").setChildren(new KList<>("natural-child"));
        IrisBiome naturalChild = biome("natural-child");
        IrisBiome riverParent = biome("river-parent").setChildren(new KList<>("river-child"));
        IrisBiome riverChild = biome("river-child");

        IrisData data = mock(IrisData.class);
        ResourceLoader<IrisBiome> biomeLoader = mock(ResourceLoader.class);
        when(data.getBiomeLoader()).thenReturn(biomeLoader);
        when(biomeLoader.load("natural-parent")).thenReturn(naturalParent);
        when(biomeLoader.load("natural-child")).thenReturn(naturalChild);
        when(biomeLoader.load("river-parent")).thenReturn(riverParent);
        when(biomeLoader.load("river-child")).thenReturn(riverChild);

        assertEquals(Set.of("natural-parent"), Set.copyOf(region.getNaturalBiomeIds()));
        assertEquals(Set.of("natural-parent", "river-parent"), Set.copyOf(region.getAllBiomeIds()));
        assertEquals(Set.of("natural-parent", "natural-child"),
                keys(region.getNaturalBiomes(() -> data)));
        assertEquals(Set.of("natural-parent", "natural-child", "river-parent", "river-child"),
                keys(region.getAllBiomes(() -> data)));
    }

    private static IrisBiome biome(String loadKey) {
        IrisBiome biome = new IrisBiome();
        biome.setLoadKey(loadKey);
        return biome;
    }

    private static Set<String> keys(KList<IrisBiome> biomes) {
        return biomes.stream().map(IrisBiome::getLoadKey).collect(Collectors.toSet());
    }
}

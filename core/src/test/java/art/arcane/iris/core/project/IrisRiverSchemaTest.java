package art.arcane.iris.core.project;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBlockData;
import art.arcane.iris.engine.object.IrisExpression;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformRegistries;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisRiverSchemaTest {
    private IrisPlatform previousPlatform;

    @Before
    public void bindPlatform() {
        previousPlatform = IrisPlatforms.isBound() ? IrisPlatforms.get() : null;
        if (previousPlatform != null) {
            IrisPlatforms.unbind();
        }
        IrisPlatform platform = mock(IrisPlatform.class);
        PlatformRegistries registries = mock(PlatformRegistries.class);
        when(platform.registries()).thenReturn(registries);
        when(registries.blockTypeKeys()).thenReturn(List.of());
        IrisPlatforms.bind(platform);
    }

    @After
    public void restorePlatform() {
        IrisPlatforms.unbind();
        if (previousPlatform != null) {
            IrisPlatforms.bind(previousPlatform);
        }
    }

    @Test
    public void riverNetworkSchemaExposesNestedNoiseLimitsModesAndBiomePools() {
        JSONObject schema = new SchemaBuilder(IrisRiverNetwork.class, schemaData()).construct();
        JSONObject definitions = schema.getJSONObject("definitions");
        JSONObject properties = schema.getJSONObject("properties");
        JSONObject topology = referencedProperties(definitions, properties.getJSONObject("topology"));
        JSONObject source = referencedProperties(definitions, topology.getJSONObject("source"));
        JSONObject terrainDefinition = referencedDefinition(definitions, properties.getJSONObject("terrain"));
        JSONObject terrain = terrainDefinition.getJSONObject("properties");
        JSONObject worms = terrain.getJSONObject("worms");
        JSONObject worm = referencedProperties(definitions, worms.getJSONObject("items"));
        JSONObject water = referencedProperties(definitions, properties.getJSONObject("water"));
        JSONObject biomes = referencedProperties(definitions, properties.getJSONObject("biomes"));
        JSONObject caves = referencedProperties(definitions, properties.getJSONObject("caves"));
        JSONObject deepPools = referencedProperties(definitions, caves.getJSONObject("deepPools"));

        assertEquals("boolean", properties.getJSONObject("enabled").getString("type"));
        assertEquals(64, topology.getJSONObject("cellSize").getInt("minimum"));
        assertEquals(4096, topology.getJSONObject("cellSize").getInt("maximum"));
        assertEquals(7, topology.getJSONObject("sinkSearchReaches").getInt("maximum"));
        assertEquals(8, topology.getJSONObject("routingBasinCells").getInt("minimum"));
        assertEquals(256, topology.getJSONObject("routingBasinCells").getInt("maximum"));
        assertEquals(0D, source.getJSONObject("chance").getDouble("minimum"), 0D);
        assertEquals(1D, source.getJSONObject("chance").getDouble("maximum"), 0D);
        assertEquals(1D, terrain.getJSONObject("maxChannelWidth").getDouble("minimum"), 0D);
        assertEquals(2048D, terrain.getJSONObject("maxChannelWidth").getDouble("maximum"), 0D);
        assertEquals(0D, terrain.getJSONObject("maxBankWidth").getDouble("minimum"), 0D);
        assertEquals(512D, terrain.getJSONObject("maxDepth").getDouble("maximum"), 0D);
        assertTrue(arrayContains(terrainDefinition.getJSONArray("required"), "worms"));
        assertEquals("array", worms.getString("type"));
        assertEquals(1, worms.getInt("minItems"));
        assertEquals(0.000001D, worm.getJSONObject("weight").getDouble("minimum"), 0D);
        assertEquals(16384D, worm.getJSONObject("wavelength").getDouble("maximum"), 0D);
        assertEquals(1D, worm.getJSONObject("tortuosity").getDouble("maximum"), 0D);
        assertEquals(1024D, worm.getJSONObject("maxOffset").getDouble("maximum"), 0D);
        assertEquals(64, worm.getJSONObject("segments").getInt("maximum"));
        assertEquals(0.125D, worm.getJSONObject("widthMultiplier").getDouble("minimum"), 0D);
        assertEquals(8D, worm.getJSONObject("bankMultiplier").getDouble("maximum"), 0D);
        assertEquals(8D, worm.getJSONObject("depthMultiplier").getDouble("maximum"), 0D);
        assertEquals(8D, worm.getJSONObject("bodyWavelength").getDouble("minimum"), 0D);
        assertEquals(16384D, worm.getJSONObject("bodyDetailWavelength").getDouble("maximum"), 0D);
        assertEquals(1D, worm.getJSONObject("bodyDetailInfluence").getDouble("maximum"), 0D);
        assertEquals(0.875D, worm.getJSONObject("widthVariation").getDouble("maximum"), 0D);
        assertEquals(0.875D, worm.getJSONObject("bankVariation").getDouble("maximum"), 0D);
        assertEquals(0.875D, worm.getJSONObject("depthVariation").getDouble("maximum"), 0D);
        assertEquals(0.875D, worm.getJSONObject("roofVariation").getDouble("maximum"), 0D);
        assertEquals(8, worm.getJSONObject("branchCap").getInt("maximum"));
        assertEquals(1D, worm.getJSONObject("branchDecay").getDouble("maximum"), 0D);
        assertEquals(8D, worm.getJSONObject("confluenceMultiplier").getDouble("maximum"), 0D);
        assertEquals(1D, worm.getJSONObject("childChance").getDouble("maximum"), 0D);
        assertEquals(1D, worm.getJSONObject("branchChildChance").getDouble("maximum"), 0D);
        assertEquals("array", worm.getJSONObject("children").getString("type"));
        assertEquals(List.of("FIXED", "TERRACED"), enumValues(definitions, water.getJSONObject("mode")));
        assertEquals(-2048, water.getJSONObject("fluidHeight").getInt("minimum"));
        assertEquals(2048, water.getJSONObject("fluidHeight").getInt("maximum"));
        assertTrue(water.has("fluidPalette"));
        assertEquals(64D, terrain.getJSONObject("channelRadiusBonus").getDouble("maximum"), 0D);
        assertEquals("array", biomes.getJSONObject("channel").getString("type"));
        assertEquals("#/definitions/erzbiomes",
                biomes.getJSONObject("channel").getJSONObject("items").getString("$ref"));
        assertTrue(properties.has("terrain"));
        assertTrue(properties.has("caves"));
        assertEquals("boolean", deepPools.getJSONObject("enabled").getString("type"));
        assertEquals(-2048, deepPools.getJSONObject("minimumFluidY").getInt("minimum"));
        assertEquals(2048, deepPools.getJSONObject("maximumFluidY").getInt("maximum"));
        assertEquals(128, deepPools.getJSONObject("horizontalRadius").getInt("maximum"));
        assertEquals(64, deepPools.getJSONObject("verticalRadius").getInt("maximum"));
        assertEquals(0.75D, deepPools.getJSONObject("shapeVariation").getDouble("maximum"), 0D);
        assertEquals(64D, deepPools.getJSONObject("warpStrength").getDouble("maximum"), 0D);
        assertTrue(deepPools.has("reach"));
        assertTrue(deepPools.has("shapeStyle"));
        assertTrue(deepPools.has("warpStyle"));
        assertTrue(deepPools.has("fluidPalette"));
    }

    @Test
    public void overrideSchemaKeepsEveryFieldOptionalAndTyped() {
        JSONObject schema = new SchemaBuilder(IrisRiverOverride.class, schemaData()).construct();
        JSONObject properties = schema.getJSONObject("properties");

        assertTrue(!schema.has("required") || schema.getJSONArray("required").length() == 0);
        assertEquals("boolean", properties.getJSONObject("allowSources").getString("type"));
        assertEquals("number", properties.getJSONObject("routingCostMultiplier").getString("type"));
        assertEquals("array", properties.getJSONObject("channelBiomes").getString("type"));
        assertEquals("#/definitions/erzbiomes",
                properties.getJSONObject("floodedCaveBiomes").getJSONObject("items").getString("$ref"));
    }

    @SuppressWarnings("unchecked")
    private static IrisData schemaData() {
        IrisData data = mock(IrisData.class);
        ResourceLoader<IrisBiome> biomeLoader = mock(ResourceLoader.class);
        ResourceLoader<IrisExpression> expressionLoader = mock(ResourceLoader.class);
        ResourceLoader<IrisBlockData> blockLoader = mock(ResourceLoader.class);
        KMap<Class<? extends IrisRegistrant>, ResourceLoader<? extends IrisRegistrant>> loaders = new KMap<>();
        loaders.put(IrisBiome.class, biomeLoader);
        loaders.put(IrisExpression.class, expressionLoader);
        when(data.getBlockLoader()).thenReturn(blockLoader);
        when(data.getLoaders()).thenReturn(loaders);
        when(data.getPossibleSnippets(anyString())).thenReturn(new KList<>());
        when(biomeLoader.getPossibleKeys()).thenReturn(new String[]{"river/channel"});
        when(biomeLoader.getFolderName()).thenReturn("biomes");
        when(biomeLoader.getResourceTypeName()).thenReturn("Biome");
        when(blockLoader.getPossibleKeys()).thenReturn(new String[0]);
        when(expressionLoader.getPossibleKeys()).thenReturn(new String[0]);
        when(expressionLoader.getFolderName()).thenReturn("expressions");
        when(expressionLoader.getResourceTypeName()).thenReturn("Expression");
        return data;
    }

    private static JSONObject referencedProperties(JSONObject definitions, JSONObject reference) {
        return referencedDefinition(definitions, reference).getJSONObject("properties");
    }

    private static JSONObject referencedDefinition(JSONObject definitions, JSONObject reference) {
        String key = reference.getString("$ref").substring("#/definitions/".length());
        return definitions.getJSONObject(key);
    }

    private static boolean arrayContains(JSONArray values, String expected) {
        for (int index = 0; index < values.length(); index++) {
            if (expected.equals(values.getString(index))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> enumValues(JSONObject definitions, JSONObject reference) {
        String key = reference.getString("$ref").substring("#/definitions/".length());
        JSONArray values = definitions.getJSONObject(key).getJSONArray("oneOf");
        List<String> names = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            names.add(values.getJSONObject(index).getString("const"));
        }
        return names;
    }
}

package art.arcane.iris.core.project;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisExpression;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisRiverSchemaTest {
    @Test
    public void riverNetworkSchemaExposesNestedNoiseLimitsModesAndBiomePools() {
        JSONObject schema = new SchemaBuilder(IrisRiverNetwork.class, schemaData()).construct();
        JSONObject definitions = schema.getJSONObject("definitions");
        JSONObject properties = schema.getJSONObject("properties");
        JSONObject topology = referencedProperties(definitions, properties.getJSONObject("topology"));
        JSONObject source = referencedProperties(definitions, topology.getJSONObject("source"));
        JSONObject terrain = referencedProperties(definitions, properties.getJSONObject("terrain"));
        JSONObject water = referencedProperties(definitions, properties.getJSONObject("water"));
        JSONObject biomes = referencedProperties(definitions, properties.getJSONObject("biomes"));

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
        assertEquals(List.of("SEA_LEVEL", "TERRACED"), enumValues(definitions, water.getJSONObject("mode")));
        assertEquals("array", biomes.getJSONObject("channel").getString("type"));
        assertEquals("#/definitions/erzbiomes",
                biomes.getJSONObject("channel").getJSONObject("items").getString("$ref"));
        assertTrue(properties.has("terrain"));
        assertTrue(properties.has("caves"));
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
        KMap<Class<? extends IrisRegistrant>, ResourceLoader<? extends IrisRegistrant>> loaders = new KMap<>();
        loaders.put(IrisBiome.class, biomeLoader);
        loaders.put(IrisExpression.class, expressionLoader);
        when(data.getLoaders()).thenReturn(loaders);
        when(data.getPossibleSnippets(anyString())).thenReturn(new KList<>());
        when(biomeLoader.getPossibleKeys()).thenReturn(new String[]{"river/channel"});
        when(biomeLoader.getFolderName()).thenReturn("biomes");
        when(biomeLoader.getResourceTypeName()).thenReturn("Biome");
        when(expressionLoader.getPossibleKeys()).thenReturn(new String[0]);
        when(expressionLoader.getFolderName()).thenReturn("expressions");
        when(expressionLoader.getResourceTypeName()).thenReturn("Expression");
        return data;
    }

    private static JSONObject referencedProperties(JSONObject definitions, JSONObject reference) {
        String key = reference.getString("$ref").substring("#/definitions/".length());
        return definitions.getJSONObject(key).getJSONObject("properties");
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

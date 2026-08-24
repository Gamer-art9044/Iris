package art.arcane.iris.core.project;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisGeneratorStyle;
import art.arcane.iris.engine.object.IrisImageMap;
import art.arcane.iris.engine.object.IrisImageMapBinding;
import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisImageMapSchemaTest {
    @Test
    public void dimensionAndGeneratorExposeResourceReferences() throws Exception {
        Field bindings = IrisDimension.class.getDeclaredField("imageMaps");
        Field generatorMap = IrisGeneratorStyle.class.getDeclaredField("imageMap");

        assertEquals(IrisImageMapBinding.class, bindings.getAnnotation(ArrayType.class).type());
        assertEquals(String.class, generatorMap.getType());
        assertEquals("image-maps", new IrisImageMap().getFolderName());
    }

    @Test
    public void schemasExposeTypedMapAndBindingObjects() {
        IrisData data = schemaData();
        JSONObject bindingSchema = new SchemaBuilder(IrisImageMapBinding.class, data).construct();
        JSONObject mapSchema = new SchemaBuilder(IrisImageMap.class, data).construct();

        assertTrue(bindingSchema.getJSONObject("properties").has("application"));
        assertTrue(bindingSchema.getJSONObject("properties").has("map"));
        assertTrue(mapSchema.getJSONObject("properties").has("type"));
        assertTrue(mapSchema.getJSONObject("properties").has("source"));
        assertTrue(mapSchema.getJSONObject("properties").has("colors"));
        assertTrue(mapSchema.getJSONObject("properties").has("blocksPerPixel"));
        JSONObject properties = mapSchema.getJSONObject("properties");
        assertEquals(IrisImageMap.MINIMUM_SCALE,
                properties.getJSONObject("blocksPerPixel").getDouble("minimum"), 0D);
        assertEquals(IrisImageMap.MINIMUM_SCALE,
                properties.getJSONObject("curveExponent").getDouble("minimum"), 0D);
        assertEquals(IrisImageMap.MAXIMUM_COLOR_TOLERANCE,
                properties.getJSONObject("colorTolerance").getDouble("maximum"), 0D);
    }

    @SuppressWarnings("unchecked")
    private static IrisData schemaData() {
        IrisData data = mock(IrisData.class);
        KMap<Class<? extends IrisRegistrant>, ResourceLoader<? extends IrisRegistrant>> loaders = new KMap<>();
        when(data.getLoaders()).thenReturn(loaders);
        when(data.getPossibleSnippets(anyString())).thenReturn(new KList<>());
        return data;
    }
}

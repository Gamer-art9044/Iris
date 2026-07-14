package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class IrisVanillaStructureStiltSettingsTest {
    @Test
    public void defaultsToCobblestoneFoundation() {
        IrisVanillaStructureStiltSettings settings = new IrisVanillaStructureStiltSettings();

        assertEquals(64, settings.getMaxDepth());
        assertNotNull(settings.getPalette());
        assertEquals(1, settings.getPalette().getPalette().size());
        assertEquals("minecraft:cobblestone", settings.getPalette().getPalette().get(0).getBlock());
    }

    @Test
    public void maxDepthSchemaIsBoundedToTheMaximumWorldHeight() throws NoSuchFieldException {
        Field maxDepth = IrisVanillaStructureStiltSettings.class.getDeclaredField("maxDepth");
        MinNumber minimum = maxDepth.getAnnotation(MinNumber.class);
        MaxNumber maximum = maxDepth.getAnnotation(MaxNumber.class);

        assertNotNull(minimum);
        assertNotNull(maximum);
        assertEquals(1.0, minimum.value(), 0.0);
        assertEquals(4064.0, maximum.value(), 0.0);
    }
}

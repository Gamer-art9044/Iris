package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class IrisStructurePlacementCarveSettingsTest {
    @Test
    public void defaultsSelectErodedOverbore() {
        IrisStructurePlacement placement = new IrisStructurePlacement();

        assertEquals(IrisStructureCarveShape.ERODED, placement.getOverboreShape());
        assertEquals(IrisStructureCarveShape.ERODED, placement.resolvedOverboreShape());
        assertEquals(0.8D, placement.getOverboreErosionStrength(), 0D);
        assertEquals(0.8D, placement.resolvedOverboreErosionStrength(), 0D);
        assertEquals(0.07D, placement.getOverboreErosionFrequency(), 0D);
        assertEquals(0.07D, placement.resolvedOverboreErosionFrequency(), 0D);
    }

    @Test
    public void carveShapesExposeTheirMaximumCeilingScale() {
        assertEquals(1D, IrisStructureCarveShape.BOX.maximumCeilingScale(), 0D);
        assertEquals(1D, IrisStructureCarveShape.ROUNDED.maximumCeilingScale(), 0D);
        assertEquals(1.8D, IrisStructureCarveShape.ERODED.maximumCeilingScale(), 0D);
        assertEquals(10, IrisStructureCarveShape.BOX.maximumCeilingExtension(10));
        assertEquals(10, IrisStructureCarveShape.ROUNDED.maximumCeilingExtension(10));
        assertEquals(18, IrisStructureCarveShape.ERODED.maximumCeilingExtension(10));
        assertEquals(10, IrisStructureCarveShape.ERODED.maximumCeilingExtension(10, 0D));
        assertEquals(14, IrisStructureCarveShape.ERODED.maximumCeilingExtension(10, 0.5D));
        assertEquals(0, IrisStructureCarveShape.ERODED.maximumCeilingExtension(0));
    }

    @Test
    public void nullAndNonFiniteValuesResolveToDefaults() {
        IrisStructurePlacement placement = new IrisStructurePlacement()
                .setOverboreShape(null)
                .setOverboreErosionStrength(Double.NaN)
                .setOverboreErosionFrequency(Double.POSITIVE_INFINITY);

        assertEquals(IrisStructureCarveShape.ERODED, placement.resolvedOverboreShape());
        assertEquals(0.8D, placement.resolvedOverboreErosionStrength(), 0D);
        assertEquals(0.07D, placement.resolvedOverboreErosionFrequency(), 0D);

        placement.setOverboreErosionStrength(Double.NEGATIVE_INFINITY);
        placement.setOverboreErosionFrequency(Double.NaN);

        assertEquals(0.8D, placement.resolvedOverboreErosionStrength(), 0D);
        assertEquals(0.07D, placement.resolvedOverboreErosionFrequency(), 0D);
    }

    @Test
    public void finiteValuesClampToAuthoredBounds() {
        IrisStructurePlacement placement = new IrisStructurePlacement()
                .setOverboreErosionStrength(-1D)
                .setOverboreErosionFrequency(0D);

        assertEquals(0D, placement.resolvedOverboreErosionStrength(), 0D);
        assertEquals(0.001D, placement.resolvedOverboreErosionFrequency(), 0D);

        placement.setOverboreErosionStrength(2D);
        placement.setOverboreErosionFrequency(2D);

        assertEquals(1D, placement.resolvedOverboreErosionStrength(), 0D);
        assertEquals(1D, placement.resolvedOverboreErosionFrequency(), 0D);
    }

    @Test
    public void numericSchemaBoundsMatchRuntimeBounds() throws NoSuchFieldException {
        assertBounds("overboreErosionStrength", 0D, 1D);
        assertBounds("overboreErosionFrequency", 0.001D, 1D);
    }

    private void assertBounds(String fieldName, double expectedMinimum,
                              double expectedMaximum) throws NoSuchFieldException {
        Field field = IrisStructurePlacement.class.getDeclaredField(fieldName);
        MinNumber minimum = field.getAnnotation(MinNumber.class);
        MaxNumber maximum = field.getAnnotation(MaxNumber.class);

        assertNotNull(minimum);
        assertNotNull(maximum);
        assertEquals(expectedMinimum, minimum.value(), 0D);
        assertEquals(expectedMaximum, maximum.value(), 0D);
    }
}

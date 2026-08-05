package art.arcane.iris.core.commands;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CommandIrisPackDimensionTypeHandlerTest {
    @Test
    public void matchingPackAndDimensionUsesBarePackName() {
        assertEquals("overworld",
                CommandIris.PackDimensionTypeHandler.packDimensionOption("overworld", "overworld"));
        assertEquals("OverWorld",
                CommandIris.PackDimensionTypeHandler.packDimensionOption("OverWorld", "overworld"));
    }

    @Test
    public void differingDimensionUsesExplicitPackReference() {
        assertEquals("custom_pack:dimensions/sky",
                CommandIris.PackDimensionTypeHandler.packDimensionOption("custom_pack", "dimensions/sky"));
    }
}

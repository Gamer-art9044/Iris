package art.arcane.iris.engine.framework;

import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NativeStructureGenerationPolicyTest {
    @Test
    public void generationStatusMessagesAreSharedAcrossPlatforms() {
        assertEquals(
                "Native structure minecraft:village_plains is disabled by this dimension's importedStructures settings.",
                NativeStructureGenerationPolicy.generationStatusMessage(
                        "minecraft:village_plains", NativeStructureGenerationStatus.DISABLED_BY_PACK));
        assertEquals(
                "Native structure minecraft:ancient_city is replaced by an Iris placement in this pack and locates through that explicit replacement.",
                NativeStructureGenerationPolicy.generationStatusMessage(
                        "minecraft:ancient_city", NativeStructureGenerationStatus.REPLACED_BY_IRIS));
    }
}

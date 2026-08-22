package art.arcane.iris.engine.framework;

import art.arcane.iris.engine.object.IrisNativeStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;

public record NativeStructureStartPlan(
        IrisStructurePlacement placement,
        IrisNativeStructure source,
        int chunkX,
        int chunkZ,
        int baseY
) {
}

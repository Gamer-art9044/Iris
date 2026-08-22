package art.arcane.iris.modded;

import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;

import java.util.function.IntBinaryOperator;

final class ModdedHeightmaps {
    private ModdedHeightmaps() {
    }

    static long[] terrainRawData(int height, IntBinaryOperator heightResolver) {
        SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(height + 1), 256);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int value = Mth.clamp(heightResolver.applyAsInt(x, z), 0, height);
                storage.set(x + z * 16, value);
            }
        }
        return storage.getRaw();
    }
}

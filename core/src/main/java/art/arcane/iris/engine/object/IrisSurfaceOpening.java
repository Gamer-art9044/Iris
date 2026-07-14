package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.math.IrisBlockVector;

final class IrisSurfaceOpening {
    private static final int SUPPORT_RADIUS = 1;

    private IrisSurfaceOpening() {
    }

    static boolean isOpen(IObjectPlacer placer, IrisData data, int x, int z, IrisObjectTranslate translate,
                          IrisObjectRotation rotation, int spinX, int spinY, int spinZ) {
        IrisBlockVector offset = new IrisBlockVector(0, 0, 0);
        if (translate != null) {
            offset = rotation == null
                    ? translate.translate(offset)
                    : translate.translate(offset, rotation, spinX, spinY, spinZ);
        }
        int centerX = x + offset.getBlockX();
        int centerZ = z + offset.getBlockZ();
        for (int dx = -SUPPORT_RADIUS; dx <= SUPPORT_RADIUS; dx++) {
            for (int dz = -SUPPORT_RADIUS; dz <= SUPPORT_RADIUS; dz++) {
                int sampleX = centerX + dx;
                int sampleZ = centerZ + dz;
                int surfaceY = placer.getHighest(sampleX, sampleZ, data, true);
                if (placer.isCarved(sampleX, surfaceY, sampleZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean containsTreeBlocks(Iterable<PlatformBlockState> states) {
        for (PlatformBlockState state : states) {
            if (state != null && state.isTreeBlock()) {
                return true;
            }
        }
        return false;
    }
}

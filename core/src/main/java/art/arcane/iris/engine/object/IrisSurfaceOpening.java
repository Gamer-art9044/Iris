package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.util.common.math.IrisBlockVector;

import java.util.List;

final class IrisSurfaceOpening {
    private static final int SUPPORT_RADIUS = 1;

    private IrisSurfaceOpening() {
    }

    static boolean isOpen(IObjectPlacer placer, IrisData data, int x, int z, IrisObjectTranslate translate,
                          IrisObjectRotation rotation, int spinX, int spinY, int spinZ,
                          List<IrisBlockVector> supportOffsets) {
        if (supportOffsets.isEmpty()) {
            IrisBlockVector origin = transform(new IrisBlockVector(0, 0, 0), translate, rotation, spinX, spinY, spinZ);
            return isOpenAround(placer, data, x + origin.getBlockX(), z + origin.getBlockZ());
        }

        for (IrisBlockVector supportOffset : supportOffsets) {
            IrisBlockVector transformed = transform(supportOffset, translate, rotation, spinX, spinY, spinZ);
            if (isOpenAround(placer, data, x + transformed.getBlockX(), z + transformed.getBlockZ())) {
                return true;
            }
        }
        return false;
    }

    private static IrisBlockVector transform(IrisBlockVector offset, IrisObjectTranslate translate,
                                             IrisObjectRotation rotation, int spinX, int spinY, int spinZ) {
        IrisBlockVector transformed = offset.clone();
        if (rotation != null) {
            transformed = rotation.rotate(transformed, spinX, spinY, spinZ);
        }
        if (translate == null) {
            return transformed;
        }
        return rotation == null
                ? translate.translate(transformed)
                : translate.translate(transformed, rotation, spinX, spinY, spinZ);
    }

    private static boolean isOpenAround(IObjectPlacer placer, IrisData data, int centerX, int centerZ) {
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
}

package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisGeneratorStyle;
import art.arcane.iris.engine.object.NoiseStyle;
import art.arcane.iris.engine.river.cave.RiverCaveGrottoShape;
import art.arcane.iris.engine.river.cave.RiverCavePlannerSettings;
import art.arcane.iris.engine.river.cave.RiverCaveSource;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.math.RNG;

final class ConfiguredRiverGrottoShape implements RiverCaveGrottoShape {
    private static final long SHAPE_SALT = 0x3C6EF372FE94F82BL;
    private static final long WARP_X_SALT = 0xA54FF53A5F1D36F1L;
    private static final long WARP_Y_SALT = 0x510E527FADE682D1L;
    private static final long WARP_Z_SALT = 0x9B05688C2B3E6C1FL;

    private final CNG shape;
    private final CNG warpX;
    private final CNG warpY;
    private final CNG warpZ;
    private final double warpStrength;
    private final double boundaryVariation;

    ConfiguredRiverGrottoShape(
            long seed,
            IrisData data,
            IrisGeneratorStyle shapeStyle,
            IrisGeneratorStyle warpStyle,
            double warpStrength,
            double boundaryVariation
    ) {
        IrisGeneratorStyle resolvedShape = shapeStyle == null
                ? new IrisGeneratorStyle(NoiseStyle.FLAT)
                : shapeStyle;
        IrisGeneratorStyle resolvedWarp = warpStyle == null
                ? new IrisGeneratorStyle(NoiseStyle.FLAT)
                : warpStyle;
        shape = resolvedShape.createNoCache(new RNG(seed ^ SHAPE_SALT), data);
        warpX = resolvedWarp.createNoCache(new RNG(seed ^ WARP_X_SALT), data);
        warpY = resolvedWarp.createNoCache(new RNG(seed ^ WARP_Y_SALT), data);
        warpZ = resolvedWarp.createNoCache(new RNG(seed ^ WARP_Z_SALT), data);
        this.warpStrength = Math.max(0D, warpStrength);
        this.boundaryVariation = Math.max(0D, Math.min(0.75D, boundaryVariation));
    }

    @Override
    public boolean contains(
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            int offsetX,
            int offsetY,
            int offsetZ
    ) {
        double worldX = source.target().x() + offsetX;
        double worldY = source.target().y() + offsetY;
        double worldZ = source.target().z() + offsetZ;
        double warpedX = offsetX + warpX.fitDouble(-warpStrength, warpStrength, worldX, worldY, worldZ);
        double warpedY = offsetY + warpY.fitDouble(-warpStrength, warpStrength, worldY, worldZ, worldX);
        double warpedZ = offsetZ + warpZ.fitDouble(-warpStrength, warpStrength, worldZ, worldX, worldY);
        double horizontalRadius = settings.grottoHorizontalRadius();
        double verticalRadius = settings.grottoVerticalRadius();
        double normalized = (warpedX * warpedX / (horizontalRadius * horizontalRadius))
                + (warpedY * warpedY / (verticalRadius * verticalRadius))
                + (warpedZ * warpedZ / (horizontalRadius * horizontalRadius));
        double boundary = shape.fitDouble(-boundaryVariation, boundaryVariation, worldX, worldY, worldZ);
        return normalized <= 1D + boundary;
    }
}

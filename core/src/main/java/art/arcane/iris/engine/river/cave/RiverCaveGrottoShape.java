package art.arcane.iris.engine.river.cave;

@FunctionalInterface
public interface RiverCaveGrottoShape {
    RiverCaveGrottoShape ELLIPSOID = (source, settings, dx, dy, dz) -> {
        double horizontalRadius = settings.grottoHorizontalRadius();
        double verticalRadius = settings.grottoVerticalRadius();
        double normalized = ((double) dx * dx / (horizontalRadius * horizontalRadius))
                + ((double) dy * dy / (verticalRadius * verticalRadius))
                + ((double) dz * dz / (horizontalRadius * horizontalRadius));
        return normalized <= 1D;
    };

    boolean contains(
            RiverCaveSource source,
            RiverCavePlannerSettings settings,
            int offsetX,
            int offsetY,
            int offsetZ
    );
}

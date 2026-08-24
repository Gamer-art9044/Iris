package art.arcane.iris.engine.river;

import java.util.Objects;

public record RiverReach(
        RiverEdgeId id,
        RiverNode from,
        RiverNode to,
        RiverRouteState state,
        int flow,
        int order,
        double width,
        double bankWidth,
        double depth,
        RiverBodyProfile bodyProfile,
        boolean mouth,
        boolean terminal,
        RiverPolyline polyline
) {
    public RiverReach {
        Objects.requireNonNull(id);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(state);
        Objects.requireNonNull(bodyProfile);
        Objects.requireNonNull(polyline);
        if (state == RiverRouteState.SUPPRESSED) {
            throw new IllegalArgumentException("Suppressed routes cannot produce reaches");
        }
        if (flow < 1 || order < 1) {
            throw new IllegalArgumentException("River reach flow and order must be positive");
        }
        if (!Double.isFinite(width) || width <= 0.0 || !Double.isFinite(bankWidth) || bankWidth < 0.0
                || !Double.isFinite(depth) || depth <= 0.0) {
            throw new IllegalArgumentException("River reach dimensions must be finite and valid");
        }
        if (Double.compare(width, bodyProfile.maximumWidth()) != 0
                || Double.compare(bankWidth, bodyProfile.maximumBankWidth()) != 0
                || Double.compare(depth, bodyProfile.maximumDepth()) != 0) {
            throw new IllegalArgumentException("River reach dimensions must equal their body-profile maxima");
        }
    }

    public double widthAt(double alongReach) {
        return bodyProfile.width(alongReach);
    }

    public double bankWidthAt(double alongReach) {
        return bodyProfile.bankWidth(alongReach);
    }

    public double depthAt(double alongReach) {
        return bodyProfile.depth(alongReach);
    }

    public double roofScaleAt(double alongReach) {
        return bodyProfile.roofScale(alongReach);
    }
}

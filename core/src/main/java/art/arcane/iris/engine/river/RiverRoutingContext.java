package art.arcane.iris.engine.river;

import java.util.Objects;
import java.util.function.Supplier;

public final class RiverRoutingContext {
    private final RiverEdgeId edgeId;
    private final RiverNode from;
    private final RiverNode to;
    private final Supplier<RiverPolyline> polylineSupplier;
    private volatile RiverPolyline polyline;

    public RiverRoutingContext(RiverEdgeId edgeId, RiverNode from, RiverNode to, RiverPolyline polyline) {
        this(edgeId, from, to, () -> polyline, Objects.requireNonNull(polyline));
    }

    static RiverRoutingContext lazy(
            RiverEdgeId edgeId,
            RiverNode from,
            RiverNode to,
            Supplier<RiverPolyline> polylineSupplier
    ) {
        return new RiverRoutingContext(edgeId, from, to, polylineSupplier, null);
    }

    private RiverRoutingContext(
            RiverEdgeId edgeId,
            RiverNode from,
            RiverNode to,
            Supplier<RiverPolyline> polylineSupplier,
            RiverPolyline polyline
    ) {
        this.edgeId = Objects.requireNonNull(edgeId);
        this.from = Objects.requireNonNull(from);
        this.to = Objects.requireNonNull(to);
        this.polylineSupplier = Objects.requireNonNull(polylineSupplier);
        this.polyline = polyline;
    }

    public RiverEdgeId edgeId() {
        return edgeId;
    }

    public RiverNode from() {
        return from;
    }

    public RiverNode to() {
        return to;
    }

    public RiverPolyline polyline() {
        RiverPolyline resolved = polyline;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (polyline == null) {
                polyline = Objects.requireNonNull(polylineSupplier.get());
            }
            return polyline;
        }
    }

    public int midpointX() {
        return (int) StrictMath.max(
                Integer.MIN_VALUE,
                StrictMath.min(Integer.MAX_VALUE, StrictMath.round((from.x() + to.x()) * 0.5))
        );
    }

    public int midpointZ() {
        return (int) StrictMath.max(
                Integer.MIN_VALUE,
                StrictMath.min(Integer.MAX_VALUE, StrictMath.round((from.z() + to.z()) * 0.5))
        );
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof RiverRoutingContext context)) {
            return false;
        }
        return edgeId.equals(context.edgeId)
                && from.equals(context.from)
                && to.equals(context.to)
                && polyline().equals(context.polyline());
    }

    @Override
    public int hashCode() {
        return Objects.hash(edgeId, from, to, polyline());
    }

    @Override
    public String toString() {
        return "RiverRoutingContext[edgeId=" + edgeId
                + ", from=" + from
                + ", to=" + to
                + ", polyline=" + polyline() + "]";
    }
}

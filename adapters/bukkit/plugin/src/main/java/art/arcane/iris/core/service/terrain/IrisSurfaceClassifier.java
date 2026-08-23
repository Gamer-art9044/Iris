package art.arcane.iris.core.service.terrain;

import art.arcane.iris.api.terrain.IrisSurfaceKind;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.runtime.IrisRiverSurfaceSample;

public final class IrisSurfaceClassifier {
    private IrisSurfaceClassifier() {
    }

    public static boolean requiresSurfaceBiome(int engineSurfaceHeight, int engineFluidHeight) {
        return engineSurfaceHeight > 0 && engineSurfaceHeight > engineFluidHeight;
    }

    public static IrisSurfaceKind classify(int engineSurfaceHeight, int engineFluidHeight, InferredType inferredType) {
        if (engineSurfaceHeight <= 0) {
            return IrisSurfaceKind.VOID;
        }

        if (engineSurfaceHeight <= engineFluidHeight) {
            return IrisSurfaceKind.OCEAN;
        }

        return inferredType == InferredType.SHORE ? IrisSurfaceKind.SHORE : IrisSurfaceKind.LAND;
    }

    public static IrisSurfaceKind classify(
            int engineSurfaceHeight,
            int engineFluidHeight,
            InferredType inferredType,
            IrisRiverSurfaceSample riverSurface
    ) {
        if (engineSurfaceHeight <= 0) {
            return IrisSurfaceKind.VOID;
        }
        if (riverSurface != null && riverSurface.river().present() && !riverSurface.subterranean()) {
            if (riverSurface.river().state() == RiverRouteState.DRY) {
                return riverSurface.river().section() == RiverSection.DRY_CHANNEL
                        ? IrisSurfaceKind.DRY_CHANNEL
                        : IrisSurfaceKind.LAND;
            }
            if (riverSurface.river().state() == RiverRouteState.WET) {
                RiverSection section = riverSurface.river().section();
                if (section == RiverSection.BANK) {
                    return IrisSurfaceKind.RIVER_SHORE;
                }
                if (section == RiverSection.CHANNEL || section == RiverSection.MOUTH) {
                    return IrisSurfaceKind.RIVER;
                }
            }
        }
        return classify(engineSurfaceHeight, engineFluidHeight, inferredType);
    }
}

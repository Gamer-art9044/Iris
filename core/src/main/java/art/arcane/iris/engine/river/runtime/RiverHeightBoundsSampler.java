package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.util.project.interpolation.NoiseBounds;

@FunctionalInterface
public interface RiverHeightBoundsSampler {
    NoiseBounds sample(int blockX, int blockZ);
}

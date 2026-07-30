package art.arcane.iris.engine.framework;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.volmlib.util.collection.KList;

import java.util.Objects;

public final class StructurePlacementScope {
    private StructurePlacementScope() {
    }

    public static KList<IrisStructurePlacement> placementsAt(Engine engine, int chunkX, int chunkZ) {
        Engine activeEngine = Objects.requireNonNull(engine, "Structure placement scope requires an engine");
        IrisComplex complex = activeEngine.getComplex();
        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;
        KList<IrisStructurePlacement> placements = new KList<>();
        if (complex != null) {
            IrisBiome biome = complex.getTrueBiomeStream().get(blockX, blockZ);
            IrisRegion region = complex.getRegionStream().get(blockX, blockZ);
            if (biome != null && biome.getStructures() != null) {
                placements.addAll(biome.getStructures());
            }
            if (region != null && region.getStructures() != null) {
                placements.addAll(region.getStructures());
            }
        }
        if (activeEngine.getDimension() != null && activeEngine.getDimension().getStructures() != null) {
            placements.addAll(activeEngine.getDimension().getStructures());
        }
        return placements;
    }
}

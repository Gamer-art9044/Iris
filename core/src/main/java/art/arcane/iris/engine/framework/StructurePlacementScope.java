package art.arcane.iris.engine.framework;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.volmlib.util.collection.KList;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

public final class StructurePlacementScope {
    private StructurePlacementScope() {
    }

    public static KList<IrisStructurePlacement> placementsAt(Engine engine, int chunkX, int chunkZ) {
        Engine activeEngine = Objects.requireNonNull(engine, "Structure placement scope requires an engine");
        IrisComplex complex = activeEngine.getComplex();
        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;
        KList<IrisStructurePlacement> placements = new KList<>();
        Set<IrisStructurePlacement> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (complex != null) {
            IrisBiome biome = complex.getTrueBiomeStream().get(blockX, blockZ);
            IrisRegion region = complex.getRegionStream().get(blockX, blockZ);
            addUnique(placements, seen, biome == null ? null : biome.getStructures());
            addUnique(placements, seen, region == null ? null : region.getStructures());
        }
        if (activeEngine.getDimension() != null) {
            addUnique(placements, seen, activeEngine.getDimension().getStructures());
        }
        return placements;
    }

    private static void addUnique(KList<IrisStructurePlacement> destination,
                                  Set<IrisStructurePlacement> seen,
                                  KList<IrisStructurePlacement> source) {
        if (source == null) {
            return;
        }
        for (IrisStructurePlacement placement : source) {
            if (placement != null && seen.add(placement)) {
                destination.add(placement);
            }
        }
    }
}

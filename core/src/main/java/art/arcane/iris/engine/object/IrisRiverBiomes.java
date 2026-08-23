package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KSet;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Biome pools used by dimension-level river sections and contained river caves.")
@Data
public class IrisRiverBiomes {
    @Desc("Noise used to select a biome inside the active river-section pool.")
    private IrisGeneratorStyle selectionStyle = new IrisGeneratorStyle(NoiseStyle.CELLULAR_IRIS_DOUBLE)
            .zoomed(512D);

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Biome pool for wet river channels.")
    private KList<String> channel = new KList<>();

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Biome pool for river banks outside the wet channel.")
    private KList<String> bank = new KList<>();

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Biome pool for river reaches meeting natural sea.")
    private KList<String> mouth = new KList<>();

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Biome pool for dry river channels and terminal tapers.")
    private KList<String> dry = new KList<>();

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Cave biome pool for accepted contained river cave bodies.")
    private KList<String> floodedCave = new KList<>();

    public KSet<String> getAllBiomeIds() {
        KSet<String> biomeIds = new KSet<>();
        addAll(biomeIds, channel);
        addAll(biomeIds, bank);
        addAll(biomeIds, mouth);
        addAll(biomeIds, dry);
        addAll(biomeIds, floodedCave);
        return biomeIds;
    }

    private static void addAll(KSet<String> destination, KList<String> source) {
        if (source != null) {
            destination.addAll(source);
        }
    }
}

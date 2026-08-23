package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KSet;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Nullable river settings overridden by a region or natural biome without changing graph identity.")
@Data
public class IrisRiverOverride {
    @Desc("Whether new river sources may begin in this area. Existing trunks are unaffected.")
    private Boolean allowSources = null;

    @Desc("How downstream routing treats this area.")
    private IrisRiverRoutingPolicy routingPolicy = null;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Multiplier applied to downstream routing cost.")
    private Double routingCostMultiplier = null;

    @MinNumber(0.0001)
    @MaxNumber(16)
    @Desc("Multiplier applied to wet channel width.")
    private Double widthMultiplier = null;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("Multiplier applied to river bank width.")
    private Double bankWidthMultiplier = null;

    @MinNumber(0.0001)
    @MaxNumber(16)
    @Desc("Multiplier applied to river-bed depth.")
    private Double depthMultiplier = null;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("Multiplier applied to maximum terrain incision.")
    private Double maxIncisionMultiplier = null;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("Multiplier applied to reach continuation probability.")
    private Double continuationChanceMultiplier = null;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("Multiplier applied to cave-entry probability.")
    private Double caveEntryMultiplier = null;

    @Desc("Optional terminal behavior override for failed routes in this area.")
    private IrisRiverTerminalMode terminalMode = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Optional replacement biome pool for wet river channels. Empty explicitly disables this pool.")
    private KList<String> channelBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Optional replacement biome pool for river banks. Empty explicitly disables this pool.")
    private KList<String> bankBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Optional replacement biome pool for river mouths. Empty explicitly disables this pool.")
    private KList<String> mouthBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Optional replacement biome pool for dry river channels. Empty explicitly disables this pool.")
    private KList<String> dryBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Optional replacement cave biome pool for accepted contained river cave bodies. Empty explicitly disables this pool.")
    private KList<String> floodedCaveBiomes = null;

    public KSet<String> getAllBiomeIds() {
        KSet<String> biomeIds = new KSet<>();
        addAll(biomeIds, channelBiomes);
        addAll(biomeIds, bankBiomes);
        addAll(biomeIds, mouthBiomes);
        addAll(biomeIds, dryBiomes);
        addAll(biomeIds, floodedCaveBiomes);
        return biomeIds;
    }

    private static void addAll(KSet<String> destination, KList<String> source) {
        if (source != null) {
            destination.addAll(source);
        }
    }
}

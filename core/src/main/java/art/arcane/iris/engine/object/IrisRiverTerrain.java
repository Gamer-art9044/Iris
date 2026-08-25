package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.Required;
import art.arcane.volmlib.util.collection.KList;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls river channel geometry, banks, incision, meanders, and terminal tapering.")
@Data
public class IrisRiverTerrain {
    @Desc("The wet channel width in blocks before stream-order scaling.")
    private IrisStyledRange channelWidth = range(8D, 20D, NoiseStyle.IRIS, 1024D);

    @Desc("The bank width outside the wet channel in blocks.")
    private IrisStyledRange bankWidth = range(5D, 18D, NoiseStyle.IRIS, 1024D);

    @Desc("The wet-bed depth below the local water surface, or dry-channel depth below natural terrain, in blocks.")
    private IrisStyledRange depth = range(2D, 7D, NoiseStyle.IRIS, 768D);

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("The radius added to every channel after worm, regional, local, and stream-order shaping.")
    private double channelRadiusBonus = 0D;

    @MinNumber(1)
    @MaxNumber(2048)
    @Desc("The final wet-channel width cap after region, biome, and stream-order scaling.")
    private double maxChannelWidth = 10D;

    @MinNumber(0)
    @MaxNumber(2048)
    @Desc("The final bank-width cap on each side after region and biome scaling.")
    private double maxBankWidth = 4D;

    @MinNumber(1)
    @MaxNumber(512)
    @Desc("The final river-depth cap after region, biome, and stream-order scaling.")
    private double maxDepth = 10D;

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("Additional channel-width fraction applied for each merged upstream flow order.")
    private double orderWidthFactor = 0.35D;

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("Additional river-bed depth fraction applied for each merged upstream flow order.")
    private double orderDepthFactor = 0.2D;

    @Desc("Selects whether a complete graph reach may incise terrain. A rejected reach follows terminal behavior.")
    private IrisRiverNoiseChance incision = new IrisRiverNoiseChance();

    @MinNumber(0)
    @MaxNumber(512)
    @Desc("The greatest permitted vertical incision below natural terrain.")
    private int maxIncision = 48;

    @MinNumber(0.125)
    @MaxNumber(16)
    @Desc("The exponent shaping the channel-to-bank cross-section transition.")
    private double bankExponent = 2D;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("The longitudinal transition length and maximum lateral and roof flare where a surface river enters or exits solid terrain.")
    private double tunnelMouthBlend = 2D;

    @Desc("Noise modulating the submerged floor of river tunnels.")
    private IrisGeneratorStyle tunnelFloorStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(48D);

    @Desc("The subterranean tunnel width multiplier relative to the surface river width.")
    private IrisStyledRange tunnelWidthMultiplier = range(1D, 1D, NoiseStyle.FLAT, 1D);

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("The maximum vertical floor variation in river tunnels.")
    private double tunnelFloorVariation = 2D;

    @Desc("Noise modulating the dry roof of river tunnels.")
    private IrisGeneratorStyle tunnelRoofStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(64D);

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("The maximum vertical roof variation in river tunnels.")
    private double tunnelRoofVariation = 3D;

    @Required
    @ArrayType(min = 1, type = IrisRiverWorm.class)
    @Desc("Weighted root Perlin-worm families with inherited child styles for trunks and tributaries.")
    private KList<IrisRiverWorm> worms = new KList<IrisRiverWorm>();

    @Desc("Modulates small river-bed height variation after the connected channel shape is solved.")
    private IrisGeneratorStyle bedRoughnessStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(96D);

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("The maximum river-bed roughness in blocks.")
    private double bedRoughness = 0.75D;

    @Desc("The behavior used when a graph route cannot continue as a wet channel.")
    private IrisRiverTerminalMode terminalMode = IrisRiverTerminalMode.DRY_CHANNEL;

    @MinNumber(8)
    @MaxNumber(1024)
    @Desc("The distance in blocks over which a terminal channel returns to natural terrain.")
    private int terminalTaper = 64;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("The probability that a failed wet route continues as a tapered dry channel.")
    private double dryContinuationChance = 1D;

    private static IrisStyledRange range(double min, double max, NoiseStyle style, double zoom) {
        return new IrisStyledRange(min, max, new IrisGeneratorStyle(style).zoomed(zoom));
    }
}

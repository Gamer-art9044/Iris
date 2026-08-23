package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
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

    @Desc("Modulates perpendicular spline displacement while preserving graph endpoints.")
    private IrisGeneratorStyle meanderStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(512D);

    @MinNumber(0)
    @MaxNumber(1024)
    @Desc("The maximum perpendicular meander displacement in blocks.")
    private double meanderStrength = 72D;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("The number of straight segments used to flatten each meandering graph reach.")
    private int meanderSubdivisions = 8;

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

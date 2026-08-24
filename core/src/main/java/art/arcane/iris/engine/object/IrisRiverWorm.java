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
@Desc("One weighted Perlin-worm river shape and its channel proportions.")
@Data
public class IrisRiverWorm {
    @Required
    @Desc("Unique lowercase identifier for this root or child style.")
    private String id = "river";

    @Desc("Stable salt for this Perlin field pair.")
    private long seed = 1L;

    @MinNumber(0.000001)
    @MaxNumber(1000000)
    @Desc("Relative probability when selecting this root family or one child transition.")
    private double weight = 1D;

    @MinNumber(8)
    @MaxNumber(16384)
    @Desc("Primary gradient-Perlin wavelength in blocks.")
    private double wavelength = 1024D;

    @MinNumber(8)
    @MaxNumber(16384)
    @Desc("Secondary gradient-Perlin wavelength in blocks.")
    private double detailWavelength = 256D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Primary heading deviation as a fraction of 180 degrees.")
    private double tortuosity = 0.5D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Secondary heading deviation as a fraction of 180 degrees.")
    private double detailTortuosity = 0.15D;

    @MinNumber(0)
    @MaxNumber(1024)
    @Desc("Maximum endpoint-bridged displacement from the reach chord in blocks.")
    private double maxOffset = 320D;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Number of deterministic Perlin-worm steps used to resolve the reach.")
    private int segments = 48;

    @MinNumber(0.125)
    @MaxNumber(8)
    @Desc("Channel-width multiplier for reaches selecting this worm.")
    private double widthMultiplier = 1D;

    @MinNumber(0.125)
    @MaxNumber(8)
    @Desc("Bank-width multiplier for reaches selecting this worm.")
    private double bankMultiplier = 1D;

    @MinNumber(0.125)
    @MaxNumber(8)
    @Desc("Depth multiplier for reaches selecting this worm.")
    private double depthMultiplier = 1D;

    @MinNumber(32)
    @MaxNumber(16384)
    @Desc("Primary world-space wavelength controlling longitudinal body swelling and pinching.")
    private double bodyWavelength = 512D;

    @MinNumber(32)
    @MaxNumber(16384)
    @Desc("Detail wavelength adding smaller changes to the longitudinal body profile.")
    private double bodyDetailWavelength = 128D;

    @MinNumber(0)
    @MaxNumber(0.875)
    @Desc("Maximum proportional channel-width variation along this style's body.")
    private double widthVariation = 0D;

    @MinNumber(0)
    @MaxNumber(0.875)
    @Desc("Maximum proportional bank or basin-width variation along this style's body.")
    private double bankVariation = 0D;

    @MinNumber(0)
    @MaxNumber(0.875)
    @Desc("Maximum proportional bed-depth variation along this style's body.")
    private double depthVariation = 0D;

    @MinNumber(0)
    @MaxNumber(0.875)
    @Desc("Maximum downward variation of tunnel roof clearance without exceeding the authored cave headroom.")
    private double roofVariation = 0D;

    @MinNumber(1)
    @MaxNumber(8)
    @Desc("Number of upstream children admitted before additional siblings decay probabilistically.")
    private int branchCap = 4;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Multiplicative survival probability for every sibling beyond branchCap.")
    private double branchDecay = 0.35D;

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("Multiplier applied to the dimension confluence attraction for this style.")
    private double confluenceMultiplier = 1D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Chance that an upstream continuation mutates from this style into one weighted child.")
    private double childChance = 0D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Additional child-mutation chance for each sibling slot beyond the primary branch.")
    private double branchChildChance = 0D;

    @ArrayType(type = IrisRiverWorm.class)
    @Desc("Weighted descendant styles inherited by the complete upstream lineage after mutation.")
    private KList<IrisRiverWorm> children = new KList<IrisRiverWorm>();
}

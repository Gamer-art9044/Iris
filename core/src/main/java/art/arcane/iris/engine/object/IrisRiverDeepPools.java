package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls sparse, river-anchored fluid pools attached to deep cave floors.")
@Data
public class IrisRiverDeepPools {
    @Desc("Enables independently configured deep cave pools along eligible wet river reaches.")
    private boolean enabled = false;

    @Desc("Selects complete wet river reaches that may host deep pools.")
    private IrisRiverNoiseChance reach = new IrisRiverNoiseChance()
            .setChance(1D / 3D)
            .setStyle(new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(4096D))
            .setInfluence(0.08D);

    @MinNumber(16)
    @MaxNumber(4096)
    @Desc("The minimum distance in blocks between deep-pool candidates.")
    private int minimumSpacing = 768;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("The maximum accepted deep pools on one river reach.")
    private int maximumPerReach = 1;

    @MinNumber(-2048)
    @MaxNumber(2048)
    @Desc("The lowest absolute world Y considered for the pool fluid surface.")
    private int minimumFluidY = -224;

    @MinNumber(-2048)
    @MaxNumber(2048)
    @Desc("The highest absolute world Y considered for the pool fluid surface.")
    private int maximumFluidY = -104;

    @MinNumber(0)
    @MaxNumber(256)
    @Desc("The horizontal distance searched from a river anchor for a contained cave floor.")
    private int searchRadius = 16;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("The number of deterministic nearby columns tested for a contained cave floor.")
    private int searchAttempts = 12;

    @MinNumber(2)
    @MaxNumber(128)
    @Desc("The horizontal radius of the generated deep-pool chamber.")
    private int horizontalRadius = 18;

    @MinNumber(2)
    @MaxNumber(64)
    @Desc("The vertical radius of the generated deep-pool chamber.")
    private int verticalRadius = 8;

    @MinNumber(1)
    @MaxNumber(63)
    @Desc("The dry chamber height retained above the deep-pool fluid surface.")
    private int dryHeadroom = 4;

    @Desc("Noise shaping the deep-pool chamber boundary.")
    private IrisGeneratorStyle shapeStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(12D);

    @MinNumber(0)
    @MaxNumber(0.75)
    @Desc("The proportional noise displacement applied to the deep-pool chamber boundary.")
    private double shapeVariation = 0.5D;

    @Desc("Noise warping the deep-pool chamber coordinate field.")
    private IrisGeneratorStyle warpStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(24D);

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("The maximum coordinate warp applied to the deep-pool chamber in blocks.")
    private double warpStrength = 6D;

    @MinNumber(64)
    @MaxNumber(1048576)
    @Desc("The greatest generated deep-pool chamber volume that may be transactionally published.")
    private int maximumVolume = 32768;

    @Desc("The fluid palette used only by accepted deep pools.")
    private IrisMaterialPalette fluidPalette = new IrisMaterialPalette().qclear().qadd("lava");
}

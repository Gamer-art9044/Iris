package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls bounded and transactionally validated river-to-cave connections.")
@Data
public class IrisRiverCaves {
    @Desc("The contained cave-water behavior available to river entry events.")
    private IrisRiverCaveMode mode = IrisRiverCaveMode.SEALED;

    @Desc("Selects cave-entry stations at stable river-reach anchors.")
    private IrisRiverNoiseChance entry = new IrisRiverNoiseChance()
            .setChance(0.12D)
            .setStyle(new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(1024D))
            .setInfluence(0.4D);

    @MinNumber(16)
    @MaxNumber(4096)
    @Desc("The minimum distance in blocks between cave-entry candidates.")
    private int minimumSpacing = 128;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("The maximum entry-noise-eligible cave anchors accepted on one ordinary reach. A forced sinkhole terminal uses its reach exclusively and requires this value above zero.")
    private int maximumPerReach = 1;

    @MinNumber(1)
    @MaxNumber(256)
    @Desc("The maximum vertical distance searched while boring from river bed to cave.")
    private int maxBoreDepth = 48;

    @MinNumber(1)
    @MaxNumber(16)
    @Desc("The radius in blocks of a generated river-to-cave throat.")
    private int throatRadius = 2;

    @MinNumber(-64)
    @MaxNumber(64)
    @Desc("The offset applied to river water height when filling an accepted cave body.")
    private int waterLevelOffset = 0;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("The minimum dry headroom retained above water in generated grottos.")
    private int dryHeadroom = 4;

    @MinNumber(2)
    @MaxNumber(128)
    @Desc("The horizontal radius in blocks of a generated sealed grotto.")
    private int grottoHorizontalRadius = 12;

    @MinNumber(2)
    @MaxNumber(128)
    @Desc("The vertical radius in blocks of a generated sealed grotto.")
    private int grottoVerticalRadius = 7;

    @Desc("Noise shaping the boundary of generated sealed grottos.")
    private IrisGeneratorStyle grottoShapeStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(24D);

    @Desc("Noise warping the coordinate field used for generated sealed grottos.")
    private IrisGeneratorStyle grottoWarpStyle = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(48D);

    @MinNumber(0)
    @MaxNumber(32)
    @Desc("The maximum coordinate warp applied to generated sealed grottos in blocks.")
    private double grottoWarpStrength = 2D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("The fraction of river-cave boundary columns that inherit the naturally resolved parent cave or surface biome instead of a flooded-cave override.")
    private double parentBiomeInheritance = 0.5D;

    @MinNumber(4)
    @MaxNumber(256)
    @Desc("The horizontal proof radius for an existing closed cave component.")
    private int maxFloodRadius = 48;

    @MinNumber(4)
    @MaxNumber(256)
    @Desc("The vertical proof depth for an existing closed cave component.")
    private int maxFloodDepth = 32;

    @MinNumber(64)
    @MaxNumber(1048576)
    @Desc("The greatest cave-component volume that may be fully proven and flooded.")
    private int maxFloodVolume = 8192;

    @Desc("The behavior used when a requested cave connection cannot be proven safe.")
    private IrisRiverCaveFallback fallback = IrisRiverCaveFallback.SEALED;

    @Desc("The policy for fluid already present in a candidate contained cave body.")
    private IrisRiverExistingFluidPolicy existingFluidPolicy = IrisRiverExistingFluidPolicy.REJECT;

    @Desc("Sparse, independently filled cave-floor pools generated at deep river-network anchors.")
    private IrisRiverDeepPools deepPools = new IrisRiverDeepPools();
}

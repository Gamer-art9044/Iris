package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Dimension-owned settings for the deterministic connected river graph.")
@Data
public class IrisRiverTopology {
    @MinNumber(64)
    @MaxNumber(4096)
    @Desc("The routing-cell width in blocks. This controls graph identity and cannot be overridden by regions or biomes.")
    private int cellSize = 512;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("The number of routing cells grouped into one immutable river cache tile.")
    private int tileCells = 4;

    @MinNumber(0)
    @MaxNumber(0.49)
    @Desc("The fraction of a routing cell used to jitter its graph node away from the center.")
    private double siteJitter = 0.35D;

    @MinNumber(1)
    @MaxNumber(256)
    @Desc("The maximum number of directed graph reaches followed by one source route.")
    private int maxRouteReaches = 16;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("The minimum number of noise-weighted source nodes selected in each routing tile while source chance is above zero.")
    private int minimumSourcesPerTile = 0;

    @MinNumber(0)
    @MaxNumber(7)
    @Desc("The number of alternate downstream reaches inspected before declaring a sink.")
    private int sinkSearchReaches = 4;

    @MinNumber(8)
    @MaxNumber(256)
    @Desc("The spacing of deterministic drainage-basin sinks in routing cells. Larger values produce longer trunks and wider tributary trees.")
    private int routingBasinCells = 64;

    @MinNumber(8)
    @MaxNumber(256)
    @Desc("The wavelength in routing cells of the smooth domain warp applied to drainage distance.")
    private int routingDeviationScaleCells = 24;

    @MinNumber(0)
    @MaxNumber(32)
    @Desc("The maximum drainage-domain displacement in routing cells. Zero keeps straight radial basin gradients.")
    private double routingDeviationStrengthCells = 0D;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("The horizontal basin-distance span in routing cells per one block of terraced water rise.")
    private double routingPlateauHeight = 8D;

    @Desc("Selects complete river source routes at stable graph nodes.")
    private IrisRiverNoiseChance source = new IrisRiverNoiseChance()
            .setChance(0.05D)
            .setStyle(new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(8192D))
            .setInfluence(0.035D);

    @Desc("Selects complete continuation reaches. A rejected reach terminates or suppresses its route rather than creating a gap.")
    private IrisRiverNoiseChance continuation = new IrisRiverNoiseChance()
            .setChance(0.99D)
            .setStyle(new IrisGeneratorStyle(NoiseStyle.VASCULAR).zoomed(4096D))
            .setInfluence(0.01D);

    @Desc("Adds deterministic cost variation while choosing downstream graph neighbors.")
    private IrisGeneratorStyle routingStyle = new IrisGeneratorStyle(NoiseStyle.VASCULAR).zoomed(8192D);

    @MinNumber(0)
    @MaxNumber(1024)
    @Desc("The maximum routing-cost contribution from routingStyle.")
    private double routingNoiseWeight = 24D;

    @MinNumber(0)
    @MaxNumber(1024)
    @Desc("The penalty for choosing a downstream edge that does not follow the local routingStyle tangent.")
    private double flowAlignmentWeight = 24D;

    @MinNumber(0)
    @MaxNumber(1024)
    @Desc("The deterministic attraction toward shared downstream nodes. Larger values form stronger tributary trees and confluences.")
    private double confluenceWeight = 0D;

    @MinNumber(1)
    @MaxNumber(8)
    @Desc("The number of upstream children a graph node accepts before additional branches begin shrinking probabilistically.")
    private int branchSoftCap = 4;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("The multiplicative survival factor for each child beyond branchSoftCap. Recursive generations remain unbounded by depth.")
    private double branchChildShrinkFactor = 0.35D;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("The contribution of natural terrain height to downstream routing cost.")
    private double terrainHeightWeight = 0.7D;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("The contribution of natural terrain slope to downstream routing cost.")
    private double terrainSlopeWeight = 0.35D;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("The routing preference toward natural sea outlets.")
    private double oceanAttraction = 1D;

    @Desc("Require every wet source route to reach natural sea or a proven sea-reaching trunk.")
    private boolean requireOcean = false;
}

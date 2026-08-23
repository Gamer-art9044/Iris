package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Dimension-owned configuration for connected surface rivers and contained river cave water.")
@Data
public class IrisRiverNetwork {
    @Desc("Enable the river network for this dimension.")
    private boolean enabled = false;

    @Desc("Dimension-owned connected graph and source-selection settings.")
    private IrisRiverTopology topology = new IrisRiverTopology();

    @Desc("Channel geometry, terrain incision, meanders, and terminal behavior.")
    private IrisRiverTerrain terrain = new IrisRiverTerrain();

    @Desc("River water-surface settings.")
    private IrisRiverWater water = new IrisRiverWater();

    @Desc("Dimension-level biome pools for river sections and contained river caves.")
    private IrisRiverBiomes biomes = new IrisRiverBiomes();

    @Desc("Bounded river-to-cave connection settings.")
    private IrisRiverCaves caves = new IrisRiverCaves();
}

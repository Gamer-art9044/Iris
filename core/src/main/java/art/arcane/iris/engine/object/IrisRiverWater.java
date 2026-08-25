package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls the river water-surface solver.")
@Data
public class IrisRiverWater {
    @Desc("The strategy used to determine river water-surface height.")
    private IrisRiverWaterMode mode = IrisRiverWaterMode.FIXED;

    @MinNumber(-2048)
    @MaxNumber(2048)
    @Desc("The base river fluid surface in absolute world Y, independent of the dimension ocean height.")
    private int fluidHeight = 63;

    @Desc("The river fluid palette used by surface channels, contained tunnels, grottos, and waterfall throats.")
    private IrisMaterialPalette fluidPalette = new IrisMaterialPalette().qclear().qadd("water");

    @MinNumber(8)
    @MaxNumber(4096)
    @Desc("The target length of each flat terraced pool in blocks.")
    private int poolLength = 96;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("The greatest terraced river height permitted above fluidHeight.")
    private int maximumPoolRise = 4;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("The vertical height of controlled drops between terraced pools.")
    private int dropHeight = 1;
}

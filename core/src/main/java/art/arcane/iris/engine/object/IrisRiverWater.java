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
    private IrisRiverWaterMode mode = IrisRiverWaterMode.SEA_LEVEL;

    @MinNumber(8)
    @MaxNumber(4096)
    @Desc("The target length of each flat terraced pool in blocks.")
    private int poolLength = 96;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("The greatest river water height permitted above the dimension fluid height.")
    private int maximumPoolRise = 4;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("The vertical height of controlled drops between terraced pools.")
    private int dropHeight = 1;
}

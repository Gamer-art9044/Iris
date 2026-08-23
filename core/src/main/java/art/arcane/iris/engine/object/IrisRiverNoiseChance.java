package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("A deterministic graph-event chance modulated by configurable noise.")
@Data
public class IrisRiverNoiseChance {
    @MinNumber(0)
    @MaxNumber(1)
    @Desc("The base probability before noise modulation.")
    private double chance = 1D;

    @Desc("The noise sampled once at the stable graph-event anchor.")
    private IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.FLAT);

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("The maximum centered noise contribution added to the base probability.")
    private double influence = 0D;
}

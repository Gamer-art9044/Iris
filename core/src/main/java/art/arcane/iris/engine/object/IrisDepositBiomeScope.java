package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Selects which biome identity is checked by a deposit's include and exclude filters.")
public enum IrisDepositBiomeScope {
    @Desc("Checks the surface biome selected for the deposit column.")
    SURFACE,
    @Desc("Checks the cave biome selected at the deposit origin.")
    CAVE
}

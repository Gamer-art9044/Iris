package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Selects how a river route ends when it cannot continue to an outlet.")
public enum IrisRiverTerminalMode {
    @Desc("Suppress the failed route instead of generating it.")
    SUPPRESS,

    @Desc("Continue as a dry channel that tapers back into natural terrain.")
    DRY_CHANNEL,

    @Desc("End in a contained underground grotto when cave hydrology accepts the connection.")
    SINKHOLE_GROTTO
}

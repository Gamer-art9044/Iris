package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverBiomes;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.iris.engine.object.IrisRiverRoutingPolicy;
import art.arcane.iris.engine.object.IrisRiverTerminalMode;
import art.arcane.volmlib.util.collection.KList;

import java.util.List;
import java.util.Objects;

public record EffectiveRiverSettings(
        boolean allowSources,
        IrisRiverRoutingPolicy routingPolicy,
        double routingCostMultiplier,
        double widthMultiplier,
        double bankWidthMultiplier,
        double depthMultiplier,
        double maxIncisionMultiplier,
        double continuationChanceMultiplier,
        double caveEntryMultiplier,
        IrisRiverTerminalMode terminalMode,
        boolean terminalModeOverridden,
        List<String> channelBiomes,
        List<String> bankBiomes,
        List<String> mouthBiomes,
        List<String> dryBiomes,
        List<String> floodedCaveBiomes
) {
    public EffectiveRiverSettings {
        Objects.requireNonNull(routingPolicy);
        Objects.requireNonNull(terminalMode);
        channelBiomes = List.copyOf(Objects.requireNonNull(channelBiomes));
        bankBiomes = List.copyOf(Objects.requireNonNull(bankBiomes));
        mouthBiomes = List.copyOf(Objects.requireNonNull(mouthBiomes));
        dryBiomes = List.copyOf(Objects.requireNonNull(dryBiomes));
        floodedCaveBiomes = List.copyOf(Objects.requireNonNull(floodedCaveBiomes));
    }

    public static EffectiveRiverSettings resolve(
            IrisRiverNetwork network,
            IrisRegion region,
            IrisBiome naturalBiome
    ) {
        Objects.requireNonNull(network);
        IrisRiverBiomes biomes = network.getBiomes() == null ? new IrisRiverBiomes() : network.getBiomes();
        Builder builder = new Builder(
                network.getTerrain().getTerminalMode(),
                copy(biomes.getChannel()),
                copy(biomes.getBank()),
                copy(biomes.getMouth()),
                copy(biomes.getDry()),
                copy(biomes.getFloodedCave())
        );
        if (region != null) {
            builder.apply(region.getRiverOverride());
        }
        if (naturalBiome != null) {
            builder.apply(naturalBiome.getRiverOverride());
        }
        return builder.build();
    }

    private static List<String> copy(KList<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static final class Builder {
        private boolean allowSources = true;
        private IrisRiverRoutingPolicy routingPolicy = IrisRiverRoutingPolicy.ALLOW;
        private double routingCostMultiplier = 1D;
        private double widthMultiplier = 1D;
        private double bankWidthMultiplier = 1D;
        private double depthMultiplier = 1D;
        private double maxIncisionMultiplier = 1D;
        private double continuationChanceMultiplier = 1D;
        private double caveEntryMultiplier = 1D;
        private IrisRiverTerminalMode terminalMode;
        private boolean terminalModeOverridden;
        private List<String> channelBiomes;
        private List<String> bankBiomes;
        private List<String> mouthBiomes;
        private List<String> dryBiomes;
        private List<String> floodedCaveBiomes;

        private Builder(
                IrisRiverTerminalMode terminalMode,
                List<String> channelBiomes,
                List<String> bankBiomes,
                List<String> mouthBiomes,
                List<String> dryBiomes,
                List<String> floodedCaveBiomes
        ) {
            this.terminalMode = terminalMode == null ? IrisRiverTerminalMode.DRY_CHANNEL : terminalMode;
            this.channelBiomes = channelBiomes;
            this.bankBiomes = bankBiomes;
            this.mouthBiomes = mouthBiomes;
            this.dryBiomes = dryBiomes;
            this.floodedCaveBiomes = floodedCaveBiomes;
        }

        private void apply(IrisRiverOverride override) {
            if (override == null) {
                return;
            }
            allowSources = override.getAllowSources() == null ? allowSources : override.getAllowSources();
            routingPolicy = override.getRoutingPolicy() == null ? routingPolicy : override.getRoutingPolicy();
            routingCostMultiplier = value(override.getRoutingCostMultiplier(), routingCostMultiplier);
            widthMultiplier = value(override.getWidthMultiplier(), widthMultiplier);
            bankWidthMultiplier = value(override.getBankWidthMultiplier(), bankWidthMultiplier);
            depthMultiplier = value(override.getDepthMultiplier(), depthMultiplier);
            maxIncisionMultiplier = value(override.getMaxIncisionMultiplier(), maxIncisionMultiplier);
            continuationChanceMultiplier = value(
                    override.getContinuationChanceMultiplier(),
                    continuationChanceMultiplier
            );
            caveEntryMultiplier = value(override.getCaveEntryMultiplier(), caveEntryMultiplier);
            if (override.getTerminalMode() != null) {
                terminalMode = override.getTerminalMode();
                terminalModeOverridden = true;
            }
            channelBiomes = replace(override.getChannelBiomes(), channelBiomes);
            bankBiomes = replace(override.getBankBiomes(), bankBiomes);
            mouthBiomes = replace(override.getMouthBiomes(), mouthBiomes);
            dryBiomes = replace(override.getDryBiomes(), dryBiomes);
            floodedCaveBiomes = replace(override.getFloodedCaveBiomes(), floodedCaveBiomes);
        }

        private EffectiveRiverSettings build() {
            return new EffectiveRiverSettings(
                    allowSources,
                    routingPolicy,
                    routingCostMultiplier,
                    widthMultiplier,
                    bankWidthMultiplier,
                    depthMultiplier,
                    maxIncisionMultiplier,
                    continuationChanceMultiplier,
                    caveEntryMultiplier,
                    terminalMode,
                    terminalModeOverridden,
                    channelBiomes,
                    bankBiomes,
                    mouthBiomes,
                    dryBiomes,
                    floodedCaveBiomes
            );
        }

        private static double value(Double configured, double inherited) {
            return configured == null || !Double.isFinite(configured) ? inherited : Math.max(0D, configured);
        }

        private static List<String> replace(KList<String> configured, List<String> inherited) {
            return configured == null ? inherited : List.copyOf(configured);
        }
    }
}

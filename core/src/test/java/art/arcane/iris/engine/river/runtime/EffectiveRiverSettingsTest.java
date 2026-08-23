package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverBiomes;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.iris.engine.object.IrisRiverRoutingPolicy;
import art.arcane.iris.engine.object.IrisRiverTerminalMode;
import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EffectiveRiverSettingsTest {
    @Test
    public void appliesRegionThenBiomeOverridesWithoutChangingGraphSettings() {
        IrisRiverNetwork network = new IrisRiverNetwork()
                .setBiomes(new IrisRiverBiomes()
                        .setChannel(new KList<>("dimension-channel"))
                        .setBank(new KList<>("dimension-bank")));
        IrisRegion region = new IrisRegion().setRiverOverride(new IrisRiverOverride()
                .setAllowSources(false)
                .setRoutingPolicy(IrisRiverRoutingPolicy.AVOID)
                .setWidthMultiplier(1.5D)
                .setChannelBiomes(new KList<>("region-channel")));
        IrisBiome biome = new IrisBiome().setRiverOverride(new IrisRiverOverride()
                .setRoutingPolicy(IrisRiverRoutingPolicy.BLOCK)
                .setWidthMultiplier(0.5D)
                .setChannelBiomes(new KList<>())
                .setTerminalMode(IrisRiverTerminalMode.SUPPRESS));

        EffectiveRiverSettings settings = EffectiveRiverSettings.resolve(network, region, biome);

        assertFalse(settings.allowSources());
        assertEquals(IrisRiverRoutingPolicy.BLOCK, settings.routingPolicy());
        assertEquals(0.5D, settings.widthMultiplier(), 0D);
        assertTrue(settings.channelBiomes().isEmpty());
        assertEquals(List.of("dimension-bank"), settings.bankBiomes());
        assertEquals(IrisRiverTerminalMode.SUPPRESS, settings.terminalMode());
        assertTrue(settings.terminalModeOverridden());
    }

    @Test
    public void inheritsDimensionDefaultsWhenOverridesAreAbsent() {
        IrisRiverNetwork network = new IrisRiverNetwork()
                .setBiomes(new IrisRiverBiomes().setDry(new KList<>("dry")));

        EffectiveRiverSettings settings = EffectiveRiverSettings.resolve(network, null, null);

        assertTrue(settings.allowSources());
        assertEquals(IrisRiverRoutingPolicy.ALLOW, settings.routingPolicy());
        assertEquals(1D, settings.routingCostMultiplier(), 0D);
        assertEquals(List.of("dry"), settings.dryBiomes());
        assertEquals(IrisRiverTerminalMode.DRY_CHANNEL, settings.terminalMode());
        assertFalse(settings.terminalModeOverridden());
    }
}

package art.arcane.iris.engine.modifier;

import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.matter.MatterCavern;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisCarveModifierFluidIntentTest {
    @Test
    public void explicitCavernIntentsOverrideExistingFluid() {
        MatterCavern airIntent = new MatterCavern(true, "", (byte) 0);
        MatterCavern waterIntent = new MatterCavern(true, "", (byte) 1);
        MatterCavern lavaIntent = new MatterCavern(true, "", (byte) 2);
        MatterCavern forcedAirIntent = new MatterCavern(true, "", (byte) 3);
        PlatformBlockState existingFluid = mock(PlatformBlockState.class);
        PlatformBlockState fluid = mock(PlatformBlockState.class);
        PlatformBlockState lava = mock(PlatformBlockState.class);
        PlatformBlockState air = mock(PlatformBlockState.class);
        when(existingFluid.isFluid()).thenReturn(true);

        assertFalse(IrisCarveModifier.hasExplicitCarveIntent(null));
        assertTrue(IrisCarveModifier.shouldPreserveExistingFluid(airIntent, existingFluid));
        assertFalse(IrisCarveModifier.shouldPreserveExistingFluid(waterIntent, existingFluid));
        assertFalse(IrisCarveModifier.shouldPreserveExistingFluid(lavaIntent, existingFluid));
        assertFalse(IrisCarveModifier.shouldPreserveExistingFluid(forcedAirIntent, existingFluid));
        assertNull(IrisCarveModifier.resolveExplicitCarveState(null, fluid, lava, air));
        assertSame(fluid, IrisCarveModifier.resolveExplicitCarveState(waterIntent, fluid, lava, air));
        assertSame(lava, IrisCarveModifier.resolveExplicitCarveState(lavaIntent, fluid, lava, air));
        assertSame(air, IrisCarveModifier.resolveExplicitCarveState(forcedAirIntent, fluid, lava, air));
        assertNull(IrisCarveModifier.resolveExplicitCarveState(airIntent, fluid, lava, air));
    }

    @Test
    public void defaultLavaIncludesConfiguredBoundary() {
        assertTrue(IrisCarveModifier.usesDefaultLava(18, 17));
        assertTrue(IrisCarveModifier.usesDefaultLava(18, 18));
        assertFalse(IrisCarveModifier.usesDefaultLava(18, 19));
    }
}

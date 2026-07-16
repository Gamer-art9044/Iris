/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine.modifier;

import art.arcane.iris.spi.PlatformBlockState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisDepositModifierContainmentTest {
    @Test
    public void depositSurfaceLimitKeepsEveryColumnSevenBlocksBuried() {
        assertEquals(73, IrisDepositModifier.depositSurfaceLimit(80));
        assertEquals(24, IrisDepositModifier.depositSurfaceLimit(31));
    }

    @Test
    public void variantHeightUsesAbsoluteWorldY() {
        assertEquals(-32, IrisDepositModifier.absoluteWorldY(-64, 32));
        assertEquals(96, IrisDepositModifier.absoluteWorldY(0, 96));
    }

    @Test
    public void airAndFluidTargetsAreRejected() {
        PlatformBlockState solid = mock(PlatformBlockState.class);
        PlatformBlockState air = mock(PlatformBlockState.class);
        PlatformBlockState fluid = mock(PlatformBlockState.class);
        when(air.isAir()).thenReturn(true);
        when(fluid.isFluid()).thenReturn(true);

        assertTrue(IrisDepositModifier.canReplaceDepositTarget(solid));
        assertFalse(IrisDepositModifier.canReplaceDepositTarget(air));
        assertFalse(IrisDepositModifier.canReplaceDepositTarget(fluid));
        assertFalse(IrisDepositModifier.canReplaceDepositTarget(null));
    }
}

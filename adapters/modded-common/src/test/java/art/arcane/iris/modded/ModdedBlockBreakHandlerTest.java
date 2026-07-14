/*
 * Iris is a World Generator for Minecraft Servers
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

package art.arcane.iris.modded;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedBlockBreakHandlerTest {
    @Test
    public void exactMatchingIncludesPropertiesWhileTypeMatchingDoesNot() {
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BlockState east = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);

        assertTrue(ModdedBlockBreakHandler.matchesState(north, north, true));
        assertFalse(ModdedBlockBreakHandler.matchesState(north, east, true));
        assertTrue(ModdedBlockBreakHandler.matchesState(north, east, false));
        assertFalse(ModdedBlockBreakHandler.matchesState(north, Blocks.COBBLESTONE.defaultBlockState(), false));
    }
}

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

package art.arcane.iris.core.structure;

import art.arcane.iris.engine.object.IrisImportedStructureControl;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeStructureLocateCapabilityTest {
    @Test
    public void monumentLocateIsUnavailableWithoutChangingGenerationPolicy() {
        assertTrue(NativeStructureLocateCapability.isPaperUnavailable("minecraft:monument"));
        assertTrue(NativeStructureLocateCapability.isPaperUnavailable(" MINECRAFT:MONUMENT "));
        assertTrue(new IrisImportedStructureControl().shouldGenerate("minecraft:monument"));
    }

    @Test
    public void otherStructuresRemainLocatable() {
        assertFalse(NativeStructureLocateCapability.isPaperUnavailable(null));
        assertFalse(NativeStructureLocateCapability.isPaperUnavailable(""));
        assertFalse(NativeStructureLocateCapability.isPaperUnavailable("minecraft:stronghold"));
    }

    @Test
    public void messageStatesThatGenerationRemainsEnabled() {
        String message = NativeStructureLocateCapability.unavailableMessage().toLowerCase();
        assertTrue(message.contains("generation is enabled"));
        assertTrue(message.contains("locat"));
    }
}

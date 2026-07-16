/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.pack;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PackValidationRegistryTest {
    @Before
    public void setUp() {
        PackValidationRegistry.clear();
    }

    @After
    public void tearDown() {
        PackValidationRegistry.clear();
    }

    @Test
    public void missingValidationFailsClosed() {
        assertBroken("overworld", "has not completed");
    }

    @Test
    public void blockingValidationFailsClosedWithOriginalReasons() {
        PackValidationRegistry.publish(new PackValidationResult(
                "overworld", List.of("replacement graph is not runtime-viable"), List.of(), 1L));

        assertBroken("overworld", "replacement graph is not runtime-viable");
    }

    @Test
    public void successfulValidationAuthorizesUse() {
        PackValidationResult result = new PackValidationResult(
                "overworld", List.of(), List.of("warning"), 1L);
        PackValidationRegistry.publish(result);

        assertEquals(result, PackValidationRegistry.requireLoadable("overworld"));
    }

    private void assertBroken(String pack, String expectedReason) {
        try {
            PackValidationRegistry.requireLoadable(pack);
        } catch (BrokenPackException e) {
            assertEquals(pack, e.getPackName());
            assertTrue(e.getReasons().toString(), e.getReasons().stream().anyMatch(
                    reason -> reason.contains(expectedReason)));
            return;
        }
        throw new AssertionError("Expected pack validation to fail closed");
    }
}

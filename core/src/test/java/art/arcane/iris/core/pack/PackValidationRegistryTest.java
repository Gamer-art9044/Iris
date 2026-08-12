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
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PackValidationRegistryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

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

    @Test
    public void exactRootsWithTheSameBasenameRemainIndependent() throws Exception {
        Path firstRoot = temporaryFolder.newFolder("first").toPath().resolve("pack");
        Path secondRoot = temporaryFolder.newFolder("second").toPath().resolve("pack");
        PackValidationResult loadable = new PackValidationResult(
                "pack", List.of(), List.of(), 1L);
        PackValidationResult broken = new PackValidationResult(
                "pack", List.of("second snapshot is broken"), List.of(), 2L);

        PackValidationRegistry.publish(firstRoot, loadable);
        PackValidationRegistry.publish(secondRoot, broken);

        assertEquals(loadable, PackValidationRegistry.requireLoadable(firstRoot));
        assertEquals(broken, PackValidationRegistry.get(secondRoot));
        assertTrue(PackValidationRegistry.isBroken(secondRoot));
        assertNull(PackValidationRegistry.get("pack"));
        assertBroken(secondRoot, "second snapshot is broken");
    }

    @Test
    public void removingOneExactRootDoesNotEvictItsSameNamedSibling() throws Exception {
        Path firstRoot = temporaryFolder.newFolder("remove-first").toPath().resolve("pack");
        Path secondRoot = temporaryFolder.newFolder("keep-second").toPath().resolve("pack");
        PackValidationResult first = new PackValidationResult("pack", List.of(), List.of(), 1L);
        PackValidationResult second = new PackValidationResult("pack", List.of(), List.of(), 2L);
        PackValidationRegistry.publish(firstRoot, first);
        PackValidationRegistry.publish(secondRoot, second);

        PackValidationRegistry.remove(firstRoot);

        assertNull(PackValidationRegistry.get(firstRoot));
        assertEquals(second, PackValidationRegistry.requireLoadable(secondRoot));
    }

    @Test
    public void existingRootAliasesResolveToTheSameRealPath() throws Exception {
        Path realRoot = temporaryFolder.newFolder("real-pack").toPath();
        Path linkedRoot = realRoot.getParent().resolve("linked-pack");
        try {
            Files.createSymbolicLink(linkedRoot, realRoot);
        } catch (IOException | UnsupportedOperationException exception) {
            Assume.assumeNoException(exception);
        }
        PackValidationResult result = new PackValidationResult("pack", List.of(), List.of(), 1L);

        PackValidationRegistry.publish(linkedRoot, result);

        assertEquals(result, PackValidationRegistry.requireLoadable(realRoot));
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

    private void assertBroken(Path packRoot, String expectedReason) {
        try {
            PackValidationRegistry.requireLoadable(packRoot);
        } catch (BrokenPackException e) {
            assertEquals(packRoot.toAbsolutePath().normalize().toString(), e.getPackName());
            assertTrue(e.getReasons().toString(), e.getReasons().stream().anyMatch(
                    reason -> reason.contains(expectedReason)));
            return;
        }
        throw new AssertionError("Expected pack validation to fail closed");
    }
}

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

package art.arcane.iris.core.service;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

public class StudioSVCManagedBetaPackTest {
    @Test
    public void startupSelectsOnlyMissingManagedBetaPacks() throws IOException {
        Path workspace = Files.createTempDirectory("iris-managed-beta-startup");
        try {
            assertEquals(
                    List.of("overworld", "underworld"),
                    StudioSVC.missingManagedBetaPacks(workspace.toFile())
            );

            createPack(workspace, "overworld");
            assertEquals(
                    List.of("underworld"),
                    StudioSVC.missingManagedBetaPacks(workspace.toFile())
            );

            createDimension(workspace, "underworld", "underworld_roof");
            assertEquals(
                    List.of("underworld"),
                    StudioSVC.missingManagedBetaPacks(workspace.toFile())
            );

            createPack(workspace, "underworld");
            assertEquals(List.of(), StudioSVC.missingManagedBetaPacks(workspace.toFile()));
        } finally {
            deleteTree(workspace);
        }
    }

    private static void createPack(Path workspace, String key) throws IOException {
        createDimension(workspace, key, key);
    }

    private static void createDimension(Path workspace, String folder, String key) throws IOException {
        Path dimensions = Files.createDirectories(workspace.resolve(folder).resolve("dimensions"));
        Files.writeString(dimensions.resolve(key + ".json"), "{}", StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

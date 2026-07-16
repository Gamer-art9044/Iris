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

import art.arcane.volmlib.util.collection.KSet;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModdedForcedDatapackTest {
    @Test
    public void scopesSharedCustomBiomeIdsByNamespace() {
        Map<String, KSet<String>> seenBiomes = new LinkedHashMap<>();

        KSet<String> firstNamespace = ModdedForcedDatapack.biomesForNamespace(seenBiomes, "first_dimension");
        KSet<String> secondNamespace = ModdedForcedDatapack.biomesForNamespace(seenBiomes, "second_dimension");

        assertTrue(firstNamespace.add("shared_biome"));
        assertTrue(secondNamespace.add("shared_biome"));
        assertFalse(firstNamespace.add("shared_biome"));
        assertNotSame(firstNamespace, secondNamespace);
        assertEquals(2, seenBiomes.size());
    }

    @Test
    public void writesForgeBlockLootModifierListAndInstance() throws IOException {
        Path packDirectory = Files.createTempDirectory("iris-forge-loot-modifier");
        try {
            ModdedForcedDatapack.writeForgeBlockLootModifier(packDirectory);

            Path list = packDirectory.resolve("data/forge/loot_modifiers/global_loot_modifiers.json");
            Path modifier = packDirectory.resolve("data/irisworldgen/loot_modifiers/block_drops.json");
            assertTrue(Files.isRegularFile(list));
            assertTrue(Files.isRegularFile(modifier));
            assertEquals("{\n"
                    + "  \"replace\": false,\n"
                    + "  \"entries\": [\"irisworldgen:block_drops\"]\n"
                    + "}\n", Files.readString(list, StandardCharsets.UTF_8));
            assertEquals("{\n"
                    + "  \"type\": \"irisworldgen:block_drops\",\n"
                    + "  \"conditions\": []\n"
                    + "}\n", Files.readString(modifier, StandardCharsets.UTF_8));
        } finally {
            deleteTree(packDirectory);
        }
    }

    @Test
    public void publishesCompleteStagingDirectoryOverExistingPack() throws IOException {
        Path root = Files.createTempDirectory("iris-forced-pack-publish");
        try {
            Path published = Files.createDirectory(root.resolve("iris"));
            Files.writeString(published.resolve("old.txt"), "old", StandardCharsets.UTF_8);
            Path staging = Files.createDirectory(root.resolve("staging"));
            Files.writeString(staging.resolve("new.txt"), "new", StandardCharsets.UTF_8);

            ModdedForcedDatapack.publishDirectory(staging, published);

            assertFalse(Files.exists(staging));
            assertFalse(Files.exists(published.resolve("old.txt")));
            assertEquals("new", Files.readString(published.resolve("new.txt"), StandardCharsets.UTF_8));
            assertEquals(List.of("iris"), directoryEntries(root));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void publishesStagingDirectoryWhenNoPriorPackExists() throws IOException {
        Path root = Files.createTempDirectory("iris-forced-pack-first-publish");
        try {
            Path published = root.resolve("iris");
            Path staging = Files.createDirectory(root.resolve("staging"));
            Files.writeString(staging.resolve("pack.mcmeta"), "first", StandardCharsets.UTF_8);

            ModdedForcedDatapack.publishDirectory(staging, published);

            assertEquals("first",
                    Files.readString(published.resolve("pack.mcmeta"), StandardCharsets.UTF_8));
            assertEquals(List.of("iris"), directoryEntries(root));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void restoresPublishedPackWhenAtomicStagingMoveFails() throws IOException {
        Path root = Files.createTempDirectory("iris-forced-pack-rollback");
        try {
            Path published = Files.createDirectory(root.resolve("iris"));
            Files.writeString(published.resolve("pack.mcmeta"), "known-good", StandardCharsets.UTF_8);
            Path missingStaging = root.resolve("missing-staging");

            assertThrows(IOException.class,
                    () -> ModdedForcedDatapack.publishDirectory(missingStaging, published));

            assertEquals("known-good",
                    Files.readString(published.resolve("pack.mcmeta"), StandardCharsets.UTF_8));
            assertEquals(List.of("iris"), directoryEntries(root));
        } finally {
            deleteTree(root);
        }
    }

    private List<String> directoryEntries(Path root) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.map((Path path) -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private void deleteTree(Path root) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(paths::add);
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}

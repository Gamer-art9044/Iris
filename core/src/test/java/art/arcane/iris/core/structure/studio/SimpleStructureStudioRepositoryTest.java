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

package art.arcane.iris.core.structure.studio;

import art.arcane.iris.core.structure.authoring.StructureKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SimpleStructureStudioRepositoryTest {
    private static final StructureKey KEY = StructureKey.parse("iris:castle/main");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesLoadsAndAtomicallyReplacesDrafts() throws Exception {
        Path root = temporaryFolder.newFolder("pack").toPath();
        SimpleStructureStudioRepository repository = new SimpleStructureStudioRepository(root);
        SimpleStructureStudioLayout layout = new SimpleStructureStudioLayout(3, 2, 16, 16, 12);
        SimpleStructureStudioDraft first = SimpleStructureStudioDraft.empty(layout, 10L);
        SimpleStructureStudioDraft second = first.withPreviewSeed(20L)
                .withCell(SimpleStructureStudioCell.create(1, 1, SimpleStructureStudioTopology.CORNER));

        assertTrue(repository.load(KEY).isEmpty());
        repository.save(KEY, first);
        assertEquals(Optional.of(first), repository.load(KEY));
        repository.save(KEY, second);
        assertEquals(Optional.of(second), repository.load(KEY));
        try (Stream<Path> paths = Files.list(repository.draftPath(KEY).getParent())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    public void malformedDraftFailsWithItsPathAndLeavesFileUntouched() throws Exception {
        Path root = temporaryFolder.newFolder("pack").toPath();
        SimpleStructureStudioRepository repository = new SimpleStructureStudioRepository(root);
        Path draft = repository.draftPath(KEY);
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, "{", StandardCharsets.UTF_8);

        Exception failure = assertThrows(Exception.class, () -> repository.load(KEY));

        assertTrue(failure.getMessage().contains(draft.toString()));
        assertEquals("{", Files.readString(draft, StandardCharsets.UTF_8));
    }
}

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

package art.arcane.iris.modded.structure;

import art.arcane.iris.core.structure.authoring.StructureBackend;
import art.arcane.iris.core.structure.authoring.StructureCapability;
import art.arcane.iris.core.structure.authoring.StructureKey;
import art.arcane.iris.core.structure.authoring.StructureResourceBundle;
import art.arcane.iris.core.structure.authoring.StructureSource;
import art.arcane.iris.core.structure.authoring.StructureWriteMode;
import art.arcane.iris.core.structure.authoring.StructureWriteOptions;
import art.arcane.iris.core.structure.authoring.StructureWriteResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedStructureImportServiceTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesThroughOwnedAddOnlyAndOverwriteTransactions() throws Exception {
        Path root = temporaryFolder.newFolder("modded-import").toPath();
        ModdedStructureImportService service = new ModdedStructureImportService(() -> null);
        ModdedStructureImportService.PreparedImport first = prepared(root, "one", StructureWriteMode.ADD_ONLY);

        ModdedStructureImportService.ImportResult added = service.write(first);
        ModdedStructureImportService.ImportResult conflict = service.write(first);
        ModdedStructureImportService.ImportResult overwritten = service.write(
                prepared(root, "two", StructureWriteMode.OVERWRITE)
        );

        assertTrue(added.success());
        assertEquals(StructureWriteResult.Status.ADDED, added.writeResult().orElseThrow().status());
        assertFalse(conflict.success());
        assertEquals(StructureWriteResult.Status.ADD_ONLY_CONFLICT, conflict.writeResult().orElseThrow().status());
        assertTrue(overwritten.success());
        assertEquals(StructureWriteResult.Status.OVERWRITTEN, overwritten.writeResult().orElseThrow().status());
        assertTrue(overwritten.capabilities().contains(StructureCapability.BLOCKS));
    }

    private static ModdedStructureImportService.PreparedImport prepared(
            Path root,
            String content,
            StructureWriteMode mode
    ) {
        StructureKey key = StructureKey.parse("iris:test_structure");
        StructureResourceBundle bundle = StructureResourceBundle.builder(key)
                .source(StructureSource.of(StructureSource.Kind.DATAPACK, StructureKey.parse("test:source")))
                .backend(StructureBackend.SNAPSHOT)
                .capability(StructureCapability.BLOCKS)
                .textResource("structures/test_structure.json", content)
                .build();
        return new ModdedStructureImportService.PreparedImport(
                root,
                new StructureWriteOptions(mode, false),
                ModdedStructureImportService.ImportKind.TEMPLATE,
                bundle,
                1,
                1,
                1
        );
    }
}

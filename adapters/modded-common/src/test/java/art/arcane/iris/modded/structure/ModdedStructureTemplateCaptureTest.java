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

import art.arcane.iris.core.structure.authoring.StructureCapability;
import art.arcane.iris.core.structure.authoring.StructureKey;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedStructureTemplateCaptureTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void capturesBlocksTilesAndExplicitStandaloneLosses() throws Exception {
        CompoundTag template = templateTag();

        ModdedStructureTemplateCapture.Capture capture = ModdedStructureTemplateCapture.captureTag(
                StructureKey.parse("minecraft:test/template"),
                template,
                BuiltInRegistries.BLOCK,
                false
        );

        assertEquals(4, capture.width());
        assertEquals(1, capture.height());
        assertEquals(1, capture.depth());
        assertEquals(3, capture.blocks());
        assertEquals(1, capture.tiles());
        assertEquals(1, capture.jigsaws());
        assertEquals(1, capture.dataMarkers());
        assertEquals(3, capture.object().getBlocks().size());
        assertEquals(1, capture.object().getStates().size());
        assertTrue(capture.capabilities().contains(StructureCapability.BLOCKS));
        assertTrue(capture.capabilities().contains(StructureCapability.BLOCK_ENTITIES));
        assertFalse(capture.capabilities().contains(StructureCapability.CONNECTORS));
        assertTrue(hasLoss(capture, "connectors_not_imported"));
        assertTrue(hasLoss(capture, "data_markers_not_imported"));
        assertTrue(hasLoss(capture, "entities_not_imported"));
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        capture.object().write(serialized);
        assertTrue(serialized.size() > 0);
    }

    @Test
    public void graphCaptureReportsConnectorCapabilityWithoutStandaloneConnectorLoss() {
        ModdedStructureTemplateCapture.Capture capture = ModdedStructureTemplateCapture.captureTag(
                StructureKey.parse("minecraft:test/template"),
                templateTag(),
                BuiltInRegistries.BLOCK,
                true
        );

        assertTrue(capture.capabilities().contains(StructureCapability.CONNECTORS));
        assertFalse(hasLoss(capture, "connectors_not_imported"));
    }

    @Test
    public void reportsAdditionalNativePalettes() {
        CompoundTag template = templateTag();
        ListTag palettes = new ListTag();
        ListTag first = template.getListOrEmpty(StructureTemplate.PALETTE_TAG);
        palettes.add(first.copy());
        palettes.add(first.copy());
        template.remove(StructureTemplate.PALETTE_TAG);
        template.put(StructureTemplate.PALETTE_LIST_TAG, palettes);

        ModdedStructureTemplateCapture.Capture capture = ModdedStructureTemplateCapture.captureTag(
                StructureKey.parse("minecraft:test/template"),
                template,
                BuiltInRegistries.BLOCK,
                true
        );

        assertTrue(hasLoss(capture, "palette_variants_not_imported"));
    }

    @Test
    public void legacyCaptureOmitsAirBlocks() {
        CompoundTag template = new CompoundTag();
        template.put(StructureTemplate.SIZE_TAG, intList(1, 1, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
        template.put(StructureTemplate.PALETTE_TAG, palette);
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0, null));
        template.put(StructureTemplate.BLOCKS_TAG, blocks);

        ModdedStructureTemplateCapture.Capture capture = ModdedStructureTemplateCapture.captureTag(
                StructureKey.parse("minecraft:test/legacy"),
                template,
                BuiltInRegistries.BLOCK,
                true,
                false
        );

        assertEquals(0, capture.blocks());
        assertTrue(capture.object().getBlocks().isEmpty());
    }

    private static CompoundTag templateTag() {
        CompoundTag template = new CompoundTag();
        template.put(StructureTemplate.SIZE_TAG, intList(4, 1, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.JIGSAW.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.STRUCTURE_BLOCK.defaultBlockState()));
        template.put(StructureTemplate.PALETTE_TAG, palette);

        ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0, null));
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "test");
        blocks.add(block(1, 0, 0, 1, chest));
        CompoundTag jigsaw = new CompoundTag();
        jigsaw.putString("final_state", "minecraft:oak_planks");
        blocks.add(block(2, 0, 0, 2, jigsaw));
        blocks.add(block(3, 0, 0, 3, new CompoundTag()));
        template.put(StructureTemplate.BLOCKS_TAG, blocks);

        ListTag entities = new ListTag();
        entities.add(new CompoundTag());
        template.put(StructureTemplate.ENTITIES_TAG, entities);
        return template;
    }

    private static CompoundTag block(int x, int y, int z, int state, CompoundTag nbt) {
        CompoundTag block = new CompoundTag();
        block.put(StructureTemplate.BLOCK_TAG_POS, intList(x, y, z));
        block.putInt(StructureTemplate.BLOCK_TAG_STATE, state);
        if (nbt != null) {
            block.put(StructureTemplate.BLOCK_TAG_NBT, nbt);
        }
        return block;
    }

    private static ListTag intList(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        return list;
    }

    private static boolean hasLoss(ModdedStructureTemplateCapture.Capture capture, String code) {
        return capture.losses().stream().anyMatch(loss -> loss.code().equals(code));
    }
}

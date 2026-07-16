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
import art.arcane.iris.core.structure.authoring.StructureLoss;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.modded.ModdedBlockResolution;
import art.arcane.iris.modded.ModdedBlockState;
import art.arcane.iris.modded.ModdedTileData;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class ModdedStructureTemplateCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    private ModdedStructureTemplateCapture() {
    }

    static Capture capture(
            StructureKey sourceKey,
            StructureTemplate template,
            HolderGetter<Block> blockLookup,
            boolean connectorsPreserved
    ) {
        return capture(sourceKey, template, blockLookup, connectorsPreserved, true);
    }

    static Capture capture(
            StructureKey sourceKey,
            StructureTemplate template,
            HolderGetter<Block> blockLookup,
            boolean connectorsPreserved,
            boolean includeAir
    ) {
        Objects.requireNonNull(template);
        CompoundTag sourceTag = template.save(new CompoundTag());
        return captureTag(sourceKey, sourceTag, blockLookup, connectorsPreserved, includeAir);
    }

    static Capture captureTag(
            StructureKey sourceKey,
            CompoundTag sourceTag,
            HolderGetter<Block> blockLookup,
            boolean connectorsPreserved
    ) {
        return captureTag(sourceKey, sourceTag, blockLookup, connectorsPreserved, true);
    }

    static Capture captureTag(
            StructureKey sourceKey,
            CompoundTag sourceTag,
            HolderGetter<Block> blockLookup,
            boolean connectorsPreserved,
            boolean includeAir
    ) {
        Objects.requireNonNull(sourceKey);
        Objects.requireNonNull(sourceTag);
        Objects.requireNonNull(blockLookup);
        Vec3i size = readSize(sourceTag);
        IrisObject object = new IrisObject(size.getX(), size.getY(), size.getZ());
        List<StructureLoss> losses = new ArrayList<>();
        ListTag palette = firstPalette(sourceTag, losses);
        ListTag blocks = sourceTag.getListOrEmpty(StructureTemplate.BLOCKS_TAG);
        CaptureCounts counts = captureBlocks(sourceKey, palette, blocks, blockLookup, object, losses, includeAir);
        recordEntityLoss(sourceTag, losses);
        recordMarkerLosses(counts, connectorsPreserved, losses);

        EnumSet<StructureCapability> capabilities = EnumSet.of(StructureCapability.BLOCKS);
        if (counts.tiles() > 0) {
            capabilities.add(StructureCapability.BLOCK_ENTITIES);
        }
        if (connectorsPreserved && counts.jigsaws() > 0) {
            capabilities.add(StructureCapability.CONNECTORS);
        }
        return new Capture(
                object,
                counts.blocks(),
                counts.tiles(),
                counts.jigsaws(),
                counts.dataMarkers(),
                size.getX(),
                size.getY(),
                size.getZ(),
                capabilities,
                losses,
                sourceTag.copy()
        );
    }

    private static Vec3i readSize(CompoundTag sourceTag) {
        ListTag size = sourceTag.getListOrEmpty(StructureTemplate.SIZE_TAG);
        int width = size.getIntOr(0, 0);
        int height = size.getIntOr(1, 0);
        int depth = size.getIntOr(2, 0);
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("Structure template has invalid dimensions "
                    + width + "x" + height + "x" + depth);
        }
        return new Vec3i(width, height, depth);
    }

    private static ListTag firstPalette(CompoundTag sourceTag, List<StructureLoss> losses) {
        ListTag palettes = sourceTag.getList(StructureTemplate.PALETTE_LIST_TAG).orElse(null);
        if (palettes != null) {
            if (palettes.isEmpty()) {
                throw new IllegalArgumentException("Structure template has no palettes");
            }
            if (palettes.size() > 1) {
                losses.add(StructureLoss.warning(
                        StructureCapability.BLOCKS,
                        "palette_variants_not_imported",
                        "Only palette 0 was converted; " + (palettes.size() - 1)
                                + " additional palette(s) remain native-only."
                ));
            }
            return palettes.getListOrEmpty(0);
        }
        ListTag palette = sourceTag.getListOrEmpty(StructureTemplate.PALETTE_TAG);
        if (palette.isEmpty() && !sourceTag.getListOrEmpty(StructureTemplate.BLOCKS_TAG).isEmpty()) {
            throw new IllegalArgumentException("Structure template has blocks but no palette");
        }
        return palette;
    }

    private static CaptureCounts captureBlocks(
            StructureKey sourceKey,
            ListTag palette,
            ListTag blocks,
            HolderGetter<Block> blockLookup,
            IrisObject object,
            List<StructureLoss> losses,
            boolean includeAir
    ) {
        int blockCount = 0;
        int tiles = 0;
        int jigsaws = 0;
        int dataMarkers = 0;
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag blockTag = blocks.getCompoundOrEmpty(index);
            BlockPosition position = readPosition(blockTag);
            if (!withinObject(position, object)) {
                losses.add(StructureLoss.warning(
                        StructureCapability.BLOCKS,
                        "out_of_bounds_block_not_imported",
                        "Block " + index + " at " + position.x() + "," + position.y() + "," + position.z()
                                + " is outside the declared template bounds."
                ));
                continue;
            }
            int paletteIndex = blockTag.getIntOr(StructureTemplate.BLOCK_TAG_STATE, 0);
            BlockState state = NbtUtils.readBlockState(blockLookup, palette.getCompoundOrEmpty(paletteIndex));
            CompoundTag blockEntityTag = blockTag.getCompound(StructureTemplate.BLOCK_TAG_NBT).orElse(null);
            if (state.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            if (state.is(Blocks.STRUCTURE_BLOCK)) {
                dataMarkers++;
                continue;
            }
            if (state.is(Blocks.JIGSAW)) {
                jigsaws++;
                BlockState finalState = resolveJigsawFinalState(sourceKey, position, blockEntityTag, losses);
                if (finalState == null || finalState.isAir()) {
                    continue;
                }
                state = finalState;
                blockEntityTag = null;
            }
            if (!includeAir && state.isAir()) {
                continue;
            }
            object.setUnsigned(position.x(), position.y(), position.z(), ModdedBlockState.of(state, null));
            blockCount++;
            if (blockEntityTag != null && captureTile(sourceKey, position, state, blockEntityTag, object, losses)) {
                tiles++;
            }
        }
        return new CaptureCounts(blockCount, tiles, jigsaws, dataMarkers);
    }

    private static BlockPosition readPosition(CompoundTag blockTag) {
        ListTag position = blockTag.getListOrEmpty(StructureTemplate.BLOCK_TAG_POS);
        return new BlockPosition(
                position.getIntOr(0, Integer.MIN_VALUE),
                position.getIntOr(1, Integer.MIN_VALUE),
                position.getIntOr(2, Integer.MIN_VALUE)
        );
    }

    private static boolean withinObject(BlockPosition position, IrisObject object) {
        return position.x() >= 0 && position.x() < object.getW()
                && position.y() >= 0 && position.y() < object.getH()
                && position.z() >= 0 && position.z() < object.getD();
    }

    private static BlockState resolveJigsawFinalState(
            StructureKey sourceKey,
            BlockPosition position,
            CompoundTag blockEntityTag,
            List<StructureLoss> losses
    ) {
        String finalState = blockEntityTag == null
                ? "minecraft:air"
                : blockEntityTag.getStringOr("final_state", "minecraft:air");
        try {
            return ModdedBlockResolution.strictParse(finalState).handle();
        } catch (IllegalArgumentException failure) {
            LOGGER.error("Failed to parse jigsaw final state '{}' in {} at {},{},{}",
                    finalState, sourceKey, position.x(), position.y(), position.z(), failure);
            losses.add(StructureLoss.warning(
                    StructureCapability.BLOCKS,
                    "jigsaw_final_state_not_imported",
                    "Jigsaw final state '" + finalState + "' at " + position.x() + "," + position.y() + ","
                            + position.z() + " could not be parsed."
            ));
            return null;
        }
    }

    private static boolean captureTile(
            StructureKey sourceKey,
            BlockPosition position,
            BlockState state,
            CompoundTag blockEntityTag,
            IrisObject object,
            List<StructureLoss> losses
    ) {
        try {
            String blockKey = ModdedBlockState.serialize(state);
            ModdedTileData tile = ModdedTileData.capture(blockKey, NbtUtils.structureToSnbt(blockEntityTag));
            object.setUnsignedTile(position.x(), position.y(), position.z(), tile);
            return true;
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Failed to capture block entity in {} at {},{},{}",
                    sourceKey, position.x(), position.y(), position.z(), failure);
            losses.add(StructureLoss.warning(
                    StructureCapability.BLOCK_ENTITIES,
                    "block_entity_not_imported",
                    "Block entity data at " + position.x() + "," + position.y() + "," + position.z()
                            + " could not be encoded."
            ));
            return false;
        }
    }

    private static void recordEntityLoss(CompoundTag sourceTag, List<StructureLoss> losses) {
        int entityCount = sourceTag.getListOrEmpty(StructureTemplate.ENTITIES_TAG).size();
        if (entityCount > 0) {
            losses.add(StructureLoss.warning(
                    StructureCapability.ENTITIES,
                    "entities_not_imported",
                    entityCount + " structure entit" + (entityCount == 1 ? "y was" : "ies were")
                            + " not converted into the Iris snapshot."
            ));
        }
    }

    private static void recordMarkerLosses(
            CaptureCounts counts,
            boolean connectorsPreserved,
            List<StructureLoss> losses
    ) {
        if (counts.dataMarkers() > 0) {
            losses.add(StructureLoss.warning(
                    StructureCapability.PROCESSORS,
                    "data_markers_not_imported",
                    counts.dataMarkers() + " structure data marker(s) require native pool-element handlers and were omitted."
            ));
        }
        if (!connectorsPreserved && counts.jigsaws() > 0) {
            losses.add(StructureLoss.warning(
                    StructureCapability.CONNECTORS,
                    "connectors_not_imported",
                    counts.jigsaws() + " jigsaw connector(s) were resolved to final blocks without importing their pool graph."
            ));
        }
    }

    record Capture(
            IrisObject object,
            int blocks,
            int tiles,
            int jigsaws,
            int dataMarkers,
            int width,
            int height,
            int depth,
            Set<StructureCapability> capabilities,
            List<StructureLoss> losses,
            CompoundTag sourceTag
    ) {
        Capture {
            Objects.requireNonNull(object);
            capabilities = Set.copyOf(capabilities);
            losses = List.copyOf(losses);
            sourceTag = sourceTag.copy();
        }
    }

    private record CaptureCounts(int blocks, int tiles, int jigsaws, int dataMarkers) {
    }

    private record BlockPosition(int x, int y, int z) {
    }
}

package art.arcane.iris.nativegen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public final class NativeStructureVegetationClearer {
    private NativeStructureVegetationClearer() {
    }

    public static boolean isUndergroundStep(GenerationStep.Decoration step) {
        return step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
                || step == GenerationStep.Decoration.UNDERGROUND_DECORATION
                || step == GenerationStep.Decoration.STRONGHOLDS;
    }

    public static boolean shouldClearEntireVegetationFootprint(GenerationStep.Decoration step,
                                                                boolean configured) {
        return configured;
    }

    public static void clearIntersectingVegetation(WorldGenLevel world, ChunkAccess chunk, BoundingBox area,
                                                   List<VegetationTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        VegetationSnapshot snapshot = captureVegetation(chunk, area);
        if (snapshot.treeBlockCount() == 0) {
            return;
        }
        boolean[] clearColumns = new boolean[area.getXSpan() * area.getZSpan()];
        for (VegetationTarget target : targets) {
            if (target != null && target.force() && target.start() != null && target.start().isValid()) {
                markVegetationColumns(area, snapshot, target, clearColumns);
            }
        }
        clearVegetationColumns(world, area, snapshot, clearColumns);
    }

    private static VegetationSnapshot captureVegetation(ChunkAccess chunk, BoundingBox area) {
        int width = area.getXSpan();
        int depth = area.getZSpan();
        BitSet[] columns = new BitSet[width * depth];
        int[] lowestY = new int[columns.length];
        Arrays.fill(lowestY, Integer.MAX_VALUE);
        int treeBlockCount = 0;
        LevelChunkSection[] sections = chunk.getSections();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            int sectionMinY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            int minY = Math.max(area.minY(), sectionMinY);
            int maxY = Math.min(area.maxY(), sectionMinY + 15);
            if (minY > maxY || section.hasOnlyAir()
                    || !section.maybeHas(NativeStructureVegetationClearer::isTreeBlock)) {
                continue;
            }
            for (int y = minY; y <= maxY; y++) {
                int localY = y - sectionMinY;
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    int localZ = z - chunkMinZ;
                    for (int x = area.minX(); x <= area.maxX(); x++) {
                        int localX = x - chunkMinX;
                        if (!isTreeBlock(section.getBlockState(localX, localY, localZ))) {
                            continue;
                        }
                        int column = (z - area.minZ()) * width + x - area.minX();
                        BitSet treeBlocks = columns[column];
                        if (treeBlocks == null) {
                            treeBlocks = new BitSet(area.getYSpan());
                            columns[column] = treeBlocks;
                        }
                        treeBlocks.set(y - area.minY());
                        lowestY[column] = Math.min(lowestY[column], y);
                        treeBlockCount++;
                    }
                }
            }
        }
        return new VegetationSnapshot(columns, lowestY, treeBlockCount);
    }

    private static void markVegetationColumns(BoundingBox area, VegetationSnapshot snapshot,
                                              VegetationTarget target, boolean[] clearColumns) {
        int width = area.getXSpan();
        int[] pieceTops = new int[clearColumns.length];
        Arrays.fill(pieceTops, Integer.MIN_VALUE);
        for (StructurePiece piece : target.start().getPieces()) {
            if (NativeStructureReferenceEnvelope.isMarker(piece)) {
                continue;
            }
            BoundingBox bounds = piece.getBoundingBox();
            int minX = Math.max(area.minX(), bounds.minX());
            int maxX = Math.min(area.maxX(), bounds.maxX());
            int minZ = Math.max(area.minZ(), bounds.minZ());
            int maxZ = Math.min(area.maxZ(), bounds.maxZ());
            if (minX > maxX || minZ > maxZ) {
                continue;
            }
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int column = (z - area.minZ()) * width + x - area.minX();
                    pieceTops[column] = Math.max(pieceTops[column], bounds.maxY());
                }
            }
        }
        for (int column = 0; column < pieceTops.length; column++) {
            if (snapshot.columns()[column] == null || pieceTops[column] == Integer.MIN_VALUE) {
                continue;
            }
            if (shouldClearVegetationColumn(pieceTops[column], snapshot.lowestY()[column], target.force())) {
                clearColumns[column] = true;
            }
        }
    }

    static boolean shouldClearVegetationColumn(int pieceTopY, int lowestTreeY, boolean force) {
        return force || pieceTopY >= lowestTreeY;
    }

    private static void clearVegetationColumns(WorldGenLevel world, BoundingBox area,
                                               VegetationSnapshot snapshot, boolean[] clearColumns) {
        int width = area.getXSpan();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int column = (z - area.minZ()) * width + x - area.minX();
                BitSet treeBlocks = snapshot.columns()[column];
                if (!clearColumns[column] || treeBlocks == null) {
                    continue;
                }
                for (int bit = treeBlocks.nextSetBit(0); bit >= 0; bit = treeBlocks.nextSetBit(bit + 1)) {
                    int y = area.minY() + bit;
                    position.set(x, y, z);
                    BlockState state = world.getBlockState(position);
                    if (isTreeBlock(state)) {
                        world.setBlock(position, air, 2);
                    }
                }
            }
        }
    }

    static boolean isTreeBlock(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae")
                || path.endsWith("_leaves");
    }

    public record VegetationTarget(StructureStart start, boolean force) {
    }

    private record VegetationSnapshot(BitSet[] columns, int[] lowestY, int treeBlockCount) {
    }
}

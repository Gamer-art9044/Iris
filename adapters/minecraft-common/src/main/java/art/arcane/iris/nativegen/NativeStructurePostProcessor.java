package art.arcane.iris.nativegen;

import art.arcane.iris.engine.framework.StructureVerticalBounds;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.IrisVanillaStructureStiltSettings;
import art.arcane.volmlib.util.math.RNG;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public final class NativeStructurePostProcessor {
    private static final int FOUNDATION_VERTICAL_TOLERANCE = 1;

    private NativeStructurePostProcessor() {
    }

    public static void place(WorldGenLevel world, StructureManager structureManager, ChunkGenerator generator,
                             WorldgenRandom random, BoundingBox area, ChunkPos chunkPos, String structureId,
                             StructureStart start, IrisNativeStructureDecision decision,
                             StiltBlockResolver stiltBlockResolver) {
        IrisVanillaStructureStiltSettings stilt = decision.stilt();
        start.placeInChunk(world, structureManager, generator, random, area, chunkPos);
        if (stilt != null) {
            placeStilts(world, area, structureId, start, stilt, stiltBlockResolver);
        }
    }

    public static int applyVerticalShift(StructureStart start, int requestedOffset, int worldMinY,
                                         int worldMaxYExclusive) {
        BoundingBox bounds = start.getBoundingBox();
        int offsetY = StructureVerticalBounds.clampOffset(
                bounds.minY(), bounds.maxY(), requestedOffset, worldMinY, worldMaxYExclusive);
        if (offsetY == 0) {
            return 0;
        }
        for (StructurePiece piece : start.getPieces()) {
            piece.move(0, offsetY, 0);
            if (piece instanceof PoolElementStructurePiece poolPiece) {
                List<JigsawJunction> junctions = poolPiece.getJunctions();
                for (int i = 0; i < junctions.size(); i++) {
                    JigsawJunction junction = junctions.get(i);
                    junctions.set(i, new JigsawJunction(
                            junction.getSourceX(),
                            junction.getSourceGroundY() + offsetY,
                            junction.getSourceZ(),
                            junction.getDeltaY(),
                            junction.getDestProjection()));
                }
            }
        }
        bounds.move(0, offsetY, 0);
        return offsetY;
    }

    public static boolean isUndergroundStep(GenerationStep.Decoration step) {
        return step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
                || step == GenerationStep.Decoration.UNDERGROUND_DECORATION
                || step == GenerationStep.Decoration.STRONGHOLDS;
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
            if (target != null && target.start() != null && target.start().isValid()) {
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
            if (minY > maxY || section.hasOnlyAir() || !section.maybeHas(NativeStructurePostProcessor::isTreeBlock)) {
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

    private static boolean isTreeBlock(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }

    private static List<FoundationColumn> foundationEnvelope(BoundingBox area, StructureStart start) {
        BoundingBox structure = start.getBoundingBox();
        int minX = Math.max(area.minX(), structure.minX());
        int minZ = Math.max(area.minZ(), structure.minZ());
        int maxX = Math.min(area.maxX(), structure.maxX());
        int maxZ = Math.min(area.maxZ(), structure.maxZ());
        if (minX > maxX || minZ > maxZ) {
            return List.of();
        }
        List<StructurePiece> pieces = start.getPieces();
        List<FoundationColumn> columns = new ArrayList<>((maxX - minX + 1) * (maxZ - minZ + 1));
        BitSet envelope = new BitSet(area.getYSpan());
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                envelope.clear();
                markFoundationEnvelope(envelope, pieces, area, x, z);
                int cellCount = envelope.cardinality();
                if (cellCount == 0) {
                    continue;
                }
                int[] ys = new int[cellCount];
                int cell = 0;
                for (int bit = envelope.nextSetBit(0); bit >= 0; bit = envelope.nextSetBit(bit + 1)) {
                    ys[cell++] = area.minY() + bit;
                }
                columns.add(new FoundationColumn(x, z, ys));
            }
        }
        return List.copyOf(columns);
    }

    private static void markFoundationEnvelope(BitSet envelope, List<StructurePiece> pieces, BoundingBox area,
                                               int x, int z) {
        for (StructurePiece piece : pieces) {
            BoundingBox bounds = piece.getBoundingBox();
            if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
                continue;
            }
            int groundY = bounds.minY();
            if (piece instanceof PoolElementStructurePiece poolPiece) {
                groundY += poolPiece.getGroundLevelDelta();
                if (groundY < bounds.minY()) {
                    continue;
                }
            }
            int minY = Math.max(area.minY(), bounds.minY());
            int maxY = Math.min(area.maxY(), Math.min(bounds.maxY(), groundY + FOUNDATION_VERTICAL_TOLERANCE));
            if (minY <= maxY) {
                envelope.set(minY - area.minY(), maxY - area.minY() + 1);
            }
        }
    }

    private static void placeStilts(WorldGenLevel world, BoundingBox area, String structureId,
                                    StructureStart start, IrisVanillaStructureStiltSettings settings,
                                    StiltBlockResolver stiltBlockResolver) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int structureHash = structureId == null ? 0 : structureId.hashCode();
        RNG rng = new RNG(world.getSeed() ^ structureHash);
        for (FoundationColumn column : foundationEnvelope(area, start)) {
            int foundationY = findFoundationY(world, column, position);
            if (foundationY == Integer.MIN_VALUE) {
                continue;
            }
            for (int depth = 0, y = foundationY - 1;
                 depth < settings.getMaxDepth() && y >= area.minY(); depth++, y--) {
                position.set(column.x(), y, column.z());
                BlockState existingState = world.getBlockState(position);
                boolean vegetation = existingState.is(BlockTags.LOGS) || existingState.is(BlockTags.LEAVES);
                if (existingState.isSolid() && !vegetation) {
                    break;
                }
                BlockState stilt = stiltBlockResolver.resolve(settings, rng, column.x(), y, column.z());
                world.setBlock(position, stilt == null ? Blocks.COBBLESTONE.defaultBlockState() : stilt, 2);
            }
        }
    }

    private static int findFoundationY(WorldGenLevel world, FoundationColumn column,
                                       BlockPos.MutableBlockPos position) {
        for (int cell = 0; cell < column.ys().length; cell++) {
            int y = column.ys()[cell];
            BlockState state = world.getBlockState(position.set(column.x(), y, column.z()));
            if (state.isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    @FunctionalInterface
    public interface StiltBlockResolver {
        BlockState resolve(IrisVanillaStructureStiltSettings settings, RNG rng, int x, int y, int z);
    }

    private record FoundationColumn(int x, int z, int[] ys) {
    }

    public record VegetationTarget(StructureStart start, boolean force) {
    }

    private record VegetationSnapshot(BitSet[] columns, int[] lowestY, int treeBlockCount) {
    }
}

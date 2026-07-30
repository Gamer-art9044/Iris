package art.arcane.iris.nativegen;

import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import art.arcane.iris.spi.IrisLogging;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;

public final class NativeStructureReferenceEnvelope {
    private static final int MARKER_GROUND_LEVEL_DELTA = Integer.MIN_VALUE;
    private static final int MAX_REFERENCE_DISTANCE_CHUNKS = 8;
    private static final StructurePoolElement MARKER_ELEMENT = StructurePoolElement.single(
            "minecraft:empty",
            Holder.direct(new StructureProcessorList(List.of(
                    BlockIgnoreProcessor.STRUCTURE_AND_AIR))),
            LiquidSettings.APPLY_WATERLOGGING
    ).apply(StructureTemplatePool.Projection.RIGID);

    private NativeStructureReferenceEnvelope() {
    }

    public static StructureStart wrap(StructureStart generated, Structure source, int references,
                                      StructureTemplateManager templateManager,
                                      IrisStructureTerrain terrain) {
        List<StructurePiece> pieces = new ArrayList<>(generated.getPieces());
        IrisStructureTerrainMode mode = terrain == null
                ? IrisStructureTerrainMode.PRESERVE : terrain.resolvedMode();
        boolean usesEnvelope = mode == IrisStructureTerrainMode.BORE
                || mode == IrisStructureTerrainMode.FORCE_CARVE
                || mode == IrisStructureTerrainMode.VACUUM
                || mode == IrisStructureTerrainMode.ENCASE;
        int horizontalPadding = usesEnvelope ? Math.max(0, terrain.getHorizontalPadding()) : 0;
        if (horizontalPadding > 0) {
            BoundingBox content = contentBounds(pieces);
            BoundingBox envelope = new BoundingBox(
                    Math.subtractExact(content.minX(), horizontalPadding),
                    content.minY(),
                    Math.subtractExact(content.minZ(), horizontalPadding),
                    Math.addExact(content.maxX(), horizontalPadding),
                    content.maxY(),
                    Math.addExact(content.maxZ(), horizontalPadding)
            );
            BoundingBox referencedEnvelope = clampReferenceRange(generated.getChunkPos(), envelope);
            if (!sameHorizontalBounds(envelope, referencedEnvelope)) {
                IrisLogging.warn("Native structure terrain envelope at "
                        + generated.getChunkPos().x() + "," + generated.getChunkPos().z()
                        + " was clipped to Minecraft's " + MAX_REFERENCE_DISTANCE_CHUNKS
                        + "-chunk structure reference range");
            }
            pieces.add(marker(templateManager, referencedEnvelope.minX(),
                    referencedEnvelope.minY(), referencedEnvelope.minZ()));
            pieces.add(marker(templateManager, referencedEnvelope.maxX(),
                    referencedEnvelope.maxY(), referencedEnvelope.maxZ()));
        }
        return new StructureStart(
                source,
                generated.getChunkPos(),
                references,
                new PiecesContainer(List.copyOf(pieces))
        );
    }

    public static BoundingBox contentBounds(StructureStart start) {
        return contentBounds(start.getPieces());
    }

    public static boolean isMarker(StructurePiece piece) {
        return piece instanceof PoolElementStructurePiece poolPiece
                && poolPiece.getGroundLevelDelta() == MARKER_GROUND_LEVEL_DELTA;
    }

    private static BoundingBox contentBounds(List<StructurePiece> pieces) {
        BoundingBox bounds = null;
        for (StructurePiece piece : pieces) {
            if (isMarker(piece)) {
                continue;
            }
            bounds = bounds == null
                    ? copy(piece.getBoundingBox())
                    : bounds.encapsulate(piece.getBoundingBox());
        }
        if (bounds == null) {
            throw new IllegalStateException("Native jigsaw generated no content pieces");
        }
        return bounds;
    }

    private static PoolElementStructurePiece marker(StructureTemplateManager templateManager,
                                                     int x, int y, int z) {
        BlockPos position = new BlockPos(x, y, z);
        return new PoolElementStructurePiece(
                templateManager,
                MARKER_ELEMENT,
                position,
                MARKER_GROUND_LEVEL_DELTA,
                Rotation.NONE,
                new BoundingBox(position),
                LiquidSettings.APPLY_WATERLOGGING
        );
    }

    private static BoundingBox clampReferenceRange(ChunkPos startChunk, BoundingBox envelope) {
        int minX = (startChunk.x() - MAX_REFERENCE_DISTANCE_CHUNKS) << 4;
        int maxX = ((startChunk.x() + MAX_REFERENCE_DISTANCE_CHUNKS) << 4) + 15;
        int minZ = (startChunk.z() - MAX_REFERENCE_DISTANCE_CHUNKS) << 4;
        int maxZ = ((startChunk.z() + MAX_REFERENCE_DISTANCE_CHUNKS) << 4) + 15;
        return new BoundingBox(
                Math.max(envelope.minX(), minX),
                envelope.minY(),
                Math.max(envelope.minZ(), minZ),
                Math.min(envelope.maxX(), maxX),
                envelope.maxY(),
                Math.min(envelope.maxZ(), maxZ)
        );
    }

    private static boolean sameHorizontalBounds(BoundingBox left, BoundingBox right) {
        return left.minX() == right.minX()
                && left.minZ() == right.minZ()
                && left.maxX() == right.maxX()
                && left.maxZ() == right.maxZ();
    }

    private static BoundingBox copy(BoundingBox bounds) {
        return new BoundingBox(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}

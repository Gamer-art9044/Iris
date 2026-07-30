package art.arcane.iris.nativegen;

import art.arcane.iris.engine.mantle.components.StructureCarveEnvelope;
import art.arcane.iris.engine.mantle.components.StructureCarvingFootprint;
import art.arcane.iris.engine.object.IrisMaterialPalette;
import art.arcane.iris.engine.object.IrisStructureCarveShape;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.math.RNG;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class NativeStructureTerrainIntegrator {
    private static final int AUTO_ENCASE_PADDING = 3;
    private static final long CARVE_CEILING_ROLL_SIGNATURE = 0x2A17L;
    private static final long CARVE_FLOOR_ROLL_SIGNATURE = 0x5B3DL;
    private static final long CARVE_LOBE_SIGNATURE = 0x7C41L;
    private static final int MAX_CACHED_CARVE_FOOTPRINTS = 4;
    private static final int MAX_CARVE_COLUMNS = 2_000_000;
    private static final int MAX_TEMPLATE_OCCUPANCY_CELLS = 4_194_304;
    private static final List<Block> TEMPLATE_VOID_BLOCKS = List.of(Blocks.AIR, Blocks.STRUCTURE_VOID);
    private static final Map<CarveFootprintKey, StructureCarvingFootprint> CARVE_FOOTPRINTS =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<CarveFootprintKey, StructureCarvingFootprint> eldest) {
                    return size() > MAX_CACHED_CARVE_FOOTPRINTS;
                }
            });

    private NativeStructureTerrainIntegrator() {
    }

    public static IrisStructureTerrain resolveNativeTerrain(StructureStart start,
                                                            IrisStructureTerrain configuredTerrain) {
        if (configuredTerrain != null) {
            return configuredTerrain;
        }
        if (start == null || !start.isValid()
                || !encasesTerrain(start.getStructure().terrainAdaptation())) {
            return null;
        }
        return new IrisStructureTerrain()
                .setMode(IrisStructureTerrainMode.ENCASE)
                .setHorizontalPadding(AUTO_ENCASE_PADDING)
                .setCeilingPadding(AUTO_ENCASE_PADDING)
                .setFloorPadding(AUTO_ENCASE_PADDING);
    }

    static boolean encasesTerrain(TerrainAdjustment adjustment) {
        return adjustment == TerrainAdjustment.BURY || adjustment == TerrainAdjustment.ENCAPSULATE;
    }

    static void integrateTerrain(WorldGenLevel world, BoundingBox area, String structureId,
                                 StructureStart start, IrisStructureTerrain configuredTerrain,
                                 NativeStructurePostProcessor.PaletteBlockResolver paletteBlockResolver) {
        IrisStructureTerrain terrain = configuredTerrain == null
                ? new IrisStructureTerrain().setMode(IrisStructureTerrainMode.SOURCE)
                : configuredTerrain;
        IrisStructureTerrainMode mode = terrain.resolvedMode();
        if (mode == IrisStructureTerrainMode.SOURCE || mode == IrisStructureTerrainMode.PRESERVE) {
            return;
        }
        if (mode == IrisStructureTerrainMode.VACUUM) {
            carvePieceBoxes(world, area, start, terrain);
            return;
        }
        if (mode == IrisStructureTerrainMode.ENCASE) {
            encasePieces(world, area, structureId, start, terrain, paletteBlockResolver);
            return;
        }
        if (mode != IrisStructureTerrainMode.BORE && mode != IrisStructureTerrainMode.FORCE_CARVE) {
            throw new IllegalStateException("Native structure terrain mode " + mode
                    + " is not implemented for '" + structureId + "'");
        }
        IrisStructureCarveShape shape = mode == IrisStructureTerrainMode.BORE
                ? IrisStructureCarveShape.BOX : terrain.resolvedShape();
        if (shape == IrisStructureCarveShape.BOX) {
            carvePieceBoxes(world, area, start, terrain);
            return;
        }
        carveOrganicColumns(world, area, organicCarve(
                carveFootprint(start, Math.max(0, terrain.getHorizontalPadding()),
                        () -> world.getLevel().getStructureManager()),
                terrain, shape, carveNoiseIdentity(world, structureId, start)));
    }

    static List<BoundingBox> contentPieceBounds(StructureStart start) {
        List<BoundingBox> bounds = new ArrayList<>(start.getPieces().size());
        for (StructurePiece piece : start.getPieces()) {
            if (!NativeStructureReferenceEnvelope.isMarker(piece)) {
                bounds.add(piece.getBoundingBox());
            }
        }
        return List.copyOf(bounds);
    }

    /**
     * Per-column occupancy of the whole start, cached because a single structure spans many chunks and
     * every one of them carves against the same shrinkwrapped footprint.
     */
    static StructureCarvingFootprint carveFootprint(StructureStart start, int horizontalPadding,
                                                    Supplier<StructureTemplateManager> templates) {
        CarveFootprintKey key = new CarveFootprintKey(start, horizontalPadding);
        StructureCarvingFootprint cached = CARVE_FOOTPRINTS.get(key);
        if (cached != null) {
            return cached;
        }
        StructureCarvingFootprint footprint = StructureCarvingFootprint.fromColumns(
                sink -> emitCarveColumns(start, templates, sink), horizontalPadding, MAX_CARVE_COLUMNS);
        if (footprint == null) {
            throw new IllegalStateException("Native structure carve footprint is empty or exceeds "
                    + MAX_CARVE_COLUMNS + " columns");
        }
        CARVE_FOOTPRINTS.put(key, footprint);
        return footprint;
    }

    static OrganicCarve organicCarve(StructureCarvingFootprint footprint, IrisStructureTerrain terrain,
                                     IrisStructureCarveShape shape, long identity) {
        double strength = terrain.resolvedErosionStrength();
        double lobeStrength = terrain.resolvedLobeStrength();
        RNG noiseRng = new RNG(identity);
        CNG blob = null;
        CNG ceilingRoll = null;
        CNG floorRoll = null;
        CNG lobe = null;
        if (shape == IrisStructureCarveShape.ERODED && strength > 0D) {
            blob = CNG.signature(noiseRng);
            ceilingRoll = CNG.signature(noiseRng.nextParallelRNG(CARVE_CEILING_ROLL_SIGNATURE));
            floorRoll = CNG.signature(noiseRng.nextParallelRNG(CARVE_FLOOR_ROLL_SIGNATURE));
        }
        if (shape == IrisStructureCarveShape.ERODED && lobeStrength > 0D) {
            // A plain single octave channel: the fractured signature noise has no usable low frequency band.
            lobe = new CNG(noiseRng.nextParallelRNG(CARVE_LOBE_SIGNATURE), 1D, 1);
        }
        return new OrganicCarve(footprint, shape,
                Math.max(0, terrain.getHorizontalPadding()),
                Math.max(0, terrain.getCeilingPadding()),
                Math.max(0, terrain.getFloorPadding()),
                strength, terrain.resolvedErosionFrequency(), blob, ceilingRoll, floorRoll,
                lobe, terrain.resolvedLobeFrequency(), lobeStrength);
    }

    static void carveOrganicColumns(WorldGenLevel world, BoundingBox area, OrganicCarve carve) {
        StructureCarvingFootprint footprint = carve.footprint();
        int minX = Math.max(area.minX(), footprint.minX());
        int maxX = Math.min(area.maxX(), footprint.maxX());
        int minZ = Math.max(area.minZ(), footprint.minZ());
        int maxZ = Math.min(area.maxZ(), footprint.maxZ());
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        boolean eroded = carve.blob() != null;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int index = footprint.indexAt(x, z);
                long horizontalDistanceSquared = footprint.distanceSquaredAt(index);
                double sideReach = StructureCarveEnvelope.lobedSideReach(carve.lobe(),
                        carve.lobeFrequency(), carve.lobeStrength(), x, z, carve.horizontalPadding());
                double sideReachSquared = sideReach * sideReach;
                if (horizontalDistanceSquared > sideReachSquared) {
                    continue;
                }
                double normalizedHorizontal = sideReachSquared == 0D
                        ? 0D : horizontalDistanceSquared / sideReachSquared;
                int sourceMinY = footprint.sourceMinYAt(index);
                int sourceMaxY = footprint.sourceMaxYAt(index);
                double upReach = eroded
                        ? StructureCarveEnvelope.lobedUpReach(carve.lobe(), carve.lobeFrequency(),
                                carve.lobeStrength(), x, z,
                                StructureCarveEnvelope.erodedUpReach(carve.ceilingRoll(),
                                        carve.frequency(), carve.strength(), x, z,
                                        carve.ceilingPadding()))
                        : Math.max(1D, carve.ceilingPadding());
                double floorReach = eroded
                        ? StructureCarveEnvelope.erodedDownReach(carve.floorRoll(), carve.frequency(),
                                carve.strength(), x, z, carve.floorPadding())
                        : carve.floorPadding();
                int columnMinY = Math.max(area.minY(), sourceMinY - (int) Math.floor(floorReach));
                int columnMaxY = Math.min(area.maxY(),
                        sourceMaxY + (eroded ? (int) Math.ceil(upReach) : carve.ceilingPadding()));
                double downReach = Math.max(1D, floorReach);
                for (int y = columnMinY; y <= columnMaxY; y++) {
                    double normalizedVertical = StructureCarveEnvelope.normalizedVerticalDistance(
                            y, sourceMinY, sourceMaxY, upReach, downReach);
                    double distanceSquared = normalizedHorizontal
                            + normalizedVertical * normalizedVertical;
                    if (distanceSquared > 1D) {
                        continue;
                    }
                    if (distanceSquared > 0D && eroded) {
                        double noise = carve.blob().fitDouble(0D, 1D,
                                x * carve.frequency(), y * carve.frequency(), z * carve.frequency());
                        if (!StructureCarveEnvelope.shouldCarveOverboreCell(
                                carve.shape(), distanceSquared, noise, carve.strength())) {
                            continue;
                        }
                    }
                    world.setBlock(position.set(x, y, z), air, 2);
                }
            }
        }
    }

    private static boolean emitCarveColumns(StructureStart start,
                                            Supplier<StructureTemplateManager> templates,
                                            StructureCarvingFootprint.ColumnSink sink) {
        for (StructurePiece piece : start.getPieces()) {
            if (NativeStructureReferenceEnvelope.isMarker(piece)) {
                continue;
            }
            BoundingBox bounds = piece.getBoundingBox();
            if (!(piece instanceof PoolElementStructurePiece poolPiece)
                    || !emitTemplateColumns(pieceTemplates(poolPiece, templates),
                            poolPiece.getPosition(), poolPiece.getRotation(), bounds, sink)) {
                emitBoxColumns(bounds, sink);
            }
        }
        return true;
    }

    /**
     * Derives per-column vertical extents from the complement of the template's air and structure-void
     * cells, so a carve tracks the actual silhouette instead of the piece's rectangular bounding box.
     */
    static boolean emitTemplateColumns(List<StructureTemplate> templates, BlockPos position,
                                       Rotation rotation, BoundingBox bounds,
                                       StructureCarvingFootprint.ColumnSink sink) {
        if (templates.isEmpty()) {
            return false;
        }
        int width = bounds.getXSpan();
        int depth = bounds.getZSpan();
        long cells = (long) width * depth * bounds.getYSpan();
        if (cells < 1L || cells > MAX_TEMPLATE_OCCUPANCY_CELLS) {
            return false;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        boolean[] voidCells = null;
        for (StructureTemplate template : templates) {
            boolean[] templateVoid = new boolean[(int) cells];
            for (Block ignored : TEMPLATE_VOID_BLOCKS) {
                for (StructureTemplate.StructureBlockInfo info
                        : template.filterBlocks(position, settings, ignored)) {
                    BlockPos voidPosition = info.pos();
                    if (bounds.isInside(voidPosition)) {
                        templateVoid[templateCellIndex(bounds, width, depth,
                                voidPosition.getX(), voidPosition.getY(), voidPosition.getZ())] = true;
                    }
                }
            }
            if (voidCells == null) {
                voidCells = templateVoid;
                continue;
            }
            for (int cell = 0; cell < voidCells.length; cell++) {
                voidCells[cell] &= templateVoid[cell];
            }
        }
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    if (voidCells[templateCellIndex(bounds, width, depth, x, y, z)]) {
                        continue;
                    }
                    if (minY == Integer.MAX_VALUE) {
                        minY = y;
                    }
                    maxY = y;
                }
                if (minY != Integer.MAX_VALUE) {
                    sink.column(x, z, minY, maxY);
                }
            }
        }
        return true;
    }

    private static void emitBoxColumns(BoundingBox bounds, StructureCarvingFootprint.ColumnSink sink) {
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                sink.column(x, z, bounds.minY(), bounds.maxY());
            }
        }
    }

    private static int templateCellIndex(BoundingBox bounds, int width, int depth,
                                         int x, int y, int z) {
        return ((y - bounds.minY()) * depth + z - bounds.minZ()) * width + x - bounds.minX();
    }

    private static List<StructureTemplate> pieceTemplates(PoolElementStructurePiece poolPiece,
                                                          Supplier<StructureTemplateManager> templates) {
        List<StructureTemplate> resolved = new ArrayList<>(1);
        collectElementTemplates(poolPiece.getElement(), templates, resolved);
        return resolved;
    }

    private static void collectElementTemplates(StructurePoolElement element,
                                                Supplier<StructureTemplateManager> templates,
                                                List<StructureTemplate> resolved) {
        if (element instanceof ListPoolElement listElement) {
            for (StructurePoolElement child : listElement.getElements()) {
                collectElementTemplates(child, templates, resolved);
            }
            return;
        }
        if (element instanceof SinglePoolElement singleElement) {
            resolved.add(NativeStructureReflection.resolveTemplate(singleElement, templates));
        }
    }

    private static long carveNoiseIdentity(WorldGenLevel world, String structureId,
                                           StructureStart start) {
        return world.getSeed()
                ^ ((long) start.getChunkPos().x() * 341873128712L)
                ^ ((long) start.getChunkPos().z() * 132897987541L)
                ^ (structureId == null ? 0 : structureId.hashCode());
    }

    private static void encasePieces(WorldGenLevel world, BoundingBox area, String structureId,
                                     StructureStart start, IrisStructureTerrain terrain,
                                     NativeStructurePostProcessor.PaletteBlockResolver paletteBlockResolver) {
        IrisMaterialPalette palette = terrain.getEncasePalette();
        RNG rng = null;
        if (palette != null) {
            Objects.requireNonNull(paletteBlockResolver,
                    "Native structure encase palette requires a platform block resolver");
            rng = new RNG(world.getSeed() ^ (structureId == null ? 0 : structureId.hashCode()));
        }
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (BoundingBox bounds : contentPieceBounds(start)) {
            BoundingBox shell = paddedPieceArea(area, bounds, terrain);
            if (shell == null) {
                continue;
            }
            for (int x = shell.minX(); x <= shell.maxX(); x++) {
                for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
                    for (int y = shell.minY(); y <= shell.maxY(); y++) {
                        BlockState existing = world.getBlockState(position.set(x, y, z));
                        if (!isEncaseable(existing)) {
                            continue;
                        }
                        BlockState fill = palette == null
                                ? defaultEncaseBlock(y)
                                : Objects.requireNonNull(
                                        paletteBlockResolver.resolve(palette, rng, x, y, z),
                                        "Encase palette returned no block for " + structureId + " at "
                                                + x + "," + y + "," + z);
                        world.setBlock(position, fill, 2);
                    }
                }
            }
        }
    }

    static boolean isEncaseable(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    static BlockState defaultEncaseBlock(int y) {
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private static void carvePieceBoxes(WorldGenLevel world, BoundingBox area, StructureStart start,
                                        IrisStructureTerrain terrain) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (BoundingBox bounds : contentPieceBounds(start)) {
            BoundingBox carve = paddedPieceArea(area, bounds, terrain);
            if (carve == null) {
                continue;
            }
            for (int x = carve.minX(); x <= carve.maxX(); x++) {
                for (int z = carve.minZ(); z <= carve.maxZ(); z++) {
                    for (int y = carve.minY(); y <= carve.maxY(); y++) {
                        world.setBlock(position.set(x, y, z), air, 2);
                    }
                }
            }
        }
    }

    private static BoundingBox paddedPieceArea(BoundingBox area, BoundingBox bounds,
                                               IrisStructureTerrain terrain) {
        int horizontalPadding = Math.max(0, terrain.getHorizontalPadding());
        int minX = Math.max(area.minX(), bounds.minX() - horizontalPadding);
        int minY = Math.max(area.minY(), bounds.minY() - Math.max(0, terrain.getFloorPadding()));
        int minZ = Math.max(area.minZ(), bounds.minZ() - horizontalPadding);
        int maxX = Math.min(area.maxX(), bounds.maxX() + horizontalPadding);
        int maxY = Math.min(area.maxY(), bounds.maxY() + Math.max(0, terrain.getCeilingPadding()));
        int maxZ = Math.min(area.maxZ(), bounds.maxZ() + horizontalPadding);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static void clearLegacyTemplateAir(WorldGenLevel world, BoundingBox area,
                                       StructureStart start,
                                       Supplier<StructureTemplateManager> templates) {
        for (StructurePiece piece : start.getPieces()) {
            if (NativeStructureReferenceEnvelope.isMarker(piece)) {
                continue;
            }
            if (!(piece instanceof PoolElementStructurePiece poolPiece)
                    || poolPiece.getElement().getProjection() != StructureTemplatePool.Projection.RIGID
                    || !intersects(poolPiece.getBoundingBox(), area)) {
                continue;
            }
            int groundY = poolPiece.getBoundingBox().minY() + poolPiece.getGroundLevelDelta();
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(poolPiece.getRotation())
                    .setBoundingBox(area);
            clearLegacyTemplateAir(world, poolPiece.getElement(), poolPiece.getPosition(),
                    groundY, settings, templates);
        }
    }

    private static void clearLegacyTemplateAir(WorldGenLevel world, StructurePoolElement element,
                                               BlockPos position, int groundY,
                                               StructurePlaceSettings settings,
                                               Supplier<StructureTemplateManager> templates) {
        if (element instanceof ListPoolElement listElement) {
            for (StructurePoolElement child : listElement.getElements()) {
                clearLegacyTemplateAir(world, child, position, groundY, settings, templates);
            }
            return;
        }
        if (!(element instanceof LegacySinglePoolElement legacyElement)) {
            return;
        }
        StructureTemplate template = NativeStructureReflection.resolveTemplate(legacyElement, templates);
        clearTemplateAir(world, template, position, groundY, settings);
    }

    static void clearTemplateAir(WorldGenLevel world, StructureTemplate template,
                                 BlockPos position, int groundY,
                                 StructurePlaceSettings settings) {
        List<StructureTemplate.StructureBlockInfo> airBlocks = template.filterBlocks(
                position, settings, Blocks.AIR);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (StructureTemplate.StructureBlockInfo airBlock : airBlocks) {
            BlockPos airPosition = airBlock.pos();
            BlockState existingState = world.getBlockState(airPosition);
            if (shouldClearLegacyAir(
                    airPosition.getY(), groundY, existingState.isAir())
                    && !NativeStructureVegetationClearer.isTreeBlock(existingState)) {
                world.setBlock(airPosition, air, 2);
            }
        }
    }

    static boolean shouldClearLegacyAir(int airY, int groundY, boolean existingAir) {
        return airY >= groundY && !existingAir;
    }

    static boolean intersects(BoundingBox first, BoundingBox second) {
        return first.maxX() >= second.minX() && first.minX() <= second.maxX()
                && first.maxY() >= second.minY() && first.minY() <= second.maxY()
                && first.maxZ() >= second.minZ() && first.minZ() <= second.maxZ();
    }

    // StructureStart has no value equality, so the key pins the exact start instance a chunk carves against.
    private record CarveFootprintKey(StructureStart start, int padding) {
    }

    record OrganicCarve(StructureCarvingFootprint footprint, IrisStructureCarveShape shape,
                        int horizontalPadding, int ceilingPadding, int floorPadding,
                        double strength, double frequency, CNG blob, CNG ceilingRoll, CNG floorRoll,
                        CNG lobe, double lobeFrequency, double lobeStrength) {
    }

    public record TerrainTarget(String structureId, StructureStart start,
                                IrisStructureTerrain terrain) {
    }
}

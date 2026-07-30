package art.arcane.iris.nativegen;

import art.arcane.iris.engine.framework.StructureVerticalBounds;
import art.arcane.iris.engine.mantle.components.StructureCarveEnvelope;
import art.arcane.iris.engine.mantle.components.StructureCarvingFootprint;
import art.arcane.iris.engine.object.IrisMaterialPalette;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.IrisObjectVacuum;
import art.arcane.iris.engine.object.IrisStructureCarveShape;
import art.arcane.iris.engine.object.IrisStructureStiltSettings;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import art.arcane.iris.engine.object.IrisStructureYBand;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.math.RNG;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;

public final class NativeStructurePostProcessor {
    private static final int AUTO_ENCASE_PADDING = 3;
    private static final long CARVE_CEILING_ROLL_SIGNATURE = 0x2A17L;
    private static final long CARVE_FLOOR_ROLL_SIGNATURE = 0x5B3DL;
    private static final long CARVE_LOBE_SIGNATURE = 0x7C41L;
    private static final String DESERT_PYRAMID_ID = "minecraft:desert_pyramid";
    private static final int FOUNDATION_VERTICAL_TOLERANCE = 1;
    private static final String JUNGLE_PYRAMID_ID = "minecraft:jungle_pyramid";
    private static final int MAX_BURIAL_COLUMNS = 2_000_000;
    private static final int MAX_CACHED_CARVE_FOOTPRINTS = 4;
    private static final int MAX_CARVE_COLUMNS = 2_000_000;
    private static final int MAX_TEMPLATE_OCCUPANCY_CELLS = 4_194_304;
    private static final int MONUMENT_BASE_BELOW_SEA_LEVEL = 24;
    private static final String OCEAN_MONUMENT_ID = "minecraft:monument";
    private static final double SURFACE_TERRAIN_FALLOFF = 2.0;
    private static final long SURFACE_TERRAIN_INFLUENCE_SCALE = 1_000_000L;
    private static final int SURFACE_TERRAIN_RADIUS = 12;
    private static final List<Block> TEMPLATE_VOID_BLOCKS = List.of(Blocks.AIR, Blocks.STRUCTURE_VOID);
    private static final int UNDERGROUND_SURFACE_CLEARANCE = 1;
    private static final Map<CarveFootprintKey, StructureCarvingFootprint> CARVE_FOOTPRINTS =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<CarveFootprintKey, StructureCarvingFootprint> eldest) {
                    return size() > MAX_CACHED_CARVE_FOOTPRINTS;
                }
            });

    private NativeStructurePostProcessor() {
    }

    public static void place(WorldGenLevel world, StructureManager structureManager, ChunkGenerator generator,
                             WorldgenRandom random, BoundingBox area, ChunkPos chunkPos, String structureId,
                             StructureStart start, IrisNativeStructureDecision decision,
                             PaletteBlockResolver paletteBlockResolver,
                             IntBinaryOperator surfaceHeight) {
        IrisStructureStiltSettings stilt = decision.stilt();
        ensureMonumentSeaLevelAlignment(start, structureId, decision.yShift(), generator.getSeaLevel(),
                area.minY(), area.maxY() + 1);
        start.placeInChunk(world, structureManager, generator, random, area, chunkPos);
        if (stilt != null) {
            placeStilts(world, area, structureId, start, stilt, paletteBlockResolver, surfaceHeight,
                    !isUndergroundStep(start.getStructure().step()));
        }
    }

    public static void prepareTerrain(WorldGenLevel world, BoundingBox area,
                                      List<TerrainTarget> targets,
                                      PaletteBlockResolver paletteBlockResolver) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (TerrainTarget target : targets) {
            integrateTerrain(world, area, target.structureId(), target.start(), target.terrain(),
                    paletteBlockResolver);
        }
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
                                 PaletteBlockResolver paletteBlockResolver) {
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
            resolved.add(resolveTemplate(singleElement, templates));
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
                                     PaletteBlockResolver paletteBlockResolver) {
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

    public static int applyVerticalPlacement(StructureStart start, String structureId, int requestedOffset,
                                             int seaLevel, int worldMinY, int worldMaxYExclusive,
                                             boolean underground, boolean preserveSourceY,
                                             IrisStructureYBand yBand,
                                             IntBinaryOperator surfaceHeight) {
        if (isOceanMonument(structureId)) {
            return alignOceanMonumentToSeaLevel(
                    start, requestedOffset, seaLevel, worldMinY, worldMaxYExclusive);
        }
        if (isAdjustedScatteredStructure(structureId)) {
            return alignScatteredStructureToSurface(
                    start, structureId, requestedOffset, worldMinY, worldMaxYExclusive, surfaceHeight);
        }
        return applyVerticalShift(start, requestedOffset, worldMinY, worldMaxYExclusive,
                underground, preserveSourceY, yBand, surfaceHeight);
    }

    public static StructureStart relocateToMinY(StructureStart start, Structure source, int targetMinY,
                                                LevelHeightAccessor heightAccessor) {
        Objects.requireNonNull(start, "Native structure start must not be null");
        Objects.requireNonNull(source, "Native structure source must not be null");
        Objects.requireNonNull(heightAccessor, "Native structure height accessor must not be null");
        if (!start.isValid()) {
            return StructureStart.INVALID_START;
        }
        List<StructurePiece> pieces = start.getPieces();
        int minY = Integer.MAX_VALUE;
        for (StructurePiece piece : pieces) {
            minY = Math.min(minY, piece.getBoundingBox().minY());
        }
        if (minY == Integer.MAX_VALUE) {
            return StructureStart.INVALID_START;
        }
        int offsetY = Math.subtractExact(targetMinY, minY);
        if (offsetY != 0) {
            for (StructurePiece piece : pieces) {
                moveStructurePiece(piece, offsetY);
            }
        }
        int worldMinY = heightAccessor.getMinY() + 1;
        int worldMaxYExclusive = Math.addExact(
                heightAccessor.getMinY(), heightAccessor.getHeight());
        for (StructurePiece piece : pieces) {
            BoundingBox bounds = piece.getBoundingBox();
            if (bounds.minY() < worldMinY || bounds.maxY() >= worldMaxYExclusive) {
                throw new IllegalStateException("Native structure cannot fit target minimum Y "
                        + targetMinY + " inside world bounds [" + worldMinY + ","
                        + worldMaxYExclusive + ")");
            }
        }
        return new StructureStart(
                source,
                start.getChunkPos(),
                start.getReferences(),
                new PiecesContainer(List.copyOf(pieces))
        );
    }

    static int alignScatteredStructureToSurface(StructureStart start, String structureId,
                                                  int configuredOffset, int worldMinY,
                                                  int worldMaxYExclusive,
                                                  IntBinaryOperator surfaceHeight) {
        Objects.requireNonNull(surfaceHeight, "Scattered native structure requires a terrain height resolver");
        ScatteredFeaturePiece piece = requireAdjustedScatteredPiece(start, structureId);
        BoundingBox bounds = start.getBoundingBox();
        BoundingBox pieceBounds = piece.getBoundingBox();
        int surfaceY = representativeScatteredSurfaceY(structureId, pieceBounds, surfaceHeight);
        int targetMinY = Math.addExact(Math.addExact(surfaceY, 1), configuredOffset);
        int requestedMove = Math.subtractExact(targetMinY, pieceBounds.minY());
        int offsetY = StructureVerticalBounds.clampOffset(
                bounds.minY(), bounds.maxY(), requestedMove, worldMinY, worldMaxYExclusive);
        if (offsetY != 0) {
            moveStructureStart(start, bounds, offsetY);
        }
        setScatteredHeightPosition(piece, Math.max(0, piece.getBoundingBox().minY()));
        return offsetY;
    }

    public static int applyVerticalShift(StructureStart start, int requestedOffset, int worldMinY,
                                         int worldMaxYExclusive, boolean underground,
                                         boolean preserveSourceY, IrisStructureYBand yBand,
                                         IntBinaryOperator surfaceHeight) {
        BoundingBox bounds = start.getBoundingBox();
        int resolvedOffset = resolveShiftOffset(start, bounds, requestedOffset, worldMinY,
                worldMaxYExclusive, underground, preserveSourceY, yBand, surfaceHeight);
        int offsetY = StructureVerticalBounds.clampOffset(
                bounds.minY(), bounds.maxY(), resolvedOffset, worldMinY, worldMaxYExclusive);
        if (offsetY == 0) {
            return 0;
        }
        moveStructureStart(start, bounds, offsetY);
        return offsetY;
    }

    private static int resolveShiftOffset(StructureStart start, BoundingBox bounds, int requestedOffset,
                                          int worldMinY, int worldMaxYExclusive, boolean underground,
                                          boolean preserveSourceY, IrisStructureYBand yBand,
                                          IntBinaryOperator surfaceHeight) {
        if (preserveSourceY) {
            return requestedOffset;
        }
        if (yBand != null) {
            return resolveYBandOffset(bounds, yBand, start.getChunkPos());
        }
        if (underground) {
            return resolveBuriedOffset(
                    bounds, requestedOffset, worldMinY, worldMaxYExclusive, surfaceHeight);
        }
        return requestedOffset;
    }

    static int alignOceanMonumentToSeaLevel(StructureStart start, int configuredOffset, int seaLevel,
                                             int worldMinY, int worldMaxYExclusive) {
        OceanMonumentPieces.MonumentBuilding building = requireOceanMonumentBuilding(start);
        BoundingBox bounds = start.getBoundingBox();
        int targetMinY = Math.addExact(
                Math.subtractExact(seaLevel, MONUMENT_BASE_BELOW_SEA_LEVEL), configuredOffset);
        int requestedOffset = Math.subtractExact(targetMinY, building.getBoundingBox().minY());
        int offsetY = StructureVerticalBounds.clampOffset(
                bounds.minY(), bounds.maxY(), requestedOffset, worldMinY, worldMaxYExclusive);
        if (offsetY != requestedOffset) {
            throw new IllegalStateException("Ocean monument cannot align to sea level " + seaLevel
                    + " with configured offset " + configuredOffset + " inside world bounds ["
                    + worldMinY + "," + worldMaxYExclusive + ")");
        }
        if (offsetY == 0) {
            return 0;
        }
        moveStructureStart(start, bounds, offsetY);
        return offsetY;
    }

    private static void ensureMonumentSeaLevelAlignment(StructureStart start, String structureId,
                                                         int configuredOffset, int seaLevel,
                                                         int worldMinY, int worldMaxYExclusive) {
        if (isOceanMonument(structureId)) {
            alignOceanMonumentToSeaLevel(
                    start, configuredOffset, seaLevel, worldMinY, worldMaxYExclusive);
        }
    }

    private static boolean isOceanMonument(String structureId) {
        return OCEAN_MONUMENT_ID.equals(structureId);
    }

    private static boolean isAdjustedScatteredStructure(String structureId) {
        return DESERT_PYRAMID_ID.equals(structureId) || JUNGLE_PYRAMID_ID.equals(structureId);
    }

    private static ScatteredFeaturePiece requireAdjustedScatteredPiece(StructureStart start,
                                                                        String structureId) {
        Objects.requireNonNull(start, "Scattered native structure start must not be null");
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.size() != 1) {
            throw new IllegalStateException(structureId + " must contain exactly one scattered piece, found "
                    + pieces.size());
        }
        StructurePiece piece = pieces.get(0);
        if (DESERT_PYRAMID_ID.equals(structureId) && piece instanceof DesertPyramidPiece desertPyramid) {
            return desertPyramid;
        }
        if (JUNGLE_PYRAMID_ID.equals(structureId) && piece instanceof JungleTemplePiece jungleTemple) {
            return jungleTemple;
        }
        throw new IllegalStateException(structureId + " contains unexpected piece "
                + piece.getClass().getName());
    }

    private static int representativeScatteredSurfaceY(String structureId, BoundingBox bounds,
                                                        IntBinaryOperator surfaceHeight) {
        if (DESERT_PYRAMID_ID.equals(structureId)) {
            return lowestSurfaceY(bounds, surfaceHeight);
        }
        if (JUNGLE_PYRAMID_ID.equals(structureId)) {
            return averageSurfaceY(bounds, surfaceHeight);
        }
        throw new IllegalStateException("Unsupported scattered native structure " + structureId);
    }

    private static int lowestSurfaceY(BoundingBox bounds, IntBinaryOperator surfaceHeight) {
        int lowestY = Integer.MAX_VALUE;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                lowestY = Math.min(lowestY, surfaceHeight.applyAsInt(x, z));
            }
        }
        if (lowestY == Integer.MAX_VALUE) {
            throw new IllegalStateException("Scattered native structure has an empty terrain footprint");
        }
        return lowestY;
    }

    private static int averageSurfaceY(BoundingBox bounds, IntBinaryOperator surfaceHeight) {
        long totalY = 0L;
        long columns = 0L;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                totalY += surfaceHeight.applyAsInt(x, z);
                columns++;
            }
        }
        if (columns == 0L) {
            throw new IllegalStateException("Scattered native structure has an empty terrain footprint");
        }
        return Math.toIntExact(totalY / columns);
    }

    private static void setScatteredHeightPosition(ScatteredFeaturePiece piece, int heightPosition) {
        try {
            ScatteredHeightPositionAccess.FIELD.setInt(piece, heightPosition);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot lock scattered native structure height", error);
        }
    }

    static Field resolveScatteredHeightPositionField() {
        Field resolved = null;
        for (Field field : ScatteredFeaturePiece.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || field.getType() != int.class
                    || !Modifier.isProtected(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }
            if (resolved != null) {
                throw new IllegalStateException("ScatteredFeaturePiece has multiple mutable protected int fields");
            }
            resolved = field;
        }
        if (resolved == null) {
            throw new IllegalStateException("ScatteredFeaturePiece height-position field is missing");
        }
        if (!resolved.trySetAccessible()) {
            throw new IllegalStateException("ScatteredFeaturePiece height-position field is inaccessible");
        }
        return resolved;
    }

    private static OceanMonumentPieces.MonumentBuilding requireOceanMonumentBuilding(StructureStart start) {
        Objects.requireNonNull(start, "Ocean monument start must not be null");
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.size() != 1 || !(pieces.get(0) instanceof OceanMonumentPieces.MonumentBuilding building)) {
            throw new IllegalStateException("minecraft:monument must contain exactly one MonumentBuilding, found "
                    + pieces.size() + " top-level pieces");
        }
        return building;
    }

    private static void moveStructureStart(StructureStart start, BoundingBox cachedBounds, int offsetY) {
        for (StructurePiece piece : start.getPieces()) {
            moveStructurePiece(piece, offsetY);
        }
        cachedBounds.move(0, offsetY, 0);
    }

    private static void moveStructurePiece(StructurePiece piece, int offsetY) {
        piece.move(0, offsetY, 0);
        if (piece instanceof OceanMonumentPieces.MonumentBuilding building) {
            for (StructurePiece child : monumentChildPieces(building)) {
                child.move(0, offsetY, 0);
            }
        }
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

    static List<StructurePiece> monumentChildPieces(OceanMonumentPieces.MonumentBuilding building) {
        Object value;
        try {
            value = MonumentChildPiecesAccess.FIELD.get(building);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot read Ocean Monument child pieces", error);
        }
        if (!(value instanceof List<?> children)) {
            throw new IllegalStateException("Ocean Monument child-pieces field is not a list");
        }
        List<StructurePiece> pieces = new ArrayList<>(children.size());
        for (Object child : children) {
            if (!(child instanceof StructurePiece monumentPiece)
                    || child.getClass().getEnclosingClass() != OceanMonumentPieces.class) {
                throw new IllegalStateException("Ocean Monument child-pieces list contains "
                        + (child == null ? "null" : child.getClass().getName()));
            }
            pieces.add(monumentPiece);
        }
        return List.copyOf(pieces);
    }

    private static Field resolveMonumentChildPiecesField() {
        Field resolved = null;
        for (Field field : OceanMonumentPieces.MonumentBuilding.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != List.class) {
                continue;
            }
            if (resolved != null) {
                throw new IllegalStateException("Ocean Monument has multiple instance List fields");
            }
            resolved = field;
        }
        if (resolved == null) {
            throw new IllegalStateException("Ocean Monument child-pieces List field is missing");
        }
        if (!Modifier.isPrivate(resolved.getModifiers()) || !Modifier.isFinal(resolved.getModifiers())) {
            throw new IllegalStateException("Ocean Monument child-pieces field has an unexpected access contract");
        }
        if (!resolved.trySetAccessible()) {
            throw new IllegalStateException("Ocean Monument child-pieces field is inaccessible");
        }
        return resolved;
    }

    static int resolveYBandOffset(BoundingBox bounds, IrisStructureYBand yBand, ChunkPos startChunk) {
        Objects.requireNonNull(bounds, "Native structure bounds must not be null");
        Objects.requireNonNull(startChunk, "Native structure Y band requires a start chunk");
        int target = yBandTargetMidpointY(
                bounds.minY(), bounds.maxY(), yBand.resolvedMin(), yBand.resolvedMax(), startChunk);
        return Math.subtractExact(target, midpointY(bounds.minY(), bounds.maxY()));
    }

    static int yBandTargetMidpointY(int minY, int maxY, int bandMin, int bandMax, ChunkPos startChunk) {
        int height = Math.subtractExact(maxY, minY);
        int lowest = Math.addExact(bandMin, height / 2);
        int highest = Math.subtractExact(bandMax, height - height / 2);
        if (lowest > highest) {
            // The band is shorter than the structure, so only its midpoint can be honoured.
            return Math.floorDiv(Math.addExact(bandMin, bandMax), 2);
        }
        int span = highest - lowest + 1;
        if (span == 1) {
            return lowest;
        }
        long identity = (long) startChunk.x() * 341873128712L ^ (long) startChunk.z() * 132897987541L;
        return lowest + new RNG(identity).nextInt(span);
    }

    private static int midpointY(int minY, int maxY) {
        return minY + (maxY - minY) / 2;
    }

    static int resolveBuriedOffset(BoundingBox bounds, int requestedOffset, int worldMinY,
                                   int worldMaxYExclusive, IntBinaryOperator surfaceHeight) {
        Objects.requireNonNull(bounds, "Native structure bounds must not be null");
        Objects.requireNonNull(surfaceHeight, "Underground native structure requires a terrain height resolver");
        long width = (long) bounds.maxX() - bounds.minX() + 1L;
        long depth = (long) bounds.maxZ() - bounds.minZ() + 1L;
        if (width <= 0L || depth <= 0L || width > MAX_BURIAL_COLUMNS / depth) {
            throw new IllegalStateException("Underground native structure burial footprint is invalid or exceeds "
                    + MAX_BURIAL_COLUMNS + " columns");
        }
        int maximumOffset = requestedOffset;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                int allowedTopY = surfaceHeight.applyAsInt(x, z) - UNDERGROUND_SURFACE_CLEARANCE;
                maximumOffset = Math.min(maximumOffset, allowedTopY - bounds.maxY());
            }
        }
        int clampedOffset = StructureVerticalBounds.clampOffset(
                bounds.minY(), bounds.maxY(), maximumOffset, worldMinY, worldMaxYExclusive);
        if (clampedOffset > maximumOffset) {
            IrisLogging.warn("Native structure burial at " + bounds.minX() + "," + bounds.minZ()
                    + " clamped to world floor: wanted " + maximumOffset + ", used " + clampedOffset);
        }
        return clampedOffset;
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

    public static void prepareSurfaceStructures(WorldGenLevel world, BoundingBox area,
                                                List<StructureStart> starts,
                                                IntBinaryOperator surfaceHeight) {
        if (starts == null || starts.isEmpty()) {
            return;
        }
        Objects.requireNonNull(surfaceHeight, "Surface structure terrain fitting requires an Iris height resolver");
        List<SurfaceAnchor> anchors = collectSurfaceAnchors(starts);
        if (!anchors.isEmpty()) {
            fitSurfaceTerrain(world, area, anchors, surfaceHeight);
        }
        Supplier<StructureTemplateManager> templates = () -> world.getLevel().getStructureManager();
        for (StructureStart start : starts) {
            if (requiresSurfaceTerrain(start)) {
                clearLegacyTemplateAir(world, area, start, templates);
            }
        }
    }

    static boolean shouldPrepareSurfaceTerrain(TerrainAdjustment adjustment,
                                               GenerationStep.Decoration step) {
        return adjustment == TerrainAdjustment.BEARD_THIN
                && step == GenerationStep.Decoration.SURFACE_STRUCTURES;
    }

    static int resolveSurfaceTarget(List<SurfaceAnchor> anchors, int worldX, int worldZ,
                                    int originalY) {
        int localTargetY = originalY;
        SurfaceAnchor selectedLocal = null;
        long totalInfluence = 0L;
        long weightedMeetY = 0L;
        long maximumInfluence = 0L;
        for (SurfaceAnchor anchor : anchors) {
            int outX = IrisObjectVacuum.outset(worldX, anchor.minX(), anchor.maxX());
            int outZ = IrisObjectVacuum.outset(worldZ, anchor.minZ(), anchor.maxZ());
            long distanceSquared = (long) outX * outX + (long) outZ * outZ;
            if (distanceSquared > (long) SURFACE_TERRAIN_RADIUS * SURFACE_TERRAIN_RADIUS) {
                continue;
            }
            boolean containsColumn = outX == 0 && outZ == 0;
            if (containsColumn) {
                if (precedes(anchor, selectedLocal)) {
                    localTargetY = anchor.meetY();
                    selectedLocal = anchor;
                }
                continue;
            }
            double factor = IrisObjectVacuum.columnInfluence(
                    worldX, worldZ,
                    anchor.minX(), anchor.maxX(), anchor.minZ(), anchor.maxZ(),
                    SURFACE_TERRAIN_RADIUS, SURFACE_TERRAIN_FALLOFF);
            long influence = Math.round(factor * SURFACE_TERRAIN_INFLUENCE_SCALE);
            if (influence <= 0L) {
                continue;
            }
            long weightedInfluence = influence * Math.max(1, anchor.strength());
            totalInfluence += weightedInfluence;
            weightedMeetY += weightedInfluence * anchor.meetY();
            maximumInfluence = Math.max(maximumInfluence, influence);
        }
        if (selectedLocal != null) {
            return localTargetY;
        }
        if (totalInfluence == 0L) {
            return originalY;
        }
        double blendedMeetY = weightedMeetY / (double) totalInfluence;
        double factor = maximumInfluence / (double) SURFACE_TERRAIN_INFLUENCE_SCALE;
        return (int) Math.round(originalY + ((blendedMeetY - originalY) * factor));
    }

    private static boolean precedes(SurfaceAnchor candidate, SurfaceAnchor selected) {
        if (selected == null) {
            return true;
        }
        if (candidate.strength() != selected.strength()) {
            return candidate.strength() > selected.strength();
        }
        if (candidate.meetY() != selected.meetY()) {
            return candidate.meetY() < selected.meetY();
        }
        if (candidate.minX() != selected.minX()) {
            return candidate.minX() < selected.minX();
        }
        if (candidate.minZ() != selected.minZ()) {
            return candidate.minZ() < selected.minZ();
        }
        if (candidate.maxX() != selected.maxX()) {
            return candidate.maxX() < selected.maxX();
        }
        if (candidate.maxZ() != selected.maxZ()) {
            return candidate.maxZ() < selected.maxZ();
        }
        return false;
    }

    private static List<SurfaceAnchor> collectSurfaceAnchors(List<StructureStart> starts) {
        List<SurfaceAnchor> anchors = new ArrayList<>();
        for (StructureStart start : starts) {
            if (!requiresSurfaceTerrain(start)) {
                continue;
            }
            for (StructurePiece piece : start.getPieces()) {
                if (NativeStructureReferenceEnvelope.isMarker(piece)) {
                    continue;
                }
                if (piece instanceof PoolElementStructurePiece poolPiece) {
                    if (poolPiece.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                        BoundingBox bounds = poolPiece.getBoundingBox();
                        anchors.add(new SurfaceAnchor(
                                bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(),
                                bounds.minY() + poolPiece.getGroundLevelDelta() - 1, 2));
                    }
                    for (JigsawJunction junction : poolPiece.getJunctions()) {
                        anchors.add(new SurfaceAnchor(
                                junction.getSourceX(), junction.getSourceX(),
                                junction.getSourceZ(), junction.getSourceZ(),
                                junction.getSourceGroundY() - 1, 1));
                    }
                    continue;
                }
                BoundingBox bounds = piece.getBoundingBox();
                anchors.add(new SurfaceAnchor(
                        bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(),
                        bounds.minY() - 1, 2));
            }
        }
        return List.copyOf(anchors);
    }

    static boolean requiresSurfaceTerrain(StructureStart start) {
        return start != null
                && start.isValid()
                && shouldPrepareSurfaceTerrain(
                        start.getStructure().terrainAdaptation(), start.getStructure().step());
    }

    private static void fitSurfaceTerrain(WorldGenLevel world, BoundingBox area,
                                          List<SurfaceAnchor> anchors,
                                          IntBinaryOperator surfaceHeight) {
        int width = area.getXSpan();
        int depth = area.getZSpan();
        int[] originalHeights = new int[width * depth];
        int[] targetHeights = new int[width * depth];
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int column = (z - area.minZ()) * width + x - area.minX();
                int originalY = Math.max(area.minY(), Math.min(
                        area.maxY(), surfaceHeight.applyAsInt(x, z)));
                originalHeights[column] = originalY;
                targetHeights[column] = Math.max(area.minY(), Math.min(
                        area.maxY(), resolveSurfaceTarget(anchors, x, z, originalY)));
            }
        }
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int column = (z - area.minZ()) * width + x - area.minX();
                applySurfaceColumn(world, position, x, z,
                        originalHeights[column], targetHeights[column], area.minY(), area.maxY());
            }
        }
    }

    static void applySurfaceColumn(WorldGenLevel world, BlockPos.MutableBlockPos position,
                                   int x, int z, int originalY, int targetY,
                                   int worldMinY, int worldMaxY) {
        if (targetY == originalY) {
            return;
        }
        SurfaceMaterials materials = resolveSurfaceMaterials(world, position, x, z, originalY, worldMinY);
        if (targetY < originalY) {
            BlockState clearedState = clearSurfaceDecorationAndResolveFill(
                    world, position, x, z, originalY, worldMaxY);
            for (int y = originalY; y > targetY; y--) {
                world.setBlock(position.set(x, y, z), clearedState, 2);
            }
            world.setBlock(position.set(x, targetY, z), materials.surface(), 2);
            return;
        }
        for (int y = originalY + 1; y < targetY; y++) {
            position.set(x, y, z);
            if (!isTreeBlock(world.getBlockState(position))) {
                world.setBlock(position, materials.subsurface(), 2);
            }
        }
        position.set(x, targetY, z);
        if (!isTreeBlock(world.getBlockState(position))) {
            world.setBlock(position, materials.surface(), 2);
        }
    }

    private static BlockState clearSurfaceDecorationAndResolveFill(
            WorldGenLevel world, BlockPos.MutableBlockPos position,
            int x, int z, int originalY, int worldMaxY) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = originalY + 1; y <= worldMaxY; y++) {
            BlockState state = world.getBlockState(position.set(x, y, z));
            if (state.isAir()) {
                return air;
            }
            if (!state.getFluidState().isEmpty()) {
                BlockState fluid = state.getFluidState().createLegacyBlock();
                if (state != fluid) {
                    world.setBlock(position, fluid, 2);
                }
                return fluid;
            }
            if (state.isSolid() || isTreeBlock(state)) {
                return air;
            }
            world.setBlock(position, air, 2);
        }
        return air;
    }

    private static SurfaceMaterials resolveSurfaceMaterials(WorldGenLevel world,
                                                            BlockPos.MutableBlockPos position,
                                                            int x, int z, int originalY,
                                                            int worldMinY) {
        BlockState surface = world.getBlockState(position.set(x, originalY, z));
        BlockState subsurface = null;
        for (int y = originalY - 1; y >= worldMinY; y--) {
            BlockState candidate = world.getBlockState(position.set(x, y, z));
            if (isTerrainBlock(candidate)) {
                subsurface = candidate;
                break;
            }
        }
        if (subsurface == null) {
            subsurface = isTerrainBlock(surface) ? surface : Blocks.STONE.defaultBlockState();
        }
        if (!isTerrainBlock(surface)) {
            surface = subsurface;
        }
        return new SurfaceMaterials(surface, subsurface);
    }

    private static boolean isTerrainBlock(BlockState state) {
        return state.isSolid() && !isTreeBlock(state);
    }

    private static void clearLegacyTemplateAir(WorldGenLevel world, BoundingBox area,
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
        StructureTemplate template = resolveTemplate(legacyElement, templates);
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
                    && !isTreeBlock(existingState)) {
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

    private static StructureTemplate resolveTemplate(SinglePoolElement element,
                                                     Supplier<StructureTemplateManager> templates) {
        Object value;
        try {
            value = SinglePoolTemplateAccess.FIELD.get(element);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot read native structure pool template", error);
        }
        if (!(value instanceof Either<?, ?> reference)) {
            throw new IllegalStateException("Native structure pool template field is not an Either");
        }
        return resolveTemplateReference(reference, templates);
    }

    static StructureTemplate resolveTemplateReference(Either<?, ?> reference,
                                                       Supplier<StructureTemplateManager> templates) {
        return reference.map(
                location -> resolveNamedTemplate(location, templates),
                NativeStructurePostProcessor::requireRuntimeTemplate);
    }

    private static StructureTemplate resolveNamedTemplate(Object value,
                                                          Supplier<StructureTemplateManager> templates) {
        if (!(value instanceof Identifier identifier)) {
            throw new IllegalStateException("Native structure pool template identifier is "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return Objects.requireNonNull(templates == null ? null : templates.get(),
                "Native structure template manager is unavailable").getOrCreate(identifier);
    }

    private static StructureTemplate requireRuntimeTemplate(Object value) {
        if (!(value instanceof StructureTemplate template)) {
            throw new IllegalStateException("Native runtime structure pool template is "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return template;
    }

    static Field resolveSinglePoolTemplateField() {
        Field resolved = null;
        for (Field field : SinglePoolElement.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != Either.class) {
                continue;
            }
            if (resolved != null) {
                throw new IllegalStateException("SinglePoolElement has multiple instance Either fields");
            }
            resolved = field;
        }
        if (resolved == null) {
            throw new IllegalStateException("SinglePoolElement template Either field is missing");
        }
        if (!Modifier.isProtected(resolved.getModifiers()) || !Modifier.isFinal(resolved.getModifiers())) {
            throw new IllegalStateException("SinglePoolElement template field has an unexpected access contract");
        }
        if (!resolved.trySetAccessible()) {
            throw new IllegalStateException("SinglePoolElement template field is inaccessible");
        }
        return resolved;
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

    private static boolean isTreeBlock(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae")
                || path.endsWith("_leaves");
    }

    private static List<FoundationColumn> foundationEnvelope(BoundingBox area, StructureStart start) {
        BoundingBox structure = NativeStructureReferenceEnvelope.contentBounds(start);
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
            if (NativeStructureReferenceEnvelope.isMarker(piece)) {
                continue;
            }
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
                                    StructureStart start, IrisStructureStiltSettings settings,
                                    PaletteBlockResolver paletteBlockResolver,
                                    IntBinaryOperator surfaceHeight,
                                    boolean surfaceStructure) {
        Objects.requireNonNull(surfaceHeight, "Structure stilts require an Iris terrain height resolver");
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int structureHash = structureId == null ? 0 : structureId.hashCode();
        RNG rng = new RNG(world.getSeed() ^ structureHash);
        for (FoundationColumn column : foundationEnvelope(area, start)) {
            if (!isStiltColumn(column.x(), column.z(), settings.getSpacing())) {
                continue;
            }
            int foundationY = findFoundationY(world, column, position);
            if (foundationY == Integer.MIN_VALUE) {
                continue;
            }
            int terrainY = surfaceStructure
                    ? Math.max(area.minY(), Math.min(
                            area.maxY(), surfaceHeight.applyAsInt(column.x(), column.z())))
                    : area.minY() - 1;
            int anchorY = findStiltAnchorY(
                    world, column.x(), column.z(), foundationY,
                    Math.max(1, settings.getMaxDepth()), terrainY, area.minY(), position);
            if (anchorY == Integer.MIN_VALUE) {
                continue;
            }
            for (int y = foundationY - 1; y > anchorY; y--) {
                position.set(column.x(), y, column.z());
                BlockState stilt = settings.getPalette() == null
                        ? Blocks.COBBLESTONE.defaultBlockState()
                        : Objects.requireNonNull(
                                paletteBlockResolver.resolve(
                                        settings.getPalette(), rng, column.x(), y, column.z()),
                                "Stilt palette returned no block for " + structureId + " at "
                                        + column.x() + "," + y + "," + column.z());
                world.setBlock(position, stilt, 2);
            }
        }
    }

    static boolean isStiltColumn(int x, int z, int spacing) {
        int resolvedSpacing = Math.max(1, spacing);
        return resolvedSpacing == 1
                || Math.floorMod(x, resolvedSpacing) == 0
                && Math.floorMod(z, resolvedSpacing) == 0;
    }

    static int findStiltAnchorY(
            WorldGenLevel world, int x, int z, int foundationY, int maxDepth,
            int terrainY, int areaMinY, BlockPos.MutableBlockPos position) {
        int minimumAnchorY = Math.max(
                areaMinY, Math.max(terrainY, foundationY - maxDepth - 1));
        for (int y = foundationY - 1; y >= minimumAnchorY; y--) {
            BlockState state = world.getBlockState(position.set(x, y, z));
            boolean vegetation = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
            if (!vegetation && state.isFaceSturdy(
                    world, position, Direction.UP, SupportType.FULL)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    public static StiltSupportAudit auditStiltSupport(WorldGenLevel world, BoundingBox area,
                                                       StructureStart start, BlockState expectedStilt,
                                                       IntBinaryOperator surfaceHeight) {
        Objects.requireNonNull(expectedStilt, "Expected stilt state must not be null");
        Objects.requireNonNull(surfaceHeight, "Structure stilt audit requires an Iris terrain height resolver");
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int baseColumns = 0;
        int stiltBlocks = 0;
        int stiltColumns = 0;
        int unsupportedColumns = 0;
        for (FoundationColumn column : foundationEnvelope(area, start)) {
            int foundationY = findFoundationY(world, column, position);
            if (foundationY == Integer.MIN_VALUE) {
                continue;
            }
            baseColumns++;
            int terrainY = Math.max(area.minY(), Math.min(
                    area.maxY(), surfaceHeight.applyAsInt(column.x(), column.z())));
            boolean grounded = foundationY <= terrainY + 1;
            boolean stiltColumn = false;
            for (int y = foundationY - 1; y >= area.minY(); y--) {
                if (y <= terrainY) {
                    grounded = true;
                    break;
                }
                BlockState state = world.getBlockState(position.set(column.x(), y, column.z()));
                if (state.is(expectedStilt.getBlock())) {
                    stiltBlocks++;
                    stiltColumn = true;
                    if (y == area.minY()) {
                        grounded = true;
                    }
                    continue;
                }
                boolean vegetation = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
                grounded = state.isSolid() && !vegetation;
                break;
            }
            if (stiltColumn) {
                stiltColumns++;
            }
            if (!grounded) {
                unsupportedColumns++;
            }
        }
        return new StiltSupportAudit(baseColumns, stiltBlocks, stiltColumns, unsupportedColumns);
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
    public interface PaletteBlockResolver {
        BlockState resolve(IrisMaterialPalette palette, RNG rng, int x, int y, int z);
    }

    private record FoundationColumn(int x, int z, int[] ys) {
    }

    // StructureStart has no value equality, so the key pins the exact start instance a chunk carves against.
    private record CarveFootprintKey(StructureStart start, int padding) {
    }

    record OrganicCarve(StructureCarvingFootprint footprint, IrisStructureCarveShape shape,
                        int horizontalPadding, int ceilingPadding, int floorPadding,
                        double strength, double frequency, CNG blob, CNG ceilingRoll, CNG floorRoll,
                        CNG lobe, double lobeFrequency, double lobeStrength) {
    }

    public record VegetationTarget(StructureStart start, boolean force) {
    }

    public record TerrainTarget(String structureId, StructureStart start,
                                IrisStructureTerrain terrain) {
    }

    public record StiltSupportAudit(int baseColumns, int stiltBlocks, int stiltColumns,
                                    int unsupportedColumns) {
    }

    private record VegetationSnapshot(BitSet[] columns, int[] lowestY, int treeBlockCount) {
    }

    record SurfaceAnchor(int minX, int maxX, int minZ, int maxZ, int meetY, int strength) {
    }

    private record SurfaceMaterials(BlockState surface, BlockState subsurface) {
    }

    private static final class MonumentChildPiecesAccess {
        private static final Field FIELD = resolveMonumentChildPiecesField();

        private MonumentChildPiecesAccess() {
        }
    }

    private static final class ScatteredHeightPositionAccess {
        private static final Field FIELD = resolveScatteredHeightPositionField();

        private ScatteredHeightPositionAccess() {
        }
    }

    private static final class SinglePoolTemplateAccess {
        private static final Field FIELD = resolveSinglePoolTemplateField();

        private SinglePoolTemplateAccess() {
        }
    }
}

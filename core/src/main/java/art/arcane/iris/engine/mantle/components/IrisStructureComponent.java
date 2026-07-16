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

package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.PlacedStructurePiece;
import art.arcane.iris.engine.framework.StructurePlacementMarker;
import art.arcane.iris.engine.mantle.ComponentFlag;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.mantle.IrisMantleComponent;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisMaterialPalette;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.IrisStructureStiltSettings;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.math.RNG;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@ComponentFlag(ReservedFlag.JIGSAW)
public class IrisStructureComponent extends IrisMantleComponent {
    private static final long MAX_BORE_VOLUME = 6_000_000L;
    private static final long MAX_OVERBORE_VOLUME = 48_000_000L;
    private static final double OVERBORE_MIN_BOUNDARY = 0.2;
    private static final double OVERBORE_MIN_BOUNDARY_SQUARED = OVERBORE_MIN_BOUNDARY * OVERBORE_MIN_BOUNDARY;
    private static final double OVERBORE_BOUNDARY_SPAN = 0.8;
    private static final double OVERBORE_MAX_UP_REACH = 1.8;
    private static final int DYNAMIC_STRUCTURE_Y_TOLERANCE = 32;
    private static final MatterCavern CARVE_CAVERN = new MatterCavern(true, "", (byte) 3);

    public IrisStructureComponent(EngineMantle engineMantle) {
        super(engineMantle, ReservedFlag.JIGSAW, 3);
    }

    @Override
    @ChunkCoordinates
    public void generateLayer(MantleWriter writer, int x, int z, ChunkContext context) {
        IrisComplex complex = context.getComplex();
        int xxx = 8 + (x << 4);
        int zzz = 8 + (z << 4);
        IrisRegion region = complex.getRegionStream().get(xxx, zzz);
        IrisBiome biome = complex.getTrueBiomeStream().get(xxx, zzz);
        KList<IrisStructurePlacement> placements = new KList<>();
        if (biome != null) {
            placements.addAll(biome.getStructures());
        }
        if (region != null) {
            placements.addAll(region.getStructures());
        }
        placements.addAll(getDimension().getStructures());

        for (IrisStructurePlacement placement : placements) {
            placeFromPlacement(writer, placement, x, z);
        }
    }

    @ChunkCoordinates
    private void placeFromPlacement(MantleWriter writer, IrisStructurePlacement placement, int cx, int cz) {
        IrisStructureLocator.ResolvedPlacement resolved = IrisStructureLocator.resolvePlacement(
                getEngineMantle().getEngine(), placement, cx, cz);
        if (resolved == null) {
            return;
        }

        boolean trace = IrisSettings.get().getGeneral().isDebug();
        if (trace) {
            IrisLogging.info("[StructTrace] ORIGIN chunk=" + cx + "," + cz + " structures=" + placement.getStructures()
                    + " underground=" + placement.isUnderground() + " band=" + placement.getMinHeight() + ".." + placement.getMaxHeight());
        }

        String key = resolved.structureKey();
        IrisStructure structure = resolved.structure();
        KList<PlacedStructurePiece> pieces = resolvedPiecesOrNull(resolved, cx, cz);
        if (pieces == null) {
            return;
        }
        RNG rng = resolved.rng();
        int baseY = resolved.baseY();
        if (trace) {
            IrisLogging.info("[StructTrace] ASSEMBLED chunk=" + cx + "," + cz + " key=" + key + " baseY=" + baseY + " pieces=" + pieces.size());
        }

        if (!placement.isUnderground()) {
            clearIntersectingObjectTrees(writer, resolved);
        }

        if (placement.isOverbore()) {
            overboreStructure(writer, pieces, placement.getOverboreRadius(), placement.getOverboreHeight(), placement.getOverboreFloor());
        } else if (placement.isBore()) {
            boreStructure(writer, pieces, placement.getBorePadding());
        }

        ObjectPlaceMode mode = structure.getPlaceMode();
        int failedPieces = 0;
        Long2IntOpenHashMap foundationColumns = placement.getStilt() == null
                ? null : new Long2IntOpenHashMap();
        if (placement.isUnderground()) {
            ObjectPlaceMode undergroundMode = (mode == ObjectPlaceMode.ORGANIC_STILT || mode == ObjectPlaceMode.CEILING_HANG)
                    ? mode : ObjectPlaceMode.STRUCTURE_PIECE;
            for (PlacedStructurePiece p : pieces) {
                if (placeObject(writer, structure, p, undergroundMode, p.getY(), rng, foundationColumns) == -1) {
                    failedPieces++;
                }
            }
        } else if (mode == ObjectPlaceMode.STRUCTURE_PIECE || mode == ObjectPlaceMode.FLOATING) {
            for (PlacedStructurePiece p : pieces) {
                if (placeObject(writer, structure, p, ObjectPlaceMode.STRUCTURE_PIECE, p.getY(), rng, foundationColumns) == -1) {
                    failedPieces++;
                }
            }
        } else if (pieces.size() == 1) {
            if (placeObject(writer, structure, pieces.getFirst(), mode, -1, rng, foundationColumns) == -1) {
                failedPieces++;
            }
        } else {
            for (PlacedStructurePiece p : pieces) {
                if (placeObject(writer, structure, p, ObjectPlaceMode.STRUCTURE_PIECE, p.getY(), rng, foundationColumns) == -1) {
                    failedPieces++;
                }
            }
        }
        requireAppliedPieces(resolved, cx, cz, failedPieces);
        if (failedPieces == 0 && placement.getStilt() != null) {
            placeFoundation(writer, foundationColumns, placement.getStilt(), rng);
        }
    }

    private void placeFoundation(MantleWriter writer, Long2IntOpenHashMap columns,
                                 IrisStructureStiltSettings settings, RNG rng) {
        IrisMaterialPalette palette = Objects.requireNonNull(
                settings.getPalette(), "Structure stilt palette must not be null");
        int mantleOffset = getEngineMantle().getEngine().getMinHeight();
        int maxDepth = Math.max(1, settings.getMaxDepth());
        for (Long2IntMap.Entry column : columns.long2IntEntrySet()) {
            int worldX = StructureFoundationPlanner.unpackX(column.getLongKey());
            int worldZ = StructureFoundationPlanner.unpackZ(column.getLongKey());
            int foundationY = column.getIntValue();
            PlatformBlockState foundationState = writer.getDataIfPresent(
                    worldX, foundationY, worldZ, PlatformBlockState.class);
            if (foundationState == null || !foundationState.isSolid()) {
                continue;
            }
            int terrainHeight = getEngineMantle().trueHeight(worldX, worldZ);
            int groundY = StructureFoundationPlanner.findGroundY(
                    foundationY, maxDepth, 0,
                    y -> StructureFoundationPlanner.isGroundSolid(
                            writer.getDataIfPresent(worldX, y, worldZ, PlatformBlockState.class),
                            writer.getDataIfPresent(worldX, y, worldZ, MatterCavern.class) != null,
                            y, terrainHeight));
            if (groundY == StructureFoundationPlanner.NO_GROUND) {
                continue;
            }
            StructureFoundationPlanner.fillSupportColumn(foundationY, groundY, y -> {
                writeFoundationSupport(
                        writer, palette, rng, getData(), worldX, y, worldZ, mantleOffset);
            });
        }
    }

    static void writeFoundationSupport(MantleWriter writer, IrisMaterialPalette palette, RNG rng,
                                       IrisData data, int worldX, int mantleY, int worldZ,
                                       int mantleOffset) {
        int worldY = mantleY + mantleOffset;
        PlatformBlockState support = palette.get(rng, worldX, worldY, worldZ, data);
        if (support == null) {
            throw new IllegalStateException("Structure stilt palette resolved no block at "
                    + worldX + "," + worldY + "," + worldZ);
        }
        writer.clearData(worldX, mantleY, worldZ, MatterCavern.class);
        writer.set(worldX, mantleY, worldZ, support);
    }

    static KList<PlacedStructurePiece> resolvedPiecesOrNull(
            IrisStructureLocator.ResolvedPlacement resolved,
            int chunkX,
            int chunkZ
    ) {
        if (resolved == null) {
            return null;
        }
        KList<PlacedStructurePiece> pieces = resolved.pieces();
        if (!IrisStructureLocator.requirePlacementOutput(
                resolved.placement(), resolved.structureKey(), chunkX, chunkZ,
                pieces != null && !pieces.isEmpty(), "placement application received no assembled pieces")) {
            return null;
        }
        return pieces;
    }

    static void requireAppliedPieces(IrisStructureLocator.ResolvedPlacement resolved,
                                     int chunkX, int chunkZ, int failedPieces) {
        IrisStructureLocator.requirePlacementOutput(
                resolved.placement(), resolved.structureKey(), chunkX, chunkZ, failedPieces == 0,
                "object placement rejected " + failedPieces + " of " + resolved.pieces().size()
                        + " assembled piece(s)");
    }

    private void boreStructure(MantleWriter writer, KList<PlacedStructurePiece> pieces, int padding) {
        int[] bounds = computePieceBounds(pieces);
        if (bounds == null) {
            return;
        }
        int pad = Math.max(0, padding);
        int minX = bounds[0] - pad;
        int minY = bounds[1];
        int minZ = bounds[2] - pad;
        int maxX = bounds[3] + pad;
        int maxY = bounds[4] + pad;
        int maxZ = bounds[5] + pad;
        int worldMin = getEngineMantle().getEngine().getMinHeight() + 1;
        int worldMax = getEngineMantle().getEngine().getMinHeight() + getEngineMantle().getEngine().getHeight() - 1;
        minY = Math.max(minY, worldMin);
        maxY = Math.min(maxY, worldMax);
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return;
        }
        long volume = (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
        if (volume > MAX_BORE_VOLUME) {
            IrisLogging.warn("Skipping structure bore of " + volume + " blocks (cap " + MAX_BORE_VOLUME + "); use a smaller structure or larger spacing.");
            return;
        }
        int mantleOffset = getEngineMantle().getEngine().getMinHeight();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    writer.setDataIfAbsent(bx, by - mantleOffset, bz, CARVE_CAVERN);
                }
            }
        }
    }

    private void overboreStructure(MantleWriter writer, KList<PlacedStructurePiece> pieces, int radius, int ceiling, int floorDepth) {
        int[] bounds = computePieceBounds(pieces);
        if (bounds == null) {
            return;
        }
        int margin = Math.max(1, radius);
        int head = Math.max(0, ceiling);
        int floorCut = Math.max(0, floorDepth);
        int mantleOffset = getEngineMantle().getEngine().getMinHeight();
        int worldMin = getEngineMantle().getEngine().getMinHeight() + 1;
        int worldMax = getEngineMantle().getEngine().getMinHeight() + getEngineMantle().getEngine().getHeight() - 1;

        double freq = 0.07;
        double rollFreq = 0.03;
        double reachSide = margin;
        double reachUp = head < 1 ? 1.0 : head;
        double reachDown = floorCut < 1 ? 1.0 : floorCut;
        double upReachMin = 0.4;
        double upReachSpan = 1.4;
        int sideExt = overboreSideExtension(margin);
        int upExt = overboreUpExtension(reachUp);

        long work = 0L;
        for (PlacedStructurePiece p : pieces) {
            long wx = (long) (p.getMaxX() - p.getMinX() + 1) + 2L * sideExt;
            long wz = (long) (p.getMaxZ() - p.getMinZ() + 1) + 2L * sideExt;
            long wy = (long) (p.getMaxY() - p.getMinY() + 1) + upExt + floorCut;
            work += wx * wy * wz;
        }
        if (work > MAX_OVERBORE_VOLUME) {
            IrisLogging.warn("Skipping structure overbore of " + work + " blocks (cap " + MAX_OVERBORE_VOLUME + "); reduce overboreRadius/overboreHeight or use larger spacing.");
            return;
        }

        RNG noiseRng = new RNG(seed() + Cache.key(bounds[0], bounds[2]));
        CNG blob = CNG.signature(noiseRng);
        CNG roll = CNG.signature(noiseRng.nextParallelRNG(0x2A17));

        if (IrisSettings.get().getGeneral().isDebug()) {
            IrisLogging.info("Overbore carving organic cavern: pieces=" + pieces.size() + " margin=" + margin + " head=" + head + " floorCut=" + floorCut + " work=" + work);
        }

        for (PlacedStructurePiece p : pieces) {
            int pMinX = p.getMinX();
            int pMinY = p.getMinY();
            int pMinZ = p.getMinZ();
            int pMaxX = p.getMaxX();
            int pMaxY = p.getMaxY();
            int pMaxZ = p.getMaxZ();
            int exMinX = pMinX - sideExt;
            int exMaxX = pMaxX + sideExt;
            int exMinZ = pMinZ - sideExt;
            int exMaxZ = pMaxZ + sideExt;
            int exMinY = Math.max(worldMin, pMinY - floorCut);
            int exMaxY = Math.min(worldMax, pMaxY + upExt);
            for (int bx = exMinX; bx <= exMaxX; bx++) {
                double dx = bx < pMinX ? pMinX - bx : bx > pMaxX ? bx - pMaxX : 0;
                double nx = dx / reachSide;
                for (int bz = exMinZ; bz <= exMaxZ; bz++) {
                    double dz = bz < pMinZ ? pMinZ - bz : bz > pMaxZ ? bz - pMaxZ : 0;
                    double nz = dz / reachSide;
                    double nxz = nx * nx + nz * nz;
                    if (nxz > 1.0) {
                        continue;
                    }
                    double w = roll.fitDouble(0.0, 1.0, bx * rollFreq, bz * rollFreq) * 0.7
                            + roll.fitDouble(0.0, 1.0, bx * rollFreq * 3.0, bz * rollFreq * 3.0) * 0.3;
                    double contrast = (w - 0.5) * 2.6 + 0.5;
                    if (contrast < 0.0) {
                        contrast = 0.0;
                    } else if (contrast > 1.0) {
                        contrast = 1.0;
                    }
                    double upReach = reachUp * (upReachMin + upReachSpan * contrast);
                    if (upReach < 1.0) {
                        upReach = 1.0;
                    }
                    for (int by = exMinY; by <= exMaxY; by++) {
                        double ny;
                        if (by > pMaxY) {
                            ny = (by - pMaxY) / upReach;
                        } else if (by < pMinY) {
                            ny = (pMinY - by) / reachDown;
                        } else {
                            ny = 0.0;
                        }
                        double distanceSquared = nxz + ny * ny;
                        if (distanceSquared > 1.0) {
                            continue;
                        }
                        if (distanceSquared > OVERBORE_MIN_BOUNDARY_SQUARED) {
                            double n = blob.fitDouble(0.0, 1.0, bx * freq, by * freq, bz * freq);
                            if (!shouldCarveOverboreCell(distanceSquared, n)) {
                                continue;
                            }
                        }
                        writer.carveDataIfAbsent(bx, by - mantleOffset, bz, CARVE_CAVERN);
                    }
                }
            }
        }
    }

    static int overboreSideExtension(int radius) {
        return Math.max(1, radius);
    }

    static int overboreUpExtension(double reachUp) {
        return (int) Math.ceil(Math.max(1.0, reachUp) * OVERBORE_MAX_UP_REACH);
    }

    static double overboreBoundaryLimit(double noise) {
        double clampedNoise = Math.max(0.0, Math.min(1.0, noise));
        return OVERBORE_MIN_BOUNDARY + OVERBORE_BOUNDARY_SPAN * clampedNoise;
    }

    static boolean shouldCarveOverboreCell(double distanceSquared, double noise) {
        if (distanceSquared <= 0.0) {
            return true;
        }
        if (distanceSquared > 1.0) {
            return false;
        }
        double limit = overboreBoundaryLimit(noise);
        return distanceSquared <= limit * limit;
    }

    private void clearIntersectingObjectTrees(MantleWriter writer, IrisStructureLocator.ResolvedPlacement resolved) {
        int mantleOffset = getEngineMantle().getEngine().getMinHeight();
        int worldHeight = getEngineMantle().getEngine().getHeight();
        int verticalTolerance = resolved.exactY() ? 0 : DYNAMIC_STRUCTURE_Y_TOLERANCE;
        for (PlacedStructurePiece piece : resolved.pieces()) {
            int minY = Math.max(0, piece.getMinY() - mantleOffset - verticalTolerance);
            int maxY = Math.min(worldHeight - 1, piece.getMaxY() - mantleOffset + verticalTolerance);
            if (minY > maxY) {
                continue;
            }
            for (int x = piece.getMinX(); x <= piece.getMaxX(); x++) {
                for (int z = piece.getMinZ(); z <= piece.getMaxZ(); z++) {
                    clearIntersectingObjectTreeColumn(writer, x, z, minY, maxY, worldHeight);
                }
            }
        }
    }

    private void clearIntersectingObjectTreeColumn(MantleWriter writer, int x, int z,
                                                   int minY, int maxY, int worldHeight) {
        Set<String> intersectingMarkers = null;
        for (int y = minY; y <= maxY; y++) {
            PlatformBlockState state = writer.getDataIfPresent(x, y, z, PlatformBlockState.class);
            if (state == null || !state.isTreeBlock()) {
                continue;
            }
            String marker = writer.getDataIfPresent(x, y, z, String.class);
            if (isOrdinaryObjectMarker(marker)) {
                if (intersectingMarkers == null) {
                    intersectingMarkers = new HashSet<>();
                }
                intersectingMarkers.add(marker);
            }
        }
        if (intersectingMarkers == null) {
            return;
        }
        for (int y = 0; y < worldHeight; y++) {
            String marker = writer.getDataIfPresent(x, y, z, String.class);
            if (!intersectingMarkers.contains(marker)) {
                continue;
            }
            PlatformBlockState state = writer.getDataIfPresent(x, y, z, PlatformBlockState.class);
            if (state == null || !state.isTreeBlock()) {
                continue;
            }
            writer.clearBlock(x, y, z);
            writer.clearData(x, y, z, String.class);
        }
    }

    static boolean isOrdinaryObjectMarker(String marker) {
        StructurePlacementMarker.Decoded decoded = StructurePlacementMarker.decode(marker);
        return decoded != null && !decoded.structureAware();
    }

    private int[] computePieceBounds(KList<PlacedStructurePiece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacedStructurePiece p : pieces) {
            minX = Math.min(minX, p.getMinX());
            minY = Math.min(minY, p.getMinY());
            minZ = Math.min(minZ, p.getMinZ());
            maxX = Math.max(maxX, p.getMaxX());
            maxY = Math.max(maxY, p.getMaxY());
            maxZ = Math.max(maxZ, p.getMaxZ());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private int placeObject(MantleWriter writer, IrisStructure structure, PlacedStructurePiece p,
                            ObjectPlaceMode mode, int y, RNG rng, Long2IntOpenHashMap foundationColumns) {
        IrisObject object = p.getObject();
        String objectKey = object.getLoadKey();
        IrisObjectPlacement config = structure.createLootPlacement(objectKey);
        config.setMode(mode);
        config.setRotation(p.getRotation());
        if (!structure.getEdit().isEmpty()) {
            config.setEdit(structure.getEdit());
        }
        if (mode != ObjectPlaceMode.STRUCTURE_PIECE && mode != ObjectPlaceMode.FLOATING) {
            config.setForcePlace(true);
        }
        int placeY = (y == -1) ? -1 : y - getEngineMantle().getEngine().getMinHeight();
        String marker = structurePlacementMarker(structure, p, objectKey);
        return object.place(p.getX(), placeY, p.getZ(), writer, config, rng, (position, state) -> {
            StructureFoundationPlanner.recordBaseCell(
                    foundationColumns, position.getX(), position.getY(), position.getZ(), state);
            if (marker != null && shouldWriteStructureMarker(state)) {
                writer.setData(position.getX(), position.getY(), position.getZ(), marker);
            }
        }, null, getData());
    }

    static boolean shouldWriteStructureMarker(PlatformBlockState state) {
        return state != null && state.isStorageChest();
    }

    static int structurePlacementId(String structureKey, String objectKey, int x, int y, int z) {
        int hash = 17;
        hash = 31 * hash + structureKey.hashCode();
        hash = 31 * hash + objectKey.hashCode();
        hash = 31 * hash + x;
        hash = 31 * hash + y;
        hash = 31 * hash + z;
        return hash & Integer.MAX_VALUE;
    }

    private static String structurePlacementMarker(IrisStructure structure, PlacedStructurePiece piece, String objectKey) {
        String structureKey = structure.getLoadKey();
        if (structureKey == null || structureKey.isBlank() || objectKey == null || objectKey.isBlank()) {
            return null;
        }
        int placementId = structurePlacementId(structureKey, objectKey, piece.getX(), piece.getY(), piece.getZ());
        return StructurePlacementMarker.encodeStructure(objectKey, placementId, structureKey);
    }

    @Override
    protected int computeRadius() {
        IrisDimension dimension = getDimension();
        int maxBlocks = 0;

        for (IrisRegion region : dimension.getAllRegions(this::getData)) {
            maxBlocks = Math.max(maxBlocks, maxBlocksFrom(region.getStructures()));
        }
        for (IrisBiome biome : dimension.getReachableBiomes(this::getData)) {
            maxBlocks = Math.max(maxBlocks, maxBlocksFrom(biome.getStructures()));
        }
        maxBlocks = Math.max(maxBlocks, maxBlocksFrom(dimension.getStructures()));

        return maxBlocks;
    }

    private int maxBlocksFrom(KList<IrisStructurePlacement> placements) {
        int max = 0;
        for (IrisStructurePlacement placement : placements) {
            int carvePadding = placement.isOverbore() ? Math.max(0, placement.getOverboreRadius())
                    : placement.isBore() ? Math.max(0, placement.getBorePadding()) : 0;
            for (String key : placement.getStructures()) {
                IrisStructure structure = getData().load(IrisStructure.class, key, false);
                if (structure != null) {
                    max = Math.max(max, Math.max(1, structure.getMaxSizeChunks()) * 16 + carvePadding);
                }
            }
        }
        return max;
    }
}

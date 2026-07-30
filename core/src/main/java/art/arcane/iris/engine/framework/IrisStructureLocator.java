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

package art.arcane.iris.engine.framework;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.structure.StructureGraphCatalog;
import art.arcane.iris.engine.framework.structure.StructureGraphCompilation;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisNativeStructure;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.IrisStructureCarveShape;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import art.arcane.iris.engine.object.NativeStructureSuppression;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.engine.object.StructureDistribution;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds where Iris assembly and native jigsaw placements generate. A structure key matches
 * an Iris structure load key, its {@code vanillaSource}, or a configured native registry key.
 *
 * A per-engine index of placed keys is cached so {@code /locate} and {@code /iris goto} skip
 * the grid scan entirely for keys that are not placed (keeping them as fast as vanilla).
 *
 * For RANDOM_SPREAD placements (the common, vanilla-style spaced grid) the locator jumps
 * straight to the single candidate chunk in each spacing-sized grid cell rather than testing
 * every chunk -- roughly a {@code spacing^2} reduction in placement checks, matching vanilla
 * locate performance. Density placements use a bounded per-chunk ring scan, while concentric
 * placements enumerate their finite configured starts directly.
 */
public final class IrisStructureLocator {
    private static final int DENSITY_CANDIDATE_BUDGET = 4_096;
    private static final int MAX_BURIAL_COLUMNS = 2_000_000;
    private static final int UNDERGROUND_SURFACE_CLEARANCE = 1;
    private static final Pattern NAMESPACED_RESOURCE_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private static final Cache<Engine, PlacementIndex> INDEX_CACHE = Caffeine.newBuilder().weakKeys().build();
    private static final PlacementIndex EMPTY_INDEX = new PlacementIndex(
            Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
            Collections.emptyList());
    private static final LocateResult NOT_FOUND_RESULT = new LocateResult(LocateStatus.NOT_FOUND, 0, 0, 0);
    private static final LocateResult SEARCH_LIMIT_RESULT =
            new LocateResult(LocateStatus.SEARCH_LIMIT_REACHED, 0, 0, 0);

    private IrisStructureLocator() {
    }

    /** Structure keys referenced by any Iris assembly or native jigsaw placement. */
    public static Set<String> placedKeys(Engine engine) {
        if (engine == null) {
            return Collections.emptySet();
        }
        return index(engine).loadKeys;
    }

    public static boolean isPlaced(Engine engine, String key) {
        if (engine == null || key == null || key.isEmpty()) {
            return false;
        }
        PlacementIndex placementIndex = index(engine);
        String normalizedKey = normalize(key);
        return placementIndex.normalizedLoadKeys.contains(normalizedKey)
                || placementIndex.vanillaAliases.contains(normalizedKey);
    }

    public static boolean suppressesVanilla(Engine engine, String vanillaKey) {
        if (engine == null || vanillaKey == null || vanillaKey.isEmpty()) {
            return false;
        }
        return index(engine).suppressedVanillaSources.contains(normalize(vanillaKey));
    }

    public static void invalidate(Engine engine) {
        if (engine != null) {
            INDEX_CACHE.invalidate(engine);
        }
    }

    public static boolean startsInChunk(Engine engine, String key, int cx, int cz) {
        if (!isPlaced(engine, key)) {
            return false;
        }
        return resolveInChunk(engine, key, cx, cz) != null;
    }

    public static LocateResult locate(Engine engine, String key, int fromBlockX, int fromBlockZ, int maxRadiusChunks) {
        if (!isPlaced(engine, key)) {
            return NOT_FOUND_RESULT;
        }
        int max = Math.max(1, Math.min(maxRadiusChunks, 2048));
        int pcx = fromBlockX >> 4;
        int pcz = fromBlockZ >> 4;
        long maxDistSq = (long) max * (long) max;
        LocatedCandidate best = null;
        PlacementCatalog catalog = collectPlacementCatalog(engine, key);
        if (catalog.randomSpread().isEmpty() && catalog.concentricRings().isEmpty() && !catalog.hasDensity()) {
            return NOT_FOUND_RESULT;
        }
        SeedManager seedManager = engine.getSeedManager();
        if (seedManager == null) {
            throw new IllegalStateException("Iris structure locate requires a bound seed manager for '" + key + "'");
        }
        long seed = seedManager.getMantle();

        for (RandomSpreadParameters parameters : catalog.randomSpread) {
            int spacing = Math.max(1, parameters.spacing());
            int centerCellX = Math.floorDiv(pcx, spacing);
            int centerCellZ = Math.floorDiv(pcz, spacing);
            int cellRadius = (max / spacing) + 2;

            for (int r = 0; r <= cellRadius; r++) {
                long lowerBound = cellRingDistanceLowerBound(r, spacing, pcx, pcz, centerCellX, centerCellZ);
                if (best != null && lowerBound * lowerBound >= best.distanceSquared()) {
                    break;
                }
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        int[] candidate = StructurePlacementGrid.randomSpreadCellChunk(
                                centerCellX + dx, centerCellZ + dz, spacing, parameters.separation(), parameters.salt(), seed);
                        int cx = candidate[0];
                        int cz = candidate[1];
                        long distSq = distanceSquared(cx, cz, pcx, pcz);
                        if (distSq > maxDistSq || best != null && distSq >= best.distanceSquared()) {
                            continue;
                        }
                        ResolvedStart resolved = resolveInChunk(engine, key, cx, cz);
                        if (resolved != null) {
                            best = new LocatedCandidate(resolved, distSq);
                        }
                    }
                }
            }
        }

        Set<Long> checkedRingChunks = new LinkedHashSet<>();
        for (IrisStructurePlacement placement : catalog.concentricRings) {
            int count = Math.max(1, placement.getRingCount());
            for (int placementIndex = 0; placementIndex < count; placementIndex++) {
                int[] candidate = StructurePlacementGrid.concentricRingChunk(placement, placementIndex, seed);
                if (candidate == null || !checkedRingChunks.add(chunkKey(candidate[0], candidate[1]))) {
                    continue;
                }
                long distSq = distanceSquared(candidate[0], candidate[1], pcx, pcz);
                if (distSq > maxDistSq || best != null && distSq >= best.distanceSquared()) {
                    continue;
                }
                ResolvedStart resolved = resolveInChunk(engine, key, candidate[0], candidate[1]);
                if (resolved != null) {
                    best = new LocatedCandidate(resolved, distSq);
                }
            }
        }

        if (catalog.hasDensity) {
            int checkedDensityCandidates = 0;
            for (int r = 0; r <= max; r++) {
                if (best != null && (long) r * r > best.distanceSquared()) {
                    break;
                }
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        int cx = pcx + dx;
                        int cz = pcz + dz;
                        long distSq = distanceSquared(cx, cz, pcx, pcz);
                        if (distSq > maxDistSq || best != null && distSq >= best.distanceSquared()) {
                            continue;
                        }
                        if (checkedDensityCandidates >= DENSITY_CANDIDATE_BUDGET) {
                            return SEARCH_LIMIT_RESULT;
                        }
                        checkedDensityCandidates++;
                        ResolvedStart resolved = resolveInChunk(engine, key, cx, cz);
                        if (resolved != null) {
                            best = new LocatedCandidate(resolved, distSq);
                        }
                    }
                }
            }
        }

        if (best == null) {
            return NOT_FOUND_RESULT;
        }
        ResolvedStart resolved = best.resolved();
        return new LocateResult(LocateStatus.FOUND, resolved.originX(), resolved.baseY(), resolved.originZ());
    }

    public static ResolvedPlacement resolvePlacement(Engine engine, IrisStructurePlacement placement, int cx, int cz) {
        if (engine == null || engine.getData() == null || engine.getSeedManager() == null) {
            throw new IllegalStateException("Iris structure placement requires a fully bound engine");
        }
        NativeStructurePlacementPlanner.validateBackend(placement);
        if (!placement.hasIrisStructures() || placement.getDistribution() == null) {
            throw new IllegalStateException("Iris structure placement is missing its distribution or structure list");
        }
        long seed = engine.getSeedManager().getMantle();
        if (!StructurePlacementGrid.startsInChunk(placement, cx, cz, seed)) {
            return null;
        }

        RNG rng = StructurePlacementGrid.placementRng(placement, cx, cz, seed);
        int originX = (cx << 4) + rng.nextInt(16);
        int originZ = (cz << 4) + rng.nextInt(16);
        Integer baseY = resolveBaseY(engine, placement, originX, originZ, rng);
        if (baseY == null) {
            return null;
        }

        String selectedKey = selectStructureKey(placement, rng);
        if (selectedKey == null || selectedKey.isBlank()) {
            throw new IllegalStateException("Iris structure placement selected a blank structure key");
        }
        IrisStructure structure = engine.getData().load(IrisStructure.class, selectedKey, false);
        if (structure == null) {
            throw new IllegalStateException("Iris structure placement references missing structure '"
                    + selectedKey + "'");
        }
        StructureGraphCompilation compilation = StructureGraphCatalog.compile(engine.getData(), structure);
        if (!compilation.isAssemblyViable()) {
            throw new IllegalStateException("Iris structure '" + selectedKey
                    + "' has no runtime-viable assembly graph");
        }

        StructureAssembler assembler = StructureAssembler.forData(
                engine.getData(), structure, new IrisPosition(originX, baseY, originZ));
        KList<PlacedStructurePiece> pieces = assembler.assemble(rng);
        if (!requirePlacementOutput(placement, selectedKey, cx, cz,
                pieces != null && !pieces.isEmpty(), "runtime assembly produced no pieces")) {
            return null;
        }
        pieces = alignSurfacePieces(pieces, placement, structure, baseY);
        if (!requirePlacementOutput(placement, selectedKey, cx, cz,
                pieces != null && !pieces.isEmpty(), "surface alignment produced no pieces")) {
            return null;
        }
        boolean exactY = hasExactY(placement, structure, pieces);

        int worldMin = engine.getMinHeight() + 1;
        int worldMax = engine.getMinHeight() + engine.getHeight() - 1;
        if (exactY) {
            Integer verticalShift = resolveVerticalShift(pieces, placement, baseY, worldMin, worldMax);
            if (!requirePlacementOutput(placement, selectedKey, cx, cz, verticalShift != null,
                    "assembled pieces cannot fit the configured vertical and world bounds")) {
                return null;
            }
            if (verticalShift != 0) {
                pieces = shiftPieces(pieces, verticalShift);
                baseY += verticalShift;
            }
        }
        if (placement.isUnderground()) {
            Integer burialShift = resolveUndergroundBurialShift(
                    engine, pieces, placement, baseY, worldMin, worldMax);
            if (!requirePlacementOutput(placement, selectedKey, cx, cz, burialShift != null,
                    "assembled pieces and carving envelope cannot remain fully buried beneath terrain")) {
                return null;
            }
            if (burialShift != 0) {
                pieces = shiftPieces(pieces, burialShift);
                baseY += burialShift;
            }
        }

        long configuredRadius = (long) Math.max(1, structure.getMaxSizeChunks()) * 16L;
        int structureRadius = (int) Math.min(Integer.MAX_VALUE, configuredRadius);
        boolean withinBounds = fitsHorizontalBounds(pieces, originX, originZ, structureRadius)
                && (!exactY || fitsVerticalBounds(pieces, worldMin, worldMax));
        if (!requirePlacementOutput(placement, selectedKey, cx, cz, withinBounds,
                "assembled pieces exceed the configured structure or world bounds")) {
            return null;
        }
        return new ResolvedPlacement(placement, selectedKey, structure, pieces, rng, originX, baseY, originZ, exactY);
    }

    public static boolean requirePlacementOutput(IrisStructurePlacement placement, String structureKey,
                                                 int chunkX, int chunkZ, boolean outputPresent,
                                                 String failureReason) {
        if (outputPresent) {
            return true;
        }
        if (placement == null
                || NativeStructureSuppression.REPLACE_SOURCE != placement.getNativeSuppression()) {
            return false;
        }
        String key = structureKey == null || structureKey.isBlank()
                ? String.valueOf(placement.getStructures()) : structureKey;
        String reason = failureReason == null || failureReason.isBlank()
                ? "placement produced no output" : failureReason;
        throw new IllegalStateException("REPLACE_SOURCE placement for Iris structure '" + key
                + "' failed in chunk " + chunkX + "," + chunkZ + ": " + reason
                + ". Native generation is suppressed and will not be used as a fallback");
    }

    static boolean fitsWorldBounds(KList<PlacedStructurePiece> pieces, int worldMin, int worldMax,
                                  int originX, int originZ, int structureRadius) {
        int[] bounds = computeBounds(pieces);
        if (bounds == null) {
            return false;
        }
        return fitsVerticalBounds(bounds, worldMin, worldMax)
                && fitsHorizontalBounds(bounds, originX, originZ, structureRadius);
    }

    private static ResolvedStart resolveInChunk(Engine engine, String key, int cx, int cz) {
        IrisData data = engine.getData();
        KList<IrisStructurePlacement> placements = StructurePlacementScope.placementsAt(engine, cx, cz);
        for (IrisStructurePlacement placement : placements) {
            if (!matches(placement, key, data)) {
                continue;
            }
            if (placement.hasNativeStructures()) {
                NativeStructureStartPlan plan = NativeStructurePlacementPlanner.planAt(
                        engine, placement, cx, cz);
                if (plan != null && normalize(plan.source().getStructure()).equals(normalize(key))) {
                    return new ResolvedStart(
                            cx << 4, plan.baseY(), cz << 4);
                }
                continue;
            }
            ResolvedPlacement resolved = resolvePlacement(engine, placement, cx, cz);
            if (resolved != null && matchesResolved(resolved, key)) {
                return new ResolvedStart(
                        resolved.originX(), resolved.baseY(), resolved.originZ());
            }
        }
        return null;
    }

    private static Integer resolveBaseY(Engine engine, IrisStructurePlacement placement, int originX, int originZ, RNG rng) {
        int worldMin = engine.getMinHeight() + 1;
        int worldMax = engine.getMinHeight() + engine.getHeight() - 1;
        if (worldMin > worldMax) {
            return null;
        }
        if (!placement.isUnderground()) {
            int surfaceY = engine.getHeight(originX, originZ, true) + engine.getMinHeight();
            return surfaceY < placement.getMinHeight() || surfaceY > placement.getMaxHeight() ? null : surfaceY;
        }
        int bandMin = Math.max(worldMin, Math.min(placement.getMinHeight(), placement.getMaxHeight()));
        int bandMax = Math.min(worldMax, Math.max(placement.getMinHeight(), placement.getMaxHeight()));
        if (bandMin > bandMax) {
            return null;
        }
        return randomInclusive(rng, bandMin, bandMax);
    }

    static Integer resolveVerticalShift(KList<PlacedStructurePiece> pieces, IrisStructurePlacement placement,
                                        int baseY, int worldMin, int worldMax) {
        int[] bounds = computeBounds(pieces);
        if (bounds == null) {
            return null;
        }
        if (!placement.isUnderground()) {
            return bounds[1] >= worldMin && bounds[4] <= worldMax ? 0 : null;
        }
        int bandMin = Math.max(worldMin, Math.min(placement.getMinHeight(), placement.getMaxHeight()));
        int bandMax = Math.min(worldMax, Math.max(placement.getMinHeight(), placement.getMaxHeight()));
        int minimumShift = Math.max(worldMin - bounds[1], bandMin - baseY);
        int maximumShift = Math.min(worldMax - bounds[4], bandMax - baseY);
        if (minimumShift > maximumShift) {
            return null;
        }
        if (minimumShift > 0) {
            return minimumShift;
        }
        if (maximumShift < 0) {
            return maximumShift;
        }
        return 0;
    }

    static Integer resolveUndergroundBurialShift(Engine engine, KList<PlacedStructurePiece> pieces,
                                                  IrisStructurePlacement placement, int baseY,
                                                  int worldMin, int worldMax) {
        int[] bounds = computeBounds(pieces);
        if (engine == null || bounds == null || placement == null || !placement.isUnderground()) {
            return null;
        }

        IrisStructureTerrain terrain = placement.resolvedTerrain();
        IrisStructureTerrainMode terrainMode = terrain.resolvedMode();
        boolean forceCarve = terrainMode == IrisStructureTerrainMode.FORCE_CARVE;
        boolean bore = terrainMode == IrisStructureTerrainMode.BORE;
        int sideExtension = forceCarve || bore ? Math.max(0, terrain.getHorizontalPadding()) : 0;
        IrisStructureCarveShape carveShape = terrain.resolvedShape();
        int topExtension = forceCarve
                ? carveShape.maximumCeilingExtension(
                        terrain.getCeilingPadding(), terrain.resolvedErosionStrength())
                : bore ? Math.max(0, terrain.getCeilingPadding()) : 0;
        int bottomExtension = forceCarve || bore ? Math.max(0, terrain.getFloorPadding()) : 0;
        int bandMin = Math.max(worldMin, Math.min(placement.getMinHeight(), placement.getMaxHeight()));
        int bandMax = Math.min(worldMax, Math.max(placement.getMinHeight(), placement.getMaxHeight()));
        int minimumShift = Math.max(worldMin - (bounds[1] - bottomExtension), bandMin - baseY);
        int maximumShift = Math.min(0, Math.min(worldMax - (bounds[4] + topExtension), bandMax - baseY));
        if (minimumShift > maximumShift) {
            return null;
        }

        Long2IntOpenHashMap surfaceHeights = new Long2IntOpenHashMap();
        surfaceHeights.defaultReturnValue(Integer.MIN_VALUE);
        if (bore || forceCarve && carveShape == IrisStructureCarveShape.BOX) {
            maximumShift = resolveBurialEnvelopeShift(
                    engine,
                    bounds[0] - sideExtension,
                    bounds[3] + sideExtension,
                    bounds[2] - sideExtension,
                    bounds[5] + sideExtension,
                    bounds[4] + topExtension,
                    maximumShift,
                    surfaceHeights
            );
            if (maximumShift == Integer.MIN_VALUE) {
                return null;
            }
        } else {
            for (PlacedStructurePiece piece : pieces) {
                maximumShift = resolveBurialEnvelopeShift(
                        engine,
                        piece.getMinX() - sideExtension,
                        piece.getMaxX() + sideExtension,
                        piece.getMinZ() - sideExtension,
                        piece.getMaxZ() + sideExtension,
                        piece.getMaxY() + topExtension,
                        maximumShift,
                        surfaceHeights
                );
                if (maximumShift == Integer.MIN_VALUE) {
                    return null;
                }
            }
        }
        return minimumShift > maximumShift ? null : maximumShift;
    }

    private static int resolveBurialEnvelopeShift(Engine engine, int minX, int maxX, int minZ, int maxZ,
                                                   int envelopeTopY, int maximumShift,
                                                   Long2IntOpenHashMap surfaceHeights) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long columnKey = ((long) x << 32) ^ (z & 0xffffffffL);
                int surfaceY = surfaceHeights.get(columnKey);
                if (surfaceY == Integer.MIN_VALUE) {
                    if (surfaceHeights.size() >= MAX_BURIAL_COLUMNS) {
                        return Integer.MIN_VALUE;
                    }
                    surfaceY = engine.getHeight(x, z, true) + engine.getMinHeight();
                    surfaceHeights.put(columnKey, surfaceY);
                }
                int allowedTopY = surfaceY - UNDERGROUND_SURFACE_CLEARANCE;
                maximumShift = Math.min(maximumShift, allowedTopY - envelopeTopY);
            }
        }
        return maximumShift;
    }

    static String selectStructureKey(IrisStructurePlacement placement, RNG rng) {
        if (placement == null || placement.getStructures() == null || placement.getStructures().isEmpty()) {
            return null;
        }
        return placement.getStructures().get(rng.nextInt(placement.getStructures().size()));
    }

    private static KList<PlacedStructurePiece> alignSurfacePieces(KList<PlacedStructurePiece> pieces,
                                                                  IrisStructurePlacement placement,
                                                                  IrisStructure structure, int baseY) {
        if (placement.isUnderground() || pieces.size() <= 1
                || structure.getPlaceMode() == ObjectPlaceMode.STRUCTURE_PIECE
                || structure.getPlaceMode() == ObjectPlaceMode.FLOATING) {
            return pieces;
        }
        int[] bounds = computeBounds(pieces);
        return bounds == null ? pieces : shiftPieces(pieces, baseY - bounds[1]);
    }

    static boolean hasExactY(IrisStructurePlacement placement, IrisStructure structure,
                             KList<PlacedStructurePiece> pieces) {
        return placement.isUnderground() || pieces.size() > 1
                || structure.getPlaceMode() == ObjectPlaceMode.STRUCTURE_PIECE
                || structure.getPlaceMode() == ObjectPlaceMode.FLOATING;
    }

    private static boolean fitsVerticalBounds(KList<PlacedStructurePiece> pieces, int worldMin, int worldMax) {
        int[] bounds = computeBounds(pieces);
        return bounds != null && fitsVerticalBounds(bounds, worldMin, worldMax);
    }

    private static boolean fitsVerticalBounds(int[] bounds, int worldMin, int worldMax) {
        return bounds[1] >= worldMin && bounds[4] <= worldMax;
    }

    private static boolean fitsHorizontalBounds(KList<PlacedStructurePiece> pieces, int originX, int originZ,
                                                int structureRadius) {
        int[] bounds = computeBounds(pieces);
        return bounds != null && fitsHorizontalBounds(bounds, originX, originZ, structureRadius);
    }

    private static boolean fitsHorizontalBounds(int[] bounds, int originX, int originZ, int structureRadius) {
        return bounds[0] >= originX - structureRadius && bounds[3] <= originX + structureRadius
                && bounds[2] >= originZ - structureRadius && bounds[5] <= originZ + structureRadius;
    }

    private static KList<PlacedStructurePiece> shiftPieces(KList<PlacedStructurePiece> pieces, int shiftY) {
        KList<PlacedStructurePiece> shifted = new KList<>();
        for (PlacedStructurePiece piece : pieces) {
            shifted.add(new PlacedStructurePiece(
                    piece.getPiece(), piece.getObject(), piece.getX(), piece.getY() + shiftY, piece.getZ(), piece.getRotation(),
                    piece.getMinX(), piece.getMinY() + shiftY, piece.getMinZ(),
                    piece.getMaxX(), piece.getMaxY() + shiftY, piece.getMaxZ()));
        }
        return shifted;
    }

    private static int[] computeBounds(KList<PlacedStructurePiece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacedStructurePiece piece : pieces) {
            minX = Math.min(minX, piece.getMinX());
            minY = Math.min(minY, piece.getMinY());
            minZ = Math.min(minZ, piece.getMinZ());
            maxX = Math.max(maxX, piece.getMaxX());
            maxY = Math.max(maxY, piece.getMaxY());
            maxZ = Math.max(maxZ, piece.getMaxZ());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static int randomInclusive(RNG rng, int minimum, int maximum) {
        if (minimum == maximum) {
            return minimum;
        }
        return minimum + rng.nextInt((maximum - minimum) + 1);
    }

    private static PlacementCatalog collectPlacementCatalog(Engine engine, String key) {
        Set<RandomSpreadParameters> randomSpread = new LinkedHashSet<>();
        List<IrisStructurePlacement> concentricRings = new ArrayList<>();
        boolean hasDensity = false;
        for (IrisStructurePlacement placement : index(engine).placements) {
            if (!matches(placement, key, engine.getData())) {
                continue;
            }
            if (placement.getDistribution() == StructureDistribution.RANDOM_SPREAD) {
                randomSpread.add(new RandomSpreadParameters(
                        Math.max(1, placement.getSpacing()), placement.getSeparation(),
                        StructurePlacementGrid.placementSalt(placement)));
            } else if (placement.getDistribution() == StructureDistribution.CONCENTRIC_RINGS) {
                concentricRings.add(placement);
            } else if (isSearchableDensityPlacement(engine, placement)) {
                hasDensity = true;
            }
        }
        return new PlacementCatalog(List.copyOf(randomSpread), List.copyOf(concentricRings), hasDensity);
    }

    static boolean isSearchableDensityPlacement(Engine engine, IrisStructurePlacement placement) {
        if (engine == null || placement == null || placement.getDistribution() != StructureDistribution.DENSITY
                || !(placement.getDensity() > 0.0) || engine.getHeight() <= 0) {
            return false;
        }
        long worldMin = (long) engine.getMinHeight() + (placement.isUnderground() ? 1L : 0L);
        long worldMax = (long) engine.getMinHeight() + engine.getHeight() - 1L;
        long configuredMin = placement.isUnderground()
                ? Math.min(placement.getMinHeight(), placement.getMaxHeight())
                : placement.getMinHeight();
        long configuredMax = placement.isUnderground()
                ? Math.max(placement.getMinHeight(), placement.getMaxHeight())
                : placement.getMaxHeight();
        return configuredMin <= configuredMax && configuredMin <= worldMax && configuredMax >= worldMin;
    }

    private static long distanceSquared(int cx, int cz, int pcx, int pcz) {
        long dx = (long) cx - pcx;
        long dz = (long) cz - pcz;
        return dx * dx + dz * dz;
    }

    static long cellRingDistanceLowerBound(int ring, int spacing, int pcx, int pcz,
                                           int centerCellX, int centerCellZ) {
        if (ring <= 0) {
            return 0L;
        }
        int localX = pcx - (centerCellX * spacing);
        int localZ = pcz - (centerCellZ * spacing);
        long ringOffset = (long) ring * spacing;
        long positiveX = ringOffset - localX;
        long negativeX = ringOffset + localX - (spacing - 1L);
        long positiveZ = ringOffset - localZ;
        long negativeZ = ringOffset + localZ - (spacing - 1L);
        return Math.min(Math.min(positiveX, negativeX), Math.min(positiveZ, negativeZ));
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    private static boolean matches(IrisStructurePlacement placement, String key, IrisData data) {
        if (placement == null || key == null || data == null) {
            return false;
        }
        String normalizedKey = normalize(key);
        if (placement.getNativeStructures() != null) {
            for (IrisNativeStructure source : placement.getNativeStructures()) {
                if (source != null && normalize(source.getStructure()).equals(normalizedKey)) {
                    return true;
                }
            }
        }
        if (placement.getStructures() == null) {
            return false;
        }
        for (String structureKey : placement.getStructures()) {
            if (structureKey == null || structureKey.isBlank()) {
                continue;
            }
            IrisStructure structure = data.load(IrisStructure.class, structureKey, false);
            if (structure == null) {
                continue;
            }
            if (normalize(structureKey).equals(normalizedKey)
                    || normalize(structure.getLoadKey()).equals(normalizedKey)
                    || normalize(structure.getVanillaSource()).equals(normalizedKey)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesResolved(ResolvedPlacement resolved, String key) {
        String normalizedKey = normalize(key);
        return normalize(resolved.structureKey()).equals(normalizedKey)
                || normalize(resolved.structure().getLoadKey()).equals(normalizedKey)
                || normalize(resolved.structure().getVanillaSource()).equals(normalizedKey);
    }

    private static String normalize(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }

    private static PlacementIndex index(Engine engine) {
        if (engine == null) {
            return EMPTY_INDEX;
        }
        if (engine.getData() == null || engine.getDimension() == null) {
            throw new IllegalStateException("Iris structure index requires a fully bound engine and dimension");
        }
        return INDEX_CACHE.get(engine, ignored -> build(engine));
    }

    private static PlacementIndex build(Engine engine) {
        IrisData data = engine.getData();
        Set<String> loadKeys = new LinkedHashSet<>();
        Set<String> normalizedLoadKeys = new LinkedHashSet<>();
        Set<String> vanillaAliases = new LinkedHashSet<>();
        Set<String> suppressedVanillaSources = new LinkedHashSet<>();
        List<IrisStructurePlacement> placements = new ArrayList<>();
        collect(engine.getDimension().getStructures(), data, loadKeys, normalizedLoadKeys, vanillaAliases,
                suppressedVanillaSources, placements, true);
        for (IrisRegion region : engine.getDimension().getAllRegions(engine)) {
            collect(region.getStructures(), data, loadKeys, normalizedLoadKeys, vanillaAliases,
                    suppressedVanillaSources, placements, false);
        }
        for (IrisBiome biome : engine.getDimension().getReachableBiomes(engine)) {
            collect(biome.getStructures(), data, loadKeys, normalizedLoadKeys, vanillaAliases,
                    suppressedVanillaSources, placements, false);
        }
        return new PlacementIndex(
                Collections.unmodifiableSet(loadKeys),
                Collections.unmodifiableSet(normalizedLoadKeys),
                Collections.unmodifiableSet(vanillaAliases),
                Collections.unmodifiableSet(suppressedVanillaSources),
                List.copyOf(placements));
    }

    private static void collect(KList<IrisStructurePlacement> source, IrisData data, Set<String> loadKeys,
                                Set<String> normalizedLoadKeys, Set<String> vanillaAliases,
                                Set<String> suppressedVanillaSources,
                                List<IrisStructurePlacement> placements, boolean allowNativeSuppression) {
        if (source == null) {
            return;
        }
        for (IrisStructurePlacement placement : source) {
            if (placement == null) {
                throw new IllegalStateException("Iris structure placement list contains a null placement");
            }
            if (placement.getDistribution() == null) {
                throw new IllegalStateException("Iris structure placement is missing its distribution");
            }
            NativeStructurePlacementPlanner.validateBackend(placement);
            boolean replacesNative = NativeStructureSuppression.REPLACE_SOURCE == placement.getNativeSuppression();
            if (replacesNative && !allowNativeSuppression) {
                throw new IllegalStateException("REPLACE_SOURCE is only valid on dimension-level structure placements");
            }
            if (placement.hasNativeStructures()) {
                for (IrisNativeStructure nativeStructure : placement.getNativeStructures()) {
                    if (nativeStructure == null
                            || nativeStructure.getStructure() == null
                            || !NAMESPACED_RESOURCE_KEY.matcher(nativeStructure.getStructure()).matches()) {
                        throw new IllegalStateException("Native structure placement contains an invalid structure key");
                    }
                    String sourceKey = nativeStructure.getStructure();
                    loadKeys.add(sourceKey);
                    normalizedLoadKeys.add(normalize(sourceKey));
                    vanillaAliases.add(normalize(sourceKey));
                    if (replacesNative) {
                        suppressedVanillaSources.add(normalize(sourceKey));
                    }
                }
                placements.add(placement);
                continue;
            }
            for (String structureKey : placement.getStructures()) {
                if (structureKey == null || structureKey.isBlank()) {
                    throw new IllegalStateException(replacesNative
                            ? "REPLACE_SOURCE contains a blank Iris structure reference"
                            : "Iris structure placement contains a blank structure reference");
                }
                IrisStructure structure = data.load(IrisStructure.class, structureKey, false);
                if (structure == null) {
                    throw new IllegalStateException((replacesNative
                            ? "REPLACE_SOURCE references missing Iris structure '"
                            : "Iris structure placement references missing structure '")
                            + structureKey + "'");
                }
                loadKeys.add(structureKey);
                normalizedLoadKeys.add(normalize(structureKey));
                if (structure.getLoadKey() != null && !structure.getLoadKey().isBlank()) {
                    loadKeys.add(structure.getLoadKey());
                    normalizedLoadKeys.add(normalize(structure.getLoadKey()));
                }
                if (structure.getVanillaSource() != null && !structure.getVanillaSource().isBlank()) {
                    vanillaAliases.add(normalize(structure.getVanillaSource()));
                }
                if (replacesNative) {
                    String vanillaSource = structure.getVanillaSource();
                    if (vanillaSource == null || !NAMESPACED_RESOURCE_KEY.matcher(vanillaSource).matches()) {
                        throw new IllegalStateException("REPLACE_SOURCE structure '" + structureKey
                                + "' must declare a valid namespaced vanillaSource");
                    }
                    if (!StructureGraphCatalog.guaranteesRuntimeOutput(data, structure)) {
                        throw new IllegalStateException("REPLACE_SOURCE structure '" + structureKey
                                + "' is not runtime-viable; native generation will not be used as a fallback");
                    }
                    suppressedVanillaSources.add(normalize(vanillaSource));
                }
            }
            placements.add(placement);
        }
    }

    public record ResolvedPlacement(IrisStructurePlacement placement, String structureKey, IrisStructure structure,
                                    KList<PlacedStructurePiece> pieces, RNG rng,
                                    int originX, int baseY, int originZ, boolean exactY) {
    }

    public enum LocateStatus {
        FOUND,
        NOT_FOUND,
        SEARCH_LIMIT_REACHED
    }

    public record LocateResult(LocateStatus status, int originX, int baseY, int originZ) {
        public boolean found() {
            return status == LocateStatus.FOUND;
        }
    }

    private record RandomSpreadParameters(int spacing, int separation, int salt) {
    }

    private record PlacementCatalog(List<RandomSpreadParameters> randomSpread,
                                    List<IrisStructurePlacement> concentricRings, boolean hasDensity) {
    }

    private record ResolvedStart(int originX, int baseY, int originZ) {
    }

    private record LocatedCandidate(ResolvedStart resolved, long distanceSquared) {
    }

    private static final class PlacementIndex {
        private final Set<String> loadKeys;
        private final Set<String> normalizedLoadKeys;
        private final Set<String> vanillaAliases;
        private final Set<String> suppressedVanillaSources;
        private final List<IrisStructurePlacement> placements;

        private PlacementIndex(Set<String> loadKeys, Set<String> normalizedLoadKeys,
                               Set<String> vanillaAliases, Set<String> suppressedVanillaSources,
                               List<IrisStructurePlacement> placements) {
            this.loadKeys = loadKeys;
            this.normalizedLoadKeys = normalizedLoadKeys;
            this.vanillaAliases = vanillaAliases;
            this.suppressedVanillaSources = suppressedVanillaSources;
            this.placements = placements;
        }
    }
}

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
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.engine.object.StructureDistribution;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds where IRIS_PLACED structures generate. A structure key matches either the iris
 * structure's own load key or its {@code vanillaSource} (so a vanilla key like
 * {@code minecraft:ancient_city} resolves to the imported {@code minecraft_ancient_city}).
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

    private static final Cache<Engine, PlacementIndex> INDEX_CACHE = Caffeine.newBuilder().weakKeys().build();
    private static final PlacementIndex EMPTY_INDEX = new PlacementIndex(
            Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptyList());
    private static final LocateResult NOT_FOUND_RESULT = new LocateResult(LocateStatus.NOT_FOUND, 0, 0, 0);
    private static final LocateResult SEARCH_LIMIT_RESULT =
            new LocateResult(LocateStatus.SEARCH_LIMIT_REACHED, 0, 0, 0);

    private IrisStructureLocator() {
    }

    /** Iris structure load keys that are referenced by any IRIS_PLACED placement (for autocomplete). */
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
                || placementIndex.vanillaSources.contains(normalizedKey);
    }

    public static boolean suppressesVanilla(Engine engine, String vanillaKey) {
        if (engine == null || vanillaKey == null || vanillaKey.isEmpty()) {
            return false;
        }
        return index(engine).vanillaSources.contains(normalize(vanillaKey));
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
            return NOT_FOUND_RESULT;
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
                        ResolvedPlacement resolved = resolveInChunk(engine, key, cx, cz);
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
                ResolvedPlacement resolved = resolveInChunk(engine, key, candidate[0], candidate[1]);
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
                        ResolvedPlacement resolved = resolveInChunk(engine, key, cx, cz);
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
        ResolvedPlacement resolved = best.resolved();
        return new LocateResult(LocateStatus.FOUND, resolved.originX(), resolved.baseY(), resolved.originZ());
    }

    public static ResolvedPlacement resolvePlacement(Engine engine, IrisStructurePlacement placement, int cx, int cz, int placementOrdinal) {
        if (engine == null || placement == null || placement.getDistribution() == null
                || placement.getStructures() == null || placement.getStructures().isEmpty()
                || engine.getData() == null || engine.getSeedManager() == null) {
            return null;
        }
        long seed = engine.getSeedManager().getMantle();
        if (!StructurePlacementGrid.startsInChunk(placement, cx, cz, seed, placementOrdinal)) {
            return null;
        }

        RNG rng = StructurePlacementGrid.placementRng(placement, cx, cz, seed, placementOrdinal);
        int originX = (cx << 4) + rng.nextInt(16);
        int originZ = (cz << 4) + rng.nextInt(16);
        Integer baseY = resolveBaseY(engine, placement, originX, originZ, rng);
        if (baseY == null) {
            return null;
        }

        String selectedKey = selectStructureKey(placement, rng);
        if (selectedKey == null || selectedKey.isBlank()) {
            return null;
        }
        IrisStructure structure = IrisData.loadAnyStructure(selectedKey, engine.getData());
        if (structure == null) {
            return null;
        }

        StructureAssembler assembler = new StructureAssembler(engine.getData(), structure, originX, baseY, originZ);
        KList<PlacedStructurePiece> pieces = assembler.assemble(rng);
        if (pieces == null || pieces.isEmpty()) {
            return null;
        }
        pieces = alignSurfacePieces(pieces, placement, structure, baseY);
        boolean exactY = hasExactY(placement, structure, pieces);

        int worldMin = engine.getMinHeight() + 1;
        int worldMax = engine.getMinHeight() + engine.getHeight() - 1;
        if (exactY) {
            Integer verticalShift = resolveVerticalShift(pieces, placement, baseY, worldMin, worldMax);
            if (verticalShift == null) {
                return null;
            }
            if (verticalShift != 0) {
                pieces = shiftPieces(pieces, verticalShift);
                baseY += verticalShift;
            }
        }

        long configuredRadius = (long) Math.max(1, structure.getMaxSizeChunks()) * 16L;
        int structureRadius = (int) Math.min(Integer.MAX_VALUE, configuredRadius);
        if (!fitsHorizontalBounds(pieces, originX, originZ, structureRadius)
                || (exactY && !fitsVerticalBounds(pieces, worldMin, worldMax))) {
            return null;
        }
        return new ResolvedPlacement(placement, selectedKey, structure, pieces, rng, originX, baseY, originZ, exactY);
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

    private static ResolvedPlacement resolveInChunk(Engine engine, String key, int cx, int cz) {
        IrisData data = engine.getData();
        KList<IrisStructurePlacement> placements = placementsAt(engine, cx, cz);
        for (int placementOrdinal = 0; placementOrdinal < placements.size(); placementOrdinal++) {
            IrisStructurePlacement placement = placements.get(placementOrdinal);
            if (!matches(placement, key, data)) {
                continue;
            }
            ResolvedPlacement resolved = resolvePlacement(engine, placement, cx, cz, placementOrdinal);
            if (resolved != null && matchesResolved(resolved, key)) {
                return resolved;
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
                        Math.max(1, placement.getSpacing()), placement.getSeparation(), placement.getSalt()));
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

    private static KList<IrisStructurePlacement> placementsAt(Engine engine, int cx, int cz) {
        KList<IrisStructurePlacement> placements = new KList<>();
        if (engine.getDimension() == null || engine.getComplex() == null) {
            return placements;
        }
        int bx = 8 + (cx << 4);
        int bz = 8 + (cz << 4);
        IrisBiome biome = engine.getComplex().getTrueBiomeStream().get(bx, bz);
        IrisRegion region = engine.getComplex().getRegionStream().get(bx, bz);
        if (biome != null) {
            placements.addAll(biome.getStructures());
        }
        if (region != null) {
            placements.addAll(region.getStructures());
        }
        placements.addAll(engine.getDimension().getStructures());
        return placements;
    }

    private static boolean matches(IrisStructurePlacement placement, String key, IrisData data) {
        if (placement == null || placement.getStructures() == null || key == null || data == null) {
            return false;
        }
        String normalizedKey = normalize(key);
        for (String structureKey : placement.getStructures()) {
            if (structureKey == null || structureKey.isBlank()) {
                continue;
            }
            IrisStructure structure = IrisData.loadAnyStructure(structureKey, data);
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
        if (engine == null || engine.getData() == null || engine.getDimension() == null) {
            return EMPTY_INDEX;
        }
        return INDEX_CACHE.get(engine, ignored -> build(engine));
    }

    private static PlacementIndex build(Engine engine) {
        IrisData data = engine.getData();
        Set<String> loadKeys = new LinkedHashSet<>();
        Set<String> normalizedLoadKeys = new LinkedHashSet<>();
        Set<String> vanillaSources = new LinkedHashSet<>();
        List<IrisStructurePlacement> placements = new ArrayList<>();
        collect(engine.getDimension().getStructures(), data, loadKeys, normalizedLoadKeys, vanillaSources, placements);
        for (IrisRegion region : engine.getDimension().getAllRegions(engine)) {
            collect(region.getStructures(), data, loadKeys, normalizedLoadKeys, vanillaSources, placements);
        }
        for (IrisBiome biome : engine.getDimension().getReachableBiomes(engine)) {
            collect(biome.getStructures(), data, loadKeys, normalizedLoadKeys, vanillaSources, placements);
        }
        return new PlacementIndex(
                Collections.unmodifiableSet(loadKeys),
                Collections.unmodifiableSet(normalizedLoadKeys),
                Collections.unmodifiableSet(vanillaSources),
                List.copyOf(placements));
    }

    private static void collect(KList<IrisStructurePlacement> source, IrisData data, Set<String> loadKeys,
                                Set<String> normalizedLoadKeys, Set<String> vanillaSources,
                                List<IrisStructurePlacement> placements) {
        if (source == null) {
            return;
        }
        for (IrisStructurePlacement placement : source) {
            if (placement == null || placement.getStructures() == null) {
                continue;
            }
            boolean validPlacement = false;
            for (String structureKey : placement.getStructures()) {
                if (structureKey == null || structureKey.isBlank()) {
                    continue;
                }
                IrisStructure structure = IrisData.loadAnyStructure(structureKey, data);
                if (structure == null) {
                    continue;
                }
                validPlacement = true;
                loadKeys.add(structureKey);
                normalizedLoadKeys.add(normalize(structureKey));
                if (structure.getLoadKey() != null && !structure.getLoadKey().isBlank()) {
                    loadKeys.add(structure.getLoadKey());
                    normalizedLoadKeys.add(normalize(structure.getLoadKey()));
                }
                if (structure.getVanillaSource() != null && !structure.getVanillaSource().isBlank()) {
                    vanillaSources.add(normalize(structure.getVanillaSource()));
                }
            }
            if (validPlacement) {
                placements.add(placement);
            }
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

    private record LocatedCandidate(ResolvedPlacement resolved, long distanceSquared) {
    }

    private static final class PlacementIndex {
        private final Set<String> loadKeys;
        private final Set<String> normalizedLoadKeys;
        private final Set<String> vanillaSources;
        private final List<IrisStructurePlacement> placements;

        private PlacementIndex(Set<String> loadKeys, Set<String> normalizedLoadKeys,
                               Set<String> vanillaSources, List<IrisStructurePlacement> placements) {
            this.loadKeys = loadKeys;
            this.normalizedLoadKeys = normalizedLoadKeys;
            this.vanillaSources = vanillaSources;
            this.placements = placements;
        }
    }
}

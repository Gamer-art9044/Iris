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


import art.arcane.iris.util.common.data.B;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.mantle.ComponentFlag;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.mantle.IrisMantleComponent;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.modifier.IrisFloatingChildBiomeModifier;
import art.arcane.iris.engine.object.FloatingIslandSample;
import art.arcane.iris.engine.object.FloatingObjectFootprint;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisFloatingChildBiomes;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisObjectRotation;
import art.arcane.iris.engine.object.IrisObjectTranslate;
import art.arcane.iris.engine.object.IrisProceduralBlocks;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.math.IrisBlockVector;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.context.ChunkedDataCache;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.spi.PlatformBlockState;

import java.io.File;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

@ComponentFlag(ReservedFlag.FLOATING_OBJECT)
public class MantleFloatingObjectComponent extends IrisMantleComponent {
    private static final int MIN_FOOTPRINT_CELLS_CHECKED = 3;
    private static final int INVERTED_PICK_ATTEMPTS = 8;
    private static final IrisObjectRotation ROTATION_NONE = IrisObjectRotation.of(0, 0, 0);

    public MantleFloatingObjectComponent(EngineMantle engineMantle) {
        super(engineMantle, ReservedFlag.FLOATING_OBJECT, 2);
    }

    @Override
    public void generateLayer(MantleWriter writer, int x, int z, ChunkContext context) {
        IrisComplex complex = context.getComplex();
        IrisData data = getData();
        int chunkHeight = getEngineMantle().getEngine().getHeight();
        int minX = x << 4;
        int minZ = z << 4;
        long baseSeed = getEngineMantle().getEngine().getSeedManager().getTerrain() ^ IrisFloatingChildBiomeModifier.FLOATING_BASE_SEED_SALT;
        RNG chunkRng = new RNG(Cache.key(x, z) + seed() + 0x0FA710BEL);
        ChunkedDataCache<IrisBiome> biomeCache = context.getBiome();

        FloatingIslandSample.clearChunkMemo();

        FloatingIslandSample[] samples = new FloatingIslandSample[256];
        for (int xf = 0; xf < 16; xf++) {
            for (int zf = 0; zf < 16; zf++) {
                int wx = minX + xf;
                int wz = minZ + zf;
                IrisBiome parent = biomeCache.get(xf, zf);
                if (parent == null || parent.getFloatingChildBiomes() == null || parent.getFloatingChildBiomes().isEmpty()) {
                    continue;
                }
                FloatingIslandSample sample = FloatingIslandSample.sampleMemoized(parent, wx, wz, chunkHeight, baseSeed, data, getEngineMantle().getEngine());
                if (sample != null) {
                    samples[(zf << 4) | xf] = sample;
                }
            }
        }

        IdentityHashMap<IrisFloatingChildBiomes, KList<Integer>> entryColumns = new IdentityHashMap<>();
        IdentityHashMap<IrisFloatingChildBiomes, KList<Integer>> bottomEntryColumns = new IdentityHashMap<>();
        for (int i = 0; i < 256; i++) {
            FloatingIslandSample s = samples[i];
            if (s == null || s.entry == null) {
                continue;
            }
            entryColumns.computeIfAbsent(s.entry, e -> new KList<>()).add(i);
            IrisFloatingChildBiomes bottomEntry = s.bottomEntry();
            if (bottomEntry != null) {
                bottomEntryColumns.computeIfAbsent(bottomEntry, e -> new KList<>()).add(i);
            }
        }

        for (Map.Entry<IrisFloatingChildBiomes, KList<Integer>> ec : entryColumns.entrySet()) {
            IrisFloatingChildBiomes entry = ec.getKey();
            KList<Integer> columns = ec.getValue();
            if (columns.isEmpty()) {
                continue;
            }

            IrisBiome parent = biomeCache.get(columns.get(0) & 15, columns.get(0) >> 4);
            IrisBiome target = entry.getRealBiome(parent, data);

            KList<IrisObjectPlacement> floating = entry.getFloatingObjects();
            if (floating != null && !floating.isEmpty()) {
                for (IrisObjectPlacement placement : floating) {
                    tryPlaceFloatingChunk(writer, complex, chunkRng, data, placement, samples, columns, minX, minZ, entry);
                }
            }

            KList<IrisObjectPlacement> surface = target != null ? entry.resolveTopObjects(target) : null;
            KList<IrisObjectPlacement> extras = entry.getExtraObjects();
            boolean hasSurface = surface != null && !surface.isEmpty();
            boolean hasExtras = extras != null && !extras.isEmpty();
            KList<Integer> interior = null;
            if (hasSurface || hasExtras) {
                interior = interiorColumns(samples, columns);
                if (hasSurface) {
                    for (IrisObjectPlacement placement : surface) {
                        tryPlaceAnchoredChunk(writer, complex, chunkRng, data, placement, samples, columns, interior, minX, minZ, entry);
                    }
                }
                if (hasExtras) {
                    for (IrisObjectPlacement placement : extras) {
                        tryPlaceAnchoredChunk(writer, complex, chunkRng, data, placement, samples, columns, interior, minX, minZ, entry);
                    }
                }
            }
        }

        for (Map.Entry<IrisFloatingChildBiomes, KList<Integer>> ec : bottomEntryColumns.entrySet()) {
            IrisFloatingChildBiomes entry = ec.getKey();
            KList<Integer> columns = ec.getValue();
            if (columns.isEmpty()) {
                continue;
            }

            IrisBiome parent = biomeCache.get(columns.get(0) & 15, columns.get(0) >> 4);
            IrisBiome target = entry.getRealBiome(parent, data);
            KList<IrisObjectPlacement> bottom = target != null ? entry.resolveBottomObjects(target) : null;
            if (bottom != null && !bottom.isEmpty()) {
                KList<Integer> interior = interiorColumns(samples, columns);
                for (IrisObjectPlacement placement : bottom) {
                    tryPlaceInvertedChunk(writer, complex, chunkRng, data, placement, samples, columns, interior, minX, minZ, entry);
                }
            }
        }
    }

    @ChunkCoordinates
    private void tryPlaceFloatingChunk(MantleWriter writer, IrisComplex complex, RNG rng, IrisData data, IrisObjectPlacement placement, FloatingIslandSample[] samples, KList<Integer> columns, int minX, int minZ, IrisFloatingChildBiomes entry) {
        if (placement == null || columns == null || columns.isEmpty()) {
            return;
        }
        int density = placement.getDensity(rng, minX, minZ, data);
        double perAttempt = placement.getChance();
        for (int i = 0; i < density; i++) {
            if (!rng.chance(perAttempt + rng.d(-0.005, 0.005))) {
                continue;
            }
            IrisObject raw = placement.getObject(complex, rng);
            if (raw == null) {
                continue;
            }
            IrisObject obj0 = placement.getScale().get(rng, raw);
            if (obj0 == null) {
                continue;
            }
            if (entry != null && entry.hasObjectShrink()) {
                obj0 = entry.getShrinkScale().get(rng, obj0);
                if (obj0 == null) {
                    continue;
                }
            }
            final IrisObject obj = obj0;

            int key = columns.get(rng.i(0, columns.size() - 1));
            int xx = minX + (key & 15);
            int zz = minZ + (key >> 4);
            IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());
            int id = rng.i(0, Integer.MAX_VALUE);

            try {
                obj.place(xx, -1, zz, writer, floatingPlacement, rng, (b, bd) -> {
                    String marker = placementMarker(obj, id);
                    if (marker != null && shouldWritePlacementMarker(writer, bd, b.getX(), b.getY(), b.getZ())) {
                        writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                    }
                }, null, data);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
    }

    @ChunkCoordinates
    private void tryPlaceAnchoredChunk(MantleWriter writer, IrisComplex complex, RNG rng, IrisData data, IrisObjectPlacement placement, FloatingIslandSample[] samples, KList<Integer> columns, KList<Integer> interior, int minX, int minZ, IrisFloatingChildBiomes entry) {
        if (placement == null || columns.isEmpty()) {
            return;
        }
        int density = placement.getDensity(rng, minX, minZ, data);
        double perAttempt = placement.getChance();

        for (int i = 0; i < density; i++) {
            if (!rng.chance(perAttempt + rng.d(-0.005, 0.005))) {
                continue;
            }

            IrisObject raw = placement.getObject(complex, rng);
            if (raw == null) {
                continue;
            }
            IrisObject obj0 = placement.getScale().get(rng, raw);
            if (obj0 == null) {
                continue;
            }
            if (entry != null && entry.hasObjectShrink()) {
                obj0 = entry.getShrinkScale().get(rng, obj0);
                if (obj0 == null) {
                    continue;
                }
            }
            final IrisObject obj = obj0;

            FloatingObjectFootprint fp = FloatingObjectFootprint.compute(obj);

            KList<Integer> pool = interior.isEmpty() ? columns : interior;

            int pickedKey = pool.get(rng.i(0, pool.size() - 1));
            int pickedXf = pickedKey & 15;
            int pickedZf = pickedKey >> 4;
            FloatingIslandSample pickedSample = samples[(pickedZf << 4) | pickedXf];
            if (pickedSample == null) {
                continue;
            }
            int pickTopY = pickedSample.topY();

            if (!isFootprintFlat(fp, pickedXf, pickedZf, pickTopY, samples, 2)) {
                if (!isFootprintFlat(fp, pickedXf, pickedZf, pickTopY, samples, 4)) {
                    continue;
                }
            }

            int wx = minX + pickedXf - fp.getTallestKx();
            int wz = minZ + pickedZf - fp.getTallestKz();

            IrisObjectPlacement anchored = placement.toPlacement(obj.getLoadKey());
            anchored.setMode(translateStiltModeForFloating(anchored.getMode()));
            anchored.setTranslate(new IrisObjectTranslate());
            anchored.setRotation(ROTATION_NONE);
            anchored.setForcePlace(true);
            anchored.setBottom(false);

            int yv = pickTopY + 1 - fp.getLowestSolidKeyY();

            IslandObjectPlacer islandPlacer = new IslandObjectPlacer(writer, samples, minX, minZ, pickTopY);
            int id = rng.i(0, Integer.MAX_VALUE);

            try {
                obj.place(wx, yv, wz, islandPlacer, anchored, rng, (b, bd) -> {
                    String marker = placementMarker(obj, id);
                    if (marker != null
                            && islandPlacer.canWriteObjectBlock(b.getX(), b.getY(), b.getZ())
                            && shouldWritePlacementMarker(islandPlacer, bd, b.getX(), b.getY(), b.getZ())) {
                        writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                    }
                }, null, data);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
    }

    @ChunkCoordinates
    private void tryPlaceInvertedChunk(MantleWriter writer, IrisComplex complex, RNG rng, IrisData data, IrisObjectPlacement placement, FloatingIslandSample[] samples, KList<Integer> columns, KList<Integer> interior, int minX, int minZ, IrisFloatingChildBiomes entry) {
        if (placement == null || columns.isEmpty()) {
            return;
        }
        int density = placement.getDensity(rng, minX, minZ, data);
        double perAttempt = placement.getChance();

        for (int i = 0; i < density; i++) {
            if (!rng.chance(perAttempt + rng.d(-0.005, 0.005))) {
                continue;
            }

            IrisObject raw = placement.getObject(complex, rng);
            if (raw == null) {
                continue;
            }
            IrisObject obj0 = placement.getScale().get(rng, raw);
            if (obj0 == null) {
                continue;
            }
            if (entry != null && entry.hasObjectShrink()) {
                obj0 = entry.getShrinkScale().get(rng, obj0);
                if (obj0 == null) {
                    continue;
                }
            }
            final IrisObject obj = obj0;

            FloatingObjectFootprint fp = FloatingObjectFootprint.compute(obj);
            int invertedYRotation = rng.i(0, 3) * 90;
            IrisObjectRotation invertedRotation = IrisObjectRotation.xFlip180WithY(invertedYRotation);

            KList<Integer> pool = interior.isEmpty() ? columns : interior;

            int pickedXf = -1;
            int pickedZf = -1;
            int pickBottomY = -1;
            boolean foundBottomAnchor = false;
            for (int attempt = 0; attempt < INVERTED_PICK_ATTEMPTS; attempt++) {
                int pickedKey = pool.get(rng.i(0, pool.size() - 1));
                int candidateXf = pickedKey & 15;
                int candidateZf = pickedKey >> 4;
                FloatingIslandSample candidateSample = samples[(candidateZf << 4) | candidateXf];
                if (candidateSample == null) {
                    continue;
                }
                int candidateBottomY = candidateSample.bottomY();
                if (candidateBottomY < 0) {
                    continue;
                }
                if (!isFootprintFlatBottom(fp, invertedRotation, candidateXf, candidateZf, candidateBottomY, samples, 2)
                        && !isFootprintFlatBottom(fp, invertedRotation, candidateXf, candidateZf, candidateBottomY, samples, 4)) {
                    continue;
                }
                pickedXf = candidateXf;
                pickedZf = candidateZf;
                pickBottomY = candidateBottomY;
                foundBottomAnchor = true;
                break;
            }
            if (!foundBottomAnchor) {
                continue;
            }

            int wx = invertedBaseX(minX, pickedXf, fp, invertedRotation);
            int wz = invertedBaseZ(minZ, pickedZf, fp, invertedRotation);

            IrisObjectPlacement inverted = placement.toPlacement(obj.getLoadKey());
            inverted.setMode(translateStiltModeForFloating(inverted.getMode()));
            inverted.setTranslate(new IrisObjectTranslate());
            inverted.setRotation(invertedRotation);
            inverted.setForcePlace(true);
            inverted.setBottom(false);

            int yv = invertedBaseY(pickBottomY, fp, invertedRotation);

            IslandObjectPlacer islandPlacer = new IslandObjectPlacer(writer, samples, minX, minZ, pickBottomY, IslandObjectPlacer.AnchorFace.BOTTOM);
            int id = rng.i(0, Integer.MAX_VALUE);

            try {
                obj.place(wx, yv, wz, islandPlacer, inverted, rng, (b, bd) -> {
                    String marker = placementMarker(obj, id);
                    if (marker != null
                            && islandPlacer.canWriteObjectBlock(b.getX(), b.getY(), b.getZ())
                            && shouldWritePlacementMarker(islandPlacer, bd, b.getX(), b.getY(), b.getZ())) {
                        writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                    }
                }, null, data);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
    }

    private static boolean isFootprintFlatBottom(FloatingObjectFootprint fp, IrisObjectRotation rotation, int pickedXf, int pickedZf, int pickBottomY, FloatingIslandSample[] samples, int tolerance) {
        IrisBlockVector anchor = invertedFootprintAnchor(fp, rotation);
        int checked = 0;
        boolean touchedChunkEdge = false;
        long[] cells = fp.footprintXZ();
        for (int i = 0, n = cells.length; i < n; i++) {
            long encoded = cells[i];
            int kx = (int) (encoded >> 32);
            int kz = (int) (encoded & 0xFFFFFFFFL);
            IrisBlockVector cell = rotation.rotate(new IrisBlockVector(kx, 0, kz), 0, 0, 0);
            int colXf = pickedXf + cell.getBlockX() - anchor.getBlockX();
            int colZf = pickedZf + cell.getBlockZ() - anchor.getBlockZ();
            if (colXf < 0 || colXf >= 16 || colZf < 0 || colZf >= 16) {
                touchedChunkEdge = true;
                continue;
            }
            FloatingIslandSample s = samples[(colZf << 4) | colXf];
            if (s == null) {
                return false;
            }
            int by = s.bottomY();
            if (by < 0 || Math.abs(by - pickBottomY) > tolerance) {
                return false;
            }
            checked++;
        }
        if (checked >= MIN_FOOTPRINT_CELLS_CHECKED) {
            return true;
        }
        return touchedChunkEdge;
    }

    static int invertedBaseX(int minX, int pickedXf, FloatingObjectFootprint fp) {
        return invertedBaseX(minX, pickedXf, fp, IrisObjectRotation.xFlip180());
    }

    static int invertedBaseY(int pickBottomY, FloatingObjectFootprint fp) {
        return invertedBaseY(pickBottomY, fp, IrisObjectRotation.xFlip180());
    }

    static int invertedBaseZ(int minZ, int pickedZf, FloatingObjectFootprint fp) {
        return invertedBaseZ(minZ, pickedZf, fp, IrisObjectRotation.xFlip180());
    }

    static int invertedBaseX(int minX, int pickedXf, FloatingObjectFootprint fp, IrisObjectRotation rotation) {
        return minX + pickedXf - invertedFootprintAnchor(fp, rotation).getBlockX();
    }

    static int invertedBaseY(int pickBottomY, FloatingObjectFootprint fp, IrisObjectRotation rotation) {
        return pickBottomY - 1 - invertedSolidAnchor(fp, rotation).getBlockY();
    }

    static int invertedBaseZ(int minZ, int pickedZf, FloatingObjectFootprint fp, IrisObjectRotation rotation) {
        return minZ + pickedZf - invertedFootprintAnchor(fp, rotation).getBlockZ();
    }

    private static IrisBlockVector invertedFootprintAnchor(FloatingObjectFootprint fp, IrisObjectRotation rotation) {
        return rotation.rotate(new IrisBlockVector(fp.getTallestKx(), 0, fp.getTallestKz()), 0, 0, 0);
    }

    private static IrisBlockVector invertedSolidAnchor(FloatingObjectFootprint fp, IrisObjectRotation rotation) {
        return rotation.rotate(new IrisBlockVector(fp.getTallestKx(), fp.getLowestSolidKeyY(), fp.getTallestKz()), 0, 0, 0);
    }

    private static boolean shouldWritePlacementMarker(IObjectPlacer placer, PlatformBlockState state, int x, int y, int z) {
        if (state == null) {
            return false;
        }
        PlatformBlockState existingState = placer.get(x, y, z);
        boolean wouldReplace = B.isSolid(existingState) && B.isVineBlock(state);
        String material = IrisProceduralBlocks.materialKey(state);
        boolean placesBlock = !material.equals("minecraft:air") && !material.equals("minecraft:cave_air") && !wouldReplace;
        return state.isCustom() || placesBlock;
    }

    private static boolean isFootprintFlat(FloatingObjectFootprint fp, int pickedXf, int pickedZf, int pickTopY, FloatingIslandSample[] samples, int tolerance) {
        int tallestKx = fp.getTallestKx();
        int tallestKz = fp.getTallestKz();
        int checked = 0;
        boolean touchedChunkEdge = false;
        long[] cells = fp.footprintXZ();
        for (int i = 0, n = cells.length; i < n; i++) {
            long encoded = cells[i];
            int kx = (int) (encoded >> 32);
            int kz = (int) (encoded & 0xFFFFFFFFL);
            int colXf = pickedXf + (kx - tallestKx);
            int colZf = pickedZf + (kz - tallestKz);
            if (colXf < 0 || colXf >= 16 || colZf < 0 || colZf >= 16) {
                touchedChunkEdge = true;
                continue;
            }
            FloatingIslandSample s = samples[(colZf << 4) | colXf];
            if (s == null || Math.abs(s.topY() - pickTopY) > tolerance) {
                return false;
            }
            checked++;
        }
        if (checked >= MIN_FOOTPRINT_CELLS_CHECKED) {
            return true;
        }
        return touchedChunkEdge;
    }

    private static KList<Integer> interiorColumns(FloatingIslandSample[] samples, KList<Integer> columns) {
        KList<Integer> interior = new KList<>();
        for (int key : columns) {
            int xf = key & 15;
            int zf = key >> 4;
            if (xf <= 0 || xf >= 15 || zf <= 0 || zf >= 15) {
                continue;
            }
            if (samples[(zf << 4) | (xf + 1)] == null) {
                continue;
            }
            if (samples[(zf << 4) | (xf - 1)] == null) {
                continue;
            }
            if (samples[((zf + 1) << 4) | xf] == null) {
                continue;
            }
            if (samples[((zf - 1) << 4) | xf] == null) {
                continue;
            }
            interior.add(key);
        }
        return interior;
    }

    private static String placementMarker(IrisObject object, int id) {
        if (object == null) {
            return null;
        }
        String key = object.getLoadKey();
        if (key == null || key.isEmpty() || key.equals("null")) {
            return null;
        }
        return key + "@" + id;
    }

    private static ObjectPlaceMode translateStiltModeForFloating(ObjectPlaceMode m) {
        return switch (m) {
            case STILT -> ObjectPlaceMode.MAX_HEIGHT;
            case FAST_STILT -> ObjectPlaceMode.FAST_MAX_HEIGHT;
            case MIN_STILT -> ObjectPlaceMode.MIN_HEIGHT;
            case FAST_MIN_STILT -> ObjectPlaceMode.FAST_MIN_HEIGHT;
            case CENTER_STILT -> ObjectPlaceMode.CENTER_HEIGHT;
            case ERODE_STILT -> ObjectPlaceMode.MAX_HEIGHT;
            case STRUCTURE_PIECE -> ObjectPlaceMode.CENTER_HEIGHT;
            default -> m;
        };
    }

    @Override
    protected int computeRadius() {
        int maxObjectExtent = 0;
        Set<String> objectKeys = new HashSet<>();
        try {
            IrisData data = getData();
            for (IrisBiome biome : getDimension().getReachableBiomes(this::getData)) {
                KList<IrisFloatingChildBiomes> entries = biome.getFloatingChildBiomes();
                if (entries == null || entries.isEmpty()) {
                    continue;
                }
                for (IrisFloatingChildBiomes entry : entries) {
                    collectPlacementKeys(entry.getFloatingObjects(), objectKeys);
                    collectPlacementKeys(entry.getExtraObjects(), objectKeys);
                    collectPlacementKeys(entry.getTopObjectOverrides(), objectKeys);
                    collectPlacementKeys(entry.getBottomObjectOverrides(), objectKeys);
                    try {
                        IrisBiome target = entry.getRealBiome(biome, data);
                        if (target != null) {
                            collectPlacementKeys(entry.resolveTopObjects(target), objectKeys);
                            collectPlacementKeys(entry.resolveBottomObjects(target), objectKeys);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            for (String key : objectKeys) {
                try {
                    File f = data.getObjectLoader().findFile(key);
                    if (f == null) {
                        continue;
                    }
                    IrisBlockVector sz = IrisObject.sampleSize(f);
                    int extent = Math.max(sz.getBlockX(), sz.getBlockZ());
                    if (extent > maxObjectExtent) {
                        maxObjectExtent = extent;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return Math.max(16, maxObjectExtent);
    }

    private static void collectPlacementKeys(KList<IrisObjectPlacement> placements, Set<String> out) {
        if (placements == null) {
            return;
        }
        for (IrisObjectPlacement p : placements) {
            if (p == null || p.getPlace() == null) {
                continue;
            }
            out.addAll(p.getPlace());
        }
    }
}

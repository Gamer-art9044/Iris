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
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.UpperDimensionContext;
import art.arcane.iris.engine.mantle.ComponentFlag;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.mantle.IrisMantleComponent;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.CarvingMode;
import art.arcane.iris.engine.object.DecayControlPlacer;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.IrisAxisRotationClamp;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisCaveAnchorMode;
import art.arcane.iris.engine.object.IrisCaveProfile;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisObjectRotation;
import art.arcane.iris.engine.object.IrisObjectScale;
import art.arcane.iris.engine.object.IrisObjectVacuum;
import art.arcane.iris.engine.object.IrisProceduralObjects;
import art.arcane.iris.engine.object.IrisProceduralPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.documentation.BlockCoordinates;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.MatterStructurePOI;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.iris.util.project.noise.NoiseType;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.math.IrisBlockVector;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ComponentFlag(ReservedFlag.OBJECT)
public class MantleObjectComponent extends IrisMantleComponent {
    private static final long CAVE_REJECT_LOG_THROTTLE_MS = 5000L;
    private static final int BEDROCK_CLEARANCE = 6;
    private static final Map<String, CaveRejectLogState> CAVE_REJECT_LOG_STATE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_LOAD_KEY_WARNED = ConcurrentHashMap.newKeySet();
    private static final int[] GOLDEN_DEBUG_TARGET = parseGoldenDebugTarget(resolveGoldenDebugSpec());
    private static final boolean GOLDEN_DEBUG = GOLDEN_DEBUG_TARGET != null;

    private static String resolveGoldenDebugSpec() {
        String property = System.getProperty("iris.goldendebug");
        if (property != null && !property.isBlank()) {
            return property;
        }
        try {
            java.io.File marker = new java.io.File("plugins/Iris/goldendebug.txt");
            if (marker.isFile()) {
                return java.nio.file.Files.readString(marker.toPath()).trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int[] parseGoldenDebugTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 2 && parts.length != 3) {
            return null;
        }
        try {
            int radius = parts.length == 3 ? Integer.parseInt(parts[2].trim()) : 0;
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), radius};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isGoldenDebugChunk(int x, int z) {
        return GOLDEN_DEBUG
                && Math.abs(GOLDEN_DEBUG_TARGET[0] - x) <= GOLDEN_DEBUG_TARGET[2]
                && Math.abs(GOLDEN_DEBUG_TARGET[1] - z) <= GOLDEN_DEBUG_TARGET[2];
    }

    public MantleObjectComponent(EngineMantle engineMantle) {
        super(engineMantle, ReservedFlag.OBJECT, 1);
    }

    private static String placementMarker(IrisObject object, int id, String context) {
        String key = object == null ? null : object.getLoadKey();
        if (key == null || key.isEmpty() || key.equals("null")) {
            String fingerprint = context + "|" + (object == null ? "<null>" : object.getClass().getSimpleName());
            if (MISSING_LOAD_KEY_WARNED.add(fingerprint)) {
                java.io.File file = object == null ? null : object.getLoadFile();
                IrisLogging.warn("Skipping placement marker write: IrisObject has no loadKey (context=" + context
                        + ", file=" + (file == null ? "<unknown>" : file.getPath()) + "). "
                        + "This would previously produce 'Couldn't find Object: null' warnings on chunk reload.");
            }
            return null;
        }
        return key + "@" + id;
    }

    @Override
    public void generateLayer(MantleWriter writer, int x, int z, ChunkContext context) {
        IrisComplex complex = context.getComplex();
        boolean traceRegen = isRegenTraceThread();
        RNG rng = applyNoise(x, z, Cache.key(x, z) + seed());
        int xxx = 8 + (x << 4);
        int zzz = 8 + (z << 4);
        IrisRegion region = complex.getRegionStream().get(xxx, zzz);
        IrisBiome surfaceBiome = complex.getTrueBiomeStream().get(xxx, zzz);
        int surfaceY = getEngineMantle().getEngine().getHeight(xxx, zzz, true);
        IrisBiome caveBiome = resolveCaveObjectBiome(xxx, zzz, surfaceY, surfaceBiome);
        if (IrisSettings.get().getGeneral().isDebug() && (x & 31) == 0 && (z & 31) == 0) {
            int carvedBlocks = 0;
            int minY = 1;
            int maxY = Math.min(getEngineMantle().getEngine().getHeight() - 1, surfaceY - 14);
            for (int sy = minY; sy < maxY; sy++) {
                if (writer.isCarved(8 + (x << 4), sy, 8 + (z << 4))) {
                    carvedBlocks++;
                }
            }
            IrisLogging.info("Cave object diag: chunk=" + x + "," + z
                    + " surfaceBiome=" + surfaceBiome.getLoadKey()
                    + " caveBiome=" + caveBiome.getLoadKey()
                    + " surfaceY=" + surfaceY
                    + " maxAnchorY=" + maxY
                    + " carvedAtCenter=" + carvedBlocks
                    + " biomeCarvingObjects=" + caveBiome.getCarvingObjects().size()
                    + " regionCarvingObjects=" + region.getCarvingObjects().size()
                    + " sameBiome=" + (caveBiome == surfaceBiome || java.util.Objects.equals(caveBiome.getLoadKey(), surfaceBiome.getLoadKey())));
        }
        if (traceRegen) {
            IrisLogging.info("Regen object layer start: chunk=" + x + "," + z
                    + " surfaceBiome=" + surfaceBiome.getLoadKey()
                    + " caveBiome=" + caveBiome.getLoadKey()
                    + " region=" + region.getLoadKey()
                    + " biomeSurfacePlacers=" + surfaceBiome.getSurfaceObjects().size()
                    + " biomeCavePlacers=" + caveBiome.getCarvingObjects().size()
                    + " regionSurfacePlacers=" + region.getSurfaceObjects().size()
                    + " regionCavePlacers=" + region.getCarvingObjects().size());
        }
        ObjectPlacementSummary summary = placeObjects(writer, rng, x, z, surfaceBiome, caveBiome, region, complex, traceRegen);
        placeProceduralObjects(writer, rng, x, z, surfaceBiome, caveBiome, region);
        UpperDimensionContext upperCtx = getEngineMantle().getEngine().getUpperContext();
        IrisDimension dimension = getDimension();
        if (upperCtx != null && dimension.isUpperDimensionObjects()) {
            placeUpperObjects(writer, rng, x, z, xxx, zzz, surfaceY, upperCtx, dimension, complex, traceRegen);
        }
        if (traceRegen) {
            IrisLogging.info("Regen object layer done: chunk=" + x + "," + z
                    + " biomeSurfacePlacersChecked=" + summary.biomeSurfacePlacersChecked()
                    + " biomeSurfacePlacersTriggered=" + summary.biomeSurfacePlacersTriggered()
                    + " biomeCavePlacersChecked=" + summary.biomeCavePlacersChecked()
                    + " biomeCavePlacersTriggered=" + summary.biomeCavePlacersTriggered()
                    + " regionSurfacePlacersChecked=" + summary.regionSurfacePlacersChecked()
                    + " regionSurfacePlacersTriggered=" + summary.regionSurfacePlacersTriggered()
                    + " regionCavePlacersChecked=" + summary.regionCavePlacersChecked()
                    + " regionCavePlacersTriggered=" + summary.regionCavePlacersTriggered()
                    + " objectAttempts=" + summary.objectAttempts()
                    + " objectPlaced=" + summary.objectPlaced()
                    + " objectRejected=" + summary.objectRejected()
                    + " objectNull=" + summary.objectNull()
                    + " objectErrors=" + summary.objectErrors());
        }
    }

    private RNG applyNoise(int x, int z, long seed) {
        CNG noise = CNG.signatureFast(new RNG(seed), NoiseType.WHITE, NoiseType.GLOB);
        return new RNG((long) (seed * noise.noise(x, z)));
    }

    private IrisBiome resolveCaveObjectBiome(int x, int z, int surfaceY, IrisBiome surfaceBiome) {
        int legacySampleY = Math.max(1, surfaceY - 48);
        IrisBiome legacyCaveBiome = getEngineMantle().getEngine().getCaveBiome(x, legacySampleY, z);
        if (legacyCaveBiome == null) {
            legacyCaveBiome = surfaceBiome;
        }

        int[] sampleDepths = new int[]{48, 80, 112};
        IrisBiome ladderChoice = null;
        for (int sampleDepth : sampleDepths) {
            int sampleY = Math.max(1, surfaceY - sampleDepth);
            IrisBiome sampled = getEngineMantle().getEngine().getCaveBiome(x, sampleY, z);
            boolean sameSurface = sampled == surfaceBiome;
            if (!sameSurface && sampled != null && surfaceBiome != null) {
                String sampledKey = sampled.getLoadKey();
                String surfaceKey = surfaceBiome.getLoadKey();
                sameSurface = sampledKey != null && sampledKey.equals(surfaceKey);
            }

            if (sampled == null || sameSurface) {
                continue;
            }

            if (!sampled.getCarvingObjects().isEmpty()) {
                ladderChoice = sampled;
            }
        }

        if (ladderChoice != null) {
            return ladderChoice;
        }

        return legacyCaveBiome;
    }

    @ChunkCoordinates
    private ObjectPlacementSummary placeObjects(MantleWriter writer, RNG rng, int x, int z, IrisBiome surfaceBiome, IrisBiome caveBiome, IrisRegion region, IrisComplex complex, boolean traceRegen) {
        int biomeSurfaceChecked = 0;
        int biomeSurfaceTriggered = 0;
        int biomeCaveChecked = 0;
        int biomeCaveTriggered = 0;
        int regionSurfaceChecked = 0;
        int regionSurfaceTriggered = 0;
        int regionCaveChecked = 0;
        int regionCaveTriggered = 0;
        int attempts = 0;
        int placed = 0;
        int rejected = 0;
        int nullObjects = 0;
        int errors = 0;
        IrisCaveProfile biomeCaveProfile = resolveCaveProfile(caveBiome.getCaveProfile(), region.getCaveProfile());
        IrisCaveProfile regionCaveProfile = resolveCaveProfile(region.getCaveProfile(), caveBiome.getCaveProfile());
        CaveAnchorCache caveAnchorCache = new CaveAnchorCache();

        for (IrisObjectPlacement i : surfaceBiome.getSurfaceObjects()) {
            biomeSurfaceChecked++;
            boolean chance = rng.chance(i.getChance() + rng.d(-0.005, 0.005));
            if (traceRegen) {
                IrisLogging.info("Regen object placer chance: chunk=" + x + "," + z
                        + " scope=biome-surface"
                        + " chanceResult=" + chance
                        + " chanceBase=" + i.getChance()
                        + " densityMid=" + i.getDensity()
                        + " objects=" + i.getPlace().size());
            }
            if (chance) {
                biomeSurfaceTriggered++;
                try {
                    ObjectPlacementResult result = placeObject(writer, rng, x << 4, z << 4, i, complex, traceRegen, x, z, "biome-surface");
                    attempts += result.attempts();
                    placed += result.placed();
                    rejected += result.rejected();
                    nullObjects += result.nullObjects();
                    errors += result.errors();
                } catch (Throwable e) {
                    errors++;
                    IrisLogging.reportError(e);
                    IrisLogging.error("Failed to place objects in the following biome: " + surfaceBiome.getName());
                    IrisLogging.error("Object(s) " + i.getPlace().toString(", ") + " (" + e.getClass().getSimpleName() + ").");
                    IrisLogging.error("Are these objects missing?");
                    e.printStackTrace();
                }
            }
        }

        for (IrisObjectPlacement i : caveBiome.getCarvingObjects()) {
            if (!i.getCarvingSupport().supportsCarving()) {
                continue;
            }
            biomeCaveChecked++;
            boolean chance = rng.chance(i.getChance());
            if (traceRegen) {
                IrisLogging.info("Regen object placer chance: chunk=" + x + "," + z
                        + " scope=biome-cave"
                        + " chanceResult=" + chance
                        + " chanceBase=" + i.getChance()
                        + " densityMid=" + i.getDensity()
                        + " objects=" + i.getPlace().size());
            }
            if (chance) {
                biomeCaveTriggered++;
                try {
                    ObjectPlacementResult result = placeCaveObject(writer, rng, x, z, i, biomeCaveProfile, complex, traceRegen, x, z, "biome-cave", caveBiome.getLoadKey(), caveAnchorCache);
                    attempts += result.attempts();
                    placed += result.placed();
                    rejected += result.rejected();
                    nullObjects += result.nullObjects();
                    errors += result.errors();
                } catch (Throwable e) {
                    errors++;
                    IrisLogging.reportError(e);
                    IrisLogging.error("Failed to place cave objects in the following biome: " + caveBiome.getName());
                    IrisLogging.error("Object(s) " + i.getPlace().toString(", ") + " (" + e.getClass().getSimpleName() + ").");
                    IrisLogging.error("Are these objects missing?");
                    e.printStackTrace();
                }
            }
        }

        for (IrisObjectPlacement i : region.getSurfaceObjects()) {
            regionSurfaceChecked++;
            boolean chance = rng.chance(i.getChance() + rng.d(-0.005, 0.005));
            if (traceRegen) {
                IrisLogging.info("Regen object placer chance: chunk=" + x + "," + z
                        + " scope=region-surface"
                        + " chanceResult=" + chance
                        + " chanceBase=" + i.getChance()
                        + " densityMid=" + i.getDensity()
                        + " objects=" + i.getPlace().size());
            }
            if (chance) {
                regionSurfaceTriggered++;
                try {
                    ObjectPlacementResult result = placeObject(writer, rng, x << 4, z << 4, i, complex, traceRegen, x, z, "region-surface");
                    attempts += result.attempts();
                    placed += result.placed();
                    rejected += result.rejected();
                    nullObjects += result.nullObjects();
                    errors += result.errors();
                } catch (Throwable e) {
                    errors++;
                    IrisLogging.reportError(e);
                    IrisLogging.error("Failed to place objects in the following region: " + region.getName());
                    IrisLogging.error("Object(s) " + i.getPlace().toString(", ") + " (" + e.getClass().getSimpleName() + ").");
                    IrisLogging.error("Are these objects missing?");
                    e.printStackTrace();
                }
            }
        }

        for (IrisObjectPlacement i : region.getCarvingObjects()) {
            if (!i.getCarvingSupport().supportsCarving()) {
                continue;
            }
            regionCaveChecked++;
            boolean chance = rng.chance(i.getChance());
            if (traceRegen) {
                IrisLogging.info("Regen object placer chance: chunk=" + x + "," + z
                        + " scope=region-cave"
                        + " chanceResult=" + chance
                        + " chanceBase=" + i.getChance()
                        + " densityMid=" + i.getDensity()
                        + " objects=" + i.getPlace().size());
            }
            if (chance) {
                regionCaveTriggered++;
                try {
                    ObjectPlacementResult result = placeCaveObject(writer, rng, x, z, i, regionCaveProfile, complex, traceRegen, x, z, "region-cave", null, caveAnchorCache);
                    attempts += result.attempts();
                    placed += result.placed();
                    rejected += result.rejected();
                    nullObjects += result.nullObjects();
                    errors += result.errors();
                } catch (Throwable e) {
                    errors++;
                    IrisLogging.reportError(e);
                    IrisLogging.error("Failed to place cave objects in the following region: " + region.getName());
                    IrisLogging.error("Object(s) " + i.getPlace().toString(", ") + " (" + e.getClass().getSimpleName() + ").");
                    IrisLogging.error("Are these objects missing?");
                    e.printStackTrace();
                }
            }
        }

        return new ObjectPlacementSummary(
                biomeSurfaceChecked,
                biomeSurfaceTriggered,
                biomeCaveChecked,
                biomeCaveTriggered,
                regionSurfaceChecked,
                regionSurfaceTriggered,
                regionCaveChecked,
                regionCaveTriggered,
                attempts,
                placed,
                rejected,
                nullObjects,
                errors
        );
    }

    @ChunkCoordinates
    private void placeProceduralObjects(MantleWriter writer, RNG rng, int x, int z, IrisBiome surfaceBiome, IrisBiome caveBiome, IrisRegion region) {
        placeProceduralFrom(writer, rng, x, z, surfaceBiome.getProceduralObjects(), surfaceBiome.getName());
        placeProceduralFrom(writer, rng, x, z, region.getProceduralObjects(), region.getName());
        if (caveBiome != null && caveBiome != surfaceBiome) {
            placeProceduralFrom(writer, rng, x, z, caveBiome.getProceduralObjects(), caveBiome.getName());
        }
    }

    @ChunkCoordinates
    private void placeProceduralFrom(MantleWriter writer, RNG rng, int x, int z, IrisProceduralObjects proceduralObjects, String scope) {
        if (proceduralObjects == null || proceduralObjects.isEmpty()) {
            return;
        }

        int blockX = x << 4;
        int blockZ = z << 4;
        boolean golden = isGoldenDebugChunk(x, z);
        CaveAnchorCache caveAnchorCache = new CaveAnchorCache();
        for (IrisProceduralPlacement p : proceduralObjects.getAllPlacements()) {
            boolean chancePassed = rng.chance(p.getChance() + rng.d(-0.005, 0.005));
            if (golden) {
                IrisLogging.info("Goldendebug procedural chance: chunk=" + x + "," + z
                        + " scope=" + scope
                        + " placement=" + p.getName()
                        + " passed=" + chancePassed);
            }
            if (!chancePassed) {
                continue;
            }

            IrisObjectPlacement placement = p.asPlacement();
            boolean carving = placement.getCarvingSupport() == CarvingMode.CARVING_ONLY;
            if (carving && placement.getMode() == ObjectPlaceMode.CENTER_HEIGHT) {
                placement.setMode(ObjectPlaceMode.FAST_MIN_HEIGHT);
            }
            IObjectPlacer placer = p.isPlausible() ? new DecayControlPlacer(writer) : writer;
            if (golden) {
                placer = new GoldenDebugPlacer(placer, scope + "/" + p.getName());
            }
            int density = Math.max(1, p.getDensity());
            for (int i = 0; i < density; i++) {
                IrisObject variant = p.getVariantObject(getData(), rng);
                if (variant == null) {
                    if (golden) {
                        IrisLogging.info("Goldendebug procedural pick: chunk=" + x + "," + z
                                + " placement=" + p.getName()
                                + " densityIndex=" + i
                                + " variant=null");
                    }
                    continue;
                }
                int xx = rng.i(blockX, blockX + 15);
                int zz = rng.i(blockZ, blockZ + 15);
                int id = rng.i(0, Integer.MAX_VALUE);
                if (golden) {
                    KList<IrisObject> pool = p.getVariantObjects(getData());
                    IrisLogging.info("Goldendebug procedural pick: chunk=" + x + "," + z
                            + " placement=" + p.getName()
                            + " densityIndex=" + i
                            + " variant=" + variant.getLoadKey()
                            + " variantIndex=" + pool.indexOf(variant) + "/" + pool.size()
                            + " xx=" + xx
                            + " zz=" + zz
                            + " mode=" + placement.getMode()
                            + " carving=" + carving);
                }
                try {
                    int placeResult = -1;
                    if (carving) {
                        int caveFloorY = findNearestCaveFloor(writer, xx, zz, caveAnchorCache);
                        if (golden) {
                            IrisLogging.info("Goldendebug procedural caveFloor: chunk=" + x + "," + z
                                    + " placement=" + p.getName()
                                    + " xx=" + xx
                                    + " zz=" + zz
                                    + " caveFloorY=" + caveFloorY);
                        }
                        if (caveFloorY > 0) {
                            placeResult = variant.place(xx, caveFloorY, zz, placer, placement, rng, (b, data) -> {
                                String marker = placementMarker(variant, id, "procedural");
                                if (marker != null) {
                                    writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                                }
                            }, null, getData());
                        }
                    } else {
                        placeResult = variant.place(xx, -1, zz, placer, placement, rng, (b, data) -> {
                            String marker = placementMarker(variant, id, "procedural");
                            if (marker != null) {
                                writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                            }
                        }, null, getData());
                    }
                    if (golden) {
                        IrisLogging.info("Goldendebug procedural result: chunk=" + x + "," + z
                                + " placement=" + p.getName()
                                + " variant=" + variant.getLoadKey()
                                + " xx=" + xx
                                + " zz=" + zz
                                + " resultY=" + placeResult);
                    }
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    IrisLogging.error("Failed to place procedural object '" + p.getName() + "' in " + scope);
                    e.printStackTrace();
                }
            }
        }
    }

    @BlockCoordinates
    private ObjectPlacementResult placeObject(
            MantleWriter writer,
            RNG rng,
            int x,
            int z,
            IrisObjectPlacement objectPlacement,
            IrisComplex complex,
            boolean traceRegen,
            int chunkX,
            int chunkZ,
            String scope
    ) {
        int attempts = 0;
        int placed = 0;
        int rejected = 0;
        int nullObjects = 0;
        int errors = 0;
        int density = objectPlacement.getDensity(rng, x, z, getData());
        boolean golden = isGoldenDebugChunk(chunkX, chunkZ);

        for (int i = 0; i < density; i++) {
            attempts++;
            IrisObject v = objectPlacement.getScale().get(rng, objectPlacement.getObject(complex, rng));
            if (v == null) {
                nullObjects++;
                if (traceRegen) {
                    IrisLogging.warn("Regen object placement null object: chunk=" + chunkX + "," + chunkZ
                            + " scope=" + scope
                            + " densityIndex=" + i
                            + " density=" + density
                            + " placementKeys=" + objectPlacement.getPlace().toString(","));
                }
                continue;
            }
            int xx = rng.i(x, x + 15);
            int zz = rng.i(z, z + 15);
            IrisObjectPlacement effectivePlacement = resolveEffectivePlacement(objectPlacement, v);
            int id = rng.i(0, Integer.MAX_VALUE);
            IObjectPlacer placePlacer = golden ? new GoldenDebugPlacer(writer, scope + "/" + v.getLoadKey()) : writer;
            if (golden) {
                IrisLogging.info("Goldendebug object attempt: chunk=" + chunkX + "," + chunkZ
                        + " scope=" + scope
                        + " object=" + v.getLoadKey()
                        + " densityIndex=" + i
                        + " xx=" + xx
                        + " zz=" + zz
                        + " mode=" + effectivePlacement.getMode());
            }
            try {
                int result = v.place(xx, -1, zz, placePlacer, effectivePlacement, rng, (b, data) -> {
                    String marker = placementMarker(v, id, "surface");
                    if (marker != null) {
                        writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                    }
                    if (effectivePlacement.isDolphinTarget() && effectivePlacement.isUnderwater() && B.isStorageChest(data)) {
                        writer.setData(b.getX(), b.getY(), b.getZ(), MatterStructurePOI.BURIED_TREASURE);
                    }
                }, null, getData());

                if (result >= 0) {
                    placed++;
                } else {
                    rejected++;
                }

                if (golden) {
                    IrisLogging.info("Goldendebug object result: chunk=" + chunkX + "," + chunkZ
                            + " scope=" + scope
                            + " object=" + v.getLoadKey()
                            + " resultY=" + result
                            + " fallback=surface");
                }

                if (traceRegen) {
                    IrisLogging.info("Regen object placement result: chunk=" + chunkX + "," + chunkZ
                            + " scope=" + scope
                            + " object=" + v.getLoadKey()
                            + " resultY=" + result
                            + " px=" + xx
                            + " pz=" + zz
                            + " fallback=surface"
                            + " densityIndex=" + i
                            + " density=" + density);
                }
            } catch (Throwable e) {
                errors++;
                IrisLogging.reportError(e);
                IrisLogging.error("Regen object placement exception: chunk=" + chunkX + "," + chunkZ
                        + " scope=" + scope
                        + " object=" + v.getLoadKey()
                        + " densityIndex=" + i
                        + " density=" + density
                        + " error=" + e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        }

        return new ObjectPlacementResult(attempts, placed, rejected, nullObjects, errors);
    }

    @ChunkCoordinates
    private boolean caveAnchorBiomeConflicts(int x, int y, int z, String expectedCaveBiomeKey) {
        if (expectedCaveBiomeKey == null) {
            return false;
        }
        Engine engine = getEngineMantle().getEngine();
        IrisBiome at = engine.getCaveBiome(x, y, z);
        if (at == null) {
            return false;
        }
        String atKey = at.getLoadKey();
        if (atKey == null || atKey.equals(expectedCaveBiomeKey)) {
            return false;
        }
        IrisBiome surface = engine.getSurfaceBiome(x, z);
        if (surface != null && atKey.equals(surface.getLoadKey())) {
            return false;
        }
        return true;
    }

    private ObjectPlacementResult placeCaveObject(
            MantleWriter writer,
            RNG rng,
            int chunkX,
            int chunkZ,
            IrisObjectPlacement objectPlacement,
            IrisCaveProfile caveProfile,
            IrisComplex complex,
            boolean traceRegen,
            int metricChunkX,
            int metricChunkZ,
            String scope,
            String expectedCaveBiomeKey,
            CaveAnchorCache anchorCache
    ) {
        int attempts = 0;
        int placed = 0;
        int rejected = 0;
        int nullObjects = 0;
        int errors = 0;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int density = objectPlacement.getDensity(rng, minX, minZ, getData());
        IrisCaveAnchorMode anchorMode = resolveAnchorMode(objectPlacement, caveProfile);
        if (objectPlacement.getMode() == ObjectPlaceMode.CEILING_HANG) {
            anchorMode = IrisCaveAnchorMode.CEILING;
        }
        int anchorScanStep = resolveAnchorScanStep(caveProfile);
        int objectMinDepthBelowSurface = resolveObjectMinDepthBelowSurface(caveProfile);
        int anchorSearchAttempts = resolveAnchorSearchAttempts(caveProfile);

        for (int i = 0; i < density; i++) {
            attempts++;
            IrisObject object = objectPlacement.getScale().get(rng, objectPlacement.getObject(complex, rng));
            if (object == null) {
                nullObjects++;
                if (traceRegen) {
                    IrisLogging.warn("Regen cave object placement null object: chunk=" + metricChunkX + "," + metricChunkZ
                            + " scope=" + scope
                            + " densityIndex=" + i
                            + " density=" + density
                            + " placementKeys=" + objectPlacement.getPlace().toString(","));
                }
                logCaveReject(
                        scope,
                        "NULL_OBJECT",
                        metricChunkX,
                        metricChunkZ,
                        objectPlacement,
                        null,
                        i,
                        density,
                        anchorMode,
                        anchorSearchAttempts,
                        anchorScanStep,
                        objectMinDepthBelowSurface,
                        null,
                        null
                );
                continue;
            }

            int x = 0;
            int z = 0;
            int y = -1;
            for (int search = 0; search < anchorSearchAttempts; search++) {
                int candidateX = rng.i(minX, minX + 15);
                int candidateZ = rng.i(minZ, minZ + 15);
                int candidateY = findCaveAnchorY(writer, rng, candidateX, candidateZ, anchorMode, anchorScanStep, objectMinDepthBelowSurface, anchorCache);
                if (candidateY < 0) {
                    continue;
                }

                if (caveAnchorBiomeConflicts(candidateX, candidateY, candidateZ, expectedCaveBiomeKey)) {
                    continue;
                }

                x = candidateX;
                z = candidateZ;
                y = candidateY;
                break;
            }

            if (y < 0) {
                rejected++;
                logCaveReject(
                        scope,
                        "NO_ANCHOR",
                        metricChunkX,
                        metricChunkZ,
                        objectPlacement,
                        object,
                        i,
                        density,
                        anchorMode,
                        anchorSearchAttempts,
                        anchorScanStep,
                        objectMinDepthBelowSurface,
                        null,
                        null
                );
                continue;
            }

            int id = rng.i(0, Integer.MAX_VALUE);
            IrisObjectPlacement resolvedPlacement = resolveEffectivePlacement(objectPlacement, object);
            if (resolvedPlacement.getMode() == ObjectPlaceMode.CENTER_HEIGHT && caveProfile != null) {
                ObjectPlaceMode profileMode = caveProfile.getDefaultObjectPlaceMode();
                if (profileMode != null) {
                    resolvedPlacement = resolvedPlacement.toPlacement(object.getLoadKey());
                    resolvedPlacement.setMode(profileMode);
                }
            }
            IrisObjectPlacement effectivePlacement = resolvedPlacement;
            AtomicBoolean wrotePlacementData = new AtomicBoolean(false);

            try {
                int caveCeiling = findCaveCeiling(writer, x, y, z);
                IObjectPlacer clampedPlacer = new CeilingClampedPlacer(writer, caveCeiling);
                int placeY = y;
                if (effectivePlacement.getMode() == ObjectPlaceMode.CEILING_HANG) {
                    placeY = Math.max(1, caveCeiling - 1 - Math.floorDiv(object.getH(), 2));
                }
                int result = object.place(x, placeY, z, clampedPlacer, effectivePlacement, rng, (b, data) -> {
                    wrotePlacementData.set(true);
                    String marker = placementMarker(object, id, "cave");
                    if (marker != null) {
                        writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                    }
                    if (effectivePlacement.isDolphinTarget() && effectivePlacement.isUnderwater() && B.isStorageChest(data)) {
                        writer.setData(b.getX(), b.getY(), b.getZ(), MatterStructurePOI.BURIED_TREASURE);
                    }
                }, null, getData());

                boolean wroteBlocks = wrotePlacementData.get();
                if (wroteBlocks) {
                    placed++;
                } else if (result < 0) {
                    rejected++;
                    logCaveReject(
                            scope,
                            "PLACE_NEGATIVE",
                            metricChunkX,
                            metricChunkZ,
                            objectPlacement,
                            object,
                            i,
                            density,
                            anchorMode,
                            anchorSearchAttempts,
                            anchorScanStep,
                            objectMinDepthBelowSurface,
                            y,
                            null
                    );
                }

                if (traceRegen) {
                    IrisLogging.info("Regen cave object placement result: chunk=" + metricChunkX + "," + metricChunkZ
                            + " scope=" + scope
                            + " object=" + object.getLoadKey()
                            + " resultY=" + result
                            + " anchorY=" + y
                            + " px=" + x
                            + " pz=" + z
                            + " wroteBlocks=" + wroteBlocks
                            + " densityIndex=" + i
                            + " density=" + density);
                }
            } catch (Throwable e) {
                errors++;
                IrisLogging.reportError(e);
                IrisLogging.error("Regen cave object placement exception: chunk=" + metricChunkX + "," + metricChunkZ
                        + " scope=" + scope
                        + " object=" + object.getLoadKey()
                        + " densityIndex=" + i
                        + " density=" + density
                        + " error=" + e.getClass().getSimpleName() + ":" + e.getMessage());
                logCaveReject(
                        scope,
                        "EXCEPTION",
                        metricChunkX,
                        metricChunkZ,
                        objectPlacement,
                        object,
                        i,
                        density,
                        anchorMode,
                        anchorSearchAttempts,
                        anchorScanStep,
                        objectMinDepthBelowSurface,
                        y,
                        e
                );
            }
        }

        return new ObjectPlacementResult(attempts, placed, rejected, nullObjects, errors);
    }

    @ChunkCoordinates
    private void placeUpperObjects(
            MantleWriter writer,
            RNG rng,
            int chunkX,
            int chunkZ,
            int centerX,
            int centerZ,
            int lowerSurfaceCenterY,
            UpperDimensionContext upperCtx,
            IrisDimension dimension,
            IrisComplex complex,
            boolean traceRegen
    ) {
        IrisBiome upperBiome = upperCtx.getUpperBiome(centerX, centerZ);
        IrisRegion upperRegion = upperCtx.getUpperRegion(centerX, centerZ);
        if (upperBiome == null && upperRegion == null) {
            return;
        }

        boolean forcePlace = dimension.isUpperObjectsForcePlace();
        if (upperBiome != null) {
            for (IrisObjectPlacement i : upperBiome.getSurfaceObjects()) {
                if (!rng.chance(i.getChance() + rng.d(-0.005, 0.005))) {
                    continue;
                }
                try {
                    placeUpperObject(writer, rng, chunkX, chunkZ, i, upperCtx, dimension, complex, forcePlace, traceRegen, "upper-biome-surface");
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    IrisLogging.error("Failed to place upper-dimension objects in biome " + upperBiome.getName()
                            + ": " + i.getPlace().toString(", ") + " (" + e.getClass().getSimpleName() + ")");
                    e.printStackTrace();
                }
            }
        }

        if (upperRegion != null) {
            for (IrisObjectPlacement i : upperRegion.getSurfaceObjects()) {
                if (!rng.chance(i.getChance() + rng.d(-0.005, 0.005))) {
                    continue;
                }
                try {
                    placeUpperObject(writer, rng, chunkX, chunkZ, i, upperCtx, dimension, complex, forcePlace, traceRegen, "upper-region-surface");
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    IrisLogging.error("Failed to place upper-dimension objects in region " + upperRegion.getName()
                            + ": " + i.getPlace().toString(", ") + " (" + e.getClass().getSimpleName() + ")");
                    e.printStackTrace();
                }
            }
        }
    }

    @ChunkCoordinates
    private void placeUpperObject(
            MantleWriter writer,
            RNG rng,
            int chunkX,
            int chunkZ,
            IrisObjectPlacement objectPlacement,
            UpperDimensionContext upperCtx,
            IrisDimension dimension,
            IrisComplex complex,
            boolean forcePlace,
            boolean traceRegen,
            String scope
    ) {
        int chunkHeight = getEngineMantle().getEngine().getHeight();
        int upperGap = dimension.getUpperDimensionGap();
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int density = objectPlacement.getDensity(rng, minX, minZ, getData());

        for (int i = 0; i < density; i++) {
            IrisObject v = objectPlacement.getScale().get(rng, objectPlacement.getObject(complex, rng));
            if (v == null) {
                continue;
            }

            int xx = rng.i(minX, minX + 15);
            int zz = rng.i(minZ, minZ + 15);
            int columnLowerSurfaceY = getEngineMantle().getEngine().getHeight(xx, zz, true);
            int rawUpperSurface = upperCtx.getUpperSurfaceY(xx, zz);
            int upperSurfaceY = Math.max(rawUpperSurface, columnLowerSurfaceY + upperGap);
            if (upperSurfaceY >= chunkHeight - 2) {
                continue;
            }

            int halfH = Math.floorDiv(v.getH(), 2);
            int anchorY = upperSurfaceY - 1 - halfH;
            if (anchorY <= 1) {
                continue;
            }

            int id = rng.i(0, Integer.MAX_VALUE);
            IrisObjectPlacement placement = objectPlacement.toPlacement(v.getLoadKey());
            placement.setMode(ObjectPlaceMode.CENTER_HEIGHT);
            placement.setRotation(buildUpsideDownRotation());
            placement.setCarvingSupport(CarvingMode.ANYWHERE);
            if (forcePlace) {
                placement.setForcePlace(true);
            }

            int result = v.place(xx, anchorY, zz, writer, placement, rng, (b, data) -> {
                String marker = placementMarker(v, id, "upper");
                if (marker != null) {
                    writer.setData(b.getX(), b.getY(), b.getZ(), marker);
                }
                if (placement.isDolphinTarget() && placement.isUnderwater() && B.isStorageChest(data)) {
                    writer.setData(b.getX(), b.getY(), b.getZ(), MatterStructurePOI.BURIED_TREASURE);
                }
            }, null, getData());

            if (traceRegen) {
                IrisLogging.info("Upper object placement: chunk=" + chunkX + "," + chunkZ
                        + " scope=" + scope
                        + " object=" + v.getLoadKey()
                        + " anchorY=" + anchorY
                        + " upperSurfaceY=" + upperSurfaceY
                        + " resultY=" + result
                        + " forcePlace=" + forcePlace);
            }
        }
    }

    private IrisObjectRotation buildUpsideDownRotation() {
        IrisObjectRotation rt = new IrisObjectRotation();
        rt.setEnabled(true);
        rt.setXAxis(new IrisAxisRotationClamp(true, true, 180D, 180D, 90D));
        rt.setYAxis(new IrisAxisRotationClamp(true, false, 0D, 0D, 90D));
        rt.setZAxis(new IrisAxisRotationClamp());
        return rt;
    }

    private void logCaveReject(
            String scope,
            String reason,
            int chunkX,
            int chunkZ,
            IrisObjectPlacement objectPlacement,
            IrisObject object,
            int densityIndex,
            int density,
            IrisCaveAnchorMode anchorMode,
            int anchorSearchAttempts,
            int anchorScanStep,
            int objectMinDepthBelowSurface,
            Integer anchorY,
            Throwable error
    ) {
        if (!IrisSettings.get().getGeneral().isDebug()) {
            return;
        }

        String placementKeys = objectPlacement == null ? "none" : objectPlacement.getPlace().toString(",");
        String objectKey = object == null ? "null" : object.getLoadKey();
        String throttleKey = scope + "|" + reason + "|" + placementKeys + "|" + objectKey;
        CaveRejectLogState state = CAVE_REJECT_LOG_STATE.computeIfAbsent(throttleKey, (k) -> new CaveRejectLogState());
        long now = System.currentTimeMillis();
        long last = state.lastLogMs.get();
        if ((now - last) < CAVE_REJECT_LOG_THROTTLE_MS) {
            state.suppressed.incrementAndGet();
            return;
        }

        if (!state.lastLogMs.compareAndSet(last, now)) {
            state.suppressed.incrementAndGet();
            return;
        }

        int suppressed = state.suppressed.getAndSet(0);
        String anchorYText = anchorY == null ? "none" : String.valueOf(anchorY);
        String errorText = error == null ? "none" : error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
        IrisLogging.warn("Cave object reject: scope=" + scope
                + " reason=" + reason
                + " chunk=" + chunkX + "," + chunkZ
                + " object=" + objectKey
                + " placements=" + placementKeys
                + " densityIndex=" + densityIndex
                + " density=" + density
                + " anchorMode=" + anchorMode
                + " anchorSearchAttempts=" + anchorSearchAttempts
                + " anchorScanStep=" + anchorScanStep
                + " minDepthBelowSurface=" + objectMinDepthBelowSurface
                + " anchorY=" + anchorYText
                + " forcePlace=" + (objectPlacement != null && objectPlacement.isForcePlace())
                + " carvingSupport=" + (objectPlacement == null ? "none" : objectPlacement.getCarvingSupport())
                + " bottom=" + (objectPlacement != null && objectPlacement.isBottom())
                + " suppressed=" + suppressed
                + " error=" + errorText);
    }

    private IrisObjectPlacement resolveEffectivePlacement(IrisObjectPlacement objectPlacement, IrisObject object) {
        if (objectPlacement == null || object == null) {
            return objectPlacement;
        }

        String loadKey = object.getLoadKey();
        if (loadKey == null || loadKey.isBlank()) {
            return objectPlacement;
        }

        String normalized = loadKey.toLowerCase(Locale.ROOT);
        boolean imported = normalized.startsWith("imports/")
                || normalized.contains("/imports/")
                || normalized.contains("imports/");

        if (!imported) {
            return objectPlacement;
        }

        ObjectPlaceMode mode = objectPlacement.getMode();
        if (mode == ObjectPlaceMode.FLOATING || mode == ObjectPlaceMode.STRUCTURE_PIECE) {
            return objectPlacement;
        }
        boolean needsModeChange = mode != ObjectPlaceMode.FAST_MIN_STILT;
        if (!needsModeChange) {
            return objectPlacement;
        }

        IrisObjectPlacement effectivePlacement = objectPlacement.toPlacement(loadKey);
        effectivePlacement.setMode(ObjectPlaceMode.FAST_MIN_STILT);
        return effectivePlacement;
    }

    private int findNearestCaveFloor(MantleWriter writer, int x, int z, CaveAnchorCache anchorCache) {
        KList<Integer> anchors = anchorCache.get(writer, IrisCaveAnchorMode.FLOOR, 1, 0, x, z);
        if (anchors.isEmpty()) {
            return -1;
        }
        return anchors.get(anchors.size() - 1);
    }

    private int findCaveCeiling(MantleWriter writer, int x, int anchorY, int z) {
        Engine engine = getEngineMantle().getEngine();
        int surfaceY = engine.getHeight(x, z);
        int maxScan = Math.min(engine.getHeight() - 1, Math.max(0, surfaceY));
        for (int sy = anchorY + 1; sy <= maxScan; sy++) {
            if (!writer.isCarved(x, sy, z)) {
                return sy;
            }
        }
        return maxScan;
    }

    private static final class GoldenDebugPlacer implements IObjectPlacer {
        private final IObjectPlacer delegate;
        private final String tag;

        private GoldenDebugPlacer(IObjectPlacer delegate, String tag) {
            this.delegate = delegate;
            this.tag = tag;
        }

        @Override
        public int getHighest(int x, int z, IrisData data) {
            int result = delegate.getHighest(x, z, data);
            IrisLogging.info("Goldendebug query: tag=" + tag + " getHighest(" + x + "," + z + ")=" + result);
            return result;
        }

        @Override
        public int getHighest(int x, int z, IrisData data, boolean ignoreFluid) {
            int result = delegate.getHighest(x, z, data, ignoreFluid);
            IrisLogging.info("Goldendebug query: tag=" + tag + " getHighest(" + x + "," + z + ",ignoreFluid=" + ignoreFluid + ")=" + result);
            return result;
        }

        @Override
        public void set(int x, int y, int z, PlatformBlockState d) {
            delegate.set(x, y, z, d);
        }

        @Override
        public PlatformBlockState get(int x, int y, int z) {
            return delegate.get(x, y, z);
        }

        @Override
        public boolean isPreventingDecay() {
            return delegate.isPreventingDecay();
        }

        @Override
        public boolean isCarved(int x, int y, int z) {
            boolean result = delegate.isCarved(x, y, z);
            IrisLogging.info("Goldendebug query: tag=" + tag + " isCarved(" + x + "," + y + "," + z + ")=" + result);
            return result;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            boolean result = delegate.isSolid(x, y, z);
            IrisLogging.info("Goldendebug query: tag=" + tag + " isSolid(" + x + "," + y + "," + z + ")=" + result);
            return result;
        }

        @Override
        public boolean isUnderwater(int x, int z) {
            return delegate.isUnderwater(x, z);
        }

        @Override
        public int getFluidHeight() {
            return delegate.getFluidHeight();
        }

        @Override
        public boolean isDebugSmartBore() {
            return delegate.isDebugSmartBore();
        }

        @Override
        public <T> void setData(int xx, int yy, int zz, T data) {
            delegate.setData(xx, yy, zz, data);
        }

        @Override
        public <T> T getData(int xx, int yy, int zz, Class<T> t) {
            return delegate.getData(xx, yy, zz, t);
        }

        @Override
        public void setTile(int xx, int yy, int zz, TileData tile) {
            delegate.setTile(xx, yy, zz, tile);
        }

        @Override
        public Engine getEngine() {
            return delegate.getEngine();
        }
    }

    private static final class CeilingClampedPlacer implements IObjectPlacer {
        private final IObjectPlacer delegate;
        private final int maxY;

        private CeilingClampedPlacer(IObjectPlacer delegate, int maxY) {
            this.delegate = delegate;
            this.maxY = maxY;
        }

        @Override
        public int getHighest(int x, int z, IrisData data) {
            return delegate.getHighest(x, z, data);
        }

        @Override
        public int getHighest(int x, int z, IrisData data, boolean ignoreFluid) {
            return delegate.getHighest(x, z, data, ignoreFluid);
        }

        @Override
        public void set(int x, int y, int z, PlatformBlockState d) {
            if (y >= maxY) {
                return;
            }
            delegate.set(x, y, z, d);
        }

        @Override
        public PlatformBlockState get(int x, int y, int z) {
            return delegate.get(x, y, z);
        }

        @Override
        public boolean isPreventingDecay() {
            return delegate.isPreventingDecay();
        }

        @Override
        public boolean isCarved(int x, int y, int z) {
            return delegate.isCarved(x, y, z);
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return delegate.isSolid(x, y, z);
        }

        @Override
        public boolean isUnderwater(int x, int z) {
            return delegate.isUnderwater(x, z);
        }

        @Override
        public int getFluidHeight() {
            return delegate.getFluidHeight();
        }

        @Override
        public boolean isDebugSmartBore() {
            return delegate.isDebugSmartBore();
        }

        @Override
        public <T> void setData(int xx, int yy, int zz, T data) {
            delegate.setData(xx, yy, zz, data);
        }

        @Override
        public <T> T getData(int xx, int yy, int zz, Class<T> t) {
            return delegate.getData(xx, yy, zz, t);
        }

        @Override
        public void setTile(int xx, int yy, int zz, TileData tile) {
            if (yy >= maxY) {
                return;
            }
            delegate.setTile(xx, yy, zz, tile);
        }

        @Override
        public Engine getEngine() {
            return delegate.getEngine();
        }
    }

    private int findCaveAnchorY(MantleWriter writer, RNG rng, int x, int z, IrisCaveAnchorMode anchorMode, int anchorScanStep, int objectMinDepthBelowSurface, CaveAnchorCache anchorCache) {
        KList<Integer> anchors = anchorCache.get(writer, anchorMode, anchorScanStep, objectMinDepthBelowSurface, x, z);
        if (anchors.isEmpty()) {
            return -1;
        }

        if (anchors.size() == 1) {
            return anchors.get(0);
        }

        return anchors.get(rng.i(0, anchors.size() - 1));
    }

    private KList<Integer> scanCaveAnchorColumn(MantleWriter writer, IrisCaveAnchorMode anchorMode, int anchorScanStep, int objectMinDepthBelowSurface, int x, int z, CaveAnchorCache anchorCache) {
        int height = getEngineMantle().getEngine().getHeight();
        int step = Math.max(1, anchorScanStep);
        int surfaceY = anchorCache.getSurfaceHeight(x, z);
        int baseMaxAnchorY = Math.min(height - 1, surfaceY - Math.max(0, objectMinDepthBelowSurface));
        if (baseMaxAnchorY <= 1) {
            return new KList<>();
        }

        int widenedMaxAnchorY = Math.min(height - 1, surfaceY - 3);
        widenedMaxAnchorY = Math.min(widenedMaxAnchorY, baseMaxAnchorY + Math.max(0, objectMinDepthBelowSurface) / 2);
        int carvedHeight = Math.min(height, Math.max(baseMaxAnchorY, widenedMaxAnchorY) + 4);
        byte[] carvedColumn = anchorCache.getCarvedColumn(writer, x, z, carvedHeight);
        KList<Integer> anchors = scanCaveAnchorRange(anchorMode, step, carvedHeight, BEDROCK_CLEARANCE, baseMaxAnchorY, carvedColumn);
        if (!anchors.isEmpty()) {
            return anchors;
        }

        if (widenedMaxAnchorY > baseMaxAnchorY) {
            anchors = scanCaveAnchorRange(anchorMode, step, carvedHeight, baseMaxAnchorY, widenedMaxAnchorY, carvedColumn);
            if (!anchors.isEmpty()) {
                return anchors;
            }
        }

        return anchors;
    }

    private KList<Integer> scanCaveAnchorRange(IrisCaveAnchorMode anchorMode, int step, int height, int minAnchorY, int maxAnchorY, byte[] carvedColumn) {
        KList<Integer> anchors = new KList<>();
        int startY = alignAnchorScanStart(minAnchorY, step);
        for (int y = startY; y < maxAnchorY; y += step) {
            if (!isCarved(carvedColumn, y)) {
                continue;
            }

            boolean solidBelow = hasSolidNeighbor(carvedColumn, y, height, -1);
            boolean solidAbove = hasSolidNeighbor(carvedColumn, y, height, 1);
            if (matchesCaveAnchor(anchorMode, solidBelow, solidAbove)) {
                anchors.add(y);
            }
        }
        return anchors;
    }

    private int alignAnchorScanStart(int minAnchorY, int step) {
        if (minAnchorY <= BEDROCK_CLEARANCE) {
            return BEDROCK_CLEARANCE;
        }

        int delta = minAnchorY - BEDROCK_CLEARANCE;
        int steps = (delta + step - 1) / step;
        return BEDROCK_CLEARANCE + (steps * step);
    }

    private boolean isCarved(byte[] carvedColumn, int y) {
        return y >= 0 && y < carvedColumn.length && carvedColumn[y] == 1;
    }

    private boolean hasSolidNeighbor(byte[] carvedColumn, int y, int height, int direction) {
        for (int d = 1; d <= 3; d++) {
            int ny = y + (direction * d);
            if (ny < 0 || ny >= height) {
                return true;
            }
            if (!isCarved(carvedColumn, ny)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCaveAnchor(IrisCaveAnchorMode anchorMode, boolean solidBelow, boolean solidAbove) {
        return switch (anchorMode) {
            case PROFILE_DEFAULT, FLOOR -> solidBelow;
            case CEILING -> solidAbove;
            case CENTER -> !solidBelow && !solidAbove;
            case ANY -> true;
        };
    }

    private IrisCaveProfile resolveCaveProfile(IrisCaveProfile preferred, IrisCaveProfile secondary) {
        IrisCaveProfile dimensionProfile = getDimension().getCaveProfile();
        if (preferred != null && preferred.isEnabled()) {
            return preferred;
        }

        if (secondary != null && secondary.isEnabled()) {
            return secondary;
        }

        if (dimensionProfile != null) {
            return dimensionProfile;
        }

        return new IrisCaveProfile();
    }

    private IrisCaveAnchorMode resolveAnchorMode(IrisObjectPlacement objectPlacement, IrisCaveProfile caveProfile) {
        IrisCaveAnchorMode placementMode = objectPlacement.getCaveAnchorMode();
        if (placementMode != null && !placementMode.equals(IrisCaveAnchorMode.PROFILE_DEFAULT)) {
            return placementMode;
        }

        if (caveProfile == null) {
            return IrisCaveAnchorMode.FLOOR;
        }

        IrisCaveAnchorMode profileMode = caveProfile.getDefaultObjectAnchor();
        if (profileMode == null || profileMode.equals(IrisCaveAnchorMode.PROFILE_DEFAULT)) {
            return IrisCaveAnchorMode.FLOOR;
        }

        return profileMode;
    }

    private int resolveAnchorScanStep(IrisCaveProfile caveProfile) {
        if (caveProfile == null) {
            return 1;
        }

        return Math.max(1, caveProfile.getAnchorScanStep());
    }

    private int resolveObjectMinDepthBelowSurface(IrisCaveProfile caveProfile) {
        if (caveProfile == null) {
            return 6;
        }

        return Math.max(0, caveProfile.getObjectMinDepthBelowSurface());
    }

    private int resolveAnchorSearchAttempts(IrisCaveProfile caveProfile) {
        if (caveProfile == null) {
            return 6;
        }

        return Math.max(1, caveProfile.getAnchorSearchAttempts());
    }

    private boolean isRegenTraceThread() {
        return Thread.currentThread().getName().startsWith("Iris-Regen-")
                && IrisSettings.get().getGeneral().isDebug();
    }

    private record ObjectPlacementSummary(
            int biomeSurfacePlacersChecked,
            int biomeSurfacePlacersTriggered,
            int biomeCavePlacersChecked,
            int biomeCavePlacersTriggered,
            int regionSurfacePlacersChecked,
            int regionSurfacePlacersTriggered,
            int regionCavePlacersChecked,
            int regionCavePlacersTriggered,
            int objectAttempts,
            int objectPlaced,
            int objectRejected,
            int objectNull,
            int objectErrors
    ) {
    }

    private record ObjectPlacementResult(int attempts, int placed, int rejected, int nullObjects, int errors) {
    }

    private static final class CaveRejectLogState {
        private final AtomicLong lastLogMs = new AtomicLong(0L);
        private final AtomicInteger suppressed = new AtomicInteger(0);
    }

    @BlockCoordinates
    private Set<String> guessPlacedKeys(RNG rng, int x, int z, IrisObjectPlacement objectPlacement) {
        Set<String> f = new KSet<>();
        for (int i = 0; i < objectPlacement.getDensity(rng, x, z, getData()); i++) {
            IrisObject v = objectPlacement.getScale().get(rng, objectPlacement.getObject(getComplex(), rng));
            if (v == null) {
                continue;
            }

            f.add(v.getLoadKey());
        }

        return f;
    }

    public Set<String> guess(int x, int z) {
        // todo The guess doesnt bring into account that the placer may return -1
        RNG rng = applyNoise(x, z, Cache.key(x, z) + seed());
        IrisBiome biome = getEngineMantle().getEngine().getSurfaceBiome((x << 4) + 8, (z << 4) + 8);
        IrisRegion region = getEngineMantle().getEngine().getRegion((x << 4) + 8, (z << 4) + 8);
        Set<String> v = new KSet<>();
        for (IrisObjectPlacement i : biome.getSurfaceObjects()) {
            if (rng.chance(i.getChance() + rng.d(-0.005, 0.005))) {
                v.addAll(guessPlacedKeys(rng, x, z, i));
            }
        }

        for (IrisObjectPlacement i : region.getSurfaceObjects()) {
            if (rng.chance(i.getChance() + rng.d(-0.005, 0.005))) {
                v.addAll(guessPlacedKeys(rng, x, z, i));
            }
        }

        return v;
    }

    protected int computeRadius() {
        IrisDimension dimension = getDimension();

        AtomicInteger xg = new AtomicInteger();
        AtomicInteger zg = new AtomicInteger();

        KSet<String> objects = new KSet<>();
        KMap<IrisObjectScale, KList<String>> scalars = new KMap<>();
        KList<IrisObjectPlacement> vacuumPlacements = new KList<>();
        for (IrisRegion region : dimension.getAllRegions(this::getData)) {
            for (IrisObjectPlacement j : region.getObjects()) {
                if (j.getScale().canScaleBeyond()) {
                    scalars.put(j.getScale(), j.getPlace());
                } else {
                    objects.addAll(j.getPlace());
                }
                if (IrisObjectVacuum.isVacuumMode(j.getMode())) {
                    vacuumPlacements.add(j);
                }
            }
            updateProceduralRadiusBounds(region.getProceduralObjects(), xg, zg);
        }
        for (IrisBiome biome : dimension.getReachableBiomes(this::getData)) {
            updateProceduralRadiusBounds(biome.getProceduralObjects(), xg, zg);
            for (IrisObjectPlacement j : biome.getObjects()) {
                if (j.getScale().canScaleBeyond()) {
                    scalars.put(j.getScale(), j.getPlace());
                } else {
                    objects.addAll(j.getPlace());
                }
                if (IrisObjectVacuum.isVacuumMode(j.getMode())) {
                    vacuumPlacements.add(j);
                }
            }
        }

        KMap<String, IrisBlockVector> sizeCache = new KMap<>();
        for (String i : objects) {
            updateRadiusBounds(sizeCache, xg, zg, i, 1D);
        }

        for (Map.Entry<IrisObjectScale, KList<String>> entry : scalars.entrySet()) {
            double ms = entry.getKey().getMaxScale();
            for (String j : entry.getValue()) {
                updateRadiusBounds(sizeCache, xg, zg, j, ms);
            }
        }

        for (IrisObjectPlacement j : vacuumPlacements) {
            updateVacuumRadiusBounds(sizeCache, xg, zg, j);
        }

        return Math.max(xg.get(), zg.get());
    }

    private void updateProceduralRadiusBounds(IrisProceduralObjects procedural, AtomicInteger xg, AtomicInteger zg) {
        if (procedural == null || procedural.isEmpty()) {
            return;
        }
        for (IrisProceduralPlacement placement : procedural.getAllPlacements()) {
            if (placement == null) {
                continue;
            }
            KList<IrisObject> variants = placement.getVariantObjects(getData());
            if (variants == null) {
                continue;
            }
            for (IrisObject variant : variants) {
                if (variant == null) {
                    continue;
                }
                xg.getAndSet(Math.max(variant.getW(), xg.get()));
                zg.getAndSet(Math.max(variant.getD(), zg.get()));
            }
        }
    }

    private void updateRadiusBounds(
            KMap<String, IrisBlockVector> sizeCache,
            AtomicInteger xg,
            AtomicInteger zg,
            String objectKey,
            double scale
    ) {
        try {
            IrisBlockVector bv = loadObjectSize(sizeCache, objectKey);
            if (bv == null) {
                throw new RuntimeException();
            }

            if (Math.max(bv.getBlockX(), bv.getBlockZ()) > 128) {
                if (scale > 1D) {
                    IrisLogging.warn("Object " + objectKey + " has a large size (" + bv + ") and may increase memory usage! (Object scaled up to " + Form.pc(scale, 2) + ")");
                } else {
                    IrisLogging.warn("Object " + objectKey + " has a large size (" + bv + ") and may increase memory usage!");
                }
            }

            xg.getAndSet(Math.max((int) Math.ceil(bv.getBlockX() * scale), xg.get()));
            zg.getAndSet(Math.max((int) Math.ceil(bv.getBlockZ() * scale), zg.get()));
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    private void updateVacuumRadiusBounds(
            KMap<String, IrisBlockVector> sizeCache,
            AtomicInteger xg,
            AtomicInteger zg,
            IrisObjectPlacement placement
    ) {
        int pad = 2 * IrisObjectVacuum.resolveRadius(placement.getMode(), placement.getVacuumSettings());
        if (pad <= 0) {
            return;
        }

        double scale = placement.getScale() != null ? Math.max(1D, placement.getScale().getMaxScale()) : 1D;
        for (String objectKey : placement.getPlace()) {
            try {
                IrisBlockVector bv = loadObjectSize(sizeCache, objectKey);
                if (bv == null) {
                    continue;
                }

                int reachX = (int) Math.ceil(Math.abs(bv.getBlockX()) * scale) + pad;
                int reachZ = (int) Math.ceil(Math.abs(bv.getBlockZ()) * scale) + pad;
                xg.getAndSet(Math.max(reachX, xg.get()));
                zg.getAndSet(Math.max(reachZ, zg.get()));
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
    }

    private IrisBlockVector loadObjectSize(KMap<String, IrisBlockVector> sizeCache, String objectKey) {
        return sizeCache.computeIfAbsent(objectKey, k -> {
            try {
                return IrisObject.sampleSize(getData().getObjectLoader().findFile(objectKey));
            } catch (IOException e) {
                IrisLogging.reportError(e);
                e.printStackTrace();
            }

            return null;
        });
    }

    private final class CaveAnchorCache {
        private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<KList<Integer>>> settingCaches;
        private final Long2ObjectOpenHashMap<byte[]> carvedColumns;
        private final Long2IntOpenHashMap surfaceHeights;

        private CaveAnchorCache() {
            this.settingCaches = new Long2ObjectOpenHashMap<>();
            this.carvedColumns = new Long2ObjectOpenHashMap<>();
            this.surfaceHeights = new Long2IntOpenHashMap();
            this.surfaceHeights.defaultReturnValue(Integer.MIN_VALUE);
        }

        private KList<Integer> get(MantleWriter writer, IrisCaveAnchorMode anchorMode, int anchorScanStep, int objectMinDepthBelowSurface, int x, int z) {
            if (anchorScanStep > 65535 || objectMinDepthBelowSurface > 65535) {
                return scanCaveAnchorColumn(writer, anchorMode, anchorScanStep, objectMinDepthBelowSurface, x, z, this);
            }

            long settingKey = ((long) anchorMode.ordinal() << 32) | ((long) anchorScanStep << 16) | objectMinDepthBelowSurface;
            Long2ObjectOpenHashMap<KList<Integer>> columnCache = settingCaches.get(settingKey);
            if (columnCache == null) {
                columnCache = new Long2ObjectOpenHashMap<>();
                settingCaches.put(settingKey, columnCache);
            }

            long columnKey = Cache.key(x, z);
            KList<Integer> anchors = columnCache.get(columnKey);
            if (anchors != null) {
                return anchors;
            }

            anchors = scanCaveAnchorColumn(writer, anchorMode, anchorScanStep, objectMinDepthBelowSurface, x, z, this);
            columnCache.put(columnKey, anchors);
            return anchors;
        }

        private byte[] getCarvedColumn(MantleWriter writer, int x, int z, int height) {
            long columnKey = Cache.key(x, z);
            byte[] carvedColumn = carvedColumns.get(columnKey);
            if (carvedColumn != null && carvedColumn.length == height) {
                return carvedColumn;
            }

            carvedColumn = writer.getCarvedColumn(x, z, height);
            carvedColumns.put(columnKey, carvedColumn);
            return carvedColumn;
        }

        private int getSurfaceHeight(int x, int z) {
            long columnKey = Cache.key(x, z);
            int surfaceY = surfaceHeights.get(columnKey);
            if (surfaceY != Integer.MIN_VALUE) {
                return surfaceY;
            }

            surfaceY = getEngineMantle().getEngine().getHeight(x, z);
            surfaceHeights.put(columnKey, surfaceY);
            return surfaceY;
        }
    }

}

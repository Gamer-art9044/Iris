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

package art.arcane.iris.engine.modifier;

import art.arcane.iris.engine.UpperDimensionContext;
import art.arcane.iris.engine.actuator.IrisDecorantActuator;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedModifier;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDecorationPart;
import art.arcane.iris.engine.object.IrisDecorator;
import art.arcane.iris.engine.object.IrisDimensionCarvingResolver;
import art.arcane.iris.engine.object.IrisProceduralBlocks;
import art.arcane.iris.engine.object.IrisRiverCaves;
import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.common.data.B;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.math.BlockPosition;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.PowerOfTwoCoordinates;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.volmlib.util.matter.slices.MarkerMatter;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.Data;
import art.arcane.iris.spi.PlatformBlockState;

import java.util.Map;

public class IrisCarveModifier extends EngineAssignedModifier<PlatformBlockState> {
    private static final byte LIQUID_FLUID = 1;
    private static final ThreadLocal<IrisCarveScratch> SCRATCH = ThreadLocal.withInitial(IrisCarveScratch::new);
    private static final int CAVE_BIOME_BLEND_RADIUS = 3;
    private static final int CAVE_BIOME_BLEND_CENTER_WEIGHT = 4;
    private static final int CAVE_BIOME_BLEND_TOTAL_WEIGHT = 8;
    private static final int RIVER_BIOME_INHERITANCE_CELL_SIZE = 4;
    private static final long RIVER_BIOME_INHERITANCE_SALT = 0x4CF5AD432745937FL;
    private static final MatterCavern BASIC_CAVERN = new MatterCavern(true, "", (byte) 0);
    private final RNG rng;
    private final PlatformBlockState AIR = B.getState("CAVE_AIR");
    private final PlatformBlockState LAVA = B.getState("LAVA");
    private final IrisDecorantActuator decorant;

    public IrisCarveModifier(Engine engine) {
        super(engine, "Carve");
        rng = new RNG(getEngine().getSeedManager().getCarve());
        decorant = new IrisDecorantActuator(engine);
    }

    @Override
    @ChunkCoordinates
    public void onModify(int x, int z, Hunk<PlatformBlockState> output, boolean multicore, ChunkContext context) {
        PrecisionStopwatch caveStopwatch = PrecisionStopwatch.start();
        Mantle<Matter> mantle = getEngine().getMantle().getMantle();
        IrisDimensionCarvingResolver.State resolverState = new IrisDimensionCarvingResolver.State();
        Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache = new Long2ObjectOpenHashMap<>(2048);
        IrisCarveScratch scratch = SCRATCH.get();
        scratch.reset();
        CarveWallBuffer walls = scratch.walls;
        CarveColumnMask[] columnMasks = scratch.columnMasks;
        CarveColumnMask[] boundaryMasks = scratch.boundaryMasks;
        int[] surfaceHeights = scratch.surfaceHeights;
        Map<String, IrisBiome> customBiomeCache = scratch.customBiomeCache;
        UpperDimensionContext upperCtx = getEngine().getUpperContext();
        boolean protectUpper = upperCtx != null && !getEngine().getDimension().isUpperDimensionCarving();
        int[] upperSurfaceHeights = protectUpper ? scratch.getOrCreateUpperSurfaceHeights() : null;
        int chunkBlockX = PowerOfTwoCoordinates.chunkToBlock(x);
        int chunkBlockZ = PowerOfTwoCoordinates.chunkToBlock(z);
        for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
            int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int localZ = columnIndex & 15;
            surfaceHeights[columnIndex] = context.getRoundedHeight(localX, localZ);
            if (protectUpper) {
                int worldX = localX + chunkBlockX;
                int worldZ = localZ + chunkBlockZ;
                int rawUpper = upperCtx.getUpperSurfaceY(worldX, worldZ);
                int gap = getEngine().getDimension().getUpperDimensionGap();
                upperSurfaceHeights[columnIndex] = Math.max(rawUpper, surfaceHeights[columnIndex] + gap);
            }
        }

        MantleChunk<Matter> mantleChunk = mantle.getChunk(x, z).use();
        try {
            PrecisionStopwatch resolveStopwatch = PrecisionStopwatch.start();
            int worldHeightSpan = getEngine().getWorld().maxHeight() - getEngine().getWorld().minHeight();
            int caveLavaHeight = getEngine().getDimension().getCaveLavaHeight();
            CarveResolutionContext resolutionContext = new CarveResolutionContext(
                    output,
                    context,
                    scratch,
                    columnMasks,
                    upperSurfaceHeights,
                    worldHeightSpan,
                    caveLavaHeight
            );
            CarveResolver carveResolver = new CarveResolver(resolutionContext);
            mantleChunk.iterate(MatterCavern.class, (xx, yy, zz, cavern) -> carveResolver.apply(
                    xx,
                    yy,
                    zz,
                    cavern,
                    dataIfPresent(mantleChunk, xx, yy, zz, RiverCaveHydrology.class)
            ));
            mantleChunk.iterate(RiverCaveHydrology.class, (xx, yy, zz, hydrology) -> {
                if (dataIfPresent(mantleChunk, xx, yy, zz, MatterCavern.class) == null) {
                    carveResolver.apply(xx, yy, zz, null, hydrology);
                }
            });
            if (scratch.customCaveBiomePresent) {
                addInternalWallsFromMantle(mantleChunk, walls, columnMasks);
            } else {
                addInternalWallsFromMasks(walls, columnMasks);
            }
            addCrossChunkBoundaryWalls(mantle, mantleChunk, walls, boundaryMasks, x, z, surfaceHeights);
            getEngine().getMetrics().getCarveResolve().put(resolveStopwatch.getMilliseconds());

            PrecisionStopwatch applyStopwatch = PrecisionStopwatch.start();
            try {
                walls.forEach((rx, yy, rz, cavern, riverBoundary) -> {
                    RiverCaveHydrology hydrology = dataIfPresent(
                            mantleChunk, rx, yy, rz, RiverCaveHydrology.class);
                    if (hydrology != null && hydrology.protectsPlacement()) {
                        return;
                    }
                    int worldX = rx + chunkBlockX;
                    int worldZ = rz + chunkBlockZ;
                    String customBiome = cavern.getCustomBiome();
                    IrisBiome biome = customBiome.isEmpty()
                            ? resolveCaveBiome(caveBiomeCache, worldX, yy, worldZ, resolverState)
                            : resolveCustomBiome(customBiomeCache, customBiome);

                    if (biome != null) {
                        PlatformBlockState data = biome.getWall().get(rng, worldX, yy, worldZ, getData());
                        int columnIndex = PowerOfTwoCoordinates.packLocal16(rx, rz);

                        if (data != null && B.isSolid(output.getRaw(rx, yy, rz)) && yy < surfaceHeights[columnIndex]) {
                            output.setRaw(rx, yy, rz, data);
                        }
                    }
                });

                for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
                    int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
                    int localZ = columnIndex & 15;
                    processColumnFromMask(
                            output,
                            mantleChunk,
                            mantle,
                            columnMasks[columnIndex],
                            columnIndex,
                            x,
                            z,
                            resolverState,
                            caveBiomeCache,
                            customBiomeCache,
                            context.getFluid().get(localX, localZ)
                    );
                }

                for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
                    if (boundaryMasks[columnIndex].isEmpty() || !columnMasks[columnIndex].isEmpty()) {
                        continue;
                    }
                    processBoundaryColumnFromMask(
                            output,
                            mantleChunk,
                            boundaryMasks[columnIndex],
                            walls,
                            columnIndex,
                            x,
                            z,
                            resolverState,
                            caveBiomeCache,
                            customBiomeCache
                    );
                }

                // Surface-break carving must not leave an ore cap suspended across the opening.
                for (int columnIndex = 0; columnIndex < surfaceHeights.length; columnIndex++) {
                    int surfaceY = surfaceHeights[columnIndex];
                    if (surfaceY <= 0 || surfaceY >= output.getHeight()) {
                        continue;
                    }

                    int belowY = surfaceY - 1;
                    if (!columnMasks[columnIndex].contains(belowY) && !boundaryMasks[columnIndex].contains(belowY)) {
                        continue;
                    }

                    int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
                    int localZ = columnIndex & 15;
                    PlatformBlockState surface = output.getRaw(localX, surfaceY, localZ);
                    PlatformBlockState below = output.getRaw(localX, belowY, localZ);
                    if (isUnsupportedSurfaceOre(surface, below)) {
                        output.setRaw(localX, surfaceY, localZ, AIR);
                    }
                }
            } finally {
                getEngine().getMetrics().getCarveApply().put(applyStopwatch.getMilliseconds());
            }
        } finally {
            getEngine().getMetrics().getCave().put(caveStopwatch.getMilliseconds());
            mantleChunk.release();
        }
    }

    static boolean hasExplicitCarveIntent(MatterCavern cavern) {
        return cavern != null && (isFluidIntent(cavern) || cavern.isLava() || cavern.getLiquid() == 3);
    }

    static boolean isFluidIntent(MatterCavern cavern) {
        return cavern != null && cavern.getLiquid() == LIQUID_FLUID;
    }

    static boolean shouldPreserveExistingFluid(MatterCavern cavern, PlatformBlockState current) {
        return B.isFluid(current) && !hasExplicitCarveIntent(cavern);
    }

    static boolean usesDefaultLava(int caveLavaHeight, int y) {
        return y <= caveLavaHeight;
    }

    static boolean isUnsupportedSurfaceOre(PlatformBlockState surface, PlatformBlockState below) {
        return B.isOre(surface) && !B.isSolid(below);
    }

    static PlatformBlockState resolveExplicitCarveState(MatterCavern cavern, PlatformBlockState fluid,
                                                        PlatformBlockState lava, PlatformBlockState air) {
        if (cavern == null) {
            return null;
        }
        if (isFluidIntent(cavern)) {
            return fluid;
        }
        if (cavern.isLava()) {
            return lava;
        }
        return cavern.getLiquid() == 3 ? air : null;
    }

    static MatterCavern composeCavern(MatterCavern baseline, RiverCaveHydrology hydrology) {
        return hydrology == null ? baseline : hydrology.asCavern();
    }

    static PlatformBlockState resolveHydrologyState(
            RiverCaveHydrology hydrology,
            PlatformBlockState current,
            PlatformBlockState fluid,
            PlatformBlockState air
    ) {
        if (hydrology == null) {
            return null;
        }
        return switch (hydrology.action()) {
            case WET_SOURCE -> fluid;
            case FALLING_WATER -> fallingFluidState(fluid);
            case DRY_AIR -> air;
            case SEAL_GUARD -> normalizeWaterlogging(current, null);
        };
    }

    static PlatformBlockState normalizeWaterlogging(PlatformBlockState state, PlatformBlockState resultingFluid) {
        if (state == null || B.isFluid(state) || !IrisProceduralBlocks.hasProperty(state, "waterlogged")) {
            return state;
        }
        String target = resultingFluid != null && resultingFluid.isWater() ? "true" : "false";
        if (target.equals(IrisProceduralBlocks.propertyValue(state, "waterlogged"))) {
            return state;
        }
        return state.withProperty("waterlogged", target);
    }

    static PlatformBlockState normalizeHydrologyWaterlogging(
            PlatformBlockState state,
            MatterCavern baseline,
            RiverCaveHydrology hydrology,
            PlatformBlockState columnFluid
    ) {
        if (hydrology == null) {
            return state;
        }
        MatterCavern composed = composeCavern(baseline, hydrology);
        PlatformBlockState resultingFluid = isFluidIntent(composed) ? columnFluid : null;
        return normalizeWaterlogging(state, resultingFluid);
    }

    private static PlatformBlockState fallingFluidState(PlatformBlockState fluid) {
        if (fluid == null || !IrisProceduralBlocks.hasProperty(fluid, "level")) {
            return fluid;
        }
        if ("8".equals(IrisProceduralBlocks.propertyValue(fluid, "level"))) {
            return fluid;
        }
        return fluid.withProperty("level", "8");
    }

    private final class CarveResolver {
        private final CarveResolutionContext context;

        private CarveResolver(CarveResolutionContext context) {
            this.context = context;
        }

        private void apply(
                int x,
                int y,
                int z,
                MatterCavern baseline,
                RiverCaveHydrology hydrology
        ) {
            if (y >= context.worldHeightSpan() || y <= 0) {
                return;
            }

            int localX = x & 15;
            int localZ = z & 15;
            int columnIndex = PowerOfTwoCoordinates.packLocal16(localX, localZ);
            if (context.upperSurfaceHeights() != null && y >= context.upperSurfaceHeights()[columnIndex]) {
                return;
            }

            PlatformBlockState current = context.output().getRaw(localX, y, localZ);
            if (hydrology != null && hydrology.action() == RiverCaveAction.SEAL_GUARD) {
                PlatformBlockState normalized = resolveHydrologyState(hydrology, current, null, AIR);
                if (normalized != current) {
                    context.output().setRaw(localX, y, localZ, normalized);
                }
                return;
            }

            MatterCavern cavern = composeCavern(baseline, hydrology);
            if (cavern == null || shouldPreserveExistingFluid(cavern, current)) {
                return;
            }

            context.columnMasks()[columnIndex].add(y);
            if (!cavern.getCustomBiome().isEmpty()) {
                context.scratch().customCaveBiomePresent = true;
            }

            boolean explicitCarveIntent = hasExplicitCarveIntent(cavern);
            if (current.isAir() && !explicitCarveIntent) {
                return;
            }

            PlatformBlockState fluid = isFluidIntent(cavern)
                    ? context.chunkContext().getFluid().get(localX, localZ)
                    : null;
            if (hydrology != null) {
                context.output().setRaw(localX, y, localZ,
                        resolveHydrologyState(hydrology, current, fluid, AIR));
                return;
            }
            if (explicitCarveIntent) {
                context.output().setRaw(localX, y, localZ,
                        resolveExplicitCarveState(cavern, fluid, LAVA, AIR));
            } else if (usesDefaultLava(context.caveLavaHeight(), y)) {
                context.output().setRaw(localX, y, localZ, LAVA);
            } else {
                context.output().setRaw(localX, y, localZ, AIR);
            }
        }
    }

    private record CarveResolutionContext(
            Hunk<PlatformBlockState> output,
            ChunkContext chunkContext,
            IrisCarveScratch scratch,
            CarveColumnMask[] columnMasks,
            int[] upperSurfaceHeights,
            int worldHeightSpan,
            int caveLavaHeight
    ) {
    }

    private void addInternalWallsFromMasks(CarveWallBuffer walls, CarveColumnMask[] columnMasks) {
        for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
            CarveColumnMask columnMask = columnMasks[columnIndex];
            if (columnMask.isEmpty()) {
                continue;
            }

            int rx = columnIndex >> 4;
            int rz = columnIndex & 15;
            int yy = columnMask.nextSetBit(0);
            while (yy >= 0) {
                if (rz < 15 && !columnMasks[columnIndex + 1].contains(yy)) {
                    walls.put(rx, yy, rz + 1, BASIC_CAVERN, false);
                }
                if (rx < 15 && !columnMasks[columnIndex + 16].contains(yy)) {
                    walls.put(rx + 1, yy, rz, BASIC_CAVERN, false);
                }
                if (rz > 0 && !columnMasks[columnIndex - 1].contains(yy)) {
                    walls.put(rx, yy, rz - 1, BASIC_CAVERN, false);
                }
                if (rx > 0 && !columnMasks[columnIndex - 16].contains(yy)) {
                    walls.put(rx - 1, yy, rz, BASIC_CAVERN, false);
                }
                yy = columnMask.nextSetBit(yy + 1);
            }
        }
    }

    private void addInternalWallsFromMantle(MantleChunk<Matter> mc, CarveWallBuffer walls, CarveColumnMask[] columnMasks) {
        for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
            CarveColumnMask columnMask = columnMasks[columnIndex];
            if (columnMask.isEmpty()) {
                continue;
            }

            int rx = columnIndex >> 4;
            int rz = columnIndex & 15;
            int yy = columnMask.nextSetBit(0);
            while (yy >= 0) {
                MatterCavern cavern = composedCavernAt(mc, rx, yy, rz);
                if (cavern != null) {
                    RiverCaveHydrology hydrology = dataIfPresent(mc, rx, yy, rz, RiverCaveHydrology.class);
                    boolean riverBoundary = hydrology != null && !hydrology.floodedBiomeKey().isEmpty();
                    if (rz < 15 && composedCavernAt(mc, rx, yy, rz + 1) == null) {
                        walls.put(rx, yy, rz + 1, cavern, riverBoundary);
                    }
                    if (rx < 15 && composedCavernAt(mc, rx + 1, yy, rz) == null) {
                        walls.put(rx + 1, yy, rz, cavern, riverBoundary);
                    }
                    if (rz > 0 && composedCavernAt(mc, rx, yy, rz - 1) == null) {
                        walls.put(rx, yy, rz - 1, cavern, riverBoundary);
                    }
                    if (rx > 0 && composedCavernAt(mc, rx - 1, yy, rz) == null) {
                        walls.put(rx - 1, yy, rz, cavern, riverBoundary);
                    }
                }
                yy = columnMask.nextSetBit(yy + 1);
            }
        }
    }

    private void addCrossChunkBoundaryWalls(
            Mantle<Matter> mantle,
            MantleChunk<Matter> mc,
            CarveWallBuffer walls,
            CarveColumnMask[] boundaryMasks,
            int chunkX,
            int chunkZ,
            int[] surfaceHeights
    ) {
        int baseX = PowerOfTwoCoordinates.chunkToBlock(chunkX);
        int baseZ = PowerOfTwoCoordinates.chunkToBlock(chunkZ);
        int maxSurfaceY = 0;
        for (int index = 0; index < surfaceHeights.length; index++) {
            if (surfaceHeights[index] > maxSurfaceY) {
                maxSurfaceY = surfaceHeights[index];
            }
        }
        int maxY = Math.min(getEngine().getWorld().maxHeight() - getEngine().getWorld().minHeight() - 1, maxSurfaceY + 1);
        if (maxY < 1) {
            return;
        }

        MantleChunk<Matter> west = existingMantleChunk(mantle, chunkX - 1, chunkZ);
        MantleChunk<Matter> east = existingMantleChunk(mantle, chunkX + 1, chunkZ);
        MantleChunk<Matter> north = existingMantleChunk(mantle, chunkX, chunkZ - 1);
        MantleChunk<Matter> south = existingMantleChunk(mantle, chunkX, chunkZ + 1);
        if (west == null && east == null && north == null && south == null) {
            return;
        }

        for (int yy = 1; yy <= maxY; yy++) {
            for (int offset = 0; offset < 16; offset++) {
                if (west != null) {
                    tryAddBoundaryWall(mc, west, walls, boundaryMasks, 0, yy, offset, 15, offset);
                }
                if (east != null) {
                    tryAddBoundaryWall(mc, east, walls, boundaryMasks, 15, yy, offset, 0, offset);
                }
                if (north != null) {
                    tryAddBoundaryWall(mc, north, walls, boundaryMasks, offset, yy, 0, offset, 15);
                }
                if (south != null) {
                    tryAddBoundaryWall(mc, south, walls, boundaryMasks, offset, yy, 15, offset, 0);
                }
            }
        }
    }

    private void tryAddBoundaryWall(
            MantleChunk<Matter> mc,
            MantleChunk<Matter> neighborChunk,
            CarveWallBuffer walls,
            CarveColumnMask[] boundaryMasks,
            int localX,
            int yy,
            int localZ,
            int neighborX,
            int neighborZ
    ) {
        if (composedCavernAt(mc, localX, yy, localZ) != null) {
            return;
        }

        MatterCavern neighbor = composedCavernAt(neighborChunk, neighborX, yy, neighborZ);
        if (neighbor == null) {
            return;
        }

        RiverCaveHydrology hydrology = dataIfPresent(
                neighborChunk, neighborX, yy, neighborZ, RiverCaveHydrology.class);
        boolean riverBoundary = hydrology != null && !hydrology.floodedBiomeKey().isEmpty();
        walls.put(localX, yy, localZ, neighbor, riverBoundary);
        int columnIndex = PowerOfTwoCoordinates.packLocal16(localX, localZ);
        boundaryMasks[columnIndex].add(yy);
    }

    private MantleChunk<Matter> existingMantleChunk(Mantle<Matter> mantle, int chunkX, int chunkZ) {
        TectonicPlate<Matter> plate = mantle.getLoadedRegions().get(Mantle.key(chunkX >> 5, chunkZ >> 5));
        if (plate == null || plate.isClosed()) {
            return null;
        }
        return plate.get(chunkX & 31, chunkZ & 31);
    }

    private MatterCavern composedCavernAt(MantleChunk<Matter> mantleChunk, int x, int y, int z) {
        MatterCavern baseline = dataIfPresent(mantleChunk, x, y, z, MatterCavern.class);
        RiverCaveHydrology hydrology = dataIfPresent(mantleChunk, x, y, z, RiverCaveHydrology.class);
        return composeCavern(baseline, hydrology);
    }

    private static <T> T dataIfPresent(MantleChunk<Matter> mantleChunk, int x, int y, int z, Class<T> type) {
        int section = y >> 4;
        if (y < 0 || !mantleChunk.exists(section)) {
            return null;
        }
        Matter matter = mantleChunk.get(section);
        if (matter == null || !matter.hasSlice(type)) {
            return null;
        }
        return matter.<T>getSlice(type).get(x & 15, y & 15, z & 15);
    }

    private void processColumnFromMask(
            Hunk<PlatformBlockState> output,
            MantleChunk<Matter> mc,
            Mantle<Matter> mantle,
            CarveColumnMask columnMask,
            int columnIndex,
            int chunkX,
            int chunkZ,
            IrisDimensionCarvingResolver.State resolverState,
            Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache,
            Map<String, IrisBiome> customBiomeCache,
            PlatformBlockState columnFluid
    ) {
        if (columnMask == null || columnMask.isEmpty()) {
            return;
        }

        int firstHeight = columnMask.nextSetBit(0);
        if (firstHeight < 0) {
            return;
        }

        int rx = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
        int rz = columnIndex & 15;
        int worldX = rx + PowerOfTwoCoordinates.chunkToBlock(chunkX);
        int worldZ = rz + PowerOfTwoCoordinates.chunkToBlock(chunkZ);
        CaveZone zone = new CaveZone();
        zone.setFloor(firstHeight);
        int buf = firstHeight - 1;
        int y = firstHeight;

        while (y >= 0) {
            if (y <= getEngine().getHeight()) {
                if (y == buf + 1) {
                    buf = y;
                    zone.ceiling = buf;
                } else {
                    if (zone.isValid(getEngine())) {
                        processZone(output, mc, mantle, zone, rx, rz, worldX, worldZ, resolverState,
                                caveBiomeCache, customBiomeCache, columnFluid);
                    }
                    zone = new CaveZone();
                    zone.setFloor(y);
                    buf = y;
                }
            }

            y = columnMask.nextSetBit(y + 1);
        }

        if (zone.isValid(getEngine())) {
            processZone(output, mc, mantle, zone, rx, rz, worldX, worldZ, resolverState,
                    caveBiomeCache, customBiomeCache, columnFluid);
        }
    }

    private void processBoundaryColumnFromMask(
            Hunk<PlatformBlockState> output,
            MantleChunk<Matter> mantleChunk,
            CarveColumnMask boundaryMask,
            CarveWallBuffer walls,
            int columnIndex,
            int chunkX,
            int chunkZ,
            IrisDimensionCarvingResolver.State resolverState,
            Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache,
            Map<String, IrisBiome> customBiomeCache
    ) {
        int firstHeight = boundaryMask.nextSetBit(0);
        if (firstHeight < 0) {
            return;
        }

        int rx = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
        int rz = columnIndex & 15;
        int worldX = rx + PowerOfTwoCoordinates.chunkToBlock(chunkX);
        int worldZ = rz + PowerOfTwoCoordinates.chunkToBlock(chunkZ);
        int zoneFloor = firstHeight;
        int zoneCeiling = firstHeight;
        int y = boundaryMask.nextSetBit(firstHeight + 1);

        while (y >= 0) {
            if (y == zoneCeiling + 1) {
                zoneCeiling = y;
            } else {
                paintBoundaryZone(output, mantleChunk, walls, rx, rz, worldX, worldZ, zoneFloor, zoneCeiling,
                        resolverState, caveBiomeCache, customBiomeCache);
                zoneFloor = y;
                zoneCeiling = y;
            }
            y = boundaryMask.nextSetBit(y + 1);
        }

        paintBoundaryZone(output, mantleChunk, walls, rx, rz, worldX, worldZ, zoneFloor, zoneCeiling,
                resolverState, caveBiomeCache, customBiomeCache);
    }

    private void paintBoundaryZone(
            Hunk<PlatformBlockState> output,
            MantleChunk<Matter> mantleChunk,
            CarveWallBuffer walls,
            int rx,
            int rz,
            int worldX,
            int worldZ,
            int zoneFloor,
            int zoneCeiling,
            IrisDimensionCarvingResolver.State resolverState,
            Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache,
            Map<String, IrisBiome> customBiomeCache
    ) {
        IrisBiome floorBiome = resolveCaveBoundaryBiome(
                walls.get(rx, zoneFloor, rz), worldX, zoneFloor, worldZ,
                resolverState, caveBiomeCache, customBiomeCache,
                walls.isRiverBoundary(rx, zoneFloor, rz));
        IrisBiome ceilingBiome = resolveCaveBoundaryBiome(
                walls.get(rx, zoneCeiling, rz), worldX, zoneCeiling, worldZ,
                resolverState, caveBiomeCache, customBiomeCache,
                walls.isRiverBoundary(rx, zoneCeiling, rz));
        if (floorBiome == null && ceilingBiome == null) {
            return;
        }

        if (floorBiome != null) {
            KList<PlatformBlockState> floorLayers = floorBiome.generateLayers(
                    getDimension(), worldX, worldZ, rng, 3, zoneFloor, getData(), getComplex());
            for (int i = 0; i < zoneFloor - 1; i++) {
                if (!floorLayers.hasIndex(i)) {
                    break;
                }
                int floorY = zoneFloor - i - 1;
                if (floorY < 0) {
                    break;
                }
                RiverCaveHydrology hydrology = dataIfPresent(
                        mantleChunk, rx, floorY, rz, RiverCaveHydrology.class);
                if (hydrology != null
                        && hydrology.protectsPlacement()
                        && hydrology.action() != RiverCaveAction.SEAL_GUARD) {
                    continue;
                }
                PlatformBlockState existing = output.getRaw(rx, floorY, rz);
                PlatformBlockState layer = floorLayers.get(i);
                if (!B.isSolid(existing)
                        || !canReplaceRiverGuard(hydrology, layer, false)
                        || !canReplaceCaveFloorLayer(output, rx, floorY, rz, layer)) {
                    continue;
                }
                if (B.isOre(existing)) {
                    output.setRaw(rx, floorY, rz, B.toDeepSlateOre(existing, layer));
                    continue;
                }
                output.setRaw(rx, floorY, rz, layer);
            }
        }

        if (ceilingBiome != null) {
            int worldMaxY = getEngine().getWorld().maxHeight() - getEngine().getWorld().minHeight();
            KList<PlatformBlockState> ceilingLayers = ceilingBiome.generateCeilingLayers(
                    getDimension(), worldX, worldZ, rng, 3, zoneCeiling, getData(), getComplex());
            for (int i = 0; i < ceilingLayers.size(); i++) {
                int ceilingY = zoneCeiling + i + 1;
                if (ceilingY >= worldMaxY) {
                    break;
                }
                RiverCaveHydrology hydrology = dataIfPresent(
                        mantleChunk, rx, ceilingY, rz, RiverCaveHydrology.class);
                if (hydrology != null
                        && hydrology.protectsPlacement()
                        && hydrology.action() != RiverCaveAction.SEAL_GUARD) {
                    continue;
                }
                PlatformBlockState existing = output.getRaw(rx, ceilingY, rz);
                if (!B.isSolid(existing)) {
                    continue;
                }
                PlatformBlockState layer = ceilingLayers.get(i);
                if (!canReplaceRiverGuard(hydrology, layer, true)) {
                    continue;
                }
                if (B.isOre(existing)) {
                    output.setRaw(rx, ceilingY, rz, B.toDeepSlateOre(existing, layer));
                    continue;
                }
                output.setRaw(rx, ceilingY, rz, layer);
            }
        }
    }

    /**
     * 1-in-16 marker roll from a SplitMix64 finalizer over (carve seed, block position, salt).
     * Deterministic per seed and position, thread-order independent, allocation free.
     */
    private boolean markerRoll(int x, int y, int z, long salt) {
        long h = (getEngine().getSeedManager().getCarve() + salt) ^ BlockPosition.toLong(x, y, z);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h & 15L) == 0L;
    }

    private void processZone(Hunk<PlatformBlockState> output, MantleChunk<Matter> mc, Mantle<Matter> mantle,
                             CaveZone zone, int rx, int rz, int xx, int zz,
                             IrisDimensionCarvingResolver.State resolverState,
                             Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache,
                             Map<String, IrisBiome> customBiomeCache,
                             PlatformBlockState columnFluid) {
        int maxY = output.getHeight();

        if (zone.ceiling + 1 < maxY && B.isDecorant(output.getRaw(rx, zone.ceiling + 1, rz))) {
            output.setRaw(rx, zone.ceiling + 1, rz, AIR);
        }

        if (B.isDecorant(output.getRaw(rx, zone.ceiling, rz))) {
            output.setRaw(rx, zone.ceiling, rz, AIR);
        }

        // Seed-derived and position-keyed, never Math.random(): these markers persist into the
        // mantle and drive cave spawning, so the same seed must stamp the same markers on
        // every run and every platform. Inline mix, no allocation on this hot path.
        if (markerRoll(xx, zone.ceiling, zz, 0x9E3779B97F4A7C15L)) {
            mantle.set(xx, zone.ceiling, zz, MarkerMatter.CAVE_CEILING);
        }

        if (markerRoll(xx, zone.floor, zz, 0xC2B2AE3D27D4EB4FL)) {
            mantle.set(xx, zone.floor, zz, MarkerMatter.CAVE_FLOOR);
        }

        IrisBiome floorBiome = resolveCaveBoundaryBiome(mc, rx, zone.floor, rz, xx, zz, resolverState, caveBiomeCache, customBiomeCache);
        IrisBiome ceilingBiome = resolveCaveBoundaryBiome(mc, rx, zone.ceiling, rz, xx, zz, resolverState, caveBiomeCache, customBiomeCache);
        if (floorBiome == null && ceilingBiome == null) {
            normalizeCaveZoneWaterlogging(output, mc, zone, rx, rz, columnFluid);
            return;
        }

        if (floorBiome != null) {
            KList<PlatformBlockState> floorBlocks = floorBiome.generateLayers(getDimension(), xx, zz, rng, 3, zone.floor, getData(), getComplex());
            for (int i = 0; i < zone.floor - 1; i++) {
                if (!floorBlocks.hasIndex(i)) {
                    break;
                }
                int y = zone.floor - i - 1;
                RiverCaveHydrology hydrology = dataIfPresent(mc, rx, y, rz, RiverCaveHydrology.class);
                if (hydrology != null
                        && hydrology.protectsPlacement()
                        && hydrology.action() != RiverCaveAction.SEAL_GUARD) {
                    continue;
                }
                PlatformBlockState block = floorBlocks.get(i);
                PlatformBlockState existing = output.getRaw(rx, y, rz);
                if (!B.isSolid(existing)
                        || !canReplaceRiverGuard(hydrology, block, false)
                        || !canReplaceCaveFloorLayer(output, rx, y, rz, block)) {
                    continue;
                }
                if (B.isOre(existing)) {
                    output.setRaw(rx, y, rz, B.toDeepSlateOre(existing, block));
                    continue;
                }
                output.setRaw(rx, y, rz, block);
            }
        }

        if (ceilingBiome != null) {
            KList<PlatformBlockState> ceilingBlocks = ceilingBiome.generateCeilingLayers(getDimension(), xx, zz, rng, 3, zone.ceiling, getData(), getComplex());
            for (int i = 0; i < ceilingBlocks.size(); i++) {
                int cy = zone.ceiling + i + 1;
                if (cy >= maxY) {
                    break;
                }
                RiverCaveHydrology hydrology = dataIfPresent(mc, rx, cy, rz, RiverCaveHydrology.class);
                if (hydrology != null
                        && hydrology.protectsPlacement()
                        && hydrology.action() != RiverCaveAction.SEAL_GUARD) {
                    continue;
                }
                PlatformBlockState block = ceilingBlocks.get(i);
                PlatformBlockState existing = output.getRaw(rx, cy, rz);
                if (!B.isSolid(existing) || !canReplaceRiverGuard(hydrology, block, true)) {
                    continue;
                }
                if (B.isOre(existing)) {
                    output.setRaw(rx, cy, rz, B.toDeepSlateOre(existing, block));
                    continue;
                }
                output.setRaw(rx, cy, rz, block);
            }
        }

        IrisDecorator[] surfaceDecorators = floorBiome == null
                ? new IrisDecorator[0]
                : floorBiome.getDecoratorBucket(IrisDecorationPart.NONE);
        if (surfaceDecorators.length > 0 && hasStableCaveFloorSupport(output, rx, zone.getFloor(), rz)) {
            decorant.getSurfaceDecorator().decorate(rx, rz, xx, xx, xx, zz, zz, zz, output, floorBiome, InferredType.CAVE, zone.getFloor() - 1, zone.airThickness());
        }

        IrisDecorator[] ceilingDecorators = ceilingBiome == null
                ? new IrisDecorator[0]
                : ceilingBiome.getDecoratorBucket(IrisDecorationPart.CEILING);
        if (ceilingDecorators.length > 0 && zone.getCeiling() + 1 < maxY && B.isSolid(output.getRaw(rx, zone.getCeiling() + 1, rz))) {
            decorant.getCeilingDecorator().decorate(rx, rz, xx, xx, xx, zz, zz, zz, output, ceilingBiome, InferredType.CAVE, zone.getCeiling(), zone.airThickness());
        }

        normalizeCaveZoneWaterlogging(output, mc, zone, rx, rz, columnFluid);
    }

    private void normalizeCaveZoneWaterlogging(
            Hunk<PlatformBlockState> output,
            MantleChunk<Matter> mantleChunk,
            CaveZone zone,
            int localX,
            int localZ,
            PlatformBlockState columnFluid
    ) {
        int minimumY = Math.max(0, zone.floor - 1);
        int maximumY = Math.min(output.getHeight() - 1, zone.ceiling + 1);
        for (int y = minimumY; y <= maximumY; y++) {
            RiverCaveHydrology hydrology = dataIfPresent(
                    mantleChunk, localX, y, localZ, RiverCaveHydrology.class);
            if (hydrology == null) {
                continue;
            }
            MatterCavern baseline = dataIfPresent(
                    mantleChunk, localX, y, localZ, MatterCavern.class);
            PlatformBlockState current = output.getRaw(localX, y, localZ);
            PlatformBlockState normalized = normalizeHydrologyWaterlogging(
                    current,
                    baseline,
                    hydrology,
                    columnFluid
            );
            if (normalized != current) {
                output.setRaw(localX, y, localZ, normalized);
            }
        }
    }

    IrisBiome resolveCaveBoundaryBiome(MantleChunk<Matter> mantleChunk, int x, int y, int z, int worldX, int worldZ, IrisDimensionCarvingResolver.State resolverState, Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache, Map<String, IrisBiome> customBiomeCache) {
        MatterCavern cavern = composedCavernAt(mantleChunk, x, y, z);
        RiverCaveHydrology hydrology = dataIfPresent(
                mantleChunk, x, y, z, RiverCaveHydrology.class);
        return resolveCaveBoundaryBiome(
                cavern, worldX, y, worldZ, resolverState, caveBiomeCache, customBiomeCache,
                hydrology != null && !hydrology.floodedBiomeKey().isEmpty());
    }

    IrisBiome resolveCaveBoundaryBiome(MatterCavern cavern, int worldX, int y, int worldZ, IrisDimensionCarvingResolver.State resolverState, Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache, Map<String, IrisBiome> customBiomeCache) {
        return resolveCaveBoundaryBiome(
                cavern, worldX, y, worldZ, resolverState, caveBiomeCache, customBiomeCache, false);
    }

    private IrisBiome resolveCaveBoundaryBiome(MatterCavern cavern, int worldX, int y, int worldZ, IrisDimensionCarvingResolver.State resolverState, Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache, Map<String, IrisBiome> customBiomeCache, boolean riverBoundary) {
        if (cavern != null && !cavern.getCustomBiome().isEmpty()) {
            if (riverBoundary && selectsParentRiverBiome(
                    getEngine().getSeedManager().getCarve(),
                    worldX,
                    worldZ,
                    riverParentBiomeInheritance())) {
                IrisBiome parent = resolveRiverParentBiome(
                        caveBiomeCache, worldX, y, worldZ, resolverState);
                if (parent != null) {
                    return parent;
                }
            }
            return resolveCustomBiome(customBiomeCache, cavern.getCustomBiome());
        }
        return resolveCaveBiome(caveBiomeCache, worldX, y, worldZ, resolverState);
    }

    static boolean selectsParentRiverBiome(long seed, int worldX, int worldZ, double inheritance) {
        if (inheritance <= 0D) {
            return false;
        }
        if (inheritance >= 1D) {
            return true;
        }
        int cellX = Math.floorDiv(worldX, RIVER_BIOME_INHERITANCE_CELL_SIZE);
        int cellZ = Math.floorDiv(worldZ, RIVER_BIOME_INHERITANCE_CELL_SIZE);
        long hash = (seed + RIVER_BIOME_INHERITANCE_SALT) ^ BlockPosition.toLong(cellX, 0, cellZ);
        hash = (hash ^ (hash >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        double roll = (hash >>> 11) * 0x1.0p-53;
        return roll < inheritance;
    }

    static boolean canReplaceRiverGuard(
            RiverCaveHydrology hydrology,
            PlatformBlockState layer,
            boolean ceiling
    ) {
        if (hydrology == null || hydrology.action() != RiverCaveAction.SEAL_GUARD) {
            return true;
        }
        return layer != null
                && B.isSolid(layer)
                && !B.isFluid(layer)
                && (!ceiling || !isGravityAffected(layer));
    }

    private double riverParentBiomeInheritance() {
        if (getDimension().getRivers() == null || getDimension().getRivers().getCaves() == null) {
            return 0D;
        }
        IrisRiverCaves caves = getDimension().getRivers().getCaves();
        return Math.max(0D, Math.min(1D, caves.getParentBiomeInheritance()));
    }

    private IrisBiome resolveRiverParentBiome(
            Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache,
            int worldX,
            int y,
            int worldZ,
            IrisDimensionCarvingResolver.State resolverState
    ) {
        IrisBiome parent = resolveCaveBiome(caveBiomeCache, worldX, y, worldZ, resolverState);
        if (parent != null && parent == getEngine().getSurfaceBiome(worldX, worldZ)) {
            IrisBiome natural = getComplex().getNaturalTrueBiomeStream().get(worldX, worldZ);
            return natural == null ? parent : natural;
        }
        return parent;
    }

    static boolean canReplaceCaveFloorLayer(Hunk<PlatformBlockState> output, int x, int y, int z, PlatformBlockState layer) {
        return !isGravityAffected(layer) || y > 0 && B.isSolid(output.getRaw(x, y - 1, z));
    }

    static boolean hasStableCaveFloorSupport(Hunk<PlatformBlockState> output, int x, int floorY, int z) {
        if (floorY <= 0) {
            return false;
        }
        PlatformBlockState support = output.getRaw(x, floorY - 1, z);
        if (!B.isSolid(support)) {
            return false;
        }
        return !isGravityAffected(support) || floorY > 1 && B.isSolid(output.getRaw(x, floorY - 2, z));
    }

    static boolean isGravityAffected(PlatformBlockState state) {
        if (state == null) {
            return false;
        }
        String key = IrisProceduralBlocks.materialKey(state);
        return key.equals("minecraft:sand")
                || key.equals("minecraft:red_sand")
                || key.equals("minecraft:gravel")
                || key.equals("minecraft:suspicious_sand")
                || key.equals("minecraft:suspicious_gravel")
                || key.endsWith("_concrete_powder");
    }

    private IrisBiome resolveCaveBiome(Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache, int x, int y, int z, IrisDimensionCarvingResolver.State resolverState) {
        IrisBiome center = sampleCaveBiome(caveBiomeCache, x, y, z, resolverState);
        if (center == null) {
            return null;
        }

        IrisBiome xPos = sampleCaveBiome(caveBiomeCache, x + CAVE_BIOME_BLEND_RADIUS, y, z, resolverState);
        IrisBiome xNeg = sampleCaveBiome(caveBiomeCache, x - CAVE_BIOME_BLEND_RADIUS, y, z, resolverState);
        IrisBiome zPos = sampleCaveBiome(caveBiomeCache, x, y, z + CAVE_BIOME_BLEND_RADIUS, resolverState);
        IrisBiome zNeg = sampleCaveBiome(caveBiomeCache, x, y, z - CAVE_BIOME_BLEND_RADIUS, resolverState);

        if (xPos == center && xNeg == center && zPos == center && zNeg == center) {
            return center;
        }

        int roll = Math.floorMod(rng.nextParallelRNG(BlockPosition.toLong(x, y, z)).nextInt(), CAVE_BIOME_BLEND_TOTAL_WEIGHT);
        if (roll < CAVE_BIOME_BLEND_CENTER_WEIGHT) {
            return center;
        }
        roll -= CAVE_BIOME_BLEND_CENTER_WEIGHT;
        if (roll == 0) {
            return xPos != null ? xPos : center;
        }
        if (roll == 1) {
            return xNeg != null ? xNeg : center;
        }
        if (roll == 2) {
            return zPos != null ? zPos : center;
        }
        return zNeg != null ? zNeg : center;
    }

    private IrisBiome sampleCaveBiome(Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache, int x, int y, int z, IrisDimensionCarvingResolver.State resolverState) {
        long key = BlockPosition.toLong(x, y, z);
        IrisBiome cachedBiome = caveBiomeCache.get(key);
        if (cachedBiome != null) {
            return cachedBiome;
        }

        IrisBiome resolvedBiome = getEngine().getCaveBiome(x, y, z, resolverState);
        if (resolvedBiome != null) {
            caveBiomeCache.put(key, resolvedBiome);
        }
        return resolvedBiome;
    }

    private IrisBiome resolveCustomBiome(Map<String, IrisBiome> customBiomeCache, String customBiome) {
        if (customBiomeCache.containsKey(customBiome)) {
            return customBiomeCache.get(customBiome);
        }

        IrisBiome loaded = getEngine().getData().getBiomeLoader().load(customBiome);
        customBiomeCache.put(customBiome, loaded);
        return loaded;
    }

    @Data
    public static class CaveZone {
        private int ceiling = -1;
        private int floor = -1;

        public int airThickness() {
            return (ceiling - floor) - 1;
        }

        public boolean isValid(Engine engine) {
            return floor < ceiling && ceiling - floor >= 1 && floor >= 0 && ceiling <= engine.getHeight() && airThickness() > 0;
        }

        public String toString() {
            return floor + "-" + ceiling;
        }
    }
}

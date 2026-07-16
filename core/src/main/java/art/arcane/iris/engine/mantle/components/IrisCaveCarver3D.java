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

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.IrisCaveFieldModule;
import art.arcane.iris.engine.object.IrisCaveProfile;
import art.arcane.iris.engine.object.IrisRange;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.math.PowerOfTwoCoordinates;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.volmlib.util.matter.MatterSlice;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class IrisCaveCarver3D {
    private static final byte LIQUID_AIR = 0;
    private static final byte LIQUID_WATER = 1;
    private static final byte LIQUID_LAVA = 2;
    private static final byte LIQUID_FORCED_AIR = 3;
    private static final int ADAPTIVE_MIN_PLANE_COLUMNS = 16;
    private static final int ADAPTIVE_DEEP_SAMPLE_STEP = 8;
    private static final int ADAPTIVE_DEEP_SURFACE_MARGIN = 12;
    private static final double ADAPTIVE_LOCAL_RANGE_SCALE = 0.125D;
    private static final double ADAPTIVE_DEEP_MARGIN_BOOST = 0.015D;

    private final Engine engine;
    private final IrisData data;
    private final IrisCaveProfile profile;
    private final CNG baseDensity;
    private final CNG detailDensity;
    private final CNG warpDensity;
    private final CNG surfaceBreakDensity;
    private final RNG thresholdRng;
    private final ModuleState[] modules;
    private final double inverseNormalization;
    private final MatterCavern carveAir;
    private final MatterCavern carveWater;
    private final MatterCavern carveLava;
    private final MatterCavern carveForcedAir;
    private final double normalizationFactor;
    private final double baseWeight;
    private final double detailWeight;
    private final double detailMinContribution;
    private final double detailMaxContribution;
    private final double warpStrength;
    private final boolean hasWarp;
    private final boolean hasModules;
    private final int warpResolution;
    private final ThreadLocal<Scratch> scratchCache = ThreadLocal.withInitial(Scratch::new);

    public IrisCaveCarver3D(Engine engine, IrisCaveProfile profile) {
        this.engine = engine;
        this.data = engine.getData();
        this.profile = profile;
        this.carveAir = new MatterCavern(true, "", LIQUID_AIR);
        this.carveWater = new MatterCavern(true, "", LIQUID_WATER);
        this.carveLava = new MatterCavern(true, "", LIQUID_LAVA);
        this.carveForcedAir = new MatterCavern(true, "", LIQUID_FORCED_AIR);
        List<ModuleState> moduleStates = new ArrayList<>();

        RNG baseRng = new RNG(engine.getSeedManager().getCarve());
        this.baseDensity = profile.getBaseDensityStyle().create(baseRng.nextParallelRNG(934_447), data);
        this.detailDensity = profile.getDetailDensityStyle().create(baseRng.nextParallelRNG(612_991), data);
        this.warpDensity = profile.getWarpStyle().create(baseRng.nextParallelRNG(770_713), data);
        this.surfaceBreakDensity = profile.getSurfaceBreakStyle().create(baseRng.nextParallelRNG(341_219), data);
        this.thresholdRng = baseRng.nextParallelRNG(489_112);
        this.baseWeight = profile.getBaseWeight();
        this.detailWeight = profile.getDetailWeight();
        this.warpStrength = profile.getWarpStrength();
        this.hasWarp = this.warpStrength > 0D;
        this.warpResolution = 2;

        double weight = Math.abs(baseWeight) + Math.abs(detailWeight);
        int index = 0;
        for (IrisCaveFieldModule module : profile.getModules()) {
            CNG moduleDensity = module.getStyle().create(baseRng.nextParallelRNG(1_000_003L + (index * 65_537L)), data);
            ModuleState state = new ModuleState(module, moduleDensity);
            moduleStates.add(state);
            weight += Math.abs(state.weight);
            index++;
        }

        this.modules = moduleStates.toArray(new ModuleState[0]);
        double normalization = weight <= 0 ? 1 : weight;
        normalizationFactor = normalization;
        inverseNormalization = 1D / normalization;
        hasModules = modules.length > 0;
        detailMinContribution = -detailWeight;
        detailMaxContribution = detailWeight;
    }

    public int carve(MantleWriter writer, int chunkX, int chunkZ) {
        Scratch scratch = scratchCache.get();
        if (!scratch.fullWeightsInitialized) {
            Arrays.fill(scratch.fullWeights, 1D);
            scratch.fullWeightsInitialized = true;
        }
        return carve(writer, chunkX, chunkZ, scratch.fullWeights, 0D, 0D, null, null);
    }

    public int carve(
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            double[] columnWeights,
            double minWeight,
            double thresholdPenalty
    ) {
        return carve(writer, chunkX, chunkZ, columnWeights, minWeight, thresholdPenalty, null, null);
    }

    public int carve(
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            double[] columnWeights,
            double minWeight,
            double thresholdPenalty,
            IrisRange worldYRange
    ) {
        return carve(writer, chunkX, chunkZ, columnWeights, minWeight, thresholdPenalty, worldYRange, null);
    }

    public int carve(
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            double[] columnWeights,
            double minWeight,
            double thresholdPenalty,
            IrisRange worldYRange,
            int[] precomputedSurfaceHeights
    ) {
        return carve(writer, chunkX, chunkZ, columnWeights, minWeight, thresholdPenalty, worldYRange, precomputedSurfaceHeights, null);
    }

    public int carve(
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            double[] columnWeights,
            double minWeight,
            double thresholdPenalty,
            IrisRange worldYRange,
            int[] precomputedSurfaceHeights,
            IrisRange overrideVerticalRange
    ) {
        WaterSupportPlan waterSupportPlan = new WaterSupportPlan();
        int carved = carve(writer, chunkX, chunkZ, columnWeights, minWeight, thresholdPenalty,
                worldYRange, precomputedSurfaceHeights, overrideVerticalRange, waterSupportPlan);
        waterSupportPlan.resolve(writer.acquireChunk(chunkX, chunkZ));
        return carved;
    }

    int carve(
            MantleWriter writer,
            int chunkX,
            int chunkZ,
            double[] columnWeights,
            double minWeight,
            double thresholdPenalty,
            IrisRange worldYRange,
            int[] precomputedSurfaceHeights,
            IrisRange overrideVerticalRange,
            WaterSupportPlan waterSupportPlan
    ) {
        PrecisionStopwatch applyStopwatch = PrecisionStopwatch.start();
        try {
            Scratch scratch = scratchCache.get();
            if (columnWeights == null || columnWeights.length < 256) {
                if (!scratch.fullWeightsInitialized) {
                    Arrays.fill(scratch.fullWeights, 1D);
                    scratch.fullWeightsInitialized = true;
                }
                columnWeights = scratch.fullWeights;
            }

            double resolvedMinWeight = Math.max(0D, Math.min(1D, minWeight));
            double resolvedThresholdPenalty = Math.max(0D, thresholdPenalty);
            int worldHeight = writer.getMantle().getWorldHeight();
            IrisRange effectiveVerticalRange = overrideVerticalRange != null ? overrideVerticalRange : profile.getVerticalRange();
            int minY = Math.max(0, (int) Math.floor(effectiveVerticalRange.getMin()));
            int maxY = Math.min(worldHeight - 1, (int) Math.ceil(effectiveVerticalRange.getMax()));
            if (worldYRange != null) {
                int worldMinHeight = engine.getWorld().minHeight();
                int rangeMinY = (int) Math.floor(worldYRange.getMin() - worldMinHeight);
                int rangeMaxY = (int) Math.ceil(worldYRange.getMax() - worldMinHeight);
                minY = Math.max(minY, rangeMinY);
                maxY = Math.min(maxY, rangeMaxY);
            }
            int sampleStep = Math.max(1, profile.getSampleStep());
            boolean exactSampling = sampleStep <= 2;
            boolean adaptiveSampling = exactSampling && profile.isAdaptiveSampling();
            int adaptiveSampleStep = Math.max(2, profile.getAdaptiveSampleStep());
            double adaptiveThresholdMargin = Math.max(0D, profile.getAdaptiveThresholdMargin());
            int surfaceClearance = Math.max(0, profile.getSurfaceClearance());
            int surfaceBreakDepth = Math.max(0, profile.getSurfaceBreakDepth());
            double surfaceBreakNoiseThreshold = profile.getSurfaceBreakNoiseThreshold();
            double surfaceBreakThresholdBoost = Math.max(0, profile.getSurfaceBreakThresholdBoost());
            boolean allowSurfaceBreak = profile.isAllowSurfaceBreak();
            if (maxY < minY) {
                return 0;
            }

            MantleChunk<Matter> chunk = writer.acquireChunk(chunkX, chunkZ);
            if (chunk == null) {
                return 0;
            }

            int x0 = PowerOfTwoCoordinates.chunkToBlock(chunkX);
            int z0 = PowerOfTwoCoordinates.chunkToBlock(chunkZ);
            int[] columnMaxY = scratch.columnMaxY;
            int[] waterMaxY = scratch.waterMaxY;
            int[] surfaceBreakFloorY = scratch.surfaceBreakFloorY;
            boolean[] surfaceBreakColumn = scratch.surfaceBreakColumn;
            double[] columnThreshold = scratch.columnThreshold;
            double[] clampedWeights = scratch.clampedColumnWeights;
            double[] verticalEdgeFade = prepareVerticalEdgeFadeTable(scratch, minY, maxY);
            MatterCavern[] matterByY = prepareMatterByYTable(scratch, minY, maxY);
            prepareSectionCaches(scratch, minY, maxY);

            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx;
                for (int lz = 0; lz < 16; lz++) {
                    int z = z0 + lz;
                    int index = PowerOfTwoCoordinates.packLocal16(lx, lz);
                    int columnSurfaceY;
                    if (precomputedSurfaceHeights != null && precomputedSurfaceHeights.length > index) {
                        columnSurfaceY = precomputedSurfaceHeights[index];
                    } else {
                        columnSurfaceY = engine.getHeight(x, z);
                    }
                    int clearanceTopY = Math.min(maxY, Math.max(minY, columnSurfaceY - surfaceClearance));
                    boolean breakColumn = allowSurfaceBreak
                            && surfaceBreakDensity.noiseFastSigned2D(x, z) >= surfaceBreakNoiseThreshold;
                    int columnTopY = breakColumn
                            ? Math.min(maxY, Math.max(minY, columnSurfaceY))
                            : clearanceTopY;

                    columnMaxY[index] = columnTopY;
                    waterMaxY[index] = resolveWaterMaxY(columnSurfaceY);
                    surfaceBreakFloorY[index] = Math.max(minY, columnSurfaceY - surfaceBreakDepth);
                    surfaceBreakColumn[index] = breakColumn;
                    columnThreshold[index] = profile.getDensityThreshold().get(thresholdRng, x, z, data) - profile.getThresholdBias();
                    clampedWeights[index] = clampColumnWeight(columnWeights[index]);
                }
            }

            int carved;
            if (exactSampling) {
                if (adaptiveSampling) {
                    carved = carvePassAdaptive(
                            chunk,
                            x0,
                            z0,
                            minY,
                            maxY,
                            adaptiveSampleStep,
                            adaptiveThresholdMargin,
                            surfaceBreakThresholdBoost,
                            columnMaxY,
                            waterMaxY,
                            surfaceBreakFloorY,
                            surfaceBreakColumn,
                            columnThreshold,
                            clampedWeights,
                            verticalEdgeFade,
                            matterByY,
                            profile.isWaterRequiresFloor() ? waterSupportPlan : null,
                            resolvedMinWeight,
                            resolvedThresholdPenalty,
                            0D,
                            false
                    );
                } else {
                    carved = carvePassExact(
                            chunk,
                            x0,
                            z0,
                            minY,
                            maxY,
                            surfaceBreakThresholdBoost,
                            columnMaxY,
                            waterMaxY,
                            surfaceBreakFloorY,
                            surfaceBreakColumn,
                            columnThreshold,
                            clampedWeights,
                            verticalEdgeFade,
                            matterByY,
                            profile.isWaterRequiresFloor() ? waterSupportPlan : null,
                            resolvedMinWeight,
                            resolvedThresholdPenalty,
                            0D,
                            false
                    );
                }
            } else {
                int latticeStep = sampleStep;
                carved = carvePassLattice(
                        chunk,
                        x0,
                        z0,
                        minY,
                        maxY,
                        latticeStep,
                        surfaceBreakThresholdBoost,
                        columnMaxY,
                        waterMaxY,
                        surfaceBreakFloorY,
                        surfaceBreakColumn,
                        columnThreshold,
                        clampedWeights,
                        verticalEdgeFade,
                        matterByY,
                        profile.isWaterRequiresFloor() ? waterSupportPlan : null,
                        resolvedMinWeight,
                        resolvedThresholdPenalty,
                        0D,
                        false
                );
                if (carved == 0 && hasFallbackCandidates(columnMaxY, clampedWeights, minY, resolvedMinWeight)) {
                    carved += carvePassFallback(
                            chunk,
                            x0,
                            z0,
                            minY,
                            maxY,
                            sampleStep,
                            surfaceBreakThresholdBoost,
                            columnMaxY,
                            waterMaxY,
                            surfaceBreakFloorY,
                            surfaceBreakColumn,
                            columnThreshold,
                            clampedWeights,
                            verticalEdgeFade,
                            matterByY,
                            profile.isWaterRequiresFloor() ? waterSupportPlan : null,
                            resolvedMinWeight,
                            resolvedThresholdPenalty,
                            0D,
                            false
                    );
                }
            }

            return carved;
        } finally {
            engine.getMetrics().getCarveApply().put(applyStopwatch.getMilliseconds());
        }
    }

    private int carvePassExact(
            MantleChunk<Matter> chunk,
            int x0,
            int z0,
            int minY,
            int maxY,
            double surfaceBreakThresholdBoost,
            int[] columnMaxY,
            int[] waterMaxY,
            int[] surfaceBreakFloorY,
            boolean[] surfaceBreakColumn,
            double[] columnThreshold,
            double[] clampedWeights,
            double[] verticalEdgeFade,
            MatterCavern[] matterByY,
            WaterSupportPlan waterSupportPlan,
            double minWeight,
            double thresholdPenalty,
            double thresholdBoost,
            boolean skipExistingCarved
    ) {
        int carved = 0;
        Scratch scratch = scratchCache.get();
        double[] passThreshold = scratch.passThreshold;
        int[] activeColumnIndices = scratch.activeColumnIndices;
        int[] activeColumnTopY = scratch.activeColumnTopY;
        int activeColumnCount = 0;

        for (int index = 0; index < 256; index++) {
            double columnWeight = clampedWeights[index];
            if (columnWeight <= minWeight || columnMaxY[index] < minY) {
                passThreshold[index] = Double.NaN;
                continue;
            }

            passThreshold[index] = columnThreshold[index] + thresholdBoost - ((1D - columnWeight) * thresholdPenalty);
            activeColumnIndices[activeColumnCount] = index;
            activeColumnTopY[activeColumnCount] = columnMaxY[index];
            activeColumnCount++;
        }

        if (activeColumnCount == 0) {
            return 0;
        }

        int[] planeColumnIndices = scratch.planeColumnIndices;
        double[] planeThresholdLimit = scratch.planeThresholdLimit;
        boolean[] planeCarve = scratch.planeCarve;
        int minSection = PowerOfTwoCoordinates.floorDivPow2(minY, 4);
        int maxSection = PowerOfTwoCoordinates.floorDivPow2(maxY, 4);

        for (int sectionIndex = minSection; sectionIndex <= maxSection; sectionIndex++) {
            int sectionMinY = Math.max(minY, PowerOfTwoCoordinates.chunkToBlock(sectionIndex));
            int sectionMaxY = Math.min(maxY, PowerOfTwoCoordinates.chunkToBlock(sectionIndex) + 15);
            MatterSlice<MatterCavern> cavernSlice = resolveCavernSlice(scratch, chunk, sectionIndex);

            for (int y = sectionMinY; y <= sectionMaxY; y++) {
                int planeCount = 0;
                for (int activeIndex = 0; activeIndex < activeColumnCount; activeIndex++) {
                    if (activeColumnTopY[activeIndex] < y) {
                        continue;
                    }

                    int columnIndex = activeColumnIndices[activeIndex];
                    planeColumnIndices[planeCount] = columnIndex;
                    double localThreshold = passThreshold[columnIndex];
                    if (surfaceBreakColumn[columnIndex] && y >= surfaceBreakFloorY[columnIndex]) {
                        localThreshold += surfaceBreakThresholdBoost;
                    }
                    localThreshold -= verticalEdgeFade[y - minY];
                    planeThresholdLimit[planeCount] = localThreshold * normalizationFactor;
                    planeCount++;
                }

                if (planeCount == 0) {
                    continue;
                }

                classifyDensityPlane(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
                int fadeIndex = y - minY;
                int localY = y & 15;
                MatterCavern verticalMatter = matterByY[fadeIndex];

                if (skipExistingCarved) {
                    for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
                        if (!planeCarve[planeIndex]) {
                            continue;
                        }

                        int columnIndex = planeColumnIndices[planeIndex];
                        int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
                        int localZ = columnIndex & 15;
                        if (cavernSlice.get(localX, localY, localZ) != null) {
                            continue;
                        }

                        double localThreshold = planeThresholdLimit[planeIndex] * inverseNormalization;
                        MatterCavern matter = resolveMatter(verticalMatter, x0 + localX, y, z0 + localZ,
                                columnIndex, waterMaxY, localThreshold);
                        writeCavern(cavernSlice, localX, y, localZ, matter, waterSupportPlan);
                        carved++;
                    }
                    continue;
                }

                for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
                    if (!planeCarve[planeIndex]) {
                        continue;
                    }

                    int columnIndex = planeColumnIndices[planeIndex];
                    int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
                    int localZ = columnIndex & 15;
                    double localThreshold = planeThresholdLimit[planeIndex] * inverseNormalization;
                    MatterCavern matter = resolveMatter(verticalMatter, x0 + localX, y, z0 + localZ,
                            columnIndex, waterMaxY, localThreshold);
                    writeCavern(cavernSlice, localX, y, localZ, matter, waterSupportPlan);
                    carved++;
                }
            }
        }

        return carved;
    }

    private int carvePassAdaptive(
            MantleChunk<Matter> chunk,
            int x0,
            int z0,
            int minY,
            int maxY,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin,
            double surfaceBreakThresholdBoost,
            int[] columnMaxY,
            int[] waterMaxY,
            int[] surfaceBreakFloorY,
            boolean[] surfaceBreakColumn,
            double[] columnThreshold,
            double[] clampedWeights,
            double[] verticalEdgeFade,
            MatterCavern[] matterByY,
            WaterSupportPlan waterSupportPlan,
            double minWeight,
            double thresholdPenalty,
            double thresholdBoost,
            boolean skipExistingCarved
    ) {
        int carved = 0;
        Scratch scratch = scratchCache.get();
        double[] passThreshold = scratch.passThreshold;
        int[] activeColumnIndices = scratch.activeColumnIndices;
        int[] activeColumnTopY = scratch.activeColumnTopY;
        int activeColumnCount = 0;

        for (int index = 0; index < 256; index++) {
            double columnWeight = clampedWeights[index];
            if (columnWeight <= minWeight || columnMaxY[index] < minY) {
                passThreshold[index] = Double.NaN;
                continue;
            }

            passThreshold[index] = columnThreshold[index] + thresholdBoost - ((1D - columnWeight) * thresholdPenalty);
            activeColumnIndices[activeColumnCount] = index;
            activeColumnTopY[activeColumnCount] = columnMaxY[index];
            activeColumnCount++;
        }

        if (activeColumnCount == 0) {
            return 0;
        }

        int[] planeColumnIndices = scratch.planeColumnIndices;
        double[] planeThresholdLimit = scratch.planeThresholdLimit;
        boolean[] planeCarve = scratch.planeCarve;
        int minSection = PowerOfTwoCoordinates.floorDivPow2(minY, 4);
        int maxSection = PowerOfTwoCoordinates.floorDivPow2(maxY, 4);

        for (int sectionIndex = minSection; sectionIndex <= maxSection; sectionIndex++) {
            int sectionMinY = Math.max(minY, PowerOfTwoCoordinates.chunkToBlock(sectionIndex));
            int sectionMaxY = Math.min(maxY, PowerOfTwoCoordinates.chunkToBlock(sectionIndex) + 15);
            MatterSlice<MatterCavern> cavernSlice = resolveCavernSlice(scratch, chunk, sectionIndex);

            for (int y = sectionMinY; y <= sectionMaxY; y++) {
                int planeCount = 0;
                for (int activeIndex = 0; activeIndex < activeColumnCount; activeIndex++) {
                    if (activeColumnTopY[activeIndex] < y) {
                        continue;
                    }

                    int columnIndex = activeColumnIndices[activeIndex];
                    planeColumnIndices[planeCount] = columnIndex;
                    double localThreshold = passThreshold[columnIndex];
                    if (surfaceBreakColumn[columnIndex] && y >= surfaceBreakFloorY[columnIndex]) {
                        localThreshold += surfaceBreakThresholdBoost;
                    }
                    localThreshold -= verticalEdgeFade[y - minY];
                    planeThresholdLimit[planeCount] = localThreshold * normalizationFactor;
                    planeCount++;
                }

                if (planeCount == 0) {
                    continue;
                }

                int effectiveAdaptiveSampleStep = resolveAdaptivePlaneSampleStep(y, adaptiveSampleStep);
                double effectiveAdaptiveThresholdMargin = resolveAdaptivePlaneThresholdMargin(
                        adaptiveThresholdMargin,
                        adaptiveSampleStep,
                        effectiveAdaptiveSampleStep
                );
                classifyDensityPlaneAdaptive(
                        x0,
                        z0,
                        y,
                        planeColumnIndices,
                        planeThresholdLimit,
                        planeCount,
                        planeCarve,
                        effectiveAdaptiveSampleStep,
                        effectiveAdaptiveThresholdMargin
                );
                int fadeIndex = y - minY;
                int localY = y & 15;
                MatterCavern verticalMatter = matterByY[fadeIndex];

                if (skipExistingCarved) {
                    for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
                        if (!planeCarve[planeIndex]) {
                            continue;
                        }

                        int columnIndex = planeColumnIndices[planeIndex];
                        int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
                        int localZ = columnIndex & 15;
                        if (cavernSlice.get(localX, localY, localZ) != null) {
                            continue;
                        }

                        double localThreshold = planeThresholdLimit[planeIndex] * inverseNormalization;
                        MatterCavern matter = resolveMatter(verticalMatter, x0 + localX, y, z0 + localZ,
                                columnIndex, waterMaxY, localThreshold);
                        writeCavern(cavernSlice, localX, y, localZ, matter, waterSupportPlan);
                        carved++;
                    }
                    continue;
                }

                for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
                    if (!planeCarve[planeIndex]) {
                        continue;
                    }

                    int columnIndex = planeColumnIndices[planeIndex];
                    int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
                    int localZ = columnIndex & 15;
                    double localThreshold = planeThresholdLimit[planeIndex] * inverseNormalization;
                    MatterCavern matter = resolveMatter(verticalMatter, x0 + localX, y, z0 + localZ,
                            columnIndex, waterMaxY, localThreshold);
                    writeCavern(cavernSlice, localX, y, localZ, matter, waterSupportPlan);
                    carved++;
                }
            }
        }

        return carved;
    }

    private int resolveAdaptivePlaneSampleStep(int y, int adaptiveSampleStep) {
        if (adaptiveSampleStep >= ADAPTIVE_DEEP_SAMPLE_STEP) {
            return adaptiveSampleStep;
        }

        int profileMaxY = (int) Math.ceil(profile.getVerticalRange().getMax());
        int fineBandFloorY = profileMaxY - profile.getSurfaceBreakDepth() - ADAPTIVE_DEEP_SURFACE_MARGIN;
        return y >= fineBandFloorY ? adaptiveSampleStep : ADAPTIVE_DEEP_SAMPLE_STEP;
    }

    private double resolveAdaptivePlaneThresholdMargin(
            double adaptiveThresholdMargin,
            int adaptiveSampleStep,
            int effectiveAdaptiveSampleStep
    ) {
        if (effectiveAdaptiveSampleStep <= adaptiveSampleStep) {
            return adaptiveThresholdMargin;
        }

        return adaptiveThresholdMargin + ((effectiveAdaptiveSampleStep - adaptiveSampleStep) * ADAPTIVE_DEEP_MARGIN_BOOST);
    }

    private int carvePassLattice(
            MantleChunk<Matter> chunk,
            int x0,
            int z0,
            int minY,
            int maxY,
            int latticeStep,
            double surfaceBreakThresholdBoost,
            int[] columnMaxY,
            int[] waterMaxY,
            int[] surfaceBreakFloorY,
            boolean[] surfaceBreakColumn,
            double[] columnThreshold,
            double[] clampedWeights,
            double[] verticalEdgeFade,
            MatterCavern[] matterByY,
            WaterSupportPlan waterSupportPlan,
            double minWeight,
            double thresholdPenalty,
            double thresholdBoost,
            boolean skipExistingCarved
    ) {
        int carved = 0;
        Scratch scratch = scratchCache.get();
        double[] passThreshold = scratch.passThreshold;
        int[] tileIndices = scratch.tileIndices;
        int[] tileLocalX = scratch.tileLocalX;
        int[] tileLocalZ = scratch.tileLocalZ;
        int[] tileTopY = scratch.tileTopY;

        for (int index = 0; index < 256; index++) {
            double columnWeight = clampedWeights[index];
            if (columnWeight <= minWeight || columnMaxY[index] < minY) {
                passThreshold[index] = Double.NaN;
                continue;
            }
            passThreshold[index] = columnThreshold[index] + thresholdBoost - ((1D - columnWeight) * thresholdPenalty);
        }

        for (int lx = 0; lx < 16; lx += 2) {
            int x = x0 + lx;
            int lx1 = lx + 1;
            for (int lz = 0; lz < 16; lz += 2) {
                int z = z0 + lz;
                int lz1 = lz + 1;
                int activeColumns = 0;

                int index00 = PowerOfTwoCoordinates.packLocal16(lx, lz);
                if (!Double.isNaN(passThreshold[index00])) {
                    tileIndices[activeColumns] = index00;
                    tileLocalX[activeColumns] = lx;
                    tileLocalZ[activeColumns] = lz;
                    tileTopY[activeColumns] = columnMaxY[index00];
                    activeColumns++;
                }

                int index01 = PowerOfTwoCoordinates.packLocal16(lx, lz1);
                if (!Double.isNaN(passThreshold[index01])) {
                    tileIndices[activeColumns] = index01;
                    tileLocalX[activeColumns] = lx;
                    tileLocalZ[activeColumns] = lz1;
                    tileTopY[activeColumns] = columnMaxY[index01];
                    activeColumns++;
                }

                int index10 = PowerOfTwoCoordinates.packLocal16(lx1, lz);
                if (!Double.isNaN(passThreshold[index10])) {
                    tileIndices[activeColumns] = index10;
                    tileLocalX[activeColumns] = lx1;
                    tileLocalZ[activeColumns] = lz;
                    tileTopY[activeColumns] = columnMaxY[index10];
                    activeColumns++;
                }

                int index11 = PowerOfTwoCoordinates.packLocal16(lx1, lz1);
                if (!Double.isNaN(passThreshold[index11])) {
                    tileIndices[activeColumns] = index11;
                    tileLocalX[activeColumns] = lx1;
                    tileLocalZ[activeColumns] = lz1;
                    tileTopY[activeColumns] = columnMaxY[index11];
                    activeColumns++;
                }

                if (activeColumns == 0) {
                    continue;
                }

                int tileMaxY = minY;
                for (int columnIndex = 0; columnIndex < activeColumns; columnIndex++) {
                    if (tileTopY[columnIndex] > tileMaxY) {
                        tileMaxY = tileTopY[columnIndex];
                    }
                }
                if (tileMaxY < minY) {
                    continue;
                }

                for (int y = minY; y <= tileMaxY; y += latticeStep) {
                    double density = sampleDensityOptimized(x, y, z);
                    int stampMaxY = Math.min(maxY, y + 1);
                    for (int yy = y; yy <= stampMaxY; yy++) {
                        MatterCavern verticalMatter = matterByY[yy - minY];
                        MatterSlice<MatterCavern> cavernSlice = resolveCavernSlice(scratch, chunk, PowerOfTwoCoordinates.floorDivPow2(yy, 4));
                        int localY = yy & 15;
                        int fadeIndex = yy - minY;
                        for (int columnIndex = 0; columnIndex < activeColumns; columnIndex++) {
                            if (yy > tileTopY[columnIndex]) {
                                continue;
                            }

                            int index = tileIndices[columnIndex];
                            double localThreshold = passThreshold[index];
                            if (surfaceBreakColumn[index] && yy >= surfaceBreakFloorY[index]) {
                                localThreshold += surfaceBreakThresholdBoost;
                            }
                            localThreshold -= verticalEdgeFade[fadeIndex];
                            if (density > localThreshold) {
                                continue;
                            }

                            int localX = tileLocalX[columnIndex];
                            int localZ = tileLocalZ[columnIndex];
                            int worldX = x0 + localX;
                            int worldZ = z0 + localZ;
                            MatterCavern matter = resolveMatter(verticalMatter, worldX, yy, worldZ,
                                    index, waterMaxY, localThreshold);
                            if (skipExistingCarved) {
                                if (cavernSlice.get(localX, localY, localZ) == null) {
                                    writeCavern(cavernSlice, localX, yy, localZ, matter, waterSupportPlan);
                                    carved++;
                                }
                                continue;
                            }

                            writeCavern(cavernSlice, localX, yy, localZ, matter, waterSupportPlan);
                            carved++;
                        }
                    }
                }
            }
        }

        return carved;
    }

    private int carvePassFallback(
            MantleChunk<Matter> chunk,
            int x0,
            int z0,
            int minY,
            int maxY,
            int sampleStep,
            double surfaceBreakThresholdBoost,
            int[] columnMaxY,
            int[] waterMaxY,
            int[] surfaceBreakFloorY,
            boolean[] surfaceBreakColumn,
            double[] columnThreshold,
            double[] clampedWeights,
            double[] verticalEdgeFade,
            MatterCavern[] matterByY,
            WaterSupportPlan waterSupportPlan,
            double minWeight,
            double thresholdPenalty,
            double thresholdBoost,
            boolean skipExistingCarved
    ) {
        int carved = 0;
        Scratch scratch = scratchCache.get();

        for (int lx = 0; lx < 16; lx++) {
            int x = x0 + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = z0 + lz;
                int index = PowerOfTwoCoordinates.packLocal16(lx, lz);
                double columnWeight = clampedWeights[index];
                if (columnWeight <= minWeight) {
                    continue;
                }

                int columnTopY = columnMaxY[index];
                if (columnTopY < minY) {
                    continue;
                }

                boolean breakColumn = surfaceBreakColumn[index];
                int breakFloorY = surfaceBreakFloorY[index];
                double threshold = columnThreshold[index] + thresholdBoost - ((1D - columnWeight) * thresholdPenalty);

                for (int y = minY; y <= columnTopY; y += sampleStep) {
                    double localThreshold = threshold;
                    if (breakColumn && y >= breakFloorY) {
                        localThreshold += surfaceBreakThresholdBoost;
                    }

                    localThreshold -= verticalEdgeFade[y - minY];
                    if (sampleDensityOptimized(x, y, z) > localThreshold) {
                        continue;
                    }

                    int carveMaxY = Math.min(columnTopY, y + sampleStep - 1);
                    for (int yy = y; yy <= carveMaxY; yy++) {
                        MatterCavern verticalMatter = matterByY[yy - minY];
                        MatterCavern matter = resolveMatter(verticalMatter, x, yy, z,
                                index, waterMaxY, localThreshold);
                        MatterSlice<MatterCavern> cavernSlice = resolveCavernSlice(scratch, chunk, PowerOfTwoCoordinates.floorDivPow2(yy, 4));
                        int localY = yy & 15;
                        if (skipExistingCarved) {
                            if (cavernSlice.get(lx, localY, lz) == null) {
                                writeCavern(cavernSlice, lx, yy, lz, matter, waterSupportPlan);
                                carved++;
                            }
                            continue;
                        }

                        writeCavern(cavernSlice, lx, yy, lz, matter, waterSupportPlan);
                        carved++;
                    }
                }
            }
        }

        return carved;
    }

    private boolean hasFallbackCandidates(int[] columnMaxY, double[] clampedWeights, int minY, double minWeight) {
        for (int index = 0; index < 256; index++) {
            if (clampedWeights[index] <= minWeight) {
                continue;
            }

            if (columnMaxY[index] >= minY) {
                return true;
            }
        }

        return false;
    }

    private double sampleDensityOptimized(int x, int y, int z) {
        if (!hasWarp) {
            if (!hasModules) {
                return sampleDensityNoWarpNoModules(x, y, z);
            }

            return sampleDensityNoWarpModules(x, y, z);
        }

        if (!hasModules) {
            return sampleDensityWarpOnly(x, y, z);
        }

        return sampleDensityWarpModules(x, y, z);
    }

    private void classifyDensityPlane(int x0, int z0, int y, int[] planeColumnIndices, double[] planeThresholdLimit, int planeCount, boolean[] planeCarve) {
        if (!hasWarp) {
            if (!hasModules) {
                classifyDensityPlaneNoWarpNoModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
                return;
            }

            classifyDensityPlaneNoWarpModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
            return;
        }

        if (!hasModules) {
            classifyDensityPlaneWarpOnly(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
            return;
        }

        classifyDensityPlaneWarpModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
    }

    private void classifyDensityPlaneAdaptive(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin
    ) {
        if (adaptiveSampleStep <= 1 || planeCount < ADAPTIVE_MIN_PLANE_COLUMNS) {
            classifyDensityPlane(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
            return;
        }

        if (!hasWarp) {
            if (!hasModules) {
                classifyDensityPlaneAdaptiveNoWarpNoModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve, adaptiveSampleStep, adaptiveThresholdMargin);
                return;
            }

            classifyDensityPlaneAdaptiveNoWarpModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve, adaptiveSampleStep, adaptiveThresholdMargin);
            return;
        }

        if (!hasModules) {
            classifyDensityPlaneAdaptiveWarpOnly(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve, adaptiveSampleStep, adaptiveThresholdMargin);
            return;
        }

        classifyDensityPlaneAdaptiveWarpModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve, adaptiveSampleStep, adaptiveThresholdMargin);
    }

    private void classifyDensityPlaneNoWarpNoModules(int x0, int z0, int y, int[] planeColumnIndices, double[] planeThresholdLimit, int planeCount, boolean[] planeCarve) {
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int x = x0 + PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int z = z0 + (columnIndex & 15);
            planeCarve[planeIndex] = classifyDensityPointNoWarpNoModules(x, y, z, planeThresholdLimit[planeIndex]);
        }
    }

    private void classifyDensityPlaneNoWarpModules(int x0, int z0, int y, int[] planeColumnIndices, double[] planeThresholdLimit, int planeCount, boolean[] planeCarve) {
        Scratch scratch = scratchCache.get();
        int activeModuleCount = prepareActiveModules(scratch, y);
        if (activeModuleCount == 0) {
            classifyDensityPlaneNoWarpNoModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
            return;
        }

        ModuleState[] localModules = scratch.activeModules;
        double[] remainingMin = scratch.activeModuleRemainingMin;
        double[] remainingMax = scratch.activeModuleRemainingMax;

        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int x = x0 + PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int z = z0 + (columnIndex & 15);
            planeCarve[planeIndex] = classifyDensityPointNoWarpModules(
                    x,
                    y,
                    z,
                    planeThresholdLimit[planeIndex],
                    localModules,
                    activeModuleCount,
                    remainingMin,
                    remainingMax
            );
        }
    }

    private void classifyDensityPlaneWarpOnly(int x0, int z0, int y, int[] planeColumnIndices, double[] planeThresholdLimit, int planeCount, boolean[] planeCarve) {
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int x = x0 + PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int z = z0 + (columnIndex & 15);
            planeCarve[planeIndex] = classifyDensityPointWarpOnly(x, y, z, planeThresholdLimit[planeIndex]);
        }
    }

    private void classifyDensityPlaneWarpModules(int x0, int z0, int y, int[] planeColumnIndices, double[] planeThresholdLimit, int planeCount, boolean[] planeCarve) {
        Scratch scratch = scratchCache.get();
        int activeModuleCount = prepareActiveModules(scratch, y);
        if (activeModuleCount == 0) {
            classifyDensityPlaneWarpOnly(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve);
            return;
        }

        ModuleState[] localModules = scratch.activeModules;
        double[] remainingMin = scratch.activeModuleRemainingMin;
        double[] remainingMax = scratch.activeModuleRemainingMax;

        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int x = x0 + PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int z = z0 + (columnIndex & 15);
            planeCarve[planeIndex] = classifyDensityPointWarpModules(
                    x,
                    y,
                    z,
                    planeThresholdLimit[planeIndex],
                    localModules,
                    activeModuleCount,
                    remainingMin,
                    remainingMax
            );
        }
    }

    private void classifyDensityPlaneAdaptiveNoWarpNoModules(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin
    ) {
        Scratch scratch = scratchCache.get();
        double[] adaptivePlaneDensity = scratch.adaptivePlaneDensity;
        int axisCells = (16 + adaptiveSampleStep - 1) / adaptiveSampleStep;
        int axisSamples = axisCells + 1;
        int[] adaptivePlaneSampleBounds = scratch.adaptivePlaneSampleBounds;
        prepareAdaptivePlaneSampleBounds(planeColumnIndices, planeCount, adaptiveSampleStep, adaptivePlaneSampleBounds, axisCells);
        for (int sampleXIndex = adaptivePlaneSampleBounds[0]; sampleXIndex <= adaptivePlaneSampleBounds[1]; sampleXIndex++) {
            int sampleLocalX = Math.min(sampleXIndex * adaptiveSampleStep, 16);
            int x = x0 + sampleLocalX;
            int rowOffset = sampleXIndex * axisSamples;
            for (int sampleZIndex = adaptivePlaneSampleBounds[2]; sampleZIndex <= adaptivePlaneSampleBounds[3]; sampleZIndex++) {
                int sampleLocalZ = Math.min(sampleZIndex * adaptiveSampleStep, 16);
                adaptivePlaneDensity[rowOffset + sampleZIndex] = sampleDensityNoWarpNoModules(x, y, z0 + sampleLocalZ);
            }
        }

        classifyAdaptivePlaneColumnsNoWarpNoModules(
                x0,
                z0,
                y,
                planeColumnIndices,
                planeThresholdLimit,
                planeCount,
                planeCarve,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples
        );
    }

    private void classifyDensityPlaneAdaptiveNoWarpModules(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin
    ) {
        Scratch scratch = scratchCache.get();
        int activeModuleCount = prepareActiveModules(scratch, y);
        if (activeModuleCount == 0) {
            classifyDensityPlaneAdaptiveNoWarpNoModules(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve, adaptiveSampleStep, adaptiveThresholdMargin);
            return;
        }

        ModuleState[] localModules = scratch.activeModules;
        double[] remainingMin = scratch.activeModuleRemainingMin;
        double[] remainingMax = scratch.activeModuleRemainingMax;
        double[] adaptivePlaneDensity = scratch.adaptivePlaneDensity;
        int axisCells = (16 + adaptiveSampleStep - 1) / adaptiveSampleStep;
        int axisSamples = axisCells + 1;
        int[] adaptivePlaneSampleBounds = scratch.adaptivePlaneSampleBounds;
        prepareAdaptivePlaneSampleBounds(planeColumnIndices, planeCount, adaptiveSampleStep, adaptivePlaneSampleBounds, axisCells);
        for (int sampleXIndex = adaptivePlaneSampleBounds[0]; sampleXIndex <= adaptivePlaneSampleBounds[1]; sampleXIndex++) {
            int sampleLocalX = Math.min(sampleXIndex * adaptiveSampleStep, 16);
            int x = x0 + sampleLocalX;
            int rowOffset = sampleXIndex * axisSamples;
            for (int sampleZIndex = adaptivePlaneSampleBounds[2]; sampleZIndex <= adaptivePlaneSampleBounds[3]; sampleZIndex++) {
                int sampleLocalZ = Math.min(sampleZIndex * adaptiveSampleStep, 16);
                adaptivePlaneDensity[rowOffset + sampleZIndex] = sampleDensityNoWarpNoModules(x, y, z0 + sampleLocalZ);
            }
        }

        classifyAdaptivePlaneColumnsNoWarpModules(
                x0,
                z0,
                y,
                planeColumnIndices,
                planeThresholdLimit,
                planeCount,
                planeCarve,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples,
                localModules,
                activeModuleCount,
                remainingMin,
                remainingMax
        );
    }

    private void classifyDensityPlaneAdaptiveWarpOnly(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin
    ) {
        Scratch scratch = scratchCache.get();
        double[] adaptivePlaneDensity = scratch.adaptivePlaneDensity;
        int axisCells = (16 + adaptiveSampleStep - 1) / adaptiveSampleStep;
        int axisSamples = axisCells + 1;
        int[] adaptivePlaneSampleBounds = scratch.adaptivePlaneSampleBounds;
        prepareAdaptivePlaneSampleBounds(planeColumnIndices, planeCount, adaptiveSampleStep, adaptivePlaneSampleBounds, axisCells);
        for (int sampleXIndex = adaptivePlaneSampleBounds[0]; sampleXIndex <= adaptivePlaneSampleBounds[1]; sampleXIndex++) {
            int sampleLocalX = Math.min(sampleXIndex * adaptiveSampleStep, 16);
            int x = x0 + sampleLocalX;
            int rowOffset = sampleXIndex * axisSamples;
            for (int sampleZIndex = adaptivePlaneSampleBounds[2]; sampleZIndex <= adaptivePlaneSampleBounds[3]; sampleZIndex++) {
                int sampleLocalZ = Math.min(sampleZIndex * adaptiveSampleStep, 16);
                adaptivePlaneDensity[rowOffset + sampleZIndex] = sampleDensityWarpOnly(x, y, z0 + sampleLocalZ);
            }
        }

        classifyAdaptivePlaneColumnsWarpOnly(
                x0,
                z0,
                y,
                planeColumnIndices,
                planeThresholdLimit,
                planeCount,
                planeCarve,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples
        );
    }

    private void classifyDensityPlaneAdaptiveWarpModules(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin
    ) {
        Scratch scratch = scratchCache.get();
        int activeModuleCount = prepareActiveModules(scratch, y);
        if (activeModuleCount == 0) {
            classifyDensityPlaneAdaptiveWarpOnly(x0, z0, y, planeColumnIndices, planeThresholdLimit, planeCount, planeCarve, adaptiveSampleStep, adaptiveThresholdMargin);
            return;
        }

        ModuleState[] localModules = scratch.activeModules;
        double[] remainingMin = scratch.activeModuleRemainingMin;
        double[] remainingMax = scratch.activeModuleRemainingMax;
        double[] adaptivePlaneDensity = scratch.adaptivePlaneDensity;
        int axisCells = (16 + adaptiveSampleStep - 1) / adaptiveSampleStep;
        int axisSamples = axisCells + 1;
        int[] adaptivePlaneSampleBounds = scratch.adaptivePlaneSampleBounds;
        prepareAdaptivePlaneSampleBounds(planeColumnIndices, planeCount, adaptiveSampleStep, adaptivePlaneSampleBounds, axisCells);
        for (int sampleXIndex = adaptivePlaneSampleBounds[0]; sampleXIndex <= adaptivePlaneSampleBounds[1]; sampleXIndex++) {
            int sampleLocalX = Math.min(sampleXIndex * adaptiveSampleStep, 16);
            int x = x0 + sampleLocalX;
            int rowOffset = sampleXIndex * axisSamples;
            for (int sampleZIndex = adaptivePlaneSampleBounds[2]; sampleZIndex <= adaptivePlaneSampleBounds[3]; sampleZIndex++) {
                int sampleLocalZ = Math.min(sampleZIndex * adaptiveSampleStep, 16);
                adaptivePlaneDensity[rowOffset + sampleZIndex] = sampleDensityWarpModules(
                        scratch,
                        x,
                        y,
                        z0 + sampleLocalZ,
                        localModules,
                        activeModuleCount
                );
            }
        }

        classifyAdaptivePlaneColumnsWarpModulesSampled(
                x0,
                z0,
                y,
                planeColumnIndices,
                planeThresholdLimit,
                planeCount,
                planeCarve,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples,
                localModules,
                activeModuleCount,
                remainingMin,
                remainingMax
        );
    }

    private void classifyAdaptivePlaneColumnsNoWarpNoModules(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin,
            double[] adaptivePlaneDensity,
            int axisCells,
            int axisSamples
    ) {
        Scratch scratch = scratchCache.get();
        double[] adaptivePlanePrediction = scratch.adaptivePlanePrediction;
        double[] adaptivePlaneAmbiguity = scratch.adaptivePlaneAmbiguity;
        prepareAdaptivePlaneColumns(
                planeColumnIndices,
                planeCount,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples,
                adaptivePlanePrediction,
                adaptivePlaneAmbiguity
        );
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int localZ = columnIndex & 15;
            double threshold = planeThresholdLimit[planeIndex] * inverseNormalization;
            double predictedDensity = adaptivePlanePrediction[planeIndex];
            double ambiguityMargin = adaptivePlaneAmbiguity[planeIndex];
            if (isAdaptivePlaneSampleAligned(localX, localZ, adaptiveSampleStep)) {
                planeCarve[planeIndex] = predictedDensity <= threshold;
                continue;
            }
            if (predictedDensity <= threshold - ambiguityMargin) {
                planeCarve[planeIndex] = true;
                continue;
            }
            if (predictedDensity > threshold + ambiguityMargin) {
                planeCarve[planeIndex] = false;
                continue;
            }
            if (adaptiveSampleStep >= ADAPTIVE_DEEP_SAMPLE_STEP) {
                planeCarve[planeIndex] = predictedDensity <= threshold;
                continue;
            }

            planeCarve[planeIndex] = classifyDensityPointNoWarpNoModules(x0 + localX, y, z0 + localZ, planeThresholdLimit[planeIndex]);
        }
    }

    private void classifyAdaptivePlaneColumnsNoWarpModules(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin,
            double[] adaptivePlaneDensity,
            int axisCells,
            int axisSamples,
            ModuleState[] localModules,
            int activeModuleCount,
            double[] remainingMin,
            double[] remainingMax
    ) {
        Scratch scratch = scratchCache.get();
        double[] adaptivePlanePrediction = scratch.adaptivePlanePrediction;
        double[] adaptivePlaneAmbiguity = scratch.adaptivePlaneAmbiguity;
        prepareAdaptivePlaneColumns(
                planeColumnIndices,
                planeCount,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples,
                adaptivePlanePrediction,
                adaptivePlaneAmbiguity
        );
        double minRemaining = remainingMin[0] * inverseNormalization;
        double maxRemaining = remainingMax[0] * inverseNormalization;
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int localZ = columnIndex & 15;
            double threshold = planeThresholdLimit[planeIndex] * inverseNormalization;
            double predictedDensity = adaptivePlanePrediction[planeIndex];
            double ambiguityMargin = adaptivePlaneAmbiguity[planeIndex];
            if (isAdaptivePlaneSampleAligned(localX, localZ, adaptiveSampleStep)) {
                planeCarve[planeIndex] = classifyDensityPointNoWarpModulesFromExactDensity(
                        x0 + localX,
                        y,
                        z0 + localZ,
                        threshold,
                        predictedDensity,
                        localModules,
                        activeModuleCount,
                        remainingMin,
                        remainingMax
                );
                continue;
            }
            if ((predictedDensity + maxRemaining) <= threshold - ambiguityMargin) {
                planeCarve[planeIndex] = true;
                continue;
            }
            if ((predictedDensity + minRemaining) > threshold + ambiguityMargin) {
                planeCarve[planeIndex] = false;
                continue;
            }
            if (adaptiveSampleStep >= ADAPTIVE_DEEP_SAMPLE_STEP) {
                planeCarve[planeIndex] = predictedDensity <= threshold;
                continue;
            }

            planeCarve[planeIndex] = classifyDensityPointNoWarpModules(
                    x0 + localX,
                    y,
                    z0 + localZ,
                    planeThresholdLimit[planeIndex],
                    localModules,
                    activeModuleCount,
                    remainingMin,
                    remainingMax
            );
        }
    }

    private void classifyAdaptivePlaneColumnsWarpOnly(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin,
            double[] adaptivePlaneDensity,
            int axisCells,
            int axisSamples
    ) {
        Scratch scratch = scratchCache.get();
        double[] adaptivePlanePrediction = scratch.adaptivePlanePrediction;
        double[] adaptivePlaneAmbiguity = scratch.adaptivePlaneAmbiguity;
        prepareAdaptivePlaneColumns(
                planeColumnIndices,
                planeCount,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples,
                adaptivePlanePrediction,
                adaptivePlaneAmbiguity
        );
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int localZ = columnIndex & 15;
            double threshold = planeThresholdLimit[planeIndex] * inverseNormalization;
            double predictedDensity = adaptivePlanePrediction[planeIndex];
            double ambiguityMargin = adaptivePlaneAmbiguity[planeIndex];
            if (isAdaptivePlaneSampleAligned(localX, localZ, adaptiveSampleStep)) {
                planeCarve[planeIndex] = predictedDensity <= threshold;
                continue;
            }
            if (predictedDensity <= threshold - ambiguityMargin) {
                planeCarve[planeIndex] = true;
                continue;
            }
            if (predictedDensity > threshold + ambiguityMargin) {
                planeCarve[planeIndex] = false;
                continue;
            }
            if (adaptiveSampleStep >= ADAPTIVE_DEEP_SAMPLE_STEP) {
                planeCarve[planeIndex] = predictedDensity <= threshold;
                continue;
            }

            planeCarve[planeIndex] = classifyDensityPointWarpOnly(x0 + localX, y, z0 + localZ, planeThresholdLimit[planeIndex]);
        }
    }

    private void classifyAdaptivePlaneColumnsWarpModules(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin,
            double[] adaptivePlaneDensity,
            int axisCells,
            int axisSamples,
            ModuleState[] localModules,
            int activeModuleCount,
            double[] remainingMin,
            double[] remainingMax
    ) {
        Scratch scratch = scratchCache.get();
        double[] adaptivePlanePrediction = scratch.adaptivePlanePrediction;
        double[] adaptivePlaneAmbiguity = scratch.adaptivePlaneAmbiguity;
        prepareAdaptivePlaneColumns(
                planeColumnIndices,
                planeCount,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples,
                adaptivePlanePrediction,
                adaptivePlaneAmbiguity
        );
        double minRemaining = remainingMin[0] * inverseNormalization;
        double maxRemaining = remainingMax[0] * inverseNormalization;
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int localZ = columnIndex & 15;
            double threshold = planeThresholdLimit[planeIndex] * inverseNormalization;
            double predictedDensity = adaptivePlanePrediction[planeIndex];
            double ambiguityMargin = adaptivePlaneAmbiguity[planeIndex];
            if (isAdaptivePlaneSampleAligned(localX, localZ, adaptiveSampleStep)) {
                planeCarve[planeIndex] = classifyDensityPointWarpModulesFromExactDensity(
                        x0 + localX,
                        y,
                        z0 + localZ,
                        threshold,
                        predictedDensity,
                        localModules,
                        activeModuleCount,
                        remainingMin,
                        remainingMax
                );
                continue;
            }
            if ((predictedDensity + maxRemaining) <= threshold - ambiguityMargin) {
                planeCarve[planeIndex] = true;
                continue;
            }
            if ((predictedDensity + minRemaining) > threshold + ambiguityMargin) {
                planeCarve[planeIndex] = false;
                continue;
            }

            planeCarve[planeIndex] = classifyDensityPointWarpModules(
                    x0 + localX,
                    y,
                    z0 + localZ,
                    planeThresholdLimit[planeIndex],
                    localModules,
                    activeModuleCount,
                    remainingMin,
                    remainingMax
            );
        }
    }

    private void classifyAdaptivePlaneColumnsWarpModulesSampled(
            int x0,
            int z0,
            int y,
            int[] planeColumnIndices,
            double[] planeThresholdLimit,
            int planeCount,
            boolean[] planeCarve,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin,
            double[] adaptivePlaneDensity,
            int axisCells,
            int axisSamples,
            ModuleState[] localModules,
            int activeModuleCount,
            double[] remainingMin,
            double[] remainingMax
    ) {
        Scratch scratch = scratchCache.get();
        double[] adaptivePlanePrediction = scratch.adaptivePlanePrediction;
        double[] adaptivePlaneAmbiguity = scratch.adaptivePlaneAmbiguity;
        prepareAdaptivePlaneColumns(
                planeColumnIndices,
                planeCount,
                adaptiveSampleStep,
                adaptiveThresholdMargin,
                adaptivePlaneDensity,
                axisCells,
                axisSamples,
                adaptivePlanePrediction,
                adaptivePlaneAmbiguity
        );
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int localZ = columnIndex & 15;
            double threshold = planeThresholdLimit[planeIndex] * inverseNormalization;
            double predictedDensity = adaptivePlanePrediction[planeIndex];
            double ambiguityMargin = adaptivePlaneAmbiguity[planeIndex];
            if (isAdaptivePlaneSampleAligned(localX, localZ, adaptiveSampleStep)) {
                planeCarve[planeIndex] = predictedDensity <= threshold;
                continue;
            }
            if (predictedDensity <= threshold - ambiguityMargin) {
                planeCarve[planeIndex] = true;
                continue;
            }
            if (predictedDensity > threshold + ambiguityMargin) {
                planeCarve[planeIndex] = false;
                continue;
            }
            if (adaptiveSampleStep >= ADAPTIVE_DEEP_SAMPLE_STEP) {
                planeCarve[planeIndex] = predictedDensity <= threshold;
                continue;
            }

            planeCarve[planeIndex] = classifyDensityPointWarpModules(
                    x0 + localX,
                    y,
                    z0 + localZ,
                    planeThresholdLimit[planeIndex],
                    localModules,
                    activeModuleCount,
                    remainingMin,
                    remainingMax
            );
        }
    }

    private boolean classifyDensityPointNoWarpNoModules(int x, int y, int z, double thresholdLimit) {
        double density = baseDensity.noiseFastSigned3D(x, y, z) * baseWeight;
        if ((density + detailMinContribution) > thresholdLimit) {
            return false;
        }
        if ((density + detailMaxContribution) <= thresholdLimit) {
            return true;
        }

        density += detailDensity.noiseFastSigned3D(x, y, z) * detailWeight;
        return density <= thresholdLimit;
    }

    private boolean classifyDensityPointNoWarpModules(
            int x,
            int y,
            int z,
            double thresholdLimit,
            ModuleState[] localModules,
            int activeModuleCount,
            double[] remainingMin,
            double[] remainingMax
    ) {
        if (activeModuleCount == 0) {
            return classifyDensityPointNoWarpNoModules(x, y, z, thresholdLimit);
        }

        double density = baseDensity.noiseFastSigned3D(x, y, z) * baseWeight;
        if ((density + detailMinContribution + remainingMin[0]) > thresholdLimit) {
            return false;
        }
        if ((density + detailMaxContribution + remainingMax[0]) <= thresholdLimit) {
            return true;
        }

        density += detailDensity.noiseFastSigned3D(x, y, z) * detailWeight;
        if ((density + remainingMin[0]) > thresholdLimit) {
            return false;
        }
        if ((density + remainingMax[0]) <= thresholdLimit) {
            return true;
        }

        for (int moduleIndex = 0; moduleIndex < activeModuleCount; moduleIndex++) {
            density += localModules[moduleIndex].sample(x, y, z);
            if ((density + remainingMin[moduleIndex + 1]) > thresholdLimit) {
                return false;
            }
            if ((density + remainingMax[moduleIndex + 1]) <= thresholdLimit) {
                return true;
            }
        }

        return density <= thresholdLimit;
    }

    private int snapWarp(int c) {
        int g = warpResolution;
        if (g <= 1) {
            return c;
        }
        if ((g & (g - 1)) == 0) {
            return c & -g;
        }
        return Math.floorDiv(c, g) * g;
    }

    private boolean classifyDensityPointWarpOnly(int x, int y, int z, double thresholdLimit) {
        Scratch scratch = scratchCache.get();
        int sx = snapWarp(x);
        int sy = snapWarp(y);
        int sz = snapWarp(z);
        int warpSlot = prepareWarpSample(scratch, sx, sy, sz);
        double warpA = scratch.warpCacheA[warpSlot];
        double warpB = scratch.warpCacheB[warpSlot];
        double warpedX = x + (warpA * warpStrength);
        double warpedY = y + (warpB * warpStrength);
        double warpedZ = z + ((warpA - warpB) * 0.5D * warpStrength);
        double density = baseDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * baseWeight;
        if ((density + detailMinContribution) > thresholdLimit) {
            return false;
        }
        if ((density + detailMaxContribution) <= thresholdLimit) {
            return true;
        }

        density += detailDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * detailWeight;
        return density <= thresholdLimit;
    }

    private boolean classifyDensityPointWarpModules(
            int x,
            int y,
            int z,
            double thresholdLimit,
            ModuleState[] localModules,
            int activeModuleCount,
            double[] remainingMin,
            double[] remainingMax
    ) {
        if (activeModuleCount == 0) {
            return classifyDensityPointWarpOnly(x, y, z, thresholdLimit);
        }

        Scratch scratch = scratchCache.get();
        int sx = snapWarp(x);
        int sy = snapWarp(y);
        int sz = snapWarp(z);
        int warpSlot = prepareWarpSample(scratch, sx, sy, sz);
        double warpA = scratch.warpCacheA[warpSlot];
        double warpB = scratch.warpCacheB[warpSlot];
        double warpedX = x + (warpA * warpStrength);
        double warpedY = y + (warpB * warpStrength);
        double warpedZ = z + ((warpA - warpB) * 0.5D * warpStrength);
        double density = baseDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * baseWeight;
        if ((density + detailMinContribution + remainingMin[0]) > thresholdLimit) {
            return false;
        }
        if ((density + detailMaxContribution + remainingMax[0]) <= thresholdLimit) {
            return true;
        }

        density += detailDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * detailWeight;
        if ((density + remainingMin[0]) > thresholdLimit) {
            return false;
        }
        if ((density + remainingMax[0]) <= thresholdLimit) {
            return true;
        }

        for (int moduleIndex = 0; moduleIndex < activeModuleCount; moduleIndex++) {
            density += localModules[moduleIndex].sample(warpedX, warpedY, warpedZ);
            if ((density + remainingMin[moduleIndex + 1]) > thresholdLimit) {
                return false;
            }
            if ((density + remainingMax[moduleIndex + 1]) <= thresholdLimit) {
                return true;
            }
        }

        return density <= thresholdLimit;
    }

    private boolean classifyDensityPointNoWarpModulesFromExactDensity(
            int x,
            int y,
            int z,
            double threshold,
            double density,
            ModuleState[] localModules,
            int activeModuleCount,
            double[] remainingMin,
            double[] remainingMax
    ) {
        if (activeModuleCount == 0) {
            return density <= threshold;
        }

        double minRemaining = remainingMin[0] * inverseNormalization;
        double maxRemaining = remainingMax[0] * inverseNormalization;
        if ((density + minRemaining) > threshold) {
            return false;
        }
        if ((density + maxRemaining) <= threshold) {
            return true;
        }

        for (int moduleIndex = 0; moduleIndex < activeModuleCount; moduleIndex++) {
            density += localModules[moduleIndex].sample(x, y, z) * inverseNormalization;
            if ((density + (remainingMin[moduleIndex + 1] * inverseNormalization)) > threshold) {
                return false;
            }
            if ((density + (remainingMax[moduleIndex + 1] * inverseNormalization)) <= threshold) {
                return true;
            }
        }

        return density <= threshold;
    }

    private boolean classifyDensityPointWarpModulesFromExactDensity(
            int x,
            int y,
            int z,
            double threshold,
            double density,
            ModuleState[] localModules,
            int activeModuleCount,
            double[] remainingMin,
            double[] remainingMax
    ) {
        if (activeModuleCount == 0) {
            return density <= threshold;
        }

        double minRemaining = remainingMin[0] * inverseNormalization;
        double maxRemaining = remainingMax[0] * inverseNormalization;
        if ((density + minRemaining) > threshold) {
            return false;
        }
        if ((density + maxRemaining) <= threshold) {
            return true;
        }

        Scratch scratch = scratchCache.get();
        int sx = snapWarp(x);
        int sy = snapWarp(y);
        int sz = snapWarp(z);
        int warpSlot = prepareWarpSample(scratch, sx, sy, sz);
        double warpA = scratch.warpCacheA[warpSlot];
        double warpB = scratch.warpCacheB[warpSlot];
        double warpedX = x + (warpA * warpStrength);
        double warpedY = y + (warpB * warpStrength);
        double warpedZ = z + ((warpA - warpB) * 0.5D * warpStrength);
        for (int moduleIndex = 0; moduleIndex < activeModuleCount; moduleIndex++) {
            density += localModules[moduleIndex].sample(warpedX, warpedY, warpedZ) * inverseNormalization;
            if ((density + (remainingMin[moduleIndex + 1] * inverseNormalization)) > threshold) {
                return false;
            }
            if ((density + (remainingMax[moduleIndex + 1] * inverseNormalization)) <= threshold) {
                return true;
            }
        }

        return density <= threshold;
    }

    private boolean isAdaptivePlaneSampleAligned(int localX, int localZ, int adaptiveSampleStep) {
        if ((adaptiveSampleStep & (adaptiveSampleStep - 1)) == 0) {
            int mask = adaptiveSampleStep - 1;
            return (localX & mask) == 0 && (localZ & mask) == 0;
        }
        return localX % adaptiveSampleStep == 0 && localZ % adaptiveSampleStep == 0;
    }

    private void prepareAdaptivePlaneSampleBounds(
            int[] planeColumnIndices,
            int planeCount,
            int adaptiveSampleStep,
            int[] adaptivePlaneSampleBounds,
            int axisCells
    ) {
        Scratch scratch = scratchCache.get();
        prepareAdaptiveGeometry(scratch, adaptiveSampleStep, axisCells, axisCells + 1);
        int[] adaptiveCellX = scratch.adaptiveCellX;
        int[] adaptiveCellZ = scratch.adaptiveCellZ;
        int minSampleX = axisCells;
        int maxSampleX = 0;
        int minSampleZ = axisCells;
        int maxSampleZ = 0;

        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int sampleX = adaptiveCellX[columnIndex];
            int sampleZ = adaptiveCellZ[columnIndex];
            if (sampleX < minSampleX) {
                minSampleX = sampleX;
            }
            if (sampleX + 1 > maxSampleX) {
                maxSampleX = sampleX + 1;
            }
            if (sampleZ < minSampleZ) {
                minSampleZ = sampleZ;
            }
            if (sampleZ + 1 > maxSampleZ) {
                maxSampleZ = sampleZ + 1;
            }
        }

        adaptivePlaneSampleBounds[0] = minSampleX;
        adaptivePlaneSampleBounds[1] = maxSampleX;
        adaptivePlaneSampleBounds[2] = minSampleZ;
        adaptivePlaneSampleBounds[3] = maxSampleZ;
    }

    private void prepareAdaptivePlaneColumns(
            int[] planeColumnIndices,
            int planeCount,
            int adaptiveSampleStep,
            double adaptiveThresholdMargin,
            double[] adaptivePlaneDensity,
            int axisCells,
            int axisSamples,
            double[] adaptivePlanePrediction,
            double[] adaptivePlaneAmbiguity
    ) {
        Scratch scratch = scratchCache.get();
        prepareAdaptiveGeometry(scratch, adaptiveSampleStep, axisCells, axisSamples);
        int[] adaptiveCellZ = scratch.adaptiveCellZ;
        int[] adaptiveRow0 = scratch.adaptiveRow0;
        int[] adaptiveRow1 = scratch.adaptiveRow1;
        double[] adaptiveTx = scratch.adaptiveTx;
        double[] adaptiveTz = scratch.adaptiveTz;
        for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            int columnIndex = planeColumnIndices[planeIndex];
            int cellZ = adaptiveCellZ[columnIndex];
            int row0 = adaptiveRow0[columnIndex];
            int row1 = adaptiveRow1[columnIndex];
            double tx = adaptiveTx[columnIndex];
            double tz = adaptiveTz[columnIndex];
            double d00 = adaptivePlaneDensity[row0 + cellZ];
            double d01 = adaptivePlaneDensity[row0 + cellZ + 1];
            double d10 = adaptivePlaneDensity[row1 + cellZ];
            double d11 = adaptivePlaneDensity[row1 + cellZ + 1];
            double dx0 = d00 + ((d10 - d00) * tx);
            double dx1 = d01 + ((d11 - d01) * tx);
            adaptivePlanePrediction[planeIndex] = dx0 + ((dx1 - dx0) * tz);
            double minDensity = Math.min(Math.min(d00, d01), Math.min(d10, d11));
            double maxDensity = Math.max(Math.max(d00, d01), Math.max(d10, d11));
            adaptivePlaneAmbiguity[planeIndex] = adaptiveThresholdMargin + ((maxDensity - minDensity) * ADAPTIVE_LOCAL_RANGE_SCALE);
        }
    }

    private void prepareAdaptiveGeometry(Scratch scratch, int adaptiveSampleStep, int axisCells, int axisSamples) {
        if (scratch.adaptiveGeometryStep == adaptiveSampleStep && scratch.adaptiveGeometryAxisCells == axisCells) {
            return;
        }

        for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
            int localX = columnIndex >> 4;
            int localZ = columnIndex & 15;
            int cellX = Math.min(localX / adaptiveSampleStep, axisCells - 1);
            int cellZ = Math.min(localZ / adaptiveSampleStep, axisCells - 1);
            int x0 = cellX * adaptiveSampleStep;
            int z0 = cellZ * adaptiveSampleStep;
            int x1 = Math.min(x0 + adaptiveSampleStep, 16);
            int z1 = Math.min(z0 + adaptiveSampleStep, 16);
            scratch.adaptiveCellX[columnIndex] = cellX;
            scratch.adaptiveCellZ[columnIndex] = cellZ;
            scratch.adaptiveRow0[columnIndex] = cellX * axisSamples;
            scratch.adaptiveRow1[columnIndex] = (cellX + 1) * axisSamples;
            scratch.adaptiveTx[columnIndex] = x1 == x0 ? 0D : (localX - x0) / (double) (x1 - x0);
            scratch.adaptiveTz[columnIndex] = z1 == z0 ? 0D : (localZ - z0) / (double) (z1 - z0);
        }

        scratch.adaptiveGeometryStep = adaptiveSampleStep;
        scratch.adaptiveGeometryAxisCells = axisCells;
    }

    private double sampleDensityNoWarpNoModules(int x, int y, int z) {
        double density = baseDensity.noiseFastSigned3D(x, y, z) * baseWeight;
        density += detailDensity.noiseFastSigned3D(x, y, z) * detailWeight;
        return density * inverseNormalization;
    }

    private double sampleDensityNoWarpModules(int x, int y, int z) {
        Scratch scratch = scratchCache.get();
        int activeModuleCount = prepareActiveModules(scratch, y);
        if (activeModuleCount == 0) {
            return sampleDensityNoWarpNoModules(x, y, z);
        }

        ModuleState[] localModules = scratch.activeModules;
        double density = baseDensity.noiseFastSigned3D(x, y, z) * baseWeight;
        density += detailDensity.noiseFastSigned3D(x, y, z) * detailWeight;
        for (int moduleIndex = 0; moduleIndex < activeModuleCount; moduleIndex++) {
            ModuleState module = localModules[moduleIndex];
            double moduleDensity = module.density.noiseFastSigned3D(x, y, z) - module.threshold;
            if (module.invert) {
                moduleDensity = -moduleDensity;
            }

            density += moduleDensity * module.weight;
        }

        return density * inverseNormalization;
    }

    private double sampleDensityWarpOnly(int x, int y, int z) {
        Scratch scratch = scratchCache.get();
        int sx = snapWarp(x);
        int sy = snapWarp(y);
        int sz = snapWarp(z);
        int warpSlot = prepareWarpSample(scratch, sx, sy, sz);
        double warpA = scratch.warpCacheA[warpSlot];
        double warpB = scratch.warpCacheB[warpSlot];
        double warpedX = x + (warpA * warpStrength);
        double warpedY = y + (warpB * warpStrength);
        double warpedZ = z + ((warpA - warpB) * 0.5D * warpStrength);
        double density = baseDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * baseWeight;
        density += detailDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * detailWeight;
        return density * inverseNormalization;
    }

    private double sampleDensityWarpModules(int x, int y, int z) {
        Scratch scratch = scratchCache.get();
        int activeModuleCount = prepareActiveModules(scratch, y);
        if (activeModuleCount == 0) {
            return sampleDensityWarpOnly(x, y, z);
        }

        ModuleState[] localModules = scratch.activeModules;
        return sampleDensityWarpModules(scratch, x, y, z, localModules, activeModuleCount);
    }

    private double sampleDensityWarpModules(Scratch scratch, int x, int y, int z, ModuleState[] localModules, int activeModuleCount) {
        int sx = snapWarp(x);
        int sy = snapWarp(y);
        int sz = snapWarp(z);
        int warpSlot = prepareWarpSample(scratch, sx, sy, sz);
        double warpA = scratch.warpCacheA[warpSlot];
        double warpB = scratch.warpCacheB[warpSlot];
        double warpedX = x + (warpA * warpStrength);
        double warpedY = y + (warpB * warpStrength);
        double warpedZ = z + ((warpA - warpB) * 0.5D * warpStrength);
        double density = baseDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * baseWeight;
        density += detailDensity.noiseFastSigned3D(warpedX, warpedY, warpedZ) * detailWeight;
        for (int moduleIndex = 0; moduleIndex < activeModuleCount; moduleIndex++) {
            ModuleState module = localModules[moduleIndex];
            double moduleDensity = module.density.noiseFastSigned3D(warpedX, warpedY, warpedZ) - module.threshold;
            if (module.invert) {
                moduleDensity = -moduleDensity;
            }

            density += moduleDensity * module.weight;
        }

        return density * inverseNormalization;
    }

    private int prepareWarpSample(Scratch scratch, int sx, int sy, int sz) {
        int slot = mixWarpKey(sx, sy, sz) & (scratch.warpCacheX.length - 1);
        if (scratch.warpCacheSet[slot]
                && scratch.warpCacheX[slot] == sx
                && scratch.warpCacheY[slot] == sy
                && scratch.warpCacheZ[slot] == sz) {
            return slot;
        }

        scratch.warpCacheSet[slot] = true;
        scratch.warpCacheX[slot] = sx;
        scratch.warpCacheY[slot] = sy;
        scratch.warpCacheZ[slot] = sz;
        scratch.warpCacheA[slot] = warpDensity.noiseFastSigned3D(sx, sy, sz);
        scratch.warpCacheB[slot] = warpDensity.noiseFastSigned3D(sx + 31.37D, sy - 17.21D, sz + 23.91D);
        return slot;
    }

    private static int mixWarpKey(int sx, int sy, int sz) {
        int hash = sx * 73428767;
        hash ^= sy * 91227153;
        hash ^= sz * 43828939;
        hash ^= hash >>> 16;
        return hash;
    }

    private int prepareActiveModules(Scratch scratch, int y) {
        ModuleState[] configuredModules = modules;
        int configuredCount = configuredModules.length;
        if (configuredCount == 0) {
            return 0;
        }

        if (scratch.activeModules.length < configuredCount) {
            scratch.activeModules = new ModuleState[configuredCount];
        }

        int activeCount = 0;
        for (int moduleIndex = 0; moduleIndex < configuredCount; moduleIndex++) {
            ModuleState module = configuredModules[moduleIndex];
            if (y < module.minY || y > module.maxY) {
                continue;
            }

            scratch.activeModules[activeCount] = module;
            activeCount++;
        }

        if (scratch.activeModuleRemainingMin.length < activeCount + 1) {
            scratch.activeModuleRemainingMin = new double[activeCount + 1];
            scratch.activeModuleRemainingMax = new double[activeCount + 1];
        }

        scratch.activeModuleRemainingMin[activeCount] = 0D;
        scratch.activeModuleRemainingMax[activeCount] = 0D;
        for (int moduleIndex = activeCount - 1; moduleIndex >= 0; moduleIndex--) {
            ModuleState module = scratch.activeModules[moduleIndex];
            scratch.activeModuleRemainingMin[moduleIndex] = scratch.activeModuleRemainingMin[moduleIndex + 1] + module.minContribution;
            scratch.activeModuleRemainingMax[moduleIndex] = scratch.activeModuleRemainingMax[moduleIndex + 1] + module.maxContribution;
        }

        return activeCount;
    }

    private MatterSlice<MatterCavern> resolveCavernSlice(Scratch scratch, MantleChunk<Matter> chunk, int sectionIndex) {
        @SuppressWarnings("unchecked")
        MatterSlice<MatterCavern> cachedSlice = (MatterSlice<MatterCavern>) scratch.sectionSlices[sectionIndex];
        if (cachedSlice != null) {
            return cachedSlice;
        }

        Matter sectionMatter = scratch.sectionMatter[sectionIndex];
        if (sectionMatter == null) {
            sectionMatter = chunk.getOrCreate(sectionIndex);
            scratch.sectionMatter[sectionIndex] = sectionMatter;
        }

        MatterSlice<MatterCavern> resolvedSlice = sectionMatter.slice(MatterCavern.class);
        scratch.sectionSlices[sectionIndex] = resolvedSlice;
        return resolvedSlice;
    }

    private MatterCavern[] prepareMatterByYTable(Scratch scratch, int minY, int maxY) {
        int size = Math.max(0, maxY - minY + 1);
        if (scratch.matterByY.length < size) {
            scratch.matterByY = new MatterCavern[size];
        }

        MatterCavern[] matterByY = scratch.matterByY;
        boolean allowLava = profile.isAllowLava();
        int lavaHeight = engine.getDimension().getCaveLavaHeight();

        for (int y = minY; y <= maxY; y++) {
            int offset = y - minY;
            if (allowLava && y <= lavaHeight) {
                matterByY[offset] = carveLava;
                continue;
            }
            if (!allowLava && y <= lavaHeight) {
                matterByY[offset] = carveForcedAir;
                continue;
            }

            matterByY[offset] = carveAir;
        }

        return matterByY;
    }

    private int resolveWaterMaxY(int columnSurfaceY) {
        if (!profile.isAllowWater()) {
            return Integer.MIN_VALUE;
        }

        int minDepth = Math.max(0, profile.getWaterMinDepthBelowSurface());
        return Math.min(engine.getDimension().getFluidHeight(), columnSurfaceY - minDepth);
    }

    private MatterCavern resolveMatter(MatterCavern verticalMatter, int x, int y, int z,
                                       int columnIndex, int[] waterMaxY, double localThreshold) {
        if (verticalMatter != carveLava
                && y <= waterMaxY[columnIndex]
                && isAquiferCandidate(x, y, z, localThreshold)) {
            return carveWater;
        }
        return verticalMatter;
    }

    private boolean isAquiferCandidate(int x, int y, int z, double localThreshold) {
        int fluidHeight = engine.getDimension().getFluidHeight();
        double depthFactor = Math.max(0D, Math.min(1.5D, (fluidHeight - y) / 48D));
        double cutoff = 0.35D + (depthFactor * 0.2D);
        if (detailDensity.noiseFastSigned3D(x, y * 0.5D, z) <= cutoff) {
            return false;
        }
        return !profile.isWaterRequiresFloor() || hasAquiferCupSupport(x, y, z, localThreshold);
    }

    private boolean hasAquiferCupSupport(int x, int y, int z, double threshold) {
        int floorY = Math.max(0, y - 1);
        int deepFloorY = Math.max(0, y - 2);
        int aboveY = Math.min(engine.getHeight() - 1, y + 1);
        if (!isDensitySolid(x, floorY, z, threshold)) {
            return false;
        }
        if (!isDensitySolid(x, deepFloorY, z, threshold - 0.05D)) {
            return false;
        }

        int support = 0;
        if (isDensitySolid(x + 1, y, z, threshold)) {
            support++;
        }
        if (isDensitySolid(x - 1, y, z, threshold)) {
            support++;
        }
        if (isDensitySolid(x, y, z + 1, threshold)) {
            support++;
        }
        if (isDensitySolid(x, y, z - 1, threshold)) {
            support++;
        }
        if (isDensitySolid(x, aboveY, z, threshold)) {
            support++;
        }
        return support >= 4;
    }

    private boolean isDensitySolid(int x, int y, int z, double threshold) {
        return sampleDensityOptimized(x, y, z) > threshold;
    }

    private void writeCavern(MatterSlice<MatterCavern> cavernSlice, int localX, int y, int localZ,
                             MatterCavern matter, WaterSupportPlan waterSupportPlan) {
        cavernSlice.set(localX, y & 15, localZ, matter);
        if (waterSupportPlan != null && matter == carveWater) {
            waterSupportPlan.add(localX, y, localZ, carveWater, carveAir);
        }
    }

    private void prepareSectionCaches(Scratch scratch, int minY, int maxY) {
        int minSection = Math.max(0, PowerOfTwoCoordinates.floorDivPow2(minY, 4));
        int maxSection = Math.max(minSection, PowerOfTwoCoordinates.floorDivPow2(maxY, 4));
        int requiredSections = maxSection + 1;
        if (scratch.sectionMatter.length < requiredSections) {
            scratch.sectionMatter = new Matter[requiredSections];
            scratch.sectionSlices = new MatterSlice<?>[requiredSections];
            return;
        }

        for (int section = minSection; section <= maxSection; section++) {
            scratch.sectionMatter[section] = null;
            scratch.sectionSlices[section] = null;
        }
    }

    private double clampColumnWeight(double weight) {
        if (Double.isNaN(weight) || Double.isInfinite(weight)) {
            return 0D;
        }

        if (weight <= 0D) {
            return 0D;
        }

        if (weight >= 1D) {
            return 1D;
        }

        return weight;
    }

    private double signed(double value) {
        return (value * 2D) - 1D;
    }

    private double[] prepareVerticalEdgeFadeTable(Scratch scratch, int minY, int maxY) {
        int size = Math.max(0, maxY - minY + 1);
        if (scratch.verticalEdgeFade.length < size) {
            scratch.verticalEdgeFade = new double[size];
        }

        double[] verticalEdgeFade = scratch.verticalEdgeFade;
        int fadeRange = Math.max(0, profile.getVerticalEdgeFade());
        double fadeStrength = Math.max(0D, profile.getVerticalEdgeFadeStrength());
        if (size <= 0 || fadeRange <= 0 || maxY <= minY || fadeStrength <= 0D) {
            Arrays.fill(verticalEdgeFade, 0, size, 0D);
            return verticalEdgeFade;
        }

        for (int y = minY; y <= maxY; y++) {
            int floorDistance = y - minY;
            int ceilingDistance = maxY - y;
            int edgeDistance = Math.min(floorDistance, ceilingDistance);
            int offsetIndex = y - minY;
            if (edgeDistance >= fadeRange) {
                verticalEdgeFade[offsetIndex] = 0D;
                continue;
            }

            double t = Math.max(0D, Math.min(1D, edgeDistance / (double) fadeRange));
            double smooth = t * t * (3D - (2D * t));
            verticalEdgeFade[offsetIndex] = (1D - smooth) * fadeStrength;
        }

        return verticalEdgeFade;
    }

    static final class WaterSupportPlan {
        private final IdentityHashMap<MatterCavern, WaterCandidateGroup> groups = new IdentityHashMap<>();

        void add(int localX, int y, int localZ, MatterCavern water, MatterCavern air) {
            WaterCandidateGroup group = groups.computeIfAbsent(water, key -> new WaterCandidateGroup(water, air));
            int columnIndex = PowerOfTwoCoordinates.packLocal16(localX, localZ);
            group.positions.set((y << 8) | columnIndex);
        }

        void resolve(MantleChunk<Matter> chunk) {
            if (chunk == null) {
                groups.clear();
                return;
            }

            for (Map.Entry<MatterCavern, WaterCandidateGroup> entry : groups.entrySet()) {
                WaterCandidateGroup group = entry.getValue();
                for (int position = group.positions.nextSetBit(0); position >= 0; position = group.positions.nextSetBit(position + 1)) {
                    int y = position >>> 8;
                    int columnIndex = position & 255;
                    int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
                    int localZ = columnIndex & 15;
                    MatterCavern current = getCavern(chunk, localX, y, localZ);
                    if (current != group.water || hasCupSupport(chunk, localX, y, localZ)) {
                        continue;
                    }

                    Matter section = chunk.get(y >> 4);
                    MatterSlice<MatterCavern> cavernSlice = section.getSlice(MatterCavern.class);
                    cavernSlice.set(localX, y & 15, localZ, group.air);
                }
            }
            groups.clear();
        }

        private static boolean hasCupSupport(MantleChunk<Matter> chunk, int localX, int y, int localZ) {
            if (localX <= 0 || localX >= 15 || localZ <= 0 || localZ >= 15
                    || y <= 1 || !isSolid(chunk, localX, y - 1, localZ)
                    || !isSolid(chunk, localX, y - 2, localZ)) {
                return false;
            }

            int support = 0;
            if (isSolid(chunk, localX + 1, y, localZ)) {
                support++;
            }
            if (isSolid(chunk, localX - 1, y, localZ)) {
                support++;
            }
            if (isSolid(chunk, localX, y, localZ + 1)) {
                support++;
            }
            if (isSolid(chunk, localX, y, localZ - 1)) {
                support++;
            }
            if (isSolid(chunk, localX, y + 1, localZ)) {
                support++;
            }
            return support >= 4;
        }

        private static boolean isSolid(MantleChunk<Matter> chunk, int localX, int y, int localZ) {
            if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) {
                return false;
            }
            MatterCavern cavern = getCavern(chunk, localX, y, localZ);
            return cavern == null || !cavern.isCavern();
        }

        private static MatterCavern getCavern(MantleChunk<Matter> chunk, int localX, int y, int localZ) {
            Matter section = chunk.get(y >> 4);
            if (section == null) {
                return null;
            }

            MatterSlice<MatterCavern> cavernSlice = section.getSlice(MatterCavern.class);
            return cavernSlice == null ? null : cavernSlice.get(localX, y & 15, localZ);
        }
    }

    private static final class WaterCandidateGroup {
        private final MatterCavern water;
        private final MatterCavern air;
        private final BitSet positions = new BitSet();

        private WaterCandidateGroup(MatterCavern water, MatterCavern air) {
            this.water = water;
            this.air = air;
        }
    }

    private static final class ModuleState {
        private final CNG density;
        private final int minY;
        private final int maxY;
        private final double weight;
        private final double threshold;
        private final boolean invert;
        private final double minContribution;
        private final double maxContribution;

        private ModuleState(IrisCaveFieldModule module, CNG density) {
            IrisRange range = module.getVerticalRange();
            this.density = density;
            this.minY = (int) Math.floor(range.getMin());
            this.maxY = (int) Math.ceil(range.getMax());
            this.weight = module.getWeight();
            this.threshold = module.getThreshold();
            this.invert = module.isInvert();
            double rawMin = invert ? threshold - 1D : -1D - threshold;
            double rawMax = invert ? threshold + 1D : 1D - threshold;
            this.minContribution = rawMin * weight;
            this.maxContribution = rawMax * weight;
        }

        private double sample(double x, int y, double z) {
            double sampled = density.noiseFastSigned3D(x, y, z);
            return invert ? (threshold - sampled) * weight : (sampled - threshold) * weight;
        }

        private double sample(double x, double y, double z) {
            double sampled = density.noiseFastSigned3D(x, y, z);
            return invert ? (threshold - sampled) * weight : (sampled - threshold) * weight;
        }
    }

    private static final class Scratch {
        private final int[] columnMaxY = new int[256];
        private final int[] waterMaxY = new int[256];
        private final int[] surfaceBreakFloorY = new int[256];
        private final boolean[] surfaceBreakColumn = new boolean[256];
        private final double[] columnThreshold = new double[256];
        private final double[] passThreshold = new double[256];
        private final double[] fullWeights = new double[256];
        private final double[] clampedColumnWeights = new double[256];
        private final int[] activeColumnIndices = new int[256];
        private final int[] activeColumnTopY = new int[256];
        private final int[] planeColumnIndices = new int[256];
        private final double[] planeThresholdLimit = new double[256];
        private final boolean[] planeCarve = new boolean[256];
        private final double[] adaptivePlaneDensity = new double[81];
        private final double[] adaptivePlanePrediction = new double[256];
        private final double[] adaptivePlaneAmbiguity = new double[256];
        private final int[] adaptivePlaneSampleBounds = new int[4];
        private final int[] adaptiveCellX = new int[256];
        private final int[] adaptiveCellZ = new int[256];
        private final int[] adaptiveRow0 = new int[256];
        private final int[] adaptiveRow1 = new int[256];
        private final double[] adaptiveTx = new double[256];
        private final double[] adaptiveTz = new double[256];
        private final boolean[] warpCacheSet = new boolean[256];
        private final int[] warpCacheX = new int[256];
        private final int[] warpCacheY = new int[256];
        private final int[] warpCacheZ = new int[256];
        private final double[] warpCacheA = new double[256];
        private final double[] warpCacheB = new double[256];
        private final int[] tileIndices = new int[4];
        private final int[] tileLocalX = new int[4];
        private final int[] tileLocalZ = new int[4];
        private final int[] tileTopY = new int[4];
        private ModuleState[] activeModules = new ModuleState[0];
        private double[] activeModuleRemainingMin = new double[0];
        private double[] activeModuleRemainingMax = new double[0];
        private double[] verticalEdgeFade = new double[0];
        private MatterCavern[] matterByY = new MatterCavern[0];
        private Matter[] sectionMatter = new Matter[0];
        private MatterSlice<?>[] sectionSlices = new MatterSlice<?>[0];
        private int adaptiveGeometryStep = -1;
        private int adaptiveGeometryAxisCells = -1;
        private boolean fullWeightsInitialized;
    }
}

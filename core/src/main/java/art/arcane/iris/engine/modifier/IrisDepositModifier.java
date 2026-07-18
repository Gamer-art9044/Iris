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

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedModifier;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDepositGenerator;
import art.arcane.iris.engine.object.IrisDepositVariant;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionCarvingResolver;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisProceduralBlocks;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.data.HeightMap;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.data.B;

public class IrisDepositModifier extends EngineAssignedModifier<PlatformBlockState> {
    private final RNG rng;

    public IrisDepositModifier(Engine engine) {
        super(engine, "Deposit");
        rng = new RNG(getEngine().getSeedManager().getDeposit());
    }

    @Override
    public void onModify(int x, int z, Hunk<PlatformBlockState> output, boolean multicore, ChunkContext context) {
        PrecisionStopwatch p = PrecisionStopwatch.start();
        generateDeposits(output, Math.floorDiv(x, 16), Math.floorDiv(z, 16), multicore, context);
        getEngine().getMetrics().getDeposit().put(p.getMilliseconds());
    }

    public void generateDeposits(Hunk<PlatformBlockState> terrain, int x, int z, boolean multicore, ChunkContext context) {
        IrisRegion region = context.getRegion().get(7, 7);
        IrisBiome biome = context.getBiome().get(7, 7);
        BurstExecutor burst = burst().burst(multicore);

        long seed = x * 341873128712L + z * 132897987541L;
        long mask = 0;
        MantleChunk chunk = getEngine().getMantle().getMantle().getChunk(x, z).use();
        try {
            for (IrisDepositGenerator k : getDimension().getDeposits()) {
                long finalSeed = seed * ++mask;
                burst.queue(() -> generate(k, chunk, terrain, rng.nextParallelRNG(finalSeed), x, z, false, context));
            }

            for (IrisDepositGenerator k : region.getDeposits()) {
                long finalSeed = seed * ++mask;
                burst.queue(() -> generate(k, chunk, terrain, rng.nextParallelRNG(finalSeed), x, z, false, context));
            }

            for (IrisDepositGenerator k : biome.getDeposits()) {
                long finalSeed = seed * ++mask;
                burst.queue(() -> generate(k, chunk, terrain, rng.nextParallelRNG(finalSeed), x, z, false, context));
            }
            burst.complete();
        } finally {
            chunk.release();
        }
    }

    public void generate(IrisDepositGenerator k, MantleChunk chunk, Hunk<PlatformBlockState> data, RNG rng, int cx, int cz, boolean safe, ChunkContext context) {
        generate(k, chunk, data, rng, cx, cz, safe, null, context);
    }

    public void generate(IrisDepositGenerator k, MantleChunk chunk, Hunk<PlatformBlockState> data, RNG rng, int cx, int cz, boolean safe, HeightMap he, ChunkContext context) {
        IrisDimensionCarvingResolver.State carvingState = new IrisDimensionCarvingResolver.State();
        if (k.getSpawnChance() < rng.d())
            return;

        for (int l = 0; l < rng.i(k.getMinPerChunk(), k.getMaxPerChunk() + 1); l++) {
            if (k.getPerClumpSpawnChance() < rng.d())
                continue;

            IrisObject clump = k.getClump(getEngine(), rng, getData());

            int dim = clump.getW();
            int min = dim / 2;
            int max = (int) (16D - dim / 2D);

            if (min > max || min < 0 || max > 15) {
                min = 6;
                max = 9;
            }

            int x = rng.i(min, max + 1);
            int z = rng.i(min, max + 1);
            int height = getDepositSurfaceLimit(cx, cz, x, z, he, context);

            if (height <= 0)
                continue;

            int minY = Math.max(0, k.getMinHeight());
            int maxY = Math.min(height, Math.min(getEngine().getHeight() - 1, k.getMaxHeight()));

            if (minY >= maxY)
                continue;

            int y = rng.i(minY, maxY + 1);

            if (y > k.getMaxHeight() || y < k.getMinHeight() || y > height - 2)
                continue;

            if (k.isOre(getData())) {
                IrisBiome depositBiome = getEngine().getCaveBiome(
                        (cx << 4) + x, y, (cz << 4) + z, carvingState);

                if (depositBiome != null) {
                    double frequencyMultiplier = depositBiome.getOreDepositFrequencyMultiplier();
                    if (frequencyMultiplier < 1D
                            && !passesOreFrequency(frequencyMultiplier, rng.d())) {
                        continue;
                    }

                    double sizeMultiplier = depositBiome.getOreDepositSizeMultiplier();
                    if (sizeMultiplier != 1D) {
                        IrisObject scaledClump = k.getClump(getEngine(), rng, getData(), sizeMultiplier);
                        int scaledDimension = scaledClump.getW();
                        x = clampDepositCenter(x, scaledDimension, 16);
                        y = clampDepositCenter(y, scaledDimension, getEngine().getHeight());
                        z = clampDepositCenter(z, scaledDimension, 16);
                        clump = scaledClump;
                    }
                }
            }

            IrisDimension dimension = getDimension();

            for (art.arcane.iris.util.common.math.IrisBlockVector j : clump.getBlocks().keys()) {
                int nx = j.getBlockX() + x;
                int ny = j.getBlockY() + y;
                int nz = j.getBlockZ() + z;

                if (nx > 15 || nx < 0 || ny >= getEngine().getHeight() || ny < 0 || nz < 0 || nz > 15) {
                    continue;
                }
                int columnSurfaceLimit = getDepositSurfaceLimit(cx, cz, nx, nz, he, context);
                if (ny > columnSurfaceLimit) {
                    continue;
                }

                PlatformBlockState current = data.get(nx, ny, nz);
                if (!canReplaceDepositTarget(current)) {
                    continue;
                }
                if (!k.isReplaceBedrock() && IrisProceduralBlocks.materialKey(current).equals("minecraft:bedrock")) {
                    continue;
                }

                if (chunk.get(nx, ny, nz, MatterCavern.class) == null) {
                    PlatformBlockState ore = clump.getBlocks().get(j);
                    PlatformBlockState remapped = resolveDepositVariant(
                            cx, cz, nx, ny, nz, ore, dimension, context, carvingState);
                    PlatformBlockState finalBlock = remapped != null
                            ? remapped
                            : B.toDeepSlateOre(current, ore);
                    data.set(nx, ny, nz, finalBlock);
                }
            }
        }
    }

    private int getDepositSurfaceLimit(int cx, int cz, int localX, int localZ, HeightMap heightMap, ChunkContext context) {
        int surfaceY = heightMap != null
                ? heightMap.getHeight((cx << 4) + localX, (cz << 4) + localZ)
                : context.getRoundedHeight(localX, localZ);
        return depositSurfaceLimit(surfaceY);
    }

    static int depositSurfaceLimit(int surfaceY) {
        return surfaceY - 7;
    }

    static int absoluteWorldY(int minHeight, int localY) {
        return minHeight + localY;
    }

    static boolean canReplaceDepositTarget(PlatformBlockState state) {
        return state != null && !state.isAir() && !state.isFluid();
    }

    static boolean passesOreFrequency(double multiplier, double sample) {
        return multiplier >= 1D || sample < Math.max(0D, multiplier);
    }

    static int clampDepositCenter(int center, int dimension, int limit) {
        int minimum = dimension / 2;
        int maximum = (int) (limit - dimension / 2D);
        return Math.max(minimum, Math.min(center, maximum));
    }

    private PlatformBlockState resolveDepositVariant(int cx, int cz, int nx, int localY, int nz, PlatformBlockState ore, IrisDimension dimension, ChunkContext context, IrisDimensionCarvingResolver.State carvingState) {
        int worldX = (cx << 4) + nx;
        int worldZ = (cz << 4) + nz;
        int worldY = absoluteWorldY(getEngine().getMinHeight(), localY);

        IrisBiome biome = getEngine().getCaveBiome(worldX, localY, worldZ, carvingState);
        if (biome != null) {
            PlatformBlockState match = matchDepositVariant(biome.getDepositVariants(), ore, worldY);
            if (match != null) {
                return match;
            }
        }

        IrisRegion region = context.getRegion().get(nx, nz);
        if (region != null) {
            PlatformBlockState match = matchDepositVariant(region.getDepositVariants(), ore, worldY);
            if (match != null) {
                return match;
            }
        }

        if (dimension != null) {
            PlatformBlockState match = matchDepositVariant(dimension.getDepositVariants(), ore, worldY);
            if (match != null) {
                return match;
            }
        }

        return null;
    }

    private PlatformBlockState matchDepositVariant(java.util.List<IrisDepositVariant> variants, PlatformBlockState ore, int y) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }

        for (IrisDepositVariant variant : variants) {
            if (y < variant.getMinHeight() || y > variant.getMaxHeight()) {
                continue;
            }

            PlatformBlockState swapped = variant.remapOrNull(ore, getData());
            if (swapped != null) {
                return swapped;
            }
        }

        return null;
    }
}

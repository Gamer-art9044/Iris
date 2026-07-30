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

package art.arcane.iris.engine.object;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.Required;
import art.arcane.iris.engine.object.annotations.Snippet;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.math.IrisBlockVector;
import art.arcane.iris.util.common.math.Vector3i;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.math.BlockPosition;
import art.arcane.volmlib.util.math.RNG;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Snippet("deposit")
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Creates ore & other block deposits underground")
@Data
public class IrisDepositGenerator {
    private final transient ConcurrentMap<ClumpCacheKey, KList<IrisObject>> objects = new ConcurrentHashMap<>();
    private final transient AtomicCache<KList<PlatformBlockState>> blockData = new AtomicCache<>();
    private final transient AtomicCache<Boolean> ore = new AtomicCache<>();
    private final transient ConcurrentMap<ClumpCacheKey, KList<IrisObject>> scaledObjects = new ConcurrentHashMap<>();
    @Required
    @MinNumber(0)
    @MaxNumber(8192)
    @Desc("The minimum height this deposit can generate at")
    private int minHeight = 1;
    @Required
    @MinNumber(0)
    @MaxNumber(8192)
    @Desc("The maximum height this deposit can generate at")
    private int maxHeight = 75;
    @Required
    @MinNumber(0)
    @MaxNumber(8192)
    @Desc("The minimum amount of deposit blocks per clump")
    private int minSize = 0;
    @Required
    @MinNumber(0)
    @MaxNumber(8192)
    @Desc("The maximum amount of deposit blocks per clump")
    private int maxSize = 128;
    @Required
    @MinNumber(0)
    @MaxNumber(2048)
    @Desc("The maximum amount of clumps per chunk")
    private int maxPerChunk = 3;
    @Required
    @MinNumber(0)
    @MaxNumber(2048)
    @Desc("The minimum amount of clumps per chunk")
    private int minPerChunk = 0;
    @MinNumber(0)
    @MaxNumber(1)
    @Desc("The change of the deposit spawning in a chunk")
    private double spawnChance = 1;
    @MinNumber(0)
    @MaxNumber(1)
    @Desc("The change of the a clump spawning in a chunk")
    private double perClumpSpawnChance = 1;
    @Required
    @ArrayType(min = 1, type = IrisBlockData.class)
    @Desc("The palette of blocks to be used in this deposit generator")
    private KList<IrisBlockData> palette = new KList<>();
    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Ore varience is how many different objects clumps iris will create")
    private int varience = 3;
    @Desc("If set to true, this deposit will replace bedrock")
    private boolean replaceBedrock = false;

    public IrisObject getClump(Engine engine, RNG rng, IrisData rdata) {
        ClumpCacheKey cacheKey = new ClumpCacheKey(engine.getSeedManager().getDeposit(), minSize, maxSize);
        KList<IrisObject> objects = this.objects.computeIfAbsent(cacheKey, key -> {
            RNG rngv = new RNG(key.depositSeed() + hashCode());
            KList<IrisObject> objectsf = new KList<>();

            for (int i = 0; i < varience; i++) {
                objectsf.add(generateClumpObject(
                        rngv.nextParallelRNG(2349 * i + 3598), rdata, key.minSize(), key.maxSize()));
            }

            return objectsf;
        });
        return objects.get(rng.i(0, objects.size()));
    }

    public IrisObject getClump(Engine engine, RNG rng, IrisData rdata, double sizeMultiplier) {
        if (sizeMultiplier == 1D) {
            return getClump(engine, rng, rdata);
        }

        int scaledMinSize = scaledDepositSize(minSize, sizeMultiplier);
        int scaledMaxSize = scaledDepositSize(maxSize, sizeMultiplier);
        ClumpCacheKey cacheKey = new ClumpCacheKey(
                engine.getSeedManager().getDeposit(), scaledMinSize, scaledMaxSize);
        KList<IrisObject> objects = scaledObjects.computeIfAbsent(cacheKey, key -> {
            long sizeSeed = ((long) key.minSize() << 32) ^ (key.maxSize() & 0xffffffffL);
            RNG rngv = new RNG(key.depositSeed() + hashCode() + sizeSeed);
            KList<IrisObject> generated = new KList<>();

            for (int i = 0; i < varience; i++) {
                generated.add(generateClumpObject(
                        rngv.nextParallelRNG(2349 * i + 3598), rdata, key.minSize(), key.maxSize()));
            }

            return generated;
        });
        return objects.get(rng.i(0, objects.size()));
    }

    public int getMaxDimension() {
        return Math.min(11, (int) Math.ceil(Math.cbrt(maxSize)));
    }

    static int scaledDepositSize(int size, double multiplier) {
        return Math.max(0, Math.min(8192, (int) Math.round(size * multiplier)));
    }

    private IrisObject generateClumpObject(RNG rngv, IrisData rdata, int clumpMinSize, int clumpMaxSize) {
        int s = rngv.i(clumpMinSize, clumpMaxSize + 1);
        if (s == 1) {
            IrisObject o = new IrisObject(1, 1, 1);
            Vector3i center = o.getCenter();
            o.getBlocks().put(new IrisBlockVector(center.getX(), center.getY(), center.getZ()), nextBlock(rngv, rdata));
            return o;
        }

        int dim = Math.min(11, (int) Math.ceil(Math.cbrt(s)));
        IrisObject o = new IrisObject(dim, dim, dim);

        int volume = dim * dim * dim;
        if (s >= volume) {
            int x = 0, y = 0, z = 0;

            while (z < dim) {
                o.setUnsigned(x++, y, z, nextBlock(rngv, rdata));

                if (x == dim) {
                    x = 0;
                    y++;
                }

                if (y == dim) {
                    y = 0;
                    z++;
                }
            }
            return o;
        }

        KSet<BlockPosition> set = new KSet<>();
        while (s > 0) {
            BlockPosition ang = new BlockPosition(
                    rngv.i(0, dim),
                    rngv.i(0, dim),
                    rngv.i(0, dim)
            );
            if (!set.add(ang)) continue;

            s--;
            o.setUnsigned(ang.getX(), ang.getY(), ang.getZ(), nextBlock(rngv, rdata));
        }

        return o;
    }

    private PlatformBlockState nextBlock(RNG rngv, IrisData rdata) {
        return getBlockData(rdata).get(rngv.i(0, getBlockData(rdata).size()));
    }

    public KList<PlatformBlockState> getBlockData(IrisData rdata) {
        return blockData.aquire(() ->
        {
            KList<PlatformBlockState> blockData = new KList<>();

            for (IrisBlockData ix : palette) {
                PlatformBlockState bx = ix.getBlockData(rdata);

                if (bx != null) {
                    blockData.add(bx);
                }
            }

            return blockData;
        });
    }

    public boolean isOre(IrisData rdata) {
        return ore.aquire(() -> {
            for (PlatformBlockState block : getBlockData(rdata)) {
                if (block.isOre()) {
                    return true;
                }
            }

            return false;
        });
    }

    record ClumpCacheKey(long depositSeed, int minSize, int maxSize) {
    }
}

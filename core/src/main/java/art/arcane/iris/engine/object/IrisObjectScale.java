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

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.Snippet;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Snippet("object-scale")
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Scale objects")
@Data
public class IrisObjectScale {
    private static final int CACHE_INITIAL_CAPACITY = 256;
    private static final int CACHE_MAX_WEIGHT = 8192;
    private static final ConcurrentLinkedHashMap<CacheKey, KList<IrisObject>> cache
            = new ConcurrentLinkedHashMap.Builder<CacheKey, KList<IrisObject>>()
            .initialCapacity(CACHE_INITIAL_CAPACITY)
            .maximumWeightedCapacity(CACHE_MAX_WEIGHT)
            .concurrencyLevel(32)
            .build();

    /**
     * Coarse flush wired into IrisData dump/hotload: keys hold strong IrisObject references
     * (and through them the owning IrisData), so without this every hotload or world unload
     * pinned the previous pack graph for the process lifetime. Entries are pure derived data,
     * so a flushed still-live entry just recomputes.
     */
    public static void invalidate() {
        cache.clear();
    }

    @MinNumber(0.01)
    @MaxNumber(50)
    @Desc("Fixed scale multiplier for this object. 0.5 shrinks to half size, 2.0 doubles the size. When set to anything other than 1, this overrides minimumScale and maximumScale. Leave at 1 to use the minimumScale/maximumScale range.")
    private double size = 1;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Iris Objects are scaled and cached to speed up placements. Because of this extra memory is used, so we evenly distribute variations across the defined scale range, then pick one randomly. If the differences is small, use a lower number. For more possibilities on the scale spectrum, increase this at the cost of memory.")
    private int variations = 7;

    @MinNumber(0.01)
    @MaxNumber(50)
    @Desc("The minimum scale. Used when size is 1 to pick a random scale per placement.")
    private double minimumScale = 1;

    @MinNumber(0.01)
    @MaxNumber(50)
    @Desc("The maximum scale. Used when size is 1 to pick a random scale per placement.")
    private double maximumScale = 1;

    @Desc("If this object is scaled up beyond its origin size, specify a 3D interpolator. NONE keeps blocky scaled output, TRILINEAR (LERP) smooths with linear interpolation, TRICUBIC and TRIHERMITE produce smoother but slower output.")
    private IrisObjectPlacementScaleInterpolator interpolation = IrisObjectPlacementScaleInterpolator.NONE;

    public boolean shouldScale() {
        if (size != 1) {
            return true;
        }
        if (variations <= 0) {
            return false;
        }
        return minimumScale != 1 || maximumScale != 1;
    }

    public int getMaxSizeFor(int indim) {
        return (int) Math.ceil(getMaxScale() * indim);
    }

    public double getMaxScale() {
        if (size != 1) {
            return size;
        }
        return Math.max(minimumScale, maximumScale);
    }

    public IrisObject get(RNG rng, IrisObject origin) {
        if (origin == null) {
            return null;
        }
        if (!shouldScale()) {
            return origin;
        }

        CacheKey key = new CacheKey(origin, size, minimumScale, maximumScale, variations, interpolation);
        return cache.computeIfAbsent(key, (k) -> {
            KList<IrisObject> c = new KList<>();

            if (size != 1) {
                c.add(origin.scaled(size, interpolation));
                return c;
            }

            if (minimumScale == maximumScale) {
                c.add(origin.scaled(minimumScale, interpolation));
                return c;
            }

            int vs = Math.max(1, Math.min(variations, 32));
            double step = (maximumScale - minimumScale) / (double) vs;
            for (int v = 0; v < vs; v++) {
                c.add(origin.scaled(minimumScale + step * v, interpolation));
            }
            return c;
        }).getRandom(rng);
    }

    public boolean canScaleBeyond() {
        return shouldScale() && getMaxScale() > 1;
    }

    private record CacheKey(IrisObject origin, double size, double minimumScale, double maximumScale, int variations, IrisObjectPlacementScaleInterpolator interpolation) {
    }
}

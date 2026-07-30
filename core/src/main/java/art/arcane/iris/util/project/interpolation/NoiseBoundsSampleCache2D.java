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

package art.arcane.iris.util.project.interpolation;

import art.arcane.volmlib.util.function.NoiseProvider;

import java.util.Arrays;

/**
 * Open-addressed memo table that samples a {@link NoiseBoundsProvider} once per column and serves
 * both the min and max side from the same entry. Single threaded by contract; instances are held in
 * thread locals.
 * <p>
 * The min/max {@link NoiseProvider} views are allocated once per cache instance and read the
 * currently bound provider, so a bounds interpolation pass allocates no lambdas per column.
 */
final class NoiseBoundsSampleCache2D {
    private final NoiseProvider minView = this::sampleMin;
    private final NoiseProvider maxView = this::sampleMax;
    private NoiseBoundsProvider boundProvider;
    private long[] xBits;
    private long[] zBits;
    private double[] minValues;
    private double[] maxValues;
    private byte[] states;
    private int mask;
    private int resizeThreshold;
    private int size;

    public NoiseBoundsSampleCache2D(int initialCapacity) {
        int minimumCapacity = Math.max(8, initialCapacity);
        int tableSize = tableSizeFor((minimumCapacity << 1) + minimumCapacity);
        xBits = new long[tableSize];
        zBits = new long[tableSize];
        minValues = new double[tableSize];
        maxValues = new double[tableSize];
        states = new byte[tableSize];
        mask = tableSize - 1;
        resizeThreshold = Math.max(1, (tableSize * 3) >> 2);
        size = 0;
    }

    public void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(states, (byte) 0);
        size = 0;
    }

    /**
     * Binds the provider that {@link #minView()} and {@link #maxView()} delegate to, returning the
     * previously bound provider so callers can restore it.
     */
    public NoiseBoundsProvider bindProvider(NoiseBoundsProvider provider) {
        NoiseBoundsProvider previous = boundProvider;
        boundProvider = provider;
        return previous;
    }

    public NoiseProvider minView() {
        return minView;
    }

    public NoiseProvider maxView() {
        return maxView;
    }

    private double sampleMin(double sampleX, double sampleZ) {
        return getOrSampleMin(sampleX, sampleZ, boundProvider);
    }

    private double sampleMax(double sampleX, double sampleZ) {
        return getOrSampleMax(sampleX, sampleZ, boundProvider);
    }

    public double getOrSampleMin(double sampleX, double sampleZ, NoiseBoundsProvider provider) {
        long xBitsValue = Double.doubleToLongBits(sampleX);
        long zBitsValue = Double.doubleToLongBits(sampleZ);
        int slot = findSlot(xBitsValue, zBitsValue);
        if (states[slot] != 0) {
            return minValues[slot];
        }

        NoiseBounds bounds = provider.noise(sampleX, sampleZ);
        insert(slot, xBitsValue, zBitsValue, bounds.min(), bounds.max());
        return bounds.min();
    }

    public double getOrSampleMax(double sampleX, double sampleZ, NoiseBoundsProvider provider) {
        long xBitsValue = Double.doubleToLongBits(sampleX);
        long zBitsValue = Double.doubleToLongBits(sampleZ);
        int slot = findSlot(xBitsValue, zBitsValue);
        if (states[slot] != 0) {
            return maxValues[slot];
        }

        NoiseBounds bounds = provider.noise(sampleX, sampleZ);
        insert(slot, xBitsValue, zBitsValue, bounds.min(), bounds.max());
        return bounds.max();
    }

    private int findSlot(long xb, long zb) {
        int slot = mix(xb, zb) & mask;
        while (states[slot] != 0) {
            if (xBits[slot] == xb && zBits[slot] == zb) {
                break;
            }
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void insert(int slot, long xb, long zb, double min, double max) {
        xBits[slot] = xb;
        zBits[slot] = zb;
        minValues[slot] = min;
        maxValues[slot] = max;
        states[slot] = 1;
        size++;
        if (size >= resizeThreshold) {
            grow();
        }
    }

    private int mix(long xb, long zb) {
        long hash = xb * 0x9E3779B97F4A7C15L;
        hash ^= Long.rotateLeft(zb * 0xC2B2AE3D27D4EB4FL, 32);
        hash ^= (hash >>> 33);
        hash *= 0xff51afd7ed558ccdL;
        hash ^= (hash >>> 33);
        return (int) hash;
    }

    private void grow() {
        long[] previousXBits = xBits;
        long[] previousZBits = zBits;
        double[] previousMin = minValues;
        double[] previousMax = maxValues;
        byte[] previousStates = states;

        int nextLength = xBits.length << 1;
        long[] nextXBits = new long[nextLength];
        long[] nextZBits = new long[nextLength];
        double[] nextMin = new double[nextLength];
        double[] nextMax = new double[nextLength];
        byte[] nextStates = new byte[nextLength];

        xBits = nextXBits;
        zBits = nextZBits;
        minValues = nextMin;
        maxValues = nextMax;
        states = nextStates;
        mask = nextLength - 1;
        resizeThreshold = Math.max(1, (nextLength * 3) >> 2);
        size = 0;

        for (int i = 0; i < previousStates.length; i++) {
            if (previousStates[i] == 0) {
                continue;
            }
            int slot = findSlot(previousXBits[i], previousZBits[i]);
            xBits[slot] = previousXBits[i];
            zBits[slot] = previousZBits[i];
            minValues[slot] = previousMin[i];
            maxValues[slot] = previousMax[i];
            states[slot] = 1;
            size++;
        }
    }

    private int tableSizeFor(int value) {
        int n = value - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        int tableSize = n + 1;
        if (tableSize < 8) {
            return 8;
        }
        return tableSize;
    }
}

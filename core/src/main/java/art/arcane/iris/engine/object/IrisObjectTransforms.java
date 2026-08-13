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

import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.common.data.VectorMap;
import art.arcane.iris.util.common.math.IrisBlockVector;
import art.arcane.iris.util.common.math.IrisVector;
import art.arcane.iris.util.project.interpolation.Interpolation3D;

/**
 * Geometric transforms for {@link IrisObject}: rotation, scaling and the interpolated upscalers.
 */
final class IrisObjectTransforms {
    private IrisObjectTransforms() {
    }

    static IrisObject rotateCopy(IrisObject self, IrisObjectRotation rt) {
        IrisObject copy = self.copy();
        rotate(copy, rt, 0, 0, 0);
        return copy;
    }

    static void rotate(IrisObject self, IrisObjectRotation r, int spinx, int spiny, int spinz) {
        self.writeLock.lock();
        try {
            VectorMap<PlatformBlockState> d = new VectorMap<>();

            for (var entry : self.blocks) {
                d.put(r.rotate(entry.getKey(), spinx, spiny, spinz), r.rotate(entry.getValue(), spinx, spiny, spinz));
            }

            VectorMap<TileData> dx = new VectorMap<>();

            for (var entry : self.states) {
                dx.put(r.rotate(entry.getKey(), spinx, spiny, spinz), entry.getValue());
            }

            self.blocks = d;
            self.states = dx;
            IrisObjectShaping.shrinkwrap(self);
            self.surfaceSupportOffsets.reset();
            self.floatingFootprint.reset();
        } finally {
            self.writeLock.unlock();
        }
    }

    static IrisObject scaled(IrisObject self, double scale, IrisObjectPlacementScaleInterpolator interpolation) {
        if (interpolation == null) {
            interpolation = IrisObjectPlacementScaleInterpolator.NONE;
        }
        IrisVector sm1 = new IrisVector(scale - 1, scale - 1, scale - 1);
        scale = Math.max(0.001, Math.min(50, scale));
        if (scale < 1) {
            scale = scale - 0.0001;
        }

        IrisPosition l1 = self.getAABB().max();
        IrisPosition l2 = self.getAABB().min();
        VectorMap<PlatformBlockState> placeBlock = new VectorMap<>();

        IrisVector center = new IrisVector(self.getCenter().getX(), self.getCenter().getY(), self.getCenter().getZ());
        if (self.getH() == 2) {
            center = center.setY(center.getBlockY() + 0.5);
        }
        if (self.getW() == 2) {
            center = center.setX(center.getBlockX() + 0.5);
        }
        if (self.getD() == 2) {
            center = center.setZ(center.getBlockZ() + 0.5);
        }

        IrisObject oo = new IrisObject((int) Math.ceil((self.w * scale) + (scale * 2)), (int) Math.ceil((self.h * scale) + (scale * 2)), (int) Math.ceil((self.d * scale) + (scale * 2)));
        oo.setLoadKey(self.getLoadKey());
        oo.setLoader(self.getLoader());
        oo.setLoadFile(self.getLoadFile());

        self.readLock.lock();
        try {
            for (var entry : self.blocks) {
                PlatformBlockState bd = entry.getValue();
                placeBlock.put(entry.getKey().clone().add(IrisObject.HALF).subtract(center)
                        .multiply(scale).add(sm1).toBlockVector(), bd);
            }
        } finally {
            self.readLock.unlock();
        }

        for (var entry : placeBlock) {
            IrisBlockVector v = entry.getKey();
            if (scale > 1) {
                for (IrisBlockVector vec : IrisObjectShaping.blocksBetweenTwoPoints(v.clone().add(center), v.clone().add(center).add(sm1))) {
                    oo.blocks.put(vec, entry.getValue());
                }
            } else {
                oo.setUnsigned(v.getBlockX(), v.getBlockY(), v.getBlockZ(), entry.getValue());
            }
        }

        if (scale > 1) {
            switch (interpolation) {
                case TRILINEAR -> trilinear(oo, (int) Math.round(scale));
                case TRICUBIC -> tricubic(oo, (int) Math.round(scale));
                case TRIHERMITE -> trihermite(oo, (int) Math.round(scale));
            }
        }

        return oo;
    }

    static void trilinear(IrisObject self, int rad) {
        self.writeLock.lock();
        try {
            VectorMap<PlatformBlockState> v = self.blocks;
            VectorMap<PlatformBlockState> b = new VectorMap<>();
            IrisPosition min = self.getAABB().min();
            IrisPosition max = self.getAABB().max();

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        if (Interpolation3D.getTrilinear(x, y, z, rad, (xx, yy, zz) -> {
                            PlatformBlockState data = v.get(new IrisBlockVector((int) xx, (int) yy, (int) zz));

                            if (B.isAir(data)) {
                                return 0;
                            }

                            return 1;
                        }) >= 0.5) {
                            b.put(new IrisBlockVector(x, y, z), nearestBlockData(self, x, y, z));
                        } else {
                            b.put(new IrisBlockVector(x, y, z), IrisObject.States.AIR);
                        }
                    }
                }
            }

            self.blocks = b;
            self.surfaceSupportOffsets.reset();
            self.floatingFootprint.reset();
        } finally {
            self.writeLock.unlock();
        }
    }

    static void tricubic(IrisObject self, int rad) {
        self.writeLock.lock();
        try {
            VectorMap<PlatformBlockState> v = self.blocks;
            VectorMap<PlatformBlockState> b = new VectorMap<>();
            IrisPosition min = self.getAABB().min();
            IrisPosition max = self.getAABB().max();

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        if (Interpolation3D.getTricubic(x, y, z, rad, (xx, yy, zz) -> {
                            PlatformBlockState data = v.get(new IrisBlockVector((int) xx, (int) yy, (int) zz));

                            if (B.isAir(data)) {
                                return 0;
                            }

                            return 1;
                        }) >= 0.5) {
                            b.put(new IrisBlockVector(x, y, z), nearestBlockData(self, x, y, z));
                        } else {
                            b.put(new IrisBlockVector(x, y, z), IrisObject.States.AIR);
                        }
                    }
                }
            }

            self.blocks = b;
            self.surfaceSupportOffsets.reset();
            self.floatingFootprint.reset();
        } finally {
            self.writeLock.unlock();
        }
    }

    static void trihermite(IrisObject self, int rad) {
        trihermite(self, rad, 0D, 0D);
    }

    static void trihermite(IrisObject self, int rad, double tension, double bias) {
        self.writeLock.lock();
        try {
            VectorMap<PlatformBlockState> v = self.blocks;
            VectorMap<PlatformBlockState> b = new VectorMap<>();
            IrisPosition min = self.getAABB().min();
            IrisPosition max = self.getAABB().max();

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        if (Interpolation3D.getTrihermite(x, y, z, rad, (xx, yy, zz) -> {
                            PlatformBlockState data = v.get(new IrisBlockVector((int) xx, (int) yy, (int) zz));

                            if (B.isAir(data)) {
                                return 0;
                            }

                            return 1;
                        }, tension, bias) >= 0.5) {
                            b.put(new IrisBlockVector(x, y, z), nearestBlockData(self, x, y, z));
                        } else {
                            b.put(new IrisBlockVector(x, y, z), IrisObject.States.AIR);
                        }
                    }
                }
            }

            self.blocks = b;
            self.surfaceSupportOffsets.reset();
            self.floatingFootprint.reset();
        } finally {
            self.writeLock.unlock();
        }
    }

    private static PlatformBlockState nearestBlockData(IrisObject self, int x, int y, int z) {
        IrisBlockVector vv = new IrisBlockVector(x, y, z);
        self.readLock.lock();
        try {
            PlatformBlockState r = self.blocks.get(vv);

            if (!B.isAir(r)) {
                return r;
            }

            double d = Double.MAX_VALUE;

            for (var entry : self.blocks) {
                PlatformBlockState dat = entry.getValue();

                if (B.isAir(dat)) {
                    continue;
                }

                double dx = entry.getKey().distanceSquared(vv);

                if (dx < d) {
                    d = dx;
                    r = dat;
                }
            }

            return r;
        } finally {
            self.readLock.unlock();
        }
    }
}

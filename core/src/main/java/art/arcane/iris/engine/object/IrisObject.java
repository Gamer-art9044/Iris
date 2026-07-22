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

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.PlacedObject;
import art.arcane.iris.engine.framework.placer.HeightmapObjectPlacer;
import art.arcane.iris.platform.bukkit.BukkitBlockState;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.common.data.VectorMap;
import art.arcane.volmlib.util.format.Form;
import art.arcane.iris.util.project.interpolation.IrisInterpolation;
import art.arcane.iris.util.project.noise.SimplexNoise;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.math.BlockPosition;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.common.math.AxisAlignedBB;
import art.arcane.iris.util.common.math.IrisBlockVector;
import art.arcane.iris.util.common.math.IrisVector;
import art.arcane.iris.util.common.math.Vector3i;
import art.arcane.volmlib.util.matter.MatterMarker;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import art.arcane.iris.util.common.scheduling.jobs.Job;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.util.project.stream.ProceduralStream;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.stream.StreamSupport;

@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class IrisObject extends IrisRegistrant {
    protected static final IrisVector HALF = new IrisVector(0.5, 0.5, 0.5);
    static final class States {
        static final PlatformBlockState AIR = B.getState("CAVE_AIR");
        static final PlatformBlockState STONE = B.getState("STONE");
        static final PlatformBlockState VAIR = B.getState("VOID_AIR");
        static final PlatformBlockState VAIR_DEBUG = B.getState("COBWEB");
        static final PlatformBlockState[] SNOW_LAYERS = new PlatformBlockState[]{B.getState("minecraft:snow[layers=1]"), B.getState("minecraft:snow[layers=2]"), B.getState("minecraft:snow[layers=3]"), B.getState("minecraft:snow[layers=4]"), B.getState("minecraft:snow[layers=5]"), B.getState("minecraft:snow[layers=6]"), B.getState("minecraft:snow[layers=7]"), B.getState("minecraft:snow[layers=8]")};
    }
    private static final long IMPLAUSIBLE_BEDROCK_WARN_THROTTLE_MS = 5000L;
    private static final long VACUUM_WAVE_SEED = 7392113L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> IMPLAUSIBLE_BEDROCK_WARNS = new java.util.concurrent.ConcurrentHashMap<>();
    protected transient final Lock readLock;
    protected transient final Lock writeLock;
    @Getter
    @Setter
    protected transient volatile boolean smartBored = false;
    @Setter
    protected transient AtomicCache<AxisAlignedBB> aabb = new AtomicCache<>();
    private transient final AtomicCache<KList<IrisBlockVector>> surfaceSupportOffsets = new AtomicCache<>();
    @Getter
    private VectorMap<PlatformBlockState> blocks;
    @Getter
    private VectorMap<TileData> states;
    @Getter
    @Setter
    private int w;
    @Getter
    @Setter
    private int d;
    @Getter
    @Setter
    private int h;
    @Getter
    @Setter
    private transient Vector3i center;
    @Getter
    private transient Vector3i shrinkOffset;

    public IrisObject(int w, int h, int d) {
        blocks = new VectorMap<>();
        states = new VectorMap<>();
        this.w = w;
        this.h = h;
        this.d = d;
        center = new Vector3i(w / 2, h / 2, d / 2);
        shrinkOffset = new Vector3i(0, 0, 0);
        var lock = new ReentrantReadWriteLock();
        readLock = lock.readLock();
        writeLock = lock.writeLock();
    }

    public IrisObject() {
        this(0, 0, 0);
    }

    public static AxisAlignedBB getAABBFor(IrisBlockVector size) {
        IrisBlockVector center = new IrisBlockVector(size.getX() / 2, size.getY() / 2, size.getZ() / 2);
        IrisVector min = new IrisBlockVector(0, 0, 0).subtract(center);
        IrisVector max = new IrisBlockVector(size.getX() - 1, size.getY() - 1, size.getZ() - 1).subtract(center);
        return new AxisAlignedBB(new IrisPosition(min.getBlockX(), min.getBlockY(), min.getBlockZ()),
                new IrisPosition(max.getBlockX(), max.getBlockY(), max.getBlockZ()));
    }

    public static IrisBlockVector sampleSize(File file) throws IOException {
        try (DataInputStream din = new DataInputStream(new FileInputStream(file))) {
            return new IrisBlockVector(din.readInt(), din.readInt(), din.readInt());
        }
    }

    private static List<IrisBlockVector> blocksBetweenTwoPoints(IrisVector loc1, IrisVector loc2) {
        List<IrisBlockVector> locations = new ArrayList<>();
        int topBlockX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int bottomBlockX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int topBlockY = Math.max(loc1.getBlockY(), loc2.getBlockY());
        int bottomBlockY = Math.min(loc1.getBlockY(), loc2.getBlockY());
        int topBlockZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());
        int bottomBlockZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());

        for (int x = bottomBlockX; x <= topBlockX; x++) {
            for (int z = bottomBlockZ; z <= topBlockZ; z++) {
                for (int y = bottomBlockY; y <= topBlockY; y++) {
                    locations.add(new IrisBlockVector(x, y, z));
                }
            }
        }
        return locations;
    }

    private static boolean shouldStilt(PlatformBlockState state) {
        if (!state.isOccluding()) {
            return false;
        }
        String material = materialKey(state);
        if (material.endsWith("_stairs") || material.endsWith("_slab")) {
            return false;
        }
        return !material.equals("minecraft:dirt_path");
    }

    private static String materialKey(PlatformBlockState state) {
        return IrisProceduralBlocks.materialKey(state);
    }

    public AxisAlignedBB getAABB() {
        return aabb.aquire(() -> getAABBFor(new IrisBlockVector(w, h, d)));
    }

    public void ensureSmartBored(boolean debug) {
        if (smartBored) {
            return;
        }

        PrecisionStopwatch p = PrecisionStopwatch.start();
        PlatformBlockState vair = debug ? States.VAIR_DEBUG : States.VAIR;
        writeLock.lock();
        AtomicInteger applied = new AtomicInteger();
        if (blocks.isEmpty()) {
            writeLock.unlock();
            IrisLogging.warn("Cannot Smart Bore " + getLoadKey() + " because it has 0 blocks in it.");
            smartBored = true;
            return;
        }

        IrisBlockVector max = new IrisBlockVector(Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE);
        IrisBlockVector min = new IrisBlockVector(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);

        for (IrisBlockVector i : blocks.keys()) {
            max.setX(Math.max(i.getX(), max.getX()));
            min.setX(Math.min(i.getX(), min.getX()));
            max.setY(Math.max(i.getY(), max.getY()));
            min.setY(Math.min(i.getY(), min.getY()));
            max.setZ(Math.max(i.getZ(), max.getZ()));
            min.setZ(Math.min(i.getZ(), min.getZ()));
        }

        BurstExecutor burst = MultiBurst.burst.burst();

        // Smash X
        for (int rayY = min.getBlockY(); rayY <= max.getBlockY(); rayY++) {
            int finalRayY = rayY;
            burst.queue(() -> {
                for (int rayZ = min.getBlockZ(); rayZ <= max.getBlockZ(); rayZ++) {
                    int start = Integer.MAX_VALUE;
                    int end = Integer.MIN_VALUE;

                    for (int ray = min.getBlockX(); ray <= max.getBlockX(); ray++) {
                        if (blocks.containsKey(new IrisBlockVector(ray, finalRayY, rayZ))) {
                            start = Math.min(ray, start);
                            end = Math.max(ray, end);
                        }
                    }

                    if (start != Integer.MAX_VALUE && end != Integer.MIN_VALUE) {
                        for (int i = start; i <= end; i++) {
                            IrisBlockVector v = new IrisBlockVector(i, finalRayY, rayZ);

                            if (!vair.equals(blocks.get(v))) {
                                blocks.computeIfAbsent(v, (vv) -> vair);
                                applied.getAndIncrement();
                            }
                        }
                    }
                }
            });
        }

        // Smash Y
        for (int rayX = min.getBlockX(); rayX <= max.getBlockX(); rayX++) {
            int finalRayX = rayX;
            burst.queue(() -> {
                for (int rayZ = min.getBlockZ(); rayZ <= max.getBlockZ(); rayZ++) {
                    int start = Integer.MAX_VALUE;
                    int end = Integer.MIN_VALUE;

                    for (int ray = min.getBlockY(); ray <= max.getBlockY(); ray++) {
                        if (blocks.containsKey(new IrisBlockVector(finalRayX, ray, rayZ))) {
                            start = Math.min(ray, start);
                            end = Math.max(ray, end);
                        }
                    }

                    if (start != Integer.MAX_VALUE && end != Integer.MIN_VALUE) {
                        for (int i = start; i <= end; i++) {
                            IrisBlockVector v = new IrisBlockVector(finalRayX, i, rayZ);

                            if (!vair.equals(blocks.get(v))) {
                                blocks.computeIfAbsent(v, (vv) -> vair);
                                applied.getAndIncrement();
                            }
                        }
                    }
                }
            });
        }

        // Smash Z
        for (int rayX = min.getBlockX(); rayX <= max.getBlockX(); rayX++) {
            int finalRayX = rayX;
            burst.queue(() -> {
                for (int rayY = min.getBlockY(); rayY <= max.getBlockY(); rayY++) {
                    int start = Integer.MAX_VALUE;
                    int end = Integer.MIN_VALUE;

                    for (int ray = min.getBlockZ(); ray <= max.getBlockZ(); ray++) {
                        if (blocks.containsKey(new IrisBlockVector(finalRayX, rayY, ray))) {
                            start = Math.min(ray, start);
                            end = Math.max(ray, end);
                        }
                    }

                    if (start != Integer.MAX_VALUE && end != Integer.MIN_VALUE) {
                        for (int i = start; i <= end; i++) {
                            IrisBlockVector v = new IrisBlockVector(finalRayX, rayY, i);

                            if (!vair.equals(blocks.get(v))) {
                                blocks.computeIfAbsent(v, (vv) -> vair);
                                applied.getAndIncrement();
                            }
                        }
                    }
                }
            });
        }

        burst.complete();
        smartBored = true;
        writeLock.unlock();
        IrisLogging.debug("Smart Bore: " + getLoadKey() + " in " + Form.duration(p.getMilliseconds(), 2) + " (" + Form.f(applied.get()) + ")");
    }

    public synchronized IrisObject copy() {
        IrisObject o = new IrisObject(w, h, d);
        o.setLoadKey(getLoadKey());
        o.setLoader(getLoader());
        o.setLoadFile(getLoadFile());
        o.setCenter(getCenter().clone());

        blocks.forEach((i, v) -> o.blocks.put(i.clone(), v));
        states.forEach((i, v) -> o.states.put(i.clone(), v.clone()));

        return o;
    }

    public void readLegacy(InputStream in) throws IOException {
        surfaceSupportOffsets.reset();
        DataInputStream din = new DataInputStream(in);
        this.w = din.readInt();
        this.h = din.readInt();
        this.d = din.readInt();
        center = new Vector3i(w / 2, h / 2, d / 2);
        int s = din.readInt();

        for (int i = 0; i < s; i++) {
            IrisBlockVector pos = new IrisBlockVector(din.readShort(), din.readShort(), din.readShort());
            PlatformBlockState data = B.getState(din.readUTF());
            if (isStructureMarker(data)) {
                continue;
            }
            blocks.put(pos, data);
        }

        if (din.available() == 0)
            return;

        try {
            int size = din.readInt();

            for (int i = 0; i < size; i++) {
                states.put(new IrisBlockVector(din.readShort(), din.readShort(), din.readShort()), TileData.read(din));
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    public void read(InputStream in) throws Throwable {
        surfaceSupportOffsets.reset();
        DataInputStream din = new DataInputStream(in);
        this.w = din.readInt();
        this.h = din.readInt();
        this.d = din.readInt();
        if (!din.readUTF().equals("Iris V2 IOB;")) {
            throw new HeaderException();
        }
        center = new Vector3i(w / 2, h / 2, d / 2);
        int s = din.readShort();
        int i;
        KList<String> palette = new KList<>();

        for (i = 0; i < s; i++) {
            palette.add(din.readUTF());
        }

        s = din.readInt();

        for (i = 0; i < s; i++) {
            IrisBlockVector pos = new IrisBlockVector(din.readShort(), din.readShort(), din.readShort());
            PlatformBlockState data = B.getState(palette.get(din.readShort()));
            if (isStructureMarker(data)) {
                continue;
            }
            blocks.put(pos, data);
        }

        s = din.readInt();

        for (i = 0; i < s; i++) {
            states.put(new IrisBlockVector(din.readShort(), din.readShort(), din.readShort()), TileData.read(din));
        }
    }

    private static boolean isStructureMarker(PlatformBlockState data) {
        if (data == null) {
            return false;
        }
        String material = materialKey(data);
        return material.equals("minecraft:jigsaw") || material.equals("minecraft:structure_block") || material.equals("minecraft:structure_void");
    }

    public void write(OutputStream o) throws IOException {
        DataOutputStream dos = new DataOutputStream(o);
        dos.writeInt(w);
        dos.writeInt(h);
        dos.writeInt(d);
        dos.writeUTF("Iris V2 IOB;");
        KList<String> palette = new KList<>();

        for (PlatformBlockState i : blocks.values()) {
            palette.addIfMissing(i.key());
        }

        dos.writeShort(palette.size());

        for (String i : palette) {
            dos.writeUTF(i);
        }

        dos.writeInt(blocks.size());

        for (var entry : blocks) {
            var i = entry.getKey();
            dos.writeShort(i.getBlockX());
            dos.writeShort(i.getBlockY());
            dos.writeShort(i.getBlockZ());
            dos.writeShort(palette.indexOf(entry.getValue().key()));
        }

        dos.writeInt(states.size());
        for (var entry : states) {
            var i = entry.getKey();
            dos.writeShort(i.getBlockX());
            dos.writeShort(i.getBlockY());
            dos.writeShort(i.getBlockZ());
            entry.getValue().toBinary(dos);
        }
    }

    public void write(OutputStream o, VolmitSender sender) throws IOException {
        AtomicReference<IOException> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new Job() {
            private int total = blocks.size() * 3 + states.size();
            private int c = 0;

            @Override
            public String getName() {
                return IrisLanguage.text(RuntimeUiMessages.JOB_SAVING_OBJECT);
            }

            @Override
            public void execute() {
                try {
                    DataOutputStream dos = new DataOutputStream(o);
                    dos.writeInt(w);
                    dos.writeInt(h);
                    dos.writeInt(d);
                    dos.writeUTF("Iris V2 IOB;");

                    KList<String> palette = new KList<>();

                    for (PlatformBlockState i : blocks.values()) {
                        palette.addIfMissing(i.key());
                        ++c;
                    }
                    total -= blocks.size() - palette.size();

                    dos.writeShort(palette.size());

                    for (String i : palette) {
                        dos.writeUTF(i);
                        ++c;
                    }

                    dos.writeInt(blocks.size());

                    for (var entry : blocks) {
                        var i = entry.getKey();
                        dos.writeShort(i.getBlockX());
                        dos.writeShort(i.getBlockY());
                        dos.writeShort(i.getBlockZ());
                        dos.writeShort(palette.indexOf(entry.getValue().key()));
                        ++c;
                    }

                    dos.writeInt(states.size());
                    for (var entry : states) {
                        var i = entry.getKey();
                        dos.writeShort(i.getBlockX());
                        dos.writeShort(i.getBlockY());
                        dos.writeShort(i.getBlockZ());
                        entry.getValue().toBinary(dos);
                        ++c;
                    }
                } catch (IOException e) {
                    ref.set(e);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void completeWork() {}

            @Override
            public int getTotalWork() {
                return total;
            }

            @Override
            public int getWorkCompleted() {
                return c;
            }
        }.execute(sender, true, () -> {});

        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while writing object", interrupted);
        }
        if (ref.get() != null)
            throw ref.get();
    }

    public void read(File file) throws IOException {
        try (var fin = new BufferedInputStream(new FileInputStream(file))) {
            read(fin);
        } catch (Throwable e) {
            if (!(e instanceof HeaderException))
                IrisLogging.reportError(e);
            try (var fin = new BufferedInputStream(new FileInputStream(file))) {
                readLegacy(fin);
            }
        }
    }

    public void write(File file) throws IOException {
        if (file == null) {
            return;
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            write(out);
        }
    }

    public void write(File file, VolmitSender sender) throws IOException {
        if (file == null) {
            return;
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            write(out, sender);
        }
    }

    public void shrinkwrap() {
        if (blocks.isEmpty()) return;
        IrisBlockVector min = new IrisBlockVector(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        IrisBlockVector max = new IrisBlockVector(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

        for (IrisBlockVector i : blocks.keys()) {
            min.setX(Math.min(min.getX(), i.getX()));
            min.setY(Math.min(min.getY(), i.getY()));
            min.setZ(Math.min(min.getZ(), i.getZ()));
            max.setX(Math.max(max.getX(), i.getX()));
            max.setY(Math.max(max.getY(), i.getY()));
            max.setZ(Math.max(max.getZ(), i.getZ()));
        }

        w = max.getBlockX() - min.getBlockX() + 1;
        h = max.getBlockY() - min.getBlockY() + 1;
        d = max.getBlockZ() - min.getBlockZ() + 1;
        center = new Vector3i(w / 2, h / 2, d / 2);

        Vector3i offset = new Vector3i(
                -center.getBlockX() - min.getBlockX(),
                -center.getBlockY() - min.getBlockY(),
                -center.getBlockZ() - min.getBlockZ()
        );
        if (offset.getBlockX() == 0 && offset.getBlockY() == 0 && offset.getBlockZ() == 0)
            return;

        VectorMap<PlatformBlockState> b = new VectorMap<>();
        VectorMap<TileData> s = new VectorMap<>();
        IrisBlockVector shift = new IrisBlockVector(offset.getX(), offset.getY(), offset.getZ());

        blocks.forEach((vector, data) -> {
            vector.add(shift);
            b.put(vector, data);
        });

        states.forEach((vector, data) -> {
            vector.add(shift);
            s.put(vector, data);
        });

        shrinkOffset = offset;
        blocks = b;
        states = s;
        surfaceSupportOffsets.reset();
    }

    public void clean() {
        VectorMap<PlatformBlockState> d = new VectorMap<>();
        d.putAll(blocks);

        VectorMap<TileData> dx = new VectorMap<>();
        dx.putAll(states);

        blocks = d;
        states = dx;
        surfaceSupportOffsets.reset();
    }

    public IrisBlockVector getSigned(int x, int y, int z) {
        if (x >= w || y >= h || z >= d) {
            throw new RuntimeException(x + " " + y + " " + z + " exceeds limit of " + w + " " + h + " " + d);
        }

        return new IrisBlockVector(x - center.getX(), y - center.getY(), z - center.getZ());
    }

    public void setUnsigned(int x, int y, int z, PlatformBlockState block) {
        surfaceSupportOffsets.reset();
        IrisBlockVector v = getSigned(x, y, z);

        if (block == null) {
            blocks.remove(v);
            states.remove(v);
        } else {
            blocks.put(v, block);
        }
    }

    public void setUnsignedTile(int x, int y, int z, TileData tile) {
        IrisBlockVector v = getSigned(x, y, z);

        if (tile == null) {
            states.remove(v);
        } else {
            states.put(v, tile);
        }
    }

    public void setUnsigned(int x, int y, int z, Block block, boolean legacy) {
        surfaceSupportOffsets.reset();
        IrisBlockVector v = getSigned(x, y, z);

        if (block == null) {
            blocks.remove(v);
            states.remove(v);
        } else {
            BlockData data = block.getBlockData();
            blocks.put(v, BukkitBlockState.of(data));
            TileData state = TileData.getTileState(block, legacy);
            if (state != null) {
                IrisLogging.debug("Saved State " + v);
                states.put(v, state);
            }
        }
    }

    public int place(int x, int z, IObjectPlacer placer, IrisObjectPlacement config, RNG rng, IrisData rdata) {
        return place(x, -1, z, placer, config, rng, rdata);
    }

    public int place(int x, int z, IObjectPlacer placer, IrisObjectPlacement config, RNG rng, CarveResult c, IrisData rdata) {
        return place(x, -1, z, placer, config, rng, null, c, rdata);
    }

    public int place(int x, int yv, int z, IObjectPlacer placer, IrisObjectPlacement config, RNG rng, IrisData rdata) {
        return place(x, yv, z, placer, config, rng, null, null, rdata);
    }

    public int place(Location loc, IObjectPlacer placer, IrisObjectPlacement config, RNG rng, IrisData rdata) {
        return place(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), placer, config, rng, rdata);
    }

    public int place(int x, int yv, int z, IObjectPlacer oplacer, IrisObjectPlacement config, RNG rng, BiConsumer<BlockPosition, PlatformBlockState> listener, CarveResult c, IrisData rdata) {
        IObjectPlacer placer = config.getHeightmap() != null ? new HeightmapObjectPlacer(rng, x, yv, z, config, oplacer) : oplacer;

        if (rdata != null) {
            // Slope condition
            if (!config.getSlopeCondition().isDefault() &&
                    !config.getSlopeCondition().isValid(rdata.getEngine().getComplex().getSlopeStream().get(x, z)) && !config.isForcePlace()) {
                return -1;
            }

//            if (config.getCarvingSupport().supportsSurface()) {
//                int y = placer.getHighest(x, z, rdata);
//                if (placer.isCarved(x, y, z)) {
//                    return -1;
//                }
//            }

            // Rotation calculation
            int slopeRotationY = 0;
            ProceduralStream<Double> heightStream = rdata.getEngine().getComplex().getHeightStream();
            if (config.isRotateTowardsSlope()) {
                // Whichever side of the rectangle that bounds the object is lowest is the 'direction' of the slope (simply said).
                double hNorth = heightStream.get(x, z + ((float) d) / 2);
                double hEast = heightStream.get(x + ((float) w) / 2, z);
                double hSouth = heightStream.get(x, z - ((float) d) / 2);
                double hWest = heightStream.get(x - ((float) w) / 2, z);
                double min = Math.min(Math.min(hNorth, hEast), Math.min(hSouth, hWest));
                if (min == hNorth) {
                    slopeRotationY = 0;
                } else if (min == hEast) {
                    slopeRotationY = 90;
                } else if (min == hSouth) {
                    slopeRotationY = 180;
                } else if (min == hWest) {
                    slopeRotationY = 270;
                }

                double newRotation = config.getRotation().getYAxis().getMin() + slopeRotationY;
                IrisObjectRotation originalRotation = config.getRotation();
                IrisObjectRotation slopeRotation = new IrisObjectRotation();
                slopeRotation.setXAxis(originalRotation.getXAxis());
                slopeRotation.setZAxis(originalRotation.getZAxis());
                if (newRotation == 0) {
                    slopeRotation.setYAxis(new IrisAxisRotationClamp(false, false, 0, 0, 90));
                    slopeRotation.setEnabled(originalRotation.canRotateX() || originalRotation.canRotateZ());
                } else {
                    slopeRotation.setYAxis(new IrisAxisRotationClamp(true, false, newRotation, newRotation, 90));
                    slopeRotation.setEnabled(true);
                }
                config = config.toPlacement(config.getPlace().toArray(new String[0]));
                config.setRotation(slopeRotation);
            }
        }

        if (config.isSmartBore()) {
            ensureSmartBored(placer.isDebugSmartBore());
        }

        boolean warped = !config.getWarp().isFlat();
        boolean rawStructurePiece = config.getMode() == ObjectPlaceMode.STRUCTURE_PIECE;
        boolean organicFloor = config.getMode() == ObjectPlaceMode.ORGANIC_STILT;
        boolean ceilingHang = config.getMode() == ObjectPlaceMode.CEILING_HANG;
        boolean organic = organicFloor || ceilingHang;
        boolean vacuuming = IrisObjectVacuum.isVacuumMode(config.getMode());
        boolean stilting = (config.getMode().equals(ObjectPlaceMode.STILT) || config.getMode().equals(ObjectPlaceMode.FAST_STILT) ||
                config.getMode() == ObjectPlaceMode.MIN_STILT || config.getMode() == ObjectPlaceMode.FAST_MIN_STILT ||
                config.getMode() == ObjectPlaceMode.CENTER_STILT || config.getMode() == ObjectPlaceMode.ERODE_STILT || organic);
        boolean eroding = config.getMode() == ObjectPlaceMode.ERODE_STILT;
        KMap<Position2, Integer> heightmap = config.getSnow() > 0 ? new KMap<>() : null;
        int spinx = rng.imax() / 1000;
        int spiny = rng.imax() / 1000;
        int spinz = rng.imax() / 1000;
        int rty = config.getRotation().rotate(new IrisBlockVector(0, getCenter().getBlockY(), 0), spinx, spiny, spinz).getBlockY();
        int ty = config.getTranslate().translate(new IrisBlockVector(0, getCenter().getBlockY(), 0), config.getRotation(), spinx, spiny, spinz).getBlockY();
        int y = -1;
        int xx, zz;
        int yrand = config.getTranslate().getYRandom();
        yrand = yrand > 0 ? rng.i(0, yrand) : yrand < 0 ? rng.i(yrand, 0) : yrand;
        boolean bail = false;

        if (config.isFromBottom()) {
            // todo Convert this to a dedicated mode.
            y = (getH() + 1) + rty;
            if (!config.isForcePlace()) {
                if (shouldBailForCarvingAnchor(placer, config, x, y, z)) {
                    bail = true;
                }
            }
        } else  if (yv < 0) {
            if (config.getMode().equals(ObjectPlaceMode.CENTER_HEIGHT) || config.getMode() == ObjectPlaceMode.CENTER_STILT
                    || organic || vacuuming) {
                y = (c != null ? c.getSurface() : placer.getHighest(x, z, getLoader(), config.isUnderwater())) + rty;
                if (!config.isForcePlace()) {
                    if (shouldBailForCarvingAnchor(placer, config, x, y, z)) {
                        bail = true;
                    }
                }
            } else if (config.getMode().equals(ObjectPlaceMode.MAX_HEIGHT) || config.getMode().equals(ObjectPlaceMode.STILT)) {
                IrisBlockVector offset = new IrisBlockVector(config.getTranslate().getX(), config.getTranslate().getY(), config.getTranslate().getZ());
                IrisBlockVector rotatedDimensions = config.getRotation().rotate(new IrisBlockVector(getW(), getH(), getD()), spinx, spiny, spinz).clone();
                int xLength = (rotatedDimensions.getBlockX() / 2) + offset.getBlockX();
                int minX = Math.min(x - xLength, x + xLength);
                int maxX = Math.max(x - xLength, x + xLength);
                int zLength = (rotatedDimensions.getBlockZ() / 2) + offset.getBlockZ();
                int minZ = Math.min(z - zLength, z + zLength);
                int maxZ = Math.max(z - zLength, z + zLength);
                for (int i = minX; i <= maxX; i++) {
                    for (int ii = minZ; ii <= maxZ; ii++) {
                        int h = placer.getHighest(i, ii, getLoader(), config.isUnderwater()) + rty;
                        if (!config.isForcePlace()) {
                            if (shouldBailForCarvingAnchor(placer, config, i, h, ii)) {
                                bail = true;
                                break;
                            }
                        }
                        if (h > y)
                            y = h;
                    }
                }
            } else if (config.getMode().equals(ObjectPlaceMode.FAST_MAX_HEIGHT) || config.getMode().equals(ObjectPlaceMode.FAST_STILT)) {
                IrisBlockVector offset = new IrisBlockVector(config.getTranslate().getX(), config.getTranslate().getY(), config.getTranslate().getZ());
                IrisBlockVector rotatedDimensions = config.getRotation().rotate(new IrisBlockVector(getW(), getH(), getD()), spinx, spiny, spinz).clone();

                int xRadius = (rotatedDimensions.getBlockX() / 2);
                int xLength = xRadius + offset.getBlockX();
                int minX = Math.min(x - xLength, x + xLength);
                int maxX = Math.max(x - xLength, x + xLength);
                int zRadius = (rotatedDimensions.getBlockZ() / 2);
                int zLength = zRadius + offset.getBlockZ();
                int minZ = Math.min(z - zLength, z + zLength);
                int maxZ = Math.max(z - zLength, z + zLength);

                for (int i = minX; i <= maxX; i += Math.abs(xRadius) + 1) {
                    for (int ii = minZ; ii <= maxZ; ii += Math.abs(zRadius) + 1) {
                        int h = placer.getHighest(i, ii, getLoader(), config.isUnderwater()) + rty;
                        if (!config.isForcePlace()) {
                            if (shouldBailForCarvingAnchor(placer, config, i, h, ii)) {
                                bail = true;
                                break;
                            }
                        }
                        if (h > y)
                            y = h;
                    }
                }
            } else if (config.getMode().equals(ObjectPlaceMode.MIN_HEIGHT) || config.getMode() == ObjectPlaceMode.MIN_STILT) {
                y = rdata.getEngine().getHeight() + 1;
                IrisBlockVector offset = new IrisBlockVector(config.getTranslate().getX(), config.getTranslate().getY(), config.getTranslate().getZ());
                IrisBlockVector rotatedDimensions = config.getRotation().rotate(new IrisBlockVector(getW(), getH(), getD()), spinx, spiny, spinz).clone();

                int xLength = (rotatedDimensions.getBlockX() / 2) + offset.getBlockX();
                int minX = Math.min(x - xLength, x + xLength);
                int maxX = Math.max(x - xLength, x + xLength);
                int zLength = (rotatedDimensions.getBlockZ() / 2) + offset.getBlockZ();
                int minZ = Math.min(z - zLength, z + zLength);
                int maxZ = Math.max(z - zLength, z + zLength);
                for (int i = minX; i <= maxX; i++) {
                    for (int ii = minZ; ii <= maxZ; ii++) {
                        int h = placer.getHighest(i, ii, getLoader(), config.isUnderwater()) + rty;
                        if (!config.isForcePlace()) {
                            if (shouldBailForCarvingAnchor(placer, config, i, h, ii)) {
                                bail = true;
                                break;
                            }
                        }
                        if (h < y) {
                            y = h;
                        }
                    }
                }
            } else if (config.getMode().equals(ObjectPlaceMode.FAST_MIN_HEIGHT) || config.getMode() == ObjectPlaceMode.FAST_MIN_STILT) {
                y = rdata.getEngine().getHeight() + 1;
                IrisBlockVector offset = new IrisBlockVector(config.getTranslate().getX(), config.getTranslate().getY(), config.getTranslate().getZ());
                IrisBlockVector rotatedDimensions = config.getRotation().rotate(new IrisBlockVector(getW(), getH(), getD()), spinx, spiny, spinz).clone();

                int xRadius = (rotatedDimensions.getBlockX() / 2);
                int xLength = xRadius + offset.getBlockX();
                int minX = Math.min(x - xLength, x + xLength);
                int maxX = Math.max(x - xLength, x + xLength);
                int zRadius = (rotatedDimensions.getBlockZ() / 2);
                int zLength = zRadius + offset.getBlockZ();
                int minZ = Math.min(z - zLength, z + zLength);
                int maxZ = Math.max(z - zLength, z + zLength);

                for (int i = minX; i <= maxX; i += Math.abs(xRadius) + 1) {
                    for (int ii = minZ; ii <= maxZ; ii += Math.abs(zRadius) + 1) {
                        int h = placer.getHighest(i, ii, getLoader(), config.isUnderwater()) + rty;
                        if (!config.isForcePlace()) {
                            if (shouldBailForCarvingAnchor(placer, config, i, h, ii)) {
                                bail = true;
                                break;
                            }
                        }
                        if (h < y) {
                            y = h;
                        }
                    }
                }
            } else if (config.getMode().equals(ObjectPlaceMode.PAINT)) {
                y = placer.getHighest(x, z, getLoader(), config.isUnderwater()) + rty;
                if (!config.isForcePlace()) {
                    if (shouldBailForCarvingAnchor(placer, config, x, y, z)) {
                        bail = true;
                    }
                }
            } else if (config.getMode().equals(ObjectPlaceMode.FLOATING)) {
                y = rty;
            }
        } else {
            y = yv;
            if (!config.isForcePlace() && !rawStructurePiece) {
                if (shouldBailForCarvingAnchor(placer, config, x, y, z)) {
                    bail = true;
                }
            }
        }

        if (yv >= 0 && config.isBottom() && !rawStructurePiece) {
            y += Math.floorDiv(h, 2);
            CarvingMode carvingMode = config.getCarvingSupport();
            if (!config.isForcePlace() && !carvingMode.equals(CarvingMode.CARVING_ONLY)) {
                if (shouldBailForCarvingAnchor(placer, config, x, y, z)) {
                    bail = true;
                }
            }
        }

        if (yv < 0
                && !config.isForcePlace()
                && !config.isFromBottom()
                && config.getMode() != ObjectPlaceMode.FLOATING
                && !rawStructurePiece
                && config.getCarvingSupport().supportsSurface()
                && placer.getEngine() != null
                && placer.getEngine().getDimension().isBedrock()
                && y <= 1) {
            warnImplausibleBedrockPlacement(placer, config, x, y, z);
            return -1;
        }

        if (bail && !config.isForcePlace()) {
            return -1;
        }

        if (yv < 0
                && !config.isForcePlace()
                && !config.isFromBottom()
                && config.getMode() != ObjectPlaceMode.FLOATING
                && !rawStructurePiece
                && !vacuuming
                && !config.isUnderwater()
                && !config.isOnwater()
                && config.getCarvingSupport().supportsSurface()
                && IrisSurfaceOpening.isOpen(oplacer, getLoader(), x, z, config.getTranslate(),
                        config.getRotation(), spinx, spiny, spinz, getSurfaceSupportOffsets())) {
            return -1;
        }

        if (yv < 0 && !config.getMode().equals(ObjectPlaceMode.FLOATING) && !rawStructurePiece) {
            if (!config.isForcePlace() && !config.isUnderwater() && !config.isOnwater() && placer.isUnderwater(x, z)) {
                return -1;
            }
        }

        if (!config.isForcePlace() && !rawStructurePiece && c != null && Math.max(0, h + yrand + ty) + 1 >= c.getHeight()) {
            return -1;
        }

        if (!config.isForcePlace() && !rawStructurePiece && config.isUnderwater() && y + rty + ty >= placer.getFluidHeight()) {
            return -1;
        }

        if (!config.isForcePlace() && !rawStructurePiece && !config.getClamp().canPlace(y + rty + ty, y - rty + ty)) {
            return -1;
        }

        if (!config.isForcePlace() && !rawStructurePiece && (!config.getAllowedCollisions().isEmpty() || !config.getForbiddenCollisions().isEmpty())) {
            Engine engine = rdata.getEngine();
            IrisBlockVector offset = new IrisBlockVector(config.getTranslate().getX(), config.getTranslate().getY(), config.getTranslate().getZ());
            for (int i = x - Math.floorDiv(w, 2) + (int) offset.getX(); i <= x + Math.floorDiv(w, 2) - (w % 2 == 0 ? 1 : 0) + (int) offset.getX(); i++) {
                for (int j = y - Math.floorDiv(h, 2)  + (int) offset.getY(); j <= y + Math.floorDiv(h, 2) - (h % 2 == 0 ? 1 : 0) + (int) offset.getY(); j++) {
                    for (int k = z - Math.floorDiv(d, 2) + (int) offset.getZ(); k <= z + Math.floorDiv(d, 2) - (d % 2 == 0 ? 1 : 0) + (int) offset.getZ(); k++) {
                        PlacedObject p = engine.getObjectPlacement(i, j, k);
                        if (p == null) continue;
                        IrisObject o = p.getObject();
                        if (o == null) continue;
                        String key = o.getLoadKey();
                        if (key != null) {
                            if (config.getForbiddenCollisions().contains(key) && !config.getAllowedCollisions().contains(key)) {
                                return -1;
                            }
                        }
                    }
                }
            }
        }

        if (config.isBore()) {
            IrisBlockVector offset = new IrisBlockVector(config.getTranslate().getX(), config.getTranslate().getY(), config.getTranslate().getZ());
            for (int i = x - Math.floorDiv(w, 2) + (int) offset.getX(); i <= x + Math.floorDiv(w, 2) - (w % 2 == 0 ? 1 : 0) + (int) offset.getX(); i++) {
                for (int j = y - Math.floorDiv(h, 2) - config.getBoreExtendMinY() + (int) offset.getY(); j <= y + Math.floorDiv(h, 2) + config.getBoreExtendMaxY() - (h % 2 == 0 ? 1 : 0) + (int) offset.getY(); j++) {
                    for (int k = z - Math.floorDiv(d, 2) + (int) offset.getZ(); k <= z + Math.floorDiv(d, 2) - (d % 2 == 0 ? 1 : 0) + (int) offset.getZ(); k++) {
                        placer.set(i, j, k, States.AIR);
                    }
                }
            }
        }

        int lowest = Integer.MAX_VALUE;
        int topLayer = Integer.MIN_VALUE;
        int vacuumLowest = Integer.MAX_VALUE;
        int vacuumHighest = Integer.MIN_VALUE;
        y += yrand;
        readLock.lock();

        KMap<IrisBlockVector, String> markers = null;

        try {
            if (config.getMarkers().isNotEmpty() && placer.getEngine() != null) {
                markers = new KMap<>();
                var list = StreamSupport.stream(blocks.keys().spliterator(), false)
                        .collect(KList.collector());

                for (IrisObjectMarker j : config.getMarkers()) {
                    IrisMarker marker = getLoader().getMarkerLoader().load(j.getMarker());

                    if (marker == null) {
                        continue;
                    }

                    int max = j.getMaximumMarkers();
                    for (IrisBlockVector i : list.shuffle()) {
                        if (max <= 0) {
                            break;
                        }

                        PlatformBlockState data = blocks.get(i);
                        if (data == null) {
                            continue;
                        }

                        for (PlatformBlockState k : j.getMark(rdata)) {
                            if (max <= 0) {
                                break;
                            }

                            if (j.isExact() ? k.matches(data) : materialKey(k).equals(materialKey(data))) {
                                boolean a = !blocks.containsKey((IrisBlockVector) i.clone().add(new IrisBlockVector(0, 1, 0)));
                                boolean fff = !blocks.containsKey((IrisBlockVector) i.clone().add(new IrisBlockVector(0, 2, 0)));

                                if (!marker.isEmptyAbove() || (a && fff)) {
                                    markers.put(i, j.getMarker());
                                    max--;
                                }
                            }
                        }
                    }
                }
            }

            for (var entry : blocks) {
                var g = entry.getKey();
                PlatformBlockState d;
                TileData tile = null;

                try {
                    d = entry.getValue();
                    tile = states.get(g);
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    IrisLogging.warn("Failed to read block node " + g.getBlockX() + "," + g.getBlockY() + "," + g.getBlockZ() + " in object " + getLoadKey() + " (cme)");
                    d = States.AIR;
                }

                if (d == null) {
                    IrisLogging.warn("Failed to read block node " + g.getBlockX() + "," + g.getBlockY() + "," + g.getBlockZ() + " in object " + getLoadKey() + " (null)");
                    d = States.AIR;
                }

                IrisBlockVector i = g.clone();
                PlatformBlockState data = d;
                i = config.getRotation().rotate(i.clone(), spinx, spiny, spinz).clone();
                if (ceilingHang) {
                    i.setY(-i.getBlockY());
                }
                i = config.getTranslate().translate(i.clone(), config.getRotation(), spinx, spiny, spinz).clone();

                if (stilting && shouldStilt(data)) {
                    if (i.getBlockY() < lowest) {
                        lowest = i.getBlockY();
                    }
                    if (i.getBlockY() > topLayer) {
                        topLayer = i.getBlockY();
                    }
                }

                if (placer.isPreventingDecay() && IrisProceduralBlocks.hasProperty(data, "distance") && "false".equals(IrisProceduralBlocks.propertyValue(data, "persistent"))) {
                    data = data.withProperty("persistent", "true");
                }

                for (IrisObjectReplace j : config.getEdit()) {
                    if (rng.chance(j.getChance())) {
                        for (PlatformBlockState k : j.getFind(rdata)) {
                            if (j.isExact() ? k.matches(data) : materialKey(k).equals(materialKey(data))) {
                                PlatformBlockState newData = j.getReplace(rng, i.getX() + x, i.getY() + y, i.getZ() + z, rdata);

                                if (materialKey(newData).equals(materialKey(data)) && !(newData.isCustom() || data.isCustom()))
                                    data = BlockDataMergeSupport.merge(data, newData);
                                else
                                    data = newData;

                                Optional<TileData> t = j.getReplace().getTile(rng, x, y, z, rdata);
                                if (t.isPresent()) {
                                    tile = t.get();
                                }
                            }
                        }
                    }
                }

                data = config.getRotation().rotate(data, spinx, spiny, spinz);
                xx = x + (int) Math.round(i.getX());

                int yy = y + (int) Math.round(i.getY());
                zz = z + (int) Math.round(i.getZ());

                if (warped) {
                    xx += config.warp(rng, i.getX() + x, i.getY() + y, i.getZ() + z, getLoader());
                    zz += config.warp(rng, i.getZ() + z, i.getY() + y, i.getX() + x, getLoader());
                }

                if (yv < 0 && (config.getMode().equals(ObjectPlaceMode.PAINT)) && !B.isVineBlock(data)) {
                    yy = (int) Math.round(i.getY()) + Math.floorDiv(h, 2) + placer.getHighest(xx, zz, getLoader(), config.isUnderwater());
                }

                if (heightmap != null) {
                    Position2 pos = new Position2(xx, zz);

                    if (!heightmap.containsKey(pos)) {
                        heightmap.put(pos, yy);
                    }

                    if (heightmap.get(pos) < yy) {
                        heightmap.put(pos, yy);
                    }
                }

                if (config.isMeld() && !rawStructurePiece && !placer.isSolid(xx, yy, zz)) {
                    continue;
                }

                if (IrisProceduralBlocks.hasProperty(data, "waterlogged") && shouldAutoWaterlogBlock(placer, config, yv, xx, yy, zz)) {
                    data = data.withProperty("waterlogged", "true");
                }

                if (B.isVineBlock(data)) {
                    data = attachVineFaces(placer, data, xx, yy, zz);
                }

                PlatformBlockState existingState = placer.get(xx, yy, zz);
                boolean wouldReplace = B.isSolid(existingState) && B.isVineBlock(data);
                String material = materialKey(data);
                boolean air = material.equals("minecraft:air") || material.equals("minecraft:cave_air");
                boolean place = shouldPlaceObjectBlock(rawStructurePiece, air, wouldReplace);

                if (data.isCustom() || place) {
                    placer.set(xx, yy, zz, data);
                    if (tile != null) {
                        placer.setTile(xx, yy, zz, tile);
                    }
                    if (markers != null && markers.containsKey(g)) {
                        placer.setData(xx, yy, zz, new MatterMarker(markers.get(g)));
                    }
                    if (listener != null) {
                        listener.accept(new BlockPosition(xx, yy, zz), data);
                    }
                    if (vacuuming && yy < vacuumLowest) {
                        vacuumLowest = yy;
                    }
                    if (vacuuming && yy > vacuumHighest) {
                        vacuumHighest = yy;
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
            IrisLogging.reportError(e);
        }
        readLock.unlock();

        if (stilting) {
            readLock.lock();
            IrisStiltSettings settings = config.getStiltSettings();

            double erodeCentroidX = 0;
            double erodeCentroidZ = 0;
            double erodeMaxDist = 1;
            if (eroding) {
                int centroidCount = 0;
                for (IrisBlockVector g : blocks.keys()) {
                    IrisBlockVector rot = config.getRotation().rotate(g.clone(), spinx, spiny, spinz).clone();
                    rot = config.getTranslate().translate(rot.clone(), config.getRotation(), spinx, spiny, spinz).clone();
                    if (rot.getBlockY() == lowest) {
                        PlatformBlockState bd = blocks.get(g);
                        if (bd != null && shouldStilt(bd)) {
                            erodeCentroidX += rot.getX();
                            erodeCentroidZ += rot.getZ();
                            centroidCount++;
                        }
                    }
                }
                if (centroidCount > 0) {
                    erodeCentroidX /= centroidCount;
                    erodeCentroidZ /= centroidCount;
                }
                for (IrisBlockVector g : blocks.keys()) {
                    IrisBlockVector rot = config.getRotation().rotate(g.clone(), spinx, spiny, spinz).clone();
                    rot = config.getTranslate().translate(rot.clone(), config.getRotation(), spinx, spiny, spinz).clone();
                    if (rot.getBlockY() == lowest) {
                        PlatformBlockState bd = blocks.get(g);
                        if (bd != null && shouldStilt(bd)) {
                            double dx = rot.getX() - erodeCentroidX;
                            double dz = rot.getZ() - erodeCentroidZ;
                            double dist = Math.sqrt(dx * dx + dz * dz);
                            if (dist > erodeMaxDist) {
                                erodeMaxDist = dist;
                            }
                        }
                    }
                }
            }

            for (IrisBlockVector g : blocks.keys()) {
                PlatformBlockState sourceData;
                try {
                    sourceData = blocks.get(g);
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    IrisLogging.warn("Failed to read block node " + g.getBlockX() + "," + g.getBlockY() + "," + g.getBlockZ() + " in object " + getLoadKey() + " (stilt cme)");
                    sourceData = States.AIR;
                }

                if (sourceData == null) {
                    IrisLogging.warn("Failed to read block node " + g.getBlockX() + "," + g.getBlockY() + "," + g.getBlockZ() + " in object " + getLoadKey() + " (stilt null)");
                    sourceData = States.AIR;
                }

                if (!shouldStilt(sourceData)) {
                    continue;
                }

                PlatformBlockState d = sourceData;
                if (settings != null && settings.getPalette() != null) {
                    d = config.getStiltSettings().getPalette().get(rng, x, y, z, rdata);
                } else {
                    String mat = materialKey(d);
                    if (mat.equals("minecraft:grass_block") || mat.equals("minecraft:mycelium") || mat.equals("minecraft:podzol") || mat.equals("minecraft:dirt_path")) {
                        d = B.getState("minecraft:dirt");
                    }
                }

                IrisBlockVector i = g.clone();
                i = config.getRotation().rotate(i.clone(), spinx, spiny, spinz).clone();
                if (ceilingHang) {
                    i.setY(-i.getBlockY());
                }
                i = config.getTranslate().translate(i.clone(), config.getRotation(), spinx, spiny, spinz).clone();
                d = config.getRotation().rotate(d, spinx, spiny, spinz);

                int targetLayer = ceilingHang ? topLayer : lowest;
                if (i.getBlockY() != targetLayer)
                    continue;

                for (IrisObjectReplace j : config.getEdit()) {
                    if (rng.chance(j.getChance())) {
                        for (PlatformBlockState k : j.getFind(rdata)) {
                            if (d == null) {
                                continue;
                            }
                            if (j.isExact() ? k.matches(d) : materialKey(k).equals(materialKey(d))) {
                                PlatformBlockState newData = j.getReplace(rng, i.getX() + x, i.getY() + y, i.getZ() + z, rdata);

                                if (materialKey(newData).equals(materialKey(d))) {
                                    d = BlockDataMergeSupport.merge(d, newData);
                                } else {
                                    d = newData;
                                }
                            }
                        }
                    }
                }

                if (d == null || !d.isOccluding())
                    continue;

                xx = x + (int) Math.round(i.getX());
                zz = z + (int) Math.round(i.getZ());

                if (warped) {
                    xx += config.warp(rng, i.getX() + x, i.getY() + y, i.getZ() + z, getLoader());
                    zz += config.warp(rng, i.getZ() + z, i.getY() + y, i.getX() + x, getLoader());
                }

                if (organic) {
                    int startY = targetLayer + y;
                    int maxScan = settings != null ? Math.max(1, settings.getOrganicMaxScan()) : 48;
                    int jitterMax = settings != null ? Math.max(0, settings.getOrganicJitter()) : 3;
                    double scratch = settings != null ? Math.max(0, Math.min(1, settings.getOrganicScratch())) : 0.55;
                    long colHash = ((long) xx * 341873128712L) ^ ((long) zz * 132897987541L);
                    int jitter = jitterMax > 0 ? (int) (Math.abs(colHash) % (jitterMax + 1)) : 0;

                    if (ceilingHang) {
                        int scan = 0;
                        int solidY = startY + 1;
                        while (scan < maxScan && !placer.isSolid(xx, solidY, zz)) {
                            solidY++;
                            scan++;
                        }
                        int topBound = (scan < maxScan ? solidY - 1 : startY + Math.min(maxScan, 8)) - jitter;
                        int total = topBound - startY;
                        for (int j = startY; j <= topBound; j++) {
                            if (scratch > 0 && total > 0) {
                                double ratio = (double) (j - startY) / total;
                                if (ratio > (1.0 - scratch)) {
                                    long sh = ((long) xx * 341873128712L) ^ ((long) j * 132897987541L) ^ ((long) zz * 735791245321L);
                                    double skipChance = (ratio - (1.0 - scratch)) / scratch;
                                    if ((Math.abs(sh) % 1000) / 1000.0 < skipChance * 0.7) {
                                        continue;
                                    }
                                }
                            }
                            placer.set(xx, j, zz, d);
                        }
                    } else {
                        int scan = 0;
                        int solidY = startY - 1;
                        while (scan < maxScan && !placer.isSolid(xx, solidY, zz)) {
                            solidY--;
                            scan++;
                        }
                        int bottomBound = (scan < maxScan ? solidY + 1 : startY - Math.min(maxScan, 8)) + jitter;
                        int total = startY - bottomBound;
                        for (int j = startY; j >= bottomBound; j--) {
                            if (scratch > 0 && total > 0) {
                                double ratio = (double) (startY - j) / total;
                                if (ratio > (1.0 - scratch)) {
                                    long sh = ((long) xx * 341873128712L) ^ ((long) j * 132897987541L) ^ ((long) zz * 735791245321L);
                                    double skipChance = (ratio - (1.0 - scratch)) / scratch;
                                    if ((Math.abs(sh) % 1000) / 1000.0 < skipChance * 0.7) {
                                        continue;
                                    }
                                }
                            }
                            placer.set(xx, j, zz, d);
                        }
                    }
                    continue;
                }

                int highest = placer.getHighest(xx, zz, getLoader(), true);

                if (IrisProceduralBlocks.hasProperty(d, "waterlogged") && shouldAutoWaterlogBlock(placer, config, yv, xx, highest, zz)) {
                    d = d.withProperty("waterlogged", "true");
                }

                int lowerBound = highest - 1;
                if (settings != null) {
                    lowerBound -= config.getStiltSettings().getOverStilt() - rng.i(0, config.getStiltSettings().getYRand());
                    if (settings.getYMax() != 0)
                        lowerBound -= Math.min(config.getStiltSettings().getYMax() - (lowest + y - highest), 0);
                }

                if (eroding) {
                    double dx = i.getX() - erodeCentroidX;
                    double dz = i.getZ() - erodeCentroidZ;
                    double normalizedDist = Math.sqrt(dx * dx + dz * dz) / erodeMaxDist;
                    normalizedDist = Math.min(normalizedDist, 1.0);
                    int totalDepth = (lowest + y) - lowerBound;
                    int erodeDepth = (int) (totalDepth * Math.pow(1.0 - normalizedDist, 1.5));
                    lowerBound = (lowest + y) - erodeDepth;
                }

                for (int j = lowest + y; j > lowerBound; j--) {
                    PlatformBlockState fluidState = placer.get(xx, j, zz);
                    if (B.isFluid(fluidState)) {
                        break;
                    }
                    if (eroding) {
                        int depth = (lowest + y) - j;
                        int totalDepth = (lowest + y) - lowerBound;
                        double depthRatio = totalDepth > 0 ? (double) depth / totalDepth : 0;
                        if (depthRatio > 0.4) {
                            long hash = ((long) (xx * 341873128712L) ^ ((long) j * 132897987541L) ^ ((long) zz * 735791245321L));
                            double skipChance = (depthRatio - 0.4) / 0.6;
                            if ((Math.abs(hash) % 1000) / 1000.0 < skipChance * 0.7) {
                                continue;
                            }
                        }
                    }

                    if (B.isVineBlock(d)) {
                        d = attachVineFaces(placer, d, xx, j, zz);
                    }
                    placer.set(xx, j, zz, d);
                }

            }

            readLock.unlock();
        }

        if (vacuuming && vacuumLowest != Integer.MAX_VALUE && placer.getEngine() != null) {
            IrisBlockVector rotDim = config.getRotation().rotate(new IrisBlockVector(getW(), getH(), getD()), spinx, spiny, spinz).clone();
            int lowX = IrisObjectVacuum.footprintLow(rotDim.getBlockX());
            int highX = IrisObjectVacuum.footprintHigh(rotDim.getBlockX());
            int lowZ = IrisObjectVacuum.footprintLow(rotDim.getBlockZ());
            int highZ = IrisObjectVacuum.footprintHigh(rotDim.getBlockZ());
            int centerX = x + config.getTranslate().getX();
            int centerZ = z + config.getTranslate().getZ();
            vacuumTerrain(placer, config, centerX, centerZ, lowX, highX, lowZ, highZ, vacuumLowest, vacuumHighest);
        }

        if (heightmap != null) {
            RNG rngx = rng.nextParallelRNG(3468854);

            for (Position2 i : heightmap.k()) {
                int vx = i.getX();
                int vy = heightmap.get(i);
                int vz = i.getZ();

                if (config.getSnow() > 0) {
                    int height = rngx.i(0, (int) (config.getSnow() * 7));
                    placer.set(vx, vy + 1, vz, States.SNOW_LAYERS[Math.max(Math.min(height, 7), 0)]);
                }
            }
        }

        return y;
    }

    static boolean shouldPlaceObjectBlock(boolean rawStructurePiece, boolean air, boolean wouldReplace) {
        return !wouldReplace && (rawStructurePiece || !air);
    }

    private void warnImplausibleBedrockPlacement(IObjectPlacer placer, IrisObjectPlacement config, int x, int y, int z) {
        String key = getLoadKey();
        String fingerprint = (key == null ? "<null>" : key) + "|" + config.getMode();
        long now = System.currentTimeMillis();
        Long last = IMPLAUSIBLE_BEDROCK_WARNS.get(fingerprint);
        if (last != null && now - last < IMPLAUSIBLE_BEDROCK_WARN_THROTTLE_MS) {
            return;
        }
        IMPLAUSIBLE_BEDROCK_WARNS.put(fingerprint, now);
        IrisLogging.warn("Implausible object placement rejected: "
                + (key == null ? "<no loadKey>" : key)
                + " resolved anchorY=" + y + " at (" + x + "," + z + ") mode=" + config.getMode()
                + " carving=" + config.getCarvingSupport()
                + ". Surface-anchored placement should never land on the bedrock row. "
                + "Height sampling returned a bogus value — not configured for floor placement "
                + "(forcePlace=false, fromBottom=false, mode!=FLOATING). Skipping to protect bedrock.");
    }

    private void vacuumTerrain(IObjectPlacer placer, IrisObjectPlacement config, int centerX, int centerZ, int lowX, int highX, int lowZ, int highZ, int baseY, int topY) {
        ObjectPlaceMode mode = config.getMode();
        IrisVacuumSettings settings = config.getVacuumSettings();
        int radius = IrisObjectVacuum.resolveRadius(mode, settings);
        int step = IrisObjectVacuum.resolveStep(mode);
        double falloff = IrisObjectVacuum.resolveFalloff(settings);
        int jitter = settings != null ? Math.max(0, settings.getOrganicJitter()) : 4;
        boolean organicEdge = mode == ObjectPlaceMode.VACUUM_ORGANIC;
        boolean wavyEdge = mode == ObjectPlaceMode.VACUUM_WAVY;
        double waveAmplitude = IrisObjectVacuum.resolveWaveAmplitude(settings);
        double waveScale = IrisObjectVacuum.resolveWaveScale(settings);
        SimplexNoise waveNoise = (wavyEdge && waveAmplitude > 0) ? new SimplexNoise(VACUUM_WAVE_SEED) : null;
        int meetY = baseY - 1;

        IrisComplex complex = placer.getEngine().getComplex();
        int worldMin = placer.getEngine().getMinHeight();
        int worldMax = worldMin + placer.getEngine().getHeight() - 1;

        for (int dx = lowX - radius; dx <= highX + radius; dx += step) {
            for (int dz = lowZ - radius; dz <= highZ + radius; dz += step) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                double effRadius = radius;
                if (organicEdge && jitter > 0) {
                    long h = ((long) cx * 341873128712L) ^ ((long) cz * 132897987541L);
                    double n = ((Math.abs(h) % 1000) / 1000.0) - 0.5;
                    effRadius = Math.max(1.0, radius + (n * 2.0 * jitter));
                }
                int origY = placer.getHighest(cx, cz, getLoader(), true);
                int targetY = IrisObjectVacuum.columnTargetY(dx, dz, lowX, highX, lowZ, highZ, effRadius, falloff, origY, meetY);
                if (waveNoise != null) {
                    int outX = IrisObjectVacuum.outset(dx, lowX, highX);
                    int outZ = IrisObjectVacuum.outset(dz, lowZ, highZ);
                    double waveDistance = Math.sqrt((double) (outX * outX) + (double) (outZ * outZ));
                    double sample = waveNoise.noiseSigned(cx * waveScale, cz * waveScale);
                    targetY += IrisObjectVacuum.waveOffset(waveDistance, effRadius, sample, waveAmplitude);
                }
                if (targetY == origY) {
                    continue;
                }
                targetY = Math.max(worldMin + 1, Math.min(worldMax, targetY));
                if (targetY > origY) {
                    PlatformBlockState fill = complex != null ? complex.getRockStream().get(cx, cz) : null;
                    if (B.isAir(fill)) {
                        fill = States.STONE;
                    }
                    for (int yy = origY + 1; yy <= targetY; yy++) {
                        placer.set(cx, yy, cz, fill);
                    }
                } else if (targetY < origY) {
                    boolean inside = IrisObjectVacuum.outset(dx, lowX, highX) == 0 && IrisObjectVacuum.outset(dz, lowZ, highZ) == 0;
                    int carveFloor = IrisObjectVacuum.carveFloorY(targetY, topY, inside);
                    for (int yy = origY; yy >= carveFloor; yy--) {
                        placer.set(cx, yy, cz, States.AIR);
                    }
                }
            }
        }
    }

    private boolean shouldBailForCarvingAnchor(IObjectPlacer placer, IrisObjectPlacement placement, int x, int y, int z) {
        CarvingMode carvingMode = placement.getCarvingSupport();
        return switch (carvingMode) {
            case SURFACE_ONLY -> placer.isCarved(x, y, z);
            case CARVING_ONLY -> !isCarvedCaveAnchor(placer, x, y, z);
            case ANYWHERE -> false;
        };
    }

    KList<IrisBlockVector> getSurfaceSupportOffsets() {
        return surfaceSupportOffsets.aquire(() -> {
            readLock.lock();
            try {
                int lowestY = Integer.MAX_VALUE;
                KList<IrisBlockVector> offsets = new KList<>();
                for (Map.Entry<IrisBlockVector, PlatformBlockState> entry : blocks) {
                    PlatformBlockState state = entry.getValue();
                    if (state == null || !state.isSolid() || state.isFoliage()) {
                        continue;
                    }
                    IrisBlockVector position = entry.getKey();
                    int blockY = position.getBlockY();
                    if (blockY < lowestY) {
                        lowestY = blockY;
                        offsets.clear();
                    }
                    if (blockY == lowestY) {
                        offsets.add(position.clone());
                    }
                }
                return offsets;
            } finally {
                readLock.unlock();
            }
        });
    }

    private boolean isCarvedCaveAnchor(IObjectPlacer placer, int x, int y, int z) {
        return placer.isCarved(x, y, z)
                || placer.isCarved(x, y - 1, z)
                || placer.isCarved(x, y - 2, z)
                || placer.isCarved(x, y - 3, z);
    }

    private boolean shouldAutoWaterlogBlock(IObjectPlacer placer, IrisObjectPlacement placement, int yv, int x, int y, int z) {
        if (!(placement.isWaterloggable() || placement.isUnderwater())) {
            return false;
        }

        if (yv >= 0 && placement.getCarvingSupport().equals(CarvingMode.CARVING_ONLY)) {
            return false;
        }

        PlatformBlockState existing = placer.get(x, y, z);
        if (existing == null) {
            return false;
        }

        return B.isWater(existing) || B.isWaterLogged(existing);
    }

    private static PlatformBlockState attachVineFaces(IObjectPlacer placer, PlatformBlockState data, int x, int y, int z) {
        PlatformBlockState result = data;
        for (String face : IrisProceduralBlocks.FACE_PROPERTIES) {
            if (!IrisProceduralBlocks.hasProperty(data, face)) {
                continue;
            }
            int[] mod = IrisProceduralBlocks.faceOffset(face);
            PlatformBlockState facing = placer.get(x + mod[0], y + mod[1], z + mod[2]);
            if (B.isSolid(facing) && !B.isVineBlock(facing)) {
                result = result.withProperty(face, "true");
            }
        }
        return result;
    }

    public IrisObject rotateCopy(IrisObjectRotation rt) {
        IrisObject copy = copy();
        copy.rotate(rt, 0, 0, 0);
        return copy;
    }

    public void rotate(IrisObjectRotation r, int spinx, int spiny, int spinz) {
        writeLock.lock();
        VectorMap<PlatformBlockState> d = new VectorMap<>();

        for (var entry : blocks) {
            d.put(r.rotate(entry.getKey(), spinx, spiny, spinz), r.rotate(entry.getValue(), spinx, spiny, spinz));
        }

        VectorMap<TileData> dx = new VectorMap<>();

        for (var entry : states) {
            dx.put(r.rotate(entry.getKey(), spinx, spiny, spinz), entry.getValue());
        }

        blocks = d;
        states = dx;
        surfaceSupportOffsets.reset();
        shrinkwrap();
        writeLock.unlock();
    }

    public void place(Location at) {
        readLock.lock();
        for (var entry : blocks) {
            var i = entry.getKey();
            Block b = at.clone().add(0, getCenter().getY(), 0).add(i.getX(), i.getY(), i.getZ()).getBlock();
            b.setBlockData((BlockData) Objects.requireNonNull(entry.getValue()).nativeHandle(), false);

            if (states.containsKey(i)) {
                IrisLogging.info(Objects.requireNonNull(states.get(i)).toString());
                Objects.requireNonNull(states.get(i)).toBukkitTry(b);
            }
        }
        readLock.unlock();
    }

    public void placeCenterY(Location at) {
        readLock.lock();
        for (var entry : blocks) {
            var i = entry.getKey();
            Block b = at.clone().add(getCenter().getX(), getCenter().getY(), getCenter().getZ()).add(i.getX(), i.getY(), i.getZ()).getBlock();
            b.setBlockData((BlockData) Objects.requireNonNull(entry.getValue()).nativeHandle(), false);

            if (states.containsKey(i)) {
                Objects.requireNonNull(states.get(i)).toBukkitTry(b);
            }
        }
        readLock.unlock();
    }

    public void unplaceCenterY(Location at) {
        readLock.lock();
        for (IrisBlockVector i : blocks.keys()) {
            at.clone().add(getCenter().getX(), getCenter().getY(), getCenter().getZ()).add(i.getX(), i.getY(), i.getZ()).getBlock().setBlockData((BlockData) States.AIR.nativeHandle(), false);
        }
        readLock.unlock();
    }

    public IrisObject scaled(double scale, IrisObjectPlacementScaleInterpolator interpolation) {
        if (interpolation == null) {
            interpolation = IrisObjectPlacementScaleInterpolator.NONE;
        }
        IrisVector sm1 = new IrisVector(scale - 1, scale - 1, scale - 1);
        scale = Math.max(0.001, Math.min(50, scale));
        if (scale < 1) {
            scale = scale - 0.0001;
        }

        IrisPosition l1 = getAABB().max();
        IrisPosition l2 = getAABB().min();
        VectorMap<PlatformBlockState> placeBlock = new VectorMap<>();

        IrisVector center = new IrisVector(getCenter().getX(), getCenter().getY(), getCenter().getZ());
        if (getH() == 2) {
            center = center.setY(center.getBlockY() + 0.5);
        }
        if (getW() == 2) {
            center = center.setX(center.getBlockX() + 0.5);
        }
        if (getD() == 2) {
            center = center.setZ(center.getBlockZ() + 0.5);
        }

        IrisObject oo = new IrisObject((int) Math.ceil((w * scale) + (scale * 2)), (int) Math.ceil((h * scale) + (scale * 2)), (int) Math.ceil((d * scale) + (scale * 2)));
        oo.setLoadKey(getLoadKey());
        oo.setLoader(getLoader());
        oo.setLoadFile(getLoadFile());

        readLock.lock();
        for (var entry : blocks) {
            PlatformBlockState bd = entry.getValue();
            placeBlock.put(entry.getKey().clone().add(HALF).subtract(center)
                    .multiply(scale).add(sm1).toBlockVector(), bd);
        }
        readLock.unlock();

        for (var entry : placeBlock) {
            IrisBlockVector v = entry.getKey();
            if (scale > 1) {
                for (IrisBlockVector vec : blocksBetweenTwoPoints(v.clone().add(center), v.clone().add(center).add(sm1))) {
                    oo.blocks.put(vec, entry.getValue());
                }
            } else {
                oo.setUnsigned(v.getBlockX(), v.getBlockY(), v.getBlockZ(), entry.getValue());
            }
        }

        if (scale > 1) {
            switch (interpolation) {
                case TRILINEAR -> oo.trilinear((int) Math.round(scale));
                case TRICUBIC -> oo.tricubic((int) Math.round(scale));
                case TRIHERMITE -> oo.trihermite((int) Math.round(scale));
            }
        }

        return oo;
    }

    public void trilinear(int rad) {
        writeLock.lock();
        VectorMap<PlatformBlockState> v = blocks;
        VectorMap<PlatformBlockState> b = new VectorMap<>();
        IrisPosition min = getAABB().min();
        IrisPosition max = getAABB().max();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    if (IrisInterpolation.getTrilinear(x, y, z, rad, (xx, yy, zz) -> {
                        PlatformBlockState data = v.get(new IrisBlockVector((int) xx, (int) yy, (int) zz));

                        if (B.isAir(data)) {
                            return 0;
                        }

                        return 1;
                    }) >= 0.5) {
                        b.put(new IrisBlockVector(x, y, z), nearestBlockData(x, y, z));
                    } else {
                        b.put(new IrisBlockVector(x, y, z), States.AIR);
                    }
                }
            }
        }

        blocks = b;
        surfaceSupportOffsets.reset();
        writeLock.unlock();
    }

    public void tricubic(int rad) {
        writeLock.lock();
        VectorMap<PlatformBlockState> v = blocks;
        VectorMap<PlatformBlockState> b = new VectorMap<>();
        IrisPosition min = getAABB().min();
        IrisPosition max = getAABB().max();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    if (IrisInterpolation.getTricubic(x, y, z, rad, (xx, yy, zz) -> {
                        PlatformBlockState data = v.get(new IrisBlockVector((int) xx, (int) yy, (int) zz));

                        if (B.isAir(data)) {
                            return 0;
                        }

                        return 1;
                    }) >= 0.5) {
                        b.put(new IrisBlockVector(x, y, z), nearestBlockData(x, y, z));
                    } else {
                        b.put(new IrisBlockVector(x, y, z), States.AIR);
                    }
                }
            }
        }

        blocks = b;
        surfaceSupportOffsets.reset();
        writeLock.unlock();
    }

    public void trihermite(int rad) {
        trihermite(rad, 0D, 0D);
    }

    public void trihermite(int rad, double tension, double bias) {
        writeLock.lock();
        VectorMap<PlatformBlockState> v = blocks;
        VectorMap<PlatformBlockState> b = new VectorMap<>();
        IrisPosition min = getAABB().min();
        IrisPosition max = getAABB().max();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    if (IrisInterpolation.getTrihermite(x, y, z, rad, (xx, yy, zz) -> {
                        PlatformBlockState data = v.get(new IrisBlockVector((int) xx, (int) yy, (int) zz));

                        if (B.isAir(data)) {
                            return 0;
                        }

                        return 1;
                    }, tension, bias) >= 0.5) {
                        b.put(new IrisBlockVector(x, y, z), nearestBlockData(x, y, z));
                    } else {
                        b.put(new IrisBlockVector(x, y, z), States.AIR);
                    }
                }
            }
        }

        blocks = b;
        surfaceSupportOffsets.reset();
        writeLock.unlock();
    }

    private PlatformBlockState nearestBlockData(int x, int y, int z) {
        IrisBlockVector vv = new IrisBlockVector(x, y, z);
        readLock.lock();
        PlatformBlockState r = blocks.get(vv);

        if (!B.isAir(r)) {
            return r;
        }

        double d = Double.MAX_VALUE;

        for (var entry : blocks) {
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
        readLock.unlock();

        return r;
    }

    public int volume() {
        return blocks.size();
    }

    @Override
    public String getFolderName() {
        return "objects";
    }

    @Override
    public String getTypeName() {
        return "Object";
    }

    @Override
    public void scanForErrors(JSONObject p, VolmitSender sender) {
    }

    private static class HeaderException extends IOException {
        public HeaderException() {
            super("Invalid Header");
        }
    }
}

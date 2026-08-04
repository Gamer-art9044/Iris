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

package art.arcane.iris.core.structure;

import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.WorldCreatorCompat;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.tools.TreePlausibilizer;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.io.IO;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.volmlib.util.localization.MessageArgument;
public final class FeatureImporter {
    public record Report(int total, int imported, int skipped, int failed) {
    }

    private static final String SCRATCH_WORLD_NAME = "iris_vanilla_import";
    private static final int CAPTURE_RADIUS = 16;
    private static final int CAPTURE_HEIGHT = 40;
    private static final int CELL_STRIDE = 48;
    private static final int CELL_COLUMNS = 16;
    private static final int PLACE_ATTEMPTS = 6;
    private static final long REGION_TIMEOUT_SECONDS = 30L;

    private FeatureImporter() {
    }

    public static Report importAllObjectFeatures(IrisData data, int variants, VolmitSender sender) {
        int wantVariants = Math.max(1, variants);

        List<Row> rows = parseRows(INMS.get().getObjectFeatureKeys());
        int total = rows.size();
        if (total == 0) {
            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_NO_VANILLA_TREE_OBJECT_FEATURES_ARE_EXPOSED_BY_ACTIVE_NMS_BINDING_IMPORTING));
            return new Report(0, 0, 0, 0);
        }

        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_IMPORTING_VANILLA_TREE_OBJECT_FEATURES_VARIANTS_EACH_INTO_SCRATCH_WORLD, MessageArgument.untrusted("total", String.valueOf(total)), MessageArgument.untrusted("wantVariants", String.valueOf(wantVariants))));

        World scratch = createScratchWorld(sender);
        if (scratch == null) {
            return new Report(total, 0, 0, total);
        }

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        int cellIndex = 0;

        try {
            for (Row row : rows) {
                try {
                    int written = 0;
                    Set<Long> hashes = new HashSet<>();
                    for (int v = 0; v < wantVariants; v++) {
                        CaptureResult capture = captureOne(scratch, row.key(), v, cellIndex++);
                        if (capture == null || !capture.placed() || capture.object() == null) {
                            continue;
                        }
                        IrisObject object = capture.object();
                        if (object.getBlocks().isEmpty()) {
                            continue;
                        }
                        object.shrinkwrap();
                        if (row.group().equals("trees") || row.group().equals("fallen_trees")) {
                            try {
                                TreePlausibilizer.apply(object, TreePlausibilizer.seedOf(row.key() + "#" + written), TreePlausibilizer.DEFAULT_REACH);
                            } catch (Throwable e) {
                                IrisLogging.reportError(e);
                            }
                        }
                        long hash = hashOf(object);
                        if (!hashes.add(hash)) {
                            continue;
                        }
                        write(objectFile(data, row.group(), row.safeName(), written), object);
                        written++;
                    }

                    if (written > 0) {
                        imported++;
                        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_OBJ_OBJECTS_VANILLA, MessageArgument.untrusted("key", String.valueOf(row.key())), MessageArgument.untrusted("group", String.valueOf(row.group())), MessageArgument.untrusted("safeName", String.valueOf(row.safeName())), MessageArgument.untrusted("written", String.valueOf(written))));
                    } else {
                        skipped++;
                        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_SKIP_FEATURE_PLACED_NOTHING_AFTER_RETRIES, MessageArgument.untrusted("key", String.valueOf(row.key()))));
                    }
                } catch (Throwable e) {
                    failed++;
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_FAIL, MessageArgument.untrusted("key", String.valueOf(row.key())), MessageArgument.untrusted("error", String.valueOf(e.getMessage()))));
                    IrisLogging.reportError(e);
                }

                int processed = imported + skipped + failed;
                if (processed % 25 == 0) {
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_IMPORTED_SKIPPED_FAILED, MessageArgument.untrusted("processed", String.valueOf(processed)), MessageArgument.untrusted("total", String.valueOf(total)), MessageArgument.untrusted("imported", String.valueOf(imported)), MessageArgument.untrusted("skipped", String.valueOf(skipped)), MessageArgument.untrusted("failed", String.valueOf(failed))));
                }
            }
        } finally {
            destroyScratchWorld(scratch, sender);
        }

        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_FEATURE_IMPORT_COMPLETE_FEATURES_WRITTEN_SKIPPED_FAILED_TOTAL, MessageArgument.untrusted("imported", String.valueOf(imported)), MessageArgument.untrusted("skipped", String.valueOf(skipped)), MessageArgument.untrusted("failed", String.valueOf(failed)), MessageArgument.untrusted("total", String.valueOf(total))));
        return new Report(total, imported, skipped, failed);
    }

    private static List<Row> parseRows(KList<String> raw) {
        List<Row> rows = new ArrayList<>();
        if (raw == null) {
            return rows;
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            int pipe = entry.indexOf('|');
            if (pipe <= 0 || pipe >= entry.length() - 1) {
                continue;
            }
            String group = entry.substring(0, pipe);
            String key = entry.substring(pipe + 1);
            if (key.isBlank()) {
                continue;
            }
            rows.add(new Row(group, key, BulkStructureImporter.templateNameFor(key)));
        }
        rows.sort(Comparator.comparing(r -> r.group() + "/" + r.safeName()));
        return rows;
    }

    private static CaptureResult captureOne(World world, String featureKey, int variant, int cellIndex) {
        int originX = (cellIndex % CELL_COLUMNS) * CELL_STRIDE;
        int originZ = (cellIndex / CELL_COLUMNS) * CELL_STRIDE;
        int centerX = originX + CELL_STRIDE / 2;
        int centerZ = originZ + CELL_STRIDE / 2;
        int anchorChunkX = centerX >> 4;
        int anchorChunkZ = centerZ >> 4;

        AtomicReference<IrisObject> objectRef = new AtomicReference<>();
        AtomicBoolean placedRef = new AtomicBoolean(false);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        boolean scheduled = J.runRegion(world, anchorChunkX, anchorChunkZ, () -> {
            try {
                int chunkMinX = (centerX - CAPTURE_RADIUS) >> 4;
                int chunkMaxX = (centerX + CAPTURE_RADIUS) >> 4;
                int chunkMinZ = (centerZ - CAPTURE_RADIUS) >> 4;
                int chunkMaxZ = (centerZ + CAPTURE_RADIUS) >> 4;
                for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                    for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                        world.getChunkAt(cx, cz);
                    }
                }

                int baseY = world.getHighestBlockYAt(centerX, centerZ) + 1;

                boolean placed = false;
                for (int attempt = 0; attempt < PLACE_ATTEMPTS; attempt++) {
                    if (INMS.get().placeFeature(world, centerX, baseY, centerZ, featureKey, featureSeed(featureKey, variant, attempt))) {
                        placed = true;
                        break;
                    }
                }
                placedRef.set(placed);

                if (placed) {
                    int width = CAPTURE_RADIUS * 2 + 1;
                    int depth = CAPTURE_RADIUS * 2 + 1;
                    int minX = centerX - CAPTURE_RADIUS;
                    int minZ = centerZ - CAPTURE_RADIUS;
                    IrisObject object = new IrisObject(width, CAPTURE_HEIGHT, depth);
                    for (int dx = 0; dx < width; dx++) {
                        for (int dy = 0; dy < CAPTURE_HEIGHT; dy++) {
                            for (int dz = 0; dz < depth; dz++) {
                                Block block = world.getBlockAt(minX + dx, baseY + dy, minZ + dz);
                                if (block.getType() == Material.AIR) {
                                    continue;
                                }
                                object.setUnsigned(dx, dy, dz, block, false);
                            }
                        }
                    }
                    objectRef.set(object);
                }

                for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                    for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                        try {
                            world.unloadChunk(cx, cz, false);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable e) {
                errorRef.set(e);
            } finally {
                latch.countDown();
            }
        });

        if (!scheduled) {
            return null;
        }

        try {
            if (!latch.await(REGION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (errorRef.get() != null) {
            IrisLogging.reportError(errorRef.get());
            return null;
        }
        return new CaptureResult(placedRef.get(), objectRef.get());
    }

    private static long featureSeed(String key, int variant, int attempt) {
        long h = key.hashCode() & 0xFFFFFFFFL;
        h = h * 0x9E3779B97F4A7C15L + variant * 0x100000001B3L + attempt * 31L + 0x5DEECE66DL;
        return h;
    }

    private static long hashOf(IrisObject object) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            object.write(out);
            byte[] bytes = out.toByteArray();
            long h = 1125899906842597L;
            for (byte b : bytes) {
                h = 31 * h + b;
            }
            return h;
        } catch (Throwable e) {
            return object.getW() * 73856093L + object.getH() * 19349663L + object.getD();
        }
    }

    private static File objectFile(IrisData data, String group, String safeName, int writtenIndex) {
        String suffix = writtenIndex == 0 ? "" : "_" + (writtenIndex + 1);
        return new File(data.getDataFolder(), "objects/vanilla/" + group + "/" + safeName + suffix + ".iob");
    }

    private static void write(File file, IrisObject object) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        object.write(file);
    }

    static World createScratchWorld(VolmitSender sender) {
        try {
            World existing = WorldIdentity.resolve(IrisWorldStorage.keyFromName(SCRATCH_WORLD_NAME)).orElse(null);
            if (existing != null) {
                return existing;
            }
            WorldCreator creator = WorldCreatorCompat.ofKey(IrisWorldStorage.keyFromName(SCRATCH_WORLD_NAME))
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.FLAT)
                    .generateStructures(false);
            return J.sfut(() -> INMS.get().createWorldAsync(creator))
                    .thenCompose(Function.identity())
                    .get();
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.FEATURE_IMPORTER_COULD_NOT_CREATE_SCRATCH_WORLD_FEATURE_IMPORT_SKIPPING_TREE_OBJECT_PASS, MessageArgument.untrusted("error", String.valueOf(e.getMessage()))));
            return null;
        }
    }

    static void destroyScratchWorld(World world, VolmitSender sender) {
        if (world == null) {
            return;
        }
        File folder = world.getWorldFolder();
        try {
            J.sfut(() -> {
                Bukkit.unloadWorld(world, false);
                return Boolean.TRUE;
            }).get();
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
        try {
            if (folder != null && folder.exists()) {
                IO.delete(folder);
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    private record Row(String group, String key, String safeName) {
    }

    private record CaptureResult(boolean placed, IrisObject object) {
    }
}

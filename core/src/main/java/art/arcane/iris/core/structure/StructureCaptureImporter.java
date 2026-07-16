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

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.collection.KList;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StructureCaptureImporter {
    public record Report(int total, int imported, int skipped, int failed) {
    }

    private static final int MAX_SPAN = 48;
    private static final int CELL_STRIDE = 128;
    private static final int CELL_COLUMNS = 8;
    private static final long REGION_TIMEOUT_SECONDS = 30L;

    private StructureCaptureImporter() {
    }

    public static Report importAllStructures(IrisData data, StructureImporter.Mode mode, VolmitSender sender) {
        StructureImporter.Mode activeMode = mode == null ? StructureImporter.Mode.ADD_ONLY : mode;
        if (!INMS.get().supportsStructureCapture()) {
            sender.sendMessage(C.YELLOW + "Structure capture is not supported by the active NMS binding; skipping the capture pass.");
            return new Report(0, 0, 0, 0);
        }

        KList<String> keys = INMS.get().getStructureKeys();
        if (keys == null || keys.isEmpty()) {
            return new Report(0, 0, 0, 0);
        }

        KList<String> targets = new KList<>();
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            String name = StructureImporter.deriveName(key);
            File structureFile = new File(data.getDataFolder(), "structures/" + name + ".json");
            if (structureFile.exists() && activeMode != StructureImporter.Mode.OVERWRITE) {
                continue;
            }
            targets.add(key);
        }

        int total = targets.size();
        if (total == 0) {
            sender.sendMessage(C.GRAY + "No code-generated structures left to capture (everything is already imported as a structure).");
            return new Report(0, 0, 0, 0);
        }

        sender.sendMessage(C.GREEN + "Capturing " + C.WHITE + total + C.GREEN + " code-generated structures (no NBT template) into a scratch world (skipping any wider/taller than " + MAX_SPAN + " blocks)...");

        World scratch = FeatureImporter.createScratchWorld(sender);
        if (scratch == null) {
            return new Report(total, 0, 0, total);
        }

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        int cellIndex = 0;

        try {
            for (String key : targets) {
                String name = StructureImporter.deriveName(key);
                try {
                    IrisObject object = captureOne(scratch, key, cellIndex++);
                    if (object == null || object.getBlocks().isEmpty()) {
                        skipped++;
                        sender.sendMessage(C.YELLOW + "[skip] " + key + ": did not place a capturable structure here (too large, wrong dimension, or no valid placement in a flat world). Stays vanilla-generated.");
                        continue;
                    }
                    object.shrinkwrap();
                    StructureImporter.Result result = StructureImporter.writeSinglePieceStructure(
                            data, name, key, object, "CENTER_HEIGHT", activeMode);
                    if (!result.success()) {
                        failed++;
                        sender.sendMessage(C.RED + "[fail] " + key + ": " + result.message());
                        continue;
                    }
                    imported++;
                    sender.sendMessage(C.GRAY + "[capture] " + key + " -> objects/" + name + ".iob (" + object.getW() + "x" + object.getH() + "x" + object.getD() + ")");
                } catch (Throwable e) {
                    failed++;
                    sender.sendMessage(C.RED + "[fail] " + key + ": " + e.getMessage());
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                }

                int processed = imported + skipped + failed;
                if (processed % 10 == 0) {
                    sender.sendMessage(C.GRAY + "..." + processed + "/" + total + " (" + imported + " captured, " + skipped + " skipped, " + failed + " failed)");
                }
            }
        } finally {
            FeatureImporter.destroyScratchWorld(scratch, sender);
        }

        sender.sendMessage(C.GREEN + "Structure capture complete: " + C.WHITE + imported + C.GREEN + " captured, " + C.WHITE + skipped + C.GREEN + " skipped, " + C.WHITE + failed + C.GREEN + " failed (" + C.WHITE + total + C.GREEN + " total).");
        return new Report(total, imported, skipped, failed);
    }

    private static IrisObject captureOne(World world, String key, int cellIndex) {
        int originX = (cellIndex % CELL_COLUMNS) * CELL_STRIDE;
        int originZ = (cellIndex / CELL_COLUMNS) * CELL_STRIDE;
        int anchorChunkX = (originX + CELL_STRIDE / 2) >> 4;
        int anchorChunkZ = (originZ + CELL_STRIDE / 2) >> 4;
        long seed = key.hashCode() * 0x9E3779B97F4A7C15L + cellIndex * 0x100000001B3L + 0x5DEECE66DL;

        AtomicReference<IrisObject> objectRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        boolean scheduled = J.runRegion(world, anchorChunkX, anchorChunkZ, () -> {
            try {
                int[] box = INMS.get().placeStructure(world, anchorChunkX, anchorChunkZ, key, seed, MAX_SPAN);
                if (box == null) {
                    return;
                }
                int width = box[3] - box[0] + 1;
                int height = box[4] - box[1] + 1;
                int depth = box[5] - box[2] + 1;
                if (width <= 0 || height <= 0 || depth <= 0) {
                    return;
                }
                IrisObject object = new IrisObject(width, height, depth);
                boolean any = false;
                for (int dx = 0; dx < width; dx++) {
                    for (int dy = 0; dy < height; dy++) {
                        for (int dz = 0; dz < depth; dz++) {
                            Block block = world.getBlockAt(box[0] + dx, box[1] + dy, box[2] + dz);
                            if (block.getType() == Material.AIR) {
                                continue;
                            }
                            object.setUnsigned(dx, dy, dz, block, false);
                            any = true;
                        }
                    }
                }
                if (any) {
                    objectRef.set(object);
                }

                int minCX = box[0] >> 4;
                int maxCX = box[3] >> 4;
                int minCZ = box[2] >> 4;
                int maxCZ = box[5] >> 4;
                for (int cx = minCX; cx <= maxCX; cx++) {
                    for (int cz = minCZ; cz <= maxCZ; cz++) {
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
            throw captureFailure(key, anchorChunkX, anchorChunkZ,
                    "region task was not accepted", null);
        }

        try {
            if (!latch.await(REGION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw captureFailure(key, anchorChunkX, anchorChunkZ,
                        "region task did not complete within " + REGION_TIMEOUT_SECONDS + " seconds", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw captureFailure(key, anchorChunkX, anchorChunkZ,
                    "interrupted while waiting for the region task", e);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            throw captureFailure(key, anchorChunkX, anchorChunkZ,
                    "platform placement failed", error);
        }
        return objectRef.get();
    }

    static IllegalStateException captureFailure(String key, int chunkX, int chunkZ,
                                                String detail, Throwable cause) {
        String message = "Structure capture failed for '" + key + "' at chunk "
                + chunkX + "," + chunkZ + ": " + detail;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}

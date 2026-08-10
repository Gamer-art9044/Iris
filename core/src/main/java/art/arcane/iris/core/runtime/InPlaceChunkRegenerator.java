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

package art.arcane.iris.core.runtime;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeProgressMessages;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.platform.EngineBukkitOps;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

public final class InPlaceChunkRegenerator {
    private static final int BIOME_STEP = 4;
    private static final int APPLY_AHEAD = 8;

    private final World world;
    private final Engine engine;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radius;
    private final ChunkJobReporter reporter;

    public InPlaceChunkRegenerator(World world, Engine engine, VolmitSender sender, int centerChunkX, int centerChunkZ, int radius) {
        this.world = world;
        this.engine = engine;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radius = Math.max(0, radius);
        this.reporter = new ChunkJobReporter(sender, IrisLanguage.text(RuntimeProgressMessages.CHUNK_TITLE_REGEN), world);
    }

    public void start() {
        reporter.start();
        Thread thread = new Thread(this::run, "Iris Regenerate");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        boolean error = false;
        try {
            reporter.setStage(IrisLanguage.text(RuntimeProgressMessages.CHUNK_STAGE_RESETTING_MANTLE));
            resetMantleMargin();

            List<int[]> targets = ChunkJobReporter.orderedTargets(centerChunkX, centerChunkZ, radius);
            reporter.setTotal(targets.size());
            reporter.setStage(IrisLanguage.text(RuntimeProgressMessages.CHUNK_STAGE_REGENERATING));
            regenerate(targets);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            error = true;
        } finally {
            reporter.finish(error);
        }
    }

    private void resetMantleMargin() {
        EngineMantle engineMantle = engine.getMantle();
        Mantle mantle = engineMantle.getMantle();
        int margin = radius + Math.max(engineMantle.getRadius(), engineMantle.getRealRadius()) + 1;
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dz = -margin; dz <= margin; dz++) {
                mantle.deleteChunk(centerChunkX + dx, centerChunkZ + dz);
            }
        }
    }

    private void regenerate(List<int[]> targets) throws InterruptedException {
        Semaphore inFlight = new Semaphore(APPLY_AHEAD);
        CountDownLatch allApplied = new CountDownLatch(targets.size());

        for (int[] target : targets) {
            int chunkX = target[0];
            int chunkZ = target[1];

            inFlight.acquire();
            MultiBurst.burst.lazy(() -> {
                TerrainChunk buffer = TerrainChunk.create(world);
                try {
                    engine.generate(chunkX << 4, chunkZ << 4, buffer, false);
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                    reporter.countApplied(false);
                    inFlight.release();
                    allApplied.countDown();
                    return;
                }

                boolean scheduled = J.runRegion(world, chunkX, chunkZ, () -> {
                    boolean ok = false;
                    try {
                        applyToLiveChunk(chunkX, chunkZ, buffer);
                        ok = true;
                    } catch (Throwable e) {
                        IrisLogging.reportError(e);
                    } finally {
                        reporter.countApplied(ok);
                        inFlight.release();
                        allApplied.countDown();
                    }
                });

                if (!scheduled) {
                    IrisLogging.warn("Regen could not schedule chunk apply at " + chunkX + "," + chunkZ + " in " + world.getName() + ".");
                    reporter.countApplied(false);
                    inFlight.release();
                    allApplied.countDown();
                }
            });
        }

        allApplied.await();
    }

    private void applyToLiveChunk(int chunkX, int chunkZ, TerrainChunk buffer) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        if (!INMS.get().applyChunkBlocks(chunk, buffer)) {
            applyBlockDiffs(chunk, chunk.getChunkSnapshot(false, false, false), buffer.getChunkData(), minHeight, maxHeight);
        }

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int x = 0; x < 16; x += BIOME_STEP) {
            for (int z = 0; z < 16; z += BIOME_STEP) {
                for (int y = minHeight; y < maxHeight; y += BIOME_STEP) {
                    PlatformBiome biome = buffer.getBiome(x, y, z);
                    if (biome != null) {
                        Biome bukkitBiome = (Biome) biome.nativeHandle();
                        if (world.getBiome(baseX + x, y, baseZ + z) != bukkitBiome) {
                            world.setBiome(baseX + x, y, baseZ + z, bukkitBiome);
                        }
                    }
                }
            }
        }

        EngineBukkitOps.updateChunk(engine, chunk);
        world.refreshChunk(chunkX, chunkZ);
    }

    public static void applyBlockDiffs(
            Chunk chunk,
            ChunkData generated,
            int minHeight,
            int maxHeight
    ) {
        applyBlockDiffs(
                chunk,
                chunk.getChunkSnapshot(false, false, false),
                generated,
                minHeight,
                maxHeight);
    }

    public static void applyBlockDiffs(
            Chunk chunk,
            ChunkSnapshot snapshot,
            ChunkData generated,
            int minHeight,
            int maxHeight
    ) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minHeight; y < maxHeight; y++) {
                    Material target = generated.getType(x, y, z);
                    if (snapshot.getBlockType(x, y, z) != target) {
                        chunk.getBlock(x, y, z).setBlockData(generated.getBlockData(x, y, z), false);
                        continue;
                    }

                    if (target == Material.AIR) {
                        continue;
                    }

                    BlockData data = generated.getBlockData(x, y, z);
                    if (!data.equals(snapshot.getBlockData(x, y, z))) {
                        chunk.getBlock(x, y, z).setBlockData(data, false);
                    }
                }
            }
        }
    }
}

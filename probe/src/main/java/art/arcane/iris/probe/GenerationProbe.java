/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

package art.arcane.iris.probe;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedComponent;
import art.arcane.iris.engine.framework.EngineEffects;
import art.arcane.iris.engine.framework.EngineEffectsProvider;
import art.arcane.iris.engine.framework.EnginePlatformHooks;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.framework.EngineWorldManager;
import art.arcane.iris.engine.framework.EngineWorldManagerProvider;
import art.arcane.iris.engine.framework.MeteredCache;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.hunk.Hunk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public final class GenerationProbe {
    private static final String DIMENSION_KEY = "overworld";
    private static final long SEED = 1337L;
    private static final int BIOME_STEP = 4;
    private static final List<Throwable> REPORTED = Collections.synchronizedList(new ArrayList<>());

    private static final class InertPreservation implements PreservationRegistry {
        @Override
        public void register(Thread thread) {
        }

        @Override
        public void register(ExecutorService service) {
        }

        @Override
        public void registerCache(MeteredCache cache) {
        }

        @Override
        public void dereference() {
        }
    }

    private static final class InertWorldManager implements EngineWorldManager {
        @Override
        public void close() {
        }

        @Override
        public int getEntityCount() {
            return 0;
        }

        @Override
        public int getChunkCount() {
            return 0;
        }

        @Override
        public double getEntitySaturation() {
            return 0;
        }

        @Override
        public void onTick() {
        }

        @Override
        public void onSave() {
        }
    }

    private static final class InertEffects extends EngineAssignedComponent implements EngineEffects {
        private InertEffects(Engine engine) {
            super(engine, "FX");
        }

        @Override
        public void updatePlayerMap() {
        }

        @Override
        public void tickRandomPlayer() {
        }
    }

    private static final class InertPlatformHooks implements EnginePlatformHooks {
    }

    public static void main(String[] args) throws Exception {
        IrisPlatforms.bind(new StubPlatform());
        StubPlatform.verbose(true);
        StubPlatform.errorSink(REPORTED::add);
        IrisServices.register(PreservationRegistry.class, new InertPreservation());
        IrisServices.register(EngineWorldManagerProvider.class, (EngineWorldManagerProvider) (Engine engine) -> new InertWorldManager());
        IrisServices.register(EngineEffectsProvider.class, (EngineEffectsProvider) InertEffects::new);
        IrisServices.register(EnginePlatformHooks.class, new InertPlatformHooks());

        File packSource = new File(args[0]);
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        if (!packSource.isDirectory()) {
            System.out.println("[genprobe] pack folder not found: " + packSource.getAbsolutePath());
            System.exit(1);
        }

        File workRoot = Files.createTempDirectory("iris-genprobe").toFile();
        File pack = clonePack(packSource, workRoot);
        System.out.println("[genprobe] pack: " + packSource.getAbsolutePath());
        System.out.println("[genprobe] work copy: " + pack.getAbsolutePath());
        System.out.println("[genprobe] radius: " + radius + " (" + ((2 * radius + 1) * (2 * radius + 1)) + " chunks)");

        Engine engine;
        try {
            IrisData data = IrisData.get(pack);
            IrisDimension dimension = data.getDimensionLoader().load(DIMENSION_KEY);
            if (dimension == null) {
                System.out.println("[genprobe] FAIL: dimension '" + DIMENSION_KEY + "' did not load from " + pack.getAbsolutePath());
                System.exit(1);
                return;
            }

            IrisWorld world = IrisWorld.builder()
                    .platformIdentity("iris:probe")
                    .name("probe")
                    .seed(SEED)
                    .worldFolder(new File(workRoot, "world"))
                    .minHeight(dimension.getMinHeight())
                    .maxHeight(dimension.getMaxHeight())
                    .build();
            EngineTarget target = new EngineTarget(world, dimension, data);
            engine = new IrisEngine(target, false);
        } catch (Throwable e) {
            System.out.println("[genprobe] FAIL: engine construction threw before generation could start");
            e.printStackTrace(System.out);
            printDistinctCauses("construction-time reported errors", drainReported());
            System.exit(1);
            return;
        }

        List<Throwable> initNoise = settleAndDrain();
        printDistinctCauses("engine-init reported errors (non-fatal, async)", initNoise);

        int height = engine.getTarget().getHeight();
        System.out.println("[genprobe] engine up: dim=" + engine.getDimension().getLoadKey()
                + " seed=" + engine.getSeedManager().getSeed()
                + " minY=" + engine.getMinHeight() + " maxY=" + engine.getMaxHeight());

        Map<String, Integer> distinct = new LinkedHashMap<>();
        Map<String, String> firstChunk = new LinkedHashMap<>();
        int ok = 0;
        int failed = 0;

        for (int cz = -radius; cz <= radius; cz++) {
            for (int cx = -radius; cx <= radius; cx++) {
                String at = cx + "," + cz;
                drainReported();
                List<Throwable> failures = new ArrayList<>();
                Hunk<PlatformBlockState> blocks = Hunk.newArrayHunk(16, height, 16);
                Hunk<PlatformBiome> biomes = Hunk.newArrayHunk(16, height, 16);
                try {
                    engine.generate(cx << 4, cz << 4, blocks, biomes, false);
                } catch (Throwable e) {
                    failures.add(e);
                }
                failures.addAll(drainReported());

                if (failures.isEmpty()) {
                    ok++;
                    System.out.println("[genprobe] chunk " + at + " OK " + hashChunk(blocks, biomes, height));
                } else {
                    failed++;
                    System.out.println("[genprobe] chunk " + at + " FAILED (" + failures.size() + " error(s))");
                    for (Throwable failure : failures) {
                        failure.printStackTrace(System.out);
                        String key = causeKey(failure);
                        distinct.merge(key, 1, Integer::sum);
                        firstChunk.putIfAbsent(key, at);
                    }
                }
            }
        }

        System.out.println("[genprobe] generated OK: " + ok + ", failed: " + failed);
        if (!distinct.isEmpty()) {
            System.out.println("[genprobe] DISTINCT ROOT CAUSES (" + distinct.size() + "):");
            for (Map.Entry<String, Integer> entry : distinct.entrySet()) {
                System.out.println("  x" + entry.getValue() + " (first at chunk " + firstChunk.get(entry.getKey()) + ") " + entry.getKey());
            }
        }
        System.out.println("[genprobe] RESULT: " + (failed == 0 ? "PASS" : "FAIL"));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static File clonePack(File source, File workRoot) throws Exception {
        File destination = new File(workRoot, source.getName());
        Process clone = new ProcessBuilder("cp", "-Rc", source.getAbsolutePath(), destination.getAbsolutePath())
                .inheritIO()
                .start();
        if (clone.waitFor() != 0) {
            Process copy = new ProcessBuilder("cp", "-R", source.getAbsolutePath(), destination.getAbsolutePath())
                    .inheritIO()
                    .start();
            if (copy.waitFor() != 0) {
                throw new IllegalStateException("Failed to copy pack to " + destination.getAbsolutePath());
            }
        }
        return destination;
    }

    private static List<Throwable> settleAndDrain() throws InterruptedException {
        List<Throwable> drained = new ArrayList<>();
        long quietSince = System.currentTimeMillis();
        long start = quietSince;
        while (System.currentTimeMillis() - start < 15000L) {
            List<Throwable> batch = drainReported();
            if (batch.isEmpty()) {
                if (System.currentTimeMillis() - quietSince >= 1500L) {
                    break;
                }
            } else {
                drained.addAll(batch);
                quietSince = System.currentTimeMillis();
            }
            Thread.sleep(50L);
        }
        return drained;
    }

    private static List<Throwable> drainReported() {
        synchronized (REPORTED) {
            List<Throwable> drained = new ArrayList<>(REPORTED);
            REPORTED.clear();
            return drained;
        }
    }

    private static void printDistinctCauses(String label, List<Throwable> errors) {
        if (errors.isEmpty()) {
            return;
        }
        Map<String, Integer> distinct = new LinkedHashMap<>();
        for (Throwable error : errors) {
            distinct.merge(causeKey(error), 1, Integer::sum);
        }
        System.out.println("[genprobe] " + label + " (" + distinct.size() + " distinct):");
        for (Map.Entry<String, Integer> entry : distinct.entrySet()) {
            System.out.println("  x" + entry.getValue() + " " + entry.getKey());
        }
        for (Throwable error : errors) {
            error.printStackTrace(System.out);
        }
    }

    private static String causeKey(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Throwable cause = failure;
        while (cause != null && !chain.contains(cause)) {
            chain.add(cause);
            cause = cause.getCause();
        }
        Throwable root = chain.get(chain.size() - 1);
        return root.getClass().getName() + ": " + root.getMessage() + " @ " + siteOf(chain);
    }

    private static String siteOf(List<Throwable> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (StackTraceElement frame : chain.get(i).getStackTrace()) {
                String className = frame.getClassName();
                if (className.startsWith("art.arcane.") && !className.startsWith("art.arcane.iris.probe.")) {
                    return frame.toString();
                }
            }
        }
        StackTraceElement[] trace = chain.get(chain.size() - 1).getStackTrace();
        return trace.length > 0 ? trace[0].toString() : "<no frames>";
    }

    private static String hashChunk(Hunk<PlatformBlockState> blocks, Hunk<PlatformBiome> biomes, int height) {
        MessageDigest blockDigest = sha256();
        MessageDigest biomeDigest = sha256();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < height; y++) {
                    PlatformBlockState state = blocks.get(x, y, z);
                    String key = state == null ? "minecraft:air" : state.key();
                    blockDigest.update((key + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        for (int x = 0; x < 16; x += BIOME_STEP) {
            for (int z = 0; z < 16; z += BIOME_STEP) {
                for (int y = 0; y < height; y += BIOME_STEP) {
                    PlatformBiome biome = biomes.get(x, y, z);
                    String key = biome == null ? "null" : biome.key();
                    biomeDigest.update((key + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return HexFormat.of().formatHex(blockDigest.digest()).substring(0, 16)
                + " " + HexFormat.of().formatHex(biomeDigest.digest()).substring(0, 16);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

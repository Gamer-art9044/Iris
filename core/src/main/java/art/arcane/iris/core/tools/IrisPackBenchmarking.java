package art.arcane.iris.core.tools;


import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.io.IO;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import org.bukkit.World;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;


public class IrisPackBenchmarking {
    private static final ThreadLocal<IrisPackBenchmarking> instance = new ThreadLocal<>();
    private final PrecisionStopwatch stopwatch = new PrecisionStopwatch();
    private final IrisDimension dimension;
    private final int radius;
    private final boolean gui;

    public IrisPackBenchmarking(IrisDimension dimension, int radius, boolean gui) {
        this.dimension = dimension;
        this.radius = radius;
        this.gui = gui;
        runBenchmark();
    }

    public static IrisPackBenchmarking getInstance() {
        return instance.get();
    }

    private void runBenchmark() {
        Thread.ofVirtual()
                .name("PackBenchmarking")
                .start(() -> {
                    IrisLogging.info("Setting up benchmark environment ");
                    IO.delete(IrisWorldStorage.dimensionRoot("benchmark"));
                    createBenchmark();
                    while (!IrisToolbelt.isIrisWorld(benchmarkWorld())) {
                        J.sleep(1000);
                        IrisLogging.debug("Iris PackBenchmark: Waiting...");
                    }
                    IrisLogging.info("Starting Benchmark!");
                    stopwatch.begin();
                    startBenchmark();
                });

    }

    public void finishedBenchmark(KList<Integer> cps) {
        try {
            String time = Form.duration((long) stopwatch.getMilliseconds());
            World benchmarkWorld = benchmarkWorld();
            if (benchmarkWorld == null) {
                throw new IllegalStateException("Benchmark world is not loaded.");
            }
            Engine engine = IrisToolbelt.access(benchmarkWorld).getEngine();
            IrisLogging.info("-----------------");
            IrisLogging.info("Results:");
            IrisLogging.info("- Total time: " + time);
            IrisLogging.info("- Average CPS: " + calculateAverage(cps));
            IrisLogging.info("  - Median CPS: " + calculateMedian(cps));
            IrisLogging.info("  - Highest CPS: " + findHighest(cps));
            IrisLogging.info("  - Lowest CPS: " + findLowest(cps));
            IrisLogging.info("-----------------");
            IrisLogging.info("Creating a report..");
            File results = IrisPlatforms.get().dataFile("packbenchmarks", dimension.getName() + " " + LocalDateTime.now(Clock.systemDefaultZone()).toString().replace(':', '-') + ".txt");
            KMap<String, Double> metrics = engine.getMetrics().pull();
            try (FileWriter writer = new FileWriter(results)) {
                writer.write("-----------------\n");
                writer.write("Results:\n");
                writer.write("Dimension: " + dimension.getName() + "\n");
                writer.write("- Date of Benchmark: " + LocalDateTime.now(Clock.systemDefaultZone()) + "\n");
                writer.write("\n");
                writer.write("Metrics");
                for (String m : metrics.k()) {
                    double i = metrics.get(m);
                    writer.write("- " + m + ": " + i);
                }
                writer.write("- " + metrics);
                writer.write("Benchmark: " + LocalDateTime.now(Clock.systemDefaultZone()) + "\n");
                writer.write("- Total time: " + time + "\n");
                writer.write("- Average CPS: " + calculateAverage(cps) + "\n");
                writer.write("  - Median CPS: " + calculateMedian(cps) + "\n");
                writer.write("  - Highest CPS: " + findHighest(cps) + "\n");
                writer.write("  - Lowest CPS: " + findLowest(cps) + "\n");
                writer.write("-----------------\n");
                IrisLogging.info("Finished generating a report!");
            } catch (IOException e) {
                IrisLogging.error("An error occurred writing to the file.");
                e.printStackTrace();
            }

            J.s(() -> {
                World world = benchmarkWorld();
                if (world == null) return;
                IrisToolbelt.evacuate(world);
                WorldLifecycleService.get().unload(world, true);
            });

            stopwatch.end();
        } catch (Exception e) {
            IrisLogging.error("Something has gone wrong!");
            e.printStackTrace();
        }
    }

    private void createBenchmark() {
        try {
            IrisToolbelt.createWorld()
                    .dimension(dimension.getLoadKey())
                    .name("benchmark")
                    .seed(1337)
                    .studio(false)
                    .benchmark(true)
                    .create();
        } catch (IrisException e) {
            throw new RuntimeException(e);
        }
    }

    private void startBenchmark() {
        try {
            instance.set(this);
            IrisToolbelt.pregenerate(PregenTask
                    .builder()
                    .gui(gui)
                    .radiusX(radius)
                    .radiusZ(radius)
                    .build(), benchmarkWorld()
            );
        } finally {
            instance.remove();
        }
    }

    private static World benchmarkWorld() {
        return WorldIdentity.resolve(IrisWorldStorage.keyFromName("benchmark")).orElse(null);
    }

    private double calculateAverage(KList<Integer> list) {
        double sum = 0;
        for (int num : list) {
            sum += num;
        }
        return sum / list.size();
    }

    private double calculateMedian(KList<Integer> list) {
        KList<Integer> sorted = new KList<>(list);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;

        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        } else {
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
        }
    }

    private int findLowest(KList<Integer> list) {
        return Collections.min(list);
    }

    private int findHighest(KList<Integer> list) {
        return Collections.max(list);
    }
}

package art.arcane.iris.util.common.parallel;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.IrisSettings;
import art.arcane.volmlib.util.parallel.MultiBurstSupport;
import art.arcane.volmlib.util.math.M;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.IntSupplier;

public class MultiBurst extends MultiBurstSupport {
    private static final long TIMEOUT = Long.getLong("iris.burst.timeout", 15000);
    public static final MultiBurst burst = new MultiBurst();
    public static final MultiBurst ioBurst = new MultiBurst("Iris IO", () -> IrisSettings.get().getConcurrency().getIoParallelism());

    public MultiBurst() {
        this("Iris");
    }

    public MultiBurst(String name) {
        this(name, Thread.MIN_PRIORITY, () -> IrisSettings.get().getConcurrency().getParallelism());
    }

    public MultiBurst(String name, IntSupplier parallelism) {
        this(name, Thread.MIN_PRIORITY, parallelism);
    }

    public MultiBurst(String name, int priority, IntSupplier parallelism) {
        super(name, priority, parallelism, IrisSettings::getThreadCount, M::ms, IrisLogging::reportError, IrisLogging::info, IrisLogging::warn, TIMEOUT);
    }

    public boolean ownsCurrentThread() {
        Thread thread = Thread.currentThread();
        if (!(thread instanceof ForkJoinWorkerThread worker)) {
            return false;
        }

        ExecutorService service = service();
        if (!(service instanceof ForkJoinPool pool)) {
            return false;
        }

        return worker.getPool() == pool;
    }

    @Override
    public BurstExecutor burst(int estimate) {
        return new BurstExecutor(service(), estimate);
    }

    @Override
    public BurstExecutor burst() {
        return burst(16);
    }

    @Override
    public BurstExecutor burst(boolean multicore) {
        BurstExecutor b = burst();
        b.setMulticore(multicore);
        return b;
    }

    public static void close(ExecutorService service) {
        MultiBurstSupport.close(service, M::ms, IrisLogging::info, IrisLogging::warn, IrisLogging::reportError, TIMEOUT);
    }
}

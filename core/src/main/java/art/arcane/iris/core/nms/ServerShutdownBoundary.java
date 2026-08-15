package art.arcane.iris.core.nms;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ServerShutdownBoundary {
    private static final long MAX_JOIN_SLICE_MILLIS = 1000L;

    private ServerShutdownBoundary() {
    }

    public static boolean await(
            BooleanSupplier boundaryReached,
            Thread serverThread,
            long timeout,
            TimeUnit unit
    ) {
        BooleanSupplier reached = Objects.requireNonNull(boundaryReached, "Server shutdown boundary");
        Thread activeServerThread = Objects.requireNonNull(serverThread, "Server thread");
        TimeUnit activeUnit = Objects.requireNonNull(unit, "Server shutdown timeout unit");
        if (reached.getAsBoolean()) {
            return true;
        }
        if (activeServerThread == Thread.currentThread()) {
            return false;
        }

        long timeoutNanos = Math.max(0L, activeUnit.toNanos(timeout));
        long started = System.nanoTime();
        boolean interrupted = false;
        while (!reached.getAsBoolean()) {
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0L || !activeServerThread.isAlive()) {
                restoreInterrupt(interrupted);
                return reached.getAsBoolean();
            }

            long joinMillis = Math.max(
                    1L,
                    Math.min(MAX_JOIN_SLICE_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining))
            );
            try {
                activeServerThread.join(joinMillis);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        restoreInterrupt(interrupted);
        return true;
    }

    private static void restoreInterrupt(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

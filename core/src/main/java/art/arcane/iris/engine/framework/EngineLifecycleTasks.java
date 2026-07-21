package art.arcane.iris.engine.framework;

import art.arcane.iris.util.project.context.IrisContext;

import java.util.Objects;
import java.util.function.Supplier;

public final class EngineLifecycleTasks {
    private EngineLifecycleTasks() {
    }

    public static boolean run(Engine engine, String operation, Runnable task) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(task);
        try (GenerationSessionLease lease = engine.acquireGenerationLease(operation);
             IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            task.run();
            return true;
        } catch (GenerationSessionException exception) {
            if (engine.isClosing() || engine.isClosed() || exception.isExpectedTeardown()) {
                return false;
            }
            throw new IllegalStateException("Iris lifecycle rejected " + operation + ".", exception);
        }
    }

    public static <T> T call(Engine engine, String operation, Supplier<T> task, T unavailable) {
        Objects.requireNonNull(task);
        try (GenerationSessionLease lease = engine.acquireGenerationLease(operation);
             IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            return task.get();
        } catch (GenerationSessionException exception) {
            if (engine.isClosing() || engine.isClosed() || exception.isExpectedTeardown()) {
                return unavailable;
            }
            throw new IllegalStateException("Iris lifecycle rejected " + operation + ".", exception);
        }
    }
}

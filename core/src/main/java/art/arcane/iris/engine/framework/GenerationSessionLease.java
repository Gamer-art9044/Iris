package art.arcane.iris.engine.framework;

public final class GenerationSessionLease implements AutoCloseable {
    private static final GenerationSessionLease NOOP = new GenerationSessionLease(null, null, 0L);

    private final GenerationSessionManager manager;
    private final GenerationSessionManager.GenerationSessionState state;
    private final long sessionId;
    private boolean released;

    GenerationSessionLease(GenerationSessionManager manager, GenerationSessionManager.GenerationSessionState state, long sessionId) {
        this.manager = manager;
        this.state = state;
        this.sessionId = sessionId;
        this.released = false;
    }

    public static GenerationSessionLease noop() {
        return NOOP;
    }

    public long sessionId() {
        return sessionId;
    }

    @Override
    public void close() {
        if (released || state == null) {
            return;
        }

        released = true;
        manager.releaseLease(state);
    }
}

package art.arcane.iris.engine.framework;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class GenerationSessionManager {
    private final AtomicLong sessionSequence;
    private final AtomicReference<GenerationSessionState> current;
    private final Object drainMonitor;

    public GenerationSessionManager() {
        this.sessionSequence = new AtomicLong(0L);
        this.current = new AtomicReference<>(new GenerationSessionState(nextSessionId(), new AtomicBoolean(true), new AtomicInteger(0), new AtomicBoolean(false), new AtomicReference<>(null)));
        this.drainMonitor = new Object();
    }

    public GenerationSessionLease acquire(String operation) throws GenerationSessionException {
        while (true) {
            GenerationSessionState state = current.get();
            if (state == null || !state.accepting().get()) {
                throw rejected(operation, state == null ? null : state);
            }

            state.activeLeases().incrementAndGet();
            if (state != current.get()) {
                state.activeLeases().decrementAndGet();
                continue;
            }

            if (!state.accepting().get()) {
                releaseLease(state);
                throw rejected(operation, state);
            }

            return new GenerationSessionLease(this, state, state.sessionId());
        }
    }

    public GenerationSessionLease continueSession(String operation, long sessionId) throws GenerationSessionException {
        while (true) {
            GenerationSessionState state = current.get();
            if (state == null || state.sessionId() != sessionId) {
                throw rejected(operation, state);
            }

            state.activeLeases().incrementAndGet();
            if (state != current.get()) {
                releaseLease(state);
                continue;
            }

            return new GenerationSessionLease(this, state, state.sessionId());
        }
    }

    public long currentSessionId() {
        GenerationSessionState state = current.get();
        return state == null ? 0L : state.sessionId();
    }

    public int activeLeases() {
        GenerationSessionState state = current.get();
        return state == null ? 0 : state.activeLeases().get();
    }

    public void sealAndAwait(String reason, long timeoutMs) throws GenerationSessionException {
        sealAndAwait(reason, timeoutMs, false);
    }

    public void sealAndAwait(String reason, long timeoutMs, boolean teardown) throws GenerationSessionException {
        GenerationSessionState state = current.get();
        if (state == null) {
            return;
        }

        state.accepting().set(false);
        state.teardown().set(teardown);
        state.sealReason().set(reason);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (drainMonitor) {
            while (state.activeLeases().get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }

                try {
                    drainMonitor.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GenerationSessionException("Generation session " + state.sessionId() + " was interrupted while draining for " + reason + ".", teardown);
                }
            }
        }

        if (state.activeLeases().get() > 0) {
            throw new GenerationSessionException("Generation session " + state.sessionId() + " failed to drain for " + reason + " after " + timeoutMs + "ms. Active leases=" + state.activeLeases().get() + ".", teardown);
        }
    }

    public void activateNextSession() {
        current.set(new GenerationSessionState(nextSessionId(), new AtomicBoolean(true), new AtomicInteger(0), new AtomicBoolean(false), new AtomicReference<>(null)));
    }

    private long nextSessionId() {
        return sessionSequence.incrementAndGet();
    }

    void releaseLease(GenerationSessionState state) {
        int remaining = state.activeLeases().decrementAndGet();
        if (remaining <= 0) {
            synchronized (drainMonitor) {
                drainMonitor.notifyAll();
            }
        }
    }

    private GenerationSessionException rejected(String operation, GenerationSessionState state) {
        long sessionId = state == null ? currentSessionId() : state.sessionId();
        boolean teardown = state != null && state.teardown().get();
        String reason = state == null ? null : state.sealReason().get();
        if (teardown && reason != null && !reason.isBlank()) {
            return new GenerationSessionException("Generation session " + sessionId + " rejected new work for " + operation + " during " + reason + ".", true);
        }

        return new GenerationSessionException("Generation session " + sessionId + " rejected new work for " + operation + ".", teardown);
    }

    record GenerationSessionState(long sessionId, AtomicBoolean accepting, AtomicInteger activeLeases, AtomicBoolean teardown, AtomicReference<String> sealReason) {
    }
}

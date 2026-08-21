package art.arcane.iris.core.localization;

import java.util.Objects;

final class LocaleHotloadGate {
    private final Timing timing;

    private long generation;
    private LocaleHotloadSnapshot baseline;
    private LocaleHotloadSnapshot observed;
    private long observedSinceNanos;
    private LocaleHotloadSnapshot pending;
    private Attempt inFlight;
    private long lastCompletedAtNanos;
    private boolean hasCompleted;

    LocaleHotloadGate(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "Locale hotload timing cannot be null");
    }

    synchronized Attempt observe(LocaleHotloadSnapshot snapshot, long nowNanos) {
        LocaleHotloadSnapshot current = Objects.requireNonNull(snapshot, "Locale hotload snapshot cannot be null");
        if (!current.equals(observed)) {
            observed = current;
            observedSinceNanos = nowNanos;
            return null;
        }

        long stabilityNanos = current.missing()
                ? timing.deletionGraceNanos()
                : timing.contentStabilityNanos();
        if (!elapsed(nowNanos, observedSinceNanos, stabilityNanos)) {
            return null;
        }

        pending = current.equals(baseline) ? null : current;
        if (pending == null || inFlight != null || !pending.equals(observed)) {
            return null;
        }
        if (hasCompleted && !elapsed(nowNanos, lastCompletedAtNanos, timing.cooldownNanos())) {
            return null;
        }

        Attempt attempt = new Attempt(generation, pending);
        inFlight = attempt;
        return attempt;
    }

    synchronized void unavailable() {
        observed = null;
        observedSinceNanos = 0L;
    }

    synchronized void complete(Attempt attempt, long nowNanos, boolean applied) {
        if (attempt == null || inFlight == null || !inFlight.equals(attempt) || attempt.generation() != generation) {
            return;
        }

        inFlight = null;
        lastCompletedAtNanos = nowNanos;
        hasCompleted = true;
        if (!applied) {
            return;
        }

        baseline = attempt.snapshot();
        if (attempt.snapshot().equals(pending)) {
            pending = null;
        }
    }

    synchronized void reset(LocaleHotloadSnapshot snapshot) {
        generation++;
        baseline = snapshot;
        observed = snapshot;
        observedSinceNanos = 0L;
        pending = null;
        inFlight = null;
        lastCompletedAtNanos = 0L;
        hasCompleted = false;
    }

    private boolean elapsed(long nowNanos, long startNanos, long durationNanos) {
        return nowNanos - startNanos >= durationNanos;
    }

    record Timing(long contentStabilityNanos, long deletionGraceNanos, long cooldownNanos) {
        Timing {
            if (contentStabilityNanos < 0L || deletionGraceNanos < 0L || cooldownNanos < 0L) {
                throw new IllegalArgumentException("Locale hotload timing cannot be negative");
            }
        }
    }

    record Attempt(long generation, LocaleHotloadSnapshot snapshot) {
        Attempt {
            snapshot = Objects.requireNonNull(snapshot, "Locale hotload attempt snapshot cannot be null");
        }
    }
}

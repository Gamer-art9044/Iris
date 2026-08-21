package art.arcane.iris.core.localization;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class LocaleHotloadGateTest {
    @Test
    public void anchorsCooldownAtCompletedApplication() {
        LocaleHotloadGate gate = gate();
        LocaleHotloadSnapshot initial = snapshot("initial");
        LocaleHotloadSnapshot first = snapshot("first");
        LocaleHotloadSnapshot second = snapshot("second");
        gate.reset(initial);

        assertNull(gate.observe(first, 0L));
        LocaleHotloadGate.Attempt firstAttempt = gate.observe(first, 100L);
        assertNotNull(firstAttempt);
        gate.complete(firstAttempt, 500L, true);

        assertNull(gate.observe(second, 600L));
        assertNull(gate.observe(second, 700L));
        assertNull(gate.observe(second, 3_499L));
        LocaleHotloadGate.Attempt secondAttempt = gate.observe(second, 3_500L);

        assertNotNull(secondAttempt);
        assertEquals(second, secondAttempt.snapshot());
    }

    @Test
    public void coalescesCooldownBurstToLatestStableSnapshot() {
        LocaleHotloadGate gate = gate();
        LocaleHotloadSnapshot first = snapshot("first");
        LocaleHotloadSnapshot intermediate = snapshot("intermediate");
        LocaleHotloadSnapshot latest = snapshot("latest");
        gate.reset(snapshot("initial"));

        assertNull(gate.observe(first, 0L));
        LocaleHotloadGate.Attempt firstAttempt = gate.observe(first, 100L);
        assertNotNull(firstAttempt);
        gate.complete(firstAttempt, 200L, true);

        assertNull(gate.observe(intermediate, 250L));
        assertNull(gate.observe(intermediate, 350L));
        assertNull(gate.observe(latest, 400L));
        assertNull(gate.observe(latest, 500L));
        assertNull(gate.observe(latest, 3_199L));
        LocaleHotloadGate.Attempt latestAttempt = gate.observe(latest, 3_200L);

        assertNotNull(latestAttempt);
        assertEquals(latest, latestAttempt.snapshot());
    }

    @Test
    public void requiresDeletionGraceAndCancelsTransientMissingSnapshot() {
        LocaleHotloadGate gate = gate();
        LocaleHotloadSnapshot initial = snapshot("initial");
        LocaleHotloadSnapshot replacement = snapshot("replacement");
        LocaleHotloadSnapshot missing = LocaleHotloadSnapshot.missing(initial.file(), "en_US");
        gate.reset(initial);

        assertNull(gate.observe(missing, 0L));
        assertNull(gate.observe(missing, 499L));
        assertNull(gate.observe(replacement, 500L));
        LocaleHotloadGate.Attempt replacementAttempt = gate.observe(replacement, 600L);
        assertNotNull(replacementAttempt);
        assertEquals(replacement, replacementAttempt.snapshot());
        gate.complete(replacementAttempt, 600L, true);

        assertNull(gate.observe(missing, 700L));
        assertNull(gate.observe(missing, 1_199L));
        assertNull(gate.observe(missing, 1_200L));
        LocaleHotloadGate.Attempt deletionAttempt = gate.observe(missing, 3_600L);

        assertNotNull(deletionAttempt);
        assertEquals(missing, deletionAttempt.snapshot());
    }

    @Test
    public void unavailableReadBlocksStalePendingSnapshot() {
        LocaleHotloadGate gate = gate();
        LocaleHotloadSnapshot first = snapshot("first");
        LocaleHotloadSnapshot pending = snapshot("pending");
        gate.reset(snapshot("initial"));

        assertNull(gate.observe(first, 0L));
        LocaleHotloadGate.Attempt firstAttempt = gate.observe(first, 100L);
        assertNotNull(firstAttempt);
        gate.complete(firstAttempt, 200L, true);
        assertNull(gate.observe(pending, 300L));
        assertNull(gate.observe(pending, 400L));

        gate.unavailable();
        assertNull(gate.observe(pending, 3_200L));
        LocaleHotloadGate.Attempt recoveredAttempt = gate.observe(pending, 3_300L);

        assertNotNull(recoveredAttempt);
        assertEquals(pending, recoveredAttempt.snapshot());
    }

    @Test
    public void failedApplicationRetriesOnlyAfterCooldown() {
        LocaleHotloadGate gate = gate();
        LocaleHotloadSnapshot changed = snapshot("changed");
        gate.reset(snapshot("initial"));

        assertNull(gate.observe(changed, 0L));
        LocaleHotloadGate.Attempt failedAttempt = gate.observe(changed, 100L);
        assertNotNull(failedAttempt);
        gate.complete(failedAttempt, 500L, false);

        assertNull(gate.observe(changed, 3_499L));
        LocaleHotloadGate.Attempt retryAttempt = gate.observe(changed, 3_500L);

        assertNotNull(retryAttempt);
        assertEquals(changed, retryAttempt.snapshot());
    }

    @Test
    public void manualResetInvalidatesInFlightAutomaticAttempt() {
        LocaleHotloadGate gate = gate();
        LocaleHotloadSnapshot automatic = snapshot("automatic");
        LocaleHotloadSnapshot manual = snapshot("manual");
        LocaleHotloadSnapshot changed = snapshot("changed");
        gate.reset(snapshot("initial"));

        assertNull(gate.observe(automatic, 0L));
        LocaleHotloadGate.Attempt staleAttempt = gate.observe(automatic, 100L);
        assertNotNull(staleAttempt);
        gate.reset(manual);
        gate.complete(staleAttempt, 200L, true);

        assertNull(gate.observe(manual, 200L));
        assertNull(gate.observe(changed, 300L));
        LocaleHotloadGate.Attempt currentAttempt = gate.observe(changed, 400L);

        assertNotNull(currentAttempt);
        assertNotEquals(staleAttempt.generation(), currentAttempt.generation());
        assertEquals(changed, currentAttempt.snapshot());
    }

    private LocaleHotloadGate gate() {
        return new LocaleHotloadGate(new LocaleHotloadGate.Timing(100L, 500L, 3_000L));
    }

    private LocaleHotloadSnapshot snapshot(String content) {
        return LocaleHotloadSnapshot.present(
                new File("locale.json"),
                "en_US",
                content,
                content
        );
    }
}

package art.arcane.iris.core.service;

import art.arcane.iris.core.pack.BrokenPackException;
import art.arcane.iris.core.pack.PackValidationResult;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StudioSVCPackAdmissionTest {
    @Test
    public void loadablePackIsAdmitted() {
        PackValidationResult validation = new PackValidationResult(
                "overworld", List.of(), List.of(), 1L);

        assertNull(StudioSVC.resolvePackAdmissionFailure(
                "overworld", Optional.empty(), validation));
    }

    @Test
    public void missingValidationFailsClosedWithTheResolvedPackName() {
        BrokenPackException failure = StudioSVC.resolvePackAdmissionFailure(
                "overworld-pack", Optional.empty(), null);

        assertEquals("overworld-pack", failure.getPackName());
        assertEquals(List.of(
                "Required pack validation has not completed. Studio creation fails closed until validation succeeds."),
                failure.getReasons());
    }

    @Test
    public void blockingValidationPreservesEveryReasonInOrder() {
        List<String> reasons = List.of(
                "Biome 'broken' has no resolvable regions.",
                "Structure 'castle' references missing pool 'castle/start'.");
        PackValidationResult validation = new PackValidationResult(
                "overworld", reasons, List.of(), 1L);

        BrokenPackException failure = StudioSVC.resolvePackAdmissionFailure(
                "overworld", Optional.empty(), validation);

        assertEquals("overworld", failure.getPackName());
        assertEquals(reasons, failure.getReasons());
    }

    @Test
    public void startupDenialTakesPriorityOverCachedPackValidation() {
        String denial = "Restart the server after changing external datapacks.";
        PackValidationResult validation = new PackValidationResult(
                "overworld", List.of(), List.of(), 1L);

        BrokenPackException failure = StudioSVC.resolvePackAdmissionFailure(
                "overworld", Optional.of(denial), validation);

        assertEquals(List.of(denial), failure.getReasons());
    }
}

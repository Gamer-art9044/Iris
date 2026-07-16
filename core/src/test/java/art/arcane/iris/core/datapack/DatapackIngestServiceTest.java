package art.arcane.iris.core.datapack;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatapackIngestServiceTest {
    @Test
    public void manifestRemainsPendingWhenAnyPackFails() {
        DatapackIngestService.Entry first = new DatapackIngestService.Entry();
        DatapackIngestService.Entry second = new DatapackIngestService.Entry();

        boolean completed = DatapackIngestService.markStructuresImportedIfComplete(
                List.of(first, second), 2, 1);

        assertFalse(completed);
        assertFalse(first.structuresImported);
        assertFalse(second.structuresImported);
    }

    @Test
    public void manifestRemainsPendingWhenNoPackWasAttempted() {
        DatapackIngestService.Entry entry = new DatapackIngestService.Entry();

        boolean completed = DatapackIngestService.markStructuresImportedIfComplete(
                List.of(entry), 0, 0);

        assertFalse(completed);
        assertFalse(entry.structuresImported);
    }

    @Test
    public void manifestCompletesOnlyAfterEveryAttemptedPackSucceeds() {
        DatapackIngestService.Entry first = new DatapackIngestService.Entry();
        DatapackIngestService.Entry second = new DatapackIngestService.Entry();

        boolean completed = DatapackIngestService.markStructuresImportedIfComplete(
                List.of(first, second), 2, 2);

        assertTrue(completed);
        assertTrue(first.structuresImported);
        assertTrue(second.structuresImported);
    }
}

package art.arcane.iris.core.localization;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class IrisLanguageHotloadSnapshotTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void capturesMissingOverrideAsStableTombstone() throws Exception {
        File override = new File(temporaryFolder.getRoot(), "languages/overrides/en_US.json");

        LocaleHotloadSnapshot snapshot = IrisLanguage.captureHotloadSnapshot(override, "en_US");

        assertTrue(snapshot.missing());
        assertEquals("missing", snapshot.sha256());
    }

    @Test
    public void detectsSameMetadataContentReplacementBySha256() throws Exception {
        File override = new File(temporaryFolder.getRoot(), "languages/overrides/en_US.json");
        Files.createDirectories(override.toPath().getParent());
        String firstContent = "{\"messages\":{\"a\":\"1\"}}";
        String secondContent = "{\"messages\":{\"a\":\"2\"}}";
        Files.writeString(override.toPath(), firstContent, StandardCharsets.UTF_8);
        FileTime fixedTime = FileTime.fromMillis(10_000L);
        Files.setLastModifiedTime(override.toPath(), fixedTime);
        LocaleHotloadSnapshot first = IrisLanguage.captureHotloadSnapshot(override, "en_US");

        Files.writeString(override.toPath(), secondContent, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(override.toPath(), fixedTime);
        LocaleHotloadSnapshot second = IrisLanguage.captureHotloadSnapshot(override, "en_US");

        assertFalse(first.missing());
        assertFalse(second.missing());
        assertEquals(firstContent.length(), secondContent.length());
        assertEquals(first.file(), second.file());
        assertEquals(firstContent, first.content());
        assertEquals(secondContent, second.content());
        assertNotEquals(first.sha256(), second.sha256());
        assertNotEquals(first, second);
    }
}

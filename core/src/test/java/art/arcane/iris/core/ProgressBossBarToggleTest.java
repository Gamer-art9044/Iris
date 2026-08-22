package art.arcane.iris.core;

import art.arcane.iris.platform.bukkit.BukkitPlatform;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ProgressBossBarToggleTest {
    @Test
    public void progressBossBarsAreEnabledByDefault() {
        assertTrue(new IrisSettings.IrisSettingsGeneral().isProgressBossBar());
    }

    @Test
    public void showProgressLaneNeverTouchesTheLaneServiceWhenDisabled() {
        withProgressBossBar(false, () ->
                BukkitPlatform.showProgressLane(null, "iris:job", "Working", 0.5D, 4000L));
    }

    @Test
    public void showProgressLaneStillReachesTheLaneServiceWhenEnabled() {
        withProgressBossBar(true, () -> assertThrows(IllegalStateException.class,
                () -> BukkitPlatform.showProgressLane(null, "iris:job", "Working", 0.5D, 4000L)));
    }

    @Test
    public void studioOpenBossBarIsGatedBySettings() throws Exception {
        assertGated("core/project/StudioOpenProgressReporter.java");
    }

    @Test
    public void worldCreationBossBarIsGatedBySettings() throws Exception {
        assertGated("core/tools/WorldCreationProgressReporter.java");
    }

    @Test
    public void chunkJobBossBarIsGatedBySettings() throws Exception {
        assertGated("core/runtime/ChunkJobReporter.java");
    }

    @Test
    public void packDownloadLaneIsGatedWhileTheActionBarSurvives() throws Exception {
        String source = source("core/service/PackDownloadProgressReporter.java");

        assertTrue(source.contains("isProgressBossBar()"));
        assertTrue("the action bar must keep reporting when boss bars are off",
                source.contains("sender.sendAction(snapshot.line())"));
    }

    @Test
    public void pregenLaneIsGatedWhileTheActionBarSurvives() throws Exception {
        String source = source("core/tools/IrisCreator.java");
        int lane = source.indexOf("\"iris:pregen\"");
        int gate = source.lastIndexOf("isProgressBossBar()", lane);

        assertTrue("the pregen boss bar lane must sit behind general.progressBossBar",
                gate > 0 && lane - gate < 200);
        assertTrue("the action bar must keep reporting when boss bars are off",
                source.contains("RuntimeProgressMessages.WORLD_PREGEN_ACTION"));
    }

    private void assertGated(String relativePath) throws Exception {
        assertTrue(relativePath + " must consult general.progressBossBar before creating a boss bar",
                source(relativePath).contains("isProgressBossBar()"));
    }

    private String source(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java/art/arcane/iris").resolve(relativePath)).replace("\r\n", "\n");
    }

    private void withProgressBossBar(boolean enabled, Runnable body) {
        IrisSettings previous = IrisSettings.settings;
        try {
            IrisSettings live = new IrisSettings();
            live.getGeneral().setProgressBossBar(enabled);
            IrisSettings.settings = live;
            assertFalse("another test hosted a HUD; this test needs an unhosted platform",
                    BukkitPlatform.hasHud());
            body.run();
        } finally {
            IrisSettings.settings = previous;
        }
    }
}

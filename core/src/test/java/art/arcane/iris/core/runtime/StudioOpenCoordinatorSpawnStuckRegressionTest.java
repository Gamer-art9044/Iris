package art.arcane.iris.core.runtime;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioOpenCoordinatorSpawnStuckRegressionTest {
    @Test
    public void legacySafeEntryRetryLoopIsRemoved() {
        boolean found = Arrays.stream(StudioOpenCoordinator.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("waitForSafeEntry"));
        assertFalse("waitForSafeEntry retry loop must remain removed", found);
    }

    @Test
    public void requestEntryChunkRedundantLoopIsRemoved() {
        boolean found = Arrays.stream(StudioOpenCoordinator.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("requestEntryChunk"));
        assertFalse("requestEntryChunk must be removed — createLevel already loads (0,0)", found);
    }

    @Test
    public void waitForEntryChunkRedundantLoopIsRemoved() {
        boolean found = Arrays.stream(StudioOpenCoordinator.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("waitForEntryChunk"));
        assertFalse("waitForEntryChunk retry loop must be removed", found);
    }

    @Test
    public void entryTeleportDoesNotIssueASecondChunkRequest() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");

        assertTrue(source.contains("WorldRuntimeControlService.get().teleportInMode("));
        assertFalse(source.contains("prepareStudioEntryChunks("));
        assertFalse(source.contains("requestChunkAsync("));
    }

    @Test
    public void foliaEntryPathNeverReadsTerrainOnARegionThread() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");

        assertTrue(source.contains("resolveEntryAnchor(world, provider)"));
        assertFalse(source.contains("findStudioEntryLocation"));
        assertFalse(source.contains("getHighestBlockYAt("));
        assertFalse(source.contains("resolveSafeEntry(world, entryAnchor)"));
    }
}

package art.arcane.iris.core.runtime;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioOpenCoordinatorSpawnStuckRegressionTest {
    @Test
    public void waitForSafeEntryRetryLoopIsRemoved() {
        boolean found = Arrays.stream(StudioOpenCoordinator.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("waitForSafeEntry"));
        assertFalse("waitForSafeEntry retry loop must be removed — it burns up to 120s on ocean columns", found);
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
    public void entryChunkLoadUsesTheUrgentAsyncRequestWithoutRetentionTickets() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int asyncLoad = source.indexOf("requested = WorldRuntimeControlService.get().requestChunkAsync(");
        int urgentFlag = source.indexOf("true);", asyncLoad);

        assertTrue(asyncLoad >= 0);
        assertTrue(urgentFlag > asyncLoad);
        assertFalse(source.contains("addPluginChunkTicket"));
        assertFalse(source.contains("removePluginChunkTicket"));
    }

    @Test
    public void foliaUsesTheSameNonBlockingAsyncEntryPath() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int loadStart = source.indexOf("private EntryChunkResolution loadEntryChunk(");
        int loadEnd = source.indexOf("private void settleEntryUseAfterOperation(", loadStart);
        String load = source.substring(loadStart, loadEnd);

        assertTrue(load.contains("requestChunkAsync("));
        assertTrue(load.contains("J.isOwnedByCurrentRegion(world, chunkX, chunkZ)"));
        assertTrue(load.contains("J.runRegion(world, chunkX, chunkZ"));
        assertTrue(load.contains("findTopSafeStudioLocation(world, entryAnchor)"));
        assertFalse(source.contains("resolveSafeEntry(world, entryAnchor)"));
        assertFalse(load.contains("J.isFolia()"));
    }
}

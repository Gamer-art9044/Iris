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
    public void paperEntryChunkLoadIsAsyncBeforeTheRetentionTicket() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int asyncLoad = source.indexOf("requested = WorldRuntimeControlService.get().requestChunkAsync(");
        int urgentFlag = source.indexOf("true);", asyncLoad);
        int retentionTicket = source.indexOf("world.addPluginChunkTicket", asyncLoad);

        assertTrue(asyncLoad >= 0);
        assertTrue(urgentFlag > asyncLoad);
        assertTrue(retentionTicket > asyncLoad);
    }

    @Test
    public void foliaRetainsItsNonBlockingTicketBootstrapPath() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int foliaBranch = source.indexOf("if (!J.isFolia())");
        int retentionSchedule = source.indexOf(
                "return scheduleEntryChunkRetention(world, chunkX, chunkZ);",
                foliaBranch);

        assertTrue(foliaBranch >= 0);
        assertTrue(retentionSchedule > foliaBranch);
    }
}

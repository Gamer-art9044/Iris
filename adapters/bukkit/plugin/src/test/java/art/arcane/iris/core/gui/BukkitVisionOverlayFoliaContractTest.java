package art.arcane.iris.core.gui;

import art.arcane.iris.core.runtime.WorldRuntimeControlService;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class BukkitVisionOverlayFoliaContractTest {
    @Test
    public void teleportLoadsTheDestinationChunkBeforeItsOwningRegionReadsTheSurface() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/gui/BukkitVisionOverlay.java"
        )).replace("\r\n", "\n");
        String request = method(source, "private void requestTeleportChunk(");

        assertBefore(request, "requestChunkAsync(", "requested.whenComplete(");
        assertBefore(request, "requested.whenComplete(", "J.runRegion(");
        assertBefore(request, "J.runRegion(", "world.getHighestBlockYAt(");
        assertBefore(request, "world.getHighestBlockYAt(", "J.runEntity(");
        assertTrue(request.contains("chunkX,\n                    chunkZ,\n                    true,\n                    true"));
        assertEquals(1, occurrences(request, "world.getHighestBlockYAt("));
    }

    @Test
    public void teleportReportsAsyncAndSchedulingFailuresWithDestinationContext() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/gui/BukkitVisionOverlay.java"
        )).replace("\r\n", "\n");
        String request = method(source, "private void requestTeleportChunk(");
        String reporter = method(source, "private void reportTeleportFailure(");

        assertTrue(request.contains("if (requested == null)"));
        assertTrue(request.contains("if (failure != null)"));
        assertTrue(request.contains("if (chunk == null ||"));
        assertTrue(request.contains("if (!J.runEntity("));
        assertTrue(request.contains("if (!scheduled)"));
        assertTrue(reporter.contains("IrisLogging.reportError("));
        assertTrue(reporter.contains("world.getName()"));
        assertTrue(reporter.contains("blockX + \",\" + blockZ"));
    }

    @Test
    public void teleportObservesNativeCompletionAndRejectsFalseSettlement() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/gui/BukkitVisionOverlay.java"
        )).replace("\r\n", "\n");
        String delegate = method(source, "private void delegateTeleport(");

        assertTrue(delegate.contains("teleport.whenComplete("));
        assertTrue(delegate.contains("!Boolean.TRUE.equals(success)"));
        assertTrue(delegate.contains("restartLatest(request)"));
    }

    @Test
    public void staleChunkCompletionCannotTeleportOverTheLatestRequest() {
        VisionHarness harness = new VisionHarness();
        CompletableFuture<Chunk> firstChunk = new CompletableFuture<>();
        CompletableFuture<Chunk> secondChunk = new CompletableFuture<>();
        harness.stubChunk(0, 0, firstChunk);
        harness.stubChunk(2, 2, secondChunk);

        try (harness) {
            harness.overlay.teleport(1.5D, 1.5D);
            harness.overlay.teleport(33.5D, 33.5D);

            firstChunk.complete(harness.chunk);
            assertEquals(0, harness.destinations.size());
            secondChunk.complete(harness.chunk);

            assertEquals(1, harness.destinations.size());
            assertEquals(33, harness.destinations.get(0).getBlockX());
            assertEquals(33, harness.destinations.get(0).getBlockZ());
        }
    }

    @Test
    public void latestRequestRunsAfterAnOlderNativeTeleportSettles() {
        VisionHarness harness = new VisionHarness();
        harness.stubChunk(0, 0, CompletableFuture.completedFuture(harness.chunk));
        harness.stubChunk(2, 2, CompletableFuture.completedFuture(harness.chunk));
        CompletableFuture<Boolean> firstTeleport = new CompletableFuture<>();
        CompletableFuture<Boolean> secondTeleport = new CompletableFuture<>();
        harness.nativeTeleports.add(firstTeleport);
        harness.nativeTeleports.add(secondTeleport);

        try (harness) {
            harness.overlay.teleport(1.5D, 1.5D);
            harness.overlay.teleport(33.5D, 33.5D);
            assertEquals(1, harness.destinations.size());

            firstTeleport.complete(true);
            assertEquals(2, harness.destinations.size());
            assertEquals(33, harness.destinations.get(1).getBlockX());
            assertEquals(33, harness.destinations.get(1).getBlockZ());
            secondTeleport.complete(true);
        }
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("Missing source contract token: " + first, firstIndex >= 0);
        assertTrue("Missing source contract token: " + second, secondIndex >= 0);
        assertTrue(first + " must occur before " + second, firstIndex < secondIndex);
    }

    private static int occurrences(String source, String match) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(match, offset)) >= 0) {
            count++;
            offset += match.length();
        }
        return count;
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Missing source contract signature: " + signature, start >= 0);
        int openBrace = source.indexOf('{', start);
        assertTrue("Missing source contract method body: " + signature, openBrace >= 0);
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new IllegalArgumentException("Unclosed source contract method: " + signature);
    }

    private static final class VisionHarness implements AutoCloseable {
        private final Engine engine;
        private final IrisWorld target;
        private final World world;
        private final Player player;
        private final Chunk chunk;
        private final WorldRuntimeControlService runtime;
        private final MockedStatic<J> scheduling;
        private final MockedStatic<BukkitWorldBinding> binding;
        private final MockedStatic<WorldRuntimeControlService> runtimeAccess;
        private final MockedStatic<BukkitPlatform> platform;
        private final List<Location> destinations;
        private final List<CompletableFuture<Boolean>> nativeTeleports;
        private final AtomicInteger nativeTeleportIndex;
        private final BukkitVisionOverlay overlay;

        private VisionHarness() {
            engine = mock(Engine.class);
            target = mock(IrisWorld.class);
            world = mock(World.class);
            player = mock(Player.class);
            chunk = mock(Chunk.class);
            runtime = mock(WorldRuntimeControlService.class);
            destinations = new ArrayList<>();
            nativeTeleports = new ArrayList<>();
            nativeTeleportIndex = new AtomicInteger();

            when(engine.getWorld()).thenReturn(target);
            when(target.hasPlatformWorld()).thenReturn(true);
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(chunk.getWorld()).thenReturn(world);
            when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(70);

            scheduling = mockStatic(J.class);
            scheduling.when(() -> J.runGlobal(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return true;
            });
            scheduling.when(() -> J.runRegion(
                            same(world),
                            anyInt(),
                            anyInt(),
                            any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        invocation.getArgument(3, Runnable.class).run();
                        return true;
                    });
            scheduling.when(() -> J.runEntity(same(player), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(1, Runnable.class).run();
                return true;
            });

            binding = mockStatic(BukkitWorldBinding.class);
            binding.when(() -> BukkitWorldBinding.world(target)).thenReturn(world);
            binding.when(() -> BukkitWorldBinding.players(target)).thenReturn(List.of(player));

            runtimeAccess = mockStatic(WorldRuntimeControlService.class);
            runtimeAccess.when(WorldRuntimeControlService::get).thenReturn(runtime);

            platform = mockStatic(BukkitPlatform.class);
            platform.when(() -> BukkitPlatform.teleportAsync(same(player), any(Location.class)))
                    .thenAnswer(invocation -> {
                        destinations.add(invocation.getArgument(1, Location.class));
                        int index = nativeTeleportIndex.getAndIncrement();
                        return index < nativeTeleports.size()
                                ? nativeTeleports.get(index)
                                : CompletableFuture.completedFuture(true);
                    });
            overlay = new BukkitVisionOverlay(engine);
        }

        private void stubChunk(
                int chunkX,
                int chunkZ,
                CompletableFuture<Chunk> requested
        ) {
            when(runtime.requestChunkAsync(
                    same(world),
                    eq(chunkX),
                    eq(chunkZ),
                    eq(true),
                    eq(true))).thenReturn(requested);
        }

        @Override
        public void close() {
            platform.close();
            runtimeAccess.close();
            binding.close();
            scheduling.close();
        }
    }
}

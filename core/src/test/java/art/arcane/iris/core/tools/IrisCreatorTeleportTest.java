package art.arcane.iris.core.tools;

import art.arcane.iris.core.runtime.WorldRuntimeControlService;
import art.arcane.iris.util.common.plugin.VolmitSender;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

public class IrisCreatorTeleportTest {
    @Test
    public void createTeleportTarget_onlySelectsProductionPlayerSender() {
        Player player = mock(Player.class);
        VolmitSender playerSender = mock(VolmitSender.class);
        VolmitSender consoleSender = mock(VolmitSender.class);
        doReturn(true).when(playerSender).isPlayer();
        doReturn(player).when(playerSender).player();
        doReturn(false).when(consoleSender).isPlayer();

        assertSame(player, IrisCreator.createTeleportTarget(playerSender, false, false));
        assertNull(IrisCreator.createTeleportTarget(consoleSender, false, false));
        assertNull(IrisCreator.createTeleportTarget(playerSender, true, false));
        assertNull(IrisCreator.createTeleportTarget(playerSender, false, true));
    }

    @Test
    public void teleportSenderToCreatedWorld_loadsAndResolvesSafeEntryBeforeTeleport() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        WorldRuntimeControlService runtimeControl = mock(WorldRuntimeControlService.class);
        Location anchor = new Location(world, 32.5D, 80D, -15.5D);
        Location safeEntry = new Location(world, 32.5D, 94D, -15.5D);
        doReturn(anchor).when(runtimeControl).resolveEntryAnchor(world);
        doReturn(CompletableFuture.completedFuture(chunk))
                .when(runtimeControl).requestChunkAsync(world, 2, -1, true);
        doReturn(CompletableFuture.completedFuture(safeEntry))
                .when(runtimeControl).resolveSafeEntry(world, anchor);
        doReturn(CompletableFuture.completedFuture(true))
                .when(runtimeControl).teleport(player, safeEntry);

        CompletableFuture<Boolean> result = IrisCreator.teleportSenderToCreatedWorld(player, world, runtimeControl);

        assertTrue(result.join());
        InOrder order = inOrder(runtimeControl);
        order.verify(runtimeControl).resolveEntryAnchor(world);
        order.verify(runtimeControl).requestChunkAsync(world, 2, -1, true);
        order.verify(runtimeControl).resolveSafeEntry(world, anchor);
        order.verify(runtimeControl).teleport(player, safeEntry);
    }

    @Test
    public void teleportSenderToCreatedWorld_preservesFalseTeleportResult() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        WorldRuntimeControlService runtimeControl = mock(WorldRuntimeControlService.class);
        Location anchor = new Location(world, 0.5D, 80D, 0.5D);
        doReturn(anchor).when(runtimeControl).resolveEntryAnchor(world);
        doReturn(CompletableFuture.completedFuture(mock(Chunk.class)))
                .when(runtimeControl).requestChunkAsync(world, 0, 0, true);
        doReturn(CompletableFuture.completedFuture(anchor))
                .when(runtimeControl).resolveSafeEntry(world, anchor);
        doReturn(CompletableFuture.completedFuture(false))
                .when(runtimeControl).teleport(player, anchor);

        CompletableFuture<Boolean> result = IrisCreator.teleportSenderToCreatedWorld(player, world, runtimeControl);

        assertFalse(result.join());
    }

    @Test
    public void teleportSenderToCreatedWorld_failsWhenSafeEntryCannotResolve() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        WorldRuntimeControlService runtimeControl = mock(WorldRuntimeControlService.class);
        Location anchor = new Location(world, 0.5D, 80D, 0.5D);
        doReturn("irisworld").when(world).getName();
        doReturn(anchor).when(runtimeControl).resolveEntryAnchor(world);
        doReturn(CompletableFuture.completedFuture(mock(Chunk.class)))
                .when(runtimeControl).requestChunkAsync(world, 0, 0, true);
        doReturn(CompletableFuture.completedFuture(null))
                .when(runtimeControl).resolveSafeEntry(world, anchor);

        CompletableFuture<Boolean> result = IrisCreator.teleportSenderToCreatedWorld(player, world, runtimeControl);

        assertThrows(CompletionException.class, result::join);
    }
}

package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.core.lifecycle.WorldReplacementSeed;
import art.arcane.iris.util.common.scheduling.J;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CurrentPaperWorldDataWriter {
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 30L;

    private CurrentPaperWorldDataWriter() {
    }

    static void write(
            Path sourceWorldDirectory,
            Path targetWorldDirectory,
            long seed
    ) throws IOException {
        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        MinecraftServer server = craftServer.getHandle().getServer();
        PaperLevelOverrides levelOverrides = captureLevelOverrides(craftServer, server);

        Path targetWorld = targetWorldDirectory.toAbsolutePath().normalize();
        UUID metadataUuid = UUID.randomUUID();
        WorldReplacementSeed.copyWithAuthoritativeSeed(sourceWorldDirectory, targetWorld, seed);
        try (SavedDataStorage savedDataStorage = new SavedDataStorage(
                targetWorld.resolve("data"),
                server.getFixerUpper(),
                server.registryAccess()
        )) {
            savedDataStorage.set(PaperWorldMetadata.TYPE, new PaperWorldMetadata(metadataUuid));
            savedDataStorage.set(PaperLevelOverrides.TYPE, levelOverrides);
        }

        List<Path> requiredDataFiles = List.of(
                targetWorld.resolve("data/minecraft/world_gen_settings.dat"),
                targetWorld.resolve("data/paper/metadata.dat"),
                targetWorld.resolve("data/paper/level_overrides.dat")
        );
        for (Path requiredDataFile : requiredDataFiles) {
            if (!Files.isRegularFile(requiredDataFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Current Paper world data was not written: " + requiredDataFile);
            }
        }

        long writtenSeed = WorldReplacementSeed.readAuthoritativeSeed(targetWorld);
        if (writtenSeed != seed) {
            throw new IOException("Current Paper world data did not retain the requested seed.");
        }

        try (SavedDataStorage verificationStorage = new SavedDataStorage(
                targetWorld.resolve("data"),
                server.getFixerUpper(),
                server.registryAccess()
        )) {
            PaperWorldMetadata metadata = verificationStorage.get(PaperWorldMetadata.TYPE);
            if (metadata == null || !metadataUuid.equals(metadata.uuid())) {
                throw new IOException("Current Paper world metadata could not be verified.");
            }
            PaperLevelOverrides overrides = verificationStorage.get(PaperLevelOverrides.TYPE);
            if (overrides == null || overrides.isInitialized()) {
                throw new IOException("Current Paper level overrides could not be verified.");
            }
        }
    }

    private static PaperLevelOverrides captureLevelOverrides(
            CraftServer craftServer,
            MinecraftServer server
    ) throws IOException {
        if (craftServer.isGlobalTickThread()) {
            return createLevelOverrides(craftServer, server);
        }
        if (J.isFolia() && J.isPrimaryThread()) {
            throw new IOException("Current Paper world data cannot be staged from a Folia region tick thread.");
        }

        CompletableFuture<PaperLevelOverrides> captured = new CompletableFuture<>();
        boolean scheduled = J.runGlobal(() -> {
            try {
                captured.complete(createLevelOverrides(craftServer, server));
            } catch (Throwable failure) {
                captured.completeExceptionally(failure);
            }
        });
        if (!scheduled) {
            throw new IOException("Could not schedule the current Paper level-data snapshot on the global thread.");
        }

        try {
            return captured.get(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while capturing current Paper level data.", failure);
        } catch (ExecutionException failure) {
            throw new IOException("Could not capture current Paper level data.", failure.getCause());
        } catch (TimeoutException failure) {
            throw new IOException("Timed out while capturing current Paper level data.", failure);
        }
    }

    private static PaperLevelOverrides createLevelOverrides(
            CraftServer craftServer,
            MinecraftServer server
    ) throws IOException {
        if (!craftServer.isGlobalTickThread()) {
            throw new IOException("Current Paper level data must be captured on the global tick thread.");
        }
        if (!(server.getWorldData().overworldData() instanceof PrimaryLevelData primaryLevelData)) {
            throw new IOException("Paper primary level data is unavailable for current world data staging.");
        }
        return PaperLevelOverrides.createFromLiveLevelData(primaryLevelData);
    }
}

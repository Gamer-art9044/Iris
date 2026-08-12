/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded;

import art.arcane.iris.core.pack.BrokenPackException;
import art.arcane.iris.core.pack.PackDirectoryResolver;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import art.arcane.iris.core.pack.PackValidator;
import art.arcane.iris.modded.command.ModdedPackCommands;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class ModdedStartup {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final AtomicBoolean PREPARED = new AtomicBoolean(false);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final Object PACK_LOCK = new Object();

    private ModdedStartup() {
    }

    public static void reset() {
        PREPARED.set(false);
        STARTED.set(false);
    }

    public static void prepareForStartup() {
        if (!PREPARED.compareAndSet(false, true)) {
            return;
        }
        validateAllPacks();
    }

    /**
     * Boot trigger for the forced datapack. Runs on its own daemon thread rather than the Iris scheduler:
     * ModdedEngineBootstrap.start clears the async queue at SERVER_STARTING, which would silently drop this
     * one-shot task, and the datapack must be regenerated before the level PackRepository reload if it can.
     */
    public static void prefetchDefaultPack() {
        Thread thread = new Thread(ModdedStartup::refreshPacksAndDatapack, "iris-modded-pack-prefetch");
        thread.setDaemon(true);
        thread.start();
    }

    private static void refreshPacksAndDatapack() {
        ensureDefaultPack();
        try {
            ModdedForcedDatapack.regenerateIfStale("boot");
        } catch (Throwable failure) {
            LOGGER.error("Iris could not refresh the forced datapack at boot", failure);
        }
    }

    public static void runOnce(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) {
            return;
        }
        prepareForStartup();
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        ModdedForcedDatapack.verifyInjected();
        ModdedMixinAudit.runOnce();
        reinjectPersistentDimensions(server);

        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler == null) {
            refreshPacksAndDatapack();
            return;
        }
        scheduler.async(ModdedStartup::refreshPacksAndDatapack);
    }

    public static void validateAllPacks() {
        File packsRoot = ModdedPackCommands.packsRoot();
        List<File> packDirs = PackDirectoryResolver.listVisiblePackDirectories(packsRoot);
        PackValidationRegistry.clear();
        if (packDirs.isEmpty()) {
            LOGGER.info("Iris found no packs to validate under {}; install one with /iris download <pack>",
                    packsRoot.getAbsolutePath());
            return;
        }
        for (File packDir : packDirs) {
            try {
                PackValidationResult result = PackValidator.validate(packDir);
                PackValidationRegistry.publish(result);
                if (!result.isLoadable()) {
                    LOGGER.error("Iris pack '{}' FAILED validation with {} blocking error(s); world/studio creation will be refused. First error: {}",
                            result.getPackName(), result.getBlockingErrors().size(),
                            result.getBlockingErrors().getFirst());
                } else if (!result.getWarnings().isEmpty()) {
                    LOGGER.info("Iris pack '{}' validated ({} warning(s)).", result.getPackName(), result.getWarnings().size());
                    for (String warning : result.getWarnings()) {
                        LOGGER.warn("  [{}] {}", result.getPackName(), warning);
                    }
                } else {
                    LOGGER.info("Iris pack '{}' validated.", result.getPackName());
                }
            } catch (Throwable e) {
                LOGGER.error("Iris pack validation failed for '{}'", packDir.getName(), e);
                String detail = e.getMessage();
                if (detail == null || detail.isBlank()) {
                    detail = e.getClass().getSimpleName();
                }
                PackValidationRegistry.publish(new PackValidationResult(
                        packDir.getName(),
                        List.of("Pack validation failed with " + e.getClass().getSimpleName() + ": " + detail),
                        List.of(),
                        System.currentTimeMillis()));
            }
        }
    }

    public static PackValidationResult requirePackForWorldCreation(String pack) {
        if (pack == null || pack.isBlank()) {
            throw new IllegalArgumentException("Pack name is required for world creation");
        }
        File packDir = PackDirectoryResolver.resolveExisting(ModdedPackCommands.packsRoot(), pack);
        if (packDir == null) {
            throw new BrokenPackException(pack, List.of(
                    "Pack folder does not exist under " + ModdedPackCommands.packsRoot().getAbsolutePath() + "."));
        }
        PackValidationResult cached = PackValidationRegistry.get(pack);
        if (cached != null && cached.getValidatedAtMillis() >= newestModificationMillis(packDir.toPath())) {
            // prepareForStartup already validated this pack and nothing in it changed since; re-validating per
            // persistent dimension at boot costs a full pack parse each time.
            return PackValidationRegistry.requireLoadable(pack);
        }
        try {
            PackValidationResult result = PackValidator.validate(packDir);
            PackValidationRegistry.publish(result);
            return PackValidationRegistry.requireLoadable(pack);
        } catch (BrokenPackException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.error("Iris required world-creation validation failed for '{}'", pack, e);
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }
            PackValidationResult failure = new PackValidationResult(
                    pack,
                    List.of("Pack validation failed with " + e.getClass().getSimpleName() + ": " + detail),
                    List.of(),
                    System.currentTimeMillis());
            PackValidationRegistry.publish(failure);
            throw new BrokenPackException(pack, failure.getBlockingErrors());
        }
    }

    private static void reinjectPersistentDimensions(MinecraftServer server) {
        List<ModdedDimensionRegistryStore.PersistentDimension> dimensions =
                ModdedDimensionRegistryStore.loadForStartup(server);
        if (dimensions.isEmpty()) {
            return;
        }
        int injected = 0;
        int index = 0;
        long startedAt = System.currentTimeMillis();
        for (ModdedDimensionRegistryStore.PersistentDimension dimension : dimensions) {
            index++;
            long dimensionStartedAt = System.currentTimeMillis();
            try {
                ModdedDimensionManager.create(server, dimension.id(), dimension.pack(), dimension.dimension(), dimension.seed());
                injected++;
                LOGGER.info("Iris re-injected {}/{} '{}' (pack={} dim={}) in {}ms",
                        index, dimensions.size(), dimension.id(), dimension.pack(), dimension.dimension(),
                        System.currentTimeMillis() - dimensionStartedAt);
            } catch (Throwable e) {
                LOGGER.error("Iris failed to re-inject persistent dimension '{}' (pack={} dim={} seed={})", dimension.id(), dimension.pack(), dimension.dimension(), dimension.seed(), e);
                if (e instanceof OutOfMemoryError outOfMemory) {
                    throw outOfMemory;
                }
            }
        }
        LOGGER.info("Iris re-injected {}/{} persistent dimension(s) at startup in {}ms",
                injected, dimensions.size(), System.currentTimeMillis() - startedAt);
    }

    private static long newestModificationMillis(Path root) {
        long newest = 0L;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                long modified = attributes.lastModifiedTime().toMillis();
                if (modified > newest) {
                    newest = modified;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.debug("Iris could not stat {} for validation reuse; revalidating", root, unreadable);
            return Long.MAX_VALUE;
        }
        return newest;
    }

    /**
     * SP-6: a pack excluded by validation is otherwise only visible in the console. Tell the operators who
     * can actually act on it when they join.
     */
    public static void warnPackFailuresTo(ServerPlayer player) {
        if (player == null || !Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
            return;
        }
        for (Map.Entry<String, PackValidationResult> entry : PackValidationRegistry.snapshot().entrySet()) {
            PackValidationResult result = entry.getValue();
            if (result == null || result.isLoadable()) {
                continue;
            }
            String reason = result.getBlockingErrors().isEmpty()
                    ? "unknown validation failure"
                    : result.getBlockingErrors().getFirst();
            player.sendSystemMessage(Component.literal("Iris pack '" + entry.getKey()
                    + "' failed validation and cannot be used: " + reason));
        }
    }

    public static void ensureDefaultPack() {
        synchronized (PACK_LOCK) {
            ModdedModConfig config = ModdedModConfig.get();
            if (!config.autoDownloadDefaultPack()) {
                return;
            }
            Path configDir = ModdedEngineBootstrap.loader().configDir();
            File packsRoot = ModdedPackCommands.packsRoot();
            for (String pack : startupPacks(config.defaultPack())) {
                ensureStartupPack(configDir, packsRoot, pack);
            }
        }
    }

    static List<String> startupPacks(String configuredDefault) {
        LinkedHashSet<String> packs = new LinkedHashSet<>(PackDownloader.managedBetaPacks());
        if (configuredDefault != null && !configuredDefault.isBlank()) {
            packs.add(configuredDefault);
        }
        return List.copyOf(packs);
    }

    private static void ensureStartupPack(Path configDir, File packsRoot, String pack) {
        boolean present = PackDownloader.isManagedBetaPack(pack)
                ? PackDownloader.isManagedBetaPackPresent(packsRoot, pack)
                : PackDownloader.isPackPresent(packsRoot, pack);
        if (present) {
            return;
        }
        boolean managedBeta = PackDownloader.isManagedBetaPack(pack);
        String branch = managedBeta ? "beta" : "master";
        String source = managedBeta ? "beta release" : "master branch";
        String role = managedBeta ? "managed beta pack" : "default pack";
        LOGGER.info("Iris {} '{}' missing; downloading IrisDimensions/{} ({})", role, pack, pack, source);
        boolean installed = ModdedPackInstaller.install(
                configDir, pack, branch, false, false,
                (String line) -> LOGGER.info("Iris: {}", line));
        if (!installed) {
            LOGGER.warn("Iris {} '{}' could not be downloaded; install it with /iris download {}", role, pack, pack);
        }
    }
}

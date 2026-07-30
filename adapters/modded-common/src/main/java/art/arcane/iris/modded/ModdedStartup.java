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
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import art.arcane.iris.core.pack.PackValidator;
import art.arcane.iris.modded.command.ModdedPackCommands;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public static void prefetchDefaultPack() {
        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler != null) {
            scheduler.async(ModdedStartup::ensureDefaultPack);
            return;
        }
        Thread thread = new Thread(ModdedStartup::ensureDefaultPack, "iris-modded-pack-prefetch");
        thread.setDaemon(true);
        thread.start();
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
        reinjectPersistentDimensions(server);

        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler == null) {
            ensureDefaultPack();
            return;
        }
        scheduler.async(ModdedStartup::ensureDefaultPack);
    }

    public static void validateAllPacks() {
        File packsRoot = ModdedPackCommands.packsRoot();
        File[] packDirs = packsRoot.listFiles(File::isDirectory);
        PackValidationRegistry.clear();
        if (packDirs == null || packDirs.length == 0) {
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
        File packDir = new File(ModdedPackCommands.packsRoot(), pack);
        if (!packDir.isDirectory()) {
            throw new BrokenPackException(pack, List.of(
                    "Pack folder does not exist under " + ModdedPackCommands.packsRoot().getAbsolutePath() + "."));
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
        List<ModdedDimensionRegistryStore.PersistentDimension> dimensions = ModdedDimensionRegistryStore.load(server);
        if (dimensions.isEmpty()) {
            return;
        }
        int injected = 0;
        for (ModdedDimensionRegistryStore.PersistentDimension dimension : dimensions) {
            try {
                ModdedDimensionManager.create(server, dimension.id(), dimension.pack(), dimension.dimension(), dimension.seed());
                injected++;
            } catch (Throwable e) {
                LOGGER.error("Iris failed to re-inject persistent dimension '{}' (pack={} dim={} seed={})", dimension.id(), dimension.pack(), dimension.dimension(), dimension.seed(), e);
                if (e instanceof Error fatalError) {
                    throw fatalError;
                }
            }
        }
        LOGGER.info("Iris re-injected {} persistent dimension(s) at startup", injected);
    }

    public static void ensureDefaultPack() {
        synchronized (PACK_LOCK) {
            ModdedModConfig config = ModdedModConfig.get();
            if (!config.autoDownloadDefaultPack()) {
                return;
            }
            String pack = config.defaultPack();
            Path configDir = ModdedEngineBootstrap.loader().configDir();
            File packFolder = new File(ModdedPackCommands.packsRoot(), pack);
            if (new File(packFolder, "dimensions/" + pack + ".json").isFile()) {
                return;
            }
            String source = "master branch";
            LOGGER.info("Iris default pack '{}' missing; downloading IrisDimensions/{} ({})", pack, pack, source);
            boolean installed = ModdedPackInstaller.install(
                    configDir, pack, "master", false,
                    (String line) -> LOGGER.info("Iris: {}", line));
            if (!installed) {
                LOGGER.warn("Iris default pack '{}' could not be downloaded; install it with /iris download {}", pack, pack);
            }
        }
    }
}

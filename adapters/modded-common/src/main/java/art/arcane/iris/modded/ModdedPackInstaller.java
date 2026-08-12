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

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.PackDownloadMessages;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ModdedPackInstaller {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Pattern PACK_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern BRANCH_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final ConcurrentHashMap<String, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();

    private ModdedPackInstaller() {
    }

    public static boolean install(Path configDir, String pack, String branch,
                                  boolean forceOverwrite, boolean refreshDatapack,
                                  Consumer<String> feedback) {
        if (pack == null || !PACK_NAME.matcher(pack).matches()) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.INVALID_PACK_NAME, MessageArgument.untrusted("pack", String.valueOf(pack))));
            return false;
        }
        boolean managedBeta = PackDownloader.isManagedBetaPack(pack);
        if (!acceptsBranch(pack, branch)) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.INVALID_BRANCH_NAME, MessageArgument.untrusted("branch", String.valueOf(branch))));
            return false;
        }

        Object installLock = INSTALL_LOCKS.computeIfAbsent(pack, key -> new Object());
        synchronized (installLock) {
            File packs = configDir.resolve("irisworldgen").resolve("packs").toFile();
            try {
                PackDownloader.PackInstallResult result = managedBeta
                        ? PackDownloader.downloadManagedBeta(packs, pack, forceOverwrite, feedback)
                        : PackDownloader.download(
                                packs,
                                "IrisDimensions/" + pack,
                                branch,
                                forceOverwrite,
                                false,
                                pack,
                                feedback);
                boolean installed = result != null;
                if (shouldRefreshDatapack(result, refreshDatapack)) {
                    // Pack-install completion is one of the four forced-datapack regeneration triggers; every
                    // install call site already runs off the server thread, so regenerate inline here. A
                    // regeneration failure must never turn a successful install into a failed one.
                    try {
                        ModdedForcedDatapack.regenerateIfStale("pack install " + pack);
                    } catch (Throwable regenerationFailure) {
                        LOGGER.error("Iris installed pack '{}' but could not regenerate the forced datapack", pack, regenerationFailure);
                    }
                }
                if (result != null && result.restartRequired()) {
                    feedback.accept("Pack '" + pack + "' is installed on disk and requires a server restart before its active data changes.");
                }
                return installed;
            } catch (IOException error) {
                String source = managedBeta ? "beta release" : "branch " + branch;
                LOGGER.error("Iris pack download failed for IrisDimensions/{} ({})", pack, source, error);
                feedback.accept(IrisLanguage.plain(
                        PackDownloadMessages.DOWNLOAD_FAILED,
                        MessageArgument.untrusted("type", error.getClass().getSimpleName()),
                        MessageArgument.trusted("errorMessage", IrisLanguage.errorDetail(error))
                ));
                return false;
            }
        }
    }

    static boolean acceptsBranch(String pack, String branch) {
        return PackDownloader.isManagedBetaPack(pack)
                || (branch != null && BRANCH_NAME.matcher(branch).matches());
    }

    static boolean shouldRefreshDatapack(PackDownloader.PackInstallResult result, boolean refreshDatapack) {
        return result != null && result.changed() && refreshDatapack;
    }
}

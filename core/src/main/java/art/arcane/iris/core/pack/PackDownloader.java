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

package art.arcane.iris.core.pack;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.PackDownloadMessages;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.misc.WebCache;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class PackDownloader {
    private static final String DEFAULT_OVERWORLD_PACK = "overworld";
    private static final String DEFAULT_OVERWORLD_REPOSITORY = "IrisDimensions/overworld";
    private static final String DEFAULT_OVERWORLD_RELEASE_URL = "https://github.com/IrisDimensions/overworld/releases/download/beta/overworld.zip";
    private static final Pattern GITHUB_REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern GITHUB_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");

    private PackDownloader() {
    }

    public static boolean isDefaultOverworld(String pack) {
        return DEFAULT_OVERWORLD_PACK.equals(pack);
    }

    public static String downloadDefaultOverworld(File packsFolder, boolean forceOverwrite, Consumer<String> feedback) throws IOException {
        return download(packsFolder, DEFAULT_OVERWORLD_REPOSITORY, defaultOverworldReleaseUrl(), forceOverwrite, true, feedback);
    }

    public static String download(File packsFolder, String repo, String ref, boolean forceOverwrite, boolean directUrl, Consumer<String> feedback) throws IOException {
        String url = directUrl ? ref : resolveGithubArchiveUrl(repo, ref);
        feedback.accept(IrisLanguage.plain(PackDownloadMessages.DOWNLOADING, MessageArgument.untrusted("url", url)) + " "); //The extra space stops a bug in adventure API from repeating the last letter of the URL
        File zip = WebCache.getNonCachedFile("pack-" + repo, url);
        File temp = WebCache.getTemp();
        File work = new File(temp, "dl-" + UUID.randomUUID());

        if (zip == null || !zip.exists()) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.FAILED_TO_FIND, MessageArgument.untrusted("url", url)));
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.CHECK_REPOSITORY_AND_BRANCH));
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.EXAMPLE_COMMAND));
            return null;
        }
        feedback.accept(IrisLanguage.plain(PackDownloadMessages.UNPACKING, MessageArgument.untrusted("repository", repo)));
        try {
            ZipUtil.unpack(zip, work);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.UNPACK_FAILED));
        }
        File dir = null;
        File[] zipFiles = work.listFiles();

        if (zipFiles == null) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.NO_EXTRACTED_FILES));
            return null;
        }

        try {
            dir = zipFiles.length > 1 ? work : zipFiles[0].isDirectory() ? zipFiles[0] : null;
        } catch (NullPointerException e) {
            IrisLogging.reportError(e);
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.HOME_DIRECTORY_ERROR));
            return null;
        }

        if (dir == null) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.INVALID_ARCHIVE_FORMAT));
            return null;
        }

        IrisData data = IrisData.get(dir);
        String[] dimensions = data.getDimensionLoader().getPossibleKeys();

        if (dimensions == null || dimensions.length == 0) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.NO_DIMENSION_FILE));
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.CHECK_GITHUB));
            return null;
        }

        if (dimensions.length != 1) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.ONE_DIMENSION_REQUIRED));
            return null;
        }

        IrisDimension d = data.getDimensionLoader().load(dimensions[0]);
        data.close();

        if (d == null) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.INVALID_DIMENSION));
            return null;
        }

        String key = d.getLoadKey();
        feedback.accept(IrisLanguage.plain(PackDownloadMessages.IMPORTING, MessageArgument.untrusted("name", d.getName()), MessageArgument.untrusted("key", key)));
        File packEntry = new File(packsFolder, key);

        if (forceOverwrite) {
            IO.delete(packEntry);
        }

        if (IrisData.loadAnyDimension(key, null) != null) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.DIMENSION_KEY_CONFLICT, MessageArgument.untrusted("key", key)));
            return null;
        }

        File[] existingEntries = packEntry.listFiles();
        if (packEntry.exists() && existingEntries != null && existingEntries.length > 0) {
            feedback.accept(IrisLanguage.plain(PackDownloadMessages.PACK_KEY_CONFLICT, MessageArgument.untrusted("key", key)));
            return null;
        }

        FileUtils.copyDirectory(dir, packEntry);

        IrisData.getLoaded(packEntry)
                .ifPresent(IrisData::hotloaded);

        feedback.accept(IrisLanguage.plain(PackDownloadMessages.ACQUIRED, MessageArgument.untrusted("name", d.getName())));
        validateDownloaded(packEntry, feedback);
        return key;
    }

    static String resolveGithubArchiveUrl(String repo, String ref) {
        if (repo == null || !GITHUB_REPOSITORY.matcher(repo).matches()) {
            throw new IllegalArgumentException("Invalid GitHub repository '" + repo + "'");
        }
        String[] repositoryParts = repo.split("/", -1);
        if (repositoryParts[0].equals(".") || repositoryParts[0].equals("..")
                || repositoryParts[1].equals(".") || repositoryParts[1].equals("..")) {
            throw new IllegalArgumentException("Invalid GitHub repository '" + repo + "'");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("GitHub reference cannot be empty");
        }
        if (COMMIT_SHA.matcher(ref).matches()) {
            return "https://github.com/" + repo + "/archive/" + ref + ".zip";
        }
        if (ref.startsWith("refs/") && !ref.startsWith("refs/heads/") && !ref.startsWith("refs/tags/")) {
            throw new IllegalArgumentException("Unsupported GitHub reference '" + ref + "'");
        }

        String qualifiedRef;
        if (ref.startsWith("refs/heads/") || ref.startsWith("refs/tags/")) {
            qualifiedRef = ref;
        } else {
            qualifiedRef = "refs/heads/" + ref;
        }
        validateGithubRef(qualifiedRef);
        return "https://codeload.github.com/" + repo + "/zip/" + qualifiedRef;
    }

    static String defaultOverworldReleaseUrl() {
        return DEFAULT_OVERWORLD_RELEASE_URL;
    }

    private static void validateGithubRef(String qualifiedRef) {
        String refPath = qualifiedRef.startsWith("refs/heads/")
                ? qualifiedRef.substring("refs/heads/".length())
                : qualifiedRef.substring("refs/tags/".length());
        if (!GITHUB_REF.matcher(refPath).matches()
                || refPath.contains("..")
                || refPath.contains("//")
                || refPath.contains("@{")
                || refPath.endsWith("/")
                || refPath.endsWith(".")
                || refPath.endsWith(".lock")) {
            throw new IllegalArgumentException("Invalid GitHub reference '" + qualifiedRef + "'");
        }

        String[] segments = refPath.split("/");
        for (String segment : segments) {
            if (segment.startsWith(".") || segment.endsWith(".lock")) {
                throw new IllegalArgumentException("Invalid GitHub reference '" + qualifiedRef + "'");
            }
        }
    }

    private static void validateDownloaded(File packEntry, Consumer<String> feedback) {
        try {
            PackValidationResult result = PackValidator.validate(packEntry);
            PackValidationRegistry.publish(result);

            if (!result.isLoadable()) {
                feedback.accept(IrisLanguage.plain(PackDownloadMessages.VALIDATION_FAILED, MessageArgument.untrusted("pack", result.getPackName())));
                for (String reason : result.getBlockingErrors()) {
                    feedback.accept(IrisLanguage.plain(PackDownloadMessages.VALIDATION_REASON, MessageArgument.untrusted("reason", reason)));
                }
            } else if (!result.getWarnings().isEmpty()) {
                feedback.accept(IrisLanguage.plain(
                        PackDownloadMessages.VALIDATED_WITH_WARNINGS,
                        MessageArgument.untrusted("pack", result.getPackName()),
                        MessageArgument.trusted("count", result.getWarnings().size())
                ));
            } else {
                feedback.accept(IrisLanguage.plain(PackDownloadMessages.VALIDATED, MessageArgument.untrusted("pack", result.getPackName())));
            }
        } catch (Throwable e) {
            IrisLogging.reportError("Pack validation failed for '" + packEntry.getName() + "'", e);
        }
    }
}

package art.arcane.iris.core.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;
import java.util.Map;

public final class PackDownloadMessages {
    public static final TextKey INVALID_PACK_NAME = TextKey.of(
            "iris.runtime.pack_download.invalid_pack_name",
            "Invalid pack name '{pack}' (allowed: a-z, 0-9, _ and -)"
    );
    public static final TextKey INVALID_BRANCH_NAME = TextKey.of(
            "iris.runtime.pack_download.invalid_branch_name",
            "Invalid branch name '{branch}' (allowed: letters, digits, . _ and -)"
    );
    public static final TextKey DOWNLOAD_FAILED = TextKey.of(
            "iris.runtime.pack_download.failed",
            "Pack download failed: {type}{errorMessage}"
    );
    public static final TextKey DOWNLOADING = TextKey.of(
            "iris.runtime.pack_download.downloading",
            "Downloading {url}"
    );
    public static final TextKey FAILED_TO_FIND = TextKey.of(
            "iris.runtime.pack_download.failed_to_find",
            "Failed to find pack at {url}"
    );
    public static final TextKey CHECK_REPOSITORY_AND_BRANCH = TextKey.of(
            "iris.runtime.pack_download.check_repository_and_branch",
            "Make sure you specified the correct repo and branch!"
    );
    public static final TextKey EXAMPLE_COMMAND = TextKey.of(
            "iris.runtime.pack_download.example_command",
            "For example: /iris download overworld branch=stable"
    );
    public static final TextKey UNPACKING = TextKey.of(
            "iris.runtime.pack_download.unpacking",
            "Unpacking {repository}"
    );
    public static final LinesKey UNPACK_FAILED = LinesKey.of(
            "iris.runtime.pack_download.unpack_failed",
            "Issue when unpacking. Please check/do the following:",
            "1. Do you have a functioning internet connection?",
            "2. Did the download corrupt?",
            "3. Try deleting the */plugins/iris/packs folder and re-download.",
            "4. Download the pack from the GitHub repo: https://github.com/IrisDimensions/overworld",
            "5. Contact support (if all other options do not help)"
    );
    public static final TextKey NO_EXTRACTED_FILES = TextKey.of(
            "iris.runtime.pack_download.no_extracted_files",
            "No files were extracted from the zip file."
    );
    public static final TextKey HOME_DIRECTORY_ERROR = TextKey.of(
            "iris.runtime.pack_download.home_directory_error",
            "Error when finding home directory. Are there any non-text characters in the file name?"
    );
    public static final TextKey INVALID_ARCHIVE_FORMAT = TextKey.of(
            "iris.runtime.pack_download.invalid_archive_format",
            "Invalid format. Missing root folder or too many folders!"
    );
    public static final TextKey NO_DIMENSION_FILE = TextKey.of(
            "iris.runtime.pack_download.no_dimension_file",
            "No dimension file found in the extracted zip file."
    );
    public static final TextKey CHECK_GITHUB = TextKey.of(
            "iris.runtime.pack_download.check_github",
            "Check that it is present on GitHub and report this to staff!"
    );
    public static final TextKey ONE_DIMENSION_REQUIRED = TextKey.of(
            "iris.runtime.pack_download.one_dimension_required",
            "The dimensions folder must contain exactly one file."
    );
    public static final TextKey INVALID_DIMENSION = TextKey.of(
            "iris.runtime.pack_download.invalid_dimension",
            "Invalid dimension folder under dimensions/."
    );
    public static final TextKey IMPORTING = TextKey.of(
            "iris.runtime.pack_download.importing",
            "Importing {name} ({key})"
    );
    public static final TextKey DIMENSION_KEY_CONFLICT = TextKey.of(
            "iris.runtime.pack_download.dimension_key_conflict",
            "Another dimension in the packs folder is already using the key {key}. Import failed!"
    );
    public static final TextKey PACK_KEY_CONFLICT = TextKey.of(
            "iris.runtime.pack_download.pack_key_conflict",
            "Another pack is using the key {key}. Import failed!"
    );
    public static final TextKey ACQUIRED = TextKey.of(
            "iris.runtime.pack_download.acquired",
            "Successfully acquired {name}."
    );
    public static final TextKey VALIDATION_FAILED = TextKey.of(
            "iris.runtime.pack_download.validation_failed",
            "Pack '{pack}' failed validation; world and Studio creation will be refused. Reasons:"
    );
    public static final TextKey VALIDATION_REASON = TextKey.of(
            "iris.runtime.pack_download.validation_reason",
            "  - {reason}"
    );
    public static final PluralKey VALIDATED_WITH_WARNINGS = PluralKey.of(
            "iris.runtime.pack_download.validated_with_warnings",
            "count",
            Map.of(
                    "one", "Pack '{pack}' validated with {count} warning.",
                    "other", "Pack '{pack}' validated with {count} warnings."
            )
    );
    public static final TextKey VALIDATED = TextKey.of(
            "iris.runtime.pack_download.validated",
            "Pack '{pack}' validated."
    );

    private static final List<MessageKey> KEYS = List.of(
            INVALID_PACK_NAME,
            INVALID_BRANCH_NAME,
            DOWNLOAD_FAILED,
            DOWNLOADING,
            FAILED_TO_FIND,
            CHECK_REPOSITORY_AND_BRANCH,
            EXAMPLE_COMMAND,
            UNPACKING,
            UNPACK_FAILED,
            NO_EXTRACTED_FILES,
            HOME_DIRECTORY_ERROR,
            INVALID_ARCHIVE_FORMAT,
            NO_DIMENSION_FILE,
            CHECK_GITHUB,
            ONE_DIMENSION_REQUIRED,
            INVALID_DIMENSION,
            IMPORTING,
            DIMENSION_KEY_CONFLICT,
            PACK_KEY_CONFLICT,
            ACQUIRED,
            VALIDATION_FAILED,
            VALIDATION_REASON,
            VALIDATED_WITH_WARNINGS,
            VALIDATED
    );

    private PackDownloadMessages() {
    }

    public static List<MessageKey> keys() {
        return KEYS;
    }
}

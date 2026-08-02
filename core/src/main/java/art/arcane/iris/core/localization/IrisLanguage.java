package art.arcane.iris.core.localization;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationIssue;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.ResolvedLines;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class IrisLanguage {
    private static final long MAX_LOCALE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_REPORTED_ISSUES = 12;
    private static final Pattern LOCALE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)\\u00a7[0-9A-FK-ORX]");
    private static final MessageCatalog CATALOG = IrisMessages.catalog();
    private static final LocalizationManager MANAGER = new LocalizationManager(
            LocalizationCandidate.english(CATALOG, PluralSelector.oneOther())
    );
    /**
     * Memoized argument-free {@link #plain(MessageKey)} results. HUD and overlay code calls it several times
     * per frame for fixed labels; resolve plus placeholder render plus colour clean is not free at 60fps.
     * Keyed by message id and pinned to the snapshot it was resolved against, so a locale reload publishes a
     * new snapshot and the whole memo is discarded. Bounded by the catalog size.
     */
    private static final AtomicReference<PlainMemo> PLAIN_MEMO = new AtomicReference<>(null);

    private static volatile File dataFolder;
    private static volatile File watchedFile;
    private static volatile long watchedSignature = Long.MIN_VALUE;
    private static volatile String activeLocale = CATALOG.englishLocale();

    private IrisLanguage() {
    }

    public static boolean initialize() {
        if (!IrisPlatforms.isBound()) {
            return false;
        }
        return reload(IrisPlatforms.get().dataFolder(), configuredLocale());
    }

    public static synchronized boolean reload() {
        File root = dataFolder;
        if (root == null && IrisPlatforms.isBound()) {
            root = IrisPlatforms.get().dataFolder();
        }
        if (root == null) {
            return false;
        }
        return reload(root, configuredLocale());
    }

    public static synchronized boolean reload(File root, String locale) {
        File resolvedRoot = root == null ? null : root.getAbsoluteFile();
        if (resolvedRoot == null) {
            throw new IllegalArgumentException("Iris locale data folder cannot be null");
        }
        String requestedLocale;
        try {
            requestedLocale = normalizeLocale(locale);
        } catch (RuntimeException exception) {
            dataFolder = resolvedRoot;
            IrisLogging.error("Rejected locale setting '" + locale + "'; continuing with " + activeLocale + ".");
            IrisLogging.reportError(exception);
            exception.printStackTrace();
            return false;
        }
        LocalizationReloadResult result = MANAGER.reload(() -> loadCandidate(resolvedRoot, requestedLocale));
        dataFolder = resolvedRoot;
        File override = overrideFile(resolvedRoot, requestedLocale);
        watchedFile = override;
        watchedSignature = signature(override);
        if (!result.applied()) {
            reportRejectedReload(requestedLocale, result);
            return false;
        }

        activeLocale = requestedLocale;
        int warnings = result.validation().warnings().size();
        IrisLogging.info("Loaded locale " + requestedLocale + " with " + warnings + " fallback "
                + (warnings == 1 ? "entry" : "entries") + ".");
        return true;
    }

    public static synchronized boolean update() {
        File root = dataFolder;
        if (root == null) {
            return initialize();
        }
        String locale;
        try {
            locale = normalizeLocale(configuredLocale());
        } catch (RuntimeException exception) {
            return reload(root, configuredLocale());
        }
        File expected = overrideFile(root, locale);
        long signature = signature(expected);
        if (expected.equals(watchedFile) && signature == watchedSignature) {
            return true;
        }
        return reload(root, locale);
    }

    public static String activeLocale() {
        return activeLocale;
    }

    public static File overrideFolder() {
        File root = dataFolder;
        if (root == null && IrisPlatforms.isBound()) {
            root = IrisPlatforms.get().dataFolder();
        }
        if (root == null) {
            return null;
        }
        return new File(root, "languages/overrides");
    }

    public static String text(MessageKey key, MessageArgument... arguments) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument argument : arguments) {
            builder.add(argument);
        }
        return text(key, builder.build());
    }

    public static String text(MessageKey key) {
        return text(key, MessageArgs.empty());
    }

    public static String text(MessageKey key, MessageArgs arguments) {
        return render(resolve(key, arguments));
    }

    public static String plain(MessageKey key, MessageArgument... arguments) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument argument : arguments) {
            builder.add(argument);
        }
        return plain(key, builder.build());
    }

    public static String plain(MessageKey key) {
        LocalizationSnapshot snapshot = MANAGER.snapshot();
        PlainMemo memo = PLAIN_MEMO.get();
        if (memo == null || memo.snapshot() != snapshot) {
            memo = new PlainMemo(snapshot, new ConcurrentHashMap<>());
            PLAIN_MEMO.set(memo);
        }
        String cached = memo.values().get(key.id());
        if (cached != null) {
            return cached;
        }
        String resolved = plain(key, MessageArgs.empty());
        memo.values().put(key.id(), resolved);
        return resolved;
    }

    public static String plain(MessageKey key, MessageArgs arguments) {
        String rendered = render(resolve(key, arguments));
        return IrisLogging.clean(rendered);
    }

    public static String errorDetail(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        return plain(RuntimeUiMessages.ERROR_DETAIL_SUFFIX, MessageArgument.untrusted("error", message));
    }

    public static DirectorTextResolver directorResolver() {
        return (key, arguments) -> {
            MessageKey definition = CATALOG.key(key.id());
            if (!(definition instanceof TextKey textKey)) {
                return DirectorTextResolver.ENGLISH.resolve(key, arguments);
            }
            return plain(textKey, arguments);
        };
    }

    public static MessageCatalog catalog() {
        return CATALOG;
    }

    private static ResolvedText resolve(MessageKey key, MessageArgs arguments) {
        if (key instanceof TextKey textKey) {
            return MANAGER.snapshot().resolve(textKey, arguments);
        }
        if (key instanceof PluralKey pluralKey) {
            return MANAGER.snapshot().resolve(pluralKey, arguments);
        }
        if (key instanceof LinesKey linesKey) {
            ResolvedLines lines = MANAGER.snapshot().resolve(linesKey, arguments);
            return new ResolvedText(lines.key(), lines.locale(), String.join("\n", lines.lines()), lines.arguments());
        }
        throw new IllegalArgumentException("Unsupported Iris message key: " + key.id());
    }

    private static LocalizationCandidate loadCandidate(File root, String locale) throws Exception {
        File folder = new File(root, "languages/overrides");
        Files.createDirectories(folder.toPath());
        List<LocaleOverlay> overlays = new ArrayList<>(2);
        File override = overrideFile(root, locale);
        if (override.exists()) {
            overlays.add(loadFileOverlay(override, locale));
        }

        if (!CATALOG.englishLocale().equals(locale)) {
            LocaleOverlay bundled = loadBundledOverlay(locale);
            if (bundled != null) {
                overlays.add(bundled);
            }
        }
        return new LocalizationCandidate(CATALOG, overlays, PluralSelector.oneOther());
    }

    static LocaleOverlay loadBundledOverlay(String locale) throws Exception {
        String normalizedLocale = normalizeLocale(locale);
        if (CATALOG.englishLocale().equals(normalizedLocale)) {
            return null;
        }
        String resourceName = "/languages/" + normalizedLocale + ".json";
        InputStream input = IrisLanguage.class.getResourceAsStream(resourceName);
        if (input == null) {
            if (VolmitLocales.isBundled(normalizedLocale)) {
                throw new IllegalStateException("Missing bundled Iris locale resource: " + resourceName);
            }
            return null;
        }
        try (InputStream stream = input) {
            byte[] bytes = stream.readNBytes((int) MAX_LOCALE_BYTES + 1);
            if (bytes.length > MAX_LOCALE_BYTES) {
                throw new IllegalArgumentException("Bundled locale is too large: " + resourceName);
            }
            return parseOverlay("bundled:" + resourceName, normalizedLocale, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static LocaleOverlay loadFileOverlay(File override, String locale) throws Exception {
        if (!override.isFile()) {
            throw new IllegalArgumentException("Locale override is not a regular file: " + override.getPath());
        }
        if (override.length() > MAX_LOCALE_BYTES) {
            throw new IllegalArgumentException("Locale override is too large: " + override.getPath());
        }
        String raw = Files.readString(override.toPath(), StandardCharsets.UTF_8);
        return parseOverlay(override.getPath(), locale, raw);
    }

    private static LocaleOverlay parseOverlay(String source, String locale, String raw) {
        JsonElement parsed = JsonParser.parseString(raw == null || raw.isBlank() ? "{}" : raw);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Locale source is not a JSON object: " + source);
        }
        JsonObject root = parsed.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!key.equals("locale") && !key.equals("messages")) {
                throw new IllegalArgumentException("Unknown locale root key: " + key);
            }
        }
        if (root.has("locale")) {
            JsonElement declaredLocale = root.get("locale");
            if (!declaredLocale.isJsonPrimitive() || !locale.equals(normalizeLocale(declaredLocale.getAsString()))) {
                throw new IllegalArgumentException("Locale source declares a different locale than its file: " + source);
            }
        }
        LocaleOverlay.Builder builder = LocaleOverlay.builder(source, locale);
        if (!root.has("messages")) {
            return builder.build();
        }
        JsonElement messages = root.get("messages");
        if (!messages.isJsonObject()) {
            throw new IllegalArgumentException("Locale messages must be a JSON object: " + source);
        }
        appendMessages(builder, messages.getAsJsonObject(), "");
        return builder.build();
    }

    private static void appendMessages(LocaleOverlay.Builder builder, JsonObject object, String prefix) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                throw new IllegalArgumentException("Locale value cannot be null: " + key);
            }
            MessageKey definition = CATALOG.key(key);
            if (value.isJsonObject() && definition instanceof PluralKey) {
                builder.plural(key, readPlural(key, value.getAsJsonObject()));
            } else if (value.isJsonObject()) {
                appendMessages(builder, value.getAsJsonObject(), key);
            } else if (value.isJsonArray()) {
                builder.lines(key, readLines(key, value.getAsJsonArray()));
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                builder.text(key, value.getAsString());
            } else {
                throw new IllegalArgumentException("Locale value must be text, lines, or plural forms: " + key);
            }
        }
    }

    private static List<String> readLines(String key, JsonArray array) {
        List<String> lines = new ArrayList<>(array.size());
        for (JsonElement value : array) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Locale line must be text: " + key);
            }
            lines.add(value.getAsString());
        }
        return lines;
    }

    private static Map<String, String> readPlural(String key, JsonObject object) {
        Map<String, String> forms = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Locale plural form must be text: " + key + "." + entry.getKey());
            }
            forms.put(entry.getKey(), value.getAsString());
        }
        return forms;
    }

    private static String render(ResolvedText resolved) {
        String prepared = resolved.template();
        List<RenderedArgument> replacements = new ArrayList<>(resolved.arguments().size());
        int index = 0;
        for (MessageArgument argument : resolved.arguments().arguments().values()) {
            String token = "\uE000" + index + "\uE001";
            prepared = prepared.replace("{" + argument.name() + "}", token);
            replacements.add(new RenderedArgument(token, argument));
            index++;
        }

        String rendered = translateColors(prepared);
        StringBuilder output = new StringBuilder(rendered.length());
        int cursor = 0;
        while (cursor < rendered.length()) {
            if (rendered.charAt(cursor) != '\uE000') {
                output.append(rendered.charAt(cursor));
                cursor++;
                continue;
            }
            int end = rendered.indexOf('\uE001', cursor + 1);
            int replacementIndex = end < 0 ? -1 : parseReplacementIndex(rendered, cursor + 1, end);
            if (replacementIndex < 0 || replacementIndex >= replacements.size()) {
                output.append(rendered.charAt(cursor));
                cursor++;
                continue;
            }
            RenderedArgument replacement = replacements.get(replacementIndex);
            if (replacement.token().length() != end - cursor + 1
                    || !rendered.regionMatches(cursor, replacement.token(), 0, replacement.token().length())) {
                output.append(rendered.charAt(cursor));
                cursor++;
                continue;
            }
            MessageArgument argument = replacement.argument();
            String value = String.valueOf(argument.value());
            output.append(argument.kind() == MessageArgumentKind.TRUSTED
                    ? translateColors(value)
                    : escapeUntrusted(value));
            cursor = end + 1;
        }
        return output.toString();
    }

    private static int parseReplacementIndex(String value, int start, int end) {
        if (start >= end) {
            return -1;
        }
        int result = 0;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return -1;
            }
            int digit = character - '0';
            if (result > (Integer.MAX_VALUE - digit) / 10) {
                return -1;
            }
            result = result * 10 + digit;
        }
        return result;
    }

    private static String translateColors(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char[] characters = value.toCharArray();
        for (int index = 0; index < characters.length - 1; index++) {
            if (characters[index] == '&' && isColorCode(characters[index + 1])) {
                characters[index] = '\u00a7';
                characters[index + 1] = Character.toLowerCase(characters[index + 1]);
            }
        }
        return new String(characters);
    }

    private static boolean isColorCode(char value) {
        char lowered = Character.toLowerCase(value);
        return lowered >= '0' && lowered <= '9' || lowered >= 'a' && lowered <= 'f'
                || lowered >= 'k' && lowered <= 'o' || lowered == 'r' || lowered == 'x';
    }

    private static String escapeUntrusted(String value) {
        return LEGACY_COLOR.matcher(value).replaceAll("")
                .replace("&", "＆")
                .replace("<", "‹")
                .replace(">", "›");
    }

    private static String configuredLocale() {
        IrisSettings.IrisSettingsGeneral general = IrisSettings.get().getGeneral();
        return general == null ? CATALOG.englishLocale() : general.getLanguage();
    }

    private static String normalizeLocale(String locale) {
        String value = locale == null || locale.isBlank() ? CATALOG.englishLocale() : locale.trim();
        if (!LOCALE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid locale name: " + value);
        }
        return value;
    }

    private static File overrideFile(File root, String locale) {
        return new File(new File(root, "languages/overrides"), normalizeLocale(locale) + ".json").getAbsoluteFile();
    }

    private static long signature(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        return file.lastModified() * 31L + file.length();
    }

    private static void reportRejectedReload(String locale, LocalizationReloadResult result) {
        IrisLogging.error("Rejected locale reload for " + locale + "; continuing with " + activeLocale + ".");
        List<LocalizationIssue> issues = result.validation().errors();
        for (int index = 0; index < Math.min(issues.size(), MAX_REPORTED_ISSUES); index++) {
            LocalizationIssue issue = issues.get(index);
            IrisLogging.error(issue.source() + " [" + issue.key() + "]: " + issue.detail());
        }
        if (issues.size() > MAX_REPORTED_ISSUES) {
            IrisLogging.error((issues.size() - MAX_REPORTED_ISSUES) + " additional locale errors were omitted.");
        }
        if (result.failure() != null) {
            IrisLogging.reportError(result.failure());
            result.failure().printStackTrace();
        }
    }

    private record RenderedArgument(String token, MessageArgument argument) {
    }

    private record PlainMemo(LocalizationSnapshot snapshot, Map<String, String> values) {
    }
}

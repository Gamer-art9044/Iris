package art.arcane.iris.core.pack;

import art.arcane.iris.core.IrisDatapackCompiler;
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.core.nms.datapack.IDataFixer;
import art.arcane.volmlib.util.collection.KList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class DefaultPackBootstrapProvisioner {
    private static final URI DEFAULT_SOURCE = URI.create("https://github.com/IrisDimensions/overworld/releases/download/beta/overworld.zip");
    private static final String WORLD_DATAPACK_DIRECTORY = "iris";
    private static final int PACK_FORMAT = 107;
    private static final int MARKER_SCHEMA = 2;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final AtomicBoolean PROVISIONED_THIS_STARTUP = new AtomicBoolean(false);

    private DefaultPackBootstrapProvisioner() {
    }

    public static ProvisionResult provision(Path dataDirectory, Consumer<String> feedback) throws IOException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(feedback, "feedback");
        Path serverRoot = Path.of("").toAbsolutePath().normalize();
        Path levelRoot = resolveLevelRoot(serverRoot);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        ProvisionOptions options = new ProvisionOptions(
                DEFAULT_SOURCE,
                client,
                Clock.systemUTC(),
                Duration.ofMinutes(30),
                Duration.ofSeconds(45),
                3,
                Duration.ofMillis(250),
                MAX_ARCHIVE_BYTES,
                levelRoot
        );
        return provision(dataDirectory, feedback, options);
    }

    public static boolean isProvisioned(Path dataDirectory) {
        if (dataDirectory == null) {
            return false;
        }
        try {
            return isProvisioned(
                    dataDirectory,
                    resolveLevelRoot(Path.of("").toAbsolutePath().normalize())
            );
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static boolean isProvisioned(Path dataDirectory, Path levelRoot) {
        try {
            Path normalizedData = dataDirectory.toAbsolutePath().normalize();
            Path normalizedLevel = levelRoot.toAbsolutePath().normalize();
            Path bootstrapRoot = normalizedData.resolve("bootstrap");
            Path datapackRoot = worldDatapackRoot(normalizedLevel);
            Path packRoot = normalizedData.resolve("packs/overworld");
            Path markerFile = bootstrapRoot.resolve("provisioned.properties");
            if (!Files.isRegularFile(markerFile) || !isPackRoot(packRoot) || !isDatapackRoot(datapackRoot)) {
                return false;
            }
            Properties marker = loadProperties(markerFile);
            if (!Integer.toString(MARKER_SCHEMA).equals(marker.getProperty("schema"))) {
                return false;
            }
            return directoryFingerprint(packRoot).equals(marker.getProperty("defaultPackFingerprint"))
                    && directoryFingerprint(datapackRoot).equals(marker.getProperty("datapackFingerprint"))
                    && datapackRoot.toString().equals(marker.getProperty("datapackPath"))
                    && packRootsFingerprint(IrisDatapackCompiler.collectPackRoots(
                            normalizedData,
                            normalizedLevel
                    )).equals(marker.getProperty("aggregateFingerprint"));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    public static boolean wasProvisionedThisStartup() {
        return PROVISIONED_THIS_STARTUP.get();
    }

    static ProvisionResult provision(Path dataDirectory, Consumer<String> feedback, ProvisionOptions options) throws IOException {
        Path normalizedData = dataDirectory.toAbsolutePath().normalize();
        Path packsRoot = normalizedData.resolve("packs");
        Path packRoot = packsRoot.resolve("overworld");
        Path bootstrapRoot = normalizedData.resolve("bootstrap");
        Path legacyDatapackRoot = bootstrapRoot.resolve("datapack");
        Path datapacksRoot = options.levelRoot().toAbsolutePath().normalize().resolve("datapacks");
        Path datapackRoot = datapacksRoot.resolve(WORLD_DATAPACK_DIRECTORY);
        Path markerFile = bootstrapRoot.resolve("provisioned.properties");
        Path cacheRoot = normalizedData.resolve("cache/bootstrap");
        Files.createDirectories(packsRoot);
        Files.createDirectories(bootstrapRoot);
        Files.createDirectories(cacheRoot);
        Files.createDirectories(datapacksRoot);

        Properties previousMarker = Files.isRegularFile(markerFile) ? loadProperties(markerFile) : new Properties();
        boolean existingPack = isPackRoot(packRoot);
        boolean existingDatapack = isDatapackRoot(datapackRoot);
        String currentPackFingerprint = existingPack ? directoryFingerprint(packRoot) : "";
        boolean markerOwned = "true".equals(previousMarker.getProperty("managedDefault"));
        boolean unchangedManagedPack = markerOwned
                && currentPackFingerprint.equals(previousMarker.getProperty("defaultPackFingerprint"));
        boolean managedDefault = !existingPack || unchangedManagedPack;
        if (Files.isSymbolicLink(packRoot)) {
            managedDefault = false;
        }
        Archive archive = managedDefault
                ? acquireArchive(cacheRoot, previousMarker, feedback, options)
                : new Archive(null, currentPackFingerprint);
        boolean replacePack = !existingPack || managedDefault
                && (!archive.sha256().equals(previousMarker.getProperty("sourceSha256"))
                || !currentPackFingerprint.equals(previousMarker.getProperty("defaultPackFingerprint")));

        Path stagedPack = null;
        Path extractionRoot = null;
        Path compileContainer = null;
        Path packBackup = null;
        Path datapackBackup = null;
        boolean packReplaced = false;
        boolean datapackReplaced = false;
        try {
            if (replacePack) {
                extractionRoot = cacheRoot.resolve(".extract-" + UUID.randomUUID());
                Files.createDirectories(extractionRoot);
                Path extractedPack = extractArchive(archive.path(), extractionRoot);
                stagedPack = packsRoot.resolve(".overworld-stage-" + UUID.randomUUID());
                copyDirectory(extractedPack, stagedPack);
                validatePackRoot(stagedPack);
                packBackup = replaceWithBackup(stagedPack, packRoot);
                packReplaced = true;
            }

            List<File> packRoots = IrisDatapackCompiler.collectPackRoots(normalizedData, options.levelRoot());
            if (packRoots.isEmpty()) {
                throw new IOException("No Iris pack roots were available for bootstrap datapack compilation");
            }
            String aggregateFingerprint = packRootsFingerprint(packRoots);
            boolean rebuildDatapack = replacePack
                    || !existingDatapack
                    || !aggregateFingerprint.equals(previousMarker.getProperty("aggregateFingerprint"))
                    || !datapackRoot.toString().equals(previousMarker.getProperty("datapackPath"))
                    || !directoryFingerprint(datapackRoot).equals(previousMarker.getProperty("datapackFingerprint"));
            if (rebuildDatapack) {
                compileContainer = datapacksRoot.resolve("." + WORLD_DATAPACK_DIRECTORY + "-stage-" + UUID.randomUUID());
                Files.createDirectories(compileContainer);
                KList<File> outputFolders = new KList<File>().qadd(compileContainer.toFile());
                IDataFixer fixer = DataVersion.getLatest().get();
                if (fixer == null) {
                    throw new IOException("Latest Iris datapack fixer is unavailable during bootstrap");
                }
                IrisDatapackCompiler.compile(packRoots, outputFolders, fixer, PACK_FORMAT, false);
                if (!isDatapackRoot(compileContainer)) {
                    throw new IOException("Canonical Iris datapack compiler produced incomplete output at " + compileContainer);
                }
                datapackBackup = replaceWithBackup(compileContainer, datapackRoot);
                compileContainer = null;
                datapackReplaced = true;
            }

            validatePackRoot(packRoot);
            if (!isDatapackRoot(datapackRoot)) {
                throw new IOException("Bootstrap datapack output is incomplete at " + datapackRoot);
            }
            String finalPackFingerprint = directoryFingerprint(packRoot);
            String finalAggregateFingerprint = packRootsFingerprint(
                    IrisDatapackCompiler.collectPackRoots(normalizedData, options.levelRoot())
            );
            String finalDatapackFingerprint = directoryFingerprint(datapackRoot);
            Properties marker = new Properties();
            marker.setProperty("schema", Integer.toString(MARKER_SCHEMA));
            marker.setProperty("source", options.source().toString());
            marker.setProperty("sourceSha256", archive.sha256());
            marker.setProperty("managedDefault", Boolean.toString(managedDefault));
            marker.setProperty("defaultPackFingerprint", finalPackFingerprint);
            marker.setProperty("aggregateFingerprint", finalAggregateFingerprint);
            marker.setProperty("datapackFingerprint", finalDatapackFingerprint);
            marker.setProperty("datapackPath", datapackRoot.toString());
            marker.setProperty("completedAt", Long.toString(options.clock().millis()));
            storePropertiesAtomic(markerFile, marker);
            PROVISIONED_THIS_STARTUP.set(true);
            deleteQuietly(packBackup, feedback);
            deleteQuietly(datapackBackup, feedback);
            deleteQuietly(legacyDatapackRoot, feedback);

            ProvisionStatus status;
            if (!existingPack && !existingDatapack) {
                status = ProvisionStatus.INSTALLED;
            } else if (replacePack || rebuildDatapack) {
                status = ProvisionStatus.UPDATED;
            } else {
                status = ProvisionStatus.UNCHANGED;
            }
            feedback.accept("Iris bootstrap pack is " + status.name().toLowerCase() + ".");
            return new ProvisionResult(packRoot, datapackRoot, status);
        } catch (IOException failure) {
            IOException rollbackFailure = rollback(packRoot, packBackup, packReplaced, datapackRoot, datapackBackup, datapackReplaced);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            IOException rollbackFailure = rollback(packRoot, packBackup, packReplaced, datapackRoot, datapackBackup, datapackReplaced);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IOException("Iris bootstrap provisioning failed", failure);
        } finally {
            deleteQuietly(stagedPack, feedback);
            deleteQuietly(extractionRoot, feedback);
            deleteQuietly(compileContainer, feedback);
        }
    }

    private static Archive acquireArchive(
            Path cacheRoot,
            Properties marker,
            Consumer<String> feedback,
            ProvisionOptions options
    ) throws IOException {
        Path archivePath = cacheRoot.resolve("default-overworld.zip");
        Path metadataPath = cacheRoot.resolve("default-overworld.properties");
        Properties metadata = Files.isRegularFile(metadataPath) ? loadProperties(metadataPath) : new Properties();
        boolean validCache = false;
        if (Files.isRegularFile(archivePath)) {
            try {
                validCache = validateArchive(archivePath);
            } catch (IOException exception) {
                feedback.accept("Cached default overworld archive is invalid; downloading a replacement.");
            }
        }
        long fetchedAt = parseLong(metadata.getProperty("fetchedAt"), 0L);
        boolean fresh = validCache && options.clock().millis() - fetchedAt < options.refreshInterval().toMillis();
        if (fresh) {
            return new Archive(archivePath, sha256(archivePath));
        }

        IOException networkFailure = null;
        for (int attempt = 1; attempt <= options.attempts(); attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(options.source())
                        .timeout(options.requestTimeout())
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "Iris-Bootstrap")
                        .GET();
                if (validCache) {
                    String etag = metadata.getProperty("etag");
                    String lastModified = metadata.getProperty("lastModified");
                    if (etag != null && !etag.isBlank()) {
                        request.header("If-None-Match", etag);
                    }
                    if (lastModified != null && !lastModified.isBlank()) {
                        request.header("If-Modified-Since", lastModified);
                    }
                }
                HttpResponse<InputStream> response = options.client().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 304 && validCache) {
                    close(response.body());
                    metadata.setProperty("fetchedAt", Long.toString(options.clock().millis()));
                    storePropertiesAtomic(metadataPath, metadata);
                    return new Archive(archivePath, sha256(archivePath));
                }
                if (status == 200) {
                    Path temporary = cacheRoot.resolve(".download-" + UUID.randomUUID() + ".zip");
                    try {
                        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(temporary)) {
                            copyLimited(input, output, options.maxArchiveBytes());
                        }
                        if (!validateArchive(temporary)) {
                            throw new IOException("Downloaded default overworld archive is invalid");
                        }
                        move(temporary, archivePath, true);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                    Properties updated = new Properties();
                    response.headers().firstValue("etag").ifPresent(value -> updated.setProperty("etag", value));
                    response.headers().firstValue("last-modified").ifPresent(value -> updated.setProperty("lastModified", value));
                    updated.setProperty("fetchedAt", Long.toString(options.clock().millis()));
                    updated.setProperty("sha256", sha256(archivePath));
                    storePropertiesAtomic(metadataPath, updated);
                    feedback.accept("Downloaded the Iris default overworld beta archive.");
                    return new Archive(archivePath, updated.getProperty("sha256"));
                }
                close(response.body());
                IOException statusFailure = new IOException("Default overworld download returned HTTP " + status);
                if (!retryableStatus(status)) {
                    networkFailure = statusFailure;
                    break;
                }
                networkFailure = statusFailure;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Default overworld download was interrupted", exception);
            } catch (IOException exception) {
                networkFailure = exception;
            }
            if (attempt < options.attempts()) {
                pause(options.retryDelay().multipliedBy(attempt));
            }
        }

        if (validCache) {
            feedback.accept("Default overworld download failed; using the validated cached archive.");
            return new Archive(archivePath, sha256(archivePath));
        }
        if (isManagedPackOutputUsable(marker, cacheRoot.getParent().getParent())) {
            String sourceSha = marker.getProperty("sourceSha256");
            return new Archive(null, sourceSha);
        }
        throw networkFailure == null
                ? new IOException("Default overworld archive is unavailable and no valid cache exists")
                : new IOException("Default overworld archive is unavailable and no valid cache exists", networkFailure);
    }

    private static boolean isManagedPackOutputUsable(Properties marker, Path dataDirectory) {
        String sourceSha = marker.getProperty("sourceSha256");
        String expectedFingerprint = marker.getProperty("defaultPackFingerprint");
        if (sourceSha == null || sourceSha.isBlank() || expectedFingerprint == null || expectedFingerprint.isBlank()) {
            return false;
        }
        Path packRoot = dataDirectory.toAbsolutePath().normalize().resolve("packs/overworld");
        try {
            return isPackRoot(packRoot) && directoryFingerprint(packRoot).equals(expectedFingerprint);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static Path resolveLevelRoot(Path serverRoot) throws IOException {
        Path normalizedServerRoot = serverRoot.toAbsolutePath().normalize();
        String levelName = readConfiguredLevelName(normalizedServerRoot);
        Path configured = Path.of(levelName);
        return configured.isAbsolute()
                ? configured.normalize()
                : normalizedServerRoot.resolve(configured).normalize();
    }

    private static String readConfiguredLevelName(Path serverRoot) throws IOException {
        String levelName = "world";
        Path propertiesFile = serverRoot.resolve("server.properties");
        if (Files.isRegularFile(propertiesFile)) {
            Properties properties = loadProperties(propertiesFile);
            levelName = properties.getProperty("level-name", levelName);
        }

        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            String following = index + 1 < arguments.length ? arguments[index + 1] : null;
            String parsed = parseLevelArgument(argument, following);
            if (parsed != null) {
                levelName = parsed;
            }
        }
        if (levelName.isBlank()) {
            throw new IOException("Configured level name is empty");
        }
        return levelName;
    }

    private static String parseLevelArgument(String argument, String following) {
        for (String key : List.of("-w", "--level-name", "--world")) {
            if (argument.equals(key) && following != null && !following.isBlank()) {
                return following;
            }
            String prefix = key + "=";
            if (argument.startsWith(prefix) && argument.length() > prefix.length()) {
                return argument.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean validateArchive(Path archive) throws IOException {
        int entries = 0;
        long expanded = 0L;
        boolean dimensionFound = false;
        Set<String> paths = new HashSet<>();
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Default overworld archive contains too many entries");
                }
                String name = normalizedZipEntry(entry.getName());
                if (!paths.add(name)) {
                    throw new IOException("Default overworld archive contains duplicate path " + name);
                }
                if (name.equals("dimensions/overworld.json") || name.endsWith("/dimensions/overworld.json")) {
                    dimensionFound = true;
                }
                if (!entry.isDirectory()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        expanded += read;
                        if (expanded > MAX_EXPANDED_BYTES) {
                            throw new IOException("Default overworld archive expands beyond the safety limit");
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        return entries > 0 && dimensionFound;
    }

    private static Path extractArchive(Path archive, Path extractionRoot) throws IOException {
        if (archive == null) {
            throw new IOException("Cached default pack archive is unavailable for required pack rebuild");
        }
        long expanded = 0L;
        int entries = 0;
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Default overworld archive contains too many entries");
                }
                String name = normalizedZipEntry(entry.getName());
                Path output = extractionRoot.resolve(name).normalize();
                if (!output.startsWith(extractionRoot)) {
                    throw new IOException("Unsafe path in default overworld archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (OutputStream file = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            expanded += read;
                            if (expanded > MAX_EXPANDED_BYTES) {
                                throw new IOException("Default overworld archive expands beyond the safety limit");
                            }
                            file.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (isPackRoot(extractionRoot)) {
            return extractionRoot;
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(extractionRoot)) {
            for (Path child : children) {
                if (Files.isDirectory(child) && isPackRoot(child)) {
                    candidates.add(child);
                }
            }
        }
        if (candidates.size() != 1) {
            throw new IOException("Default overworld archive has an invalid root layout");
        }
        return candidates.getFirst();
    }

    private static String normalizedZipEntry(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.startsWith("/") || raw.startsWith("\\")) {
            throw new IOException("Invalid path in default overworld archive");
        }
        String normalized = raw.replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || path.startsWith("..") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("Unsafe path in default overworld archive: " + raw);
        }
        return path.toString().replace('\\', '/');
    }

    private static boolean isPackRoot(Path path) {
        return path != null && Files.isRegularFile(path.resolve("dimensions/overworld.json"));
    }

    private static void validatePackRoot(Path path) throws IOException {
        if (!isPackRoot(path)) {
            throw new IOException("Default overworld pack is missing dimensions/overworld.json at " + path);
        }
    }

    private static boolean isDatapackRoot(Path path) {
        return path != null
                && Files.isRegularFile(path.resolve("pack.mcmeta"))
                && Files.isDirectory(path.resolve("data/iris/dimension_type"));
    }

    static Path worldDatapackRoot(Path levelRoot) {
        return levelRoot.toAbsolutePath().normalize().resolve("datapacks").resolve(WORLD_DATAPACK_DIRECTORY);
    }

    private static String packRootsFingerprint(List<File> packRoots) throws IOException {
        MessageDigest digest = digest();
        List<Path> roots = packRoots.stream()
                .map(File::toPath)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        for (Path root : roots) {
            digest.update(root.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(directoryFingerprint(root).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String directoryFingerprint(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        Path scanRoot = root.toRealPath();
        MessageDigest digest = digest();
        try (Stream<Path> stream = Files.walk(scanRoot)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> scanRoot.relativize(path).toString()))
                    .toList();
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                String relative = scanRoot.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                try (InputStream input = Files.newInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path replaceWithBackup(Path staged, Path target) throws IOException {
        Path backup = target.resolveSibling("." + target.getFileName() + "-backup-" + UUID.randomUUID());
        if (Files.exists(target)) {
            move(target, backup, false);
        } else {
            backup = null;
        }
        try {
            move(staged, target, false);
            return backup;
        } catch (IOException failure) {
            if (backup != null && Files.exists(backup)) {
                move(backup, target, false);
            }
            throw failure;
        }
    }

    private static IOException rollback(
            Path packRoot,
            Path packBackup,
            boolean packReplaced,
            Path datapackRoot,
            Path datapackBackup,
            boolean datapackReplaced
    ) {
        IOException failure = null;
        try {
            restore(packRoot, packBackup, packReplaced);
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            restore(datapackRoot, datapackBackup, datapackReplaced);
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        return failure;
    }

    private static void restore(Path target, Path backup, boolean replaced) throws IOException {
        if (!replaced) {
            return;
        }
        delete(target);
        if (backup != null && Files.exists(backup)) {
            move(backup, target, false);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path entry : stream.toList()) {
                Path relative = source.relativize(entry);
                Path output = target.resolve(relative);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(output);
                } else if (Files.isRegularFile(entry)) {
                    Files.createDirectories(output.getParent());
                    Files.copy(entry, output, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        if (source == null) {
            return;
        }
        Files.createDirectories(target.getParent());
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private static void delete(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> entries = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void deleteQuietly(Path path, Consumer<String> feedback) {
        try {
            delete(path);
        } catch (IOException exception) {
            feedback.accept("Unable to clean temporary Iris bootstrap path " + path + ": " + exception.getMessage());
        }
    }

    private static void copyLimited(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new IOException("Default overworld archive exceeds the download size limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void storePropertiesAtomic(Path path, Properties properties) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, null);
            }
            move(temporary, path, true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static void pause(Duration duration) throws IOException {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Default overworld retry wait was interrupted", exception);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    public enum ProvisionStatus {
        INSTALLED,
        UPDATED,
        UNCHANGED
    }

    public record ProvisionResult(Path packRoot, Path datapackRoot, ProvisionStatus status) {
        public ProvisionResult {
            Objects.requireNonNull(packRoot, "packRoot");
            Objects.requireNonNull(datapackRoot, "datapackRoot");
            Objects.requireNonNull(status, "status");
        }
    }

    record ProvisionOptions(
            URI source,
            HttpClient client,
            Clock clock,
            Duration refreshInterval,
            Duration requestTimeout,
            int attempts,
            Duration retryDelay,
            long maxArchiveBytes,
            Path levelRoot
    ) {
        ProvisionOptions {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(refreshInterval, "refreshInterval");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(retryDelay, "retryDelay");
            Objects.requireNonNull(levelRoot, "levelRoot");
            if (attempts < 1 || maxArchiveBytes < 1L) {
                throw new IllegalArgumentException("Invalid bootstrap provisioning options");
            }
        }
    }

    private record Archive(Path path, String sha256) {
        private Archive {
            Objects.requireNonNull(sha256, "sha256");
        }
    }
}

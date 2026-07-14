/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.datapack;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.datapack.ModrinthResolver.ResolvedDatapack;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.structure.BulkStructureImporter;
import art.arcane.iris.core.structure.StructureImporter;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisImportedStructureControl;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.io.ZipUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class DatapackIngestService {
    private static final String USER_AGENT = "VolmitSoftware/Iris (datapack-ingest)";
    private static final String OVERRIDES_STRIPPED_MARKER = ".iris-overrides-stripped";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DatapackIngestService() {
    }

    public static Report ingestAll(VolmitSender sender, boolean restart) {
        return ingest(sender, collectConfiguredImports(), restart);
    }

    public static void autoIngestOnStartup() {
        boolean restarting = false;
        if (IrisSettings.get().getGeneral().autoIngestDatapacks) {
            KList<String> urls = collectConfiguredImports();
            if (!urls.isEmpty()) {
                IrisLogging.info("Auto-ingesting " + urls.size() + " external datapack import(s) from pack datapackImports...");
                Report report = ingest(null, urls, true);
                restarting = report.changed();
            }
        }
        if (!restarting) {
            refreshWorkspaces();
            autoImportDatapackStructures();
        }
    }

    public static void refreshWorkspaces() {
        try (Stream<IrisData> stream = ServerConfigurator.allPacks()) {
            stream.forEach(DatapackIngestService::refreshWorkspace);
        }
    }

    public static void refreshWorkspace(IrisData data) {
        if (data == null || !hasImports(data)) {
            return;
        }
        try {
            new IrisProject(data.getDataFolder()).updateWorkspace();
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    public static Report ingest(VolmitSender sender, KList<String> urls, boolean restart) {
        Report report = new Report();
        if (urls == null || urls.isEmpty()) {
            message(sender, C.YELLOW + "No datapackImports configured in any loaded pack. Add Modrinth URLs to a dimension's 'datapackImports' list, then run /iris datapack ingest.");
            return report;
        }

        File root = IrisPlatforms.get().dataFolder("datapacks");
        File cacheDir = new File(root, "cache");
        File stagingDir = new File(root, "staging");
        cacheDir.mkdirs();
        stagingDir.mkdirs();

        KList<File> worldFolders = ServerConfigurator.getDatapacksFolder();
        String mcVersion = serverMcVersion();
        Manifest manifest = readManifest(root);
        boolean stripOverrides = resolveStripOverrides();

        message(sender, C.GRAY + "Ingesting " + C.WHITE + urls.size() + C.GRAY + " datapack import(s)" + (mcVersion == null ? "" : " for MC " + mcVersion) + (stripOverrides ? C.GRAY + " (datapackOverrides=false: minecraft-namespaced structure overrides will be stripped)" : "") + "...");

        for (String url : urls) {
            try {
                ingestSingle(sender, url, mcVersion, cacheDir, stagingDir, worldFolders, manifest, report, stripOverrides);
            } catch (Exception e) {
                report.failed.add(url + " - " + e.getMessage());
                message(sender, C.RED + "  Failed: " + C.WHITE + url + C.RED + " - " + e.getMessage());
                IrisLogging.reportError(e);
            }
        }

        writeManifest(root, manifest);
        message(sender, C.GREEN + "Datapack ingest complete: " + C.WHITE + report.updated.size() + C.GREEN + " updated, " + C.WHITE + report.upToDate.size() + C.GREEN + " up to date, " + C.WHITE + report.failed.size() + C.GREEN + " failed.");

        if (report.changed()) {
            message(sender, C.YELLOW + "New datapack structures were installed. A server restart is required for them to register and generate.");
            message(sender, C.GRAY + "After the restart their jigsaw pools, pieces & objects are imported automatically (set general.autoImportDatapackStructures=false to disable), or run /iris structure import <dimension> to import everything on demand. Reference an imported key from a 'structures' placement to position it manually.");
            message(sender, C.GRAY + "Datapacks replace matching vanilla structure keys by default. Set 'importedStructures.datapackOverrides' to false to keep minecraft-namespaced structure definitions untouched; control non-minecraft datapack and mod structures with importedStructures.enabled/disabled.");
            if (restart) {
                ServerConfigurator.restart();
            } else {
                message(sender, C.GRAY + "Run with restart=true to restart now, or restart manually. After restart, run /iris structure list <dimension> to see the new keys.");
            }
        }

        return report;
    }

    public static void reapplyFromStaging(KList<File> worldFolders) {
        File stagingDir = IrisPlatforms.get().dataFolderNoCreate("datapacks", "staging");
        if (stagingDir == null || !stagingDir.isDirectory()) {
            return;
        }
        File[] staged = stagingDir.listFiles(File::isDirectory);
        if (staged == null || staged.length == 0) {
            return;
        }
        boolean stripOverrides = resolveStripOverrides();
        for (File stagedDir : staged) {
            if (!new File(stagedDir, "pack.mcmeta").isFile()) {
                continue;
            }
            try {
                install(stagedDir, worldFolders, stagedDir.getName(), false, stripOverrides);
            } catch (IOException e) {
                IrisLogging.reportError(e);
            }
        }
    }

    public static boolean remove(VolmitSender sender, String id) {
        String cleaned = sanitizeId(id);
        File root = IrisPlatforms.get().dataFolder("datapacks");
        Manifest manifest = readManifest(root);
        boolean removed = false;

        File stagedDir = new File(new File(root, "staging"), cleaned);
        if (stagedDir.isDirectory()) {
            IO.delete(stagedDir);
            removed = true;
        }
        for (File worldFolder : ServerConfigurator.getDatapacksFolder()) {
            File target = new File(worldFolder, cleaned);
            if (target.isDirectory()) {
                IO.delete(target);
                removed = true;
            }
        }
        if (manifest.removeById(cleaned)) {
            removed = true;
        }
        writeManifest(root, manifest);

        if (removed) {
            message(sender, C.GREEN + "Removed datapack '" + C.WHITE + cleaned + C.GREEN + "'. Restart for it to stop generating, and delete its URL from the pack's datapackImports to keep it gone.");
        } else {
            message(sender, C.YELLOW + "No installed datapack named '" + cleaned + "'. Run /iris datapack list to see installed ids.");
        }
        return removed;
    }

    public static KList<String> collectConfiguredImports() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        try (Stream<IrisData> stream = ServerConfigurator.allPacks()) {
            stream.forEach(data -> collectImports(data, urls));
        }
        KList<String> result = new KList<>();
        result.addAll(urls);
        return result;
    }

    public static List<Entry> installed() {
        return readManifest(IrisPlatforms.get().dataFolder("datapacks")).entries;
    }

    private static void ingestSingle(VolmitSender sender, String url, String mcVersion, File cacheDir, File stagingDir, KList<File> worldFolders, Manifest manifest, Report report, boolean stripOverrides) throws IOException {
        ResolvedDatapack resolved = ModrinthResolver.resolve(url, mcVersion);
        String id = deriveId(resolved);
        File stagedDir = new File(stagingDir, id);
        Entry existing = manifest.find(url);
        boolean sameVersion = existing != null
                && Objects.equals(existing.versionId, resolved.getVersionId())
                && (resolved.getSha1() == null || Objects.equals(existing.sha1, resolved.getSha1()))
                && stagedDir.isDirectory()
                && new File(stagedDir, "pack.mcmeta").isFile();

        if (sameVersion) {
            install(stagedDir, worldFolders, id, false, stripOverrides);
            report.upToDate.add(id + " (" + safe(resolved.getVersionNumber()) + ")");
            message(sender, C.GRAY + "  Up to date: " + C.WHITE + id + C.GRAY + " " + safe(resolved.getVersionNumber()));
            return;
        }

        message(sender, C.GRAY + "  Downloading " + C.WHITE + id + C.GRAY + " " + safe(resolved.getVersionNumber()) + "...");
        File zip = new File(cacheDir, id + "-" + safeFile(resolved.getVersionId()) + ".zip");
        download(resolved.getDownloadUrl(), zip);

        String checksum = sha1(zip);
        if (resolved.getSha1() != null && !resolved.getSha1().isBlank() && !resolved.getSha1().equalsIgnoreCase(checksum)) {
            IO.delete(zip);
            throw new IOException("Checksum mismatch for " + id + " (expected " + resolved.getSha1() + ", got " + checksum + ")");
        }

        IO.delete(stagedDir);
        stagedDir.mkdirs();
        ZipUtils.unzipFile(zip, stagedDir);
        flattenIfWrapped(stagedDir);
        if (!new File(stagedDir, "pack.mcmeta").isFile()) {
            IO.delete(stagedDir);
            throw new IOException(id + " is not a valid datapack (missing pack.mcmeta)");
        }

        install(stagedDir, worldFolders, id, true, stripOverrides);

        Entry entry = existing != null ? existing : new Entry();
        entry.url = url;
        entry.id = id;
        entry.versionId = resolved.getVersionId();
        entry.versionNumber = resolved.getVersionNumber();
        entry.sha1 = checksum;
        entry.filename = resolved.getFileName();
        entry.installedEpoch = System.currentTimeMillis();
        entry.structuresImported = false;
        manifest.put(entry);

        report.updated.add(id + " (" + safe(resolved.getVersionNumber()) + ")");
        message(sender, C.GREEN + "  Installed " + C.WHITE + id + C.GREEN + " " + safe(resolved.getVersionNumber()));
    }

    private static void collectImports(IrisData data, LinkedHashSet<String> urls) {
        if (data == null || data.getDimensionLoader() == null) {
            return;
        }
        for (IrisDimension dimension : data.getDimensionLoader().loadAll(data.getDimensionLoader().getPossibleKeys())) {
            if (dimension == null) {
                continue;
            }
            KList<String> imports = dimension.getDatapackImports();
            if (imports == null) {
                continue;
            }
            for (String url : imports) {
                if (url != null && !url.isBlank()) {
                    urls.add(url.trim());
                }
            }
        }
    }

    private static boolean hasImports(IrisData data) {
        if (data.getDimensionLoader() == null) {
            return false;
        }
        for (IrisDimension dimension : data.getDimensionLoader().loadAll(data.getDimensionLoader().getPossibleKeys())) {
            if (dimension == null) {
                continue;
            }
            KList<String> imports = dimension.getDatapackImports();
            if (imports != null && !imports.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void install(File stagedDir, KList<File> worldFolders, String id, boolean force, boolean stripOverrides) throws IOException {
        for (File worldFolder : worldFolders) {
            File target = new File(worldFolder, id);
            File marker = new File(target, OVERRIDES_STRIPPED_MARKER);
            boolean installed = target.isDirectory() && new File(target, "pack.mcmeta").isFile();
            boolean stripStateMatches = marker.isFile() == stripOverrides;
            if (!force && installed && stripStateMatches) {
                continue;
            }
            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }
            IO.delete(target);
            IO.copyDirectory(stagedDir.toPath(), target.toPath());
            if (stripOverrides) {
                stripVanillaStructureOverrides(target);
                writeMarker(marker);
            }
        }
    }

    private static boolean resolveStripOverrides() {
        try (Stream<IrisData> stream = ServerConfigurator.allPacks()) {
            return stream.anyMatch(DatapackIngestService::packDisablesOverrides);
        }
    }

    private static boolean packDisablesOverrides(IrisData data) {
        if (data == null || data.getDimensionLoader() == null) {
            return false;
        }
        for (IrisDimension dimension : data.getDimensionLoader().loadAll(data.getDimensionLoader().getPossibleKeys())) {
            if (dimension == null) {
                continue;
            }
            IrisImportedStructureControl control = dimension.getImportedStructures();
            if (control != null && !control.isDatapackOverrides()) {
                return true;
            }
        }
        return false;
    }

    private static void stripVanillaStructureOverrides(File datapackRoot) {
        File minecraftData = new File(new File(datapackRoot, "data"), "minecraft");
        if (!minecraftData.isDirectory()) {
            return;
        }
        String[] relativeTrees = {
                "worldgen" + File.separator + "structure_set",
                "worldgen" + File.separator + "structure",
                "worldgen" + File.separator + "template_pool",
                "structure"
        };
        for (String tree : relativeTrees) {
            File dir = new File(minecraftData, tree);
            if (dir.exists()) {
                IO.delete(dir);
            }
        }
    }

    private static void writeMarker(File marker) {
        try {
            Files.writeString(marker.toPath(), "stripped", StandardCharsets.UTF_8);
        } catch (IOException e) {
            IrisLogging.reportError(e);
        }
    }

    private static void autoImportDatapackStructures() {
        if (!IrisSettings.get().getGeneral().autoImportDatapackStructures) {
            return;
        }
        File root = IrisPlatforms.get().dataFolder("datapacks");
        Manifest manifest = readManifest(root);
        if (manifest.entries.isEmpty()) {
            return;
        }
        boolean pending = false;
        for (Entry entry : manifest.entries) {
            if (!entry.structuresImported) {
                pending = true;
                break;
            }
        }
        if (!pending) {
            return;
        }

        IrisLogging.info("Importing datapack structures (jigsaw pools, pieces & objects) into packs that declare datapackImports...");
        AtomicInteger packs = new AtomicInteger();
        try (Stream<IrisData> stream = ServerConfigurator.allPacks()) {
            stream.forEach(data -> {
                if (data == null || !hasImports(data)) {
                    return;
                }
                try {
                    BulkStructureImporter.importDatapackStructures(data, StructureImporter.Mode.ADD_ONLY, BukkitPlatform.console());
                    packs.incrementAndGet();
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                }
            });
        }

        for (Entry entry : manifest.entries) {
            entry.structuresImported = true;
        }
        writeManifest(root, manifest);
        if (packs.get() > 0) {
            IrisLogging.info("Datapack structure import finished for " + packs.get() + " pack(s). Reference the imported keys from a 'structures' placement to position them manually.");
        }
    }

    private static void flattenIfWrapped(File dir) throws IOException {
        if (new File(dir, "pack.mcmeta").isFile()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        File singleDir = null;
        int dirCount = 0;
        int fileCount = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                dirCount++;
                singleDir = child;
            } else {
                fileCount++;
            }
        }
        if (dirCount != 1 || fileCount != 0 || singleDir == null || !new File(singleDir, "pack.mcmeta").isFile()) {
            return;
        }
        File[] inner = singleDir.listFiles();
        if (inner != null) {
            for (File item : inner) {
                File moved = new File(dir, item.getName());
                if (item.renameTo(moved)) {
                    continue;
                }
                if (item.isDirectory()) {
                    IO.copyDirectory(item.toPath(), moved.toPath());
                } else {
                    Files.copy(item.toPath(), moved.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        IO.delete(singleDir);
    }

    private static void download(String url, File dest) throws IOException {
        String current = url;
        for (int attempt = 0; attempt < 5; attempt++) {
            URL target = URI.create(current).toURL();
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);

            int code = connection.getResponseCode();
            if (code / 100 == 3) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank()) {
                    throw new IOException("Redirect without a location header from " + current);
                }
                current = location;
                continue;
            }
            if (code != 200) {
                connection.disconnect();
                throw new IOException("HTTP " + code + " downloading " + current);
            }

            File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            File temp = new File(parent, dest.getName() + ".part");
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            } finally {
                connection.disconnect();
            }
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        throw new IOException("Too many redirects downloading " + url);
    }

    private static String sha1(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, length);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 algorithm unavailable", e);
        }
    }

    private static String deriveId(ResolvedDatapack resolved) {
        String base = resolved.getProjectSlug();
        if (base == null || base.isBlank()) {
            base = resolved.getFileName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
        }
        return sanitizeId(base);
    }

    private static String sanitizeId(String value) {
        if (value == null) {
            return "datapack";
        }
        String lower = value.toLowerCase(Locale.ROOT).trim();
        StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                builder.append(c);
            } else if (c == ' ' || c == '/' || c == '\\') {
                builder.append('-');
            }
        }
        String cleaned = builder.toString().replaceAll("-+", "-");
        cleaned = cleaned.replaceAll("^[-_.]+", "").replaceAll("[-_.]+$", "");
        return cleaned.isBlank() ? "datapack" : cleaned;
    }

    private static String serverMcVersion() {
        String bukkit = Bukkit.getBukkitVersion();
        if (bukkit == null || bukkit.isBlank()) {
            return null;
        }
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String safeFile(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void message(VolmitSender sender, String text) {
        if (sender != null) {
            sender.sendMessage(text);
            return;
        }
        IrisLogging.info(text);
    }

    private static Manifest readManifest(File root) {
        File file = new File(root, "manifest.json");
        if (!file.isFile()) {
            return new Manifest();
        }
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Manifest manifest = GSON.fromJson(json, Manifest.class);
            if (manifest == null) {
                return new Manifest();
            }
            if (manifest.entries == null) {
                manifest.entries = new ArrayList<>();
            }
            return manifest;
        } catch (Exception e) {
            IrisLogging.reportError(e);
            return new Manifest();
        }
    }

    private static void writeManifest(File root, Manifest manifest) {
        File file = new File(root, "manifest.json");
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.writeString(file.toPath(), GSON.toJson(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            IrisLogging.reportError(e);
        }
    }

    public static final class Report {
        private final KList<String> updated = new KList<>();
        private final KList<String> upToDate = new KList<>();
        private final KList<String> failed = new KList<>();

        public boolean changed() {
            return !updated.isEmpty();
        }

        public KList<String> getUpdated() {
            return updated;
        }

        public KList<String> getUpToDate() {
            return upToDate;
        }

        public KList<String> getFailed() {
            return failed;
        }
    }

    public static final class Entry {
        public String url;
        public String id;
        public String versionId;
        public String versionNumber;
        public String sha1;
        public String filename;
        public long installedEpoch;
        public boolean structuresImported;
    }

    private static final class Manifest {
        private List<Entry> entries = new ArrayList<>();

        private Entry find(String url) {
            for (Entry entry : entries) {
                if (entry.url != null && entry.url.equals(url)) {
                    return entry;
                }
            }
            return null;
        }

        private void put(Entry entry) {
            for (int i = 0; i < entries.size(); i++) {
                Entry current = entries.get(i);
                if (current.url != null && current.url.equals(entry.url)) {
                    entries.set(i, entry);
                    return;
                }
            }
            entries.add(entry);
        }

        private boolean removeById(String id) {
            return entries.removeIf(entry -> id.equals(entry.id));
        }
    }
}

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

package art.arcane.iris.core.loader;

import com.google.common.util.concurrent.AtomicDouble;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.project.SchemaBuilder;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.MeteredCache;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.data.KCache;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.io.CustomOutputStream;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Data
@EqualsAndHashCode(exclude = "manager")
@ToString(exclude = "manager")
public class ResourceLoader<T extends IrisRegistrant> implements MeteredCache {
    public static final AtomicDouble tlt = new AtomicDouble(0);
    private static final int CACHE_SIZE = 100000;
    private static final ExecutorService schemaBuildExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Iris-Schema-Builder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final Set<String> schemaBuildQueue = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean schemaBuildExecutorRegistered = new AtomicBoolean();
    protected final AtomicCache<KList<File>> folderCache;
    protected volatile KSet<String> firstAccess;
    protected File root;
    protected String folderName;
    protected String resourceTypeName;
    protected KCache<String, T> loadCache;
    protected Class<? extends T> objectClass;
    protected String cname;
    protected String[] possibleKeys = null;
    protected IrisData manager;
    protected AtomicInteger loads;
    protected ChronoLatch sec;
    private final Options options;

    public ResourceLoader(
            File root,
            IrisData manager,
            String folderName,
            String resourceTypeName,
            Class<? extends T> objectClass,
            Options options
    ) {
        this.options = Objects.requireNonNull(options, "options");
        this.manager = manager;
        firstAccess = new KSet<>();
        folderCache = new AtomicCache<>();
        sec = new ChronoLatch(5000);
        loads = new AtomicInteger();
        this.objectClass = objectClass;
        cname = objectClass.getCanonicalName();
        this.resourceTypeName = resourceTypeName;
        this.root = root;
        this.folderName = folderName;
        loadCache = new KCache<>(this::loadRaw, options.cacheSize());
        IrisLogging.debug("Loader<" + C.GREEN + resourceTypeName + C.LIGHT_PURPLE + "> created in " + C.RED + "IDM/" + manager.getId() + C.LIGHT_PURPLE + " on " + C.GRAY + manager.getDataFolder().getPath());
        if (options.registerPreservation()) {
            IrisServices.get(PreservationRegistry.class).registerCache(this);
            PreservationRegistry preservation = IrisServices.getOrNull(PreservationRegistry.class);
            if (preservation != null && schemaBuildExecutorRegistered.compareAndSet(false, true)) {
                preservation.register(schemaBuildExecutor);
            }
        }
    }

    public JSONObject buildSchema() {
        IrisLogging.debug("Building Schema " + objectClass.getSimpleName() + " " + root.getPath());
        JSONObject o = new JSONObject();
        KList<String> fm = new KList<>();

        for (int g = 1; g < 8; g++) {
            fm.add("/" + folderName + Form.repeat("/*", g) + ".json");
        }

        o.put("fileMatch", new JSONArray(fm.toArray()));
        o.put("url", "./.iris/schema/" + getFolderName() + "-schema.json");
        File a = new File(getManager().getDataFolder(), ".iris/schema/" + getFolderName() + "-schema.json");
        String schemaPath = a.getAbsolutePath();
        if (schemaBuildQueue.add(schemaPath)) {
            schemaBuildExecutor.execute(() -> {
                try {
                    IO.writeAll(a, new SchemaBuilder(objectClass, manager).construct().toString(4));
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                } finally {
                    schemaBuildQueue.remove(schemaPath);
                }
            });
        }

        return o;
    }

    public File findFile(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        if (name.equals("null")) {
            IrisLogging.warn("Refusing " + resourceTypeName + " lookup for literal string \"null\" (called by " + callerHint() + ")");
            return null;
        }

        File file = resolveFile(name, ".json");

        if (file != null) {
            return file;
        }

        IrisLogging.warn("Couldn't find " + resourceTypeName + ": " + name + " (called by " + callerHint() + ")");

        return null;
    }

    /**
     * Resolves a resource file by key. An exact <code>name + extension</code> hit always wins;
     * only then is the dotted-prefix scan used (so plains.json beats plains.disabled.json).
     */
    protected File resolveFile(String name, String extension) {
        return resolveFile(name, extension, getFolders(name));
    }

    protected File resolveFile(String name, String extension, KList<File> folders) {
        if (folders == null) {
            return null;
        }

        for (File folder : folders) {
            File exact = new File(folder, name + extension);

            if (exact.isFile()) {
                return exact;
            }

            File[] listed = folder.listFiles();

            if (listed == null) {
                continue;
            }

            KList<File> matches = new KList<>();

            for (File candidate : listed) {
                if (candidate.isFile() && candidate.getName().endsWith(extension) && candidate.getName().split("\\Q.\\E")[0].equals(name)) {
                    matches.add(candidate);
                }
            }

            if (matches.isEmpty()) {
                continue;
            }

            if (matches.size() > 1) {
                matches.sort(Comparator.comparing(File::getName));
                IrisLogging.warn("Ambiguous " + resourceTypeName + " " + name + " in " + folder.getPath() + ": "
                        + matches.stream().map(File::getName).collect(Collectors.joining(", "))
                        + " (using " + matches.get(0).getName() + ")");
            }

            return matches.get(0);
        }

        return null;
    }

    protected static String describeName(String name) {
        if (name == null) return "<java null>";
        if (name.isEmpty()) return "<empty string>";
        if (name.equals("null")) return "\"null\" (literal string)";
        return "\"" + name + "\"";
    }

    protected static String callerHint() {
        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        return walker.walk(frames -> frames
                .filter(f -> {
                    String cn = f.getClassName();
                    return !cn.startsWith("art.arcane.iris.core.loader.")
                            && !cn.startsWith("art.arcane.volmlib.util.data.")
                            && !cn.startsWith("com.github.benmanes.caffeine.");
                })
                .limit(3)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                        + "." + f.getMethodName() + ":" + f.getLineNumber())
                .reduce((a, b) -> a + " <- " + b)
                .orElse("<unknown>"));
    }

    public void logLoad(File path, T t) {
        loads.getAndIncrement();

        if (loads.get() == 1) {
            sec.flip();
        }

        if (sec.flip()) {
            Runnable summary = () -> {
                IrisLogging.debug("Loaded " + C.WHITE + loads.get() + " " + resourceTypeName + (loads.get() == 1 ? "" : "s") + C.GRAY + " (" + Form.f(getLoadCache().getSize()) + " " + resourceTypeName + (loadCache.getSize() == 1 ? "" : "s") + " Loaded)");
                loads.set(0);
            };
            if (options.synchronousReporting()) {
                summary.run();
            } else {
                J.a(summary);
            }
        }

        IrisLogging.debug("Loader<" + C.GREEN + resourceTypeName + C.LIGHT_PURPLE + "> iload " + C.YELLOW + t.getLoadKey() + C.LIGHT_PURPLE + " in " + C.GRAY + t.getLoadFile().getPath() + C.LIGHT_PURPLE + " TLT: " + C.RED + Form.duration(tlt.get(), 2));
    }

    public void failLoad(File path, Throwable e) {
        failLoad(path, null, e);
    }

    public void failLoad(File path, String rawText, Throwable e) {
        Runnable report = () -> JsonSchemaValidator.reportLoadFailure(path, rawText, resourceTypeName, e);
        if (options.synchronousReporting()) {
            report.run();
        } else {
            J.a(report);
        }
    }

    private KList<File> matchAllFiles(File root, Predicate<File> f) {
        KList<File> files = new KList<>();
        HashSet<String> visitedDirectories = new HashSet<>();
        matchFiles(root, files, f, visitedDirectories);
        return files;
    }

    private void matchFiles(File at, KList<File> files, Predicate<File> f, HashSet<String> visitedDirectories) {
        if (at == null || !at.exists()) {
            return;
        }

        if (at.isDirectory()) {
            String canonicalPath = toCanonicalPath(at);
            if (canonicalPath != null && !visitedDirectories.add(canonicalPath)) {
                return;
            }

            File[] listedFiles = at.listFiles();
            if (listedFiles == null) {
                return;
            }

            for (File listedFile : listedFiles) {
                matchFiles(listedFile, files, f, visitedDirectories);
            }
            return;
        }

        if (f.test(at)) {
            files.add(at);
        }
    }

    private String toCanonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return null;
        }
    }

    public String[] getPossibleKeys() {
        if (possibleKeys != null) {
            return possibleKeys;
        }

        KList<File> files = getFolders();

        if (files == null) {
            possibleKeys = new String[0];
            return possibleKeys;
        }

        HashSet<String> m = new HashSet<>();
        for (File i : files) {
            for (File j : matchAllFiles(i, (f) -> f.getName().endsWith(".json"))) {
                m.add(i.toURI().relativize(j.toURI()).getPath().replaceAll("\\Q.json\\E", ""));
            }
        }

        KList<String> v = new KList<>(m);
        possibleKeys = v.toArray(new String[0]);
        return possibleKeys;
    }

    public long count() {
        return loadCache.getSize();
    }

    protected T loadFile(File j, String name) {
        String rawText = null;
        try {
            PrecisionStopwatch p = PrecisionStopwatch.start();
            rawText = IO.readAll(j);
            JSONObject parsed = new JSONObject(rawText);
            JsonSchemaValidator.validateTopLevelKeys(parsed, rawText, j, resourceTypeName, objectClass);
            T t = getManager().getGson()
                    .fromJson(preprocess(parsed).toString(0), objectClass);
            t.setLoadKey(name);
            t.setLoadFile(j);
            t.setLoader(manager);
            getManager().preprocessObject(t);
            logLoad(j, t);
            tlt.addAndGet(p.getMilliseconds());
            return t;
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            failLoad(j, rawText, e);
            return null;
        }
    }

    protected JSONObject preprocess(JSONObject j) {
        return j;
    }

    public Stream<T> streamAll() {
        return streamAll(Arrays.stream(getPossibleKeys()));
    }

    public Stream<T> streamAll(Stream<String> s) {
        return s.map(this::load);
    }

    public KList<T> loadAll(KList<String> s) {
        KList<T> m = new KList<>();

        for (String i : s) {
            T t = load(i);

            if (t != null) {
                m.add(t);
            }
        }

        return m;
    }

    public KList<T> loadAllParallel(KList<String> s) {
        KList<T> m = new KList<>();
        BurstExecutor burst = MultiBurst.ioBurst.burst(s.size());

        for (String i : s) {
            burst.queue(() -> {
                T t = load(i);
                if (t == null)
                    return;

                synchronized (m) {
                    m.add(t);
                }
            });
        }

        burst.complete();
        return m;
    }

    public KList<T> loadAll(KList<String> s, Consumer<T> postLoad) {
        KList<T> m = new KList<>();

        for (String i : s) {
            T t = load(i);

            if (t != null) {
                m.add(t);
                postLoad.accept(t);
            }
        }

        return m;
    }

    public KList<T> loadAll(String[] s) {
        KList<T> m = new KList<>();

        for (String i : s) {
            T t = load(i);

            if (t != null) {
                m.add(t);
            }
        }

        return m;
    }

    public T load(String name) {
        return load(name, true);
    }

    private T loadRaw(String name) {
        File file = resolveFile(name, ".json");
        return file == null ? null : loadFile(file, name);
    }

    public T load(String name, boolean warn) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        if (name.equals("null") && warn) {
            IrisLogging.warn("Refusing " + resourceTypeName + " load for literal string \"null\" (called by " + callerHint() + ")");
            return null;
        }

        KSet<String> set = firstAccess;
        if (set != null) set.add(name);
        return loadCache.get(name);
    }

    private File prefetchFile(Engine engine) {
        String id = "DIM" + Math.abs(engine.getSeedManager().getSeed() + engine.getDimension().getVersion() + engine.getDimension().getLoadKey().hashCode());
        return IrisPlatforms.get().dataFile("prefetch/" + id + "/" + Math.abs(getFolderName().hashCode()) + ".ipfch");
    }

    public void loadFirstAccess(Engine engine) throws IOException {
        File file = prefetchFile(engine);

        if (!file.exists()) {
            return;
        }

        KList<String> s = new KList<>();

        try (FileInputStream fin = new FileInputStream(file);
             GZIPInputStream gzi = new GZIPInputStream(fin);
             DataInputStream din = new DataInputStream(gzi)) {
            int m = din.readInt();

            if (m < 0) {
                throw new IOException("Bad prefetch count " + m);
            }

            for (int i = 0; i < m; i++) {
                s.add(din.readUTF());
            }
        } catch (IOException e) {
            IrisLogging.warn("Discarding corrupt prefetch " + file.getPath() + ": " + e.getMessage());

            if (!file.delete()) {
                IrisLogging.warn("Couldn't delete corrupt prefetch " + file.getPath());
            }

            return;
        }

        IrisLogging.info("Loading " + s.size() + " prefetch " + getFolderName());
        firstAccess = null;
        loadAllParallel(s);
    }

    public void saveFirstAccess(Engine engine) throws IOException {
        KSet<String> set = firstAccess;
        if (set == null) return;
        KList<String> snapshot = new KList<>(set);
        File file = prefetchFile(engine);
        File parent = file.getParentFile();

        if (parent == null) {
            throw new IOException("Prefetch path has no parent: " + file.getPath());
        }

        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Couldn't create prefetch folder " + parent.getPath());
        }

        File temp = File.createTempFile(file.getName(), ".tmp", parent);

        try {
            try (FileOutputStream fos = new FileOutputStream(temp);
                 GZIPOutputStream gzo = new CustomOutputStream(fos, 9);
                 DataOutputStream dos = new DataOutputStream(gzo)) {
                dos.writeInt(snapshot.size());

                for (String i : snapshot) {
                    dos.writeUTF(i);
                }
            }

            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp.toPath());
        }

        firstAccess = null;
    }

    public KList<File> getFolders() {
        return folderCache.aquire(() -> {
            KList<File> fc = new KList<>();

            File[] files = root.listFiles();
            if (files == null) {
                throw new IllegalStateException("Failed to list files in " + root);
            }

            for (File i : files) {
                if (i.isDirectory()) {
                    if (i.getName().equals(folderName)) {
                        fc.add(i);
                        break;
                    }
                }
            }
            return fc;
        });
    }

    public KList<File> getFolders(String rc) {
        KList<File> folders = getFolders().copy();

        if (rc.contains(":")) {
            for (File i : folders.copy()) {
                if (!rc.startsWith(i.getName() + ":")) {
                    folders.remove(i);
                }
            }
        }

        return folders;
    }

    public void clearCache() {
        possibleKeys = null;
        loadCache.invalidate();
        folderCache.reset();
    }

    public File fileFor(T b) {
        return resolveFile(b.getLoadKey(), ".json", getFolders());
    }

    public boolean isLoaded(String next) {
        return loadCache.contains(next);
    }

    public void clearList() {
        folderCache.reset();
        possibleKeys = null;
    }

    public KList<String> getPossibleKeys(String arg) {
        KList<String> f = new KList<>();

        for (String i : getPossibleKeys()) {
            if (i.equalsIgnoreCase(arg) || i.toLowerCase(Locale.ROOT).startsWith(arg.toLowerCase(Locale.ROOT)) || i.toLowerCase(Locale.ROOT).contains(arg.toLowerCase(Locale.ROOT)) || arg.toLowerCase(Locale.ROOT).contains(i.toLowerCase(Locale.ROOT))) {
                f.add(i);
            }
        }

        return f;
    }

    public boolean supportsSchemas() {
        return true;
    }

    public void clean() {

    }

    public long getSize() {
        return loadCache.getSize();
    }

    @Override
    public KCache<?, ?> getRawCache() {
        return loadCache;
    }

    @Override
    public long getMaxSize() {
        return loadCache.getMaxSize();
    }

    @Override
    public boolean isClosed() {
        return getManager().isClosed();
    }

    public long getTotalStorage() {
        return getSize();
    }

    public record Options(int cacheSize, boolean registerPreservation, boolean synchronousReporting) {
        public Options {
            if (cacheSize < 1) {
                throw new IllegalArgumentException("Resource loader cache size must be positive");
            }
        }

        public static Options runtime() {
            return new Options(
                    IrisSettings.get().getPerformance().getResourceLoaderCacheSize(),
                    true,
                    false
            );
        }

        public static Options datapackCompiler() {
            return new Options(CACHE_SIZE, false, true);
        }
    }
}

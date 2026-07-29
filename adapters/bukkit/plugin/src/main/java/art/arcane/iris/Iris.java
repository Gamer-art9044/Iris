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

package art.arcane.iris;

import art.arcane.iris.engine.IrisEngineEffects;
import art.arcane.iris.engine.IrisWorldManager;

import art.arcane.iris.engine.framework.EngineComponentCleanup;
import art.arcane.iris.engine.framework.EngineEffectsProvider;
import art.arcane.iris.engine.framework.EnginePlatformHooks;
import art.arcane.iris.engine.framework.EngineWorldManagerProvider;
import art.arcane.iris.core.splash.IrisSplashComposer;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisWorldStorage;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.iris.core.lifecycle.PaperLibBootstrap;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.runtime.BukkitEnginePlatformHooks;
import art.arcane.iris.core.runtime.TransientWorldCleanupSupport;
import art.arcane.iris.core.runtime.WorldRuntimeControlService;
import art.arcane.iris.core.lifecycle.WorldLifecycleStaging;
import art.arcane.iris.api.terrain.IrisTerrainService;
import art.arcane.iris.core.link.IrisPapiInstaller;
import art.arcane.iris.core.link.IrisPapiListener;
import art.arcane.iris.core.link.IrisPapiState;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.link.MultiverseCoreLink;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.pack.BrokenPackException;
import art.arcane.iris.core.pack.PackValidationRegistry;
import art.arcane.iris.core.pack.PackValidationResult;
import art.arcane.iris.core.pack.PackValidator;
import art.arcane.iris.core.gui.BukkitGuiHost;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.service.EditSVC;
import art.arcane.iris.core.service.PreservationSVC;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.EnginePanic;
import art.arcane.iris.engine.framework.BlockEditAccess;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.framework.TreeBlockMaterial;
import art.arcane.iris.engine.object.IrisCompat;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.core.safeguard.IrisSafeguard;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.platform.bukkit.BukkitEnvironment;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.LogLevel;
import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.function.NastyRunnable;
import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.io.InstanceState;
import art.arcane.volmlib.util.io.JarScanner;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.common.misc.Bindings;
import art.arcane.iris.util.common.misc.SlimJar;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.plugin.VolmitPlugin;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.plugin.chunk.ChunkTickets;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.iris.util.common.misc.ServerProperties;
import art.arcane.iris.util.simd.SimdSupport;
import art.arcane.volmlib.util.scheduling.Queue;
import art.arcane.volmlib.util.scheduling.ShurikenQueue;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("CanBeFinal")
public class Iris extends VolmitPlugin implements Listener, ReloadAware {
    private static final Queue<Runnable> syncJobs = new ShurikenQueue<>();

    static {
        System.setProperty("iris.cache.fast", "true");
    }

    public static Iris instance;
    public static Bindings.Adventure audiences;
    public static MultiverseCoreLink linkMultiverseCore;
    public static IrisCompat compat;
    public static ConfigHotloadEngine configHotloadEngine;
    public static ChunkTickets tickets;
    private static VolmitSender sender;
    private static Thread shutdownHook;
    private static File settingsFile;
    private static final String PENDING_WORLD_DELETE_FILE = "pending-world-deletes.txt";
    private static final StackWalker DEBUG_STACK_WALKER = StackWalker.getInstance();
    static {
        try {
            InstanceState.updateInstanceId();
        } catch (Throwable ex) {
            System.err.println("[Iris] Failed to update instance id: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " - " + ex.getMessage()));
            ex.printStackTrace();
        }
    }

    private final AtomicBoolean alreadyDrained = new AtomicBoolean(false);
    private volatile PlaceholderRegistration papiRegistration;
    private volatile IrisPapiListener papiListener;
    private volatile IrisPapiState papiState;
    private KMap<Class<? extends IrisService>, IrisService> services;

    public static VolmitSender getSender() {
        if (sender == null) {
            sender = new VolmitSender(Bukkit.getConsoleSender());
            sender.setTag(instance.getTag());
        }
        return sender;
    }

    @SuppressWarnings("unchecked")
    public static <T> T service(Class<T> c) {
        return (T) instance.services.get(c);
    }

    public static void callEvent(Event e) {
        Runnable dispatcher = () -> {
            try {
                Bukkit.getPluginManager().callEvent(e);
            } catch (Throwable ex) {
                reportError("Event dispatch failed for \"" + e.getEventName() + "\".", ex);
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (ex instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(ex);
            }
        };
        if (!e.isAsynchronous()) {
            J.s(dispatcher);
        } else {
            dispatcher.run();
        }
    }

    private static <T> KList<T> initialize(String s, Class<T> requiredType) {
        JarScanner js = new JarScanner(instance.getJarFile(), s);
        KList<T> v = new KList<>();
        J.attempt(js::scan);
        for (Class<?> i : js.getClasses()) {
            if (!isConcreteImplementation(i, requiredType)) {
                continue;
            }
            try {
                v.add(requiredType.cast(i.getDeclaredConstructor().newInstance()));
            } catch (Throwable ex) {
                Iris.warn("Skipped class initialization for %s: %s%s",
                        i.getName(),
                        ex.getClass().getSimpleName(),
                        ex.getMessage() == null ? "" : " - " + ex.getMessage());
                Iris.reportError(ex);
            }
        }

        return v;
    }

    static boolean isConcreteImplementation(Class<?> candidate, Class<?> requiredType) {
        int modifiers = candidate.getModifiers();
        return requiredType.isAssignableFrom(candidate)
                && !candidate.isInterface()
                && !Modifier.isAbstract(modifiers);
    }

    public static KList<Class<?>> getClasses(String s, Class<? extends Annotation> slicedClass) {
        JarScanner js = new JarScanner(instance.getJarFile(), s);
        KList<Class<?>> v = new KList<>();
        J.attempt(js::scan);
        for (Class<?> i : js.getClasses()) {
            if (slicedClass == null || i.isAnnotationPresent(slicedClass)) {
                try {
                    v.add(i);
                } catch (Throwable ex) {
                    Iris.warn("Skipped class discovery entry for %s: %s%s",
                            i.getName(),
                            ex.getClass().getSimpleName(),
                            ex.getMessage() == null ? "" : " - " + ex.getMessage());
                    Iris.reportError(ex);
                }
            }
        }

        return v;
    }

    public static void sq(Runnable r) {
        synchronized (syncJobs) {
            syncJobs.queue(r);
        }
    }

    public static File getTemp() {
        return instance.getDataFolder("cache", "temp");
    }

    public static void msg(String string) {
        try {
            getSender().sendMessage(string);
        } catch (Throwable e) {
            try {
                instance.getLogger().info(instance.getTag() + IrisLogging.clean(string));
            } catch (Throwable inner) {
                System.err.println("[Iris] Failed to emit log message: " + inner.getMessage());
                inner.printStackTrace(System.err);
            }
        }
    }

    public static File getCached(String name, String url) {
        String h = IO.hash(name + "@" + url);
        File f = Iris.instance.getDataFile("cache", h.substring(0, 2), h.substring(3, 5), h);

        if (!f.exists()) {
            try (BufferedInputStream in = new BufferedInputStream(URI.create(url).toURL().openStream()); FileOutputStream fileOutputStream = new FileOutputStream(f)) {
                byte[] dataBuffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                    fileOutputStream.write(dataBuffer, 0, bytesRead);
                    Iris.verbose("Aquiring " + name);
                }
            } catch (IOException e) {
                Iris.reportError(e);
            }
        }

        return f.exists() ? f : null;
    }

    public static String getNonCached(String name, String url) {
        String h = IO.hash(name + "*" + url);
        File f = Iris.instance.getDataFile("cache", h.substring(0, 2), h.substring(3, 5), h);

        try (BufferedInputStream in = new BufferedInputStream(URI.create(url).toURL().openStream()); FileOutputStream fileOutputStream = new FileOutputStream(f)) {
            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }
        } catch (IOException e) {
            Iris.reportError(e);
        }

        try {
            return IO.readAll(f);
        } catch (IOException e) {
            Iris.reportError(e);
        }

        return "";
    }

    public static File getNonCachedFile(String name, String url) {
        String h = IO.hash(name + "*" + url);
        File f = Iris.instance.getDataFile("cache", h.substring(0, 2), h.substring(3, 5), h);
        Iris.verbose("Download " + name + " -> " + url);
        try (BufferedInputStream in = new BufferedInputStream(URI.create(url).toURL().openStream()); FileOutputStream fileOutputStream = new FileOutputStream(f)) {
            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }

            fileOutputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            Iris.reportError(e);
        }

        return f;
    }

    public static void warn(String format, Object... objs) {
        msg(C.YELLOW + safeFormat(format, objs));
    }

    public static void error(String format, Object... objs) {
        msg(C.RED + safeFormat(format, objs));
    }

    public static void debug(String string) {
        if (!IrisSettings.get().getGeneral().isDebug()) {
            return;
        }

        StackWalker.StackFrame frame = null;
        try {
            frame = DEBUG_STACK_WALKER.walk(stream -> stream.skip(1).findFirst().orElse(null));
        } catch (Throwable ignored) {
        }

        if (frame == null) {
            debug("Origin", -1, string);
            return;
        }

        String className = frame.getClassName();
        String[] cc = className == null ? new String[0] : className.split("\\Q.\\E");
        int line = frame.getLineNumber();

        if (cc.length > 5) {
            debug(cc[3] + "/" + cc[4] + "/" + cc[cc.length - 1], line, string);
            return;
        }

        if (cc.length > 4) {
            debug(cc[3] + "/" + cc[4], line, string);
            return;
        }

        if (cc.length > 0) {
            debug(cc[cc.length - 1], line, string);
            return;
        }

        debug("Origin", line, string);
    }

    public static void debug(String category, int line, String string) {
        if (!IrisSettings.get().getGeneral().isDebug()) {
            return;
        }
        if (IrisSettings.get().getGeneral().isUseConsoleCustomColors()) {
            msg("<gradient:#095fe0:#a848db>" + category + " <#bf3b76>" + line + "<reset> " + C.LIGHT_PURPLE + string.replaceAll("\\Q<\\E", "[").replaceAll("\\Q>\\E", "]"));
        } else {
            msg(C.BLUE + category + ":" + C.AQUA + line + C.RESET + C.LIGHT_PURPLE + " " + string.replaceAll("\\Q<\\E", "[").replaceAll("\\Q>\\E", "]"));

        }
    }

    public static void verbose(String string) {
        debug(string);
    }

    public static void success(String string) {
        msg(C.IRIS + string);
    }

    public static void info(String format, Object... args) {
        msg(C.WHITE + safeFormat(format, args));
    }

    private static String safeFormat(String format, Object... args) {
        return IrisLogging.format(format, args);
    }

    public static void later(NastyRunnable object) {
        try {
            J.a(() -> {
                try {
                    object.run();
                } catch (Throwable e) {
                    e.printStackTrace();
                    Iris.reportError(e);
                }
            }, RNG.r.i(100, 1200));
        } catch (IllegalPluginAccessException ex) {
            Iris.verbose("Skipping deferred task registration because plugin access is unavailable: "
                    + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " - " + ex.getMessage()));
        }
    }

    public static int jobCount() {
        return syncJobs.size();
    }

    public static void clearQueues() {
        synchronized (syncJobs) {
            syncJobs.clear();
        }
    }

    public static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }

    public static String getJava() {
        String javaRuntimeName = System.getProperty("java.vm.name");
        String javaRuntimeVendor = System.getProperty("java.vendor");
        String javaRuntimeVersion = System.getProperty("java.vm.version");
        return String.format("%s %s (build %s)", javaRuntimeName, javaRuntimeVendor, javaRuntimeVersion);
    }

    public static void reportErrorChunk(int x, int z, Throwable e, String extra) {
        if (IrisSettings.get().getGeneral().isDebug()) {
            File f = instance.getDataFile("debug", "chunk-errors", "chunk." + x + "." + z + ".txt");

            if (!f.exists()) {
                J.attempt(() -> {
                    PrintWriter pw = new PrintWriter(f);
                    pw.println("Thread: " + Thread.currentThread().getName());
                    pw.println("First: " + new Date(M.ms()));
                    e.printStackTrace(pw);
                    pw.close();
                });
            }

            Iris.debug("Chunk " + x + "," + z + " Exception Logged: " + e.getClass().getSimpleName() + ": " + C.RESET + "" + C.LIGHT_PURPLE + e.getMessage());
        }
    }

    public static void reportError(Throwable e) {
        if (e == null) {
            return;
        }

        Bindings.capture(e);
        boolean debug = false;
        if (instance != null) {
            try {
                IrisSettings currentSettings = IrisSettings.settings != null ? IrisSettings.settings : IrisSettings.get();
                debug = currentSettings != null && currentSettings.getGeneral().isDebug();
            } catch (Throwable ignored) {
                debug = false;
            }
        }

        if (debug) {
            String n = e.getClass().getCanonicalName() + "-" + e.getStackTrace()[0].getClassName() + "-" + e.getStackTrace()[0].getLineNumber();

            if (e.getCause() != null) {
                n += "-" + e.getCause().getStackTrace()[0].getClassName() + "-" + e.getCause().getStackTrace()[0].getLineNumber();
            }

            File f = instance.getDataFile("debug", "caught-exceptions", n + ".txt");

            if (!f.exists()) {
                J.attempt(() -> {
                    PrintWriter pw = new PrintWriter(f);
                    pw.println("Thread: " + Thread.currentThread().getName());
                    pw.println("First: " + new Date(M.ms()));
                    e.printStackTrace(pw);
                    pw.close();
                });
            }

            Iris.debug("Exception Logged: " + e.getClass().getSimpleName() + ": " + C.RESET + "" + C.LIGHT_PURPLE + e.getMessage());
        }
    }

    public static void reportError(String context, Throwable e) {
        Throwable error = e == null ? new IllegalStateException("Unknown Iris failure") : e;
        String message = context == null || context.isBlank() ? "Unhandled Iris failure." : context;

        try {
            if (instance != null) {
                Iris.error(message);
            } else {
                System.err.println("[Iris] " + message);
            }
        } catch (Throwable inner) {
            System.err.println("[Iris] " + message);
            inner.printStackTrace(System.err);
        }

        reportError(error);
        error.printStackTrace(System.err);
    }

    public static void dump() {
        try {
            File fi = Iris.instance.getDataFile("dump", "td-" + new java.sql.Date(M.ms()) + ".txt");
            FileOutputStream fos = new FileOutputStream(fi);
            Map<Thread, StackTraceElement[]> f = Thread.getAllStackTraces();
            PrintWriter pw = new PrintWriter(fos);
            for (Thread i : f.keySet()) {
                pw.println("========================================");
                pw.println("Thread: '" + i.getName() + "' ID: " + i.threadId() + " STATUS: " + i.getState().name());

                for (StackTraceElement j : f.get(i)) {
                    pw.println("    @ " + j.toString());
                }

                pw.println("========================================");
                pw.println();
                pw.println();
            }
            pw.println("[%%__USER__%%,%%__RESOURCE__%%,%%__PRODUCT__%%,%%__BUILTBYBIT__%%]");

            pw.close();
            Iris.info("DUMPED! See " + fi.getAbsolutePath());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void panic() {
        EnginePanic.panic();
    }

    public static void addPanic(String s, String v) {
        EnginePanic.add(s, v);
    }

    public Iris() {
        instance = this;
        BukkitPlatform.hostPlugin(this);
        BukkitPlatform.hostConsoleSender(Iris::getSender);
        BukkitPlatform.hostBridge(new BukkitPlatform.HostBridge(
                Iris::bridgeLog,
                Iris::msg,
                Iris::reportError,
                (event) -> Iris.callEvent((org.bukkit.event.Event) event),
                () -> Iris.instance.getDataFolder(),
                (path) -> Iris.instance.getDataFile(path),
                () -> Iris.instance.getJarFile(),
                () -> Iris.instance.getIrisVersion(),
                () -> Iris.instance.getMCVersion()));
        SlimJar.load();
    }

    private static void bridgeLog(LogLevel level, String message) {
        LogLevel target = level == null ? LogLevel.INFO : level;
        switch (target) {
            case DEBUG -> Iris.debug(message);
            case INFO -> Iris.info(message);
            case WARN -> Iris.warn(message);
            case ERROR -> Iris.error(message);
        }
    }

    private void enable() {
        alreadyDrained.set(false);
        IrisLanguage.initialize();
        PaperLibBootstrap.install();
        SimdSupport.install();
        services = new KMap<>();
        setupAudience();
        Bindings.setupSentry();
        initialize("art.arcane.iris.core.service", IrisService.class).forEach((i) -> {
            Class<? extends IrisService> serviceType = i.getClass().asSubclass(IrisService.class);
            services.put(serviceType, i);
            IrisServices.register(serviceType, i);
        });
        IrisServices.register(BlockEditAccess.class, services.get(EditSVC.class));
        IrisServices.register(PreservationRegistry.class, services.get(PreservationSVC.class));
        IO.delete(new File("iris"));
        compat = IrisCompat.configured(getDataFile("compat.json"));
        IrisServices.register(IrisCompat.class, compat);
        ServerConfigurator.configure();
        IrisToolbelt.applyPregenPerformanceProfile();
        validateAllPacks();
        IrisSafeguard.execute();
        getSender().setTag(getTag());
        splash();
        IrisSafeguard.printReports();
        IrisSafeguard.printFooter();
        tickets = new ChunkTickets();
        linkMultiverseCore = new MultiverseCoreLink();
        IrisServices.register(MultiverseCoreLink.class, linkMultiverseCore);
        IrisServices.register(EngineComponentCleanup.class, (EngineComponentCleanup) BukkitPlatform::unregisterListener);
        IrisServices.register(EngineEffectsProvider.class, (EngineEffectsProvider) IrisEngineEffects::new);
        IrisServices.register(EnginePlatformHooks.class, new BukkitEnginePlatformHooks());
        IrisServices.register(EngineWorldManagerProvider.class,
                (EngineWorldManagerProvider) IrisWorldManager::new);
        IrisServices.register(art.arcane.iris.core.runtime.WorldDeletionQueue.class, (art.arcane.iris.core.runtime.WorldDeletionQueue) Iris::queueWorldDeletionOnStartup);
        settingsFile = getDataFile("settings.json");
        configHotloadEngine = new ConfigHotloadEngine(
                Iris::isSettingsFile,
                Iris::knownSettingsFiles,
                Iris::readSettingsContent,
                Iris::normalizeSettingsContent
        );
        configHotloadEngine.configure(3_000L, List.of(settingsFile), List.of());
        services.values().forEach(IrisService::onEnable);
        services.values().forEach(this::registerListener);
        addShutdownHook();
        processPendingStartupWorldDeletes();
        WorldLifecycleService.get();
        WorldRuntimeControlService.get();

        if (J.isFolia()) {
            J.s(() -> checkForBukkitWorlds(s -> true), 1);
        }

        J.s(() -> {
            J.a(() -> IO.delete(getTemp()));
            J.a(this::bstats);
            J.ar(this::checkConfigHotload, 60);
            J.sr(this::tickQueue, 0);
            J.s(this::setupPapi);
            J.a(DatapackIngestService::autoIngestOnStartup, 60);

            autoStartStudio();
            if (!J.isFolia()) {
                checkForBukkitWorlds(s -> true);
            }
            IrisToolbelt.retainMantleDataForSlice(String.class.getCanonicalName());
            IrisToolbelt.retainMantleDataForSlice(BlockData.class.getCanonicalName());
            IrisToolbelt.retainMantleDataForSlice(TreeBlockMaterial.class.getCanonicalName());
        });
    }

    public void addShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ex) {
                Iris.debug("Skipping shutdown hook replacement because JVM shutdown is already in progress.");
                return;
            }
        }
        shutdownHook = new Thread(() -> {
            if (alreadyDrained.compareAndSet(false, true)) {
                try {
                    Bukkit.getWorlds()
                            .stream()
                            .map(World::getGenerator)
                            .filter(PlatformChunkGenerator.class::isInstance)
                            .map(PlatformChunkGenerator.class::cast)
                            .forEach(PlatformChunkGenerator::close);
                } catch (Throwable e) {
                    Iris.reportError("Failed to close Iris world generators from the JVM shutdown hook.", e);
                }
            }

            MultiBurst.burst.close();
            MultiBurst.ioBurst.close();
            IrisServices.clear();
        }, "Iris-ShutdownHook");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ex) {
            Iris.debug("Skipping shutdown hook registration because JVM shutdown is already in progress.");
        }
    }

    public void checkForBukkitWorlds(Predicate<String> filter) {
        try {
            KList<String> deferredStartupWorlds = new KList<>();
            IrisWorlds.readBukkitWorlds().forEach((s, generator) -> {
                try {
                    NamespacedKey worldKey = IrisWorldStorage.keyFromName(s);
                    if (WorldIdentity.resolve(worldKey).isPresent() || !filter.test(s)) return;

                    Iris.info("Loading World: %s | Generator: %s", s, generator);
                    ChunkGenerator gen = getDefaultWorldGenerator(s, generator);
                    IrisDimension dim = loadDimension(s, generator);
                    assert dim != null && gen != null;

                    Iris.info(C.LIGHT_PURPLE + "Preparing Spawn for " + s + "' using Iris:" + generator + "...");
                    WorldCreator c = WorldCreator.ofKey(worldKey)
                            .generator(gen)
                            .environment(BukkitEnvironment.from(dim.getEnvironment()));
                    Long stagedSeed = IrisWorlds.readBukkitWorldSeed(s);
                    if (stagedSeed != null) {
                        c.seed(stagedSeed);
                    }
                    INMS.get().createWorld(c);
                    Iris.info(C.LIGHT_PURPLE + "Loaded " + s + "!");
                } catch (Throwable e) {
                    if (containsCreateWorldUnsupportedOperation(e)) {
                        if (J.isFolia()) {
                            if (!deferredStartupWorlds.contains(s)) {
                                deferredStartupWorlds.add(s);
                            }
                            return;
                        }
                        Iris.error("Failed to load world " + s + "!");
                        Iris.error("This server denied Bukkit.createWorld for \"" + s + "\" at the current startup phase.");
                        Iris.error("Ensure Iris is loaded at STARTUP and restart after staging worlds in bukkit.yml.");
                        reportError("Failed to load staged startup world \"" + s + "\".", e);
                        return;
                    }
                    reportError("Failed to load startup world \"" + s + "\".", e);
                }
            });
            if (!deferredStartupWorlds.isEmpty()) {
                Iris.warn("Staged Iris worlds could not load on Folia: %s", String.join(", ", deferredStartupWorlds));
                Iris.warn("Bukkit.createWorld is unsupported on this server and the Iris runtime world backend is unavailable (%s).", WorldLifecycleService.get().capabilities().paperLikeResolution());
            }
        } catch (Throwable e) {
            reportError("Failed while loading startup Iris worlds.", e);
        }
    }

    private static boolean containsCreateWorldUnsupportedOperation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof UnsupportedOperationException || cursor instanceof IllegalStateException) {
                for (StackTraceElement element : cursor.getStackTrace()) {
                    if ("org.bukkit.craftbukkit.CraftServer".equals(element.getClassName())
                            && "createWorld".equals(element.getMethodName())) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    public static synchronized int queueWorldDeletionOnStartup(Collection<String> worldNames) throws IOException {
        if (instance == null || worldNames == null || worldNames.isEmpty()) {
            return 0;
        }

        LinkedHashMap<String, String> queue = loadPendingWorldDeleteMap();
        int before = queue.size();

        for (String worldName : worldNames) {
            String normalized = normalizeWorldName(worldName);
            if (normalized == null) {
                continue;
            }
            queue.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }

        if (queue.size() != before) {
            writePendingWorldDeleteMap(queue);
        }

        return queue.size() - before;
    }

    private void processPendingStartupWorldDeletes() {
        try {
            try {
                int unregistered = art.arcane.iris.core.tools.IrisCreator.removeTransientStudioWorldsFromBukkitYml();
                if (unregistered > 0) {
                    Iris.info("Unregistered " + unregistered + " transient studio world(s) from bukkit.yml on startup.");
                }
            } catch (Throwable e) {
                Iris.reportError("Failed to unregister transient studio worlds from bukkit.yml on startup.", e);
            }

            LinkedHashMap<String, String> queue = loadPendingWorldDeleteMap();
            for (String transientStudioWorld : TransientWorldCleanupSupport.collectTransientStudioWorldNames(IrisWorldStorage.levelRoot())) {
                queue.putIfAbsent(transientStudioWorld.toLowerCase(Locale.ROOT), transientStudioWorld);
            }
            if (queue.isEmpty()) {
                return;
            }

            LinkedHashMap<String, String> remaining = new LinkedHashMap<>();
            for (String worldName : queue.values()) {
                if (worldName.equalsIgnoreCase(ServerProperties.LEVEL_NAME)) {
                    Iris.warn("Skipping queued deletion for \"" + worldName + "\" because it is configured as level-name.");
                    continue;
                }

                NamespacedKey worldKey = IrisWorldStorage.keyFromName(worldName);
                World loaded = WorldIdentity.resolve(worldKey).orElse(null);
                if (loaded != null) {
                    if (TransientWorldCleanupSupport.isTransientStudioWorldName(worldName)) {
                        try {
                            PlatformChunkGenerator generator = IrisToolbelt.access(loaded);
                            if (generator != null) {
                                generator.close();
                            }
                            IrisToolbelt.evacuate(loaded);
                            Bukkit.unloadWorld(loaded, false);
                            Iris.info("Unloaded leftover studio world \"" + worldName + "\" for deletion.");
                        } catch (Throwable e) {
                            Iris.reportError("Failed to unload leftover studio world \"" + worldName + "\".", e);
                        }

                        if (WorldIdentity.resolve(worldKey).isPresent()) {
                            Iris.warn("Studio world \"" + worldName + "\" is still loaded after unload; will retry next startup.");
                            remaining.put(worldName.toLowerCase(Locale.ROOT), worldName);
                            continue;
                        }
                    } else {
                        Iris.warn("Skipping queued deletion for \"" + worldName + "\" because it is currently loaded.");
                        remaining.put(worldName.toLowerCase(Locale.ROOT), worldName);
                        continue;
                    }
                }

                boolean foundAny = false;
                boolean deletedAll = true;
                for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
                    File worldFolder = IrisWorldStorage.dimensionRoot(familyWorldName);
                    if (!worldFolder.exists()) {
                        continue;
                    }

                    foundAny = true;
                    IO.delete(worldFolder);
                    if (worldFolder.exists()) {
                        deletedAll = false;
                        Iris.warn("Failed to delete queued world folder \"" + familyWorldName + "\". Retrying on next startup.");
                    } else {
                        Iris.info("Deleted queued world folder \"" + familyWorldName + "\".");
                    }
                }

                if (!foundAny) {
                    Iris.info("Queued world deletion skipped for \"" + worldName + "\" (folder missing).");
                    continue;
                }

                if (!deletedAll) {
                    remaining.put(worldName.toLowerCase(Locale.ROOT), worldName);
                    continue;
                }
            }

            writePendingWorldDeleteMap(remaining);
        } catch (Throwable e) {
            Iris.error("Failed to process queued startup world deletions.");
            reportError(e);
            e.printStackTrace();
        }
    }

    private static LinkedHashMap<String, String> loadPendingWorldDeleteMap() throws IOException {
        LinkedHashMap<String, String> queue = new LinkedHashMap<>();
        if (instance == null) {
            return queue;
        }

        File queueFile = instance.getDataFile(PENDING_WORLD_DELETE_FILE);
        if (!queueFile.exists()) {
            return queue;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(queueFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeWorldName(line);
                if (normalized == null) {
                    continue;
                }
                queue.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
            }
        }

        return queue;
    }

    private static void writePendingWorldDeleteMap(Map<String, String> queue) throws IOException {
        if (instance == null) {
            return;
        }

        File queueFile = instance.getDataFile(PENDING_WORLD_DELETE_FILE);
        if (queue.isEmpty()) {
            if (queueFile.exists()) {
                IO.delete(queueFile);
            }
            return;
        }

        File parent = queueFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create queue directory: " + parent.getAbsolutePath());
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(queueFile))) {
            for (String worldName : queue.values()) {
                writer.println(worldName);
            }
        }
    }

    @Nullable
    private static String normalizeWorldName(String worldName) {
        if (worldName == null) {
            return null;
        }

        String trimmed = worldName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed;
    }

    private void autoStartStudio() {
        if (IrisSettings.get().getStudio().isAutoStartDefaultStudio()) {
            Iris.info("Starting up auto Studio!");
            try {
                Player r = new KList<>(getServer().getOnlinePlayers()).getRandom();
                Iris.service(StudioSVC.class).open(r != null ? new VolmitSender(r) : getSender(), 1337, IrisSettings.get().getGenerator().getDefaultWorldType(), (w) -> {
                    J.s(() -> {
                        final Location spawn = w.getSpawnLocation();
                        for (Player i : getServer().getOnlinePlayers()) {
                            final Runnable playerTask = () -> {
                                i.setGameMode(GameMode.CREATIVE);
                                BukkitPlatform.teleportAsync(i, spawn);
                            };
                            if (!J.runEntity(i, playerTask)) {
                                playerTask.run();
                            }
                        }
                    });
                });
            } catch (IrisException e) {
                reportError(e);
            }
        }
    }

    private void setupAudience() {
        try {
            audiences = new Bindings.Adventure(this);
            BukkitPlatform.hostAudiences(audiences);
        } catch (Throwable e) {
            e.printStackTrace();
            IrisSettings.get().getGeneral().setUseConsoleCustomColors(false);
            IrisSettings.get().getGeneral().setUseCustomColorsIngame(false);
            Iris.error("Failed to setup Adventure API... No custom colors :(");
        }
    }

    public void onEnable() {
        IrisPlatforms.bind(new BukkitPlatform());
        enable();
        BukkitGuiHost.install();
        super.onEnable();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    public void onDisable() {
        teardownPapi();
        if (IrisSafeguard.isForceShutdown()) return;
        if (alreadyDrained.compareAndSet(false, true)) {
            drainWorldGenerators("onDisable", 30L);
        }
        if (services != null) {
            services.values().forEach(IrisService::onDisable);
        }
        IrisServices.clear();
        if (configHotloadEngine != null) {
            configHotloadEngine.clear();
            configHotloadEngine = null;
        }
        J.cancelPluginTasks();
        HandlerList.unregisterAll((Plugin) this);
        runPostShutdown();
        super.onDisable();

        J.attempt(new JarScanner(instance.getJarFile(), "", false)::scanAll);
        IrisPlatforms.unbind();
    }

    @Override
    public void onPreUnload(ReloadAware.PreUnloadReason reason) {
        teardownPapi();
        if (!alreadyDrained.compareAndSet(false, true)) {
            Iris.info("Pre-unload hook skipped; Iris already drained.");
            return;
        }
        Iris.info("BileTools pre-unload hook fired (" + reason + "). Freezing all Iris worlds.");
        drainWorldGenerators("pre-unload:" + reason, 45L);
    }

    private void drainWorldGenerators(String reason, long timeoutSeconds) {
        List<World> irisWorlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (IrisToolbelt.access(world) != null) {
                irisWorlds.add(world);
            }
        }
        if (irisWorlds.isEmpty()) {
            Iris.info("No Iris worlds to freeze.");
            return;
        }

        for (World world : irisWorlds) {
            IrisToolbelt.beginWorldMaintenance(world, reason, true);
        }

        J.attempt(PregeneratorJob::shutdownInstance);

        List<CompletableFuture<Void>> closes = new ArrayList<>();
        for (World world : irisWorlds) {
            PlatformChunkGenerator gen = IrisToolbelt.access(world);
            if (gen == null) continue;

            Engine engine = gen.getEngine();
            if (engine != null) {
                J.attempt(() -> engine.getMantle().saveAllNow());
            }

            try {
                closes.add(gen.closeAsync());
            } catch (Throwable t) {
                Iris.reportError(t);
            }
        }

        if (closes.isEmpty()) return;

        try {
            CompletableFuture.allOf(closes.toArray(new CompletableFuture<?>[0]))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            Iris.info("All Iris chunk generators parked. Safe to unload.");
        } catch (TimeoutException e) {
            Iris.warn("Iris generator drain timed out after " + timeoutSeconds + "s; unload proceeding anyway.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Iris.warn("Iris generator drain interrupted; unload proceeding.");
        } catch (ExecutionException e) {
            Iris.reportError(e.getCause() == null ? e : e.getCause());
        }
    }

    private void setupPapi() {
        if (!PlaceholderRegistration.isPlaceholderApiEnabled()) {
            return;
        }

        IrisPapiState state = new IrisPapiState(() -> IrisServices.getOrNull(IrisTerrainService.class));
        PlaceholderRegistration registration = new PlaceholderRegistration(getLogger());

        if (!IrisPapiInstaller.install(registration, state, getLogger())) {
            return;
        }

        IrisPapiListener listener = new IrisPapiListener(state);

        try {
            Bukkit.getPluginManager().registerEvents(listener, this);
        } catch (Throwable failure) {
            registration.unregister();
            Iris.warn("Failed to attach the Iris PlaceholderAPI listener: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
            return;
        }

        papiState = state;
        papiListener = listener;
        papiRegistration = registration;
    }

    private void teardownPapi() {
        IrisPapiListener listener = papiListener;
        papiListener = null;

        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }

        PlaceholderRegistration registration = papiRegistration;
        papiRegistration = null;

        if (registration != null) {
            registration.unregister();
        }

        IrisPapiState state = papiState;
        papiState = null;

        if (state != null) {
            state.clear();
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public String getTag(String subTag) {
        return IrisSafeguard.mode().tag(subTag);
    }

    private void checkConfigHotload() {
        if (configHotloadEngine == null) {
            return;
        }

        for (File file : configHotloadEngine.pollTouchedFiles()) {
            configHotloadEngine.processFileChange(file, ignored -> {
                IrisSettings.invalidate();
                IrisSettings.get();
                IrisLanguage.reload();
                return true;
            }, ignored -> Iris.info("Hotloaded settings.json "));
        }
        IrisLanguage.update();
    }

    private static boolean isSettingsFile(File file) {
        if (file == null || settingsFile == null) {
            return false;
        }
        return settingsFile.getAbsoluteFile().equals(file.getAbsoluteFile());
    }

    private static List<File> knownSettingsFiles() {
        if (settingsFile == null) {
            return List.of();
        }
        return List.of(settingsFile);
    }

    private static String readSettingsContent(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        try {
            return IO.readAll(file);
        } catch (Throwable ex) {
            Iris.warn("Failed to read settings file %s: %s%s",
                    file.getAbsolutePath(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage() == null ? "" : " - " + ex.getMessage());
            Iris.reportError(ex);
            return null;
        }
    }

    private static String normalizeSettingsContent(String text) {
        if (text == null) {
            return null;
        }

        return text.replace("\r\n", "\n").trim();
    }

    private void tickQueue() {
        synchronized (Iris.syncJobs) {
            if (!Iris.syncJobs.hasNext()) {
                return;
            }

            long ms = M.ms();

            while (Iris.syncJobs.hasNext() && M.ms() - ms < 25) {
                try {
                    Iris.syncJobs.next().run();
                } catch (Throwable e) {
                    e.printStackTrace();
                    Iris.reportError(e);
                }
            }
        }
    }

    private void bstats() {
        if (IrisSettings.get().getGeneral().isPluginMetrics()) {
            Bindings.setupBstats(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return super.onCommand(sender, command, label, args);
    }

    public void imsg(CommandSender s, String msg) {
        s.sendMessage(C.IRIS + "[" + C.DARK_GRAY + "Iris" + C.IRIS + "]" + C.GRAY + ": " + msg);
    }

    @Nullable
    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull String worldName, @Nullable String id) {
        org.bukkit.generator.BiomeProvider stagedBiomeProvider = WorldLifecycleStaging.consumeBiomeProvider(worldName);
        if (stagedBiomeProvider != null) {
            Iris.debug("Using staged runtime biome provider for " + worldName);
            return stagedBiomeProvider;
        }
        Iris.debug("Biome Provider Called for " + worldName + " using ID: " + id);
        return super.getDefaultBiomeProvider(worldName, id);
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        ChunkGenerator stagedGenerator = WorldLifecycleStaging.consumeGenerator(worldName);
        if (stagedGenerator != null) {
            Iris.debug("Using staged runtime generator for " + worldName);
            return stagedGenerator;
        }
        Iris.debug("Default World Generator Called for " + worldName + " using ID: " + id);
        if (id == null || id.isEmpty()) id = IrisSettings.get().getGenerator().getDefaultWorldType();
        Iris.debug("Generator ID: " + id + " requested by bukkit/plugin");

        PackValidationResult validation = PackValidationRegistry.get(id);
        if (validation != null && !validation.isLoadable()) {
            Iris.error("Refusing to create world '" + worldName + "' using broken pack '" + id + "':");
            for (String reason : validation.getBlockingErrors()) {
                Iris.error("  - " + reason);
            }
            throw new BrokenPackException(id, validation.getBlockingErrors());
        }

        IrisDimension dim = loadDimension(worldName, id);
        if (dim == null) {
            throw new RuntimeException("Can't find dimension " + id + "!");
        }

        Iris.debug("Assuming IrisDimension: " + dim.getName());
        NamespacedKey worldKey = IrisWorldStorage.keyFromName(worldName);

        IrisWorld w = IrisWorld.builder()
                .platformIdentity(worldKey.toString())
                .name(worldName)
                .seed(1337)
                .worldFolder(IrisWorldStorage.dimensionRoot(worldKey))
                .minHeight(dim.getMinHeight())
                .maxHeight(dim.getMaxHeight())
                .build();

        Iris.debug("Generator Config: " + w.toString());

        File ff = new File(w.worldFolder(), "iris/pack");
        File[] files = ff.listFiles();
        if (files == null || files.length == 0)
            IO.delete(ff);

        if (!ff.exists()) {
            ff.mkdirs();
            dim = service(StudioSVC.class).installIntoWorld(getSender(), dim, w.worldFolder());
            if (dim == null) {
                throw new IllegalStateException("Failed to install dimension pack for " + id);
            }
        }

        return new BukkitChunkGenerator(w, false, ff, dim.getLoadKey());
    }

    public static void validateAllPacks() {
        File packsRoot = Iris.instance.getDataFolder("packs");
        File[] packDirs = packsRoot.listFiles(File::isDirectory);
        if (packDirs == null || packDirs.length == 0) {
            return;
        }
        PackValidationRegistry.clear();
        for (File packDir : packDirs) {
            try {
                PackValidationResult result = PackValidator.validate(packDir);
                PackValidationRegistry.publish(result);
                if (!result.isLoadable()) {
                    Iris.error("Pack '" + result.getPackName() + "' FAILED validation - world/studio creation will be refused. Reasons:");
                    for (String reason : result.getBlockingErrors()) {
                        Iris.error("  - " + reason);
                    }
                } else if (!result.getWarnings().isEmpty()) {
                    Iris.info("Pack '" + result.getPackName() + "' validated ("
                            + result.getWarnings().size() + " warning(s)).");
                    for (String warning : result.getWarnings()) {
                        Iris.warn("  [" + result.getPackName() + "] " + warning);
                    }
                } else {
                    Iris.success("Pack '" + result.getPackName() + "' validated.");
                }
            } catch (Throwable e) {
                Iris.reportError("Pack validation failed for '" + packDir.getName() + "'", e);
            }
        }
    }

    @Nullable
    public static IrisDimension loadDimension(@NonNull String worldName, @NonNull String id) {
        File pack = IrisWorldStorage.packRoot(IrisWorldStorage.keyFromName(worldName));
        IrisDimension dimension = pack.isDirectory() ? IrisData.get(pack).getDimensionLoader().load(id) : null;
        if (dimension == null) dimension = IrisData.loadAnyDimension(id, null);
        if (dimension == null) {
            Iris.warn("Unable to find dimension type " + id + " Looking for online packs...");
            Iris.service(StudioSVC.class).downloadSearch(new VolmitSender(Bukkit.getConsoleSender()), id, false);
            dimension = IrisData.loadAnyDimension(id, null);

            if (dimension != null) {
                Iris.info("Resolved missing dimension, proceeding.");
            }
        }

        return dimension;
    }

    public void splash() {
        Iris.info("Custom Biomes: " + INMS.get().countCustomBiomes());
        printPacks();

        IrisSafeguard.mode().trySplash();
    }

    private void printPacks() {
        File packFolder = Iris.service(StudioSVC.class).getWorkspaceFolder();
        for (String line : IrisSplashComposer.composePackLines(packFolder, Iris::reportError)) {
            Iris.info(line);
        }
    }

    public int getIrisVersion() {
        String input = Iris.instance.getDescription().getVersion();
        int hyphenIndex = input.indexOf('-');
        if (hyphenIndex != -1) {
            String result = input.substring(0, hyphenIndex);
            result = result.replaceAll("\\.", "");
            return Integer.parseInt(result);
        }
        return -1;
    }

    public int getMCVersion() {
        try {
            String version = Bukkit.getVersion();
            Matcher matcher = Pattern.compile("\\(MC: ([\\d.]+)\\)").matcher(version);
            if (matcher.find()) {
                version = matcher.group(1).replaceAll("\\.", "");
                long versionNumber = Long.parseLong(version);
                if (versionNumber > Integer.MAX_VALUE) {
                    return -1;
                }
                return (int) versionNumber;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}

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
import art.arcane.iris.core.BukkitWorldReconciler;
import art.arcane.iris.core.IrisWorldGeneratorResolver;
import art.arcane.iris.core.PendingWorldDeleteQueue;
import art.arcane.iris.core.SettingsHotloadWatch;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.iris.core.lifecycle.PaperLibBootstrap;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.runtime.BukkitEnginePlatformHooks;
import art.arcane.iris.core.runtime.WorldDeletionQueue;
import art.arcane.iris.core.runtime.WorldRuntimeControlService;
import art.arcane.iris.api.terrain.IrisTerrainService;
import art.arcane.iris.core.link.IrisPapiInstaller;
import art.arcane.iris.core.link.IrisPapiListener;
import art.arcane.iris.core.link.IrisPapiState;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.link.MultiverseCoreLink;
import art.arcane.iris.core.nms.INMS;
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
import art.arcane.iris.core.safeguard.IrisSafeguard;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.spi.LogLevel;
import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.function.NastyRunnable;
import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;
import art.arcane.volmlib.util.hud.HudBossBarLane;
import art.arcane.volmlib.util.hud.HudSlotService;
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
import art.arcane.iris.util.simd.SimdSupport;
import art.arcane.volmlib.util.scheduling.Queue;
import art.arcane.volmlib.util.scheduling.ShurikenQueue;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final Object TEARDOWN_LOCK = new Object();
    private final AtomicBoolean alreadyDrained = new AtomicBoolean(false);
    private final AtomicBoolean servicesDisabled = new AtomicBoolean(false);
    private final AtomicBoolean sharedRuntimeClosed = new AtomicBoolean(false);
    private volatile PlaceholderRegistration papiRegistration;
    private volatile IrisPapiListener papiListener;
    private volatile IrisPapiState papiState;
    private KMap<Class<? extends IrisService>, IrisService> services;
    private final IrisWorldGeneratorResolver generatorResolver = new IrisWorldGeneratorResolver(this);
    private final BukkitWorldReconciler worldReconciler = new BukkitWorldReconciler(this);
    private final PendingWorldDeleteQueue pendingWorldDeletes = new PendingWorldDeleteQueue(this);
    private volatile SettingsHotloadWatch settingsHotloadWatch;

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
        servicesDisabled.set(false);
        sharedRuntimeClosed.set(false);
        MultiBurst.burst.reopen();
        MultiBurst.ioBurst.reopen();
        IrisLanguage.initialize();
        PaperLibBootstrap.install();
        SimdSupport.install();
        services = new KMap<>();
        setupAudience();
        BukkitPlatform.hostHud(new HudSlotService(this), new HudBossBarLane());
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
        generatorResolver.validateAllPacks();
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
        IrisServices.register(WorldDeletionQueue.class, pendingWorldDeletes);
        SettingsHotloadWatch watch = new SettingsHotloadWatch(getDataFile("settings.json"));
        settingsHotloadWatch = watch;
        configHotloadEngine = new ConfigHotloadEngine(
                watch::isSettingsFile,
                watch::knownSettingsFiles,
                watch::readSettingsContent,
                watch::normalizeSettingsContent
        );
        configHotloadEngine.configure(3_000L, List.of(watch.settingsFile()), List.of());
        // Stale-temp cleanup must complete before services enable: StudioSVC.onEnable downloads
        // packs through cache/temp on an async thread, and a concurrent delete of that folder
        // truncated pack imports mid-copy (partial packs/<key> without dimensions/).
        IO.delete(getTemp());
        services.values().forEach(IrisService::onEnable);
        services.values().forEach(this::registerListener);
        addShutdownHook();
        pendingWorldDeletes.processPendingStartupWorldDeletes();
        WorldLifecycleService.get();
        WorldRuntimeControlService.get();

        if (J.isFolia()) {
            J.s(() -> worldReconciler.checkForBukkitWorlds(s -> true), 1);
        }

        J.s(() -> {
            J.a(this::bstats);
            J.ar(() -> settingsHotloadWatch.checkConfigHotload(configHotloadEngine), 60);
            J.sr(this::tickQueue, 0);
            J.s(this::setupPapi);
            J.a(DatapackIngestService::autoIngestOnStartup, 60);

            autoStartStudio();
            if (!J.isFolia()) {
                worldReconciler.checkForBukkitWorlds(s -> true);
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
        shutdownHook = new Thread(() -> teardownRuntime("shutdown-hook", 30L), "Iris-ShutdownHook");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ex) {
            Iris.debug("Skipping shutdown hook registration because JVM shutdown is already in progress.");
        }
    }

    public BukkitWorldReconciler worldReconciler() {
        return worldReconciler;
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
        teardownRuntime("onDisable", 30L);
        if (BukkitPlatform.hasHud()) {
            BukkitPlatform.hudSlots().shutdown();
            BukkitPlatform.hudLanes().shutdown();
        }
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
        if (alreadyDrained.get()) {
            Iris.info("Pre-unload hook skipped; Iris already drained.");
            return;
        }
        Iris.info("BileTools pre-unload hook fired (" + reason + "). Freezing all Iris worlds.");
        drainOnce("pre-unload:" + reason, 45L);
    }

    /**
     * Drains the world generators exactly once. Serialized against the JVM shutdown hook so a
     * second caller cannot rip the pools or services out from under an in-flight drain.
     */
    private void drainOnce(String reason, long timeoutSeconds) {
        synchronized (TEARDOWN_LOCK) {
            if (alreadyDrained.compareAndSet(false, true)) {
                drainWorldGenerators(reason, timeoutSeconds);
            }
        }
    }

    /**
     * Full teardown: generators, then services, then the shared pools and the service map.
     * Both onDisable and the JVM shutdown hook route through here; whichever runs second is a no-op.
     */
    private void teardownRuntime(String reason, long timeoutSeconds) {
        synchronized (TEARDOWN_LOCK) {
            if (alreadyDrained.compareAndSet(false, true)) {
                drainWorldGenerators(reason, timeoutSeconds);
            }

            if (services != null && servicesDisabled.compareAndSet(false, true)) {
                for (IrisService service : services.values()) {
                    try {
                        service.onDisable();
                    } catch (Throwable e) {
                        Iris.reportError("Failed to disable " + service.getClass().getSimpleName() + ".", e);
                    }
                }
            }

            if (!sharedRuntimeClosed.compareAndSet(false, true)) {
                return;
            }

            J.attempt(MultiBurst.burst::close);
            J.attempt(MultiBurst.ioBurst::close);
            IrisServices.clear();
        }
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
        return generatorResolver.resolveDefaultBiomeProvider(worldName, id, () -> super.getDefaultBiomeProvider(worldName, id));
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return generatorResolver.resolveDefaultWorldGenerator(worldName, id);
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

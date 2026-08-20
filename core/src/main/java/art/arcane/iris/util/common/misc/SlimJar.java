package art.arcane.iris.util.common.misc;

import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.plugin.VolmitPlugin;
import io.github.slimjar.app.builder.ApplicationBuilder;
import io.github.slimjar.app.builder.SpigotApplicationBuilder;
import io.github.slimjar.injector.loader.factory.InjectableFactory;
import io.github.slimjar.logging.ProcessLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class SlimJar {
    private static final boolean DEBUG = Boolean.getBoolean("iris.debug-slimjar");

    private static final ReentrantLock lock = new ReentrantLock();
    private static final AtomicBoolean loaded = new AtomicBoolean();

    public static void load() {
        if (loaded.get()) return;
        lock.lock();

        try {
            if (loaded.getAndSet(true)) return;
            VolmitPlugin plugin = BukkitPlatform.volmitPlugin();
            Path downloadPath = plugin.getDataFolder("cache", "libraries").toPath();
            Logger logger = plugin.getLogger();

            logger.info("Loading libraries...");
            try {
                new SpigotApplicationBuilder(plugin)
                        .downloadDirectoryPath(downloadPath)
                        .debug(DEBUG)
                        .build();
            } catch (Throwable e) {
                // The Spigot builder is a probe: not every server exposes it, and the fallback is the
                // supported path on the ones that do not.
                IrisLogging.info("Failed to inject the library loader, falling back to application builder");
                ApplicationBuilder.appending(plugin.getName())
                        .injectableFactory(InjectableFactory.selecting(InjectableFactory.ERROR, InjectableFactory.INJECTABLE, InjectableFactory.WRAPPED, InjectableFactory.UNSAFE))
                        .downloadDirectoryPath(downloadPath)
                        .logger(new ProcessLogger() {
                            @Override
                            public void info(@NotNull String message, @Nullable Object... args) {
                                if (!DEBUG) return;
                                plugin.getLogger().info(message.formatted(args));
                            }

                            @Override
                            public void error(@NotNull String message, @Nullable Object... args) {
                                plugin.getLogger().severe(message.formatted(args));
                            }

                            @Override
                            public void debug(@NotNull String message, @Nullable Object... args) {
                                if (!DEBUG) return;
                                plugin.getLogger().info(message.formatted(args));
                            }
                        })
                        .build();
            }
            logger.info("Libraries loaded successfully!");
        } finally {
            lock.unlock();
        }
    }
}

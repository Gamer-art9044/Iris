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

package art.arcane.iris.core;

import com.google.gson.Gson;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONException;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.iris.util.common.misc.getHardware;
import art.arcane.iris.util.common.plugin.VolmitSender;
import lombok.Data;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("SynchronizeOnNonFinalField")
@Data
public class IrisSettings {
    public static IrisSettings settings;
    private IrisSettingsGeneral general = new IrisSettingsGeneral();
    private IrisSettingsWorld world = new IrisSettingsWorld();
    private IrisSettingsGUI gui = new IrisSettingsGUI();
    private IrisSettingsAutoconfiguration autoConfiguration = new IrisSettingsAutoconfiguration();
    private IrisSettingsGenerator generator = new IrisSettingsGenerator();
    private IrisSettingsConcurrency concurrency = new IrisSettingsConcurrency();
    private IrisSettingsStudio studio = new IrisSettingsStudio();
    private IrisSettingsPerformance performance = new IrisSettingsPerformance();
    private IrisSettingsPregen pregen = new IrisSettingsPregen();
    private IrisSettingsSentry sentry = new IrisSettingsSentry();
    private IrisSettingsTreeFeller treeFeller = new IrisSettingsTreeFeller();

    public static int getThreadCount(int c) {
        return Math.max(switch (c) {
            case -1, -2, -4 -> Runtime.getRuntime().availableProcessors() / -c;
            default -> Math.max(c, 2);
        }, 1);
    }

    public static IrisSettings get() {
        if (settings != null) {
            return settings;
        }

        settings = new IrisSettings();

        File s = IrisPlatforms.get().dataFile("settings.json");

        if (!s.exists()) {
            try {
                IO.writeAll(s, new JSONObject(new Gson().toJson(settings)).toString(4));
            } catch (JSONException | IOException e) {
                e.printStackTrace();
                IrisLogging.reportError(e);
            }
        } else {
            try {
                String ss = IO.readAll(s);
                settings = new Gson().fromJson(ss, IrisSettings.class);
                migrateLegacyKeys(ss);
                try {
                    IO.writeAll(s, new JSONObject(new Gson().toJson(settings)).toString(4));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (Throwable ee) {
                // IrisLogging.reportError(ee); causes a self-reference & stackoverflow
                IrisLogging.error("Configuration Error in settings.json! " + ee.getClass().getSimpleName() + ": " + ee.getMessage());
            }
        }

        return settings;
    }

    private static void migrateLegacyKeys(String rawJson) {
        JSONObject root = new JSONObject(rawJson);
        JSONObject worldObject = root.optJSONObject("world");
        if (worldObject == null || !worldObject.has("anbientEntitySpawningSystem")) {
            return;
        }

        settings.getWorld().setAmbientEntitySpawningSystem(worldObject.optBoolean("anbientEntitySpawningSystem", settings.getWorld().isAmbientEntitySpawningSystem()));
        IrisLogging.info("Migrated legacy settings key world.anbientEntitySpawningSystem -> world.ambientEntitySpawningSystem");
    }

    public static void invalidate() {
        synchronized (settings) {
            settings = null;
        }
    }

    public void forceSave() {
        File s = IrisPlatforms.get().dataFile("settings.json");

        try {
            IO.writeAll(s, new JSONObject(new Gson().toJson(settings)).toString(4));
        } catch (JSONException | IOException e) {
            e.printStackTrace();
            IrisLogging.reportError(e);
        }
    }

    @Data
    public static class IrisSettingsAutoconfiguration {
        public boolean configureSpigotTimeoutTime = true;
        public boolean configurePaperWatchdogDelay = true;
        public boolean autoRestartOnCustomBiomeInstall = true;
    }

    @Data
    public static class IrisSettingsWorld {
        public boolean postLoadBlockUpdates = true;
        public boolean forcePersistEntities = true;
        public boolean ambientEntitySpawningSystem = true;
        public long asyncTickIntervalMS = 700;
        public double targetSpawnEntitiesPerChunk = 0.95;
        public boolean markerEntitySpawningSystem = true;
        public boolean effectSystem = true;
        public boolean worldEditWandCUI = true;
        public boolean globalPregenCache = false;
    }

    @Data
    public static class IrisSettingsConcurrency {
        public int getParallelism() {
            return Math.max(2, Runtime.getRuntime().availableProcessors());
        }

        public int getIoParallelism() {
            return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        }

        public int getWorldGenThreads() {
            return Math.max(2, Runtime.getRuntime().availableProcessors());
        }
    }

    @Data
    public static class IrisSettingsPregen {
        private static final int REFERENCE_WORLD_HEIGHT = 384;
        private static final int MIN_RESIDENT_TECTONIC_PLATES = 16;
        private static final double MANTLE_HEAP_FRACTION = 0.6D;
        private static final int REFERENCE_PLATE_MEGABYTES = 48;
        public boolean useTicketQueue = true;
        public IrisRuntimeSchedulerMode runtimeSchedulerMode = IrisRuntimeSchedulerMode.AUTO;
        public IrisPaperLikeBackendMode paperLikeBackendMode = IrisPaperLikeBackendMode.AUTO;
        public int chunkLoadTimeoutSeconds = 15;
        public int timeoutWarnIntervalMs = 500;
        public int saveIntervalMs = 30_000;
        public int maxResidentTectonicPlates = 96;
        public int mantleBackpressureWaitMs = 25;
        public int mantleBackpressureTimeoutMs = 60_000;
        public int moddedPregenInFlight = 0;

        public int getChunkLoadTimeoutSeconds() {
            return Math.max(5, Math.min(chunkLoadTimeoutSeconds, 120));
        }

        public int getModdedPregenInFlight() {
            if (moddedPregenInFlight > 0) {
                return Math.min(512, moddedPregenInFlight);
            }

            int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
            return Math.max(16, Math.min(48, cpu * 2));
        }

        public int getMaxResidentTectonicPlates() {
            return Math.max(16, maxResidentTectonicPlates);
        }

        public int getEffectiveResidentTectonicPlates(int worldHeight) {
            int baseCap = getMaxResidentTectonicPlates();
            int normalizedHeight = Math.max(1, worldHeight);
            int heightScaledCap = (int) Math.round((double) baseCap * REFERENCE_WORLD_HEIGHT / (double) normalizedHeight);
            long maxHeapMegabytes = getHardware.getProcessMemory();
            double plateMegabytes = (double) REFERENCE_PLATE_MEGABYTES * (double) normalizedHeight / (double) REFERENCE_WORLD_HEIGHT;
            int byteBudgetCap = (int) Math.floor(MANTLE_HEAP_FRACTION * (double) maxHeapMegabytes / plateMegabytes);
            int effective = Math.min(heightScaledCap, byteBudgetCap);
            return Math.max(MIN_RESIDENT_TECTONIC_PLATES, Math.min(baseCap, effective));
        }

        public int getMantleBackpressureWaitMs() {
            return Math.max(5, Math.min(mantleBackpressureWaitMs, 1_000));
        }

        public int getMantleBackpressureTimeoutMs() {
            return Math.max(5_000, Math.min(mantleBackpressureTimeoutMs, 600_000));
        }

        public int getTimeoutWarnIntervalMs() {
            return Math.max(timeoutWarnIntervalMs, 250);
        }

        public IrisPaperLikeBackendMode getPaperLikeBackendMode() {
            if (paperLikeBackendMode == null) {
                return IrisPaperLikeBackendMode.AUTO;
            }

            return paperLikeBackendMode;
        }

        public int getSaveIntervalMs() {
            return Math.max(5_000, Math.min(saveIntervalMs, 900_000));
        }
    }

    @Data
    public static class IrisSettingsPerformance {
        private IrisSettingsEngineSVC engineSVC = new IrisSettingsEngineSVC();
        public boolean trimMantleInStudio = false; 
        public int mantleKeepAlive = 30;
        public int noiseCacheSize = 1_024;
        public int resourceLoaderCacheSize = 1_024;
        public int objectLoaderCacheSize = 4_096;
        public int mantleCleanupDelay = 200;
        public boolean simdKernels = true;
    }

    @Data
    public static class IrisSettingsGeneral {
        public String language = "en_US";
        public boolean commandSounds = true;
        public boolean debug = false;
        public boolean dumpMantleOnError = false;
        public boolean disableNMS = false;
        public boolean pluginMetrics = true;
        public boolean splashLogoStartup = true;
        public boolean useConsoleCustomColors = true;
        public boolean useCustomColorsIngame = true;
        public boolean adjustVanillaHeight = false;
        public boolean autoIngestDatapacks = true;
        public boolean autoImportDatapackStructures = true;
        public int spinh = -20;
        public int spins = 7;
        public int spinb = 8;


        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        public boolean canUseCustomColors(VolmitSender volmitSender) {
            return volmitSender.isPlayer() ? useCustomColorsIngame : useConsoleCustomColors;
        }
    }

    @Data
    public static class IrisSettingsSentry {
        public boolean includeServerId = true;
        public boolean disableAutoReporting = false;
        public boolean debug = false;
    }

    @Data
    public static class IrisSettingsGUI {
        public boolean useServerLaunchedGuis = true;
        public boolean maximumPregenGuiFPS = false;
        public boolean colorMode = true;
    }

    @Data
    public static class IrisSettingsGenerator {
        public String defaultWorldType = "overworld";
        public int maxBiomeChildDepth = 4;
        public boolean preventLeafDecay = true;
    }

    @Data
    public static class IrisSettingsTreeFeller {
        public boolean enabled = false;
        public int durabilityPreservationChance = 0;

        public int getDurabilityPreservationChance() {
            return Math.max(0, Math.min(durabilityPreservationChance, 100));
        }
    }

    @Data
    public static class IrisSettingsStudio {
        public boolean studio = true;
        public boolean openVSCode = true;
        public boolean disableTimeAndWeather = true;
        public boolean entitySpawning = true;
        public boolean autoStartDefaultStudio = false;
    }

    @Data
    public static class IrisSettingsEngineSVC {
        public boolean useVirtualThreads = true;
        public boolean forceMulticoreWrite = false;
        public int priority = Thread.NORM_PRIORITY;
        public int parallelism = -1;

        public int getPriority() {
            return Math.max(Math.min(priority, Thread.MAX_PRIORITY), Thread.MIN_PRIORITY);
        }

        public int getParallelism() {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            if (parallelism > 0) {
                int maximumParallelism = processors > Integer.MAX_VALUE / 2
                        ? Integer.MAX_VALUE
                        : processors * 2;
                return Math.min(parallelism, maximumParallelism);
            }

            return Math.max(1, (int) Math.ceil(Math.sqrt(processors)));
        }
    }
}

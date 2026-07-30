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

package art.arcane.iris.modded;

import art.arcane.iris.BuildConstants;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.LogLevel;
import art.arcane.iris.spi.PlatformBiomeWriter;
import art.arcane.iris.spi.PlatformEntityType;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.spi.PlatformScheduler;
import art.arcane.iris.spi.PlatformStructureHooks;
import art.arcane.iris.spi.PlatformWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;

import java.io.File;
import java.util.function.Consumer;

public final class ModdedPlatform implements IrisPlatform {
    private static volatile Consumer<Throwable> ERROR_SINK = null;
    private static volatile Consumer<Throwable> CAPTURE_SINK = null;

    private final ModdedLoader loader;
    private final ModdedRegistries registries;
    private final ModdedScheduler scheduler;
    private final ModdedStructureHooks structureHooks;
    private final ModdedBiomeWriter biomeWriter;

    public ModdedPlatform(ModdedLoader loader) {
        this.loader = loader;
        this.registries = new ModdedRegistries(ModdedEngineBootstrap::currentServer);
        this.scheduler = new ModdedScheduler();
        this.structureHooks = new ModdedStructureHooks(ModdedEngineBootstrap::currentServer);
        this.biomeWriter = new ModdedBiomeWriter(ModdedEngineBootstrap::currentServer);
    }

    public static void errorSink(Consumer<Throwable> sink) {
        ERROR_SINK = sink;
    }

    public static void captureSink(Consumer<Throwable> sink) {
        CAPTURE_SINK = sink;
    }

    public MinecraftServer server() {
        return ModdedEngineBootstrap.currentServer();
    }

    public ModdedScheduler moddedScheduler() {
        return scheduler;
    }

    @Override
    public String platformName() {
        return loader.platformName();
    }

    @Override
    public String minecraftVersion() {
        return loader.minecraftVersion();
    }

    @Override
    public PlatformRegistries registries() {
        return registries;
    }

    @Override
    public PlatformScheduler scheduler() {
        return scheduler;
    }

    @Override
    public PlatformStructureHooks structureHooks() {
        return structureHooks;
    }

    @Override
    public PlatformBiomeWriter biomeWriter() {
        return biomeWriter;
    }

    @Override
    public File dataFolder() {
        File folder = loader.configDir().resolve("iris").toFile();
        folder.mkdirs();
        return folder;
    }

    @Override
    public File dataFile(String... path) {
        File file = new File(dataFolder(), String.join(File.separator, path));
        file.getParentFile().mkdirs();
        return file;
    }

    @Override
    public File pluginJar() {
        File jar = loader.modJar();
        return jar != null ? jar : new File(dataFolder(), "iris-" + loader.platformName() + ".jar");
    }

    @Override
    public int irisVersionNumber() {
        return parseVersion(loader.modVersion());
    }

    @Override
    public int minecraftVersionNumber() {
        int fromLoader = parseVersion(loader.minecraftVersion());
        return fromLoader > 0 ? fromLoader : parseVersion(BuildConstants.MINECRAFT_VERSION);
    }

    @Override
    public void callEvent(Object event) {
    }

    @Override
    public void dispatchConsoleCommand(String command) {
        ModdedServerCommands.dispatch(ModdedEngineBootstrap.currentServer(), command);
    }

    @Override
    public boolean spawnEntity(PlatformWorld world, String entityKey, double x, double y, double z) {
        if (world == null || entityKey == null || !(world.nativeHandle() instanceof ServerLevel level)) {
            return false;
        }
        PlatformEntityType resolved = registries.entity(entityKey);
        if (resolved == null) {
            return false;
        }
        EntityType<?> type = (EntityType<?>) resolved.nativeHandle();
        BlockPos pos = BlockPos.containing(x, y, z);
        Entity entity = type.spawn(level, pos, EntitySpawnReason.COMMAND);
        return entity != null;
    }

    @Override
    public void log(LogLevel level, String message) {
        ModdedIrisLog.log(level, message);
    }

    @Override
    public void msg(String message) {
        ModdedIrisLog.info(message);
    }

    @Override
    public void reportError(Throwable error) {
        if (error == null) {
            return;
        }
        Consumer<Throwable> sink = ERROR_SINK;
        if (sink != null) {
            sink.accept(error);
            return;
        }
        ModdedIrisLog.error("Iris reported error", error);
        Consumer<Throwable> capture = CAPTURE_SINK;
        if (capture != null) {
            try {
                capture.accept(error);
            } catch (Throwable captureFailure) {
                ModdedIrisLog.error("Iris error-reporting sink failed", captureFailure);
            }
        }
    }

    private static int parseVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        int hyphen = raw.indexOf('-');
        String head = hyphen >= 0 ? raw.substring(0, hyphen) : raw;
        StringBuilder digits = new StringBuilder(head.length());
        for (int i = 0; i < head.length(); i++) {
            char ch = head.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else if (ch != '.') {
                break;
            }
        }
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            long value = Long.parseLong(digits.toString());
            return value > Integer.MAX_VALUE ? -1 : (int) value;
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}

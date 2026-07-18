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

package art.arcane.iris.forge;

import art.arcane.iris.modded.ModdedLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.File;
import java.nio.file.Path;

public final class ForgeModdedLoader implements ModdedLoader {
    @Override
    public String platformName() {
        return "forge";
    }

    @Override
    public String minecraftVersion() {
        return FMLLoader.versionInfo().mcVersion();
    }

    @Override
    public String modVersion() {
        return ModList.getModContainerById("irisworldgen")
                .map((net.minecraftforge.fml.ModContainer container) -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public MinecraftServer currentServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    @Override
    public void invalidateLevelCache(MinecraftServer server) {
        server.markWorldsDirty();
    }

    @Override
    public boolean clientEnvironment() {
        return FMLEnvironment.dist.isClient();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public File modJar() {
        IModFileInfo info = ModList.getModFileById("irisworldgen");
        return info == null ? null : info.getFile().getFilePath().toFile();
    }
}

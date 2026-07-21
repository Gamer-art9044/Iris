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

package art.arcane.iris.fabric;

import art.arcane.iris.modded.ModdedLoader;
import art.arcane.iris.modded.service.ModdedTreeFellerService;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.nio.file.Path;

public final class FabricModdedLoader implements ModdedLoader {
    private static final Identifier TREE_FELLER_PERMISSION = Identifier.fromNamespaceAndPath("iris", "treefeller");

    @Override
    public String platformName() {
        return "fabric";
    }

    @Override
    public String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map((ModContainer container) -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer("irisworldgen")
                .map((ModContainer container) -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public MinecraftServer currentServer() {
        Object instance = FabricLoader.getInstance().getGameInstance();
        return instance instanceof MinecraftServer server ? server : null;
    }

    @Override
    public void invalidateLevelCache(MinecraftServer server) {
    }

    @Override
    public boolean clientEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public File modJar() {
        return FabricLoader.getInstance().getModContainer("irisworldgen")
                .flatMap((ModContainer container) -> container.getOrigin().getPaths().stream().findFirst())
                .map((Path p) -> p.toFile())
                .orElse(null);
    }

    @Override
    public boolean hasTreeFellerPermission(ServerPlayer player) {
        return ((PermissionContextOwner) player).checkPermission(
                TREE_FELLER_PERMISSION,
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public boolean canTreeFellerBreak(
            ServerLevel level,
            ServerPlayer player,
            BlockPos position,
            BlockState state
    ) {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(position) : null;
        return ModdedTreeFellerService.runBreakProbe(() -> PlayerBlockBreakEvents.BEFORE.invoker()
                .beforeBlockBreak(level, player, position, state, blockEntity));
    }
}

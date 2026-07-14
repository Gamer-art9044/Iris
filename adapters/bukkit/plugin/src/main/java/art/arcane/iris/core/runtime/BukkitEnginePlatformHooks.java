/*
 * Iris is a World Generator for Minecraft Bukkit Servers
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

package art.arcane.iris.core.runtime;

import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.iris.core.events.IrisEngineHotloadEvent;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.core.tools.WorldMaintenance;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineMode;
import art.arcane.iris.engine.framework.EnginePlatformHooks;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionRuntimeContract;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.World;

public final class BukkitEnginePlatformHooks implements EnginePlatformHooks {
    @Override
    public void refreshWorkspace(Engine engine) {
        new IrisProject(engine.getData().getDataFolder()).updateWorkspace();
    }

    @Override
    public void refreshDatapackWorkspace(Engine engine) {
        DatapackIngestService.refreshWorkspace(engine.getData());
    }

    @Override
    public void reloadDatapacks(Engine engine) {
        synchronized (ServerConfigurator.class) {
            ServerConfigurator.installDataPacks(false);
        }
    }

    @Override
    public void fireHotloadEvent(Engine engine) {
        IrisPlatforms.get().callEvent(new IrisEngineHotloadEvent(engine));
    }

    @Override
    public void validateDimensionHotload(Engine engine, IrisDimension replacement) {
        IrisDimensionRuntimeContract.requireHotloadCompatible(
                "Bukkit Studio world '" + engine.getWorld().name() + "'",
                engine.getDimension(),
                replacement,
                "iris");
    }

    @Override
    public boolean isPregeneratorActive(Engine engine) {
        IrisWorld world = engine.getWorld();
        if (world == null) {
            return false;
        }
        PregeneratorJob pregeneratorJob = PregeneratorJob.getInstance();
        return pregeneratorJob != null && pregeneratorJob.targetsWorldIdentity(world.identity());
    }

    @Override
    public void shutdownPregenerator(Engine engine) {
        PregeneratorJob.shutdownInstance();
    }

    @Override
    public boolean shouldDisableChunkContextCache(Engine engine) {
        IrisWorld world = engine.getWorld();
        if (!J.isFolia() || world == null || !world.hasPlatformWorld()) {
            return false;
        }
        boolean maintenanceActive = WorldMaintenance.isWorldMaintenanceActive(world.identity());
        return EngineMode.shouldDisableContextCacheForMaintenance(maintenanceActive, isPregeneratorActive(engine));
    }

    @Override
    public boolean shouldSkipMantleCleanup(Engine engine) {
        IrisWorld world = engine.getWorld();
        return world != null
                && WorldMaintenance.isWorldMaintenanceActive(world.identity())
                && !isPregeneratorActive(engine);
    }

    @Override
    public boolean shouldSkipMantleMarkerRead(Engine engine, int chunkX, int chunkZ) {
        IrisWorld irisWorld = engine.getWorld();
        if (!J.isFolia() || irisWorld == null || !irisWorld.hasPlatformWorld()) {
            return false;
        }
        World world = BukkitWorldBinding.world(irisWorld);
        return world != null && J.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    @Override
    public boolean shouldBypassMantleStages(Engine engine) {
        if (!J.isFolia() || !engine.getWorld().hasPlatformWorld()) {
            return false;
        }
        World world = BukkitWorldBinding.world(engine.getWorld());
        return world != null && IrisToolbelt.isWorldMaintenanceBypassingMantleStages(world);
    }
}

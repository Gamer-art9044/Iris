package art.arcane.iris.core.service;

import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.iris.core.datapack.DatapackStructureScopeIndex;
import art.arcane.iris.core.nms.DatapackStructureScopeResult;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.plugin.IrisService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.IOException;
import java.util.Set;

public final class DatapackStructureScopeSVC implements IrisService {
    private DatapackStructureScopeIndex scopeIndex = DatapackStructureScopeIndex.create(null);

    @Override
    public void onEnable() {
        try {
            scopeIndex = DatapackStructureScopeIndex.create(
                    DatapackIngestService.installedStructureScopeResources());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Iris could not establish ownership for installed datapack structure sets", e);
        }
        if (scopeIndex.isEmpty()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            applyScope(world);
        }
    }

    @Override
    public void onDisable() {
        for (World world : Bukkit.getWorlds()) {
            INMS.get().abandonStudioStructureBootstrap(world);
        }
        scopeIndex = DatapackStructureScopeIndex.create(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldInit(WorldInitEvent event) {
        boolean studioEntryBootstrap = event.getWorld().getGenerator()
                instanceof BukkitChunkGenerator generator
                && generator.isStudioEntryBootstrapActive();
        if (shouldApplyScope(scopeIndex.isEmpty(), studioEntryBootstrap)) {
            applyScope(event.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldUnload(WorldUnloadEvent event) {
        INMS.get().abandonStudioStructureBootstrap(event.getWorld());
    }

    static boolean shouldApplyScope(boolean scopeIndexEmpty, boolean studioEntryBootstrap) {
        return !scopeIndexEmpty || studioEntryBootstrap;
    }

    private void applyScope(World world) {
        Set<String> declaredSources = declaredSources(world);
        try {
            DatapackStructureScopeResult result = INMS.get().scopeDatapackStructures(
                    world, scopeIndex, declaredSources);
            IrisLogging.info("Scoped Iris-managed datapack structure sets for world '"
                    + world.getName() + "': " + result.retainedManagedSets() + " retained, "
                    + result.excludedManagedSets() + " excluded.");
        } catch (Throwable error) {
            throw new IllegalStateException("Could not scope Iris-managed datapack structure sets for world '"
                    + world.getName() + "'", error);
        }
    }

    private Set<String> declaredSources(World world) {
        PlatformChunkGenerator generator = IrisToolbelt.access(world);
        if (generator == null) {
            return Set.of();
        }
        return scopeIndex.declaredSources(
                generator.getTarget().getDimension().getDatapackImports());
    }
}

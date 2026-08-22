package art.arcane.iris.core.lifecycle;

import art.arcane.iris.core.nms.INMS;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.util.concurrent.CompletableFuture;

final class BukkitPublicBackend implements WorldLifecycleBackend {
    private final CapabilitySnapshot capabilities;

    BukkitPublicBackend(CapabilitySnapshot capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public boolean supports(WorldLifecycleRequest request, CapabilitySnapshot capabilities) {
        return true;
    }

    @Override
    public CompletableFuture<World> create(WorldLifecycleRequest request) {
        World existing = WorldIdentity.resolve(request.worldKey()).orElse(null);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }

        WorldCreator creator = request.toWorldCreator();
        if (request.generator() != null) {
            WorldLifecycleStaging.stageGenerator(request.worldName(), request.generator(), request.biomeProvider());
            WorldLifecycleStaging.stageStemGenerator(request.worldName(), request.generator());
        }

        INMS.get().ensureServerLevelInjection();

        try {
            World world = creator.createWorld();
            return CompletableFuture.completedFuture(world);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(WorldLifecycleSupport.unwrap(e));
        } finally {
            WorldLifecycleStaging.clearAll(request.worldName());
        }
    }

    @Override
    public CompletableFuture<Boolean> unloadAsync(World world, boolean save) {
        return WorldLifecycleSupport.unloadWorldAsync(capabilities, world, save);
    }

    @Override
    public String backendName() {
        return "bukkit_public";
    }
}

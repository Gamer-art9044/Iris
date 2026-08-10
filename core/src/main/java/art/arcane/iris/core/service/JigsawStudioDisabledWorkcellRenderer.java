package art.arcane.iris.core.service;

import art.arcane.iris.core.runtime.jigsaw.JigsawStudioBay;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioBounds;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioLayout;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class JigsawStudioDisabledWorkcellRenderer {
    private static final String ENTITY_TAG = "iris_jigsaw_disabled_workcell";

    private final Map<UUID, RequestDisplays> requests = new HashMap<>();

    public void reconcile(World world, UUID requestId, JigsawStudioLayout layout) {
        World activeWorld = Objects.requireNonNull(world, "Jigsaw Studio display world");
        UUID activeRequestId = Objects.requireNonNull(requestId, "Jigsaw Studio display request ID");
        Map<String, Descriptor> desired = descriptors(Objects.requireNonNull(
                layout,
                "Jigsaw Studio display layout"));
        List<BlockDisplay> removals = new ArrayList<>();
        long generation;
        synchronized (this) {
            RequestDisplays state = requests.computeIfAbsent(
                    activeRequestId,
                    ignored -> new RequestDisplays(activeWorld.getUID()));
            if (!state.worldId.equals(activeWorld.getUID())) {
                removals.addAll(state.entities.values());
                state = new RequestDisplays(activeWorld.getUID());
                requests.put(activeRequestId, state);
            }
            generation = Math.incrementExact(state.generation);
            state.generation = generation;
            state.desired.clear();
            state.desired.putAll(desired);
            for (Map.Entry<String, BlockDisplay> entry : new ArrayList<>(state.entities.entrySet())) {
                Descriptor descriptor = desired.get(entry.getKey());
                if (descriptor == null || !descriptor.equals(state.rendered.get(entry.getKey()))) {
                    state.entities.remove(entry.getKey());
                    state.rendered.remove(entry.getKey());
                    removals.add(entry.getValue());
                }
            }
        }
        remove(removals);
        for (Descriptor descriptor : desired.values()) {
            scheduleSpawn(activeWorld, activeRequestId, generation, descriptor);
        }
    }

    public void unloadChunk(UUID requestId, int chunkX, int chunkZ) {
        if (requestId == null) {
            return;
        }
        List<BlockDisplay> removals;
        synchronized (this) {
            RequestDisplays state = requests.get(requestId);
            if (state == null) {
                return;
            }
            removals = detachChunkDisplays(state.entities, state.rendered, chunkX, chunkZ);
        }
        remove(removals);
    }

    public void removeRequest(UUID requestId) {
        if (requestId == null) {
            return;
        }
        RequestDisplays removed;
        synchronized (this) {
            removed = requests.remove(requestId);
        }
        if (removed != null) {
            remove(new ArrayList<>(removed.entities.values()));
        }
    }

    public void removeAll() {
        List<BlockDisplay> removals = new ArrayList<>();
        synchronized (this) {
            for (RequestDisplays state : requests.values()) {
                removals.addAll(state.entities.values());
            }
            requests.clear();
        }
        remove(removals);
    }

    static Map<String, Descriptor> descriptors(JigsawStudioLayout layout) {
        Map<String, Descriptor> descriptors = new LinkedHashMap<>();
        for (JigsawStudioBay bay : layout.bays()) {
            if (bay.enabled()) {
                continue;
            }
            JigsawStudioBounds bounds = bay.bounds();
            descriptors.put(bay.stableId(), new Descriptor(
                    bay.stableId(),
                    bounds.originX(),
                    bounds.originY(),
                    bounds.originZ(),
                    bounds.dimensions().width(),
                    bounds.dimensions().height(),
                    bounds.dimensions().depth()));
        }
        return Map.copyOf(descriptors);
    }

    synchronized int activeDisplayCount(UUID requestId) {
        RequestDisplays state = requests.get(requestId);
        return state == null ? 0 : state.entities.size();
    }

    static List<BlockDisplay> detachChunkDisplays(
            Map<String, BlockDisplay> entities,
            Map<String, Descriptor> rendered,
            int chunkX,
            int chunkZ
    ) {
        List<BlockDisplay> removals = new ArrayList<>();
        for (Map.Entry<String, BlockDisplay> entry : new ArrayList<>(entities.entrySet())) {
            Descriptor descriptor = rendered.get(entry.getKey());
            if (descriptor == null
                    || descriptor.originX() >> 4 != chunkX
                    || descriptor.originZ() >> 4 != chunkZ) {
                continue;
            }
            entities.remove(entry.getKey());
            rendered.remove(entry.getKey());
            removals.add(entry.getValue());
        }
        return List.copyOf(removals);
    }

    private void scheduleSpawn(
            World world,
            UUID requestId,
            long generation,
            Descriptor descriptor
    ) {
        synchronized (this) {
            RequestDisplays state = requests.get(requestId);
            if (state == null
                    || state.generation != generation
                    || state.entities.containsKey(descriptor.workcellId())
                    || !descriptor.equals(state.desired.get(descriptor.workcellId()))) {
                return;
            }
        }
        J.runRegion(
                world,
                descriptor.originX() >> 4,
                descriptor.originZ() >> 4,
                () -> spawn(world, requestId, generation, descriptor));
    }

    private void spawn(
            World world,
            UUID requestId,
            long generation,
            Descriptor descriptor
    ) {
        if (!world.isChunkLoaded(descriptor.originX() >> 4, descriptor.originZ() >> 4)) {
            return;
        }
        synchronized (this) {
            RequestDisplays state = requests.get(requestId);
            if (state == null
                    || state.generation != generation
                    || state.entities.containsKey(descriptor.workcellId())
                    || !descriptor.equals(state.desired.get(descriptor.workcellId()))) {
                return;
            }
        }
        BlockDisplay display = world.spawn(
                new Location(world, descriptor.originX(), descriptor.originY(), descriptor.originZ()),
                BlockDisplay.class,
                entity -> configure(entity, descriptor));
        boolean retained;
        synchronized (this) {
            RequestDisplays state = requests.get(requestId);
            retained = state != null
                    && state.generation == generation
                    && !state.entities.containsKey(descriptor.workcellId())
                    && descriptor.equals(state.desired.get(descriptor.workcellId()));
            if (retained) {
                state.entities.put(descriptor.workcellId(), display);
                state.rendered.put(descriptor.workcellId(), descriptor);
            }
        }
        if (!retained) {
            remove(display);
        }
    }

    private static void configure(BlockDisplay display, Descriptor descriptor) {
        display.setBlock(Material.RED_STAINED_GLASS.createBlockData());
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(descriptor.width(), descriptor.height(), descriptor.depth()),
                new Quaternionf()));
        display.setBrightness(new Display.Brightness(15, 15));
        display.setDisplayWidth(Math.max(descriptor.width(), descriptor.depth()));
        display.setDisplayHeight(descriptor.height());
        display.setViewRange(128.0F);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setSilent(true);
        display.addScoreboardTag(ENTITY_TAG);
    }

    private static void remove(List<BlockDisplay> displays) {
        for (BlockDisplay display : displays) {
            remove(display);
        }
    }

    private static void remove(BlockDisplay display) {
        if (display != null) {
            J.runEntity(display, display::remove);
        }
    }

    record Descriptor(
            String workcellId,
            int originX,
            int originY,
            int originZ,
            int width,
            int height,
            int depth
    ) {
        Descriptor {
            workcellId = Objects.requireNonNull(workcellId, "Jigsaw Studio display workcell ID");
            if (width < 1 || height < 1 || depth < 1) {
                throw new IllegalArgumentException("Jigsaw Studio display dimensions must be positive");
            }
        }
    }

    private static final class RequestDisplays {
        private final UUID worldId;
        private final Map<String, Descriptor> desired = new HashMap<>();
        private final Map<String, Descriptor> rendered = new HashMap<>();
        private final Map<String, BlockDisplay> entities = new HashMap<>();
        private long generation;

        private RequestDisplays(UUID worldId) {
            this.worldId = worldId;
        }
    }
}

package art.arcane.iris.core.runtime;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.lifecycle.CapabilitySnapshot;
import art.arcane.iris.core.lifecycle.ServerFamily;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.service.BoardSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.scheduling.J;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class WorldRuntimeControlService {
    private static volatile WorldRuntimeControlService instance;

    private final CapabilitySnapshot capabilities;
    private final WorldRuntimeControlBackend backend;
    private final String capabilityDescription;

    private WorldRuntimeControlService(CapabilitySnapshot capabilities) {
        this.capabilities = capabilities;
        this.backend = selectBackend(capabilities);
        this.capabilityDescription = "family=" + capabilities.serverFamily().id()
                + ", backend=" + backend.backendName()
                + ", " + backend.describeCapabilities();
    }

    public static WorldRuntimeControlService get() {
        WorldRuntimeControlService current = instance;
        if (current != null) {
            return current;
        }

        synchronized (WorldRuntimeControlService.class) {
            if (instance != null) {
                return instance;
            }

            CapabilitySnapshot capabilities = WorldLifecycleService.get().capabilities();
            instance = new WorldRuntimeControlService(capabilities);
            IrisLogging.info("WorldRuntimeControl capabilities: %s", instance.capabilityDescription);
            return instance;
        }
    }

    public String backendName() {
        return backend.backendName();
    }

    public String capabilityDescription() {
        return capabilityDescription;
    }

    public OptionalLong readDayTime(World world) {
        return backend.readDayTime(world);
    }

    public boolean applyStudioWorldRules(World world) {
        if (world == null) {
            return false;
        }

        IrisServices.get(art.arcane.iris.core.link.MultiverseCoreLink.class).removeFromConfig(world);
        setIntGameRule(world, 0, "SPAWN_CHUNK_RADIUS", "spawnChunkRadius");
        enableStudioEntitySpawning(world);
        if (!IrisSettings.get().getStudio().isDisableTimeAndWeather()) {
            return true;
        }

        setBooleanGameRule(world, false, "ADVANCE_WEATHER", "DO_WEATHER_CYCLE", "WEATHER_CYCLE", "doWeatherCycle", "weatherCycle");
        setBooleanGameRule(world, false, "ADVANCE_TIME", "DO_DAYLIGHT_CYCLE", "DAYLIGHT_CYCLE", "doDaylightCycle", "daylightCycle");
        applyNoonTimeLock(world);
        return true;
    }

    public boolean applyObjectStudioWorldRules(World world) {
        if (world == null) {
            return false;
        }

        applyStudioWorldRules(world);

        setBooleanGameRule(world, false, "DO_FIRE_TICK", "doFireTick");
        setBooleanGameRule(world, false, "DO_MOB_LOOT", "doMobLoot");
        setBooleanGameRule(world, true, "DO_IMMEDIATE_RESPAWN", "doImmediateRespawn");
        setBooleanGameRule(world, false, "FALL_DAMAGE", "fallDamage");
        setBooleanGameRule(world, false, "FIRE_DAMAGE", "fireDamage");
        setBooleanGameRule(world, false, "DROWNING_DAMAGE", "drowningDamage");
        setBooleanGameRule(world, false, "FREEZE_DAMAGE", "freezeDamage");
        setBooleanGameRule(world, false, "MOB_GRIEFING", "mobGriefing");
        setBooleanGameRule(world, false, "DO_TILE_DROPS", "doTileDrops");
        setBooleanGameRule(world, true, "KEEP_INVENTORY", "keepInventory");
        setIntGameRule(world, 0, "RANDOM_TICK_SPEED", "randomTickSpeed");
        setIntGameRule(world, 0, "SPAWN_RADIUS", "spawnRadius");
        setIntGameRule(world, 0, "MAX_ENTITY_CRAMMING", "maxEntityCramming");
        applyNoonTimeLock(world);
        return true;
    }

    static void enableStudioEntitySpawning(World world) {
        setBooleanGameRule(world, true, "DO_MOB_SPAWNING", "doMobSpawning");
        setBooleanGameRule(world, true, "DO_TRADER_SPAWNING", "doTraderSpawning");
        setBooleanGameRule(world, true, "DO_PATROL_SPAWNING", "doPatrolSpawning");
        setBooleanGameRule(world, true, "DO_INSOMNIA", "doInsomnia");
        setBooleanGameRule(world, true, "DO_WARDEN_SPAWNING", "doWardenSpawning");
    }

    public boolean applyNoonTimeLock(World world) {
        if (world == null) {
            return false;
        }

        if (!hasMutableClock(world)) {
            return false;
        }

        OptionalLong currentTime = readDayTime(world);
        if (currentTime.isEmpty()) {
            return false;
        }

        long skipAmount = (6000L - currentTime.getAsLong()) % 24000L;
        if (skipAmount < 0L) {
            skipAmount += 24000L;
        }

        long effectiveSkip = skipAmount;
        if (TIME_SKIP_EVENT_AVAILABLE) {
            long fired = fireTimeSkipEvent(world, skipAmount);
            if (fired == TIME_SKIP_CANCELLED) {
                return false;
            }
            effectiveSkip = fired;
        }

        try {
            boolean written = backend.writeDayTime(world, currentTime.getAsLong() + effectiveSkip);
            if (!written) {
                return false;
            }
            backend.syncTime(world);
            return true;
        } catch (Throwable e) {
            IrisLogging.debug("Runtime time lock skipped for world \"" + world.getName() + "\": " + e.getMessage());
            return false;
        }
    }

    private static final long TIME_SKIP_CANCELLED = Long.MIN_VALUE;
    private static final boolean TIME_SKIP_EVENT_AVAILABLE = probeTimeSkipEvent();

    private static boolean probeTimeSkipEvent() {
        try {
            Class.forName("org.bukkit.event.world.TimeSkipEvent$SkipReason");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private long fireTimeSkipEvent(World world, long skipAmount) {
        try {
            TimeSkipEvent event = new TimeSkipEvent(world, TimeSkipEvent.SkipReason.CUSTOM, skipAmount);
            PluginManager pluginManager = Bukkit.getPluginManager();
            if (pluginManager != null) {
                pluginManager.callEvent(event);
            }
            if (event.isCancelled()) {
                return TIME_SKIP_CANCELLED;
            }
            return event.getSkipAmount();
        } catch (Throwable e) {
            return skipAmount;
        }
    }

    public CompletableFuture<Chunk> requestChunkAsync(World world, int chunkX, int chunkZ, boolean generate) {
        return backend.requestChunkAsync(world, chunkX, chunkZ, generate);
    }

    public CompletableFuture<Chunk> requestChunkAsync(
            World world,
            int chunkX,
            int chunkZ,
            boolean generate,
            boolean urgent
    ) {
        return backend.requestChunkAsync(world, chunkX, chunkZ, generate, urgent);
    }

    public void prepareGenerator(World world) {
        if (world == null) {
            return;
        }

        try {
            art.arcane.iris.engine.platform.PlatformChunkGenerator provider = art.arcane.iris.core.tools.IrisToolbelt.access(world);
            if (provider == null) {
                return;
            }

            art.arcane.iris.engine.framework.Engine engine = provider.getEngine();
            if (engine == null) {
                return;
            }

            engine.getMantle().getComponents();
            engine.getMantle().getRealRadius();
        } catch (Throwable e) {
            IrisLogging.reportError("Failed to prepare generator state for world \"" + world.getName() + "\".", e);
        }
    }

    public Location resolveEntryAnchor(World world) {
        if (world == null) {
            return null;
        }

        PlatformChunkGenerator provider = IrisToolbelt.access(world);
        return resolveEntryAnchor(world, provider);
    }

    static Location resolveEntryAnchor(World world, PlatformChunkGenerator provider) {
        if (world == null) {
            return null;
        }

        if (provider != null && provider.isStudio() && provider instanceof BukkitChunkGenerator bukkitProvider) {
            Location initialSpawn = bukkitProvider.getInitialSpawnLocation(world);
            if (initialSpawn != null) {
                return initialSpawn.clone();
            }
        }

        Location spawnLocation = world.getSpawnLocation();
        if (spawnLocation != null) {
            return spawnLocation.clone();
        }

        int minY = world.getMinHeight() + 1;
        int y = Math.max(minY, 96);
        return new Location(world, 0.5D, y, 0.5D);
    }

    public CompletableFuture<Location> resolveSafeEntry(World world, Location source) {
        if (world == null || source == null) {
            return CompletableFuture.completedFuture(null);
        }

        int chunkX = source.getBlockX() >> 4;
        int chunkZ = source.getBlockZ() >> 4;
        CompletableFuture<Location> future = new CompletableFuture<>();
        boolean scheduled = J.runRegion(world, chunkX, chunkZ, () -> {
            try {
                future.complete(findTopSafeLocation(world, source));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        if (!scheduled) {
            future.completeExceptionally(new IllegalStateException(
                    "Failed to schedule safe-entry resolve for " + world.getName() + "@" + chunkX + "," + chunkZ + "."));
        }
        return future;
    }

    public CompletableFuture<Boolean> teleport(Player player, Location location) {
        if (player == null || location == null) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        boolean scheduled = J.runEntity(player, () -> {
            try {
                CompletableFuture<Boolean> teleportFuture = PaperLib.teleportAsync(player, location);
                if (teleportFuture == null) {
                    future.complete(false);
                    return;
                }

                teleportFuture.whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                        return;
                    }

                    if (Boolean.TRUE.equals(success)) {
                        J.runEntity(player, () -> IrisServices.get(BoardSVC.class).updatePlayer(player));
                        future.complete(true);
                        return;
                    }

                    future.complete(false);
                });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        if (!scheduled) {
            return CompletableFuture.failedFuture(new IllegalStateException("Failed to schedule teleport for " + player.getName() + "."));
        }

        return future;
    }

    public boolean hasMutableClock(World world) {
        try {
            Object handle = invokeNoArg(world, "getHandle");
            if (handle == null) {
                return false;
            }

            Object dimensionTypeHolder = invokeNoArg(handle, "dimensionTypeRegistration");
            Object dimensionType = unwrapDimensionType(dimensionTypeHolder);
            if (dimensionType == null) {
                return false;
            }

            return !dimensionTypeHasFixedTime(dimensionType);
        } catch (Throwable e) {
            return false;
        }
    }

    private static WorldRuntimeControlBackend selectBackend(CapabilitySnapshot capabilities) {
        ServerFamily family = capabilities.serverFamily();
        if (family.isPaperLike()) {
            return new PaperLikeRuntimeControlBackend(capabilities);
        }

        return new BukkitPublicRuntimeControlBackend(capabilities);
    }

    static Location findTopSafeLocation(World world, Location source) {
        int x = source.getBlockX();
        int z = source.getBlockZ();
        float yaw = source.getYaw();
        float pitch = source.getPitch();
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        if (world.isChunkLoaded(x >> 4, z >> 4)) {
            int raw = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            int y = Math.max(minY, Math.min(maxY, raw + 1));
            return new Location(world, x + 0.5D, y, z + 0.5D, yaw, pitch);
        }
        int y = Math.max(minY, Math.min(maxY, source.getBlockY()));
        return new Location(world, x + 0.5D, y, z + 0.5D, yaw, pitch);
    }

    @SuppressWarnings("unchecked")
    private static void setBooleanGameRule(World world, boolean value, String... names) {
        GameRule<Boolean> gameRule = resolveBooleanGameRule(world, names);
        if (gameRule != null) {
            world.setGameRule(gameRule, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setIntGameRule(World world, int value, String... names) {
        GameRule<Integer> gameRule = resolveIntGameRule(world, names);
        if (gameRule != null) {
            world.setGameRule(gameRule, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Integer> resolveIntGameRule(World world, String... names) {
        if (world == null || names == null || names.length == 0) {
            return null;
        }

        Set<String> candidates = buildRuleNameCandidates(names);
        for (String name : candidates) {
            if (name == null || name.isBlank()) {
                continue;
            }

            try {
                Field field = GameRule.class.getField(name);
                Object value = field.get(null);
                if (value instanceof GameRule<?> gameRule && Integer.class.equals(gameRule.getType())) {
                    return (GameRule<Integer>) gameRule;
                }
            } catch (Throwable ignored) {
            }

            try {
                GameRule<?> byName = GameRule.getByName(name);
                if (byName != null && Integer.class.equals(byName.getType())) {
                    return (GameRule<Integer>) byName;
                }
            } catch (Throwable ignored) {
            }
        }

        String[] availableRules = world.getGameRules();
        if (availableRules == null || availableRules.length == 0) {
            return null;
        }

        Set<String> normalizedCandidates = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                normalizedCandidates.add(normalizeRuleName(candidate));
            }
        }

        for (String availableRule : availableRules) {
            String normalizedAvailable = normalizeRuleName(availableRule);
            if (!normalizedCandidates.contains(normalizedAvailable)) {
                continue;
            }

            try {
                GameRule<?> byName = GameRule.getByName(availableRule);
                if (byName != null && Integer.class.equals(byName.getType())) {
                    return (GameRule<Integer>) byName;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Boolean> resolveBooleanGameRule(World world, String... names) {
        if (world == null || names == null || names.length == 0) {
            return null;
        }

        Set<String> candidates = buildRuleNameCandidates(names);
        for (String name : candidates) {
            if (name == null || name.isBlank()) {
                continue;
            }

            try {
                Field field = GameRule.class.getField(name);
                Object value = field.get(null);
                if (value instanceof GameRule<?> gameRule && Boolean.class.equals(gameRule.getType())) {
                    return (GameRule<Boolean>) gameRule;
                }
            } catch (Throwable ignored) {
            }

            try {
                GameRule<?> byName = GameRule.getByName(name);
                if (byName != null && Boolean.class.equals(byName.getType())) {
                    return (GameRule<Boolean>) byName;
                }
            } catch (Throwable ignored) {
            }
        }

        String[] availableRules = world.getGameRules();
        if (availableRules == null || availableRules.length == 0) {
            return null;
        }

        Set<String> normalizedCandidates = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                normalizedCandidates.add(normalizeRuleName(candidate));
            }
        }

        for (String availableRule : availableRules) {
            String normalizedAvailable = normalizeRuleName(availableRule);
            if (!normalizedCandidates.contains(normalizedAvailable)) {
                continue;
            }

            try {
                GameRule<?> byName = GameRule.getByName(availableRule);
                if (byName != null && Boolean.class.equals(byName.getType())) {
                    return (GameRule<Boolean>) byName;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static Set<String> buildRuleNameCandidates(String... names) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }

            candidates.add(name);
            candidates.add(name.toUpperCase());
            candidates.add(name.toLowerCase());
        }

        return candidates;
    }

    private static String normalizeRuleName(String name) {
        if (name == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                builder.append(Character.toLowerCase(current));
            }
        }
        return builder.toString();
    }

    private static boolean dimensionTypeHasFixedTime(Object dimensionType) throws ReflectiveOperationException {
        Object fixedTimeFlag;
        try {
            fixedTimeFlag = invokeNoArg(dimensionType, "hasFixedTime");
        } catch (NoSuchMethodException ignored) {
            Object fixedTime = invokeNoArg(dimensionType, "fixedTime");
            if (fixedTime instanceof OptionalLong optionalLong) {
                return optionalLong.isPresent();
            }
            if (fixedTime instanceof Optional<?> optional) {
                return optional.isPresent();
            }
            return false;
        }

        return fixedTimeFlag instanceof Boolean && (Boolean) fixedTimeFlag;
    }

    private static Object unwrapDimensionType(Object dimensionTypeHolder) throws ReflectiveOperationException {
        if (dimensionTypeHolder == null) {
            return null;
        }

        Class<?> holderClass = dimensionTypeHolder.getClass();
        if (holderClass.getName().startsWith("net.minecraft.world.level.dimension.")) {
            return dimensionTypeHolder;
        }

        Method valueMethod = holderClass.getMethod("value");
        return valueMethod.invoke(dimensionTypeHolder);
    }

    private static Object invokeNoArg(Object instance, String methodName) throws ReflectiveOperationException {
        Method method = instance.getClass().getMethod(methodName);
        return method.invoke(instance);
    }
}

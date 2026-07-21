package art.arcane.iris.core.service;

import art.arcane.iris.api.tree.IrisTreeFellerService;
import art.arcane.iris.api.tree.TreeFellerAccess;
import art.arcane.iris.api.tree.TreeFellerOptions;
import art.arcane.iris.api.tree.TreeFellerRunHooks;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.service.tree.BlockDropRouter;
import art.arcane.iris.core.service.tree.TreeDefinitionIndex;
import art.arcane.iris.core.service.tree.TreeMarkerTraversal;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.StructurePlacementMarker;
import art.arcane.iris.engine.framework.TreeBlockMaterial;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.ServicePriority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TreeFellerSVC implements IrisService, IrisTreeFellerService {
    private static final String PERMISSION = "iris.treefeller";

    private final AtomicBoolean serviceEnabled = new AtomicBoolean();
    private final Map<BlockBreakEvent, PendingFell> pending = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<BlockBreakEvent> managedEvents = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>())
    );
    private final Set<TreeClaim> activeClaims = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<FellingRun>> activeRuns = new ConcurrentHashMap<>();
    private final Map<Engine, TreeDefinitionIndex> definitions = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onEnable() {
        serviceEnabled.set(true);
        Bukkit.getServicesManager().register(
                IrisTreeFellerService.class,
                this,
                BukkitPlatform.plugin(),
                ServicePriority.Normal
        );
        IrisServices.register(IrisTreeFellerService.class, this);
    }

    @Override
    public void onDisable() {
        serviceEnabled.set(false);
        Bukkit.getServicesManager().unregister(IrisTreeFellerService.class, this);
        IrisServices.remove(IrisTreeFellerService.class);
        pending.clear();
        managedEvents.clear();
        for (Set<FellingRun> runs : activeRuns.values()) {
            for (FellingRun run : runs) {
                finish(run);
            }
        }
        activeRuns.clear();
        activeClaims.clear();
        definitions.clear();
    }

    @Override
    public boolean tryFell(BlockBreakEvent event, TreeFellerOptions options) {
        if (!serviceEnabled.get()
                || event == null
                || options == null
                || event.isCancelled()
                || isManagedBreak(event)) {
            return false;
        }
        if (!canUse(event.getPlayer(), options.access())) {
            return false;
        }

        TreeCandidate candidate;
        try {
            candidate = resolveCandidate(event.getBlock(), event.getPlayer());
        } catch (Throwable error) {
            IrisLogging.reportError("Failed to resolve an Iris tree-feller request.", error);
            return false;
        }
        if (candidate == null) {
            return false;
        }

        PendingFell requested = new PendingFell(
                candidate,
                resolvePreservationChance(options),
                options.runHooks()
        );
        synchronized (pending) {
            PendingFell existing = pending.get(event);
            if (existing != null && existing.candidate().access() == TreeFellerAccess.INTEGRATION_OVERRIDE) {
                return options.access() == TreeFellerAccess.INTEGRATION_OVERRIDE;
            }
            if (existing == null || options.access() == TreeFellerAccess.INTEGRATION_OVERRIDE) {
                pending.put(event, requested.withAccess(options.access()));
            }
        }
        managedEvents.add(event);
        return true;
    }

    @Override
    public boolean isManagedBreak(BlockBreakEvent event) {
        return event != null && managedEvents.contains(event);
    }

    @Override
    public boolean isTreeBlock(Block block) {
        if (block == null) {
            return false;
        }
        try {
            return resolveTreeContext(block) != null;
        } catch (Throwable error) {
            IrisLogging.reportError("Failed to inspect Iris tree provenance.", error);
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void requestStandalone(BlockBreakEvent event) {
        tryFell(event, TreeFellerOptions.standalone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeBreak(BlockBreakEvent event) {
        PendingFell requested;
        synchronized (pending) {
            requested = pending.remove(event);
        }

        if (requested == null) {
            if (!event.isCancelled() && !isManagedBreak(event)) {
                deferSuccessfulBreakCleanup(event);
            }
            return;
        }

        if (event.isCancelled()) {
            releaseManagedLater(event);
            return;
        }

        TreeCandidate current;
        try {
            current = resolveCandidate(event.getBlock(), event.getPlayer());
        } catch (Throwable error) {
            IrisLogging.reportError("Failed to finalize an Iris tree-feller request.", error);
            deferSuccessfulBreakCleanup(event);
            releaseManagedLater(event);
            return;
        }
        if (current == null || !current.context().marker().equals(requested.candidate().context().marker())) {
            deferSuccessfulBreakCleanup(event);
            releaseManagedLater(event);
            return;
        }

        TreeClaim claim = new TreeClaim(current.world().getUID(), current.context().marker());
        if (!activeClaims.add(claim)) {
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
            releaseManagedLater(event);
            return;
        }

        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);
        releaseManagedLater(event);

        FellingRun run = new FellingRun(
                claim,
                current,
                requested.preservationChance(),
                requested.runHooks(),
                event.getPlayer().getInventory().getHeldItemSlot(),
                event.getPlayer().getLocation().clone().add(0D, 0.15D, 0D)
        );
        activeRuns.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(run);
        notifyActivationAccepted(run.runHooks);
        run.presentation.activate(event.getBlock());
        discover(run);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void haltWhenSneakingStops(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            finishRuns(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void haltWhenHeldSlotChanges(PlayerItemHeldEvent event) {
        if (event.getNewSlot() != event.getPreviousSlot()) {
            finishRuns(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void haltWhenHandsSwap(PlayerSwapHandItemsEvent event) {
        finishRuns(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void clearPlacedProvenance(BlockPlaceEvent event) {
        ProvenanceSnapshot snapshot = captureProvenance(event.getBlockPlaced());
        if (snapshot == null) {
            return;
        }
        Location location = event.getBlockPlaced().getLocation();
        Runnable cleanup = () -> {
            if (!event.isCancelled()) {
                clearProvenanceIfMatching(snapshot);
            }
        };
        if (!J.runAt(location, cleanup, 1) && !J.isFolia()) {
            J.s(cleanup, 1);
        }
    }

    private boolean canUse(Player player, TreeFellerAccess access) {
        if (access == TreeFellerAccess.INTEGRATION_OVERRIDE) {
            return true;
        }
        IrisSettings.IrisSettingsTreeFeller settings = IrisSettings.get().getTreeFeller();
        return settings != null && settings.isEnabled() && player.hasPermission(PERMISSION);
    }

    private int resolvePreservationChance(TreeFellerOptions options) {
        if (options.access() == TreeFellerAccess.INTEGRATION_OVERRIDE) {
            return options.durabilityPreservationChance();
        }
        IrisSettings.IrisSettingsTreeFeller settings = IrisSettings.get().getTreeFeller();
        return settings == null ? 0 : settings.getDurabilityPreservationChance();
    }

    private void notifyActivationAccepted(TreeFellerRunHooks runHooks) {
        try {
            runHooks.onActivationAccepted();
        } catch (Throwable error) {
            IrisLogging.reportError("An Iris tree-feller integration activation callback failed.", error);
        }
    }

    private TreeCandidate resolveCandidate(Block block, Player player) {
        if (player.getGameMode() != GameMode.SURVIVAL || !player.isSneaking()) {
            return null;
        }
        if (!Tag.LOGS.isTagged(block.getType())) {
            return null;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isAxe(tool)) {
            return null;
        }
        TreeContext context = resolveTreeContext(block);
        if (context == null) {
            return null;
        }
        return new TreeCandidate(
                context,
                block.getWorld(),
                player,
                tool.clone(),
                TreeFellerAccess.STANDALONE,
                new TreeMarkerTraversal.Position(block.getX(), block.getY(), block.getZ())
        );
    }

    private TreeContext resolveTreeContext(Block block) {
        if (block.getType().isAir()) {
            return null;
        }
        PlatformChunkGenerator access = IrisToolbelt.access(block.getWorld());
        if (access == null || access.getEngine() == null) {
            return null;
        }
        Engine engine = access.getEngine();
        World world = block.getWorld();
        int minimumY = world.getMinHeight();
        int maximumY = world.getMaxHeight();
        int relativeY = block.getY() - minimumY;
        String marker = markerAt(engine, minimumY, block.getX(), block.getY(), block.getZ());
        StructurePlacementMarker.Decoded decoded = StructurePlacementMarker.decode(marker);
        if (decoded == null || decoded.structureAware()) {
            return null;
        }
        TreeBlockMaterial expected = engine.getMantle().getMantle().get(
                block.getX(),
                relativeY,
                block.getZ(),
                TreeBlockMaterial.class
        );
        if (expected != null && !matchesExpectedMaterial(block, expected)) {
            return null;
        }
        if (expected == null
                && !decoded.objectKey().startsWith("trees/")
                && !definitionIndex(engine).isTreeMarker(marker)) {
            return null;
        }
        return new TreeContext(engine, marker, expected, minimumY, maximumY);
    }

    private TreeDefinitionIndex definitionIndex(Engine engine) {
        synchronized (definitions) {
            return definitions.computeIfAbsent(engine, TreeDefinitionIndex::build);
        }
    }

    private void discover(FellingRun run) {
        J.a(() -> {
            if (run.candidate.context().engine().isClosed()) {
                finish(run);
                return;
            }
            try {
                TreeMarkerTraversal.Discovery discovery = TreeMarkerTraversal.discover(
                        run.candidate.trigger(),
                        run.candidate.context().marker(),
                        run.candidate.context().minimumY(),
                        run.candidate.context().maximumY(),
                        (x, y, z) -> markerAt(
                                run.candidate.context().engine(),
                                run.candidate.context().minimumY(),
                                x,
                                y,
                                z
                        )
                );
                List<TreeMarkerTraversal.Position> positions = positionsForFelling(discovery, run.candidate.trigger());
                preflight(run, positions, discovery.complete());
            } catch (Throwable error) {
                IrisLogging.reportError("Failed to discover an Iris tree for felling.", error);
                preflight(run, List.of(run.candidate.trigger()), false);
            }
        });
    }

    private void preflight(FellingRun run, List<TreeMarkerTraversal.Position> positions, boolean allowFallback) {
        Map<ChunkPosition, List<TreeMarkerTraversal.Position>> grouped = groupByChunk(positions);
        if (grouped.isEmpty()) {
            finish(run);
            return;
        }
        Map<TreeMarkerTraversal.Position, Integer> erosionOrder = new HashMap<>(positions.size());
        for (int index = 0; index < positions.size(); index++) {
            erosionOrder.put(positions.get(index), index);
        }

        List<TreeMember> members = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean();
        AtomicInteger remaining = new AtomicInteger(grouped.size());
        AtomicBoolean completed = new AtomicBoolean();

        for (Map.Entry<ChunkPosition, List<TreeMarkerTraversal.Position>> entry : grouped.entrySet()) {
            ChunkPosition chunk = entry.getKey();
            Runnable task = () -> {
                try {
                    if (!run.candidate.world().isChunkLoaded(chunk.x(), chunk.z())) {
                        failed.set(true);
                        return;
                    }
                    for (TreeMarkerTraversal.Position position : entry.getValue()) {
                        TreeMember member = inspectMember(run, position, erosionOrder.getOrDefault(position, Integer.MAX_VALUE));
                        if (member != null) {
                            members.add(member);
                        }
                    }
                } catch (Throwable error) {
                    failed.set(true);
                    IrisLogging.reportError(
                            "Failed to preflight an Iris tree-feller chunk at " + chunk.x() + "," + chunk.z() + ".",
                            error
                    );
                } finally {
                    completePreflightGroup(run, members, failed, remaining, completed, allowFallback);
                }
            };
            if (!J.runRegion(run.candidate.world(), chunk.x(), chunk.z(), task)) {
                failed.set(true);
                completePreflightGroup(run, members, failed, remaining, completed, allowFallback);
            }
        }
    }

    static List<TreeMarkerTraversal.Position> positionsForFelling(
            TreeMarkerTraversal.Discovery discovery,
            TreeMarkerTraversal.Position trigger
    ) {
        return discovery.complete() ? discovery.members() : List.of(trigger);
    }

    private void completePreflightGroup(
            FellingRun run,
            List<TreeMember> members,
            AtomicBoolean failed,
            AtomicInteger remaining,
            AtomicBoolean completed,
            boolean allowFallback
    ) {
        if (remaining.decrementAndGet() != 0 || !completed.compareAndSet(false, true)) {
            return;
        }
        if (failed.get() && allowFallback) {
            preflight(run, List.of(run.candidate.trigger()), false);
            return;
        }
        if (failed.get()) {
            finish(run);
            return;
        }

        List<TreeMember> ordered = orderMembers(run.candidate.trigger(), members);
        if (ordered.isEmpty() || !ordered.getFirst().position().equals(run.candidate.trigger())) {
            finish(run);
            return;
        }
        run.work = ordered;
        run.blocksPerPulse = TreeFellerPresentation.blocksPerPulse(ordered.size());
        run.effectStride = TreeFellerPresentation.effectStride(run.blocksPerPulse);
        processNext(run);
    }

    private TreeMember inspectMember(FellingRun run, TreeMarkerTraversal.Position position, int erosionOrder) {
        World world = run.candidate.world();
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        TreeContext context = run.candidate.context();
        if (!context.marker().equals(markerAt(context.engine(), context.minimumY(), position))) {
            return null;
        }
        TreeBlockMaterial expected = materialAt(context.engine(), context.minimumY(), position);
        if (expected != null && !matchesExpectedMaterial(block, expected)) {
            clearProvenance(context.engine(), context.minimumY(), position);
            return null;
        }
        if (block.getType().isAir()) {
            clearProvenance(context.engine(), context.minimumY(), position);
            return null;
        }
        return new TreeMember(position, Tag.LOGS.isTagged(block.getType()), expected, erosionOrder);
    }

    private List<TreeMember> orderMembers(
            TreeMarkerTraversal.Position trigger,
            Collection<TreeMember> discovered
    ) {
        Comparator<TreeMember> erosionOrder = Comparator
                .comparingInt(TreeMember::erosionOrder)
                .thenComparingInt(member -> member.position().y())
                .thenComparingInt(member -> member.position().x())
                .thenComparingInt(member -> member.position().z());
        List<TreeMember> ordered = discovered.stream().sorted(erosionOrder).toList();
        if (ordered.isEmpty() || !ordered.getFirst().position().equals(trigger)) {
            return List.of();
        }
        return ordered;
    }

    private void processNext(FellingRun run) {
        if (run.finished.get()) {
            return;
        }
        int index = run.cursor.getAndIncrement();
        if (index >= run.work.size()) {
            finish(run);
            return;
        }

        TreeMember member = run.work.get(index);
        ChunkPosition chunk = new ChunkPosition(member.position().x() >> 4, member.position().z() >> 4);
        Runnable task = () -> runTask(
                run,
                "Failed to prepare an Iris tree-feller block.",
                () -> prepareBreak(run, member)
        );
        if (!J.runRegion(run.candidate.world(), chunk.x(), chunk.z(), task)) {
            if (member.log()) {
                finish(run);
            } else {
                continueRun(run);
            }
        }
    }

    private void prepareBreak(FellingRun run, TreeMember member) {
        Block block = liveMemberBlock(run, member);
        if (block == null) {
            if (member.log()) {
                finish(run);
            } else {
                continueRun(run);
            }
            return;
        }

        if (!member.log()) {
            runMutationTask(
                    run,
                    member,
                    new DamageReservation(run.expectedTool.clone(), false, false, false),
                    new AtomicBoolean()
            );
            return;
        }

        Runnable task = () -> runTask(
                run,
                "Failed to reserve Iris tree-feller tool durability.",
                () -> reserveDamage(run, member)
        );
        if (!J.runEntity(run.candidate.player(), task)) {
            finish(run);
        }
    }

    private void reserveDamage(FellingRun run, TreeMember member) {
        Player player = run.candidate.player();
        if (!isRunControlActive(run, player)) {
            finish(run);
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(run.heldSlot);
        if (current == null
                || inventory.getHeldItemSlot() != run.heldSlot
                || !current.isSimilar(run.expectedTool)
                || !isAxe(current)) {
            finish(run);
            return;
        }

        if (!reserveLogCost(run)) {
            finish(run);
            return;
        }

        ItemStack before = current.clone();
        ItemMeta meta = current.getItemMeta();
        if (meta.isUnbreakable() || ThreadLocalRandom.current().nextInt(100) < run.preservationChance) {
            scheduleMutation(run, member, new DamageReservation(before, false, false, true));
            return;
        }
        if (!(meta instanceof Damageable damageable) || current.getType().getMaxDurability() <= 0) {
            refundAndFinish(run, new DamageReservation(before, false, false, true));
            return;
        }

        int nextDamage = damageable.getDamage() + 1;
        boolean broke = nextDamage >= current.getType().getMaxDurability();
        if (broke) {
            inventory.setItem(run.heldSlot, new ItemStack(Material.AIR));
            run.expectedTool = new ItemStack(Material.AIR);
        } else {
            damageable.setDamage(nextDamage);
            current.setItemMeta(meta);
            inventory.setItem(run.heldSlot, current);
            run.expectedTool = current.clone();
        }
        scheduleMutation(run, member, new DamageReservation(before, true, broke, true));
    }

    private void scheduleMutation(FellingRun run, TreeMember member, DamageReservation reservation) {
        TreeMarkerTraversal.Position position = member.position();
        AtomicBoolean mutationSucceeded = new AtomicBoolean();
        Runnable task = () -> runMutationTask(run, member, reservation, mutationSucceeded);
        boolean scheduled;
        try {
            scheduled = J.runRegion(
                    run.candidate.world(),
                    position.x() >> 4,
                    position.z() >> 4,
                    task
            );
        } catch (Throwable error) {
            IrisLogging.reportError("Failed to schedule an Iris tree-feller block removal.", error);
            refundAndFinish(run, reservation);
            return;
        }
        if (!scheduled) {
            refundAndFinish(run, reservation);
        }
    }

    private void runMutationTask(
            FellingRun run,
            TreeMember member,
            DamageReservation reservation,
            AtomicBoolean mutationSucceeded
    ) {
        if (run.finished.get()) {
            refundAndFinish(run, reservation);
            return;
        }
        try {
            probeAndMutate(run, member, reservation, mutationSucceeded);
        } catch (Throwable error) {
            IrisLogging.reportError("Failed to remove an Iris tree-feller block.", error);
            if (mutationSucceeded.get()) {
                finish(run);
            } else {
                refundAndFinish(run, reservation);
            }
        }
    }

    private void probeAndMutate(
            FellingRun run,
            TreeMember member,
            DamageReservation reservation,
            AtomicBoolean mutationSucceeded
    ) {
        Block block = liveMemberBlock(run, member);
        if (block == null) {
            refundAndFinish(run, reservation);
            return;
        }

        BlockBreakEvent probe = new RoutedBlockBreakEvent(block, run.candidate.player(), run);
        managedEvents.add(probe);
        try {
            Bukkit.getPluginManager().callEvent(probe);
        } catch (Throwable error) {
            probe.setCancelled(true);
            IrisLogging.reportError("Failed to dispatch an Iris tree-feller block probe.", error);
        } finally {
            managedEvents.remove(probe);
        }

        try {
            if (probe.isCancelled()) {
                if (reservation.charged() || reservation.logCostReserved()) {
                    refundAndFinish(run, reservation);
                } else if (member.log()) {
                    finish(run);
                } else {
                    continueRun(run);
                }
                return;
            }

            block = liveMemberBlock(run, member);
            if (run.finished.get() || block == null) {
                probe.setCancelled(true);
                refundAndFinish(run, reservation);
                return;
            }

            Location source = block.getLocation().clone().add(0.5D, 0.5D, 0.5D);
            BlockData visualData = block.getBlockData().clone();
            List<ItemStack> vanillaDrops = probe.isDropItems()
                    ? block.getDrops(reservation.toolForDrops()).stream()
                    .map(ItemStack::clone)
                    .toList()
                    : List.of();
            block.setType(Material.AIR, false);
            if (!block.getType().isAir()) {
                probe.setCancelled(true);
                refundAndFinish(run, reservation);
                return;
            }
            mutationSucceeded.set(true);
            run.presentation.erode(
                    source,
                    visualData,
                    member.erosionOrder(),
                    run.processed.get(),
                    run.blocksPerPulse,
                    run.effectStride,
                    run.work.size()
            );

            clearProvenance(
                    run.candidate.context().engine(),
                    run.candidate.context().minimumY(),
                    member.position()
            );
            routeDrops(run, vanillaDrops, source);
            if (!run.presentation.routeExperience(probe.getExpToDrop())) {
                dropExperience(source, probe.getExpToDrop());
            }
            if (reservation.logCostReserved()) {
                completeLogCost(run, reservation);
                return;
            }
            completeSuccessfulMutation(run, reservation);
        } catch (RuntimeException | Error error) {
            if (!mutationSucceeded.get()) {
                probe.setCancelled(true);
            }
            throw error;
        }
    }

    private Block liveMemberBlock(FellingRun run, TreeMember member) {
        World world = run.candidate.world();
        TreeMarkerTraversal.Position position = member.position();
        if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            return null;
        }
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        TreeContext context = run.candidate.context();
        if (!context.marker().equals(markerAt(context.engine(), context.minimumY(), position))) {
            return null;
        }
        if (block.getType().isAir() || member.log() != Tag.LOGS.isTagged(block.getType())) {
            clearProvenance(context.engine(), context.minimumY(), position);
            return null;
        }
        TreeBlockMaterial expected = materialAt(context.engine(), context.minimumY(), position);
        if (member.expectedMaterial() != null && !member.expectedMaterial().equals(expected)) {
            clearProvenance(context.engine(), context.minimumY(), position);
            return null;
        }
        if (expected != null && !matchesExpectedMaterial(block, expected)) {
            clearProvenance(context.engine(), context.minimumY(), position);
            return null;
        }
        return block;
    }

    private void refundAndFinish(FellingRun run, DamageReservation reservation) {
        if (!reservation.charged() && !reservation.logCostReserved()) {
            finish(run);
            return;
        }
        if (!J.runEntity(run.candidate.player(), () -> {
            if (reservation.charged()) {
                PlayerInventory inventory = run.candidate.player().getInventory();
                ItemStack current = inventory.getItem(run.heldSlot);
                boolean expectedAir = run.expectedTool.getType() == Material.AIR;
                boolean currentMatches = expectedAir
                        ? current == null || current.getType() == Material.AIR
                        : current != null && current.isSimilar(run.expectedTool);
                if (currentMatches) {
                    inventory.setItem(run.heldSlot, reservation.toolForDrops().clone());
                    run.expectedTool = reservation.toolForDrops().clone();
                }
            }
            if (reservation.logCostReserved()) {
                refundLogCost(run);
            }
            finish(run);
        })) {
            finish(run);
        }
    }

    private boolean isRunControlActive(FellingRun run, Player player) {
        return player.isOnline()
                && player.getGameMode() == GameMode.SURVIVAL
                && player.isSneaking()
                && player.getWorld().equals(run.candidate.world());
    }

    private boolean reserveLogCost(FellingRun run) {
        try {
            return run.runHooks.reserveLogCost();
        } catch (Throwable error) {
            IrisLogging.reportError("An Iris tree-feller integration log-cost reservation failed.", error);
            return false;
        }
    }

    private void completeLogCost(FellingRun run, DamageReservation reservation) {
        if (!J.runEntity(run.candidate.player(), () -> {
            try {
                run.runHooks.commitLogCost();
            } catch (Throwable error) {
                IrisLogging.reportError("An Iris tree-feller integration log-cost commit failed.", error);
                finish(run);
                return;
            }
            completeSuccessfulMutation(run, reservation);
        })) {
            finish(run);
        }
    }

    private void refundLogCost(FellingRun run) {
        try {
            run.runHooks.refundLogCost();
        } catch (Throwable error) {
            IrisLogging.reportError("An Iris tree-feller integration log-cost refund failed.", error);
        }
    }

    private void completeSuccessfulMutation(FellingRun run, DamageReservation reservation) {
        if (reservation.broke()) {
            finish(run);
            return;
        }
        continueRun(run);
    }

    private void continueRun(FellingRun run) {
        int processed = run.processed.incrementAndGet();
        if (processed % run.blocksPerPulse == 0) {
            J.s(() -> runTask(run, "Failed to continue an Iris tree-feller run.", () -> processNext(run)), 1);
        } else {
            processNext(run);
        }
    }

    private void runTask(FellingRun run, String context, Runnable task) {
        if (run.finished.get() || !serviceEnabled.get()) {
            finish(run);
            return;
        }
        try {
            task.run();
        } catch (Throwable error) {
            IrisLogging.reportError(context, error);
            finish(run);
        }
    }

    private void finish(FellingRun run) {
        if (run.finished.compareAndSet(false, true)) {
            activeClaims.remove(run.claim);
            Set<FellingRun> runs = activeRuns.get(run.candidate.player().getUniqueId());
            if (runs != null) {
                runs.remove(run);
                if (runs.isEmpty()) {
                    activeRuns.remove(run.candidate.player().getUniqueId(), runs);
                }
            }
            run.presentation.finish();
        }
    }

    private void finishRuns(UUID playerId) {
        Set<FellingRun> runs = activeRuns.get(playerId);
        if (runs == null) {
            return;
        }
        for (FellingRun run : List.copyOf(runs)) {
            finish(run);
        }
    }

    private void releaseManagedLater(BlockBreakEvent event) {
        J.s(() -> managedEvents.remove(event), 1);
    }

    private void deferSuccessfulBreakCleanup(BlockBreakEvent event) {
        ProvenanceSnapshot snapshot = captureProvenance(event.getBlock());
        if (snapshot == null) {
            return;
        }
        Location location = event.getBlock().getLocation();
        Runnable cleanup = () -> {
            if (!event.isCancelled()) {
                clearProvenanceIfMatching(snapshot);
            }
        };
        if (!J.runAt(location, cleanup, 1) && !J.isFolia()) {
            J.s(cleanup, 1);
        }
    }

    private Map<ChunkPosition, List<TreeMarkerTraversal.Position>> groupByChunk(
            List<TreeMarkerTraversal.Position> positions
    ) {
        Map<ChunkPosition, List<TreeMarkerTraversal.Position>> grouped = new LinkedHashMap<>();
        for (TreeMarkerTraversal.Position position : positions) {
            ChunkPosition chunk = new ChunkPosition(position.x() >> 4, position.z() >> 4);
            grouped.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(position);
        }
        return grouped;
    }

    private String markerAt(Engine engine, int minimumY, TreeMarkerTraversal.Position position) {
        return markerAt(engine, minimumY, position.x(), position.y(), position.z());
    }

    private String markerAt(Engine engine, int minimumY, int x, int y, int z) {
        return engine.getMantle().getMantle().get(x, y - minimumY, z, String.class);
    }

    private TreeBlockMaterial materialAt(Engine engine, int minimumY, TreeMarkerTraversal.Position position) {
        return engine.getMantle().getMantle().get(
                position.x(),
                position.y() - minimumY,
                position.z(),
                TreeBlockMaterial.class
        );
    }

    private ProvenanceSnapshot captureProvenance(Block block) {
        PlatformChunkGenerator access = IrisToolbelt.access(block.getWorld());
        if (access == null || access.getEngine() == null) {
            return null;
        }
        Engine engine = access.getEngine();
        TreeMarkerTraversal.Position position = new TreeMarkerTraversal.Position(
                block.getX(),
                block.getY(),
                block.getZ()
        );
        int minimumY = block.getWorld().getMinHeight();
        String marker = markerAt(engine, minimumY, position);
        TreeBlockMaterial material = materialAt(engine, minimumY, position);
        if (marker == null && material == null) {
            return null;
        }
        return new ProvenanceSnapshot(engine, block.getWorld(), minimumY, position, marker, material);
    }

    private void clearProvenanceIfMatching(ProvenanceSnapshot snapshot) {
        String marker = markerAt(snapshot.engine(), snapshot.minimumY(), snapshot.position());
        TreeBlockMaterial material = materialAt(snapshot.engine(), snapshot.minimumY(), snapshot.position());
        if (Objects.equals(snapshot.marker(), marker) && Objects.equals(snapshot.material(), material)) {
            clearProvenance(snapshot.engine(), snapshot.minimumY(), snapshot.position());
        }
    }

    private void clearProvenance(Engine engine, int minimumY, TreeMarkerTraversal.Position position) {
        int relativeY = position.y() - minimumY;
        engine.getMantle().getMantle().remove(position.x(), relativeY, position.z(), String.class);
        engine.getMantle().getMantle().remove(position.x(), relativeY, position.z(), TreeBlockMaterial.class);
    }

    private boolean matchesExpectedMaterial(Block block, TreeBlockMaterial expected) {
        return expected.matches(block.getBlockData().getAsString());
    }

    private boolean isAxe(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getType().name().endsWith("_AXE");
    }

    private void routeDrops(FellingRun run, List<ItemStack> drops, Location source) {
        World world = source.getWorld();
        if (world == null) {
            return;
        }
        for (ItemStack drop : drops) {
            if (!run.presentation.routeDrop(drop)) {
                world.dropItem(source, drop);
            }
        }
    }

    private void dropExperience(Location location, int experience) {
        if (experience <= 0 || location.getWorld() == null) {
            return;
        }
        ExperienceOrb orb = location.getWorld().spawn(
                location,
                ExperienceOrb.class
        );
        orb.setExperience(experience);
    }

    private record PendingFell(
            TreeCandidate candidate,
            int preservationChance,
            TreeFellerRunHooks runHooks
    ) {
        private PendingFell withAccess(TreeFellerAccess access) {
            return new PendingFell(candidate.withAccess(access), preservationChance, runHooks);
        }
    }

    private record TreeCandidate(
            TreeContext context,
            World world,
            Player player,
            ItemStack tool,
            TreeFellerAccess access,
            TreeMarkerTraversal.Position trigger
    ) {
        private TreeCandidate withAccess(TreeFellerAccess access) {
            return new TreeCandidate(context, world, player, tool, access, trigger);
        }
    }

    private record TreeContext(
            Engine engine,
            String marker,
            TreeBlockMaterial expectedMaterial,
            int minimumY,
            int maximumY
    ) {
    }

    private record TreeClaim(UUID worldId, String marker) {
    }

    private record ProvenanceSnapshot(
            Engine engine,
            World world,
            int minimumY,
            TreeMarkerTraversal.Position position,
            String marker,
            TreeBlockMaterial material
    ) {
    }

    private record ChunkPosition(int x, int z) {
    }

    private record TreeMember(
            TreeMarkerTraversal.Position position,
            boolean log,
            TreeBlockMaterial expectedMaterial,
            int erosionOrder
    ) {
    }

    private record DamageReservation(
            ItemStack toolForDrops,
            boolean charged,
            boolean broke,
            boolean logCostReserved
    ) {
    }

    private final class RoutedBlockBreakEvent extends BlockBreakEvent implements BlockDropRouter {
        private final FellingRun run;

        private RoutedBlockBreakEvent(Block block, Player player, FellingRun run) {
            super(block, player);
            this.run = run;
        }

        @Override
        public boolean routeDrop(Object drop) {
            return serviceEnabled.get() && run.presentation.routeDrop(drop);
        }
    }

    private static final class FellingRun {
        private final TreeClaim claim;
        private final TreeCandidate candidate;
        private final int preservationChance;
        private final TreeFellerRunHooks runHooks;
        private final int heldSlot;
        private final TreeFellerPresentation presentation;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicInteger cursor = new AtomicInteger();
        private final AtomicInteger processed = new AtomicInteger();
        private volatile int blocksPerPulse = 1;
        private volatile int effectStride = 1;
        private volatile ItemStack expectedTool;
        private volatile List<TreeMember> work = List.of();

        private FellingRun(
                TreeClaim claim,
                TreeCandidate candidate,
                int preservationChance,
                TreeFellerRunHooks runHooks,
                int heldSlot,
                Location fallbackLocation
        ) {
            this.claim = claim;
            this.candidate = candidate;
            this.preservationChance = preservationChance;
            this.runHooks = runHooks;
            this.heldSlot = heldSlot;
            this.presentation = new TreeFellerPresentation(candidate.player(), candidate.world(), fallbackLocation);
            this.expectedTool = candidate.tool().clone();
        }
    }
}

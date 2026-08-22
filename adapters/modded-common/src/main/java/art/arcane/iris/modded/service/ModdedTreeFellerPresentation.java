package art.arcane.iris.modded.service;

import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class ModdedTreeFellerPresentation {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final int MIN_BLOCKS_PER_PULSE = 4;
    private static final int MAX_BLOCKS_PER_PULSE = 64;
    private static final int TARGET_EROSION_PULSES = 60;
    private static final int MAX_EFFECT_ORIGINS_PER_PULSE = 16;

    private final ServerPlayer player;
    private final ServerLevel sourceLevel;
    private final List<ItemStack> pendingDrops = new ArrayList<>();
    private final AtomicBoolean effectFailureReported = new AtomicBoolean();
    private final AtomicBoolean deliveryFailureReported = new AtomicBoolean();
    private boolean flushScheduled;
    private double fallbackX;
    private double fallbackY;
    private double fallbackZ;

    ModdedTreeFellerPresentation(ServerPlayer player, ServerLevel sourceLevel) {
        this.player = player;
        this.sourceLevel = sourceLevel;
        this.fallbackX = player.getX();
        this.fallbackY = player.getY() + 0.15D;
        this.fallbackZ = player.getZ();
    }

    static int blocksPerPulse(int blockCount) {
        int requested = Math.max(1, (blockCount + TARGET_EROSION_PULSES - 1) / TARGET_EROSION_PULSES);
        return Math.max(MIN_BLOCKS_PER_PULSE, Math.min(requested, MAX_BLOCKS_PER_PULSE));
    }

    static int effectStride(int blocksPerPulse) {
        return Math.max(
                1,
                (blocksPerPulse + MAX_EFFECT_ORIGINS_PER_PULSE - 1) / MAX_EFFECT_ORIGINS_PER_PULSE
        );
    }

    static List<ItemStack> consolidateDrops(Collection<ItemStack> drops) {
        List<ItemStack> consolidated = new ArrayList<>();
        for (ItemStack drop : drops) {
            mergeDrop(consolidated, drop);
        }
        return List.copyOf(consolidated);
    }

    void activate(BlockPos position, BlockState state) {
        try {
            double x = position.getX() + 0.5D;
            double y = position.getY() + 0.5D;
            double z = position.getZ() + 0.5D;
            sourceLevel.sendParticles(ParticleTypes.ENCHANT, x, y, z, 24, 0.45D, 0.45D, 0.45D, 0.18D);
            sourceLevel.sendParticles(ParticleTypes.END_ROD, x, y, z, 8, 0.25D, 0.25D, 0.25D, 0.035D);
            sourceLevel.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    x,
                    y,
                    z,
                    8,
                    0.25D,
                    0.25D,
                    0.25D,
                    0.04D
            );
            sourceLevel.playSound(null, position, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 0.55F, 1.35F);
            sourceLevel.playSound(null, position, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.4F, 0.8F);
        } catch (Throwable error) {
            reportEffectFailure(error);
        }
    }

    void erode(BlockPos position, BlockState state, int processed, int effectStride, float pitch) {
        if (processed % effectStride != 0) {
            return;
        }
        try {
            double x = position.getX() + 0.5D;
            double y = position.getY() + 0.5D;
            double z = position.getZ() + 0.5D;
            sourceLevel.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    x,
                    y,
                    z,
                    5,
                    0.3D,
                    0.3D,
                    0.3D,
                    0.04D
            );
            sourceLevel.sendParticles(ParticleTypes.ENCHANT, x, y, z, 3, 0.28D, 0.28D, 0.28D, 0.12D);
            sourceLevel.playSound(null, position, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.22F, pitch);
        } catch (Throwable error) {
            reportEffectFailure(error);
        }
    }

    synchronized boolean route(Iterable<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (drop != null && !drop.isEmpty()) {
                pendingDrops.add(drop.copy());
            }
        }
        scheduleFlush();
        return true;
    }

    synchronized void flush() {
        flushScheduled = false;
        if (pendingDrops.isEmpty()) {
            return;
        }
        List<ItemStack> drops = consolidateDrops(pendingDrops);
        pendingDrops.clear();
        boolean atPlayer = !player.isRemoved() && player.level() == sourceLevel;
        double x = atPlayer ? player.getX() : fallbackX;
        double y = atPlayer ? player.getY() + 0.15D : fallbackY;
        double z = atPlayer ? player.getZ() : fallbackZ;
        if (atPlayer) {
            fallbackX = x;
            fallbackY = y;
            fallbackZ = z;
        }
        int delivered = 0;
        for (ItemStack drop : drops) {
            try {
                ItemEntity item = new ItemEntity(sourceLevel, x, y, z, drop, 0D, 0.08D, 0D);
                item.setDefaultPickUpDelay();
                if (sourceLevel.addFreshEntity(item)) {
                    delivered++;
                } else {
                    pendingDrops.add(drop.copy());
                }
            } catch (Throwable error) {
                pendingDrops.add(drop.copy());
                reportDeliveryFailure(error);
            }
        }
        try {
            int particles = Math.min(32, 6 + (delivered * 2));
            sourceLevel.sendParticles(ParticleTypes.ENCHANT, x, y + 0.35D, z, particles, 0.3D, 0.25D, 0.3D, 0.1D);
            sourceLevel.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.28F, 1.75F);
        } catch (Throwable error) {
            reportEffectFailure(error);
        }
        if (!pendingDrops.isEmpty()) {
            scheduleFlush();
        }
    }

    synchronized void finish() {
        flush();
    }

    private synchronized void scheduleFlush() {
        if (pendingDrops.isEmpty() || flushScheduled) {
            return;
        }
        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler == null) {
            flush();
            return;
        }
        flushScheduled = true;
        scheduler.laterGlobal(this::flush, 1);
    }

    private static void mergeDrop(List<ItemStack> consolidated, ItemStack drop) {
        if (drop == null || drop.isEmpty()) {
            return;
        }
        ItemStack remaining = drop.copy();
        for (ItemStack existing : consolidated) {
            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int capacity = existing.getMaxStackSize() - existing.getCount();
            if (capacity <= 0) {
                continue;
            }
            int moved = Math.min(capacity, remaining.getCount());
            existing.grow(moved);
            remaining.shrink(moved);
            if (remaining.isEmpty()) {
                return;
            }
        }
        while (!remaining.isEmpty()) {
            int amount = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            consolidated.add(remaining.copyWithCount(amount));
            remaining.shrink(amount);
        }
    }

    private void reportEffectFailure(Throwable error) {
        if (effectFailureReported.compareAndSet(false, true)) {
            LOGGER.error("Iris modded tree-feller presentation failed", error);
        }
    }

    private void reportDeliveryFailure(Throwable error) {
        if (deliveryFailureReported.compareAndSet(false, true)) {
            LOGGER.error("Iris modded tree-feller drop delivery failed", error);
        }
    }
}
